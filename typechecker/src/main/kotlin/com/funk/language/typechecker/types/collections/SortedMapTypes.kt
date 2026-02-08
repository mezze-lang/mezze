package com.funk.language.typechecker.types.collections

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: FunkType): FunkType {
    return FunkType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for SortedMap operations.
 *
 * Corresponds to stdlib file:
 * - std/collections/sorted_map.funk
 *
 * SortedMap is backed by Vavr TreeMap for O(log n) operations with keys in sorted order.
 */
object SortedMapTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerSortedMapNatives(context)
    }

    private fun registerSortedMapNatives(context: MutableMap<String, FunkType>) {
        // __native_sorted_map_empty: {} -> SortedMap 'k 'v
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_empty"] = FunkType.FunctionType(
                FunkType.RecordType(emptyMap()),
                FunkType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_insert: {m: SortedMap 'k 'v, k: 'k, v: 'v} -> SortedMap 'k 'v
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_insert"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "m" to FunkType.SortedMapType(keyType, valueType),
                    "k" to keyType,
                    "v" to valueType
                )),
                FunkType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_get: {m: SortedMap 'k 'v, k: 'k} -> Option 'v
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_get"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "m" to FunkType.SortedMapType(keyType, valueType),
                    "k" to keyType
                )),
                optionType(valueType)
            )
        }

        // __native_sorted_map_remove: {m: SortedMap 'k 'v, k: 'k} -> SortedMap 'k 'v
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_remove"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "m" to FunkType.SortedMapType(keyType, valueType),
                    "k" to keyType
                )),
                FunkType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_contains_key: {m: SortedMap 'k 'v, k: 'k} -> Bool
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_contains_key"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "m" to FunkType.SortedMapType(keyType, valueType),
                    "k" to keyType
                )),
                FunkType.BooleanType
            )
        }

        // __native_sorted_map_size: {m: SortedMap 'k 'v} -> Int
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_size"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("m" to FunkType.SortedMapType(keyType, valueType))),
                FunkType.IntType
            )
        }

        // __native_sorted_map_is_empty: {m: SortedMap 'k 'v} -> Bool
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_is_empty"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("m" to FunkType.SortedMapType(keyType, valueType))),
                FunkType.BooleanType
            )
        }

        // __native_sorted_map_keys: {m: SortedMap 'k 'v} -> List 'k
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_keys"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("m" to FunkType.SortedMapType(keyType, valueType))),
                FunkType.ListType(keyType)
            )
        }

        // __native_sorted_map_values: {m: SortedMap 'k 'v} -> List 'v
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_values"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("m" to FunkType.SortedMapType(keyType, valueType))),
                FunkType.ListType(valueType)
            )
        }

        // __native_sorted_map_entries: {m: SortedMap 'k 'v} -> List {key: 'k, value: 'v}
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_entries"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("m" to FunkType.SortedMapType(keyType, valueType))),
                FunkType.ListType(FunkType.RecordType(mapOf("key" to keyType, "value" to valueType)))
            )
        }

        // __native_sorted_map_min_key: {m: SortedMap 'k 'v} -> Option 'k
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_min_key"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("m" to FunkType.SortedMapType(keyType, valueType))),
                optionType(keyType)
            )
        }

        // __native_sorted_map_max_key: {m: SortedMap 'k 'v} -> Option 'k
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_max_key"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("m" to FunkType.SortedMapType(keyType, valueType))),
                optionType(keyType)
            )
        }

        // __native_sorted_map_min_entry: {m: SortedMap 'k 'v} -> Option {key: 'k, value: 'v}
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_min_entry"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("m" to FunkType.SortedMapType(keyType, valueType))),
                optionType(FunkType.RecordType(mapOf("key" to keyType, "value" to valueType)))
            )
        }

        // __native_sorted_map_max_entry: {m: SortedMap 'k 'v} -> Option {key: 'k, value: 'v}
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_max_entry"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("m" to FunkType.SortedMapType(keyType, valueType))),
                optionType(FunkType.RecordType(mapOf("key" to keyType, "value" to valueType)))
            )
        }

        // __native_sorted_map_from_map: {m: Map 'k 'v} -> SortedMap 'k 'v
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_from_map"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("m" to FunkType.MapType(keyType, valueType))),
                FunkType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_to_map: {m: SortedMap 'k 'v} -> Map 'k 'v
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_to_map"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("m" to FunkType.SortedMapType(keyType, valueType))),
                FunkType.MapType(keyType, valueType)
            )
        }

        // __native_sorted_map_equals: {m1: SortedMap 'k 'v, m2: SortedMap 'k 'v} -> Bool
        run {
            val keyType = FunkType.VarType(TypeVar("k"))
            val valueType = FunkType.VarType(TypeVar("v"))
            context["__native_sorted_map_equals"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "m1" to FunkType.SortedMapType(keyType, valueType),
                    "m2" to FunkType.SortedMapType(keyType, valueType)
                )),
                FunkType.BooleanType
            )
        }
    }
}
