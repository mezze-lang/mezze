package com.mezze.language.typechecker.phase

import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.ast.solving.Substitution

/**
 * Emit a best-effort partial TypedModule when global constraint solving
 * failed but per-binding solving had already resolved most decls.
 *
 * Decls whose own constraints solved cleanly (recorded in
 * `perBindingSubstitution` / `constraintResults`) get their real types.
 * Decls whose body failed end up with `VarType("unknown")` placeholders
 * inside the resulting TypedAST -- `TypedAstBuilder.build` handles this
 * gracefully and emits a TypedLetDecl regardless. The LSP uses the
 * partial module for hover/completion on unrelated surviving decls
 * (see #149).
 *
 * Wrapped in a single try/catch -- if the partial pass itself blows up
 * (e.g. an unrelated invariant the builder enforces), we just return
 * null and the caller falls back to the LSP's last-successful cache.
 */
internal fun buildPartialTypedModule(
    parsedModule: ParsedModule,
    substitution: Substitution,
    constraintResults: Map<String, ConstraintResult>,
    context: TypingContext,
    buildResult: ContextBuilder.BuildResult,
    importContext: ModuleImportContext?,
    registry: DeclarationRegistry,
    bindLetLocs: Set<com.mezze.language.parsedast.Loc> = emptySet(),
): com.mezze.language.typedast.TypedModule? {
    return try {
        val resolvedResults = constraintResults.mapValues { (_, r) ->
            r.copy(type = substitution.apply(r.type))
        }
        val methodCallResolutions = constraintResults.values
            .fold(emptyMap<Pair<com.mezze.language.parsedast.Loc, String>, ConstraintResult.MethodCallInfo>()) { acc, r ->
                acc + r.methodCallResolutions
            }
        val callReturnTypes = constraintResults.values
            .fold(emptyMap<com.mezze.language.parsedast.Loc, com.mezze.language.ast.MezzeType>()) { acc, r ->
                acc + r.callReturnTypes
            }
        val matchCaseBindings = constraintResults.values
            .fold(emptyMap<com.mezze.language.parsedast.Loc, Map<String, com.mezze.language.ast.MezzeType>>()) { acc, r ->
                acc + r.matchCaseBindings.mapValues { (_, bindings) ->
                    bindings.mapValues { (_, ty) -> substitution.apply(ty) }
                }
            }
        val tailCallLocations = constraintResults.values
            .flatMap { it.tailCalls }
            .filterNotNull()
            .toSet()
        val builder = com.mezze.language.typechecker.emit.TypedAstBuilder(
            substitution = substitution,
            constraintResults = resolvedResults,
            abilityImpls = context.getAllAbilityImpls(),
            registry = registry,
            performerToEffect = buildResult.performerToEffect,
            importContext = importContext,
            identifierContentHashes = context.getContentHashes(),
            implDeclarations = buildResult.implDeclarations,
            tailCallLocations = tailCallLocations,
            mustBeOpenRowVars = emptySet(),
            methodCallResolutions = methodCallResolutions,
            callReturnTypes = callReturnTypes,
            matchCaseBindings = matchCaseBindings,
            bindLetLocs = bindLetLocs,
        )
        builder.build(parsedModule)
    } catch (e: Exception) {
        if (System.getenv("MEZZE_DEBUG") != null) {
            System.err.println("[partial-builder] ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace(System.err)
        }
        null
    }
}
