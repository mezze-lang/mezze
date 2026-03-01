package com.mezze.language.typechecker.module

import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.Module as ResolverModule
import com.mezze.language.typechecker.ModuleError as ResolverModuleError
import com.mezze.language.typechecker.ProgramIndex
import java.nio.file.Paths

/**
 * Module loader - loads and parses modules from the filesystem.
 *
 * This wraps the existing ModuleResolver with a clean interface
 * that returns our new result types.
 *
 * ## Design:
 * - Pure functions (no mutation!)
 * - Immutable results
 * - Explicit error handling
 * - Uses existing ModuleResolver for actual loading
 *
 * ## Usage:
 * ```kotlin
 * val loader = ModuleLoader.create()
 * val result = loader.loadModule("std::collections::array")
 *
 * when (result) {
 *     is ModuleLoadResult.Success -> {
 *         val module = result.module
 *         // Use the loaded module
 *     }
 *     is ModuleLoadResult.Failure -> {
 *         for (error in result.errors) {
 *             println(error.message)
 *         }
 *     }
 * }
 * ```
 */
class ModuleLoader(
    private val resolver: ModuleResolver
) {
    /**
     * Load a module by its path.
     *
     * This loads and parses the module, but does NOT type-check it yet.
     * Type-checking is done by ModuleTypeChecker.
     *
     * @param modulePath Module path (e.g., "std::collections::array")
     * @return ModuleLoadResult (Success or Failure)
     */
    fun loadModule(modulePath: String): ModuleLoadResult {
        return try {
            // Use existing ModuleResolver to load the module
            val resolverModule = resolver.resolveModule(modulePath)

            // Convert to our LoadedModule type
            val loadedModule = convertResolverModule(resolverModule)

            ModuleLoadResult.Success(loadedModule)

        } catch (e: ResolverModuleError) {
            // Convert ModuleResolver errors to our error types
            val moduleError = convertResolverError(e)
            ModuleLoadResult.Failure(listOf(moduleError))

        } catch (e: Exception) {
            // Catch unexpected errors
            val moduleError = ModuleError.ParseError(
                modulePath = modulePath,
                error = "Unexpected error: ${e.message ?: "Unknown error"}"
            )
            ModuleLoadResult.Failure(listOf(moduleError))
        }
    }

    /**
     * Get the underlying ModuleResolver.
     *
     * This allows ModuleTypeChecker to update the resolver's cache
     * with inferred types after type-checking.
     */
    fun getResolver(): ModuleResolver {
        return resolver
    }

    /**
     * Get a module from the cache if it's already been loaded.
     *
     * @param modulePath Module path (e.g., "std::collections::array")
     * @return LoadedModule if cached, null otherwise
     */
    fun getModule(modulePath: String): LoadedModule? {
        return try {
            val resolverModule = resolver.resolveModule(modulePath)
            convertResolverModule(resolverModule)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Load all dependencies of a module.
     *
     * This recursively loads all transitive dependencies,
     * building a complete dependency graph.
     *
     * @param module The module whose dependencies to load
     * @return Map of module path -> LoadedModule
     */
    fun loadDependencies(module: LoadedModule): Map<String, LoadedModule> {
        val loaded = mutableMapOf<String, LoadedModule>()
        val toLoad = mutableListOf<String>()

        // Start with direct dependencies
        toLoad.addAll(module.dependencies)

        // Track loading to detect cycles
        val loading = mutableSetOf<String>()

        while (toLoad.isNotEmpty()) {
            val modulePath = toLoad.removeAt(0)

            // Skip if already loaded
            if (loaded.containsKey(modulePath)) {
                continue
            }

            // Check for circular dependency
            if (loading.contains(modulePath)) {
                throw ModuleError.CircularDependency(
                    loading.toList() + modulePath
                )
            }

            // Mark as loading
            loading.add(modulePath)

            // Load the module
            val result = loadModule(modulePath)

            when (result) {
                is ModuleLoadResult.Success -> {
                    val dependency = result.module
                    loaded[modulePath] = dependency

                    // Add transitive dependencies
                    toLoad.addAll(dependency.dependencies.filter { !loaded.containsKey(it) })
                }
                is ModuleLoadResult.Failure -> {
                    // Propagate error
                    throw result.errors.first()
                }
            }

            // Done loading this module
            loading.remove(modulePath)
        }

        return loaded
    }

    /**
     * Load a module and all its dependencies.
     *
     * This is a convenience method that loads a module
     * and all its transitive dependencies in one call.
     *
     * @param modulePath Module path to load
     * @return Map of all loaded modules (including the requested module)
     */
    fun loadModuleWithDependencies(modulePath: String): ModuleLoadResult {
        return try {
            // Load the root module
            val rootResult = loadModule(modulePath)

            when (rootResult) {
                is ModuleLoadResult.Success -> {
                    // Load all dependencies
                    val dependencies = loadDependencies(rootResult.module)

                    // Return success with the root module
                    // (dependencies are tracked separately)
                    rootResult
                }
                is ModuleLoadResult.Failure -> {
                    // Propagate failure
                    rootResult
                }
            }

        } catch (e: ModuleError) {
            ModuleLoadResult.Failure(listOf(e))
        } catch (e: Exception) {
            ModuleLoadResult.Failure(
                listOf(
                    ModuleError.ParseError(
                        modulePath,
                        "Failed to load dependencies: ${e.message}"
                    )
                )
            )
        }
    }

    // ========================================================================
    // CONVERSION HELPERS
    // ========================================================================

    /**
     * Convert ModuleResolver's Module to our LoadedModule type.
     */
    private fun convertResolverModule(resolverModule: ResolverModule): LoadedModule {
        return LoadedModule(
            modulePath = resolverModule.name,
            filePath = resolverModule.filePath,
            program = resolverModule.program,
            exports = resolverModule.exports.mapValues { (_, exportedItem) ->
                ExportedItem(
                    name = exportedItem.name,
                    type = convertExportType(exportedItem.type),
                    mezzeType = exportedItem.mezzeType,
                    originalName = exportedItem.originalName,
                    visibility = exportedItem.visibility,
                    contentHash = exportedItem.contentHash
                )
            },
            dependencies = resolverModule.dependencies.map { it.modulePath },
            index = resolverModule.index
        )
    }

    /**
     * Convert ModuleResolver's ExportedItemType to our enum.
     */
    private fun convertExportType(
        resolverType: com.mezze.language.typechecker.ExportedItemType
    ): ExportedItemType {
        return when (resolverType) {
            com.mezze.language.typechecker.ExportedItemType.FUNCTION -> ExportedItemType.FUNCTION
            com.mezze.language.typechecker.ExportedItemType.TYPE -> ExportedItemType.TYPE
            com.mezze.language.typechecker.ExportedItemType.CONSTRUCTOR -> ExportedItemType.CONSTRUCTOR
            com.mezze.language.typechecker.ExportedItemType.ABILITY -> ExportedItemType.ABILITY
            com.mezze.language.typechecker.ExportedItemType.EFFECT -> ExportedItemType.EFFECT
        }
    }

    /**
     * Convert ModuleResolver errors to our error types.
     */
    private fun convertResolverError(error: ResolverModuleError): ModuleError {
        return when (error) {
            is ResolverModuleError.ModuleNotFound -> ModuleError.ModuleNotFound(
                modulePath = error.modulePath,
                searchPaths = error.searchPaths
            )
            is ResolverModuleError.CircularDependency -> ModuleError.CircularDependency(
                cycle = error.cycle
            )
            is ResolverModuleError.ParseError -> ModuleError.ParseError(
                modulePath = error.modulePath,
                error = error.error
            )
            is ResolverModuleError.ExportNotFound -> ModuleError.ExportNotFound(
                modulePath = error.modulePath,
                exportName = error.exportName,
                sourceLocation = error.sourceLocation
            )
            else -> {
                // Fallback for other error types
                ModuleError.ParseError(
                    modulePath = "unknown",
                    error = error.message ?: "Unknown error"
                )
            }
        }
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    companion object {
        /**
         * Create a module loader with default configuration.
         */
        fun create(): ModuleLoader {
            val resolver = ModuleResolver(Paths.get("."))
            return ModuleLoader(resolver)
        }

        /**
         * Create a module loader from a specific directory.
         */
        fun fromPath(path: String): ModuleLoader {
            val resolver = ModuleResolver(Paths.get(path))
            return ModuleLoader(resolver)
        }
    }
}
