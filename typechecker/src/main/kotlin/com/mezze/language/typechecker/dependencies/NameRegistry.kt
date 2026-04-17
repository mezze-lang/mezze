package com.mezze.language.typechecker.dependencies

import com.mezze.language.ast.BindingKey
import com.mezze.language.ast.hashing.MezzeHash

/**
 * Bidirectional registry mapping names to content hashes and vice versa.
 *
 * This is essential for Unison-style content-addressed code:
 * - Names are metadata, not part of the definition's identity
 * - The same definition can have multiple names (aliases)
 * - The same name can refer to different definitions over time (edits)
 *
 * ## Usage Pattern:
 * ```kotlin
 * val registry = NameRegistry()
 *
 * // Register a definition
 * registry.register("myModule", "myFunction", hash)
 *
 * // Look up by name
 * val hash = registry.getHash("myModule", "myFunction")
 *
 * // Look up by hash (reverse lookup)
 * val names = registry.getNames(hash)  // May return multiple names (aliases)
 * ```
 *
 * ## Thread Safety:
 * This class is NOT thread-safe. Use external synchronization if needed.
 */
class NameRegistry {
    /**
     * A qualified name consisting of module path and local name.
     */
    data class QualifiedName(
        val modulePath: String?,  // null for current module
        val name: String
    ) {
        val fullName: String
            get() = if (modulePath != null) "$modulePath::$name" else name

        override fun toString(): String = fullName
    }

    // Forward mapping: QualifiedName -> MezzeHash
    private val nameToHash = mutableMapOf<QualifiedName, MezzeHash>()

    // Reverse mapping: MezzeHash -> Set<QualifiedName>
    // A hash can have multiple names (aliases)
    private val hashToNames = mutableMapOf<MezzeHash, MutableSet<QualifiedName>>()

    /**
     * Register a name-to-hash mapping.
     *
     * @param modulePath The module path (null for current module)
     * @param name The local name within the module
     * @param hash The content hash of the definition
     */
    fun register(modulePath: String?, name: String, hash: MezzeHash) {
        val qualifiedName = QualifiedName(modulePath, name)

        // Remove old mapping if this name was previously registered
        nameToHash[qualifiedName]?.let { oldHash ->
            hashToNames[oldHash]?.remove(qualifiedName)
        }

        // Add new mappings
        nameToHash[qualifiedName] = hash
        hashToNames.getOrPut(hash) { mutableSetOf() }.add(qualifiedName)
    }

    /**
     * Register a BindingKey-to-hash mapping.
     * This is the preferred method - uses structured data.
     *
     * @param key The binding key with qualifier and local name
     * @param hash The content hash of the definition
     */
    fun register(key: BindingKey, hash: MezzeHash) {
        register(key.qualifier, key.localName, hash)
    }

    /**
     * Get the hash for a qualified name.
     *
     * @param modulePath The module path (null for current module)
     * @param name The local name
     * @return The content hash, or null if not found
     */
    fun getHash(modulePath: String?, name: String): MezzeHash? {
        return nameToHash[QualifiedName(modulePath, name)]
    }

    /**
     * Get the hash for a BindingKey.
     * This is the preferred method - uses structured data instead of string parsing.
     *
     * @param key The binding key with qualifier and local name
     * @return The content hash, or null if not found
     */
    fun getHash(key: BindingKey): MezzeHash? {
        return nameToHash[QualifiedName(key.qualifier, key.localName)]
    }

    /**
     * Get all names that refer to a given hash.
     *
     * @param hash The content hash
     * @return Set of qualified names (may be empty)
     */
    fun getNames(hash: MezzeHash): Set<QualifiedName> {
        return hashToNames[hash]?.toSet() ?: emptySet()
    }

    /**
     * Get the primary name for a hash (the first registered name).
     *
     * @param hash The content hash
     * @return A qualified name, or null if no names are registered
     */
    fun getPrimaryName(hash: MezzeHash): QualifiedName? {
        return hashToNames[hash]?.firstOrNull()
    }

    /**
     * Check if a name is registered.
     */
    fun hasName(modulePath: String?, name: String): Boolean {
        return nameToHash.containsKey(QualifiedName(modulePath, name))
    }

    /**
     * Check if a BindingKey is registered.
     * This is the preferred method - uses structured data.
     */
    fun hasName(key: BindingKey): Boolean {
        return nameToHash.containsKey(QualifiedName(key.qualifier, key.localName))
    }

    /**
     * Check if a hash is registered.
     */
    fun hasHash(hash: MezzeHash): Boolean {
        return hashToNames.containsKey(hash)
    }

    /**
     * Remove a name mapping.
     *
     * @return The hash that was associated with the name, or null if not found
     */
    fun removeName(modulePath: String?, name: String): MezzeHash? {
        val qualifiedName = QualifiedName(modulePath, name)
        val hash = nameToHash.remove(qualifiedName) ?: return null
        hashToNames[hash]?.remove(qualifiedName)
        if (hashToNames[hash]?.isEmpty() == true) {
            hashToNames.remove(hash)
        }
        return hash
    }

    /**
     * Remove a BindingKey mapping.
     * This is the preferred method - uses structured data.
     *
     * @return The hash that was associated with the key, or null if not found
     */
    fun removeName(key: BindingKey): MezzeHash? {
        return removeName(key.qualifier, key.localName)
    }

    /**
     * Remove all names for a hash.
     *
     * @return The set of names that were removed
     */
    fun removeHash(hash: MezzeHash): Set<QualifiedName> {
        val names = hashToNames.remove(hash) ?: return emptySet()
        for (name in names) {
            nameToHash.remove(name)
        }
        return names
    }

    /**
     * Get all registered names.
     */
    fun getAllNames(): Set<QualifiedName> = nameToHash.keys.toSet()

    /**
     * Get all registered hashes.
     */
    fun getAllHashes(): Set<MezzeHash> = hashToNames.keys.toSet()

    /**
     * Get the total number of registered names.
     */
    val size: Int get() = nameToHash.size

    /**
     * Check if the registry is empty.
     */
    fun isEmpty(): Boolean = nameToHash.isEmpty()

    /**
     * Clear all mappings.
     */
    fun clear() {
        nameToHash.clear()
        hashToNames.clear()
    }

    /**
     * Merge another registry into this one.
     * If there are conflicts (same name, different hash), the other registry wins.
     */
    fun merge(other: NameRegistry) {
        for ((name, hash) in other.nameToHash) {
            register(name.modulePath, name.name, hash)
        }
    }

    /**
     * Create a snapshot of the current state.
     */
    fun snapshot(): NameRegistrySnapshot {
        return NameRegistrySnapshot(
            mappings = nameToHash.map { (name, hash) ->
                NameRegistrySnapshot.Mapping(name.modulePath, name.name, hash)
            }
        )
    }

    /**
     * Restore from a snapshot.
     */
    fun restore(snapshot: NameRegistrySnapshot) {
        clear()
        for (mapping in snapshot.mappings) {
            register(mapping.modulePath, mapping.name, mapping.hash)
        }
    }

    companion object {
        /**
         * Create a registry from a snapshot.
         */
        fun fromSnapshot(snapshot: NameRegistrySnapshot): NameRegistry {
            val registry = NameRegistry()
            registry.restore(snapshot)
            return registry
        }
    }
}

/**
 * Immutable snapshot of a NameRegistry for serialization/persistence.
 */
data class NameRegistrySnapshot(
    val mappings: List<Mapping>
) {
    data class Mapping(
        val modulePath: String?,
        val name: String,
        val hash: MezzeHash
    )
}
