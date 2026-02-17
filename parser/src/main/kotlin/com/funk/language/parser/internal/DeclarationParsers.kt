package com.funk.language.parser.internal

import com.funk.language.ast.*
import com.funk.language.parser.*
import com.funk.language.parser.internal.lib.*

/**
 * Extension function to convert a Token to SourceLocation.
 */
private fun Token.toSourceLocation(): SourceLocation = SourceLocation(line, column, null)

/**
 * Declaration parsers for module-level constructs.
 *
 * This file contains internal extension functions for FunkParser that handle:
 * - Let bindings (let x = ...)
 * - Constrained lambdas (function types with ability constraints)
 * - Effect declarations (effect Name where { ... })
 * - Type declarations (type/newtype)
 * - Impl declarations (impl Type for Ability { ... })
 * - Ability declarations (ability Name { ... })
 * - Impl ability declarations (impl ability Name for Type { ... })
 * - Use/import declarations (use path::to::module)
 *
 * All parsers return ProgramItem (sealed interface from AST).
 * All functions are internal - only used within the parser module.
 *
 * Extracted from FunkParser.kt as part of parser refactoring (Task 13).
 */

// ===== LET BINDING =====

internal fun FunkParser.parseLetBinding(stream: TokenStream): ParseResult<ProgramItem> {
    return parseLetBindingWithVisibility(stream, Visibility.PRIVATE)
}


internal fun FunkParser.parseLetBindingWithVisibility(stream: TokenStream, visibility: Visibility): ParseResult<ProgramItem> {
        // Parse: let <pattern> : <type> = <expression>
        //    or: let <pattern> = <expression>
        // Where <pattern> can be:
        //   - identifier (simple binding): let x = ...
        //   - record pattern (destructuring): let { x, y } = ...
        //   - wildcard: let _ = ... (discard value)
        val letToken = tok(TokenType.LET)(stream)
        return when (letToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(letToken.remaining)

                // Try to parse an irrefutable pattern
                val patternResult = letBindingPattern(stream1)

                when (patternResult) {
                    is ParseResult.Success -> {
                        val pattern = patternResult.value
                        val stream2 = skipNewlines(patternResult.remaining)

                        // Validate that the pattern is irrefutable
                        if (!pattern.isIrrefutable()) {
                            return ParseResult.Failure(
                                "Let bindings only support irrefutable patterns. " +
                                "${pattern.kindDescription()} can fail to match. " +
                                "Use 'match' expression for refutable patterns.",
                                stream1.getSourcePosition(),
                                stream1.getSourceLine(),
                                stream1.getSourceColumn()
                            )
                        }

                        // Check for optional type annotation
                        val colonToken = tok(TokenType.COLON)(stream2)
                        val (typeAnnotation, streamAfterType) = when (colonToken) {
                            is ParseResult.Success -> {
                                // Has type annotation
                                val stream3 = skipNewlines(colonToken.remaining)
                                val typeAnn = typeAnnotationParser(stream3)
                                when (typeAnn) {
                                    is ParseResult.Success -> Pair(typeAnn.value, typeAnn.remaining)
                                    is ParseResult.Failure -> return typeAnn  // Type annotation parse failed
                                }
                            }
                            is ParseResult.Failure -> {
                                // No type annotation
                                Pair(null, stream2)
                            }
                        }

                        val stream4 = skipNewlines(streamAfterType)
                        val assign = tok(TokenType.ASSIGN)(stream4)

                        when (assign) {
                            is ParseResult.Success -> {
                                val stream5 = skipNewlines(assign.remaining)
                                val expr = expression(stream5)

                                when (expr) {
                                    is ParseResult.Success -> ParseResult.Success(
                                        LetBinding(
                                            pattern,
                                            visibility,
                                            typeAnnotation,
                                            null, // inferredType filled by typechecker
                                            null, // typeScheme filled by typechecker
                                            expr.value
                                        ),
                                        expr.remaining
                                    )
                                    is ParseResult.Failure -> expr
                                }
                            }
                            is ParseResult.Failure -> assign
                        }
                    }
                    is ParseResult.Failure -> patternResult
                }
            }
            is ParseResult.Failure -> letToken
        }
    }

    // Parser for irrefutable patterns in let bindings
    // Supports: variable, wildcard, record pattern (with nested irrefutable patterns)

    // ===== CONSTRAINED LAMBDA =====
    // Parse: { params } -> ReturnType with Constraint1 'a, Constraint2 'b = { params } -> body
internal fun FunkParser.parseConstrainedLambda(stream: TokenStream): ParseResult<ProgramItem> {
        // Only try if we see a { (record type parameter)
        if (stream.current()?.type != TokenType.LBRACE) {
            return stream.failure("Not a constrained lambda")
        }

        // Try to parse function type annotation
        val typeAnn = functionTypeAnnotation(stream)
        if (typeAnn !is ParseResult.Success) {
            return stream.failure("Expected function type annotation")
        }

        val stream1 = skipNewlines(typeAnn.remaining)
        val typeAnnotation = typeAnn.value

        // Check if it's actually a function type
        if (typeAnnotation !is FunctionTypeAnnotation) {
            return stream.failure("Expected function type annotation")
        }

        // Check if it has ability constraints (from the 'with' keyword)
        // The functionTypeAnnotation parser already parsed these
        if (typeAnnotation.abilityConstraints.isEmpty()) {
            // No constraints, not what we're looking for
            return stream.failure("No ability constraints")
        }

        // Expect '='
        val assignToken = tok(TokenType.ASSIGN)(stream1)
        when (assignToken) {
            is ParseResult.Failure -> return assignToken
            is ParseResult.Success -> {
                val stream2 = skipNewlines(assignToken.remaining)

                // Parse the lambda expression
                val lambda = expression(stream2)
                when (lambda) {
                    is ParseResult.Failure -> return lambda
                    is ParseResult.Success -> {
                        // Check that it's actually a lambda
                        val lambdaExpr = lambda.value
                        if (lambdaExpr !is LambdaExpression) {
                            return stream2.failure("Expected lambda expression like {x} -> x + 1 after '='")
                        }

                        // Create lambda with ability constraints attached
                        val constrainedLambda = lambdaExpr.copy(
                            returnType = typeAnnotation.returnType,
                            abilityConstraints = typeAnnotation.abilityConstraints
                        )

                        return ParseResult.Success(
                            TopLevelExpression(constrainedLambda),
                            lambda.remaining
                        )
                    }
                }
            }
        }
    }

    // ===== EFFECT DECLARATION =====

