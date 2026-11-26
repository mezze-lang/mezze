package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.ModulePath
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.parsedast.Loc
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.ParsedPerform
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.dependencies.DefinitionRef
import com.mezze.language.typedast.AbilityImplRef
import com.mezze.language.typedast.Hash
import com.mezze.language.parsedast.*


sealed class ErrorContext {
    data class Simple(val message: String) : ErrorContext()

    data class FunctionArgument(
        val functionName: String?,
        val argumentName: String?,
        val parameterIndex: Int = 0     // 0-indexed position
    ) : ErrorContext()

    data class TypeAnnotation(
        val bindingName: String,
        val isReturnType: Boolean = false
    ) : ErrorContext()

    data class FieldAccess(
        val fieldName: String,
        val recordExpression: String? = null
    ) : ErrorContext()

    data class PatternMatch(
        val patternDescription: String,
        val constructorName: String? = null
    ) : ErrorContext()

    data class BinaryOperation(
        val operator: String,
        val isLeftOperand: Boolean
    ) : ErrorContext()

    data class SeqElement(val elementIndex: Int) : ErrorContext()

    data class ReturnType(val functionName: String?) : ErrorContext()

    data class StringInterpolation(val expressionIndex: Int) : ErrorContext()
}

/**
 * One handler at one `perform` site, with what that site resolved to.
 *
 * Both post-solve effect checks read this: the computed-abort-payload
 * comparison needs [blockType], and C7 — a handler-bound function escaping a
 * SCOPED perform — needs [performerIsScoped] alongside it. Neither can be
 * decided during constraint generation: the payload's type comes from the
 * op-body solve, and a bare lambda's type is still a variable there.
 */
data class PerformSiteHandler(
    val performerName: String,
    val blockType: MezzeType,
    val performerIsScoped: Boolean,
)

sealed interface Constraint {
    val loc: Loc?
    val context: String?
    fun mapTypes(f: (MezzeType) -> MezzeType): Constraint

    data class Equality(
        val left: MezzeType,
        val right: MezzeType,
        override val context: String? = null,
        override val loc: Loc? = null,
        val isMatchCaseContext: Boolean = false,
        /**
         * These two types are ALTERNATIVES reaching one place, so when both are
         * variants their constructors JOIN rather than having to match.
         *
         * `isMatchCaseContext` is the same idea under a narrower name — match
         * arms were the first place it was needed. Branches of an `if` and the
         * elements of a seq literal are the same question: `[Red, Pink]` is a
         * seq of `| Red, Pink |`, not a type error and not `[| Red |]`.
         *
         * Without it the seq literal equated every element to one fresh var,
         * `mergeVariantConstructors` declined to merge (neither a match context
         * nor two open rows), and whichever element bound the var first won —
         * so `[Red, Pink]` was `[| Red |]` and `[Pink, Red]` was `[| Pink |]`,
         * the answer decided by the order they were written in.
         */
        val isAlternativesJoin: Boolean = false,
        val errorContext: ErrorContext? = null,
        /**
         * #376: effect names discharged by enclosing `perform` blocks at the
         * point this constraint was generated. Populated by
         * `ExprPerformConstraints.generatePerform` after body generation
         * returns; default empty for outermost / non-body constraints. Its
         * former reader — the value-carried effect-row leak guard — was removed
         * with the effect-model migration (effects now ride the arrow).
         */
        val activeDischargedEffects: Set<String> = emptySet(),
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(left = f(left), right = f(right))
    }

    data class AbilityRequired(
        val typeVar: TypeVar,
        val abilityName: String,
        val abilityHash: Hash? = null,
        val typeArgs: List<MezzeType> = emptyList(),
        /**
         * The index [typeVar] occupied in the obligation's ORIGINAL positional
         * tuple, when the producer knew it. The split into `typeVar` + `typeArgs`
         * does not otherwise record it, and every site that rebuilds the full
         * tuple needs it — see `abilityClassArgs`. Null means "not recorded",
         * which reconstructs at the head as it always did.
         */
        val primaryIndex: Int? = null,
        override val context: String? = null,
        override val loc: Loc? = null
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(typeArgs = typeArgs.map(f))
    }

    data class AssociatedTypeResolution(
        val abilityName: String,
        val primaryTypeVar: TypeVar,
        val associatedTypeVars: Map<String, TypeVar>,
        // The FULL class-parameter type-var tuple in ability-decl order (e.g. for
        // `NumAdd 'r for 'l`, [ 'r, 'l ]). Needed to disambiguate which impl
        // supplies the `having` bindings when several impls share a receiver type
        // — e.g. `NumAdd Float for Float` and `NumAdd Int for Float` both have
        // receiver `'l = Float`, so matching the receiver alone picks the wrong
        // impl's `'out`. Matching the whole tuple resolves it. Empty for
        // single-param abilities (primaryTypeVar alone suffices).
        /**
         * The ability's FULL positional tuple, in the order an impl stores its
         * `targetTypeAnnotations` (`AbilityImplFactory` builds those as
         * `abilityTypeArgs + targetTypes`).
         *
         * TYPES, not vars, because a position is routinely CONCRETE and the
         * tuple is what disambiguates impls that differ only in a non-receiver
         * position. This was `List<TypeVar>` and both call-site emitters built
         * it with `.mapNotNull { it as? VarType }`, so every concrete position
         * was DROPPED: `NumAdd Int Float` arrived as the 1-tuple `[Int]`, which
         * matches no 2-annotation impl, reads as a single-parameter ability (so
         * the B1 multi-param guard does not apply), and falls through to
         * receiver-only matching on whichever var survived — picking
         * `impl NumAdd Int for Int`, whose `out = Int`, and reporting
         *
         *     'out': expected Int but got Float
         *
         * on `let direct = { n } -> 0.5 + n` called at Int. `bench/cases/hetero`
         * is the reproducer.
         */
        val classArgTypes: List<MezzeType> = emptyList(),
        /** Content hash of the ability this resolution belongs to. A NAME is not an
         *  identity — two abilities in scope may declare the same one — so matching
         *  a body's resolution to an annotation's constraint goes through
         *  [sameAbility], which prefers this. Null where the hash was not yet known
         *  at the construction site. */
        val abilityHash: Hash? = null,
        override val context: String? = null,
        override val loc: Loc? = null
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = this
    }

