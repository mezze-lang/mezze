package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

object SortedMapTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_sorted_map_empty") { val k = tvar("k"); val v = tvar("v"); returns(sortedMap(k, v)) }
        fn("__native_sorted_map_insert") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "k" to k, "v" to v); returns(sortedMap(k, v)) }
        fn("__native_sorted_map_get") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "k" to k); returns(option(v)) }
        fn("__native_sorted_map_remove") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "k" to k); returns(sortedMap(k, v)) }
        fn("__native_sorted_map_contains_key") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "k" to k); returns(bool) }
        fn("__native_sorted_map_size") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v)); returns(int) }
        fn("__native_sorted_map_is_empty") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v)); returns(bool) }
        fn("__native_sorted_map_keys") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v)); returns(list(k)) }
        fn("__native_sorted_map_values") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v)); returns(list(v)) }
        fn("__native_sorted_map_entries") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v))
            returns(list(rec("k" to k, "v" to v)))
        }
        fn("__native_sorted_map_min_key") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v)); returns(option(k)) }
        fn("__native_sorted_map_max_key") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v)); returns(option(k)) }
        fn("__native_sorted_map_min_entry") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v))
            returns(option(rec("k" to k, "v" to v)))
        }
        fn("__native_sorted_map_max_entry") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v))
            returns(option(rec("k" to k, "v" to v)))
        }
        fn("__native_sorted_map_from_map") { val k = tvar("k"); val v = tvar("v"); param("m" to map(k, v)); returns(sortedMap(k, v)) }
        fn("__native_sorted_map_to_map") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v)); returns(map(k, v)) }
        fn("__native_sorted_map_equals") { val k = tvar("k"); val v = tvar("v"); param("m1" to sortedMap(k, v), "m2" to sortedMap(k, v)); returns(bool) }
        fn("__native_sorted_map_compare") { val k = tvar("k"); val v = tvar("v"); param("m1" to sortedMap(k, v), "m2" to sortedMap(k, v)); returns(int) }
        fn("__native_sorted_map_singleton") { val k = tvar("k"); val v = tvar("v"); param("k" to k, "v" to v); returns(sortedMap(k, v)) }

        // Higher-Order Functions
        fn("__native_sorted_map_filter") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(sortedMap(k, v))
        }
        fn("__native_sorted_map_fold") {
            val k = tvar("k"); val v = tvar("v"); val acc = tvar("acc")
            param("m" to sortedMap(k, v), "init" to acc, "f" to MezzeType.FunctionType(rec("acc" to acc, "k" to k, "v" to v), acc))
            returns(acc)
        }
        fn("__native_sorted_map_map_values") {
            val k = tvar("k"); val v = tvar("v"); val w = tvar("w")
            param("m" to sortedMap(k, v), "f" to MezzeType.FunctionType(rec("val" to v), w))
            returns(sortedMap(k, w))
        }
        fn("__native_sorted_map_any") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(bool)
        }
        fn("__native_sorted_map_all") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(bool)
        }
        fn("__native_sorted_map_none") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(bool)
        }
        fn("__native_sorted_map_find") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(option(rec("k" to k, "v" to v)))
        }
        fn("__native_sorted_map_count") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(int)
        }

        // Additional Operations
        fn("__native_sorted_map_contains_value") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "v" to v); returns(bool) }
        fn("__native_sorted_map_get_or_default") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "k" to k, "default" to v); returns(v) }
        fn("__native_sorted_map_update") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v), "k" to k, "f" to MezzeType.FunctionType(rec("val" to option(v)), v))
            returns(sortedMap(k, v))
        }
        fn("__native_sorted_map_merge") { val k = tvar("k"); val v = tvar("v"); param("m1" to sortedMap(k, v), "m2" to sortedMap(k, v)); returns(sortedMap(k, v)) }
        fn("__native_sorted_map_merge_with") {
            val k = tvar("k"); val v = tvar("v")
            param("m1" to sortedMap(k, v), "m2" to sortedMap(k, v), "f" to MezzeType.FunctionType(rec("l" to v, "r" to v), v))
            returns(sortedMap(k, v))
        }

        // Conversion
        fn("__native_sorted_map_to_seq") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v))
            returns(seq(rec("k" to k, "v" to v)))
        }
        fn("__native_sorted_map_keys_seq") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v)); returns(seq(k)) }
        fn("__native_sorted_map_values_seq") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v)); returns(seq(v)) }

        // Navigation Operations
        fn("__native_sorted_map_partition") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v), "p" to MezzeType.FunctionType(rec("k" to k, "v" to v), bool))
            returns(rec("pass" to sortedMap(k, v), "fail" to sortedMap(k, v)))
        }
        fn("__native_sorted_map_range") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "lo" to k, "hi" to k); returns(sortedMap(k, v)) }
        fn("__native_sorted_map_floor_key") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "k" to k); returns(option(k)) }
        fn("__native_sorted_map_ceiling_key") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "k" to k); returns(option(k)) }
        fn("__native_sorted_map_lower_key") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "k" to k); returns(option(k)) }
        fn("__native_sorted_map_higher_key") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "k" to k); returns(option(k)) }
        fn("__native_sorted_map_floor_entry") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v), "k" to k)
            returns(option(rec("k" to k, "v" to v)))
        }
        fn("__native_sorted_map_ceiling_entry") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v), "k" to k)
            returns(option(rec("k" to k, "v" to v)))
        }

        // View Operations
        fn("__native_sorted_map_head_map") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "to" to k); returns(sortedMap(k, v)) }
        fn("__native_sorted_map_tail_map") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "from" to k); returns(sortedMap(k, v)) }
        fn("__native_sorted_map_sub_map") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "lo" to k, "hi" to k); returns(sortedMap(k, v)) }
        fn("__native_sorted_map_take") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "n" to int); returns(sortedMap(k, v)) }
        fn("__native_sorted_map_drop") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v), "n" to int); returns(sortedMap(k, v)) }

        // Additional HOF Operations
        fn("__native_sorted_map_map_keys") {
            val k = tvar("k"); val v = tvar("v"); val k2 = tvar("k2")
            param("m" to sortedMap(k, v), "f" to MezzeType.FunctionType(rec("val" to k), k2))
            returns(sortedMap(k2, v))
        }
        fn("__native_sorted_map_filter_keys") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v), "p" to MezzeType.FunctionType(rec("val" to k), bool))
            returns(sortedMap(k, v))
        }
        fn("__native_sorted_map_filter_values") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v), "p" to MezzeType.FunctionType(rec("val" to v), bool))
            returns(sortedMap(k, v))
        }
        fn("__native_sorted_map_invert") { val k = tvar("k"); val v = tvar("v"); param("m" to sortedMap(k, v)); returns(sortedMap(v, k)) }

        // List Conversions
        fn("__native_sorted_map_to_list") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v))
            returns(list(rec("k" to k, "v" to v)))
        }
        fn("__native_sorted_map_from_list") {
            val k = tvar("k"); val v = tvar("v")
            param("xs" to list(rec("k" to k, "v" to v)))
            returns(sortedMap(k, v))
        }
    }
}
