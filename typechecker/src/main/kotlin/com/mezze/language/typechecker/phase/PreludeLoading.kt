package com.mezze.language.typechecker.phase

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.module.ImportResolutionResult
import com.mezze.language.typechecker.module.ImportResolver
import com.mezze.language.ast.ModulePath
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.typechecker.module.TypedModule

internal fun loadPreludeModules(
    importResolver: ImportResolver,
    userImportContext: ModuleImportContext,
    cachedPreludeContext: ModuleImportContext?,
    onPreludeLoaded: (ModuleImportContext, Map<ModulePath, TypedModule>) -> Unit,
): ModuleImportContext {
    cachedPreludeContext?.let { return it }

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
            // method-call resolution sees their operation types (#304).
            val ctx = result.importContext
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
