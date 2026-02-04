package com.funk.language.typechecker.validation

import com.funk.language.ast.*

/**
 * Validation errors - errors found after type inference.
 *
 * This is PHASE 4 of the new architecture:
 * - PHASE 1: Build context (ContextBuilder)
 * - PHASE 2: Generate constraints (ConstraintGenerator)
 * - PHASE 3: Solve constraints (ConstraintSolver)
 * - PHASE 4: Validate types (TypeValidator) ← WE ARE HERE
 * - PHASE 5: Apply substitution (SubstitutionApplier)
 *
 * Validation catches errors that can only be detected after type inference:
 * - Ability constraints not satisfied (e.g., trying to + on non-numeric types)
 * - Effects not handled (e.g., using IO effect without handler)
 * - Exhaustiveness of pattern matching
 * - Type application validity
 * - Recursive type validity
 *
 * Design:
 * - Sealed ValidationError interface (exhaustive pattern matching!)
 * - Each error type has context and helpful message
 * - No nulls - all optional fields have defaults
 */
sealed interface ValidationError {
    /**
     * Get a human-readable error message.
     */
    fun message(): String

    /**
     * Get source location if available.
     */
    fun location(): SourceLocation?

    /**
     * Ability constraint not satisfied.
     *
     * Example: Trying to use + on String (doesn't have NumAdd ability)
     */
    data class AbilityNotSatisfied(
        val type: FunkType,
        val abilityName: String,
        val requiredBy: String? = null,
        val availableAbilities: List<String> = emptyList(),
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Type ${formatType(type)} does not satisfy ability '$abilityName'")
            if (requiredBy != null) {
                append(" (required by $requiredBy)")
            }
            if (availableAbilities.isNotEmpty()) {
                append(". Available abilities: ${availableAbilities.joinToString()}")
            }
        }

