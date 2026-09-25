package com.mezze.language.typechecker.derive

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.ParsedNewtypeDef
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.solving.expandTypeApplicationToRecord
import com.mezze.language.typechecker.solving.expandTypeApplicationToVariant
import com.mezze.language.typedast.Hash

/**
 * THE structural-derivability rule (task F). Whether the compiler derives an
 * ability for a type — never whether a declared impl exists, which is the
 * caller's half.
 *
 * There were two of these. `AbilityChecker`'s carried the cycle guard, the
 * newtype rule, and record-as-well-as-variant expansion; `TypeValidator`'s
 * carried none of them while making the same decisions from the same arms. The
 * difference that mattered: without the newtype rule, `TypeValidator` believed a
 * newtype derives Eq, which the checker that actually decides says it does not.
 * A validator and a checker disagreeing about derivability is how an impl gets
 * accepted in one place and rejected in another.
 *
 * The two callers still differ in how they decide a COMPONENT is satisfied — one
 * consults registered impls, the other matches target annotations in scope — so
 * that half stays theirs and arrives as [componentSatisfies]. Only the rule is
 * shared, which is the part that was diverging.
 */
internal fun canDeriveAbilityStructurally(
    abilityName: String,
    type: MezzeType,
    abilityHash: Hash?,
    registry: DeclarationRegistry,
    seen: MutableSet<Any>,
    componentSatisfies: (String, MezzeType, Hash?, MutableSet<Any>) -> Boolean,
): Boolean {
    val resolvedHash = abilityHash ?: registry.resolveAbilityHash(abilityName)
    var unwrappedType = type
    var isNewtype = unwrappedType is MezzeType.NewtypeType

    if (unwrappedType is MezzeType.TypeApplication) {
        // Cycle guard: a recursive nominal type (`type Tree = | Node { child: Tree
        // } | Leaf`) re-enters through its own fields. A back-edge to a type
        // already on the path counts as satisfied — derivability is decided by the
        // non-recursive components. Without it the recursion never terminates and
        // a recursive type cannot derive Eq at all.
        if (!seen.add(unwrappedType.constructor)) return true
        val typeDecl = registry.lookupType(unwrappedType.constructor)
            ?: ModuleResolver.lookupTypeDeclaration(unwrappedType.constructor)
        if (typeDecl?.definition is ParsedNewtypeDef) {
            isNewtype = true
        }

        val expandedVariant = expandTypeApplicationToVariant(unwrappedType, registry)
        if (expandedVariant != null) {
            unwrappedType = expandedVariant
        } else {
            expandTypeApplicationToRecord(unwrappedType, registry)?.let { unwrappedType = it }
        }
    }

    // A newtype derives NOTHING but Inspect and Hash. It is a distinct type from
    // what it wraps, so wrapping an Int does not make it ordered or comparable —
    // the author says what it means by writing the impl.
    //
    // Hash is the deliberate exception, so a newtype is usable as a `Map` key or
    // `Set` element with no boilerplate. It is safe in a way Eq is not BECAUSE of
    // the Eq rule above: a newtype that wants its own equality must write it, and
    // writing one obliges it to write a matching Hash. So the derived hash is only
    // ever paired with the derived (structural) equality it agrees with.
    //
    // Embeddable leans on this rule too, and it is the entire mechanism there:
    // because a newtype does not derive it, `no impl == not embeddable` holds
    // uniformly. `Promise 'a` is rejected for exactly the same reason `Meters` is
    // before its author opts in — nobody wrote the impl. That is why Embeddable
    // needs no "is this opaque" test and no list of native types to maintain.
    if (isNewtype &&
        !WellKnownHashConstants.isInspect(resolvedHash) &&
        !WellKnownHashConstants.isHash(resolvedHash)
    ) {
        return false
    }

    return when {
        WellKnownHashConstants.isInspect(resolvedHash) -> true

        // Every value has a structural hash — `Objects.hashCode` is total, and it
        // is already what every hash container computes for its keys. So unlike
        // Ord (whose runtime comparison nodes THROW for composites) there is no
        // shape this cannot answer for, and deriving it universally type-checks
        // nothing that then fails at runtime.
        WellKnownHashConstants.isHash(resolvedHash) -> true

        WellKnownHashConstants.isEq(resolvedHash) ->
            unwrappedType is MezzeType.VarType ||
                BuiltInAbilities.isBoolVariant(unwrappedType) ||
                // A PRIMITIVE derives Eq, with that primitive's own semantics.
                //
                // Nothing here is for an author to decide: `Int` compares as a
                // long, `Str` as a string, `Float` by the platform's IEEE rules (so
                // `NaN` is not equal to itself). `==` lowers to a comparison node
                // that specialises per type and already gets each of these right,
                // so deriving costs nothing and spares every primitive an impl that
                // could only restate what the language already means.
                //
                // A NEWTYPE is the one thing this cannot cover, and is excluded
                // above: it is erased to its payload at runtime, so a derived
                // comparison would silently be the WRAPPED type's. That is why a
                // newtype — and only a newtype — must say what its equality is.
                unwrappedType is MezzeType.IntType ||
                unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.StringType ||
                unwrappedType is MezzeType.CharType ||
                unwrappedType is MezzeType.BytesType ||
                unwrappedType is MezzeType.FracType ||
                (unwrappedType is MezzeType.RecordType &&
                    (unwrappedType as MezzeType.RecordType).fields.values
                        .all { componentSatisfies(abilityName, it, abilityHash, seen) }) ||
                (unwrappedType is MezzeType.VariantType &&
                    (unwrappedType as MezzeType.VariantType).constructors.values
                        .all { it == null || componentSatisfies(abilityName, it, abilityHash, seen) }) ||
                // BOTH container representations, via the single source in
                // BuiltinTypes. These used to be `is ListType` / `is SeqType`
                // arms only, so a seq that arrived name-carried
                // (`TypeApplication("Seq", [P])`, the DOMINANT form — 5722 vs
                // 1546 across the typed stdlib) did not derive Eq at all:
                // `let a: Seq P = …; a == b` was rejected while the identical
                // `let a: [P] = …` worked. Disease 2, and user-visible.
                (com.mezze.language.ast.BuiltinTypes.sequenceElementType(unwrappedType)
                    ?.let { componentSatisfies(abilityName, it, abilityHash, seen) } == true)

        WellKnownHashConstants.isOrd(resolvedHash) ->
            // Ord is NOT structurally derivable for composite types. Unlike Eq —
            // whose DirectEqualNode has an Objects.equals fallback reaching
            // MezzeVariant/MezzeRecord — the runtime Direct{LessThan,GreaterThan,
            // Le,Ge}Node only specialize long/double/String/Frac and THROW on
            // anything else. Deriving Ord for a record/variant/list/seq would
            // type-check a program that crashes at `a < b`. A VarType stays
            // derivable so it can resolve to something that does have Ord.
            unwrappedType is MezzeType.VarType

        // Embeddable: can a value of this type be written into the compiled
        // artifact and rebuilt in a fresh process? True exactly when the value is
        // data all the way down.
        //
        // Rejected by falling through to `else`: FunctionType (the value is a code
        // pointer plus captures) and every declared type with no derivable shape.
        // Newtypes are rejected earlier, by the rule above — that is the opt-in,
        // and it is what makes `Promise`, `MutInt`, `Foreign` and friends fail
        // without any of them being named here.
        WellKnownHashConstants.isEmbeddable(resolvedHash) ->
            // A var is still open; let it resolve. A comptime site additionally
            // requires the result type to be fully solved (V0803), so an
            // unresolved var cannot survive to reification by this route.
            unwrappedType is MezzeType.VarType ||
                // Bool needs no arm of its own: it used to have two surface types,
                // a primitive `BoolType` and a `| True, False |` variant, and only
                // the variant survives — so `isBoolVariant` above is the whole test.
                BuiltInAbilities.isBoolVariant(unwrappedType) ||
                // Primitives are data by definition.
                unwrappedType is MezzeType.IntType ||
                unwrappedType is MezzeType.FloatType ||
                unwrappedType is MezzeType.StringType ||
                unwrappedType is MezzeType.CharType ||
                unwrappedType is MezzeType.BytesType ||
                unwrappedType is MezzeType.FracType ||
                // Records (including `{}`) and variants: data iff every component
                // is. This is what makes rejection RECURSIVE — a record with a
                // `MutInt` field, or a `Seq (Promise Int)`, fails here rather than
                // surviving to the reifier.
                (unwrappedType is MezzeType.RecordType &&
                    (unwrappedType as MezzeType.RecordType).fields.values
                        .all { componentSatisfies(abilityName, it, abilityHash, seen) }) ||
                (unwrappedType is MezzeType.VariantType &&
                    (unwrappedType as MezzeType.VariantType).constructors.values
                        .all { it == null || componentSatisfies(abilityName, it, abilityHash, seen) }) ||
                // The immutable containers — Seq, List, Map, Set, SortedMap,
                // SortedSet, Deque, Option, Result. All of `:datastructures` is
                // persistent, so the container itself is always data and only its
                // arguments decide.
                //
                // Keyed on `isReservedTypeName`, which is the sanctioned identity
                // channel for these types: BuiltinTypes documents that their
                // identity is CARRIED BY THE NAME (TypeIdentity.Builtin) and that
                // this is sound only because the name is reserved. A user type
                // cannot reach this arm.
                (unwrappedType is MezzeType.TypeApplication &&
                    com.mezze.language.ast.BuiltinTypes.isReservedTypeName(
                        (unwrappedType as MezzeType.TypeApplication).constructor
                    ) &&
                    (unwrappedType as MezzeType.TypeApplication).args
                        .all { componentSatisfies(abilityName, it, abilityHash, seen) })

        resolvedHash == WellKnownHashConstants.ABILITY_LOGICALAND ||
            resolvedHash == WellKnownHashConstants.ABILITY_LOGICALOR ->
            BuiltInAbilities.isBoolVariant(unwrappedType)

        else -> false
    }
}
