package com.mezze.language.ast.hashing

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
@ConsistentCopyVisibility
data class MezzeHash private constructor(
    private val bytes: ByteArray
) {
    init {
        require(bytes.size == HASH_SIZE_BYTES) {
            "MezzeHash must be exactly $HASH_SIZE_BYTES bytes, got ${bytes.size}"
        }
    }

    override fun toString(): String = "#" + hex(DISPLAY_BYTES)

    fun toFullHexString(): String = hex(bytes.size)

    /**
     * Lower-case hex of the first [n] bytes.
     *
     * Table lookup into a fixed char array rather than `String.format("%02x")`
     * per byte. Both produce identical output, but String.format reparses the
     * format string and allocates a Formatter on every call, and this runs 64
     * times per hash. Hashing is on the typechecker's hot path — a sampling
     * profile of a repeated typecheck put 68% of all samples inside this one
     * method, which made every keystroke in the browser editor cost ~180ms.
     */
    private fun hex(n: Int): String {
        val out = CharArray(n * 2)
        for (i in 0 until n) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MezzeHash) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()

        const val HASH_SIZE_BYTES = 64
        const val DISPLAY_BYTES = 16
        const val SHORT_DISPLAY_BYTES = 8

        fun fromRawBytes(bytes: ByteArray): MezzeHash {
            require(bytes.size == HASH_SIZE_BYTES) { "Raw bytes must be exactly $HASH_SIZE_BYTES bytes" }
            return MezzeHash(bytes.copyOf())
        }
    }
}

/**
 * Bytes to hash, appended a piece at a time and digested once by [build].
 *
 * Buffered: a hash is built from many small pieces (a tag, a length, a name),
 * and feeding each to the digest separately — through a digest looked up from
 * the security providers for every builder — was most of the cost of stamping
 * a module's identities.
 */
class MezzeHashBuilder {
    // This thread's spare buffer when it is free — a builder lives for one hash,
    // and growing a fresh one for each was a tenth of what stamping allocated —
    // or a new one when another builder on this thread holds it.
    private var buf: ByteArray = SPARE.get()?.also { SPARE.set(null) } ?: ByteArray(1024)
    private var len = 0

    private fun room(n: Int) {
        if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
    }

    private fun put(b: Byte) {
        room(1)
        buf[len++] = b
    }

    fun append(bytes: ByteArray): MezzeHashBuilder {
        room(bytes.size)
        System.arraycopy(bytes, 0, buf, len, bytes.size)
        len += bytes.size
        return this
    }

    fun append(str: String): MezzeHashBuilder {
        appendInt(str.length)
        return append(str.toByteArray(Charsets.UTF_8))
    }

    fun appendInt(value: Int): MezzeHashBuilder {
        room(4)
        buf[len++] = (value shr 24).toByte(); buf[len++] = (value shr 16).toByte()
        buf[len++] = (value shr 8).toByte(); buf[len++] = value.toByte()
        return this
    }

    fun appendLong(value: Long): MezzeHashBuilder {
        appendInt((value shr 32).toInt())
        return appendInt(value.toInt())
    }

    fun appendBoolean(value: Boolean): MezzeHashBuilder { put(if (value) 1 else 0); return this }

    fun append(hash: MezzeHash): MezzeHashBuilder = append(hash.toByteArray())

    /** The hash of everything appended since the last build; the builder starts empty again. */
    fun build(): MezzeHash {
        val d = DIGEST.get()
        d.update(buf, 0, len)
        len = 0
        // Handed back for the next builder; this one, built, appends no more.
        if (SPARE.get() == null) SPARE.set(buf)
        buf = EMPTY
        return MezzeHash.fromRawBytes(d.digest())
    }

    fun buildHash(): com.mezze.language.typedast.Hash = com.mezze.language.typedast.Hash(build().toFullHexString())

    private companion object {
        val SPARE = ThreadLocal<ByteArray?>()
        val EMPTY = ByteArray(0)

        /**
         * One digest per thread: looking one up is slow, and a digest is not
         * thread-safe. SHA-512, not SHA3-512: the JVM's SHA3 is five times
         * slower on the small inputs identities are made of, and stamping hashes
         * every declaration of every module.
         */
        val DIGEST: ThreadLocal<MessageDigest> = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-512") }
    }
}

