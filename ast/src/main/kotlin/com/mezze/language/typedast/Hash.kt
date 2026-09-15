package com.mezze.language.typedast

import kotlinx.serialization.Serializable

/**
 * Content hash — the universal identifier for definitions.
 *
 * Computed from the content (structure, types, body) of a definition.
 * NOT from its name or file path.
 * Two definitions with identical content have identical hashes.
 */
/**
 * Demoted from `@JvmInline value class` to a plain `data class` so Java
 * code can construct `Hash` directly via `new Hash(hex)` and call
 * `getHex()` without going through Kotlin's name-mangled `box-impl` /
 * `unbox-impl` symbols. The interpreter is mostly Java; mixing value-
 * class Hash with Java callers turned every interop point into a
 * paper-cut ("symbol not found: of-04ja5z0").
 *
 * Cost: one Object allocation per `Hash(...)`. Hash equality remains
 * String-equality on `hex` (same as the value-class form). In hot
 * dispatch caches Hashes are constructed once and reused as keys.
 */
@Serializable
data class Hash(val hex: String) {
    // Pre-computed hash for O(1) fast-reject in equals() — avoids
    // 128-char String.equals() when hashes differ (the common case
    // in handler chain linear scans).
    @Transient
    private val cachedHash: Int = hex.hashCode()

    init {
        require(hex.isNotEmpty()) { "Hash must not be empty" }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == '#' }) {
            "Hash must be hex, got: ${hex.take(20)}"
        }
    }

    override fun hashCode(): Int = cachedHash

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Hash) return false
        // Fast-reject on pre-computed hash before touching the 128-char string
        return cachedHash == other.cachedHash && hex == other.hex
    }

    override fun toString(): String = hex.take(8) + "..."

    companion object {
        val ZERO = Hash("0".repeat(64))

        /** Derive operation hash from impl hash + operation name. Single source of truth. */
        @JvmStatic
        fun computeOpHash(implHash: Hash, opName: String): Hash {
            val builder = com.mezze.language.ast.hashing.MezzeHashBuilder()
            builder.append(implHash.hex)
            builder.append(opName)
            return Hash(builder.build().toFullHexString())
        }

        /**
         * The identity of an APPLIED type — a constructor's declaration hash
         * combined with its arguments' identity tokens. `Result PyForeign IErr`
         * and `Result JsForeign IErr` share a constructor hash, so every key
         * derived from that hash alone collides for them; this is what tells the
         * two apart.
         *
         * Single source of truth, deliberately: registration (filing an
         * `impl Result <T> <E>`) and resolution (a `Result …` receiver looking one
         * up) must mint the SAME bytes or the impl is unreachable. Both reach it
         * through `com.mezze.language.typechecker.util.appliedHashOf`.
         */
        @JvmStatic
        fun computeAppliedHash(constructorHash: Hash, argTokens: List<String>): Hash {
            val builder = com.mezze.language.ast.hashing.MezzeHashBuilder()
            builder.append(constructorHash.hex)
            builder.append("<")
            for (token in argTokens) {
                builder.append(token)
                builder.append(",")
            }
            builder.append(">")
            return Hash(builder.build().toFullHexString())
        }
    }
}

