package com.mezze.language.typechecker.phase

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.module.ImportResolutionResult
import com.mezze.language.typechecker.module.ModuleError
import com.mezze.language.typechecker.module.ImportResolver
import com.mezze.language.ast.ModulePath
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.typechecker.module.TypedModuleResult

internal fun loadPreludeModules(
    importResolver: ImportResolver,
    userImportContext: ModuleImportContext,
    cachedPreludeContext: ModuleImportContext?,
    onPreludeLoaded: (ModuleImportContext, Map<ModulePath, TypedModuleResult>) -> Unit,
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
        // A prelude that fails to load is reported, not absorbed.
        //
        // This used to answer `ModuleImportContext.empty()`, which is a lie with
        // no diagnostic attached: every ability, impl and operator the standard
        // library provides silently vanishes, and the FIRST thing the user's
        // program does with one becomes the error they see. One broken stdlib
        // module presents as `1 + 2` reporting "Cannot use '+' with Int, Int. To
        // enable this, implement: impl NumAdd Int for Int" — a message that is
        // not true, names nothing that would lead anywhere, and is identical
        // whatever the actual cause was. It cost an afternoon to trace once; a
        // print at this line named the real error in one run.
        //
        // MEASURED before changing it: across both full suites, with a healthy
        // stdlib, this branch fires ZERO times. It is not a path normal
        // compilation takes — it is reached only when the prelude is genuinely
        // broken, which is exactly when it must say so.
        //
        // Every error is carried, not just the first: the prelude is many modules
        // and one bad declaration can fail several imports at once.
        is ImportResolutionResult.Failure -> throw preludeFailure(result.errors)
    }

    return preludeContext
}

/**
 * The diagnostic for a prelude that would not load. Separate from the call site
 * so it can be tested: reaching the real branch needs a deliberately broken
 * standard library, and no harness here builds one.
 *
 * Reports EVERY error rather than the first. The prelude is many modules and one
 * bad declaration can fail several imports at once, so stopping at one would
 * hide the others behind a recompile each.
 */
internal fun preludeFailure(errors: List<ModuleError>): TypeCheckError {
    val first = errors.firstOrNull()
    return TypeCheckError(
        message = "The prelude failed to load, so nothing from the standard library is in scope. " +
            "This is a fault in the standard library or in how it was built, not in your program " +
            "— unless one of your own modules shadows a `std::` path. " +
            "First problem: ${first?.message ?: "no further detail was reported"}",
        code = ErrorCode.M0804_MODULE_HAS_ERRORS,
        hints = listOf(
            "if you are changing the stdlib, the module named above does not typecheck — fix it first",
            "otherwise reinstall the runtime: `./gradlew :cli:installRuntime`",
        ) + (first?.hints ?: emptyList()),
        cause = first,
        additional = errors.drop(1).map { e ->
            TypeCheckError(message = e.message, code = e.code, hints = e.hints, cause = e)
        },
    )
}
