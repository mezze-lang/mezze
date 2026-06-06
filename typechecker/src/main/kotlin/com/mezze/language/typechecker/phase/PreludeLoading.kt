package com.mezze.language.typechecker.phase

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.module.ImportResolutionResult
import com.mezze.language.typechecker.module.ImportResolver
import com.mezze.language.typechecker.module.ModuleImportContext

/**
 * Load prelude modules and return a combined ModuleImportContext.
 * Cached globally -- computed once per JVM process since stdlib doesn't change.
 */
internal fun loadPreludeModules(
    importResolver: ImportResolver,
    userImportContext: ModuleImportContext,
    cachedPreludeContext: ModuleImportContext?,
    onPreludeLoaded: (ModuleImportContext, Map<com.mezze.language.ast.ModulePath, com.mezze.language.typechecker.module.TypedModule>) -> Unit,
): ModuleImportContext {
    // Return cached prelude if available
    cachedPreludeContext?.let { return it }

    // Load prelude module list from _prelude.mz (compiler-internal, not user-importable).
    val preludeSource = ModuleResolver.readStdlibFile("stdlib/std/_prelude.mz")
        ?: ModuleResolver.readStdlibFile("std/_prelude.mz")
        ?: error(
            "Compiler internal error: stdlib/std/_prelude.mz not found on the classpath. " +
            "This file is required to bootstrap the prelude. Rebuild the runtime jar via " +
            "`./gradlew :cli:installRuntime` (or `installNative`)."
        )
    val syntheticModule = MezzeParser().parseParsedModule(preludeSource, file = "<prelude>")

    val result = importResolver.resolveImports(syntheticModule)
    val preludeContext = when (result) {
        is ImportResolutionResult.Success -> {
            // Import inherent impls for method syntax + named ability impls for
            // context-based constraint solving (replacing hardcoded checks).
            // No bindings -- prevents name collisions (option::fold vs seq::fold).
            //
            // Abilities ARE carried so that `with X 'a` constraints in user
            // code can refer to prelude abilities (Sized, Show, etc.) and
            // method-call resolution sees their operation types (#304).
            //
            // Effects and effect impls are stripped. The Exception effect /
            // ExceptionToResult performer needed by the do-bind desugar are
            // injected per-module by `injectExceptionToResultForBind` ONLY
            // when the module contains a `<-`. See do-bind-proposal.md.
            val ctx = result.importContext
            // Cache loaded modules for inclusion in Monomorphizer's allModules
            onPreludeLoaded(ctx, result.loadedModules)
            ModuleImportContext(
                importedBindings = emptyMap(),
                importedTypes = emptyMap(),
                importedAbilities = ctx.importedAbilities,
                importedEffects = emptyMap(),
                importedImpls = emptyMap(),
                importedAbilityImpls = ctx.importedAbilityImpls,
                inherentImplOpReturnTypes = ctx.inherentImplOpReturnTypes,
                inherentImplOpParamTypes = ctx.inherentImplOpParamTypes,
                inherentImplOpEffects = ctx.inherentImplOpEffects
            )
        }
        is ImportResolutionResult.Failure -> ModuleImportContext.empty()
    }

    return preludeContext
}

/**
 * Inject Exception + ExceptionToResult into the import context so the
 * synthetic `perform ExceptionToResult in ...` emitted by DoBindDesugar
 * resolves at constraint-generation time. Only called when the module
 * actually contains `<-`.
 */
internal fun injectExceptionToResultForBind(
    baseCtx: ModuleImportContext,
    importResolver: ImportResolver,
    cachedExceptionForBindCtx: ModuleImportContext?,
    onCached: (ModuleImportContext) -> Unit,
): ModuleImportContext {
    val exceptionCtx = cachedExceptionForBindCtx ?: run {
        val effectName = com.mezze.language.typechecker.desugar.DoBindDesugar.EXCEPTION_EFFECT_NAME
        val performerName = com.mezze.language.typechecker.desugar.DoBindDesugar.EXCEPTION_TO_RESULT_NAME
        val syntheticSrc = "use std::effects::exception::{ $effectName, $performerName }"
        val syntheticModule = MezzeParser().parseParsedModule(syntheticSrc, file = "<dobind-synthetic>")
        when (val r = importResolver.resolveImports(syntheticModule)) {
            is ImportResolutionResult.Success -> r.importContext.also {
                onCached(it)
            }
            is ImportResolutionResult.Failure -> return baseCtx
        }
    }
    return baseCtx.copy(
        importedEffects = baseCtx.importedEffects + exceptionCtx.importedEffects,
        importedImpls = baseCtx.importedImpls + exceptionCtx.importedImpls,
    )
}
