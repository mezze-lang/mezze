package com.mezze.language.parser.internal

import com.mezze.language.parsedast.*
import com.mezze.language.parser.*
import com.mezze.language.parser.internal.lib.*
import com.mezze.language.ast.Expression as V1Expression

/**
 * Expression parsers that emit ParsedAST types (parsedast package).
 *
 * These run alongside the v1 parsers during migration.
 * Once all consumers use parsedast, the v1 parsers are deleted.
 */

// ============================================================================
// LITERALS
// ============================================================================

internal val MezzeParser.parsedLiteralExpr: Parser<ParsedExpr>
    get() = { stream ->
        val token = stream.current()
        val file = currentFile ?: "<unknown>"
        when (token?.type) {
            TokenType.INTEGER -> {
                val loc = tokenLoc(token, file)
                ParseResult.Success(
                    ParsedIntLit(token.text.toLong(), loc),
                    stream.advance()
                )
            }
            TokenType.NAT -> {
                val loc = tokenLoc(token, file)
                ParseResult.Success(
                    ParsedNatLit(token.text.toLong(), loc),
                    stream.advance()
                )
            }
            TokenType.FLOAT -> {
                val loc = tokenLoc(token, file)
                ParseResult.Success(
                    ParsedFloatLit(token.text.toDouble(), loc),
                    stream.advance()
                )
            }
            TokenType.FRACTION -> {
                val parts = token.text.split("//")
                if (parts.size == 2) {
                    val loc = tokenLoc(token, file)
                    ParseResult.Success(
                        ParsedFracLit(parts[0].toLong(), parts[1].toLong(), loc),
                        stream.advance()
                    )
                } else {
                    stream.failure("Invalid fraction literal '${token.text}'. Expected format: numerator//denominator (e.g., 3//4)")
                }
            }
            TokenType.STRING -> {
                val stringContent = token.text.removeSurrounding("\"")
                val loc = tokenLoc(token, file)
                if (hasUnescapedInterpolation(stringContent)) {
                    parseParsedInterpolatedString(stringContent, stream.advance(), loc)
                } else {
                    val processedContent = processEscapeSequences(stringContent)
                    ParseResult.Success(
                        ParsedStringLit(listOf(ParsedStringPart.Literal(processedContent)), loc),
                        stream.advance()
                    )
                }
            }
            else -> stream.failure("Expected a literal (number, string, or boolean), but got ${tokenGotDescription(token)}")
        }
    }

/**
 * Parse an interpolated string into ParsedStringLit with parts.
 */
internal fun MezzeParser.parseParsedInterpolatedString(
    content: String,
    remaining: TokenStream,
    loc: Loc
): ParseResult<ParsedExpr> {
    val parts = mutableListOf<ParsedStringPart>()
    var i = 0
    val sb = StringBuilder()

    while (i < content.length) {
        if (i + 1 < content.length && content[i] == '$' && content[i + 1] == '(') {
            // Flush literal text
            if (sb.isNotEmpty()) {
                parts.add(ParsedStringPart.Literal(processEscapeSequences(sb.toString())))
                sb.clear()
            }
            // Find matching close paren
            var depth = 1
            var j = i + 2
            while (j < content.length && depth > 0) {
                if (content[j] == '(') depth++
                if (content[j] == ')') depth--
                j++
            }
            val exprText = content.substring(i + 2, j - 1)
            // Parse the expression inside $(...)
            val exprResult = try {
                val lexer = Lexer(exprText)
                val tokens = lexer.tokenize()
                val exprStream = TokenStream(tokens)
                expression(exprStream)
            } catch (e: Exception) {
                return remaining.failure("Failed to parse interpolated expression: $exprText")
            }
            when (exprResult) {
                is ParseResult.Success -> {
                    parts.add(ParsedStringPart.Interpolation(exprResult.value))
                }
                is ParseResult.Failure -> {
                    return remaining.failure("Failed to parse interpolated expression: ${exprResult.message}")
                }
            }
            i = j
        } else if (content[i] == '\\' && i + 1 < content.length && content[i + 1] == '$') {
            sb.append('$')
            i += 2
        } else {
            sb.append(content[i])
            i++
        }
    }
    if (sb.isNotEmpty()) {
        parts.add(ParsedStringPart.Literal(processEscapeSequences(sb.toString())))
    }

    return ParseResult.Success(ParsedStringLit(parts, loc), remaining)
}

// ============================================================================
// VARIABLES
// ============================================================================

internal val MezzeParser.parsedIdentifierExpr: Parser<ParsedExpr>
    get() = { stream ->
        val token = stream.current()
        val file = currentFile ?: "<unknown>"
        if (token?.type == TokenType.IDENTIFIER) {
            // Check for qualified: x::y::z
            var currentStream = stream.advance()
            val segments = mutableListOf(token.text)
            var lastToken = token

            while (true) {
                val colonColon = currentStream.current()
                val nextIdent = currentStream.peek(1)
                if (colonColon?.type == TokenType.DOUBLE_COLON &&
                    nextIdent?.type == TokenType.IDENTIFIER) {
                    segments.add(nextIdent.text)
                    lastToken = nextIdent
                    currentStream = currentStream.advance(2)
                } else {
                    break
                }
            }

            if (segments.size == 1) {
                // Simple identifier
                val loc = tokenLoc(token, file)
                ParseResult.Success(
                    ParsedIdentifier(localName = token.text, qualifier = null, loc = loc),
                    currentStream
                )
            } else {
                // Qualified: last segment is localName, rest is qualifier
                val localName = segments.last()
                val qualifierSegments = segments.dropLast(1)
                val loc = spanLoc(token, lastToken, file)
                ParseResult.Success(
                    ParsedIdentifier(
                        localName = localName,
                        qualifier = ParsedModulePath(qualifierSegments),
                        loc = loc
                    ),
                    currentStream
                )
            }
        } else {
            stream.failure("Expected identifier, but got ${tokenGotDescription(token)}")
        }
    }

// ============================================================================
// VARIANT CONSTRUCTION
// ============================================================================

