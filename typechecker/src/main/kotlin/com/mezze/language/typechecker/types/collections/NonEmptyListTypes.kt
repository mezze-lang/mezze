package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

private fun nel(elem: MezzeType): MezzeType =
    com.mezze.language.typechecker.util.WellKnownTypeApp.of("NonEmptyList", listOf(elem))

object NonEmptyListTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        // Basic Operations
        fn("__native_nel_equals") { val a = tvar("a"); param("xs" to nel(a), "ys" to nel(a)); returns(bool) }
        fn("__native_nel_compare") { val a = tvar("a"); param("xs" to nel(a), "ys" to nel(a)); returns(int) }
        fn("__native_nel_singleton") { val a = tvar("a"); param("x" to a); returns(nel(a)) }
        fn("__native_nel_from_head_tail") { val a = tvar("a"); param("head" to a, "tail" to list(a)); returns(nel(a)) }
        fn("__native_nel_head") { val a = tvar("a"); param("xs" to nel(a)); returns(a) }
        fn("__native_nel_tail") { val a = tvar("a"); param("xs" to nel(a)); returns(list(a)) }
        fn("__native_nel_size") { val a = tvar("a"); param("xs" to nel(a)); returns(int) }

        // Construction
        fn("__native_nel_cons") { val a = tvar("a"); param("x" to a, "xs" to nel(a)); returns(nel(a)) }
        fn("__native_nel_append") { val a = tvar("a"); param("xs" to nel(a), "ys" to nel(a)); returns(nel(a)) }
        fn("__native_nel_prepend_list") { val a = tvar("a"); param("xs" to list(a), "y" to a); returns(nel(a)) }
        fn("__native_nel_append_list") { val a = tvar("a"); param("xs" to nel(a), "ys" to list(a)); returns(nel(a)) }

        // Access
        fn("__native_nel_get") { val a = tvar("a"); param("xs" to nel(a), "index" to int); returns(a) }
        fn("__native_nel_last") { val a = tvar("a"); param("xs" to nel(a)); returns(a) }

        // Higher-Order Functions
        fn("__native_nel_map") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to nel(a), "f" to MezzeType.FunctionType(rec("x" to a), b))
            returns(nel(b))
        }
        fn("__native_nel_flat_map") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to nel(a), "f" to MezzeType.FunctionType(rec("x" to a), nel(b)))
            returns(nel(b))
        }
        fn("__native_nel_fold_left") {
            val a = tvar("a"); val acc = tvar("acc")
            param("xs" to nel(a), "init" to acc, "f" to MezzeType.FunctionType(rec("acc" to acc, "x" to a), acc))
            returns(acc)
        }
        fn("__native_nel_fold_right") {
            val a = tvar("a"); val acc = tvar("acc")
            param("xs" to nel(a), "init" to acc, "f" to MezzeType.FunctionType(rec("x" to a, "acc" to acc), acc))
            returns(acc)
        }
        fn("__native_nel_reduce") {
            val a = tvar("a")
            param("xs" to nel(a), "f" to MezzeType.FunctionType(rec("acc" to a, "x" to a), a))
            returns(a)
        }

        // Predicates
        fn("__native_nel_any") {
            val a = tvar("a")
            param("xs" to nel(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(bool)
        }
        fn("__native_nel_all") {
            val a = tvar("a")
            param("xs" to nel(a), "p" to MezzeType.FunctionType(rec("x" to a), bool))
            returns(bool)
        }
        fn("__native_nel_contains") { val a = tvar("a"); param("xs" to nel(a), "x" to a); returns(bool) }

        // Transformations
        fn("__native_nel_reverse") { val a = tvar("a"); param("xs" to nel(a)); returns(nel(a)) }
        fn("__native_nel_zip") {
            val a = tvar("a"); val b = tvar("b")
            param("xs" to nel(a), "ys" to nel(b))
            returns(nel(rec("fst" to a, "snd" to b)))
        }
        fn("__native_nel_intersperse") { val a = tvar("a"); param("xs" to nel(a), "sep" to a); returns(nel(a)) }

        // Conversions
        fn("__native_nel_to_list") { val a = tvar("a"); param("xs" to nel(a)); returns(list(a)) }
        fn("__native_nel_from_list") { val a = tvar("a"); param("xs" to list(a)); returns(nel(a)) }
        fn("__native_nel_to_seq") { val a = tvar("a"); param("xs" to nel(a)); returns(seq(a)) }
        fn("__native_nel_from_seq") { val a = tvar("a"); param("xs" to seq(a)); returns(nel(a)) }

        // Sorting
        fn("__native_nel_sort") { val a = tvar("a"); param("xs" to nel(a)); returns(nel(a)) }
        fn("__native_nel_sort_by") {
            val a = tvar("a")
            param("xs" to nel(a), "cmp" to MezzeType.FunctionType(rec("l" to a, "r" to a), int))
            returns(nel(a))
        }
    }
}
