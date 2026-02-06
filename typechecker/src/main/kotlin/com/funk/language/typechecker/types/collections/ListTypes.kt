package com.funk.language.typechecker.types.collections

import com.funk.language.ast.FunkType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: FunkType): FunkType {
    return FunkType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for List (immutable linked list) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/list.funk
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 * This is critical because these types are registered as polymorphic and get
 * fresh instantiation on each use. If we shared type variables across functions,
 * unifying one would affect all others.
 */
object ListTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerListNatives(context)
        registerConversionFunctions(context)
    }

    private fun registerListNatives(context: MutableMap<String, FunkType>) {
        // __native_list_empty: {} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_empty"] = FunkType.FunctionType(
                FunkType.RecordType(emptyMap()),
                listType
            )
        }

        // __native_list_cons: {head: 'a, tail: List 'a} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_cons"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "head" to elemType,
                    "tail" to listType
                )),
                listType
            )
        }

        // __native_list_is_empty: {xs: List 'a} -> Bool
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_is_empty"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to listType)),
                FunkType.BooleanType
            )
        }

        // __native_list_head: {xs: List 'a} -> Option 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_head"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to listType)),
                optionType(elemType)
            )
        }

        // __native_list_tail: {xs: List 'a} -> Option (List 'a)
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_tail"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to listType)),
                optionType(listType)
            )
        }

        // __native_list_length: {xs: List 'a} -> Int
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_length"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to listType)),
                FunkType.IntType
            )
        }

        // __native_list_append: {xs: List 'a, ys: List 'a} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_append"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "ys" to listType
                )),
                listType
            )
        }

        // __native_list_get: {xs: List 'a, index: Int} -> Option 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_get"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "index" to FunkType.IntType
                )),
                optionType(elemType)
            )
        }

        // __native_list_take: {xs: List 'a, n: Int} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_take"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "n" to FunkType.IntType
                )),
                listType
            )
        }

        // __native_list_drop: {xs: List 'a, n: Int} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_drop"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "n" to FunkType.IntType
                )),
                listType
            )
        }

        // __native_list_map: {xs: List 'a, f: {x: 'a} -> 'b} -> List 'b
        run {
            val inputElemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val inputListType = FunkType.ListType(inputElemType)
            val outputElemType = FunkType.VarType(com.funk.language.ast.TypeVar("b"))
            val outputListType = FunkType.ListType(outputElemType)
            val funcType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("x" to inputElemType)),
                outputElemType
            )
            context["__native_list_map"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to inputListType,
                    "f" to funcType
                )),
                outputListType
            )
        }

        // __native_list_filter: {xs: List 'a, p: {x: 'a} -> Bool} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            val predicateType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("x" to elemType)),
                FunkType.BooleanType
            )
            context["__native_list_filter"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "p" to predicateType
                )),
                listType
            )
        }

        // __native_list_fold_left: {xs: List 'a, init: 'b, f: {acc: 'b, x: 'a} -> 'b} -> 'b
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            val accType = FunkType.VarType(com.funk.language.ast.TypeVar("b"))
            val funcType = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "acc" to accType,
                    "x" to elemType
                )),
                accType
            )
            context["__native_list_fold_left"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "init" to accType,
                    "f" to funcType
                )),
                accType
            )
        }

        // __native_list_fold_right: {xs: List 'a, init: 'b, f: {acc: 'b, x: 'a} -> 'b} -> 'b
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            val accType = FunkType.VarType(com.funk.language.ast.TypeVar("b"))
            val funcType = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "acc" to accType,
                    "x" to elemType
                )),
                accType
            )
            context["__native_list_fold_right"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "init" to accType,
                    "f" to funcType
                )),
                accType
            )
        }

        // __native_list_reduce: {xs: List 'a, init: 'a, f: {acc: 'a, x: 'a} -> 'a} -> 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            val funcType = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "acc" to elemType,
                    "x" to elemType
                )),
                elemType
            )
            context["__native_list_reduce"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "init" to elemType,
                    "f" to funcType
                )),
                elemType
            )
        }

        // __native_list_any: {xs: List 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            val predicateType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("x" to elemType)),
                FunkType.BooleanType
            )
            context["__native_list_any"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "p" to predicateType
                )),
                FunkType.BooleanType
            )
        }

        // __native_list_all: {xs: List 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            val predicateType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("x" to elemType)),
                FunkType.BooleanType
            )
            context["__native_list_all"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "p" to predicateType
                )),
                FunkType.BooleanType
            )
        }

        // __native_list_contains: {xs: List 'a, x: 'a} -> Bool
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_contains"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "x" to elemType
                )),
                FunkType.BooleanType
            )
        }

        // __native_list_reverse: {xs: List 'a} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_reverse"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to listType)),
                listType
            )
        }

        // __native_list_zip: {xs: List 'a, ys: List 'a} -> List {fst: 'a, snd: 'a}
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            val pairType = FunkType.RecordType(mapOf(
                "fst" to elemType,
                "snd" to elemType
            ))
            context["__native_list_zip"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "ys" to listType
                )),
                FunkType.ListType(pairType)
            )
        }

        // __native_list_range: {start: Int, end: Int} -> List Int
        context["__native_list_range"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "start" to FunkType.IntType,
                "end" to FunkType.IntType
            )),
            FunkType.ListType(FunkType.IntType)
        )

        // __native_list_repeat: {x: 'a, n: Int} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_repeat"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "x" to elemType,
                    "n" to FunkType.IntType
                )),
                listType
            )
        }

        // __native_list_concat: {xss: List (List 'a)} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            val listOfListType = FunkType.ListType(listType)
            context["__native_list_concat"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xss" to listOfListType)),
                listType
            )
        }

        // __native_list_unzip: {xs: List {fst: 'a, snd: 'a}} -> {fst: List 'a, snd: List 'a}
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            val pairType = FunkType.RecordType(mapOf(
                "fst" to elemType,
                "snd" to elemType
            ))
            context["__native_list_unzip"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to FunkType.ListType(pairType))),
                FunkType.RecordType(mapOf(
                    "fst" to listType,
                    "snd" to listType
                ))
            )
        }

        // __native_list_intersperse: {xs: List 'a, sep: 'a} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_intersperse"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "sep" to elemType
                )),
                listType
            )
        }

        // __native_list_sort: {xs: List 'a} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_sort"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to listType)),
                listType
            )
        }

        // __native_list_sort_by: {xs: List 'a, cmp: {a: 'a, b: 'a} -> Int} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            val cmpFuncType = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "a" to elemType,
                    "b" to elemType
                )),
                FunkType.IntType
            )
            context["__native_list_sort_by"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to listType,
                    "cmp" to cmpFuncType
                )),
                listType
            )
        }
    }

    private fun registerConversionFunctions(context: MutableMap<String, FunkType>) {
        // __native_list_to_array: {xs: List 'a} -> Array 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val listType = FunkType.ListType(elemType)
            context["__native_list_to_array"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to listType)),
                FunkType.ArrayType(elemType)
            )
        }

        // __native_array_to_list: {arr: Array 'a} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            context["__native_array_to_list"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("arr" to FunkType.ArrayType(elemType))),
                FunkType.ListType(elemType)
            )
        }

        // __native_list_from_array: {xs: Array 'a} -> List 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            context["__native_list_from_array"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to FunkType.ArrayType(elemType))),
                FunkType.ListType(elemType)
            )
        }
    }
}