internal val MezzeParser.parsedVariantConstruct: Parser<ParsedExpr>
    get() = { stream ->
        val token = stream.current()
        val file = currentFile ?: "<unknown>"
        if (token?.type == TokenType.IDENTIFIER && token.text.first().isUpperCase()) {
            val startToken = token
            val currentStream = stream.advance()

            // Check if followed by a record literal argument
            val nextToken = currentStream.current()
            if (nextToken?.type == TokenType.LBRACE && lookAheadForRecordLiteral(currentStream)) {
                // Variant with value: Some { val = 42 }
                when (val argResult = parsedRecordLitExpr(currentStream)) {
                    is ParseResult.Success -> {
                        val loc = spanLocTo(startToken, argResult.remaining, file)
                        ParseResult.Success(
                            ParsedVariantConstruct(startToken.text, argResult.value, loc),
                            argResult.remaining
                        )
                    }
                    is ParseResult.Failure -> argResult
                }
            } else {
                // Bare variant: None, True, False, Red
                val loc = tokenLoc(startToken, file)
                ParseResult.Success(
                    ParsedVariantConstruct(startToken.text, null, loc),
                    currentStream
                )
            }
        } else {
            stream.failure("Expected uppercase constructor, but got ${tokenGotDescription(token)}")
        }
    }

// ============================================================================
// RECORDS
// ============================================================================

internal fun MezzeParser.parseParsedRecordLit(stream: TokenStream): ParseResult<ParsedExpr> {
    val file = currentFile ?: "<unknown>"
    val lbrace = tok(TokenType.LBRACE)(stream)
    if (lbrace is ParseResult.Failure) return lbrace

    val startToken = (lbrace as ParseResult.Success).value
    var currentStream = skipNewlines(lbrace.remaining)
    val entries = mutableListOf<ParsedRecordEntry>()

    // Empty record {}
    if (currentStream.current()?.type == TokenType.RBRACE) {
        val endToken = currentStream.current()!!
        val loc = spanLoc(startToken, endToken, file)
        return ParseResult.Success(ParsedRecordLit(entries, loc), currentStream.advance())
    }

    // Parse entries
    while (true) {
        currentStream = skipNewlines(currentStream)
        val entryToken = currentStream.current()

        if (entryToken?.type == TokenType.RBRACE) break

        if (entryToken?.type == TokenType.ELLIPSIS) {
            val spreadStart = entryToken
            currentStream = currentStream.advance()
            when (val exprResult = parsedPrimaryExpr(currentStream)) {
                is ParseResult.Success -> {
                    val spreadLoc = spanLocTo(spreadStart, exprResult.remaining, file)
                    entries.add(ParsedRecordEntry.Spread(exprResult.value, spreadLoc))
                    currentStream = exprResult.remaining
                }
                is ParseResult.Failure -> return exprResult
            }
        } else if (entryToken?.type == TokenType.IDENTIFIER) {
            val fieldName = entryToken.text
            val fieldStart = entryToken
            currentStream = currentStream.advance()
            currentStream = skipNewlines(currentStream)

            val afterIdent = currentStream.current()
            if (afterIdent?.type == TokenType.ASSIGN) {
                currentStream = currentStream.advance()
                currentStream = skipNewlines(currentStream)
                when (val valResult = parsedExpr(currentStream)) {
                    is ParseResult.Success -> {
                        val fieldLoc = spanLocTo(fieldStart, valResult.remaining, file)
                        entries.add(ParsedRecordEntry.Field(fieldName, valResult.value, fieldLoc))
                        currentStream = valResult.remaining
                    }
                    is ParseResult.Failure -> return valResult
                }
            } else {
                val fieldLoc = tokenLoc(fieldStart, file)
                entries.add(ParsedRecordEntry.Field(fieldName, null, fieldLoc))
            }
        } else {
            return currentStream.failure("Expected field name, spread (...), or '}' in record literal, but got ${tokenGotDescription(entryToken)}")
        }

        currentStream = skipNewlines(currentStream)
        if (currentStream.current()?.type == TokenType.COMMA) {
            currentStream = currentStream.advance()
        }
    }

    val endToken = currentStream.current()
    return if (endToken?.type == TokenType.RBRACE) {
        val loc = spanLoc(startToken, endToken, file)
        ParseResult.Success(ParsedRecordLit(entries, loc), currentStream.advance())
    } else {
        currentStream.failure("Expected '}' to close record literal, but got ${tokenGotDescription(endToken)}")
    }
}

internal val MezzeParser.parsedRecordLitExpr: Parser<ParsedExpr>
    get() = { stream -> parseParsedRecordLit(stream) }

// ============================================================================
// PARENTHESIZED
// ============================================================================

internal val MezzeParser.parsedParenExpr: Parser<ParsedExpr>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        when (val lparen = tok(TokenType.LPAREN)(stream)) {
            is ParseResult.Failure -> lparen
            is ParseResult.Success -> {
                val startToken = lparen.value
                when (val inner = parsedExpr(lparen.remaining)) {
                    is ParseResult.Failure -> inner
                    is ParseResult.Success -> {
                        when (val rparen = tok(TokenType.RPAREN)(inner.remaining)) {
                            is ParseResult.Failure -> rparen
                            is ParseResult.Success -> {
                                val loc = spanLoc(startToken, rparen.value, file)
                                ParseResult.Success(
                                    ParsedParens(inner.value, loc),
                                    rparen.remaining
                                )
                            }
                        }
                    }
                }
            }
        }
    }

// ============================================================================
// PRIMARY EXPRESSION (dispatch)
// ============================================================================

/**
 * Parsed primary expression — the base of any expression.
 * Dispatches to specific parsers based on the current token.
 */