internal fun FunkParser.parseEffectDeclaration(stream: TokenStream, visibility: Visibility): ParseResult<ProgramItem> {
        // Parse: effect Name [having 'state] where { op1: Type1 -> Type2, op2: Type3 -> Type4 }
        val effectToken = tok(TokenType.EFFECT)(stream)
        return when (effectToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(effectToken.remaining)
                val name = identifierParser(stream1)

                when (name) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(name.remaining)

                        // Check for optional 'having' keyword for multiple associated types
                        val associatedTypes = mutableListOf<String>()
                        val streamAfterHaving = when (val havingToken = tok(TokenType.HAVING)(stream2)) {
                            is ParseResult.Success -> {
                                // Parse comma-separated type variables (e.g., 'state, 'error)
                                val stream2a = skipNewlines(havingToken.remaining)
                                var currentStream = stream2a

                                // Parse first type variable
                                when (val typeVar = identifierParser(currentStream)) {
                                    is ParseResult.Success -> {
                                        // Validate it's a type variable (starts with ')
                                        if (!typeVar.value.text.startsWith("'")) {
                                            return ParseResult.Failure(
                                                "Type variable must start with ' (e.g., 'a, 'state). Got: ${typeVar.value.text}",
                                                typeVar.value.position,
                                                typeVar.value.line,
                                                typeVar.value.column
                                            )
                                        }
                                        associatedTypes.add(typeVar.value.text)
                                        currentStream = skipNewlines(typeVar.remaining)

                                        // Parse additional type variables separated by commas
                                        while (true) {
                                            when (val comma = tok(TokenType.COMMA)(currentStream)) {
                                                is ParseResult.Success -> {
                                                    val streamAfterComma = skipNewlines(comma.remaining)
                                                    when (val nextTypeVar = identifierParser(streamAfterComma)) {
                                                        is ParseResult.Success -> {
                                                            if (!nextTypeVar.value.text.startsWith("'")) {
                                                                return ParseResult.Failure(
                                                                    "Type variable must start with ' (e.g., 'a, 'state). Got: ${nextTypeVar.value.text}",
                                                                    nextTypeVar.value.position,
                                                                    nextTypeVar.value.line,
                                                                    nextTypeVar.value.column
                                                                )
                                                            }
                                                            associatedTypes.add(nextTypeVar.value.text)
                                                            currentStream = skipNewlines(nextTypeVar.remaining)
                                                        }
                                                        is ParseResult.Failure -> {
                                                            return streamAfterComma.failure(
                                                                "Expected type variable after ',' in having clause. " +
                                                                "Example: effect ${name.value.text} having 'state, 'error where { ... }"
                                                            )
                                                        }
                                                    }
                                                }
                                                is ParseResult.Failure -> break
                                            }
                                        }
                                        currentStream
                                    }
                                    is ParseResult.Failure -> {
                                        return stream2a.failure(
                                            "Expected type variable after 'having' keyword in effect declaration. " +
                                            "Example: effect ${name.value.text} having 'state where { ... }"
                                        )
                                    }
                                }
                            }
                            is ParseResult.Failure -> stream2
                        }

                        // Parse required 'where' keyword
                        val whereToken = tok(TokenType.WHERE)(streamAfterHaving)
                        when (whereToken) {
                            is ParseResult.Success -> {
                                val stream3 = skipNewlines(whereToken.remaining)
                                val lbrace = tok(TokenType.LBRACE)(stream3)

                                when (lbrace) {
                                    is ParseResult.Success -> {
                                        val stream4 = skipNewlines(lbrace.remaining)
                                        // Parse operations separated by commas (trailing comma allowed)
                                        val operations = mutableListOf<EffectOperation>()
                                        var currentStream = stream4

                                        // Parse first operation (if any)
                                        when (val firstOp = effectOperationParser(currentStream)) {
                                            is ParseResult.Success -> {
                                                operations.add(firstOp.value)
                                                currentStream = skipNewlines(firstOp.remaining)

                                                // Parse remaining operations, each preceded by comma
                                                while (true) {
                                                    when (val comma = tok(TokenType.COMMA)(currentStream)) {
                                                        is ParseResult.Success -> {
                                                            currentStream = skipNewlines(comma.remaining)
                                                            // Check if this is a trailing comma (next token is })
                                                            if (currentStream.current()?.type == TokenType.RBRACE) {
                                                                break  // Trailing comma, done
                                                            }
                                                            // Parse next operation
                                                            when (val op = effectOperationParser(currentStream)) {
                                                                is ParseResult.Success -> {
                                                                    operations.add(op.value)
                                                                    currentStream = skipNewlines(op.remaining)
                                                                }
                                                                is ParseResult.Failure -> {
                                                                    return op  // Return error from operation parsing
                                                                }
                                                            }
                                                        }
                                                        is ParseResult.Failure -> {
                                                            // No comma - check if there's another operation (error) or }
                                                            val next = currentStream.current()
                                                            if (next?.type == TokenType.IDENTIFIER) {
                                                                return currentStream.failure(
                                                                    "Missing ',' between effect operations. " +
                                                                    "Effect operations must be comma-separated."
                                                                )
                                                            }
                                                            break  // No comma and no identifier, done
                                                        }
                                                    }
                                                }
                                            }
                                            is ParseResult.Failure -> {
                                                // No operations, that's fine - empty effect
                                            }
                                        }

                                        val stream5 = skipNewlines(currentStream)
                                        val rbrace = tok(TokenType.RBRACE)(stream5)

                                        when (rbrace) {
                                            is ParseResult.Success -> {
                                                ParseResult.Success(
                                                    EffectDeclaration(
                                                        name.value.text,
                                                        visibility,
                                                        operations,
                                                        associatedTypes,  // Pass the list of associated types
                                                        inferredType = null,
                                                        sourceLocation = effectToken.value.toSourceLocation()
                                                    ),
                                                    rbrace.remaining
                                                )
                                            }
                                            is ParseResult.Failure -> stream5.failure(
                                                "Expected '}' to close effect declaration '${name.value.text}'. " +
                                                "Make sure all effect operations are properly formatted: operationName: { params } -> ReturnType <${name.value.text}>"
                                            )
                                        }
                                    }
                                    is ParseResult.Failure -> stream3.failure(
                                        "Expected '{' after 'where' in effect declaration '${name.value.text}'. " +
                                        "Syntax: effect ${name.value.text} where { operations }"
                                    )
                                }
                            }
                            is ParseResult.Failure -> streamAfterHaving.failure(
                                "Effect declarations require 'where' keyword. " +
                                "Expected: effect ${name.value.text} where { operations }. " +
                                "Each operation must declare the effect in its type signature: operationName: { params } -> ReturnType <${name.value.text}>"
                            )
                        }
                    }
                    is ParseResult.Failure -> name
                }
            }
            is ParseResult.Failure -> effectToken
        }
    }

internal val FunkParser.effectOperationParser: Parser<EffectOperation> get() = { stream ->
        // Parse: operationName: ParamType -> ReturnType <EffectName>
        val name = identifierParser(stream)
        when (name) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(name.remaining)
                val colon = tok(TokenType.COLON)(stream1)

                when (colon) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(colon.remaining)
                        // Parse ParamType -> ReturnType as a function type
                        val functionType = functionTypeAnnotation(stream2)

                        when (functionType) {
                            is ParseResult.Success -> {
                                // Extract param and return types from function type
                                val funcType = functionType.value as? FunctionTypeAnnotation
                                if (funcType != null) {
                                    val effectList = funcType.effects?.effects ?: emptyList()
                                    ParseResult.Success(
                                        EffectOperation(
                                            name.value.text,
                                            funcType.paramType,
                                            funcType.returnType,
                                            effectList
                                        ),
                                        functionType.remaining
                                    )
                                } else {
                                    stream2.failure(
                                        "Expected function type for effect operation '${name.value.text}'. " +
                                        "Effect operations must have the format: operationName: { params } -> ReturnType <EffectName>"
                                    )
                                }
                            }
                            is ParseResult.Failure -> ParseResult.Failure(
                                "Invalid type annotation for effect operation '${name.value.text}'. " +
                                "Expected format: ${name.value.text}: { params } -> ReturnType <EffectName>. " +
                                "Error: ${functionType.message}",
                                functionType.position,
                                functionType.line,
                                functionType.column
                            )
                        }
                    }
                    is ParseResult.Failure -> stream1.failure(
                        "Expected ':' after effect operation name '${name.value.text}'. " +
                        "Effect operations must have the format: ${name.value.text}: { params } -> ReturnType <EffectName>"
                    )
                }
            }
            is ParseResult.Failure -> name
        }
    }

    // ===== TYPE DECLARATIONS =====

