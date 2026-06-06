package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

object SetTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_set_empty") { val a = tvar("a"); returns(set(a)) }
        fn("__native_set_insert") { val a = tvar("a"); param("s" to set(a), "x" to a); returns(set(a)) }
        fn("__native_set_remove") { val a = tvar("a"); param("s" to set(a), "x" to a); returns(set(a)) }
        fn("__native_set_contains") { val a = tvar("a"); param("s" to set(a), "x" to a); returns(bool) }
        fn("__native_set_size") { val a = tvar("a"); param("s" to set(a)); returns(int) }
        fn("__native_set_union") { val a = tvar("a"); param("s1" to set(a), "s2" to set(a)); returns(set(a)) }
        fn("__native_set_intersection") { val a = tvar("a"); param("s1" to set(a), "s2" to set(a)); returns(set(a)) }
        fn("__native_set_difference") { val a = tvar("a"); param("s1" to set(a), "s2" to set(a)); returns(set(a)) }
        fn("__native_set_is_subset") { val a = tvar("a"); param("s1" to set(a), "s2" to set(a)); returns(bool) }
        fn("__native_set_to_seq") { val a = tvar("a"); param("s" to set(a)); returns(seq(a)) }
        fn("__native_set_from_seq") { val a = tvar("a"); param("xs" to seq(a)); returns(set(a)) }
        fn("__native_set_to_list") { val a = tvar("a"); param("s" to set(a)); returns(list(a)) }
        fn("__native_set_from_list") { val a = tvar("a"); param("xs" to list(a)); returns(set(a)) }
        fn("__native_set_filter") {
            val a = tvar("a")
            param("s" to set(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(set(a))
        }
        fn("__native_set_map") {
            val a = tvar("a"); val b = tvar("b")
            param("s" to set(a), "f" to MezzeType.FunctionType(rec("val" to a), b))
            returns(set(b))
        }
        fn("__native_set_fold") {
            val a = tvar("a"); val acc = tvar("acc")
            param("s" to set(a), "init" to acc, "f" to MezzeType.FunctionType(rec("acc" to acc, "x" to a), acc))
            returns(acc)
        }
        fn("__native_set_insert_all") { val a = tvar("a"); param("s" to set(a), "xs" to seq(a)); returns(set(a)) }
        fn("__native_set_remove_all") { val a = tvar("a"); param("s" to set(a), "xs" to seq(a)); returns(set(a)) }
        fn("__native_set_equals") { val a = tvar("a"); param("s1" to set(a), "s2" to set(a)); returns(bool) }

        // Predicate Operations
        fn("__native_set_any") {
            val a = tvar("a")
            param("s" to set(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(bool)
        }
        fn("__native_set_all") {
            val a = tvar("a")
            param("s" to set(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(bool)
        }
        fn("__native_set_none") {
            val a = tvar("a")
            param("s" to set(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(bool)
        }
        fn("__native_set_find") {
            val a = tvar("a")
            param("s" to set(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(com.mezze.language.typechecker.util.WellKnownTypeApp.of(BuiltinTypes.OPTION, listOf(a)))
        }
        fn("__native_set_count") {
            val a = tvar("a")
            param("s" to set(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(int)
        }

        // Min/Max/Sum/Product
        fn("__native_set_min") {
            val a = tvar("a"); param("s" to set(a))
            returns(com.mezze.language.typechecker.util.WellKnownTypeApp.of(BuiltinTypes.OPTION, listOf(a)))
        }
        fn("__native_set_max") {
            val a = tvar("a"); param("s" to set(a))
            returns(com.mezze.language.typechecker.util.WellKnownTypeApp.of(BuiltinTypes.OPTION, listOf(a)))
        }
        fn("__native_set_sum") { param("s" to set(int)); returns(int) }
        fn("__native_set_product") { param("s" to set(int)); returns(int) }

        // Conversion
        fn("__native_set_singleton") { val a = tvar("a"); param("x" to a); returns(set(a)) }

        // Additional Operations
        fn("__native_set_reduce") {
            val a = tvar("a")
            param("s" to set(a), "f" to MezzeType.FunctionType(rec("acc" to a, "x" to a), a))
            returns(com.mezze.language.typechecker.util.WellKnownTypeApp.of(BuiltinTypes.OPTION, listOf(a)))
        }
        fn("__native_set_min_by") {
            val a = tvar("a"); val b = tvar("b")
            param("s" to set(a), "f" to MezzeType.FunctionType(rec("val" to a), b))
            returns(com.mezze.language.typechecker.util.WellKnownTypeApp.of(BuiltinTypes.OPTION, listOf(a)))
        }
        fn("__native_set_max_by") {
            val a = tvar("a"); val b = tvar("b")
            param("s" to set(a), "f" to MezzeType.FunctionType(rec("val" to a), b))
            returns(com.mezze.language.typechecker.util.WellKnownTypeApp.of(BuiltinTypes.OPTION, listOf(a)))
        }
        fn("__native_set_partition") {
            val a = tvar("a")
            param("s" to set(a), "p" to MezzeType.FunctionType(rec("val" to a), bool))
            returns(rec("pass" to set(a), "fail" to set(a)))
        }
        fn("__native_set_sum_float") { param("s" to set(float)); returns(float) }
        fn("__native_set_product_float") { param("s" to set(float)); returns(float) }

        // Task #20: Additional Operations
        fn("__native_set_flat_map") {
            val a = tvar("a"); val b = tvar("b")
            param("s" to set(a), "f" to MezzeType.FunctionType(rec("val" to a), set(b)))
            returns(set(b))
        }
        fn("__native_set_group_by") {
            val a = tvar("a"); val b = tvar("b")
            param("s" to set(a), "f" to MezzeType.FunctionType(rec("val" to a), b))
            returns(list(rec("key" to b, "values" to set(a))))
        }
        fn("__native_set_symmetric_difference") { val a = tvar("a"); param("s1" to set(a), "s2" to set(a)); returns(set(a)) }

        // Task #30: Collect Operations
        fn("__native_set_filter_map") {
            val a = tvar("a"); val b = tvar("b")
            param("set" to set(a), "f" to MezzeType.FunctionType(rec("val" to a), com.mezze.language.typechecker.util.WellKnownTypeApp.of(BuiltinTypes.OPTION, listOf(b))))
            returns(set(b))
        }
        fn("__native_set_collect_seq") {
            val a = tvar("a"); val b = tvar("b")
            param("set" to set(a), "f" to MezzeType.FunctionType(rec("val" to a), com.mezze.language.typechecker.util.WellKnownTypeApp.of(BuiltinTypes.OPTION, listOf(b))))
            returns(seq(b))
        }
        fn("__native_set_collect_list") {
            val a = tvar("a"); val b = tvar("b")
            param("set" to set(a), "f" to MezzeType.FunctionType(rec("val" to a), com.mezze.language.typechecker.util.WellKnownTypeApp.of(BuiltinTypes.OPTION, listOf(b))))
            returns(list(b))
        }

        // SortedSet Conversions
        fn("__native_set_to_sorted_set") { val a = tvar("a"); param("s" to set(a)); returns(sortedSet(a)) }
        fn("__native_set_from_sorted_set") { val a = tvar("a"); param("s" to sortedSet(a)); returns(set(a)) }
    }
}