    data class Subsumes(
        val general: MezzeType,
        val specific: MezzeType,
        override val context: String? = null,
        override val loc: Loc? = null
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(general = f(general), specific = f(specific))
    }

    data class HasField(
        val recordType: MezzeType,
        val fieldName: String,
        val fieldType: MezzeType,
        override val context: String? = null,
        override val loc: Loc? = null,
        val strictFieldCheck: Boolean = false,
        /**
         * This field read is a PATTERN's, so the groups arriving at it come from
         * different match ARMS and must be UNIONED rather than unified.
         *
         * `Some { val = Red }` and `Some { val = Pink }` are two arms of one
         * match. At the top level the arms union, because the solver's
         * `Equality` path consults `mergeVariantConstructors` when the
         * constraint carries `isMatchCaseContext`. A nested payload is
         * reconciled by `solveHasField`, which unifies the two field types
         * directly -- so without this flag the second arm's group met the
         * first's as a plain mismatch and the arms-define-the-group rule (5.9)
         * stopped at the top level.
         */
        val isPatternContext: Boolean = false,
        /**
         * Global COMPLETION pass: succeed where the receiver is now a concrete
         * record carrying the field, and defer everywhere else — invent nothing,
         * reject nothing.
         *
         * A field read whose receiver is still a variable DEFERS in the
         * per-binding solve, and these constraints used to be stripped before the
         * global solve, so nothing ever finished them. Re-running them normally
         * grows open rows by inventing the field; re-running them STRICTLY
         * rejects a field a spread legitimately provides. This third mode does
         * neither.
         */
        val completionOnly: Boolean = false,
        /**
         * This access is a CALL's function — `x.m {…}`, not a data-field read.
         *
         * A method call reaches the field path only when its candidate family is
         * NON-homogeneous (`to_str` is `{it} -> Str` on six types and
         * `{it} -> Result Str Str` on Bytes) AND the receiver is genuinely
         * polymorphic, so there is no representative signature to type it with and
         * no impl to pick. Growing the receiver into `{ to_str: {} -> 'a }` types
         * the call and the runtime dispatches on the value, which works — but that
         * shape is not a record the program has, so it must not be PUBLISHED into
         * the shared substitution: emit would see a record where the value is a
         * Long and lower a data-field read.
         * [ConstraintSolver.methodGrownReceiverVars] records the receivers it grew.
         */
        val methodCallee: Boolean = false,
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(recordType = f(recordType), fieldType = f(fieldType))
    }

    data class HasConstructor(
        val variantType: MezzeType,
        val constructorName: String,
        val payloadType: MezzeType?,
        override val context: String? = null,
        override val loc: Loc? = null,
        val isPatternContext: Boolean = false
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(variantType = f(variantType), payloadType = payloadType?.let(f))
    }

    data class MustBeOpen(
        val type: MezzeType,
        val bindingName: String? = null,
        override val context: String? = null,
        override val loc: Loc? = null,
        // #403 payload carve-out: set when this MustBeOpen guards a CLOSED record
        // pattern whose scrutinee is a FRESH inference var (an anonymous variant
        // payload inferred purely from this pattern). Such a row must be CLOSED to
        // exactly the pattern's fields UNLESS a genuinely wider value flows into it
        // — see solveMustBeOpen for the discrimination.
        val closeableIfPatternInferred: Boolean = false
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(type = f(type))
    }

    data class RecordPatternExhaustive(
        val recordType: MezzeType,
        val patternFields: Set<String>,
        override val context: String? = null,
        override val loc: Loc? = null
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(recordType = f(recordType))
    }

    /**
     * #160: resolve an inherent method whose receiver type is only known AFTER
     * solving, when the candidate impls DON'T share a signature (e.g. `abs`:
     * Int->Int, Float->Float, Frac->Frac — the return type varies by receiver).
     *
     * At generation time we can't pick the impl (the receiver is a tyvar) nor a
     * representative return type. So we defer: the solver waits until
     * [receiverType] resolves to a concrete type, then picks the candidate whose
     * `targetTypeName` matches, unifies [argsType] with that candidate's non-`it`
     * param record and [resultType] with its return type. A receiver that solves
     * to a type with no matching candidate is a real error (sound: dispatch would
     * have no runtime impl). See `solveInherentMethodResolve`.
     */
    data class InherentMethodResolve(
        val receiverType: MezzeType,
        val methodName: String,
        val candidates: List<InherentMethodCandidate>,
        val argsType: MezzeType,
        val resultType: MezzeType,
        // #160: the RECEIVER expression's own loc. Emit resolves a receiver's type
        // via the per-call-site callReturnTypes map (keyed by loc), which can pin a
        // type the flat substitution alone doesn't (forward-refs / effect contexts).
        // The undispatchability check consults it so it matches what emit dispatches
        // on — avoiding false "could not be determined" on resolvable receivers.
        val receiverLoc: Loc? = null,
        override val context: String? = null,
        override val loc: Loc? = null
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(
            receiverType = f(receiverType),
            argsType = f(argsType),
            resultType = f(resultType),
            candidates = candidates.map { it.copy(methodType = f(it.methodType)) }
        )
    }

    /**
     * #160: one candidate for [InherentMethodResolve] — enough to unify the
     * call's args/return against the picked impl's signature and to re-pick the
     * impl by solved receiver type at emit time.
     */
    data class InherentMethodCandidate(
        val targetTypeName: String,
        val methodType: MezzeType,
        val implContentHash: Hash,
        // #280/F2: the target type's declaration content hash, so the deferred
        // solve picks the impl by the SOLVED receiver's hash (rename-safe,
        // module-distinct) rather than by bare `targetTypeName`. `null` for
        // builtins with no hash — selection falls back to the name then.
        val targetTypeHash: Hash? = null,
        /** The impl's target as a type — decides between same-hash candidates. */
        val targetType: MezzeType? = null,
    )

