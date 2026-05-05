package com.mezze.language.ast.hashing

import com.mezze.language.ast.MezzeType

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
 * **TypeVar caveat**: TypeVars carry non-portable ids (the constraint
 * solver allocates fresh ids each run). We hash by name only — meaning
 * two TypeVars with the same name but different ids hash equal. Callers
 * that hash for content-addressed identity must ensure types are
 * substituted to concrete form *before* hashing; passing an
 * unsubstituted type will produce a hash that's only valid within one
 * solver run.
 */
fun appendType(b: MezzeHashBuilder, type: MezzeType) {
    when (type) {
        is MezzeType.IntType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x01))
        is MezzeType.FloatType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x02))
        is MezzeType.StringType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x03))
        is MezzeType.BoolType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x04))
        is MezzeType.NatType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x06))
        is MezzeType.CharType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x07))
        is MezzeType.FracType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x08))
        is MezzeType.BytesType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x09))
        is MezzeType.ByteBufferType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x0A))
        is MezzeType.OrderingType -> b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x0B))
        is MezzeType.SeqType -> { b.append(byteArrayOf(HashTags.SEQ_TYPE)); appendType(b, type.elementType) }
        is MezzeType.ListType -> { b.append(byteArrayOf(HashTags.SEQ_TYPE, 0x01)); appendType(b, type.elementType) }
        is MezzeType.SetType -> { b.append(byteArrayOf(HashTags.SEQ_TYPE, 0x02)); appendType(b, type.elementType) }
        is MezzeType.MapType -> {
            b.append(byteArrayOf(HashTags.SEQ_TYPE, 0x03))
            appendType(b, type.keyType); appendType(b, type.valueType)
        }
        is MezzeType.SortedMapType -> {
            b.append(byteArrayOf(HashTags.SEQ_TYPE, 0x04))
            appendType(b, type.keyType); appendType(b, type.valueType)
        }
        is MezzeType.SortedSetType -> { b.append(byteArrayOf(HashTags.SEQ_TYPE, 0x05)); appendType(b, type.elementType) }
        is MezzeType.SuspendedType -> { b.append(byteArrayOf(HashTags.SEQ_TYPE, 0x06)); appendType(b, type.valueType) }
        is MezzeType.NewtypeType -> {
            b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR, 0x10))
            b.append(type.name); b.append(type.constructor)
            appendType(b, type.wrappedType)
        }
        is MezzeType.TypeApplication -> {
            b.append(byteArrayOf(HashTags.TYPE_CONSTRUCTOR))
            b.append(type.constructor)
            type.typeHash?.let { b.appendBoolean(true); b.append(it.hex) } ?: b.appendBoolean(false)
            b.appendInt(type.args.size)
            for (arg in type.args) appendType(b, arg)
        }
        is MezzeType.FunctionType -> {
            b.append(byteArrayOf(HashTags.FUNCTION_TYPE))
            appendType(b, type.paramType); appendType(b, type.returnType)
        }
        is MezzeType.RecordType -> {
            b.append(byteArrayOf(HashTags.RECORD_TYPE))
            type.typeHash?.let { b.appendBoolean(true); b.append(it.hex) } ?: b.appendBoolean(false)
            b.appendInt(type.fields.size)
            for (name in type.fields.keys.sorted()) {
                b.append(name); appendType(b, type.fields[name]!!)
            }
        }
        is MezzeType.VariantType -> {
            b.append(byteArrayOf(HashTags.VARIANT_TYPE))
            type.typeHash?.let { b.appendBoolean(true); b.append(it.hex) } ?: b.appendBoolean(false)
            b.appendInt(type.constructors.size)
            for (name in type.constructors.keys.sorted()) {
                b.append(name)
                val payload = type.constructors[name]
                b.appendBoolean(payload != null)
                payload?.let { appendType(b, it) }
            }
        }
        is MezzeType.VarType -> {
            // Defensive — should never appear in a fully-substituted type.
            // Hash deterministically by name only (id is non-portable).
            b.append(byteArrayOf(HashTags.TYPE_VAR))
            b.append(type.variable.name)
        }
        is MezzeType.EffectRow -> {
            b.append(byteArrayOf(HashTags.PERFORM, 0x02))
            b.appendInt(type.effects.size)
            for (e in type.effects.toSortedSet()) b.append(e)
        }
    }
}