internal val MezzeParser.parsedPrimaryExpr: Parser<ParsedExpr>
    get() = { stream ->
        val token = stream.current()
        when {
            token == null -> stream.failure("Unexpected end of input")
            token.type == TokenType.INTEGER ||
            token.type == TokenType.NAT ||
            token.type == TokenType.FLOAT ||
            token.type == TokenType.FRACTION ||
            token.type == TokenType.STRING -> parsedLiteralExpr(stream)

            token.type == TokenType.IDENTIFIER && token.text.first().isUpperCase() ->
                parsedVariantConstruct(stream)

            token.type == TokenType.IDENTIFIER ->
                parsedIdentifierExpr(stream)

            token.type == TokenType.LPAREN ->
                parsedParenExpr(stream)

            token.type == TokenType.AT -> {
                val loc = tokenLoc(token, currentFile ?: "<unknown>")
                ParseResult.Success(ParsedPipePlaceholder(loc), stream.advance())
            }

            else -> stream.failure("Expected expression, but got ${tokenGotDescription(token)}")
        }
    }

// ============================================================================
// TOP-LEVEL EXPRESSION (placeholder — delegates to v1 and converts)
// ============================================================================

/**
 * Full expression parser — now delegates directly to the migrated expression parser
 * which already emits ParsedExpr.
 */
internal val MezzeParser.parsedExpr: Parser<ParsedExpr>
    get() = expression

// ============================================================================
// ParsedExpr → V1 Bridge (temporary, needed while DeclarationParsers/MezzeParser
// still emit v1 types from the expression pipeline)
// ============================================================================

/**
 * Wrap expression parser to return v1 Expression.
 * Used by declaration parsers that still produce v1 AST.
 * This is a temporary bridge — deleted once all parsers emit ParsedExpr.
 */
internal val MezzeParser.expressionV1: Parser<V1Expression>
    get() = { stream ->
        when (val result = expression(stream)) {
            is ParseResult.Success -> {
                val file = currentFile ?: "<unknown>"
                val v1 = parsedExprToV1Expr(result.value, file)
                ParseResult.Success(v1, result.remaining)
            }
            is ParseResult.Failure -> result
        }
    }

/**
 * Wrap letBindingPattern to return v1 Pattern.
 * Used by declaration parsers that still produce v1 AST.
 */
internal val MezzeParser.letBindingPatternV1: Parser<com.mezze.language.ast.Pattern>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        when (val result = letBindingPattern(stream)) {
            is ParseResult.Success -> ParseResult.Success(parsedPatternToV1(result.value, file), result.remaining)
            is ParseResult.Failure -> result
        }
    }

/**
 * Wrap patternParser to return v1 Pattern.
 * Used by declaration parsers that still produce v1 AST.
 */
internal val MezzeParser.patternParserV1: Parser<com.mezze.language.ast.Pattern>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        when (val result = patternParser(stream)) {
            is ParseResult.Success -> ParseResult.Success(parsedPatternToV1(result.value, file), result.remaining)
            is ParseResult.Failure -> result
        }
    }

/**
 * Wrap typeAnnotationParser to return v1 TypeAnnotation.
 * Used by declaration parsers that still produce v1 AST.
 */
internal val MezzeParser.typeAnnotationParserV1: Parser<com.mezze.language.ast.TypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        when (val result = typeAnnotationParser(stream)) {
            is ParseResult.Success -> ParseResult.Success(parsedTypeAnnotationToV1(result.value, file), result.remaining)
            is ParseResult.Failure -> result
        }
    }

/**
 * Wrap primaryTypeAnnotation to return v1 TypeAnnotation.
 */
internal val MezzeParser.primaryTypeAnnotationV1: Parser<com.mezze.language.ast.TypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        when (val result = primaryTypeAnnotation(stream)) {
            is ParseResult.Success -> ParseResult.Success(parsedTypeAnnotationToV1(result.value, file), result.remaining)
            is ParseResult.Failure -> result
        }
    }

/**
 * Wrap simpleTypeArgument to return v1 TypeAnnotation.
 */
internal val MezzeParser.simpleTypeArgumentV1: Parser<com.mezze.language.ast.TypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        when (val result = simpleTypeArgument(stream)) {
            is ParseResult.Success -> ParseResult.Success(parsedTypeAnnotationToV1(result.value, file), result.remaining)
            is ParseResult.Failure -> result
        }
    }

/**
 * Wrap seqTypeAnnotation to return v1 TypeAnnotation.
 */
internal val MezzeParser.seqTypeAnnotationV1: Parser<com.mezze.language.ast.TypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        when (val result = seqTypeAnnotation(stream)) {
            is ParseResult.Success -> ParseResult.Success(parsedTypeAnnotationToV1(result.value, file), result.remaining)
            is ParseResult.Failure -> result
        }
    }

/**
 * Wrap recordTypeAnnotationParser to return v1 TypeAnnotation.
 */
internal val MezzeParser.recordTypeAnnotationParserV1: Parser<com.mezze.language.ast.TypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        when (val result = recordTypeAnnotationParser(stream)) {
            is ParseResult.Success -> ParseResult.Success(parsedTypeAnnotationToV1(result.value, file), result.remaining)
            is ParseResult.Failure -> result
        }
    }

/**
 * Wrap identifierTypeAnnotation to return v1 TypeAnnotation.
 */
internal val MezzeParser.identifierTypeAnnotationV1: Parser<com.mezze.language.ast.TypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        when (val result = identifierTypeAnnotation(stream)) {
            is ParseResult.Success -> ParseResult.Success(parsedTypeAnnotationToV1(result.value, file), result.remaining)
            is ParseResult.Failure -> result
        }
    }

/**
 * Wrap functionTypeAnnotation to return v1 TypeAnnotation.
 */
internal val MezzeParser.functionTypeAnnotationV1: Parser<com.mezze.language.ast.TypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        when (val result = functionTypeAnnotation(stream)) {
            is ParseResult.Success -> ParseResult.Success(parsedTypeAnnotationToV1(result.value, file), result.remaining)
            is ParseResult.Failure -> result
        }
    }

/**
 * Wrap parseQualifiedRef to return v1 QualifiedRef.
 */
