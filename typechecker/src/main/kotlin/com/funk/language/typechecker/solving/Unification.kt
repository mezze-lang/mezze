package com.funk.language.typechecker.solving

import com.funk.language.ast.*

/**
 * Unification algorithm - the heart of type inference.
 *
 * Unification finds a substitution that makes two types equal.
 * This is the core algorithm used in Hindley-Milner type inference.
 *
 * Example:
 *   unify(List α, List Int) = Success({α -> Int})
 *   unify(Int, String) = Failure("Cannot unify Int with String")
 *   unify(α, List α) = Failure("Occurs check: α occurs in List α")
 *
 * Design:
 * - Pure function (no side effects)
 * - Returns sealed UnificationResult (no nulls!)
 * - Exhaustive pattern matching on all FunkType cases
 * - Structural unification for records/variants
 */

/**
 * Result of unification.
 *
 * Sealed type - exhaustive pattern matching, no nulls!
 */
sealed class UnificationResult {
    /**
     * Unification succeeded with a substitution.
     */
    data class Success(val substitution: Substitution) : UnificationResult()

    /**
     * Unification failed with an error message.
     */
    data class Failure(
        val message: String,
        val type1: FunkType? = null,
        val type2: FunkType? = null
    ) : UnificationResult()
}

/**
 * Unify two types.
 *
 * Finds a substitution that makes the two types equal.
 * Returns Success with substitution, or Failure with error message.
 *
 * @param typeDeclarations Map of type declarations for expanding type applications
 */
