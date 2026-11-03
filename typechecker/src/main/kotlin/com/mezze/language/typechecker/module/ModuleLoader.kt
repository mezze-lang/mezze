package com.mezze.language.typechecker.module

import com.mezze.language.ast.ModulePath
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.World
import com.mezze.language.typechecker.Module as ResolverModule
import com.mezze.language.typechecker.ModuleError as ResolverModuleError
import java.nio.file.Paths
import com.mezze.language.typechecker.ExportedItemType as ResolverItemType

class ModuleLoader(
    private val resolver: ModuleResolver
) {
    fun loadModule(path: ModulePath): ModuleLoadResult = try {
        ModuleLoadResult.Success(convertResolverModule(resolver.resolveModule(path)))
    } catch (e: ResolverModuleError) {
        ModuleLoadResult.Failure(listOf(convertResolverError(e)))
    } catch (e: Exception) {
        // An exception with no message still has a CLASS, and naming it beats
        // "Unknown error" — which told the reader nothing while the one fact
        // available went unprinted.
        ModuleLoadResult.Failure(listOf(ModuleError.ParseError(
            path.fullPath,
            "Could not load this module: " + (e.message ?: e::class.simpleName
                ?: "a failure that carried neither a message nor a type") +
                ". Re-run with --verbose for the full stack trace.",
        )))
    }

    fun loadModule(modulePath: String): ModuleLoadResult = loadModule(ModulePath.parse(modulePath))

    fun getResolver(): ModuleResolver = resolver

    /**
     * `null` means the module DOES NOT EXIST — that is the whole contract, and it
     * is the condition this catches by name. It used to catch every exception,
     * which reported a circular dependency and an unreadable file as "no such
     * module" too. Measured at zero firings across all four suites.
     */
    fun getModule(modulePath: String): LoadedModule? = try {
        convertResolverModule(resolver.resolveModule(modulePath))
    } catch (_: com.mezze.language.typechecker.ModuleError.ModuleNotFound) {
        null
    }

    fun loadDependencies(module: LoadedModule): Map<ModulePath, LoadedModule> {
        val loaded = mutableMapOf<ModulePath, LoadedModule>()
        val toLoad = ArrayDeque(module.dependencies)
        val loading = mutableSetOf<ModulePath>()

        while (toLoad.isNotEmpty()) {
            val path = toLoad.removeFirst()
            if (path in loaded) continue
            if (path in loading) throw ModuleError.CircularDependency(loading.map { it.fullPath } + path.fullPath)

            loading.add(path)
            when (val result = loadModule(path)) {
                is ModuleLoadResult.Success -> {
                    loaded[path] = result.module
                    toLoad.addAll(result.module.dependencies.filter { it !in loaded })
                }
                is ModuleLoadResult.Failure -> throw result.errors.first()
            }
            loading.remove(path)
        }
        return loaded
    }

    fun loadModuleWithDependencies(path: ModulePath): ModuleLoadResult = try {
        val rootResult = loadModule(path)
        if (rootResult is ModuleLoadResult.Success) loadDependencies(rootResult.module)
        rootResult
    } catch (e: ModuleError) {
        ModuleLoadResult.Failure(listOf(e))
    } catch (e: Exception) {
        ModuleLoadResult.Failure(listOf(ModuleError.ParseError(path.fullPath, "Failed to load dependencies: ${e.message}")))
    }

    fun loadModuleWithDependencies(modulePath: String): ModuleLoadResult =
        loadModuleWithDependencies(ModulePath.parse(modulePath))

    private fun convertResolverModule(resolverModule: ResolverModule): LoadedModule {
        @Suppress("DEPRECATION")
        // Never re-parsed here: a module's declarations carry identities the
        // resolver stamps once its imports are known, and a fresh parse has none.
        val parsedModule = resolverModule.parsedModule
            ?: error("internal: the resolver produced ${resolverModule.path.fullPath} with no parsed module")
        return LoadedModule(
            path = resolverModule.path,
            filePath = resolverModule.filePath,
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
            dependencies = resolverModule.dependencies.map { ModulePath.parse(it.modulePath) },
            index = resolverModule.index,
            sourceHash = resolverModule.sourceHash,
            parsedModule = parsedModule,
            identity = resolverModule.identity,
        )
    }

    private fun convertExportType(
        resolverType: ResolverItemType
    ): ExportedItemType = when (resolverType) {
        ResolverItemType.FUNCTION -> ExportedItemType.FUNCTION
        ResolverItemType.TYPE -> ExportedItemType.TYPE
        ResolverItemType.CONSTRUCTOR -> ExportedItemType.CONSTRUCTOR
        ResolverItemType.ABILITY -> ExportedItemType.ABILITY
        ResolverItemType.EFFECT -> ExportedItemType.EFFECT
    }

    private fun convertResolverError(error: ResolverModuleError): ModuleError = toModuleError(error)

    companion object {
        /**
         * Convert a resolver-side [ResolverModuleError] to the typechecker's
         * [ModuleError] for rendering. Used by the loader, and by CLI/LSP entry
         * points that catch a raw resolver error (e.g. an ambiguous module path
         * thrown straight from resolution) and want the canonical `error[M08xx]`
         * layout rather than the exception's `toString`.
         */
        @JvmStatic
        fun toModuleError(error: ResolverModuleError): ModuleError = when (error) {
            is ResolverModuleError.ModuleNotFound -> ModuleError.ModuleNotFound(error.modulePath, error.searchPaths, error.suggestion)
            is ResolverModuleError.CircularDependency -> ModuleError.CircularDependency(error.cycle)
            is ResolverModuleError.ParseError -> ModuleError.ParseError(error.modulePath, error.error)
            is ResolverModuleError.AmbiguousModule -> ModuleError.AmbiguousModule(error.modulePath, error.locations)
            is ResolverModuleError.ExportNotFound -> ModuleError.ExportNotFound(error.modulePath, error.exportName, error.loc)
            // The module path is genuinely not known on this arm -- the error
            // shape that reaches it carries none -- so say that rather than
            // printing "unknown" as if it were a filename.
            else -> ModuleError.ParseError(
                "<module not identified>",
                error.message ?: ("a " + error::class.simpleName + " that carried no message"),
            )
        }

        @JvmOverloads
        fun create(world: World = World.DEFAULT) =
            ModuleLoader(ModuleResolver(Paths.get("."), world))

        /**
         * A loader whose resolver reads and writes [world].
         *
         * The world has to enter here rather than at the checker: the resolver is
         * built at this point, and a resolver in one world under a checker in
         * another splits the caches — ModuleTypeChecker's init refuses that pairing.
         */
        @JvmOverloads
        fun fromPath(path: String, world: World = World.DEFAULT) =
            ModuleLoader(ModuleResolver(Paths.get(path), world))
    }
}