internal fun MezzeParser.parseQualifiedRefV1(stream: TokenStream): ParseResult<com.mezze.language.ast.QualifiedRef> {
    val file = currentFile ?: "<unknown>"
    return when (val result = parseQualifiedRef(stream)) {
        is ParseResult.Success -> {
            val ref = result.value
            val sl = com.mezze.language.ast.SourceLocation(ref.loc.startLine, ref.loc.startCol, file)
            val v1Ref = com.mezze.language.ast.QualifiedRef(
                localName = ref.localName,
                qualifier = ref.qualifier?.let { com.mezze.language.ast.ModulePath(it.segments) },
                resolvedContentHash = null,
                sourceLocation = sl
            )
            ParseResult.Success(v1Ref, result.remaining)
        }
        is ParseResult.Failure -> result
    }
}

/**
 * Convert a ParsedExpr back to v1 Expression.
 * Temporary bridge until all consumers use ParsedExpr directly.
 */
internal fun parsedExprToV1Expr(expr: ParsedExpr, file: String): V1Expression {
    val sl = com.mezze.language.ast.SourceLocation(expr.loc.startLine, expr.loc.startCol, file)
    return when (expr) {
        is ParsedIntLit -> com.mezze.language.ast.IntLiteral(expr.value, sourceLocation = sl)
        is ParsedNatLit -> com.mezze.language.ast.NatLiteral(expr.value, sourceLocation = sl)
        is ParsedFloatLit -> com.mezze.language.ast.FloatLiteral(expr.value, sourceLocation = sl)
        is ParsedFracLit -> com.mezze.language.ast.FractionLiteral(expr.numerator, expr.denominator, sourceLocation = sl)
        is ParsedStringLit -> {
            val hasParts = expr.parts.any { it is ParsedStringPart.Interpolation }
            if (hasParts) {
                val v1Parts = expr.parts.map { part ->
                    when (part) {
                        is ParsedStringPart.Literal -> com.mezze.language.ast.StringPart(part.value)
                        is ParsedStringPart.Interpolation -> com.mezze.language.ast.ExpressionPart(parsedExprToV1Expr(part.expr, file))
                    }
                }
                com.mezze.language.ast.StringLiteral("", parts = v1Parts, sourceLocation = sl)
            } else {
                val text = expr.parts.filterIsInstance<ParsedStringPart.Literal>().joinToString("") { it.value }
                com.mezze.language.ast.StringLiteral(text, sourceLocation = sl)
            }
        }
        is ParsedIdentifier -> com.mezze.language.ast.Identifier(
            localName = expr.localName,
            qualifier = expr.qualifier?.let { com.mezze.language.ast.ModulePath(it.segments) },
            sourceLocation = sl
        )
        is ParsedVariantConstruct -> com.mezze.language.ast.VariantConstruction(
            constructor = expr.constructor,
            value = expr.value?.let { parsedExprToV1Expr(it, file) },
            sourceLocation = sl
        )
        is ParsedRecordLit -> com.mezze.language.ast.RecordLiteral(
            entries = expr.entries.map { entry ->
                when (entry) {
                    is ParsedRecordEntry.Field -> com.mezze.language.ast.RecordFieldEntry(entry.name, entry.value?.let { parsedExprToV1Expr(it, file) })
                    is ParsedRecordEntry.Spread -> com.mezze.language.ast.RecordSpreadEntry(parsedExprToV1Expr(entry.expr, file))
                }
            },
            isOpen = false,
            sourceLocation = sl
        )
        is ParsedMemberAccess -> com.mezze.language.ast.MemberAccess(
            receiver = parsedExprToV1Expr(expr.receiver, file),
            member = expr.member,
            sourceLocation = sl
        )
        is ParsedFunctionCall -> com.mezze.language.ast.FunctionCall(
            function = parsedExprToV1Expr(expr.function, file),
            arguments = parsedExprToV1Expr(expr.arguments, file),
            sourceLocation = sl
        )
        is ParsedIf -> com.mezze.language.ast.IfExpression(
            condition = parsedExprToV1Expr(expr.condition, file),
            thenBranch = parsedExprToV1Expr(expr.thenBranch, file),
            elseBranch = parsedExprToV1Expr(expr.elseBranch, file),
            sourceLocation = sl
        )
        is ParsedMatch -> com.mezze.language.ast.MatchExpression(
            expression = parsedExprToV1Expr(expr.scrutinee, file),
            cases = expr.cases.map { c ->
                com.mezze.language.ast.MatchCase(
                    patterns = c.patterns.map { parsedPatternToV1(it, file) },
                    guard = c.guard?.let { parsedExprToV1Expr(it, file) },
                    expression = parsedExprToV1Expr(c.body, file),
                    ifLetBindings = c.ifLetBindings.map { g ->
                        com.mezze.language.ast.IfLetGuard(
                            parsedPatternToV1(g.pattern, file),
                            parsedExprToV1Expr(g.expr, file),
                            com.mezze.language.ast.SourceLocation(g.loc.startLine, g.loc.startCol, file)
                        )
                    }
                )
            },
            sourceLocation = sl
        )
        is ParsedDo -> com.mezze.language.ast.DoExpression(
            statements = expr.statements.map { stmt ->
                when (stmt) {
                    is ParsedStatement.Let -> com.mezze.language.ast.LetStatement(
                        parsedPatternToV1(stmt.pattern, file),
                        stmt.typeAnnotation?.let { parsedTypeAnnotationToV1(it, file) },
                        parsedExprToV1Expr(stmt.value, file)
                    )
                    is ParsedStatement.Expr -> com.mezze.language.ast.ExpressionStatement(
                        parsedExprToV1Expr(stmt.expr, file)
                    )
                }
            },
            result = parsedExprToV1Expr(expr.result, file),
            sourceLocation = sl
        )
        is ParsedSeqLit -> com.mezze.language.ast.SeqLiteral(
            elements = expr.elements.map { elem ->
                when (elem) {
                    is ParsedSeqElement.Regular -> com.mezze.language.ast.SeqRegularElement(parsedExprToV1Expr(elem.expr, file))
                    is ParsedSeqElement.Spread -> com.mezze.language.ast.SeqSpreadElement(parsedExprToV1Expr(elem.expr, file))
                }
            },
            sourceLocation = sl
        )
        is ParsedPerform -> com.mezze.language.ast.PerformExpression(
            expression = parsedExprToV1Expr(expr.body, file),
            handlers = expr.handlers.map { h ->
                com.mezze.language.ast.PerformHandler(
                    performer = com.mezze.language.ast.QualifiedRef(
                        localName = h.performer.localName,
                        qualifier = h.performer.qualifier?.let { com.mezze.language.ast.ModulePath(it.segments) },
                        resolvedContentHash = null,
                        sourceLocation = com.mezze.language.ast.SourceLocation(h.performer.loc.startLine, h.performer.loc.startCol, file)
                    ),
                    parameters = h.params?.let { parsedExprToV1Expr(it, file) as? com.mezze.language.ast.RecordLiteral }
                )
            },
            sourceLocation = sl
        )
        is ParsedEffectCall -> com.mezze.language.ast.EffectCallExpression(
            operation = com.mezze.language.ast.QualifiedRef(
                localName = expr.operation.localName,
                qualifier = expr.operation.qualifier?.let { com.mezze.language.ast.ModulePath(it.segments) },
                resolvedContentHash = null,
                sourceLocation = com.mezze.language.ast.SourceLocation(expr.operation.loc.startLine, expr.operation.loc.startCol, file)
            ),
            arguments = parsedExprToV1Expr(expr.arguments, file),
            sourceLocation = sl
        )
        is ParsedParens -> com.mezze.language.ast.ParenthesizedExpression(
            expression = parsedExprToV1Expr(expr.expr, file),
            sourceLocation = sl
        )
        is ParsedPipe -> com.mezze.language.ast.PipeExpression(
            left = parsedExprToV1Expr(expr.left, file),
            right = parsedExprToV1Expr(expr.right, file),
            sourceLocation = sl
        )
        is ParsedPipePlaceholder -> com.mezze.language.ast.PipePlaceholder(sourceLocation = sl)
        is ParsedLambda -> com.mezze.language.ast.LambdaExpression(
            parameters = when (val p = expr.params) {
                is ParsedLambdaParams.Typed -> com.mezze.language.ast.TypedParameters(
                    com.mezze.language.ast.RecordTypeAnnotation(
                        p.fields.map { f ->
                            com.mezze.language.ast.RecordTypeEntry(f.name, parsedTypeAnnotationToV1(f.type, file))
                        },
                        false
                    )
                )
                is ParsedLambdaParams.Untyped -> com.mezze.language.ast.UntypedParameters(p.names, p.isOpen)
            },
            body = parsedExprToV1Expr(expr.body, file),
            returnType = expr.returnType?.let { parsedTypeAnnotationToV1(it, file) },
            abilityConstraints = expr.abilityConstraints.map { c ->
                com.mezze.language.ast.FunctionAbilityConstraint(
                    abilityName = c.abilityName,
                    typeArgs = c.typeArgs.map { parsedTypeAnnotationToV1(it, file) },
                    associatedTypeBindings = c.associatedTypeBindings.mapValues { (_, v) -> parsedTypeAnnotationToV1(v, file) }
                )
            },
            sourceLocation = sl
        )
        is ParsedCharLit -> com.mezze.language.ast.StringLiteral(expr.value.toString(), sourceLocation = sl)
    }
}

