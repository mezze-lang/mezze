package com.mezze.language.typedast

import kotlinx.serialization.Serializable

/**
 * Content hash — the universal identifier for definitions.
 *
 * Computed from the content (structure, types, body) of a definition.
 * NOT from its name or file path.
 * Two definitions with identical content have identical hashes.
 */
@JvmInline
@Serializable
value class Hash(val hex: String) {
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
        fun computeOpHash(implHash: Hash, opName: String): Hash {
            val builder = com.mezze.language.ast.hashing.MezzeHashBuilder()
            builder.append(implHash.hex)
            builder.append(opName)
            return Hash(builder.build().toFullHexString())
        }
    }
}
