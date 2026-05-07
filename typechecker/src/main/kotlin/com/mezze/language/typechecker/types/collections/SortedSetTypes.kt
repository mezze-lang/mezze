package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return com.mezze.language.typechecker.util.WellKnownTypeApp.of("Option", listOf(elementType))
}

/**
 * Type signatures for SortedSet operations.
 *
 * Corresponds to stdlib file:
 * - std/collections/sorted_set.mz
 *
 * SortedSet is backed by Vavr TreeSet for O(log n) operations with elements in sorted order.
 */
object SortedSetTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerSortedSetNatives(context)
    }

    private fun registerSortedSetNatives(context: MutableMap<String, MezzeType>) {
        // __native_sorted_set_empty: {} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_singleton: {x: 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_singleton"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elementType)),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_add: {s: SortedSet 'a, x: 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_add"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "x" to elementType
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_remove: {s: SortedSet 'a, x: 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_remove"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "x" to elementType
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_contains: {s: SortedSet 'a, x: 'a} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_contains"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "x" to elementType
                )),
                MezzeType.BoolType
            )
        }

        // __native_sorted_set_size: {s: SortedSet 'a} -> Int
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_size"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(elementType))),
                MezzeType.IntType
            )
        }

        // __native_sorted_set_is_empty: {s: SortedSet 'a} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_is_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(elementType))),
                MezzeType.BoolType
            )
        }

        // __native_sorted_set_union: {s1: SortedSet 'a, s2: SortedSet 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_union"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s1" to MezzeType.SortedSetType(elementType),
                    "s2" to MezzeType.SortedSetType(elementType)
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_intersect: {s1: SortedSet 'a, s2: SortedSet 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_intersect"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s1" to MezzeType.SortedSetType(elementType),
                    "s2" to MezzeType.SortedSetType(elementType)
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_difference: {s1: SortedSet 'a, s2: SortedSet 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_difference"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s1" to MezzeType.SortedSetType(elementType),
                    "s2" to MezzeType.SortedSetType(elementType)
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_min: {s: SortedSet 'a} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_min"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(elementType))),
                optionType(elementType)
            )
        }

        // __native_sorted_set_max: {s: SortedSet 'a} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_max"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(elementType))),
                optionType(elementType)
            )
        }

        // __native_sorted_set_to_list: {s: SortedSet 'a} -> List 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_to_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(elementType))),
                MezzeType.ListType(elementType)
            )
        }

        // __native_sorted_set_from_list: {xs: List 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_from_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to MezzeType.ListType(elementType))),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_from_set: {s: Set 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_from_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SetType(elementType))),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_to_set: {s: SortedSet 'a} -> Set 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_to_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(elementType))),
                MezzeType.SetType(elementType)
            )
        }

        // __native_sorted_set_equals: {s1: SortedSet 'a, s2: SortedSet 'a} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_equals"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s1" to MezzeType.SortedSetType(elementType),
                    "s2" to MezzeType.SortedSetType(elementType)
                )),
                MezzeType.BoolType
            )
        }

        // __native_sorted_set_compare: {s1: SortedSet 'a, s2: SortedSet 'a} -> Int
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_compare"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s1" to MezzeType.SortedSetType(elementType),
                    "s2" to MezzeType.SortedSetType(elementType)
                )),
                MezzeType.IntType
            )
        }

        // ==================== Higher-Order Functions ====================

        // __native_sorted_set_filter: {s: SortedSet 'a, p: {val: 'a} -> Bool} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_filter"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elementType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_fold: {s: SortedSet 'a, init: 'acc, f: {acc: 'acc, x: 'a} -> 'acc} -> 'acc
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val accType = MezzeType.VarType(TypeVar("acc"))
            context["__native_sorted_set_fold"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "init" to accType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("acc" to accType, "x" to elementType)),
                        accType
                    )
                )),
                accType
            )
        }

        // __native_sorted_set_map: {s: SortedSet 'a, f: {val: 'a} -> 'b} -> SortedSet 'b
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val resultType = MezzeType.VarType(TypeVar("b"))
            context["__native_sorted_set_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elementType)),
                        resultType
                    )
                )),
                MezzeType.SortedSetType(resultType)
            )
        }

        // __native_sorted_set_any: {s: SortedSet 'a, p: {val: 'a} -> Bool} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_any"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elementType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.BoolType
            )
        }

        // __native_sorted_set_all: {s: SortedSet 'a, p: {val: 'a} -> Bool} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_all"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elementType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.BoolType
            )
        }

        // __native_sorted_set_none: {s: SortedSet 'a, p: {val: 'a} -> Bool} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_none"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elementType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.BoolType
            )
        }

        // __native_sorted_set_find: {s: SortedSet 'a, p: {val: 'a} -> Bool} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_find"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elementType)),
                        MezzeType.BoolType
                    )
                )),
                optionType(elementType)
            )
        }

        // __native_sorted_set_count: {s: SortedSet 'a, p: {val: 'a} -> Bool} -> Int
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_count"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elementType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.IntType
            )
        }

        // __native_sorted_set_reduce: {s: SortedSet 'a, f: {acc: 'a, x: 'a} -> 'a} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_reduce"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("acc" to elementType, "x" to elementType)),
                        elementType
                    )
                )),
                optionType(elementType)
            )
        }

        // ==================== Aggregation ====================

        // __native_sorted_set_sum: {s: SortedSet Int} -> Int
        context["__native_sorted_set_sum"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(MezzeType.IntType))),
            MezzeType.IntType
        )

        // __native_sorted_set_sum_float: {s: SortedSet Float} -> Float
        context["__native_sorted_set_sum_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(MezzeType.FloatType))),
            MezzeType.FloatType
        )

        // __native_sorted_set_product: {s: SortedSet Int} -> Int
        context["__native_sorted_set_product"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(MezzeType.IntType))),
            MezzeType.IntType
        )

        // __native_sorted_set_product_float: {s: SortedSet Float} -> Float
        context["__native_sorted_set_product_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(MezzeType.FloatType))),
            MezzeType.FloatType
        )

        // ==================== Conversion ====================

        // __native_sorted_set_to_seq: {s: SortedSet 'a} -> ['a]
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_to_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(elementType))),
                MezzeType.SeqType(elementType)
            )
        }

        // ==================== Additional Operations (Task #13) ====================

        // __native_sorted_set_partition: {s: SortedSet 'a, p: {val: 'a} -> Bool} -> {pass: SortedSet 'a, fail: SortedSet 'a}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_partition"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elementType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.RecordType(mapOf(
                    "pass" to MezzeType.SortedSetType(elementType),
                    "fail" to MezzeType.SortedSetType(elementType)
                ))
            )
        }

        // __native_sorted_set_min_by: {s: SortedSet 'a, f: {val: 'a} -> 'b} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val keyType = MezzeType.VarType(TypeVar("b"))
            context["__native_sorted_set_min_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elementType)),
                        keyType
                    )
                )),
                optionType(elementType)
            )
        }

        // __native_sorted_set_max_by: {s: SortedSet 'a, f: {val: 'a} -> 'b} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val keyType = MezzeType.VarType(TypeVar("b"))
            context["__native_sorted_set_max_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elementType)),
                        keyType
                    )
                )),
                optionType(elementType)
            )
        }

        // __native_sorted_set_take: {s: SortedSet 'a, n: Int} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_take"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "n" to MezzeType.IntType
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_drop: {s: SortedSet 'a, n: Int} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_drop"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "n" to MezzeType.IntType
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_range: {s: SortedSet 'a, lo: 'a, hi: 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_range"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "lo" to elementType,
                    "hi" to elementType
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // ==================== Advanced Operations (Task #14) ====================

        // __native_sorted_set_symmetric_difference: {s1: SortedSet 'a, s2: SortedSet 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_symmetric_difference"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s1" to MezzeType.SortedSetType(elementType),
                    "s2" to MezzeType.SortedSetType(elementType)
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_head_set: {s: SortedSet 'a, to: 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_head_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "to" to elementType
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_tail_set: {s: SortedSet 'a, from: 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_tail_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "from" to elementType
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // __native_sorted_set_sub_set: {s: SortedSet 'a, lo: 'a, hi: 'a} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_sub_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "lo" to elementType,
                    "hi" to elementType
                )),
                MezzeType.SortedSetType(elementType)
            )
        }

        // ==================== Task #30: Collect Operations ====================

        // __native_sorted_set_filter_map: {set: SortedSet 'a, f: {val: 'a} -> Option 'b} -> SortedSet 'b
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val resultType = MezzeType.VarType(TypeVar("b"))
            context["__native_sorted_set_filter_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "set" to MezzeType.SortedSetType(elemType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        optionType(resultType)
                    )
                )),
                MezzeType.SortedSetType(resultType)
            )
        }

        // __native_sorted_set_collect_seq: {set: SortedSet 'a, f: {val: 'a} -> Option 'b} -> ['b]
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val resultType = MezzeType.VarType(TypeVar("b"))
            context["__native_sorted_set_collect_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "set" to MezzeType.SortedSetType(elemType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        optionType(resultType)
                    )
                )),
                MezzeType.SeqType(resultType)
            )
        }

        // __native_sorted_set_collect_list: {set: SortedSet 'a, f: {val: 'a} -> Option 'b} -> List 'b
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val resultType = MezzeType.VarType(TypeVar("b"))
            context["__native_sorted_set_collect_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "set" to MezzeType.SortedSetType(elemType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        optionType(resultType)
                    )
                )),
                MezzeType.ListType(resultType)
            )
        }

        // ==================== Seq Conversions ====================

        // __native_sorted_set_from_seq: {xs: ['a]} -> SortedSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            context["__native_sorted_set_from_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to MezzeType.SeqType(elementType))),
                MezzeType.SortedSetType(elementType)
            )
        }

        // ==================== For Each ====================

        // __native_sorted_set_for_each: {s: SortedSet 'a, f: {val: 'a} -> 'b} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val resultType = MezzeType.VarType(TypeVar("b"))
            context["__native_sorted_set_for_each"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SortedSetType(elementType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elementType)),
                        resultType
                    )
                )),
                MezzeType.RecordType(emptyMap())
            )
        }
    }
}
