package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for Map (key-value dictionary) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/map.mz
 */
object MapTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerMapNatives(context)
    }

    private fun registerMapNatives(context: MutableMap<String, MezzeType>) {
        val mapKeyTypeVar = com.mezze.language.ast.TypeVar("k")
        val mapValTypeVar = com.mezze.language.ast.TypeVar("v")
        val mapValTypeVarW = com.mezze.language.ast.TypeVar("w")
        val mapAccTypeVar = com.mezze.language.ast.TypeVar("acc")

        val mapType = MezzeType.MapType(MezzeType.VarType(mapKeyTypeVar), MezzeType.VarType(mapValTypeVar))
        val mapTypeKW = MezzeType.MapType(MezzeType.VarType(mapKeyTypeVar), MezzeType.VarType(mapValTypeVarW))

        context["__native_map_empty"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            mapType
        )

        context["__native_map_insert"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "k" to MezzeType.VarType(mapKeyTypeVar),
                "v" to MezzeType.VarType(mapValTypeVar)
            )),
            mapType
        )

        context["__native_map_get"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "k" to MezzeType.VarType(mapKeyTypeVar)
            )),
            optionType(MezzeType.VarType(mapValTypeVar))
        )

        context["__native_map_remove"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "k" to MezzeType.VarType(mapKeyTypeVar)
            )),
            mapType
        )

        context["__native_map_contains_key"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "k" to MezzeType.VarType(mapKeyTypeVar)
            )),
            MezzeType.BooleanType
        )

        context["__native_map_size"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.IntType
        )

        context["__native_map_keys"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.ListType(MezzeType.VarType(mapKeyTypeVar))
        )

        context["__native_map_values"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.ListType(MezzeType.VarType(mapValTypeVar))
        )

        context["__native_map_entries"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("m" to mapType)),
            MezzeType.ArrayType(MezzeType.RecordType(mapOf(
                "key" to MezzeType.VarType(mapKeyTypeVar),
                "value" to MezzeType.VarType(mapValTypeVar)
            )))
        )

        context["__native_map_map_values"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(mapValTypeVar))),
                    MezzeType.VarType(mapValTypeVarW)
                )
            )),
            mapTypeKW
        )

        context["__native_map_filter"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "p" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "k" to MezzeType.VarType(mapKeyTypeVar),
                        "v" to MezzeType.VarType(mapValTypeVar)
                    )),
                    MezzeType.BooleanType
                )
            )),
            mapType
        )

        context["__native_map_fold"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "init" to MezzeType.VarType(mapAccTypeVar),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "acc" to MezzeType.VarType(mapAccTypeVar),
                        "k" to MezzeType.VarType(mapKeyTypeVar),
                        "v" to MezzeType.VarType(mapValTypeVar)
                    )),
                    MezzeType.VarType(mapAccTypeVar)
                )
            )),
            MezzeType.VarType(mapAccTypeVar)
        )

        context["__native_map_from_keys"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ks" to MezzeType.ListType(MezzeType.VarType(mapKeyTypeVar)),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(mapKeyTypeVar))),
                    MezzeType.VarType(mapValTypeVar)
                )
            )),
            mapType
        )

        context["__native_map_merge"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m1" to mapType,
                "m2" to mapType
            )),
            mapType
        )

        context["__native_map_merge_with"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m1" to mapType,
                "m2" to mapType,
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "l" to MezzeType.VarType(mapValTypeVar),
                        "r" to MezzeType.VarType(mapValTypeVar)
                    )),
                    MezzeType.VarType(mapValTypeVar)
                )
            )),
            mapType
        )

        context["__native_map_remove_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m" to mapType,
                "ks" to MezzeType.ListType(MezzeType.VarType(mapKeyTypeVar))
            )),
            mapType
        )

        // __native_map_equals: {m1: Map 'k 'v, m2: Map 'k 'v} -> Bool
        context["__native_map_equals"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "m1" to mapType,
                "m2" to mapType
            )),
            MezzeType.BooleanType
        )
    }
}
