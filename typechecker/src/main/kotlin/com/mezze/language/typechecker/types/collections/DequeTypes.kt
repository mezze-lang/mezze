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
                MezzeType.BooleanType
            )
        }

        // __native_deque_length: {d: Deque 'a} -> Int
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_length"] = MezzeType.FunctionType(
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

        // __native_deque_to_array: {d: Deque 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_to_array"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("d" to dequeType)),
                MezzeType.ArrayType(elemType)
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
                        MezzeType.BooleanType
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
                MezzeType.BooleanType
            )
        }
    }
}
