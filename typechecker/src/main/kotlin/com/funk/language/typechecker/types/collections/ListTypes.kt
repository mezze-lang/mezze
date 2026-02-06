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
 */
object ListTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerListNatives(context)
        registerConversionFunctions(context)
    }

    private fun registerListNatives(context: MutableMap<String, FunkType>) {
        val listElemTypeVar = com.funk.language.ast.TypeVar("a")
        val listElemType = FunkType.VarType(listElemTypeVar)
        val listType = FunkType.ListType(listElemType)

        context["__native_list_empty"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            listType
        )

        context["__native_list_cons"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "head" to listElemType,
                "tail" to listType
            )),
            listType
        )

        context["__native_list_is_empty"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            FunkType.BooleanType
        )

        context["__native_list_head"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            optionType(listElemType)
        )

        context["__native_list_tail"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            optionType(listType)
        )

        context["__native_list_length"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            FunkType.IntType
        )

        context["__native_list_append"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "ys" to listType
            )),
            listType
        )

        context["__native_list_get"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "index" to FunkType.IntType
            )),
            optionType(listElemType)
        )

        context["__native_list_take"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "n" to FunkType.IntType
            )),
            listType
        )

        context["__native_list_drop"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "n" to FunkType.IntType
            )),
            listType
        )

        val mapFuncTypeVar = com.funk.language.ast.TypeVar("b")
        val mapFuncReturnType = FunkType.VarType(mapFuncTypeVar)
        // The interpreter wraps elements in {x: elem}, so callback takes {x: 'a} -> 'b
        val mapFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to listElemType)),
            mapFuncReturnType
        )
        val mappedListType = FunkType.ListType(mapFuncReturnType)

        context["__native_list_map"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "f" to mapFuncType
            )),
            mappedListType
        )

        // Funk functions always take records, so predicate takes {x: 'a} -> Bool
        val predicateType = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to listElemType)),
            FunkType.BooleanType
        )

        context["__native_list_filter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "p" to predicateType
            )),
            listType
        )

        val foldTypeVar = com.funk.language.ast.TypeVar("acc")
        val foldAccType = FunkType.VarType(foldTypeVar)
        // Fold callback takes {acc: 'acc, x: 'a} -> 'acc
        val foldFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "acc" to foldAccType,
                "x" to listElemType
            )),
            foldAccType
        )

        context["__native_list_fold_left"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "init" to foldAccType,
                "f" to foldFuncType
            )),
            foldAccType
        )

        context["__native_list_fold_right"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "init" to foldAccType,
                "f" to foldFuncType
            )),
            foldAccType
        )

        // Reduce callback takes {acc: 'a, x: 'a} -> 'a
        val reduceFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "acc" to listElemType,
                "x" to listElemType
            )),
            listElemType
        )

        context["__native_list_reduce"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "init" to listElemType,
                "f" to reduceFuncType
            )),
            listElemType
        )

        context["__native_list_any"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "p" to predicateType
            )),
            FunkType.BooleanType
        )

        context["__native_list_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "p" to predicateType
            )),
            FunkType.BooleanType
        )

        context["__native_list_contains"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "x" to listElemType
            )),
            FunkType.BooleanType
        )

        context["__native_list_reverse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            listType
        )

        context["__native_list_zip"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "ys" to listType
            )),
            FunkType.ListType(FunkType.RecordType(mapOf(
                "fst" to listElemType,
                "snd" to listElemType
            )))
        )

        context["__native_list_range"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "start" to FunkType.IntType,
                "end" to FunkType.IntType
            )),
            FunkType.ListType(FunkType.IntType)
        )

        context["__native_list_repeat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "x" to listElemType,
                "n" to FunkType.IntType
            )),
            listType
        )

        context["__native_list_concat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xss" to FunkType.ListType(listType))),
            listType
        )

        context["__native_list_unzip"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to FunkType.ListType(FunkType.RecordType(mapOf(
                "fst" to listElemType,
                "snd" to listElemType
            ))))),
            FunkType.RecordType(mapOf(
                "fst" to listType,
                "snd" to listType
            ))
        )

        context["__native_list_intersperse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to listType,
                "sep" to listElemType
            )),
            listType
        )

        context["__native_list_sort"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            listType
        )

        val cmpFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "a" to listElemType,
                "b" to listElemType
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

    private fun registerConversionFunctions(context: MutableMap<String, FunkType>) {
        val listElemTypeVar = com.funk.language.ast.TypeVar("a")
        val listElemType = FunkType.VarType(listElemTypeVar)
        val listType = FunkType.ListType(listElemType)

        context["__native_list_to_array"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to listType)),
            FunkType.ArrayType(listElemType)
        )

        context["__native_array_to_list"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("arr" to FunkType.ArrayType(listElemType))),
            listType
        )

        context["__native_list_from_array"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to FunkType.ArrayType(listElemType))),
            listType
        )
    }
}
