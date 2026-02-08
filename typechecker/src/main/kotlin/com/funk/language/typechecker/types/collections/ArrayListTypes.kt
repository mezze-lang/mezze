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
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 * This is critical because these types are registered as polymorphic and get
 * fresh instantiation on each use. If we shared type variables across functions,
 * unifying one would affect all others.
 */
object ArrayListTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerArrayListNatives(context)
    }

    private fun registerArrayListNatives(context: MutableMap<String, FunkType>) {
        // __native_arraylist_length: {xs: ['a]} -> Int
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            context["__native_arraylist_length"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to arrayType)),
                FunkType.IntType
            )
        }

        // __native_arraylist_get: {xs: ['a], i: Int} -> Option 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            context["__native_arraylist_get"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to arrayType,
                    "i" to FunkType.IntType
                )),
                optionType(elemType)
            )
        }

        // __native_arraylist_set: {xs: ['a], i: Int, x: 'a} -> Option ['a]
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            context["__native_arraylist_set"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to arrayType,
                    "i" to FunkType.IntType,
                    "x" to elemType
                )),
                optionType(arrayType)
            )
        }

        // __native_arraylist_add: {xs: ['a], x: 'a} -> ['a]
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            context["__native_arraylist_add"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to arrayType,
                    "x" to elemType
                )),
                arrayType
            )
        }

        // __native_arraylist_remove: {xs: ['a], i: Int} -> Option ['a]
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            context["__native_arraylist_remove"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to arrayType,
                    "i" to FunkType.IntType
                )),
                optionType(arrayType)
            )
        }

        // __native_arraylist_concat: {xs: ['a], ys: ['a]} -> ['a]
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            context["__native_arraylist_concat"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to arrayType,
                    "ys" to arrayType
                )),
                arrayType
            )
        }

        // __native_arraylist_is_empty: {xs: ['a]} -> Bool
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            context["__native_arraylist_is_empty"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to arrayType)),
                FunkType.BooleanType
            )
        }

        // __native_arraylist_map: {xs: ['a], f: {x: 'a} -> 'b} -> ['b]
        // Note: Map allows transformation from 'a to 'b (different element types)
        run {
            val inputElemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val inputArrayType = FunkType.ArrayType(inputElemType)
            val outputElemType = FunkType.VarType(com.funk.language.ast.TypeVar("b"))
            val outputArrayType = FunkType.ArrayType(outputElemType)
            val funcType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("x" to inputElemType)),
                outputElemType
            )
            context["__native_arraylist_map"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to inputArrayType,
                    "f" to funcType
                )),
                outputArrayType
            )
        }

        // __native_arraylist_filter: {xs: ['a], p: {x: 'a} -> Bool} -> ['a]
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            val predicateType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("x" to elemType)),
                FunkType.BooleanType
            )
            context["__native_arraylist_filter"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to arrayType,
                    "p" to predicateType
                )),
                arrayType
            )
        }

        // __native_arraylist_fold: {xs: ['a], init: 'b, f: {acc: 'b, x: 'a} -> 'b} -> 'b
        // Note: Fold has separate accumulator type 'b and element type 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            val accType = FunkType.VarType(com.funk.language.ast.TypeVar("b"))
            val funcType = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "acc" to accType,
                    "x" to elemType
                )),
                accType
            )
            context["__native_arraylist_fold"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to arrayType,
                    "init" to accType,
                    "f" to funcType
                )),
                accType
            )
        }

        // __native_arraylist_reduce: {xs: ['a], f: {acc: 'a, x: 'a} -> 'a} -> 'a
        // Note: Reduce uses same type for accumulator and elements
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            val funcType = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "acc" to elemType,
                    "x" to elemType
                )),
                elemType
            )
            context["__native_arraylist_reduce"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to arrayType,
                    "f" to funcType
                )),
                elemType
            )
        }

        // __native_arraylist_find: {xs: ['a], p: {x: 'a} -> Bool} -> Option 'a
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            val predicateType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("x" to elemType)),
                FunkType.BooleanType
            )
            context["__native_arraylist_find"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to arrayType,
                    "p" to predicateType
                )),
                optionType(elemType)
            )
        }

        // __native_arraylist_contains: {xs: ['a], x: 'a} -> Bool
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            context["__native_arraylist_contains"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to arrayType,
                    "x" to elemType
                )),
                FunkType.BooleanType
            )
        }

        // __native_arraylist_index_of: {xs: ['a], x: 'a} -> Option Int
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            context["__native_arraylist_index_of"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to arrayType,
                    "x" to elemType
                )),
                optionType(FunkType.IntType)
            )
        }

        // __native_arraylist_reverse: {xs: ['a]} -> ['a]
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            context["__native_arraylist_reverse"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to arrayType)),
                arrayType
            )
        }

        // __native_arraylist_sort: {xs: ['a]} -> ['a]
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            context["__native_arraylist_sort"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("xs" to arrayType)),
                arrayType
            )
        }

        // __native_arraylist_sort_by: {xs: ['a], cmp: {a: 'a, b: 'a} -> Int} -> ['a]
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            val cmpFuncType = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "a" to elemType,
                    "b" to elemType
                )),
                FunkType.IntType
            )
            context["__native_arraylist_sort_by"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to arrayType,
                    "cmp" to cmpFuncType
                )),
                arrayType
            )
        }

        // __native_arraylist_slice: {xs: ['a], start: Int, end: Int} -> ['a]
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            context["__native_arraylist_slice"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "xs" to arrayType,
                    "start" to FunkType.IntType,
                    "end" to FunkType.IntType
                )),
                arrayType
            )
        }

        // __native_arraylist_equals: {l: ['a], r: ['a]} -> Bool
        run {
            val elemType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            val arrayType = FunkType.ArrayType(elemType)
            context["__native_arraylist_equals"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "l" to arrayType,
                    "r" to arrayType
                )),
                FunkType.BooleanType
            )
        }
    }
}
