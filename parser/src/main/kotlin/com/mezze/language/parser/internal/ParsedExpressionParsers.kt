package com.mezze.language.parser.internal

import com.mezze.language.parsedast.*
import com.mezze.language.parser.*
import com.mezze.language.parser.internal.lib.*

/**
 * Expression parsers that emit ParsedAST types (parsedast package).
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


