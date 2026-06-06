package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

private fun deque(elem: MezzeType): MezzeType =
    com.mezze.language.typechecker.util.WellKnownTypeApp.of("Deque", listOf(elem))

object DequeTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {

        // Construction
        fn("__native_deque_empty") { val a = tvar("a"); returns(deque(a)) }
        fn("__native_deque_singleton") { val a = tvar("a"); param("x" to a); returns(deque(a)) }
        fn("__native_deque_from_list") { val a = tvar("a"); param("xs" to list(a)); returns(deque(a)) }

        // Add elements
        fn("__native_deque_push_front") { val a = tvar("a"); param("d" to deque(a), "x" to a); returns(deque(a)) }
        fn("__native_deque_push_back") { val a = tvar("a"); param("d" to deque(a), "x" to a); returns(deque(a)) }

        // Remove elements
        fn("__native_deque_pop_front") {
            val a = tvar("a")
            param("d" to deque(a))
            returns(option(rec("head" to a, "tail" to deque(a))))
        }
        fn("__native_deque_pop_back") {
            val a = tvar("a")
            param("d" to deque(a))
            returns(option(rec("init" to deque(a), "last" to a)))
        }

        // Peek
        fn("__native_deque_peek_front") { val a = tvar("a"); param("d" to deque(a)); returns(option(a)) }
        fn("__native_deque_peek_back") { val a = tvar("a"); param("d" to deque(a)); returns(option(a)) }

        // Queries
        fn("__native_deque_is_empty") { val a = tvar("a"); param("d" to deque(a)); returns(bool) }
        fn("__native_deque_size") { val a = tvar("a"); param("d" to deque(a)); returns(int) }

        // Conversions
        fn("__native_deque_to_list") { val a = tvar("a"); param("d" to deque(a)); returns(list(a)) }
        fn("__native_deque_to_seq") { val a = tvar("a"); param("d" to deque(a)); returns(seq(a)) }

