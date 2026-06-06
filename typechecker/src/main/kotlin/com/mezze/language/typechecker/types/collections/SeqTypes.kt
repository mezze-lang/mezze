package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

private fun deque(elem: MezzeType): MezzeType =
    com.mezze.language.typechecker.util.WellKnownTypeApp.of("Deque", listOf(elem))

object SeqTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_seq_empty") { val a = tvar("a"); returns(seq(a)) }
        fn("__native_seq_size") { val a = tvar("a"); param("xs" to seq(a)); returns(int) }
        fn("__native_seq_is_empty") { val a = tvar("a"); param("xs" to seq(a)); returns(bool) }
        fn("__native_seq_get") { val a = tvar("a"); param("xs" to seq(a), "i" to int); returns(option(a)) }
        fn("__native_seq_set") { val a = tvar("a"); param("xs" to seq(a), "i" to int, "x" to a); returns(option(seq(a))) }
        fn("__native_seq_add") { val a = tvar("a"); param("xs" to seq(a), "x" to a); returns(seq(a)) }
        fn("__native_seq_push") { val a = tvar("a"); param("xs" to seq(a), "x" to a); returns(seq(a)) }
        fn("__native_seq_push_front") { val a = tvar("a"); param("xs" to seq(a), "x" to a); returns(seq(a)) }
        fn("__native_seq_remove") { val a = tvar("a"); param("xs" to seq(a), "i" to int); returns(option(seq(a))) }
        fn("__native_seq_concat") { val a = tvar("a"); param("xs" to seq(a), "ys" to seq(a)); returns(seq(a)) }
        fn("__native_seq_head") { val a = tvar("a"); param("xs" to seq(a)); returns(option(a)) }
        fn("__native_seq_first") { val a = tvar("a"); param("xs" to seq(a)); returns(option(a)) }
        fn("__native_seq_last") { val a = tvar("a"); param("xs" to seq(a)); returns(option(a)) }
        fn("__native_seq_tail") { val a = tvar("a"); param("xs" to seq(a)); returns(seq(a)) }
        fn("__native_seq_init") { val a = tvar("a"); param("xs" to seq(a)); returns(seq(a)) }
        fn("__native_seq_slice") { val a = tvar("a"); param("xs" to seq(a), "start" to int, "end" to int); returns(seq(a)) }
        fn("__native_seq_take") { val a = tvar("a"); param("xs" to seq(a), "n" to int); returns(seq(a)) }
        fn("__native_seq_drop") { val a = tvar("a"); param("xs" to seq(a), "n" to int); returns(seq(a)) }
        fn("__native_seq_take_while") {
            val a = tvar("a")
            param("xs" to seq(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(seq(a))
        }
        fn("__native_seq_drop_while") {
            val a = tvar("a")
            param("xs" to seq(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(seq(a))
        }
        fn("__native_seq_contains") { val a = tvar("a"); param("xs" to seq(a), "x" to a); returns(bool) }
        fn("__native_seq_index_of") { val a = tvar("a"); param("xs" to seq(a), "x" to a); returns(option(int)) }
        fn("__native_seq_find") {
            val a = tvar("a")
            param("xs" to seq(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(option(a))
        }
        fn("__native_seq_find_index") {
            val a = tvar("a")
            param("xs" to seq(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(option(int))
        }
        fn("__native_seq_map") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(seq(b))
        }
        fn("__native_seq_filter") {
            val a = tvar("a")
            param("xs" to seq(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(seq(a))
        }
        fn("__native_seq_flat_map") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), seq(b)))
            returns(seq(b))
        }
        fn("__native_seq_fold") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "init" to b, "f" to MezzeType.FunctionType(rec("acc" to b, "x" to a), b))
            returns(b)
        }
        fn("__native_seq_reduce") {
            val a = tvar("a")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("acc" to a, "x" to a), a))
            returns(option(a))
        }
        fn("__native_seq_any") {
            val a = tvar("a")
            param("xs" to seq(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(bool)
        }
        fn("__native_seq_all") {
            val a = tvar("a")
            param("xs" to seq(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(bool)
        }
        fn("__native_seq_none") {
            val a = tvar("a")
            param("xs" to seq(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(bool)
        }
        fn("__native_seq_count") {
            val a = tvar("a")
            param("xs" to seq(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(int)
        }
        fn("__native_seq_reverse") { val a = tvar("a"); param("xs" to seq(a)); returns(seq(a)) }
        fn("__native_seq_sort") { val a = tvar("a"); param("xs" to seq(a)); returns(seq(a)) }
        fn("__native_seq_sort_by") {
            val a = tvar("a")
            param("xs" to seq(a), "cmp" to MezzeType.FunctionType(rec("a" to a, "b" to a), int))
            returns(seq(a))
        }
        fn("__native_seq_unique") { val a = tvar("a"); param("xs" to seq(a)); returns(seq(a)) }
        fn("__native_seq_distinct") { val a = tvar("a"); param("xs" to seq(a)); returns(seq(a)) }
        fn("__native_seq_flatten") { val a = tvar("a"); param("xs" to seq(seq(a))); returns(seq(a)) }
        fn("__native_seq_partition") {
            val a = tvar("a")
            param("xs" to seq(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(rec("pass" to seq(a), "fail" to seq(a)))
        }
        fn("__native_seq_zip") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "ys" to seq(b))
            returns(seq(rec("fst" to a, "snd" to b)))
        }
        fn("__native_seq_zip_with_index") {
            val a = tvar("a")
            param("xs" to seq(a))
            returns(seq(rec("value" to a, "index" to int)))
        }
        fn("__native_seq_unzip") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(rec("fst" to a, "snd" to b)))
            returns(rec("fsts" to seq(a), "snds" to seq(b)))
        }
        fn("__native_seq_intersperse") { val a = tvar("a"); param("xs" to seq(a), "sep" to a); returns(seq(a)) }
        fn("__native_seq_chunk") { val a = tvar("a"); param("xs" to seq(a), "size" to int); returns(seq(seq(a))) }
        fn("__native_seq_sliding") { val a = tvar("a"); param("xs" to seq(a), "size" to int); returns(seq(seq(a))) }
        fn("__native_seq_range") { param("start" to int, "end" to int); returns(seq(int)) }
        fn("__native_seq_range_step") { param("start" to int, "end" to int, "step" to int); returns(seq(int)) }
        fn("__native_seq_equals") { val a = tvar("a"); param("l" to seq(a), "r" to seq(a)); returns(bool) }
        fn("__native_seq_compare") { val a = tvar("a"); param("xs" to seq(a), "ys" to seq(a)); returns(int) }

        // Min/Max/Sum/Product
        fn("__native_seq_min") { val a = tvar("a"); param("xs" to seq(a)); returns(option(a)) }
        fn("__native_seq_max") { val a = tvar("a"); param("xs" to seq(a)); returns(option(a)) }
        fn("__native_seq_min_by") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(option(a))
        }
        fn("__native_seq_max_by") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(option(a))
        }
        fn("__native_seq_sum") { param("xs" to seq(int)); returns(int) }
        fn("__native_seq_sum_float") { param("xs" to seq(float)); returns(float) }
        fn("__native_seq_product") { param("xs" to seq(int)); returns(int) }
        fn("__native_seq_product_float") { param("xs" to seq(float)); returns(float) }

        // Group By
        fn("__native_seq_group_by") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(seq(rec("key" to b, "values" to seq(a))))
        }

        // Enumerate/ForEach/Scan
        fn("__native_seq_enumerate") {
            val a = tvar("a")
            param("xs" to seq(a))
            returns(seq(rec("value" to a, "index" to int)))
        }
        fn("__native_seq_for_each") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(unit)
        }
        fn("__native_seq_scan") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "init" to b, "f" to MezzeType.FunctionType(rec("acc" to b, "x" to a), b))
            returns(seq(b))
        }

        // Split Operations
        fn("__native_seq_split_at") {
            val a = tvar("a")
            param("xs" to seq(a), "i" to int)
            returns(rec("l" to seq(a), "r" to seq(a)))
        }
        fn("__native_seq_span") {
            val a = tvar("a")
            param("xs" to seq(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(rec("l" to seq(a), "r" to seq(a)))
        }

        // Dedup
        fn("__native_seq_dedup") { val a = tvar("a"); param("xs" to seq(a)); returns(seq(a)) }
        fn("__native_seq_dedup_by") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(seq(a))
        }

        // Singleton/Nth/Fill
        fn("__native_seq_singleton") { val a = tvar("a"); param("x" to a); returns(seq(a)) }
        fn("__native_seq_nth") { val a = tvar("a"); param("xs" to seq(a), "i" to int); returns(option(a)) }
        fn("__native_seq_fill") { val a = tvar("a"); param("n" to int, "x" to a); returns(seq(a)) }
        fn("__native_seq_fill_with") {
            val a = tvar("a")
            param("n" to int, "f" to MezzeType.FunctionType(rec("i" to int), a))
            returns(seq(a))
        }

        // Group Consecutive
        fn("__native_seq_group_consecutive") { val a = tvar("a"); param("xs" to seq(a)); returns(seq(seq(a))) }
        fn("__native_seq_group_consecutive_by") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(seq(seq(a)))
        }

        // Windows/Chunks/Rotate
        fn("__native_seq_windows") { val a = tvar("a"); param("xs" to seq(a), "size" to int); returns(seq(seq(a))) }
        fn("__native_seq_chunks_exact") { val a = tvar("a"); param("xs" to seq(a), "size" to int); returns(seq(seq(a))) }
        fn("__native_seq_rotate_left") { val a = tvar("a"); param("xs" to seq(a), "n" to int); returns(seq(a)) }
        fn("__native_seq_rotate_right") { val a = tvar("a"); param("xs" to seq(a), "n" to int); returns(seq(a)) }

        // Binary Search
        fn("__native_seq_binary_search") { val a = tvar("a"); param("xs" to seq(a), "x" to a); returns(option(int)) }
        fn("__native_seq_binary_search_by") {
            val a = tvar("a")
            param("xs" to seq(a), "x" to a, "cmp" to MezzeType.FunctionType(rec("a" to a, "b" to a), int))
            returns(option(int))
        }

        // Sort By Key
        fn("__native_seq_sort_by_key") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(seq(a))
        }

        // New Seq Operations
        fn("__native_seq_last_index_of") { val a = tvar("a"); param("xs" to seq(a), "x" to a); returns(option(int)) }
        fn("__native_seq_replace") { val a = tvar("a"); param("xs" to seq(a), "old" to a, "new_val" to a); returns(seq(a)) }
        fn("__native_seq_replace_all") { val a = tvar("a"); param("xs" to seq(a), "old" to a, "new_val" to a); returns(seq(a)) }
        fn("__native_seq_insert_at") { val a = tvar("a"); param("xs" to seq(a), "i" to int, "x" to a); returns(seq(a)) }
        fn("__native_seq_swap") { val a = tvar("a"); param("xs" to seq(a), "i" to int, "j" to int); returns(option(seq(a))) }
        fn("__native_seq_starts_with") { val a = tvar("a"); param("xs" to seq(a), "prefix" to seq(a)); returns(bool) }
        fn("__native_seq_ends_with") { val a = tvar("a"); param("xs" to seq(a), "suffix" to seq(a)); returns(bool) }
        fn("__native_seq_join") { param("xs" to seq(str), "sep" to str); returns(str) }

        // Advanced Zip Operations
        fn("__native_seq_zip_with") {
            val a = tvar("a"); val b = tvar("b"); val c = tvar("c")
            param("xs" to seq(a), "ys" to seq(b), "f" to MezzeType.FunctionType(rec("a" to a, "b" to b), c))
            returns(seq(c))
        }
        fn("__native_seq_zip3") {
            val a = tvar("a"); val b = tvar("b"); val c = tvar("c")
            param("xs" to seq(a), "ys" to seq(b), "zs" to seq(c))
            returns(seq(rec("fst" to a, "snd" to b, "thd" to c)))
        }
        fn("__native_seq_zip4") {
            val a = tvar("a"); val b = tvar("b"); val c = tvar("c"); val d = tvar("d")
            param("xs" to seq(a), "ys" to seq(b), "zs" to seq(c), "ws" to seq(d))
            returns(seq(rec("fst" to a, "snd" to b, "thd" to c, "fth" to d)))
        }
        fn("__native_seq_cartesian_product") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "ys" to seq(b))
            returns(seq(rec("fst" to a, "snd" to b)))
        }

        // Combinatorics
        fn("__native_seq_permutations") { val a = tvar("a"); param("xs" to seq(a)); returns(seq(seq(a))) }
        fn("__native_seq_combinations") { val a = tvar("a"); param("xs" to seq(a), "k" to int); returns(seq(seq(a))) }
        fn("__native_seq_subsequences") { val a = tvar("a"); param("xs" to seq(a)); returns(seq(seq(a))) }

        // Statistical Operations
        fn("__native_seq_average") {
            param("xs" to seq(float))
            returns(MezzeType.VariantType(mapOf("Some" to rec("val" to float), "None" to null)))
        }
        fn("__native_seq_variance") {
            param("xs" to seq(float))
            returns(MezzeType.VariantType(mapOf("Some" to rec("val" to float), "None" to null)))
        }
        fn("__native_seq_stddev") {
            param("xs" to seq(float))
            returns(MezzeType.VariantType(mapOf("Some" to rec("val" to float), "None" to null)))
        }
        fn("__native_seq_median") {
            param("xs" to seq(float))
            returns(MezzeType.VariantType(mapOf("Some" to rec("val" to float), "None" to null)))
        }

        // Sorting Checks
        fn("__native_seq_is_sorted") { val a = tvar("a"); param("xs" to seq(a)); returns(bool) }
        fn("__native_seq_is_sorted_by") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to seq(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(bool)
        }
        fn("__native_seq_is_sorted_descending") { val a = tvar("a"); param("xs" to seq(a)); returns(bool) }

        // Advanced Binary Search
        fn("__native_seq_binary_search_insert_point") { val a = tvar("a"); param("xs" to seq(a), "x" to a); returns(int) }
        fn("__native_seq_lower_bound") { val a = tvar("a"); param("xs" to seq(a), "x" to a); returns(int) }
        fn("__native_seq_upper_bound") { val a = tvar("a"); param("xs" to seq(a), "x" to a); returns(int) }
        fn("__native_seq_equal_range") {
            val a = tvar("a")
            param("xs" to seq(a), "x" to a)
            returns(rec("start" to int, "end" to int))
        }

        // Counting
        fn("__native_seq_count_element") { val a = tvar("a"); param("xs" to seq(a), "x" to a); returns(int) }

        // Update Operations
        fn("__native_seq_update") {
            val a = tvar("a")
            param("xs" to seq(a), "i" to int, "f" to MezzeType.FunctionType(rec("x" to a), a))
            returns(MezzeType.VariantType(mapOf("Some" to rec("val" to seq(a)), "None" to null)))
        }
        fn("__native_seq_remove_at") {
            val a = tvar("a")
            param("xs" to seq(a), "i" to int)
            returns(MezzeType.VariantType(mapOf("Some" to rec("val" to seq(a)), "None" to null)))
        }

        // Split Operations
        fn("__native_seq_split") { val a = tvar("a"); param("xs" to seq(a), "sep" to a); returns(seq(seq(a))) }

        // Float-specific Operations
        fn("__native_seq_min_float") {
            param("xs" to seq(float))
            returns(MezzeType.VariantType(mapOf("Some" to rec("val" to float), "None" to null)))
        }
        fn("__native_seq_max_float") {
            param("xs" to seq(float))
            returns(MezzeType.VariantType(mapOf("Some" to rec("val" to float), "None" to null)))
        }

        // Cross-Collection Conversions
        fn("__native_seq_to_list") { val a = tvar("a"); param("xs" to seq(a)); returns(list(a)) }
        fn("__native_seq_to_set") { val a = tvar("a"); param("xs" to seq(a)); returns(set(a)) }
        fn("__native_seq_to_deque") { val a = tvar("a"); param("xs" to seq(a)); returns(deque(a)) }
        fn("__native_seq_to_sorted_set") { val a = tvar("a"); param("xs" to seq(a)); returns(sortedSet(a)) }
        fn("__native_seq_to_map") {
            val k = tvar("k"); val v = tvar("v")
            param("xs" to seq(rec("k" to k, "v" to v)))
            returns(map(k, v))
        }
        fn("__native_seq_to_sorted_map") {
            val k = tvar("k"); val v = tvar("v")
            param("xs" to seq(rec("k" to k, "v" to v)))
            returns(sortedMap(k, v))
        }
        fn("__native_seq_from_list") { val a = tvar("a"); param("xs" to list(a)); returns(seq(a)) }
        fn("__native_seq_from_set") { val a = tvar("a"); param("s" to set(a)); returns(seq(a)) }
        fn("__native_seq_from_deque") { val a = tvar("a"); param("d" to deque(a)); returns(seq(a)) }
        fn("__native_seq_from_sorted_set") { val a = tvar("a"); param("s" to sortedSet(a)); returns(seq(a)) }
        fn("__native_seq_from_map") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v))
            returns(seq(rec("k" to k, "v" to v)))
        }
        fn("__native_seq_from_sorted_map") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v))
            returns(seq(rec("k" to k, "v" to v)))
        }
    }
}
