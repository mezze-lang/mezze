package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

object MapTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_map_empty") { val k = tvar("k"); val v = tvar("v"); returns(map(k, v)) }
        fn("__native_map_insert") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v), "k" to k, "v" to v); returns(map(k, v)) }
        fn("__native_map_get") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v), "k" to k); returns(option(v)) }
        fn("__native_map_remove") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v), "k" to k); returns(map(k, v)) }
        fn("__native_map_contains_key") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v), "k" to k); returns(bool) }
        fn("__native_map_size") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(int) }
        fn("__native_map_keys") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(list(k)) }
        fn("__native_map_values") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(list(v)) }
        fn("__native_map_entries") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v))
            returns(seq(rec("key" to k, "value" to v)))
        }
        fn("__native_map_map_values") {
            val k = tvar("k"); val v = tvar("v"); val w = tvar("w")
            param("m" to map(k, v), "f" to MezzeType.FunctionType(rec("val" to v), w))
            returns(map(k, w))
        }
        fn("__native_map_filter") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(map(k, v))
        }
        fn("__native_map_fold") {
            val k = tvar("k"); val v = tvar("v"); val acc = tvar("acc")
            param("m" to map(k, v), "init" to acc, "f" to MezzeType.FunctionType(rec("acc" to acc, "k" to k, "v" to v), acc))
            returns(acc)
        }
        fn("__native_map_from_keys") {
            val k = tvar("k"); val v = tvar("v")
            param("ks" to list(k), "f" to MezzeType.FunctionType(rec("val" to k), v))
            returns(map(k, v))
        }
        fn("__native_map_merge") { val k = tvar("k"); val v = tvar("v"); param("m1" to map(k, v), "m2" to map(k, v)); returns(map(k, v)) }
        fn("__native_map_merge_with") {
            val k = tvar("k"); val v = tvar("v")
            param("m1" to map(k, v), "m2" to map(k, v), "f" to MezzeType.FunctionType(rec("l" to v, "r" to v), v))
            returns(map(k, v))
        }
        fn("__native_map_remove_all") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v), "ks" to list(k)); returns(map(k, v)) }
        fn("__native_map_equals") { val k = tvar("k"); val v = tvar("v"); param("m1" to map(k, v), "m2" to map(k, v)); returns(bool) }

        // Predicate Operations
        fn("__native_map_any") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(bool)
        }
        fn("__native_map_all") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(bool)
        }
        fn("__native_map_none") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(bool)
        }
        fn("__native_map_find") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(option(rec("k" to k, "v" to v)))
        }
        fn("__native_map_count") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(int)
        }

        // Additional Operations
        fn("__native_map_contains_value") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v), "v" to v); returns(bool) }
        fn("__native_map_update") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v), "k" to k, "f" to MezzeType.FunctionType(rec("val" to option(v)), v))
            returns(map(k, v))
        }
        fn("__native_map_get_or_default") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v), "k" to k, "default" to v); returns(v) }
        fn("__native_map_singleton") { val k = tvar("k"); val v = tvar("v"); param("k" to k, "v" to v); returns(map(k, v)) }

        // Conversion to Seq
        fn("__native_map_to_seq") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(seq(rec("k" to k, "v" to v))) }
        fn("__native_map_keys_seq") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(seq(k)) }
        fn("__native_map_values_seq") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(seq(v)) }
        fn("__native_map_partition") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(rec("pass" to map(k, v), "fail" to map(k, v)))
        }

        // Min/Max Operations
        fn("__native_map_min_key") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(option(k)) }
        fn("__native_map_max_key") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(option(k)) }
        fn("__native_map_min_value") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(option(v)) }
        fn("__native_map_max_value") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(option(v)) }
        fn("__native_map_invert") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(map(v, k)) }

        // Key/Value Operations
        fn("__native_map_select_keys") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v), "ks" to seq(k)); returns(map(k, v)) }
        fn("__native_map_reject_keys") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v), "ks" to seq(k)); returns(map(k, v)) }
        fn("__native_map_reduce") {
            val k = tvar("k"); val v = tvar("v")
            val entryType = rec("k" to k, "v" to v)
            param("m" to map(k, v), "f" to MezzeType.FunctionType(rec("acc" to entryType, "entry" to entryType), entryType))
            returns(option(entryType))
        }
        fn("__native_map_group_by_value") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v))
            returns(map(v, list(k)))
        }

        // Key Operations
        fn("__native_map_map_keys") {
            val k = tvar("k"); val v = tvar("v"); val k2 = tvar("k2")
            param("m" to map(k, v), "f" to MezzeType.FunctionType(rec("val" to k), k2))
            returns(map(k2, v))
        }
        fn("__native_map_filter_keys") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v), "p" to MezzeType.FunctionType(rec("val" to k), bool))
            returns(map(k, v))
        }
        fn("__native_map_filter_values") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v), "p" to MezzeType.FunctionType(rec("val" to v), bool))
            returns(map(k, v))
        }

        // Multi-Map Operations
        fn("__native_map_zip_maps") {
            val k = tvar("k"); val v1 = tvar("v1"); val v2 = tvar("v2")
            param("m1" to map(k, v1), "m2" to map(k, v2))
            returns(map(k, rec("fst" to v1, "snd" to v2)))
        }
        fn("__native_map_diff_maps") { val k = tvar("k"); val v = tvar("v"); param("m1" to map(k, v), "m2" to map(k, v)); returns(map(k, v)) }
        fn("__native_map_intersect_maps") { val k = tvar("k"); val v = tvar("v"); param("m1" to map(k, v), "m2" to map(k, v)); returns(map(k, v)) }
        fn("__native_map_symmetric_diff_maps") { val k = tvar("k"); val v = tvar("v"); param("m1" to map(k, v), "m2" to map(k, v)); returns(map(k, v)) }

        // List and SortedMap Conversions
        fn("__native_map_to_list") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(list(rec("k" to k, "v" to v))) }
        fn("__native_map_from_list") { val k = tvar("k"); val v = tvar("v"); param("xs" to list(rec("k" to k, "v" to v))); returns(map(k, v)) }
        fn("__native_map_flat_map") {
            val k = tvar("k"); val v = tvar("v"); val k2 = tvar("k2"); val v2 = tvar("v2")
            param("m" to map(k, v), "f" to MezzeType.FunctionType(rec("k" to k, "v" to v), map(k2, v2)))
            returns(map(k2, v2))
        }
        fn("__native_map_to_sorted_map") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(sortedMap(k, v)) }
    }
}
