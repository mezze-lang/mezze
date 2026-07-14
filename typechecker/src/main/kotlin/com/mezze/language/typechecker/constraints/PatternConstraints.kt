package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.parsedast.diagnostics.ErrorCode

class PatternConstraintGenerator {

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

    private fun generateWildcard(
        pattern: ParsedWildcardPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext
    ): PatternConstraintResult {
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
        return PatternConstraintResult(
            bindings = mapOf(pattern.name to scrutineeType),
            constraints = emptyList(),
            bindingLocations = mapOf(pattern.name to pattern.loc)
        )
    }

    private fun generateLiteral(
        pattern: ParsedLiteralPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLoc: Loc? = null
    ): PatternConstraintResult {

        val literalValue = pattern.value

        if (scrutineeType is MezzeType.VariantType && literalValue !is ParsedLiteralValue.BoolValue) {
            val constructors = scrutineeType.constructors.keys.sorted()
            val literalDesc = describeLiteral(literalValue)
            val firstConstructor = constructors.firstOrNull() ?: "ConstructorName"
            throw TypeCheckError(
                "Cannot match variant type [${constructors.joinToString(", ")}] with $literalDesc. " +
                "Use a constructor pattern like '$firstConstructor' instead, " +
                "or use a variable pattern to catch all cases.",
                sourceLoc,
                errorType = ErrorType.INVALID_PATTERN,
                code = ErrorCode.T0208_LITERAL_PATTERN_ON_VARIANT,
                hints = listOf("use a constructor pattern like `Some { ... }`"),
            )
        }

        if (scrutineeType is MezzeType.TypeApplication && literalValue !is ParsedLiteralValue.BoolValue) {
            val literalDesc = describeLiteral(literalValue)
            throw TypeCheckError(
                "Cannot match type '${scrutineeType.constructor}' with $literalDesc. " +
                "Use a constructor pattern instead, or use a variable pattern to catch all cases.",
                sourceLoc,
                errorType = ErrorType.INVALID_PATTERN,
                code = ErrorCode.T0208_LITERAL_PATTERN_ON_VARIANT,
                hints = listOf("use a constructor pattern like `Some { ... }`"),
            )
        }

        val warnings = mutableListOf<String>()

        val literalType = when (literalValue) {
            is ParsedLiteralValue.IntValue -> MezzeType.IntType
            is ParsedLiteralValue.NatValue -> MezzeType.NatType
            is ParsedLiteralValue.FloatValue -> {
                val locationInfo = sourceLoc?.let { " at line ${it.startLine}" } ?: ""
                warnings.add(
                    "Float literal pattern '${literalValue.value}'$locationInfo may not match due to " +
                    "floating-point precision issues. Consider using a range check or rounding instead."
                )
                MezzeType.FloatType
            }
            is ParsedLiteralValue.BoolValue -> MezzeType.VariantType(
                constructors = mapOf(BuiltinTypes.TRUE to null, BuiltinTypes.FALSE to null),
                rowVar = null
            )
            is ParsedLiteralValue.StringValue -> MezzeType.StringType
        }

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

    private fun describeLiteral(value: ParsedLiteralValue): String = when (value) {
        is ParsedLiteralValue.IntValue -> "integer literal '${value.value}'"
        is ParsedLiteralValue.NatValue -> "natural number literal '${value.value}'"
        is ParsedLiteralValue.FloatValue -> "float literal '${value.value}'"
        is ParsedLiteralValue.StringValue -> "string literal \"${value.value}\""
        is ParsedLiteralValue.BoolValue -> "boolean literal '${value.value}'"
    }

    private fun generateStringInterpolation(
        pattern: ParsedStringInterpPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLoc: Loc? = null
    ): PatternConstraintResult {
        val bindings = mutableMapOf<String, MezzeType>()
        val constraints = mutableListOf<Constraint>()

        if (scrutineeType is MezzeType.VariantType) {
            val constructors = scrutineeType.constructors.keys.sorted()
            throw TypeCheckError(
                "Cannot match variant type [${constructors.joinToString(", ")}] with a string interpolation pattern. " +
                "Use a constructor pattern like '${constructors.firstOrNull() ?: "ConstructorName"}' instead.",
                sourceLoc ?: pattern.loc,
                errorType = ErrorType.INVALID_PATTERN,
                code = ErrorCode.T0208_LITERAL_PATTERN_ON_VARIANT,
                hints = listOf("use a constructor pattern like `Some { ... }`"),
            )
        }

        if (scrutineeType is MezzeType.TypeApplication) {
            throw TypeCheckError(
                "Cannot match type '${scrutineeType.constructor}' with a string interpolation pattern. " +
                "String patterns can only match String type values.",
                sourceLoc ?: pattern.loc,
                errorType = ErrorType.INVALID_PATTERN,
                code = ErrorCode.T0208_LITERAL_PATTERN_ON_VARIANT,
            )
        }

        constraints.add(
            Constraint.Equality(
                left = scrutineeType,
                right = MezzeType.StringType,
                context = "String interpolation pattern requires String type",
                loc = sourceLoc ?: pattern.loc
            )
        )

        for (part in pattern.parts) {
            when (part) {
                is ParsedStringPatternPart.Capture -> {
                    bindings[part.variableName] = MezzeType.StringType
                }
                is ParsedStringPatternPart.Literal -> {
                }
            }
        }

        return PatternConstraintResult(
            bindings = bindings,
            constraints = constraints
        )
    }

    private fun generateConstructor(
        pattern: ParsedConstructorPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLoc: Loc? = null
    ): PatternConstraintResult {

        val payload = pattern.payload

        if (payload == null) {
            val constraints = listOf(
                Constraint.HasConstructor(
                    variantType = scrutineeType,
                    constructorName = pattern.constructor,
                    payloadType = null,
                    context = "Constructor pattern ${pattern.constructor}",
                    loc = sourceLoc,
                    isPatternContext = true
                )
            )

            return PatternConstraintResult(
                bindings = emptyMap(),
                constraints = constraints
            )
        } else {

            val payloadType = MezzeType.VarType(TypeVar.fresh())

            val constraints = mutableListOf<Constraint>(
                Constraint.HasConstructor(
                    variantType = scrutineeType,
                    constructorName = pattern.constructor,
                    payloadType = payloadType,
                    context = "Constructor pattern ${pattern.constructor}",
                    loc = sourceLoc,
                    isPatternContext = true
                )
            )

            val payloadResult = generate(payload, payloadType, context, sourceLoc)
            constraints.addAll(payloadResult.constraints)

            return PatternConstraintResult(
                bindings = payloadResult.bindings,
                constraints = constraints,
                bindingLocations = payloadResult.bindingLocations
            )
        }
    }

    private fun generateRecord(
        pattern: ParsedRecordPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLoc: Loc? = null
    ): PatternConstraintResult {

        val bindings = mutableMapOf<String, MezzeType>()
        val bindingLocations = mutableMapOf<String, Loc>()
        val constraints = mutableListOf<Constraint>()

        if (!pattern.isOpen) {
            val patternFieldNames = pattern.entries.map { it.name }.toSet()

            if (scrutineeType is MezzeType.RecordType) {
                val recordFieldNames = scrutineeType.fields.keys
                val uncoveredFields = recordFieldNames - patternFieldNames

                if (uncoveredFields.isNotEmpty()) {
                    val fieldList = uncoveredFields.sorted().joinToString(", ")
                    throw TypeCheckError(
                        "Non-exhaustive record pattern: missing field${if (uncoveredFields.size > 1) "s" else ""} $fieldList. " +
                        "Use { ${patternFieldNames.joinToString(", ")}, ... } to ignore extra fields.",
                        sourceLoc,
                        code = ErrorCode.V0602_NON_EXHAUSTIVE_RECORD_PATTERN,
                    )
                }
            } else {
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

            constraints.add(
                Constraint.HasField(
                    recordType = scrutineeType,
                    fieldName = entry.name,
                    fieldType = fieldType,
                    context = "Record pattern field ${entry.name}",
                    loc = sourceLoc
                )
            )

            val fieldPattern = entry.pattern ?: ParsedVariablePattern(entry.name, entry.loc)
            val fieldResult = generate(fieldPattern, fieldType, context, sourceLoc)

            bindings.putAll(fieldResult.bindings)
            fieldResult.bindingLocations.forEach { (name, loc) -> bindingLocations[name] = loc }
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

    private fun generateSeq(
        pattern: ParsedSeqPattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLoc: Loc? = null
    ): PatternConstraintResult {

        val elementType = MezzeType.VarType(TypeVar.fresh())
        val bindings = mutableMapOf<String, MezzeType>()
        val constraints = mutableListOf<Constraint>()

        constraints.add(
            Constraint.Equality(
                left = scrutineeType,
                right = MezzeType.SeqType(elementType),
                context = "Seq pattern requires seq type",
                loc = sourceLoc
            )
        )

        for (elementPattern in pattern.elements) {
            val elementResult = generate(elementPattern, elementType, context, sourceLoc)
            bindings.putAll(elementResult.bindings)
            constraints.addAll(elementResult.constraints)
        }

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
