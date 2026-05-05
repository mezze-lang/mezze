package com.mezze.language.ast.hashing

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * Content-addressable SHA3-512 hash for Mezze definitions.
 *
 * Deterministic, collision-resistant, alpha-invariant (for internal bindings),
 * and structure-sensitive. Parameter names ARE part of the hash because they
 * are API in Mezze's named-parameter model.
 */
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

    /**
     * Display format: "#" followed by first 16 bytes in hex (32 hex chars).
     * Example: #a7f9e2c8b1d4a6f3e5d7c9b1a3f5e7d9
     */
    override fun toString(): String {
        return "#" + bytes.take(DISPLAY_BYTES).joinToString("") {
            String.format("%02x", it)
        }
    }

    /**
     * Full hex representation of all 64 bytes.
     */
    fun toFullHexString(): String {
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    /**
     * Get raw bytes (copy to prevent mutation).
     */
    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MezzeHash) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        /** SHA3-512 produces 64 bytes */
        const val HASH_SIZE_BYTES = 64

        /** Display first 16 bytes (32 hex chars) */
        const val DISPLAY_BYTES = 16

        /** Short display: first 8 bytes (16 hex chars) */
        const val SHORT_DISPLAY_BYTES = 8

        private val sha3: ThreadLocal<MessageDigest> = ThreadLocal.withInitial {
            try {
                MessageDigest.getInstance("SHA3-512")
            } catch (e: Exception) {
                // Fallback to SHA-512 if SHA3-512 is not available
                // Note: SHA-512 is also cryptographically secure
                MessageDigest.getInstance("SHA-512")
            }
        }

        /**
         * Compute hash from raw bytes.
         */
        fun fromBytes(data: ByteArray): MezzeHash {
            val digest = sha3.get()
            digest.reset()
            val hash = digest.digest(data)
            return MezzeHash(hash)
        }

        /**
         * Compute hash from a string (UTF-8 encoded).
         */
        fun fromString(data: String): MezzeHash {
            return fromBytes(data.toByteArray(Charsets.UTF_8))
        }

        /**
         * Parse a hash from its hex string representation.
         * Accepts both full (128 chars) and display format (with or without #).
         */
        fun fromHex(hex: String): MezzeHash {
            val cleanHex = hex.removePrefix("#").lowercase()

            // If it's a short display format, we can't reconstruct the full hash
            require(cleanHex.length == HASH_SIZE_BYTES * 2) {
                "Cannot parse short hash display format. Need full ${HASH_SIZE_BYTES * 2} hex chars, got ${cleanHex.length}"
            }

            val bytes = cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return MezzeHash(bytes)
        }

        /**
         * Create a hash from already-computed bytes.
         * Use this when you already have the raw hash bytes (e.g., from database).
         */
        fun fromRawBytes(bytes: ByteArray): MezzeHash {
            require(bytes.size == HASH_SIZE_BYTES) {
                "Raw bytes must be exactly $HASH_SIZE_BYTES bytes"
            }
            return MezzeHash(bytes.copyOf())
        }

        // Operation-hash derivation lives at `Hash.computeOpHash(implHash, opName)` —
        // the typed-API canonical path used everywhere. The two `operationHash`
        // overloads that used to live here were unused; the String/String→String
        // variant returned `.toString()` (the truncated display form), which would
        // silently corrupt any caller that treated its result as a real identity.
    }
}

/**
 * Builder for incrementally computing a MezzeHash.
 *
 * Use this when you need to hash complex structures piece by piece.
 *
 * Example:
 * ```kotlin
 * val hash = MezzeHashBuilder()
 *     .append("function")
 *     .appendInt(2)  // param count
 *     .append(bodyHash)
 *     .appendDependencies(deps)
 *     .build()
 * ```
 */
class MezzeHashBuilder {
    private val digest = try {
        MessageDigest.getInstance("SHA3-512")
    } catch (e: Exception) {
        MessageDigest.getInstance("SHA-512")
    }

    /**
     * Append raw bytes.
     */
    fun append(bytes: ByteArray): MezzeHashBuilder {
        digest.update(bytes)
        return this
    }

    /**
     * Append a string (UTF-8 encoded).
     */
    fun append(str: String): MezzeHashBuilder {
        // Include length to prevent ambiguity
        appendInt(str.length)
        digest.update(str.toByteArray(Charsets.UTF_8))
        return this
    }

    /**
     * Append an integer (as 4 bytes, big-endian).
     */
    fun appendInt(value: Int): MezzeHashBuilder {
        digest.update(byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        ))
        return this
    }

    /**
     * Append a long (as 8 bytes, big-endian).
     */
    fun appendLong(value: Long): MezzeHashBuilder {
        digest.update(byteArrayOf(
            (value shr 56).toByte(),
            (value shr 48).toByte(),
            (value shr 40).toByte(),
            (value shr 32).toByte(),
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        ))
        return this
    }

    /**
     * Append a boolean.
     */
    fun appendBoolean(value: Boolean): MezzeHashBuilder {
        digest.update(if (value) 1 else 0)
        return this
    }

    /**
     * Append another MezzeHash (for dependencies).
     */
    fun append(hash: MezzeHash): MezzeHashBuilder {
        digest.update(hash.toByteArray())
        return this
    }

    /**
     * Build the final hash as the internal MezzeHash representation.
     * Prefer `buildHash()` everywhere outside this module — it returns the
     * canonical typed `Hash` directly, which is what every caller actually
     * wants.
     */
    fun build(): MezzeHash {
        return MezzeHash.fromRawBytes(digest.digest())
    }

    /**
     * Build the final hash as a typed `Hash` (full hex). Canonical exit point —
     * replaces the `Hash(builder.build().toFullHexString())` round-trip.
     */
    fun buildHash(): com.mezze.language.typedast.Hash {
        return com.mezze.language.typedast.Hash(build().toFullHexString())
    }
}

/**
 * Hash tag constants for AST node types.
 *
 * Each AST node type gets a unique byte prefix to prevent collisions
 * between structurally similar but semantically different nodes.
 */
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
