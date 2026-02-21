package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for ArrayList (mutable array) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/array.mz
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 * This is critical because these types are registered as polymorphic and get
 * fresh instantiation on each use. If we shared type variables across functions,
 * unifying one would affect all others.
 */
object ArrayListTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerArrayListNatives(context)
    }

    private fun registerArrayListNatives(context: MutableMap<String, MezzeType>) {
        // __native_arraylist_length: {xs: ['a]} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_length"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayType)),
                MezzeType.IntType
            )
        }

        // __native_arraylist_get: {xs: ['a], i: Int} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_get"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to arrayType,
                    "i" to MezzeType.IntType
                )),
                optionType(elemType)
            )
        }

        // __native_arraylist_set: {xs: ['a], i: Int, x: 'a} -> Option ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to arrayType,
                    "i" to MezzeType.IntType,
                    "x" to elemType
                )),
                optionType(arrayType)
            )
        }

        // __native_arraylist_add: {xs: ['a], x: 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_add"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to arrayType,
                    "x" to elemType
                )),
                arrayType
            )
        }

        // __native_arraylist_remove: {xs: ['a], i: Int} -> Option ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_remove"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to arrayType,
                    "i" to MezzeType.IntType
                )),
                optionType(arrayType)
            )
        }

        // __native_arraylist_concat: {xs: ['a], ys: ['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_concat"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to arrayType,
                    "ys" to arrayType
                )),
                arrayType
            )
        }

        // __native_arraylist_is_empty: {xs: ['a]} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_is_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayType)),
                MezzeType.BooleanType
            )
        }

        // __native_arraylist_map: {xs: ['a], f: {x: 'a} -> 'b} -> ['b]
        // Note: Map allows transformation from 'a to 'b (different element types)
        run {
            val inputElemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val inputArrayType = MezzeType.ArrayType(inputElemType)
            val outputElemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val outputArrayType = MezzeType.ArrayType(outputElemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to inputElemType)),
                outputElemType
            )
            context["__native_arraylist_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to inputArrayType,
                    "f" to funcType
                )),
                outputArrayType
            )
        }

        // __native_arraylist_filter: {xs: ['a], p: {x: 'a} -> Bool} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BooleanType
            )
            context["__native_arraylist_filter"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to arrayType,
                    "p" to predicateType
                )),
                arrayType
            )
        }

        // __native_arraylist_fold: {xs: ['a], init: 'b, f: {acc: 'b, x: 'a} -> 'b} -> 'b
        // Note: Fold has separate accumulator type 'b and element type 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            val accType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "acc" to accType,
                    "x" to elemType
                )),
                accType
            )
            context["__native_arraylist_fold"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
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
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "acc" to elemType,
                    "x" to elemType
                )),
                elemType
            )
            context["__native_arraylist_reduce"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to arrayType,
                    "f" to funcType
                )),
                elemType
            )
        }

        // __native_arraylist_find: {xs: ['a], p: {x: 'a} -> Bool} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BooleanType
            )
            context["__native_arraylist_find"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to arrayType,
                    "p" to predicateType
                )),
                optionType(elemType)
            )
        }

        // __native_arraylist_contains: {xs: ['a], x: 'a} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_contains"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to arrayType,
                    "x" to elemType
                )),
                MezzeType.BooleanType
            )
        }

        // __native_arraylist_index_of: {xs: ['a], x: 'a} -> Option Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_index_of"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to arrayType,
                    "x" to elemType
                )),
                optionType(MezzeType.IntType)
            )
        }

        // __native_arraylist_reverse: {xs: ['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_reverse"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayType)),
                arrayType
            )
        }

        // __native_arraylist_sort: {xs: ['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_sort"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayType)),
                arrayType
            )
        }

        // __native_arraylist_sort_by: {xs: ['a], cmp: {a: 'a, b: 'a} -> Int} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            val cmpFuncType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "a" to elemType,
                    "b" to elemType
                )),
                MezzeType.IntType
            )
            context["__native_arraylist_sort_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to arrayType,
                    "cmp" to cmpFuncType
                )),
                arrayType
            )
        }

        // __native_arraylist_slice: {xs: ['a], start: Int, end: Int} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_slice"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to arrayType,
                    "start" to MezzeType.IntType,
                    "end" to MezzeType.IntType
                )),
                arrayType
            )
        }

        // __native_arraylist_equals: {l: ['a], r: ['a]} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_equals"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "l" to arrayType,
                    "r" to arrayType
                )),
                MezzeType.BooleanType
            )
        }

        // === New Utility Functions ===

        // __native_arraylist_zip: {xs: ['a], ys: ['b]} -> [{fst: 'a, snd: 'b}]
        run {
            val elemTypeA = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val elemTypeB = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val arrayTypeA = MezzeType.ArrayType(elemTypeA)
            val arrayTypeB = MezzeType.ArrayType(elemTypeB)
            val pairType = MezzeType.RecordType(mapOf("fst" to elemTypeA, "snd" to elemTypeB))
            val resultType = MezzeType.ArrayType(pairType)
            context["__native_arraylist_zip"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayTypeA, "ys" to arrayTypeB)),
                resultType
            )
        }

        // __native_arraylist_unzip: {xs: [{fst: 'a, snd: 'b}]} -> {fsts: ['a], snds: ['b]}
        run {
            val elemTypeA = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val elemTypeB = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val pairType = MezzeType.RecordType(mapOf("fst" to elemTypeA, "snd" to elemTypeB))
            val inputType = MezzeType.ArrayType(pairType)
            val resultType = MezzeType.RecordType(mapOf(
                "fsts" to MezzeType.ArrayType(elemTypeA),
                "snds" to MezzeType.ArrayType(elemTypeB)
            ))
            context["__native_arraylist_unzip"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to inputType)),
                resultType
            )
        }

        // __native_arraylist_flatten: {xss: [['a]]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val innerArrayType = MezzeType.ArrayType(elemType)
            val outerArrayType = MezzeType.ArrayType(innerArrayType)
            context["__native_arraylist_flatten"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xss" to outerArrayType)),
                innerArrayType
            )
        }

        // __native_arraylist_any: {xs: ['a], p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BooleanType
            )
            context["__native_arraylist_any"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayType, "p" to predicateType)),
                MezzeType.BooleanType
            )
        }

        // __native_arraylist_all: {xs: ['a], p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BooleanType
            )
            context["__native_arraylist_all"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayType, "p" to predicateType)),
                MezzeType.BooleanType
            )
        }

        // __native_arraylist_partition: {xs: ['a], p: {x: 'a} -> Bool} -> {matching: ['a], not_matching: ['a]}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BooleanType
            )
            val resultType = MezzeType.RecordType(mapOf(
                "matching" to arrayType,
                "not_matching" to arrayType
            ))
            context["__native_arraylist_partition"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayType, "p" to predicateType)),
                resultType
            )
        }

        // __native_arraylist_find_index: {xs: ['a], p: {x: 'a} -> Bool} -> Option Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BooleanType
            )
            context["__native_arraylist_find_index"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayType, "p" to predicateType)),
                optionType(MezzeType.IntType)
            )
        }

        // __native_arraylist_last: {xs: ['a]} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_last"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayType)),
                optionType(elemType)
            )
        }

        // __native_arraylist_init: {xs: ['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_init"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayType)),
                arrayType
            )
        }

        // __native_arraylist_unique: {xs: ['a]} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.ArrayType(elemType)
            context["__native_arraylist_unique"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayType)),
                arrayType
            )
        }
    }
}
