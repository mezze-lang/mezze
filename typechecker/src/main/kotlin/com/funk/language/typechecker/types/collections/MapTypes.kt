package com.funk.language.typechecker.types.collections

import com.funk.language.ast.FunkType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: FunkType): FunkType {
    return FunkType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for Map (key-value dictionary) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/map.funk
 */
object MapTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerMapNatives(context)
    }

    private fun registerMapNatives(context: MutableMap<String, FunkType>) {
        val mapKeyTypeVar = com.funk.language.ast.TypeVar("k")
        val mapValTypeVar = com.funk.language.ast.TypeVar("v")
        val mapValTypeVarW = com.funk.language.ast.TypeVar("w")
        val mapAccTypeVar = com.funk.language.ast.TypeVar("acc")

        val mapType = FunkType.MapType(FunkType.VarType(mapKeyTypeVar), FunkType.VarType(mapValTypeVar))
        val mapTypeKW = FunkType.MapType(FunkType.VarType(mapKeyTypeVar), FunkType.VarType(mapValTypeVarW))

        context["__native_map_empty"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            mapType
        )

        context["__native_map_insert"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "k" to FunkType.VarType(mapKeyTypeVar),
                "v" to FunkType.VarType(mapValTypeVar)
            )),
            mapType
        )

        context["__native_map_get"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "k" to FunkType.VarType(mapKeyTypeVar)
            )),
            optionType(FunkType.VarType(mapValTypeVar))
        )

        context["__native_map_remove"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "k" to FunkType.VarType(mapKeyTypeVar)
            )),
            mapType
        )

        context["__native_map_contains_key"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "k" to FunkType.VarType(mapKeyTypeVar)
            )),
            FunkType.BooleanType
        )

        context["__native_map_size"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("m" to mapType)),
            FunkType.IntType
        )

        context["__native_map_keys"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("m" to mapType)),
            FunkType.ListType(FunkType.VarType(mapKeyTypeVar))
        )

        context["__native_map_values"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("m" to mapType)),
            FunkType.ListType(FunkType.VarType(mapValTypeVar))
        )

        context["__native_map_entries"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("m" to mapType)),
            FunkType.ListType(FunkType.RecordType(mapOf(
                "key" to FunkType.VarType(mapKeyTypeVar),
                "value" to FunkType.VarType(mapValTypeVar)
            )))
        )

        context["__native_map_map_values"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "f" to FunkType.FunctionType(
                    FunkType.VarType(mapValTypeVar),
                    FunkType.VarType(mapValTypeVarW)
                )
            )),
            mapTypeKW
        )

        context["__native_map_filter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "p" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "key" to FunkType.VarType(mapKeyTypeVar),
                        "value" to FunkType.VarType(mapValTypeVar)
                    )),
                    FunkType.BooleanType
                )
            )),
            mapType
        )

        context["__native_map_fold"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "init" to FunkType.VarType(mapAccTypeVar),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "acc" to FunkType.VarType(mapAccTypeVar),
                        "entry" to FunkType.RecordType(mapOf(
                            "key" to FunkType.VarType(mapKeyTypeVar),
                            "value" to FunkType.VarType(mapValTypeVar)
                        ))
                    )),
                    FunkType.VarType(mapAccTypeVar)
                )
            )),
            FunkType.VarType(mapAccTypeVar)
        )

        context["__native_map_from_keys"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ks" to FunkType.ListType(FunkType.VarType(mapKeyTypeVar)),
                "f" to FunkType.FunctionType(
                    FunkType.VarType(mapKeyTypeVar),
                    FunkType.VarType(mapValTypeVar)
                )
            )),
            mapType
        )

        context["__native_map_merge"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m1" to mapType,
                "m2" to mapType
            )),
            mapType
        )

        context["__native_map_merge_with"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m1" to mapType,
                "m2" to mapType,
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "v1" to FunkType.VarType(mapValTypeVar),
                        "v2" to FunkType.VarType(mapValTypeVar)
                    )),
                    FunkType.VarType(mapValTypeVar)
                )
            )),
            mapType
        )

        context["__native_map_remove_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "m" to mapType,
                "ks" to FunkType.ListType(FunkType.VarType(mapKeyTypeVar))
            )),
            mapType
        )
    }
}