internal fun FunkParser.parseTypeDeclaration(stream: TokenStream, visibility: Visibility): ParseResult<ProgramItem> {
        val typeToken = tok(TokenType.TYPE)(stream)
        return when (typeToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(typeToken.remaining)
                val name = identifierParser(stream1)

                when (name) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(name.remaining)

                        // Parse optional type parameters: 'a 'b
                        val typeParams = mutableListOf<String>()
                        var currentStream = stream2
                        while (true) {
                            val typeVar = currentStream.current()
                            if (typeVar?.type == TokenType.IDENTIFIER && typeVar.text.startsWith("'")) {
                                typeParams.add(typeVar.text)
                                currentStream = currentStream.advance()
                                currentStream = skipNewlines(currentStream)
                            } else {
                                break
                            }
                        }

                        val stream3 = skipNewlines(currentStream)
                        val assign = tok(TokenType.ASSIGN)(stream3)

                        when (assign) {
                            is ParseResult.Success -> {
                                val stream4 = skipNewlines(assign.remaining)
                                val typeDef = typeDefinitionParser(stream4)

                                when (typeDef) {
                                    is ParseResult.Success -> ParseResult.Success(
                                        TypeDeclaration(
                                            name = name.value.text,
                                            visibility = visibility,
                                            typeParams = typeParams,
                                            definition = typeDef.value,
                                            inferredType = null,
                                            sourceLocation = typeToken.value.toSourceLocation()
                                        ),
                                        typeDef.remaining
                                    )
                                    is ParseResult.Failure -> typeDef
                                }
                            }
                            is ParseResult.Failure -> assign
                        }
                    }
                    is ParseResult.Failure -> name
                }
            }
            is ParseResult.Failure -> typeToken
        }
    }

internal fun FunkParser.parseNewtypeDeclaration(stream: TokenStream, visibility: Visibility): ParseResult<ProgramItem> {
        val newtypeToken = tok(TokenType.NEWTYPE)(stream)
        return when (newtypeToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(newtypeToken.remaining)
                val typeName = identifierParser(stream1)

                when (typeName) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(typeName.remaining)

                        // Parse optional type parameters: 'a 'b
                        val typeParams = mutableListOf<String>()
                        var currentStream = stream2
                        while (true) {
                            val typeVar = currentStream.current()
                            if (typeVar?.type == TokenType.IDENTIFIER && typeVar.text.startsWith("'")) {
                                typeParams.add(typeVar.text)
                                currentStream = currentStream.advance()
                                currentStream = skipNewlines(currentStream)
                            } else {
                                break
                            }
                        }

                        val stream3 = skipNewlines(currentStream)
                        val assign = tok(TokenType.ASSIGN)(stream3)

                        when (assign) {
                            is ParseResult.Success -> {
                                val stream4 = skipNewlines(assign.remaining)
                                val constructor = identifierParser(stream4)

                                when (constructor) {
                                    is ParseResult.Success -> {
                                        val stream5 = skipNewlines(constructor.remaining)
                                        val wrappedType = typeAnnotationParser(stream5)

                                        when (wrappedType) {
                                            is ParseResult.Success -> ParseResult.Success(
                                                TypeDeclaration(
                                                    name = typeName.value.text,
                                                    visibility = visibility,
                                                    typeParams = typeParams,
                                                    definition = NewtypeDefinition(
                                                        constructor = constructor.value.text,
                                                        wrappedType = wrappedType.value
                                                    ),
                                                    inferredType = null
                                                ),
                                                wrappedType.remaining
                                            )
                                            is ParseResult.Failure -> wrappedType
                                        }
                                    }
                                    is ParseResult.Failure -> constructor
                                }
                            }
                            is ParseResult.Failure -> assign
                        }
                    }
                    is ParseResult.Failure -> typeName
                }
            }
            is ParseResult.Failure -> newtypeToken
        }
    }

internal val FunkParser.typeDefinitionParser: Parser<TypeDefinition> get() = { stream ->
        // Try variant type first (| ... |), then record type
        val variantResult = variantTypeParser(stream)
        when (variantResult) {
            is ParseResult.Success -> variantResult
            is ParseResult.Failure -> {
                // If the error indicates invalid variant syntax, don't try record parsing
                // This catches cases like: | Constructor1 | Constructor2 (missing commas)
                if (variantResult.message.startsWith("Invalid variant syntax:")) {
                    variantResult
                } else {
                    // Try record type
                    recordTypeParser(stream)
                }
            }
        }
    }

internal val FunkParser.variantTypeParser: Parser<TypeDefinition> get() = { stream ->
        val pipe1 = tok(TokenType.PIPE)(stream)
        when (pipe1) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(pipe1.remaining)

                // Check for empty variant type: | |
                val pipe2Check = tok(TokenType.PIPE)(stream1)
                if (pipe2Check is ParseResult.Success) {
                    // Empty variant type
                    ParseResult.Success(
                        VariantDefinition(emptyList(), false),
                        pipe2Check.remaining
                    )
                } else {
                    // Non-empty variant: parse constructors (with optional trailing comma)
                    val constructors = sepEndByWithNewlines(
                        variantConstructorParser,
                        tok(TokenType.COMMA)
                    )(stream1)

                    when (constructors) {
                        is ParseResult.Success -> {
                            val stream2 = skipNewlines(constructors.remaining)

                            // Check for open variant syntax: ...
                            // Note: sepEndByWithNewlines already consumed the comma before ..., so we check for ... directly
                            var isOpen = false
                            var streamAfterEllipsis = stream2

                            val ellipsisCheck = tok(TokenType.ELLIPSIS)(stream2)
                            if (ellipsisCheck is ParseResult.Success) {
                                isOpen = true
                                streamAfterEllipsis = skipNewlines(ellipsisCheck.remaining)
                            }

                            val pipe2 = tok(TokenType.PIPE)(streamAfterEllipsis)

                            when (pipe2) {
                                is ParseResult.Success -> {
                                    // Check for invalid syntax: | Constructor1 | Constructor2
                                    // This happens when user forgets comma between constructors
                                    // and writes each on its own line with |
                                    // We only flag this as invalid if the identifier appears on the SAME line
                                    // (i.e., no newlines between closing | and the identifier)
                                    // If there are newlines, it's likely a valid separate expression
                                    val afterPipe = pipe2.remaining
                                    val nextToken = afterPipe.current()
                                    if (nextToken != null &&
                                        nextToken.type == TokenType.IDENTIFIER &&
                                        nextToken.text.first().isUpperCase()) {
                                        // There's a constructor name immediately after the closing |
                                        // This is invalid - they probably meant to use comma separation
                                        ParseResult.Failure(
                                            "Invalid variant syntax: found '${nextToken.text}' after closing '|'. " +
                                            "Variant constructors must be comma-separated within | ... |. " +
                                            "Example: type Option 'a = | Some { val: 'a }, None |",
                                            afterPipe.position
                                        )
                                    } else {
                                        ParseResult.Success(
                                            VariantDefinition(constructors.value, isOpen),
                                            pipe2.remaining
                                        )
                                    }
                                }
                                is ParseResult.Failure -> pipe2
                            }
                        }
                        is ParseResult.Failure -> constructors
                    }
                }
            }
            is ParseResult.Failure -> pipe1
        }
    }

