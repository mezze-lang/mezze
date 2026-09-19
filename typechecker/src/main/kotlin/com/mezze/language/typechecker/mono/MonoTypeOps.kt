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
 *   map.
 *
 * Sharing a single `unify` would mean teaching the solver about mono's
 * name-keyed bindings or teaching mono about substitutions (overkill —
 * mono never has unsolved type variables in scope).
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
    is MezzeType.ParametricBuiltin -> t.typeArgs.all { isConcrete(it) }
    is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
    is MezzeType.BoolType, is MezzeType.FracType,
    is MezzeType.BytesType,
    is MezzeType.CharType, is MezzeType.EffectRow -> true
}

/** Receiver-vs-target matching used by impl resolution. Looser than
 *  [unify] — accepts container/TypeApplication aliases (e.g. a literal
 *  [List Int] matches a `TypeApplication("List", [Int])` target).
 *
 *  Mirrors [com.mezze.language.typechecker.compiler.TypedASTCompiler]'s
 *  `typeMatchesTarget`; kept private to mono for clarity. */
/**
 * Task F — THE receiver-vs-target matching rule. There were two: this one and a
 * near-copy in `TypedASTCompiler`, each carrying arms the other lacked, and each
 * commented as "mirrors the other". The compiler's had the Bool-variant
 * normalisation (both directions), the newtype arms, and the declared-variant
 * lookup; mono's had the VariantType-vs-VariantType subset rule. Both were right
 * about what the other missed, so this is their union and the copies are gone.
 *
 * [variantDeclHasConstructors] is the one genuine capability difference, passed
 * in rather than stubbed: it asks whether a declared variant NAMED by the target
 * has the concrete's constructor set. Each caller supplies a real
 * implementation — the compiler from its decl index, mono from its declared
 * shapes — so neither side silently loses the check.
 */
