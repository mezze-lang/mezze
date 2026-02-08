package com.funk.language.typechecker.engines

import com.funk.language.ast.*
import com.funk.language.typechecker.*
import com.funk.language.typechecker.support.Constraint
import com.funk.language.typechecker.support.TypeContext

/**
 * Engine responsible for type unification, constraint solving, and substitution.
 *
 * This engine handles the core unification algorithm used in Hindley-Milner type inference,
 * including:
 * - Type unification with substitution tracking
 * - Constraint collection and solving
 * - Substitution application to types and expressions
 * - Type variable operations (generalization, instantiation, occurs check)
 * - Type structural equality and compatibility checking
 *
 * # Architecture
 *
 * The UnificationEngine is one of four core engines extracted from TypeChecker:
 * - **UnificationEngine**: Type unification and constraint solving (this file)
 * - **InferenceEngine**: Type inference for expressions
 * - **ValidationEngine**: Type validation and checking
 * - **ResolutionEngine**: Type alias and ability resolution
 *
 * # State Management
 *
 * The engine maintains several pieces of mutable state:
 * - `typeContext`: Centralized type checking context (TypeContext)
 * - `typeVarAbilityConstraints`: Map of type variables to their ability constraints
 * - `accumulatedSubstitutions`: Accumulated substitutions from unification
 * - `usageConstraints`: Usage flow constraints for bidirectional inference
 * - `constraintSolver`: External constraint solver for complex constraint systems
 *
 * @property typeContext The centralized type checking context
 * @property constraintSolver External constraint solver for complex constraints
 */