/**
 * Convert ParsedPattern to v1 Pattern.
 */
internal fun parsedPatternToV1(pattern: ParsedPattern, file: String): com.mezze.language.ast.Pattern {
    val sl = com.mezze.language.ast.SourceLocation(pattern.loc.startLine, pattern.loc.startCol, file)
    return when (pattern) {
        is ParsedVariablePattern -> com.mezze.language.ast.VariablePattern(pattern.name, sl)
        is ParsedWildcardPattern -> com.mezze.language.ast.WildcardPattern(sourceLocation = sl)
        is ParsedConstructorPattern -> com.mezze.language.ast.ConstructorPattern(
            pattern.constructor,
            pattern.payload?.let { parsedPatternToV1(it, file) } as? com.mezze.language.ast.RecordPattern,
            sl
        )
        is ParsedRecordPattern -> com.mezze.language.ast.RecordPattern(
            entries = pattern.entries.map { e ->
                com.mezze.language.ast.RecordPatternEntry(
                    e.name,
                    e.pattern?.let { parsedPatternToV1(it, file) },
                    sl
                )
            },
            isOpen = pattern.isOpen,
            sourceLocation = sl
        )
        is ParsedLiteralPattern -> com.mezze.language.ast.LiteralPattern(
            when (val v = pattern.value) {
                is ParsedLiteralValue.IntValue -> com.mezze.language.ast.IntLiteralValue(v.value)
                is ParsedLiteralValue.NatValue -> com.mezze.language.ast.NatLiteralValue(v.value)
                is ParsedLiteralValue.FloatValue -> com.mezze.language.ast.FloatLiteralValue(v.value)
                is ParsedLiteralValue.StringValue -> com.mezze.language.ast.StringLiteralValue(v.value)
                is ParsedLiteralValue.BoolValue -> com.mezze.language.ast.BooleanLiteralValue(v.value)
            },
            sl
        )
        is ParsedSeqPattern -> com.mezze.language.ast.SeqPattern(
            elements = pattern.elements.map { parsedPatternToV1(it, file) },
            restPattern = pattern.restPattern,
            sourceLocation = sl
        )
        is ParsedStringInterpPattern -> com.mezze.language.ast.StringInterpolationPattern(
            parts = pattern.parts.map { part ->
                when (part) {
                    is ParsedStringPatternPart.Literal -> com.mezze.language.ast.StringPatternLiteral(part.value)
                    is ParsedStringPatternPart.Capture -> com.mezze.language.ast.StringPatternCapture(part.variableName)
                }
            },
            sourceLocation = sl
        )
    }
}

/**
 * Convert ParsedTypeAnnotation to v1 TypeAnnotation.
 */
