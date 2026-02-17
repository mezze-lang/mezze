package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for SortedMap operations.
 *
 * Corresponds to stdlib file:
 * - std/collections/sorted_map.mz
 *
 * SortedMap is backed by Vavr TreeMap for O(log n) operations with keys in sorted order.
 */
object SortedMapTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerSortedMapNatives(context)
    }

    private fun registerSortedMapNatives(context: MutableMap<String, MezzeType>) {
        // __native_sorted_map_empty: {} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_insert: {m: SortedMap 'k 'v, k: 'k, v: 'v} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_insert"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "k" to keyType,
                    "v" to valueType
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_get: {m: SortedMap 'k 'v, k: 'k} -> Option 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_get"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "k" to keyType
                )),
                optionType(valueType)
            )
        }

        // __native_sorted_map_remove: {m: SortedMap 'k 'v, k: 'k} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_remove"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "k" to keyType
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_contains_key: {m: SortedMap 'k 'v, k: 'k} -> Bool
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_contains_key"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "k" to keyType
                )),
                MezzeType.BooleanType
            )
        }

        // __native_sorted_map_size: {m: SortedMap 'k 'v} -> Int
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_size"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                MezzeType.IntType
            )
        }

        // __native_sorted_map_is_empty: {m: SortedMap 'k 'v} -> Bool
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_is_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                MezzeType.BooleanType
            )
        }

        // __native_sorted_map_keys: {m: SortedMap 'k 'v} -> List 'k
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_keys"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                MezzeType.ListType(keyType)
            )
        }

        // __native_sorted_map_values: {m: SortedMap 'k 'v} -> List 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_values"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                MezzeType.ListType(valueType)
            )
        }

        // __native_sorted_map_entries: {m: SortedMap 'k 'v} -> List {key: 'k, value: 'v}
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_entries"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                MezzeType.ListType(MezzeType.RecordType(mapOf("key" to keyType, "value" to valueType)))
            )
        }

        // __native_sorted_map_min_key: {m: SortedMap 'k 'v} -> Option 'k
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_min_key"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                optionType(keyType)
            )
        }

        // __native_sorted_map_max_key: {m: SortedMap 'k 'v} -> Option 'k
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_max_key"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                optionType(keyType)
            )
        }

        // __native_sorted_map_min_entry: {m: SortedMap 'k 'v} -> Option {key: 'k, value: 'v}
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_min_entry"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                optionType(MezzeType.RecordType(mapOf("key" to keyType, "value" to valueType)))
            )
        }

        // __native_sorted_map_max_entry: {m: SortedMap 'k 'v} -> Option {key: 'k, value: 'v}
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_max_entry"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                optionType(MezzeType.RecordType(mapOf("key" to keyType, "value" to valueType)))
            )
        }

        // __native_sorted_map_from_map: {m: Map 'k 'v} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_from_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.MapType(keyType, valueType))),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_to_map: {m: SortedMap 'k 'v} -> Map 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_to_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                MezzeType.MapType(keyType, valueType)
            )
        }

        // __native_sorted_map_equals: {m1: SortedMap 'k 'v, m2: SortedMap 'k 'v} -> Bool
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_equals"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m1" to MezzeType.SortedMapType(keyType, valueType),
                    "m2" to MezzeType.SortedMapType(keyType, valueType)
                )),
                MezzeType.BooleanType
            )
        }
    }
}