internal fun typeMatchesTarget(
    concrete: MezzeType,
    target: MezzeType,
    variantDeclHasConstructors: (String, Set<String>) -> Boolean,
): Boolean {
    if (concrete == target) return true
    return when {
        concrete is MezzeType.IntType && target is MezzeType.IntType -> true
        concrete is MezzeType.FloatType && target is MezzeType.FloatType -> true
        concrete is MezzeType.StringType && target is MezzeType.StringType -> true
        concrete is MezzeType.BoolType && target is MezzeType.BoolType -> true
        concrete is MezzeType.CharType && target is MezzeType.CharType -> true
        concrete is MezzeType.FracType && target is MezzeType.FracType -> true
        // Bool has two surface types — the primitive `MezzeType.BoolType` and the
        // constructor-form `MezzeType.VariantType({True, False})`. A codec-generated
        // `impl … for Bool` registers its target as `MezzeType.BoolType`, but the
        // receiver at a bare `True.encode {…}` call keeps its constructor
        // form. Normalise both directions so ability dispatch on a Bool
        // constant routes through the generated impl.
        concrete is MezzeType.VariantType && target is MezzeType.BoolType &&
            com.mezze.language.ast.PrimitiveTypes.isBoolVariant(concrete) -> true
        concrete is MezzeType.BoolType && target is MezzeType.VariantType &&
            com.mezze.language.ast.PrimitiveTypes.isBoolVariant(target) -> true
        // Hash is authoritative nominal identity: two same-named TypeApplications
        // from DIFFERENT declarations (differing content hash) must NOT match —
        // mirrors the solver's unifyTypeApplications veto (#278/F0). Name match,
        // or the same concrete decl under a rename-safe alias, still matches;
        // identity only ADDS a match after the DISTINCT reject.
        concrete is MezzeType.TypeApplication && target is MezzeType.TypeApplication &&
            identityVerdict(concrete.identity, target.identity) == IdentityVerdict.DISTINCT -> false
        concrete is MezzeType.TypeApplication && target is MezzeType.TypeApplication ->
            concrete.constructor == target.constructor ||
                sameDeclaredIdentity(concrete.identity, target.identity)
        concrete is MezzeType.VariantType && target is MezzeType.TypeApplication &&
            concrete.constructors.size == 1 &&
            concrete.constructors.keys.first() == target.constructor -> true
        concrete is MezzeType.VariantType && target is MezzeType.TypeApplication -> {
            concrete.identity.displayNameOrNull() == target.constructor ||
                VariantAliases.aliasFor(concrete.constructors.keys) == target.constructor ||
                // The receiver may have had its identity stripped: AliasStripper
                // sets Anonymous when the declaring name is not in the caller's
                // scope, which is what lets two same-shaped variants from
                // different modules unify structurally (#148 rule 6).
                //
                // Dispatch still has to find the impl. `Json` from
                // std::codec::json arrives as a bare seven-constructor variant
                // while `impl FromJson 'a for Json` targets
                // MezzeType.TypeApplication(Json), so `j.from_json {}` failed with
                // `Field not found: from_json` — though the identical call
                // through an annotated binding worked.
                //
                // The constructor SET survives stripping, and for a variant it
                // is what the declaration is. Comparing the target's declared
                // arms against the receiver's recovers the link without
                // restoring a nominal wall that unification relies on being
                // absent.
                variantDeclHasConstructors(target.constructor, concrete.constructors.keys)
        }
        // A structural container receiver (MezzeType.SeqType/MezzeType.MapType/…) matches an
        // `impl … for Seq`/`Map`/… whose target is MezzeType.TypeApplication("Seq"/…).
        // Name mapping is the single source in BuiltinTypes.builtinConstructorName.
        target is MezzeType.TypeApplication &&
            com.mezze.language.ast.BuiltinTypes.builtinConstructorName(concrete) == target.constructor -> true
        // …and the SAME alias in the other direction. A container arrives
        // name-carried far more often than structurally (5722 vs 1546 across the
        // typed stdlib), so a receiver `TypeApplication("Seq", [P])` meeting an
        // impl written `for ['a]` — a STRUCTURAL target — had no arm at all and
        // simply missed. The user-visible effect was that `let a: Seq P = …;
        // a == b` compared structurally and answered FALSE where the identical
        // `let a: [P] = …` used P's own Eq and answered true. Disease 2, B.1.
        concrete is MezzeType.TypeApplication &&
            com.mezze.language.ast.BuiltinTypes.builtinConstructorName(target) == concrete.constructor -> true
        // Structural-to-structural: an impl written `for ['a]` / `for {Map,Set,…} 'a` has a
        // STRUCTURAL target (MezzeType.SeqType/MezzeType.MapType/…), so a same-kind receiver must match element-
        // agnostically (the target's element is a VarType). Without this, `impl Sized for ['a]`
        // never dispatches to a seq receiver and `seq.size {}` degrades to a field read (#279).
        concrete is MezzeType.SeqType && target is MezzeType.SeqType -> true
        concrete is MezzeType.ListType && target is MezzeType.ListType -> true
        concrete is MezzeType.RecordType && target is MezzeType.TypeApplication &&
            concrete.identity.displayNameOrNull() == target.constructor -> true
        // MezzeType.RecordType-to-MezzeType.RecordType. Compare structurally, guarded by an
        // identity DISTINCT reject so two DIFFERENT named records with the
        // same field shape do NOT collide. Fields differ → miss.
        //
        // Fixes the (anonymous impl target, alias-tagged receiver) combo:
        // `impl Summary for { user: Str }` registers an Anonymous target;
        // `let p : Post = { user = "dev" }` where `type Post = { user: Str }`
        // carries a Declared identity. identityVerdict(Declared, Anonymous)
        // is UNDETERMINED, so the receiver reaches the field comparison and
        // matches. Two distinct Declared records with the same fields (rare
        // — the parser also rejects same-shape aliases) still hash-mismatch
        // and are correctly rejected.
        concrete is MezzeType.RecordType && target is MezzeType.RecordType -> {
            identityVerdict(concrete.identity, target.identity) != IdentityVerdict.DISTINCT &&
                concrete.fields == target.fields
        }
        // Newtype receivers dispatch to `impl … for <NewtypeName>` whose target
        // is built as a MezzeType.TypeApplication (see ContextBuilder ParsedNewtypeDef →
        // WellKnownTypeApp.of). Prefer hash identity (module-distinct, #278);
        // fall back to name when either hash is absent (builtins/pre-hash).
        concrete is MezzeType.NewtypeType && target is MezzeType.TypeApplication -> {
            val cHash = (concrete.identity as? TypeIdentity.Declared)?.hash
            val tHash = (target.identity as? TypeIdentity.Declared)?.hash
            if (cHash != null && tHash != null) cHash == tHash
            else concrete.name == target.constructor
        }
        concrete is MezzeType.TypeApplication && target is MezzeType.NewtypeType -> {
            val cHash = (concrete.identity as? TypeIdentity.Declared)?.hash
            val tHash = (target.identity as? TypeIdentity.Declared)?.hash
            if (cHash != null && tHash != null) cHash == tHash
            else concrete.constructor == target.name
        }
        // MezzeType.VariantType to MezzeType.VariantType: match if constructors overlap or type names match.
        //
        // Guarded by an identity DISTINCT reject FIRST, mirroring the sibling
        // MezzeType.TypeApplication (3316) and MezzeType.RecordType (3375) arms and the solver's
        // unifyVariants veto (#12): two DIFFERENT Declared variants (differing
        // content hash) must NOT match even when one's constructor set is a
        // subset of the other's — otherwise a sub-variant `| A |` from module X
        // dispatches an impl written for `| A, B, C |` in module Y (wrong-dispatch,
        // ledger L-36/L-55). Anonymous/UNDETERMINED identities (e.g. an
        // identity-stripped cross-module `Json` receiver) are NOT DISTINCT, so the
        // structural subset match below still recovers those links.
        concrete is MezzeType.VariantType && target is MezzeType.VariantType &&
            identityVerdict(concrete.identity, target.identity) == IdentityVerdict.DISTINCT -> false
        concrete is MezzeType.VariantType && target is MezzeType.VariantType -> {
            // Same type name (e.g., both are "Json")
            val concreteName = concrete.identity.displayNameOrNull()
            if (concreteName != null && concreteName == target.identity.displayNameOrNull()) return true
            // Same constructor set
            if (concrete.constructors.keys == target.constructors.keys) return true
            // Concrete has subset of target constructors (e.g., concrete is a single constructor variant)
            if (concrete.constructors.keys.all { it in target.constructors }) return true
            false
        }
        // A bare VarType target is an UNCONSTRAINED impl position — a blanket
        // `impl X for 'a` where the receiver self is a type variable, so it
        // legitimately matches ANY concrete receiver. This is the whole point
        // of a generic wrapper like `impl Somable for 'a` / `impl Okable 'e for
        // 'a`: `x.some {}` / `x.ok {}` must dispatch for every receiver type.
        // Matching here only ADDS a candidate for a blanket impl; disambiguation
        // among multiple matches is handled by the caller (co-operand match +
        // opHash dedup in tryResolveAbilityOp). A concrete target still requires
        // an exact structural match above, so this never widens a non-blanket impl.
        target is MezzeType.VarType -> true
        // VariantType vs VariantType. Mono had this arm and the compiler did not,
        // so it is part of the union rather than a mono-only rule: two variants
        // match by declared name, by identical constructor sets, or when the
        // concrete's constructors are a SUBSET of the target's — gated on identity
        // NOT being DISTINCT first, so two different Declared variants cannot match
        // via the subset branch (that would dispatch a superset impl to a
        // sub-variant; ledger L-56, and the solver's unifyVariants veto #12).
        concrete is MezzeType.VariantType && target is MezzeType.VariantType -> {
            if (identityVerdict(concrete.identity, target.identity) == IdentityVerdict.DISTINCT) {
                false
            } else {
                val concreteName = concrete.identity.displayNameOrNull()
                (concreteName != null && concreteName == target.identity.displayNameOrNull()) ||
                    concrete.constructors.keys == target.constructors.keys ||
                    concrete.constructors.keys.all { it in target.constructors }
            }
        }
        else -> false
    }
}

