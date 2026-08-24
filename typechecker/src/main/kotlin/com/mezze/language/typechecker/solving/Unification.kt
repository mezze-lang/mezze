package com.mezze.language.typechecker.solving

import com.mezze.language.ast.*
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.occursCheck
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.parsedast.*
import com.mezze.language.typedast.*
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.context.ContextBuilder

private val unificationCache = ThreadLocal.withInitial {
    HashMap<Pair<MezzeType, MezzeType>, UnificationResult>()
}

// Shared ContextBuilder for the type-application expand functions below. These
// call only convertParsedTypeAnnotation, which resets typeVarCache/sharedRowVar
// on each call and never touches the build()-populated decl maps (all empty
// here), so a single instance is safe to reuse and avoids allocating a fresh
// ContextBuilder on every expansion. Thread-local because ContextBuilder is not
// thread-safe and unification runs concurrently.
private val expandContextBuilder = ThreadLocal.withInitial { ContextBuilder() }

fun clearUnificationCache() {
    unificationCache.get().clear()
}

sealed class UnificationResult {
    data class Success(val substitution: Substitution) : UnificationResult()
    data class Failure(
        val message: String,
        val type1: MezzeType? = null,
        val type2: MezzeType? = null
    ) : UnificationResult()

    inline fun getOrElse(onFailure: (Failure) -> Nothing): Substitution = when (this) {
        is Success -> substitution
        is Failure -> onFailure(this)
    }
}

fun unify(type1: MezzeType, type2: MezzeType, registry: DeclarationRegistry = DeclarationRegistry.EMPTY): UnificationResult {
    if (type1 == type2) return UnificationResult.Success(Substitution.empty)

    val cacheKey = Pair(type1, type2)
    unificationCache.get()[cacheKey]?.let { return it }

    val result = unifyImpl(type1, type2, registry)
    unificationCache.get()[cacheKey] = result
    return result
}

