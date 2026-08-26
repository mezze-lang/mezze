package com.mezze.language.typechecker.mono

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.VariantAliases
import com.mezze.language.typechecker.solving.IdentityVerdict
import com.mezze.language.typechecker.solving.identityVerdict
import com.mezze.language.typechecker.solving.sameDeclaredIdentity

/**
 * Type-level helpers used by the mono pipeline. These are intentionally
 * separate from [com.mezze.language.typechecker.solving.Unification] —
 * different invariants:
 *
 * - The solver's `unify` operates on *unsolved* type variables produced
 *   during constraint generation; it threads a [Substitution] and supports
 *   the full theory (occurs check, type declarations for nominal types,
 *   row variables, etc.).
 *
 * - Mono's [unify] operates on *substituted* types where the only
 *   "variables" are quoted type-param names (the `'a` of a generic
 *   decl's signature). It records name → concrete bindings into a plain
 *   map and silently unwraps [MezzeType.SuspendType] (Mezze wraps
 *   record field values for lazy evaluation; the wrap is a runtime
 *   artifact, not a structural difference).
 *
 * Sharing a single `unify` would mean either teaching the solver about
 * SuspendType-unwrapping (wrong — solver predates the runtime laziness
 * decision) or teaching mono about substitutions (overkill — mono never
 * has unsolved type variables in scope).
 *
 * Visibility: file-private (no `internal`) — only [MonoDriver] uses
 * these. If a future caller needs them, lift to `internal` then.
 */

/** True if `t` contains no [MezzeType.VarType] anywhere in its
 *  structure. Mono callers use this to gate specialization — if a
 *  derived type-arg is still a VarType, the call site couldn't be
 *  resolved and we fall back to dict-passing.
 *
 *  NOT identical to [com.mezze.language.typechecker.solving.ConstraintSolver]'s
 *  `isConcrete` (which checks `rowVar == null` for record/variant rows
 *  — mono doesn't care because all mono types are post-substitution
 *  and rowVars have already been solved or remain forever-open). */
internal fun isConcrete(t: MezzeType): Boolean = when (t) {
    is MezzeType.VarType -> false
    is MezzeType.FunctionType -> isConcrete(t.paramType) && isConcrete(t.returnType)
    is MezzeType.RecordType -> t.fields.values.all { isConcrete(it) }
    is MezzeType.VariantType -> t.constructors.values.all { it == null || isConcrete(it) }
    is MezzeType.NewtypeType -> isConcrete(t.wrappedType)
    is MezzeType.TypeApplication -> t.args.all { isConcrete(it) }
    is MezzeType.ListType -> isConcrete(t.elementType)
    is MezzeType.SeqType -> isConcrete(t.elementType)
    is MezzeType.SetType -> isConcrete(t.elementType)
    is MezzeType.SortedSetType -> isConcrete(t.elementType)
    is MezzeType.MapType -> isConcrete(t.keyType) && isConcrete(t.valueType)
    is MezzeType.SortedMapType -> isConcrete(t.keyType) && isConcrete(t.valueType)
    is MezzeType.SuspendType -> isConcrete(t.valueType)
    is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
    is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
    is MezzeType.BytesType, is MezzeType.ByteBufferType,
    is MezzeType.CharType, is MezzeType.EffectRow -> true
}

/** Receiver-vs-target matching used by impl resolution. Looser than
 *  [unify] — accepts container/TypeApplication aliases (e.g. a literal
 *  [List Int] matches a `TypeApplication("List", [Int])` target).
 *
 *  Mirrors [com.mezze.language.typechecker.compiler.TypedASTCompiler]'s
 *  `typeMatchesTarget`; kept private to mono for clarity. */