    /**
     * A best-effort DEFAULT that runs ONLY after the main solve completes: if,
     * under the final substitution, [bindingType] has solved to the SAME
     * type-constructor as [shape] (e.g. both `Result`), unify their arguments
     * pairwise so any still-free var in [bindingType] takes the concrete type
     * [shape] fixes in that position. A discharging `perform` uses this to pin
     * the error channel of a mid-block Ok-only binding (`let bad =
     * safe_div{...}.ok{}` → `Result Int Str`, not `Result Int 'a`) to the
     * ambient handler's discharged associated type, WITHOUT coercing a binding
     * whose value is a plain `Int` (different constructor → no-op) or
     * double-wrapping an already-shaped one.
     *
     * SOUND by construction: it only ever runs `unify(solvedBinding, shape)`
     * when the two share a constructor, and a unify FAILURE is IGNORED (never an
     * error) — so it can only bind free vars, never override a concrete slot nor
     * reshape a non-matching type. [shape]'s own vars are freshened at emit time
     * so binding them is harmless.
     */
    data class DefaultAssociatedType(
        val bindingType: MezzeType,
        val shape: MezzeType,
        override val context: String? = null,
        override val loc: Loc? = null
    ) : Constraint {
        override fun mapTypes(f: (MezzeType) -> MezzeType) = copy(bindingType = f(bindingType), shape = f(shape))
    }
}

// ---------------------------------------------------------------------------
// S2.1 (#356) — Stage 2 foundation: single post-solve dispatch.
//
// The pieces below are ADDITIVE and not yet wired into the live dispatch path.
// Today, ability + builtin-op + inherent-method dispatch is decided by the
// gen-time heuristic ladder in ExprMethodConstraints.kt. Stage 2 replaces that
// scattered selection with ONE hash-keyed post-solve pass. S2.1 lays the
// foundation ONLY:
//
//   * [Resolution] is the SHAPE that single post-solve pass (S2.2) will populate
//     and that emit/mono/compile (S2.3) will read. It records the final, chosen
//     dispatch as (op hash, receiver type identity, kind). It is a pure data
//     structure here — nothing produces or consumes it yet.
//
//   * [MethodResolveBucket] generalizes the deferred-dispatch idea of
//     [Constraint.InherentMethodResolve] to EVERY method/ability/builtin call
//     site. It RECORDS the candidate set + the receiver, keyed on identity/hash,
//     and SELECTS NOTHING. Constraint generation records one per method-call
//     site (alongside the still-live ladder, via the generator's recording sink
//     — NOT into the solved constraint list), so the S2.2 pass has a
//     candidate bucket to resolve. The old ladder remains the thing that
//     actually decides dispatch until it is deleted in S2.3.
// ---------------------------------------------------------------------------

/** How a call site dispatches, once resolved post-solve. */
sealed interface ResolutionKind {
    /** An inherent method (`impl T where { m }`). */
    data object Inherent : ResolutionKind
    /** A user- or prelude-declared ability op. */
    data object Ability : ResolutionKind
    /** A built-in operator ability (NumAdd, NumMul, …). */
    data object Builtin : ResolutionKind
}

/**
 * The single, post-solve dispatch decision for one call site — the SHAPE the
 * S2.2 pass populates and S2.3's emit/mono/compile read. [opHash] identifies the
 * chosen op (ability-method / inherent-method / builtin-op) by content hash;
 * [receiverTypeIdentity] is the SOLVED receiver's identity (the impl-selection
 * key — hash for `Declared`, reserved name for `Builtin`); [kind] records which
 * dispatch family was chosen. Not yet produced or consumed — additive foundation.
 */
data class Resolution(
    val opHash: Hash,
    val receiverTypeIdentity: TypeIdentity,
    val kind: ResolutionKind,
)

/**
 * One candidate in a [MethodResolveBucket]: enough to re-select the impl by the
 * SOLVED receiver's identity post-solve, and to name which dispatch family it
 * belongs to. Keyed on identity/hash — NEVER on a bare string name for the
 * dispatch decision. [targetTypeIdentity] is the impl target's identity (`null`
 * for candidates whose target identity isn't known until solve, e.g. a builtin
 * op whose receiver is still a tyvar); [displayName] is metadata for
 * diagnostics only.
 */
data class ResolutionCandidate(
    val opHash: Hash?,
    val kind: ResolutionKind,
    val targetTypeIdentity: TypeIdentity? = null,
    val displayName: String? = null,
    /** An inherent candidate's target as a type — decides between same-identity impls. */
    val targetType: MezzeType? = null,
)

/**
 * S2.1 candidate-BUCKETING record for one method/ability/builtin call site.
 * Generalizes [Constraint.InherentMethodResolve] to cover ability + builtin ops
 * too. It RECORDS the candidate set + the receiver and SELECTS NOTHING — the
 * S2.2 single pass consumes it to produce a [Resolution]. Not a [Constraint]:
 * it never enters the solver's worklist, so adding it changes no solving
 * behaviour. Recorded by constraint generation into
 * [ExpressionConstraintGenerator.resolutionBuckets] alongside the still-live
 * ladder.
 */
data class MethodResolveBucket(
    val loc: Loc?,
    val methodName: String,
    val receiverType: MezzeType,
    val candidates: List<ResolutionCandidate>,
    /**
     * The content hash of the ability the source NAMED at this call site, when it
     * named one. A HASH and not the name: the name is resolved to the declaration
     * it denotes once, here, where the scope to resolve it in is still known —
     * so the consumer compares identities like every other candidate decision and
     * never re-matches a string.
     *
     * Null when the call is unqualified, or when the named ability has no content
     * hash to resolve to.
     */
    val explicitAbilityHash: Hash? = null,
)