private fun unifyImpl(type1: MezzeType, type2: MezzeType, registry: DeclarationRegistry): UnificationResult {
    if (type1 is MezzeType.BoolType && type2 is MezzeType.VariantType) {
        if (BuiltInAbilities.isBoolVariant(type2)) {
            return UnificationResult.Success(Substitution.empty)
        }
    }
    if (type1 is MezzeType.VariantType && type2 is MezzeType.BoolType) {
        if (BuiltInAbilities.isBoolVariant(type1)) {
            return UnificationResult.Success(Substitution.empty)
        }
    }
    when {
        type1 is MezzeType.VariantType && type2 is MezzeType.TypeApplication -> {
            val expanded = expandTypeApplicationToVariant(type2, registry)
            return if (expanded != null) {
                unify(type1, expanded, registry)
            } else {
                UnificationResult.Failure(
                    message = "Type mismatch: ${prettyPrintType(type1)} is not compatible with ${prettyPrintType(type2)}",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        type1 is MezzeType.TypeApplication && type2 is MezzeType.VariantType -> {
            val expanded = expandTypeApplicationToVariant(type1, registry)
            return if (expanded != null) {
                unify(expanded, type2, registry)
            } else {
                UnificationResult.Failure(
                    message = "Type mismatch: ${prettyPrintType(type1)} is not compatible with ${prettyPrintType(type2)}",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        type1 is MezzeType.RecordType && type2 is MezzeType.TypeApplication -> {
            when (val expansion = expandTypeApplication(type2, registry)) {
                is TypeExpansionResult.Record -> {
                    return unify(type1, expansion.recordType, registry)
                }
                is TypeExpansionResult.Newtype -> {
                    if (expansion.payloadType != null && expansion.variantType.rowVar == null) {
                        return unify(type1, expansion.payloadType, registry)
                    }
                }
                is TypeExpansionResult.Variant, is TypeExpansionResult.NotFound -> {
                }
            }
            return UnificationResult.Failure(
                message = "Type mismatch: ${prettyPrintType(type1)} is not compatible with ${prettyPrintType(type2)}",
                type1 = type1,
                type2 = type2
            )
        }
        type1 is MezzeType.TypeApplication && type2 is MezzeType.RecordType -> {
            when (val expansion = expandTypeApplication(type1, registry)) {
                is TypeExpansionResult.Record -> {
                    return unify(expansion.recordType, type2, registry)
                }
                is TypeExpansionResult.Newtype -> {
                    if (expansion.payloadType != null && expansion.variantType.rowVar == null) {
                        return unify(expansion.payloadType, type2, registry)
                    }
                }
                is TypeExpansionResult.Variant, is TypeExpansionResult.NotFound -> {
                }
            }
            return UnificationResult.Failure(
                message = "Type mismatch: ${prettyPrintType(type1)} is not compatible with ${prettyPrintType(type2)}",
                type1 = type1,
                type2 = type2
            )
        }
        type1 is MezzeType.RecordType && type2 is MezzeType.VariantType -> {
            if (type2.constructors.size == 1 && type2.rowVar == null &&
                !type2.nominalWall && !isVariantNameInScope(type2, registry)) {
                val (_, payloadType) = type2.constructors.entries.first()
                if (payloadType != null) {
                    return unify(type1, payloadType, registry)
                }
            }
            return UnificationResult.Failure(
                message = "Type mismatch: expected ${prettyPrintType(type2)}, but got ${prettyPrintType(type1)}",
                type1 = type1,
                type2 = type2
            )
        }
        type1 is MezzeType.VariantType && type2 is MezzeType.RecordType -> {
            if (type1.constructors.size == 1 && type1.rowVar == null &&
                !type1.nominalWall && !isVariantNameInScope(type1, registry)) {
                val (_, payloadType) = type1.constructors.entries.first()
                if (payloadType != null) {
                    return unify(payloadType, type2, registry)
                }
            }
            return UnificationResult.Failure(
                message = "Type mismatch: expected ${prettyPrintType(type2)}, but got ${prettyPrintType(type1)}",
                type1 = type1,
                type2 = type2
            )
        }
    }

    return when {
        type1 is MezzeType.VarType -> unifyVar(type1.variable, type2)
        type2 is MezzeType.VarType -> unifyVar(type2.variable, type1)

        type1 is MezzeType.SuspendType && type2 !is MezzeType.SuspendType -> {
            val hasConcreteEffects = type1.effects.effects.isNotEmpty() || type1.effects.effectInstances.isNotEmpty()
            if (hasConcreteEffects) {
                return UnificationResult.Failure(
                    message = "Cannot unify effectful type ${prettyPrintType(type1)} with pure type ${prettyPrintType(type2)}. " +
                            "Effects <${type1.effects.allEffectNames.joinToString(", ")}> must be handled first.",
                    type1 = type1,
                    type2 = type2
                )
            }
            unify(type1.valueType, type2, registry)
        }
        type1 !is MezzeType.SuspendType && type2 is MezzeType.SuspendType -> {
            val hasConcreteEffects = type2.effects.effects.isNotEmpty() || type2.effects.effectInstances.isNotEmpty()
            if (hasConcreteEffects) {
                return UnificationResult.Failure(
                    message = "Cannot unify pure type ${prettyPrintType(type1)} with effectful type ${prettyPrintType(type2)}. " +
                            "Effects <${type2.effects.allEffectNames.joinToString(", ")}> must be handled first.",
                    type1 = type1,
                    type2 = type2
                )
            }
            unify(type1, type2.valueType, registry)
        }

        type1 is MezzeType.FunctionType && type2 is MezzeType.FunctionType ->
            unifyFunctions(type1, type2, registry)

        type1 is MezzeType.RecordType && type2 is MezzeType.RecordType ->
            unifyRecords(type1, type2, registry)

        type1 is MezzeType.VariantType && type2 is MezzeType.VariantType ->
            unifyVariants(type1, type2, registry)

        type1 is MezzeType.TypeApplication && type2 is MezzeType.TypeApplication ->
            unifyTypeApplications(type1, type2, registry)

        type1 is MezzeType.SeqType && type2 is MezzeType.SeqType ->
            unify(type1.elementType, type2.elementType, registry)

        type1 is MezzeType.SeqType && type2 is MezzeType.TypeApplication && isBuiltinCollectionApp(type2, BuiltinTypes.SEQ) && type2.args.size == 1 ->
            unify(type1.elementType, type2.args[0], registry)
        type1 is MezzeType.TypeApplication && isBuiltinCollectionApp(type1, BuiltinTypes.SEQ) && type1.args.size == 1 && type2 is MezzeType.SeqType ->
            unify(type1.args[0], type2.elementType, registry)

        type1 is MezzeType.ListType && type2 is MezzeType.ListType ->
            unify(type1.elementType, type2.elementType, registry)

        type1 is MezzeType.ListType && type2 is MezzeType.TypeApplication && isBuiltinCollectionApp(type2, BuiltinTypes.LIST) && type2.args.size == 1 ->
            unify(type1.elementType, type2.args[0], registry)
        type1 is MezzeType.TypeApplication && isBuiltinCollectionApp(type1, BuiltinTypes.LIST) && type1.args.size == 1 && type2 is MezzeType.ListType ->
            unify(type1.args[0], type2.elementType, registry)

        type1 is MezzeType.SetType && type2 is MezzeType.SetType ->
            unify(type1.elementType, type2.elementType, registry)

        type1 is MezzeType.SetType && type2 is MezzeType.TypeApplication && isBuiltinCollectionApp(type2, BuiltinTypes.SET) && type2.args.size == 1 ->
            unify(type1.elementType, type2.args[0], registry)
        type1 is MezzeType.TypeApplication && isBuiltinCollectionApp(type1, BuiltinTypes.SET) && type1.args.size == 1 && type2 is MezzeType.SetType ->
            unify(type1.args[0], type2.elementType, registry)

        type1 is MezzeType.SortedMapType && type2 is MezzeType.SortedMapType ->
            unifySortedMaps(type1, type2, registry)

        type1 is MezzeType.SortedMapType && type2 is MezzeType.TypeApplication && isBuiltinCollectionApp(type2, BuiltinTypes.SORTED_MAP) && type2.args.size == 2 ->
            unifyKeyValue(type1.keyType, type1.valueType, type2.args[0], type2.args[1], registry)
        type1 is MezzeType.TypeApplication && isBuiltinCollectionApp(type1, BuiltinTypes.SORTED_MAP) && type1.args.size == 2 && type2 is MezzeType.SortedMapType ->
            unifyKeyValue(type1.args[0], type1.args[1], type2.keyType, type2.valueType, registry)

        type1 is MezzeType.SortedSetType && type2 is MezzeType.SortedSetType ->
            unify(type1.elementType, type2.elementType, registry)

        type1 is MezzeType.SortedSetType && type2 is MezzeType.TypeApplication && isBuiltinCollectionApp(type2, BuiltinTypes.SORTED_SET) && type2.args.size == 1 ->
            unify(type1.elementType, type2.args[0], registry)
        type1 is MezzeType.TypeApplication && isBuiltinCollectionApp(type1, BuiltinTypes.SORTED_SET) && type1.args.size == 1 && type2 is MezzeType.SortedSetType ->
            unify(type1.args[0], type2.elementType, registry)

        type1 is MezzeType.MapType && type2 is MezzeType.MapType ->
            unifyMaps(type1, type2, registry)

        type1 is MezzeType.MapType && type2 is MezzeType.TypeApplication && isBuiltinCollectionApp(type2, BuiltinTypes.MAP) && type2.args.size == 2 ->
            unifyKeyValue(type1.keyType, type1.valueType, type2.args[0], type2.args[1], registry)
        type1 is MezzeType.TypeApplication && isBuiltinCollectionApp(type1, BuiltinTypes.MAP) && type1.args.size == 2 && type2 is MezzeType.MapType ->
            unifyKeyValue(type1.args[0], type1.args[1], type2.keyType, type2.valueType, registry)

        type1 is MezzeType.SuspendType && type2 is MezzeType.SuspendType ->
            unifySuspended(type1, type2, registry)

        type1 is MezzeType.EffectRow && type2 is MezzeType.EffectRow ->
            unifyEffectRows(type1, type2)

        type1 is MezzeType.IntType && type2 is MezzeType.IntType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.FloatType && type2 is MezzeType.FloatType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.StringType && type2 is MezzeType.StringType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.BoolType && type2 is MezzeType.BoolType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.NatType && type2 is MezzeType.NatType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.FracType && type2 is MezzeType.FracType -> UnificationResult.Success(Substitution.empty)

        type1 is MezzeType.NewtypeType && type2 is MezzeType.NewtypeType -> {
            // #278/F0: newtypes are NOMINAL, module-opaque — same type only if same
            // DECLARATION. Key on the declaration content hash when BOTH sides carry
            // one; a hash mismatch means distinct declarations (two modules' same-named
            // `Token`, or a version skew) and MUST fail, even when the names match.
            // Fall back to the bare name only when a hash is absent (builtins /
            // pre-hash construction). Mirrors the TypeApplication hash-first fix (F8).
            val h1 = type1.typeHash
            val h2 = type2.typeHash
            val sameDeclaration = if (h1 != null && h2 != null) h1 == h2 else type1.name == type2.name
            if (sameDeclaration) {
                unify(type1.wrappedType, type2.wrappedType, registry)
            } else {
                UnificationResult.Failure(
                    message = "Type mismatch: ${type1.name} is not the same type as ${type2.name}",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        type1 is MezzeType.BytesType && type2 is MezzeType.BytesType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.ByteBufferType && type2 is MezzeType.ByteBufferType -> UnificationResult.Success(Substitution.empty)
        type1 is MezzeType.CharType && type2 is MezzeType.CharType -> UnificationResult.Success(Substitution.empty)

        else -> {
            UnificationResult.Failure(
                message = "Type mismatch: expected ${prettyPrintType(type2)}, but got ${prettyPrintType(type1)}",
                type1 = type1,
                type2 = type2
            )
        }
    }
}

private fun unifyVar(typeVar: TypeVar, type: MezzeType): UnificationResult {
    if (type is MezzeType.VarType && type.variable == typeVar) {
        return UnificationResult.Success(Substitution.empty)
    }
    if (type is MezzeType.SuspendType) {
        val valueType = type.valueType
        if (valueType is MezzeType.VarType && valueType.variable == typeVar) {
            return UnificationResult.Success(Substitution.empty)
        }
    }
    if (occursCheck(typeVar, type)) {
        return UnificationResult.Failure(
            message = "Infinite type: type variable '${typeVar}' occurs inside '${prettyPrintType(type)}'. " +
                    "This would create an infinite type.",
            type1 = MezzeType.VarType(typeVar),
            type2 = type
        )
    }
    return UnificationResult.Success(Substitution.single(typeVar, type))
}

private fun unifyFunctions(fn1: MezzeType.FunctionType, fn2: MezzeType.FunctionType, registry: DeclarationRegistry): UnificationResult {
    val paramSubst = unify(fn1.paramType, fn2.paramType, registry).getOrElse { return it }
    val return1 = paramSubst.apply(fn1.returnType)
    val return2 = paramSubst.apply(fn2.returnType)
    val returnSubst = unify(return1, return2, registry).getOrElse { return it }
    val effects1 = fn1.effects
    val effects2 = fn2.effects

    val isPure1 = effects1 == null || (effects1.effects.isEmpty() && effects1.effectInstances.isEmpty() && effects1.rowVar == null)
    val isPure2 = effects2 == null || (effects2.effects.isEmpty() && effects2.effectInstances.isEmpty() && effects2.rowVar == null)
    val isPolymorphic1 = effects1 != null && effects1.effects.isEmpty() && effects1.effectInstances.isEmpty() && effects1.rowVar != null
    val isPolymorphic2 = effects2 != null && effects2.effects.isEmpty() && effects2.effectInstances.isEmpty() && effects2.rowVar != null
    val hasConcrete1 = effects1 != null && (effects1.effects.isNotEmpty() || effects1.effectInstances.isNotEmpty())
    val hasConcrete2 = effects2 != null && (effects2.effects.isNotEmpty() || effects2.effectInstances.isNotEmpty())

    // A declared callback row (has caughtByAncestor) must always be checked
    // — even against a pure or polymorphic actual side — so the strict rule
    // "declared effects must be performed" can fire. Route those through
    // unifyEffectRows using an empty EffectRow for the pure side.
    val declaredCallbackPresent = (effects1?.caughtByAncestor?.isNotEmpty() == true) ||
        (effects2?.caughtByAncestor?.isNotEmpty() == true)

    val effectSubst = when {
        isPure1 && isPure2 -> Substitution.empty

        isPure1 && isPolymorphic2 -> Substitution.empty
        isPolymorphic1 && isPure2 -> Substitution.empty

        // Pure vs. concrete effects. Route through unifyEffectRows so the
        // directional extras logic applies (concrete extras on the ACTUAL
        // side, against a CLOSED formal row, are rejected; extras on the
        // FORMAL/declared side against a pure actual are contravariantly
        // accepted). For a call argument the convention is
        // Equality(left = arg, right = param) → unifyFunctions(fn1 = arg,
        // fn2 = param), so:
        //   isPure1 && hasConcrete2  = pure ACTUAL into an effectful FORMAL
        //     → widening; unifyEffectRows sees the extras as eff2Extra
        //       against a closed eff1 and accepts (contravariant). Sound.
        //   isPure2 && hasConcrete1  = effectful ACTUAL into a pure FORMAL
        //     → the launder (#215); unifyEffectRows sees the extras as
        //       eff1Extra against a closed eff2 and REJECTS. Previously this
        //       returned Substitution.empty, silently swallowing the effect
        //       (e.g. `apply_pure { f = leaky }` where leaky performs Log but
        //       apply_pure's callback param is pure) — the module then
        //       type-checked as pure while performing Log at runtime.
        isPure1 && hasConcrete2 ->
            unifyEffectRows(effects1 ?: MezzeType.EffectRow(emptySet()), effects2!!).getOrElse { return it }
        isPure2 && hasConcrete1 ->
            unifyEffectRows(effects1!!, effects2 ?: MezzeType.EffectRow(emptySet())).getOrElse { return it }

        // Both polymorphic-only or polymorphic-vs-concrete: keep the row
        // var open. Eagerly binding here (via unifyEffectRows) would
        // fix a fresh row var to the current call site's effects, which
        // then blocks other call sites with a different effect set.
        isPolymorphic1 && isPolymorphic2 -> Substitution.empty
        isPolymorphic1 && hasConcrete2 -> Substitution.empty
        hasConcrete1 && isPolymorphic2 -> Substitution.empty

        // #274-B: both sides have concrete effects — propagate through
        // row vars so `<Foo, ...>` can absorb the body's extra effects.
        else -> unifyEffectRows(effects1!!, effects2!!).getOrElse { return it }
    }

    // Unify ability-constraint ASSOCIATED TYPES, mirroring the effect-row path
    // (unifyEffectRows). Without this, `with Producer 'a having 'out = Int` in an
    // annotation is never checked against the body's resolved 'out — the exact
    // asymmetry that let effect `having` be sound while ability `having` was not.
    // Only bindings on constraints shared by (ability name, hash) are unified,
    // key-by-key; a mismatch is a hard failure (naming the key).
    fun key(c: MezzeType.FunctionAbilityConstraint) = c.abilityName to c.abilityHash
    val byKey1 = fn1.abilityConstraints.associateBy { key(it) }
    val byKey2 = fn2.abilityConstraints.associateBy { key(it) }
    var abilitySubst = returnSubst.compose(paramSubst).compose(effectSubst)
    for (k in byKey1.keys.intersect(byKey2.keys)) {
        val c1 = byKey1[k]!!
        val c2 = byKey2[k]!!
        abilitySubst = unifyAssociatedTypeMaps(
            c1.associatedTypeBindings, c2.associatedTypeBindings,
            kind = "Ability", owner = c1.abilityName, acc = abilitySubst, registry = registry
        ).getOrElse { return it }
    }

    return UnificationResult.Success(abilitySubst)
}

/**
 * Unify two associated-type binding maps key-by-key, threading [acc]. Shared by
 * the effect-row path (`EffectInstance.associatedTypes`) and the ability-
 * constraint path (`FunctionAbilityConstraint.associatedTypeBindings`) so both
 * check associated types identically. Only keys present on BOTH sides are
 * unified; a mismatch is a Failure naming the [kind] ("Effect"/"Ability"), the
 * [owner] (effect/ability name), and the key. On success returns the updated
 * substitution; the caller decides how to surface a Failure.
 */
private fun unifyAssociatedTypeMaps(
    m1: Map<String, MezzeType>,
    m2: Map<String, MezzeType>,
    kind: String,
    owner: String,
    acc: Substitution,
    registry: DeclarationRegistry,
): UnificationResult {
    var subst = acc
    for (key in m1.keys + m2.keys) {
        val t1 = m1[key] ?: continue
        val t2 = m2[key] ?: continue
        when (val r = unify(subst.apply(t1), subst.apply(t2), registry)) {
            is UnificationResult.Success -> subst = r.substitution.compose(subst)
            is UnificationResult.Failure -> return UnificationResult.Failure(
                message = "$kind '$owner' has incompatible associated type " +
                    "'${key.removePrefix("'")}': ${r.message}",
                type1 = t1,
                type2 = t2,
            )
        }
    }
    return UnificationResult.Success(subst)
}

/**
 * #268 Stage 1 — the "unify shared member payloads, thread the composed
 * substitution" loop shared by record and variant unification (records unify
 * field types; variants unify constructor payloads). Extracted verbatim from the
 * four copies (unifyRecords both-open + both-closed; unifyVariants both-open,
 * one-open, both-closed) so the compose-ordering and short-circuit are stated
 * ONCE. This is NOT the tail-binding or the nominal/effect-specific branch
 * policies — those stay per-caller (variant nominal wall, effect caughtByAncestor,
 * record exact-set-equality). Effects don't call this: their "members" span three
 * fields and unify associated-types, not a single MezzeType payload.
 *
 * For each key in [keys]:
 *   - `arityConflict(name)` runs first; if it returns a Failure, that is returned.
 *   - When BOTH sides have a payload, the payloads are unified under the running
 *     substitution and composed (new-on-left, matching every original site).
 *   - A key present on only one side is skipped here (openness/missing-member
 *     policy is the caller's job, applied before/around this loop).
 */
private inline fun unifyMemberPayloads(
    keys: Set<String>,
    member1: (String) -> MezzeType?,
    member2: (String) -> MezzeType?,
    registry: DeclarationRegistry,
    outer1: MezzeType,
    outer2: MezzeType,
    arityConflict: (String) -> UnificationResult.Failure? = { null },
    mismatchMessage: (name: String, applied1: MezzeType, applied2: MezzeType, inner: String) -> String,
): UnificationResult {
    var substitution = Substitution.empty
    for (name in keys) {
        arityConflict(name)?.let { return it }

        val payload1 = member1(name)
        val payload2 = member2(name)
        if (payload1 != null && payload2 != null) {
            val applied1 = substitution.apply(payload1)
            val applied2 = substitution.apply(payload2)
            val memberSubst = when (val memberResult = unify(applied1, applied2, registry)) {
                is UnificationResult.Success -> memberResult.substitution
                is UnificationResult.Failure -> return UnificationResult.Failure(
                    message = mismatchMessage(name, applied1, applied2, memberResult.message),
                    type1 = outer1,
                    type2 = outer2,
                )
            }
            substitution = memberSubst.compose(substitution)
        }
    }
    return UnificationResult.Success(substitution)
}

private fun unifyRecords(rec1: MezzeType.RecordType, rec2: MezzeType.RecordType, registry: DeclarationRegistry): UnificationResult {
    val fields1 = rec1.fields.keys
    val fields2 = rec2.fields.keys

    if (rec1.rowVar != null || rec2.rowVar != null) {
        val allFields = (fields1 + fields2).toSet()

        // A field declared on ONE record but absent from the OTHER is only sound
        // if the ABSENT side is OPEN — its row variable can absorb that field. If
        // the absent side is CLOSED, the field is genuinely missing: `{ a, b, ... }`
        // still REQUIRES `b`, so a closed `{ a }` value (no `b`) must fail — the
        // `...` only admits EXTRA fields beyond the declared ones, it doesn't make
        // them optional. (Openness policy — the caller's job, run before the shared
        // payload loop below.)
        for (fieldName in allFields) {
            val missingOnClosedRight = fieldName in fields1 && fieldName !in fields2 && rec2.rowVar == null
            val missingOnClosedLeft = fieldName in fields2 && fieldName !in fields1 && rec1.rowVar == null
            if (missingOnClosedRight || missingOnClosedLeft) {
                return UnificationResult.Failure(
                    message = "record fields don't match: missing '$fieldName'",
                    type1 = rec1,
                    type2 = rec2
                )
            }
        }

        return unifyMemberPayloads(
            keys = allFields,
            member1 = { rec1.fields[it] },
            member2 = { rec2.fields[it] },
            registry = registry,
            outer1 = rec1,
            outer2 = rec2,
            mismatchMessage = { name, a1, a2, inner -> formatFieldMismatch(name, a2, a1, inner) },
        )
    }

    if (fields1 != fields2) {
        val missing = fields2 - fields1   // expected has, got doesn't
        val extra   = fields1 - fields2   // got has, expected doesn't
        val parts = mutableListOf<String>()
        if (missing.isNotEmpty()) {
            parts.add("missing ${missing.joinToString(", ") { "'$it'" }}")
        }
        if (extra.isNotEmpty()) {
            parts.add("unexpected ${extra.joinToString(", ") { "'$it'" }}")
        }
        return UnificationResult.Failure(
            message = "record fields don't match: ${parts.joinToString("; ")}",
            type1 = rec1,
            type2 = rec2
        )
    }

    // Field sets are equal here; unify the shared field types pairwise.
    return unifyMemberPayloads(
        keys = fields1,
        member1 = { rec1.fields[it] },
        member2 = { rec2.fields[it] },
        registry = registry,
        outer1 = rec1,
        outer2 = rec2,
        mismatchMessage = { name, a1, a2, inner -> formatFieldMismatch(name, a2, a1, inner) },
    )
}

// #148: nominal wall if nominalWall=true or typeName is in caller's in-scope set.
private fun isVariantNameInScope(
    v: MezzeType.VariantType,
    registry: DeclarationRegistry
): Boolean {
    if (v.nominalWall) return true
    val name = v.typeName ?: return false
    val override = inScopeAliasNamesOverride.get()
    return if (override != null) override.contains(name)
           else registry.typeNames.contains(name)
}

private val inScopeAliasNamesOverride: ThreadLocal<Set<String>?> = ThreadLocal.withInitial { null }

fun <T> withInScopeAliasNames(names: Set<String>, block: () -> T): T {
    val prev = inScopeAliasNamesOverride.get()
    inScopeAliasNamesOverride.set(names)
    try {
        return block()
    } finally {
        inScopeAliasNamesOverride.set(prev)
    }
}

fun inScopeAliasNamesForSolverOrNull(): Set<String>? = inScopeAliasNamesOverride.get()

internal fun unifyVariants(var1: MezzeType.VariantType, var2: MezzeType.VariantType, registry: DeclarationRegistry): UnificationResult {
    val constructors1 = var1.constructors.keys
    val constructors2 = var2.constructors.keys

    if (var1.rowVar != null && var2.rowVar != null) {
        val allConstructors = (constructors1 + constructors2).toSet()
        // #258: a constructor present on BOTH open variants with a payload-arity
        // disagreement (nullary on one side, payload-bearing on the other) is a hard
        // conflict. A constructor unique to one side is fine (absorbed by the other's
        // row var), so only check shared constructors.
        return unifyMemberPayloads(
            keys = allConstructors,
            member1 = { var1.constructors[it] },
            member2 = { var2.constructors[it] },
            registry = registry,
            outer1 = var1,
            outer2 = var2,
            arityConflict = { name ->
                if (name in constructors1 && name in constructors2 &&
                    (var1.constructors[name] == null) != (var2.constructors[name] == null)
                ) {
                    UnificationResult.Failure("Constructor '$name' data mismatch", var1, var2)
                } else null
            },
            mismatchMessage = { name, _, _, inner -> "Constructor '$name' type mismatch: $inner" },
        )
    }

    if (var1.rowVar != null || var2.rowVar != null) {
        val (openVar, closedVar) = if (var1.rowVar != null) Pair(var1, var2) else Pair(var2, var1)
        val closedCtors = closedVar.constructors.keys
        val openCtors = openVar.constructors.keys

        // inline/inferred placeholder via #148's nominalWall flag), check that ALL
        // constructors in the open variant exist in the closed variant. This is what
        // prevents `Yellow` from unifying with `Color = | Red, Green, Blue |` when
        // Color is declared/imported here.
        if (isVariantNameInScope(closedVar, registry)) {
            val invalidConstructors = openCtors - closedCtors
            if (invalidConstructors.isNotEmpty()) {
                val available = closedCtors.sorted().joinToString(", ")
                val invalid = invalidConstructors.sorted().joinToString(", ")
                val typeDesc = when (closedVar.typeName) {
                    "<inline>" -> "inline variant type '| $available |'"
                    "<inferred>" -> "inferred variant type '| $available |'"
                    else -> "type '${closedVar.typeName}'"
                }
                return UnificationResult.Failure(
                    message = "Constructor(s) '$invalid' not found in $typeDesc. " +
                              "Available constructors: $available",
                    type1 = var1,
                    type2 = var2
                )
            }
        }

        return unifyMemberPayloads(
            keys = closedCtors,
            member1 = { var1.constructors[it] },
            member2 = { var2.constructors[it] },
            registry = registry,
            outer1 = var1,
            outer2 = var2,
            mismatchMessage = { name, _, _, inner -> "Constructor '$name' type mismatch: $inner" },
        )
    }

    // — but only if at least one of those names is in scope at the use site (#148). An alias
    // acts as a nominal wall only when its name is reachable by the caller (declared locally
    // or imported by name). When a value crosses a module boundary with an alias-typed shape
    // and the caller didn't import the alias, the name has no nominal force here.
    val bothNamed = (var1.typeHash != null && var2.typeHash != null) ||
                    (var1.typeName != null && var2.typeName != null)
    val namesMatch = if (var1.typeHash != null && var2.typeHash != null) {
        var1.typeHash == var2.typeHash
    } else {
        var1.typeName == var2.typeName
    }
    if (bothNamed && !namesMatch) {
        val var1NameInScope = isVariantNameInScope(var1, registry)
        val var2NameInScope = isVariantNameInScope(var2, registry)
        if (var1NameInScope || var2NameInScope) {
            // #149: when both walls happen to constrain to the SAME arm set,
            // unify successfully (their nominal identities are interchangeable
            // for value-typing purposes). Concretely this covers the pattern
            val sameArms = var1.constructors.keys == var2.constructors.keys
            if (!sameArms) {
                return UnificationResult.Failure(
                    message = "Cannot unify different variant types '${var1.typeName}' and '${var2.typeName}'",
                    type1 = var1,
                    type2 = var2
                )
            }
        }
    }

    // scope (#148). Otherwise the "declared" side acts purely as a structural arm set.
    val isDeclared1 = var1.typeHash != null || var1.typeName != null
    val isDeclared2 = var2.typeHash != null || var2.typeName != null
    val (declaredVar, anonymousVar) = when {
        isDeclared1 && !isDeclared2 -> Pair(var1, var2)
        isDeclared2 && !isDeclared1 -> Pair(var2, var1)
        else -> Pair(null, null)
    }

    if (declaredVar != null && anonymousVar != null) {
        val invalidConstructors = anonymousVar.constructors.keys - declaredVar.constructors.keys
        if (invalidConstructors.isNotEmpty() && isVariantNameInScope(declaredVar, registry)) {
            val available = declaredVar.constructors.keys.sorted().joinToString(", ")
            val invalid = invalidConstructors.sorted().joinToString(", ")
            val typeDesc = when (declaredVar.typeName) {
                "<inline>" -> "inline variant type '| $available |'"
                "<inferred>" -> "inferred variant type '| $available |'"
                else -> "type '${declaredVar.typeName}'"
            }
            return UnificationResult.Failure(
                message = "Constructor(s) '$invalid' not found in $typeDesc. " +
                          "Available constructors: $available",
                type1 = var1,
                type2 = var2
            )
        }
    }

    val allConstructors = (constructors1 + constructors2).toSet()
    // Same arity guard as the both-open branch, gated on shared constructors.
    return unifyMemberPayloads(
        keys = allConstructors,
        member1 = { var1.constructors[it] },
        member2 = { var2.constructors[it] },
        registry = registry,
        outer1 = var1,
        outer2 = var2,
        arityConflict = { name ->
            if (name in constructors1 && name in constructors2 &&
                (var1.constructors[name] == null) != (var2.constructors[name] == null)
            ) {
                UnificationResult.Failure("Constructor '$name' data mismatch", var1, var2)
            } else null
        },
        mismatchMessage = { name, _, _, inner -> "Constructor '$name' type mismatch: $inner" },
    )
}

private fun unifyTypeApplications(app1: MezzeType.TypeApplication, app2: MezzeType.TypeApplication, registry: DeclarationRegistry): UnificationResult {
    // #280/F8: a NAME match unifies (as before), and an equal typeHash on
    // differing names ALSO unifies (a rename-safe alias). Note: we do NOT reject
    // a name match whose hashes DIFFER — `typeHash` is not stable across a
    // RECURSIVE self-reference (e.g. `type Json = | … JArray {items:[Json]} |`,
    // where the inner and outer `Json` can carry different hashes), so a hash
    // mismatch is not a reliable "distinct declaration" signal. The cross-module
    // same-name collision the audit worried about is instead caught structurally
    // by the #148 in-scope nominal barrier below (and by NewtypeType/#278 for the
    // opaque case). Keying rejection on hash inequality here over-rejects legal
    // recursive types — see JsonCodecInterpreterTest.
    val constructorsMatch = app1.constructor == app2.constructor ||
        (app1.typeHash != null && app2.typeHash != null && app1.typeHash == app2.typeHash)

    if (!constructorsMatch) {
        val expanded1 = expandTypeApplicationToVariant(app1, registry)
        val expanded2 = expandTypeApplicationToVariant(app2, registry)

        if (expanded1 != null && expanded2 != null) {
            if (expanded1.rowVar != null || expanded2.rowVar != null) {
                return unifyVariants(expanded1, expanded2, registry)
            }

            // STRUCTURAL ALIAS HANDLING (#148): two distinct closed aliases. An
            // alias name acts as a nominal barrier ONLY when it's in scope in
            // the caller's module — declared locally or imported by name. When
            // NEITHER side is in scope, both names are reachable-but-unqualified
            // labels and the typechecker should treat the values structurally,
            // unifying their arm sets.
            val override = inScopeAliasNamesOverride.get()
            val app1InScope = if (override != null) override.contains(app1.constructor)
                              else registry.typeNames.contains(app1.constructor)
            val app2InScope = if (override != null) override.contains(app2.constructor)
                              else registry.typeNames.contains(app2.constructor)
            if (!app1InScope && !app2InScope) {
                return unifyVariants(expanded1, expanded2, registry)
            }
        }

        return UnificationResult.Failure(
            message = "Type constructor mismatch: ${app1.constructor} vs ${app2.constructor}",
            type1 = app1,
            type2 = app2
        )
    }

    if (app1.args.size != app2.args.size) {
        return UnificationResult.Failure(
            message = "Type argument count mismatch: ${app1.args.size} vs ${app2.args.size}",
            type1 = app1,
            type2 = app2
        )
    }

    var substitution = Substitution.empty
    for (i in app1.args.indices) {
        val arg1 = substitution.apply(app1.args[i])
        val arg2 = substitution.apply(app2.args[i])

        val argSubst = when (val argResult = unify(arg1, arg2, registry)) {
            is UnificationResult.Success -> argResult.substitution
            is UnificationResult.Failure -> return UnificationResult.Failure(
                message = "Type argument $i mismatch: ${argResult.message}",
                type1 = app1,
                type2 = app2
            )
        }
        substitution = argSubst.compose(substitution)
    }

    return UnificationResult.Success(substitution)
}

private fun unifyMaps(map1: MezzeType.MapType, map2: MezzeType.MapType, registry: DeclarationRegistry): UnificationResult =
    unifyKeyValue(map1.keyType, map1.valueType, map2.keyType, map2.valueType, registry)

private fun unifySortedMaps(map1: MezzeType.SortedMapType, map2: MezzeType.SortedMapType, registry: DeclarationRegistry): UnificationResult =
    unifyKeyValue(map1.keyType, map1.valueType, map2.keyType, map2.valueType, registry)

private fun unifySuspended(susp1: MezzeType.SuspendType, susp2: MezzeType.SuspendType, registry: DeclarationRegistry): UnificationResult {
    val valueSubst = unify(susp1.valueType, susp2.valueType, registry).getOrElse { return it }
    val effectSubst = unifyEffectRows(susp1.effects, susp2.effects).getOrElse { return it }
    return UnificationResult.Success(valueSubst.compose(effectSubst))
}

private fun unifyEffectRows(eff1: MezzeType.EffectRow, eff2: MezzeType.EffectRow): UnificationResult {
    val allEffects1 = eff1.allEffectNames
    val allEffects2 = eff2.allEffectNames
    val combinedEffects = allEffects1 + allEffects2

    val combinedHashes = eff1.effectHashes + eff2.effectHashes
    fun resolveEffectHash(name: String): Hash? = combinedHashes[name] ?: WellKnownHashConstants.resolveHash(name)

    val mutEffect = combinedEffects.firstOrNull { name ->
        WellKnownHashConstants.isMut(resolveEffectHash(name))
    }
    if (mutEffect != null && combinedEffects.size > 1) {
        val otherEffects = combinedEffects.filter { name ->
            !WellKnownHashConstants.isMut(resolveEffectHash(name))
        }.joinToString(", ")
        return UnificationResult.Failure(
            message = "$mutEffect effect is hermetic: cannot coexist with other effects ($otherEffects). " +
                "$mutEffect must be the only effect in scope to prevent data races. " +
                "Either handle the other effects before entering the mutation scope, " +
                "or restructure the code to separate mutation from other effectful operations.",
            type1 = eff1,
            type2 = eff2
        )
    }

    val instances1 = eff1.effectInstances.associateBy { it.name }
    val instances2 = eff2.effectInstances.associateBy { it.name }

    val commonEffects = instances1.keys.intersect(instances2.keys)

    var composedSubst = Substitution.empty
    for (effectName in commonEffects) {
        val inst1 = instances1[effectName]!!
        val inst2 = instances2[effectName]!!

        val allKeys = inst1.associatedTypes.keys + inst2.associatedTypes.keys
        for (key in allKeys) {
            val type1 = inst1.associatedTypes[key]
            val type2 = inst2.associatedTypes[key]

            if (type1 != null && type2 != null) {
                val result = unify(composedSubst.apply(type1), composedSubst.apply(type2))
                when (result) {
                    is UnificationResult.Success -> {
                        composedSubst = composedSubst.compose(result.substitution)
                    }
                    is UnificationResult.Failure -> {
                        return UnificationResult.Failure(
                            message = "Effect '$effectName' has incompatible associated type '$key': " +
                                "expected ${prettyPrintType(type2)} but got ${prettyPrintType(type1)}",
                            type1 = eff1,
                            type2 = eff2
                        )
                    }
                }
            }
        }
    }

    // #274-B: propagate effects through row vars.
    // - Effects only in eff2 must be absorbed by eff1's row var (if any).
    // - Effects only in eff1 must be absorbed by eff2's row var (if any).
    // - If both sides are closed and their effect sets differ, that's a
    //   real mismatch (annotation lies about body's effects).
    //
    // Row subtraction via `caughtByAncestor`: effects listed on a callback
    // row that don't appear on the enclosing function's return row are
    // HANDLED by the enclosing function (e.g., `spawn` catches Exception).
    // Filter these out from the ACTUAL side's extras (eff2Extra): the
    // enclosing function catches them, so they should not bind the shared
    // row var and thus should not surface to the caller.
    //
    // DECLARED-only extras (eff1Extra) are NOT filtered by caught. If
    // spawn declares `<Exception, ρ>` on the callback but the actual
    // lambda doesn't perform Exception, that's a type error — the
    // callback promised to perform Exception and didn't. Row subtraction
    // is about "spawn catches what the CALLBACK actually performs," not
    // "spawn accepts pure callbacks that ignore the contract."
    val caught = eff1.caughtByAncestor + eff2.caughtByAncestor
    val eff2Extra = (allEffects2 - allEffects1) - caught
    val eff1Extra = allEffects1 - allEffects2

    // Strict declared-row check (position-symmetric):
    // A row with non-empty caughtByAncestor is a DECLARED CALLBACK row
    // (only such rows carry caughtByAncestor — set by stampCaughtOnCallbackRows).
    // Effects listed on a declared callback row MUST be performed by the
    // actual body. If declared has effects the actual doesn't perform,
    // that's an error regardless of which side of the unification the
    // declared row lands on.
    if (eff1.caughtByAncestor.isNotEmpty()) {
        // eff1 is declared, eff2 is actual.
        val missing = allEffects1 - allEffects2
        if (missing.isNotEmpty()) {
            return UnificationResult.Failure(
                message = "Effect row mismatch: callback declared row lists <${allEffects1.sorted().joinToString(", ")}> " +
                    "but the actual body performs only <${allEffects2.sorted().joinToString(", ")}>. " +
                    "Missing: <${missing.sorted().joinToString(", ")}>. " +
                    "Every effect on a declared callback row must be performed by the body.",
                type1 = eff1,
                type2 = eff2
            )
        }
    }
    if (eff2.caughtByAncestor.isNotEmpty()) {
        // eff2 is declared, eff1 is actual.
        val missing = allEffects2 - allEffects1
        if (missing.isNotEmpty()) {
            return UnificationResult.Failure(
                message = "Effect row mismatch: callback declared row lists <${allEffects2.sorted().joinToString(", ")}> " +
                    "but the actual body performs only <${allEffects1.sorted().joinToString(", ")}>. " +
                    "Missing: <${missing.sorted().joinToString(", ")}>. " +
                    "Every effect on a declared callback row must be performed by the body.",
                type1 = eff1,
                type2 = eff2
            )
        }
    }

    // Contravariant callback rows: if one side declares extras but the other
    // side's row is CLOSED (no row var), silently accept — the extras are
    // "handled by" whatever handler declared them. This is what makes
    // `spawn : { f: {} -> 'a <Exception, ...> }` accept a callback like
    // `{} -> 42` (which performs no Exception): the declared Exception is
    // simply not present, and spawn's runtime catches nothing.
    //
    // The strict "annotation doesn't match body" check for LET BINDINGS is
    // enforced separately by validateEffectsNotOverpromised (which runs
    // post-solve on annotated let decls). Unification is where argument
    // passing / call-site type matching happens; contravariance is correct
    // there.
    if (eff2Extra.isNotEmpty()) {
        val eff1Row = eff1.rowVar
        if (eff1Row != null) {
            val extraInstances = eff2.effectInstances.filter { it.name in eff2Extra }.toSet()
            val extraSimple = eff2Extra - extraInstances.map { it.name }.toSet()
            val extraHashes = eff2.effectHashes.filterKeys { it in eff2Extra }
            val binding = MezzeType.EffectRow(
                effects = extraSimple,
                effectInstances = extraInstances,
                effectHashes = extraHashes,
                rowVar = eff2.rowVar,
                performed = false
            )
            composedSubst = composedSubst.compose(Substitution.singleRow(eff1Row, binding))
        }
    }

    if (eff1Extra.isNotEmpty()) {
        val eff2Row = eff2.rowVar
        if (eff2Row != null) {
            val extraInstances = eff1.effectInstances.filter { it.name in eff1Extra }.toSet()
            val extraSimple = eff1Extra - extraInstances.map { it.name }.toSet()
            val extraHashes = eff1.effectHashes.filterKeys { it in eff1Extra }
            val binding = MezzeType.EffectRow(
                effects = extraSimple,
                effectInstances = extraInstances,
                effectHashes = extraHashes,
                rowVar = eff1.rowVar,
                performed = false
            )
            composedSubst = composedSubst.compose(Substitution.singleRow(eff2Row, binding))
        } else {
            // Declared side has effects the actual (closed) side doesn't
            // perform. This is a real mismatch — the declaration is a
            // promise, and the actual body isn't keeping it.
            return UnificationResult.Failure(
                message = "Effect row mismatch: declared effects <${allEffects1.sorted().joinToString(", ")}> " +
                    "but actual performs only <${allEffects2.sorted().joinToString(", ")}>. " +
                    "Missing: <${eff1Extra.sorted().joinToString(", ")}>. " +
                    "The callback must perform every effect declared on its row.",
                type1 = eff1,
                type2 = eff2
            )
        }
    }

    return UnificationResult.Success(composedSubst)
}

/**
 * Build a focused error message when a record field's type fails to unify
 * against an expected shape. Avoids the doubled "Field 'x' type mismatch:
 * Type mismatch:" phrasing — strips the inner generic prefix and, when the
 * expected field type is a closed record (the shape produced by issue #216's
 * method-call inference), foregrounds the shape so the user sees what they
 * needed to pass.
 */
private fun unifyKeyValue(
    key1: MezzeType, val1: MezzeType,
    key2: MezzeType, val2: MezzeType,
    registry: DeclarationRegistry
): UnificationResult {
    val keyResult = unify(key1, key2, registry)
    return when (keyResult) {
        is UnificationResult.Success -> {
            val valueResult = unify(
                keyResult.substitution.apply(val1),
                keyResult.substitution.apply(val2),
                registry
            )
            when (valueResult) {
                is UnificationResult.Success -> UnificationResult.Success(keyResult.substitution.compose(valueResult.substitution))
                is UnificationResult.Failure -> valueResult
            }
        }
        is UnificationResult.Failure -> keyResult
    }
}

private fun formatFieldMismatch(
    fieldName: String,
    expectedFieldType: MezzeType,
    actualFieldType: MezzeType,
    innerMessage: String
): String {
    val expectedStr = prettyPrintType(expectedFieldType)
    val actualStr = prettyPrintType(actualFieldType)
    val baseMessage = "Field '$fieldName' type mismatch: expected $expectedStr, got $actualStr"
    val cleanedInner = innerMessage.removePrefix("Type mismatch: ").trim()
    val isShallow = cleanedInner.contains(expectedStr) && cleanedInner.contains(actualStr)
    return if (isShallow) baseMessage else "$baseMessage ($cleanedInner)"
}

private fun substituteTypeParamsByName(type: MezzeType, subst: Map<String, MezzeType>): MezzeType {
    // Only a type var is replaced (by name, tolerating the `'` prefix either
    // way); every other node just recurses via transformChildren, which
    // rebuilds it with copy() and so preserves typeName/typeHash/rowVar/effects.
    if (type is MezzeType.VarType) {
        val name = type.variable.name
        return subst[name] ?: subst["'$name"] ?: subst[name.removePrefix("'")] ?: type
    }
    return type.transformChildren { substituteTypeParamsByName(it, subst) }
}

internal fun expandTypeApplicationToRecord(typeApp: MezzeType.TypeApplication, registry: DeclarationRegistry): MezzeType.RecordType? {
    val typeDecl = registry.lookupType(typeApp.constructor, typeApp.typeHash) ?: return null

    val recordDef = typeDecl.definition as? ParsedRecordDef ?: return null

    val typeParamSubst = typeDecl.typeParams.zip(typeApp.args).associate { (paramName, argType) ->
        paramName to argType
    }

    val contextBuilder = expandContextBuilder.get()

    val fields = recordDef.fields.associate { field ->
        val convertedType = contextBuilder.convertParsedTypeAnnotation(field.type)
        val substitutedType = substituteTypeParamsByName(convertedType, typeParamSubst)
        field.name to substitutedType
    }

    return MezzeType.RecordType(
        fields = fields,
        // #260: expanding an OPEN record alias (`type Foo = { a, ... }`) at a use
        // site must produce a USER-open row (like the inline annotation path), so
        // solveHasField (ConstraintSolver.kt:1029) rejects reading an unlisted field
        // (`f.zzz`) instead of inventing it. A plain fresh row read as solver-inferred.
        rowVar = if (recordDef.isOpen) RowVar.userOpen() else null
    )
}

internal fun expandTypeApplicationToVariant(typeApp: MezzeType.TypeApplication, registry: DeclarationRegistry): MezzeType.VariantType? {
    val typeDecl = registry.lookupType(typeApp.constructor, typeApp.typeHash)
        ?: ModuleResolver.lookupTypeDeclaration(typeApp.constructor)
        ?: return null

    val variantDef = when (val def = typeDecl.definition) {
        is ParsedVariantDef -> def
        is ParsedNewtypeDef -> {
            val payloadType = if (def.wrappedType is ParsedRecordType) {
                def.wrappedType
            } else {
                val synLoc = Loc("<expand>", 0, 0, 0, 0)
                ParsedRecordType(
                    entries = listOf(ParsedRecordTypeEntry("val", def.wrappedType, synLoc)),
                    loc = synLoc
                )
            }
            val synLoc = Loc("<expand>", 0, 0, 0, 0)
            ParsedVariantDef(listOf(ParsedVariantCtorDef(def.constructor, payloadType, loc = synLoc)), loc = synLoc)
        }
        else -> return null
    }

    DebugLogger.debugSection(Category.Unification, "expandTypeApplication") {
        listOf(
            "Expanding ${typeApp.constructor}",
            "Variant has ${variantDef.constructors.size} constructors: ${variantDef.constructors.map { it.name }}"
        )
    }

    val typeParamSubst = typeDecl.typeParams.zip(typeApp.args).associate { (paramName, argType) ->
        paramName to argType
    }

    val contextBuilder = expandContextBuilder.get()

    val constructors = variantDef.constructors.associate { constructor ->
        val dataType = constructor.dataType?.let { annotation ->
            val convertedType = contextBuilder.convertParsedTypeAnnotation(annotation)
            substituteTypeParamsByName(convertedType, typeParamSubst)
        }
        constructor.name to dataType
    }

    DebugLogger.debugSection(Category.Unification, "expandTypeApplicationToVariant") {
        listOf(
            "Expanding ${typeApp.constructor}",
            "VariantDef has ${variantDef.constructors.size} constructors: ${variantDef.constructors.map { it.name }}",
            "Built ${constructors.size} constructors: ${constructors.keys}"
        )
    }

    return MezzeType.VariantType(
        constructors = constructors,
        rowVar = if (variantDef.isOpen) RowVar.fresh() else null,
        typeName = typeDecl.name,
        typeArgs = typeApp.args,   // #194: carry the application's args so the printer can render `Tree Int`
        typeHash = typeDecl.contentHash
    )
}

sealed class TypeExpansionResult {
    data class Record(val recordType: MezzeType.RecordType) : TypeExpansionResult()

    data class Variant(val variantType: MezzeType.VariantType) : TypeExpansionResult()

    data class Newtype(
        val variantType: MezzeType.VariantType,
        val constructorName: String,
        val payloadType: MezzeType?
    ) : TypeExpansionResult()

    object NotFound : TypeExpansionResult()
}

internal fun expandTypeApplication(
    typeApp: MezzeType.TypeApplication,
    registry: DeclarationRegistry
): TypeExpansionResult {
    val typeDecl = registry.lookupType(typeApp.constructor, typeApp.typeHash) ?: return TypeExpansionResult.NotFound

    return when (val definition = typeDecl.definition) {
        is ParsedRecordDef -> {
            val expanded = expandTypeApplicationToRecord(typeApp, registry)
            if (expanded != null) {
                TypeExpansionResult.Record(expanded)
            } else {
                TypeExpansionResult.NotFound
            }
        }
        is ParsedVariantDef -> {
            val expanded = expandTypeApplicationToVariant(typeApp, registry)
            if (expanded != null) {
                TypeExpansionResult.Variant(expanded)
            } else {
                TypeExpansionResult.NotFound
            }
        }
        is ParsedNewtypeDef -> {
            val expanded = expandTypeApplicationToVariant(typeApp, registry)
            if (expanded != null && expanded.constructors.size == 1) {
                val (constructorName, payloadType) = expanded.constructors.entries.first()
                TypeExpansionResult.Newtype(expanded, constructorName, payloadType)
            } else {
                TypeExpansionResult.NotFound
            }
        }
        is ParsedTypeAliasDef -> {
            // Transparent aliases are inlined at the annotation converter, so
            // a TypeApplication of an alias name should never reach here in
            // normal flow. If one leaks through (e.g. via a code path that
            // hasn't been ported yet) treat it as NotFound rather than
            // hallucinating record/variant/newtype shape.
            TypeExpansionResult.NotFound
        }
    }
}

private fun isBuiltinCollectionApp(app: MezzeType.TypeApplication, expectedConstructor: String): Boolean =
    app.constructor == expectedConstructor
