package com.mezze.language.ast.hashing

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.normalizeTypeVariables

/**
 * Canonical byte encoder for [MezzeType] used by every content hasher in
 * the codebase (mono spec hash, ability impl hash, op hash dependency,
 * etc).
 *
 * **Single source of truth** — there used to be two parallel copies of
 * this logic (one in `Monomorphizer.kt`, one in `TypedASTHasher.kt`) that
 * drifted apart silently. Both have been replaced by calls to
 * [appendType] here. If you need to hash a [MezzeType], use this.
 *
 * **Stability contract**: the byte sequence produced for a given
 * [MezzeType] is the content-hash input. Changing it changes every
 * derived hash in the system (impl hashes, op hashes, mono spec hashes).
 * Bump the cache schema version if you change anything here.
 *
 * **TypeVar normalization**: [appendType] is the public entry point and
 * always normalizes type variable names to first-occurrence positional
 * form (`'a, 'b, 'c, …`) before encoding. This makes hashes stable
 * across solver runs — without it, the same source program produces
 * different hashes when the constraint solver allocates fresh-var
 * counters in a different order (the historical cause of stdlib drift
 * between mezze-build-time and user-build-time, task #127).
 *
 * If you have a chain of `appendType` calls inside a single hashed
 * value, prefer [appendTypeInScope] to avoid re-normalizing typevars at
 * every leaf — re-normalizing per call would re-number the same TypeVar
 * differently at each site.
 */
fun appendType(b: MezzeHashBuilder, type: MezzeType) {
    appendTypeInScope(b, normalizeTypeVariables(type))
}

/**
 * Append a type whose typevars are already normalized in the enclosing
 * scope. Use this when the caller has done a single top-level
 * [normalizeTypeVariables] across a composite structure (e.g. a list of
 * type args that should share a typevar numbering) and is feeding the
 * sub-parts in.
 */
fun appendTypeInScope(b: MezzeHashBuilder, type: MezzeType) {
    when (type) {
        is MezzeType.IntType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x01))
        is MezzeType.FloatType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x02))
        is MezzeType.StringType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x03))
        is MezzeType.CharType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x07))
        is MezzeType.FracType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x08))
        is MezzeType.BytesType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x09))
        // 0x04 belonged to BoolType, which no longer exists: Bool is a builtin
        // VARIANT now and hashes through the VariantType arm below, so a type
        // mentioning Bool hashes differently than it did before that collapse.
        // 0x02 through 0x05 belonged to SetType, MapType, SortedMapType and
        // SortedSetType, which no
        // longer exist — both reach here as TypeApplication now. The tags are
        // NOT reused: a hash is a stable identity, and handing an old tag to a
        // new shape would silently equate two different types across a rebuild.
        is MezzeType.NewtypeType -> {
            b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x10))
            b.append(type.name); b.append(type.constructor)
            // #278/F0: fold the declaration IDENTITY into the structural hash so two
            // same-named newtypes from different declarations hash DISTINCTLY here
            // too (mirrors TypeApplication). Without this, `MezzeTypeHasher` collapses
            // them and any hash-based identity downstream re-opens the collision F0
            // closes in unification.
            appendIdentity(b, type.identity)
            appendTypeInScope(b, type.wrappedType)
        }
        is MezzeType.TypeApplication -> {
            b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR))
            // #312: the name lives in `identity` (Declared→hash, Builtin→name).
            // Since `constructor` is enforced to mirror it (Type.kt init{}), hashing
            // the string again for Declared/Builtin is pure redundancy — appendIdentity
            // already captures it. Only Anonymous carries no name in identity, so its
            // constructor string is the sole distinguisher there (undeclared-name
            // transient) and must still be hashed.
            if (type.identity is com.mezze.language.ast.TypeIdentity.Anonymous) {
                b.append(type.constructor)
            }
            appendIdentity(b, type.identity)
            b.appendInt(type.args.size)
            for (arg in type.args) appendTypeInScope(b, arg)
        }
        is MezzeType.FunctionType -> {
            b.append(byteArrayOf(HashTags.FUNCTION_TYPE))
            appendTypeInScope(b, type.paramType); appendTypeInScope(b, type.returnType)
            // An arrow's EFFECTS and its ABILITY CONSTRAINTS are part of the type,
            // and neither used to be hashed. So `{x: Int} -> Int` and
            // `{x: Int} -> Int <IO>` had the SAME content hash, as did
            // `{x: 'a} -> 'a` and `{x: 'a} -> 'a with Show 'a` — two distinct types
            // sharing one identity, which is what #264 added the record-openness
            // marker to prevent and the same reasoning applies here. A content hash
            // keys declaration identity, dispatch and the precompile cache, so a
            // collision there is not cosmetic.
            //
            // Both are hashed in a CANONICAL order rather than their stored order,
            // because neither is stored in one: a set and a list whose order is
            // whatever inference produced. Hashing them as they come would make the
            // hash depend on solve order, which is the opposite of what it is for.
            appendEffectRowContents(b, type.effects)
            b.appendInt(type.abilityConstraints.size)
            val orderedConstraints = type.abilityConstraints.sortedWith(
                compareBy({ it.abilityHash?.hex ?: "" }, { it.abilityName })
            )
            for (c in orderedConstraints) {
                // The ability's HASH when it has one, its name only as the
                // last resort — a name is not an identity.
                b.append(c.abilityHash?.hex ?: c.abilityName)
                b.appendInt(c.typeArgs.size)
                for (arg in c.typeArgs) appendTypeInScope(b, arg)
                b.appendInt(c.associatedTypeBindings.size)
                for (name in c.associatedTypeBindings.keys.sorted()) {
                    b.append(name); appendTypeInScope(b, c.associatedTypeBindings[name]!!)
                }
            }
        }
        is MezzeType.RecordType -> {
            b.append(byteArrayOf(HashTags.RECORD_TYPE))
            appendIdentity(b, type.identity)
            // #264: openness marker — an open `{x, ...}` and a closed `{x}` are
            // distinct types (different runtime shapes) and must not collide.
            b.appendBoolean(type.rowVar != null)
            b.appendInt(type.fields.size)
            for (name in type.fields.keys.sorted()) {
                b.append(name); appendTypeInScope(b, type.fields[name]!!)
            }
        }
        is MezzeType.VariantType -> {
            b.append(byteArrayOf(HashTags.VARIANT_TYPE))
            appendIdentity(b, type.identity)
            // #264: openness + nominal-wall markers — same-constructor variants that
            // differ only in these flags are distinct types.
            b.appendBoolean(type.rowVar != null)
            b.appendBoolean(type.ascriptionWall)
            b.appendInt(type.constructors.size)
            for (name in type.constructors.keys.sorted()) {
                b.append(name)
                val payload = type.constructors[name]
                b.appendBoolean(payload != null)
                payload?.let { appendTypeInScope(b, it) }
            }
        }
        is MezzeType.VarType -> {
            // Typevars are normalized to positional first-occurrence names
            // (`'a, 'b, 'c, …`) by the public [appendType] entry point. By
            // the time we get here, `type.variable.name` is canonical
            // regardless of which solver run produced it.
            b.append(byteArrayOf(HashTags.TYPE_VAR))
            b.append(type.variable.name)
        }
        is MezzeType.EffectRow -> {
            b.append(byteArrayOf(HashTags.PERFORM, 0x02))
            appendEffectRowContents(b, type)
        }
    }
}

