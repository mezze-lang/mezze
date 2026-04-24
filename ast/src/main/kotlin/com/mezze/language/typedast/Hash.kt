package com.mezze.language.typedast

import com.mezze.language.parsedast.Loc
import kotlinx.serialization.Serializable

/**
 * Content hash — the universal identifier for definitions.
 *
 * Computed from the content (structure, types, body) of a definition.
 * NOT from its name or file path.
 * Two definitions with identical content have identical hashes.
 * Accepts hashes of varying lengths for compatibility across pipeline stages.
 */
@JvmInline
@Serializable
value class Hash(val hex: String) {
    init {
        require(hex.isNotEmpty()) { "Hash must not be empty" }
        // Allow # prefix for v1 compatibility (v1 stores hashes as #hex)
        val cleanHex = if (hex.startsWith("#")) hex.substring(1) else hex
        require(cleanHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Hash must be hex, got: ${hex.take(20)}"
        }
    }

    override fun toString(): String = hex.take(8) + "..."

    companion object {
        /** Placeholder hash for testing / during development */
        val ZERO = Hash("0".repeat(64))

        /**
         * Create from a v1 MezzeHash string (may have # prefix, may be truncated).
         */
        fun fromV1(v1Hash: String?): Hash {
            if (v1Hash == null || v1Hash.isBlank()) return ZERO
            // Keep v1 hash format as-is (may have # prefix) for runtime compatibility
            // The v1 global context uses #hex as keys
            val trimmed = v1Hash.trim()
            return if (trimmed.isEmpty()) ZERO
            else Hash(trimmed)
        }
    }
}
