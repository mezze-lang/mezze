package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Type signatures for NonEmptyList operations
 *
 * NonEmptyList is a list guaranteed to have at least one element.
 * This provides type-safe operations like head/tail that cannot fail.
 *
 * Corresponds to stdlib file:
 * - std/collections/nonemptylist.mz
 */
object NonEmptyListTypes {

    /**
     * Helper to create Option type
     */
    private fun optionType(elementType: MezzeType): MezzeType {
        return MezzeType.TypeApplication("Option", listOf(elementType))
    }

    /**
     * Helper to create NonEmptyList type
     */
    private fun nelType(elementType: MezzeType): MezzeType {
        return MezzeType.TypeApplication("NonEmptyList", listOf(elementType))
    }

    /**
     * Helper to create List type
     */
    private fun listType(elementType: MezzeType): MezzeType {
        return MezzeType.ListType(elementType)
    }

    /**
     * Helper to create Array type
     */
    private fun arrayType(elementType: MezzeType): MezzeType {
        return MezzeType.ArrayType(elementType)
    }

    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerBasicOperations(context)
        registerConstruction(context)
        registerAccess(context)
        registerHigherOrderFunctions(context)
        registerPredicates(context)
        registerTransformations(context)
        registerConversions(context)
        registerSorting(context)
    }

    private fun registerBasicOperations(context: MutableMap<String, MezzeType>) {
        // __native_nel_singleton: {x: 'a} -> NonEmptyList 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_singleton"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to a)),
                nelType(a)
            )
        }

        // __native_nel_from_head_tail: {head: 'a, tail: List 'a} -> NonEmptyList 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_from_head_tail"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "head" to a,
                    "tail" to listType(a)
                )),
                nelType(a)
            )
        }

        // __native_nel_head: {xs: NonEmptyList 'a} -> 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_head"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to nelType(a))),
                a
            )
        }

        // __native_nel_tail: {xs: NonEmptyList 'a} -> List 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_tail"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to nelType(a))),
                listType(a)
            )
        }

        // __native_nel_length: {xs: NonEmptyList 'a} -> Int
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_length"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to nelType(a))),
                MezzeType.IntType
            )
        }
    }

    private fun registerConstruction(context: MutableMap<String, MezzeType>) {
        // __native_nel_cons: {x: 'a, xs: NonEmptyList 'a} -> NonEmptyList 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_cons"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "x" to a,
                    "xs" to nelType(a)
                )),
                nelType(a)
            )
        }

        // __native_nel_append: {xs: NonEmptyList 'a, ys: NonEmptyList 'a} -> NonEmptyList 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_append"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "ys" to nelType(a)
                )),
                nelType(a)
            )
        }

        // __native_nel_prepend_list: {xs: List 'a, y: 'a} -> NonEmptyList 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_prepend_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType(a),
                    "y" to a
                )),
                nelType(a)
            )
        }

        // __native_nel_append_list: {xs: NonEmptyList 'a, ys: List 'a} -> NonEmptyList 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_append_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "ys" to listType(a)
                )),
                nelType(a)
            )
        }
    }

    private fun registerAccess(context: MutableMap<String, MezzeType>) {
        // __native_nel_get: {xs: NonEmptyList 'a, index: Int} -> 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_get"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "index" to MezzeType.IntType
                )),
                a
            )
        }

        // __native_nel_last: {xs: NonEmptyList 'a} -> 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_last"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to nelType(a))),
                a
            )
        }
    }

    private fun registerHigherOrderFunctions(context: MutableMap<String, MezzeType>) {
        // __native_nel_map: {xs: NonEmptyList 'a, f: {x: 'a} -> 'b} -> NonEmptyList 'b
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            val b = MezzeType.VarType(TypeVar("b"))
            context["__native_nel_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("x" to a)),
                        b
                    )
                )),
                nelType(b)
            )
        }

        // __native_nel_flat_map: {xs: NonEmptyList 'a, f: {x: 'a} -> NonEmptyList 'b} -> NonEmptyList 'b
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            val b = MezzeType.VarType(TypeVar("b"))
            context["__native_nel_flat_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("x" to a)),
                        nelType(b)
                    )
                )),
                nelType(b)
            )
        }

        // __native_nel_fold_left: {xs: NonEmptyList 'a, init: 'acc, f: {acc: 'acc, x: 'a} -> 'acc} -> 'acc
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            val acc = MezzeType.VarType(TypeVar("acc"))
            context["__native_nel_fold_left"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "init" to acc,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("acc" to acc, "x" to a)),
                        acc
                    )
                )),
                acc
            )
        }

        // __native_nel_fold_right: {xs: NonEmptyList 'a, init: 'acc, f: {x: 'a, acc: 'acc} -> 'acc} -> 'acc
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            val acc = MezzeType.VarType(TypeVar("acc"))
            context["__native_nel_fold_right"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "init" to acc,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("x" to a, "acc" to acc)),
                        acc
                    )
                )),
                acc
            )
        }

        // __native_nel_reduce: {xs: NonEmptyList 'a, f: {acc: 'a, x: 'a} -> 'a} -> 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_reduce"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("acc" to a, "x" to a)),
                        a
                    )
                )),
                a
            )
        }
    }

    private fun registerPredicates(context: MutableMap<String, MezzeType>) {
        // __native_nel_any: {xs: NonEmptyList 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_any"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("x" to a)),
                        MezzeType.BooleanType
                    )
                )),
                MezzeType.BooleanType
            )
        }

        // __native_nel_all: {xs: NonEmptyList 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_all"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "p" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("x" to a)),
                        MezzeType.BooleanType
                    )
                )),
                MezzeType.BooleanType
            )
        }

        // __native_nel_contains: {xs: NonEmptyList 'a, x: 'a} -> Bool
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_contains"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "x" to a
                )),
                MezzeType.BooleanType
            )
        }
    }

    private fun registerTransformations(context: MutableMap<String, MezzeType>) {
        // __native_nel_reverse: {xs: NonEmptyList 'a} -> NonEmptyList 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_reverse"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to nelType(a))),
                nelType(a)
            )
        }

        // __native_nel_zip: {xs: NonEmptyList 'a, ys: NonEmptyList 'b} -> NonEmptyList {fst: 'a, snd: 'b}
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            val b = MezzeType.VarType(TypeVar("b"))
            context["__native_nel_zip"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "ys" to nelType(b)
                )),
                nelType(MezzeType.RecordType(mapOf("fst" to a, "snd" to b)))
            )
        }

        // __native_nel_intersperse: {xs: NonEmptyList 'a, sep: 'a} -> NonEmptyList 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_intersperse"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "sep" to a
                )),
                nelType(a)
            )
        }
    }

    private fun registerConversions(context: MutableMap<String, MezzeType>) {
        // __native_nel_to_list: {xs: NonEmptyList 'a} -> List 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_to_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to nelType(a))),
                listType(a)
            )
        }

        // __native_nel_from_list: {xs: List 'a} -> NonEmptyList 'a
        // Note: This throws at runtime if list is empty
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_from_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType(a))),
                nelType(a)
            )
        }

        // __native_nel_to_array: {xs: NonEmptyList 'a} -> ['a]
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_to_array"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to nelType(a))),
                arrayType(a)
            )
        }

        // __native_nel_from_array: {xs: ['a]} -> NonEmptyList 'a
        // Note: This throws at runtime if array is empty
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_from_array"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayType(a))),
                nelType(a)
            )
        }
    }

    private fun registerSorting(context: MutableMap<String, MezzeType>) {
        // __native_nel_sort: {xs: NonEmptyList 'a} -> NonEmptyList 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_sort"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to nelType(a))),
                nelType(a)
            )
        }

        // __native_nel_sort_by: {xs: NonEmptyList 'a, cmp: {l: 'a, r: 'a} -> Int} -> NonEmptyList 'a
        run {
            val a = MezzeType.VarType(TypeVar("a"))
            context["__native_nel_sort_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to nelType(a),
                    "cmp" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("l" to a, "r" to a)),
                        MezzeType.IntType
                    )
                )),
                nelType(a)
            )
        }
    }
}