internal val FunkParser.variantConstructorParser: Parser<VariantConstructorDef> get() = { stream ->
        val name = identifierParser(stream)
        when (name) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(name.remaining)
                // Check for optional type annotation (record or any other type)
                // Try record type first (for backwards compatibility), then any type annotation
                val dataType = Parsers.oneOf(
                    recordTypeAnnotationParser,
                    identifierTypeAnnotation  // Allows bare type variables like 'a
                )(stream1)

                when (dataType) {
                    is ParseResult.Success -> ParseResult.Success(
                        VariantConstructorDef(name.value.text, dataType.value),
                        dataType.remaining
                    )
                    is ParseResult.Failure -> ParseResult.Success(
                        VariantConstructorDef(name.value.text, null),
                        stream1
                    )
                }
            }
            is ParseResult.Failure -> name
        }
    }

internal val FunkParser.recordTypeParser: Parser<TypeDefinition> get() = { stream ->
        val recordType = recordTypeAnnotationParser(stream)
        when (recordType) {
            is ParseResult.Success -> {
                val value = recordType.value
                if (value is RecordTypeAnnotation) {
                    val fields = value.entries.map { entry ->
                        RecordFieldDef(entry.name, entry.type)
                    }
                    val isOpen = value.restField != null
                    ParseResult.Success(
                        RecordDefinition(fields, isOpen),
                        recordType.remaining
                    )
                } else {
                    stream.failure("Expected record type like { field: Type }")
                }
            }
            is ParseResult.Failure -> recordType
        }
    }

    // ===== IMPL DECLARATIONS =====

internal fun FunkParser.parseImplDeclaration(stream: TokenStream): ParseResult<ProgramItem> {
        val implToken = tok(TokenType.IMPL)(stream)
        return when (implToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(implToken.remaining)
                val effectName = identifierParser(stream1)

                when (effectName) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(effectName.remaining)
                        val forToken = tok(TokenType.FOR)(stream2)

                        when (forToken) {
                            is ParseResult.Success -> {
                                val stream3 = skipNewlines(forToken.remaining)
                                val performerName = identifierParser(stream3)

                                when (performerName) {
                                    is ParseResult.Success -> {
                                        val stream4 = skipNewlines(performerName.remaining)

                                        // Parse optional performer params: { seed: Int }
                                        val performerParamsResult = performerParamsParser(stream4)
                                        val (performerParams, streamAfterParams) = when (performerParamsResult) {
                                            is ParseResult.Success -> Pair(performerParamsResult.value, performerParamsResult.remaining)
                                            is ParseResult.Failure -> Pair(emptyList(), stream4)
                                        }

                                        val stream5 = skipNewlines(streamAfterParams)

                                        // Parse optional 'having' clause with associated type bindings
                                        val bindingsResult = parseAssociatedTypeBindings(stream5, effectName.value.text, "impl")
                                        val (associatedTypeBindings, streamAfterHaving) = when (bindingsResult) {
                                            is ParseResult.Success -> Pair(bindingsResult.value.bindings, bindingsResult.value.stream)
                                            is ParseResult.Failure -> return bindingsResult
                                        }

                                        val stream5d = skipNewlines(streamAfterHaving)
                                        val whereToken = tok(TokenType.WHERE)(stream5d)

                                        when (whereToken) {
                                            is ParseResult.Success -> {
                                                val stream6 = skipNewlines(whereToken.remaining)
                                                val lbrace = tok(TokenType.LBRACE)(stream6)

                                                when (lbrace) {
                                                    is ParseResult.Success -> {
                                                        val stream7 = skipNewlines(lbrace.remaining)
                                                        // Parse operations separated by commas (trailing comma allowed)
                                                        val operations = mutableListOf<PerformerOperation>()
                                                        var currentStream = stream7

                                                        // Parse first operation (if any)
                                                        when (val firstOp = performerOperationParser(currentStream)) {
                                                            is ParseResult.Success -> {
                                                                operations.add(firstOp.value)
                                                                currentStream = skipNewlines(firstOp.remaining)

                                                                // Parse remaining operations, each preceded by comma
                                                                while (true) {
                                                                    when (val comma = tok(TokenType.COMMA)(currentStream)) {
                                                                        is ParseResult.Success -> {
                                                                            currentStream = skipNewlines(comma.remaining)
                                                                            // Check if this is a trailing comma (next token is })
                                                                            if (currentStream.current()?.type == TokenType.RBRACE) {
                                                                                break  // Trailing comma, done
                                                                            }
                                                                            // Parse next operation
                                                                            when (val op = performerOperationParser(currentStream)) {
                                                                                is ParseResult.Success -> {
                                                                                    operations.add(op.value)
                                                                                    currentStream = skipNewlines(op.remaining)
                                                                                }
                                                                                is ParseResult.Failure -> {
                                                                                    return op  // Return error from operation parsing
                                                                                }
                                                                            }
                                                                        }
                                                                        is ParseResult.Failure -> {
                                                                            // No comma - check if there's another operation (error) or }
                                                                            val next = currentStream.current()
                                                                            if (next?.type == TokenType.IDENTIFIER) {
                                                                                return currentStream.failure(
                                                                                    "Missing ',' between effect impl operations. " +
                                                                                    "Operations must be comma-separated."
                                                                                )
                                                                            }
                                                                            break  // No comma and no identifier, done
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                            is ParseResult.Failure -> {
                                                                // No operations, that's fine - empty impl
                                                            }
                                                        }

                                                        val stream8 = skipNewlines(currentStream)
                                                        val rbrace = tok(TokenType.RBRACE)(stream8)

                                                        when (rbrace) {
                                                            is ParseResult.Success -> {
                                                                ParseResult.Success(
                                                                    ImplDeclaration(
                                                                        effectName = effectName.value.text,
                                                                        performerName = performerName.value.text,
                                                                        performerParams = performerParams,
                                                                        associatedTypeBindings = associatedTypeBindings,
                                                                        operations = operations,
                                                                        inferredType = null,
                                                                        sourceLocation = implToken.value.toSourceLocation()
                                                                    ),
                                                                    rbrace.remaining
                                                                )
                                                            }
                                                            is ParseResult.Failure -> rbrace
                                                        }
                                                    }
                                                    is ParseResult.Failure -> lbrace
                                                }
                                            }
                                            is ParseResult.Failure -> whereToken
                                        }
                                    }
                                    is ParseResult.Failure -> performerName
                                }
                            }
                            is ParseResult.Failure -> forToken
                        }
                    }
                    is ParseResult.Failure -> effectName
                }
            }
            is ParseResult.Failure -> implToken
        }
    }

internal val FunkParser.performerParamsParser: Parser<List<PerformerParameter>> get() = { stream ->
        val lbrace = tok(TokenType.LBRACE)(stream)
        when (lbrace) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(lbrace.remaining)
                val params = sepByWithNewlines(
                    performerParamParser,
                    tok(TokenType.COMMA)
                )(stream1)

                when (params) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(params.remaining)
                        val rbrace = tok(TokenType.RBRACE)(stream2)

                        when (rbrace) {
                            is ParseResult.Success -> ParseResult.Success(
                                params.value,
                                rbrace.remaining
                            )
                            is ParseResult.Failure -> rbrace
                        }
                    }
                    is ParseResult.Failure -> params
                }
            }
            is ParseResult.Failure -> lbrace
        }
    }

