package com.mezze.language.typechecker.module

import com.mezze.language.ast.BindingKey
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.context.collectFreeTypeVars
import com.mezze.language.typechecker.ModuleError

/**
 * Module type checker - type-checks modules with caching.
 *
 * This is responsible for type-checking loaded modules and
 * caching the results to avoid duplicate work.
 *
 * ## Design:
 * - Caches typed modules (thread-safe)
 * - Builds module-specific context
 * - Validates exports
 * - Pure functions where possible
 *
 * ## Usage:
 * ```kotlin
 * val typeChecker = ModuleTypeChecker.create()
 * val result = typeChecker.typeCheckModule(loadedModule)
 *
 * when (result) {
 *     is ModuleTypeCheckResult.Success -> {
 *         val typed = result.module
 *         // Use the typed module
 *     }
 *     is ModuleTypeCheckResult.Failure -> {
 *         for (error in result.errors) {
 *             println(error.message)
 *         }
 *     }
 * }
 * ```
 */
class ModuleTypeChecker(
    private val baseContext: TypingContext = TypingContext.empty,
    private val moduleResolver: ModuleResolver? = null  // Optional, for updating module resolver cache
) {
    companion object {
        // Global tracking of modules currently being type-checked to detect circular dependencies
        // This is static so it works across all ModuleTypeChecker instances
        private val typeCheckingStack = mutableSetOf<String>()
        private val stackLock = Any()

        // CRITICAL FIX: Static/global cache for typed modules
        // This ensures that when module A imports module B, and later module C also imports B,
        // we don't re-type-check B. Re-type-checking would fail because local variables in
        // function bodies (like `delim_handle` in continuation.mz) are not in scope when
        // processing the module as a whole.
        private val globalCache = mutableMapOf<String, TypedModule>()
        private val globalCacheLock = Any()

        /**
         * Clear the global module cache. Used in tests and between compilation runs.
         */
        @JvmStatic
        fun clearCache() {
            synchronized(globalCacheLock) {
                globalCache.clear()
            }
            // Re-populate from PrecompiledStdlib so stdlib modules don't need re-type-checking
            try {
                val stdlib = com.mezze.language.typechecker.PrecompiledStdlib
                for ((modulePath, module) in stdlib.loadedModules) {
                    preloadIntoGlobalCache(modulePath, module)
                }
            } catch (_: Exception) { /* PrecompiledStdlib not initialized yet */ }
        }

        /**
         * Get a cached module from the global cache.
         */
        private fun getFromGlobalCache(modulePath: String): TypedModule? {
            synchronized(globalCacheLock) {
                return globalCache[modulePath]
            }
        }

        /**
         * Store a typed module in the global cache.
         */
        private fun putInGlobalCache(modulePath: String, module: TypedModule) {
            synchronized(globalCacheLock) {
                globalCache[modulePath] = module
            }
        }

        /**
         * Get all cached modules (including transitive dependencies).
         * Used by the monomorphizer to access all loaded v2 TypedModules.
         */
        @JvmStatic
        fun getAllCachedModules(): Map<String, TypedModule> {
            synchronized(globalCacheLock) {
                return globalCache.toMap()
            }
        }

        /**
         * Preload a typed module into the global cache from pre-compiled stdlib.
         * Prevents re-type-checking when the prelude loads these modules.
         */
        @JvmStatic
        fun preloadIntoGlobalCache(modulePath: String, module: TypedModule) {
            putInGlobalCache(modulePath, module)
        }

        /**
         * Hydrate a v2 [com.mezze.language.typedast.TypedModule] (deserialized
         * from the cache.sqlite typed_modules table) into the in-memory
         * globalCache, wrapped in the checker-side TypedModule shell.
         *
         * Limitations of this fast path:
         * - parsedModule is null. Consumers that scan parsedModule for
         *   ParsedEffectDecl / ParsedAbilityDecl get fewer hits and may
         *   trigger a fresh re-typecheck if their consumer can't find what
         *   it needs. This is a graceful degradation, not a correctness bug.
         * - typeContext is reconstructed from the v2 module's exports.
         * - context (TypingContext) is left empty; rebuilt lazily by callers
         *   that actually need it.
         *
         * Used by tools (DevRunner / BuildCommand / LSP) on startup to skip
         * whole-module re-typecheck when the source on disk matches what was
         * typed before.
         */
        @JvmStatic
        fun hydrateFromV2Module(
            modulePath: String,
            v2Module: com.mezze.language.typedast.TypedModule
        ) {
            // Build a minimal typeContext from the v2 module's exports so
            // downstream lookups by name find the types we already know.
            val typeContext = mutableMapOf<String, com.mezze.language.ast.MezzeType>()
            for ((name, export) in v2Module.exports) {
                // ExportEntry holds the type; flatten to (name -> type).
                typeContext[name] = export.type
            }
            val wrapper = TypedModule(
                modulePath = modulePath,
                exports = emptyMap(),  // checker-side ExportedItem; not the same shape as v2 ExportEntry
                context = com.mezze.language.typechecker.context.TypingContext.empty,
                substitution = com.mezze.language.typechecker.solving.Substitution.empty,
                typedModule = v2Module,
                parsedModule = null,
                typeContext = typeContext
            )
            putInGlobalCache(modulePath, wrapper)
        }

        /**
         * Clear the type-checking stack. Used in tests.
         */
        @JvmStatic
        fun clearTypeCheckingStack() {
            synchronized(stackLock) {
                typeCheckingStack.clear()
            }
        }

        /**
         * Watch-mode targeted eviction: drop typed modules and any in-flight
         * stack entries for the given module paths.
         * Stdlib stays cached.
         */
        @JvmStatic
        fun invalidateModulePaths(modulePaths: Collection<String>) {
            synchronized(globalCacheLock) {
                for (path in modulePaths) globalCache.remove(path)
            }
            synchronized(stackLock) {
                for (path in modulePaths) typeCheckingStack.remove(path)
            }
        }

        /**
         * Check if a module is currently being type-checked (for cycle detection).
         */
        private fun isTypeChecking(modulePath: String): Boolean {
            synchronized(stackLock) {
                return modulePath in typeCheckingStack
            }
        }

        /**
         * Mark a module as being type-checked.
         */
        private fun startTypeChecking(modulePath: String) {
            synchronized(stackLock) {
                typeCheckingStack.add(modulePath)
            }
        }

        /**
         * Mark a module as done type-checking.
         */
        private fun finishTypeChecking(modulePath: String) {
            synchronized(stackLock) {
                typeCheckingStack.remove(modulePath)
            }
        }

        /**
         * Get the current type-checking stack for error messages.
         */
        private fun getTypeCheckingStack(): List<String> {
            synchronized(stackLock) {
                return typeCheckingStack.toList()
            }
        }

        /**
         * Create a module type checker with default configuration.
         */
        fun create(): ModuleTypeChecker {
            return ModuleTypeChecker()
        }

        /**
         * Create a module type checker with a specific base context.
         */
        fun withContext(context: TypingContext): ModuleTypeChecker {
            return ModuleTypeChecker(context)
        }

        /**
         * Create a module type checker with a module resolver.
         * This allows updating the ModuleResolver cache after type-checking.
         */
        fun withResolver(resolver: ModuleResolver): ModuleTypeChecker {
            return ModuleTypeChecker(moduleResolver = resolver)
        }

        /**
         * Create a module type checker with both context and resolver.
         */
        fun withContextAndResolver(context: TypingContext, resolver: ModuleResolver): ModuleTypeChecker {
            return ModuleTypeChecker(context, resolver)
        }
    }

    /**
     * Type-check a loaded module.
     *
     * This type-checks the module and caches the result.
     * Subsequent calls for the same module will return the cached result.
     *
     * @param module The loaded module to type-check
     * @param importContext Optional import context (for dependencies)
     * @return ModuleTypeCheckResult (Success or Failure)
     */
    fun typeCheckModule(
        module: LoadedModule,
        importContext: ModuleImportContext = ModuleImportContext.empty()
    ): ModuleTypeCheckResult {
        // Check GLOBAL cache first (thread-safe)
        // This is critical - when multiple modules import the same dependency,
        // we must return the cached result to avoid re-type-checking.
        // Re-type-checking fails because local variables in function bodies
        // (like `delim_handle` in continuation.mz) aren't in scope.
        getFromGlobalCache(module.modulePath)?.let { cached ->
            // Ensure the ModuleResolver also has the typed version with content hashes.
            // After clearCache(), the resolver re-loads from disk (unhashed), but this
            // global cache retains the typed module. Sync them so importers see all impls.
            val typeContext = cached.typeContext

            // Sync resolver cache with typed module data (v2 only — uses resolved parsedModule)
            val cachedParsedModule = cached.parsedModule ?: module.parsedModule
            if (cachedParsedModule != null) {
                moduleResolver?.updateModuleExportsV2(
                    cached.modulePath, cachedParsedModule, typeContext
                )
            }
            return ModuleTypeCheckResult.Success(cached)
        }

        // Check for circular dependency in type-checking
        if (isTypeChecking(module.modulePath)) {
            val cycle = getTypeCheckingStack() + module.modulePath
            throw ModuleError.CircularDependency(cycle)
        }

        // Mark as being type-checked
        startTypeChecking(module.modulePath)

        try {
            // Build context for this module
            val moduleContext = buildModuleContext(importContext)

            // Type-check the module using TypeChecker (v2 path — always uses ParsedModule)
            val typeChecker = TypeChecker.withContext(moduleContext)
            val modulePath = java.nio.file.Paths.get(module.filePath).parent ?: java.nio.file.Paths.get(".")
            val parsedModule = module.parsedModule
                ?: throw IllegalStateException("LoadedModule for '${module.modulePath}' has no parsedModule")
            val result = typeChecker.checkParsedModule(parsedModule, modulePath, module.modulePath)

            return when (result) {
                is TypeChecker.TypeCheckResult.Success -> {
                    // Use typeContext built by checkParsedModule from constraint results + substitution.
                    val typeContext = result.typeContext

                    // Update exports with inferred types from type context
                    val updatedExports = if (typeContext.isNotEmpty()) {
                        module.exports.mapValues { (_, export) ->
                            val inferredType = typeContext[export.originalName]
                            if (inferredType != null) export.copy(mezzeType = inferredType) else export
                        }
                    } else module.exports

                    // Use the resolved parsedModule if available (after ContextBuilder resolves
                    // performer->ability impls). This ensures ProgramIndex.build hashes match.
                    val resolvedModule = result.resolvedParsedModule ?: parsedModule

                    val typedModule = TypedModule(
                        modulePath = module.modulePath,
                        exports = updatedExports,
                        context = moduleContext,
                        substitution = result.substitution,
                        typedModule = result.typedModule,
                        parsedModule = resolvedModule,
                        typeContext = typeContext
                    )

                    // Cache the result in GLOBAL cache (thread-safe)
                    putInGlobalCache(module.modulePath, typedModule)

                    // Update ModuleResolver's cache with inferred types and ProgramIndex.
                    moduleResolver?.updateModuleExportsV2(module.modulePath, resolvedModule, typeContext)

                    ModuleTypeCheckResult.Success(typedModule)
                }
                is TypeChecker.TypeCheckResult.Failure -> {
                    // Convert type check errors
                    val errors = result.errors.map { error ->
                        ModuleTypeCheckResult.TypeCheckError(
                            modulePath = module.modulePath,
                            phase = error.phase,
                            message = error.message,
                            loc = error.loc
                        )
                    }
                    ModuleTypeCheckResult.Failure(errors)
                }
            }
        } finally {
            // Always remove from type-checking stack
            finishTypeChecking(module.modulePath)
        }
    }

    /**
     * Build typing context for a module.
     *
     * This creates a context that includes:
     * - Base context (built-in types, abilities, etc.)
     * - Imported items from dependencies
     */
    private fun buildModuleContext(
        importContext: ModuleImportContext
    ): TypingContext {
        // Start with base context
        var context = baseContext

        // Add imported bindings to context
        // Types with free type variables are registered as polymorphic
        // so they get instantiated with fresh type variables on each use
        for ((name, binding) in importContext.importedBindings) {
            // Parse the name to handle qualified names like "buffer::empty"
            val bindingKey = BindingKey.parse(name)
            val freeVars = collectFreeTypeVars(binding.type)
            if (freeVars.isEmpty()) {
                // Monomorphic type - use directly
                context = context.extendMono(bindingKey, binding.type, binding.modulePath, binding.contentHash)
            } else {
                // Polymorphic type - wrap in TypeScheme for fresh instantiation
                val scheme = TypeScheme(
                    quantifiedVars = freeVars.toList(),
                    constraints = emptyList(),
                    type = binding.type
                )
                context = context.extendPoly(bindingKey, scheme, binding.modulePath, binding.contentHash)
            }
        }

        // Types, abilities, effects are handled by ContextBuilder + TypeChecker.typeCheck()
        // via importContext.importedTypes/importedAbilities/importedEffects — not via TypingContext.

        return context
    }

    /**
     * Get a cached module (if available) from the global cache.
     */
    fun getCachedModule(modulePath: String): TypedModule? {
        return getFromGlobalCache(modulePath)
    }

}
