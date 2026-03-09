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
            is LiteralPattern -> generateLiteral(pattern, scrutineeType, context, sourceLocation)
            is SeqPattern -> generateSeq(pattern, scrutineeType, context, sourceLocation)
            is StringInterpolationPattern -> generateStringInterpolation(pattern, scrutineeType, context, sourceLocation)
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
            constraints = emptyList(),
            bindingLocations = pattern.sourceLocation?.let { mapOf(pattern.name to it) } ?: emptyMap()
        )
    }

    private fun generateLiteral(
        pattern: LiteralPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLocation: SourceLocation? = null
    ): PatternConstraintResult {
        // Literal pattern must match the literal type
        // Example: match x { 42 -> ... }

        val literalValue = pattern.value

        // Early detection: Check if scrutinee is a variant type (for better error messages)
        // This catches cases like trying to match a Color variant with an integer literal
        if (scrutineeType is MezzeType.VariantType && literalValue !is BooleanLiteralValue) {
            val constructors = scrutineeType.constructors.keys.sorted()
            val literalDesc = describeLiteral(literalValue)
            val firstConstructor = constructors.firstOrNull() ?: "ConstructorName"
            throw TypeCheckError(
                "Cannot match variant type [${constructors.joinToString(", ")}] with $literalDesc. " +
                "Use a constructor pattern like '$firstConstructor' instead, " +
                "or use a variable pattern to catch all cases.",
                sourceLocation,
                errorType = ErrorType.INVALID_PATTERN
            )
        }

        // Early detection: Check if scrutinee is a TypeApplication (named variant type)
        if (scrutineeType is MezzeType.TypeApplication && literalValue !is BooleanLiteralValue) {
            val literalDesc = describeLiteral(literalValue)
            throw TypeCheckError(
                "Cannot match type '${scrutineeType.constructor}' with $literalDesc. " +
                "Use a constructor pattern instead, or use a variable pattern to catch all cases.",
                sourceLocation,
                errorType = ErrorType.INVALID_PATTERN
            )
        }

        val warnings = mutableListOf<String>()

        val literalType = when (literalValue) {
            is IntLiteralValue -> MezzeType.IntType
            is NatLiteralValue -> MezzeType.NatType
            is FloatLiteralValue -> {
                // Warn about float literal patterns - floating point comparison is imprecise
                val locationInfo = sourceLocation?.let { " at line ${it.line}" } ?: ""
                warnings.add(
                    "Float literal pattern '${literalValue.value}'$locationInfo may not match due to " +
                    "floating-point precision issues. Consider using a range check or rounding instead."
                )
                MezzeType.FloatType
            }
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
                context = "Literal pattern must match scrutinee type",
                sourceLocation = sourceLocation
            )
        )

        return PatternConstraintResult(
            bindings = emptyMap(),
            constraints = constraints,
            warnings = warnings
        )
    }

    /**
     * Describe a literal value for error messages.
     */
    private fun describeLiteral(value: LiteralValue): String = when (value) {
        is IntLiteralValue -> "integer literal '${value.value}'"
        is NatLiteralValue -> "natural number literal '${value.value}'"
        is FloatLiteralValue -> "float literal '${value.value}'"
        is StringLiteralValue -> "string literal \"${value.value}\""
        is BooleanLiteralValue -> "boolean literal '${value.value}'"
    }

    // ========================================================================
    // STRING INTERPOLATION PATTERNS
    // ========================================================================

    /**
     * Generate constraints for a string interpolation pattern.
     *
     * String interpolation patterns match strings with embedded captures:
     * - "prefix-$(name)-suffix" captures the middle portion as `name`
     * - "$(greeting), $(target)!" captures two portions
     *
     * Constraints:
     * - Scrutinee must be of type String
     * - All captured variables are bound as String type
     *
     * This is a refutable pattern - it only matches if the literal parts match.
     */
    private fun generateStringInterpolation(
        pattern: StringInterpolationPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLocation: SourceLocation? = null
    ): PatternConstraintResult {
        val bindings = mutableMapOf<String, MezzeType>()
        val constraints = mutableListOf<Constraint>()

        // Early detection: Check if scrutinee is a variant type (for better error messages)
        if (scrutineeType is MezzeType.VariantType) {
            val constructors = scrutineeType.constructors.keys.sorted()
            throw TypeCheckError(
                "Cannot match variant type [${constructors.joinToString(", ")}] with a string interpolation pattern. " +
                "Use a constructor pattern like '${constructors.firstOrNull() ?: "ConstructorName"}' instead.",
                sourceLocation ?: pattern.sourceLocation,
                errorType = ErrorType.INVALID_PATTERN
            )
        }

        // Early detection: Check if scrutinee is a TypeApplication (named type)
        if (scrutineeType is MezzeType.TypeApplication) {
            throw TypeCheckError(
                "Cannot match type '${scrutineeType.constructor}' with a string interpolation pattern. " +
                "String patterns can only match String type values.",
                sourceLocation ?: pattern.sourceLocation,
                errorType = ErrorType.INVALID_PATTERN
            )
        }

        // Scrutinee must be String type
        constraints.add(
            Constraint.Equality(
                left = scrutineeType,
                right = MezzeType.StringType,
                context = "String interpolation pattern requires String type",
                sourceLocation = sourceLocation ?: pattern.sourceLocation
            )
        )

        // Extract bindings from capture parts
        // Each capture binds its variable name to String type
        for (part in pattern.parts) {
            when (part) {
                is StringPatternCapture -> {
                    // Each capture variable is bound to String type
                    bindings[part.variableName] = MezzeType.StringType
                }
                is StringPatternLiteral -> {
                    // Literal parts don't introduce bindings
                }
            }
        }

        return PatternConstraintResult(
            bindings = bindings,
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
                    sourceLocation = sourceLocation,
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
                    sourceLocation = sourceLocation,
                    isPatternContext = true  // This is a pattern context - variant should be closed
                )
            )

            // Generate constraints for payload pattern (which is a RecordPattern)
            val payloadResult = generate(recordPattern, payloadType, context, sourceLocation)
            constraints.addAll(payloadResult.constraints)

            return PatternConstraintResult(
                bindings = payloadResult.bindings,
                constraints = constraints,
                bindingLocations = payloadResult.bindingLocations
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
        val bindingLocations = mutableMapOf<String, SourceLocation>()
        val constraints = mutableListOf<Constraint>()

        // STRICT RECORD DESTRUCTURING: If pattern is not open (no ...), all fields must be covered
        // We generate a RecordPatternExhaustive constraint that will be validated at solving time.
        // This handles both cases: when scrutineeType is concrete now OR when it's a type variable
        // that will be resolved later.
        if (!pattern.isOpen) {
            val patternFieldNames = pattern.entries.map { it.name }.toSet()

            // If we already know the type, check immediately for better error messages
            if (scrutineeType is MezzeType.RecordType) {
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
            } else {
                // Type is not yet known - add a constraint for deferred checking
                constraints.add(
                    Constraint.RecordPatternExhaustive(
                        recordType = scrutineeType,
                        patternFields = patternFieldNames,
                        context = "Closed record pattern requires all fields to be matched",
                        sourceLocation = sourceLocation
                    )
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
                    context = "Record pattern field ${entry.name}",
                    sourceLocation = sourceLocation
                )
            )

            // Generate constraints for the field pattern
            // For punning {n}, entry.pattern is null - use entry's source location
            val fieldPattern = entry.pattern ?: VariablePattern(entry.name, entry.sourceLocation)
            val fieldResult = generate(fieldPattern, fieldType, context, sourceLocation)

            bindings.putAll(fieldResult.bindings)
            fieldResult.bindingLocations.forEach { (name, loc) -> bindingLocations[name] = loc }
            // For punning patterns, use the entry's source location
            val entryLoc = entry.sourceLocation
            if (entry.pattern == null && entryLoc != null) {
                bindingLocations[entry.name] = entryLoc
            }
            constraints.addAll(fieldResult.constraints)
        }

        return PatternConstraintResult(
            bindings = bindings,
            constraints = constraints,
            bindingLocations = bindingLocations
        )
    }

    // ========================================================================
    // SEQ PATTERNS
    // ========================================================================

    private fun generateSeq(
        pattern: SeqPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLocation: SourceLocation? = null
    ): PatternConstraintResult {
        // Seq pattern: [x, y, z] or [head, ...tail]
        // Scrutinee must be seq type

        val elementType = MezzeType.VarType(TypeVar.fresh())
        val bindings = mutableMapOf<String, MezzeType>()
        val constraints = mutableListOf<Constraint>()

        // Scrutinee must be Seq<elementType>
        constraints.add(
            Constraint.Equality(
                left = scrutineeType,
                right = MezzeType.SeqType(elementType),
                context = "Seq pattern requires seq type",
                sourceLocation = sourceLocation
            )
        )

        // Generate constraints for each element pattern
        for (elementPattern in pattern.elements) {
            val elementResult = generate(elementPattern, elementType, context, sourceLocation)
            bindings.putAll(elementResult.bindings)
            constraints.addAll(elementResult.constraints)
        }

        // CRITICAL FIX: Handle rest pattern (...tail)
        // The rest pattern binds to a seq of the remaining elements
        // Example: [head, ...tail] binds tail to Seq<elementType>
        val restPatternName = pattern.restPattern
        if (restPatternName != null) {
            bindings[restPatternName] = MezzeType.SeqType(elementType)
        }

        return PatternConstraintResult(
            bindings = bindings,
            constraints = constraints
        )
    }
}
