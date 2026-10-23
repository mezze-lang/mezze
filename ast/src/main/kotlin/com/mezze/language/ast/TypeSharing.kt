@file:OptIn(kotlinx.serialization.InternalSerializationApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mezze.language.ast

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SealedClassSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.builtins.serializer

/**
 * Types written once, however often they occur.
 *
 * A checked module carries a type on every expression, and declared types are
 * carried expanded, so the same large types recur thousands of times: a checked
 * 500-line module was 2.5 MB, `std::syntax`'s 188 MB, and reading them back was
 * most of a cached run. Inside [write], the first occurrence of a type is written
 * in full and numbered, and every later EQUAL one as its number; inside [read],
 * numbers are resolved to the one object they name. Outside both, types are
 * written as they always were — JSON and every other caller are unaffected.
 *
 * Numbering is post-order on both sides — a type is numbered once its parts
 * are — so the writer and the reader agree without writing a table.
 */
object TypeSharing {
    internal val writing = ThreadLocal<Table?>()

    /**
     * Written types by number: by OBJECT first — the same object recurs far more
     * often than an equal copy, and comparing two large equal types is a walk of
     * both — then by equality, which finds the copies.
     */
    internal class Table {
        private val byObject = java.util.IdentityHashMap<MezzeType, Int>()
        private val byValue = HashMap<MezzeType, Int>()
        private var next = 0

        operator fun get(type: MezzeType): Int? =
            byObject[type] ?: byValue[type]?.also { byObject[type] = it }

        fun add(type: MezzeType) {
            val n = next++
            byObject[type] = n
            byValue.putIfAbsent(type, n)
        }
    }
    internal val reading = ThreadLocal<ArrayList<MezzeType>?>()

    /**
     * The file of the location last written, or read, in this scope. A location
     * in the same file writes no file at all ([Loc]'s serializer): every node of
     * a module carries one, and its file's full path was a fifth of the bytes a
     * checked module was written as.
     */
    internal val lastFile = ThreadLocal<Array<String?>?>()

    /** Run [body] writing types shared; the bytes must be read inside [read]. */
    fun <T> write(body: () -> T): T {
        val outer = writing.get()
        val outerFile = lastFile.get()
        writing.set(Table())
        lastFile.set(arrayOfNulls(1))
        try { return body() } finally { writing.set(outer); lastFile.set(outerFile) }
    }

    /** Run [body] reading types written inside [write]. */
    fun <T> read(body: () -> T): T {
        val outer = reading.get()
        val outerFile = lastFile.get()
        reading.set(ArrayList())
        lastFile.set(arrayOfNulls(1))
        try { return body() } finally { reading.set(outer); lastFile.set(outerFile) }
    }
}

/** [MezzeType]'s serializer: the sealed one, writing shared inside [TypeSharing]. */
object MezzeTypeSerializer : KSerializer<MezzeType> {
    // Nothing encodes a MezzeType as JSON (diagnostics print it), so the
    // `typeKind` discriminator the interface declares never applies.
    private val plain: KSerializer<MezzeType> = SealedClassSerializer(
        "com.mezze.language.ast.MezzeType",
        MezzeType::class,
        arrayOf(
            MezzeType.IntType::class, MezzeType.FloatType::class, MezzeType.StringType::class,
            MezzeType.FracType::class, MezzeType.CharType::class, MezzeType.BytesType::class,
            MezzeType.VarType::class, MezzeType.RecordType::class, MezzeType.VariantType::class,
            MezzeType.NewtypeType::class, MezzeType.TypeApplication::class, MezzeType.FunctionType::class,
            MezzeType.EffectRow::class,
        ),
        arrayOf(
            MezzeType.IntType.serializer(), MezzeType.FloatType.serializer(), MezzeType.StringType.serializer(),
            MezzeType.FracType.serializer(), MezzeType.CharType.serializer(), MezzeType.BytesType.serializer(),
            MezzeType.VarType.serializer(), MezzeType.RecordType.serializer(), MezzeType.VariantType.serializer(),
            MezzeType.NewtypeType.serializer(), MezzeType.TypeApplication.serializer(), MezzeType.FunctionType.serializer(),
            MezzeType.EffectRow.serializer(),
        ),
    )

    override val descriptor: SerialDescriptor = plain.descriptor

    /** A shared type: `r`, the number of one written before, or -1 and `t`, the type in full. */
    private val shared: SerialDescriptor = buildClassSerialDescriptor("com.mezze.language.ast.SharedMezzeType") {
        element("r", Int.serializer().descriptor)
        element("t", plain.descriptor, isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: MezzeType) {
        val table = TypeSharing.writing.get() ?: return plain.serialize(encoder, value)
        val number = table[value]
        encoder.encodeStructure(shared) {
            if (number != null) {
                encodeIntElement(shared, 0, number)
            } else {
                encodeIntElement(shared, 0, -1)
                encodeSerializableElement(shared, 1, plain, value)
                table.add(value)
            }
        }
    }

    override fun deserialize(decoder: Decoder): MezzeType {
        val table = TypeSharing.reading.get() ?: return plain.deserialize(decoder)
        return decoder.decodeStructure(shared) {
            var number = -1
            var type: MezzeType? = null
            while (true) {
                when (val i = decodeElementIndex(shared)) {
                    0 -> number = decodeIntElement(shared, 0)
                    1 -> type = decodeSerializableElement(shared, 1, plain)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("unexpected element $i in a shared type")
                }
            }
            if (number >= 0) table[number] else (type ?: error("a shared type with neither a number nor a type")).also { table.add(it) }
        }
    }
}