internal fun typeMatchesTarget(concrete: MezzeType, target: MezzeType): Boolean {
    if (concrete == target) return true
    // Prim-to-prim
    if (concrete is MezzeType.IntType && target is MezzeType.IntType) return true
    if (concrete is MezzeType.FloatType && target is MezzeType.FloatType) return true
    if (concrete is MezzeType.StringType && target is MezzeType.StringType) return true
    if (concrete is MezzeType.BoolType && target is MezzeType.BoolType) return true
    if (concrete is MezzeType.NatType && target is MezzeType.NatType) return true
    if (concrete is MezzeType.CharType && target is MezzeType.CharType) return true
    if (concrete is MezzeType.FracType && target is MezzeType.FracType) return true
    // TypeApp constructor match. Hash is the authoritative nominal identity:
    // when BOTH sides carry a declaration content hash and those hashes DIFFER,
    // they are distinct declarations (two modules' `Widget`, a version skew) and
    // MUST NOT match — even when their names coincide. Mirrors the solver's
    // unifyTypeApplications veto (#278/F0). Without this the mono layer would
    // dispatch by NAME ALONE, matching where unify would reject.
    if (concrete is MezzeType.TypeApplication && target is MezzeType.TypeApplication) {
        if (identityVerdict(concrete.identity, target.identity) == IdentityVerdict.DISTINCT) return false
        // A name match, or the SAME concrete declaration under differing names
        // (a rename-safe alias: equal Declared hash / equal Builtin name), matches.
        // Identity only ever ADDS a match — the DISTINCT reject above handled the
        // distinct-declaration case.
        return concrete.constructor == target.constructor ||
            sameDeclaredIdentity(concrete.identity, target.identity)
    }
    // Container-to-TypeApp aliases. The structural-container name mapping is the
    // single source in BuiltinTypes.builtinConstructorName (must agree with the
    // compiler's typeMatchesTarget). RecordType/VariantType keep their own rules.
    if (target is MezzeType.TypeApplication) {
        BuiltinTypes.builtinConstructorName(concrete)?.let { return it == target.constructor }
        return when (concrete) {
            is MezzeType.RecordType -> concrete.identity.displayNameOrNull() == target.constructor
            is MezzeType.VariantType -> {
                if (concrete.identity.displayNameOrNull() == target.constructor) return true
                VariantAliases.aliasFor(concrete.constructors.keys) == target.constructor
            }
            else -> false
        }
    }
    // VariantType vs VariantType: typeName or constructor-set overlap
    if (concrete is MezzeType.VariantType && target is MezzeType.VariantType) {
        val concreteName = concrete.identity.displayNameOrNull()
        if (concreteName != null && concreteName == target.identity.displayNameOrNull()) return true
        if (concrete.constructors.keys == target.constructors.keys) return true
        if (concrete.constructors.keys.all { it in target.constructors }) return true
    }
    // Structural-to-structural: an impl `for ['a]` / `for {Map,Set,…} 'a` has a STRUCTURAL
    // target, so a same-kind receiver matches element-agnostically. Mirrors the compiler's
    // typeMatchesTarget so mono dispatch agrees (#279 — else `seq.size {}` degrades to a field read).
    if (concrete is MezzeType.SeqType && target is MezzeType.SeqType) return true
    if (concrete is MezzeType.ListType && target is MezzeType.ListType) return true
    if (concrete is MezzeType.SetType && target is MezzeType.SetType) return true
    if (concrete is MezzeType.MapType && target is MezzeType.MapType) return true
    if (concrete is MezzeType.SortedSetType && target is MezzeType.SortedSetType) return true
    if (concrete is MezzeType.SortedMapType && target is MezzeType.SortedMapType) return true
    return false
}

/** Structural unification: match `pattern` (with TypeVars) against
 *  `value` (concrete). Records bindings as it goes. Returns false on
 *  shape mismatch or conflicting binding. Silently unwraps
 *  [MezzeType.SuspendType] (see file-level note). */
