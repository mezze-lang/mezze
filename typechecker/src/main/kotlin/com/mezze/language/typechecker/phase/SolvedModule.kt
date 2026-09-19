package com.mezze.language.typechecker.phase

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.Loc
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.constraints.Resolution

/**
 * S5.2 (#365): the SINGLE immutable value that crosses the solver→emit seam.
 *
 * Before this, emit (`emitTypedModuleAndLower` → `TypedAstBuilder`) took a raw
 * `Substitution` plus a handful of loose Loc-keyed side-tables as SEPARATE
 * parameters, all of which were mutable solver state the emit code could reach
 * behind. `SolvedModule` bundles exactly the decided data emit READS — nothing
 * more — into one read-only value built ONCE at the boundary where the global
 * solve finishes. Emit is now a pure lowering of (SolvedModule, parsedModule):
 * it cannot reach into live solver state, only this frozen snapshot.
 *
 * Every field here is emit's INPUT, not cruft: the builders consume each one to
 * construct the TypedModule nodes (see the field docs). Deleting any of them
 * would change the emitted AST — this is a plumbing bundle, not a cleanup.
 */
data class SolvedModule(
    /** The composed, post-solve substitution. Emit applies it to resolve types. */
    val substitution: Substitution,
    /**
     * The SINGLE post-solve dispatch decision per inherent-method call site
     * (S2.2/#358, from DispatchResolver). Emit reads `resolution.opHash` instead
     * of re-picking an impl. Sites the resolver deferred have no entry.
     */
    val dispatchResolutions: Map<Loc, Resolution>,
    /** Tail-call sites the solver identified; emit stamps TypedCall.isTailCall. */
    val tailCallLocations: Set<Loc>,
    /**
     * Row vars the solver decided must stay OPEN (closed effect-row bookkeeping);
     * emit keeps them open rather than closing them.
     */
    val mustBeOpenRowVars: Set<RowVar>,
    /** Per-call-site method-call info the solver recorded; emit dispatches on it. */
    val methodCallResolutions: Map<Pair<Loc, String>, ConstraintResult.MethodCallInfo>,
    /** Solved return type per call-site Loc; emit uses it for receiver dispatch. */
    val callReturnTypes: Map<Loc, MezzeType>,
    /** Solved per-match-case binding types; emit populates PatternBinding.type. */
    val matchCaseBindings: Map<Loc, Map<String, MezzeType>>,
    /** Solved (unerased) scrutinee type per Loc; emit stamps scrutineeSourceType. */
    val matchScrutineeTypes: Map<Loc, MezzeType>,
    /**
     * #396: solved VALUE type of each do-block `let` RHS, keyed by the RHS
     * expression's Loc — the SAME authoritative type the top-level path threads
     * into `constraintResults[name]`. Emit's buildDoBlock stamps the binding's
     * stored type from this instead of re-deriving from the erased RHS node, so
     * do-block and top-level lets converge on ONE type source. The pure value
     * type (bare, no value-carried effect row); emit's #378 effect-row rider is
     * separate.
     */
    val doBindingSolvedTypes: Map<Loc, MezzeType> = emptyMap(),
    /** Effect-model migration (S2): per `perform` Loc, whether it fully discharges
     *  its body's row → emit sets TypedPerform.fullyDischarges from this instead of
     *  inspecting the value-carried effect row. */
    val performFullyDischarged: Map<Loc, Boolean> = emptyMap(),
    /** Per-lambda solved parameter types, keyed by the lambda's Loc. Emit reads a
     *  parameter's type from here when no expected type reached the lambda, so an
     *  inline callback passed to an undeclared callee is typed by what its own body
     *  determined rather than by a fresh variable. */
    val lambdaParamTypes: Map<Loc, Map<String, MezzeType>> = emptyMap(),
)
