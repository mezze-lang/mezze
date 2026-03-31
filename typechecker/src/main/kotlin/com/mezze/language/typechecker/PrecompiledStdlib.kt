package com.mezze.language.typechecker

import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.module.*
import java.nio.file.Paths

/**
 * Singleton that precomputes and holds all stdlib/prelude data.
 *
 * Computed once at first access (class load). Every TypeChecker instance
 * and every interpreter test reuses these immutable results — no per-test
 * import resolution, no per-test mergeImportContext, no per-test ModuleLoader.
 */
object PrecompiledStdlib {

    /** The prelude ModuleImportContext (ability impls only, no bindings) */
    val preludeImportContext: ModuleImportContext

    /** The loaded v1 TypedModules for all prelude/stdlib modules */
    val loadedModules: Map<String, TypedModule>

    /** The base TypingContext with native functions + all prelude bindings merged in.
     *  Tests extend this with their own user imports. */
    val enhancedContext: TypingContext

    /** The v2 TypedModules for monomorphizer, keyed by hash */
    val v2Modules: Map<com.mezze.language.typedast.Hash, com.mezze.language.typedast.TypedModule>

    init {
        // Build everything once
        val tc = TypeChecker()
        val parentContext = tc.parentContext

        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val moduleLoader = ModuleLoader.fromPath(projectRoot.toString())
        val moduleTypeChecker = ModuleTypeChecker.withContextAndResolver(
            parentContext,
            moduleLoader.getResolver()
        )
        val importResolver = ImportResolver(moduleLoader, moduleTypeChecker)

        // Load prelude modules
        val preludeCtx = tc.loadPreludeModulesPublic(importResolver)
        preludeImportContext = preludeCtx

        // Get loaded modules from the prelude loading
        loadedModules = tc.getCachedPreludeLoadedModules() ?: emptyMap()

        // Merge prelude into the base context — this is the expensive step we're caching
        enhancedContext = tc.mergeImportContextPublic(parentContext, preludeCtx)

        // Build v2 modules for monomorphizer
        val adapter = TypeCheckerV2()
        val v2 = mutableMapOf<com.mezze.language.typedast.Hash, com.mezze.language.typedast.TypedModule>()
        for ((path, v1Module) in loadedModules) {
            val v2Module = adapter.convertV1TypedModule(path, v1Module)
            v2[v2Module.hash] = v2Module
        }
        v2Modules = v2
    }
}