data class ConstraintResult(
    val type: MezzeType,
    val constraints: List<Constraint>,
    val effects: Set<String> = emptySet(),
    val effectInstances: Set<MezzeType.EffectInstance> = emptySet(),
    val performed: Boolean = false,
    // #256/#268: row vars flowing OUT of this expression's inferred effect row.
    // When a lambda body calls a callback param `f: {..} -> _ <r>`, calling `f`
    // contributes `r` here so the enclosing lambda's own effect row can carry it
    // — i.e. `launder: {f: ..<r>} -> _ <r>` (real row polymorphism), instead of
    // silently inferring `launder` pure and laundering the callback's effects.
    // This is a pure inference-time annotation; it never enters
    // Substitution.rowVarMappings (that path is #268 Stage 2+).
    val effectRowVars: Set<RowVar> = emptySet(),
    val abilityConstraints: List<Constraint.AbilityRequired> = emptyList(),
    val associatedTypeConstraints: List<Constraint.AssociatedTypeResolution> = emptyList(),
    val undefinedVariables: List<UndefinedVariable> = emptyList(),
    val duplicateBindings: List<DuplicateBinding> = emptyList(),
    val tailCalls: Set<Loc> = emptySet(),
    val usedVariables: Set<String> = emptySet(),
    val unusedVariables: List<UnusedVariable> = emptyList(),
    // BUG 3: dead handlers in a `perform` list — see Constraint.UnusedHandler.
    val unusedHandlers: List<UnusedHandler> = emptyList(),
    val patternWarnings: List<String> = emptyList(),
    val referencedBindings: Set<DefinitionRef.ByName> = emptySet(),
    val methodCallResolutions: Map<Pair<Loc, String>, MethodCallInfo> = emptyMap(),
    val callReturnTypes: Map<Loc, MezzeType> = emptyMap(),
    /** Per-call-site instantiated callee arrow — see the collector's field. */
    val callFunctionTypes: Map<Loc, MezzeType> = emptyMap(),
    val expectedTypeAt: Map<Loc, MezzeType> = emptyMap(),
    // #195 mono-fix #1: per-match-case pattern bindings, keyed by the case's
    // Loc. The values are the constraint generator's fresh TyVars from
    // PatternConstraintResult — the SAME TyVars that HasField etc. unified
    // with concrete types. TypedAstBuilder applies the final substitution
    // and uses these as PatternBinding.type + identifierContext entries for
    // guard/body emission. Without this, TypedAstBuilder constructed fresh
    // placeholder TyVars (e.g. `TypeVar(name="w", id=0)`) that had no
    // identity relationship to the solver's TyVars (id=N) — substitution
    // silently passed through, and the body kept `w: VarType` in the
    // specialized mono blob, defeating inline op dispatch on the renamed
    // bindings.
    val matchCaseBindings: Map<Loc, Map<String, MezzeType>> = emptyMap(),
    // BUG D (#332): the SOLVED type of each match's scrutinee expression, keyed
    // by the scrutinee's Loc — the UNERASED source-level scrutinee type (e.g.
    // `Age`, not the erased `Int`). This is EMIT'S INPUT: TypedAstBuilder stamps
    // it onto TypedMatch.scrutineeSourceType (#376). S5.1 (#364) then made
    // exhaustiveness read the DECIDED TypedMatch node off the emitted TypedModule
    // instead of re-inferring from this side-table — so the validator no longer
    // reads this map directly; only the emit-population path does. (Emit erases a
    // newtype scrutinee to its underlying primitive for runtime lowering; the
    // decided node carries this unerased type separately for exhaustiveness.)
    val matchScrutineeTypes: Map<Loc, MezzeType> = emptyMap(),
    // BUG C (#331): callback-effect harvest for a `<...>` HOF call
    // (collectClosureArgEffects) runs at constraint-GEN time and skips any arg
    // field that is not YET a concrete FunctionType (`as? FunctionType ?: continue`).
    // When the effectful callback reaches the slot through a polymorphic
    // intermediary (`apply_eff { f = id { g = leaky } }`), the field is a VarType
    // at gen-time, its `<Log>` is dropped, and a pure-declared wrapper is wrongly
    // accepted. We record each such harvest's raw argument type + the effects the
    // op catches, so the decl-finalization pass (which holds the final
    // substitution) can RE-harvest the SOLVED argument and fold in any effect
    // names the gen-time pass missed. Idempotent: re-folding already-seen effects
    // is a set union no-op.
    val pendingClosureArgEffects: List<PendingClosureHarvest> = emptyList(),
    // For a do-block: each intermediate `let` binding's inferred type paired with
    // the effect names its value surfaced. An enclosing `perform` reads this to
    // emit a best-effort [Constraint.DefaultAssociatedType] that pins the error
    // channel of a mid-block value carrying the discharged effect to the handler's
    // discharged associated type (post-solve, never a coercion). Populated only by
    // generateDo for its OWN direct bindings — NOT bubbled up through merge(), so
    // it never crosses a nested-block boundary.
    val doBindingEffects: List<DoBindingEffect> = emptyList(),
    // #396 (do-block let convergence): the SOLVED value type of each do-block
    // `let` binding's RHS, keyed by the RHS expression's Loc. This is the SAME
    // authoritative type the top-level path threads back into `results[name]`
    // (via bindLet's `solvedBindingsWithEffects`) — recorded here per-loc so the
    // do-block emit can stamp the binding's stored type through the SAME source
    // top-level uses, instead of re-deriving from the emit/erasure view of the
    // RHS node (`substitution.apply(value.type)`). It is the PURE value type: the
    // do-block sequences the statement's effects into the enclosing block row and
    // binds the pure value, so this map holds only the bare value (bindLet runs with
    // harvestEffectsIntoBinding = false). Emit's #378 effect-row rider stays a
    // separate step. Keyed by (globally unique) Loc, so it merges up through
    // nested blocks without collision.
    val doBindingSolvedTypes: Map<Loc, MezzeType> = emptyMap(),
    // Effect-model migration (S2, docs/effect-model.md): per `perform` expr Loc,
    // true iff it discharges every effect in its body's row (residual empty) so the
    // runtime runs the body eagerly. Threaded to emit like doBindingSolvedTypes so
    // TypedPerform.fullyDischarges is sourced from the residual-effect set, not any
    // value-carried effect row (removed in the effect-model migration).
    val performFullyDischarged: Map<Loc, Boolean> = emptyMap(),
    /**
     * Per-`perform` row of the BODY, recorded only when the body's type is a
     * FunctionType, and recorded BEFORE `removeEffectsFromType` strips it.
     *
     * S-20's `capturesIntoResult` used to re-derive this in TypedAstBuilder from
     * the post-solve `body.type`. That works for a function REFERENCE, whose
     * annotated type is concrete and cannot be overwritten, and fails for a bare
     * LAMBDA, whose type starts as a variable and gets unified with the stripped
     * result — so the row was already gone and the handler-bind was never
     * emitted. MEASURED: `perform Ticker in my_func` then calling it gives 1,
     * while `perform Ticker in {} -> Tick.tick {}` gives a MezzeSuspend from a
     * binding declared Int. Annotating the lambda fixed it, which is the tell
     * that the inference was at fault rather than the program.
     */
    val performBodyFnRow: Map<Loc, Set<String>> = emptyMap(),
    /**
     * Per `perform` site: the performers handled there, paired with the type the
     * perform EXPRESSION produces.
     *
     * The other half of the computed-abort-payload check. A performer's
     * `cont.abort { val = … }` decides what the whole block yields, and
     * `ContextBuilder.extractSuspensionType` can only type that payload when it
     * is a literal — for a call it falls back to a fresh variable, which unifies
     * with anything and makes the site comparison vacuous. The op-body solve DOES
     * compute the real type (`ImplValidator.drainAbortPayloadVars`), but it runs
     * during constraint generation and a perform site may precede the impl in the
     * file, so it cannot be fed forward. Both ends are therefore recorded and
     * compared after solving.
     */
    val performSiteExpectation: Map<Loc, List<PerformSiteHandler>> = emptyMap(),
    // Per-lambda solved PARAMETER types, keyed by the lambda's Loc. A lambda that
    // is an argument to a callee whose type nothing declares — the comparator in
    // `__native_seq_sort_by { xs = xs, cmp = { a, b } -> … }` — gets no expected
    // type pushed in, so emit minted a fresh variable per parameter and the
    // solver's own answer, which the BODY does determine, had nowhere to be read
    // back from. Merged up like callReturnTypes.
    val lambdaParamTypes: Map<Loc, Map<String, MezzeType>> = emptyMap(),
    /**
     * Each lambda's OWN effect-row instances, keyed by its Loc — what its BODY
     * determined, before the arrow discharged them.
     *
     * Emit stamps a lambda's type with the row from the PUSHED CONTEXT, which
     * for an inline argument is the CALLEE's declared row and so carries the
     * callee's variables. An `IntoErrorResponse 'e` constrained on the thrown
     * type then has nothing concrete to resolve against at the call site. The
     * body knew: `{ m } -> throw { error = "gone" }` determines
     * `Exception having 'error = Str`. Same reason [lambdaParamTypes] exists,
     * one field over.
     */
    val lambdaRowInstances: Map<Loc, Set<MezzeType.EffectInstance>> = emptyMap(),
) {

    /** A deferred callback-effect harvest for BUG C (#331). [argType] is the
     *  `<...>` HOF call's argument type as seen at gen-time (may contain VarType
     *  fields); [caughtByOp] are effect names the op discharges (row subtraction). */
    data class PendingClosureHarvest(val argType: MezzeType, val caughtByOp: Set<String>,
        /**
         * How many lambda boundaries this harvest has crossed. A lambda discharges
         * its body's effects at its arrow, so a harvest from INSIDE a nested lambda
         * must not surface on the enclosing declaration (`g: {} -> RouteGroup = {} ->
         * router {…}.websocket { handler = { … messages {…} } }` was refused for
         * "performing WebSocket"); the decl finalizer reads depth <= 1 — its own
         * value lambda's body. The `perform` body check still reads every depth,
         * which is what lets an effectful stage callback get its TYPE error
         * instead of "the handler can never fire".
         */
        val depth: Int = 0,
    )

    /** A do-block `let` binding's type + the effect names its value surfaced,
     *  used by an enclosing `perform` to pin the binding's discharged-effect
     *  associated type post-solve. See [Constraint.DefaultAssociatedType]. */
    data class DoBindingEffect(val bindingType: MezzeType, val effectNames: Set<String>)
    data class MethodCallInfo(
        val abilityName: String?,        // null for inherent methods
        val methodName: String,
        val methodHash: Hash?,
        /** The argument field the receiver arrives in — `it` for every operation
         *  that declares one. NULL means the operation declares no receiver, and
         *  the impl is chosen by the type the call PRODUCES. */
        val receiverFieldName: String?,
        val isInherentMethod: Boolean = false,
        val implContentHash: Hash? = null,
        val implStartLine: Int = 0,
        val implStartCol: Int = 0,
        val implSourceFile: String = "",
        val methodFunctionType: MezzeType? = null,
        // #195 mono-fix #2: per-call-site resolved instantiation. When
        // populated, TypedAstBuilder stamps `TypedCall.instantiation` so the
        // monomorphizer's transitive worklist (TypeSubstitution.collectByHashRefs)
        // can enqueue this call's specializations instead of silently
        // dropping every nested generic. typeArgs is in callee's typeParam
        // declaration order; abilityImpls is one entry per ability constraint
        // on the callee's function type, in declaration order. Both default to
        // empty — call sites that don't populate keep null instantiation
        // downstream (no allocation, hash-stable). Field population is wired
        // incrementally — the infrastructure can carry data even when the
        // populating site hasn't been built yet.
        val typeArgs: List<MezzeType> = emptyList(),
        val abilityImpls: List<AbilityImplRef> = emptyList(),
        // #160: when an inherent method's receiver type is only known after
        // solving (e.g. `help {..}.to_str {}` where `help` is polymorphic and
        // the args pin it to Int), we can't pick the concrete `impl Int` /
        // `impl Str` / … at generation time. Instead we record every
        // homogeneous candidate here (each carries its `targetTypeName` +
        // `implContentHash`), and TypedAstBuilder picks the one whose target
        // matches the SOLVED receiver type. Empty for the common case where the
        // impl was resolved statically (implContentHash is authoritative then).
        val candidateImpls: List<InherentImplCandidate> = emptyList(),
    )

    /**
     * A deferred-resolution candidate for [MethodCallInfo.candidateImpls]:
     * enough to re-pick the impl by solved receiver type at emit time.
     */
    data class InherentImplCandidate(
        val targetTypeName: String,
        val implContentHash: Hash,
        // #280/F1: target type's declaration content hash, so emit picks the impl
        // by the SOLVED receiver's hash (rename-safe, module-distinct) rather than
        // by bare `targetTypeName`. `null` for builtins — falls back to name.
        val targetTypeHash: Hash? = null,
        /** The impl's target as a type — decides between same-hash candidates. */
        val targetType: MezzeType? = null,
    )
    data class UndefinedVariable(
        val name: String,
        val loc: Loc?,
        val qualifier: ModulePath? = null,
        val isQualified: Boolean = false
    )

    data class DuplicateBinding(
        val name: String,
        val loc: Loc?
    )

    data class UnusedVariable(
        val name: String,
        val loc: Loc?,
        /** Which kind of binding: each has its own fix, and none of them is a `_` prefix. */
        val kind: BindingKind = BindingKind.LET,
    )

    /**
     * `_` has ONE meaning: a pattern wildcard that binds nothing. A `_`-prefixed
     * name is an ordinary name, so the fix for an unused binding is never "prefix
     * it": a `let` is removed (its expression kept as a statement if it must run),
     * a pattern binding becomes `_`, a free-standing parameter is removed (its name
     * is the field callers pass).
     */
    enum class BindingKind { LET, PATTERN, PARAMETER }

    /**
     * BUG 3: a handler in a `perform` list whose effect the body never performs.
     * The existing V0510 ERROR only fires when the body performs NOTHING at all,
     * so `perform Console, GraalPy, GraalJs in <uses Console + Py>` slipped
     * through silently — the dead `GraalJs` reads to a human as "JS is in play".
     * Reported per-handler as W0401 rather than escalated to an error: the body's
     * row is an inference result, and a warning that is occasionally imprecise is
     * recoverable where a spurious hard error is not.
     */
    data class UnusedHandler(
        val performerName: String,
        val effectName: String,
        val loc: Loc?
    )
    companion object {
        fun simple(type: MezzeType): ConstraintResult {
            return ConstraintResult(type, emptyList())
        }

        /**
         * Combine the sub-results an expression was built from.
         *
         * Every field below is propagated, and that is the point: this type has
         * twenty-odd fields, nearly all defaulted to empty, so a hand-written
         * merge that forgets one does not fail — it silently produces the empty
         * value and the sub-expression's contribution disappears. The three
         * method-call generators each merged only part, and dropped six fields
         * 10,359 times across the typechecker suite before it was measured.
         *
         * [type] and [constraints] belong to the combining site, so they are
         * parameters rather than merged. Everything a site ADDS on top goes in a
         * `copy()` over the result — `merged.effects + myOwnEffects`, not a
         * fresh union of the sub-results, so the base can never be bypassed.
         *
         * A new field on [ConstraintResult] is propagated the moment it is added
         * here, for every site at once. That is the only thing standing between
         * "twenty defaulted fields" and another silent drop.
         */
        /**
         * The sub-result of an OPERAND, ready to be carried out through its
         * enclosing expression.
         *
         * Tail position is syntactic, and an operand never has it: the call in
         * `Node {next = go {..}}`, in `(go {..}).field`, or in an argument is
         * followed by the construction, the field read or the call itself, so it
         * cannot be a tail call — only a lambda or `perform` BODY inherits the
         * property from its own tail.
         *
         * The old hand-written field lists got this right by never listing
         * `tailCalls` at all, which is indistinguishable from forgetting it —
         * and converting those lists to `copy` duly broke it: emit compiled a
         * self-tail-loop for the recursive call inside a `Node` payload and a
         * two-element `map` returned `Empty`. A WRONG ANSWER, not an error.
         */
        fun operandOf(sub: ConstraintResult): ConstraintResult =
            if (sub.tailCalls.isEmpty()) sub else sub.copy(tailCalls = emptySet())

        fun mergeOf(
            type: MezzeType,
            constraints: List<Constraint>,
            vararg subs: ConstraintResult,
        ): ConstraintResult = ConstraintResult(
            type = type,
            constraints = constraints,
            effects = union(subs) { it.effects },
            effectInstances = union(subs) { it.effectInstances },
            performed = subs.any { it.performed },
            effectRowVars = union(subs) { it.effectRowVars },
            abilityConstraints = concat(subs) { it.abilityConstraints },
            associatedTypeConstraints = concat(subs) { it.associatedTypeConstraints },
            undefinedVariables = concat(subs) { it.undefinedVariables },
            duplicateBindings = concat(subs) { it.duplicateBindings },
            tailCalls = union(subs) { it.tailCalls },
            usedVariables = union(subs) { it.usedVariables },
            unusedVariables = concat(subs) { it.unusedVariables },
            unusedHandlers = concat(subs) { it.unusedHandlers },
            patternWarnings = concat(subs) { it.patternWarnings },
            referencedBindings = union(subs) { it.referencedBindings },
            methodCallResolutions = overlay(subs) { it.methodCallResolutions },
            callReturnTypes = overlay(subs) { it.callReturnTypes },
            callFunctionTypes = overlay(subs) { it.callFunctionTypes },
            expectedTypeAt = overlay(subs) { it.expectedTypeAt },
            matchCaseBindings = overlay(subs) { it.matchCaseBindings },
            matchScrutineeTypes = overlay(subs) { it.matchScrutineeTypes },
            pendingClosureArgEffects = concat(subs) { it.pendingClosureArgEffects },
            doBindingEffects = concat(subs) { it.doBindingEffects },
            doBindingSolvedTypes = overlay(subs) { it.doBindingSolvedTypes },
            performFullyDischarged = overlay(subs) { it.performFullyDischarged },
            performBodyFnRow = overlay(subs) { it.performBodyFnRow },
            performSiteExpectation = overlay(subs) { it.performSiteExpectation },
            lambdaParamTypes = overlay(subs) { it.lambdaParamTypes },
            lambdaRowInstances = overlay(subs) { it.lambdaRowInstances },
        )

        // Merging is done at every expression, and most fields of most results are
        // empty: a field only one result has is that result's own collection, and
        // one none has is the shared empty one. Building fresh collections for all
        // 26 fields each time was a large share of what checking allocated.

        private inline fun <T> union(subs: Array<out ConstraintResult>, field: (ConstraintResult) -> Set<T>): Set<T> {
            var only: Set<T>? = null
            var many = false
            for (r in subs) {
                val f = field(r)
                if (f.isEmpty()) continue
                if (only == null) only = f else { many = true; break }
            }
            return if (!many) only ?: emptySet() else subs.flatMapTo(LinkedHashSet()) { field(it) }
        }

        private inline fun <T> concat(subs: Array<out ConstraintResult>, field: (ConstraintResult) -> List<T>): List<T> {
            var only: List<T>? = null
            var many = false
            for (r in subs) {
                val f = field(r)
                if (f.isEmpty()) continue
                if (only == null) only = f else { many = true; break }
            }
            return if (!many) only ?: emptyList() else subs.flatMap { field(it) }
        }

        /** Later results' entries over earlier ones', as `fold(+)` gave. */
        private inline fun <K, V> overlay(subs: Array<out ConstraintResult>, field: (ConstraintResult) -> Map<K, V>): Map<K, V> {
            var only: Map<K, V>? = null
            var many = false
            for (r in subs) {
                val f = field(r)
                if (f.isEmpty()) continue
                if (only == null) only = f else { many = true; break }
            }
            if (!many) return only ?: emptyMap()
            val out = LinkedHashMap<K, V>()
            for (r in subs) out.putAll(field(r))
            return out
        }
    }
}

