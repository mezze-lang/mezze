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

    override fun toString(): String =
        "#" + bytes.take(DISPLAY_BYTES).joinToString("") { String.format("%02x", it) }

    fun toFullHexString(): String =
        bytes.joinToString("") { String.format("%02x", it) }

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MezzeHash) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        const val HASH_SIZE_BYTES = 64
        const val DISPLAY_BYTES = 16
        const val SHORT_DISPLAY_BYTES = 8

        fun fromRawBytes(bytes: ByteArray): MezzeHash {
            require(bytes.size == HASH_SIZE_BYTES) { "Raw bytes must be exactly $HASH_SIZE_BYTES bytes" }
            return MezzeHash(bytes.copyOf())
        }
    }
}

class MezzeHashBuilder {
    private val digest = try {
        MessageDigest.getInstance("SHA3-512")
    } catch (e: Exception) {
        MessageDigest.getInstance("SHA-512")
    }

    fun append(bytes: ByteArray): MezzeHashBuilder { digest.update(bytes); return this }

    fun append(str: String): MezzeHashBuilder {
        appendInt(str.length)
        digest.update(str.toByteArray(Charsets.UTF_8))
        return this
    }

    fun appendInt(value: Int): MezzeHashBuilder {
        digest.update(byteArrayOf(
            (value shr 24).toByte(), (value shr 16).toByte(),
            (value shr 8).toByte(), value.toByte()
        ))
        return this
    }

    fun appendLong(value: Long): MezzeHashBuilder {
        digest.update(byteArrayOf(
            (value shr 56).toByte(), (value shr 48).toByte(),
            (value shr 40).toByte(), (value shr 32).toByte(),
            (value shr 24).toByte(), (value shr 16).toByte(),
            (value shr 8).toByte(), value.toByte()
        ))
        return this
    }

    fun appendBoolean(value: Boolean): MezzeHashBuilder { digest.update(if (value) 1 else 0); return this }

    fun append(hash: MezzeHash): MezzeHashBuilder { digest.update(hash.toByteArray()); return this }

    fun build(): MezzeHash = MezzeHash.fromRawBytes(digest.digest())

    fun buildHash(): com.mezze.language.typedast.Hash = com.mezze.language.typedast.Hash(build().toFullHexString())
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

    // Declarations
    const val LET_BINDING: Byte = 0x40
    const val TYPE_DECLARATION: Byte = 0x41
    const val EFFECT_DECLARATION: Byte = 0x42
    const val IMPL_DECLARATION: Byte = 0x43
    const val ABILITY_DECLARATION: Byte = 0x44
    const val IMPL_ABILITY_DECLARATION: Byte = 0x45

    // References
    const val LOCAL_REF: Byte = 0x50  // De Bruijn index for internal bindings
    const val EXTERNAL_REF: Byte = 0x51  // Hash of external definition
    const val RECURSIVE_REF: Byte = 0x52  // Self-reference

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
