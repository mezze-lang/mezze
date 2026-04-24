package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
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
     * @param sourceLoc Optional source location for error reporting (from scrutinee expression)
     * @return Bindings introduced + constraints on scrutinee
     */
    fun generate(
        pattern: ParsedPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLoc: Loc? = null
    ): PatternConstraintResult {
        return when (pattern) {
            is ParsedWildcardPattern -> generateWildcard(pattern, scrutineeType, context)
            is ParsedVariablePattern -> generateVariable(pattern, scrutineeType, context)
            is ParsedConstructorPattern -> generateConstructor(pattern, scrutineeType, context, sourceLoc)
            is ParsedRecordPattern -> generateRecord(pattern, scrutineeType, context, sourceLoc)
            is ParsedLiteralPattern -> generateLiteral(pattern, scrutineeType, context, sourceLoc)
            is ParsedSeqPattern -> generateSeq(pattern, scrutineeType, context, sourceLoc)
            is ParsedStringInterpPattern -> generateStringInterpolation(pattern, scrutineeType, context, sourceLoc)
        }
    }

    // ========================================================================
    // SIMPLE PATTERNS
    // ========================================================================

    private fun generateWildcard(
        pattern: ParsedWildcardPattern,
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
        pattern: ParsedVariablePattern,
        scrutineeType: MezzeType,
        context: ConstraintContext
    ): PatternConstraintResult {
        // Variable pattern binds the scrutinee to a name
        // Example: let x = expr  (x gets type of expr)
        return PatternConstraintResult(
            bindings = mapOf(pattern.name to scrutineeType),
            constraints = emptyList(),
            bindingLocations = mapOf(pattern.name to pattern.loc!!).filterValues { it != null }.mapValues { it.value!! }
        )
    }

    private fun generateLiteral(
        pattern: ParsedLiteralPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLoc: Loc? = null
    ): PatternConstraintResult {
        // Literal pattern must match the literal type
        // Example: match x { 42 -> ... }

        val literalValue = pattern.value

        // Early detection: Check if scrutinee is a variant type (for better error messages)
        // This catches cases like trying to match a Color variant with an integer literal
        if (scrutineeType is MezzeType.VariantType && literalValue !is ParsedLiteralValue.BoolValue) {
            val constructors = scrutineeType.constructors.keys.sorted()
            val literalDesc = describeLiteral(literalValue)
            val firstConstructor = constructors.firstOrNull() ?: "ConstructorName"
            throw TypeCheckError(
                "Cannot match variant type [${constructors.joinToString(", ")}] with $literalDesc. " +
                "Use a constructor pattern like '$firstConstructor' instead, " +
                "or use a variable pattern to catch all cases.",
                sourceLoc,
                errorType = ErrorType.INVALID_PATTERN
            )
        }

        // Early detection: Check if scrutinee is a TypeApplication (named variant type)
        if (scrutineeType is MezzeType.TypeApplication && literalValue !is ParsedLiteralValue.BoolValue) {
            val literalDesc = describeLiteral(literalValue)
            throw TypeCheckError(
                "Cannot match type '${scrutineeType.constructor}' with $literalDesc. " +
                "Use a constructor pattern instead, or use a variable pattern to catch all cases.",
                sourceLoc,
                errorType = ErrorType.INVALID_PATTERN
            )
        }

        val warnings = mutableListOf<String>()

        val literalType = when (literalValue) {
            is ParsedLiteralValue.IntValue -> MezzeType.IntType
            is ParsedLiteralValue.NatValue -> MezzeType.NatType
            is ParsedLiteralValue.FloatValue -> {
                // Warn about float literal patterns - floating point comparison is imprecise
                val locationInfo = sourceLoc?.let { " at line ${it.startLine}" } ?: ""
                warnings.add(
                    "Float literal pattern '${literalValue.value}'$locationInfo may not match due to " +
                    "floating-point precision issues. Consider using a range check or rounding instead."
                )
                MezzeType.FloatType
            }
            is ParsedLiteralValue.BoolValue -> MezzeType.VariantType(
                constructors = mapOf("True" to null, "False" to null),
                rowVar = null
            )
            is ParsedLiteralValue.StringValue -> MezzeType.StringType
        }

        // Scrutinee must have same type as literal
        val constraints = listOf(
            Constraint.Equality(
                left = scrutineeType,
                right = literalType,
                context = "Literal pattern must match scrutinee type",
                loc = sourceLoc
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
    private fun describeLiteral(value: ParsedLiteralValue): String = when (value) {
        is ParsedLiteralValue.IntValue -> "integer literal '${value.value}'"
        is ParsedLiteralValue.NatValue -> "natural number literal '${value.value}'"
        is ParsedLiteralValue.FloatValue -> "float literal '${value.value}'"
        is ParsedLiteralValue.StringValue -> "string literal \"${value.value}\""
        is ParsedLiteralValue.BoolValue -> "boolean literal '${value.value}'"
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
        pattern: ParsedStringInterpPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLoc: Loc? = null
    ): PatternConstraintResult {
        val bindings = mutableMapOf<String, MezzeType>()
        val constraints = mutableListOf<Constraint>()

        // Early detection: Check if scrutinee is a variant type (for better error messages)
        if (scrutineeType is MezzeType.VariantType) {
            val constructors = scrutineeType.constructors.keys.sorted()
            throw TypeCheckError(
                "Cannot match variant type [${constructors.joinToString(", ")}] with a string interpolation pattern. " +
                "Use a constructor pattern like '${constructors.firstOrNull() ?: "ConstructorName"}' instead.",
                sourceLoc ?: pattern.loc,
                errorType = ErrorType.INVALID_PATTERN
            )
        }

        // Early detection: Check if scrutinee is a TypeApplication (named type)
        if (scrutineeType is MezzeType.TypeApplication) {
            throw TypeCheckError(
                "Cannot match type '${scrutineeType.constructor}' with a string interpolation pattern. " +
                "String patterns can only match String type values.",
                sourceLoc ?: pattern.loc,
                errorType = ErrorType.INVALID_PATTERN
            )
        }

        // Scrutinee must be String type
        constraints.add(
            Constraint.Equality(
                left = scrutineeType,
                right = MezzeType.StringType,
                context = "String interpolation pattern requires String type",
                loc = sourceLoc ?: pattern.loc
            )
        )

        // Extract bindings from capture parts
        // Each capture binds its variable name to String type
        for (part in pattern.parts) {
            when (part) {
                is ParsedStringPatternPart.Capture -> {
                    // Each capture variable is bound to String type
                    bindings[part.variableName] = MezzeType.StringType
                }
                is ParsedStringPatternPart.Literal -> {
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
        pattern: ParsedConstructorPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLoc: Loc? = null
    ): PatternConstraintResult {
        // Constructor pattern: Some {val = x}
        // Scrutinee must be variant type with this constructor

        val payload = pattern.payload

        if (payload == null) {
            // No payload: constructor without data
            // Example: None
            val constraints = listOf(
                Constraint.HasConstructor(
                    variantType = scrutineeType,
                    constructorName = pattern.constructor,
                    payloadType = null,
                    context = "Constructor pattern ${pattern.constructor}",
                    loc = sourceLoc,
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
                    loc = sourceLoc,
                    isPatternContext = true  // This is a pattern context - variant should be closed
                )
            )

            // Generate constraints for payload pattern
            val payloadResult = generate(payload, payloadType, context, sourceLoc)
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
        pattern: ParsedRecordPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLoc: Loc? = null
    ): PatternConstraintResult {
        // Record pattern: {x, y = z, ...}
        // Scrutinee must be record type with these fields

        val bindings = mutableMapOf<String, MezzeType>()
        val bindingLocations = mutableMapOf<String, Loc>()
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
                        sourceLoc
                    )
                }
            } else {
                // Type is not yet known - add a constraint for deferred checking
                constraints.add(
                    Constraint.RecordPatternExhaustive(
                        recordType = scrutineeType,
                        patternFields = patternFieldNames,
                        context = "Closed record pattern requires all fields to be matched",
                        loc = sourceLoc
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
                    loc = sourceLoc
                )
            )

            // Generate constraints for the field pattern
            // For punning {n}, entry.pattern is null - use entry's source location
            val fieldPattern = entry.pattern ?: ParsedVariablePattern(entry.name, entry.loc)
            val fieldResult = generate(fieldPattern, fieldType, context, sourceLoc)

            bindings.putAll(fieldResult.bindings)
            fieldResult.bindingLocations.forEach { (name, loc) -> bindingLocations[name] = loc }
            // For punning patterns, use the entry's source location
            if (entry.pattern == null) {
                val entryLoc = entry.loc
                if (entryLoc != null) {
                    bindingLocations[entry.name] = entryLoc
                }
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
        pattern: ParsedSeqPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLoc: Loc? = null
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
                loc = sourceLoc
            )
        )

        // Generate constraints for each element pattern
        for (elementPattern in pattern.elements) {
            val elementResult = generate(elementPattern, elementType, context, sourceLoc)
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