internal fun parsedTypeAnnotationToV1(ann: ParsedTypeAnnotation, file: String): com.mezze.language.ast.TypeAnnotation {
    val sl = com.mezze.language.ast.SourceLocation(ann.loc.startLine, ann.loc.startCol, file)
    return when (ann) {
        is ParsedNamedType -> com.mezze.language.ast.IdentifierTypeAnnotation(ann.name, sl)
        is ParsedQualifiedType -> com.mezze.language.ast.QualifiedTypeAnnotation(
            ann.modulePath,
            ann.typeName,
            ann.args.map { parsedTypeAnnotationToV1(it, file) }
        )
        is ParsedTypeApp -> com.mezze.language.ast.TypeApplicationAnnotation(
            ann.constructor,
            ann.args.map { parsedTypeAnnotationToV1(it, file) }
        )
        is ParsedRecordType -> com.mezze.language.ast.RecordTypeAnnotation(
            ann.entries.map { e ->
                com.mezze.language.ast.RecordTypeEntry(e.name, parsedTypeAnnotationToV1(e.type, file))
            },
            ann.isOpen
        )
        is ParsedVariantType -> com.mezze.language.ast.VariantTypeAnnotation(
            ann.constructors.map { c ->
                com.mezze.language.ast.VariantTypeConstructor(c.name, c.dataType?.let { parsedTypeAnnotationToV1(it, file) })
            },
            ann.isOpen
        )
        is ParsedFunctionType -> com.mezze.language.ast.FunctionTypeAnnotation(
            parsedTypeAnnotationToV1(ann.paramType, file),
            parsedTypeAnnotationToV1(ann.returnType, file),
            ann.effects?.let { parsedEffectRowToV1(it, file) },
            ann.abilityConstraints.map { c ->
                com.mezze.language.ast.FunctionAbilityConstraint(
                    c.abilityName,
                    c.typeArgs.map { parsedTypeAnnotationToV1(it, file) },
                    c.associatedTypeBindings.mapValues { (_, v) -> parsedTypeAnnotationToV1(v, file) }
                )
            }
        )
        is ParsedSeqType -> com.mezze.language.ast.SeqTypeAnnotation(parsedTypeAnnotationToV1(ann.elementType, file))
        is ParsedEffectRow -> com.mezze.language.ast.EffectRowAnnotation(
            effects = ann.effects,
            effectAnnotations = ann.effectAnnotations.map { ea ->
                com.mezze.language.ast.EffectAnnotation(
                    ea.name,
                    ea.associatedTypes.mapValues { (_, v) -> parsedTypeAnnotationToV1(v, file) }
                )
            },
            isPolymorphic = ann.isPolymorphic,
            isPerformed = ann.isPerformed
        )
    }
}

/**
 * Convert ParsedEffectRow to v1 EffectRowAnnotation.
 */
internal fun parsedEffectRowToV1(row: ParsedEffectRow, file: String): com.mezze.language.ast.EffectRowAnnotation {
    return com.mezze.language.ast.EffectRowAnnotation(
        effects = row.effects,
        effectAnnotations = row.effectAnnotations.map { ea ->
            com.mezze.language.ast.EffectAnnotation(
                ea.name,
                ea.associatedTypes.mapValues { (_, v) -> parsedTypeAnnotationToV1(v, file) }
            )
        },
        isPolymorphic = row.isPolymorphic,
        isPerformed = row.isPerformed
    )
}

// ============================================================================
// V1 → ParsedAST Bridge (temporary, deleted once parser fully migrated)
// ============================================================================

/**
 * Convert a v1 Expression to ParsedExpr.
 * This is a temporary bridge during migration.
 */