class UnificationEngine(
    private val typeContext: TypeContext,
    private val constraintSolver: ConstraintSolver
) : IUnificationEngine {
    // Additional state not yet in TypeContext
    // TODO: Move these to TypeContext in future iteration
    private val typeVarAbilityConstraints = mutableMapOf<TypeVar, MutableSet<String>>()
    private val accumulatedSubstitutions = mutableMapOf<TypeVar, FunkType>()
    private val usageConstraints = mutableListOf<UsageConstraint>()

    /**
     * Add a type equality constraint and immediately unify the types.
     *
     * This ensures that constraints like "'a with NumAdd = Bool" fail immediately
     * rather than being deferred.
     *
     * @param type1 The first type to constrain
     * @param type2 The second type to constrain
     */
    fun addConstraint(type1: FunkType, type2: FunkType) {
        typeContext.constraints.add(Pair(type1, type2))
        // Immediately unify to trigger ability constraint validation
        unifyTypes(type1, type2)
    }

    /**
     * Track an ability constraint for a type variable.
     *
     * This records that a type variable must have a specific ability (type class)
     * such as NumAdd, Eq, Ord, etc.
     *
     * @param typeVar The type variable that must have the ability
     * @param abilityName The name of the ability (e.g., "NumAdd", "Eq")
     */
    fun trackAbilityConstraint(typeVar: TypeVar, abilityName: String) {
        typeVarAbilityConstraints.getOrPut(typeVar) { mutableSetOf() }.add(abilityName)
    }

    /**
     * Track ability constraints for a type if it's a type variable.
     *
     * For concrete types, this validates immediately that they support the ability.
     * For composite types, it recursively tracks constraints on their components.
     *
     * @param type The type to track constraints for
     * @param abilityName The name of the ability
     * @param sourceExpression Optional expression for error location tracking
     */
    fun trackAbilityConstraintForType(
        type: FunkType,
        abilityName: String,
        sourceExpression: Expression? = null
    ) {
        // Handle type variables - track constraints for later unification
        if (type is FunkType.VarType) {
            trackAbilityConstraint(type.variable, abilityName)
            return
        }

        // Handle function types - they don't have abilities
        if (type is FunkType.FunctionType) {
            return
        }

        // For composite types, recursively track constraints on their components
        when (type) {
            is FunkType.RecordType -> {
                // Track constraints for all field types
                type.fields.values.forEach { fieldType ->
                    trackAbilityConstraintForType(fieldType, abilityName, sourceExpression)
                }
            }
            is FunkType.VariantType -> {
                // Track constraints for all constructor types
                type.constructors.values.filterNotNull().forEach { constructorType ->
                    trackAbilityConstraintForType(constructorType, abilityName, sourceExpression)
                }
            }
            is FunkType.ListType -> {
                trackAbilityConstraintForType(type.elementType, abilityName, sourceExpression)
            }
            is FunkType.ArrayType -> {
                trackAbilityConstraintForType(type.elementType, abilityName, sourceExpression)
            }
            is FunkType.SetType -> {
                trackAbilityConstraintForType(type.elementType, abilityName, sourceExpression)
            }
            is FunkType.MapType -> {
                trackAbilityConstraintForType(type.keyType, abilityName, sourceExpression)
                trackAbilityConstraintForType(type.valueType, abilityName, sourceExpression)
            }
            is FunkType.TypeApplication -> {
                type.args.forEach { argType ->
                    trackAbilityConstraintForType(argType, abilityName, sourceExpression)
                }
            }
            is FunkType.SuspendedType -> {
                trackAbilityConstraintForType(type.valueType, abilityName, sourceExpression)
            }
            else -> {
                // Concrete types - no tracking needed
            }
        }
    }

    /**
     * Solve all accumulated constraints and return substitutions.
     *
     * This uses the external ConstraintSolver to handle complex constraint systems
     * with usage flow and ability constraints.
     *
     * @return A map of type variable substitutions
     */
    fun solveConstraints(): Map<TypeVar, FunkType> {
        if (typeContext.constraints.isNotEmpty() || usageConstraints.isNotEmpty()) {
            try {
                // Convert constraints to the format expected by the solver
                val constraintList = typeContext.constraints.map { (left, right) ->
                    Constraint.Equality(left, right)
                }

                // Use bidirectional constraint solver with usage flow
                val substitutions = constraintSolver.solveWithUsageFlow(constraintList, usageConstraints)

                // Accumulate substitutions for later application to inferredType fields
                substitutions.mapping.forEach { (typeVar, type) ->
                    accumulatedSubstitutions[typeVar] = type
                }

                // Clear both regular and usage constraints
                typeContext.constraints.clear()
                usageConstraints.clear()

                // Make a deep copy of the mapping to avoid it being cleared
                val result = HashMap<TypeVar, FunkType>()
                substitutions.mapping.forEach { (k, v) -> result[k] = v }
                return result
            } catch (e: TypeCheckError) {
                throw e
            } catch (e: ConstraintConflictError) {
                throw TypeCheckError(
                    e.message ?: "Type conflict during constraint solving",
                    null as Expression?,
                    ErrorType.TYPE_MISMATCH
                )
            } catch (e: AbilityConstraintError) {
                throw TypeCheckError(
                    e.message ?: "Ability constraint error",
                    null as Expression?,
                    ErrorType.GENERAL
                )
            } catch (e: UnificationException) {
                throw TypeCheckError("Type unification failed: ${e.message}")
            } catch (e: Exception) {
                if (System.getProperty("funk.typecheck.debug") == "true") {
                    e.printStackTrace()
                }
                return emptyMap()
            }
        }
        return emptyMap()
    }

    /**
     * Solve constraints and return substitutions (legacy method).
     *
     * This method is currently disabled and returns empty substitutions.
     * Use [solveConstraints] instead.
     *
     * @return An empty map of substitutions
     */
    fun solveConstraintsAndGetSubstitutions(): Map<TypeVar, FunkType> {
        // Currently disabled - constraints are solved immediately
        typeContext.constraints.clear()
        return emptyMap()
    }

    /**
     * Unify two types and return whether unification succeeded.
     *
     * This performs type unification and updates the type context with substitutions.
     * It also handles ability constraint merging for type variables.
     *
     * @param actualType The actual type (inferred)
     * @param expectedType The expected type (from annotation or context)
     * @return true if unification succeeded, false otherwise
     */
    override fun unifyTypes(actualType: FunkType, expectedType: FunkType): Boolean {
        val substitutions = mutableMapOf<TypeVar, FunkType>()
        return try {
            unifyTypesWithSubstitution(actualType, expectedType, substitutions)

            // Apply substitutions to context if unification succeeded
            for ((varName, varType) in typeContext.context) {
                typeContext.context[varName] = applySubstitution(varType, substitutions)
            }

            // Update ability constraints with unified type variables
            if (substitutions.isNotEmpty()) {
                val updatedConstraints = mutableMapOf<TypeVar, MutableSet<String>>()
                for ((typeVar, abilities) in typeVarAbilityConstraints) {
                    // Find the canonical type variable after substitution
                    val substitutedType = applySubstitution(FunkType.VarType(typeVar), substitutions)
                    val canonicalTypeVar = if (substitutedType is FunkType.VarType) {
                        substitutedType.variable
                    } else {
                        // Type variable unified with concrete type - keep original for validation
                        typeVar
                    }
                    // Merge abilities for the canonical type variable
                    updatedConstraints.getOrPut(canonicalTypeVar) { mutableSetOf() }.addAll(abilities)
                }
                typeVarAbilityConstraints.clear()
                typeVarAbilityConstraints.putAll(updatedConstraints)

                // Persist substitutions for ability constraint validation
                for ((typeVar, type) in substitutions) {
                    // Only add non-trivial substitutions (skip 'a = 'a)
                    if (type !is FunkType.VarType || type.variable != typeVar) {
                        accumulatedSubstitutions[typeVar] = type
                    }
                }
            }

            true
        } catch (e: TypeCheckError) {
            // Re-throw type errors (like ability constraint violations)
            throw e
        } catch (e: Exception) {
            // Other exceptions indicate unification failure
            false
        }
    }

    /**
     * Unify two types with explicit substitution tracking.
     *
     * This is the core unification algorithm implementing Hindley-Milner type inference.
     * It handles:
     * - Type variable unification with occurs check
     * - Structural unification for functions, records, variants, etc.
     * - Ability constraint merging for unified type variables
     *
     * @param type1 The first type to unify
     * @param type2 The second type to unify
     * @param substitutions Mutable map to accumulate substitutions
     */
    override fun unifyTypesWithSubstitution(
        type1: FunkType,
        type2: FunkType,
        substitutions: MutableMap<TypeVar, FunkType>
    ) {
        val t1 = applySubstitution(type1, substitutions)
        val t2 = applySubstitution(type2, substitutions)

        when {
            t1 == t2 -> return // Already unified

            t1 is FunkType.VarType -> {
                if (occursCheck(t1.variable, t2, substitutions)) {
                    // Occurs check failed - ignore constraint
                    if (System.getProperty("funk.typecheck.debug") == "true") {
                        println("Occurs check failed: ${t1.variable} in $t2")
                    }
                } else {
                    if (t2 is FunkType.VarType) {
                        // Merge ability constraints when unifying two type variables
                        // Filter out Inspect - it's universally available
                        val t1Constraints = typeVarAbilityConstraints[t1.variable]
                        val t2Constraints = typeVarAbilityConstraints[t2.variable]
                        if (t1Constraints != null && t2Constraints != null) {
                            val constraintsToAdd = t1Constraints.filter { it != "Inspect" }
                            t2Constraints.addAll(constraintsToAdd)
                        } else if (t1Constraints != null) {
                            val filteredConstraints = t1Constraints.filter { it != "Inspect" }.toMutableSet()
                            if (filteredConstraints.isNotEmpty()) {
                                typeVarAbilityConstraints[t2.variable] = filteredConstraints
                            }
                        }
                    }
                    substitutions[t1.variable] = t2
                }
            }

            t2 is FunkType.VarType -> {
                if (occursCheck(t2.variable, t1, substitutions)) {
                    // Occurs check failed - ignore constraint
                    if (System.getProperty("funk.typecheck.debug") == "true") {
                        println("Occurs check failed: ${t2.variable} in $t1")
                    }
                } else {
                    if (t1 is FunkType.VarType) {
                        // Merge ability constraints when unifying two type variables
                        val t1Constraints = typeVarAbilityConstraints[t1.variable]
                        val t2Constraints = typeVarAbilityConstraints[t2.variable]
                        if (t1Constraints != null && t2Constraints != null) {
                            val constraintsToAdd = t2Constraints.filter { it != "Inspect" }
                            t1Constraints.addAll(constraintsToAdd)
                        } else if (t2Constraints != null) {
                            val filteredConstraints = t2Constraints.filter { it != "Inspect" }.toMutableSet()
                            if (filteredConstraints.isNotEmpty()) {
                                typeVarAbilityConstraints[t1.variable] = filteredConstraints
                            }
                        }
                    }
                    substitutions[t2.variable] = t1
                }
            }

            t1 is FunkType.FunctionType && t2 is FunkType.FunctionType -> {
                unifyTypesWithSubstitution(t1.paramType, t2.paramType, substitutions)
                unifyTypesWithSubstitution(t1.returnType, t2.returnType, substitutions)
            }

            t1 is FunkType.RecordType && t2 is FunkType.RecordType -> {
                // Merge record types by combining all fields
                val mergedFields = mutableMapOf<String, FunkType>()
                mergedFields.putAll(t1.fields)

                // Unify common fields
                for ((fieldName, t2FieldType) in t2.fields) {
                    val t1FieldType = mergedFields[fieldName]
                    if (t1FieldType != null) {
                        unifyTypesWithSubstitution(t1FieldType, t2FieldType, substitutions)
                    } else {
                        mergedFields[fieldName] = t2FieldType
                    }
                }
            }

            t1 is FunkType.VariantType && t2 is FunkType.VariantType -> {
                // Unify constructor types
                val allConstructors = (t1.constructors.keys + t2.constructors.keys)
                for (constructor in allConstructors) {
                    val ct1 = t1.constructors[constructor]
                    val ct2 = t2.constructors[constructor]
                    if (ct1 != null && ct2 != null) {
                        unifyTypesWithSubstitution(ct1, ct2, substitutions)
                    }
                }
            }

            t1 is FunkType.ListType && t2 is FunkType.ListType -> {
                unifyTypesWithSubstitution(t1.elementType, t2.elementType, substitutions)
            }

            t1 is FunkType.ArrayType && t2 is FunkType.ArrayType -> {
                unifyTypesWithSubstitution(t1.elementType, t2.elementType, substitutions)
            }

            t1 is FunkType.SetType && t2 is FunkType.SetType -> {
                unifyTypesWithSubstitution(t1.elementType, t2.elementType, substitutions)
            }

            t1 is FunkType.MapType && t2 is FunkType.MapType -> {
                unifyTypesWithSubstitution(t1.keyType, t2.keyType, substitutions)
                unifyTypesWithSubstitution(t1.valueType, t2.valueType, substitutions)
            }

            t1 is FunkType.TypeApplication && t2 is FunkType.TypeApplication -> {
                if (t1.constructor == t2.constructor && t1.args.size == t2.args.size) {
                    for (i in t1.args.indices) {
                        unifyTypesWithSubstitution(t1.args[i], t2.args[i], substitutions)
                    }
                } else {
                    throw TypeCheckError(
                        "Cannot unify type applications: ${t1.constructor} vs ${t2.constructor}"
                    )
                }
            }

            t1 is FunkType.SuspendedType && t2 is FunkType.SuspendedType -> {
                unifyTypesWithSubstitution(t1.valueType, t2.valueType, substitutions)
            }

            t1 is FunkType.SuspendedType -> {
                unifyTypesWithSubstitution(t1.valueType, t2, substitutions)
            }

            t2 is FunkType.SuspendedType -> {
                unifyTypesWithSubstitution(t1, t2.valueType, substitutions)
            }

            else -> {
                // Types don't unify
                throw TypeCheckError("Cannot unify types: $t1 and $t2")
            }
        }
    }

    /**
     * Apply substitutions to a type.
     *
     * This recursively applies type variable substitutions throughout a type structure.
     *
     * @param type The type to apply substitutions to
     * @param substitutions The map of substitutions to apply
     * @return The type with substitutions applied
     */
    override fun applySubstitution(
        type: FunkType,
        substitutions: Map<TypeVar, FunkType>
    ): FunkType {
        return when (type) {
            is FunkType.VarType -> {
                substitutions[type.variable] ?: type
            }
            is FunkType.FunctionType -> {
                val substitutedParamType = applySubstitution(type.paramType, substitutions)
                val substitutedReturnType = applySubstitution(type.returnType, substitutions)
                val substitutedConstraints = type.abilityConstraints.map { constraint ->
                    FunkType.FunctionAbilityConstraint(
                        constraint.abilityName,
                        constraint.typeArgs.map { applySubstitution(it, substitutions) }
                    )
                }
                FunkType.FunctionType(
                    substitutedParamType,
                    substitutedReturnType,
                    type.effects,
                    substitutedConstraints
                )
            }
            is FunkType.RecordType -> {
                val substitutedFields = type.fields.mapValues { (_, fieldType) ->
                    applySubstitution(fieldType, substitutions)
                }
                FunkType.RecordType(substitutedFields, type.rowVar)
            }
            is FunkType.VariantType -> {
                val substitutedConstructors = type.constructors.mapValues { (_, constructorType) ->
                    constructorType?.let { applySubstitution(it, substitutions) }
                }
                FunkType.VariantType(substitutedConstructors, type.rowVar)
            }
            is FunkType.ListType -> {
                FunkType.ListType(applySubstitution(type.elementType, substitutions))
            }
            is FunkType.ArrayType -> {
                FunkType.ArrayType(applySubstitution(type.elementType, substitutions))
            }
            is FunkType.SetType -> {
                FunkType.SetType(applySubstitution(type.elementType, substitutions))
            }
            is FunkType.MapType -> {
                FunkType.MapType(
                    applySubstitution(type.keyType, substitutions),
                    applySubstitution(type.valueType, substitutions)
                )
            }
            is FunkType.TypeApplication -> {
                FunkType.TypeApplication(
                    type.constructor,
                    type.args.map { applySubstitution(it, substitutions) }
                )
            }
            is FunkType.SuspendedType -> {
                FunkType.SuspendedType(
                    applySubstitution(type.valueType, substitutions),
                    type.effects
                )
            }
            else -> type // Primitive types unchanged
        }
    }

    /**
     * Apply substitutions to a type (named variant for compatibility).
     *
     * @param type The type to apply substitutions to
     * @param substitutions The map of substitutions to apply
     * @return The type with substitutions applied
     */
    fun applySubstitutionToType(
        type: FunkType,
        substitutions: Map<TypeVar, FunkType>
    ): FunkType {
        return applySubstitution(type, substitutions)
    }

    /**
     * Apply substitutions to an expression (placeholder).
     *
     * This method is a placeholder for expression substitution and currently
     * returns the original expression unchanged.
     *
     * @param expr The expression to apply substitutions to
     * @param substitutions The map of substitutions to apply
     * @return The expression (unchanged)
     */
    fun applySubstitutionsToExpression(
        expr: Expression,
        substitutions: Map<TypeVar, FunkType>
    ): Expression {
        // Placeholder - expression substitution is complex
        return expr
    }

    /**
     * Perform the occurs check for type variable unification.
     *
     * The occurs check prevents creating infinite types like 'a = 'a -> 'b.
     *
     * @param typeVar The type variable to check
     * @param type The type to check for occurrences
     * @param substitutions Current substitutions to apply during check
     * @return true if the type variable occurs in the type, false otherwise
     */
    fun occursCheck(
        typeVar: TypeVar,
        type: FunkType,
        substitutions: Map<TypeVar, FunkType>
    ): Boolean {
        val substitutedType = applySubstitution(type, substitutions)
        return when (substitutedType) {
            is FunkType.VarType -> substitutedType.variable == typeVar
            is FunkType.FunctionType ->
                occursCheck(typeVar, substitutedType.paramType, substitutions) ||
                occursCheck(typeVar, substitutedType.returnType, substitutions)
            is FunkType.RecordType ->
                substitutedType.fields.values.any { occursCheck(typeVar, it, substitutions) }
            is FunkType.VariantType ->
                substitutedType.constructors.values.filterNotNull()
                    .any { occursCheck(typeVar, it, substitutions) }
            is FunkType.ListType ->
                occursCheck(typeVar, substitutedType.elementType, substitutions)
            is FunkType.ArrayType ->
                occursCheck(typeVar, substitutedType.elementType, substitutions)
            is FunkType.SetType ->
                occursCheck(typeVar, substitutedType.elementType, substitutions)
            is FunkType.MapType ->
                occursCheck(typeVar, substitutedType.keyType, substitutions) ||
                occursCheck(typeVar, substitutedType.valueType, substitutions)
            is FunkType.TypeApplication ->
                substitutedType.args.any { occursCheck(typeVar, it, substitutions) }
            is FunkType.SuspendedType ->
                occursCheck(typeVar, substitutedType.valueType, substitutions)
            else -> false
        }
    }

    /**
     * Generalize a type into a type scheme by quantifying free type variables.
     *
     * This implements let-polymorphism, allowing polymorphic bindings like:
     * `let id = fn(x) => x` to have type `∀'a. 'a -> 'a`
     *
     * @param type The type to generalize
     * @return A type scheme with quantified type variables
     */
    override fun generalize(type: FunkType): TypeScheme {
        val freeVars = getFreeTypeVariables(type)

        // Extract ability constraints from FunctionType if present
        val functionConstraints = if (type is FunkType.FunctionType) {
            type.abilityConstraints.flatMap { fc ->
                fc.typeArgs.mapNotNull { typeArg ->
                    if (typeArg is FunkType.VarType) {
                        AbilityConstraint(fc.abilityName, typeArg.variable.name)
                    } else {
                        null
                    }
                }
            }
        } else {
            emptyList()
        }

        // Extract constraints from typeVarAbilityConstraints
        val trackedConstraints = freeVars.flatMap { typeVar ->
            typeVarAbilityConstraints[typeVar]?.map { abilityName ->
                AbilityConstraint(abilityName, typeVar.name)
            } ?: emptyList()
        }

        // Merge and filter constraints
        val allConstraints = (functionConstraints + trackedConstraints)
            .filter { it.abilityName != "Inspect" } // Inspect is universally available
            .distinctBy { it.abilityName to it.typeParam }

        return TypeScheme(freeVars, type, allConstraints)
    }

    /**
     * Instantiate a type scheme with fresh type variables.
     *
     * This creates a fresh instance of a polymorphic type by substituting
     * fresh type variables for quantified variables, and tracking ability
     * constraints on the fresh variables.
     *
     * @param scheme The type scheme to instantiate
     * @return The instantiated type with fresh type variables
     */
    override fun instantiateScheme(scheme: TypeScheme): FunkType {
        if (scheme.isMonomorphic) return scheme.type

        // Create substitution with fresh type variables
        val substitution = scheme.quantifiedVars.associateWith {
            FunkType.VarType(TypeVar.fresh())
        }

        // Track ability constraints on fresh variables
        for (constraint in scheme.constraints) {
            val originalTypeVar = scheme.quantifiedVars.find { it.name == constraint.typeParam }
            if (originalTypeVar != null) {
                val freshTypeVar = substitution[originalTypeVar]
                if (freshTypeVar is FunkType.VarType) {
                    trackAbilityConstraint(freshTypeVar.variable, constraint.abilityName)
                }
            }
        }

        // Apply substitution to type
        return applySubstitution(scheme.type, substitution)
    }

    /**
     * Get all free type variables in a type.
     *
     * Free type variables are type variables that are not bound by any quantifier.
     *
     * @param type The type to analyze
     * @return Set of free type variables
     */
    fun getFreeTypeVariables(type: FunkType): Set<TypeVar> {
        return when (type) {
            is FunkType.VarType -> setOf(type.variable)
            is FunkType.FunctionType -> {
                getFreeTypeVariables(type.paramType) + getFreeTypeVariables(type.returnType)
            }
            is FunkType.RecordType -> {
                type.fields.values.flatMap { getFreeTypeVariables(it) }.toSet()
            }
            is FunkType.VariantType -> {
                type.constructors.values.filterNotNull().flatMap { getFreeTypeVariables(it) }.toSet()
            }
            is FunkType.IntType, is FunkType.FloatType,
            is FunkType.StringType, is FunkType.BooleanType,
            is FunkType.NatType, is FunkType.FracType,
            is FunkType.OrderingType, is FunkType.BytesType -> emptySet()
            is FunkType.EffectRow -> emptySet()
            is FunkType.ArrayType -> getFreeTypeVariables(type.elementType)
            is FunkType.ListType -> getFreeTypeVariables(type.elementType)
            is FunkType.MapType -> getFreeTypeVariables(type.keyType) + getFreeTypeVariables(type.valueType)
            is FunkType.TypeApplication -> type.args.flatMap { getFreeTypeVariables(it) }.toSet()
            is FunkType.SetType -> getFreeTypeVariables(type.elementType)
            is FunkType.SuspendedType -> getFreeTypeVariables(type.valueType)
        }
    }

    /**
     * Check if two types are structurally equal for annotation checking.
     *
     * This is STRICT equality - does NOT allow type variables to match with concrete types.
     * Only allows type variables to match with other type variables (polymorphism).
     *
     * @param type1 The first type
     * @param type2 The second type
     * @return true if types are structurally equal
     */
    override fun areTypesStructurallyEqual(type1: FunkType, type2: FunkType): Boolean {
        return when {
            // Concrete types must match exactly
            type1 is FunkType.IntType && type2 is FunkType.IntType -> true
            type1 is FunkType.FloatType && type2 is FunkType.FloatType -> true
            type1 is FunkType.StringType && type2 is FunkType.StringType -> true
            type1 is FunkType.BooleanType && type2 is FunkType.BooleanType -> true
            type1 is FunkType.NatType && type2 is FunkType.NatType -> true
            type1 is FunkType.FracType && type2 is FunkType.FracType -> true

            // Type variables are compatible only if they have the same variable
            type1 is FunkType.VarType && type2 is FunkType.VarType ->
                type1.variable == type2.variable

            // Function types - recursively check
            type1 is FunkType.FunctionType && type2 is FunkType.FunctionType ->
                areTypesStructurallyEqual(type1.paramType, type2.paramType) &&
                areTypesStructurallyEqual(type1.returnType, type2.returnType)

            // Record types - all fields must match
            type1 is FunkType.RecordType && type2 is FunkType.RecordType ->
                type1.fields.keys == type2.fields.keys &&
                type1.fields.all { (name, t1Type) ->
                    val t2Type = type2.fields[name]
                    t2Type != null && areTypesStructurallyEqual(t1Type, t2Type)
                }

            // Variant types - all constructors must match
            type1 is FunkType.VariantType && type2 is FunkType.VariantType ->
                type1.constructors.keys == type2.constructors.keys &&
                type1.constructors.all { (name, t1Type) ->
                    val t2Type = type2.constructors[name]
                    when {
                        t1Type == null && t2Type == null -> true
                        t1Type != null && t2Type != null -> areTypesStructurallyEqual(t1Type, t2Type)
                        else -> false
                    }
                }

            // List types
            type1 is FunkType.ListType && type2 is FunkType.ListType ->
                areTypesStructurallyEqual(type1.elementType, type2.elementType)

            // Array types
            type1 is FunkType.ArrayType && type2 is FunkType.ArrayType ->
                areTypesStructurallyEqual(type1.elementType, type2.elementType)

            // Set types
            type1 is FunkType.SetType && type2 is FunkType.SetType ->
                areTypesStructurallyEqual(type1.elementType, type2.elementType)

            // Map types
            type1 is FunkType.MapType && type2 is FunkType.MapType ->
                areTypesStructurallyEqual(type1.keyType, type2.keyType) &&
                areTypesStructurallyEqual(type1.valueType, type2.valueType)

            // Type applications
            type1 is FunkType.TypeApplication && type2 is FunkType.TypeApplication ->
                type1.constructor == type2.constructor &&
                type1.args.size == type2.args.size &&
                type1.args.zip(type2.args).all { (t1, t2) ->
                    areTypesStructurallyEqual(t1, t2)
                }

            // Suspended types
            type1 is FunkType.SuspendedType && type2 is FunkType.SuspendedType ->
                areTypesStructurallyEqual(type1.valueType, type2.valueType)

            else -> false
        }
    }

    /**
     * Check if two types are compatible for annotation checking.
     *
     * This is more permissive than structural equality and allows:
     * - Type variables to match with type variables (polymorphism)
     * - Annotated type variables to match with any inferred type
     * - Open records/variants to match with extended versions
     *
     * @param inferredType The inferred type from type inference
     * @param annotatedType The annotated type from user annotation
     * @param varMapping Mutable map to track type variable mappings
     * @return true if types are compatible
     */
    fun typesAreCompatibleForAnnotation(
        inferredType: FunkType,
        annotatedType: FunkType,
        varMapping: MutableMap<TypeVar, TypeVar> = mutableMapOf()
    ): Boolean {
        return when {
            // Both are the same concrete type
            inferredType == annotatedType -> true

            // Both are type variables - track the mapping
            inferredType is FunkType.VarType && annotatedType is FunkType.VarType -> {
                val existing = varMapping[inferredType.variable]
                if (existing != null) {
                    existing == annotatedType.variable
                } else {
                    varMapping[inferredType.variable] = annotatedType.variable
                    true
                }
            }

            // Annotated is type variable - always compatible
            annotatedType is FunkType.VarType -> true

            // Inferred is type variable but annotated is concrete - incompatible
            inferredType is FunkType.VarType && annotatedType !is FunkType.VarType -> false

            // Both are function types
            inferredType is FunkType.FunctionType && annotatedType is FunkType.FunctionType -> {
                typesAreCompatibleForAnnotation(inferredType.paramType, annotatedType.paramType, varMapping) &&
                typesAreCompatibleForAnnotation(inferredType.returnType, annotatedType.returnType, varMapping)
            }

            // Both are record types
            inferredType is FunkType.RecordType && annotatedType is FunkType.RecordType -> {
                // Handle open records
                if (annotatedType.rowVar != null) {
                    // Open record: inferred must have at least the annotated fields
                    annotatedType.fields.all { (name, annType) ->
                        val infType = inferredType.fields[name] ?: return false
                        typesAreCompatibleForAnnotation(infType, annType, varMapping)
                    }
                } else {
                    // Closed record: fields must match exactly
                    if (inferredType.fields.keys != annotatedType.fields.keys) return false
                    inferredType.fields.all { (name, infType) ->
                        val annType = annotatedType.fields[name] ?: return false
                        typesAreCompatibleForAnnotation(infType, annType, varMapping)
                    }
                }
            }

            // Both are variant types
            inferredType is FunkType.VariantType && annotatedType is FunkType.VariantType -> {
                // Handle open variants
                if (annotatedType.isOpen) {
                    // Open variant: any variant is compatible
                    true
                } else {
                    // Closed variant: constructors must match exactly
                    if (inferredType.constructors.keys != annotatedType.constructors.keys) return false
                    inferredType.constructors.all { (name, infType) ->
                        val annType = annotatedType.constructors[name]
                        when {
                            infType == null && annType == null -> true
                            infType != null && annType != null ->
                                typesAreCompatibleForAnnotation(infType, annType, varMapping)
                            else -> false
                        }
                    }
                }
            }

            // Type applications
            inferredType is FunkType.TypeApplication && annotatedType is FunkType.TypeApplication -> {
                inferredType.constructor == annotatedType.constructor &&
                inferredType.args.size == annotatedType.args.size &&
                inferredType.args.zip(annotatedType.args).all { (inf, ann) ->
                    typesAreCompatibleForAnnotation(inf, ann, varMapping)
                }
            }

            // List types
            inferredType is FunkType.ListType && annotatedType is FunkType.ListType ->
                typesAreCompatibleForAnnotation(inferredType.elementType, annotatedType.elementType, varMapping)

            // Array types
            inferredType is FunkType.ArrayType && annotatedType is FunkType.ArrayType ->
                typesAreCompatibleForAnnotation(inferredType.elementType, annotatedType.elementType, varMapping)

            // Set types
            inferredType is FunkType.SetType && annotatedType is FunkType.SetType ->
                typesAreCompatibleForAnnotation(inferredType.elementType, annotatedType.elementType, varMapping)

            // Map types
            inferredType is FunkType.MapType && annotatedType is FunkType.MapType ->
                typesAreCompatibleForAnnotation(inferredType.keyType, annotatedType.keyType, varMapping) &&
                typesAreCompatibleForAnnotation(inferredType.valueType, annotatedType.valueType, varMapping)

            // Suspended types
            inferredType is FunkType.SuspendedType && annotatedType is FunkType.SuspendedType ->
                typesAreCompatibleForAnnotation(inferredType.valueType, annotatedType.valueType, varMapping)

            else -> false
        }
    }

    /**
     * Get the accumulated substitutions from all unifications.
     *
     * @return Map of accumulated substitutions
     */
    fun getAccumulatedSubstitutions(): Map<TypeVar, FunkType> {
        return accumulatedSubstitutions.toMap()
    }

    /**
     * Get the type variable ability constraints.
     *
     * @return Map of type variables to their ability constraints
     */
    fun getTypeVarAbilityConstraints(): Map<TypeVar, Set<String>> {
        return typeVarAbilityConstraints.mapValues { it.value.toSet() }
    }

    /**
     * Clear all accumulated substitutions and ability constraints.
     *
     * This should be called when starting a new type checking session.
     */
    fun clear() {
        typeVarAbilityConstraints.clear()
        accumulatedSubstitutions.clear()
        usageConstraints.clear()
        typeContext.clearConstraints()
    }
}
