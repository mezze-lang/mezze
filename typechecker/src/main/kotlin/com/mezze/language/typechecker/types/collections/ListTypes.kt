package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for List (immutable linked list) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/list.mz
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 * This is critical because these types are registered as polymorphic and get
 * fresh instantiation on each use. If we shared type variables across functions,
 * unifying one would affect all others.
 */
object ListTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerListNatives(context)
        registerConversionFunctions(context)
    }

    private fun registerListNatives(context: MutableMap<String, MezzeType>) {
        // __native_list_empty: {} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                listType
            )
        }

        // __native_list_cons: {head: 'a, tail: List 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_cons"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "head" to elemType,
                    "tail" to listType
                )),
                listType
            )
        }

        // __native_list_is_empty: {xs: List 'a} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_is_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                MezzeType.BooleanType
            )
        }

        // __native_list_head: {xs: List 'a} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_head"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                optionType(elemType)
            )
        }

        // __native_list_tail: {xs: List 'a} -> Option (List 'a)
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_tail"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                optionType(listType)
            )
        }

        // __native_list_length: {xs: List 'a} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_length"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                MezzeType.IntType
            )
        }

        // __native_list_uncons: {xs: List 'a} -> Option {head: 'a, tail: List 'a}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val resultRecordType = MezzeType.RecordType(mapOf(
                "head" to elemType,
                "tail" to listType
            ))
            context["__native_list_uncons"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                optionType(resultRecordType)
            )
        }

        // __native_list_append: {xs: List 'a, ys: List 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_append"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "ys" to listType
                )),
                listType
            )
        }

        // __native_list_get: {xs: List 'a, index: Int} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_get"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "index" to MezzeType.IntType
                )),
                optionType(elemType)
            )
        }

        // __native_list_take: {xs: List 'a, n: Int} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_take"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "n" to MezzeType.IntType
                )),
                listType
            )
        }

        // __native_list_drop: {xs: List 'a, n: Int} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_drop"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "n" to MezzeType.IntType
                )),
                listType
            )
        }

        // __native_list_map: {xs: List 'a, f: {x: 'a} -> 'b} -> List 'b
        run {
            val inputElemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val inputListType = MezzeType.ListType(inputElemType)
            val outputElemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val outputListType = MezzeType.ListType(outputElemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to inputElemType)),
                outputElemType
            )
            context["__native_list_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to inputListType,
                    "f" to funcType
                )),
                outputListType
            )
        }

        // __native_list_filter: {xs: List 'a, p: {x: 'a} -> Bool} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BooleanType
            )
            context["__native_list_filter"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "p" to predicateType
                )),
                listType
            )
        }

        // __native_list_fold_left: {xs: List 'a, init: 'b, f: {acc: 'b, x: 'a} -> 'b} -> 'b
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val accType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "acc" to accType,
                    "x" to elemType
                )),
                accType
            )
            context["__native_list_fold_left"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "init" to accType,
                    "f" to funcType
                )),
                accType
            )
        }

        // __native_list_fold_right: {xs: List 'a, init: 'b, f: {acc: 'b, x: 'a} -> 'b} -> 'b
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val accType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "acc" to accType,
                    "x" to elemType
                )),
                accType
            )
            context["__native_list_fold_right"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "init" to accType,
                    "f" to funcType
                )),
                accType
            )
        }

        // __native_list_reduce: {xs: List 'a, init: 'a, f: {acc: 'a, x: 'a} -> 'a} -> 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "acc" to elemType,
                    "x" to elemType
                )),
                elemType
            )
            context["__native_list_reduce"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "init" to elemType,
                    "f" to funcType
                )),
                elemType
            )
        }

        // __native_list_any: {xs: List 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BooleanType
            )
            context["__native_list_any"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "p" to predicateType
                )),
                MezzeType.BooleanType
            )
        }

        // __native_list_all: {xs: List 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BooleanType
            )
            context["__native_list_all"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "p" to predicateType
                )),
                MezzeType.BooleanType
            )
        }

        // __native_list_contains: {xs: List 'a, x: 'a} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_contains"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "x" to elemType
                )),
                MezzeType.BooleanType
            )
        }

        // __native_list_reverse: {xs: List 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_reverse"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                listType
            )
        }

        // __native_list_zip: {xs: List 'a, ys: List 'a} -> List {fst: 'a, snd: 'a}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val pairType = MezzeType.RecordType(mapOf(
                "fst" to elemType,
                "snd" to elemType
            ))
            context["__native_list_zip"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "ys" to listType
                )),
                MezzeType.ListType(pairType)
            )
        }

        // __native_list_range: {start: Int, end: Int} -> List Int
        context["__native_list_range"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "start" to MezzeType.IntType,
                "end" to MezzeType.IntType
            )),
            MezzeType.ListType(MezzeType.IntType)
        )

        // __native_list_repeat: {x: 'a, n: Int} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_repeat"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "x" to elemType,
                    "n" to MezzeType.IntType
                )),
                listType
            )
        }

        // __native_list_concat: {xss: List (List 'a)} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val listOfListType = MezzeType.ListType(listType)
            context["__native_list_concat"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xss" to listOfListType)),
                listType
            )
        }

        // __native_list_unzip: {xs: List {fst: 'a, snd: 'a}} -> {fst: List 'a, snd: List 'a}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val pairType = MezzeType.RecordType(mapOf(
                "fst" to elemType,
                "snd" to elemType
            ))
            context["__native_list_unzip"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to MezzeType.ListType(pairType))),
                MezzeType.RecordType(mapOf(
                    "fst" to listType,
                    "snd" to listType
                ))
            )
        }

        // __native_list_intersperse: {xs: List 'a, sep: 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_intersperse"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "sep" to elemType
                )),
                listType
            )
        }

        // __native_list_sort: {xs: List 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_sort"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                listType
            )
        }

        // __native_list_sort_by: {xs: List 'a, cmp: {a: 'a, b: 'a} -> Int} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val cmpFuncType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "a" to elemType,
                    "b" to elemType
                )),
                MezzeType.IntType
            )
            context["__native_list_sort_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "cmp" to cmpFuncType
                )),
                listType
            )
        }

        // === New Utility Functions ===

        // __native_list_find: {xs: List 'a, p: {x: 'a} -> Bool} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BooleanType
            )
            context["__native_list_find"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "p" to predicateType)),
                optionType(elemType)
            )
        }

        // __native_list_find_index: {xs: List 'a, p: {x: 'a} -> Bool} -> Option Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BooleanType
            )
            context["__native_list_find_index"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "p" to predicateType)),
                optionType(MezzeType.IntType)
            )
        }

        // __native_list_partition: {xs: List 'a, p: {x: 'a} -> Bool} -> {matching: List 'a, not_matching: List 'a}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BooleanType
            )
            val resultType = MezzeType.RecordType(mapOf(
                "matching" to listType,
                "not_matching" to listType
            ))
            context["__native_list_partition"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "p" to predicateType)),
                resultType
            )
        }

        // __native_list_last: {xs: List 'a} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_last"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                optionType(elemType)
            )
        }

        // __native_list_init: {xs: List 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_init"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                listType
            )
        }
    }

    private fun registerConversionFunctions(context: MutableMap<String, MezzeType>) {
        // __native_list_to_array: {xs: List 'a} -> Array 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_to_array"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                MezzeType.ArrayType(elemType)
            )
        }

        // __native_array_to_list: {arr: Array 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            context["__native_array_to_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("arr" to MezzeType.ArrayType(elemType))),
                MezzeType.ListType(elemType)
            )
        }

        // __native_list_from_array: {xs: Array 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            context["__native_list_from_array"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to MezzeType.ArrayType(elemType))),
                MezzeType.ListType(elemType)
            )
        }
    }
}