internal val FunkParser.performerParamParser: Parser<PerformerParameter> get() = { stream ->
        val name = identifierParser(stream)
        when (name) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(name.remaining)
                val colon = tok(TokenType.COLON)(stream1)

                when (colon) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(colon.remaining)
                        val typeAnn = typeAnnotationParser(stream2)

                        when (typeAnn) {
                            is ParseResult.Success -> ParseResult.Success(
                                PerformerParameter(name.value.text, typeAnn.value),
                                typeAnn.remaining
                            )
                            is ParseResult.Failure -> typeAnn
                        }
                    }
                    is ParseResult.Failure -> colon
                }
            }
            is ParseResult.Failure -> name
        }
    }

internal val FunkParser.performerOperationParser: Parser<PerformerOperation> get() = { stream ->
        val name = identifierParser(stream)
        when (name) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(name.remaining)
                val colon = tok(TokenType.COLON)(stream1)

                when (colon) {
                    is ParseResult.Success -> {
                        // Parse the full function type: {param: Type} -> ReturnType
                        val stream2 = skipNewlines(colon.remaining)
                        val functionType = typeAnnotationParser(stream2)

                        when (functionType) {
                            is ParseResult.Success -> {
                                // Extract param, return types, and effects from the function type
                                val (paramType, returnType, effects) = when (val ft = functionType.value) {
                                    is FunctionTypeAnnotation -> Triple(ft.paramType, ft.returnType, ft.effects)
                                    else -> {
                                        // If it's not a function type, treat it as param type with Unit return
                                        Triple(ft, IdentifierTypeAnnotation("Unit"), null)
                                    }
                                }

                                val stream3 = skipNewlines(functionType.remaining)
                                // Note: Effect rows for performers are inferred from the effect declaration
                                // If explicit effect annotation is needed, it would be part of the function type
                                val assign = tok(TokenType.ASSIGN)(stream3)

                                when (assign) {
                                    is ParseResult.Success -> {
                                        val stream4 = skipNewlines(assign.remaining)
                                        val impl = expression(stream4)

                                        when (impl) {
                                            is ParseResult.Success -> ParseResult.Success(
                                                PerformerOperation(
                                                    name = name.value.text,
                                                    paramType = paramType,
                                                    returnType = returnType,
                                                    implementation = impl.value,
                                                    effects = effects  // Now preserving effect annotations
                                                ),
                                                impl.remaining
                                            )
                                            is ParseResult.Failure -> impl
                                        }
                                    }
                                    is ParseResult.Failure -> assign
                                }
                            }
                            is ParseResult.Failure -> functionType
                        }
                    }
                    is ParseResult.Failure -> {
                        // No colon - try parsing without type annotation: name = expr
                        val assign = tok(TokenType.ASSIGN)(stream1)
                        when (assign) {
                            is ParseResult.Success -> {
                                val stream2 = skipNewlines(assign.remaining)
                                val impl = expression(stream2)

                                when (impl) {
                                    is ParseResult.Success -> {
                                        // Use placeholder types - will be inferred by typechecker
                                        val placeholderParamType = RecordTypeAnnotation(emptyList(), null)
                                        val placeholderReturnType = RecordTypeAnnotation(emptyList(), null)

                                        ParseResult.Success(
                                            PerformerOperation(
                                                name = name.value.text,
                                                paramType = placeholderParamType,
                                                returnType = placeholderReturnType,
                                                implementation = impl.value,
                                                effects = null
                                            ),
                                            impl.remaining
                                        )
                                    }
                                    is ParseResult.Failure -> impl
                                }
                            }
                            is ParseResult.Failure -> colon // Return the original colon failure
                        }
                    }
                }
            }
            is ParseResult.Failure -> name
        }
    }

    // ===== ABILITY DECLARATION =====

