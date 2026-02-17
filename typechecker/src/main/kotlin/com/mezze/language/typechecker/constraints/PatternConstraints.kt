package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType

/**
 * Pattern constraint generator - generates constraints for pattern matching.
 *
 * Patterns appear in:
 * - Let bindings: let pattern = expr
 * - Match cases: match expr { pattern -> result }
 * - Lambda parameters: {pattern} -> body
 *
 * For each pattern, we:
 * 1. Generate constraints on the scrutinee type
 * 2. Extract variable bindings introduced by the pattern
 * 3. Return PatternConstraintResult with bindings + constraints
 *
 * Design:
 * - Pure functions (no mutation!)
 * - Exhaustive when on Pattern sealed interface
 * - No catch-all else clauses
 */
class PatternConstraintGenerator {

    /**
     * Generate constraints for a pattern.
     *
     * @param pattern The pattern to analyze
     * @param scrutineeType The type of the value being matched
     * @param context The constraint generation context
     * @param sourceLocation Optional source location for error reporting (from scrutinee expression)
     * @return Bindings introduced + constraints on scrutinee
     */
    fun generate(
        pattern: Pattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLocation: SourceLocation? = null
    ): PatternConstraintResult {
        return when (pattern) {
            is WildcardPattern -> generateWildcard(pattern, scrutineeType, context)
            is VariablePattern -> generateVariable(pattern, scrutineeType, context)
            is ConstructorPattern -> generateConstructor(pattern, scrutineeType, context, sourceLocation)
            is RecordPattern -> generateRecord(pattern, scrutineeType, context, sourceLocation)
            is LiteralPattern -> generateLiteral(pattern, scrutineeType, context)
            is ArrayPattern -> generateArray(pattern, scrutineeType, context, sourceLocation)
        }
    }

    // ========================================================================
    // SIMPLE PATTERNS
    // ========================================================================

    private fun generateWildcard(
        pattern: WildcardPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext
    ): PatternConstraintResult {
        // Wildcard pattern _ matches anything, introduces no bindings
        return PatternConstraintResult(
            bindings = emptyMap(),
            constraints = emptyList()
        )
    }

    private fun generateVariable(
        pattern: VariablePattern,
        scrutineeType: MezzeType,
        context: ConstraintContext
    ): PatternConstraintResult {
        // Variable pattern binds the scrutinee to a name
        // Example: let x = expr  (x gets type of expr)
        return PatternConstraintResult(
            bindings = mapOf(pattern.name to scrutineeType),
            constraints = emptyList()
        )
    }

    private fun generateLiteral(
        pattern: LiteralPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext
    ): PatternConstraintResult {
        // Literal pattern must match the literal type
        // Example: match x { 42 -> ... }

        val literalType = when (val literalValue = pattern.value) {
            is IntLiteralValue -> MezzeType.IntType
            is NatLiteralValue -> MezzeType.NatType
            is FloatLiteralValue -> MezzeType.FloatType
            is BooleanLiteralValue -> MezzeType.VariantType(
                constructors = mapOf("True" to null, "False" to null),
                rowVar = null
            )
            is StringLiteralValue -> MezzeType.StringType
        }

        // Scrutinee must have same type as literal
        val constraints = listOf(
            Constraint.Equality(
                left = scrutineeType,
                right = literalType,
                context = "Literal pattern must match scrutinee type"
            )
        )

        return PatternConstraintResult(
            bindings = emptyMap(),
            constraints = constraints
        )
    }

    // ========================================================================
    // CONSTRUCTOR PATTERNS
    // ========================================================================

