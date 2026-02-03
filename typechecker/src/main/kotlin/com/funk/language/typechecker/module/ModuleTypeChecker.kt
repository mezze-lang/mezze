package com.funk.language.typechecker.module

import com.funk.language.ast.*
import com.funk.language.typechecker.NewTypeChecker
import com.funk.language.typechecker.ModuleResolver
import com.funk.language.typechecker.context.TypingContext

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

        // Type-check the module using NewTypeChecker
        val typeChecker = NewTypeChecker.withContext(moduleContext)
        val result = typeChecker.checkProgram(module.program)

        return when (result) {
            is NewTypeChecker.TypeCheckResult.Success -> {
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
            is NewTypeChecker.TypeCheckResult.Failure -> {
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
        for ((name, binding) in importContext.importedBindings) {
            context = context.extendMono(name, binding.type, binding.modulePath)
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
    private fun buildTypeContextFromProgram(typedProgram: Program): Map<String, FunkType> {
        val typeContext = mutableMapOf<String, FunkType>()

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
     * Update module exports with inferred types.
     *
     * After type-checking, we update the export types with
     * the actual inferred types from the typed program.
     */
    private fun updateExportsWithTypes(
        exports: Map<String, ExportedItem>,
        typedProgram: Program
    ): Map<String, ExportedItem> {
        val updatedExports = mutableMapOf<String, ExportedItem>()

        // Build a map of names to inferred types from the program
        val inferredTypes = mutableMapOf<String, FunkType>()

        for (item in typedProgram.items) {
            when (item) {
                is LetBinding -> {
                    item.inferredType?.let { type ->
                        inferredTypes[item.variableName] = type
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
                }
                else -> {
                    // Other items don't contribute to exports
                }
            }
        }

        // Update exports with inferred types
        for ((name, export) in exports) {
            val inferredType = inferredTypes[export.originalName]
            updatedExports[name] = if (inferredType != null) {
                export.copy(funkType = inferredType)
            } else {
                export
            }
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
