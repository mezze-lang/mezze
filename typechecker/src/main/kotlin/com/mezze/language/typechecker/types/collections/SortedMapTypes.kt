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
                MezzeType.BoolType
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
                MezzeType.BoolType
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
                MezzeType.BoolType
            )
        }

        // __native_sorted_map_singleton: {k: 'k, v: 'v} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_singleton"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // ==================== Higher-Order Functions ====================

        // __native_sorted_map_filter: {m: SortedMap 'k 'v, p: {k: 'k, v: 'v} -> Bool} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_filter"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_fold: {m: SortedMap 'k 'v, init: 'acc, f: {acc: 'acc, k: 'k, v: 'v} -> 'acc} -> 'acc
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val accType = MezzeType.VarType(TypeVar("acc"))
            context["__native_sorted_map_fold"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "init" to accType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("acc" to accType, "k" to keyType, "v" to valueType)),
                        accType
                    )
                )),
                accType
            )
        }

        // __native_sorted_map_map_values: {m: SortedMap 'k 'v, f: {val: 'v} -> 'w} -> SortedMap 'k 'w
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val resultType = MezzeType.VarType(TypeVar("w"))
            context["__native_sorted_map_map_values"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to valueType)),
                        resultType
                    )
                )),
                MezzeType.SortedMapType(keyType, resultType)
            )
        }

        // __native_sorted_map_any: {m: SortedMap 'k 'v, p: {k: 'k, v: 'v} -> Bool} -> Bool
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_any"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.BoolType
            )
        }

        // __native_sorted_map_all: {m: SortedMap 'k 'v, p: {k: 'k, v: 'v} -> Bool} -> Bool
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_all"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.BoolType
            )
        }

        // __native_sorted_map_none: {m: SortedMap 'k 'v, p: {k: 'k, v: 'v} -> Bool} -> Bool
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_none"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.BoolType
            )
        }

        // __native_sorted_map_find: {m: SortedMap 'k 'v, p: {k: 'k, v: 'v} -> Bool} -> Option {k: 'k, v: 'v}
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_find"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)),
                        MezzeType.BoolType
                    )
                )),
                optionType(MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)))
            )
        }

        // __native_sorted_map_count: {m: SortedMap 'k 'v, p: {k: 'k, v: 'v} -> Bool} -> Int
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_count"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.IntType
            )
        }

        // ==================== Additional Operations ====================

        // __native_sorted_map_contains_value: {m: SortedMap 'k 'v, v: 'v} -> Bool
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_contains_value"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "v" to valueType
                )),
                MezzeType.BoolType
            )
        }

        // __native_sorted_map_get_or_default: {m: SortedMap 'k 'v, k: 'k, default: 'v} -> 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_get_or_default"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "k" to keyType,
                    "default" to valueType
                )),
                valueType
            )
        }

        // __native_sorted_map_update: {m: SortedMap 'k 'v, k: 'k, f: {val: Option 'v} -> 'v} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_update"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "k" to keyType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to optionType(valueType))),
                        valueType
                    )
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_merge: {m1: SortedMap 'k 'v, m2: SortedMap 'k 'v} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_merge"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m1" to MezzeType.SortedMapType(keyType, valueType),
                    "m2" to MezzeType.SortedMapType(keyType, valueType)
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_merge_with: {m1: SortedMap 'k 'v, m2: SortedMap 'k 'v, f: {l: 'v, r: 'v} -> 'v} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_merge_with"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m1" to MezzeType.SortedMapType(keyType, valueType),
                    "m2" to MezzeType.SortedMapType(keyType, valueType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("l" to valueType, "r" to valueType)),
                        valueType
                    )
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // ==================== Conversion ====================

        // __native_sorted_map_to_seq: {m: SortedMap 'k 'v} -> [{k: 'k, v: 'v}]
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_to_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                MezzeType.SeqType(MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)))
            )
        }

        // __native_sorted_map_keys_seq: {m: SortedMap 'k 'v} -> ['k]
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_keys_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                MezzeType.SeqType(keyType)
            )
        }

        // __native_sorted_map_values_seq: {m: SortedMap 'k 'v} -> ['v]
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_values_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                MezzeType.SeqType(valueType)
            )
        }

        // ==================== Task #15: Navigation Operations ====================

        // __native_sorted_map_partition: {m: SortedMap 'k 'v, p: {k: 'k, v: 'v} -> Bool} -> {pass: SortedMap 'k 'v, fail: SortedMap 'k 'v}
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val mapType = MezzeType.SortedMapType(keyType, valueType)
            context["__native_sorted_map_partition"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to mapType,
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.RecordType(mapOf("pass" to mapType, "fail" to mapType))
            )
        }

        // __native_sorted_map_range: {m: SortedMap 'k 'v, lo: 'k, hi: 'k} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_range"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "lo" to keyType,
                    "hi" to keyType
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_floor_key: {m: SortedMap 'k 'v, k: 'k} -> Option 'k
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_floor_key"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "k" to keyType
                )),
                optionType(keyType)
            )
        }

        // __native_sorted_map_ceiling_key: {m: SortedMap 'k 'v, k: 'k} -> Option 'k
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_ceiling_key"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "k" to keyType
                )),
                optionType(keyType)
            )
        }

        // __native_sorted_map_lower_key: {m: SortedMap 'k 'v, k: 'k} -> Option 'k
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_lower_key"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "k" to keyType
                )),
                optionType(keyType)
            )
        }

        // __native_sorted_map_higher_key: {m: SortedMap 'k 'v, k: 'k} -> Option 'k
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_higher_key"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "k" to keyType
                )),
                optionType(keyType)
            )
        }

        // __native_sorted_map_floor_entry: {m: SortedMap 'k 'v, k: 'k} -> Option {k: 'k, v: 'v}
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_floor_entry"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "k" to keyType
                )),
                optionType(MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)))
            )
        }

        // __native_sorted_map_ceiling_entry: {m: SortedMap 'k 'v, k: 'k} -> Option {k: 'k, v: 'v}
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_ceiling_entry"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "k" to keyType
                )),
                optionType(MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)))
            )
        }

        // ==================== Task #16: View Operations ====================

        // __native_sorted_map_head_map: {m: SortedMap 'k 'v, to: 'k} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_head_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "to" to keyType
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_tail_map: {m: SortedMap 'k 'v, from: 'k} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_tail_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "from" to keyType
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_sub_map: {m: SortedMap 'k 'v, lo: 'k, hi: 'k} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_sub_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "lo" to keyType,
                    "hi" to keyType
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_take: {m: SortedMap 'k 'v, n: Int} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_take"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "n" to MezzeType.IntType
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_drop: {m: SortedMap 'k 'v, n: Int} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_drop"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "n" to MezzeType.IntType
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // ==================== Additional HOF Operations ====================

        // __native_sorted_map_map_keys: {m: SortedMap 'k 'v, f: {val: 'k} -> 'k2} -> SortedMap 'k2 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val newKeyType = MezzeType.VarType(TypeVar("k2"))
            context["__native_sorted_map_map_keys"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to keyType)),
                        newKeyType
                    )
                )),
                MezzeType.SortedMapType(newKeyType, valueType)
            )
        }

        // __native_sorted_map_filter_keys: {m: SortedMap 'k 'v, p: {val: 'k} -> Bool} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_filter_keys"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to keyType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_filter_values: {m: SortedMap 'k 'v, p: {val: 'v} -> Bool} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_filter_values"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to MezzeType.SortedMapType(keyType, valueType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to valueType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }

        // __native_sorted_map_invert: {m: SortedMap 'k 'v} -> SortedMap 'v 'k
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_invert"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                MezzeType.SortedMapType(valueType, keyType)
            )
        }

        // ==================== List Conversions ====================

        // __native_sorted_map_to_list: {m: SortedMap 'k 'v} -> List {k: 'k, v: 'v}
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_to_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to MezzeType.SortedMapType(keyType, valueType))),
                MezzeType.ListType(MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)))
            )
        }

        // __native_sorted_map_from_list: {xs: List {k: 'k, v: 'v}} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            context["__native_sorted_map_from_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to MezzeType.ListType(MezzeType.RecordType(mapOf("k" to keyType, "v" to valueType)))
                )),
                MezzeType.SortedMapType(keyType, valueType)
            )
        }
    }
}
