package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return com.mezze.language.typechecker.util.WellKnownTypeApp.of("Option", listOf(elementType))
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
                MezzeType.BoolType
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

        // __native_list_size: {xs: List 'a} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_size"] = MezzeType.FunctionType(
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
                MezzeType.BoolType
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

        // __native_list_reduce: {xs: List 'a, f: {acc: 'a, x: 'a} -> 'a} -> Option 'a
        // Returns None for empty list, Some for non-empty (uses first element as initial acc)
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
            val optionType = MezzeType.VariantType(
                constructors = mapOf(
                    "Some" to MezzeType.RecordType(mapOf("val" to elemType)),
                    "None" to null
                ),
                rowVar = null
            )
            context["__native_list_reduce"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "f" to funcType
                )),
                optionType
            )
        }

        // __native_list_any: {xs: List 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_list_any"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "p" to predicateType
                )),
                MezzeType.BoolType
            )
        }

        // __native_list_all: {xs: List 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_list_all"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "p" to predicateType
                )),
                MezzeType.BoolType
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
                MezzeType.BoolType
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

        // __native_list_unzip: {xs: List {fst: 'a, snd: 'b}} -> {fsts: List 'a, snds: List 'b}
        run {
            val elemTypeA = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val elemTypeB = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val listTypeA = MezzeType.ListType(elemTypeA)
            val listTypeB = MezzeType.ListType(elemTypeB)
            val pairType = MezzeType.RecordType(mapOf(
                "fst" to elemTypeA,
                "snd" to elemTypeB
            ))
            context["__native_list_unzip"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to MezzeType.ListType(pairType))),
                MezzeType.RecordType(mapOf(
                    "fsts" to listTypeA,
                    "snds" to listTypeB
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
                MezzeType.BoolType
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
                MezzeType.BoolType
            )
            context["__native_list_find_index"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "p" to predicateType)),
                optionType(MezzeType.IntType)
            )
        }

        // __native_list_partition: {xs: List 'a, p: {x: 'a} -> Bool} -> {pass: List 'a, fail: List 'a}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            val resultType = MezzeType.RecordType(mapOf(
                "pass" to listType,
                "fail" to listType
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

        // ==================== Min/Max/Sum/Product ====================

        // __native_list_min: {xs: List 'a} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_min"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                optionType(elemType)
            )
        }

        // __native_list_max: {xs: List 'a} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_max"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                optionType(elemType)
            )
        }

        // __native_list_min_by: {xs: List 'a, f: {x: 'a} -> 'b} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val listType = MezzeType.ListType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                keyType
            )
            context["__native_list_min_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "f" to funcType)),
                optionType(elemType)
            )
        }

        // __native_list_max_by: {xs: List 'a, f: {x: 'a} -> 'b} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val listType = MezzeType.ListType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                keyType
            )
            context["__native_list_max_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "f" to funcType)),
                optionType(elemType)
            )
        }

        // __native_list_sum: {xs: List Int} -> Int
        run {
            val intListType = MezzeType.ListType(MezzeType.IntType)
            context["__native_list_sum"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to intListType)),
                MezzeType.IntType
            )
        }

        // __native_list_sum_float: {xs: List Float} -> Float
        run {
            val floatListType = MezzeType.ListType(MezzeType.FloatType)
            context["__native_list_sum_float"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to floatListType)),
                MezzeType.FloatType
            )
        }

        // __native_list_product: {xs: List Int} -> Int
        run {
            val intListType = MezzeType.ListType(MezzeType.IntType)
            context["__native_list_product"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to intListType)),
                MezzeType.IntType
            )
        }

        // __native_list_product_float: {xs: List Float} -> Float
        run {
            val floatListType = MezzeType.ListType(MezzeType.FloatType)
            context["__native_list_product_float"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to floatListType)),
                MezzeType.FloatType
            )
        }

        // ==================== Group By ====================

        // __native_list_group_by: {xs: List 'a, f: {x: 'a} -> 'b} -> List {key: 'b, values: List 'a}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val listType = MezzeType.ListType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                keyType
            )
            val groupType = MezzeType.RecordType(mapOf("key" to keyType, "values" to listType))
            context["__native_list_group_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "f" to funcType)),
                MezzeType.ListType(groupType)
            )
        }

        // ==================== Enumerate/ForEach/Scan ====================

        // __native_list_enumerate: {xs: List 'a} -> List {value: 'a, index: Int}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val pairType = MezzeType.RecordType(mapOf("value" to elemType, "index" to MezzeType.IntType))
            context["__native_list_enumerate"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                MezzeType.ListType(pairType)
            )
        }

        // __native_list_zip_with_index: {xs: List 'a} -> List {value: 'a, index: Int}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val pairType = MezzeType.RecordType(mapOf("value" to elemType, "index" to MezzeType.IntType))
            context["__native_list_zip_with_index"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                MezzeType.ListType(pairType)
            )
        }

        // __native_list_for_each: {xs: List 'a, f: {x: 'a} -> 'b} -> {}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val retType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val listType = MezzeType.ListType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                retType
            )
            context["__native_list_for_each"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "f" to funcType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_list_scan: {xs: List 'a, init: 'b, f: {acc: 'b, x: 'a} -> 'b} -> List 'b
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val accType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val listType = MezzeType.ListType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("acc" to accType, "x" to elemType)),
                accType
            )
            context["__native_list_scan"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "init" to accType, "f" to funcType)),
                MezzeType.ListType(accType)
            )
        }

        // ==================== Split Operations ====================

        // __native_list_split_at: {xs: List 'a, i: Int} -> {l: List 'a, r: List 'a}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val resultType = MezzeType.RecordType(mapOf("l" to listType, "r" to listType))
            context["__native_list_split_at"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "i" to MezzeType.IntType)),
                resultType
            )
        }

        // __native_list_span: {xs: List 'a, p: {x: 'a} -> Bool} -> {l: List 'a, r: List 'a}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            val resultType = MezzeType.RecordType(mapOf("l" to listType, "r" to listType))
            context["__native_list_span"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "p" to predicateType)),
                resultType
            )
        }

        // ==================== Dedup ====================

        // __native_list_dedup: {xs: List 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_dedup"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                listType
            )
        }

        // __native_list_dedup_by: {xs: List 'a, f: {x: 'a} -> 'b} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val listType = MezzeType.ListType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                keyType
            )
            context["__native_list_dedup_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "f" to funcType)),
                listType
            )
        }

        // ==================== Singleton/Nth/Fill ====================

        // __native_list_singleton: {x: 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_singleton"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                listType
            )
        }

        // __native_list_nth: {xs: List 'a, i: Int} -> Option 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_nth"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "i" to MezzeType.IntType)),
                optionType(elemType)
            )
        }

        // __native_list_fill: {n: Int, x: 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_fill"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to MezzeType.IntType, "x" to elemType)),
                listType
            )
        }

        // __native_list_fill_with: {n: Int, f: {i: Int} -> 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("i" to MezzeType.IntType)),
                elemType
            )
            context["__native_list_fill_with"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to MezzeType.IntType, "f" to funcType)),
                listType
            )
        }

        // ==================== Group Consecutive ====================

        // __native_list_group_consecutive: {xs: List 'a} -> List (List 'a)
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_group_consecutive"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                MezzeType.ListType(listType)
            )
        }

        // __native_list_group_consecutive_by: {xs: List 'a, f: {x: 'a} -> 'b} -> List (List 'a)
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val listType = MezzeType.ListType(elemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                keyType
            )
            context["__native_list_group_consecutive_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "f" to funcType)),
                MezzeType.ListType(listType)
            )
        }

        // ==================== FlatMap/TakeWhile/DropWhile ====================

        // __native_list_flat_map: {xs: List 'a, f: {x: 'a} -> List 'b} -> List 'b
        run {
            val inputElemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val inputListType = MezzeType.ListType(inputElemType)
            val outputElemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val outputListType = MezzeType.ListType(outputElemType)
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to inputElemType)),
                outputListType
            )
            context["__native_list_flat_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to inputListType, "f" to funcType)),
                outputListType
            )
        }

        // __native_list_take_while: {xs: List 'a, p: {x: 'a} -> Bool} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_list_take_while"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "p" to predicateType)),
                listType
            )
        }

        // __native_list_drop_while: {xs: List 'a, p: {x: 'a} -> Bool} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_list_drop_while"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "p" to predicateType)),
                listType
            )
        }

        // ==================== None/Count ====================

        // __native_list_none: {xs: List 'a, p: {x: 'a} -> Bool} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_list_none"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "p" to predicateType)),
                MezzeType.BoolType
            )
        }

        // __native_list_count: {xs: List 'a, p: {x: 'a} -> Bool} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val predicateType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to elemType)),
                MezzeType.BoolType
            )
            context["__native_list_count"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "p" to predicateType)),
                MezzeType.IntType
            )
        }

        // ==================== Unique/Distinct/Flatten ====================

        // __native_list_unique: {xs: List 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_unique"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                listType
            )
        }

        // __native_list_distinct: {xs: List 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_distinct"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                listType
            )
        }

        // __native_list_flatten: {xss: List (List 'a)} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val innerListType = MezzeType.ListType(elemType)
            context["__native_list_flatten"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xss" to MezzeType.ListType(innerListType))),
                innerListType
            )
        }

        // ==================== Task #17: Additional Operations ====================

        // __native_list_equals: {xs: List 'a, ys: List 'a} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_equals"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "ys" to listType)),
                MezzeType.BoolType
            )
        }

        // __native_list_compare: {xs: List 'a, ys: List 'a} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_compare"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "ys" to listType)),
                MezzeType.IntType
            )
        }

        // __native_list_index_of: {xs: List 'a, x: 'a} -> Option Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_index_of"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "x" to elemType)),
                optionType(MezzeType.IntType)
            )
        }

        // __native_list_last_index_of: {xs: List 'a, x: 'a} -> Option Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_last_index_of"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "x" to elemType)),
                optionType(MezzeType.IntType)
            )
        }

        // __native_list_slice: {xs: List 'a, start: Int, end: Int} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_slice"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "start" to MezzeType.IntType, "end" to MezzeType.IntType)),
                listType
            )
        }

        // ==================== Task #18: Windowing Operations ====================

        // __native_list_chunk: {xs: List 'a, size: Int} -> List (List 'a)
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_chunk"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "size" to MezzeType.IntType)),
                MezzeType.ListType(listType)
            )
        }

        // __native_list_windows: {xs: List 'a, size: Int} -> List (List 'a)
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_windows"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "size" to MezzeType.IntType)),
                MezzeType.ListType(listType)
            )
        }

        // __native_list_sliding: {xs: List 'a, size: Int, step: Int} -> List (List 'a)
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_sliding"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "size" to MezzeType.IntType, "step" to MezzeType.IntType)),
                MezzeType.ListType(listType)
            )
        }

        // __native_list_chunks_exact: {xs: List 'a, size: Int} -> List (List 'a)
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_chunks_exact"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "size" to MezzeType.IntType)),
                MezzeType.ListType(listType)
            )
        }

        // ==================== Task #19: Transformation Operations ====================

        // __native_list_sort_by_key: {xs: List 'a, f: {x: 'a} -> 'b} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_sort_by_key"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("x" to elemType)),
                        keyType
                    )
                )),
                listType
            )
        }

        // __native_list_rotate_left: {xs: List 'a, n: Int} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_rotate_left"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "n" to MezzeType.IntType)),
                listType
            )
        }

        // __native_list_rotate_right: {xs: List 'a, n: Int} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_rotate_right"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "n" to MezzeType.IntType)),
                listType
            )
        }
    }

    private fun registerConversionFunctions(context: MutableMap<String, MezzeType>) {
        // __native_list_to_seq: {xs: List 'a} -> Seq 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_to_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                MezzeType.SeqType(elemType)
            )
        }

        // __native_seq_to_list: {xs: Seq 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            context["__native_seq_to_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to MezzeType.SeqType(elemType))),
                MezzeType.ListType(elemType)
            )
        }

        // __native_list_from_seq: {xs: Seq 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            context["__native_list_from_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to MezzeType.SeqType(elemType))),
                MezzeType.ListType(elemType)
            )
        }

        // ==================== Task #27: Cross-Collection Conversions ====================

        // __native_list_to_seq: {xs: List 'a} -> ['a]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val arrayType = MezzeType.SeqType(elemType)
            context["__native_list_to_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                arrayType
            )
        }

        // __native_list_to_set: {xs: List 'a} -> Set 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val setType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Set", listOf(elemType))
            context["__native_list_to_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                setType
            )
        }

        // __native_list_to_deque: {xs: List 'a} -> Deque 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val dequeType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Deque", listOf(elemType))
            context["__native_list_to_deque"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                dequeType
            )
        }

        // __native_list_to_sorted_set: {xs: List 'a} -> SortedSet 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val sortedSetType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("SortedSet", listOf(elemType))
            context["__native_list_to_sorted_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                sortedSetType
            )
        }

        // __native_list_to_map: {xs: List {k: 'k, v: 'v}} -> Map 'k 'v
        run {
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("k"))
            val valType = MezzeType.VarType(com.mezze.language.ast.TypeVar("v"))
            val recordType = MezzeType.RecordType(mapOf("k" to keyType, "v" to valType))
            val listType = MezzeType.ListType(recordType)
            val mapType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Map", listOf(keyType, valType))
            context["__native_list_to_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                mapType
            )
        }

        // __native_list_to_sorted_map: {xs: List {k: 'k, v: 'v}} -> SortedMap 'k 'v
        run {
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("k"))
            val valType = MezzeType.VarType(com.mezze.language.ast.TypeVar("v"))
            val recordType = MezzeType.RecordType(mapOf("k" to keyType, "v" to valType))
            val listType = MezzeType.ListType(recordType)
            val sortedMapType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("SortedMap", listOf(keyType, valType))
            context["__native_list_to_sorted_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                sortedMapType
            )
        }

        // __native_list_from_seq: {xs: ['a]} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val arrayType = MezzeType.SeqType(elemType)
            val listType = MezzeType.ListType(elemType)
            context["__native_list_from_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to arrayType)),
                listType
            )
        }

        // __native_list_from_set: {xs: Set 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val setType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Set", listOf(elemType))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_from_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to setType)),
                listType
            )
        }

        // __native_list_from_deque: {xs: Deque 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val dequeType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Deque", listOf(elemType))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_from_deque"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to dequeType)),
                listType
            )
        }

        // __native_list_from_sorted_set: {xs: SortedSet 'a} -> List 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val sortedSetType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("SortedSet", listOf(elemType))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_from_sorted_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to sortedSetType)),
                listType
            )
        }

        // __native_list_from_map: {m: Map 'k 'v} -> List {k: 'k, v: 'v}
        run {
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("k"))
            val valType = MezzeType.VarType(com.mezze.language.ast.TypeVar("v"))
            val mapType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Map", listOf(keyType, valType))
            val recordType = MezzeType.RecordType(mapOf("k" to keyType, "v" to valType))
            val listType = MezzeType.ListType(recordType)
            context["__native_list_from_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to mapType)),
                listType
            )
        }

        // __native_list_from_sorted_map: {m: SortedMap 'k 'v} -> List {k: 'k, v: 'v}
        run {
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("k"))
            val valType = MezzeType.VarType(com.mezze.language.ast.TypeVar("v"))
            val sortedMapType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("SortedMap", listOf(keyType, valType))
            val recordType = MezzeType.RecordType(mapOf("k" to keyType, "v" to valType))
            val listType = MezzeType.ListType(recordType)
            context["__native_list_from_sorted_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("m" to sortedMapType)),
                listType
            )
        }

        // ==================== Task #32: Numeric Operations ====================

        // __native_list_average: {xs: List Float} -> Option Float
        run {
            val listType = MezzeType.ListType(MezzeType.FloatType)
            context["__native_list_average"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                optionType(MezzeType.FloatType)
            )
        }

        // __native_list_variance: {xs: List Float} -> Option Float
        run {
            val listType = MezzeType.ListType(MezzeType.FloatType)
            context["__native_list_variance"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                optionType(MezzeType.FloatType)
            )
        }

        // __native_list_stddev: {xs: List Float} -> Option Float
        run {
            val listType = MezzeType.ListType(MezzeType.FloatType)
            context["__native_list_stddev"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                optionType(MezzeType.FloatType)
            )
        }

        // __native_list_median: {xs: List Float} -> Option Float
        run {
            val listType = MezzeType.ListType(MezzeType.FloatType)
            context["__native_list_median"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                optionType(MezzeType.FloatType)
            )
        }

        // __native_list_sum_float: {xs: List Float} -> Float
        run {
            val listType = MezzeType.ListType(MezzeType.FloatType)
            context["__native_list_sum_float"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                MezzeType.FloatType
            )
        }

        // __native_list_product_float: {xs: List Float} -> Float
        run {
            val listType = MezzeType.ListType(MezzeType.FloatType)
            context["__native_list_product_float"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                MezzeType.FloatType
            )
        }

        // ==================== Task #36: Comparison Operations ====================

        // __native_list_compare: {xs: List 'a, ys: List 'a} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_compare"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "ys" to listType)),
                MezzeType.IntType
            )
        }

        // __native_list_lexicographic_compare: {xs: List 'a, ys: List 'a, cmp: {a: 'a, b: 'a} -> Int} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val cmpType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("a" to elemType, "b" to elemType)),
                MezzeType.IntType
            )
            context["__native_list_lexicographic_compare"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "ys" to listType, "cmp" to cmpType)),
                MezzeType.IntType
            )
        }

        // __native_list_is_sorted: {xs: List 'a} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_is_sorted"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                MezzeType.BoolType
            )
        }

        // __native_list_is_sorted_by: {xs: List 'a, cmp: {a: 'a, b: 'a} -> Int} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val cmpType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("a" to elemType, "b" to elemType)),
                MezzeType.IntType
            )
            context["__native_list_is_sorted_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType, "cmp" to cmpType)),
                MezzeType.BoolType
            )
        }

        // __native_list_is_sorted_descending: {xs: List 'a} -> Bool
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_is_sorted_descending"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("xs" to listType)),
                MezzeType.BoolType
            )
        }

        // Task #33: Binary search operations

        // __native_list_binary_search: {xs: List 'a, target: 'a} -> Option Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_binary_search"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "target" to elemType
                )),
                optionType(MezzeType.IntType)
            )
        }

        // __native_list_binary_search_insert_point: {xs: List 'a, target: 'a} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_binary_search_insert_point"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "target" to elemType
                )),
                MezzeType.IntType
            )
        }

        // __native_list_lower_bound: {xs: List 'a, target: 'a} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_lower_bound"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "target" to elemType
                )),
                MezzeType.IntType
            )
        }

        // __native_list_upper_bound: {xs: List 'a, target: 'a} -> Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_upper_bound"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "target" to elemType
                )),
                MezzeType.IntType
            )
        }

        // __native_list_equal_range: {xs: List 'a, target: 'a} -> {lower: Int, upper: Int}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            context["__native_list_equal_range"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "target" to elemType
                )),
                MezzeType.RecordType(mapOf(
                    "lower" to MezzeType.IntType,
                    "upper" to MezzeType.IntType
                ))
            )
        }

        // __native_list_binary_search_by: {xs: List 'a, target: 'a, cmp: {a: 'a, b: 'a} -> Int} -> Option Int
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val listType = MezzeType.ListType(elemType)
            val cmpType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("a" to elemType, "b" to elemType)),
                MezzeType.IntType
            )
            context["__native_list_binary_search_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "xs" to listType,
                    "target" to elemType,
                    "cmp" to cmpType
                )),
                optionType(MezzeType.IntType)
            )
        }
    }
}
