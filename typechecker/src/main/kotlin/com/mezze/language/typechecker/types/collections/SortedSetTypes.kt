package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

object SortedSetTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_sorted_set_empty") { val a = tvar("a"); returns(sortedSet(a)) }
        fn("__native_sorted_set_singleton") { val a = tvar("a"); param("x" to a); returns(sortedSet(a)) }
        fn("__native_sorted_set_add") { val a = tvar("a"); param("s" to sortedSet(a), "x" to a); returns(sortedSet(a)) }
        fn("__native_sorted_set_remove") { val a = tvar("a"); param("s" to sortedSet(a), "x" to a); returns(sortedSet(a)) }
        fn("__native_sorted_set_contains") { val a = tvar("a"); param("s" to sortedSet(a), "x" to a); returns(bool) }
        fn("__native_sorted_set_size") { val a = tvar("a"); param("s" to sortedSet(a)); returns(int) }
        fn("__native_sorted_set_is_empty") { val a = tvar("a"); param("s" to sortedSet(a)); returns(bool) }
        fn("__native_sorted_set_union") { val a = tvar("a"); param("s1" to sortedSet(a), "s2" to sortedSet(a)); returns(sortedSet(a)) }
        fn("__native_sorted_set_intersect") { val a = tvar("a"); param("s1" to sortedSet(a), "s2" to sortedSet(a)); returns(sortedSet(a)) }
        fn("__native_sorted_set_difference") { val a = tvar("a"); param("s1" to sortedSet(a), "s2" to sortedSet(a)); returns(sortedSet(a)) }
        fn("__native_sorted_set_min") { val a = tvar("a"); param("s" to sortedSet(a)); returns(option(a)) }
        fn("__native_sorted_set_max") { val a = tvar("a"); param("s" to sortedSet(a)); returns(option(a)) }
        fn("__native_sorted_set_to_list") { val a = tvar("a"); param("s" to sortedSet(a)); returns(list(a)) }
        fn("__native_sorted_set_from_list") { val a = tvar("a"); param("xs" to list(a)); returns(sortedSet(a)) }
        fn("__native_sorted_set_from_set") { val a = tvar("a"); param("s" to set(a)); returns(sortedSet(a)) }
        fn("__native_sorted_set_to_set") { val a = tvar("a"); param("s" to sortedSet(a)); returns(set(a)) }
        fn("__native_sorted_set_equals") { val a = tvar("a"); param("s1" to sortedSet(a), "s2" to sortedSet(a)); returns(bool) }
        fn("__native_sorted_set_compare") { val a = tvar("a"); param("s1" to sortedSet(a), "s2" to sortedSet(a)); returns(int) }

        // Higher-Order Functions
        fn("__native_sorted_set_filter") {
            val a = tvar("a")
            param("s" to sortedSet(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(sortedSet(a))
        }
        fn("__native_sorted_set_fold") {
            val a = tvar("a"); val acc = tvar("acc")
            param("s" to sortedSet(a), "init" to acc, "f" to MezzeType.FunctionType(rec("acc" to acc, "x" to a), acc))
            returns(acc)
        }
        fn("__native_sorted_set_map") {
            val a = tvar("a"); val b = tvar("b")
            param("s" to sortedSet(a), "f" to MezzeType.FunctionType(rec("val" to a), b))
            returns(sortedSet(b))
        }
        fn("__native_sorted_set_any") {
            val a = tvar("a")
            param("s" to sortedSet(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(bool)
        }
        fn("__native_sorted_set_all") {
            val a = tvar("a")
            param("s" to sortedSet(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(bool)
        }
        fn("__native_sorted_set_none") {
            val a = tvar("a")
            param("s" to sortedSet(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(bool)
        }
        fn("__native_sorted_set_find") {
            val a = tvar("a")
            param("s" to sortedSet(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(option(a))
        }
        fn("__native_sorted_set_count") {
            val a = tvar("a")
            param("s" to sortedSet(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(int)
        }
        fn("__native_sorted_set_reduce") {
            val a = tvar("a")
            param("s" to sortedSet(a), "f" to MezzeType.FunctionType(rec("acc" to a, "x" to a), a))
            returns(option(a))
        }

        // Aggregation
        fn("__native_sorted_set_sum") { param("s" to sortedSet(int)); returns(int) }
        fn("__native_sorted_set_sum_float") { param("s" to sortedSet(float)); returns(float) }
        fn("__native_sorted_set_product") { param("s" to sortedSet(int)); returns(int) }
        fn("__native_sorted_set_product_float") { param("s" to sortedSet(float)); returns(float) }

        // Conversion
        fn("__native_sorted_set_to_seq") { val a = tvar("a"); param("s" to sortedSet(a)); returns(seq(a)) }

        // Additional Operations
        fn("__native_sorted_set_partition") {
            val a = tvar("a")
            param("s" to sortedSet(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(rec("pass" to sortedSet(a), "fail" to sortedSet(a)))
        }
        fn("__native_sorted_set_min_by") {
            val a = tvar("a"); val b = tvar("b")
            param("s" to sortedSet(a), "f" to MezzeType.FunctionType(rec("val" to a), b))
            returns(option(a))
        }
        fn("__native_sorted_set_max_by") {
            val a = tvar("a"); val b = tvar("b")
            param("s" to sortedSet(a), "f" to MezzeType.FunctionType(rec("val" to a), b))
            returns(option(a))
        }
        fn("__native_sorted_set_take") { val a = tvar("a"); param("s" to sortedSet(a), "n" to int); returns(sortedSet(a)) }
        fn("__native_sorted_set_drop") { val a = tvar("a"); param("s" to sortedSet(a), "n" to int); returns(sortedSet(a)) }
        fn("__native_sorted_set_range") { val a = tvar("a"); param("s" to sortedSet(a), "lo" to a, "hi" to a); returns(sortedSet(a)) }

        // Advanced Operations
        fn("__native_sorted_set_symmetric_difference") { val a = tvar("a"); param("s1" to sortedSet(a), "s2" to sortedSet(a)); returns(sortedSet(a)) }
        fn("__native_sorted_set_head_set") { val a = tvar("a"); param("s" to sortedSet(a), "to" to a); returns(sortedSet(a)) }
        fn("__native_sorted_set_tail_set") { val a = tvar("a"); param("s" to sortedSet(a), "from" to a); returns(sortedSet(a)) }
        fn("__native_sorted_set_sub_set") { val a = tvar("a"); param("s" to sortedSet(a), "lo" to a, "hi" to a); returns(sortedSet(a)) }

        // Task #30: Collect Operations
        fn("__native_sorted_set_filter_map") {
            val a = tvar("a"); val b = tvar("b")
            param("set" to sortedSet(a), "f" to MezzeType.FunctionType(rec("val" to a), option(b)))
            returns(sortedSet(b))
        }
        fn("__native_sorted_set_collect_seq") {
            val a = tvar("a"); val b = tvar("b")
            param("set" to sortedSet(a), "f" to MezzeType.FunctionType(rec("val" to a), option(b)))
            returns(seq(b))
        }
        fn("__native_sorted_set_collect_list") {
            val a = tvar("a"); val b = tvar("b")
            param("set" to sortedSet(a), "f" to MezzeType.FunctionType(rec("val" to a), option(b)))
            returns(list(b))
        }

        // Seq Conversions
        fn("__native_sorted_set_from_seq") { val a = tvar("a"); param("xs" to seq(a)); returns(sortedSet(a)) }

        // For Each
        fn("__native_sorted_set_for_each") {
            val a = tvar("a"); val b = tvar("b")
            param("s" to sortedSet(a), "f" to MezzeType.FunctionType(rec("val" to a), b))
            returns(unit)
        }
    }
}
