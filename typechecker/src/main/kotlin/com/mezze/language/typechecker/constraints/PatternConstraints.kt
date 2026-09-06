package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.FixHint

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
            is ParsedNewtypePattern -> generateNewtype(pattern, scrutineeType, context, sourceLoc)
        }
    }

    private fun generateNewtype(
        pattern: ParsedNewtypePattern,
        scrutineeType: MezzeType,
        context: ConstraintContext,
        sourceLoc: Loc? = null,
    ): PatternConstraintResult {
        val typeDecl = context.typeDeclarations[pattern.typeName]
        val newtypeDef = typeDecl?.definition as? ParsedNewtypeDef

        if (newtypeDef == null) {
            val abilityNames = context.abilityNames
            val effectNames = context.effectNames
            val variantDecl = typeDecl?.definition as? ParsedVariantDef
            val message = when {
                pattern.typeName in abilityNames || com.mezze.language.typechecker.abilities.BuiltInAbilities.isBuiltInAbility(pattern.typeName) ->
                    "'${pattern.typeName}' is an ability, not a newtype; cannot destructure with '.(...)'."
                pattern.typeName in effectNames ->
                    "'${pattern.typeName}' is an effect, not a newtype; cannot destructure with '.(...)'."
                variantDecl != null ->
                    "'${pattern.typeName}' is a variant type, not a newtype. Destructure with '${pattern.typeName} { <field> = <pat> }'."
                pattern.typeName in PrimitiveTypes.allNames ->
                    "'${pattern.typeName}' is a primitive type, not a newtype; cannot destructure with '.(...)'."
                else -> "Unknown type '${pattern.typeName}'. Did you forget to import it?"
            }
            throw TypeCheckError(
                message = message,
                loc = sourceLoc ?: pattern.loc,
                errorType = ErrorType.UNDEFINED_VARIABLE,
                code = ErrorCode.T0212_NOT_A_NEWTYPE,
            )
        }

        val constructorBinding = context.typingContext.lookup(com.mezze.language.ast.BindingKey.unqualified(newtypeDef.constructor))
        if (constructorBinding is com.mezze.language.typechecker.context.TypingContext.LookupResult.NotFound) {
            throw TypeCheckError(
                message = "Cannot destructure newtype '${pattern.typeName}' — its constructor is private to the defining module. " +
                    "Use the module's public API to unwrap (or check whether one exists).",
                loc = sourceLoc ?: pattern.loc,
                errorType = ErrorType.MISSING_FIELD,
                code = ErrorCode.M0803_NOT_EXPORTED,
                hints = listOf(
                    "newtype constructors are not exported; look for a `pub` factory function in the defining module"
                ),
            )
        }

        val wrappedType = MezzeType.VarType(TypeVar.fresh())

        val constructorBindingVal = (constructorBinding as? com.mezze.language.typechecker.context.TypingContext.LookupResult.Found)?.binding
        val newtypeType: MezzeType
        val paramConstraints: List<Constraint>
        if (constructorBindingVal != null) {
            val instantiated = when (constructorBindingVal) {
                is com.mezze.language.typechecker.context.TypingContext.Binding.Polymorphic -> constructorBindingVal.scheme.instantiate().type
                is com.mezze.language.typechecker.context.TypingContext.Binding.Monomorphic -> constructorBindingVal.type
            }
            val fnType = instantiated as? MezzeType.FunctionType
            if (fnType != null) {
                newtypeType = fnType.returnType
                paramConstraints = listOf(
                    Constraint.Equality(
                        left = wrappedType,
                        right = fnType.paramType,
                        context = "Newtype pattern '${pattern.typeName}' inner type",
                        loc = sourceLoc ?: pattern.loc,
                    )
                )
            } else {
                newtypeType = MezzeType.NewtypeType(
                    pattern.typeName, newtypeDef.constructor, wrappedType,
                    // #400: nominal Declared identity from the decl (module-distinct).
                    identity = com.mezze.language.typechecker.phase.newtypeIdentity(typeDecl!!),
                )
                paramConstraints = emptyList()
            }
        } else {
            newtypeType = MezzeType.NewtypeType(
                pattern.typeName, newtypeDef.constructor, wrappedType,
                // #400: nominal Declared identity from the decl (module-distinct).
                identity = com.mezze.language.typechecker.phase.newtypeIdentity(typeDecl),
            )
            paramConstraints = emptyList()
        }

        val constraints = mutableListOf<Constraint>(
            Constraint.Equality(
                left = scrutineeType,
                right = newtypeType,
                context = "Newtype pattern '${pattern.typeName}' must match scrutinee type",
                loc = sourceLoc ?: pattern.loc,
            )
        )
        constraints.addAll(paramConstraints)

        val innerResult = generate(pattern.inner, wrappedType, context, sourceLoc)
        constraints.addAll(innerResult.constraints)

        return PatternConstraintResult(
            bindings = innerResult.bindings,
            constraints = constraints,
            bindingLocations = innerResult.bindingLocations,
        )
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
        // Point the diagnostic at the literal pattern itself, not the whole
        // match (callers pass `expr.loc` as sourceLoc).
        val patternLoc = pattern.loc

        if (scrutineeType is MezzeType.VariantType && literalValue !is ParsedLiteralValue.BoolValue) {
            val constructors = scrutineeType.constructors.keys.sorted()
            val literalDesc = describeLiteral(literalValue)
            val firstConstructor = constructors.firstOrNull() ?: "ConstructorName"
            val label = scrutineeType.identity.displayNameOrNull()?.takeIf { !it.startsWith("<") } ?: findAliasForVariant(scrutineeType, context) ?: "[${constructors.joinToString(", ")}]"
            throw TypeCheckError(
                "Cannot match variant type $label with $literalDesc. " +
                "Use a constructor pattern like '$firstConstructor' instead, " +
                "or use a variable pattern to catch all cases.",
                patternLoc,
                errorType = ErrorType.INVALID_PATTERN,
                code = ErrorCode.T0208_LITERAL_PATTERN_ON_VARIANT,
                hints = listOf("use a constructor pattern like `Some { ... }`"),
                // Offer each real constructor of the scrutinee as a one-click
                // replacement for the mistaken literal pattern (Rename replaces
                // the pattern span with the candidate text). Bare constructor
                // patterns like `Red` are valid Mezze. Only fires when the
                // scrutinee type is already resolved to a variant at pattern-gen
                // time; the unresolved-scrutinee case is reported by the solver.
                fixHints = listOf(FixHint.Rename(patternLoc, constructors)),
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
            is ParsedLiteralValue.NatValue -> natLiteralType(context)
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
            val label = scrutineeType.identity.displayNameOrNull()?.takeIf { !it.startsWith("<") } ?: findAliasForVariant(scrutineeType, context) ?: "[${constructors.joinToString(", ")}]"
            throw TypeCheckError(
                "Cannot match variant type $label with a string interpolation pattern. " +
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

        // #403: a record PATTERN (match case / destructuring `let`) is a partial
        // VIEW of its scrutinee, not a declaration of the scrutinee's full shape.
        // Whether the pattern is open (`{ f, ... }`) or closed (`{ f }`), the
        // scrutinee's inferred row must stay OPEN so a value carrying MORE fields
        // than the pattern names can still be destructured — e.g. matching a variant
        // payload `AppError { message }` against a value that is really
        // `AppError { code, message }` (ClosedVariantInExpressionTest). Closedness of
        // a CLOSED pattern is enforced SEPARATELY by the exhaustiveness machinery
        // (the RecordPatternExhaustive constraint / V0602 uncovered-field check
        // below), NOT by the row var. Without this marker the #403 close in bindLet
        // would drop this pattern-derived tail and wrongly reject the wider value.
        //
        // This is distinct from the #403 target: the bug is a FUNCTION PARAM row
        // grown by `.field` ACCESS (`{ order } -> order.status`), which leaks open
        // into call-site checking. A destructuring record pattern is a different
        // mechanism and must remain open here.
        constraints.add(
            Constraint.MustBeOpen(
                type = scrutineeType,
                context = "Record pattern is a partial view; keep the scrutinee row open (#403)",
                loc = sourceLoc,
                closeableIfPatternInferred = !pattern.isOpen && scrutineeType is MezzeType.VarType
            )
        )

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

    private fun findAliasForVariant(v: MezzeType.VariantType, context: ConstraintContext): String? {
        val ctors = v.constructors.keys
        for ((name, decl) in context.typeDeclarations) {
            val def = decl.definition
            if (def is ParsedVariantDef) {
                val declCtors = def.constructors.map { it.name }.toSet()
                if (declCtors == ctors) return name
            }
        }
        return null
    }
}
