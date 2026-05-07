package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.MezzeType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return com.mezze.language.typechecker.util.WellKnownTypeApp.of(BuiltinTypes.OPTION, listOf(elementType))
}

/**
 * Type signatures for Map (key-value dictionary) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/map.mz
 */
object MapTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerMapNatives(context)
    }

    private fun registerMapNatives(context: MutableMap<String, MezzeType>) {
        val mapKeyTypeVar = com.mezze.language.ast.TypeVar("k")
        val mapValTypeVar = com.mezze.language.ast.TypeVar("v")
        val mapValTypeVarW = com.mezze.language.ast.TypeVar("w")
        val mapAccTypeVar = com.mezze.language.ast.TypeVar("acc")

        val mapType = MezzeType.MapType(MezzeType.VarType(mapKeyTypeVar), MezzeType.VarType(mapValTypeVar))
        val mapTypeKW = MezzeType.MapType(MezzeType.VarType(mapKeyTypeVar), MezzeType.VarType(mapValTypeVarW))

        context["__native_map_empty"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            mapType
        )

        context["__native_map_insert"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "k" to MezzeType.VarType(mapKeyTypeVar),
                "v" to MezzeType.VarType(mapValTypeVar)
            )),
            mapType
        )

        context["__native_map_get"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "k" to MezzeType.VarType(mapKeyTypeVar)
            )),
            optionType(MezzeType.VarType(mapValTypeVar))
        )

        context["__native_map_remove"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "k" to MezzeType.VarType(mapKeyTypeVar)
            )),
            mapType
        )

        context["__native_map_contains_key"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "k" to MezzeType.VarType(mapKeyTypeVar)
            )),
            MezzeType.BoolType
        )

        context["__native_map_size"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.IntType
        )

        context["__native_map_keys"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.ListType(MezzeType.VarType(mapKeyTypeVar))
        )

        context["__native_map_values"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.ListType(MezzeType.VarType(mapValTypeVar))
        )

        context["__native_map_entries"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.SeqType(MezzeType.RecordType(mapOf(
                "key" to MezzeType.VarType(mapKeyTypeVar),
                "value" to MezzeType.VarType(mapValTypeVar)
            )))
        )

        context["__native_map_map_values"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(mapValTypeVar))),
                    MezzeType.VarType(mapValTypeVarW)
                )
            )),
            mapTypeKW
        )

        context["__native_map_filter"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "p" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "k" to MezzeType.VarType(mapKeyTypeVar),
                        "v" to MezzeType.VarType(mapValTypeVar)
                    )),
                    MezzeType.BoolType
                )
            )),
            mapType
        )

        context["__native_map_fold"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "init" to MezzeType.VarType(mapAccTypeVar),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "acc" to MezzeType.VarType(mapAccTypeVar),
                        "k" to MezzeType.VarType(mapKeyTypeVar),
                        "v" to MezzeType.VarType(mapValTypeVar)
                    )),
                    MezzeType.VarType(mapAccTypeVar)
                )
            )),
            MezzeType.VarType(mapAccTypeVar)
        )

        context["__native_map_from_keys"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ks" to MezzeType.ListType(MezzeType.VarType(mapKeyTypeVar)),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(mapKeyTypeVar))),
                    MezzeType.VarType(mapValTypeVar)
                )
            )),
            mapType
        )

        context["__native_map_merge"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m1" to mapType,
                "m2" to mapType
            )),
            mapType
        )

        context["__native_map_merge_with"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m1" to mapType,
                "m2" to mapType,
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "l" to MezzeType.VarType(mapValTypeVar),
                        "r" to MezzeType.VarType(mapValTypeVar)
                    )),
                    MezzeType.VarType(mapValTypeVar)
                )
            )),
            mapType
        )

        context["__native_map_remove_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "ks" to MezzeType.ListType(MezzeType.VarType(mapKeyTypeVar))
            )),
            mapType
        )

        // __native_map_equals: {m1: Map 'k 'v, m2: Map 'k 'v} -> Bool
        context["__native_map_equals"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m1" to mapType,
                "m2" to mapType
            )),
            MezzeType.BoolType
        )

        // ==================== Predicate Operations ====================

        val kvPredicateType = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "k" to MezzeType.VarType(mapKeyTypeVar),
                "v" to MezzeType.VarType(mapValTypeVar)
            )),
            MezzeType.BoolType
        )

        // __native_map_any: {m: Map 'k 'v, p: {k: 'k, v: 'v} -> Bool} -> Bool
        context["__native_map_any"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "p" to kvPredicateType
            )),
            MezzeType.BoolType
        )

        // __native_map_all: {m: Map 'k 'v, p: {k: 'k, v: 'v} -> Bool} -> Bool
        context["__native_map_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "p" to kvPredicateType
            )),
            MezzeType.BoolType
        )

        // __native_map_none: {m: Map 'k 'v, p: {k: 'k, v: 'v} -> Bool} -> Bool
        context["__native_map_none"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "p" to kvPredicateType
            )),
            MezzeType.BoolType
        )

        // __native_map_find: {m: Map 'k 'v, p: {k: 'k, v: 'v} -> Bool} -> Option {k: 'k, v: 'v}
        context["__native_map_find"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "p" to kvPredicateType
            )),
            optionType(MezzeType.RecordType(mapOf(
                "k" to MezzeType.VarType(mapKeyTypeVar),
                "v" to MezzeType.VarType(mapValTypeVar)
            )))
        )

        // __native_map_count: {m: Map 'k 'v, p: {k: 'k, v: 'v} -> Bool} -> Int
        context["__native_map_count"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "p" to kvPredicateType
            )),
            MezzeType.IntType
        )

        // ==================== Additional Operations ====================

        // __native_map_contains_value: {m: Map 'k 'v, v: 'v} -> Bool
        context["__native_map_contains_value"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "v" to MezzeType.VarType(mapValTypeVar)
            )),
            MezzeType.BoolType
        )

        // __native_map_update: {m: Map 'k 'v, k: 'k, f: {val: Option 'v} -> 'v} -> Map 'k 'v
        context["__native_map_update"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "k" to MezzeType.VarType(mapKeyTypeVar),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to optionType(MezzeType.VarType(mapValTypeVar)))),
                    MezzeType.VarType(mapValTypeVar)
                )
            )),
            mapType
        )

        // __native_map_get_or_default: {m: Map 'k 'v, k: 'k, default: 'v} -> 'v
        context["__native_map_get_or_default"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "k" to MezzeType.VarType(mapKeyTypeVar),
                "default" to MezzeType.VarType(mapValTypeVar)
            )),
            MezzeType.VarType(mapValTypeVar)
        )

        // __native_map_singleton: {k: 'k, v: 'v} -> Map 'k 'v
        context["__native_map_singleton"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "k" to MezzeType.VarType(mapKeyTypeVar),
                "v" to MezzeType.VarType(mapValTypeVar)
            )),
            mapType
        )

        // ==================== Conversion to Seq ====================

        // __native_map_to_seq: {m: Map 'k 'v} -> [{k: 'k, v: 'v}]
        context["__native_map_to_seq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.SeqType(MezzeType.RecordType(mapOf(
                "k" to MezzeType.VarType(mapKeyTypeVar),
                "v" to MezzeType.VarType(mapValTypeVar)
            )))
        )

        // __native_map_keys_seq: {m: Map 'k 'v} -> ['k]
        context["__native_map_keys_seq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.SeqType(MezzeType.VarType(mapKeyTypeVar))
        )

        // __native_map_values_seq: {m: Map 'k 'v} -> ['v]
        context["__native_map_values_seq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.SeqType(MezzeType.VarType(mapValTypeVar))
        )

        // __native_map_partition: {m: Map 'k 'v, p: {k: 'k, v: 'v} -> Bool} -> {pass: Map 'k 'v, fail: Map 'k 'v}
        context["__native_map_partition"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "p" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "k" to MezzeType.VarType(mapKeyTypeVar),
                        "v" to MezzeType.VarType(mapValTypeVar)
                    )),
                    MezzeType.BoolType
                )
            )),
            MezzeType.RecordType(mapOf("pass" to mapType, "fail" to mapType))
        )

        // ==================== Task #21: Min/Max Operations ====================

        // __native_map_min_key: {m: Map 'k 'v} -> Option 'k
        context["__native_map_min_key"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            optionType(MezzeType.VarType(mapKeyTypeVar))
        )

        // __native_map_max_key: {m: Map 'k 'v} -> Option 'k
        context["__native_map_max_key"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            optionType(MezzeType.VarType(mapKeyTypeVar))
        )

        // __native_map_min_value: {m: Map 'k 'v} -> Option 'v
        context["__native_map_min_value"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            optionType(MezzeType.VarType(mapValTypeVar))
        )

        // __native_map_max_value: {m: Map 'k 'v} -> Option 'v
        context["__native_map_max_value"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            optionType(MezzeType.VarType(mapValTypeVar))
        )

        // __native_map_invert: {m: Map 'k 'v} -> Map 'v 'k
        context["__native_map_invert"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.MapType(MezzeType.VarType(mapValTypeVar), MezzeType.VarType(mapKeyTypeVar))
        )

        // ==================== Task #22: Key/Value Operations ====================

        // __native_map_select_keys: {m: Map 'k 'v, ks: ['k]} -> Map 'k 'v
        context["__native_map_select_keys"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "ks" to MezzeType.SeqType(MezzeType.VarType(mapKeyTypeVar))
            )),
            mapType
        )

        // __native_map_reject_keys: {m: Map 'k 'v, ks: ['k]} -> Map 'k 'v
        context["__native_map_reject_keys"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "ks" to MezzeType.SeqType(MezzeType.VarType(mapKeyTypeVar))
            )),
            mapType
        )

        // __native_map_reduce: {m: Map 'k 'v, f: {acc: {k: 'k, v: 'v}, entry: {k: 'k, v: 'v}} -> {k: 'k, v: 'v}} -> Option {k: 'k, v: 'v}
        run {
            val entryType = MezzeType.RecordType(mapOf(
                "k" to MezzeType.VarType(mapKeyTypeVar),
                "v" to MezzeType.VarType(mapValTypeVar)
            ))
            context["__native_map_reduce"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to mapType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("acc" to entryType, "entry" to entryType)),
                        entryType
                    )
                )),
                optionType(entryType)
            )
        }

        // __native_map_group_by_value: {m: Map 'k 'v} -> Map 'v (List 'k)
        context["__native_map_group_by_value"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.MapType(MezzeType.VarType(mapValTypeVar), MezzeType.ListType(MezzeType.VarType(mapKeyTypeVar)))
        )

        // ==================== Task #29: Key Operations ====================

        // __native_map_map_keys: {m: Map 'k 'v, f: {val: 'k} -> 'k2} -> Map 'k2 'v
        run {
            val newKeyTypeVar = com.mezze.language.ast.TypeVar("k2")
            context["__native_map_map_keys"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to mapType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to MezzeType.VarType(mapKeyTypeVar))),
                        MezzeType.VarType(newKeyTypeVar)
                    )
                )),
                MezzeType.MapType(MezzeType.VarType(newKeyTypeVar), MezzeType.VarType(mapValTypeVar))
            )
        }

        // __native_map_filter_keys: {m: Map 'k 'v, p: {val: 'k} -> Bool} -> Map 'k 'v
        context["__native_map_filter_keys"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "p" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(mapKeyTypeVar))),
                    MezzeType.BoolType
                )
            )),
            mapType
        )

        // __native_map_filter_values: {m: Map 'k 'v, p: {val: 'v} -> Bool} -> Map 'k 'v
        context["__native_map_filter_values"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "p" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(mapValTypeVar))),
                    MezzeType.BoolType
                )
            )),
            mapType
        )

        // ==================== Task #37: Multi-Map Operations ====================

        // __native_map_zip_maps: {m1: Map 'k 'v1, m2: Map 'k 'v2} -> Map 'k {first: 'v1, second: 'v2}
        run {
            val v1Type = MezzeType.VarType(com.mezze.language.ast.TypeVar("v1"))
            val v2Type = MezzeType.VarType(com.mezze.language.ast.TypeVar("v2"))
            context["__native_map_zip_maps"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m1" to MezzeType.MapType(MezzeType.VarType(mapKeyTypeVar), v1Type),
                    "m2" to MezzeType.MapType(MezzeType.VarType(mapKeyTypeVar), v2Type)
                )),
                MezzeType.MapType(
                    MezzeType.VarType(mapKeyTypeVar),
                    MezzeType.RecordType(mapOf("fst" to v1Type, "snd" to v2Type))
                )
            )
        }

        // __native_map_diff_maps: {m1: Map 'k 'v, m2: Map 'k 'v} -> Map 'k 'v
        context["__native_map_diff_maps"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m1" to mapType, "m2" to mapType)),
            mapType
        )

        // __native_map_intersect_maps: {m1: Map 'k 'v, m2: Map 'k 'v} -> Map 'k 'v
        context["__native_map_intersect_maps"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m1" to mapType, "m2" to mapType)),
            mapType
        )

        // __native_map_symmetric_diff_maps: {m1: Map 'k 'v, m2: Map 'k 'v} -> Map 'k 'v
        context["__native_map_symmetric_diff_maps"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m1" to mapType, "m2" to mapType)),
            mapType
        )

        // ==================== List and SortedMap Conversions ====================

        // __native_map_to_list: {m: Map 'k 'v} -> List {k: 'k, v: 'v}
        context["__native_map_to_list"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.ListType(MezzeType.RecordType(mapOf(
                "k" to MezzeType.VarType(mapKeyTypeVar),
                "v" to MezzeType.VarType(mapValTypeVar)
            )))
        )

        // __native_map_from_list: {xs: List {k: 'k, v: 'v}} -> Map 'k 'v
        context["__native_map_from_list"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.ListType(MezzeType.RecordType(mapOf(
                    "k" to MezzeType.VarType(mapKeyTypeVar),
                    "v" to MezzeType.VarType(mapValTypeVar)
                )))
            )),
            mapType
        )

        // __native_map_flat_map: {m: Map 'k 'v, f: {k: 'k, v: 'v} -> Map 'k2 'v2} -> Map 'k2 'v2
        run {
            val k2TypeVar = com.mezze.language.ast.TypeVar("k2")
            val v2TypeVar = com.mezze.language.ast.TypeVar("v2")
            context["__native_map_flat_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "m" to mapType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf(
                            "k" to MezzeType.VarType(mapKeyTypeVar),
                            "v" to MezzeType.VarType(mapValTypeVar)
                        )),
                        MezzeType.MapType(MezzeType.VarType(k2TypeVar), MezzeType.VarType(v2TypeVar))
                    )
                )),
                MezzeType.MapType(MezzeType.VarType(k2TypeVar), MezzeType.VarType(v2TypeVar))
            )
        }

        // __native_map_to_sorted_map: {m: Map 'k 'v} -> SortedMap 'k 'v
        context["__native_map_to_sorted_map"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.SortedMapType(MezzeType.VarType(mapKeyTypeVar), MezzeType.VarType(mapValTypeVar))
        )
    }
}