internal fun FunkParser.parseAbilityDeclaration(stream: TokenStream, visibility: Visibility): ParseResult<ProgramItem> {
        // Parse: ability Encoder 'a where { operations }
        val abilityToken = tok(TokenType.ABILITY)(stream)
        return when (abilityToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(abilityToken.remaining)
                val name = identifierParser(stream1)

                when (name) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(name.remaining)

                        // Parse type parameters: 'a 'b
                        val typeParams = mutableListOf<String>()
                        var currentStream = stream2
                        while (true) {
                            val typeVar = currentStream.current()
                            if (typeVar?.type == TokenType.IDENTIFIER && typeVar.text.startsWith("'")) {
                                typeParams.add(typeVar.text)
                                currentStream = currentStream.advance()
                                currentStream = skipNewlines(currentStream)
                            } else {
                                break
                            }
                        }

                        // Check for optional 'having' keyword for multiple associated types
                        val associatedTypes = mutableListOf<String>()
                        val streamAfterHaving = when (val havingToken = tok(TokenType.HAVING)(currentStream)) {
                            is ParseResult.Success -> {
                                val streamH = skipNewlines(havingToken.remaining)
                                var havingStream = streamH

                                // Parse first type variable
                                when (val typeVar = identifierParser(havingStream)) {
                                    is ParseResult.Success -> {
                                        // Validate it's a type variable (starts with ')
                                        if (!typeVar.value.text.startsWith("'")) {
                                            return ParseResult.Failure(
                                                "Type variable must start with ' (e.g., 'a, 'item). Got: ${typeVar.value.text}",
                                                typeVar.value.position,
                                                typeVar.value.line,
                                                typeVar.value.column
                                            )
                                        }
                                        associatedTypes.add(typeVar.value.text)
                                        havingStream = skipNewlines(typeVar.remaining)

                                        // Parse additional type variables separated by commas
                                        while (true) {
                                            when (val comma = tok(TokenType.COMMA)(havingStream)) {
                                                is ParseResult.Success -> {
                                                    val streamAfterComma = skipNewlines(comma.remaining)
                                                    when (val nextTypeVar = identifierParser(streamAfterComma)) {
                                                        is ParseResult.Success -> {
                                                            if (!nextTypeVar.value.text.startsWith("'")) {
                                                                return ParseResult.Failure(
                                                                    "Type variable must start with ' (e.g., 'a, 'item). Got: ${nextTypeVar.value.text}",
                                                                    nextTypeVar.value.position,
                                                                    nextTypeVar.value.line,
                                                                    nextTypeVar.value.column
                                                                )
                                                            }
                                                            associatedTypes.add(nextTypeVar.value.text)
                                                            havingStream = skipNewlines(nextTypeVar.remaining)
                                                        }
                                                        is ParseResult.Failure -> {
                                                            return streamAfterComma.failure(
                                                                "Expected type variable after ',' in having clause. " +
                                                                "Example: ability ${name.value.text} having 'item, 'iter where { ... }"
                                                            )
                                                        }
                                                    }
                                                }
                                                is ParseResult.Failure -> break
                                            }
                                        }
                                        havingStream
                                    }
                                    is ParseResult.Failure -> {
                                        return streamH.failure(
                                            "Expected type variable after 'having' keyword in ability declaration. " +
                                            "Example: ability ${name.value.text} 'iter having 'item where { ... }"
                                        )
                                    }
                                }
                            }
                            is ParseResult.Failure -> currentStream
                        }

                        // Parse optional 'with' clause for dependencies
                        val dependencies = mutableListOf<AbilityDependency>()
                        val stream3 = skipNewlines(streamAfterHaving)
                        val withToken = tok(TokenType.WITH)(stream3)
                        val streamAfterWith = when (withToken) {
                            is ParseResult.Success -> {
                                // Parse dependencies separated by commas
                                val stream4 = skipNewlines(withToken.remaining)
                                var depStream = stream4
                                var parseFailed = false

                                while (!parseFailed) {
                                    // Parse: AbilityName TypeArg1 TypeArg2 ...
                                    val abilityName = identifierParser(depStream)
                                    when (abilityName) {
                                        is ParseResult.Success -> {
                                            val typeArgs = mutableListOf<TypeAnnotation>()
                                            var typeStream = skipNewlines(abilityName.remaining)

                                            // Parse type arguments until we hit comma or 'where'
                                            while (true) {
                                                val next = typeStream.current()
                                                if (next?.type == TokenType.COMMA || next?.type == TokenType.WHERE) {
                                                    break
                                                }

                                                val typeArg = primaryTypeAnnotation(typeStream)
                                                when (typeArg) {
                                                    is ParseResult.Success -> {
                                                        typeArgs.add(typeArg.value)
                                                        typeStream = skipNewlines(typeArg.remaining)
                                                    }
                                                    is ParseResult.Failure -> break
                                                }
                                            }

                                            dependencies.add(AbilityDependency(abilityName.value.text, typeArgs))

                                            // Check for comma (more dependencies) or 'where'
                                            val commaToken = tok(TokenType.COMMA)(typeStream)
                                            depStream = when (commaToken) {
                                                is ParseResult.Success -> skipNewlines(commaToken.remaining)
                                                is ParseResult.Failure -> {
                                                    parseFailed = true
                                                    typeStream
                                                }
                                            }
                                        }
                                        is ParseResult.Failure -> {
                                            parseFailed = true
                                            depStream
                                        }
                                    }
                                }
                                depStream
                            }
                            is ParseResult.Failure -> stream3
                        }

                        val streamBeforeWhere = skipNewlines(streamAfterWith)
                        val whereToken = tok(TokenType.WHERE)(streamBeforeWhere)

                        when (whereToken) {
                            is ParseResult.Success -> {
                                val stream4 = skipNewlines(whereToken.remaining)
                                val lbrace = tok(TokenType.LBRACE)(stream4)

                                when (lbrace) {
                                    is ParseResult.Success -> {
                                        val stream5 = skipNewlines(lbrace.remaining)
                                        // Parse operations separated by commas (trailing comma allowed)
                                        val operations = mutableListOf<AbilityOperation>()
                                        var currentStream = stream5

                                        // Parse first operation (if any)
                                        when (val firstOp = abilityOperationParser(currentStream)) {
                                            is ParseResult.Success -> {
                                                operations.add(firstOp.value)
                                                currentStream = skipNewlines(firstOp.remaining)

                                                // Parse remaining operations, each preceded by comma
                                                while (true) {
                                                    when (val comma = tok(TokenType.COMMA)(currentStream)) {
                                                        is ParseResult.Success -> {
                                                            currentStream = skipNewlines(comma.remaining)
                                                            // Check if this is a trailing comma (next token is })
                                                            if (currentStream.current()?.type == TokenType.RBRACE) {
                                                                break  // Trailing comma, done
                                                            }
                                                            // Parse next operation
                                                            when (val op = abilityOperationParser(currentStream)) {
                                                                is ParseResult.Success -> {
                                                                    operations.add(op.value)
                                                                    currentStream = skipNewlines(op.remaining)
                                                                }
                                                                is ParseResult.Failure -> {
                                                                    return op  // Return error from operation parsing
                                                                }
                                                            }
                                                        }
                                                        is ParseResult.Failure -> {
                                                            // No comma - check if there's another operation (error) or }
                                                            val next = currentStream.current()
                                                            if (next?.type == TokenType.IDENTIFIER) {
                                                                return currentStream.failure(
                                                                    "Missing ',' between ability methods. " +
                                                                    "Ability methods must be comma-separated."
                                                                )
                                                            }
                                                            break  // No comma and no identifier, done
                                                        }
                                                    }
                                                }
                                            }
                                            is ParseResult.Failure -> {
                                                // No operations, that's fine - empty ability
                                            }
                                        }

                                        val stream6 = skipNewlines(currentStream)
                                        val rbrace = tok(TokenType.RBRACE)(stream6)

                                        when (rbrace) {
                                            is ParseResult.Success -> ParseResult.Success(
                                                AbilityDeclaration(
                                                    name.value.text,
                                                    visibility,
                                                    typeParams,
                                                    associatedTypes,  // list of associated types from 'having' clause
                                                    emptyList(),  // constraints (for function ability constraints)
                                                    dependencies,  // dependencies from 'with' clause
                                                    operations,
                                                    inferredType = null,
                                                    sourceLocation = abilityToken.value.toSourceLocation()
                                                ),
                                                rbrace.remaining
                                            )
                                            is ParseResult.Failure -> {
                                                // If we parsed no operations and see an identifier, try parsing an operation to get a better error
                                                if (operations.isEmpty() && stream6.current()?.type == TokenType.IDENTIFIER) {
                                                    when (val opError = abilityOperationParser(stream6)) {
                                                        is ParseResult.Failure -> opError  // Return the more specific operation parsing error
                                                        is ParseResult.Success -> rbrace  // Shouldn't happen, but return generic error
                                                    }
                                                } else {
                                                    rbrace
                                                }
                                            }
                                        }
                                    }
                                    is ParseResult.Failure -> lbrace
                                }
                            }
                            is ParseResult.Failure -> whereToken
                        }
                    }
                    is ParseResult.Failure -> name
                }
            }
            is ParseResult.Failure -> abilityToken
        }
    }

