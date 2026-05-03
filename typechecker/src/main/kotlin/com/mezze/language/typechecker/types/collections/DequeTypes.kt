package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Type signatures for Deque (double-ended queue) operations
 *
 * Note: Deque is represented as TypeApplication("Deque", [elementType]) in the type system.
 * This allows it to work with the existing type infrastructure without modifications.
 *
 * IMPORTANT: Each function registration must create its own fresh TypeVar instances
 * to avoid type unification issues. This matches the pattern used in ListTypes.kt.
 *
 * Corresponds to stdlib file:
 * - std/collections/deque.mz
 */
object DequeTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerDequeNatives(context)
    }

    private fun registerDequeNatives(context: MutableMap<String, MezzeType>) {

        // ========================================
        // Construction
        // ========================================

        // __native_deque_empty: {} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                dequeType
            )
        }

        // __native_deque_singleton: {x: 'a} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_singleton"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                dequeType
            )
        }

        // __native_deque_from_list: {xs: List 'a} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_from_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to MezzeType.ListType(elemType))),
                dequeType
            )
        }

        // ========================================
        // Add elements
        // ========================================

        // __native_deque_push_front: {d: Deque 'a, x: 'a} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_push_front"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "x" to elemType
                )),
                dequeType
            )
        }

        // __native_deque_push_back: {d: Deque 'a, x: 'a} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_push_back"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "x" to elemType
                )),
                dequeType
            )
        }

        // ========================================
        // Remove elements (returns Option)
        // ========================================

        // __native_deque_pop_front: {d: Deque 'a} -> Option {head: 'a, tail: Deque 'a}
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_pop_front"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.TypeApplication("Option", listOf(
                    MezzeType.RecordType(mapOf(
                        "head" to elemType,
                        "tail" to dequeType
                    ))
                ))
            )
        }

        // __native_deque_pop_back: {d: Deque 'a} -> Option {init: Deque 'a, last: 'a}
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_pop_back"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.TypeApplication("Option", listOf(
                    MezzeType.RecordType(mapOf(
                        "init" to dequeType,
                        "last" to elemType
                    ))
                ))
            )
        }

        // ========================================
        // Peek (without removing)
        // ========================================

        // __native_deque_peek_front: {d: Deque 'a} -> Option 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_peek_front"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.TypeApplication("Option", listOf(elemType))
            )
        }

        // __native_deque_peek_back: {d: Deque 'a} -> Option 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_peek_back"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.TypeApplication("Option", listOf(elemType))
            )
        }

        // ========================================
        // Queries
        // ========================================

        // __native_deque_is_empty: {d: Deque 'a} -> Bool
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_is_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.BoolType
            )
        }

        // __native_deque_size: {d: Deque 'a} -> Int
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_size"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.IntType
            )
        }

        // ========================================
        // Conversions
        // ========================================

        // __native_deque_to_list: {d: Deque 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_to_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.ListType(elemType)
            )
        }

        // __native_deque_to_seq: {d: Deque 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_to_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.SeqType(elemType)
            )
        }

        // ========================================
        // Higher-order functions
        // ========================================

        // __native_deque_map: {d: Deque 'a, f: {x: 'a} -> 'b} -> Deque 'b
        run {
            val elemTypeA = MezzeType.VarType(TypeVar("a"))
            val elemTypeB = MezzeType.VarType(TypeVar("b"))
            val dequeTypeA = MezzeType.TypeApplication("Deque", listOf(elemTypeA))
            val dequeTypeB = MezzeType.TypeApplication("Deque", listOf(elemTypeB))
            context["__native_deque_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeTypeA,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("x" to elemTypeA)),
                        elemTypeB
                    )
                )),
                dequeTypeB
            )
        }

        // __native_deque_filter: {d: Deque 'a, p: {x: 'a} -> Bool} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_filter"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("x" to elemType)),
                        MezzeType.BoolType
                    )
                )),
                dequeType
            )
        }

        // __native_deque_fold: {d: Deque 'a, init: 'b, f: {acc: 'b, x: 'a} -> 'b} -> 'b
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val accType = MezzeType.VarType(TypeVar("b"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_fold"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "init" to accType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf(
                            "acc" to accType,
                            "x" to elemType
                        )),
                        accType
                    )
                )),
                accType
            )
        }

        // ========================================
        // Transformation
        // ========================================

        // __native_deque_reverse: {d: Deque 'a} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_reverse"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                dequeType
            )
        }

        // __native_deque_concat: {d1: Deque 'a, d2: Deque 'a} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_concat"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d1" to dequeType,
                    "d2" to dequeType
                )),
                dequeType
            )
        }

        // __native_deque_equals: {d1: Deque 'a, d2: Deque 'a} -> Bool
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_equals"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d1" to dequeType,
                    "d2" to dequeType
                )),
                MezzeType.BoolType
            )
        }

        // __native_deque_compare: {d1: Deque 'a, d2: Deque 'a} -> Int
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_compare"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d1" to dequeType,
                    "d2" to dequeType
                )),
                MezzeType.IntType
            )
        }

        // ========================================
        // Predicate Operations
        // ========================================

        // __native_deque_any: {d: Deque 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_any"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("x" to elemType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.BoolType
            )
        }

        // __native_deque_all: {d: Deque 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_all"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("x" to elemType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.BoolType
            )
        }

        // __native_deque_none: {d: Deque 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_none"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("x" to elemType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.BoolType
            )
        }

        // __native_deque_find: {d: Deque 'a, p: {x: 'a} -> Bool} -> Option 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_find"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("x" to elemType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.TypeApplication("Option", listOf(elemType))
            )
        }

        // __native_deque_count: {d: Deque 'a, p: {x: 'a} -> Bool} -> Int
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_count"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("x" to elemType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.IntType
            )
        }

        // ========================================
        // Min/Max/Sum/Product
        // ========================================

        // __native_deque_min: {d: Deque 'a} -> Option 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_min"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.TypeApplication("Option", listOf(elemType))
            )
        }

        // __native_deque_max: {d: Deque 'a} -> Option 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_max"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.TypeApplication("Option", listOf(elemType))
            )
        }

        // __native_deque_sum: {d: Deque Int} -> Int
        run {
            val dequeType = MezzeType.TypeApplication("Deque", listOf(MezzeType.IntType))
            context["__native_deque_sum"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.IntType
            )
        }

        // __native_deque_product: {d: Deque Int} -> Int
        run {
            val dequeType = MezzeType.TypeApplication("Deque", listOf(MezzeType.IntType))
            context["__native_deque_product"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.IntType
            )
        }

        // ========================================
        // Contains
        // ========================================

        // __native_deque_contains: {d: Deque 'a, x: 'a} -> Bool
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_contains"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "x" to elemType
                )),
                MezzeType.BoolType
            )
        }

        // ========================================
        // Conversion to/from Seq
        // ========================================

        // __native_deque_to_seq: {d: Deque 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_to_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.SeqType(elemType)
            )
        }

        // __native_deque_from_seq: {xs: ['a]} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_from_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to MezzeType.SeqType(elemType))),
                dequeType
            )
        }

        // ========================================
        // Additional Operations
        // ========================================

        // __native_deque_reduce: {d: Deque 'a, f: {acc: 'a, x: 'a} -> 'a} -> Option 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_reduce"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("acc" to elemType, "x" to elemType)),
                        elemType
                    )
                )),
                MezzeType.TypeApplication("Option", listOf(elemType))
            )
        }

        // __native_deque_flat_map: {d: Deque 'a, f: {val: 'a} -> Deque 'b} -> Deque 'b
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val resultType = MezzeType.VarType(TypeVar("b"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            val resultDequeType = MezzeType.TypeApplication("Deque", listOf(resultType))
            context["__native_deque_flat_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        resultDequeType
                    )
                )),
                resultDequeType
            )
        }

        // __native_deque_partition: {d: Deque 'a, p: {val: 'a} -> Bool} -> {pass: Deque 'a, fail: Deque 'a}
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_partition"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.RecordType(mapOf("pass" to dequeType, "fail" to dequeType))
            )
        }

        // __native_deque_take: {d: Deque 'a, n: Int} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_take"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType, "n" to MezzeType.IntType)),
                dequeType
            )
        }

        // __native_deque_drop: {d: Deque 'a, n: Int} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_drop"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType, "n" to MezzeType.IntType)),
                dequeType
            )
        }

        // __native_deque_unique: {d: Deque 'a} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_unique"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                dequeType
            )
        }

        // __native_deque_sum_float: {d: Deque Float} -> Float
        context["__native_deque_sum_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("d" to MezzeType.TypeApplication("Deque", listOf(MezzeType.FloatType)))),
            MezzeType.FloatType
        )

        // __native_deque_product_float: {d: Deque Float} -> Float
        context["__native_deque_product_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("d" to MezzeType.TypeApplication("Deque", listOf(MezzeType.FloatType)))),
            MezzeType.FloatType
        )

        // __native_deque_sort: {d: Deque 'a} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_sort"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                dequeType
            )
        }

        // ========================================
        // Min/Max By Key (Task #9)
        // ========================================

        // __native_deque_min_by: {d: Deque 'a, f: {val: 'a} -> 'b} -> Option 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val keyType = MezzeType.VarType(TypeVar("b"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_min_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        keyType
                    )
                )),
                MezzeType.TypeApplication("Option", listOf(elemType))
            )
        }

        // __native_deque_max_by: {d: Deque 'a, f: {val: 'a} -> 'b} -> Option 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val keyType = MezzeType.VarType(TypeVar("b"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_max_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        keyType
                    )
                )),
                MezzeType.TypeApplication("Option", listOf(elemType))
            )
        }

        // ========================================
        // Indexed Access (Task #9)
        // ========================================

        // __native_deque_get: {d: Deque 'a, i: Int} -> Option 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_get"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType, "i" to MezzeType.IntType)),
                MezzeType.TypeApplication("Option", listOf(elemType))
            )
        }

        // __native_deque_slice: {d: Deque 'a, start: Int, end: Int} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_slice"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType, "start" to MezzeType.IntType, "end" to MezzeType.IntType)),
                dequeType
            )
        }

        // __native_deque_index_of: {d: Deque 'a, x: 'a} -> Option Int
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_index_of"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType, "x" to elemType)),
                MezzeType.TypeApplication("Option", listOf(MezzeType.IntType))
            )
        }

        // __native_deque_find_index: {d: Deque 'a, p: {val: 'a} -> Bool} -> Option Int
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_find_index"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.TypeApplication("Option", listOf(MezzeType.IntType))
            )
        }

        // ========================================
        // Slicing Operations (Task #10)
        // ========================================

        // __native_deque_take_while: {d: Deque 'a, p: {val: 'a} -> Bool} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_take_while"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        MezzeType.BoolType
                    )
                )),
                dequeType
            )
        }

        // __native_deque_drop_while: {d: Deque 'a, p: {val: 'a} -> Bool} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_drop_while"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        MezzeType.BoolType
                    )
                )),
                dequeType
            )
        }

        // __native_deque_split_at: {d: Deque 'a, i: Int} -> {l: Deque 'a, r: Deque 'a}
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_split_at"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType, "i" to MezzeType.IntType)),
                MezzeType.RecordType(mapOf("l" to dequeType, "r" to dequeType))
            )
        }

        // __native_deque_span: {d: Deque 'a, p: {val: 'a} -> Bool} -> {l: Deque 'a, r: Deque 'a}
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_span"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        MezzeType.BoolType
                    )
                )),
                MezzeType.RecordType(mapOf("l" to dequeType, "r" to dequeType))
            )
        }

        // ========================================
        // Transformation Operations (Task #11)
        // ========================================

        // __native_deque_scan: {d: Deque 'a, init: 'b, f: {acc: 'b, x: 'a} -> 'b} -> Deque 'b
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val accType = MezzeType.VarType(TypeVar("b"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            val resultDequeType = MezzeType.TypeApplication("Deque", listOf(accType))
            context["__native_deque_scan"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "init" to accType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("acc" to accType, "x" to elemType)),
                        accType
                    )
                )),
                resultDequeType
            )
        }

        // __native_deque_dedup: {d: Deque 'a} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_dedup"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                dequeType
            )
        }

        // __native_deque_dedup_by: {d: Deque 'a, f: {val: 'a} -> 'b} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val keyType = MezzeType.VarType(TypeVar("b"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_dedup_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "d" to dequeType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        keyType
                    )
                )),
                dequeType
            )
        }

        // __native_deque_intersperse: {d: Deque 'a, sep: 'a} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_intersperse"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType, "sep" to elemType)),
                dequeType
            )
        }

        // ========================================
        // Combining Operations (Task #12)
        // ========================================

        // __native_deque_zip: {d1: Deque 'a, d2: Deque 'b} -> Deque {first: 'a, second: 'b}
        run {
            val elemType1 = MezzeType.VarType(TypeVar("a"))
            val elemType2 = MezzeType.VarType(TypeVar("b"))
            val dequeType1 = MezzeType.TypeApplication("Deque", listOf(elemType1))
            val dequeType2 = MezzeType.TypeApplication("Deque", listOf(elemType2))
            val pairType = MezzeType.RecordType(mapOf("fst" to elemType1, "snd" to elemType2))
            val resultDequeType = MezzeType.TypeApplication("Deque", listOf(pairType))
            context["__native_deque_zip"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d1" to dequeType1, "d2" to dequeType2)),
                resultDequeType
            )
        }

        // __native_deque_zip_with_index: {d: Deque 'a} -> Deque {val: 'a, idx: Int}
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            val pairType = MezzeType.RecordType(mapOf("val" to elemType, "idx" to MezzeType.IntType))
            val resultDequeType = MezzeType.TypeApplication("Deque", listOf(pairType))
            context["__native_deque_zip_with_index"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                resultDequeType
            )
        }

        // __native_deque_rotate_left: {d: Deque 'a, n: Int} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_rotate_left"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType, "n" to MezzeType.IntType)),
                dequeType
            )
        }

        // __native_deque_rotate_right: {d: Deque 'a, n: Int} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_rotate_right"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType, "n" to MezzeType.IntType)),
                dequeType
            )
        }

        // ==================== Task #28: Cross-Collection Conversions ====================

        // __native_deque_to_set: {d: Deque 'a} -> Set 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            val setType = MezzeType.TypeApplication("Set", listOf(elemType))
            context["__native_deque_to_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                setType
            )
        }

        // __native_deque_to_sorted_set: {d: Deque 'a} -> SortedSet 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            val sortedSetType = MezzeType.TypeApplication("SortedSet", listOf(elemType))
            context["__native_deque_to_sorted_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                sortedSetType
            )
        }

        // __native_deque_to_map: {d: Deque {key: 'k, value: 'v}} -> Map 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valType = MezzeType.VarType(TypeVar("v"))
            val recordType = MezzeType.RecordType(mapOf("key" to keyType, "value" to valType))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(recordType))
            val mapType = MezzeType.TypeApplication("Map", listOf(keyType, valType))
            context["__native_deque_to_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                mapType
            )
        }

        // __native_deque_to_sorted_map: {d: Deque {key: 'k, value: 'v}} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valType = MezzeType.VarType(TypeVar("v"))
            val recordType = MezzeType.RecordType(mapOf("key" to keyType, "value" to valType))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(recordType))
            val sortedMapType = MezzeType.TypeApplication("SortedMap", listOf(keyType, valType))
            context["__native_deque_to_sorted_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                sortedMapType
            )
        }

        // __native_deque_from_set: {s: Set 'a} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val setType = MezzeType.TypeApplication("Set", listOf(elemType))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_from_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to setType)),
                dequeType
            )
        }

        // __native_deque_from_sorted_set: {s: SortedSet 'a} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val sortedSetType = MezzeType.TypeApplication("SortedSet", listOf(elemType))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_from_sorted_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("s" to sortedSetType)),
                dequeType
            )
        }

        // __native_deque_from_map: {m: Map 'k 'v} -> Deque {key: 'k, value: 'v}
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valType = MezzeType.VarType(TypeVar("v"))
            val mapType = MezzeType.TypeApplication("Map", listOf(keyType, valType))
            val recordType = MezzeType.RecordType(mapOf("key" to keyType, "value" to valType))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(recordType))
            context["__native_deque_from_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to mapType)),
                dequeType
            )
        }

        // __native_deque_from_sorted_map: {m: SortedMap 'k 'v} -> Deque {key: 'k, value: 'v}
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valType = MezzeType.VarType(TypeVar("v"))
            val sortedMapType = MezzeType.TypeApplication("SortedMap", listOf(keyType, valType))
            val recordType = MezzeType.RecordType(mapOf("key" to keyType, "value" to valType))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(recordType))
            context["__native_deque_from_sorted_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to sortedMapType)),
                dequeType
            )
        }
    }
}
