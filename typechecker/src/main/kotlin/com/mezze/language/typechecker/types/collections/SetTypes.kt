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
            MezzeType.BooleanType
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
            MezzeType.BooleanType
        )

        context["__native_set_to_array"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to setType)),
            MezzeType.ArrayType(MezzeType.VarType(setElemTypeVar))
        )

        context["__native_set_from_array"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("xs" to MezzeType.ArrayType(MezzeType.VarType(setElemTypeVar)))),
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
                    MezzeType.BooleanType
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
                "xs" to MezzeType.ArrayType(MezzeType.VarType(setElemTypeVar))
            )),
            setType
        )

        context["__native_set_remove_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s" to setType,
                "xs" to MezzeType.ArrayType(MezzeType.VarType(setElemTypeVar))
            )),
            setType
        )

        // __native_set_equals: {s1: Set 'a, s2: Set 'a} -> Bool
        context["__native_set_equals"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            MezzeType.BooleanType
        )
    }
}
