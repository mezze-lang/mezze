package com.funk.language.typechecker.types.collections

import com.funk.language.ast.FunkType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: FunkType): FunkType {
    return FunkType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for ArrayList (mutable array) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/array.funk
 */
object ArrayListTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerArrayListNatives(context)
    }

    private fun registerArrayListNatives(context: MutableMap<String, FunkType>) {
        val arrayElemTypeVar = com.funk.language.ast.TypeVar("a")
        val arrayElemType = FunkType.VarType(arrayElemTypeVar)
        val arrayType = FunkType.ArrayType(arrayElemType)

        context["__native_arraylist_length"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to arrayType)),
            FunkType.IntType
        )

        context["__native_arraylist_get"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "i" to FunkType.IntType
            )),
            arrayElemType
        )

        context["__native_arraylist_set"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "i" to FunkType.IntType,
                "x" to arrayElemType
            )),
            arrayType
        )

        context["__native_arraylist_add"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "x" to arrayElemType
            )),
            arrayType
        )

        context["__native_arraylist_remove"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "i" to FunkType.IntType
            )),
            arrayType
        )

        context["__native_arraylist_concat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "ys" to arrayType
            )),
            arrayType
        )

        context["__native_arraylist_is_empty"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to arrayType)),
            FunkType.BooleanType
        )

        val arrayMapFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to arrayElemType)),
            arrayElemType
        )

        context["__native_arraylist_map"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "f" to arrayMapFuncType
            )),
            arrayType
        )

        val arrayPredicateType = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to arrayElemType)),
            FunkType.BooleanType
        )

        context["__native_arraylist_filter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "p" to arrayPredicateType
            )),
            arrayType
        )

        val arrayFoldFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "acc" to arrayElemType,
                "x" to arrayElemType
            )),
            arrayElemType
        )

        context["__native_arraylist_fold"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "init" to arrayElemType,
                "f" to arrayFoldFuncType
            )),
            arrayElemType
        )

        val arrayReduceFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "a" to arrayElemType,
                "b" to arrayElemType
            )),
            arrayElemType
        )

        context["__native_arraylist_reduce"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "f" to arrayReduceFuncType
            )),
            arrayElemType
        )

        context["__native_arraylist_find"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "p" to arrayPredicateType
            )),
            optionType(arrayElemType)
        )

        context["__native_arraylist_contains"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "x" to arrayElemType
            )),
            FunkType.BooleanType
        )

        context["__native_arraylist_index_of"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "x" to arrayElemType
            )),
            optionType(FunkType.IntType)
        )

        context["__native_arraylist_reverse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to arrayType)),
            arrayType
        )

        context["__native_arraylist_sort"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to arrayType)),
            arrayType
        )

        val arrayCmpFuncType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "a" to arrayElemType,
                "b" to arrayElemType
            )),
            FunkType.IntType
        )

        context["__native_arraylist_sort_by"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "cmp" to arrayCmpFuncType
            )),
            arrayType
        )

        context["__native_arraylist_slice"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to arrayType,
                "start" to FunkType.IntType,
                "end" to FunkType.IntType
            )),
            arrayType
        )
    }
}