fun unify(type1: FunkType, type2: FunkType, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    // Shortcut: if types are identical, no substitution needed
    if (type1 == type2) {
        return UnificationResult.Success(Substitution.empty)
    }

    // SPECIAL CASE: Bool primitive unifies with Bool variant
    // BooleanType (primitive) can unify with VariantType([True, False])
    // This allows pattern matching on True/False while keeping Bool as a primitive
    if (type1 is FunkType.BooleanType && type2 is FunkType.VariantType) {
        if (isBoolVariant(type2)) {
            return UnificationResult.Success(Substitution.empty)
        }
    }
    if (type1 is FunkType.VariantType && type2 is FunkType.BooleanType) {
        if (isBoolVariant(type1)) {
            return UnificationResult.Success(Substitution.empty)
        }
    }

    // SPECIAL CASE: TypeApplication expansion
    // Handle cross-type unification: VariantType with TypeApplication
    when {
        type1 is FunkType.VariantType && type2 is FunkType.TypeApplication -> {
            val expanded = expandTypeApplicationToVariant(type2, typeDeclarations)
            return if (expanded != null) {
                unify(type1, expanded, typeDeclarations)
            } else {
                // Can't expand - fall through to error
                UnificationResult.Failure(
                    message = "Cannot unify ${formatType(type1)} with ${formatType(type2)} (type expansion failed)",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        type1 is FunkType.TypeApplication && type2 is FunkType.VariantType -> {
            val expanded = expandTypeApplicationToVariant(type1, typeDeclarations)
            return if (expanded != null) {
                unify(expanded, type2, typeDeclarations)
            } else {
                // Can't expand - fall through to error
                UnificationResult.Failure(
                    message = "Cannot unify ${formatType(type1)} with ${formatType(type2)} (type expansion failed)",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        // Handle cross-type unification: RecordType with TypeApplication
        type1 is FunkType.RecordType && type2 is FunkType.TypeApplication -> {
            val expanded = expandTypeApplicationToRecord(type2, typeDeclarations)
            return if (expanded != null) {
                unify(type1, expanded, typeDeclarations)
            } else {
                // Can't expand - fall through to error
                UnificationResult.Failure(
                    message = "Cannot unify ${formatType(type1)} with ${formatType(type2)} (type expansion failed)",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
        type1 is FunkType.TypeApplication && type2 is FunkType.RecordType -> {
            val expanded = expandTypeApplicationToRecord(type1, typeDeclarations)
            return if (expanded != null) {
                unify(expanded, type2, typeDeclarations)
            } else {
                // Can't expand - fall through to error
                UnificationResult.Failure(
                    message = "Cannot unify ${formatType(type1)} with ${formatType(type2)} (type expansion failed)",
                    type1 = type1,
                    type2 = type2
                )
            }
        }
    }

    return when {
        // CASE 1: Type variable unification
        type1 is FunkType.VarType -> unifyVar(type1.variable, type2)
        type2 is FunkType.VarType -> unifyVar(type2.variable, type1)

        // CASE 2: Effect polymorphism - Suspended<T> with T
        // Allow pure types to unify with suspended types (effect polymorphism)
        // Examples: Int <Log> + Int → Int <Log>
        //          Int <Log> + Int <DB> → Int <Log, DB>
        type1 is FunkType.SuspendedType && type2 !is FunkType.SuspendedType -> {
            // Suspended<T> unifies with T by unifying the value types
            val valueResult = unify(type1.valueType, type2, typeDeclarations)
            when (valueResult) {
                is UnificationResult.Success -> {
                    // Result is Suspended<T> with the substitution applied
                    // The suspended type (type1) is the more specific type
                    valueResult
                }
                is UnificationResult.Failure -> valueResult
            }
        }
        type1 !is FunkType.SuspendedType && type2 is FunkType.SuspendedType -> {
            // T unifies with Suspended<T> by unifying the value types
            val valueResult = unify(type1, type2.valueType, typeDeclarations)
            when (valueResult) {
                is UnificationResult.Success -> {
                    // Result is Suspended<T> with the substitution applied
                    // The suspended type (type2) is the more specific type
                    valueResult
                }
                is UnificationResult.Failure -> valueResult
            }
        }

        // CASE 3: Function type unification
        type1 is FunkType.FunctionType && type2 is FunkType.FunctionType ->
            unifyFunctions(type1, type2, typeDeclarations)

        // CASE 4: Record type unification
        type1 is FunkType.RecordType && type2 is FunkType.RecordType ->
            unifyRecords(type1, type2, typeDeclarations)

        // CASE 5: Variant type unification
        type1 is FunkType.VariantType && type2 is FunkType.VariantType ->
            unifyVariants(type1, type2, typeDeclarations)

        // CASE 6: Type application unification
        type1 is FunkType.TypeApplication && type2 is FunkType.TypeApplication ->
            unifyTypeApplications(type1, type2, typeDeclarations)

        // CASE 7: Array type unification
        type1 is FunkType.ArrayType && type2 is FunkType.ArrayType ->
            unify(type1.elementType, type2.elementType, typeDeclarations)

        // CASE 8: List type unification
        type1 is FunkType.ListType && type2 is FunkType.ListType ->
            unify(type1.elementType, type2.elementType, typeDeclarations)

        // CASE 9: Set type unification
        type1 is FunkType.SetType && type2 is FunkType.SetType ->
            unify(type1.elementType, type2.elementType, typeDeclarations)

        // CASE 10: Map type unification
        type1 is FunkType.MapType && type2 is FunkType.MapType ->
            unifyMaps(type1, type2, typeDeclarations)

        // CASE 11: Suspended type unification (both suspended)
        type1 is FunkType.SuspendedType && type2 is FunkType.SuspendedType ->
            unifySuspended(type1, type2, typeDeclarations)

        // CASE 12: Effect row unification
        type1 is FunkType.EffectRow && type2 is FunkType.EffectRow ->
            unifyEffectRows(type1, type2)

        // CASE 13: Primitive type unification
        type1 is FunkType.IntType && type2 is FunkType.IntType -> UnificationResult.Success(Substitution.empty)
        type1 is FunkType.FloatType && type2 is FunkType.FloatType -> UnificationResult.Success(Substitution.empty)
        type1 is FunkType.StringType && type2 is FunkType.StringType -> UnificationResult.Success(Substitution.empty)
        type1 is FunkType.BooleanType && type2 is FunkType.BooleanType -> UnificationResult.Success(Substitution.empty)
        type1 is FunkType.NatType && type2 is FunkType.NatType -> UnificationResult.Success(Substitution.empty)
        type1 is FunkType.FracType && type2 is FunkType.FracType -> UnificationResult.Success(Substitution.empty)
        type1 is FunkType.OrderingType && type2 is FunkType.OrderingType -> UnificationResult.Success(Substitution.empty)

        // CASE 14: Type mismatch
        else -> {
            UnificationResult.Failure(
                message = "Cannot unify ${formatType(type1)} with ${formatType(type2)}",
                type1 = type1,
                type2 = type2
            )
        }
    }
}

/**
 * Unify a type variable with a type.
 *
 * Performs occurs check to prevent infinite types.
 */
private fun unifyVar(typeVar: TypeVar, type: FunkType): UnificationResult {
    // If type is the same variable, no substitution needed
    if (type is FunkType.VarType && type.variable == typeVar) {
        return UnificationResult.Success(Substitution.empty)
    }

    // Occurs check: prevent infinite types
    if (occursCheck(typeVar, type)) {
        return UnificationResult.Failure(
            message = "Occurs check failed: $typeVar occurs in ${formatType(type)} (would create infinite type)",
            type1 = FunkType.VarType(typeVar),
            type2 = type
        )
    }

    // Create substitution
    return UnificationResult.Success(Substitution.single(typeVar, type))
}

/**
 * Unify two function types.
 */
private fun unifyFunctions(fn1: FunkType.FunctionType, fn2: FunkType.FunctionType, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    // Unify parameter types
    val paramResult = unify(fn1.paramType, fn2.paramType, typeDeclarations)
    if (paramResult is UnificationResult.Failure) {
        return paramResult
    }
    val paramSubst = (paramResult as UnificationResult.Success).substitution

    // Apply parameter substitution and unify return types
    val return1 = paramSubst.apply(fn1.returnType)
    val return2 = paramSubst.apply(fn2.returnType)
    val returnResult = unify(return1, return2, typeDeclarations)
    if (returnResult is UnificationResult.Failure) {
        return returnResult
    }
    val returnSubst = (returnResult as UnificationResult.Success).substitution

    // Unify effects (if both have effects)
    val effects1 = fn1.effects
    val effects2 = fn2.effects

    // CRITICAL: Empty effect rows <> are equivalent to pure (null)
    val isPure1 = effects1 == null || effects1.effects.isEmpty()
    val isPure2 = effects2 == null || effects2.effects.isEmpty()

    val effectSubst = when {
        !isPure1 && !isPure2 -> {
            // Both have effects - unify them
            val effectResult = unifyEffectRows(effects1!!, effects2!!)
            if (effectResult is UnificationResult.Failure) {
                return effectResult
            }
            (effectResult as UnificationResult.Success).substitution
        }
        isPure1 && isPure2 -> {
            // Both pure - OK
            Substitution.empty
        }
        isPure1 && !isPure2 -> {
            // Unifying pure function with effectful function expectation
            // This is OK: a pure function can be used where an effectful function is expected
            // The caller is prepared to handle effects, but the function doesn't produce any
            Substitution.empty
        }
        !isPure1 && isPure2 -> {
            // Unifying effectful function with pure function expectation
            // This is NOT OK: an effectful function cannot be used where a pure function is expected
            return UnificationResult.Failure(
                message = "Cannot use effectful function <${effects1!!.effects.joinToString()}> where pure function is expected",
                type1 = fn1,
                type2 = fn2
            )
        }
        else -> Substitution.empty
    }

    // Compose all substitutions
    val finalSubst = returnSubst.compose(paramSubst).compose(effectSubst)
    return UnificationResult.Success(finalSubst)
}

/**
 * Unify two record types.
 *
 * Supports row polymorphism and currying/partial application:
 *
 * OPEN RECORDS (with rowVar):
 * - Can provide MORE fields than expected (extras ignored via row polymorphism)
 * - {x: Int | r} unifies with {x: Int, y: Int} (y goes into row variable)
 *
 * CLOSED RECORDS (no rowVar):
 * - For partial application/currying: one must be SUBSET of the other
 * - {x: Int, y: Int} unifies with {y: Int} (partial application - x will be curried)
 * - {x: Int} does NOT unify with {a: Int} (different field names)
 * - Field names must match exactly (no renaming)
 *
 * Examples:
 * - multiply {x, y} -> x * y  with  multiply {y = 5}  gives  {x} -> x * 5
 */
private fun unifyRecords(rec1: FunkType.RecordType, rec2: FunkType.RecordType, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    val fields1 = rec1.fields.keys
    val fields2 = rec2.fields.keys

    // CASE 1: At least one record is OPEN (has rowVar)
    // Open records allow extra fields via row polymorphism
    if (rec1.rowVar != null || rec2.rowVar != null) {
        // Unify shared fields, allow extra fields in either record
        val allFields = (fields1 + fields2).toSet()

        var substitution = Substitution.empty
        for (fieldName in allFields) {
            val fieldType1 = rec1.fields[fieldName]
            val fieldType2 = rec2.fields[fieldName]

            when {
                fieldType1 != null && fieldType2 != null -> {
                    // Field in both - must have compatible types
                    val appliedType1 = substitution.apply(fieldType1)
                    val appliedType2 = substitution.apply(fieldType2)

                    val fieldResult = unify(appliedType1, appliedType2, typeDeclarations)
                    if (fieldResult is UnificationResult.Failure) {
                        return UnificationResult.Failure(
                            message = "Field '$fieldName' type mismatch: ${fieldResult.message}",
                            type1 = rec1,
                            type2 = rec2
                        )
                    }

                    val fieldSubst = (fieldResult as UnificationResult.Success).substitution
                    substitution = fieldSubst.compose(substitution)
                }
                // Field only in one record - OK for open records (goes into row variable)
                else -> {}
            }
        }

        return UnificationResult.Success(substitution)
    }

    // CASE 2: Both records are CLOSED
    // Support partial application: one must be subset of the other
    // Shared fields must have EXACT name match
    val sharedFields = fields1.intersect(fields2)

    // One must be subset of the other for partial application/currying
    val isSubset = fields1.containsAll(fields2) || fields2.containsAll(fields1)

    if (!isSubset) {
        return UnificationResult.Failure(
            message = "Record field mismatch: {${fields1.joinToString()}} vs {${fields2.joinToString()}}",
            type1 = rec1,
            type2 = rec2
        )
    }

    // Unify all shared fields (must have matching types)
    var substitution = Substitution.empty
    for (fieldName in sharedFields) {
        val fieldType1 = substitution.apply(rec1.fields[fieldName]!!)
        val fieldType2 = substitution.apply(rec2.fields[fieldName]!!)

        val fieldResult = unify(fieldType1, fieldType2, typeDeclarations)
        if (fieldResult is UnificationResult.Failure) {
            return UnificationResult.Failure(
                message = "Field '$fieldName' type mismatch: ${fieldResult.message}",
                type1 = rec1,
                type2 = rec2
            )
        }

        val fieldSubst = (fieldResult as UnificationResult.Success).substitution
        substitution = fieldSubst.compose(substitution)
    }

    return UnificationResult.Success(substitution)
}

/**
 * Unify two variant types.
 */
internal fun unifyVariants(var1: FunkType.VariantType, var2: FunkType.VariantType, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    val constructors1 = var1.constructors.keys
    val constructors2 = var2.constructors.keys

    // If both variants have row variables (are open), allow merging constructors
    if (var1.rowVar != null || var2.rowVar != null) {
        // Merge constructors from both variants
        val allConstructors = (constructors1 + constructors2).toSet()

        var substitution = Substitution.empty
        for (constructorName in allConstructors) {
            val dataType1 = var1.constructors[constructorName]
            val dataType2 = var2.constructors[constructorName]

            when {
                dataType1 != null && dataType2 != null -> {
                    // Constructor in both - must have compatible payload types
                    if ((dataType1 == null) != (dataType2 == null)) {
                        return UnificationResult.Failure(
                            message = "Constructor '$constructorName' data mismatch",
                            type1 = var1,
                            type2 = var2
                        )
                    }

                    val appliedType1 = substitution.apply(dataType1)
                    val appliedType2 = substitution.apply(dataType2)

                    val constructorResult = unify(appliedType1, appliedType2, typeDeclarations)
                    if (constructorResult is UnificationResult.Failure) {
                        return UnificationResult.Failure(
                            message = "Constructor '$constructorName' type mismatch: ${constructorResult.message}",
                            type1 = var1,
                            type2 = var2
                        )
                    }

                    val constructorSubst = (constructorResult as UnificationResult.Success).substitution
                    substitution = constructorSubst.compose(substitution)
                }
                // Constructor only in one variant - that's OK for open variants
                else -> {}
            }
        }

        return UnificationResult.Success(substitution)
    }

    // Both variants are closed - merge constructors for inference purposes
    // This allows match expressions to return different constructors that get unified into
    // a combined variant type: `match x is Red -> Blue | Green -> Yellow` produces `| Blue, Yellow |`
    //
    // NOTE: For type annotations with closed variants, mismatches are caught earlier
    // during annotation validation, so this merging only happens during inference.

    // Merge ALL constructors from both variants
    val allConstructors = (constructors1 + constructors2).toSet()

    var substitution = Substitution.empty
    for (constructorName in allConstructors) {
        val dataType1 = var1.constructors[constructorName]
        val dataType2 = var2.constructors[constructorName]

        when {
            constructorName in constructors1 && constructorName in constructors2 -> {
                // Constructor in both - unify payload types
                if ((dataType1 == null) != (dataType2 == null)) {
                    return UnificationResult.Failure(
                        message = "Constructor '$constructorName' data mismatch",
                        type1 = var1,
                        type2 = var2
                    )
                }

                if (dataType1 != null && dataType2 != null) {
                    val appliedType1 = substitution.apply(dataType1)
                    val appliedType2 = substitution.apply(dataType2)

                    val constructorResult = unify(appliedType1, appliedType2, typeDeclarations)
                    if (constructorResult is UnificationResult.Failure) {
                        return UnificationResult.Failure(
                            message = "Constructor '$constructorName' type mismatch: ${constructorResult.message}",
                            type1 = var1,
                            type2 = var2
                        )
                    }

                    val constructorSubst = (constructorResult as UnificationResult.Success).substitution
                    substitution = constructorSubst.compose(substitution)
                }
            }
            // Constructor only in one variant - allowed for merging during inference
            else -> {}
        }
    }

    return UnificationResult.Success(substitution)
}

/**
 * Unify two type applications.
 */
private fun unifyTypeApplications(app1: FunkType.TypeApplication, app2: FunkType.TypeApplication, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    // Type constructors must match - unless one is an open variant
    if (app1.constructor != app2.constructor) {
        // OPEN VARIANT HANDLING: If one type is an open variant, expand both and unify as variants
        // This allows open variants to accept constructors from other types
        val expanded1 = expandTypeApplicationToVariant(app1, typeDeclarations)
        val expanded2 = expandTypeApplicationToVariant(app2, typeDeclarations)

        if (expanded1 != null && expanded2 != null) {
            // At least one must be open for cross-type unification
            if (expanded1.rowVar != null || expanded2.rowVar != null) {
                return unifyVariants(expanded1, expanded2, typeDeclarations)
            }
        }

        return UnificationResult.Failure(
            message = "Type constructor mismatch: ${app1.constructor} vs ${app2.constructor}",
            type1 = app1,
            type2 = app2
        )
    }

    // Argument count must match
    if (app1.args.size != app2.args.size) {
        return UnificationResult.Failure(
            message = "Type argument count mismatch: ${app1.args.size} vs ${app2.args.size}",
            type1 = app1,
            type2 = app2
        )
    }

    // Unify each type argument
    var substitution = Substitution.empty
    for (i in app1.args.indices) {
        val arg1 = substitution.apply(app1.args[i])
        val arg2 = substitution.apply(app2.args[i])

        val argResult = unify(arg1, arg2, typeDeclarations)
        if (argResult is UnificationResult.Failure) {
            return UnificationResult.Failure(
                message = "Type argument $i mismatch: ${argResult.message}",
                type1 = app1,
                type2 = app2
            )
        }

        val argSubst = (argResult as UnificationResult.Success).substitution
        substitution = argSubst.compose(substitution)
    }

    return UnificationResult.Success(substitution)
}

/**
 * Unify two map types.
 */
private fun unifyMaps(map1: FunkType.MapType, map2: FunkType.MapType, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    // Unify key types
    val keyResult = unify(map1.keyType, map2.keyType, typeDeclarations)
    if (keyResult is UnificationResult.Failure) {
        return keyResult
    }
    val keySubst = (keyResult as UnificationResult.Success).substitution

    // Apply key substitution and unify value types
    val value1 = keySubst.apply(map1.valueType)
    val value2 = keySubst.apply(map2.valueType)
    val valueResult = unify(value1, value2, typeDeclarations)
    if (valueResult is UnificationResult.Failure) {
        return valueResult
    }
    val valueSubst = (valueResult as UnificationResult.Success).substitution

    return UnificationResult.Success(valueSubst.compose(keySubst))
}

/**
 * Unify two suspended types.
 */
private fun unifySuspended(susp1: FunkType.SuspendedType, susp2: FunkType.SuspendedType, typeDeclarations: Map<String, TypeDeclaration> = emptyMap()): UnificationResult {
    // Unify value types
    val valueResult = unify(susp1.valueType, susp2.valueType, typeDeclarations)
    if (valueResult is UnificationResult.Failure) {
        return valueResult
    }
    val valueSubst = (valueResult as UnificationResult.Success).substitution

    // Unify effect rows
    val effectResult = unifyEffectRows(susp1.effects, susp2.effects)
    if (effectResult is UnificationResult.Failure) {
        return effectResult
    }
    val effectSubst = (effectResult as UnificationResult.Success).substitution

    return UnificationResult.Success(effectSubst.compose(valueSubst))
}

/**
 * Unify two effect rows.
 */
private fun unifyEffectRows(eff1: FunkType.EffectRow, eff2: FunkType.EffectRow): UnificationResult {
    // For now, effect rows must have the same effects
    // Row polymorphism for effects is handled separately
    if (eff1.effects != eff2.effects) {
        val missing1 = eff2.effects - eff1.effects
        val missing2 = eff1.effects - eff2.effects

        return UnificationResult.Failure(
            message = buildString {
                append("Effect row mismatch")
                if (missing1.isNotEmpty()) {
                    append(": missing [${missing1.joinToString()}]")
                }
                if (missing2.isNotEmpty()) {
                    append(": extra [${missing2.joinToString()}]")
                }
            },
            type1 = eff1,
            type2 = eff2
        )
    }

    return UnificationResult.Success(Substitution.empty)
}

/**
 * Format a type for error messages.
 */
private fun formatType(type: FunkType): String {
    return when (type) {
        is FunkType.VarType -> type.variable.toString()
        is FunkType.IntType -> "Int"
        is FunkType.FloatType -> "Float"
        is FunkType.StringType -> "String"
        is FunkType.BooleanType -> "Bool"
        is FunkType.NatType -> "Nat"
        is FunkType.FracType -> "Frac"
        is FunkType.OrderingType -> "Ordering"
        is FunkType.FunctionType -> "{...} -> ..."
        is FunkType.RecordType -> "{${type.fields.keys.take(3).joinToString()}${if (type.fields.size > 3) ", ..." else ""}}"
        is FunkType.VariantType -> "[${type.constructors.keys.take(3).joinToString()}${if (type.constructors.size > 3) ", ..." else ""}]"
        is FunkType.TypeApplication -> "${type.constructor}${if (type.args.isNotEmpty()) " ..." else ""}"
        is FunkType.ArrayType -> "Array ${formatType(type.elementType)}"
        is FunkType.ListType -> "List ${formatType(type.elementType)}"
        is FunkType.SetType -> "Set ${formatType(type.elementType)}"
        is FunkType.MapType -> "Map ${formatType(type.keyType)} ${formatType(type.valueType)}"
        is FunkType.SuspendedType -> "Suspended<${formatType(type.valueType)}>"
        is FunkType.EffectRow -> "<${type.effects.take(3).joinToString()}${if (type.effects.size > 3) ", ..." else ""}>"
    }
}

/**
 * Expand a TypeApplication to a RecordType.
 *
 * For example:
 * - `Person` expands to `{ name: String, age: Int, ... }`
 *
 * This allows unifying concrete record types with type application aliases.
 *
 * @param typeApp The type application to expand
 * @param typeDeclarations Map of type declarations
 * @return The expanded record type, or null if expansion failed
 */
internal fun expandTypeApplicationToRecord(typeApp: FunkType.TypeApplication, typeDeclarations: Map<String, TypeDeclaration>): FunkType.RecordType? {
    // Look up the type declaration
    val typeDecl = typeDeclarations[typeApp.constructor] ?: return null

    // Only record definitions can be expanded to record types
    val recordDef = typeDecl.definition as? RecordDefinition ?: return null

    // Build substitution map for type parameters
    val typeParamSubst = typeDecl.typeParams.zip(typeApp.args).associate { (paramName, argType) ->
        val substType = if (argType is FunkType.VarType) {
            FunkType.VarType(TypeVar.fresh())
        } else {
            argType
        }
        paramName to substType
    }

    // Helper to substitute type parameters in a FunkType
    fun substituteTypeParams(type: FunkType): FunkType {
        return when (type) {
            is FunkType.VarType -> {
                typeParamSubst[type.variable.name] ?: type
            }
            is FunkType.RecordType -> {
                FunkType.RecordType(
                    fields = type.fields.mapValues { (_, fieldType) ->
                        substituteTypeParams(fieldType)
                    },
                    rowVar = type.rowVar
                )
            }
            is FunkType.FunctionType -> {
                FunkType.FunctionType(
                    paramType = substituteTypeParams(type.paramType),
                    returnType = substituteTypeParams(type.returnType),
                    effects = type.effects,
                    abilityConstraints = type.abilityConstraints
                )
            }
            is FunkType.TypeApplication -> {
                FunkType.TypeApplication(
                    constructor = type.constructor,
                    args = type.args.map { substituteTypeParams(it) }
                )
            }
            is FunkType.ArrayType -> {
                FunkType.ArrayType(substituteTypeParams(type.elementType))
            }
            is FunkType.ListType -> {
                FunkType.ListType(substituteTypeParams(type.elementType))
            }
            is FunkType.SetType -> {
                FunkType.SetType(substituteTypeParams(type.elementType))
            }
            is FunkType.MapType -> {
                FunkType.MapType(
                    keyType = substituteTypeParams(type.keyType),
                    valueType = substituteTypeParams(type.valueType)
                )
            }
            is FunkType.VariantType -> {
                FunkType.VariantType(
                    constructors = type.constructors.mapValues { (_, dataType) ->
                        dataType?.let { substituteTypeParams(it) }
                    },
                    rowVar = type.rowVar
                )
            }
            is FunkType.SuspendedType -> {
                FunkType.SuspendedType(
                    valueType = substituteTypeParams(type.valueType),
                    effects = type.effects
                )
            }
            else -> type
        }
    }

    // Shared ContextBuilder instance for consistent type variable creation
    val contextBuilder = com.funk.language.typechecker.context.ContextBuilder()

    // Build the record type by converting each field
    val fields = recordDef.fields.associate { field ->
        val convertedType = contextBuilder.convertTypeAnnotation(field.type)
        val substitutedType = substituteTypeParams(convertedType)
        field.name to substitutedType
    }

    return FunkType.RecordType(
        fields = fields,
        rowVar = if (recordDef.isOpen) RowVar.fresh() else null
    )
}

/**
 * Expand a TypeApplication to a VariantType.
 *
 * For example:
 * - `Option Int` expands to `[Some { val: Int }, None]`
 * - `Result String Int` expands to `[Ok { val: Int }, Err { error: String }]`
 *
 * This allows unifying concrete variant types like `[Some]` with type applications like `Option Int`.
 *
 * @param typeApp The type application to expand
 * @param typeDeclarations Map of type declarations
 * @return The expanded variant type, or null if expansion failed
 */
internal fun expandTypeApplicationToVariant(typeApp: FunkType.TypeApplication, typeDeclarations: Map<String, TypeDeclaration>): FunkType.VariantType? {
    // Look up the type declaration
    val typeDecl = typeDeclarations[typeApp.constructor] ?: return null

    // Only variant definitions can be expanded to variant types
    val variantDef = typeDecl.definition as? VariantDefinition ?: return null

    // DEBUG: Check how many constructors we're expanding
    if (System.getProperty("funk.debug.expansion") == "true") {
        println("[DEBUG expandTypeApplication] Expanding ${typeApp.constructor}")
        println("[DEBUG expandTypeApplication] Variant has ${variantDef.constructors.size} constructors: ${variantDef.constructors.map { it.name }}")
    }

    // Build substitution map for type parameters
    // CRITICAL: When args contain TypeVars, we create a substitution that maps
    // parameter names to FRESH TypeVars, not the original ones from args.
    // This prevents occurs check failures where the same TypeVar instance appears
    // in both the TypeApplication args and the expanded payload types.
    val typeParamSubst = typeDecl.typeParams.zip(typeApp.args).associate { (paramName, argType) ->
        // If argType is a TypeVar, create a FRESH TypeVar for substitution
        // This ensures each expansion gets unique TypeVars
        val substType = if (argType is FunkType.VarType) {
            FunkType.VarType(TypeVar.fresh())
        } else {
            argType
        }
        paramName to substType
    }

    // Helper to substitute type parameters in a FunkType
    fun substituteTypeParams(type: FunkType): FunkType {
        return when (type) {
            is FunkType.VarType -> {
                // Check if this type variable matches a type parameter
                // Try both with and without the ' prefix since formats may differ:
                // - typeDecl.typeParams uses "'a" format (from parser)
                // - ContextBuilder.convertTypeAnnotation creates TypeVar with "a" format (without ')
                val varName = type.variable.name
                typeParamSubst[varName]
                    ?: typeParamSubst["'$varName"]
                    ?: typeParamSubst[varName.removePrefix("'")]
                    ?: type
            }
            is FunkType.RecordType -> {
                FunkType.RecordType(
                    fields = type.fields.mapValues { (_, fieldType) ->
                        substituteTypeParams(fieldType)
                    },
                    rowVar = type.rowVar
                )
            }
            is FunkType.FunctionType -> {
                FunkType.FunctionType(
                    paramType = substituteTypeParams(type.paramType),
                    returnType = substituteTypeParams(type.returnType),
                    effects = type.effects,
                    abilityConstraints = type.abilityConstraints
                )
            }
            is FunkType.TypeApplication -> {
                FunkType.TypeApplication(
                    constructor = type.constructor,
                    args = type.args.map { substituteTypeParams(it) }
                )
            }
            is FunkType.ArrayType -> {
                FunkType.ArrayType(substituteTypeParams(type.elementType))
            }
            is FunkType.ListType -> {
                FunkType.ListType(substituteTypeParams(type.elementType))
            }
            is FunkType.SetType -> {
                FunkType.SetType(substituteTypeParams(type.elementType))
            }
            is FunkType.MapType -> {
                FunkType.MapType(
                    keyType = substituteTypeParams(type.keyType),
                    valueType = substituteTypeParams(type.valueType)
                )
            }
            is FunkType.VariantType -> {
                FunkType.VariantType(
                    constructors = type.constructors.mapValues { (_, dataType) ->
                        dataType?.let { substituteTypeParams(it) }
                    },
                    rowVar = type.rowVar
                )
            }
            is FunkType.SuspendedType -> {
                FunkType.SuspendedType(
                    valueType = substituteTypeParams(type.valueType),
                    effects = type.effects
                )
            }
            // Primitive types don't contain type variables
            else -> type
        }
    }

    // Shared ContextBuilder instance for consistent type variable creation
    // IMPORTANT: Use a single instance to ensure type variables are consistent
    // within this expansion (though still creates fresh vars each call)
    val contextBuilder = com.funk.language.typechecker.context.ContextBuilder()

    // Build the variant type by converting each constructor
    val constructors = variantDef.constructors.associate { constructor ->
        val dataType = constructor.dataType?.let { annotation ->
            // Convert type annotation to FunkType
            // This creates TypeVars for the type parameters like 'a
            val convertedType = contextBuilder.convertTypeAnnotation(annotation)
            // Substitute those TypeVars with the actual type arguments
            substituteTypeParams(convertedType)
        }
        constructor.name to dataType
    }

    // DEBUG
    try {
        val debugFile = java.io.File("/tmp/expand_debug.txt")
        debugFile.appendText("[EXPAND] ${typeApp.constructor}\n")
        debugFile.appendText("[EXPAND] VariantDef has ${variantDef.constructors.size} constructors: ${variantDef.constructors.map { it.name }}\n")
        debugFile.appendText("[EXPAND] Built ${constructors.size} constructors: ${constructors.keys}\n\n")
    } catch (e: Exception) {
        // Ignore
    }

    return FunkType.VariantType(
        constructors = constructors,
        rowVar = if (variantDef.isOpen) RowVar.fresh() else null
    )
}

/**
 * Check if a variant type is the Bool variant: | True, False |
 */
private fun isBoolVariant(variant: FunkType.VariantType): Boolean {
    // Bool variant must have exactly 2 constructors: True and False, both with no payloads
    return variant.constructors.size == 2 &&
           variant.constructors.containsKey("True") &&
           variant.constructors.containsKey("False") &&
           variant.constructors["True"] == null &&
           variant.constructors["False"] == null
}