object HashTags {
    // Expressions
    const val INTEGER_LITERAL: Byte = 0x01
    const val FLOAT_LITERAL: Byte = 0x02
    const val STRING_LITERAL: Byte = 0x03
    const val BOOLEAN_LITERAL: Byte = 0x04
    const val UNIT_LITERAL: Byte = 0x05
    const val IDENTIFIER: Byte = 0x06
    const val LAMBDA: Byte = 0x07
    const val FUNCTION_CALL: Byte = 0x08
    const val BINARY_OP: Byte = 0x09
    const val UNARY_OP: Byte = 0x0A
    const val IF_ELSE: Byte = 0x0B
    const val MATCH: Byte = 0x0C
    const val LET_IN: Byte = 0x0D
    const val RECORD: Byte = 0x0E
    const val FIELD_ACCESS: Byte = 0x0F
    const val ARRAY: Byte = 0x10
    const val INDEX_ACCESS: Byte = 0x11
    const val BLOCK: Byte = 0x12
    const val PERFORM: Byte = 0x13
    const val HANDLE: Byte = 0x14
    const val VARIANT_CONSTRUCTOR: Byte = 0x15
    const val TUPLE: Byte = 0x16
    const val PIPE: Byte = 0x17
    const val CHAIN: Byte = 0x18
    const val MUTATE: Byte = 0x19
    const val SPREAD: Byte = 0x1A
    const val RANGE: Byte = 0x1B

    // Patterns
    const val WILDCARD_PATTERN: Byte = 0x20
    const val VARIABLE_PATTERN: Byte = 0x21
    const val LITERAL_PATTERN: Byte = 0x22
    const val CONSTRUCTOR_PATTERN: Byte = 0x23
    const val RECORD_PATTERN: Byte = 0x24
    const val CONS_PATTERN: Byte = 0x25
    const val LIST_PATTERN: Byte = 0x26
    const val AS_PATTERN: Byte = 0x27
    const val OR_PATTERN: Byte = 0x28
    const val TUPLE_PATTERN: Byte = 0x29
    const val SEQ_PATTERN: Byte = 0x2A
    const val REST_PATTERN: Byte = 0x2B
    const val STRING_INTERPOLATION_PATTERN: Byte = 0x2C

    // Types
    const val TYPE_VAR: Byte = 0x30
    const val TYPE_CONSTRUCTOR: Byte = 0x31
    const val FUNCTION_TYPE: Byte = 0x32
    const val RECORD_TYPE: Byte = 0x33
    const val VARIANT_TYPE: Byte = 0x34
    const val SEQ_TYPE: Byte = 0x35
    const val EFFECT_ROW: Byte = 0x36
    const val FORALL_TYPE: Byte = 0x37
    const val ABILITY_CONSTRAINT: Byte = 0x38
    const val SUSPENDED_TYPE: Byte = 0x39
    const val NEWTYPE: Byte = 0x3A
    const val TYPE_ALIAS: Byte = 0x3B

    // Declarations
    const val LET_BINDING: Byte = 0x40
    const val TYPE_DECLARATION: Byte = 0x41
    const val EFFECT_DECLARATION: Byte = 0x42
    const val IMPL_DECLARATION: Byte = 0x43
    const val ABILITY_DECLARATION: Byte = 0x44
    const val IMPL_ABILITY_DECLARATION: Byte = 0x45
    const val TOP_LEVEL_EXPRESSION: Byte = 0x46
    const val RECURSIVE_GROUP: Byte = 0x47   // the declarations of one mutually recursive group
    const val GROUP_MEMBER: Byte = 0x48      // one declaration's place in its group

    // References
    const val LOCAL_REF: Byte = 0x50  // De Bruijn index for internal bindings
    const val EXTERNAL_REF: Byte = 0x51  // Hash of external definition
    const val RECURSIVE_REF: Byte = 0x52  // Self-reference
    const val RESOLVED_REF: Byte = 0x53   // a written name plus the identity it resolves to
    const val MEMBER_PROVIDERS: Byte = 0x54  // every declaration in scope that can answer a member name

    // Operators
    const val OP_PLUS: Byte = 0x60
    const val OP_MINUS: Byte = 0x61
    const val OP_TIMES: Byte = 0x62
    const val OP_DIVIDE: Byte = 0x63
    const val OP_MODULO: Byte = 0x64
    const val OP_EQ: Byte = 0x65
    const val OP_NEQ: Byte = 0x66
    const val OP_LT: Byte = 0x67
    const val OP_GT: Byte = 0x68
    const val OP_LTE: Byte = 0x69
    const val OP_GTE: Byte = 0x6A
    const val OP_AND: Byte = 0x6B
    const val OP_OR: Byte = 0x6C
    const val OP_CONS: Byte = 0x6D
    const val OP_CONCAT: Byte = 0x6E
    const val OP_NOT: Byte = 0x6F
    const val OP_NEG: Byte = 0x70
    const val OP_POWER: Byte = 0x71
}
