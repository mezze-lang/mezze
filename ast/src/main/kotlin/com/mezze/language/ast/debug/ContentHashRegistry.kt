package com.mezze.language.ast.debug

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for mapping content hashes back to human-readable names.
 *
 * This is a compile-time debug utility that allows error messages and debuggers
 * to show meaningful names instead of raw hashes.
 *
 * IMPORTANT: This registry is for DISPLAY ONLY, never for lookup.
 * All runtime lookups must use the content hash directly.
 *
 * The registry is populated during module loading/typechecking when content
 * hashes are computed. It maps hash -> qualified name for debugging.
 *
 * Thread-safe: Uses ConcurrentHashMap for safe concurrent access during
 * parallel module loading.
 */
object ContentHashRegistry {
    /**
     * Map from content hash to qualified name.
     * Example: "abc123..." -> "std::int::add"
     */
    private val hashToName = ConcurrentHashMap<String, String>()

    /**
     * Map from content hash to module path.
     * Example: "abc123..." -> "std::int"
     */
    private val hashToModule = ConcurrentHashMap<String, String>()

    /**
     * Map from content hash to declaration kind.
     * Example: "abc123..." -> "function"
     */
    private val hashToKind = ConcurrentHashMap<String, DeclarationKind>()

    /**
     * Types of declarations that can be registered.
     */
    enum class DeclarationKind {
        FUNCTION,
        TYPE,
        EFFECT,
        EFFECT_HANDLER,
        ABILITY,
        ABILITY_IMPL,
        CONSTANT
    }

    /** Register a content hash with its display information. */
    fun register(
        hash: String,
        qualifiedName: String,
        modulePath: String? = null,
        kind: DeclarationKind = DeclarationKind.FUNCTION
    ) {
        hashToName[hash] = qualifiedName
        modulePath?.let { hashToModule[hash] = it }
        hashToKind[hash] = kind
    }

    /**
     * Get the human-readable name for a hash.
     * Returns a truncated hash if the name isn't registered.
     *
     * @param hash The content hash to look up
     * @return The qualified name, or a fallback display string
     */
    fun nameForHash(hash: String): String {
        return hashToName[hash] ?: "<unknown:${hash.take(8)}...>"
    }

    /**
     * Get the module path for a hash.
     *
     * @param hash The content hash to look up
     * @return The module path, or null if not registered
     */
    fun moduleForHash(hash: String): String? {
        return hashToModule[hash]
    }

    /**
     * Get the declaration kind for a hash.
     *
     * @param hash The content hash to look up
     * @return The declaration kind, or null if not registered
     */
    fun kindForHash(hash: String): DeclarationKind? {
        return hashToKind[hash]
    }

    /**
     * Check if a hash is registered.
     *
     * @param hash The content hash to check
     * @return true if the hash has been registered
     */
    fun isRegistered(hash: String): Boolean {
        return hashToName.containsKey(hash)
    }

    /**
     * Get a detailed description for debugging.
     *
     * @param hash The content hash to describe
     * @return A formatted description string
     */
    fun describeHash(hash: String): String {
        val name = hashToName[hash]
        val module = hashToModule[hash]
        val kind = hashToKind[hash]

        return if (name != null) {
            buildString {
                append(name)
                if (kind != null) {
                    append(" (")
                    append(kind.name.lowercase().replace('_', ' '))
                    append(")")
                }
                if (module != null) {
                    append(" from ")
                    append(module)
                }
            }
        } else {
            "<unregistered hash: ${hash.take(16)}...>"
        }
    }

    /**
     * Clear all registered hashes.
     * Used during test cleanup or when reloading modules.
     */
    fun clear() {
        hashToName.clear()
        hashToModule.clear()
        hashToKind.clear()
    }

    /**
     * Get the number of registered hashes.
     * Useful for debugging and testing.
     */
    fun size(): Int = hashToName.size

    /**
     * Get all registered hashes.
     * Useful for debugging and testing.
     *
     * @return Set of all registered content hashes
     */
    fun allHashes(): Set<String> = hashToName.keys.toSet()

    /**
     * Get all registered name mappings.
     * Useful for debugging and testing.
     *
     * @return Map of hash -> name
     */
    fun allMappings(): Map<String, String> = hashToName.toMap()
}