internal fun v1ExprToParsedExpr(expr: com.mezze.language.ast.Expression, file: String): ParsedExpr {
    val loc = v1SourceLocToLoc(expr.sourceLocation, file)
    return when (expr) {
        is com.mezze.language.ast.IntLiteral -> ParsedIntLit(expr.value, loc)
        is com.mezze.language.ast.NatLiteral -> ParsedNatLit(expr.value, loc)
        is com.mezze.language.ast.FloatLiteral -> ParsedFloatLit(expr.value, loc)
        is com.mezze.language.ast.FractionLiteral -> ParsedFracLit(expr.numerator, expr.denominator, loc)
        is com.mezze.language.ast.StringLiteral -> {
            val parts = if (expr.parts != null) {
                expr.parts!!.map { part ->
                    when (part) {
                        is com.mezze.language.ast.StringPart -> ParsedStringPart.Literal(part.value)
                        is com.mezze.language.ast.ExpressionPart -> ParsedStringPart.Interpolation(v1ExprToParsedExpr(part.expression, file))
                    }
                }
            } else {
                listOf(ParsedStringPart.Literal(expr.value))
            }
            ParsedStringLit(parts, loc)
        }
        is com.mezze.language.ast.Identifier -> ParsedIdentifier(
            localName = expr.localName,
            qualifier = expr.qualifier?.let { ParsedModulePath(it.segments) },
            loc = loc
        )
        is com.mezze.language.ast.PipeExpression -> ParsedPipe(
            v1ExprToParsedExpr(expr.left, file),
            v1ExprToParsedExpr(expr.right, file),
            loc
        )
        is com.mezze.language.ast.PipePlaceholder -> ParsedPipePlaceholder(loc)
        is com.mezze.language.ast.ParenthesizedExpression -> ParsedParens(
            v1ExprToParsedExpr(expr.expression, file),
            loc
        )
        is com.mezze.language.ast.RecordLiteral -> {
            val entries = expr.entries.map { entry ->
                when (entry) {
                    is com.mezze.language.ast.RecordFieldEntry -> ParsedRecordEntry.Field(
                        entry.name,
                        entry.value?.let { v1ExprToParsedExpr(it, file) },
                        loc
                    )
                    is com.mezze.language.ast.RecordSpreadEntry -> ParsedRecordEntry.Spread(
                        v1ExprToParsedExpr(entry.expression, file),
                        loc
                    )
                }
            }
            ParsedRecordLit(entries, loc)
        }
        is com.mezze.language.ast.MemberAccess -> ParsedMemberAccess(
            v1ExprToParsedExpr(expr.receiver, file),
            expr.member,
            loc
        )
        is com.mezze.language.ast.VariantConstruction -> ParsedVariantConstruct(
            expr.constructor,
            expr.value?.let { v1ExprToParsedExpr(it, file) },
            loc
        )
        is com.mezze.language.ast.LambdaExpression -> {
            val params = when (val p = expr.parameters) {
                is com.mezze.language.ast.TypedParameters -> ParsedLambdaParams.Typed(
                    p.recordType.entries.map { e ->
                        ParsedRecordTypeEntry(e.name, v1TypeAnnotationToParsed(e.type, file), loc)
                    }
                )
                is com.mezze.language.ast.UntypedParameters -> ParsedLambdaParams.Untyped(p.names, p.isOpen)
            }
            val constraints = expr.abilityConstraints.map { c ->
                ParsedFnAbilityConstraint(
                    c.abilityName,
                    c.typeArgs.map { v1TypeAnnotationToParsed(it, file) },
                    c.associatedTypeBindings.mapValues { (_, v) -> v1TypeAnnotationToParsed(v, file) },
                    loc
                )
            }
            ParsedLambda(
                params,
                v1ExprToParsedExpr(expr.body, file),
                expr.returnType?.let { v1TypeAnnotationToParsed(it, file) },
                constraints,
                loc
            )
        }
        is com.mezze.language.ast.FunctionCall -> ParsedFunctionCall(
            v1ExprToParsedExpr(expr.function, file),
            v1ExprToParsedExpr(expr.arguments, file),
            loc
        )
        is com.mezze.language.ast.IfExpression -> ParsedIf(
            v1ExprToParsedExpr(expr.condition, file),
            v1ExprToParsedExpr(expr.thenBranch, file),
            v1ExprToParsedExpr(expr.elseBranch, file),
            loc
        )
        is com.mezze.language.ast.MatchExpression -> ParsedMatch(
            v1ExprToParsedExpr(expr.expression, file),
            expr.cases.map { c ->
                ParsedMatchCase(
                    patterns = c.patterns.map { v1PatternToParsed(it, file) },
                    guard = c.guard?.let { v1ExprToParsedExpr(it, file) },
                    ifLetBindings = c.ifLetBindings.map { g ->
                        ParsedIfLetGuard(
                            v1PatternToParsed(g.pattern, file),
                            v1ExprToParsedExpr(g.expression, file),
                            v1SourceLocToLoc(g.sourceLocation, file)
                        )
                    },
                    body = v1ExprToParsedExpr(c.expression, file),
                    loc = loc
                )
            },
            loc
        )
        is com.mezze.language.ast.DoExpression -> ParsedDo(
            expr.statements.map { stmt ->
                when (stmt) {
                    is com.mezze.language.ast.LetStatement -> ParsedStatement.Let(
                        v1PatternToParsed(stmt.pattern, file),
                        stmt.typeAnnotation?.let { v1TypeAnnotationToParsed(it, file) },
                        v1ExprToParsedExpr(stmt.expression, file),
                        loc
                    )
                    is com.mezze.language.ast.ExpressionStatement -> ParsedStatement.Expr(
                        v1ExprToParsedExpr(stmt.expression, file),
                        loc
                    )
                }
            },
            v1ExprToParsedExpr(expr.result, file),
            loc
        )
        is com.mezze.language.ast.SeqLiteral -> ParsedSeqLit(
            expr.elements.map { elem ->
                when (elem) {
                    is com.mezze.language.ast.SeqRegularElement -> ParsedSeqElement.Regular(v1ExprToParsedExpr(elem.expression, file))
                    is com.mezze.language.ast.SeqSpreadElement -> ParsedSeqElement.Spread(v1ExprToParsedExpr(elem.expression, file), loc)
                }
            },
            loc
        )
        is com.mezze.language.ast.PerformExpression -> ParsedPerform(
            expr.handlers.map { h ->
                ParsedPerformHandler(
                    ParsedQualifiedRef(
                        h.performer.localName,
                        h.performer.qualifier?.let { ParsedModulePath(it.segments) },
                        v1SourceLocToLoc(h.performer.sourceLocation, file)
                    ),
                    h.parameters?.let { v1ExprToParsedExpr(it, file) as? ParsedRecordLit }
                )
            },
            v1ExprToParsedExpr(expr.expression, file),
            loc
        )
        is com.mezze.language.ast.EffectCallExpression -> ParsedEffectCall(
            ParsedQualifiedRef(
                expr.operation.localName,
                expr.operation.qualifier?.let { ParsedModulePath(it.segments) },
                v1SourceLocToLoc(expr.operation.sourceLocation, file)
            ),
            v1ExprToParsedExpr(expr.arguments, file),
            loc
        )
        // StateMachineExpression is compiler-generated, not syntax — should not appear
        is com.mezze.language.ast.StateMachineExpression -> {
            // Fallback: represent as a placeholder
            ParsedIdentifier("__state_machine", null, loc)
        }
    }
}

/**
 * Convert v1 Pattern to ParsedPattern.
 */