internal fun unify(pattern: MezzeType, value: MezzeType, bindings: MutableMap<String, MezzeType>): Boolean {
    if (pattern is MezzeType.VarType) {
        val unwrapped = unwrapSuspended(value)
        val name = pattern.variable.name
        val existing = bindings[name]
        return when {
            existing == null -> {
                // A free value TyVar carries no info. Refuse to bind —
                // this used to be a permissive `bind then continue`
                // path that incorrectly seeded specializations for
                // decls whose return type is polymorphic bottom
                // (e.g. `Exception.throw : {..} -> 'a`, whose site
                // resolves `'a` to whatever context expects, forcing
                // the suspend at the wrong time). Better to fail
                // and fall back to dict/generic dispatch.
                if (unwrapped is MezzeType.VarType) false
                else { bindings[name] = unwrapped; true }
            }
            existing == unwrapped -> true
            // Pattern already bound to something concrete; value is a
            // free TyVar. Accept — the concrete binding wins, the free
            // TyVar contributes nothing. Comes up at HOF call sites
            // where the callee scheme reuses the same typeParam across
            // an outer slot (pinned by `init: 'b = 0`) and a folder-
            // lambda's slot (typed to solver-fresh vars).
            unwrapped is MezzeType.VarType -> true
            else -> false
        }
    }
    // Unwrap SuspendType on either side when the other is not.
    val v = if (pattern !is MezzeType.SuspendType) unwrapSuspended(value) else value
    // Symmetric fallback: value side is a free TyVar. Recorded bindings
    // from `pattern`'s prior TyVar sites already carry the needed
    // information; a free TyVar on the value side can't disagree with
    // any of them. Common at HOF call sites where a folder-lambda's
    // param types are solver-fresh vars — we bind the outer typeParam
    // to `Int` from the concrete `init` field, then the folder's
    // `{acc:'b, x:'a} -> 'b` is unified against `{acc:'acc#N, x:'x#N}
    // -> Int` where `'acc#N`/`'x#N` are free.
    if (v is MezzeType.VarType) return true
    return when (pattern) {
        is MezzeType.FunctionType -> v is MezzeType.FunctionType &&
            unify(pattern.paramType, v.paramType, bindings) &&
            unify(pattern.returnType, v.returnType, bindings)
        is MezzeType.RecordType -> v is MezzeType.RecordType &&
            pattern.fields.keys == v.fields.keys &&
            pattern.fields.all { (k, t) -> unify(t, v.fields[k]!!, bindings) }
        is MezzeType.VariantType -> v is MezzeType.VariantType &&
            pattern.constructors.keys == v.constructors.keys &&
            pattern.constructors.all { (k, pT) ->
                val vT = v.constructors[k]
                when {
                    pT == null && vT == null -> true
                    pT != null && vT != null -> unify(pT, vT, bindings)
                    else -> false
                }
            }
        is MezzeType.ListType -> unifyList(pattern.elementType, v, bindings)
        is MezzeType.SeqType -> unifySeq(pattern.elementType, v, bindings)
        is MezzeType.SetType -> unifySet(pattern.elementType, v, bindings)
        is MezzeType.SortedSetType -> unifySortedSet(pattern.elementType, v, bindings)
        is MezzeType.MapType -> unifyMap(pattern.keyType, pattern.valueType, v, bindings)
        is MezzeType.SortedMapType -> unifySortedMap(pattern.keyType, pattern.valueType, v, bindings)
        is MezzeType.SuspendType -> v is MezzeType.SuspendType &&
            unify(pattern.valueType, v.valueType, bindings)
        is MezzeType.NewtypeType -> v is MezzeType.NewtypeType &&
            pattern.name == v.name && unify(pattern.wrappedType, v.wrappedType, bindings)
        is MezzeType.TypeApplication -> when {
            // Same shape on both sides: constructor + args unify.
            v is MezzeType.TypeApplication ->
                pattern.constructor == v.constructor &&
                pattern.args.size == v.args.size &&
                pattern.args.zip(v.args).all { (p2, v2) -> unify(p2, v2, bindings) }
            // Container-alias: pattern is `TypeApplication("Seq", X)` and
            // value is a concrete `SeqType(Y)`. The two shapes represent
            // the same nominal type; unify their element types.
            //
            // Without this branch, mono seeding fails for the extremely
            // common case where a stdlib decl's scheme uses the aliased
            // `TypeApplication` shape but the call site's type has been
            // solved to the concrete container shape (e.g. `seq::range`
            // returns `SeqType`, but `impl Seq 'a` methods declare their
            // receiver as `TypeApplication("Seq", 'a)`).
            pattern.args.size == 1 -> when {
                pattern.constructor == BuiltinTypes.SEQ && v is MezzeType.SeqType ->
                    unify(pattern.args[0], v.elementType, bindings)
                pattern.constructor == BuiltinTypes.LIST && v is MezzeType.ListType ->
                    unify(pattern.args[0], v.elementType, bindings)
                pattern.constructor == BuiltinTypes.SET && v is MezzeType.SetType ->
                    unify(pattern.args[0], v.elementType, bindings)
                pattern.constructor == BuiltinTypes.SORTED_SET && v is MezzeType.SortedSetType ->
                    unify(pattern.args[0], v.elementType, bindings)
                else -> false
            }
            pattern.args.size == 2 -> when {
                pattern.constructor == BuiltinTypes.MAP && v is MezzeType.MapType ->
                    unify(pattern.args[0], v.keyType, bindings) &&
                    unify(pattern.args[1], v.valueType, bindings)
                pattern.constructor == BuiltinTypes.SORTED_MAP && v is MezzeType.SortedMapType ->
                    unify(pattern.args[0], v.keyType, bindings) &&
                    unify(pattern.args[1], v.valueType, bindings)
                else -> false
            }
            else -> false
        }
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
        is MezzeType.BytesType, is MezzeType.ByteBufferType,
        is MezzeType.CharType -> pattern == v
        is MezzeType.EffectRow -> v is MezzeType.EffectRow
        is MezzeType.VarType -> error("VarType handled above")
    }
}