internal val FunkParser.abilityOperationParser: Parser<AbilityOperation> get() = { stream ->
        // Parse: operationName : ParamType -> ReturnType
        val name = identifierParser(stream)
        when (name) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(name.remaining)
                val colon = tok(TokenType.COLON)(stream1)

                when (colon) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(colon.remaining)
                        val functionType = functionTypeAnnotation(stream2)

                        when (functionType) {
                            is ParseResult.Success -> {
                                val funcType = functionType.value as? FunctionTypeAnnotation
                                if (funcType != null) {
                                    ParseResult.Success(
                                        AbilityOperation(
                                            name.value.text,
                                            funcType.paramType,
                                            funcType.returnType
                                        ),
                                        functionType.remaining
                                    )
                                } else {
                                    stream2.failure("Ability operation '${name.value.text}' must have a function type like {param: Type} -> ReturnType")
                                }
                            }
                            is ParseResult.Failure -> functionType
                        }
                    }
                    is ParseResult.Failure -> colon
                }
            }
            is ParseResult.Failure -> name
        }
    }

    // ===== IMPL ABILITY DECLARATION =====

internal fun FunkParser.parseImplAbilityDeclaration(stream: TokenStream): ParseResult<ProgramItem> {
        // Parse: impl Encoder Int where { operations }
        // Also supports: impl module::Encoder Int where { operations }
        val implToken = tok(TokenType.IMPL)(stream)
        return when (implToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(implToken.remaining)

                // Parse ability name, possibly qualified (e.g., codec::EncodeFormat)
                val firstIdent = identifierParser(stream1)

                when (firstIdent) {
                    is ParseResult.Success -> {
                        // Check for qualified path (module::Ability)
                        var pathStream = firstIdent.remaining
                        val pathSegments = mutableListOf(firstIdent.value.text)

                        while (true) {
                            val doubleColon = tok(TokenType.DOUBLE_COLON)(pathStream)
                            if (doubleColon is ParseResult.Success) {
                                val nextIdent = identifierParser(doubleColon.remaining)
                                if (nextIdent is ParseResult.Success) {
                                    pathSegments.add(nextIdent.value.text)
                                    pathStream = nextIdent.remaining
                                } else {
                                    break
                                }
                            } else {
                                break
                            }
                        }

                        val abilityModulePath = if (pathSegments.size > 1) pathSegments.dropLast(1) else emptyList()
                        val abilityName = pathSegments.last()

                        val stream2 = skipNewlines(pathStream)

                        // Parse target types (one or more)
                        // Use simpleTypeArgument to avoid type applications consuming multiple types
                        val targetTypes = mutableListOf<TypeAnnotation>()
                        var currentStream = stream2

                        // Parse first type
                        val firstType = simpleTypeArgument(currentStream)
                        when (firstType) {
                            is ParseResult.Success -> {
                                targetTypes.add(firstType.value)
                                currentStream = skipNewlines(firstType.remaining)

                                // Parse additional types until we hit 'where' or 'having' keyword
                                while (true) {
                                    // Check if next token is 'where' or 'having' - if so, we're done with types
                                    val nextToken = currentStream.current()
                                    if (nextToken?.type == TokenType.WHERE || nextToken?.type == TokenType.HAVING) {
                                        break
                                    }

                                    // Try to parse another type
                                    val nextType = simpleTypeArgument(currentStream)
                                    when (nextType) {
                                        is ParseResult.Success -> {
                                            targetTypes.add(nextType.value)
                                            currentStream = skipNewlines(nextType.remaining)
                                        }
                                        is ParseResult.Failure -> break
                                    }
                                }

                                val stream3 = skipNewlines(currentStream)

                                // Parse optional 'having' clause with associated type bindings
                                val bindingsResult = parseAssociatedTypeBindings(stream3, abilityName, "impl")
                                val (associatedTypeBindings, streamAfterHaving) = when (bindingsResult) {
                                    is ParseResult.Success -> Pair(bindingsResult.value.bindings, bindingsResult.value.stream)
                                    is ParseResult.Failure -> return bindingsResult
                                }

                                val stream3d = skipNewlines(streamAfterHaving)
                                val whereToken = tok(TokenType.WHERE)(stream3d)

                                when (whereToken) {
                                    is ParseResult.Success -> {
                                        val stream4 = skipNewlines(whereToken.remaining)
                                        val lbrace = tok(TokenType.LBRACE)(stream4)

                                        when (lbrace) {
                                            is ParseResult.Success -> {
                                                val stream5 = skipNewlines(lbrace.remaining)

                                                // Parse operations separated by commas (trailing comma allowed)
                                                val operations = mutableListOf<ImplAbilityOperation>()
                                                var opStream = stream5

                                                // Parse first operation (if any)
                                                when (val firstOp = implAbilityOperationParser(opStream)) {
                                                    is ParseResult.Success -> {
                                                        operations.add(firstOp.value)
                                                        opStream = skipNewlines(firstOp.remaining)

                                                        // Parse remaining operations, each preceded by comma
                                                        while (true) {
                                                            when (val comma = tok(TokenType.COMMA)(opStream)) {
                                                                is ParseResult.Success -> {
                                                                    opStream = skipNewlines(comma.remaining)
                                                                    // Check if this is a trailing comma (next token is })
                                                                    if (opStream.current()?.type == TokenType.RBRACE) {
                                                                        break  // Trailing comma, done
                                                                    }
                                                                    // Parse next operation
                                                                    when (val op = implAbilityOperationParser(opStream)) {
                                                                        is ParseResult.Success -> {
                                                                            operations.add(op.value)
                                                                            opStream = skipNewlines(op.remaining)
                                                                        }
                                                                        is ParseResult.Failure -> {
                                                                            return op  // Return error from operation parsing
                                                                        }
                                                                    }
                                                                }
                                                                is ParseResult.Failure -> {
                                                                    // No comma - check if there's another operation (error) or }
                                                                    val next = opStream.current()
                                                                    if (next?.type == TokenType.IDENTIFIER) {
                                                                        return opStream.failure(
                                                                            "Missing ',' between ability impl methods. " +
                                                                            "Methods must be comma-separated."
                                                                        )
                                                                    }
                                                                    break  // No comma and no identifier, done
                                                                }
                                                            }
                                                        }
                                                    }
                                                    is ParseResult.Failure -> {
                                                        // No operations, that's fine - empty impl
                                                    }
                                                }

                                                val stream6 = skipNewlines(opStream)
                                                val rbrace = tok(TokenType.RBRACE)(stream6)

                                                when (rbrace) {
                                                    is ParseResult.Success -> ParseResult.Success(
                                                        ImplAbilityDeclaration(
                                                            abilityName,
                                                            abilityModulePath,
                                                            targetTypes,
                                                            emptyList(),  // forallVars - deferred for later
                                                            associatedTypeBindings,
                                                            operations,
                                                            inferredType = null,
                                                            sourceLocation = implToken.value.toSourceLocation()
                                                        ),
                                                        rbrace.remaining
                                                    )
                                                    is ParseResult.Failure -> rbrace
                                                }
                                            }
                                            is ParseResult.Failure -> lbrace
                                        }
                                    }
                                    is ParseResult.Failure -> whereToken
                                }
                            }
                            is ParseResult.Failure -> firstType
                        }
                    }
                    is ParseResult.Failure -> firstIdent
                }
            }
            is ParseResult.Failure -> implToken
        }
    }

