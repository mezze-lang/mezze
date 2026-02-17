package com.funk.language.typechecker.types.collections

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

/**
 * Type signatures for NonEmptyList operations
 *
 * NonEmptyList is a list guaranteed to have at least one element.
 * This provides type-safe operations like head/tail that cannot fail.
 *
 * Corresponds to stdlib file:
 * - std/collections/nonemptylist.funk
 */
object NonEmptyListTypes {

    /**
     * Helper to create Option type
     */
    private fun optionType(elementType: FunkType): FunkType {
        return FunkType.TypeApplication("Option", listOf(elementType))
    }

    /**
     * Helper to create NonEmptyList type
     */
    private fun nelType(elementType: FunkType): FunkType {
        return FunkType.TypeApplication("NonEmptyList", listOf(elementType))
    }

    /**
     * Helper to create List type
     */
    private fun listType(elementType: FunkType): FunkType {
        return FunkType.ListType(elementType)
    }

    /**
     * Helper to create Array type
     */
    private fun arrayType(elementType: FunkType): FunkType {
        return FunkType.ArrayType(elementType)
    }

    fun registerAll(context: MutableMap<String, FunkType>) {
        registerBasicOperations(context)
        registerConstruction(context)
        registerAccess(context)
        registerHigherOrderFunctions(context)
        registerPredicates(context)
        registerTransformations(context)
        registerConversions(context)
        registerSorting(context)
    }