internal fun v1PatternToParsed(pattern: com.mezze.language.ast.Pattern, file: String): ParsedPattern {
    val loc = v1SourceLocToLoc(pattern.let {
        when (it) {
            is com.mezze.language.ast.VariablePattern -> it.sourceLocation
            is com.mezze.language.ast.WildcardPattern -> it.sourceLocation
            is com.mezze.language.ast.ConstructorPattern -> it.sourceLocation
            is com.mezze.language.ast.RecordPattern -> it.sourceLocation
            is com.mezze.language.ast.LiteralPattern -> it.sourceLocation
            is com.mezze.language.ast.SeqPattern -> it.sourceLocation
            is com.mezze.language.ast.StringInterpolationPattern -> it.sourceLocation
        }
    }, file)
    return when (pattern) {
        is com.mezze.language.ast.VariablePattern -> ParsedVariablePattern(pattern.name, loc)
        is com.mezze.language.ast.WildcardPattern -> ParsedWildcardPattern(loc)
        is com.mezze.language.ast.ConstructorPattern -> ParsedConstructorPattern(
            pattern.constructor,
            pattern.recordPattern?.let { v1PatternToParsed(it, file) },
            loc
        )
        is com.mezze.language.ast.RecordPattern -> ParsedRecordPattern(
            pattern.entries.map { e ->
                ParsedRecordPatternEntry(e.name, e.pattern?.let { v1PatternToParsed(it, file) }, v1SourceLocToLoc(e.sourceLocation, file))
            },
            pattern.isOpen,
            loc
        )
        is com.mezze.language.ast.LiteralPattern -> ParsedLiteralPattern(
            when (val v = pattern.value) {
                is com.mezze.language.ast.IntLiteralValue -> ParsedLiteralValue.IntValue(v.value)
                is com.mezze.language.ast.NatLiteralValue -> ParsedLiteralValue.NatValue(v.value)
                is com.mezze.language.ast.FloatLiteralValue -> ParsedLiteralValue.FloatValue(v.value)
                is com.mezze.language.ast.StringLiteralValue -> ParsedLiteralValue.StringValue(v.value)
                is com.mezze.language.ast.BooleanLiteralValue -> ParsedLiteralValue.BoolValue(v.value)
            },
            loc
        )
        is com.mezze.language.ast.SeqPattern -> ParsedSeqPattern(
            pattern.elements.map { v1PatternToParsed(it, file) },
            pattern.restPattern,
            loc
        )
        is com.mezze.language.ast.StringInterpolationPattern -> ParsedStringInterpPattern(
            pattern.parts.map { part ->
                when (part) {
                    is com.mezze.language.ast.StringPatternLiteral -> ParsedStringPatternPart.Literal(part.value)
                    is com.mezze.language.ast.StringPatternCapture -> ParsedStringPatternPart.Capture(part.variableName)
                }
            },
            loc
        )
    }
}

/**
 * Convert v1 TypeAnnotation to ParsedTypeAnnotation.
 */
internal fun v1TypeAnnotationToParsed(ann: com.mezze.language.ast.TypeAnnotation, file: String): ParsedTypeAnnotation {
    val loc = when (ann) {
        is com.mezze.language.ast.IdentifierTypeAnnotation -> v1SourceLocToLoc(ann.sourceLocation, file)
        else -> Loc(file, 1, 1, 1, 1) // Many v1 type annotations don't have sourceLocation
    }
    return when (ann) {
        is com.mezze.language.ast.IdentifierTypeAnnotation -> ParsedNamedType(ann.name, loc)
        is com.mezze.language.ast.QualifiedTypeAnnotation -> ParsedQualifiedType(ann.modulePath, ann.typeName, ann.args.map { v1TypeAnnotationToParsed(it, file) }, loc)
        is com.mezze.language.ast.TypeApplicationAnnotation -> ParsedTypeApp(ann.constructor, ann.args.map { v1TypeAnnotationToParsed(it, file) }, loc)
        is com.mezze.language.ast.RecordTypeAnnotation -> ParsedRecordType(
            ann.entries.map { e -> ParsedRecordTypeEntry(e.name, v1TypeAnnotationToParsed(e.type, file), loc) },
            ann.isOpen,
            loc
        )
        is com.mezze.language.ast.VariantTypeAnnotation -> ParsedVariantType(
            ann.constructors.map { c -> ParsedVariantTypeCtor(c.name, c.dataType?.let { v1TypeAnnotationToParsed(it, file) }, loc) },
            ann.isOpen,
            loc
        )
        is com.mezze.language.ast.FunctionTypeAnnotation -> ParsedFunctionType(
            v1TypeAnnotationToParsed(ann.paramType, file),
            v1TypeAnnotationToParsed(ann.returnType, file),
            ann.effects?.let { v1EffectRowToParsed(it, file) },
            ann.abilityConstraints.map { c ->
                ParsedFnAbilityConstraint(
                    c.abilityName,
                    c.typeArgs.map { v1TypeAnnotationToParsed(it, file) },
                    c.associatedTypeBindings.mapValues { (_, v) -> v1TypeAnnotationToParsed(v, file) },
                    loc
                )
            },
            loc
        )
        is com.mezze.language.ast.SeqTypeAnnotation -> ParsedSeqType(v1TypeAnnotationToParsed(ann.elementType, file), loc)
        is com.mezze.language.ast.EffectRowAnnotation -> {
            // EffectRowAnnotation implements TypeAnnotation in v1 but shouldn't be a type in parsedast
            // Wrap it as a function type from {} -> {} with the effect row
            ParsedNamedType("<effect-row>", loc)
        }
    }
}

/**
 * Convert v1 EffectRowAnnotation to ParsedEffectRow.
 */
internal fun v1EffectRowToParsed(row: com.mezze.language.ast.EffectRowAnnotation, file: String): ParsedEffectRow {
    return ParsedEffectRow(
        effects = row.effects,
        effectAnnotations = row.effectAnnotations.map { ea ->
            ParsedEffectAnnotation(
                ea.name,
                ea.associatedTypes.mapValues { (_, v) -> v1TypeAnnotationToParsed(v, file) },
                Loc(file, 1, 1, 1, 1)
            )
        },
        isPolymorphic = row.isPolymorphic,
        isPerformed = row.isPerformed,
        loc = Loc(file, 1, 1, 1, 1)
    )
}

/**
 * Convert v1 SourceLocation? to Loc.
 */
internal fun v1SourceLocToLoc(sl: com.mezze.language.ast.SourceLocation?, file: String): Loc {
    if (sl == null) return Loc(file, 1, 1, 1, 1)
    val endCol = if (sl.length != null) sl.column + sl.length!! - 1 else sl.column
    return Loc(
        file = sl.file ?: file,
        startLine = sl.line,
        startCol = sl.column,
        endLine = sl.line, // v1 doesn't track end line
        endCol = endCol
    )
}
