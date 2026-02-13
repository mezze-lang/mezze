package com.funk.language.parser.internal

import com.funk.language.ast.*
import com.funk.language.parser.*

import com.funk.language.parser.internal.lib.*
/**
 * Expression parsers for all expression constructs.
 *
 * This file contains internal parsers for:
 * - Lambda expressions ({ x } -> body)
 * - Record literals ({ x = 1, y = 2 })
 * - Array literals ([1, 2, 3])
 * - Primary expressions (literals, identifiers, parenthesized)
 * - If expressions (if cond then branch else branch)
 * - Perform expressions (effect calls and handlers)
 * - Do expressions (sequencing statements)
 * - Match expressions (pattern matching)
 * - Operator expressions (binary, unary, pipe, postfix)
 *
 * All parsers return Expression (sealed interface from AST).
 * All functions are internal - only used within the parser module.
 *
 * Part of the refactored parser module - previously in FunkParser.kt.
 */

// ===== LAMBDA VS RECORD DISAMBIGUATION =====

/**
 * Lookahead function to check if a brace starts a record literal.
 * A record literal has the pattern: { identifier = expr, ... } or { identifier, ... } (punning)
 * or empty record: {}
 *
 * This helps distinguish between:
 * - Function call: foo {args}  (should error if on different line)
 * - Record literal: {x = 1, y = 2}  (valid standalone expression)
 */
internal fun FunkParser.lookAheadForRecordLiteral(stream: TokenStream): Boolean {
    var pos = stream.position
    val tokens = stream.tokens

    if (pos >= tokens.size || tokens[pos].type != TokenType.LBRACE) return false
    pos++  // Skip LBRACE

    // Skip newlines after LBRACE
    while (pos < tokens.size && tokens[pos].type == TokenType.NEWLINE) {
        pos++
    }

    // Empty record {} is a record literal
    if (pos < tokens.size && tokens[pos].type == TokenType.RBRACE) {
        return true
    }

    // Check for identifier followed by = (record field) or , or } (punning)
    if (pos < tokens.size && tokens[pos].type == TokenType.IDENTIFIER) {
        pos++
        // Skip newlines
        while (pos < tokens.size && tokens[pos].type == TokenType.NEWLINE) {
            pos++
        }
        if (pos < tokens.size) {
            val nextType = tokens[pos].type
            // { identifier = ... } is a record literal
            // { identifier, ... } is a record literal with punning
            // { identifier } is a record literal with punning
            if (nextType == TokenType.ASSIGN || nextType == TokenType.COMMA || nextType == TokenType.RBRACE) {
                return true
            }
        }
    }

    return false
}

/**
 * Lookahead function to check if there's an arrow after the closing brace.
 * This is the explicit disambiguation mechanism between lambdas and record literals.
 */
internal fun FunkParser.lookAheadForArrowAfterBrace(stream: TokenStream): Boolean {
    var pos = stream.position
    val tokens = stream.tokens

    if (pos >= tokens.size || tokens[pos].type != TokenType.LBRACE) return false
    pos++  // Skip LBRACE

    var braceDepth = 1

    // Find matching RBRACE
    while (pos < tokens.size && braceDepth > 0) {
        when (tokens[pos].type) {
            TokenType.LBRACE -> braceDepth++
            TokenType.RBRACE -> braceDepth--
            TokenType.EOF -> return false
            else -> {}
        }
        pos++
    }

    // Now pos is after RBRACE, check if next non-newline token is ARROW
    while (pos < tokens.size && tokens[pos].type == TokenType.NEWLINE) {
        pos++
    }

    val result = pos < tokens.size && tokens[pos].type == TokenType.ARROW
    // Debug logging
    if (System.getProperty("parser.debug") == "true") {
        println("[lookAheadForArrowAfterBrace] Looking at position ${stream.position}, found arrow: $result")
    }
    return result
}

/**
 * Lambda or record expression parser.
 * This is the key to solving the ambiguity!
 *
 * We explicitly check for ARROW after RBRACE to distinguish:
 * - {x} -> body  (lambda)
 * - {x}          (record literal with punning)
 */
internal val FunkParser.lambdaOrRecordExpression: Parser<Expression>
    get() = { stream ->
        if (stream.current()?.type != TokenType.LBRACE) {
            stream.failure("Expected '{' to start a lambda or record literal, but got ${tokenGotDescription(stream.current())}")
        } else {
            // Look ahead to find the matching RBRACE and check if there's an ARROW after it
            val hasArrowAfterBrace = lookAheadForArrowAfterBrace(stream)

            if (hasArrowAfterBrace) {
                // It's a lambda!
                lambdaExpression(stream)
            } else {
                // It's a record literal
                recordLiteral(stream)
            }
        }
    }

// ===== LAMBDA EXPRESSION =====