data class ConstraintContext(
    val typingContext: TypingContext,
    val expectedType: MezzeType? = null,
    val allowedEffects: Set<String> = emptySet(),
    val currentFunction: String? = null,
    val performerToEffect: Map<String, String> = emptyMap(),
    val performerAddedEffects: Map<String, Set<String>> = emptyMap(),
    /** Performers declaring `initialize_scope` — a handler-bound function may
     *  not escape a `perform` over one of these (C7). */
    val scopedPerformers: Set<String> = emptySet(),
    val abilityNames: Set<String> = emptySet(),
    val effectNames: Set<String> = emptySet(),
    val effectDeclarations: Map<String, ParsedEffectDecl> = emptyMap(),
    val performContext: ParsedPerform? = null,
    val abilityDeclarations: Map<String, ParsedAbilityDecl> = emptyMap(),
    val recordLiteralBindings: Set<String> = emptySet(),
    val typeDeclarations: Map<String, ParsedTypeDecl> = emptyMap(),
    val currentBindingName: String? = null,

    val enclosingFunctionName: String? = null,
    val inTailPosition: Boolean = false,
    // Empty set means no user-driven preference (#304).
    val annotationAbilityNames: Set<String> = emptySet(),
    /**
     * The ability the SOURCE named at this call site — the `Weigh` in
     * `Weigh.rank { it = x }` — or null when the call is unqualified.
     *
     * Deliberately NOT [annotationAbilityNames], which carries a different claim:
     * the abilities mentioned in an enclosing binding's type annotation, a hint
     * about what the author probably meant. This one is what the author WROTE at
     * the call, so a consumer may treat it as decisive. Conflating them would let
     * a hint decide a dispatch.
     *
     * Set only where the `Ability.op { it = … }` form is desugared to a method
     * call on `it` — after which the receiver is the argument and the qualifier
     * is recoverable from nowhere else.
     */
    val explicitAbilityQualifier: String? = null,
    // #86 case-A shadowing: the enclosing lambda's PARAM names. A do-block `let`
    // that reuses one of these is a same-scope re-bind of a param (hard error),
    // the same class of mistake as a duplicate lambda param (#79). Reset at each
    // lambda boundary (a new function scope opens), so shadowing an OUTER param
    // — from an enclosing lambda — is NOT flagged (case B, a legal nested
    // rebind). Do-blocks track their OWN lets locally (not here) so that a
    // NESTED do-block reusing an outer block's let name stays legal too. Kept
    // separate from typingContext, which is flat and can't tell a param from an
    // outer-scope name.
    val currentScopeBindings: Set<String> = emptySet(),
    /** The member access being generated is a CALL's function (`x.m {…}`). */
    val memberAccessIsCallee: Boolean = false,
    // #392: solved types for the current arm's match-pattern bindings, recorded by
    // generateMatch after a local incremental solve. Consulted ONLY by
    // getReceiverConcreteType for method dispatch — NOT by unification (the
    // extended binding var stays a var so cross-arm unification, e.g. Nat vs an Int
    // literal, remains flexible). Keyed by binding name; a binding whose type is
    // still a VarType is absent (genuinely unresolved).
    val matchBindingConcreteTypes: Map<String, MezzeType> = emptyMap(),
    // #86 sub-gap 2 (docs/codec/redesign-2026-09.md §Open questions Q1):
    // Type-prefixed method call `TypeName.method { it = ... }` is meaningful when
    // the ability's class-param (`FromJson 'a for 'in`) appears only in the return.
    // generateFunctionCall converts `TypeName.method { it = X, ...args }` into a
    // synthetic `X.method { ...args }` and stashes `TypeApplication(TypeName)` here
    // so tryGenerateMethodCall can pin the first typeParam's fresh var to it once
    // the ability resolves. Consumed and cleared inside tryGenerateMethodCall.
    val pinFirstAbilityTypeArg: MezzeType? = null,
    // Names of do-block `let x = <rhs>` bindings whose RHS is a value-producing
    // expression whose type is PINNED BY SOLVING even though it reads as a bare
    // VarType at generation time — a polymorphic call result (`let x = help {..}`,
    // help's `having 'out` resolves to a concrete type only in the global solve),
    // a parenthesized such value, etc. (the same "solvable expression" predicate
    // receiverHasSolvableType applies to inline receivers). Consulted ONLY by
    // receiverHasSolvableType for inherent-method dispatch: such a receiver may
    // defer its impl pick to emit (`x.to_str {}` after `let x = help {..}`),
    // matching the inline `help {..}.to_str {}` behaviour. This is the discriminator
    // between that SOLVABLE let and an UNPINNABLE pattern/destructure-extracted
    // binding of a field/payload from a free-typed param (`let { n } = r`,
    // `match r is { n } -> …`, `Grams { n } -> …`), whose var nothing ever pins and
    // which must instead fall through to the structural receiver-refinement. A
    // binding whose RHS is itself a bare identifier aliasing a free param is NOT in
    // this set (receiverHasSolvableType(rhs) is false there), so it correctly refines.
    val solvableLetBindings: Set<String> = emptySet(),
    /**
     * BUG 55: the ONE open row every unannotated callee called in this lambda
     * body shares. Separate rows per callee lost all but the first — a body
     * carries a single tail — so `compose = { f, g } -> { x } -> g { x = f {…} }`
     * dropped `g`'s effects. Effects union, so a shared row is sound.
     */
    val calleeRowVar: RowVar? = null,
    /**
     * BUG 56: lambdas written DIRECTLY as a call's argument (or a field of its
     * argument record), by location. Their parameters are pinned by that call, so
     * `[1, 2].map { f = { x } -> x.to_str {} }` may defer `to_str`'s impl pick to
     * the solved `x`. A let-bound lambda is generalized and its params are not —
     * that is what [receiverHasSolvableType] keeps excluding.
     */
    val callArgumentLambdaLocs: Set<Loc> = emptySet(),
    /** BUG 56: parameter names pinned by an enclosing call (see above). */
    val callPinnedParams: Set<String> = emptySet(),
) {
    /** BUG 56: mark the lambdas [args] passes directly to a call. */
    fun withCallArguments(args: ParsedExpr): ConstraintContext {
        val locs = when (args) {
            is ParsedLambda -> setOf(args.loc)
            is ParsedRecordLit -> args.entries.mapNotNullTo(HashSet()) { e ->
                ((e as? ParsedRecordEntry.Field)?.value as? ParsedLambda)?.loc
            }
            else -> emptySet()
        }
        return if (locs.isEmpty() && callArgumentLambdaLocs.isEmpty()) this
            else copy(callArgumentLambdaLocs = locs)
    }

    fun extend(name: String, type: MezzeType): ConstraintContext {
        return copy(typingContext = typingContext.extendMono(name, type))
    }

    /** #392: merge solved match-arm binding types into the dispatch side table. */
    fun withMatchBindingConcreteTypes(types: Map<String, MezzeType>): ConstraintContext {
        return copy(matchBindingConcreteTypes = matchBindingConcreteTypes + types)
    }

    /** Record a do-block `let` whose RHS is a solve-pinned value (see field doc). */
    fun withSolvableLetBinding(name: String): ConstraintContext {
        return copy(solvableLetBindings = solvableLetBindings + name)
    }

    fun withExpectedType(type: MezzeType): ConstraintContext {
        return copy(expectedType = type)
    }

    fun clearExpectedType(): ConstraintContext {
        return copy(expectedType = null)
    }

    fun withRecordLiteralBinding(name: String): ConstraintContext {
        return copy(recordLiteralBindings = recordLiteralBindings + name)
    }

    fun inTailPosition(): ConstraintContext {
        return copy(inTailPosition = true)
    }

    fun notInTailPosition(): ConstraintContext {
        return copy(inTailPosition = false)
    }

    /** #86: open a fresh function scope (a lambda body), replacing the enclosing
     *  lambda's params with this lambda's own. A do-block `let` that reuses one
     *  of THESE param names is a same-scope error (case A); reusing an outer
     *  param — now forgotten — is a legal nested rebind (case B). */
    fun withFreshScope(paramNames: Set<String>): ConstraintContext {
        return copy(currentScopeBindings = paramNames)
    }
}

/**
 * Whether two references denote the SAME ability.
 *
 * A NAME is not an identity: two abilities in scope may declare the same name, and
 * matching on it lets one ability's constraint satisfy another's — the shape that
 * made `Weigh.rank` and `Price.rank` share a dictionary slot. Content hashes are
 * compared whenever both sides carry one.
 *
 * The name is consulted only when a hash is missing, which is not a preference but
 * a staging fact: a constraint can be raised before the ability's declaration hash
 * is in reach (a built-in during bootstrap, an annotation converted before the
 * registry is populated). Where BOTH sides carry hashes the name never decides, so
 * the discrimination this exists for applies exactly when it can.
 */
internal fun sameAbility(hash1: Hash?, name1: String, hash2: Hash?, name2: String): Boolean =
    if (hash1 != null && hash2 != null) hash1 == hash2 else name1 == name2
