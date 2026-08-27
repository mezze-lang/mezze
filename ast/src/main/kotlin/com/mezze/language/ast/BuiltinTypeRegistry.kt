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
    private val typeHomeModule: Map<String, ModulePath> = buildMap {
        // Primitives → std::<type>
        for (name in PrimitiveTypes.allNames) {
            val modulePath = when (PrimitiveTypes.fromName(name)) {
                MezzeType.StringType -> ModulePath(listOf("std", "str"))
                MezzeType.BytesType -> ModulePath(listOf("std", "collections", "bytes"))
                else -> ModulePath(listOf("std", name.lowercase()))
            }
            put(name, modulePath)
        }

        // Core types
        put(BuiltinTypes.OPTION, ModulePath(listOf("std", "option")))
        put(BuiltinTypes.RESULT, ModulePath(listOf("std", "result")))

        // Collection types
        put(BuiltinTypes.LIST, ModulePath(listOf("std", "collections", "list")))
        put(BuiltinTypes.MAP, ModulePath(listOf("std", "collections", "map")))
        put(BuiltinTypes.SET, ModulePath(listOf("std", "collections", "set")))
        put(BuiltinTypes.SEQ, ModulePath(listOf("std", "collections", "seq")))
        put(BuiltinTypes.SORTED_MAP, ModulePath(listOf("std", "collections", "sorted_map")))
        put(BuiltinTypes.SORTED_SET, ModulePath(listOf("std", "collections", "sorted_set")))
        put(BuiltinTypes.DEQUE, ModulePath(listOf("std", "collections", "deque")))
        put(BuiltinTypes.NON_EMPTY_LIST, ModulePath(listOf("std", "collections", "nonemptylist")))
        put(BuiltinTypes.ITER, ModulePath(listOf("std", "collections", "iter")))
    }

    /**
     * Get all built-in type names whose home module matches the given path.
     * These types should be treated as "local" for orphan rule purposes.
     */
    fun getLocalTypesForModule(modulePath: ModulePath): Set<String> =
        typeHomeModule.entries
            .filter { it.value == modulePath }
            .map { it.key }
            .toSet()
}