        // Higher-order functions
        fn("__native_deque_map") {
            val a = tvar("a"); val b = tvar("b")
            param("d" to deque(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(deque(b))
        }
        fn("__native_deque_filter") {
            val a = tvar("a")
            param("d" to deque(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(deque(a))
        }
        fn("__native_deque_fold") {
            val a = tvar("a"); val b = tvar("b")
            param("d" to deque(a), "init" to b, "f" to MezzeType.FunctionType(rec("acc" to b, "x" to a), b))
            returns(b)
        }

        // Transformation
        fn("__native_deque_reverse") { val a = tvar("a"); param("d" to deque(a)); returns(deque(a)) }
        fn("__native_deque_concat") { val a = tvar("a"); param("d1" to deque(a), "d2" to deque(a)); returns(deque(a)) }
        fn("__native_deque_equals") { val a = tvar("a"); param("d1" to deque(a), "d2" to deque(a)); returns(bool) }
        fn("__native_deque_compare") { val a = tvar("a"); param("d1" to deque(a), "d2" to deque(a)); returns(int) }

        // Predicate Operations
        fn("__native_deque_any") {
            val a = tvar("a")
            param("d" to deque(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(bool)
        }
        fn("__native_deque_all") {
            val a = tvar("a")
            param("d" to deque(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(bool)
        }
        fn("__native_deque_none") {
            val a = tvar("a")
            param("d" to deque(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(bool)
        }
        fn("__native_deque_find") {
            val a = tvar("a")
            param("d" to deque(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(option(a))
        }
        fn("__native_deque_count") {
            val a = tvar("a")
            param("d" to deque(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(int)
        }

        // Min/Max/Sum/Product
        fn("__native_deque_min") { val a = tvar("a"); param("d" to deque(a)); returns(option(a)) }
        fn("__native_deque_max") { val a = tvar("a"); param("d" to deque(a)); returns(option(a)) }
        fn("__native_deque_sum") { param("d" to deque(int)); returns(int) }
        fn("__native_deque_product") { param("d" to deque(int)); returns(int) }

        // Contains
        fn("__native_deque_contains") { val a = tvar("a"); param("d" to deque(a), "x" to a); returns(bool) }

        // Conversion to/from Seq
        fn("__native_deque_to_seq") { val a = tvar("a"); param("d" to deque(a)); returns(seq(a)) }
        fn("__native_deque_from_seq") { val a = tvar("a"); param("xs" to seq(a)); returns(deque(a)) }

        // Additional Operations
        fn("__native_deque_reduce") {
            val a = tvar("a")
            param("d" to deque(a), "f" to MezzeType.FunctionType(rec("acc" to a, "x" to a), a))
            returns(option(a))
        }
        fn("__native_deque_flat_map") {
            val a = tvar("a"); val b = tvar("b")
            param("d" to deque(a), "f" to MezzeType.FunctionType(rec("val" to a), deque(b)))
            returns(deque(b))
        }
        fn("__native_deque_partition") {
            val a = tvar("a")
            param("d" to deque(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(rec("pass" to deque(a), "fail" to deque(a)))
        }
        fn("__native_deque_take") { val a = tvar("a"); param("d" to deque(a), "n" to int); returns(deque(a)) }
        fn("__native_deque_drop") { val a = tvar("a"); param("d" to deque(a), "n" to int); returns(deque(a)) }
        fn("__native_deque_unique") { val a = tvar("a"); param("d" to deque(a)); returns(deque(a)) }
        fn("__native_deque_sum_float") { param("d" to deque(float)); returns(float) }
        fn("__native_deque_product_float") { param("d" to deque(float)); returns(float) }
        fn("__native_deque_sort") { val a = tvar("a"); param("d" to deque(a)); returns(deque(a)) }

        // Min/Max By Key
        fn("__native_deque_min_by") {
            val a = tvar("a"); val b = tvar("b")
            param("d" to deque(a), "f" to MezzeType.FunctionType(rec("val" to a), b))
            returns(option(a))
        }
        fn("__native_deque_max_by") {
            val a = tvar("a"); val b = tvar("b")
            param("d" to deque(a), "f" to MezzeType.FunctionType(rec("val" to a), b))
            returns(option(a))
        }

        // Indexed Access
        fn("__native_deque_get") { val a = tvar("a"); param("d" to deque(a), "i" to int); returns(option(a)) }
        fn("__native_deque_slice") { val a = tvar("a"); param("d" to deque(a), "start" to int, "end" to int); returns(deque(a)) }
        fn("__native_deque_index_of") { val a = tvar("a"); param("d" to deque(a), "x" to a); returns(option(int)) }
        fn("__native_deque_find_index") {
            val a = tvar("a")
            param("d" to deque(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(option(int))
        }

        // Slicing Operations
        fn("__native_deque_take_while") {
            val a = tvar("a")
            param("d" to deque(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(deque(a))
        }
        fn("__native_deque_drop_while") {
            val a = tvar("a")
            param("d" to deque(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(deque(a))
        }
        fn("__native_deque_split_at") {
            val a = tvar("a")
            param("d" to deque(a), "i" to int)
            returns(rec("l" to deque(a), "r" to deque(a)))
        }
        fn("__native_deque_span") {
            val a = tvar("a")
            param("d" to deque(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(rec("l" to deque(a), "r" to deque(a)))
        }

        // Transformation Operations
        fn("__native_deque_scan") {
            val a = tvar("a"); val b = tvar("b")
            param("d" to deque(a), "init" to b, "f" to MezzeType.FunctionType(rec("acc" to b, "x" to a), b))
            returns(deque(b))
        }
        fn("__native_deque_dedup") { val a = tvar("a"); param("d" to deque(a)); returns(deque(a)) }
        fn("__native_deque_dedup_by") {
            val a = tvar("a"); val b = tvar("b")
            param("d" to deque(a), "f" to MezzeType.FunctionType(rec("val" to a), b))
            returns(deque(a))
        }
        fn("__native_deque_intersperse") { val a = tvar("a"); param("d" to deque(a), "sep" to a); returns(deque(a)) }

        // Combining Operations
        fn("__native_deque_zip") {
            val a = tvar("a"); val b = tvar("b")
            param("d1" to deque(a), "d2" to deque(b))
            returns(deque(rec("fst" to a, "snd" to b)))
        }
        fn("__native_deque_zip_with_index") {
            val a = tvar("a")
            param("d" to deque(a))
            returns(deque(rec("val" to a, "idx" to int)))
        }
        fn("__native_deque_rotate_left") { val a = tvar("a"); param("d" to deque(a), "n" to int); returns(deque(a)) }
        fn("__native_deque_rotate_right") { val a = tvar("a"); param("d" to deque(a), "n" to int); returns(deque(a)) }

        // Cross-Collection Conversions
        fn("__native_deque_to_set") { val a = tvar("a"); param("d" to deque(a)); returns(set(a)) }
        fn("__native_deque_to_sorted_set") { val a = tvar("a"); param("d" to deque(a)); returns(sortedSet(a)) }
        fn("__native_deque_to_map") {
            val k = tvar("k"); val v = tvar("v")
            param("d" to deque(rec("k" to k, "v" to v)))
            returns(map(k, v))
        }
        fn("__native_deque_to_sorted_map") {
            val k = tvar("k"); val v = tvar("v")
            param("d" to deque(rec("k" to k, "v" to v)))
            returns(sortedMap(k, v))
        }
        fn("__native_deque_from_set") { val a = tvar("a"); param("s" to set(a)); returns(deque(a)) }
        fn("__native_deque_from_sorted_set") { val a = tvar("a"); param("s" to sortedSet(a)); returns(deque(a)) }
        fn("__native_deque_from_map") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to map(k, v))
            returns(deque(rec("k" to k, "v" to v)))
        }
        fn("__native_deque_from_sorted_map") {
            val k = tvar("k"); val v = tvar("v")
            param("m" to sortedMap(k, v))
            returns(deque(rec("k" to k, "v" to v)))
        }
    }
}