internal val FunkParser.implAbilityOperationParser: Parser<ImplAbilityOperation> get() = { stream ->
        // Parse: operationName : ParamType -> ReturnType = implementation
        //   or:  operationName = implementation (type inferred from ability declaration)
        val name = identifierParser(stream)
        when (name) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(name.remaining)
                val colon = tok(TokenType.COLON)(stream1)

                when (colon) {
                    is ParseResult.Success -> {
                        // With explicit type annotation
                        val stream2 = skipNewlines(colon.remaining)
                        val functionType = functionTypeAnnotation(stream2)

                        when (functionType) {
                            is ParseResult.Success -> {
                                val funcType = functionType.value as? FunctionTypeAnnotation
                                if (funcType != null) {
                                    val stream3 = skipNewlines(functionType.remaining)
                                    val assign = tok(TokenType.ASSIGN)(stream3)

                                    when (assign) {
                                        is ParseResult.Success -> {
                                            val stream4 = skipNewlines(assign.remaining)
                                            val impl = expression(stream4)

                                            when (impl) {
                                                is ParseResult.Success -> ParseResult.Success(
                                                    ImplAbilityOperation(
                                                        name.value.text,
                                                        funcType.paramType,
                                                        funcType.returnType,
                                                        impl.value
                                                    ),
                                                    impl.remaining
                                                )
                                                is ParseResult.Failure -> impl
                                            }
                                        }
                                        is ParseResult.Failure -> assign
                                    }
                                } else {
                                    stream2.failure("Ability impl operation '${name.value.text}' must have a function type like {param: Type} -> ReturnType")
                                }
                            }
                            is ParseResult.Failure -> functionType
                        }
                    }
                    is ParseResult.Failure -> {
                        // No colon, try without type annotation
                        val assign = tok(TokenType.ASSIGN)(stream1)
                        when (assign) {
                            is ParseResult.Success -> {
                                val stream2 = skipNewlines(assign.remaining)
                                val impl = expression(stream2)

                                when (impl) {
                                    is ParseResult.Success -> ParseResult.Success(
                                        ImplAbilityOperation(
                                            name.value.text,
                                            null,  // Type will be inferred by typechecker
                                            null,  // Type will be inferred by typechecker
                                            impl.value
                                        ),
                                        impl.remaining
                                    )
                                    is ParseResult.Failure -> impl
                                }
                            }
                            is ParseResult.Failure -> assign
                        }
                    }
                }
            }
            is ParseResult.Failure -> name
        }
    }

    // ===== USE DECLARATION =====

internal fun FunkParser.parseUseDeclaration(stream: TokenStream, visibility: Visibility): ParseResult<ProgramItem> {
        // Parse: use std::math::basic
        //   or:  use std::math::{trig, random}
        //   or:  use std::math::basic as m
        val useToken = tok(TokenType.USE)(stream)
        return when (useToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(useToken.remaining)

                // Parse the path segments separated by ::
                val pathSegments = mutableListOf<String>()
                var currentStream = stream1

                // Parse first segment (required)
                val firstSegment = identifierParser(currentStream)
                when (firstSegment) {
                    is ParseResult.Success -> {
                        pathSegments.add(firstSegment.value.text)
                        currentStream = firstSegment.remaining

                        // Parse remaining segments (segment ::segment ::segment)
                        @Suppress("ControlFlowWithEmptyBody")
                        while (true) {
                            val doubleColon = tok(TokenType.DOUBLE_COLON)(currentStream)
                            when (doubleColon) {
                                is ParseResult.Success -> {
                                    val nextStream = skipNewlines(doubleColon.remaining)

                                    // Check if this is a selective import { ... }
                                    val lbrace = tok(TokenType.LBRACE)(nextStream)
                                    if (lbrace is ParseResult.Success) {
                                        // Selective import: parse {item1, item2, ...}
                                        val stream2 = skipNewlines(lbrace.remaining)
                                        val items = sepByWithNewlines(
                                            importItemParser,
                                            tok(TokenType.COMMA)
                                        )(stream2)

                                        return when (items) {
                                            is ParseResult.Success -> {
                                                val stream3 = skipNewlines(items.remaining)
                                                val rbrace = tok(TokenType.RBRACE)(stream3)

                                                when (rbrace) {
                                                    is ParseResult.Success -> {
                                                        val path = SelectivePath(pathSegments, items.value)
                                                        ParseResult.Success(
                                                            if (visibility == Visibility.PUBLIC) {
                                                                PubUseDeclaration(path, null)
                                                            } else {
                                                                UseDeclaration(path, null)
                                                            },
                                                            rbrace.remaining
                                                        )
                                                    }
                                                    is ParseResult.Failure -> rbrace
                                                }
                                            }
                                            is ParseResult.Failure -> items
                                        }
                                    }

                                    // Not selective import, parse next segment
                                    val nextSegment = identifierParser(nextStream)
                                    when (nextSegment) {
                                        is ParseResult.Success -> {
                                            pathSegments.add(nextSegment.value.text)
                                            currentStream = nextSegment.remaining
                                            // Continue loop
                                        }
                                        is ParseResult.Failure -> return nextSegment
                                    }
                                }
                                is ParseResult.Failure -> {
                                    // No more ::, check for optional alias
                                    val streamAfterPath = skipNewlines(currentStream)
                                    val asKeyword = keyword("as", TokenType.IDENTIFIER)(streamAfterPath)

                                    val alias = when (asKeyword) {
                                        is ParseResult.Success -> {
                                            val stream2 = skipNewlines(asKeyword.remaining)
                                            val aliasName = identifierParser(stream2)
                                            when (aliasName) {
                                                is ParseResult.Success -> {
                                                    currentStream = aliasName.remaining
                                                    aliasName.value.text
                                                }
                                                is ParseResult.Failure -> return aliasName
                                            }
                                        }
                                        is ParseResult.Failure -> null
                                    }

                                    // Create the module path
                                    val path = ModulePath(pathSegments)
                                    return ParseResult.Success(
                                        if (visibility == Visibility.PUBLIC) {
                                            PubUseDeclaration(path, alias)
                                        } else {
                                            UseDeclaration(path, alias)
                                        },
                                        currentStream
                                    )
                                }
                            }
                        }

                        @Suppress("UNREACHABLE_CODE")
                        stream.failure("Unexpected error in use declaration")
                    }
                    is ParseResult.Failure -> firstSegment
                }
            }
            is ParseResult.Failure -> useToken
        }
    }

internal val FunkParser.importItemParser: Parser<ImportItem> get() = { stream ->
        // Parse: name or name as alias
        val name = identifierParser(stream)
        when (name) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(name.remaining)
                val asKeyword = keyword("as", TokenType.IDENTIFIER)(stream1)

                when (asKeyword) {
                    is ParseResult.Success -> {
                        // Has alias
                        val stream2 = skipNewlines(asKeyword.remaining)
                        val aliasName = identifierParser(stream2)

                        when (aliasName) {
                            is ParseResult.Success -> ParseResult.Success(
                                ImportItem(name.value.text, aliasName.value.text),
                                aliasName.remaining
                            )
                            is ParseResult.Failure -> aliasName
                        }
                    }
                    is ParseResult.Failure -> {
                        // No alias
                        ParseResult.Success(
                            ImportItem(name.value.text, null),
                            stream1
                        )
                    }
                }
            }
            is ParseResult.Failure -> name
        }
    }
