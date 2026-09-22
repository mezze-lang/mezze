package com.mezze.language.typechecker.module

import com.mezze.language.ast.ModulePath
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.Module as ResolverModule
import com.mezze.language.typechecker.ModuleError as ResolverModuleError
import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ExportedItemType as ResolverItemType

class ModuleLoader(
    private val resolver: ModuleResolver
) {
    fun loadModule(path: ModulePath): ModuleLoadResult = try {
        ModuleLoadResult.Success(convertResolverModule(resolver.resolveModule(path)))
    } catch (e: ResolverModuleError) {
        ModuleLoadResult.Failure(listOf(convertResolverError(e)))
    } catch (e: Exception) {
        ModuleLoadResult.Failure(listOf(ModuleError.ParseError(path.fullPath, "Unexpected error: ${e.message ?: "Unknown error"}")))
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
        val parsedModule = resolverModule.parsedModule
            // The read is NOT wrapped in a catch that substitutes "". A file that
            // cannot be read is not an empty module: every name it exports would
            // vanish and the consumer would be told those names are "not exported",
            // pointing at the wrong file. Measured at zero firings.
            ?: MezzeParser().parseParsedModule(
                java.io.File(resolverModule.filePath).readText(),
                resolverModule.filePath
            )
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
            parsedModule = parsedModule
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

    private fun convertResolverError(error: ResolverModuleError): ModuleError = when (error) {
        is ResolverModuleError.ModuleNotFound -> ModuleError.ModuleNotFound(error.modulePath, error.searchPaths, error.suggestion)
        is ResolverModuleError.CircularDependency -> ModuleError.CircularDependency(error.cycle)
        is ResolverModuleError.ParseError -> ModuleError.ParseError(error.modulePath, error.error)
        is ResolverModuleError.ExportNotFound -> ModuleError.ExportNotFound(error.modulePath, error.exportName, error.loc)
        else -> ModuleError.ParseError("unknown", error.message ?: "Unknown error")
    }

    companion object {
        fun create() = ModuleLoader(ModuleResolver(Paths.get(".")))
        fun fromPath(path: String) = ModuleLoader(ModuleResolver(Paths.get(path)))
    }
}