        override fun location() = sourceLocation
    }

    /**
     * Effect not handled.
     *
     * Example: Using IO.readFile without IO handler in scope
     */
    data class EffectNotHandled(
        val effectName: String,
        val usedBy: Expression? = null,
        val availableHandlers: List<String> = emptyList(),
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Effect '$effectName' is not handled")
            if (usedBy != null) {
                append(" in expression")
            }
            if (availableHandlers.isNotEmpty()) {
                append(". Available handlers: ${availableHandlers.joinToString()}")
            }
            append(". Add a handler or propagate the effect in the function signature.")
        }

        override fun location() = sourceLocation
    }

    /**
     * Pattern matching not exhaustive.
     *
     * Example: match x { Some {val} -> ... } missing None case
     */
    data class NonExhaustiveMatch(
        val expression: Expression,
        val coveredConstructors: List<String>,
        val missingConstructors: List<String>,
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Non-exhaustive pattern match")
            if (missingConstructors.isNotEmpty()) {
                append(". Missing cases: ${missingConstructors.joinToString()}")
            }
            if (coveredConstructors.isNotEmpty()) {
                append(". Covered cases: ${coveredConstructors.joinToString()}")
            }
        }

        override fun location() = sourceLocation
    }

    /**
     * Record pattern not exhaustive - missing fields without ellipsis.
     *
     * Example: let {x, y} = {x = 1, y = 2, z = 3} (missing z, no ...)
     */
    data class NonExhaustiveRecordPattern(
        val missingFields: List<String>,
        val pattern: RecordPattern,
        val recordType: FunkType,
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Non-exhaustive record pattern")
            if (missingFields.isNotEmpty()) {
                append(". Missing fields: ${missingFields.joinToString()}")
            }
            append(". Use {...} to explicitly ignore remaining fields")
        }

        override fun location() = sourceLocation
    }

    /**
     * Type application invalid.
     *
     * Example: Option Int String (Option takes 1 arg, given 2)
     */
    data class InvalidTypeApplication(
        val constructor: String,
        val expectedArity: Int,
        val actualArity: Int,
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String {
            return "Type constructor '$constructor' expects $expectedArity type arguments, but got $actualArity"
        }

        override fun location() = sourceLocation
    }

    /**
     * Recursive type without base case.
     *
     * Example: type List 'a = List 'a (infinite recursion!)
     */
    data class InvalidRecursiveType(
        val typeName: String,
        val reason: String,
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String {
            return "Invalid recursive type '$typeName': $reason"
        }

        override fun location() = sourceLocation
    }

    /**
     * Field does not exist on record type.
     *
     * Example: user.age when user only has {name: String}
     */
    data class FieldNotFound(
        val recordType: FunkType,
        val fieldName: String,
        val availableFields: List<String>,
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Field '$fieldName' not found on type ${formatType(recordType)}")
            if (availableFields.isNotEmpty()) {
                append(". Available fields: ${availableFields.joinToString()}")
            }
        }

        override fun location() = sourceLocation
    }

    /**
     * Constructor does not exist on variant type.
     *
     * Example: Some {val = x} when type is Result (doesn't have Some)
     */
    data class ConstructorNotFound(
        val variantType: FunkType,
        val constructorName: String,
        val availableConstructors: List<String>,
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Constructor '$constructorName' not found on type ${formatType(variantType)}")
            if (availableConstructors.isNotEmpty()) {
                append(". Available constructors: ${availableConstructors.joinToString()}")
            }
        }

        override fun location() = sourceLocation
    }

    /**
     * Variable not in scope.
     *
     * Example: Using variable x before it's defined
     */
    data class VariableNotInScope(
        val variableName: String,
        val similarNames: List<String> = emptyList(),
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Undefined variable or function: $variableName")
            if (similarNames.isNotEmpty()) {
                append(". Did you mean: ${similarNames.joinToString()}")
            }
        }

        override fun location() = sourceLocation
    }

    /**
     * Built-in ability used as a value.
     *
     * Example: let x = NumAdd (should be NumAdd.add)
     */
    data class AbilityUsedAsValue(
        val abilityName: String,
        val context: String? = null,
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Ability '$abilityName' cannot be used as a value")
            if (context != null) {
                append(" in $context")
            }
            append(". Did you mean to access a method like $abilityName.method_name?")
        }

        override fun location() = sourceLocation
    }

    /**
     * Type mismatch (caught during validation, not solving).
     *
     * Example: Trying to pass {x: Int} to function expecting {x: Int, y: String}
     */
    data class TypeMismatch(
        val expected: FunkType,
        val actual: FunkType,
        val context: String? = null,
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Type mismatch")
            if (context != null) {
                append(" in $context")
            }
            append(": expected ${formatType(expected)}, but got ${formatType(actual)}")
        }

        override fun location() = sourceLocation
    }

    /**
     * Type annotation doesn't match inferred type.
     *
     * Example: let x: Int = "hello" (annotation says Int, but inferred String)
     */
    data class AnnotationMismatch(
        val bindingName: String,
        val annotatedType: FunkType,
        val inferredType: FunkType,
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Type annotation for '$bindingName' doesn't match inferred type: ")
            append("annotation says ${prettyPrintType(annotatedType)}, ")
            append("but inferred ${prettyPrintType(inferredType)}")
        }

        override fun location() = sourceLocation
    }

    /**
     * Circular dependency in type definitions.
     *
     * Example: type A = B; type B = A
     */
    data class CircularDependency(
        val typeNames: List<String>,
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String {
            return "Circular dependency in type definitions: ${typeNames.joinToString(" -> ")}"
        }

        override fun location() = sourceLocation
    }

    /**
     * Orphan instance (ability implementation for type from different module).
     *
     * Example: impl Eq String in user code (String is built-in)
     */
    data class OrphanInstance(
        val abilityName: String,
        val typeName: String,
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String {
            return "Orphan instance: implementing ability '$abilityName' for type '$typeName' from different module. " +
                   "Implement in the module that defines either the ability or the type."
        }

        override fun location() = sourceLocation
    }

    /**
     * Overlapping instances (same ability implemented multiple times for same type).
     *
     * Example: impl Eq Int twice
     */
    data class OverlappingInstance(
        val abilityName: String,
        val typeName: String,
        val existingLocation: SourceLocation? = null,
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Overlapping instance: ability '$abilityName' already implemented for type '$typeName'")
            if (existingLocation != null) {
                append(" at ${existingLocation}")
            }
        }

        override fun location() = sourceLocation
    }

    /**
     * Missing ability implementation.
     *
     * Example: Show.show {val = 42} when there's no impl Show Int
     */
    data class MissingAbilityImplementation(
        val abilityName: String,
        val typeName: String,
        val context: String? = null,
        val sourceLocation: SourceLocation? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("No implementation found for ability '$abilityName' with type '$typeName'")
            if (context != null) {
                append(" in $context")
            }
            append(". You need to define: impl $abilityName $typeName where { ... }")
        }

        override fun location() = sourceLocation
    }
}

/**
 * Result of validation.
 *
 * Sealed type - exhaustive pattern matching, no nulls!
 */
sealed class ValidationResult {
    /**
     * Validation succeeded.
     */
    data class Success(
        val warnings: List<String> = emptyList()
    ) : ValidationResult()

    /**
     * Validation failed with errors.
     */
    data class Failure(
        val errors: List<ValidationError>
    ) : ValidationResult()

    /**
     * Check if validation succeeded.
     */
    val isSuccess: Boolean
        get() = this is Success

    /**
     * Check if validation failed.
     */
    val isFailure: Boolean
        get() = this is Failure
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
        is FunkType.RecordType -> "{${type.fields.keys.take(3).joinToString()}}"
        is FunkType.VariantType -> "[${type.constructors.keys.take(3).joinToString()}]"
        is FunkType.TypeApplication -> "${type.constructor}${if (type.args.isNotEmpty()) " ..." else ""}"
        is FunkType.ArrayType -> "Array ${formatType(type.elementType)}"
        is FunkType.ListType -> "List ${formatType(type.elementType)}"
        is FunkType.SetType -> "Set ${formatType(type.elementType)}"
        is FunkType.MapType -> "Map ${formatType(type.keyType)} ${formatType(type.valueType)}"
        is FunkType.SuspendedType -> "Suspended<${formatType(type.valueType)}>"
        is FunkType.EffectRow -> "<${type.effects.take(3).joinToString()}>"
    }
}
