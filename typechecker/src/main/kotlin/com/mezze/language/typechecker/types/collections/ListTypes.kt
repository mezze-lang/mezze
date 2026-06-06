package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

private fun deque(elem: MezzeType): MezzeType =
    com.mezze.language.typechecker.util.WellKnownTypeApp.of("Deque", listOf(elem))

object ListTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_list_empty") { val a = tvar("a"); returns(list(a)) }
        fn("__native_list_cons") { val a = tvar("a"); param("head" to a, "tail" to list(a)); returns(list(a)) }
        fn("__native_list_is_empty") { val a = tvar("a"); param("xs" to list(a)); returns(bool) }
        fn("__native_list_head") { val a = tvar("a"); param("xs" to list(a)); returns(option(a)) }
        fn("__native_list_tail") { val a = tvar("a"); param("xs" to list(a)); returns(option(list(a))) }
        fn("__native_list_size") { val a = tvar("a"); param("xs" to list(a)); returns(int) }
        fn("__native_list_uncons") {
            val a = tvar("a")
            param("xs" to list(a))
            returns(option(rec("head" to a, "tail" to list(a))))
        }
        fn("__native_list_append") { val a = tvar("a"); param("xs" to list(a), "ys" to list(a)); returns(list(a)) }
        fn("__native_list_get") { val a = tvar("a"); param("xs" to list(a), "index" to int); returns(option(a)) }
        fn("__native_list_take") { val a = tvar("a"); param("xs" to list(a), "n" to int); returns(list(a)) }
        fn("__native_list_drop") { val a = tvar("a"); param("xs" to list(a), "n" to int); returns(list(a)) }
        fn("__native_list_map") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to list(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(list(b))
        }
        fn("__native_list_filter") {
            val a = tvar("a")
            param("xs" to list(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(list(a))
        }
        fn("__native_list_fold_left") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to list(a), "init" to b, "f" to MezzeType.FunctionType(rec("acc" to b, "x" to a), b))
            returns(b)
        }
        fn("__native_list_fold_right") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to list(a), "init" to b, "f" to MezzeType.FunctionType(rec("acc" to b, "x" to a), b))
            returns(b)
        }
        fn("__native_list_reduce") {
            val a = tvar("a")
            param("xs" to list(a), "f" to MezzeType.FunctionType(rec("acc" to a, "x" to a), a))
            returns(MezzeType.VariantType(
                constructors = mapOf("Some" to MezzeType.RecordType(mapOf("val" to a)), "None" to null),
                rowVar = null
            ))
        }
        fn("__native_list_any") {
            val a = tvar("a")
            param("xs" to list(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(bool)
        }
        fn("__native_list_all") {
            val a = tvar("a")
            param("xs" to list(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(bool)
        }
        fn("__native_list_contains") { val a = tvar("a"); param("xs" to list(a), "x" to a); returns(bool) }
        fn("__native_list_reverse") { val a = tvar("a"); param("xs" to list(a)); returns(list(a)) }
        fn("__native_list_zip") {
            val a = tvar("a")
            param("xs" to list(a), "ys" to list(a))
            returns(list(rec("fst" to a, "snd" to a)))
        }
        fn("__native_list_range") { param("start" to int, "end" to int); returns(list(int)) }
        fn("__native_list_repeat") { val a = tvar("a"); param("x" to a, "n" to int); returns(list(a)) }
        fn("__native_list_concat") { val a = tvar("a"); param("xss" to list(list(a))); returns(list(a)) }
        fn("__native_list_unzip") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to list(rec("fst" to a, "snd" to b)))
            returns(rec("fsts" to list(a), "snds" to list(b)))
        }
        fn("__native_list_intersperse") { val a = tvar("a"); param("xs" to list(a), "sep" to a); returns(list(a)) }
        fn("__native_list_sort") { val a = tvar("a"); param("xs" to list(a)); returns(list(a)) }
        fn("__native_list_sort_by") {
            val a = tvar("a")
            param("xs" to list(a), "cmp" to MezzeType.FunctionType(rec("a" to a, "b" to a), int))
            returns(list(a))
        }

        // Utility Functions
        fn("__native_list_find") {
            val a = tvar("a")
            param("xs" to list(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(option(a))
        }
        fn("__native_list_find_index") {
            val a = tvar("a")
            param("xs" to list(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(option(int))
        }
        fn("__native_list_partition") {
            val a = tvar("a")
            param("xs" to list(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(rec("pass" to list(a), "fail" to list(a)))
        }
        fn("__native_list_last") { val a = tvar("a"); param("xs" to list(a)); returns(option(a)) }
        fn("__native_list_init") { val a = tvar("a"); param("xs" to list(a)); returns(list(a)) }

        // Min/Max/Sum/Product
        fn("__native_list_min") { val a = tvar("a"); param("xs" to list(a)); returns(option(a)) }
        fn("__native_list_max") { val a = tvar("a"); param("xs" to list(a)); returns(option(a)) }
        fn("__native_list_min_by") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to list(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(option(a))
        }
        fn("__native_list_max_by") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to list(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(option(a))
        }
        fn("__native_list_sum") { param("xs" to list(int)); returns(int) }
        fn("__native_list_sum_float") { param("xs" to list(float)); returns(float) }
        fn("__native_list_product") { param("xs" to list(int)); returns(int) }
        fn("__native_list_product_float") { param("xs" to list(float)); returns(float) }

        // Group By
        fn("__native_list_group_by") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to list(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(list(rec("key" to b, "values" to list(a))))
        }

        // Enumerate/ForEach/Scan
        fn("__native_list_enumerate") {
            val a = tvar("a")
            param("xs" to list(a))
            returns(list(rec("value" to a, "index" to int)))
        }
        fn("__native_list_zip_with_index") {
            val a = tvar("a")
            param("xs" to list(a))
            returns(list(rec("value" to a, "index" to int)))
        }
        fn("__native_list_for_each") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to list(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(unit)
        }
        fn("__native_list_scan") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to list(a), "init" to b, "f" to MezzeType.FunctionType(rec("acc" to b, "x" to a), b))
            returns(list(b))
        }

        // Split Operations
        fn("__native_list_split_at") {
            val a = tvar("a")
            param("xs" to list(a), "i" to int)
            returns(rec("l" to list(a), "r" to list(a)))
        }
        fn("__native_list_span") {
            val a = tvar("a")
            param("xs" to list(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(rec("l" to list(a), "r" to list(a)))
        }

        // Dedup
        fn("__native_list_dedup") { val a = tvar("a"); param("xs" to list(a)); returns(list(a)) }
        fn("__native_list_dedup_by") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to list(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(list(a))
        }

        // Singleton/Nth/Fill
        fn("__native_list_singleton") { val a = tvar("a"); param("x" to a); returns(list(a)) }
        fn("__native_list_nth") { val a = tvar("a"); param("xs" to list(a), "i" to int); returns(option(a)) }
        fn("__native_list_fill") { val a = tvar("a"); param("n" to int, "x" to a); returns(list(a)) }
        fn("__native_list_fill_with") {
            val a = tvar("a")
            param("n" to int, "f" to MezzeType.FunctionType(rec("i" to int), a))
            returns(list(a))
        }

        // Group Consecutive
        fn("__native_list_group_consecutive") { val a = tvar("a"); param("xs" to list(a)); returns(list(list(a))) }
        fn("__native_list_group_consecutive_by") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to list(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(list(list(a)))
        }

        // FlatMap/TakeWhile/DropWhile
        fn("__native_list_flat_map") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to list(a), "f" to MezzeType.FunctionType(rec("x" to a), list(b)))
            returns(list(b))
        }
        fn("__native_list_take_while") {
            val a = tvar("a")
            param("xs" to list(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(list(a))
        }
        fn("__native_list_drop_while") {
            val a = tvar("a")
            param("xs" to list(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(list(a))
        }

        // None/Count
        fn("__native_list_none") {
            val a = tvar("a")
            param("xs" to list(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(bool)
        }
        fn("__native_list_count") {
            val a = tvar("a")
            param("xs" to list(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(int)
        }

        // Unique/Distinct/Flatten
        fn("__native_list_unique") { val a = tvar("a"); param("xs" to list(a)); returns(list(a)) }
        fn("__native_list_distinct") { val a = tvar("a"); param("xs" to list(a)); returns(list(a)) }
        fn("__native_list_flatten") { val a = tvar("a"); param("xss" to list(list(a))); returns(list(a)) }

        // Additional Operations
        fn("__native_list_equals") { val a = tvar("a"); param("xs" to list(a), "ys" to list(a)); returns(bool) }
        fn("__native_list_compare") { val a = tvar("a"); param("xs" to list(a), "ys" to list(a)); returns(int) }
        fn("__native_list_index_of") { val a = tvar("a"); param("xs" to list(a), "x" to a); returns(option(int)) }
        fn("__native_list_last_index_of") { val a = tvar("a"); param("xs" to list(a), "x" to a); returns(option(int)) }
        fn("__native_list_slice") { val a = tvar("a"); param("xs" to list(a), "start" to int, "end" to int); returns(list(a)) }

        // Windowing Operations
        fn("__native_list_chunk") { val a = tvar("a"); param("xs" to list(a), "size" to int); returns(list(list(a))) }
        fn("__native_list_windows") { val a = tvar("a"); param("xs" to list(a), "size" to int); returns(list(list(a))) }
        fn("__native_list_sliding") { val a = tvar("a"); param("xs" to list(a), "size" to int, "step" to int); returns(list(list(a))) }
        fn("__native_list_chunks_exact") { val a = tvar("a"); param("xs" to list(a), "size" to int); returns(list(list(a))) }

        // Transformation Operations
        fn("__native_list_sort_by_key") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to list(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(list(a))
        }
        fn("__native_list_rotate_left") { val a = tvar("a"); param("xs" to list(a), "n" to int); returns(list(a)) }
        fn("__native_list_rotate_right") { val a = tvar("a"); param("xs" to list(a), "n" to int); returns(list(a)) }

        // Conversions
        fn("__native_list_to_seq") { val a = tvar("a"); param("xs" to list(a)); returns(seq(a)) }
        fn("__native_seq_to_list") { val a = tvar("a"); param("xs" to seq(a)); returns(list(a)) }
        fn("__native_list_from_seq") { val a = tvar("a"); param("xs" to seq(a)); returns(list(a)) }

        // Cross-Collection Conversions
        fn("__native_list_to_set") {
            val a = tvar("a")
            param("xs" to list(a))
            returns(com.mezze.language.typechecker.util.WellKnownTypeApp.of("Set", listOf(a)))
        }
        fn("__native_list_to_deque") { val a = tvar("a"); param("xs" to list(a)); returns(deque(a)) }
        fn("__native_list_to_sorted_set") {
            val a = tvar("a")
            param("xs" to list(a))
            returns(com.mezze.language.typechecker.util.WellKnownTypeApp.of("SortedSet", listOf(a)))
        }
        fn("__native_list_to_map") {
            val k = tvar("k"); val v = tvar("v")
            param("xs" to list(rec("k" to k, "v" to v)))
            returns(com.mezze.language.typechecker.util.WellKnownTypeApp.of("Map", listOf(k, v)))
        }
        fn("__native_list_to_sorted_map") {
            val k = tvar("k"); val v = tvar("v")
            param("xs" to list(rec("k" to k, "v" to v)))
            returns(com.mezze.language.typechecker.util.WellKnownTypeApp.of("SortedMap", listOf(k, v)))
        }
        fn("__native_list_from_set") {
            val a = tvar("a")
            param("xs" to com.mezze.language.typechecker.util.WellKnownTypeApp.of("Set", listOf(a)))
            returns(list(a))
        }
        fn("__native_list_from_deque") { val a = tvar("a"); param("xs" to deque(a)); returns(list(a)) }
        fn("__native_list_from_sorted_set") {
            val a = tvar("a")
            param("xs" to com.mezze.language.typechecker.util.WellKnownTypeApp.of("SortedSet", listOf(a)))
            returns(list(a))
        }
        fn("__native_list_from_map") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to com.mezze.language.typechecker.util.WellKnownTypeApp.of("Map", listOf(k, v)))
            returns(list(rec("k" to k, "v" to v)))
        }
        fn("__native_list_from_sorted_map") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to com.mezze.language.typechecker.util.WellKnownTypeApp.of("SortedMap", listOf(k, v)))
            returns(list(rec("k" to k, "v" to v)))
        }

        // Numeric Operations
        fn("__native_list_average") { param("xs" to list(float)); returns(option(float)) }
        fn("__native_list_variance") { param("xs" to list(float)); returns(option(float)) }
        fn("__native_list_stddev") { param("xs" to list(float)); returns(option(float)) }
        fn("__native_list_median") { param("xs" to list(float)); returns(option(float)) }

        // Comparison Operations
        fn("__native_list_lexicographic_compare") {
            val a = tvar("a")
            param("xs" to list(a), "ys" to list(a), "cmp" to MezzeType.FunctionType(rec("a" to a, "b" to a), int))
            returns(int)
        }
        fn("__native_list_is_sorted") { val a = tvar("a"); param("xs" to list(a)); returns(bool) }
        fn("__native_list_is_sorted_by") {
            val a = tvar("a")
            param("xs" to list(a), "cmp" to MezzeType.FunctionType(rec("a" to a, "b" to a), int))
            returns(bool)
        }
        fn("__native_list_is_sorted_descending") { val a = tvar("a"); param("xs" to list(a)); returns(bool) }

        // Binary search operations
        fn("__native_list_binary_search") { val a = tvar("a"); param("xs" to list(a), "target" to a); returns(option(int)) }
        fn("__native_list_binary_search_insert_point") { val a = tvar("a"); param("xs" to list(a), "target" to a); returns(int) }
        fn("__native_list_lower_bound") { val a = tvar("a"); param("xs" to list(a), "target" to a); returns(int) }
        fn("__native_list_upper_bound") { val a = tvar("a"); param("xs" to list(a), "target" to a); returns(int) }
        fn("__native_list_equal_range") {
            val a = tvar("a")
            param("xs" to list(a), "target" to a)
            returns(rec("lower" to int, "upper" to int))
        }
        fn("__native_list_binary_search_by") {
            val a = tvar("a")
            param("xs" to list(a), "target" to a, "cmp" to MezzeType.FunctionType(rec("a" to a, "b" to a), int))
            returns(option(int))
        }
    }
}