/** Structural unification: match `pattern` (with TypeVars) against
 *  `value` (concrete). Records bindings as it goes. Returns false on
 *  shape mismatch or conflicting binding. */
internal fun unify(pattern: MezzeType, value: MezzeType, bindings: MutableMap<String, MezzeType>): Boolean {
    if (pattern is MezzeType.VarType) {
        val unwrapped = value
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
    val v = value
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
            // Move-5/differential-gate: two provably-DISTINCT declared records
            // (same fields, different content hash) are different types and must
            // not seed a specialization for each other — mirrors typeMatchesTarget
            // and the solver's unifyRecords veto. Anonymous/UNDETERMINED still fall
            // through to the structural match below.
            identityVerdict(pattern.identity, v.identity) != IdentityVerdict.DISTINCT &&
            pattern.fields.keys == v.fields.keys &&
            pattern.fields.all { (k, t) -> unify(t, v.fields[k]!!, bindings) }
        is MezzeType.VariantType -> v is MezzeType.VariantType &&
            identityVerdict(pattern.identity, v.identity) != IdentityVerdict.DISTINCT &&
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
        is MezzeType.NewtypeType -> v is MezzeType.NewtypeType &&
            // Move-5: newtype is a nominal wall — two DISTINCT declared newtypes
            // (same name, different hash: the newtype-two-faces hazard) must not
            // seed for each other. Name-match only decides when identity is
            // UNDETERMINED (a hashless/legacy side), mirroring the solver.
            identityVerdict(pattern.identity, v.identity) != IdentityVerdict.DISTINCT &&
            pattern.name == v.name && unify(pattern.wrappedType, v.wrappedType, bindings)
        is MezzeType.TypeApplication -> when {
            // Same shape on both sides: constructor + args unify. Move-5: gate on
            // identity NOT being DISTINCT first — two same-named TypeApplications
            // from different declarations (differing content hash) must not seed for
            // each other, mirroring typeMatchesTarget (:79) and the solver's
            // unifyTypeApplications veto. Name match only decides when identity is
            // UNDETERMINED (a hashless side).
            v is MezzeType.TypeApplication ->
                identityVerdict(pattern.identity, v.identity) != IdentityVerdict.DISTINCT &&
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
                else -> false
            }
            pattern.args.size == 2 -> when {
                else -> false
            }
            else -> false
        }
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.BoolType, is MezzeType.FracType,
        is MezzeType.BytesType,
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
    v is MezzeType.TypeApplication && v.constructor == BuiltinTypes.SET && v.args.size == 1 ->
        unify(patternElem, v.args[0], bindings)
    else -> false
}

private fun unifySortedSet(patternElem: MezzeType, v: MezzeType, bindings: MutableMap<String, MezzeType>): Boolean = when {
    v is MezzeType.TypeApplication && v.constructor == BuiltinTypes.SORTED_SET && v.args.size == 1 ->
        unify(patternElem, v.args[0], bindings)
    else -> false
}

private fun unifyMap(patternKey: MezzeType, patternVal: MezzeType, v: MezzeType, bindings: MutableMap<String, MezzeType>): Boolean = when {
    v is MezzeType.TypeApplication && v.constructor == BuiltinTypes.MAP && v.args.size == 2 ->
        unify(patternKey, v.args[0], bindings) && unify(patternVal, v.args[1], bindings)
    else -> false
}

private fun unifySortedMap(patternKey: MezzeType, patternVal: MezzeType, v: MezzeType, bindings: MutableMap<String, MezzeType>): Boolean = when {
    v is MezzeType.TypeApplication && v.constructor == BuiltinTypes.SORTED_MAP && v.args.size == 2 ->
        unify(patternKey, v.args[0], bindings) && unify(patternVal, v.args[1], bindings)
    else -> false
}