internal val FunkParser.lambdaExpression: Parser<Expression>
    get() = parser@{ stream ->
        // Parse {params} -> body

        // Parse opening brace
        when (val lbrace = tok(TokenType.LBRACE)(stream)) {
            is ParseResult.Failure -> return@parser lbrace
            is ParseResult.Success -> {
                val lambdaLocation = tokenLocation(lbrace.value, currentFile)
                val stream1 = skipNewlines(lbrace.remaining)

                // Parse parameters - could be typed or untyped
                when (val paramsResult = lambdaParameters(stream1)) {
                    is ParseResult.Failure -> return@parser paramsResult
                    is ParseResult.Success -> {
                        // Parse closing brace
                        val stream2 = skipNewlines(paramsResult.remaining)
                        when (val rbrace = tok(TokenType.RBRACE)(stream2)) {
                            is ParseResult.Failure -> return@parser rbrace
                            is ParseResult.Success -> {
                                // Parse arrow
                                val stream3 = skipNewlines(rbrace.remaining)
                                when (val arrow = tok(TokenType.ARROW)(stream3)) {
                                    is ParseResult.Failure -> return@parser arrow
                                    is ParseResult.Success -> {
                                        // Parse body
                                        val stream4 = skipNewlines(arrow.remaining)
                                        when (val body = expression(stream4)) {
                                            is ParseResult.Failure -> return@parser body
                                            is ParseResult.Success -> ParseResult.Success(
                                                LambdaExpression(paramsResult.value, body.value, sourceLocation = lambdaLocation),
                                                body.remaining
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

internal val FunkParser.lambdaParameters: Parser<LambdaParameters>
    get() = { stream ->
        // Try typed parameters first (they have colons)
        val typedResult = typedLambdaParams(stream)
        when (typedResult) {
            is ParseResult.Success -> typedResult
            is ParseResult.Failure -> untypedLambdaParams(stream)
        }
    }

// Parser for a single lambda parameter entry with optional type annotation
// Accepts: x, x: Int, x: String, etc.
internal val FunkParser.lambdaParameterEntry: Parser<Pair<String, TypeAnnotation?>>
    get() = { stream ->
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                val paramName = ident.value.text
                val stream1 = ident.remaining

                // Check if there's a colon (type annotation)
                if (stream1.current()?.type == TokenType.COLON) {
                    // Parse type annotation
                    val colon = tok(TokenType.COLON)(stream1)
                    when (colon) {
                        is ParseResult.Success -> {
                            val typeAnnotation = typeAnnotationParser(colon.remaining)
                            when (typeAnnotation) {
                                is ParseResult.Success -> {
                                    ParseResult.Success(
                                        Pair(paramName, typeAnnotation.value),
                                        typeAnnotation.remaining
                                    )
                                }
                                is ParseResult.Failure -> typeAnnotation
                            }
                        }
                        is ParseResult.Failure -> colon
                    }
                } else {
                    // No type annotation - this is an untyped parameter
                    ParseResult.Success(Pair(paramName, null), stream1)
                }
            }
            is ParseResult.Failure -> ident
        }
    }

internal val FunkParser.typedLambdaParams: Parser<LambdaParameters>
    get() = { stream ->
        // {x: Int, y, z: String,} - allows mixed typed/untyped parameters with optional trailing comma
        // At least one parameter must have a type annotation for this to be considered typed
        val entries = Parsers.sepEndBy(lambdaParameterEntry, tok(TokenType.COMMA))(stream)
        when (entries) {
            is ParseResult.Success -> {
                val entryList = entries.value
                if (entryList.isEmpty()) {
                    // No parameters found
                    stream.failure("Expected at least one parameter in lambda. Use {x} -> expr or {x: Type} -> expr")
                } else {
                    // Check if at least one parameter has a type annotation
                    val hasTypedParam = entryList.any { it.second != null }
                    if (hasTypedParam) {
                        // Convert to RecordTypeEntry list, generating inferred type annotations for untyped params
                        val recordEntries = entryList.map { (name, typeAnnotation) ->
                            val finalType = typeAnnotation ?: IdentifierTypeAnnotation("'${name}")
                            RecordTypeEntry(name, finalType)
                        }
                        val recordType = RecordTypeAnnotation(recordEntries, null)
                        ParseResult.Success(TypedParameters(recordType), entries.remaining)
                    } else {
                        // All parameters are untyped, fail so we can try untyped parser
                        stream.failure("When using type annotations, at least one parameter must have a type. Use {x: Int} -> expr")
                    }
                }
            }
            is ParseResult.Failure -> entries
        }
    }

internal val FunkParser.untypedLambdaParams: Parser<LambdaParameters>
    get() = { stream ->
        // {x, y,} or {x, y, ...,} or {} - allows optional trailing comma
        val params = Parsers.sepEndBy(identifierParser, tok(TokenType.COMMA))(stream)
        when (params) {
            is ParseResult.Success -> {
                val names = params.value.map { it.text }
                val stream1 = params.remaining

                // Check for optional comma before ellipsis (same logic as recordPatternParser)
                val afterComma = if (stream1.current()?.type == TokenType.COMMA) {
                    skipNewlines(stream1.advance())
                } else {
                    stream1
                }

                // Check for ellipsis (open record marker: ...)
                val isOpen = afterComma.current()?.type == TokenType.ELLIPSIS

                val stream2 = if (isOpen) {
                    skipNewlines(afterComma.advance())  // Skip the ellipsis token
                } else {
                    afterComma
                }

                ParseResult.Success(UntypedParameters(names, isOpen), stream2)
            }
            is ParseResult.Failure -> params
        }
    }

// ===== RECORD LITERAL =====

internal val FunkParser.recordLiteral: Parser<Expression>
    get() = { stream ->
        // {x = 1, y = 2} or {x, y} (punning) or {...rest} or {x = 1, ...} (anonymous open record)
        // Supports multiline with newlines between entries
        val lbrace = tok(TokenType.LBRACE)(stream)
        when (lbrace) {
            is ParseResult.Success -> {
                val recordLocation = tokenLocation(lbrace.value, currentFile)
                val stream1 = skipNewlines(lbrace.remaining)

                // Use custom sepEndBy that skips newlines after commas and allows trailing commas
                val entries = sepEndByWithNewlines(
                    recordLiteralEntryParser,
                    tok(TokenType.COMMA)
                )(stream1)

                when (entries) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(entries.remaining)

                        // Check for trailing ellipsis to mark as open record: {x = 1, ...}
                        // Note: sepEndBy already consumed any trailing comma
                        var isOpen = false
                        var streamAfterEllipsis = stream2

                        val ellipsisCheck = tok(TokenType.ELLIPSIS)(stream2)
                        if (ellipsisCheck is ParseResult.Success) {
                            isOpen = true
                            streamAfterEllipsis = skipNewlines(ellipsisCheck.remaining)
                        }

                        val rbrace = tok(TokenType.RBRACE)(streamAfterEllipsis)

                        when (rbrace) {
                            is ParseResult.Success -> ParseResult.Success(
                                RecordLiteral(entries.value, if (isOpen) "..." else null, sourceLocation = recordLocation),
                                rbrace.remaining
                            )
                            is ParseResult.Failure -> rbrace
                        }
                    }
                    is ParseResult.Failure -> entries
                }
            }
            is ParseResult.Failure -> lbrace
        }
    }

internal val FunkParser.recordLiteralEntryParser: Parser<RecordLiteralEntry>
    get() = { stream ->
        // ... (spread), x = value, or x (punning)
        val current = stream.current()

        if (current?.type == TokenType.ELLIPSIS) {
            // Spread syntax
            val ellipsis = tok(TokenType.ELLIPSIS)(stream)
            when (ellipsis) {
                is ParseResult.Success -> {
                    val expr = expression(ellipsis.remaining)
                    when (expr) {
                        is ParseResult.Success -> ParseResult.Success(
                            RecordSpreadEntry(expr.value),
                            expr.remaining
                        )
                        is ParseResult.Failure -> expr
                    }
                }
                is ParseResult.Failure -> ellipsis
            }
        } else {
            // x = value or x (punning)
            val ident = identifierParser(stream)
            when (ident) {
                is ParseResult.Success -> {
                    val stream1 = ident.remaining

                    // Check for ASSIGN
                    val assign = tok(TokenType.ASSIGN)(stream1)
                    when (assign) {
                        is ParseResult.Success -> {
                            // x = value
                            val expr = expression(assign.remaining)
                            when (expr) {
                                is ParseResult.Success -> ParseResult.Success(
                                    RecordFieldEntry(ident.value.text, expr.value),
                                    expr.remaining
                                )
                                is ParseResult.Failure -> expr
                            }
                        }
                        is ParseResult.Failure -> {
                            // Punning: x means {x} with value = null
                            ParseResult.Success(
                                RecordFieldEntry(ident.value.text, null),
                                stream1
                            )
                        }
                    }
                }
                is ParseResult.Failure -> ident
            }
        }
    }

// ===== ARRAY LITERAL =====

internal val FunkParser.arrayLiteral: Parser<Expression>
    get() = { stream ->
        // Parse: [element1, element2, ...]
        // Supports spread only at the start: [...xs, a, b, c]
        // NOT allowed: [a, ...xs] or [a, ...xs, b]
        val arrayLocation = stream.current()?.let { tokenLocation(it, currentFile) }  // Capture [ token location
        val lbracket = tok(TokenType.LBRACKET)(stream)
        when (lbracket) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(lbracket.remaining)

                // Check for empty array []
                val rbracket = tok(TokenType.RBRACKET)(stream1)
                if (rbracket is ParseResult.Success) {
                    ParseResult.Success(
                        ArrayLiteral(emptyList<ArrayElement>(), sourceLocation = arrayLocation),
                        rbracket.remaining
                    )
                } else {
                    // Parse elements separated by commas (with optional trailing comma)
                    val elements = sepEndByWithNewlines(
                        arrayElementParser,
                        tok(TokenType.COMMA)
                    )(stream1)

                    when (elements) {
                        is ParseResult.Success -> {
                            // Validate spread position: spreads must come before regular elements
                            validateArraySpreadPosition(elements.value, arrayLocation)

                            val stream2 = skipNewlines(elements.remaining)
                            val rbracket2 = tok(TokenType.RBRACKET)(stream2)

                            when (rbracket2) {
                                is ParseResult.Success -> ParseResult.Success(
                                    ArrayLiteral(elements.value, sourceLocation = arrayLocation),
                                    rbracket2.remaining
                                )
                                is ParseResult.Failure -> {
                                    val token = stream2.current()
                                    stream2.failure("Expected ',' between array elements or ']' to close array, but got ${tokenGotDescription(token)}")
                                }
                            }
                        }
                        is ParseResult.Failure -> elements
                    }
                }
            }
            is ParseResult.Failure -> lbracket
        }
    }

/**
 * Validate that array spread elements only appear at the beginning.
 * Valid: [...xs, a, b, c] or [...xs]
 * Invalid: [a, ...xs] or [a, ...xs, b]
 */
private fun validateArraySpreadPosition(elements: List<ArrayElement>, location: SourceLocation?) {
    var seenRegular = false
    for (element in elements) {
        when (element) {
            is ArrayRegularElement -> seenRegular = true
            is ArraySpreadElement -> {
                if (seenRegular) {
                    throw ParseException(
                        "Array spread must come before regular elements. Use [...arr, elem1, elem2] syntax.",
                        position = 0,
                        line = location?.line ?: 0,
                        column = location?.column ?: 0
                    )
                }
            }
        }
    }
}

internal val FunkParser.arrayElementParser: Parser<ArrayElement>
    get() = { stream ->
        val current = stream.current()

        if (current?.type == TokenType.ELLIPSIS) {
            // Spread syntax: ...expr
            val ellipsis = tok(TokenType.ELLIPSIS)(stream)
            when (ellipsis) {
                is ParseResult.Success -> {
                    val expr = expression(ellipsis.remaining)
                    when (expr) {
                        is ParseResult.Success -> ParseResult.Success(
                            ArraySpreadElement(expr.value),
                            expr.remaining
                        )
                        is ParseResult.Failure -> expr
                    }
                }
                is ParseResult.Failure -> ellipsis
            }
        } else {
            // Regular element
            val expr = expression(stream)
            when (expr) {
                is ParseResult.Success -> ParseResult.Success(
                    ArrayRegularElement(expr.value),
                    expr.remaining
                )
                is ParseResult.Failure -> expr
            }
        }
    }

// ===== PRIMARY EXPRESSION =====

internal val FunkParser.primaryExpression: Parser<Expression>
    get() = { stream ->
        Parsers.oneOf(
            performExpression,
            doExpression,
            matchExpression,
            ifExpression,
            literalExpression,
            atPlaceholderExpression,  // @ placeholder for pipe operator
            identifierExpression,
            arrayLiteral,
            lambdaOrRecordExpression,  // <-- THIS IS THE KEY!
            parenExpression
        )(stream)
    }

// ===== IF EXPRESSION =====

internal val FunkParser.ifExpression: Parser<Expression>
    get() = parser@{ stream ->
        // Parse: if <condition> then <thenBranch> else <elseBranch>
        val ifLocation = stream.current()?.let { tokenLocation(it, currentFile) }

        // Parse 'if' keyword
        when (val ifToken = tok(TokenType.IF)(stream)) {
            is ParseResult.Failure -> return@parser ifToken
            is ParseResult.Success -> {
                // Parse condition
                val stream1 = skipNewlines(ifToken.remaining)
                when (val condition = expression(stream1)) {
                    is ParseResult.Failure -> return@parser condition
                    is ParseResult.Success -> {
                        // Parse 'then' keyword
                        val stream2 = skipNewlines(condition.remaining)
                        when (val thenToken = tok(TokenType.THEN)(stream2)) {
                            is ParseResult.Failure -> return@parser thenToken
                            is ParseResult.Success -> {
                                // Parse then branch
                                val stream3 = skipNewlines(thenToken.remaining)
                                when (val thenBranch = expression(stream3)) {
                                    is ParseResult.Failure -> return@parser thenBranch
                                    is ParseResult.Success -> {
                                        // Parse 'else' keyword
                                        val stream4 = skipNewlines(thenBranch.remaining)
                                        when (val elseToken = tok(TokenType.ELSE)(stream4)) {
                                            is ParseResult.Failure -> return@parser elseToken
                                            is ParseResult.Success -> {
                                                // Parse else branch
                                                val stream5 = skipNewlines(elseToken.remaining)
                                                when (val elseBranch = expression(stream5)) {
                                                    is ParseResult.Failure -> return@parser elseBranch
                                                    is ParseResult.Success -> ParseResult.Success(
                                                        IfExpression(
                                                            condition.value,
                                                            thenBranch.value,
                                                            elseBranch.value,
                                                            sourceLocation = ifLocation
                                                        ),
                                                        elseBranch.remaining
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

// TO BE CONTINUED...

// ===== PERFORM EXPRESSION =====

internal val FunkParser.performExpression: Parser<Expression>
    get() = { stream ->
        // Parse two forms:
        // 1. perform operation {args} - simple effect call
        // 2. perform Handler1, Handler2 in expression - perform with handlers
        val performLocation = stream.current()?.let { tokenLocation(it, currentFile) }  // Capture perform keyword location
        val performToken = tok(TokenType.PERFORM)(stream)
        when (performToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(performToken.remaining)

                // Parse first identifier
                val firstId = identifierParser(stream1)
                when (firstId) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(firstId.remaining)

                        // Try to parse optional parameters for first handler
                        val firstParamsResult = recordLiteral(stream2)
                        val (firstParams, stream3) = when (firstParamsResult) {
                            is ParseResult.Success -> Pair(firstParamsResult.value as RecordLiteral, firstParamsResult.remaining)
                            is ParseResult.Failure -> Pair(null, stream2)
                        }

                        val streamAfterParams = skipNewlines(stream3)
                        val nextToken = streamAfterParams.current()

                        if (nextToken?.type == TokenType.COMMA || nextToken?.type == TokenType.IN) {
                            // Form 2: perform Handler1, Handler2 in expression
                            val handlers = mutableListOf<PerformHandler>()
                            handlers.add(PerformHandler(firstId.value.text, firstParams))
                            var currentStream = streamAfterParams

                            // Parse remaining handlers if there are commas
                            var errorResult: ParseResult.Failure? = null
                            while (errorResult == null) {
                                val commaToken = tok(TokenType.COMMA)(currentStream)
                                when (commaToken) {
                                    is ParseResult.Success -> {
                                        val stream3 = skipNewlines(commaToken.remaining)
                                        val handlerName = identifierParser(stream3)
                                        when (handlerName) {
                                            is ParseResult.Success -> {
                                                val stream4 = skipNewlines(handlerName.remaining)

                                                // Check for optional params
                                                val paramsResult = recordLiteral(stream4)
                                                val (params, stream5) = when (paramsResult) {
                                                    is ParseResult.Success -> Pair(paramsResult.value as RecordLiteral, paramsResult.remaining)
                                                    is ParseResult.Failure -> Pair(null, stream4)
                                                }

                                                handlers.add(PerformHandler(handlerName.value.text, params))
                                                currentStream = skipNewlines(stream5)
                                            }
                                            is ParseResult.Failure -> errorResult = handlerName
                                        }
                                    }
                                    is ParseResult.Failure -> break
                                }
                            }

                            if (errorResult != null) {
                                errorResult
                            } else {
                                // Parse 'in' keyword
                                val inToken = tok(TokenType.IN)(currentStream)
                                when (inToken) {
                                    is ParseResult.Success -> {
                                        val stream6 = skipNewlines(inToken.remaining)
                                        val expr = expression(stream6)
                                        when (expr) {
                                            is ParseResult.Success -> ParseResult.Success(
                                                PerformExpression(expr.value, handlers, sourceLocation = performLocation),
                                                expr.remaining
                                            )
                                            is ParseResult.Failure -> expr
                                        }
                                    }
                                    is ParseResult.Failure -> inToken
                                }
                            }
                        } else {
                            // Form 1: perform operation {args} - simple effect call
                            val argsResult = recordLiteral(stream2)
                            when (argsResult) {
                                is ParseResult.Success -> ParseResult.Success(
                                    EffectCallExpression("", firstId.value.text, argsResult.value as RecordLiteral, sourceLocation = performLocation),
                                    argsResult.remaining
                                )
                                is ParseResult.Failure -> argsResult
                            }
                        }
                    }
                    is ParseResult.Failure -> firstId
                }
            }
            is ParseResult.Failure -> performToken
        }
    }

    // ===== DO EXPRESSION =====

internal val FunkParser.doExpression: Parser<Expression>
    get() = { stream ->
        // Parse: do <statement>* <result-expression>
        val doLocation = stream.current()?.let { tokenLocation(it, currentFile) }  // Capture do keyword location
        val doToken = tok(TokenType.DO)(stream)
        when (doToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(doToken.remaining)

                // Parse statements (let statements and expression statements) and final result expression
                val statements = mutableListOf<Statement>()
                var currentStream = stream1
                var finalResult: Pair<Expression, TokenStream>? = null

                // Track the column of the first token in the do block to determine scope
                val doBlockColumn = stream1.current()?.column

                // Parse statements until we find the final result expression
                while (finalResult == null) {
                    // Check if we've left the do block scope (token at lower indentation)
                    val currentToken = currentStream.current()
                    if (currentToken != null && doBlockColumn != null && currentToken.column < doBlockColumn && currentToken.type != TokenType.EOF) {
                        break
                    }

                    // Try to parse a let statement
                    val letToken = tok(TokenType.LET)(currentStream)
                    if (letToken is ParseResult.Success) {
                        // Parse let statement with pattern support
                        val stream2 = skipNewlines(letToken.remaining)
                        val patternResult = letBindingPattern(stream2)

                        when (patternResult) {
                            is ParseResult.Success -> {
                                val pattern = patternResult.value
                                val stream3 = skipNewlines(patternResult.remaining)

                                // Validate that the pattern is irrefutable
                                if (!pattern.isIrrefutable()) {
                                    // Skip this - refutable patterns not allowed in let statements
                                    break
                                }

                                // Optional type annotation
                                val colonToken = tok(TokenType.COLON)(stream3)
                                val (typeAnnotation, streamAfterType) = when (colonToken) {
                                    is ParseResult.Success -> {
                                        val stream4 = skipNewlines(colonToken.remaining)
                                        val typeAnn = typeAnnotationParser(stream4)
                                        when (typeAnn) {
                                            is ParseResult.Success -> Pair(typeAnn.value, typeAnn.remaining)
                                            is ParseResult.Failure -> break
                                        }
                                    }
                                    is ParseResult.Failure -> Pair(null, stream3)
                                }

                                val stream5 = skipNewlines(streamAfterType)
                                val assign = tok(TokenType.ASSIGN)(stream5)

                                when (assign) {
                                    is ParseResult.Success -> {
                                        val stream6 = skipNewlines(assign.remaining)
                                        val expr = expression(stream6)

                                        when (expr) {
                                            is ParseResult.Success -> {
                                                statements.add(
                                                    LetStatement(
                                                        pattern,
                                                        typeAnnotation,
                                                        expr.value
                                                    )
                                                )
                                                currentStream = skipNewlines(expr.remaining)
                                            }
                                            is ParseResult.Failure -> break
                                        }
                                    }
                                    is ParseResult.Failure -> break
                                }
                            }
                            is ParseResult.Failure -> break
                        }
                    } else {
                        // Not a let statement, try to parse an expression
                        val expr = expression(currentStream)
                        when (expr) {
                            is ParseResult.Success -> {
                                val streamAfterExpr = skipNewlines(expr.remaining)

                                // Check if there's more content in this do block after this expression
                                // If the next token is within the do block scope (higher column), it's a statement
                                // Otherwise, this expression is the final result
                                val nextToken = streamAfterExpr.current()

                                // Tokens that terminate a do block even if they're at the right indentation
                                // (e.g., comma in impl blocks, closing braces, etc.)
                                val isTerminatingToken = nextToken?.type in setOf(
                                    TokenType.COMMA,
                                    TokenType.RBRACE,
                                    TokenType.RPAREN,
                                    TokenType.RBRACKET
                                )

                                if (nextToken != null && nextToken.type != TokenType.EOF && doBlockColumn != null && nextToken.column >= doBlockColumn && !isTerminatingToken) {
                                    // Next token is still inside the do block, so current expression is a statement
                                    statements.add(ExpressionStatement(expr.value))
                                    currentStream = streamAfterExpr
                                } else {
                                    // Next token is outside do block or at EOF, this is the final result
                                    finalResult = Pair(expr.value, streamAfterExpr)
                                }
                            }
                            is ParseResult.Failure -> {
                                // Can't parse an expression, we're done
                                break
                            }
                        }
                    }
                }

                // Use the final result expression we found
                if (finalResult != null) {
                    ParseResult.Success(
                        DoExpression(statements, finalResult.first, sourceLocation = doLocation),
                        finalResult.second
                    )
                } else {
                    currentStream.failure("Do block must end with a result expression. Example:\n  do\n    let x = 1\n    x + 1")
                }
            }
            is ParseResult.Failure -> {
                doToken
            }
        }
    }

    // ===== MATCH EXPRESSION =====

internal val FunkParser.matchExpression: Parser<Expression>
    get() = { stream ->
        // Parse: match <expr> is <case1> <case2> ...
        val matchLocation = stream.current()?.let { tokenLocation(it, currentFile) }  // Capture match keyword location
        val matchToken = tok(TokenType.MATCH)(stream)
        when (matchToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(matchToken.remaining)
                val expr = expression(stream1)

                when (expr) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(expr.remaining)
                        val isToken = tok(TokenType.IS)(stream2)

                        when (isToken) {
                            is ParseResult.Success -> {
                                val stream3 = skipNewlines(isToken.remaining)

                                // Track the column of the first match case to determine scope
                                val firstCaseColumn = stream3.current()?.column

                                // Parse match cases (at least one required)
                                val firstCase = matchCase(stream3)

                                when (firstCase) {
                                    is ParseResult.Success -> {
                                        val cases = mutableListOf(firstCase.value)
                                        var currentStream = skipNewlines(firstCase.remaining)

                                        // Parse additional cases while they're at the same or greater indentation
                                        while (true) {
                                            val currentToken = currentStream.current()

                                            // Stop if:
                                            // 1. We've reached EOF, or
                                            // 2. The token is dedented below the first case column
                                            if (currentToken == null ||
                                                currentToken.type == TokenType.EOF ||
                                                (firstCaseColumn != null && currentToken.column < firstCaseColumn)) {
                                                break
                                            }

                                            // Try to parse another case
                                            val nextCase = matchCase(currentStream)
                                            when (nextCase) {
                                                is ParseResult.Success -> {
                                                    cases.add(nextCase.value)
                                                    currentStream = skipNewlines(nextCase.remaining)
                                                }
                                                is ParseResult.Failure -> {
                                                    // Can't parse another case, we're done
                                                    break
                                                }
                                            }
                                        }

                                        ParseResult.Success(
                                            MatchExpression(
                                                expr.value,
                                                cases,
                                                sourceLocation = matchLocation
                                            ),
                                            currentStream
                                        )
                                    }
                                    is ParseResult.Failure -> firstCase
                                }
                            }
                            is ParseResult.Failure -> isToken
                        }
                    }
                    is ParseResult.Failure -> expr
                }
            }
            is ParseResult.Failure -> matchToken
        }
    }

internal val FunkParser.matchCase: Parser<MatchCase>
    get() = { stream ->
        // Parse: <pattern> [| <pattern>]* [if <guard>] -> <expression>
        // Skip leading newlines before pattern
        val stream1 = skipNewlines(stream)
        val firstPattern = patternParser(stream1)

        when (firstPattern) {
            is ParseResult.Success -> {
                // Parse additional patterns separated by |
                val patterns = mutableListOf(firstPattern.value)
                var currentStream = skipNewlines(firstPattern.remaining)

                // Keep parsing | pattern
                while (currentStream.current()?.type == TokenType.PIPE) {
                    val afterPipe = skipNewlines(currentStream.advance())
                    val nextPattern = patternParser(afterPipe)

                    when (nextPattern) {
                        is ParseResult.Success -> {
                            patterns.add(nextPattern.value)
                            currentStream = skipNewlines(nextPattern.remaining)
                        }
                        is ParseResult.Failure -> {
                            // Stop parsing patterns, break out
                            break
                        }
                    }
                }

                val stream2 = currentStream

                // Check for optional guard: if <expression>
                val ifToken = keyword("if", TokenType.IF)(stream2)

                when (ifToken) {
                    is ParseResult.Success -> {
                        // Parse guard expression
                        val stream3 = skipNewlines(ifToken.remaining)
                        val guardExp = expression(stream3)

                        when (guardExp) {
                            is ParseResult.Success -> {
                                // Have guard, now parse -> and result expression
                                val streamBeforeArrow = skipNewlines(guardExp.remaining)
                                val arrow = tok(TokenType.ARROW)(streamBeforeArrow)

                                when (arrow) {
                                    is ParseResult.Success -> {
                                        val stream4 = skipNewlines(arrow.remaining)
                                        val expr = expression(stream4)

                                        when (expr) {
                                            is ParseResult.Success -> ParseResult.Success(
                                                MatchCase(
                                                    patterns,
                                                    guardExp.value,  // Guard expression
                                                    expr.value
                                                ),
                                                expr.remaining
                                            )
                                            is ParseResult.Failure -> expr
                                        }
                                    }
                                    is ParseResult.Failure -> arrow
                                }
                            }
                            is ParseResult.Failure -> guardExp
                        }
                    }
                    is ParseResult.Failure -> {
                        // No guard, parse directly -> result
                        val streamBeforeArrow = skipNewlines(stream2)
                        val arrow = tok(TokenType.ARROW)(streamBeforeArrow)

                        when (arrow) {
                            is ParseResult.Success -> {
                                val stream3 = skipNewlines(arrow.remaining)
                                val expr = expression(stream3)

                                when (expr) {
                                    is ParseResult.Success -> ParseResult.Success(
                                        MatchCase(
                                            patterns,
                                            null,  // No guard
                                            expr.value
                                        ),
                                        expr.remaining
                                    )
                                    is ParseResult.Failure -> expr
                                }
                            }
                            is ParseResult.Failure -> arrow
                        }
                    }
                }
            }
            is ParseResult.Failure -> firstPattern
        }
    }

    // Expression with operator precedence
internal val FunkParser.expression: Parser<Expression>
    get() = { stream ->
        logicalOrExpression(stream)
    }

    // Logical OR operator: ||
internal val FunkParser.logicalOrExpression: Parser<Expression>
    get() = { stream ->
        var result: ParseResult<Expression> = logicalAndExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val op = currentStream.current()

            if (op?.type == TokenType.OR) {
                val opStream = currentStream.advance()
                val right = logicalAndExpression(opStream)
                result = when (right) {
                    is ParseResult.Success -> ParseResult.Success(
                        desugarBinaryOperator(
                            op.text,
                            result.value,
                            right.value,
                            result.value.sourceLocation  // Use left operand's location
                        ),
                        right.remaining
                    )
                    is ParseResult.Failure -> result // Keep left side on error
                }
            } else {
                break
            }
        }
        result
    }

    // Logical AND operator: &&
internal val FunkParser.logicalAndExpression: Parser<Expression>
    get() = { stream ->
        var result: ParseResult<Expression> = pipeExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val op = currentStream.current()

            if (op?.type == TokenType.AND) {
                val opStream = currentStream.advance()
                val right = pipeExpression(opStream)
                result = when (right) {
                    is ParseResult.Success -> ParseResult.Success(
                        desugarBinaryOperator(
                            op.text,
                            result.value,
                            right.value,
                            result.value.sourceLocation  // Use left operand's location
                        ),
                        right.remaining
                    )
                    is ParseResult.Failure -> result // Keep left side on error
                }
            } else {
                break
            }
        }
        result
    }

    // Desugar pipe operator by replacing @ placeholders with the piped value
    // Transforms: value |> func {x = @} into func {x = value}
internal fun FunkParser.desugarPipe(pipeValue: Expression, target: Expression): Expression {
        return when (target) {
            // @ is parsed as Identifier("@") - replace with piped value
            is Identifier -> if (target.name == "@") pipeValue else target

            // Recurse into function calls
            is FunctionCall -> target.copy(
                function = desugarPipe(pipeValue, target.function),
                arguments = desugarPipe(pipeValue, target.arguments)
            )

            // Recurse into record literals (this is where @ typically appears)
            is RecordLiteral -> target.copy(
                entries = target.entries.map { entry ->
                    when (entry) {
                        is RecordFieldEntry -> entry.copy(
                            value = entry.value?.let { desugarPipe(pipeValue, it) }
                        )
                        is RecordSpreadEntry -> entry.copy(
                            expression = desugarPipe(pipeValue, entry.expression)
                        )
                    }
                }
            )

            // Recurse into conditional expressions
            is IfExpression -> target.copy(
                condition = desugarPipe(pipeValue, target.condition),
                thenBranch = desugarPipe(pipeValue, target.thenBranch),
                elseBranch = desugarPipe(pipeValue, target.elseBranch)
            )

            // Recurse into lambda expressions
            is LambdaExpression -> target.copy(
                body = desugarPipe(pipeValue, target.body)
            )

            // Recurse into match expressions
            is MatchExpression -> target.copy(
                expression = desugarPipe(pipeValue, target.expression),
                cases = target.cases.map { matchCase ->
                    matchCase.copy(
                        guard = matchCase.guard?.let { desugarPipe(pipeValue, it) },
                        expression = desugarPipe(pipeValue, matchCase.expression)
                    )
                }
            )

            // Recurse into do expressions
            is DoExpression -> target.copy(
                statements = target.statements.map { stmt ->
                    when (stmt) {
                        is LetStatement -> stmt.copy(
                            expression = desugarPipe(pipeValue, stmt.expression)
                        )
                        is ExpressionStatement -> stmt.copy(
                            expression = desugarPipe(pipeValue, stmt.expression)
                        )
                    }
                },
                result = desugarPipe(pipeValue, target.result)
            )

            // Recurse into array literals
            is ArrayLiteral -> target.copy(
                elements = target.elements.map { element ->
                    when (element) {
                        is ArrayRegularElement -> element.copy(expression = desugarPipe(pipeValue, element.expression))
                        is ArraySpreadElement -> element.copy(expression = desugarPipe(pipeValue, element.expression))
                    }
                }
            )

            // Recurse into perform expressions
            is PerformExpression -> target.copy(
                expression = desugarPipe(pipeValue, target.expression)
            )

            // Literals, identifiers, etc. don't contain placeholders
            else -> target
        }
    }

    // Pipe operator: |>
    // Low precedence, left-associative operator for function composition
    // Syntax: value |> function {param = @} where @ is replaced with value
internal val FunkParser.pipeExpression: Parser<Expression>
    get() = { stream ->
        var result: ParseResult<Expression> = comparisonExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val op = currentStream.current()

            if (op?.type == TokenType.PIPE_OP) {
                val opStream = currentStream.advance()
                val right = comparisonExpression(opStream)
                result = when (right) {
                    is ParseResult.Success -> {
                        // Desugar pipe operator immediately at parse time
                        ParseResult.Success(
                            desugarPipe(result.value, right.value),
                            right.remaining
                        )
                    }
                    is ParseResult.Failure -> result // Keep left side on error
                }
            } else {
                break
            }
        }
        result
    }

    // Comparison operators: ==, !=, <, >, <=, >=
internal val FunkParser.comparisonExpression: Parser<Expression>
    get() = { stream ->
        var result: ParseResult<Expression> = additiveExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val op = currentStream.current()

            if (op?.type == TokenType.EQ || op?.type == TokenType.NEQ ||
                op?.type == TokenType.LT || op?.type == TokenType.GT ||
                op?.type == TokenType.LTE || op?.type == TokenType.GTE) {
                val opStream = currentStream.advance()
                val right = additiveExpression(opStream)
                result = when (right) {
                    is ParseResult.Success -> ParseResult.Success(
                        desugarBinaryOperator(
                            op.text,
                            result.value,
                            right.value,
                            result.value.sourceLocation  // Use left operand's location
                        ),
                        right.remaining
                    )
                    is ParseResult.Failure -> result // Keep left side on error
                }
            } else {
                break
            }
        }
        result
    }

    // Additive operators: +, -
internal val FunkParser.additiveExpression: Parser<Expression>
    get() = { stream ->
        var result: ParseResult<Expression> = multiplicativeExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val op = currentStream.current()

            if (op?.type == TokenType.PLUS || op?.type == TokenType.MINUS) {
                val opStream = currentStream.advance()
                val right = multiplicativeExpression(opStream)
                result = when (right) {
                    is ParseResult.Success -> ParseResult.Success(
                        desugarBinaryOperator(
                            op.text,
                            result.value,
                            right.value,
                            result.value.sourceLocation  // Use left operand's location
                        ),
                        right.remaining
                    )
                    is ParseResult.Failure -> result // Keep left side on error
                }
            } else {
                break
            }
        }
        result
    }

    // Multiplicative operators: *, /, %
internal val FunkParser.multiplicativeExpression: Parser<Expression>
    get() = { stream ->
        var result: ParseResult<Expression> = unaryExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val op = currentStream.current()

            if (op?.type == TokenType.STAR || op?.type == TokenType.SLASH || op?.type == TokenType.PERCENT) {
                val opStream = currentStream.advance()
                val right = unaryExpression(opStream)
                result = when (right) {
                    is ParseResult.Success -> ParseResult.Success(
                        desugarBinaryOperator(
                            op.text,
                            result.value,
                            right.value,
                            result.value.sourceLocation  // Use left operand's location
                        ),
                        right.remaining
                    )
                    is ParseResult.Failure -> result // Keep left side on error
                }
            } else {
                break
            }
        }
        result
    }

    // Unary operators: -, !
internal val FunkParser.unaryExpression: Parser<Expression>
    get() = { stream ->
        val token = stream.current()

        if (token?.type == TokenType.MINUS) {
            // Check if this is a negative literal (skip newlines first)
            val opLocation = tokenLocation(token, currentFile)
            val streamAfterMinus = skipNewlines(stream.advance())
            val literalToken = streamAfterMinus.current()

            if (literalToken?.type == TokenType.INTEGER || literalToken?.type == TokenType.FLOAT || literalToken?.type == TokenType.FRACTION) {
                // Parse as negative literal

                when (literalToken.type) {
                    TokenType.INTEGER -> ParseResult.Success(
                        IntLiteral(-literalToken.text.toLong(), sourceLocation = opLocation),
                        streamAfterMinus.advance()
                    )
                    TokenType.FLOAT -> ParseResult.Success(
                        FloatLiteral(-literalToken.text.toDouble(), sourceLocation = opLocation),
                        streamAfterMinus.advance()
                    )
                    TokenType.FRACTION -> {
                        // Parse fraction like "1//2" into numerator and denominator
                        val parts = literalToken.text.split("//")
                        if (parts.size == 2) {
                            ParseResult.Success(
                                FractionLiteral(-parts[0].toLong(), parts[1].toLong(), sourceLocation = opLocation),
                                streamAfterMinus.advance()
                            )
                        } else {
                            streamAfterMinus.failure("Invalid fraction literal '${literalToken.text}'. Expected format: numerator//denominator (e.g., 3//4)")
                        }
                    }
                    else -> {
                        // Should not reach here given the check above
                        val opStream = stream.advance()
                        val operand = unaryExpression(opStream)
                        when (operand) {
                            is ParseResult.Success -> ParseResult.Success(
                                desugarUnaryOperator(token.text, operand.value, opLocation),
                                operand.remaining
                            )
                            is ParseResult.Failure -> operand
                        }
                    }
                }
            } else {
                // Not a literal, desugar to NumSub.neg call
                val opStream = stream.advance()
                val operand = unaryExpression(opStream)
                when (operand) {
                    is ParseResult.Success -> ParseResult.Success(
                        desugarUnaryOperator(token.text, operand.value, opLocation),
                        operand.remaining
                    )
                    is ParseResult.Failure -> operand
                }
            }
        } else if (token?.type == TokenType.NOT) {
            // Desugar to ability method call
            val opLocation = tokenLocation(token, currentFile)  // Capture operator location
            val opStream = stream.advance()
            val operand = unaryExpression(opStream) // Right-associative: !(!x) is valid
            when (operand) {
                is ParseResult.Success -> ParseResult.Success(
                    desugarUnaryOperator(token.text, operand.value, opLocation),
                    operand.remaining
                )
                is ParseResult.Failure -> operand
            }
        } else {
            // No unary operator, parse postfix
            postfixExpression(stream)
        }
    }

    // Postfix expressions: function calls and member access
    // Handles: f {x = 1}, obj.field, f {x = 1}.field, etc.
internal val FunkParser.postfixExpression: Parser<Expression>
    get() = { stream ->
        var result: ParseResult<Expression> = primaryExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val token = currentStream.current()

            when (token?.type) {
                TokenType.LBRACE -> {
                    // Function call or variant construction: f {x = 1} or Some {x = 1}
                    // Check if this is a record literal (arguments) not a lambda
                    if (!lookAheadForArrowAfterBrace(currentStream)) {
                        val args = recordLiteral(currentStream)
                        result = when (args) {
                            is ParseResult.Success -> {
                                // Check if the function is an uppercase identifier (variant constructor)
                                val expr = result.value
                                when {
                                    expr is Identifier && expr.name.firstOrNull()?.isUpperCase() == true -> {
                                        // This is a variant construction: Some {val = 42}
                                        ParseResult.Success(
                                            VariantConstruction(
                                                constructor = expr.name,
                                                value = args.value,
                                                sourceLocation = expr.sourceLocation  // Use identifier's location
                                            ),
                                            args.remaining
                                        )
                                    }
                                    expr is VariantConstruction -> {
                                        // Already parsed as variant construction (uppercase id), now add the payload
                                        ParseResult.Success(
                                            expr.copy(value = args.value),
                                            args.remaining
                                        )
                                    }
                                    else -> {
                                        // Regular function call: f {x = 1}
                                        ParseResult.Success(
                                            FunctionCall(
                                                result.value,
                                                args.value,
                                                sourceLocation = result.value.sourceLocation  // Use function expr's location
                                            ),
                                            args.remaining
                                        )
                                    }
                                }
                            }
                            is ParseResult.Failure -> break  // Not a function call
                        }
                    } else {
                        break  // It's a lambda, not a function call
                    }
                }
                TokenType.DOT -> {
                    // Member access: obj.field
                    val dotStream = currentStream.advance()
                    val member = identifierParser(dotStream)
                    result = when (member) {
                        is ParseResult.Success -> ParseResult.Success(
                            MemberAccess(
                                result.value,
                                member.value.text,
                                sourceLocation = result.value.sourceLocation  // Use receiver's location
                            ),
                            member.remaining
                        )
                        is ParseResult.Failure -> break  // Not a member access
                    }
                }
                TokenType.LPAREN -> {
                    // BUG FIX: Reject () syntax for function calls
                    // Funk uses {} for function calls, not ()
                    val funcExpr = result.value
                    val funcName = when (funcExpr) {
                        is Identifier -> funcExpr.name
                        is MemberAccess -> {
                            val receiverName = when (val receiver = funcExpr.receiver) {
                                is Identifier -> receiver.name
                                is VariantConstruction -> receiver.constructor
                                else -> "..."
                            }
                            "$receiverName.${funcExpr.member}"
                        }
                        else -> "expression"
                    }
                    throw createParseException(
                        "Function calls must use {} syntax, not (). " +
                        "Instead of '$funcName(...)', use '$funcName { <field> = <value> }' with named arguments.",
                        currentStream,
                        currentSource,
                        currentFile
                    )
                }
                TokenType.NEWLINE -> {
                    // BUG FIX: Check if there's a { after the newline(s)
                    // Only check if the expression looks like a function call (Identifier or MemberAccess)
                    val funcExpr = result.value
                    if (funcExpr is Identifier || funcExpr is MemberAccess) {
                        var checkStream = currentStream.advance()  // Skip first newline
                        // Skip any additional newlines
                        while (checkStream.current()?.type == TokenType.NEWLINE) {
                            checkStream = checkStream.advance()
                        }
                        // Check if next non-newline token is LBRACE
                        if (checkStream.current()?.type == TokenType.LBRACE) {
                            // IMPORTANT: Only error if the { would be parsed as function arguments
                            // Don't error if:
                            // 1. It's a lambda (has -> after brace)
                            // 2. It's a record literal (has identifier = ... pattern)
                            // This prevents false positives in match expressions and when returning records
                            val isLambda = lookAheadForArrowAfterBrace(checkStream)
                            val isRecordLiteral = lookAheadForRecordLiteral(checkStream)
                            if (!isLambda && !isRecordLiteral) {
                                val funcName = when (funcExpr) {
                                    is Identifier -> funcExpr.name
                                    is MemberAccess -> {
                                        val receiverName = when (val receiver = funcExpr.receiver) {
                                            is Identifier -> receiver.name
                                            else -> "..."
                                        }
                                        "$receiverName.${funcExpr.member}"
                                    }
                                    else -> "expression"
                                }
                                throw createParseException(
                                    "Newline not allowed before '{' in function call. Function calls require '{' on the same line. " +
                                    "Instead of:\n  $funcName\n  { ... }\nUse:\n  $funcName { ... }",
                                    currentStream,
                                    currentSource,
                                    currentFile
                                )
                            }
                        }
                    }
                    // Not a function call, just a newline - break out of postfix loop
                    break
                }
                TokenType.IDENTIFIER -> {
                    // BUG #10 FIX: Check for function call without {} syntax
                    // Only check when the previous expression is a simple identifier (like `my_func x`)
                    // or a variant constructor (like `Left 42`)
                    // For complex expressions (like `Show.show {val = @}` followed by newline and identifier),
                    // skip this check as the identifier is likely a separate statement
                    val funcExpr = result.value
                    if (funcExpr is Identifier) {
                        val funcName = funcExpr.name
                        val argName = token.text
                        // Throw exception directly to ensure the error message is shown
                        throw createParseException(
                            "Function calls must use {} syntax. Instead of '$funcName $argName', " +
                            "use '$funcName { ... }' with named arguments.",
                            currentStream,
                            currentSource,
                            currentFile
                        )
                    } else if (funcExpr is VariantConstruction) {
                        // BUG FIX: Reject variant constructor with bare argument like "Left 42"
                        val constructorName = funcExpr.constructor
                        val argName = token.text
                        throw createParseException(
                            "Variant constructors must use {} syntax. Instead of '$constructorName $argName', " +
                            "use '$constructorName { <field> = $argName }' with named arguments.",
                            currentStream,
                            currentSource,
                            currentFile
                        )
                    }
                    // For non-identifier expressions, treat following identifier as separate statement
                    break
                }
                TokenType.STRING, TokenType.INTEGER, TokenType.FLOAT, TokenType.FRACTION, TokenType.BOOLEAN -> {
                    // Function call with literal argument without {} syntax
                    val funcExpr = result.value
                    if (funcExpr is Identifier || funcExpr is MemberAccess || funcExpr is VariantConstruction) {
                        val funcName = when (funcExpr) {
                            is Identifier -> funcExpr.name
                            is VariantConstruction -> funcExpr.constructor
                            is MemberAccess -> {
                                // Extract clean name for member access like Parse.decode
                                val receiverName = when (val receiver = funcExpr.receiver) {
                                    is Identifier -> receiver.name
                                    is VariantConstruction -> receiver.constructor
                                    else -> "..."
                                }
                                "$receiverName.${funcExpr.member}"
                            }
                            else -> "function"
                        }
                        val literalType = when (token.type) {
                            TokenType.STRING -> "string ${token.text}"
                            TokenType.INTEGER -> "number ${token.text}"
                            TokenType.FLOAT -> "number ${token.text}"
                            TokenType.FRACTION -> "fraction ${token.text}"
                            TokenType.BOOLEAN -> "boolean ${token.text}"
                            else -> "value"
                        }

                        // Use appropriate error message based on expression type
                        val errorMessage = if (funcExpr is VariantConstruction) {
                            "Variant constructors must use {} syntax. Instead of '$funcName $literalType', " +
                            "use '$funcName { <field> = $literalType }' with named arguments."
                        } else {
                            "Function calls must use {} syntax. Instead of '$funcName $literalType', " +
                            "use '$funcName { <field> = $literalType }' with named arguments."
                        }

                        throw createParseException(
                            errorMessage,
                            currentStream,
                            currentSource,
                            currentFile
                        )
                    }
                    break
                }
                else -> break  // No more postfix operators
            }
        }

        result
    }

    // ===== BASIC PARSERS =====


    // @ placeholder for pipe operator
internal val FunkParser.atPlaceholderExpression: Parser<Expression>
    get() = { stream ->
        val atToken = stream.current()
        val location = atToken?.let { tokenLocation(it, currentFile) }
        val token = tok(TokenType.AT)(stream)
        when (token) {
            is ParseResult.Success -> ParseResult.Success(
                Identifier("@", sourceLocation = location),  // Parse @ as identifier for now, desugar later
                token.remaining
            )
            is ParseResult.Failure -> token
        }
    }

internal val FunkParser.identifierExpression: Parser<Expression>
    get() = { stream ->
        // Parse qualified names: module::function or just function
        val first = identifierParser(stream)
        when (first) {
            is ParseResult.Success -> {
                val startLocation = tokenLocation(first.value, currentFile)  // Capture location of first token
                var name = first.value.text
                var currentStream = first.remaining
                var failed: ParseResult.Failure? = null

                // Check for :: to build qualified name
                while (failed == null) {
                    val doubleColon = tok(TokenType.DOUBLE_COLON)(currentStream)
                    if (doubleColon is ParseResult.Success) {
                        val next = identifierParser(doubleColon.remaining)
                        when (next) {
                            is ParseResult.Success -> {
                                name += "::" + next.value.text
                                currentStream = next.remaining
                            }
                            is ParseResult.Failure -> failed = next
                        }
                    } else {
                        break
                    }
                }

                if (failed != null) {
                    failed
                } else {
                    // Check if this is an uppercase identifier (variant constructor without payload)
                    // Only for simple names (not qualified names with ::)
                    if (!name.contains("::") && name.firstOrNull()?.isUpperCase() == true) {
                        // This is a variant construction without payload: None, True, False, etc.
                        ParseResult.Success(
                            VariantConstruction(constructor = name, value = null, sourceLocation = startLocation),
                            currentStream
                        )
                    } else {
                        // Regular identifier
                        ParseResult.Success(Identifier(name, sourceLocation = startLocation), currentStream)
                    }
                }
            }
            is ParseResult.Failure -> first
        }
    }

internal val FunkParser.literalExpression: Parser<Expression>
    get() = { stream ->
        val token = stream.current()
        val location = token?.let { tokenLocation(it, currentFile) }
        when (token?.type) {
            TokenType.INTEGER -> ParseResult.Success(
                IntLiteral(token.text.toLong(), sourceLocation = location),
                stream.advance()
            )
            TokenType.NAT -> ParseResult.Success(
                NatLiteral(token.text.toLong(), sourceLocation = location),
                stream.advance()
            )
            TokenType.FLOAT -> ParseResult.Success(
                FloatLiteral(token.text.toDouble(), sourceLocation = location),
                stream.advance()
            )
            TokenType.FRACTION -> {
                // Parse fraction like "1//2" into numerator and denominator
                val parts = token.text.split("//")
                if (parts.size == 2) {
                    ParseResult.Success(
                        FractionLiteral(parts[0].toLong(), parts[1].toLong(), sourceLocation = location),
                        stream.advance()
                    )
                } else {
                    stream.failure("Invalid fraction literal '${token.text}'. Expected format: numerator//denominator (e.g., 3//4)")
                }
            }
            TokenType.STRING -> {
                val stringContent = token.text.removeSurrounding("\"")

                // Check if this is an interpolated string (has unescaped $()
                if (hasUnescapedInterpolation(stringContent)) {
                    // Parse as interpolated string - location will be set there
                    parseInterpolatedString(stringContent, stream.advance(), location)
                } else {
                    // Simple string literal - process escape sequences
                    val processedContent = processEscapeSequences(stringContent)
                    ParseResult.Success(
                        StringLiteral(processedContent, sourceLocation = location),
                        stream.advance()
                    )
                }
            }
            TokenType.BOOLEAN -> ParseResult.Success(
                BooleanLiteral(token.text == "true", sourceLocation = location),
                stream.advance()
            )
            else -> stream.failure("Expected a literal (number, string, or boolean), but got ${tokenGotDescription(token)}")
        }
    }

internal val FunkParser.parenExpression: Parser<Expression>
    get() = { stream ->
        Parsers.between(
            tok(TokenType.LPAREN),
            expression,
            tok(TokenType.RPAREN)
        )(stream)
    }

// Helper function for keyword parsing
internal fun FunkParser.keyword(word: String, type: TokenType): Parser<Token> =
    Parsers.token({ it.type == type && it.text == word }, word)
