package com.funk.language.typechecker.types.collections

import com.funk.language.ast.FunkType

/**
 * Type signatures for Set (unique collection) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/set.funk
 */
object SetTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerSetNatives(context)
    }

    private fun registerSetNatives(context: MutableMap<String, FunkType>) {
        val setElemTypeVar = com.funk.language.ast.TypeVar("a")
        val setElemTypeVarB = com.funk.language.ast.TypeVar("b")
        val setAccTypeVar = com.funk.language.ast.TypeVar("acc")

        val setType = FunkType.SetType(FunkType.VarType(setElemTypeVar))
        val setTypeB = FunkType.SetType(FunkType.VarType(setElemTypeVarB))

        context["__native_set_empty"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            setType
        )

        context["__native_set_insert"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "x" to FunkType.VarType(setElemTypeVar)
            )),
            setType
        )

        context["__native_set_remove"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "x" to FunkType.VarType(setElemTypeVar)
            )),
            setType
        )

        context["__native_set_contains"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "x" to FunkType.VarType(setElemTypeVar)
            )),
            FunkType.BooleanType
        )

        context["__native_set_size"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to setType)),
            FunkType.IntType
        )

        context["__native_set_union"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            setType
        )

        context["__native_set_intersection"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            setType
        )

        context["__native_set_difference"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            setType
        )

        context["__native_set_is_subset"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            FunkType.BooleanType
        )

        context["__native_set_to_array"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to setType)),
            FunkType.ArrayType(FunkType.VarType(setElemTypeVar))
        )

        context["__native_set_from_array"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("xs" to FunkType.ArrayType(FunkType.VarType(setElemTypeVar)))),
            setType
        )

        context["__native_set_filter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "p" to FunkType.FunctionType(
                    FunkType.VarType(setElemTypeVar),
                    FunkType.BooleanType
                )
            )),
            setType
        )

        context["__native_set_map"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "f" to FunkType.FunctionType(
                    FunkType.VarType(setElemTypeVar),
                    FunkType.VarType(setElemTypeVarB)
                )
            )),
            setTypeB
        )

        context["__native_set_fold"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "init" to FunkType.VarType(setAccTypeVar),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "acc" to FunkType.VarType(setAccTypeVar),
                        "x" to FunkType.VarType(setElemTypeVar)
                    )),
                    FunkType.VarType(setAccTypeVar)
                )
            )),
            FunkType.VarType(setAccTypeVar)
        )

        context["__native_set_insert_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "xs" to FunkType.ArrayType(FunkType.VarType(setElemTypeVar))
            )),
            setType
        )

        context["__native_set_remove_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s" to setType,
                "xs" to FunkType.ArrayType(FunkType.VarType(setElemTypeVar))
            )),
            setType
        )

        // __native_set_equals: {s1: Set 'a, s2: Set 'a} -> Bool
        context["__native_set_equals"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "s1" to setType,
                "s2" to setType
            )),
            FunkType.BooleanType
        )
    }
}
