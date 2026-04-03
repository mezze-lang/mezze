package com.mezze.language.typechecker

import com.mezze.language.typechecker.module.*
import java.nio.file.Paths

/**
 * Singleton that precomputes all stdlib/prelude data once at first access.
 *
 * Contains:
 * - CompiledModuleInterface: immutable type signatures ready to inject into any TypingContext
 * - Loaded TypedModules: for the v2 pipeline (monomorphizer needs them)
 * - V2 TypedModules: pre-converted for monomorphizer
 *
 * Unlike caching ModuleImportContext or TypingContext directly,
 * CompiledModuleInterface.mergeInto() is safe to call multiple times
 * because it creates fresh TypeVars on each instantiation.
 */
object PrecompiledStdlib {

    /** Compiled prelude interface — immutable, safe to reuse across compilations */
    val compiledPrelude: CompiledModuleInterface

    /** Raw prelude import context (for merging with user imports) */
    val preludeImportContext: ModuleImportContext

    /** Loaded v1 TypedModules for all prelude modules */
    val loadedModules: Map<String, TypedModule>

    /** Pre-converted v2 TypedModules for monomorphizer */
    val v2Modules: Map<com.mezze.language.typedast.Hash, com.mezze.language.typedast.TypedModule>

    /** Cached ModuleLoader — avoids per-test filesystem traversal (findProjectRoot) */
    val moduleLoader: ModuleLoader

    /** Cached ImportResolver — reuses moduleLoader and moduleTypeChecker */
    val importResolver: ImportResolver

    init {
        val tc = TypeChecker()
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        moduleLoader = ModuleLoader.fromPath(projectRoot.toString())
        val moduleTypeChecker = ModuleTypeChecker.withContextAndResolver(
            tc.parentContext,
            moduleLoader.getResolver()
        )
        importResolver = ImportResolver(moduleLoader, moduleTypeChecker)

        // Load prelude (also populates ModuleTypeChecker.globalCache + ModuleResolver.moduleCache)
        preludeImportContext = tc.loadPreludeModulesPublic(importResolver)
        loadedModules = tc.getCachedPreludeLoadedModules() ?: emptyMap()

        // Build the compiled interface from the import context
        compiledPrelude = CompiledModuleInterface.fromImportContext(preludeImportContext)

        // Build v2 modules
        val adapter = TypeCheckerV2()
        val v2 = mutableMapOf<com.mezze.language.typedast.Hash, com.mezze.language.typedast.TypedModule>()
        for ((path, v1Module) in loadedModules) {
            try {
                val v2Module = adapter.convertV1TypedModule(path, v1Module)
                v2[v2Module.hash] = v2Module
            } catch (_: Exception) {
                // Skip modules that can't be converted — they'll be handled on-demand
            }
        }
        v2Modules = v2
    }
}
