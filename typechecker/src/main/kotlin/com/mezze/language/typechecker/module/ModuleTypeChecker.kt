package com.mezze.language.typechecker.module

import com.mezze.language.ast.BindingKey
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.typechecker.ModuleError
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typedast.TypedModule as V2TypedModule

class ModuleTypeChecker(
    private val baseContext: TypingContext = TypingContext.empty,
    private val moduleResolver: ModuleResolver? = null
) {
    companion object {
        private val typeCheckingStack = mutableSetOf<String>()
        private val stackLock = Any()

        private val globalCache = mutableMapOf<String, TypedModule>()
        private val globalCacheLock = Any()

        @JvmStatic
        fun clearCache() {
            synchronized(globalCacheLock) {
                globalCache.clear()
            }
            try {
                val stdlib = PrecompiledStdlib
                for ((modulePath, module) in stdlib.loadedModules) {
                    preloadIntoGlobalCache(modulePath.fullPath, module)
                }
            } catch (_: Exception) { /* PrecompiledStdlib not initialized yet */ }
        }

        private fun getFromGlobalCache(modulePath: String): TypedModule? {
            synchronized(globalCacheLock) {
                return globalCache[modulePath]
            }
        }

        private fun putInGlobalCache(modulePath: String, module: TypedModule) {
            synchronized(globalCacheLock) {
                globalCache[modulePath] = module
            }
        }

        @JvmStatic
        fun getAllCachedModules(): Map<String, TypedModule> {
            synchronized(globalCacheLock) {
                return globalCache.toMap()
            }
        }

        @JvmStatic
        fun preloadIntoGlobalCache(modulePath: String, module: TypedModule) {
            putInGlobalCache(modulePath, module)
        }

        @JvmStatic
        fun hydrateFromV2Module(
            modulePath: String,
            v2Module: V2TypedModule
        ) {
            val typeContext = mutableMapOf<String, MezzeType>()
            for ((name, export) in v2Module.exports) {
                typeContext[name] = export.type
            }
            val wrapper = TypedModule(
                modulePath = modulePath,
                exports = emptyMap(),  // checker-side ExportedItem; not the same shape as v2 ExportEntry
                context = TypingContext.empty,
                substitution = Substitution.empty,
                typedModule = v2Module,
                parsedModule = null,
                typeContext = typeContext
            )
            putInGlobalCache(modulePath, wrapper)
        }

        @JvmStatic
        fun clearTypeCheckingStack() {
            synchronized(stackLock) {
                typeCheckingStack.clear()
            }
        }

        @JvmStatic
        fun invalidateModulePaths(modulePaths: Collection<String>) {
            synchronized(globalCacheLock) {
                for (path in modulePaths) globalCache.remove(path)
            }
            synchronized(stackLock) {
                for (path in modulePaths) typeCheckingStack.remove(path)
            }
        }

        private fun isTypeChecking(modulePath: String): Boolean {
            synchronized(stackLock) {
                return modulePath in typeCheckingStack
            }
        }

        private fun startTypeChecking(modulePath: String) {
            synchronized(stackLock) {
                typeCheckingStack.add(modulePath)
            }
        }

        private fun finishTypeChecking(modulePath: String) {
            synchronized(stackLock) {
                typeCheckingStack.remove(modulePath)
            }
        }

        private fun getTypeCheckingStack(): List<String> {
            synchronized(stackLock) {
                return typeCheckingStack.toList()
            }
        }

        fun create() = ModuleTypeChecker()
        fun withContext(context: TypingContext) = ModuleTypeChecker(context)
        fun withResolver(resolver: ModuleResolver) = ModuleTypeChecker(moduleResolver = resolver)
        fun withContextAndResolver(context: TypingContext, resolver: ModuleResolver) = ModuleTypeChecker(context, resolver)
    }

    fun typeCheckModule(
        module: LoadedModule,
        importContext: ModuleImportContext = ModuleImportContext.empty()
    ): ModuleTypeCheckResult {
        getFromGlobalCache(module.modulePath)?.let { cached ->
            val typeContext = cached.typeContext
            val cachedParsedModule = cached.parsedModule ?: module.parsedModule
            if (cachedParsedModule != null) {
                moduleResolver?.updateModuleExportsV2(
                    cached.modulePath, cachedParsedModule, typeContext
                )
            }
            return ModuleTypeCheckResult.Success(cached)
        }

        if (isTypeChecking(module.modulePath)) {
            val cycle = getTypeCheckingStack() + module.modulePath
            throw ModuleError.CircularDependency(cycle)
        }

        startTypeChecking(module.modulePath)

        try {
            val moduleContext = buildModuleContext(importContext)

            val typeChecker = TypeChecker.withContext(moduleContext)
            val modulePath = java.nio.file.Paths.get(module.filePath).parent ?: java.nio.file.Paths.get(".")
            val parsedModule = module.parsedModule
                ?: throw IllegalStateException("LoadedModule for '${module.modulePath}' has no parsedModule")
            val result = typeChecker.checkParsedModule(parsedModule, modulePath, module.modulePath)

            return when (result) {
                is TypeChecker.TypeCheckResult.Success -> {
                    val typeContext = result.typeContext
                    val updatedExports = if (typeContext.isNotEmpty()) {
                        module.exports.mapValues { (_, export) ->
                            val inferredType = typeContext[export.originalName]
                            if (inferredType != null) export.copy(mezzeType = inferredType) else export
                        }
                    } else module.exports

                    val resolvedModule = result.resolvedParsedModule ?: parsedModule

                    val typedModule = TypedModule(
                        modulePath = module.modulePath,
                        exports = updatedExports,
                        context = moduleContext,
                        substitution = result.substitution,
                        typedModule = result.typedModule,
                        parsedModule = resolvedModule,
                        typeContext = typeContext,
                        filePath = module.filePath,
                        sourceHash = module.sourceHash,
                    )

                    putInGlobalCache(module.modulePath, typedModule)

                    moduleResolver?.updateModuleExportsV2(module.modulePath, resolvedModule, typeContext)

                    ModuleTypeCheckResult.Success(typedModule)
                }
                is TypeChecker.TypeCheckResult.Failure -> {
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
            finishTypeChecking(module.modulePath)
        }
    }

    private fun buildModuleContext(
        importContext: ModuleImportContext
    ): TypingContext {
        var context = baseContext
        for ((name, binding) in importContext.importedBindings) {
            val bindingKey = BindingKey.parse(name)
            val freeVars = collectFreeTypeVars(binding.type)
            if (freeVars.isEmpty()) {
                context = context.extendMono(bindingKey, binding.type, binding.modulePath, binding.contentHash)
            } else {
                val scheme = TypeScheme(
                    quantifiedVars = freeVars.toList(),
                    constraints = emptyList(),
                    type = binding.type
                )
                context = context.extendPoly(bindingKey, scheme, binding.modulePath, binding.contentHash)
            }
        }

        return context
    }

}
