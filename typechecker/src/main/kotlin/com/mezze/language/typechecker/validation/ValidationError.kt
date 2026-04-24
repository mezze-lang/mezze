package com.mezze.language.typechecker.validation

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.typechecker.util.StringDistance

/**
 * Validation errors - errors found after type inference.
 *
 * This is PHASE 4 of the new architecture:
 * - PHASE 1: Build context (ContextBuilder)
 * - PHASE 2: Generate constraints (ConstraintGenerator)
 * - PHASE 3: Solve constraints (ConstraintSolver)
 * - PHASE 4: Emit TypedAST + V2 Pipeline (TypedAstBuilder, Monomorphizer, Resolver, Lowerer)
 * - PHASE 5: Validate types (TypeValidator, V2Validator) ← WE ARE HERE
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
    fun location(): Loc?

    /**
     * Ability constraint not satisfied.
     *
     * Example: Trying to use + on String (doesn't have NumAdd ability)
     */
    data class AbilityNotSatisfied(
        val type: MezzeType,
        val abilityName: String,
        val requiredBy: String? = null,
        val availableAbilities: List<String> = emptyList(),
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            val typeName = formatType(type)
            val friendlyAbility = friendlyAbilityName(abilityName)

            append("$typeName cannot be used here because it doesn't support $friendlyAbility")
            if (requiredBy != null) {
                append(" (needed for $requiredBy)")
            }
            append(". To enable this, implement: impl $abilityName for $typeName where { ... }")
        }

        override fun location() = loc

        private fun friendlyAbilityName(name: String): String = when (name) {
            "NumAdd" -> "addition (+)"
            "NumSub" -> "subtraction (-)"
            "NumMul" -> "multiplication (*)"
            "NumDiv" -> "division (/)"
            "NumMod" -> "modulo (%)"
            "Eq" -> "equality comparison (==)"
            "Ord" -> "ordering comparison (<, >)"
            "Show" -> "string conversion"
            "Hash" -> "hashing"
            "Default" -> "default values"
            else -> "the '$name' ability"
        }
    }

    /**
     * Effect not handled.
     *
     * Example: Using IO.readFile without IO handler in scope
     */
    data class EffectNotHandled(
        val effectName: String,
        @Deprecated("Unused — was for v1 PerformExpression") val usedBy: Any? = null,
        val availableHandlers: List<String> = emptyList(),
        val loc: Loc? = null
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

        override fun location() = loc
    }

    /**
     * Pattern matching not exhaustive.
     *
     * Example: match x { Some {val} -> ... } missing None case
     */
    data class NonExhaustiveMatch(
        val expression: Any? = null,
        val coveredConstructors: List<String>,
        val missingConstructors: List<String>,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("This match expression doesn't handle all cases")
            if (missingConstructors.isNotEmpty()) {
                val cases = if (missingConstructors.size == 1) "case" else "cases"
                append(". Missing $cases: ${missingConstructors.joinToString(", ")}")
            }
            append(". Add the missing patterns or use `_ ->` as a catch-all.")
        }

        override fun location() = loc
    }

    /**
     * Unreachable pattern - pattern can never be matched because previous patterns cover all cases.
     *
     * Example: match y is true -> 1 | false -> 0 | _ -> -1 (wildcard unreachable after true/false)
     */
    data class UnreachablePattern(
        val pattern: Any? = null,
        val reason: String,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Unreachable pattern")
            append(": $reason")
        }

        override fun location() = loc
    }

    /**
     * Record pattern not exhaustive - missing fields without ellipsis.
     *
     * Example: let {x, y} = {x = 1, y = 2, z = 3} (missing z, no ...)
     */
    data class NonExhaustiveRecordPattern(
        val missingFields: List<String>,
        val pattern: Any? = null,
        val recordType: MezzeType,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Record pattern is missing fields")
            if (missingFields.isNotEmpty()) {
                val fields = if (missingFields.size == 1) "field" else "fields"
                append(": ${missingFields.joinToString(", ")}")
            }
            append(". Either add the missing fields or use `{..}` to ignore extra fields.")
        }

        override fun location() = loc
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
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String {
            return "Type constructor '$constructor' expects $expectedArity type arguments, but got $actualArity"
        }

        override fun location() = loc
    }

    /**
     * Recursive type without base case.
     *
     * Example: type List 'a = List 'a (infinite recursion!)
     */
    data class InvalidRecursiveType(
        val typeName: String,
        val reason: String,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String {
            return "Invalid recursive type '$typeName': $reason"
        }

        override fun location() = loc
    }

    /**
     * Field does not exist on record type.
     *
     * Example: user.age when user only has {name: String}
     */
    data class FieldNotFound(
        val recordType: MezzeType,
        val fieldName: String,
        val availableFields: List<String>,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("'$fieldName' is not a field of this record")
            if (availableFields.isNotEmpty()) {
                append(". ")
                append(StringDistance.suggestOrList(fieldName, availableFields, "fields"))
            }
        }

        override fun location() = loc
    }

    /**
     * Constructor does not exist on variant type.
     *
     * Example: Some {val = x} when type is Result (doesn't have Some)
     */
    data class ConstructorNotFound(
        val variantType: MezzeType,
        val constructorName: String,
        val availableConstructors: List<String>,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("'$constructorName' is not a valid constructor for this type")
            if (availableConstructors.isNotEmpty()) {
                append(". ")
                append(StringDistance.suggestOrList(constructorName, availableConstructors, "constructors"))
            }
        }

        override fun location() = loc
    }

    /**
     * Variable not in scope.
     *
     * Example: Using variable x before it's defined
     */
    data class VariableNotInScope(
        val variableName: String,
        val similarNames: List<String> = emptyList(),
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("'$variableName' is not defined")
            if (similarNames.isNotEmpty()) {
                val suggestions = similarNames.take(3).joinToString(", ") { "'$it'" }
                append(". Did you mean $suggestions?")
            } else {
                append(". Check the spelling or make sure it's declared before use.")
            }
        }

        override fun location() = loc
    }

    /**
     * Built-in ability used as a value.
     *
     * Example: let x = NumAdd (should be NumAdd.add)
     */
    data class AbilityUsedAsValue(
        val abilityName: String,
        val context: String? = null,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Ability '$abilityName' cannot be used as a value")
            if (context != null) {
                append(" in $context")
            }
            append(". Did you mean to access a method like $abilityName.method_name?")
        }

        override fun location() = loc
    }

    /**
     * Type mismatch (caught during validation, not solving).
     *
     * Example: Trying to pass {x: Int} to function expecting {x: Int, y: String}
     */
    data class TypeMismatch(
        val expected: MezzeType,
        val actual: MezzeType,
        val context: String? = null,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Type mismatch")
            if (context != null) {
                append(" in $context")
            }
            append(": expected ${formatType(expected)}, but got ${formatType(actual)}")
        }

        override fun location() = loc
    }

    /**
     * Type annotation doesn't match inferred type.
     *
     * Example: let x: Int = "hello" (annotation says Int, but inferred String)
     */
    data class AnnotationMismatch(
        val bindingName: String,
        val annotatedType: MezzeType,
        val inferredType: MezzeType,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Type annotation for '$bindingName' doesn't match inferred type: ")
            append("annotation says ${prettyPrintType(annotatedType)}, ")
            append("but inferred ${prettyPrintType(inferredType)}")
        }

        override fun location() = loc
    }

    /**
     * Circular dependency in type definitions.
     *
     * Example: type A = B; type B = A
     */
    data class CircularDependency(
        val typeNames: List<String>,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String {
            return "Circular dependency in type definitions: ${typeNames.joinToString(" -> ")}"
        }

        override fun location() = loc
    }

    /**
     * Orphan instance (ability implementation for type from different module).
     *
     * Example: impl Eq for String in user code (String is built-in)
     */
    data class OrphanInstance(
        val abilityName: String,
        val typeName: String,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String {
            return "Orphan instance: implementing ability '$abilityName' for type '$typeName' from different module. " +
                   "Implement in the module that defines either the ability or the type."
        }

        override fun location() = loc
    }

    /**
     * Overlapping instances (same ability implemented multiple times for same type).
     *
     * Example: impl Eq for Int twice
     */
    data class OverlappingInstance(
        val abilityName: String,
        val typeName: String,
        val existingLocation: Loc? = null,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Overlapping instance: ability '$abilityName' already implemented for type '$typeName'")
            if (existingLocation != null) {
                append(" at ${existingLocation}")
            }
        }

        override fun location() = loc
    }

    /**
     * Missing ability implementation.
     *
     * Example: 42.show {} when there's no impl Show for Int
     */
    data class MissingAbilityImplementation(
        val abilityName: String,
        val typeName: String,
        val context: String? = null,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("No implementation found for ability '$abilityName' with type '$typeName'")
            if (context != null) {
                append(" in $context")
            }
            append(". You need to define: impl $abilityName for $typeName where { ... }")
        }

        override fun location() = loc
    }

    /**
     * Handler type mismatch in perform expression.
     *
     * Example: Handler expects String error type but expression throws ValidationError
     */
    data class HandlerTypeMismatch(
        val handlerName: String,
        val effectName: String,
        val expectedType: MezzeType,
        val actualType: MezzeType,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Handler type mismatch for effect '$effectName':\n")
            append("  Handler '$handlerName' expects '${formatType(expectedType)}'\n")
            append("  But expression throws '${formatType(actualType)}'\n\n")
            append("Suggestion: Use a handler that expects ${formatType(actualType)}, or ensure all ")
            append("throw calls in the expression throw values of type ${formatType(expectedType)}")
        }

        override fun location() = loc
    }

    /**
     * Unit variant (no payload) used with a payload pattern.
     *
     * Example: match color is Blue { val } -> ... when Blue has no payload
     */
    data class UnitVariantPayloadMismatch(
        val constructorName: String,
        val variantType: MezzeType,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Constructor '$constructorName' has no payload, but pattern attempts to destructure it")
        }

        override fun location() = loc
    }

    /**
     * Top-level value has performed effects (<*>).
     *
     * Top-level values (non-functions) cannot have performed effects because
     * that would mean executing effects at module load time.
     *
     * Example: let x: Int <*> = perform Console in ... (NOT ALLOWED)
     */
    data class TopLevelPerformedEffects(
        val bindingName: String,
        val effectRow: MezzeType.EffectRow,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Top-level value '$bindingName' cannot have performed effects <*>. ")
            append("Effects can only be performed inside 'main' or inside functions. ")
            append("Consider wrapping this in a function: let $bindingName = {} -> ...")
        }

        override fun location() = loc
    }

    /**
     * Top-level expression has performed effects (<*>).
     *
     * Top-level expressions (bare function calls, etc.) cannot have performed effects
     * because that would mean executing effects at module load time without assignment.
     *
     * Example: myEffectfulFunc {}  (NOT ALLOWED at top level)
     */
    data class TopLevelExpressionPerformedEffects(
        val effectRow: MezzeType.EffectRow,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Bare expression with performed effects <*> is not allowed at module level. ")
            append("This would execute effects when the module loads, which is not permitted. ")
            append("Move this expression inside a function body.")
        }

        override fun location() = loc
    }

    /**
     * main function has wrong type.
     *
     * main must have type {} -> {} <*> (takes unit, returns unit with performed effects).
     *
     * Example: main = {} -> 42 (returns Int, not {})
     * Example: main = {} -> {} (pure, should have <*>)
     * Example: main = {} -> Log.println {...} (has <Log>, should use perform)
     */
    data class MainFunctionTypeError(
        val actualType: MezzeType,
        val reason: String,
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("'main' must have type {} -> {} <*>, but ")
            append(reason)
            append(". The main function should perform all effects using 'perform'.")
        }

        override fun location() = loc
    }

    /**
     * Missing content hash on identifier.
     *
     * This is an internal compiler bug - identifiers should always have
     * their resolvedContentHash populated after typechecking (for top-level/imported bindings).
     *
     * Example: Identifier `foo` has no resolvedContentHash
     */
    data class MissingContentHash(
        val identifierName: String,
        val kind: String,  // "identifier", "ability call", "effect handler", etc.
        val loc: Loc? = null
    ) : ValidationError {
        override fun message(): String = buildString {
            append("Internal error: $kind '$identifierName' has no content hash. ")
            append("This is a bug in the typechecker - content hashes should be populated for all top-level/imported bindings.")
        }

        override fun location() = loc
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
 * Delegates to prettyPrintType from TypePrinter.kt.
 */
private fun formatType(type: MezzeType): String = prettyPrintType(type)
