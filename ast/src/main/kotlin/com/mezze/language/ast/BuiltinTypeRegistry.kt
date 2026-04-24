package com.mezze.language.ast

/**
 * Central registry mapping built-in types to their home stdlib modules.
 *
 * Even though built-in types are defined internally (not in .mz files),
 * each has a canonical "home" module where ability impls and inherent
 * impls are allowed. This enforces coherence: `impl NumAdd for Int`
 * can only live in `std::int`.
 *
 * This is the single source of truth for type → home module mappings.
 */
object BuiltinTypeRegistry {

    /**
     * All built-in type names mapped to their home stdlib module path.
     *
     * Primitives are derived from PrimitiveTypes, core/collection types
     * from BuiltinTypes constants.
     */
    private val typeHomeModule: Map<String, String> = buildMap {
        // Primitives → std::<type>
        for (name in PrimitiveTypes.allNames) {
            val moduleName = when (PrimitiveTypes.fromName(name)) {
                MezzeType.StringType -> "std::str"
                MezzeType.BytesType -> "std::collections::bytes"
                MezzeType.ByteBufferType -> "std::collections::buffer"
                else -> "std::${name.lowercase()}"
            }
            put(name, moduleName)
        }

        // Core types
        put(BuiltinTypes.OPTION, "std::option")
        put(BuiltinTypes.RESULT, "std::result")

        // Collection types
        put(BuiltinTypes.LIST, "std::collections::list")
        put(BuiltinTypes.MAP, "std::collections::map")
        put(BuiltinTypes.SET, "std::collections::set")
        put(BuiltinTypes.SEQ, "std::collections::seq")
        put(BuiltinTypes.SORTED_MAP, "std::collections::sorted_map")
        put(BuiltinTypes.SORTED_SET, "std::collections::sorted_set")
        put(BuiltinTypes.DEQUE, "std::collections::deque")
        put(BuiltinTypes.NON_EMPTY_LIST, "std::collections::nonemptylist")
    }

    /**
     * Get all built-in type names whose home module matches the given path.
     * These types should be treated as "local" for orphan rule purposes.
     */
    fun getLocalTypesForModule(modulePath: String): Set<String> =
        typeHomeModule.entries
            .filter { it.value == modulePath }
            .map { it.key }
            .toSet()
}
