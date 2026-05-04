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
    init {
        require(hex.isNotEmpty()) { "Hash must not be empty" }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == '#' }) {
            "Hash must be hex, got: ${hex.take(20)}"
        }
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
    }
}