/**
 * An effect row's contents, in a canonical order, for both the places a row can
 * appear: on an ARROW (the ordinary case — effects ride on arrows) and as a
 * standalone [MezzeType.EffectRow].
 *
 * A null row is hashed as an explicit absence rather than skipped, so a pure
 * arrow cannot hash the same as one carrying an empty row.
 *
 * The associated types are hashed too: `<Exception having 'error = Str>` and
 * `<Exception having 'error = Int>` are different types, and the row's NAME set
 * alone cannot tell them apart. Openness is a marker for the same reason a
 * record's is — an open row admits effects a closed one does not.
 */
private fun appendEffectRowContents(b: MezzeHashBuilder, row: MezzeType.EffectRow?) {
    if (row == null) {
        b.appendBoolean(false)
        return
    }
    b.appendBoolean(true)
    b.appendBoolean(row.rowVar != null)
    b.appendInt(row.effects.size)
    for (e in row.effects.toSortedSet()) b.append(e)
    b.appendInt(row.effectInstances.size)
    for (instance in row.effectInstances.sortedBy { it.name }) {
        b.append(instance.name)
        b.appendInt(instance.associatedTypes.size)
        for (name in instance.associatedTypes.keys.sorted()) {
            b.append(name); appendTypeInScope(b, instance.associatedTypes[name]!!)
        }
    }
}

/**
 * Encode a nominal type's [TypeIdentity] into the content hash. This is the
 * single, exhaustive expression of "what distinguishes this nominal type from
 * another of the same structural shape" — one discriminant tag per identity kind
 * followed by the kind's distinguishing content:
 *
 *  - [TypeIdentity.Declared] → tag + the declaration content hash. The
 *    `displayName` is DELIBERATELY NOT hashed: the hash is the sole identity, so
 *    two declared types with the same content hash but different display names are
 *    the same type (rename-safe), exactly as unification treats them.
 *  - [TypeIdentity.Builtin]  → tag + the reserved builtin name (its canonical
 *    identity — there is exactly one `Option`/`List`/… so the name IS the hash).
 *  - [TypeIdentity.Anonymous] → tag only. An inline structural type has no identity
 *    beyond its fields/constructors, which the surrounding branch already hashes.
 *
 * The discriminant tag makes the three kinds hash distinctly even when their
 * distinguishing content coincides.
 */
private fun appendIdentity(b: MezzeHashBuilder, identity: com.mezze.language.ast.TypeIdentity) {
    when (identity) {
        is com.mezze.language.ast.TypeIdentity.Declared -> {
            b.append(byteArrayOf(0x01)); b.append(identity.hash.hex)
        }
        is com.mezze.language.ast.TypeIdentity.Builtin -> {
            b.append(byteArrayOf(0x02)); b.append(identity.name)
        }
        com.mezze.language.ast.TypeIdentity.Anonymous -> {
            b.append(byteArrayOf(0x04))
        }
    }
}
