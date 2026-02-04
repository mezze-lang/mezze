package com.funk.language.typechecker.abilities

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

/**
 * Built-in ability registry for NewTypeChecker.
 *
 * Abilities are type classes that define operations on types.
 * For example, NumAdd defines the `add` operation for numeric types.
 *
 * Built-in abilities are automatically available without imports:
 * - NumAdd, NumSub, NumMul, NumDiv, NumMod, NumNeg (arithmetic)
 * - Eq (equality comparison)
 * - Ord (ordering comparison)
 * - Inspect (to_str conversion)
 * - LogicalAnd, LogicalOr, LogicalNot (boolean operations)
 *
 * Operators are desugared to ability method calls:
 * - x + y  →  NumAdd.add {l = x, r = y}
 * - x == y →  Eq.eq {l = x, r = y}
 * - x < y  →  Ord.lt {l = x, r = y}
 */
object BuiltInAbilities {
    /**
     * Bool variant type: type Bool = | True, False |
     */
    private val boolType = FunkType.VariantType(
        constructors = mapOf("True" to null, "False" to null),
        rowVar = null
    )
    /**
     * Map of ability names to their methods.
     * Each method is a function that takes a type variable and returns the method's function type.
     */
    private val abilities = mapOf(
        "NumAdd" to mapOf(
            "add" to { typeVar: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "l" to typeVar,
                            "r" to typeVar
                        ),
                        rowVar = null
                    ),
                    returnType = typeVar
                )
            }
        ),
        "NumSub" to mapOf(
            "sub" to { typeVar: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "l" to typeVar,
                            "r" to typeVar
                        ),
                        rowVar = null
                    ),
                    returnType = typeVar
                )
            },
            "neg" to { typeVar: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "n" to typeVar
                        ),
                        rowVar = null
                    ),
                    returnType = typeVar
                )
            }
        ),
        "NumMul" to mapOf(
            "mul" to { typeVar: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "l" to typeVar,
                            "r" to typeVar
                        ),
                        rowVar = null
                    ),
                    returnType = typeVar
                )
            }
        ),
        "NumDiv" to mapOf(
            "div" to { typeVar: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "l" to typeVar,
                            "r" to typeVar
                        ),
                        rowVar = null
                    ),
                    returnType = typeVar
                )
            }
        ),
        "NumMod" to mapOf(
            "mod" to { typeVar: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "l" to typeVar,
                            "r" to typeVar
                        ),
                        rowVar = null
                    ),
                    returnType = typeVar
                )
            }
        ),
        "Eq" to mapOf(
            "eq" to { typeVar: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "l" to typeVar,
                            "r" to typeVar
                        ),
                        rowVar = null
                    ),
                    returnType = boolType
                )
            },
            "neq" to { typeVar: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "l" to typeVar,
                            "r" to typeVar
                        ),
                        rowVar = null
                    ),
                    returnType = boolType
                )
            }
        ),
        "Ord" to mapOf(
            "lt" to { typeVar: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "l" to typeVar,
                            "r" to typeVar
                        ),
                        rowVar = null
                    ),
                    returnType = boolType
                )
            },
            "le" to { typeVar: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "l" to typeVar,
                            "r" to typeVar
                        ),
                        rowVar = null
                    ),
                    returnType = boolType
                )
            },
            "gt" to { typeVar: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "l" to typeVar,
                            "r" to typeVar
                        ),
                        rowVar = null
                    ),
                    returnType = boolType
                )
            },
            "ge" to { typeVar: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "l" to typeVar,
                            "r" to typeVar
                        ),
                        rowVar = null
                    ),
                    returnType = boolType
                )
            }
        ),
        "Inspect" to mapOf(
            "to_str" to { typeVar: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "val" to typeVar
                        ),
                        rowVar = null
                    ),
                    returnType = FunkType.StringType
                )
            }
        ),
        "LogicalAnd" to mapOf(
            "and" to { _: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "l" to boolType,
                            "r" to boolType
                        ),
                        rowVar = null
                    ),
                    returnType = boolType
                )
            }
        ),
        "LogicalOr" to mapOf(
            "or" to { _: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "l" to boolType,
                            "r" to boolType
                        ),
                        rowVar = null
                    ),
                    returnType = boolType
                )
            }
        ),
        "LogicalNot" to mapOf(
            "not" to { _: FunkType.VarType ->
                FunkType.FunctionType(
                    paramType = FunkType.RecordType(
                        fields = mapOf(
                            "val" to boolType
                        ),
                        rowVar = null
                    ),
                    returnType = boolType
                )
            }
        )
    )

    /**
     * All built-in ability names.
     */
    private val allBuiltInAbilityNames = setOf(
        "NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "NumNeg",
        "Eq", "Ord", "Inspect",
        "LogicalAnd", "LogicalOr", "LogicalNot"
    )

    /**
     * Get the type of an ability method.
     *
     * @param abilityName The ability name (e.g., "NumAdd")
     * @param methodName The method name (e.g., "add")
     * @param typeVar The type variable for polymorphic abilities
     * @return The method's function type, or null if not found
     */
    fun getAbilityMethodType(
        abilityName: String,
        methodName: String,
        typeVar: FunkType.VarType
    ): FunkType? {
        return abilities[abilityName]?.get(methodName)?.invoke(typeVar)
    }

    /**
     * Check if a name is a built-in ability.
     */
    fun isBuiltInAbility(name: String): Boolean {
        return name in allBuiltInAbilityNames
    }

    /**
     * Check if a variant type is the Bool variant: | True, False |
     *
     * Bool is represented as a closed variant with exactly two constructors (True and False),
     * both with no payloads. This function allows other parts of the typechecker to recognize
     * Bool variants without duplicating the check logic.
     */
    fun isBoolVariant(type: FunkType): Boolean {
        return type is FunkType.VariantType &&
               type.constructors.size == 2 &&
               type.constructors.containsKey("True") &&
               type.constructors.containsKey("False") &&
               type.constructors["True"] == null &&
               type.constructors["False"] == null
    }

    /**
     * Get the canonical Bool type.
     */
    fun getBoolType(): FunkType.VariantType = boolType

    /**
     * Get all method names for an ability.
     */
    fun getAvailableMethods(abilityName: String): List<String> {
        return abilities[abilityName]?.keys?.toList() ?: emptyList()
    }

    /**
     * Get all built-in ability names.
     */
    fun getAllAbilityNames(): Set<String> {
        return allBuiltInAbilityNames
    }
}
