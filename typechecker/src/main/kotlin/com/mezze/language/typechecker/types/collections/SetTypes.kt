package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType

/**
 * Type signatures for Set (unique collection) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/set.mz
 */
object SetTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerSetNatives(context)
    }

    private fun registerSetNatives(context: MutableMap<String, MezzeType>) {
        val setElemTypeVar = com.mezze.language.ast.TypeVar("a")
        val setElemTypeVarB = com.mezze.language.ast.TypeVar("b")
        val setAccTypeVar = com.mezze.language.ast.TypeVar("acc")

        val setType = MezzeType.SetType(MezzeType.VarType(setElemTypeVar))
        val setTypeB = MezzeType.SetType(MezzeType.VarType(setElemTypeVarB))

        context["__native_set_empty"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            setType
        )

        context["__native_set_insert"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "x" to MezzeType.VarType(setElemTypeVar)
            )),
            setType
        )

        context["__native_set_remove"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "x" to MezzeType.VarType(setElemTypeVar)
            )),
            setType
        )

        context["__native_set_contains"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "x" to MezzeType.VarType(setElemTypeVar)
            )),
            MezzeType.BoolType
        )

        context["__native_set_size"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to setType)),
            MezzeType.IntType
        )

        context["__native_set_union"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            setType
        )

        context["__native_set_intersection"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            setType
        )

        context["__native_set_difference"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            setType
        )

        context["__native_set_is_subset"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            MezzeType.BoolType
        )

        context["__native_set_to_seq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to setType)),
            MezzeType.SeqType(MezzeType.VarType(setElemTypeVar))
        )

        context["__native_set_from_seq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("xs" to MezzeType.SeqType(MezzeType.VarType(setElemTypeVar)))),
            setType
        )

        context["__native_set_to_list"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to setType)),
            MezzeType.ListType(MezzeType.VarType(setElemTypeVar))
        )

        context["__native_set_from_list"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("xs" to MezzeType.ListType(MezzeType.VarType(setElemTypeVar)))),
            setType
        )

        context["__native_set_filter"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "p" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(setElemTypeVar))),
                    MezzeType.BoolType
                )
            )),
            setType
        )

        context["__native_set_map"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(setElemTypeVar))),
                    MezzeType.VarType(setElemTypeVarB)
                )
            )),
            setTypeB
        )

        context["__native_set_fold"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "init" to MezzeType.VarType(setAccTypeVar),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "acc" to MezzeType.VarType(setAccTypeVar),
                        "x" to MezzeType.VarType(setElemTypeVar)
                    )),
                    MezzeType.VarType(setAccTypeVar)
                )
            )),
            MezzeType.VarType(setAccTypeVar)
        )

        context["__native_set_insert_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "xs" to MezzeType.SeqType(MezzeType.VarType(setElemTypeVar))
            )),
            setType
        )

        context["__native_set_remove_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "xs" to MezzeType.SeqType(MezzeType.VarType(setElemTypeVar))
            )),
            setType
        )

        // __native_set_equals: {s1: Set 'a, s2: Set 'a} -> Bool
        context["__native_set_equals"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            MezzeType.BoolType
        )

        // ==================== Predicate Operations ====================

        // __native_set_any: {s: Set 'a, p: {val: 'a} -> Bool} -> Bool
        context["__native_set_any"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "p" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(setElemTypeVar))),
                    MezzeType.BoolType
                )
            )),
            MezzeType.BoolType
        )

        // __native_set_all: {s: Set 'a, p: {val: 'a} -> Bool} -> Bool
        context["__native_set_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "p" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(setElemTypeVar))),
                    MezzeType.BoolType
                )
            )),
            MezzeType.BoolType
        )

        // __native_set_none: {s: Set 'a, p: {val: 'a} -> Bool} -> Bool
        context["__native_set_none"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "p" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(setElemTypeVar))),
                    MezzeType.BoolType
                )
            )),
            MezzeType.BoolType
        )

        // __native_set_find: {s: Set 'a, p: {val: 'a} -> Bool} -> Option 'a
        context["__native_set_find"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "p" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(setElemTypeVar))),
                    MezzeType.BoolType
                )
            )),
            MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(setElemTypeVar)))
        )

        // __native_set_count: {s: Set 'a, p: {val: 'a} -> Bool} -> Int
        context["__native_set_count"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "p" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(setElemTypeVar))),
                    MezzeType.BoolType
                )
            )),
            MezzeType.IntType
        )

        // ==================== Min/Max/Sum/Product ====================

        // __native_set_min: {s: Set 'a} -> Option 'a
        context["__native_set_min"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to setType)),
            MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(setElemTypeVar)))
        )

        // __native_set_max: {s: Set 'a} -> Option 'a
        context["__native_set_max"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to setType)),
            MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(setElemTypeVar)))
        )

        // __native_set_sum: {s: Set Int} -> Int
        context["__native_set_sum"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.SetType(MezzeType.IntType))),
            MezzeType.IntType
        )

        // __native_set_product: {s: Set Int} -> Int
        context["__native_set_product"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.SetType(MezzeType.IntType))),
            MezzeType.IntType
        )

        // ==================== Conversion ====================

        // __native_set_to_seq: {s: Set 'a} -> ['a]
        context["__native_set_to_seq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to setType)),
            MezzeType.SeqType(MezzeType.VarType(setElemTypeVar))
        )

        // __native_set_singleton: {x: 'a} -> Set 'a
        context["__native_set_singleton"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("x" to MezzeType.VarType(setElemTypeVar))),
            setType
        )

        // ==================== Additional Operations ====================

        // __native_set_reduce: {s: Set 'a, f: {acc: 'a, x: 'a} -> 'a} -> Option 'a
        context["__native_set_reduce"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("acc" to MezzeType.VarType(setElemTypeVar), "x" to MezzeType.VarType(setElemTypeVar))),
                    MezzeType.VarType(setElemTypeVar)
                )
            )),
            MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(setElemTypeVar)))
        )

        // __native_set_min_by: {s: Set 'a, f: {val: 'a} -> 'b} -> Option 'a
        val setElemTypeVarB2 = com.mezze.language.ast.TypeVar("b")
        context["__native_set_min_by"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(setElemTypeVar))),
                    MezzeType.VarType(setElemTypeVarB2)
                )
            )),
            MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(setElemTypeVar)))
        )

        // __native_set_max_by: {s: Set 'a, f: {val: 'a} -> 'b} -> Option 'a
        context["__native_set_max_by"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(setElemTypeVar))),
                    MezzeType.VarType(setElemTypeVarB2)
                )
            )),
            MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(setElemTypeVar)))
        )

        // __native_set_partition: {s: Set 'a, p: {val: 'a} -> Bool} -> {pass: Set 'a, fail: Set 'a}
        context["__native_set_partition"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "p" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(setElemTypeVar))),
                    MezzeType.BoolType
                )
            )),
            MezzeType.RecordType(mapOf("pass" to setType, "fail" to setType))
        )

        // __native_set_sum_float: {s: Set Float} -> Float
        context["__native_set_sum_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.SetType(MezzeType.FloatType))),
            MezzeType.FloatType
        )

        // __native_set_product_float: {s: Set Float} -> Float
        context["__native_set_product_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.SetType(MezzeType.FloatType))),
            MezzeType.FloatType
        )

        // ==================== Task #20: Additional Operations ====================

        // __native_set_flat_map: {s: Set 'a, f: {val: 'a} -> Set 'b} -> Set 'b
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val resultType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            context["__native_set_flat_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SetType(elemType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        MezzeType.SetType(resultType)
                    )
                )),
                MezzeType.SetType(resultType)
            )
        }

        // __native_set_group_by: {s: Set 'a, f: {val: 'a} -> 'b} -> List {key: 'b, values: Set 'a}
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val keyType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            context["__native_set_group_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s" to MezzeType.SetType(elemType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        keyType
                    )
                )),
                MezzeType.ListType(MezzeType.RecordType(mapOf(
                    "key" to keyType,
                    "values" to MezzeType.SetType(elemType)
                )))
            )
        }

        // __native_set_symmetric_difference: {s1: Set 'a, s2: Set 'a} -> Set 'a
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            context["__native_set_symmetric_difference"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "s1" to MezzeType.SetType(elemType),
                    "s2" to MezzeType.SetType(elemType)
                )),
                MezzeType.SetType(elemType)
            )
        }

        // ==================== Task #30: Collect Operations ====================

        // __native_set_filter_map: {set: Set 'a, f: {val: 'a} -> Option 'b} -> Set 'b
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val resultType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            context["__native_set_filter_map"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "set" to MezzeType.SetType(elemType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        MezzeType.TypeApplication("Option", listOf(resultType))
                    )
                )),
                MezzeType.SetType(resultType)
            )
        }

        // __native_set_collect_seq: {set: Set 'a, f: {val: 'a} -> Option 'b} -> ['b]
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val resultType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            context["__native_set_collect_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "set" to MezzeType.SetType(elemType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        MezzeType.TypeApplication("Option", listOf(resultType))
                    )
                )),
                MezzeType.SeqType(resultType)
            )
        }

        // __native_set_collect_list: {set: Set 'a, f: {val: 'a} -> Option 'b} -> List 'b
        run {
            val elemType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            val resultType = MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            context["__native_set_collect_list"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "set" to MezzeType.SetType(elemType),
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("val" to elemType)),
                        MezzeType.TypeApplication("Option", listOf(resultType))
                    )
                )),
                MezzeType.ListType(resultType)
            )
        }

        // ==================== SortedSet Conversions ====================

        // __native_set_to_sorted_set: {s: Set 'a} -> SortedSet 'a
        context["__native_set_to_sorted_set"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to setType)),
            MezzeType.SortedSetType(MezzeType.VarType(setElemTypeVar))
        )

        // __native_set_from_sorted_set: {s: SortedSet 'a} -> Set 'a
        context["__native_set_from_sorted_set"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.SortedSetType(MezzeType.VarType(setElemTypeVar)))),
            setType
        )
    }
}