    private fun generateConstructor(
        pattern: ConstructorPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLocation: SourceLocation? = null
    ): PatternConstraintResult {
        // Constructor pattern: Some {val = x}
        // Scrutinee must be variant type with this constructor

        val recordPattern = pattern.recordPattern

        if (recordPattern == null) {
            // No payload: constructor without data
            // Example: None
            val constraints = listOf(
                Constraint.HasConstructor(
                    variantType = scrutineeType,
                    constructorName = pattern.constructor,
                    payloadType = null,
                    context = "Constructor pattern ${pattern.constructor}",
                    isPatternContext = true  // This is a pattern context - variant should be closed
                )
            )

            return PatternConstraintResult(
                bindings = emptyMap(),
                constraints = constraints
            )
        } else {
            // With payload: constructor with data
            // Example: Some {val = x}

            val payloadType = MezzeType.VarType(TypeVar.fresh())

            // Scrutinee must have this constructor with payload type
            val constraints = mutableListOf<Constraint>(
                Constraint.HasConstructor(
                    variantType = scrutineeType,
                    constructorName = pattern.constructor,
                    payloadType = payloadType,
                    context = "Constructor pattern ${pattern.constructor}",
                    isPatternContext = true  // This is a pattern context - variant should be closed
                )
            )

            // Generate constraints for payload pattern (which is a RecordPattern)
            val payloadResult = generate(recordPattern, payloadType, context, sourceLocation)
            constraints.addAll(payloadResult.constraints)

            return PatternConstraintResult(
                bindings = payloadResult.bindings,
                constraints = constraints
            )
        }
    }

    // ========================================================================
    // RECORD PATTERNS
    // ========================================================================

    private fun generateRecord(
        pattern: RecordPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLocation: SourceLocation? = null
    ): PatternConstraintResult {
        // Record pattern: {x, y = z, ...}
        // Scrutinee must be record type with these fields

        val bindings = mutableMapOf<String, MezzeType>()
        val constraints = mutableListOf<Constraint>()

        // STRICT RECORD DESTRUCTURING: If pattern is not open (no ...), all fields must be covered
        // Matches OldTypeChecker.kt lines 11776-11790
        if (!pattern.isOpen && scrutineeType is MezzeType.RecordType) {
            val patternFieldNames = pattern.entries.map { it.name }.toSet()
            val recordFieldNames = scrutineeType.fields.keys
            val uncoveredFields = recordFieldNames - patternFieldNames

            if (uncoveredFields.isNotEmpty()) {
                val fieldList = uncoveredFields.sorted().joinToString(", ")
                throw TypeCheckError(
                    "Non-exhaustive record pattern: missing field${if (uncoveredFields.size > 1) "s" else ""} $fieldList. " +
                    "Use { ${patternFieldNames.joinToString(", ")}, ... } to ignore extra fields.",
                    sourceLocation
                )
            }
        }

        for (entry in pattern.entries) {
            val fieldType = MezzeType.VarType(TypeVar.fresh())

            // Scrutinee must have this field
            constraints.add(
                Constraint.HasField(
                    recordType = scrutineeType,
                    fieldName = entry.name,
                    fieldType = fieldType,
                    context = "Record pattern field ${entry.name}"
                )
            )

            // Generate constraints for the field pattern
            val fieldPattern = entry.pattern ?: VariablePattern(entry.name)
            val fieldResult = generate(fieldPattern, fieldType, context, sourceLocation)

            bindings.putAll(fieldResult.bindings)
            constraints.addAll(fieldResult.constraints)
        }

        return PatternConstraintResult(
            bindings = bindings,
            constraints = constraints
        )
    }

    // ========================================================================
    // ARRAY PATTERNS
    // ========================================================================

    private fun generateArray(
        pattern: ArrayPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLocation: SourceLocation? = null
    ): PatternConstraintResult {
        // Array pattern: [x, y, z] or [head, ...tail]
        // Scrutinee must be array type

        val elementType = MezzeType.VarType(TypeVar.fresh())
        val bindings = mutableMapOf<String, MezzeType>()
        val constraints = mutableListOf<Constraint>()

        // Scrutinee must be Array<elementType>
        constraints.add(
            Constraint.Equality(
                left = scrutineeType,
                right = MezzeType.ArrayType(elementType),
                context = "Array pattern requires array type"
            )
        )

        // Generate constraints for each element pattern
        for (elementPattern in pattern.elements) {
            val elementResult = generate(elementPattern, elementType, context, sourceLocation)
            bindings.putAll(elementResult.bindings)
            constraints.addAll(elementResult.constraints)
        }

        // CRITICAL FIX: Handle rest pattern (...tail)
        // The rest pattern binds to an array of the remaining elements
        // Example: [head, ...tail] binds tail to Array<elementType>
        val restPatternName = pattern.restPattern
        if (restPatternName != null) {
            bindings[restPatternName] = MezzeType.ArrayType(elementType)
        }

        return PatternConstraintResult(
            bindings = bindings,
            constraints = constraints
        )
    }
}
