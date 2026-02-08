package com.funk.language.typechecker.types.collections

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

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
 * - std/collections/deque.funk
 */
object DequeTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerDequeNatives(context)
    }

    private fun registerDequeNatives(context: MutableMap<String, FunkType>) {

        // ========================================
        // Construction
        // ========================================

        // __native_deque_empty: {} -> Deque 'a
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_empty"] = FunkType.FunctionType(
                FunkType.RecordType(emptyMap()),
                dequeType
            )
        }

        // __native_deque_singleton: {x: 'a} -> Deque 'a
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_singleton"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("x" to elemType)),
                dequeType
            )
        }

        // __native_deque_from_list: {xs: List 'a} -> Deque 'a
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_from_list"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to FunkType.ListType(elemType))),
                dequeType
            )
        }

        // ========================================
        // Add elements
        // ========================================

        // __native_deque_push_front: {d: Deque 'a, x: 'a} -> Deque 'a
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_push_front"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "d" to dequeType,
                    "x" to elemType
                )),
                dequeType
            )
        }

        // __native_deque_push_back: {d: Deque 'a, x: 'a} -> Deque 'a
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_push_back"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
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
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_pop_front"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("d" to dequeType)),
                FunkType.TypeApplication("Option", listOf(
                    FunkType.RecordType(mapOf(
                        "head" to elemType,
                        "tail" to dequeType
                    ))
                ))
            )
        }

        // __native_deque_pop_back: {d: Deque 'a} -> Option {init: Deque 'a, last: 'a}
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_pop_back"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("d" to dequeType)),
                FunkType.TypeApplication("Option", listOf(
                    FunkType.RecordType(mapOf(
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
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_peek_front"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("d" to dequeType)),
                FunkType.TypeApplication("Option", listOf(elemType))
            )
        }

        // __native_deque_peek_back: {d: Deque 'a} -> Option 'a
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_peek_back"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("d" to dequeType)),
                FunkType.TypeApplication("Option", listOf(elemType))
            )
        }

        // ========================================
        // Queries
        // ========================================

        // __native_deque_is_empty: {d: Deque 'a} -> Bool
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_is_empty"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("d" to dequeType)),
                FunkType.BooleanType
            )
        }

        // __native_deque_length: {d: Deque 'a} -> Int
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_length"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("d" to dequeType)),
                FunkType.IntType
            )
        }

        // ========================================
        // Conversions
        // ========================================

        // __native_deque_to_list: {d: Deque 'a} -> List 'a
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_to_list"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("d" to dequeType)),
                FunkType.ListType(elemType)
            )
        }

        // __native_deque_to_array: {d: Deque 'a} -> ['a]
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_to_array"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("d" to dequeType)),
                FunkType.ArrayType(elemType)
            )
        }

        // ========================================
        // Higher-order functions
        // ========================================

        // __native_deque_map: {d: Deque 'a, f: {x: 'a} -> 'b} -> Deque 'b
        run {
            val elemTypeA = FunkType.VarType(TypeVar("a"))
            val elemTypeB = FunkType.VarType(TypeVar("b"))
            val dequeTypeA = FunkType.TypeApplication("Deque", listOf(elemTypeA))
            val dequeTypeB = FunkType.TypeApplication("Deque", listOf(elemTypeB))
            context["__native_deque_map"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "d" to dequeTypeA,
                    "f" to FunkType.FunctionType(
                        FunkType.RecordType(mapOf("x" to elemTypeA)),
                        elemTypeB
                    )
                )),
                dequeTypeB
            )
        }

        // __native_deque_filter: {d: Deque 'a, p: {x: 'a} -> Bool} -> Deque 'a
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_filter"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "d" to dequeType,
                    "p" to FunkType.FunctionType(
                        FunkType.RecordType(mapOf("x" to elemType)),
                        FunkType.BooleanType
                    )
                )),
                dequeType
            )
        }

        // __native_deque_fold: {d: Deque 'a, init: 'b, f: {acc: 'b, x: 'a} -> 'b} -> 'b
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val accType = FunkType.VarType(TypeVar("b"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_fold"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "d" to dequeType,
                    "init" to accType,
                    "f" to FunkType.FunctionType(
                        FunkType.RecordType(mapOf(
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
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_reverse"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("d" to dequeType)),
                dequeType
            )
        }

        // __native_deque_concat: {d1: Deque 'a, d2: Deque 'a} -> Deque 'a
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_concat"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "d1" to dequeType,
                    "d2" to dequeType
                )),
                dequeType
            )
        }

        // __native_deque_equals: {d1: Deque 'a, d2: Deque 'a} -> Bool
        run {
            val elemType = FunkType.VarType(TypeVar("a"))
            val dequeType = FunkType.TypeApplication("Deque", listOf(elemType))
            context["__native_deque_equals"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "d1" to dequeType,
                    "d2" to dequeType
                )),
                FunkType.BooleanType
            )
        }
    }
}
