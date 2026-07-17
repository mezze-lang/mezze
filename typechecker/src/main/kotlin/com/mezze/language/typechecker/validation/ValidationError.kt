package com.mezze.language.typechecker.validation

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.FixHint
import com.mezze.language.parsedast.diagnostics.LevenshteinSuggest
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.parsedast.*

sealed interface ValidationError {
    fun message(): String

    fun location(): Loc?

    val code: ErrorCode

    val hints: List<String>
        get() = emptyList()

    val fixHints: List<FixHint>
        get() = emptyList()

        data class AbilityNotSatisfied(
        val type: MezzeType,
        val abilityName: String,
        val requiredBy: String? = null,
        val availableAbilities: List<String> = emptyList(),
        val loc: Loc? = null
    ) : ValidationError {
        override val code: ErrorCode = ErrorCode.V0402_ABILITY_NOT_SATISFIED
        override val hints: List<String>
            get() = listOf("implement `impl $abilityName for ${prettyPrintType(type)} where { ... }`")

        override fun message(): String = buildString {
            val typeName = prettyPrintType(type)
            val friendlyAbility = friendlyAbilityName(abilityName)

            append("$typeName cannot be used here because it doesn't support $friendlyAbility")
            if (requiredBy != null) {
                append(" (needed for $requiredBy)")
            }
            append(". To enable this, implement: impl $abilityName for $typeName where { ... }")
        }

        override fun location() = loc

        override val fixHints: List<FixHint>
            get() {
                if (loc == null || availableAbilities.isEmpty()) return emptyList()
                val close = LevenshteinSuggest.closestN(abilityName, availableAbilities, 3)
                return if (close.isNotEmpty())
                    listOf(FixHint.Rename(loc, close))
                else emptyList()
            }

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

        data class NonExhaustiveMatch(
        val expression: Any? = null,
        val coveredConstructors: List<String>,
        val missingConstructors: List<String>,
        val loc: Loc? = null
    ) : ValidationError {
        override val code: ErrorCode = ErrorCode.V0601_NON_EXHAUSTIVE_MATCH
        override val hints: List<String>
            get() = if (missingConstructors.isNotEmpty()) {
                listOf("add a case for: ${missingConstructors.joinToString(", ")}")
            } else {
                emptyList()
            }

        // Only offer a "fill arms" fix when the missing entries are REAL
        // constructor names — the open-variant / wildcard-required cases stash a
        // descriptive placeholder (`_ (…)`) that isn't insertable as an arm.
        override val fixHints: List<FixHint>
            get() {
                val real = missingConstructors.filter { !it.startsWith("_") && !it.contains("(") }
                return if (loc != null && real.isNotEmpty())
                    listOf(FixHint.FillMatchArms(loc, real)) else emptyList()
            }

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

        data class UnreachablePattern(
        val pattern: Any? = null,
        val reason: String,
        val loc: Loc? = null
    ) : ValidationError {
        override val code: ErrorCode = ErrorCode.V0603_UNREACHABLE_PATTERN
        override val hints: List<String>
            get() = listOf("this pattern can never match because an earlier pattern already covers it")

        override fun message(): String = buildString {
            append("Unreachable pattern")
            append(": $reason")
        }

        override fun location() = loc
    }

        data class NonExhaustiveRecordPattern(
        val missingFields: List<String>,
        val pattern: Any? = null,
        val recordType: MezzeType,
        val loc: Loc? = null
    ) : ValidationError {
        override val code: ErrorCode = ErrorCode.V0602_NON_EXHAUSTIVE_RECORD_PATTERN
        override val hints: List<String>
            get() = if (missingFields.isNotEmpty()) {
                listOf("add the missing fields: ${missingFields.joinToString(", ")}")
            } else {
                emptyList()
            }

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

        data class ConstructorNotFound(
        val variantType: MezzeType,
        val constructorName: String,
        val availableConstructors: List<String>,
        val loc: Loc? = null
    ) : ValidationError {
        override val code: ErrorCode = ErrorCode.V0704_UNKNOWN_CONSTRUCTOR

        override fun message(): String = buildString {
            append("'$constructorName' is not a valid constructor for this type")
            if (availableConstructors.isNotEmpty()) {
                append(". ")
                append(StringDistance.suggestOrList(constructorName, availableConstructors, "constructors"))
            }
        }

        override fun location() = loc

        override val fixHints: List<FixHint>
            get() {
                if (loc == null || availableConstructors.isEmpty()) return emptyList()
                val close = LevenshteinSuggest.closestN(constructorName, availableConstructors, 3)
                return if (close.isNotEmpty())
                    listOf(FixHint.Rename(loc, close))
                else emptyList()
            }
    }

        data class AbilityUsedAsValue(
        val abilityName: String,
        val context: String? = null,
        val loc: Loc? = null
    ) : ValidationError {
        override val code: ErrorCode = ErrorCode.V0409_ABILITY_USED_AS_VALUE

        override fun message(): String = buildString {
            append("Ability '$abilityName' cannot be used as a value")
            if (context != null) {
                append(" in $context")
            }
            append(". Did you mean to access a method like $abilityName.method_name?")
        }

        override fun location() = loc
    }

        data class AnnotationMismatch(
        val bindingName: String,
        val annotatedType: MezzeType,
        val inferredType: MezzeType,
        val loc: Loc? = null
    ) : ValidationError {
        override val code: ErrorCode = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH

        override fun message(): String = buildString {
            append("Type annotation for '$bindingName' doesn't match inferred type: ")
            append("annotation says ${prettyPrintType(annotatedType)}, ")
            append("but inferred ${prettyPrintType(inferredType)}")
        }

        override fun location() = loc
    }

        data class MissingAbilityImplementation(
        val abilityName: String,
        val typeName: String,
        val context: String? = null,
        val loc: Loc? = null
    ) : ValidationError {
        override val code: ErrorCode = ErrorCode.V0408_NO_IMPLEMENTATION
        override val hints: List<String>
            get() = listOf("define: `impl $abilityName for $typeName where { ... }`")

        override fun message(): String = buildString {
            append("No implementation found for ability '$abilityName' with type '$typeName'")
            if (context != null) {
                append(" in $context")
            }
            append(". You need to define: impl $abilityName for $typeName where { ... }")
        }

        override fun location() = loc
    }

        data class HandlerTypeMismatch(
        val handlerName: String,
        val effectName: String,
        val expectedType: MezzeType,
        val actualType: MezzeType,
        val loc: Loc? = null
    ) : ValidationError {
        override val code: ErrorCode = ErrorCode.V0501_EFFECT_NOT_HANDLED

        override fun message(): String = buildString {
            append("Handler type mismatch for effect '$effectName':\n")
            append("  Handler '$handlerName' expects '${prettyPrintType(expectedType)}'\n")
            append("  But expression throws '${prettyPrintType(actualType)}'\n\n")
            append("Suggestion: Use a handler that expects ${prettyPrintType(actualType)}, or ensure all ")
            append("throw calls in the expression throw values of type ${prettyPrintType(expectedType)}")
        }

        override fun location() = loc
    }

        data class UnitVariantPayloadMismatch(
        val constructorName: String,
        val variantType: MezzeType,
        val loc: Loc? = null
    ) : ValidationError {
        override val code: ErrorCode = ErrorCode.V0604_CONSTRUCTOR_NO_PAYLOAD

        override fun message(): String = buildString {
            append("Constructor '$constructorName' has no payload, but pattern attempts to destructure it")
        }

        override fun location() = loc
    }

        data class TopLevelPerformedEffects(
        val bindingName: String,
        val effectRow: MezzeType.EffectRow,
        val loc: Loc? = null
    ) : ValidationError {
        override val code: ErrorCode = ErrorCode.V0502_TOP_LEVEL_PERFORM

        override fun message(): String = buildString {
            append("Top-level value '$bindingName' cannot have performed effects <*>. ")
            append("Effects can only be performed inside 'main' or inside functions. ")
            append("Consider wrapping this in a function: let $bindingName = {} -> ...")
        }

        override fun location() = loc
    }

        data class StructuralError(
        val reason: String,
        val loc: Loc? = null
    ) : ValidationError {
        override val code: ErrorCode = ErrorCode.V0901_STRUCTURAL_VALIDATION

        override fun message(): String = reason

        override fun location() = loc
    }

}

sealed class ValidationResult {
    data class Success(
        val warnings: List<String> = emptyList()
    ) : ValidationResult()

    data class Failure(
        val errors: List<ValidationError>
    ) : ValidationResult()

    val isSuccess: Boolean
        get() = this is Success

    val isFailure: Boolean
        get() = this is Failure
}

