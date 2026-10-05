package com.mezze.language.typechecker.constraints

import com.mezze.language.parsedast.diagnostics.DiagnosticHints
import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typedast.Hash

/**
 * Task A.1 — one impl operation's body solve: the ConstraintResult its
 * constraint generation produced and the Substitution that solved it.
 *
 * Public because [ProgramConstraintResult] carries it out to TypeChecker, while
 * [ImplValidator] itself is internal.
 */
data class ImplOpSolution(
    val result: ConstraintResult,
    val substitution: Substitution,
)

/** The one variable every impl-target variable is collapsed to when deciding
 *  whether two impls name the same target. See [ImplValidator.canonicalTargetKey]. */
private val CANONICAL_TARGET_VAR = MezzeType.VarType(TypeVar("'_target", 0, -1))

internal class ImplValidator(
    private val expressionGenerator: ExpressionConstraintGenerator,
    private val registry: DeclarationRegistry,
    private val performerToEffect: Map<String, String>,
    private val performerAddedEffects: MutableMap<String, Set<String>>,
    private val abilityNames: Set<String>,
    private val effectNames: Set<String>,
    private val convertTypeAnnotation: (ParsedTypeAnnotation) -> MezzeType,
) {
    /**
     * asymmetry fix: ability preconditions inferred from impl-operation BODIES,
     * keyed by the impl's content hash. `validateAbilityImplementation` fills
     * this while checking each impl; the caller (via [drainInferredImplConstraints])
     * folds them into the frozen context's `AbilityImplInfo.operationConstraints`
     * before the final use-site solve. An impl op is just a function: its body's
     * residual ability obligations on the impl's type params become the impl's
     * preconditions, exactly as a top-level fn records `with Ord 'a` in its sig.
     */
    private val inferredImplConstraints = mutableMapOf<Hash, MutableList<ParsedFnAbilityConstraint>>()

    fun drainInferredImplConstraints(): Map<Hash, List<ParsedFnAbilityConstraint>> {
        val snapshot = inferredImplConstraints.mapValues { it.value.toList() }
        inferredImplConstraints.clear()
        return snapshot
    }

    /**
     * Task A.1 — the impl-op body solve, kept instead of discarded.
     *
     * [validateImplOperationBody] already runs a FULL solve of every impl
     * operation: it generates the body's constraints, solves them, and checks the
     * result against the declared return type. Then it throws both the
     * ConstraintResult and the Substitution away, and emit later rebuilds those
     * bodies from nothing — which is why 566 of the 983 elaboration gaps task 0.2
     * measured sit in impl operations, and why `TypedAstBuilder` has to type
     * match-bound names structurally there ("the solver never produced entries for
     * these bodies").
     *
     * The work was always being done. This is the channel that keeps it.
     */
    private val implOpSolutions = mutableListOf<ImplOpSolution>()

    fun drainImplOpSolutions(): List<ImplOpSolution> {
        val snapshot = implOpSolutions.toList()
        implOpSolutions.clear()
        return snapshot
    }

    /**
     * An impl target with every type VARIABLE collapsed to one canonical variable,
     * so two writings of the same target compare equal.
     *
     * Each `convertTypeAnnotation` mints fresh variables, and `TypeVar` carries an
     * id in its equality — so `impl Eq for ['a]` and `impl Hash for ['a]` convert to
     * `[ 'a#26948 ]` and `[ 'a#27001 ]`, which are different types. What
     * makes them the same TARGET is the shape, not which variable filled the hole.
     */
    /**
     * What to call an impl's receiver in a coherence error, or null when the
     * coherence rule does not apply to it at all.
     *
     * It applies to exactly two kinds of receiver. A DECLARED nominal type — the
     * compiler derives its `Eq`/`Inspect` structurally and owns that instance. And
     * a PRIMITIVE, which has no declaration to carry an identity and so used to
     * escape the rule entirely: `impl Eq for Int` was accepted and then never
     * reached, because a primitive receiver takes the inline comparison node before
     * ability resolution runs. Silently-unreachable code is worse than a duplicate,
     * so a primitive is named by the only name it has and held to the same rule.
     *
     * For a primitive the ability matters, because SATISFYING an ability and having
     * an instance DERIVED are not the same claim, and only the second makes a
     * hand-written impl a duplicate. `Bool` satisfies `LogicalAnd` — `&&` is
     * answered by an inline node — but nothing generates an instance, so
     * `impl LogicalAnd for Bool` is the only one there is and has to be allowed.
     * `Eq` and `Hash` are the two the compiler actually writes for a primitive.
     *
     * Everything else — a builtin shape like `['a]`, an anonymous record — is not a
     * derived instance's territory and is left alone.
     */
    private fun coherenceReceiverName(receiver: MezzeType, abilityHash: Hash?): String? {
        if (PrimitiveTypes.isPrimitiveType(receiver)) {
            val generated = WellKnownHashConstants.isEq(abilityHash) ||
                WellKnownHashConstants.isHash(abilityHash)
            return if (generated) declaredTypeName(receiver) else null
        }
        return declaredTypeName(receiver)
    }

    /**
     * What to CALL a type in a diagnostic: the name it was declared under.
     *
     * A declared receiver arrives in one of two shapes (the two-representations
     * landmine): alias-expanded `type P = {..}` / `type V = | .. |` is a RecordType
     * or VariantType carrying the declaration's identity, while `Option 'a` and a
     * newtype are a TypeApplication — so the identity is read off whichever shape
     * came, never off a constructor name. A primitive has no declaration at all and
     * answers with the only name it has; without that it rendered as its Kotlin
     * `toString`, and an error message read `MezzeType$IntType@73d6d0c`.
     *
     * Null for anything with no name of its own — an anonymous record, a bare shape.
     */
    private fun declaredTypeName(type: MezzeType): String? =
        PrimitiveTypes.toName(type)
            ?: (((type as? MezzeType.RecordType)?.identity
                ?: (type as? MezzeType.VariantType)?.identity
                ?: (type as? MezzeType.TypeApplication)?.identity)
                as? TypeIdentity.Declared)?.displayName

    private fun canonicalTargetKey(type: MezzeType): MezzeType {
        val vars = com.mezze.language.ast.solving.collectFreeTypeVars(type)
        if (vars.isEmpty()) return type
        return Substitution(vars.associateWith { CANONICAL_TARGET_VAR }).apply(type)
    }

    /**
     * `Eq` and `Hash` are ONE decision: a module that writes one for a type must
     * write the other.
     *
     * Every hash container buckets by `hash` and then compares by `eq`. If the two
     * disagree, two values that ARE equal land in different buckets, the equality
     * is never consulted, and the container silently keeps both. So the dangerous
     * combination is not a missing ability — it is a CUSTOM one paired with a
     * DERIVED one, in either direction:
     *
     *   - a hand-written `Eq` with the derived structural hash, and
     *   - a hand-written `Hash` with the derived structural equality.
     *
     * The second is rejected by the coherence rule above (which asks about `Eq`
     * when the impl is a `Hash`). This is the first: `Hash` derives for every type,
     * so nothing else would ever notice its absence.
     *
     * Checked per MODULE and matched on the impl's target ANNOTATION text, which is
     * what a reader sees as "the same type" — an impl for a type declared elsewhere
     * still has to be written next to its partner, because coherence already
     * confines both to the type's own module.
     */
    fun validateEqHashPairing(decls: List<ParsedDecl>): List<TypeCheckError> {
        // Keyed on the converted TYPES, never on their rendering: `mezzeTypeToString`
        // is lossy and two different types can print alike, which would silently pair
        // an `Eq` with someone else's `Hash`. Type VARIABLES are canonicalised first —
        // each conversion of `['a]` mints a fresh variable with its own id, so the raw
        // types of `impl Eq for ['a]` and `impl Hash for ['a]` are never equal.
        val eqTargets = LinkedHashMap<List<MezzeType>, ParsedImplAbilityDecl>()
        val hashTargets = HashSet<List<MezzeType>>()
        for (decl in decls) {
            if (decl !is ParsedImplAbilityDecl || decl.isInherent || decl.derived) continue
            val name = (decl.target as? ParsedImplTarget.Named)?.ability?.localName ?: continue
            val abilityHash = registry.resolveAbilityHash(name)
            val isEq = WellKnownHashConstants.isEq(abilityHash)
            val isHash = WellKnownHashConstants.isHash(abilityHash)
            if (!isEq && !isHash) continue
            val key = decl.targetTypes.map { canonicalTargetKey(convertTypeAnnotation(it)) }
            if (isEq) eqTargets.putIfAbsent(key, decl) else hashTargets.add(key)
        }
        return eqTargets.filterKeys { it !in hashTargets }.map { (key, decl) ->
            val shown = key.joinToString(" ") { t -> declaredTypeName(t) ?: t.toString() }
            TypeCheckError(
                "`$shown` writes its own `Eq` but not a `Hash`. They are one decision: a hash " +
                    "container buckets by `hash` and compares by `eq`, so a custom equality left " +
                    "with the derived structural hash means two values that ARE equal hash apart, " +
                    "never meet, and are both kept. Add an `impl Hash for $shown` whose `hash` " +
                    "agrees with this `Eq`.",
                decl.loc,
                code = ErrorCode.V0401_ABILITY_NOT_DEFINED,
            )
        }
    }

    fun validateAbilityImplementation(impl: ParsedImplAbilityDecl, context: TypingContext) {
        if (impl.isInherent) return

        val abilityRef = (impl.target as ParsedImplTarget.Named).ability
        val abilityName = abilityRef.localName
        val abilityQualifier = abilityRef.qualifier
        val qualifiedAbilityName = abilityQualifier?.let { "${it.segments.joinToString("::")}::$abilityName" } ?: abilityName
        val abilityDeclaration = registry.abilityDeclarations[abilityName]
            ?: registry.abilityDeclarations[qualifiedAbilityName]
            ?: throw TypeCheckError(
                "Ability '$qualifiedAbilityName' is not defined or not imported. " +
                "Did you forget to import it with 'use'?",
                impl.loc,
                code = ErrorCode.V0401_ABILITY_NOT_DEFINED,
            )

        // Include EVERY ability type arg (concrete OR tyvar) — tyvar args
        // are forall introductions that fill their slot with a fresh unknown.
        // Previously this dropped tyvar args, causing `impl Okable 'e for 'a`
        // (Okable has 2 typeParams `'e, 'a`) to zip against only 1 arg
        // (the target), silently losing the `'e` slot binding.
        val allTypeArgs: List<ParsedTypeAnnotation> = impl.abilityTypeArgs + impl.targetTypes

        val paramSubstitution = abilityDeclaration.typeParams.zip(allTypeArgs).associate { (param, targetType) ->
            param to convertTypeAnnotation(targetType)
        }

        // Coherence: the compiler DERIVES Eq/Inspect for records and variants, and
        // Inspect + Hash for EVERY type INCLUDING a newtype — and it OWNS those
        // instances. A hand-written impl for a derived instance is a SECOND instance
        // for the same type: a duplicate, never an override. This is NOT special to
        // records/variants — a newtype derives Inspect (and Hash), so
        // `impl Inspect for MyNewtype` is just as much a duplicate; the newtype was
        // WRONGLY exempted here before, which let a value override its own debug
        // rendering. What a newtype does NOT derive is Eq/Ord, so a hand-written
        // Eq/Ord/Hash on a newtype is its ONE legitimate instance — and that is
        // decided uniformly below by `canDeriveAbility` (false for Eq/Ord on a
        // newtype), not by a blanket exemption. Only a declared nominal receiver (or
        // a primitive) is a candidate — builtin shapes (`['a]`, `Bool`) aren't
        // derived this way. Derivability is the SAME structural predicate the solver
        // uses, keyed on the ability's hash. A declared receiver arrives in one of
        // TWO shapes (the two-representations landmine): alias-expanded
        // `type P = {..}` / `type V = | ..` is a RecordType / VariantType carrying
        // the declaration's identity, while `Option 'a` / a newtype is a
        // TypeApplication — read the identity off whichever shape came, never the
        // constructor name.
        run {
            // The compiler-generated derived instance IS the one instance — never a
            // duplicate of itself (DerivedImplGenerator).
            if (impl.derived) return@run
            val receiverAnn = impl.targetTypes.lastOrNull() ?: return@run
            val receiver = convertTypeAnnotation(receiverAnn)
            val abilityHash = registry.resolveAbilityHash(abilityName)
            val displayName = coherenceReceiverName(receiver, abilityHash) ?: return@run
            // `Hash` is derived for EVERY type, so "is it already derived?" is
            // always yes and would forbid every hand-written Hash — including the
            // ones the Eq/Hash pairing rule REQUIRES. The question that actually
            // matters for Hash is whether the type's EQUALITY is the derived one:
            // a custom hash paired with a structural equality breaks
            // `a == b ⇒ hash a == hash b` just as surely as the reverse. So ask
            // about Eq, and the two abilities stay written together or not at all.
            val coherenceAbility =
                if (WellKnownHashConstants.isHash(abilityHash)) "Eq" else abilityName
            val coherenceHash = registry.resolveAbilityHash(coherenceAbility)
            val derives = com.mezze.language.typechecker.solving.AbilityChecker(context, registry)
                .canDeriveAbility(coherenceAbility, receiver, coherenceHash)
            if (derives) {
                throw TypeCheckError(
                    if (coherenceAbility == abilityName)
                        "Duplicate ability impl: `$abilityName` is derived by the compiler for " +
                        "`$displayName`, so it is " +
                        "already implemented — a hand-written impl is a second instance for the same type."
                    else
                        "`Hash` cannot be hand-written for `$displayName`: its `Eq` is the " +
                        "compiler-derived structural one, and a custom hash paired with a structural " +
                        "equality breaks the invariant every hash container relies on — two values that " +
                        "compare equal would hash apart and both be kept. Write `Eq` and `Hash` together, " +
                        "or neither.",
                    impl.loc,
                    code = ErrorCode.V0407_OVERLAPPING_INSTANCE,
                )
            }
        }

        // Associated-type bindings (`having 'out = Str`) must substitute into the op
        // signatures too — otherwise a declared return type of `'out` stays an
        // unconstrained var and the body's actual type unifies against it freely
        // (HOLE B: `produce = { it } -> it` returning Int under `having 'out = Str`
        // type-checked, then `(5).produce{}` typed Str yielded a Long →
        // ClassCastException). The effect-impl path already does this; mirror it.
        // Keys are stored under both the `'`-prefixed and bare name because
        // substituteTypeParams matches type vars by name.
        val associatedTypeSubstitution = impl.associatedTypeBindings.mapKeys { (key, _) ->
            if (key.startsWith("'")) key else "'$key"
        }.mapValues { (_, typeAnnotation) ->
            convertTypeAnnotation(typeAnnotation)
        }
        val typeParamSubstitution = paramSubstitution +
            associatedTypeSubstitution +
            associatedTypeSubstitution.mapKeys { (k, _) -> k.removePrefix("'") }

        // asymmetry fix: harvest body-inferred ability preconditions on the impl's
        // own type params. Only for a generic impl (has forallVars) with a content
        // hash to key the accumulation by.
        val implForallVars = impl.forallVars.toSet()
        val inferredSink: MutableList<ParsedFnAbilityConstraint>? =
            if (implForallVars.isNotEmpty() && impl.contentHash != null)
                inferredImplConstraints.getOrPut(impl.contentHash!!) { mutableListOf() }
            else null

        for (implOp in impl.operations) {
            val declOp = abilityDeclaration.operations.find { it.name == implOp.name }
                ?: continue

            // #91: the ability op's declared types come from the ABILITY's
            // module. A named type there (e.g. `config: Config`) may not be
            // imported by name in THIS impl module, so the impl converter can't
            // stamp its content hash — leaving a bare TypeApplication that a
            // later HasField constraint (`config.name`) can't resolve without
            // the import. Stamp hashes from the global type set so the type
            // resolves by hash, keeping it closed/nominal without forcing an
            // import the impl never textually needs.
            val expectedParamType = stampTypeHashes(substituteTypeParams(convertTypeAnnotation(declOp.paramType), typeParamSubstitution))
            val expectedReturnType = stampTypeHashes(substituteTypeParams(convertTypeAnnotation(declOp.returnType), typeParamSubstitution))

            if (implOp.implementation is ParsedLambda) {
                validateImplOperationBody(
                    lambda = implOp.implementation as ParsedLambda,
                    opName = implOp.name,
                    opLoc = implOp.loc,
                    expectedParamType = expectedParamType,
                    expectedReturnType = expectedReturnType,
                    baseContext = context,
                    implLabel = "ability '$abilityName'",
                    validationReason = "Ability body validation",
                    implForallVars = implForallVars,
                    inferredSink = inferredSink,
                    onBodyResult = { bodyResult, _ ->
                        validateImplOpEffectContract(
                            declOp = declOp,
                            bodyEffects = bodyResult.effects,
                            abilityName = abilityName,
                            opName = implOp.name,
                            loc = implOp.loc,
                        )
                    },
                    onSolveFailure = { solveResult ->
                        // A field access whose RECEIVER is not a record at all. The only
                        // shape this catches (measured across both suites: exactly one,
                        // `Str.length`) is an INHERENT method read as a field — inherent
                        // dispatch has not run when an impl body is solved in isolation,
                        // so it is not an error here and the use site checks it.
                        //
                        // Keyed on the constraint's shape. This used to ask the MESSAGE
                        // whether it contained "Cannot access field" and "non-record
                        // type", so rewording that one diagnostic would have turned a
                        // swallowed non-error into a reported one — silently rejecting a
                        // program that compiles today.
                        //
                        // A record that is simply MISSING the field carries the same
                        // T0205 code, and must still be reported: hence the extra
                        // structural test rather than a code check alone.
                        val allNonRecordReceiverFieldErrors = solveResult.errors.all { err ->
                            val c = err.constraint
                            err.code == ErrorCode.T0205_FIELD_NOT_FOUND &&
                                c is Constraint.HasField &&
                                c.recordType !is MezzeType.RecordType
                        }
                        if (!allNonRecordReceiverFieldErrors) {
                            throw TypeCheckError(
                                "Type error in ability '${abilityName}' operation '${implOp.name}' implementation: ${solveResult.errors.firstOrNull()?.message ?: "Unknown error"}",
                                implOp.loc,
                                code = ErrorCode.T0201_TYPE_MISMATCH,
                            )
                        }
                        false
                    },
                )
            }
        }
    }

    /**
     * Enforce the ability operation's effect contract on an impl body.
     *
     * - Pure (no effect row): body must produce no effects.
     * - Closed concrete `<E1, E2>` (not polymorphic): body effects must be a
     *   subset of the declared set.
     * - Concrete + open tail `<E, ...>` (polymorphic with named effects): body
     *   must include every declared effect, but may add others.
     * - Fully polymorphic `<...>` (polymorphic, no named effects): anything goes.
     */
    private fun validateImplOpEffectContract(
        declOp: ParsedAbilityOp,
        bodyEffects: Set<String>,
        abilityName: String,
        opName: String,
        loc: Loc,
    ) {
        val declEffects = declOp.effects

        if (declEffects == null) {
            // Pure: body must not introduce effects.
            if (bodyEffects.isNotEmpty()) {
                throw TypeCheckError(
                    "Operation '$opName' in ability '$abilityName' is declared pure (no effect annotation), " +
                        "but the impl body uses effects: ${bodyEffects.sorted().joinToString(", ")}. " +
                        "Either remove the effectful calls from the impl body, or add an effect annotation " +
                        "to the ability operation.",
                    loc,
                    code = ErrorCode.V0421_IMPL_EFFECT_MISMATCH,
                )
            }
            return
        }

        if (declEffects.isPolymorphic && declEffects.allEffectNames.isEmpty()) {
            // Fully polymorphic `<...>`: any effects allowed.
            return
        }

        val declaredNames = declEffects.allEffectNames

        if (declEffects.isPolymorphic) {
            // Concrete + open tail `<E, ...>`: body must include the declared effects.
            val missing = declaredNames - bodyEffects
            if (missing.isNotEmpty()) {
                throw TypeCheckError(
                    "Operation '$opName' in ability '$abilityName' declares effects " +
                        "<${declaredNames.sorted().joinToString(", ")}, ...>, " +
                        "but the impl body is missing: ${missing.sorted().joinToString(", ")}. " +
                        "The impl must at least use the declared effects.",
                    loc,
                    code = ErrorCode.V0421_IMPL_EFFECT_MISMATCH,
                )
            }
        } else {
            // Closed concrete `<E1, E2>`: body effects must be a subset.
            val extra = bodyEffects - declaredNames
            if (extra.isNotEmpty()) {
                throw TypeCheckError(
                    "Operation '$opName' in ability '$abilityName' declares effects " +
                        "<${declaredNames.sorted().joinToString(", ")}>, " +
                        "but the impl body also uses: ${extra.sorted().joinToString(", ")}. " +
                        "Either add the extra effects to the ability operation's annotation, " +
                        "or handle them within the impl body.",
                    loc,
                    code = ErrorCode.V0421_IMPL_EFFECT_MISMATCH,
                )
            }
        }
    }

    /**
     * Find, for each of the impl's declared type params, the solver [TypeVar] that
     * represents it inside the op's expected param type (e.g. the `'a` in
     * `{ it: Wrap 'a, r: Wrap 'a }`). Keyed by the param's surface name.
     */
    private fun collectParamVarsByName(
        paramType: MezzeType,
        forallVars: Set<String>
    ): Map<String, TypeVar> {
        val out = mutableMapOf<String, TypeVar>()
        fun walk(t: MezzeType) {
            if (t is MezzeType.VarType) {
                val n = t.variable.name.removePrefix("'")
                if (forallVars.any { it.removePrefix("'") == n } && n !in out) out[n] = t.variable
            }
            t.transformChildren { walk(it); it }
        }
        walk(paramType)
        return out
    }

    private fun validateImplOperationBody(
        lambda: ParsedLambda,
        opName: String,
        opLoc: Loc,
        expectedParamType: MezzeType,
        expectedReturnType: MezzeType,
        baseContext: TypingContext,
        implLabel: String,
        validationReason: String,
        recordLiteralBindings: Set<String> = emptySet(),
        prepareContext: ((TypingContext) -> TypingContext)? = null,
        onBodyResult: ((ConstraintResult, TypingContext) -> Unit)? = null,
        onUndefinedVars: ((ConstraintResult) -> Boolean)? = null,
        onSolveFailure: ((ConstraintSolver.SolveResult.Failure) -> Boolean)? = null,
        // asymmetry fix: the impl's declared type params (tick-prefixed). When set,
        // ability obligations the body places on one of these params are harvested
        // into `inferredSink` as `with <Ability> 'param` — an impl op is just a
        // function, so its body's residual constraints become the impl's
        // preconditions (like a top-level fn records `with Ord 'a` in its sig).
        implForallVars: Set<String> = emptySet(),
        inferredSink: MutableList<ParsedFnAbilityConstraint>? = null,
    ) {
        val paramNames = when (val params = lambda.params) {
            is ParsedLambdaParams.Untyped -> params.names
            is ParsedLambdaParams.Typed -> params.fields.map { it.name }
        }

        val paramTypes = if (expectedParamType is MezzeType.RecordType) {
            paramNames.associateWith { name ->
                expectedParamType.fields[name] ?: MezzeType.VarType(TypeVar.fresh())
            }
        } else {
            paramNames.associateWith { expectedParamType }
        }

        var bodyContext = baseContext
        for ((name, type) in paramTypes) {
            bodyContext = bodyContext.extendMono(name, type)
        }

        if (prepareContext != null) {
            bodyContext = prepareContext(bodyContext)
        }

        val constraintContext = ConstraintContext(
            bodyContext,
            // Stays null, and S-1b is the reason to leave it that way rather than an
            // oversight. Pushing `expectedReturnType` down here as the body's
            // expected type LOOKS like the fix for a return-type-dispatched op and
            // is not: measured, it changes nothing that the return-type equality in
            // the body solve (below) does not already achieve. It was tried, it made
            // the variant case pass on its own, and once the equality was added
            // every case passed without it. One change, one reason.
            expectedType = null,
            performerToEffect = performerToEffect,
            performerAddedEffects = performerAddedEffects,
            abilityNames = abilityNames,
            effectNames = effectNames,
            effectDeclarations = registry.effectDeclarations,
            abilityDeclarations = registry.abilityDeclarations,
            typeDeclarations = registry.typeDeclarations,
            recordLiteralBindings = recordLiteralBindings
        )

        val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

        onBodyResult?.invoke(bodyResult, bodyContext)

        if (onUndefinedVars != null) {
            if (onUndefinedVars(bodyResult)) return
        } else {
            val realUndefined = bodyResult.undefinedVariables.filter { !it.name.startsWith(com.mezze.language.ast.MagicParams.NATIVE_PREFIX) }
            if (realUndefined.isNotEmpty()) {
                val undefinedVar = realUndefined.first()
                throw TypeCheckError(
                    "Type error in $implLabel operation '$opName' implementation: " +
                    "Undefined variable '${undefinedVar.name}'",
                    undefinedVar.loc,
                    code = ErrorCode.V0702_UNDEFINED_VARIABLE,
                )
            }
        }

        // deferExistingAbilityImpls: an operator body (`+`, `<`, …) types its
        // result as a fresh associated-type `'out` var, resolved via an
        // AssociatedTypeResolution against the operand's ability impl. Feeding the
        // body's ability + associated-type constraints into THIS isolated solve is
        // what pins `'out` to a concrete type before the return-type check —
        // otherwise `'out` stays a free var and unifies with the declared concrete
        // return vacuously, silently accepting a lying body (the soundness hole:
        // `impl Eq for Foo where { eq = {it,r} -> it.v + r.v }` — body Int, decl
        // Bool). The top-level binding path (collectGlobalConstraints) already folds
        // these in for the same reason; an impl op is just a function.
        //
        // The flag makes a merely-not-import-visible ability obligation defer to the
        // top-level solve rather than reject here (e.g. `(it.compare {r}) < 0` over
        // Int when the Int impl exists but isn't `use`d at this site). A
        // genuinely-absent impl (`NumAdd Str`) still fails, leaving `'out`
        // unresolved so the return check rejects — which is exactly the soundness
        // guarantee we want.
        // S-1b: the declared return type is a constraint on THIS solve, not only a
        // check after it.
        //
        // It was used solely in the separate `unificationSolver` below, which runs
        // after ability resolution has already had to commit. For an op that
        // dispatches on its RETURN type (`make: {} -> 'a`, `arbitrary: {} -> 'a`)
        // that is too late: the body's fresh ability var had nothing tying it to a
        // concrete type, so `impl Maker for Point where { make = {} -> { x =
        // Maker.make {}, … } }` was rejected as undeterminable — while the identical
        // `let p: Point = { x = Maker.make {} }` checked fine, because a `let`'s
        // annotation equality lands in the SAME solve as its body.
        //
        // Additive: the post-hoc check below is untouched and still the backstop
        // that catches a lying body.
        val solver = ConstraintSolver(bodyContext, registry, deferExistingAbilityImpls = true)
        val solveResult = solver.solve(
            bodyResult.constraints + bodyResult.abilityConstraints + bodyResult.associatedTypeConstraints +
                Constraint.Equality(bodyResult.type, expectedReturnType, validationReason, loc = opLoc)
        )

        when (solveResult) {
            is ConstraintSolver.SolveResult.Failure -> {
                if (onSolveFailure != null) {
                    if (onSolveFailure(solveResult)) return
                } else {
                    throw TypeCheckError(
                        "Type error in $implLabel operation '$opName' implementation: ${solveResult.errors.firstOrNull()?.message ?: "Unknown error"}",
                        opLoc,
                        code = ErrorCode.T0201_TYPE_MISMATCH,
                    )
                }
            }
            is ConstraintSolver.SolveResult.Success -> {
                val substitution = solveResult.substitution
                // A.1: keep this solve. Its side tables are keyed by the body's own
                // source locations, which no other node shares (measured — see
                // LocKeyCollisionMeasurementTest), and its TypeVars carry globally
                // unique ids, so merging into the module's tables cannot collide
                // with another declaration's entries.
                implOpSolutions.add(ImplOpSolution(bodyResult, substitution))
                val actualBodyType = substitution.apply(bodyResult.type)

                val unificationSolver = ConstraintSolver(bodyContext, registry)
                val unificationResult = unificationSolver.solve(
                    listOf(Constraint.Equality(actualBodyType, expectedReturnType, validationReason, loc = opLoc))
                )

                if (unificationResult is ConstraintSolver.SolveResult.Failure) {
                    val actualTypeStr = prettyPrintType(actualBodyType)
                    val expectedTypeStr = prettyPrintType(expectedReturnType)
                    throw TypeCheckError(
                        "Operation '$opName' in $implLabel returns wrong type: " +
                        "expected $expectedTypeStr, but body returns $actualTypeStr.",
                        opLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf(DiagnosticHints.ANNOTATION_VS_BODY),
                    )
                }

                // Harvest body-inferred ability preconditions on the impl's type
                // params (the asymmetry fix). The body's `it.v < r.v` produces an
                // `Ord <var>` obligation; if <var> resolves to one of the impl's
                // declared type params, record `with Ord 'param` so the impl is
                // only applicable when that param satisfies the ability.
                if (inferredSink != null && implForallVars.isNotEmpty()) {
                    // Map each of the impl's declared type params to its
                    // solver var AS IT APPEARS in the op's param type, then to that
                    // var's post-solve REPRESENTATIVE. A residual `Ord <var>` counts
                    // as `with Ord 'param` when <var>'s representative equals the
                    // param's representative — matching by unification-equivalence,
                    // not by name (the body's field-access introduces a fresh var
                    // that unifies with `'a` but keeps a solver-internal name).
                    val paramVarsByName = collectParamVarsByName(expectedParamType, implForallVars)
                    val repToParamName = paramVarsByName.mapNotNull { (paramName, v) ->
                        val rep = substitution.apply(MezzeType.VarType(v))
                        if (rep is MezzeType.VarType) rep.variable.id to paramName else null
                    }.toMap()
                    for (ab in bodyResult.abilityConstraints) {
                        val rep = substitution.apply(MezzeType.VarType(ab.typeVar))
                        val paramName = (rep as? MezzeType.VarType)?.let { repToParamName[it.variable.id] }
                            ?: continue
                        inferredSink.add(ParsedFnAbilityConstraint(
                            abilityName = ab.abilityName,
                            typeArgs = listOf(ParsedNamedType("'${paramName.removePrefix("'")}", ab.loc ?: opLoc)),
                            loc = ab.loc ?: opLoc
                        ))
                    }
                }
            }
        }
    }

    fun validateEffectImplementation(impl: ParsedPerformerDecl, context: TypingContext) {
        val effectRef = impl.effect
        val effectQualifier = effectRef.qualifier
        val effectLookupKey = if (effectQualifier != null) {
            "${effectQualifier.segments.last()}::${effectRef.localName}"
        } else {
            effectRef.localName
        }
        val effectDeclaration = registry.effectDeclarations[effectLookupKey]
            ?: registry.effectDeclarations[effectRef.localName]
            ?: throw TypeCheckError(
                "Effect '${effectLookupKey}' is not defined or not imported. " +
                "Did you forget to import it with 'use'?",
                impl.loc,
                code = ErrorCode.V0505_EFFECT_NOT_DEFINED,
                hints = listOf(
                    "declare `effect ${effectRef.localName} { ... }` or import it with `use module::${effectRef.localName}`"
                ),
            )

        val associatedTypeSubstitution = impl.associatedTypeBindings.mapKeys { (key, _) ->
            if (key.startsWith("'")) key else "'$key"
        }.mapValues { (_, typeAnnotation) ->
            convertTypeAnnotation(typeAnnotation)
        }

        val fullSubstitution = associatedTypeSubstitution + associatedTypeSubstitution.mapKeys { (k, _) ->
            k.removePrefix("'")
        }

        // Converts one parameter at a time, and `convertTypeAnnotation` clears the
        // type-variable cache per call — which LOOKS like the "one written `'a`
        // became two" bug that was fixed in `ContextBuilder` and `TypedAstBuilder`
        // for these same params. MEASURED, and it is not: `impl Store for Mem
        // { a: 'a, b: 'a }` with `perform Mem { a = 1, b = "s" }` is rejected
        // ("Field 'b' type mismatch: expected Int, got Str") BOTH with a shared
        // scope here and without it, because the tie is already established on the
        // context-building path. Converting these together changed nothing — the
        // clobber count over the prelude stayed at exactly 250, same shapes.
        // Don't "fix" this site without a case that shows a difference first.
        var baseContext = context
        for (param in impl.params) {
            val paramType = convertTypeAnnotation(param.type)
            baseContext = baseContext.extendMono(param.name, paramType)
        }

        var initializeScope: ParsedPerformerOp? = null
        var finalizeScope: ParsedPerformerOp? = null
        for (operation in impl.operations) {
            when (operation.name) {
                ScopedEffectConstants.INITIALIZE_SCOPE -> initializeScope = operation
                ScopedEffectConstants.FINALIZE_SCOPE -> finalizeScope = operation
            }
        }
        val isScopedEffect = initializeScope != null
        val hasFinalizer = finalizeScope != null

        if (isScopedEffect && !hasFinalizer) {
            throw TypeCheckError(
                "initialize_scope requires finalize_scope for resource safety. " +
                "Add 'finalize_scope = {} -> {}' to clean up resources when the perform block exits.",
                initializeScope!!.loc,
                code = ErrorCode.V0506_SCOPED_EFFECT_INIT_FINALIZE_REQUIRED,
                hints = listOf("scoped effects need both `initialize_scope` and `finalize_scope` operations"),
            )
        }
        if (hasFinalizer && !isScopedEffect) {
            throw TypeCheckError(
                "finalize_scope requires initialize_scope. " +
                "Add initialize_scope to create the scope that finalize_scope cleans up.",
                finalizeScope!!.loc,
                code = ErrorCode.V0506_SCOPED_EFFECT_INIT_FINALIZE_REQUIRED,
                hints = listOf("scoped effects need both `initialize_scope` and `finalize_scope` operations"),
            )
        }

        if (isScopedEffect) {
            initializeScope!!.returnType?.let { retType ->
                if (retType !is ParsedRecordType) {
                    throw TypeCheckError(
                        "initialize_scope must return a record type like { field1 = value1, ... }. " +
                        "The returned record becomes the 'scope' variable available in operations.",
                        initializeScope.loc,
                        code = ErrorCode.T0201_TYPE_MISMATCH,
                    )
                }
            }
            finalizeScope?.returnType?.let { retType ->
                if (retType !is ParsedRecordType || (retType as ParsedRecordType).entries.isNotEmpty()) {
                    throw TypeCheckError(
                        "finalize_scope must return {} (unit). " +
                        "It's for cleanup side effects only, not for returning values.",
                        finalizeScope.loc,
                        code = ErrorCode.T0201_TYPE_MISMATCH,
                    )
                }
            }
        }

        var inferredScopeType: MezzeType? = null

        initializeScope?.let { initOp ->
            if (initOp.implementation is ParsedLambda) {
                val lambda = initOp.implementation as ParsedLambda

                val initContext = baseContext

                val constraintContext = ConstraintContext(
                    initContext,
                    expectedType = null,
                    performerToEffect = performerToEffect,
                    performerAddedEffects = performerAddedEffects,
                    abilityNames = abilityNames,
                    effectNames = effectNames,
                    effectDeclarations = registry.effectDeclarations,
                    abilityDeclarations = registry.abilityDeclarations,
                    typeDeclarations = registry.typeDeclarations
                )

                val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

                val scopeUndefined = bodyResult.undefinedVariables.find { it.name == "scope" }
                if (scopeUndefined != null) {
                    throw TypeCheckError(
                        "The 'scope' variable is not available in initialize_scope. " +
                        "The scope is being created here and will only be available in operations and finalize_scope.",
                        scopeUndefined.loc,
                        code = ErrorCode.V0702_UNDEFINED_VARIABLE,
                    )
                }

                inferredScopeType = bodyResult.type

                val initEffects = bodyResult.effects - effectRef.localName
                if (initEffects.isNotEmpty()) {
                    val existingEffects = performerAddedEffects[impl.performerName] ?: emptySet()
                    performerAddedEffects[impl.performerName] = existingEffects + initEffects
                }
            }
        }

        val scopeType: MezzeType? = inferredScopeType ?: if (isScopedEffect) {
            initializeScope?.let { initOp ->
                val returnType = initOp.returnType
                if (returnType is ParsedRecordType) {
                    convertTypeAnnotation(returnType)
                } else {
                    null
                }
            }
        } else {
            null
        }

        val contextWithScope = if (scopeType != null) {
            baseContext.extendMono("scope", scopeType)
        } else {
            baseContext
        }

        finalizeScope?.let { finalOp ->
            if (finalOp.implementation is ParsedLambda) {
                val lambda = finalOp.implementation as ParsedLambda

                val finalizeContext = contextWithScope

                val scopeRecordBindings = if (scopeType != null) setOf("scope") else emptySet()

                val constraintContext = ConstraintContext(
                    finalizeContext,
                    expectedType = null,
                    performerToEffect = performerToEffect,
                    performerAddedEffects = performerAddedEffects,
                    abilityNames = abilityNames,
                    effectNames = effectNames,
                    effectDeclarations = registry.effectDeclarations,
                    abilityDeclarations = registry.abilityDeclarations,
                    typeDeclarations = registry.typeDeclarations,
                    recordLiteralBindings = scopeRecordBindings
                )

                val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

                val finalizeEffects = bodyResult.effects - effectRef.localName
                if (finalizeEffects.isNotEmpty()) {
                    val existingEffects = performerAddedEffects[impl.performerName] ?: emptySet()
                    performerAddedEffects[impl.performerName] = existingEffects + finalizeEffects
                }
            }
        }

        for (implOp in impl.operations) {
            if (implOp.name == ScopedEffectConstants.INITIALIZE_SCOPE || implOp.name == ScopedEffectConstants.FINALIZE_SCOPE) {
                continue
            }

            val declOp = effectDeclaration.operations.find { it.name == implOp.name }
                ?: continue

            val declParamType = convertTypeAnnotation(declOp.paramType)
            val declReturnType = convertTypeAnnotation(declOp.returnType)

            val expectedParamType = substituteTypeParams(declParamType, fullSubstitution)
            val expectedReturnType = substituteTypeParams(declReturnType, fullSubstitution)

            val implParamTypeAnnotation = implOp.paramType
            val isPlaceholderParamType = implParamTypeAnnotation is ParsedRecordType &&
                implParamTypeAnnotation.entries.isEmpty() && !implParamTypeAnnotation.isOpen

            if (implParamTypeAnnotation != null && !isPlaceholderParamType) {
                val implParamType = convertTypeAnnotation(implParamTypeAnnotation)
                val unificationSolver = ConstraintSolver(baseContext, registry)
                val unificationResult = unificationSolver.solve(
                    listOf(Constraint.Equality(implParamType, expectedParamType, "Effect param type validation", loc = implOp.loc))
                )
                if (unificationResult is ConstraintSolver.SolveResult.Failure) {
                    val implTypeStr = prettyPrintType(implParamType)
                    val expectedTypeStr = prettyPrintType(expectedParamType)
                    throw TypeCheckError(
                        "Effect impl '${impl.performerName}' operation '${implOp.name}' has wrong parameter type: " +
                        "expected $expectedTypeStr (from effect '${effectRef.localName}'), " +
                        "but impl declares $implTypeStr. " +
                        "Parameter names and types must match exactly.",
                        implOp.implementation.loc,
                        code = ErrorCode.T0201_TYPE_MISMATCH,
                    )
                }
            }

            val implReturnTypeAnnotation = implOp.returnType
            val isPlaceholderReturnType = implReturnTypeAnnotation is ParsedRecordType &&
                implReturnTypeAnnotation.entries.isEmpty() && !implReturnTypeAnnotation.isOpen

            if (implReturnTypeAnnotation != null && !isPlaceholderReturnType) {
                val implReturnType = convertTypeAnnotation(implReturnTypeAnnotation)
                val unificationSolver = ConstraintSolver(baseContext, registry)
                val unificationResult = unificationSolver.solve(
                    listOf(Constraint.Equality(implReturnType, expectedReturnType, "Effect return type validation", loc = implOp.loc))
                )
                if (unificationResult is ConstraintSolver.SolveResult.Failure) {
                    val implTypeStr = prettyPrintType(implReturnType)
                    val expectedTypeStr = prettyPrintType(expectedReturnType)
                    throw TypeCheckError(
                        "Effect impl '${impl.performerName}' operation '${implOp.name}' has wrong return type: " +
                        "expected $expectedTypeStr (from effect '${effectRef.localName}'), " +
                        "but impl declares $implTypeStr.",
                        implOp.implementation.loc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf(DiagnosticHints.ANNOTATION_VS_BODY),
                    )
                }
            }

            val implEffects = implOp.effects
            if (implEffects != null && effectRef.localName in implEffects.effects) {
                throw TypeCheckError(
                    "Effect impl '${impl.performerName}' operation '${implOp.name}' cannot require " +
                    "the same effect '${effectRef.localName}' it is implementing. " +
                    "The impl should handle the effect, not propagate it. " +
                    "Either remove '${effectRef.localName}' from the effect annotation or transform it to a different effect.",
                    implOp.implementation.loc,
                    code = ErrorCode.V0504_HERMETIC_EFFECT_CONFLICT,
                )
            }

            if (implOp.implementation is ParsedLambda) {
                val operationBaseContext = if (implOp.name == ScopedEffectConstants.INITIALIZE_SCOPE) {
                    baseContext
                } else {
                    contextWithScope
                }

                val scopeRecordBindings = if (scopeType != null && implOp.name != ScopedEffectConstants.INITIALIZE_SCOPE) {
                    setOf("scope")
                } else {
                    emptySet()
                }

                validateImplOperationBody(
                    lambda = implOp.implementation as ParsedLambda,
                    opName = implOp.name,
                    opLoc = implOp.loc,
                    expectedParamType = expectedParamType,
                    expectedReturnType = expectedReturnType,
                    baseContext = operationBaseContext,
                    implLabel = "impl '${impl.performerName}'",
                    validationReason = "Effect body validation",
                    recordLiteralBindings = scopeRecordBindings,
                    prepareContext = { ctx ->
                        EffectHandlerBindings
                            .extendWithAbortAndResume(ctx, expectedReturnType)
                    },
                    onBodyResult = { bodyResult, _ ->
                        val bodyEffects = bodyResult.effects - effectRef.localName
                        if (bodyEffects.isNotEmpty()) {
                            val existingEffects = performerAddedEffects[impl.performerName] ?: emptySet()
                            performerAddedEffects[impl.performerName] = existingEffects + bodyEffects
                        }
                    },
                    onUndefinedVars = { bodyResult ->
                        if (bodyResult.undefinedVariables.isNotEmpty()) {
                            val scopeUndefined = bodyResult.undefinedVariables.find { it.name == "scope" }
                            if (scopeUndefined != null) {
                                val errorMessage = if (implOp.name == ScopedEffectConstants.INITIALIZE_SCOPE) {
                                    "The 'scope' variable is not available in initialize_scope. " +
                                    "The scope is being created here and will only be available in operations and finalize_scope."
                                } else if (!isScopedEffect) {
                                    "The 'scope' variable is not available because this impl does not define initialize_scope. " +
                                    "Add 'initialize_scope = {} -> { ... }' to create a scope."
                                } else {
                                    "Undefined variable 'scope' in operation '${implOp.name}'."
                                }
                                throw TypeCheckError(
                                    errorMessage,
                                    scopeUndefined.loc,
                                    code = ErrorCode.V0702_UNDEFINED_VARIABLE,
                                )
                            }
                            true
                        } else {
                            false
                        }
                    },
                    onSolveFailure = { solveResult ->
                        val firstError = solveResult.errors.firstOrNull()
                        if (firstError != null) {
                            val constraint = firstError.constraint

                            if (constraint is Constraint.Equality) {
                                val left = constraint.left
                                val right = constraint.right
                                val isCallingNonFunction =
                                    (left is MezzeType.FunctionType && !isFunctionLike(right)) ||
                                    (right is MezzeType.FunctionType && !isFunctionLike(left))

                                if (isCallingNonFunction) {
                                    val nonFunctionType = if (left is MezzeType.FunctionType) right else left
                                    throw TypeCheckError(
                                        "Cannot call non-function type as function in impl '${impl.performerName}' " +
                                        "operation '${implOp.name}': type '${prettyPrintType(nonFunctionType)}' " +
                                        "is not a function",
                                        firstError.constraint.loc ?: implOp.implementation.loc,
                                        code = ErrorCode.T0302_CANNOT_APPLY_NON_FUNCTION,
                                    )
                                }
                            }

                            // Report a mismatch whose meaning is already SETTLED here.
                            // A performer impl body is solved in isolation, so an error
                            // that still mentions an unresolved type variable is not
                            // settled — those vars are pinned at the use site, and
                            // reporting them here rejects programs that are fine.
                            // Effect mismatches belong to validateImplOpEffectContract,
                            // which runs separately, so an arrow carrying effects is
                            // likewise not this check's business.
                            //
                            // This used to ask the MESSAGE the same three questions: a
                            // `'[a-z]` regex for type vars, `<` and `>` for effects, and
                            // the substrings "List"/"Seq"/"["/"Set"/"Map" for
                            // collections. A user type merely NAMED `Mapping` matched the
                            // last one and suppressed a real error, and rewording any
                            // diagnostic changed which programs compiled. The collections
                            // test is gone: what it was groping for is "the types are not
                            // pinned yet", which holdsTypeVar answers directly.
                            // The two types this failure is really about. Anything whose
                            // shape we do not recognise stays unreported (empty list).
                            val mismatched: List<MezzeType> = when (constraint) {
                                is Constraint.Equality -> listOf(constraint.left, constraint.right)
                                is Constraint.HasField -> listOf(constraint.recordType, constraint.fieldType)
                                is Constraint.Subsumes -> listOf(constraint.general, constraint.specific)
                                else -> emptyList()
                            }
                            // A KIND violation is settled the moment it is raised, so
                            // it skips the "are the types pinned yet" test entirely. The
                            // #22 foreign-handle gate rejects binding a polyglot handle
                            // to a structural type, and no later pinning of the
                            // surrounding vars can make that legal — where an ordinary
                            // T0201 between two not-yet-pinned types genuinely may
                            // resolve at the use site.
                            //
                            // S-18: without this, S-1b's declared-return-type equality
                            // put exactly that rejection into this solve, the body type
                            // was still a bare var (`'u651` vs
                            // `Result XForeign InteropError`), `holdsTypeVar()` called it
                            // provisional, and the gate's error was dropped — so a
                            // provider op declared `-> Result XForeign` laundered
                            // `eval_raw`'s bare foreign and crashed a caller's
                            // `match … is Ok/Err` with "no variant tag matched".
                            val isKindViolation =
                                firstError.code == ErrorCode.T0217_FOREIGN_HANDLE_NOT_STRUCTURAL
                            val settled = isKindViolation || (
                                firstError.code == ErrorCode.T0201_TYPE_MISMATCH &&
                                    mismatched.isNotEmpty() &&
                                    mismatched.none { it.holdsTypeVar() } &&
                                    mismatched.none { (it as? MezzeType.FunctionType)?.effects != null })

                            if (settled) {
                                throw TypeCheckError(
                                    "Type error in impl '${impl.performerName}' operation '${implOp.name}': ${firstError.message}",
                                    firstError.constraint.loc ?: implOp.implementation.loc,
                                    code = ErrorCode.T0201_TYPE_MISMATCH,
                                )
                            }
                        }
                        true
                    },
                )
            }
        }
    }

    /**
     * #91: stamp a content hash onto every hash-less [MezzeType.TypeApplication]
     * whose constructor name resolves in the global type set. Applied only to
     * ability-op declared types flowing into an impl body, so a cross-module
     * named type (e.g. `Config`) resolves by hash without being imported by
     * name. A TypeApplication that already carries a hash, or whose name is a
     * type variable / not a known type, is left untouched.
     */
    private fun stampTypeHashes(type: MezzeType): MezzeType {
        val stamped = if (type is MezzeType.TypeApplication && type.identity !is TypeIdentity.Declared) {
            registry.typeHashFor(type.constructor)?.let {
                type.copy(identity = TypeIdentity.declared(type.constructor, it))
            } ?: type
        } else type
        return stamped.transformChildren { stampTypeHashes(it) }
    }

    // Task F: was a near-copy of substituteTypeParamsByName. The two differed in
    // opposite directions — this one rebuilt a TypeApplication through
    // WellKnownTypeApp.of, the other tolerated the `'` prefix on lookup — so the
    // survivor does both and this is now a call.
    private fun substituteTypeParams(type: MezzeType, substitution: Map<String, MezzeType>): MezzeType =
        com.mezze.language.typechecker.solving.substituteTypeParamsByName(type, substitution)

    private fun isFunctionLike(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.FunctionType -> true
            is MezzeType.VarType -> true
            else -> false
        }
    }
}
