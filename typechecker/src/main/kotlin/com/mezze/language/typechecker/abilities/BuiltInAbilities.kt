package com.mezze.language.typechecker.abilities

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Built-in ability registry for TypeChecker.
 *
 * Abilities are type classes that define operations on types.
 * For example, NumAdd defines the `add` operation for numeric types.
 *
 * Built-in abilities are automatically available without imports:
 * - NumAdd, NumSub, NumMul, NumDiv, NumMod, NumNeg (arithmetic)
 * - Eq (equality comparison)
 * - Ord (ordering comparison)
 * - Inspect (to_dbg_str conversion)
 * - LogicalAnd, LogicalOr, LogicalNot (boolean operations)
 *
 * Operators are desugared to method-call syntax (receiver is implicit `it`):
 * - x + y  →  x.add {r = y}   (NumAdd ability)
 * - x == y →  x.eq {r = y}    (Eq ability)
 * - x < y  →  x.lt {r = y}    (Ord ability)
 */
object BuiltInAbilities {
    /**
     * Bool type - use the singleton BoolType for consistency with inferred types.
     * Note: Bool is technically a variant with True/False constructors in the language,
     * but internally we use BoolType for efficient comparison and consistency.
     */
    private val boolType = MezzeType.BoolType
    /**
     * Map of ability names to their methods.
     * Each method is a function that takes a type variable and returns the method's function type.
     */
    private val abilities = mapOf(
        // Arithmetic: receiver is `it`, second operand is `r`
        "NumAdd" to mapOf(
            "add" to { typeVar: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = mapOf("r" to typeVar), rowVar = null),
                    returnType = typeVar
                )
            }
        ),
        "NumSub" to mapOf(
            "sub" to { typeVar: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = mapOf("r" to typeVar), rowVar = null),
                    returnType = typeVar
                )
            },
            "neg" to { typeVar: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = emptyMap(), rowVar = null),
                    returnType = typeVar
                )
            }
        ),
        "NumMul" to mapOf(
            "mul" to { typeVar: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = mapOf("r" to typeVar), rowVar = null),
                    returnType = typeVar
                )
            }
        ),
        "NumDiv" to mapOf(
            "div" to { typeVar: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = mapOf("r" to typeVar), rowVar = null),
                    returnType = typeVar
                )
            }
        ),
        "NumMod" to mapOf(
            "mod" to { typeVar: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = mapOf("r" to typeVar), rowVar = null),
                    returnType = typeVar
                )
            }
        ),
        // Equality: receiver is `it`, second operand is `r`
        "Eq" to mapOf(
            "eq" to { typeVar: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = mapOf("r" to typeVar), rowVar = null),
                    returnType = boolType
                )
            },
            "neq" to { typeVar: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = mapOf("r" to typeVar), rowVar = null),
                    returnType = boolType
                )
            }
        ),
        // Ordering: receiver is `it`, second operand is `r`
        "Ord" to mapOf(
            "lt" to { typeVar: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = mapOf("r" to typeVar), rowVar = null),
                    returnType = boolType
                )
            },
            "le" to { typeVar: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = mapOf("r" to typeVar), rowVar = null),
                    returnType = boolType
                )
            },
            "gt" to { typeVar: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = mapOf("r" to typeVar), rowVar = null),
                    returnType = boolType
                )
            },
            "ge" to { typeVar: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = mapOf("r" to typeVar), rowVar = null),
                    returnType = boolType
                )
            }
        ),
        // Inspect: receiver is `it`, no explicit params
        "Inspect" to mapOf(
            "to_dbg_str" to { _: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = emptyMap(), rowVar = null),
                    returnType = MezzeType.StringType
                )
            }
        ),
        // Logical: receiver is `it` (Bool), second operand is `r`
        "LogicalAnd" to mapOf(
            "and" to { _: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = mapOf("r" to boolType), rowVar = null),
                    returnType = boolType
                )
            }
        ),
        "LogicalOr" to mapOf(
            "or" to { _: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = mapOf("r" to boolType), rowVar = null),
                    returnType = boolType
                )
            }
        ),
        // LogicalNot: receiver is `it` (Bool), no explicit params
        "LogicalNot" to mapOf(
            "not" to { _: MezzeType.VarType ->
                MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(fields = emptyMap(), rowVar = null),
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
        typeVar: MezzeType.VarType
    ): MezzeType? {
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
    fun isBoolVariant(type: MezzeType): Boolean {
        return type is MezzeType.VariantType &&
               type.constructors.size == 2 &&
               type.constructors.containsKey("True") &&
               type.constructors.containsKey("False") &&
               type.constructors["True"] == null &&
               type.constructors["False"] == null
    }

    /**
     * Get the canonical Bool type.
     */
    fun getBoolType(): MezzeType = boolType

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