/** Container/TypeApplication cross-shape unifiers. Mezze uses two
 *  representations for the same nominal container types: the concrete
 *  container shape (`SeqType`, `ListType`, …) and the aliased shape
 *  (`TypeApplication("Seq", …)`, `TypeApplication("List", …)`, …).
 *  Both directions must unify: `SeqType(Int)` vs `TypeApplication("Seq", 'a)`
 *  must bind `'a → Int` for stdlib mono seeding to work. */
private fun unifySeq(patternElem: MezzeType, v: MezzeType, bindings: MutableMap<String, MezzeType>): Boolean = when {
    v is MezzeType.SeqType -> unify(patternElem, v.elementType, bindings)
    v is MezzeType.TypeApplication && v.constructor == BuiltinTypes.SEQ && v.args.size == 1 ->
        unify(patternElem, v.args[0], bindings)
    else -> false
}

private fun unifyList(patternElem: MezzeType, v: MezzeType, bindings: MutableMap<String, MezzeType>): Boolean = when {
    v is MezzeType.ListType -> unify(patternElem, v.elementType, bindings)
    v is MezzeType.TypeApplication && v.constructor == BuiltinTypes.LIST && v.args.size == 1 ->
        unify(patternElem, v.args[0], bindings)
    else -> false
}

private fun unifySet(patternElem: MezzeType, v: MezzeType, bindings: MutableMap<String, MezzeType>): Boolean = when {
    v is MezzeType.SetType -> unify(patternElem, v.elementType, bindings)
    v is MezzeType.TypeApplication && v.constructor == BuiltinTypes.SET && v.args.size == 1 ->
        unify(patternElem, v.args[0], bindings)
    else -> false
}

private fun unifySortedSet(patternElem: MezzeType, v: MezzeType, bindings: MutableMap<String, MezzeType>): Boolean = when {
    v is MezzeType.SortedSetType -> unify(patternElem, v.elementType, bindings)
    v is MezzeType.TypeApplication && v.constructor == BuiltinTypes.SORTED_SET && v.args.size == 1 ->
        unify(patternElem, v.args[0], bindings)
    else -> false
}

private fun unifyMap(patternKey: MezzeType, patternVal: MezzeType, v: MezzeType, bindings: MutableMap<String, MezzeType>): Boolean = when {
    v is MezzeType.MapType -> unify(patternKey, v.keyType, bindings) && unify(patternVal, v.valueType, bindings)
    v is MezzeType.TypeApplication && v.constructor == BuiltinTypes.MAP && v.args.size == 2 ->
        unify(patternKey, v.args[0], bindings) && unify(patternVal, v.args[1], bindings)
    else -> false
}

private fun unifySortedMap(patternKey: MezzeType, patternVal: MezzeType, v: MezzeType, bindings: MutableMap<String, MezzeType>): Boolean = when {
    v is MezzeType.SortedMapType -> unify(patternKey, v.keyType, bindings) && unify(patternVal, v.valueType, bindings)
    v is MezzeType.TypeApplication && v.constructor == BuiltinTypes.SORTED_MAP && v.args.size == 2 ->
        unify(patternKey, v.args[0], bindings) && unify(patternVal, v.args[1], bindings)
    else -> false
}

/** Strip the outermost [MezzeType.SuspendType] wrapper if present. */
internal fun unwrapSuspended(t: MezzeType): MezzeType =
    if (t is MezzeType.SuspendType) t.valueType else t
