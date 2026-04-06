package com.mezze.language.typechecker.module

import com.mezze.language.ast.*
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
         * Preload a typed module into the global cache from pre-compiled stdlib.
         * Prevents re-type-checking when the prelude loads these modules.
         */
        @JvmStatic
        fun preloadIntoGlobalCache(modulePath: String, module: TypedModule) {
            putInGlobalCache(modulePath, module)
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

            // Update v1 ModuleResolver cache if the cached program is non-empty
            @Suppress("DEPRECATION")
            val cachedProgram = cached.program
            if (cachedProgram.items.isNotEmpty()) {
                @Suppress("DEPRECATION")
                moduleResolver?.updateModuleExports(
                    cached.modulePath, cachedProgram, typeContext
                )
            }
            // Also store the parsedModule in the resolver cache for v2 consumers
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

            // Type-check the module using TypeChecker
            // Use the module's file path parent directory for import resolution
            val typeChecker = TypeChecker.withContext(moduleContext)
            val modulePath = java.nio.file.Paths.get(module.filePath).parent ?: java.nio.file.Paths.get(".")
            // Prefer ParsedModule (v2) if available, fall back to v1 Program
            @Suppress("DEPRECATION")
            val result = if (module.parsedModule != null) {
                typeChecker.checkParsedModule(module.parsedModule!!, modulePath, module.modulePath)
            } else {
                typeChecker.checkProgram(module.program, modulePath, module.modulePath)
            }

            return when (result) {
                is TypeChecker.TypeCheckResult.Success -> {
                    // Create typed module — use original program for structure
                    @Suppress("DEPRECATION")
                    val moduleProgram = if (result.program.items.isNotEmpty()) result.program else module.program

                    // Build type context once — reused for exports and resolver updates
                    val typeContext = if (moduleProgram.items.isNotEmpty())
                        buildTypeContextFromProgram(moduleProgram) else emptyMap()

                    val typedModule = TypedModule(
                        modulePath = module.modulePath,
                        program = moduleProgram,
                        exports = if (moduleProgram.items.isNotEmpty())
                            updateExportsWithTypes(module.exports, moduleProgram)
                            else module.exports,
                        context = moduleContext,
                        substitution = result.substitution,
                        monomorphicInstances = result.monomorphicInstances,
                        v2TypedModule = result.typedModule,
                        parsedModule = module.parsedModule,
                        typeContext = typeContext
                    )

                    // Cache the result in GLOBAL cache (thread-safe)
                    putInGlobalCache(module.modulePath, typedModule)

                    // Update ModuleResolver's cache with real inferred types and ProgramIndex.
                    // The v1 updateModuleExports rebuilds the ProgramIndex from the typed program
                    // (which has cross-module ability impls resolved and content hashes computed).
                    // Then updateModuleExportsV2 overlays the parsedModule for v2 consumers.
                    if (moduleProgram.items.isNotEmpty()) {
                        moduleResolver?.let { resolver ->
                            @Suppress("DEPRECATION")
                            resolver.updateModuleExports(module.modulePath, moduleProgram, typeContext)
                            // Also store the parsedModule in the resolver cache for v2 path
                            if (module.parsedModule != null) {
                                resolver.updateModuleExportsV2(module.modulePath, module.parsedModule!!, typeContext)
                            }
                        }
                    }

                    ModuleTypeCheckResult.Success(typedModule)
                }
                is TypeChecker.TypeCheckResult.Failure -> {
                    // Convert type check errors
                    val errors = result.errors.map { error ->
                        ModuleTypeCheckResult.TypeCheckError(
                            modulePath = module.modulePath,
                            phase = error.phase,
                            message = error.message,
                            sourceLocation = error.sourceLocation
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

        // TODO: Add imported types, abilities, effects

        return context
    }

    /**
     * Build type context from a typed program.
     *
     * This extracts all inferred types from the program into a map
     * that can be passed to ModuleResolver.updateModuleExports().
     */
    private fun buildTypeContextFromProgram(typedProgram: Program): Map<String, MezzeType> {
        val typeContext = mutableMapOf<String, MezzeType>()

        for (item in typedProgram.items) {
            when (item) {
                is LetBinding -> {
                    item.inferredType?.let { type ->
                        typeContext[item.variableName] = type
                    }
                }
                is TypeDeclaration -> {
                    item.inferredType?.let { type ->
                        typeContext[item.name] = type
                    }
                }
                is EffectDeclaration -> {
                    item.inferredType?.let { type ->
                        typeContext[item.name] = type
                    }
                }
                is ImplDeclaration -> {
                    item.inferredType?.let { type ->
                        typeContext[item.performerName] = type
                    }
                }
                else -> {
                    // Other items don't contribute to type context
                }
            }
        }

        return typeContext
    }

    /**
     * Update module exports with inferred types and content hashes.
     *
     * After type-checking, we update the export types with
     * the actual inferred types from the typed program.
     */
    private fun updateExportsWithTypes(
        exports: Map<String, ExportedItem>,
        typedProgram: Program
    ): Map<String, ExportedItem> {
        val updatedExports = mutableMapOf<String, ExportedItem>()

        val inferredTypes = mutableMapOf<String, MezzeType>()
        val contentHashes = mutableMapOf<String, String>()

        for (item in typedProgram.items) {
            when (item) {
                is LetBinding -> {
                    item.inferredType?.let { inferredTypes[item.variableName] = it }
                    item.contentHash?.let { contentHashes[item.variableName] = it }
                }
                is TypeDeclaration -> item.inferredType?.let { inferredTypes[item.name] = it }
                is EffectDeclaration -> item.inferredType?.let { inferredTypes[item.name] = it }
                is ImplDeclaration -> {
                    item.inferredType?.let { inferredTypes[item.performerName] = it }
                    item.contentHash?.let { contentHashes[item.performerName] = it }
                }
                else -> {}
            }
        }

        for ((name, export) in exports) {
            val inferredType = inferredTypes[export.originalName]
            val contentHash = contentHashes[export.originalName]
            updatedExports[name] = export.copy(
                mezzeType = inferredType ?: export.mezzeType,
                contentHash = contentHash ?: export.contentHash
            )
        }

        return updatedExports
    }

    /**
     * Get a cached module (if available) from the global cache.
     */
    fun getCachedModule(modulePath: String): TypedModule? {
        return getFromGlobalCache(modulePath)
    }

}