    private fun registerBasicOperations(context: MutableMap<String, FunkType>) {
        // __native_nel_singleton: {x: 'a} -> NonEmptyList 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_singleton"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("x" to a)),
                nelType(a)
            )
        }

        // __native_nel_from_head_tail: {head: 'a, tail: List 'a} -> NonEmptyList 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_from_head_tail"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "head" to a,
                    "tail" to listType(a)
                )),
                nelType(a)
            )
        }

        // __native_nel_head: {xs: NonEmptyList 'a} -> 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_head"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to nelType(a))),
                a
            )
        }

        // __native_nel_tail: {xs: NonEmptyList 'a} -> List 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_tail"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to nelType(a))),
                listType(a)
            )
        }

        // __native_nel_length: {xs: NonEmptyList 'a} -> Int
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_length"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to nelType(a))),
                FunkType.IntType
            )
        }
    }

    private fun registerConstruction(context: MutableMap<String, FunkType>) {
        // __native_nel_cons: {x: 'a, xs: NonEmptyList 'a} -> NonEmptyList 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_cons"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "x" to a,
                    "xs" to nelType(a)
                )),
                nelType(a)
            )
        }

        // __native_nel_append: {xs: NonEmptyList 'a, ys: NonEmptyList 'a} -> NonEmptyList 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_append"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "ys" to nelType(a)
                )),
                nelType(a)
            )
        }

        // __native_nel_prepend_list: {xs: List 'a, y: 'a} -> NonEmptyList 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_prepend_list"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType(a),
                    "y" to a
                )),
                nelType(a)
            )
        }

        // __native_nel_append_list: {xs: NonEmptyList 'a, ys: List 'a} -> NonEmptyList 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_append_list"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "ys" to listType(a)
                )),
                nelType(a)
            )
        }
    }

    private fun registerAccess(context: MutableMap<String, FunkType>) {
        // __native_nel_get: {xs: NonEmptyList 'a, index: Int} -> 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_get"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "index" to FunkType.IntType
                )),
                a
            )
        }

        // __native_nel_last: {xs: NonEmptyList 'a} -> 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_last"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to nelType(a))),
                a
            )
        }
    }

    private fun registerHigherOrderFunctions(context: MutableMap<String, FunkType>) {
        // __native_nel_map: {xs: NonEmptyList 'a, f: {x: 'a} -> 'b} -> NonEmptyList 'b
        run {
            val a = FunkType.VarType(TypeVar("a"))
            val b = FunkType.VarType(TypeVar("b"))
            context["__native_nel_map"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "f" to FunkType.FunctionType(
                        FunkType.RecordType(mapOf("x" to a)),
                        b
                    )
                )),
                nelType(b)
            )
        }

        // __native_nel_flat_map: {xs: NonEmptyList 'a, f: {x: 'a} -> NonEmptyList 'b} -> NonEmptyList 'b
        run {
            val a = FunkType.VarType(TypeVar("a"))
            val b = FunkType.VarType(TypeVar("b"))
            context["__native_nel_flat_map"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "f" to FunkType.FunctionType(
                        FunkType.RecordType(mapOf("x" to a)),
                        nelType(b)
                    )
                )),
                nelType(b)
            )
        }

        // __native_nel_fold_left: {xs: NonEmptyList 'a, init: 'acc, f: {acc: 'acc, x: 'a} -> 'acc} -> 'acc
        run {
            val a = FunkType.VarType(TypeVar("a"))
            val acc = FunkType.VarType(TypeVar("acc"))
            context["__native_nel_fold_left"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "init" to acc,
                    "f" to FunkType.FunctionType(
                        FunkType.RecordType(mapOf("acc" to acc, "x" to a)),
                        acc
                    )
                )),
                acc
            )
        }

        // __native_nel_fold_right: {xs: NonEmptyList 'a, init: 'acc, f: {x: 'a, acc: 'acc} -> 'acc} -> 'acc
        run {
            val a = FunkType.VarType(TypeVar("a"))
            val acc = FunkType.VarType(TypeVar("acc"))
            context["__native_nel_fold_right"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "init" to acc,
                    "f" to FunkType.FunctionType(
                        FunkType.RecordType(mapOf("x" to a, "acc" to acc)),
                        acc
                    )
                )),
                acc
            )
        }

        // __native_nel_reduce: {xs: NonEmptyList 'a, f: {acc: 'a, x: 'a} -> 'a} -> 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_reduce"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "f" to FunkType.FunctionType(
                        FunkType.RecordType(mapOf("acc" to a, "x" to a)),
                        a
                    )
                )),
                a
            )
        }
    }

    private fun registerPredicates(context: MutableMap<String, FunkType>) {
        // __native_nel_any: {xs: NonEmptyList 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_any"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "p" to FunkType.FunctionType(
                        FunkType.RecordType(mapOf("x" to a)),
                        FunkType.BooleanType
                    )
                )),
                FunkType.BooleanType
            )
        }

        // __native_nel_all: {xs: NonEmptyList 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_all"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "p" to FunkType.FunctionType(
                        FunkType.RecordType(mapOf("x" to a)),
                        FunkType.BooleanType
                    )
                )),
                FunkType.BooleanType
            )
        }

        // __native_nel_contains: {xs: NonEmptyList 'a, x: 'a} -> Bool
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_contains"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "x" to a
                )),
                FunkType.BooleanType
            )
        }
    }

    private fun registerTransformations(context: MutableMap<String, FunkType>) {
        // __native_nel_reverse: {xs: NonEmptyList 'a} -> NonEmptyList 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_reverse"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to nelType(a))),
                nelType(a)
            )
        }

        // __native_nel_zip: {xs: NonEmptyList 'a, ys: NonEmptyList 'b} -> NonEmptyList {fst: 'a, snd: 'b}
        run {
            val a = FunkType.VarType(TypeVar("a"))
            val b = FunkType.VarType(TypeVar("b"))
            context["__native_nel_zip"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "ys" to nelType(b)
                )),
                nelType(FunkType.RecordType(mapOf("fst" to a, "snd" to b)))
            )
        }

        // __native_nel_intersperse: {xs: NonEmptyList 'a, sep: 'a} -> NonEmptyList 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_intersperse"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "sep" to a
                )),
                nelType(a)
            )
        }
    }

    private fun registerConversions(context: MutableMap<String, FunkType>) {
        // __native_nel_to_list: {xs: NonEmptyList 'a} -> List 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_to_list"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to nelType(a))),
                listType(a)
            )
        }

        // __native_nel_from_list: {xs: List 'a} -> NonEmptyList 'a
        // Note: This throws at runtime if list is empty
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_from_list"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to listType(a))),
                nelType(a)
            )
        }

        // __native_nel_to_array: {xs: NonEmptyList 'a} -> ['a]
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_to_array"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to nelType(a))),
                arrayType(a)
            )
        }

        // __native_nel_from_array: {xs: ['a]} -> NonEmptyList 'a
        // Note: This throws at runtime if array is empty
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_from_array"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to arrayType(a))),
                nelType(a)
            )
        }
    }

    private fun registerSorting(context: MutableMap<String, FunkType>) {
        // __native_nel_sort: {xs: NonEmptyList 'a} -> NonEmptyList 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_sort"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to nelType(a))),
                nelType(a)
            )
        }

        // __native_nel_sort_by: {xs: NonEmptyList 'a, cmp: {l: 'a, r: 'a} -> Int} -> NonEmptyList 'a
        run {
            val a = FunkType.VarType(TypeVar("a"))
            context["__native_nel_sort_by"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "cmp" to FunkType.FunctionType(
                        FunkType.RecordType(mapOf("l" to a, "r" to a)),
                        FunkType.IntType
                    )
                )),
                nelType(a)
            )
        }
    }
}
