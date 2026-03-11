package com.mezze.language.typechecker.module

import com.mezze.language.ast.*
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.context.collectFreeTypeVars

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
    // Thread-safe cache for typed modules
    private val cache = mutableMapOf<String, TypedModule>()
    private val cacheLock = Any()

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
        // Check cache first (thread-safe)
        synchronized(cacheLock) {
            cache[module.modulePath]?.let {
                return ModuleTypeCheckResult.Success(it)
            }
        }

        // Build context for this module
        val moduleContext = buildModuleContext(importContext)

        // Type-check the module using TypeChecker
        // Use the module's file path parent directory for import resolution
        val typeChecker = TypeChecker.withContext(moduleContext)
        val modulePath = java.nio.file.Paths.get(module.filePath).parent ?: java.nio.file.Paths.get(".")
        val result = typeChecker.checkProgram(module.program, modulePath)

        return when (result) {
            is TypeChecker.TypeCheckResult.Success -> {
                // Create typed module
                val typedModule = TypedModule(
                    modulePath = module.modulePath,
                    program = result.program,
                    exports = updateExportsWithTypes(module.exports, result.program),
                    context = moduleContext,
                    substitution = result.substitution,
                    monomorphicInstances = result.monomorphicInstances
                )

                // Cache the result (thread-safe)
                synchronized(cacheLock) {
                    cache[module.modulePath] = typedModule
                }

                // CRITICAL FIX: Update ModuleResolver's cache with real inferred types
                // This ensures that subsequent imports of this module get the actual function types
                // instead of placeholder type variables
                moduleResolver?.let { resolver ->
                    // Build type context from inferred types
                    val typeContext = buildTypeContextFromProgram(result.program)
                    resolver.updateModuleExports(module.modulePath, result.program, typeContext)
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

        // Build maps of names to inferred types and content hashes from the program
        val inferredTypes = mutableMapOf<String, MezzeType>()
        val contentHashes = mutableMapOf<String, String>()

        for (item in typedProgram.items) {
            when (item) {
                is LetBinding -> {
                    item.inferredType?.let { type ->
                        inferredTypes[item.variableName] = type
                    }
                    item.contentHash?.let { hash ->
                        contentHashes[item.variableName] = hash
                    }
                }
                is TypeDeclaration -> {
                    item.inferredType?.let { type ->
                        inferredTypes[item.name] = type
                    }
                }
                is EffectDeclaration -> {
                    item.inferredType?.let { type ->
                        inferredTypes[item.name] = type
                    }
                }
                is ImplDeclaration -> {
                    item.inferredType?.let { type ->
                        inferredTypes[item.performerName] = type
                    }
                    item.contentHash?.let { hash ->
                        contentHashes[item.performerName] = hash
                    }
                }
                else -> {
                    // Other items don't contribute to exports
                }
            }
        }

        // Update exports with inferred types and content hashes
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
     * Clear the module cache.
     *
     * This is useful for testing or when modules have been updated.
     */
    fun clearCache() {
        synchronized(cacheLock) {
            cache.clear()
        }
    }

    /**
     * Get a cached module (if available).
     */
    fun getCachedModule(modulePath: String): TypedModule? {
        synchronized(cacheLock) {
            return cache[modulePath]
        }
    }

    companion object {
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
}
