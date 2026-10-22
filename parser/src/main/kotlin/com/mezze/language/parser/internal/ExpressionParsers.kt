package com.mezze.language.parser.internal

import com.mezze.language.parsedast.*
import com.mezze.language.parser.*
import com.mezze.language.ast.SyntaxMetadata
import com.mezze.language.ast.OperatorMapping

import com.mezze.language.parser.internal.lib.*

internal fun MezzeParser.lookAheadForArrowAfterBrace(stream: TokenStream): Boolean {
    var pos = stream.position
    val tokens = stream.tokens

    if (pos >= tokens.size || tokens[pos].type != TokenType.LBRACE) return false
    pos++
    var braceDepth = 1

    while (pos < tokens.size && braceDepth > 0) {
        when (tokens[pos].type) {
            TokenType.LBRACE -> braceDepth++
            TokenType.RBRACE -> braceDepth--
            TokenType.EOF -> return false
            else -> {}
        }
        pos++
    }

    while (pos < tokens.size && tokens[pos].type == TokenType.NEWLINE) {
        pos++
    }

    val result = pos < tokens.size && tokens[pos].type == TokenType.ARROW
    if (System.getProperty("parser.debug") == "true") {
        println("[lookAheadForArrowAfterBrace] Looking at position ${stream.position}, found arrow: $result")
    }
    return result
}

/**
 * Expression parser specifically for if-let bindings in match guards.
 *
 * In the context `if let Pattern = Expression -> body`, the `->` after the expression
 * belongs to the match case, NOT to a lambda. This parser treats all `{...}` after
 * an identifier as function call arguments, never as lambda starts.
 *
 * This is necessary because normal expression parsing would see `getValue {x = n} -> v`
 * and think `{x = n} -> v` is a lambda (due to lookAheadForArrowAfterBrace).
 */
internal val MezzeParser.ifLetBindingExpression: Parser<ParsedExpr>
    get() = { stream ->
        ifLetPostfixExpression(stream)
    }

internal val MezzeParser.ifLetPostfixExpression: Parser<ParsedExpr>
    get() = { stream ->
        var result: ParseResult<ParsedExpr> = primaryExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val token = currentStream.current()

            when (token?.type) {
                TokenType.LBRACE -> {
                    val args = recordLiteral(currentStream)
                    result = when (args) {
                        is ParseResult.Success -> {
                            val expr = result.value
                            when {
                                expr is ParsedVariantConstruct && expr.value == null -> {
                                    ParseResult.Success(
                                        ParsedVariantConstruct(
                                            constructor = expr.constructor,
                                            value = args.value,
                                            loc = expr.loc
                                        ),
                                        args.remaining
                                    )
                                }
                                expr is ParsedVariantConstruct -> {
                                    ParseResult.Success(
                                        expr.copy(value = args.value),
                                        args.remaining
                                    )
                                }
                                expr is ParsedIdentifier && expr.localName.firstOrNull()?.isUpperCase() == true -> {
                                    ParseResult.Success(
                                        ParsedVariantConstruct(
                                            constructor = expr.localName,
                                            value = args.value,
                                            loc = expr.loc.spanTo(args.value.loc)
                                        ),
                                        args.remaining
                                    )
                                }
                                else -> {
                                    ParseResult.Success(
                                        ParsedFunctionCall(
                                            result.value,
                                            args.value,
                                            loc = result.value.loc.spanTo(args.value.loc)
                                        ),
                                        args.remaining
                                    )
                                }
                            }
                        }
                        is ParseResult.Failure -> break
                    }
                }
                TokenType.DOT -> {
                    val dotStream = currentStream.advance()
                    // Newtype construction: Name.(expr)
                    if (dotStream.current()?.type == TokenType.LPAREN) {
                        val receiver = result.value
                        val typeName = when {
                            receiver is ParsedIdentifier -> receiver.localName
                            receiver is ParsedVariantConstruct && receiver.value == null -> receiver.constructor
                            else -> null
                        }
                        if (typeName != null) {
                            val afterLparen = skipNewlines(dotStream.advance())
                            val innerExpr = expression(afterLparen)
                            result = when (innerExpr) {
                                is ParseResult.Success -> {
                                    val rparen = tok(TokenType.RPAREN)(skipNewlines(innerExpr.remaining))
                                    when (rparen) {
                                        is ParseResult.Success -> {
                                            val file = currentFile ?: "<unknown>"
                                            ParseResult.Success(
                                                ParsedNewtypeConstruct(
                                                    typeName = typeName,
                                                    value = innerExpr.value,
                                                    loc = receiver.loc.spanTo(tokenLoc(rparen.value, file))
                                                ),
                                                rparen.remaining
                                            )
                                        }
                                        is ParseResult.Failure -> ParseResult.Failure(
                                            "Expected ')' to close newtype construction '$typeName.(...)'",
                                            rparen.position, rparen.line, rparen.column
                                        )
                                    }
                                }
                                is ParseResult.Failure -> ParseResult.Failure(
                                    "Expected expression inside newtype construction '$typeName.(...)'",
                                    innerExpr.position, innerExpr.line, innerExpr.column
                                )
                            }
                        } else {
                            result = ParseResult.Failure(
                                "Newtype construction '.(' requires a type name on the left, but got an expression. Did you mean to call a method? Use '.method_name {}' instead.",
                                currentStream.getSourcePosition(), currentStream.getSourceLine(), currentStream.getSourceColumn()
                            )
                        }
                    } else {
                        val member = memberIdentifierParser(dotStream)
                        result = when (member) {
                            is ParseResult.Success -> {
                                val file = currentFile ?: "<unknown>"
                                val memberTokenLoc = tokenLoc(member.value, file)
                                ParseResult.Success(
                                    ParsedMemberAccess(
                                        result.value,
                                        member.value.text,
                                        loc = result.value.loc.spanTo(memberTokenLoc),
                                        memberLoc = memberTokenLoc
                                    ),
                                    member.remaining
                                )
                            }
                            is ParseResult.Failure -> break
                        }
                    }
                }
                else -> break
            }
        }

        result
    }

internal val MezzeParser.lambdaOrRecordExpression: Parser<ParsedExpr>
    get() = { stream ->
        if (stream.current()?.type != TokenType.LBRACE) {
            stream.failure("Expected '{' to start a lambda or record literal, but got ${tokenGotDescription(stream.current())}")
        } else {
            val hasArrowAfterBrace = lookAheadForArrowAfterBrace(stream)

            if (hasArrowAfterBrace) {
                lambdaExpression(stream)
            } else {
                recordLiteral(stream)
            }
        }
    }


internal val MezzeParser.lambdaExpression: Parser<ParsedExpr>
    get() = parser@{ stream ->

        when (val lbrace = tok(TokenType.LBRACE)(stream)) {
            is ParseResult.Failure -> return@parser lbrace
            is ParseResult.Success -> {
                val file = currentFile ?: "<unknown>"
                val lbraceTok = lbrace.value
                val stream1 = skipNewlines(lbrace.remaining)

                when (val paramsResult = lambdaParameters(stream1)) {
                    is ParseResult.Failure -> return@parser paramsResult
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(paramsResult.remaining)
                        when (val rbrace = tok(TokenType.RBRACE)(stream2)) {
                            is ParseResult.Failure -> return@parser rbrace
                            is ParseResult.Success -> {
                                val stream3 = skipNewlines(rbrace.remaining)
                                when (val arrow = tok(TokenType.ARROW)(stream3)) {
                                    is ParseResult.Failure -> return@parser arrow
                                    is ParseResult.Success -> {
                                        val stream4 = skipNewlines(arrow.remaining)

                                        // Targeted error: lambda body cannot be a `let`.
                                        // A lambda body is a single expression; `let` is
                                        // a statement and only legal inside a `do` block.
                                        // Catching this here gives the user the right
                                        // location (the arrow) and the right fix (`do`),
                                        // instead of the misleading "Do block must end
                                        // with a result expression" surfaced at the
                                        // enclosing let when the parser unwinds.
                                        val nextTok = stream4.current()
                                        if (nextTok?.type == TokenType.LET) {
                                            // Mark fatal so the enclosing do-block surfaces THIS
                                            // error verbatim instead of falling through to its
                                            // generic "Do block is empty / must end with result"
                                            // recovery message (cascade noise).
                                            return@parser ParseResult.Failure(
                                                "Lambda body cannot begin with `let` — `let` is a statement, " +
                                                    "and a lambda body is a single expression.",
                                                stream4.getSourcePosition(),
                                                nextTok.line,
                                                nextTok.column,
                                                isFatal = true,
                                                code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0121_LAMBDA_BODY_BARE_LET,
                                                hints = listOf(
                                                    "change `{ ... } ->` to `{ ... } -> do` and put the let-statements + final expression inside"
                                                )
                                            )
                                        }

                                        // Targeted error: `{params} -> Ret do body`.
                                        // Mezze lambdas don't take a return-type annotation
                                        // inline. Users who try this end up with the type as
                                        // the lambda body and a stray `do body` that silently
                                        // gets parsed as something else, hiding the param
                                        // bindings — the typechecker then says "n is undefined".
                                        // Catch the shape here.
                                        //
                                        // Heuristic: lookahead — capitalised identifier
                                        // (starts a type) immediately followed by `do`.
                                        // Conservative; doesn't trigger on lower-case body
                                        // expressions that happen to be a single identifier
                                        // (those legitimately can precede a `do` block as
                                        // separate statements in a do-block-wrapped lambda).
                                        if (nextTok != null && nextTok.type == TokenType.IDENTIFIER) {
                                            val firstChar = nextTok.text.firstOrNull()
                                            val isTypeName = firstChar != null && firstChar.isUpperCase()
                                            if (isTypeName) {
                                                // identifier args (e.g. `List Int`, `Result Int Str`).
                                                // Stop at newline, `do`, or anything that isn't an ident.
                                                var scan = stream4.advance()
                                                val typeTextParts = mutableListOf(nextTok.text)
                                                while (true) {
                                                    val t = scan.current() ?: break
                                                    if (t.type == TokenType.IDENTIFIER) {
                                                        typeTextParts.add(t.text)
                                                        scan = scan.advance()
                                                    } else break
                                                }
                                                val afterType = skipNewlines(scan)
                                                val maybeDo = afterType.current()
                                                if (maybeDo?.type == TokenType.DO) {
                                                    val typeText = typeTextParts.joinToString(" ")
                                                    return@parser ParseResult.Failure(
                                                        "Lambda return-type annotation isn't supported inline. " +
                                                            "Mezze infers return types — drop the `$typeText` and write `-> do body`. " +
                                                            "If you want to annotate the function's type, put it on the `let`: " +
                                                            "`let f: { ... } -> $typeText = { ... } -> do body`.",
                                                        stream4.getSourcePosition(),
                                                        nextTok.line,
                                                        nextTok.column,
                                                        isFatal = true,
                                                        code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0123_LAMBDA_INLINE_RETURN_TYPE,
                                                        hints = listOf(
                                                            "remove `$typeText` between `->` and `do` (return type is inferred)",
                                                            "or annotate the whole function: `let f: ... -> $typeText = { ... } -> do ...`"
                                                        )
                                                    )
                                                }
                                            }
                                        }

                                        when (val body = expression(stream4)) {
                                            is ParseResult.Failure -> return@parser body
                                            is ParseResult.Success -> {
                                                // Span from the opening brace through the body — covers
                                                // the whole `{ params } -> body` expression so the LSP
                                                // can find this lambda for any position inside.
                                                val lambdaLoc = spanLocTo(lbraceTok, body.remaining, file)
                                                ParseResult.Success(
                                                    ParsedLambda(paramsResult.value, body.value, returnType = null, abilityConstraints = emptyList(), loc = lambdaLoc),
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
    }

internal val MezzeParser.lambdaParameters: Parser<ParsedLambdaParams>
    get() = { stream ->
        val typedResult = typedLambdaParams(stream)
        when (typedResult) {
            is ParseResult.Success -> typedResult
            is ParseResult.Failure -> untypedLambdaParams(stream)
        }
    }

internal val MezzeParser.lambdaParameterEntry: Parser<Pair<String, ParsedTypeAnnotation?>>
    get() = { stream ->
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                val paramName = ident.value.text

                val reservedError = checkReservedName(paramName)
                if (reservedError != null) {
                    // Use remaining position so this error "wins" over earlier failures,
                    // but report the token's line/column for better error pointing
                    ParseResult.Failure(
                        reservedError,
                        ident.remaining.getSourcePosition(),
                        ident.value.line,
                        ident.value.column
                    )
                } else {
                    val stream1 = ident.remaining

                    if (stream1.current()?.type == TokenType.COLON) {
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
                        ParseResult.Success(Pair(paramName, null), stream1)
                    }
                }
            }
            is ParseResult.Failure -> ident
        }
    }

internal val MezzeParser.typedLambdaParams: Parser<ParsedLambdaParams>
    get() = { stream ->
        // {x: Int, y, z: String,} - allows mixed typed/untyped parameters with optional trailing comma
        // At least one parameter must have a type annotation for this to be considered typed
        val entries = Parsers.sepEndBy(lambdaParameterEntry, tok(TokenType.COMMA))(stream)
        when (entries) {
            is ParseResult.Success -> {
                val entryList = entries.value
                if (entryList.isEmpty()) {
                    stream.failure("Expected at least one parameter in lambda. Use {x} -> expr or {x: Type} -> expr")
                } else {
                    val hasTypedParam = entryList.any { it.second != null }
                    if (hasTypedParam) {
                        val file = currentFile ?: "<unknown>"
                        val recordEntries = entryList.map { (name, typeAnnotation) ->
                            val finalType: ParsedTypeAnnotation = typeAnnotation ?: run {
                                val loc = streamLoc(entries.remaining, file)
                                ParsedNamedType("'${name}", loc)
                            }
                            val loc = streamLoc(entries.remaining, file)
                            ParsedRecordTypeEntry(name, finalType, loc)
                        }
                        ParseResult.Success(ParsedLambdaParams.Typed(recordEntries), entries.remaining)
                    } else {
                        // All parameters are untyped, fail so we can try untyped parser
                        stream.failure("When using type annotations, at least one parameter must have a type. Use {x: Int} -> expr")
                    }
                }
            }
            is ParseResult.Failure -> entries
        }
    }

internal val MezzeParser.untypedLambdaParams: Parser<ParsedLambdaParams>
    get() = parser@{ stream ->
        // {x, y,} or {x, y, ...,} or {} - allows optional trailing comma
        val params = Parsers.sepEndBy(identifierParser, tok(TokenType.COMMA))(stream)
        when (params) {
            is ParseResult.Success -> {
                val tokens = params.value

                for (token in tokens) {
                    val reservedError = checkReservedName(token.text)
                    if (reservedError != null) {
                        return@parser ParseResult.Failure(
                            reservedError,
                            params.remaining.getSourcePosition(),
                            token.line,
                            token.column
                        )
                    }
                }

                val names = tokens.map { it.text }
                val file = currentFile ?: "<unknown>"
                val nameLocs = tokens.map { tokenLoc(it, file) }
                val stream1 = params.remaining

                val afterComma = if (stream1.current()?.type == TokenType.COMMA) {
                    skipNewlines(stream1.advance())
                } else {
                    stream1
                }

                val isOpen = afterComma.current()?.type == TokenType.ELLIPSIS

                val stream2 = if (isOpen) {
                    skipNewlines(afterComma.advance())  // Skip the ellipsis token
                } else {
                    afterComma
                }

                ParseResult.Success(ParsedLambdaParams.Untyped(names, isOpen, nameLocs), stream2)
            }
            is ParseResult.Failure -> params
        }
    }


internal val MezzeParser.recordLiteral: Parser<ParsedExpr>
    get() = { stream ->
        // {x = 1, y = 2} or {x, y} (punning) or {...rest} or {x = 1, ...} (anonymous open record)
        val lbrace = tok(TokenType.LBRACE)(stream)
        when (lbrace) {
            is ParseResult.Success -> {
                val file = currentFile ?: "<unknown>"
                val recordStartToken = lbrace.value

                // Explicit entry loop (instead of the shared sepEndBy combinator)
                // so comments between fields survive: collect an annotation block
                // before each entry and attach it. Bails to the combinator's
                // behaviour on the empty/spread/close cases.
                val collectedEntries = mutableListOf<ParsedRecordEntry>()
                var cur = skipNewlinesOnly(lbrace.remaining)
                var recordFailure: ParseResult.Failure? = null
                while (true) {
                    parseTick(cur)  // anti-hang: bounded work per record entry
                    val (afterAnn, ann) = skipNewlinesCollectAnnotations(cur)
                    cur = afterAnn
                    val here = cur.current()
                    // Stop at `}`. A trailing `...` with a `}` after it (possibly
                    // across newlines, for a multi-line record) is the open-record
                    // marker (handled below); a `...expr` spread is a real entry,
                    // so only bail on a bare `...` that closes the record.
                    if (here == null || here.type == TokenType.RBRACE) break
                    if (here.type == TokenType.ELLIPSIS &&
                        skipNewlinesOnly(cur.advance()).current()?.type == TokenType.RBRACE) break

                    val entryResult = recordLiteralEntryParser(cur)
                    if (entryResult !is ParseResult.Success) {
                        recordFailure = entryResult as? ParseResult.Failure
                        break
                    }
                    val entry = when (val e = entryResult.value) {
                        is ParsedRecordEntry.Field -> e.copy(
                            leadingComments = ann.regularComments + (if (ann.docComment != null)
                                listOf(com.mezze.language.parsedast.ParsedComment("## ${ann.docComment}", true, e.loc)) else emptyList())
                        )
                        is ParsedRecordEntry.Spread -> e.copy(leadingComments = ann.regularComments)
                    }
                    collectedEntries.add(entry)
                    cur = skipNewlinesOnly(entryResult.remaining)
                    // Optional trailing comma; skip it (and following newlines).
                    val comma = tok(TokenType.COMMA)(cur)
                    if (comma is ParseResult.Success) {
                        cur = skipNewlinesOnly(comma.remaining)
                    } else {
                        break
                    }
                }

                if (recordFailure != null) {
                    recordFailure
                } else {
                    // Trailing comments before `}` / `...` (skip; rare).
                    var stream2 = skipNewlines(cur)
                    val ellipsisCheck = tok(TokenType.ELLIPSIS)(stream2)
                    if (ellipsisCheck is ParseResult.Success) {
                        stream2 = skipNewlines(ellipsisCheck.remaining)
                    }
                    val rbrace = tok(TokenType.RBRACE)(stream2)
                    when (rbrace) {
                        is ParseResult.Success -> {
                            val loc = spanLoc(recordStartToken, rbrace.value, file)
                            ParseResult.Success(ParsedRecordLit(collectedEntries, loc), rbrace.remaining)
                        }
                        is ParseResult.Failure -> rbrace
                    }
                }
            }
            is ParseResult.Failure -> lbrace
        }
    }

internal val MezzeParser.recordLiteralEntryParser: Parser<ParsedRecordEntry>
    get() = { stream ->
        val current = stream.current()
        val file = currentFile ?: "<unknown>"

        if (current?.type == TokenType.ELLIPSIS) {
            val ellipsis = tok(TokenType.ELLIPSIS)(stream)
            when (ellipsis) {
                is ParseResult.Success -> {
                    val ellipsisToken = ellipsis.value
                    val expr = expression(ellipsis.remaining)
                    when (expr) {
                        is ParseResult.Success -> {
                            val loc = spanLocTo(ellipsisToken, expr.remaining, file)
                            ParseResult.Success(
                                ParsedRecordEntry.Spread(expr.value, loc),
                                expr.remaining
                            )
                        }
                        is ParseResult.Failure -> expr
                    }
                }
                is ParseResult.Failure -> ellipsis
            }
        } else {
            val ident = identifierParser(stream)
            when (ident) {
                is ParseResult.Success -> {
                    val stream1 = ident.remaining
                    val fieldToken = ident.value

                    // `{ a: ... }` in value position: a record LITERAL binds fields
                    // with `=`, not `:` (which annotates a TYPE, only in a type
                    // position). Catch it here — otherwise `a` puns as a field and
                    // the stray `:` surfaces later as a misleading "Expected '}'".
                    if (stream1.current()?.type == TokenType.COLON) {
                        stream1.failure(
                            "Record fields bind with `=`, not `:`: write " +
                                "`{ ${ident.value.text} = value }`. A `:` here reads as a " +
                                "type annotation, which only belongs in a type position.",
                            code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0129_RECORD_FIELD_USES_EQUALS,
                            hints = listOf(
                                "write `${ident.value.text} = <value>`, or just `${ident.value.text}` to pun a same-named binding"
                            )
                        )
                    } else {
                    val assign = tok(TokenType.ASSIGN)(stream1)
                    when (assign) {
                        is ParseResult.Success -> {
                            val expr = expression(assign.remaining)
                            when (expr) {
                                is ParseResult.Success -> {
                                    val loc = spanLocTo(fieldToken, expr.remaining, file)
                                    ParseResult.Success(
                                        ParsedRecordEntry.Field(ident.value.text, expr.value, loc),
                                        expr.remaining
                                    )
                                }
                                is ParseResult.Failure -> expr
                            }
                        }
                        is ParseResult.Failure -> {
                            val loc = tokenLoc(fieldToken, file)
                            ParseResult.Success(
                                ParsedRecordEntry.Field(ident.value.text, null, loc),
                                stream1
                            )
                        }
                    }
                    }
                }
                is ParseResult.Failure -> ident
            }
        }
    }


internal val MezzeParser.arrayLiteral: Parser<ParsedExpr>
    get() = { stream ->
        // Supports spread only at the start: [...xs, a, b, c]
        // NOT allowed: [a, ...xs] or [a, ...xs, b]
        val file = currentFile ?: "<unknown>"
        val lbracketToken = stream.current()
        // Fall back to the token-aware streamLoc helper (which picks up the
        // last token's position when the stream is exhausted) instead of a
        // hardcoded (1,1) — under EOF that gave misleading "error at start
        // of file" diagnostics for `[` at end of input. Audit #211.
        val seqLoc = lbracketToken?.let { tokenLoc(it, file) } ?: streamLoc(stream, file)
        val lbracket = tok(TokenType.LBRACKET)(stream)
        when (lbracket) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(lbracket.remaining)

                val rbracket = tok(TokenType.RBRACKET)(stream1)
                if (rbracket is ParseResult.Success) {
                    val loc = spanLoc(lbracket.value, rbracket.value, file)
                    ParseResult.Success(
                        ParsedSeqLit(emptyList(), loc),
                        rbracket.remaining
                    )
                } else {
                    val elements = sepEndByWithNewlines(
                        arrayElementParser,
                        tok(TokenType.COMMA)
                    )(stream1)

                    when (elements) {
                        is ParseResult.Success -> {
                            // Validate spread position: spreads must come before regular elements
                            validateSeqSpreadPosition(elements.value, seqLoc, currentSource, currentFile)

                            val stream2 = skipNewlines(elements.remaining)
                            val rbracket2 = tok(TokenType.RBRACKET)(stream2)

                            when (rbracket2) {
                                is ParseResult.Success -> {
                                    val loc = spanLoc(lbracket.value, rbracket2.value, file)
                                    ParseResult.Success(
                                        ParsedSeqLit(elements.value, loc),
                                        rbracket2.remaining
                                    )
                                }
                                is ParseResult.Failure -> {
                                    val token = stream2.current()
                                    stream2.failure("Expected ',' between seq elements or ']' to close seq, but got ${tokenGotDescription(token)}")
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

private fun validateSeqSpreadPosition(elements: List<ParsedSeqElement>, loc: Loc, source: String?, file: String?) {
    var seenRegular = false
    for (element in elements) {
        when (element) {
            is ParsedSeqElement.Regular -> seenRegular = true
            is ParsedSeqElement.Spread -> {
                if (seenRegular) {
                    throw ParseException(
                        "Spread (...) must come before regular elements in seq literals. " +
                        "Use [...existing, new1, new2] to prepend elements to an existing seq.",
                        position = 0,
                        line = loc.startLine,
                        column = loc.startCol,
                        sourceCode = source,
                        file = file,
                        code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0104_SPREAD_NOT_AT_START,
                    )
                }
            }
        }
    }
}

internal val MezzeParser.arrayElementParser: Parser<ParsedSeqElement>
    get() = { stream ->
        val current = stream.current()
        val file = currentFile ?: "<unknown>"

        if (current?.type == TokenType.ELLIPSIS) {
            val ellipsis = tok(TokenType.ELLIPSIS)(stream)
            when (ellipsis) {
                is ParseResult.Success -> {
                    val ellipsisToken = ellipsis.value
                    val expr = expression(ellipsis.remaining)
                    when (expr) {
                        is ParseResult.Success -> {
                            val loc = spanLocTo(ellipsisToken, expr.remaining, file)
                            ParseResult.Success(
                                ParsedSeqElement.Spread(expr.value, loc),
                                expr.remaining
                            )
                        }
                        is ParseResult.Failure -> expr
                    }
                }
                is ParseResult.Failure -> ellipsis
            }
        } else {
            val expr = expression(stream)
            when (expr) {
                is ParseResult.Success -> ParseResult.Success(
                    ParsedSeqElement.Regular(expr.value),
                    expr.remaining
                )
                is ParseResult.Failure -> expr
            }
        }
    }


internal val MezzeParser.primaryExpression: Parser<ParsedExpr>
    get() = { stream ->
        val current = stream.current()
        val next = stream.peek(1)

        when {
            current?.type == TokenType.LET -> {
                stream.failure(
                    "Cannot use 'let' here. To sequence multiple statements, wrap them in a 'do' block"
                )
            }
            current?.type == TokenType.LPAREN && next?.type == TokenType.RPAREN -> {
                val afterParen = stream.advance(2)
                val afterParenToken = afterParen.current()
                if (afterParenToken?.type == TokenType.ARROW) {
                    stream.failure(
                        "Invalid lambda syntax: '() ->' is not valid in Mezze.\n" +
                        "   Use '{}' for a no-argument lambda: {} -> expression\n" +
                        "   Or with arguments: {x, y} -> expression"
                    )
                } else {
                    stream.failure(
                        "Empty parentheses '()' are not valid in Mezze.\n" +
                        "   • Use '{}' for an empty record\n" +
                        "   • Use '{} -> expr' for a no-argument lambda"
                    )
                }
            }
            else -> {
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
        }
    }


internal val MezzeParser.ifExpression: Parser<ParsedExpr>
    get() = parser@{ stream ->
        val file = currentFile ?: "<unknown>"

        when (val ifToken = tok(TokenType.IF)(stream)) {
            is ParseResult.Failure -> return@parser ifToken
            is ParseResult.Success -> {
                val ifTok = ifToken.value
                val stream1 = skipNewlines(ifToken.remaining)
                when (val condition = expression(stream1)) {
                    is ParseResult.Failure -> return@parser condition
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(condition.remaining)
                        when (val thenToken = tok(TokenType.THEN)(stream2)) {
                            is ParseResult.Failure -> return@parser thenToken
                            is ParseResult.Success -> {
                                val stream3 = skipNewlines(thenToken.remaining)
                                when (val thenBranch = expression(stream3)) {
                                    is ParseResult.Failure -> return@parser thenBranch
                                    is ParseResult.Success -> {
                                        val stream4 = skipNewlines(thenBranch.remaining)
                                        when (val elseToken = tok(TokenType.ELSE)(stream4)) {
                                            is ParseResult.Failure -> return@parser elseToken
                                            is ParseResult.Success -> {
                                                val stream5 = skipNewlines(elseToken.remaining)
                                                when (val elseBranch = expression(stream5)) {
                                                    is ParseResult.Failure -> return@parser elseBranch
                                                    is ParseResult.Success -> ParseResult.Success(
                                                        ParsedIf(
                                                            condition.value,
                                                            thenBranch.value,
                                                            elseBranch.value,
                                                            loc = spanLocTo(ifTok, elseBranch.remaining, file)
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



internal fun MezzeParser.parseQualifiedRef(stream: TokenStream): ParseResult<ParsedQualifiedRef> {
    val file = currentFile ?: "<unknown>"
    val firstId = identifierParser(stream)
    return when (firstId) {
        is ParseResult.Success -> {
            val startToken = firstId.value
            val segments = mutableListOf(firstId.value.text)
            var currentStream = firstId.remaining

            while (true) {
                val doubleColon = tok(TokenType.DOUBLE_COLON)(currentStream)
                if (doubleColon is ParseResult.Success) {
                    val nextId = identifierParser(doubleColon.remaining)
                    when (nextId) {
                        is ParseResult.Success -> {
                            segments.add(nextId.value.text)
                            currentStream = nextId.remaining
                        }
                        is ParseResult.Failure -> break
                    }
                } else {
                    break
                }
            }

            // Last segment is the localName, previous segments form the qualifier
            val localName = segments.last()
            val qualifierSegments = segments.dropLast(1)
            val qualifier = if (qualifierSegments.isEmpty()) null else ParsedModulePath(qualifierSegments)
            val loc = spanLocTo(startToken, currentStream, file)

            val ref = ParsedQualifiedRef(
                localName = localName,
                qualifier = qualifier,
                loc = loc
            )
            ParseResult.Success(ref, currentStream)
        }
        is ParseResult.Failure -> firstId
    }
}

internal val MezzeParser.performExpression: Parser<ParsedExpr>
    get() = { stream ->
        // 1. perform operation {args} - simple effect call
        // 2. perform Handler1, Handler2 in expression - perform with handlers
        val file = currentFile ?: "<unknown>"
        val performToken = tok(TokenType.PERFORM)(stream)
        when (performToken) {
            is ParseResult.Success -> {
                val performTok = performToken.value
                val stream1 = skipNewlines(performToken.remaining)

                val firstRef = parseQualifiedRef(stream1)
                when (firstRef) {
                    is ParseResult.Success -> {
                        val firstQualifiedRef = firstRef.value
                        val stream2 = skipNewlines(firstRef.remaining)

                        // Try to parse optional parameters for first handler
                        val firstParamsResult = recordLiteral(stream2)
                        val (firstParams, stream3) = when (firstParamsResult) {
                            is ParseResult.Success -> Pair(firstParamsResult.value as? ParsedRecordLit, firstParamsResult.remaining)
                            is ParseResult.Failure -> Pair(null, stream2)
                        }

                        val streamAfterParams = skipNewlines(stream3)
                        val nextToken = streamAfterParams.current()

                        if (nextToken?.type == TokenType.COMMA || nextToken?.type == TokenType.IN) {
                            // Form 2: perform Handler1, Handler2 in expression
                            val handlers = mutableListOf<ParsedPerformHandler>()
                            handlers.add(ParsedPerformHandler(firstQualifiedRef, firstParams))
                            var currentStream = streamAfterParams

                            var errorResult: ParseResult.Failure? = null
                            while (errorResult == null) {
                                val commaToken = tok(TokenType.COMMA)(currentStream)
                                when (commaToken) {
                                    is ParseResult.Success -> {
                                        val streamAfterComma = skipNewlines(commaToken.remaining)
                                        val handlerRefResult = parseQualifiedRef(streamAfterComma)
                                        when (handlerRefResult) {
                                            is ParseResult.Success -> {
                                                val handlerRef = handlerRefResult.value
                                                val stream4 = skipNewlines(handlerRefResult.remaining)

                                                val paramsResult = recordLiteral(stream4)
                                                val (params, stream5) = when (paramsResult) {
                                                    is ParseResult.Success -> Pair(paramsResult.value as? ParsedRecordLit, paramsResult.remaining)
                                                    is ParseResult.Failure -> Pair(null, stream4)
                                                }

                                                handlers.add(ParsedPerformHandler(handlerRef, params))
                                                currentStream = skipNewlines(stream5)
                                            }
                                            is ParseResult.Failure -> errorResult = handlerRefResult
                                        }
                                    }
                                    is ParseResult.Failure -> break
                                }
                            }

                            if (errorResult != null) {
                                errorResult
                            } else {
                                val inToken = tok(TokenType.IN)(currentStream)
                                when (inToken) {
                                    is ParseResult.Success -> {
                                        val stream6 = skipNewlines(inToken.remaining)
                                        val expr = expression(stream6)
                                        when (expr) {
                                            is ParseResult.Success -> ParseResult.Success(
                                                ParsedPerform(handlers, expr.value, loc = spanLocTo(performTok, expr.remaining, file)),
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
                            // The qualified ref is the operation (e.g., info or Log::info)
                            val argsResult = recordLiteral(stream2)
                            when (argsResult) {
                                is ParseResult.Success -> ParseResult.Success(
                                    ParsedEffectCall(
                                        operation = firstQualifiedRef,
                                        arguments = argsResult.value,
                                        loc = spanLocTo(performTok, argsResult.remaining, file)
                                    ),
                                    argsResult.remaining
                                )
                                is ParseResult.Failure -> argsResult
                            }
                        }
                    }
                    is ParseResult.Failure -> firstRef
                }
            }
            is ParseResult.Failure -> performToken
        }
    }


internal val MezzeParser.doExpression: Parser<ParsedExpr>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        val doToken = tok(TokenType.DO)(stream)
        when (doToken) {
            is ParseResult.Success -> {
                val doTok = doToken.value
                // Skip newlines only (not comments) so a comment before the
                // first statement survives to the loop's annotation collector.
                val stream1 = skipNewlinesOnly(doToken.remaining)

                val statements = mutableListOf<ParsedStatement>()
                var currentStream = stream1
                var finalResult: Pair<ParsedExpr, TokenStream>? = null
                var finalResultComments: List<ParsedComment> = emptyList()
                var lastStatementWasLet = false
                var lastLetName: String? = null
                // Position of the `let` keyword of the last statement, so the
                // "Do block must end with a result expression" error can point
                // at the actual offending `let`, not the (much later) position
                // where the parser noticed the missing trailing expression.
                // Only valid when lastStatementWasLet is true.
                var lastLetLine = 0
                var lastLetCol = 0

                // Capture the deepest fatal failure encountered while trying to
                // parse a statement body. Fatal failures (`isFatal = true`)
                // come from parsers that have already committed to a path —
                // e.g. `additiveExpression` after consuming a left operand
                // and then seeing `++`. We surface those instead of the
                // generic "Do block is empty / must end with expression"
                // message which buried the real error.
                var fatalFailure: ParseResult.Failure? = null
                fun keepIfFatal(f: ParseResult.Failure) {
                    if (f.isFatal && (fatalFailure == null || f.position > fatalFailure!!.position)) {
                        fatalFailure = f
                    }
                }

                // Track the column of the first *code* token in the do block to
                // determine scope — skip past any leading comments (which the
                // loop's collector will pick up) so the column reflects the
                // first statement's indentation, not a comment's.
                val doBlockColumn = skipNewlines(stream1).current()?.column

                while (finalResult == null) {
                    parseTick(currentStream)  // anti-hang: bounded work per do-statement
                    // Collect any comments (`#`, `#{}`, `##`) sitting before the
                    // next statement so the formatter can round-trip them. They
                    // attach to whichever statement is parsed next.
                    val beforeAnnotations = currentStream
                    val (afterAnnotations, stmtAnnotations) = skipNewlinesCollectAnnotations(currentStream)
                    currentStream = afterAnnotations

                    val currentToken = currentStream.current()
                    if (currentToken != null && doBlockColumn != null && currentToken.column < doBlockColumn && currentToken.type != TokenType.EOF) {
                        // The next real token dedents out of the block. Any
                        // comments we just consumed belong to the FOLLOWING
                        // top-level decl (e.g. a `##` doc block above `let help`),
                        // not to a phantom statement in this block — rewind so the
                        // outer decl parser can attach them. Without this the doc
                        // comment is silently eaten and never reaches the decl.
                        currentStream = beforeAnnotations
                        break
                    }

                    // Try to parse a let statement
                    val letToken = tok(TokenType.LET)(currentStream)
                    if (letToken is ParseResult.Success) {
                        val stream2 = skipNewlines(letToken.remaining)
                        val patternResult = letBindingPattern(stream2)

                        when (patternResult) {
                            is ParseResult.Success -> {
                                val pattern = patternResult.value
                                val stream3 = skipNewlines(patternResult.remaining)

                                // Validate that the pattern is irrefutable
                                if (!pattern.isIrrefutable()) {
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
                                            is ParseResult.Failure -> { keepIfFatal(typeAnn); break }
                                        }
                                    }
                                    is ParseResult.Failure -> Pair(null, stream3)
                                }

                                val stream5 = skipNewlines(streamAfterType)

                                // A `let` binding statement: `let pat = expr`.
                                // The leading `let` is REQUIRED (it makes every
                                // binding statement start with `let`,
                                // disambiguating it from a bare expression at
                                // token one — which keeps the grammar
                                // LL(1)-friendly and tooling-parseable).
                                val assign = tok(TokenType.ASSIGN)(stream5)

                                when (assign) {
                                    is ParseResult.Success -> {
                                        val stream6 = skipNewlines(assign.remaining)
                                        val expr = expression(stream6)

                                        when (expr) {
                                            is ParseResult.Success -> {
                                                // Span the entire statement: `let x = expr` covers
                                                // from `let` through the RHS so position lookup
                                                // can hit the binding from any column.
                                                val stmtLoc = spanLocTo(letToken.value, expr.remaining, file)
                                                statements.add(
                                                    ParsedStatement.Let(
                                                        pattern,
                                                        typeAnnotation,
                                                        expr.value,
                                                        stmtLoc,
                                                        leadingComments = stmtAnnotations.regularComments,
                                                        docComment = stmtAnnotations.docComment,
                                                        metadata = stmtAnnotations.metadata,
                                                    )
                                                )
                                                currentStream = skipNewlinesOnly(expr.remaining)
                                                lastStatementWasLet = true
                                                lastLetName = when (pattern) {
                                                    is ParsedVariablePattern -> pattern.name
                                                    else -> null
                                                }
                                                lastLetLine = letToken.value.line
                                                lastLetCol = letToken.value.column
                                            }
                                            is ParseResult.Failure -> { keepIfFatal(expr); break }
                                        }
                                    }
                                    is ParseResult.Failure -> { keepIfFatal(assign); break }
                                }
                            }
                            is ParseResult.Failure -> { keepIfFatal(patternResult); break }
                        }
                    } else {
                        // Not a let statement (`let pat = expr` is handled in the
                        // `let` branch above). Before falling back to expression
                        // parsing, detect a binding that FORGOT its leading
                        // `let` — `x = e` — and give a targeted "you forgot
                        // `let`" error instead of the cryptic downstream failure.
                        val missingLetPat = letBindingPattern(currentStream)
                        if (missingLetPat is ParseResult.Success) {
                            val afterPat = skipNewlines(missingLetPat.remaining)
                            when (afterPat.current()?.type) {
                                TokenType.ASSIGN -> {
                                    fatalFailure = currentStream.failure(
                                        "A binding statement must start with `let`. " +
                                        "Write `let … = …` (this `= …` binding is missing its `let`)."
                                    ).copy(isFatal = true)
                                    break
                                }
                                else -> { /* genuinely an expression statement; fall through */ }
                            }
                        }

                        // Try to parse the statement as an expression.
                        val expr = expression(currentStream)
                        when (expr) {
                            is ParseResult.Success -> {
                                val streamAfterExpr = skipNewlines(expr.remaining)

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
                                    val stmtLoc = expr.value.loc
                                    statements.add(ParsedStatement.Expr(
                                        expr.value, stmtLoc,
                                        leadingComments = stmtAnnotations.regularComments,
                                        docComment = stmtAnnotations.docComment,
                                        metadata = stmtAnnotations.metadata,
                                    ))
                                    // Advance past newlines only (not comments) so a comment on
                                    // the following line is captured by the next iteration.
                                    currentStream = skipNewlinesOnly(expr.remaining)
                                    lastStatementWasLet = false
                                    lastLetName = null
                                } else {
                                    // Next token is outside the do block or at EOF, so this
                                    // expression is the block's final result. Return the stream
                                    // positioned RIGHT AFTER the result expression (no newline
                                    // or comment skipping), so the enclosing decl's span ends at
                                    // the result and every following comment — regular `#`,
                                    // metadata `#{}`, or doc `##` — is left for the next
                                    // top-level decl to attach as its leading block. Skipping
                                    // here bled the decl loc onto the next line's comment, which
                                    // the trailing-comment heuristic then mis-stole.
                                    finalResult = Pair(expr.value, expr.remaining)
                                    // The comments collected for THIS iteration belong to the
                                    // result expression. ParsedDo.result is a bare ParsedExpr
                                    // with no comment fields, so without carrying them here the
                                    // formatter has nothing to print and silently drops them.
                                    finalResultComments = stmtAnnotations.regularComments
                                }
                            }
                            is ParseResult.Failure -> {
                                // Can't parse an expression, we're done
                                keepIfFatal(expr)
                                break
                            }
                        }
                    }
                }

                // Use the final result expression we found
                if (finalResult != null) {
                    // Span the do block from `do` to the END OF THE RESULT
                    // EXPRESSION, not to the trailing stream position — which
                    // may sit on a following `##` doc block after a dedent.
                    // Using the stream position bled the loc onto that comment,
                    // making the top-level trailing-comment heuristic mis-attach
                    // the next decl's doc block to this one (comment then lost).
                    val resultLoc = finalResult.first.loc
                    val doLoc = Loc(
                        file = file,
                        startLine = doTok.line, startCol = doTok.column,
                        endLine = resultLoc.endLine, endCol = resultLoc.endCol,
                    )
                    // INDENTATION: every statement AND the result must start at the same
                    // column. Membership is decided by `column >= the first statement's`
                    // (see the loop above), so a MORE-indented statement is otherwise
                    // swallowed silently — e.g. a body that opens at the margin and then
                    // indents the next line. Reject a block whose statements don't line up,
                    // pointing at the first one that breaks the alignment.
                    val stmtLocs = statements.map { it.loc } + listOf(finalResult.first.loc)
                    val blockCol = stmtLocs.first().startCol
                    val misaligned = stmtLocs.drop(1).firstOrNull { it.startCol != blockCol }
                    if (misaligned != null) {
                        ParseResult.Failure(
                            "Every statement in a `do` block must start at the same column. " +
                                "This one starts at column ${misaligned.startCol}, but the block's first statement is at column $blockCol.",
                            currentStream.getSourcePosition(),
                            misaligned.startLine,
                            misaligned.startCol,
                            code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0135_DO_BLOCK_INCONSISTENT_INDENT,
                            hints = listOf("line up every statement in the `do` block at the same indentation"),
                        )
                    } else {
                        ParseResult.Success(
                            ParsedDo(statements, finalResult.first, loc = doLoc,
                                resultLeadingComments = finalResultComments),
                            finalResult.second
                        )
                    }
                } else if (fatalFailure != null) {
                    // A statement parser produced a targeted error that was
                    // explicitly marked fatal (e.g. "'++' is not a valid operator"
                    // detected after the LHS was already consumed). Surface that
                    // instead of the generic do-block-shape message.
                    fatalFailure!!
                } else {
                    // Did a dedented line close the block (vs. running out of input)?
                    // If so the "ends with a let" error is most often a mis-indent — that
                    // next line was meant to stay inside the block — so add a hint pointing
                    // at it. When the block ended at EOF there is no such line; keep the
                    // plain "add a result expression" advice (the user really did forget it).
                    val dedentTok = currentStream.current()?.takeIf { it.type != TokenType.EOF }
                    val letHints = listOfNotNull(
                        "add a final expression after the let; e.g. `return-value`",
                        dedentTok?.let {
                            "or line ${it.line} is indented less than the block — if it belongs inside the `do`, line it up with the other statements"
                        },
                    )
                    val (errorMsg, errorCode, errorHints) = if (lastStatementWasLet && lastLetName != null) {
                        Triple(
                            "Do block must end with a result expression, not the 'let $lastLetName = ...' statement",
                            com.mezze.language.parsedast.diagnostics.ErrorCode.P0112_DO_BLOCK_LAST_STATEMENT_IS_LET,
                            letHints
                        )
                    } else if (lastStatementWasLet) {
                        Triple(
                            "Do block must end with a result expression, not a 'let' statement",
                            com.mezze.language.parsedast.diagnostics.ErrorCode.P0113_DO_BLOCK_LAST_STATEMENT_NEEDS_EXPR,
                            letHints
                        )
                    } else if (statements.isEmpty()) {
                        Triple(
                            "Do block is empty; needs at least one expression",
                            com.mezze.language.parsedast.diagnostics.ErrorCode.P0114_DO_BLOCK_EMPTY,
                            listOf("add at least one expression inside the do block")
                        )
                    } else {
                        Triple(
                            "Do block must end with a result expression",
                            com.mezze.language.parsedast.diagnostics.ErrorCode.UNCATEGORIZED,
                            emptyList<String>()
                        )
                    }
                    // For "ends with a let" errors, point at the offending
                    // `let` keyword rather than the post-statement position
                    // where the parser noticed the missing trailing expr.
                    if (lastStatementWasLet) {
                        ParseResult.Failure(
                            errorMsg,
                            currentStream.getSourcePosition(),
                            lastLetLine,
                            lastLetCol,
                            code = errorCode,
                            hints = errorHints
                        )
                    } else {
                        currentStream.failure(errorMsg, code = errorCode, hints = errorHints)
                    }
                }
            }
            is ParseResult.Failure -> {
                doToken
            }
        }
    }


internal val MezzeParser.matchExpression: Parser<ParsedExpr>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        val matchToken = tok(TokenType.MATCH)(stream)
        when (matchToken) {
            is ParseResult.Success -> {
                val matchTok = matchToken.value
                val stream1 = skipNewlines(matchToken.remaining)

                // Pre-scan for the Rust/Swift-style `match x case ...` shape.
                // Without this, the inner expression parser eats `x case` as a
                // juxtaposed function call and surfaces P0118 ("Function calls
                // must use {} syntax") pointing at `case`, which is misleading.
                // Only the `case` form is detected here — a bare `{` after the
                // scrutinee is too ambiguous with legitimate function-call
                // scrutinees like `match f { x = 1 } is …` and the existing
                // post-`is` P0122 hint catches the `match x { pattern -> ... }`
                // shape via the inner case parser failing.
                run {
                    val first = stream1.current()
                    if (first?.type == TokenType.IDENTIFIER) {
                        val streamAfter = stream1.advance()
                        val afterScrutinee = streamAfter.current()
                        val isCase = afterScrutinee?.type == TokenType.IDENTIFIER &&
                            afterScrutinee.text == "case"
                        if (isCase) {
                            throw createParseException(
                                "Expected `is` after the match expression. " +
                                    "Mezze match syntax does NOT use a `case` keyword — " +
                                    "write `match x is` and put each arm indented on its own line: " +
                                    "`pattern -> body`.",
                                streamAfter,
                                currentSource,
                                currentFile,
                                code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0122_MATCH_NEEDS_IS,
                                hints = listOf(
                                    "rewrite as: `match x is`",
                                    "  Pattern1 -> result1",
                                    "  Pattern2 -> result2",
                                ),
                            )
                        }
                    }
                }

                val expr = expression(stream1)

                when (expr) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(expr.remaining)
                        val isToken = tok(TokenType.IS)(stream2)

                        when (isToken) {
                            is ParseResult.Success -> {
                                // Skip newlines only so a comment before the first
                                // arm survives; collect it into the arm's fields.
                                val stream3 = skipNewlinesOnly(isToken.remaining)

                                // First-arm column = first *code* token (past comments).
                                val firstCaseColumn = skipNewlines(stream3).current()?.column

                                val (afterFirstAnn, firstAnn) = skipNewlinesCollectAnnotations(stream3)
                                val firstCase = matchCase(afterFirstAnn)

                                when (firstCase) {
                                    is ParseResult.Success -> {
                                        val cases = mutableListOf(firstCase.value.copy(
                                            leadingComments = firstAnn.regularComments,
                                            docComment = firstAnn.docComment,
                                            metadata = firstAnn.metadata,
                                        ))
                                        var currentStream = skipNewlinesOnly(firstCase.remaining)

                                        while (true) {
                                            parseTick(currentStream)  // anti-hang: bounded work per match arm
                                            // Comments before the next arm are only
                                            // THIS match's if a case actually follows.
                                            // Collect them on a SPECULATIVE stream and
                                            // commit that stream only when the arm parses;
                                            // otherwise rewind to before the comments so
                                            // the enclosing decl loop re-collects them as
                                            // the leading doc of the FOLLOWING declaration.
                                            // Consuming-then-discarding here silently ate
                                            // the next decl's `## doc` whenever an inline
                                            // `match … is` ended a top-level let.
                                            val preAnnStream = currentStream
                                            val (afterAnn, armAnn) = skipNewlinesCollectAnnotations(currentStream)
                                            val currentToken = afterAnn.current()

                                            // Stop if:
                                            // 1. We've reached EOF, or
                                            // 2. The token is dedented below the first case column
                                            if (currentToken == null ||
                                                currentToken.type == TokenType.EOF ||
                                                (firstCaseColumn != null && currentToken.column < firstCaseColumn)) {
                                                currentStream = preAnnStream
                                                break
                                            }

                                            // Try to parse another case
                                            val nextCase = matchCase(afterAnn)
                                            when (nextCase) {
                                                is ParseResult.Success -> {
                                                    cases.add(nextCase.value.copy(
                                                        leadingComments = armAnn.regularComments,
                                                        docComment = armAnn.docComment,
                                                        metadata = armAnn.metadata,
                                                    ))
                                                    currentStream = skipNewlinesOnly(nextCase.remaining)
                                                }
                                                is ParseResult.Failure -> {
                                                    // Can't parse another case, we're done —
                                                    // rewind so the comments we speculatively
                                                    // consumed lead the next declaration.
                                                    currentStream = preAnnStream
                                                    break
                                                }
                                            }
                                        }

                                        ParseResult.Success(
                                            ParsedMatch(
                                                expr.value,
                                                cases,
                                                loc = spanLocTo(matchTok, currentStream, file)
                                            ),
                                            currentStream
                                        )
                                    }
                                    is ParseResult.Failure -> firstCase
                                }
                            }
                            is ParseResult.Failure -> {
                                // Targeted hint for users coming from Rust/Swift/etc.
                                // `match x { case Foo -> ... }` is a common attempt;
                                // Mezze syntax is `match x is\n  Foo -> ...` (no `case`
                                // keyword, no braces, indented arms after `is`).
                                val tok = stream2.current()
                                if (tok != null && (tok.type == TokenType.IDENTIFIER && tok.text == "case" ||
                                                    tok.type == TokenType.LBRACE)) {
                                    ParseResult.Failure(
                                        if (tok.text == "case")
                                            "Expected `is` after the match expression. " +
                                                "Mezze match syntax does NOT use a `case` keyword — " +
                                                "write `match x is` and put each arm indented on its own line: " +
                                                "`pattern -> body`."
                                        else
                                            "Expected `is` after the match expression. " +
                                                "Mezze match syntax does NOT use `{ ... }` braces — " +
                                                "write `match x is` and put each arm indented on its own line.",
                                        stream2.getSourcePosition(),
                                        tok.line,
                                        tok.column,
                                        isFatal = true,
                                        code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0122_MATCH_NEEDS_IS,
                                        hints = listOf(
                                            "rewrite as: `match x is`",
                                            "  Pattern1 -> result1",
                                            "  Pattern2 -> result2",
                                        )
                                    )
                                } else {
                                    isToken
                                }
                            }
                        }
                    }
                    is ParseResult.Failure -> expr
                }
            }
            is ParseResult.Failure -> matchToken
        }
    }

internal val MezzeParser.matchCase: Parser<ParsedMatchCase>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        val stream1 = skipNewlines(stream)
        val firstPattern = patternParser(stream1)

        when (firstPattern) {
            is ParseResult.Success -> {
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

                val ifToken = keyword("if", TokenType.IF)(stream2)

                when (ifToken) {
                    is ParseResult.Success -> {
                        val stream3 = skipNewlines(ifToken.remaining)

                        val letCheck = tok(TokenType.LET)(stream3)

                        when (letCheck) {
                            is ParseResult.Success -> {
                                parseIfLetBindingsAndBody(patterns, letCheck.remaining, letCheck.value, file)
                            }
                            is ParseResult.Failure -> {
                                // No 'let', parse as regular guard expression. The
                                // `->` that ends the guard is the match-arm arrow,
                                // not a lambda, so postfix `{…}` here must be read
                                // as call args (see [inMatchGuard]).
                                val savedInMatchGuard = inMatchGuard
                                inMatchGuard = true
                                val guardExp = try {
                                    expression(stream3)
                                } finally {
                                    inMatchGuard = savedInMatchGuard
                                }

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
                                                    is ParseResult.Success -> {
                                                        val caseLoc = firstPattern.value.loc
                                                        ParseResult.Success(
                                                            ParsedMatchCase(
                                                                patterns,
                                                                guardExp.value,  // Guard expression
                                                                emptyList(),  // No if-let bindings
                                                                expr.value,
                                                                caseLoc
                                                            ),
                                                            expr.remaining
                                                        )
                                                    }
                                                    is ParseResult.Failure -> expr
                                                }
                                            }
                                            is ParseResult.Failure -> arrow
                                        }
                                    }
                                    is ParseResult.Failure -> guardExp
                                }
                            }
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
                                    is ParseResult.Success -> {
                                        val caseLoc = firstPattern.value.loc
                                        ParseResult.Success(
                                            ParsedMatchCase(
                                                patterns,
                                                null,  // No guard
                                                emptyList(),  // No if-let bindings
                                                expr.value,
                                                caseLoc
                                            ),
                                            expr.remaining
                                        )
                                    }
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

private fun MezzeParser.parseIfLetBinding(
    stream: TokenStream,
    letToken: Token,
    file: String
): ParseResult<Pair<ParsedIfLetGuard, TokenStream>> {
    val patternStream = skipNewlines(stream)
    val patternResult = patternParser(patternStream)

    return when (patternResult) {
        is ParseResult.Success -> {
            val afterPattern = skipNewlines(patternResult.remaining)
            val eqToken = tok(TokenType.ASSIGN)(afterPattern)

            when (eqToken) {
                is ParseResult.Success -> {
                    val afterEq = skipNewlines(eqToken.remaining)
                    val bindingExpr = ifLetBindingExpression(afterEq)

                    when (bindingExpr) {
                        is ParseResult.Success -> {
                            val guardLoc = tokenLoc(letToken, file)
                            val ifLetBinding = ParsedIfLetGuard(
                                pattern = patternResult.value,
                                expr = bindingExpr.value,
                                loc = guardLoc
                            )
                            ParseResult.Success(
                                Pair(ifLetBinding, bindingExpr.remaining),
                                bindingExpr.remaining
                            )
                        }
                        is ParseResult.Failure -> ParseResult.Failure(
                            bindingExpr.message,
                            bindingExpr.position,
                            bindingExpr.line,
                            bindingExpr.column
                        )
                    }
                }
                is ParseResult.Failure -> {
                    ParseResult.Failure(
                        "Expected '=' after pattern in 'if let' guard",
                        eqToken.position,
                        eqToken.line,
                        eqToken.column
                    )
                }
            }
        }
        is ParseResult.Failure -> {
            ParseResult.Failure(
                "Expected pattern after 'let' in 'if let' guard",
                patternResult.position,
                patternResult.line,
                patternResult.column
            )
        }
    }
}

private fun MezzeParser.parseIfLetBindingsAndBody(
    patterns: List<ParsedPattern>,
    firstBindingStream: TokenStream,
    firstLetToken: Token,
    file: String
): ParseResult<ParsedMatchCase> {
    val ifLetBindings = mutableListOf<ParsedIfLetGuard>()

    val firstBindingResult = parseIfLetBinding(firstBindingStream, firstLetToken, file)
    when (firstBindingResult) {
        is ParseResult.Success -> {
            ifLetBindings.add(firstBindingResult.value.first)
            var currentStream = firstBindingResult.value.second

            while (true) {
                val nextStream = skipNewlines(currentStream)
                val andToken = tok(TokenType.AND)(nextStream)
                when (andToken) {
                    is ParseResult.Success -> {
                        val afterAnd = skipNewlines(andToken.remaining)
                        val nextLetToken = tok(TokenType.LET)(afterAnd)
                        when (nextLetToken) {
                            is ParseResult.Success -> {
                                val nextBindingResult = parseIfLetBinding(nextLetToken.remaining, nextLetToken.value, file)
                                when (nextBindingResult) {
                                    is ParseResult.Success -> {
                                        ifLetBindings.add(nextBindingResult.value.first)
                                        currentStream = nextBindingResult.value.second
                                    }
                                    is ParseResult.Failure -> {
                                        return ParseResult.Failure(
                                            nextBindingResult.message,
                                            nextBindingResult.position,
                                            nextBindingResult.line,
                                            nextBindingResult.column
                                        )
                                    }
                                }
                            }
                            is ParseResult.Failure -> {
                                // && without let - this is an error in this context
                                return ParseResult.Failure(
                                    "Expected 'let' after '&&' in if-let chain",
                                    nextLetToken.position,
                                    nextLetToken.line,
                                    nextLetToken.column
                                )
                            }
                        }
                    }
                    is ParseResult.Failure -> break  // No more &&, done with bindings
                }
            }

            // Now parse -> and result expression
            val streamBeforeArrow = skipNewlines(currentStream)

            val orCheck = tok(TokenType.OR)(streamBeforeArrow)
            if (orCheck is ParseResult.Success) {
                return ParseResult.Failure(
                    "Cannot use '||' to chain if-let bindings. Use '&&' instead. " +
                    "Example: if let Some {val = a} = expr1 && let Some {val = b} = expr2",
                    orCheck.value.position,
                    orCheck.value.line,
                    orCheck.value.column
                )
            }

            val arrow = tok(TokenType.ARROW)(streamBeforeArrow)

            return when (arrow) {
                is ParseResult.Success -> {
                    val stream4 = skipNewlines(arrow.remaining)
                    val expr = expression(stream4)

                    when (expr) {
                        is ParseResult.Success -> {
                            val caseLoc = patterns.firstOrNull()?.loc ?: tokenLoc(firstLetToken, file)
                            ParseResult.Success(
                                ParsedMatchCase(
                                    patterns,
                                    null,  // No additional guard expression
                                    ifLetBindings.toList(),
                                    expr.value,
                                    caseLoc
                                ),
                                expr.remaining
                            )
                        }
                        is ParseResult.Failure -> expr
                    }
                }
                is ParseResult.Failure -> arrow
            }
        }
        is ParseResult.Failure -> {
            return ParseResult.Failure(
                firstBindingResult.message,
                firstBindingResult.position,
                firstBindingResult.line,
                firstBindingResult.column
            )
        }
    }
}

    // Expression with operator precedence.
    //
    // Rewriting "Expected 'perform' ..." failures into a generic
    // "expected an expression" message:
    //   The expression alternation chain ends with `perform`, so when no
    //   alternative matches (most commonly: empty input, EOF where an
    //   expression should be, or starts-with-unexpected-keyword), the
    //   deepest failure ends up being from the perform-keyword parser.
    //   That bubbles out as "Expected 'perform' but got X" — which is
    //   incomprehensible to anyone who isn't reading the parser source.
    //   We catch that specific shape and replace it with a user-relevant
    //   message about the actual missing thing (an expression).
internal val MezzeParser.expression: Parser<ParsedExpr>
    get() = { stream ->
        // Anti-hang backstop: every expression re-entry is one unit of work, so
        // a spin or exponential re-scan trips the step/time budget (P0132) here.
        parseTick(stream)
        // Depth guard: each nested `(expr)` / `[..]` / `{ f = expr }` re-enters
        // here, so deep nesting recurses the whole precedence chain and can blow
        // the stack. Fail cleanly past the limit instead of crashing (fuzz-found).
        if (nestDepth >= MezzeParser.MAX_NEST_DEPTH) {
            stream.failure(
                "Expression is nested too deeply (over ${MezzeParser.MAX_NEST_DEPTH} levels) — " +
                    "usually runaway or unbalanced brackets.",
                code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0131_NESTING_TOO_DEEP,
                hints = listOf("check for unbalanced `(`/`[`/`{`, or split into named `let` bindings")
            )
        } else {
            nestDepth++
            try {
                val r = logicalOrExpression(stream)
                if (r is ParseResult.Failure && r.message.startsWith("Expected 'perform'")) {
                    val got = tokenGotDescription(stream.current())
                    ParseResult.Failure(
                        "Expected an expression, but got $got",
                        r.position, r.line, r.column, r.isFatal,
                    )
                } else r
            } finally {
                nestDepth--
            }
        }
    }

    // Logical OR operator: ||
internal val MezzeParser.logicalOrExpression: Parser<ParsedExpr>
    get() = { stream ->
        var result: ParseResult<ParsedExpr> = logicalAndExpression(stream)

        // Stream-advancement guard: see postfixExpression for rationale.
        var lastOrPos = -1
        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val pos = currentStream.position
            if (pos == lastOrPos) {
                break
            }
            lastOrPos = pos
            val op = currentStream.current()

            if (op?.type == TokenType.OR) {
                // like `a ||\n  b` parse without breaking. Mirrors pipe and
                // dot-chain handling at the same precedence levels.
                val opStream = skipNewlines(currentStream.advance())
                val right = logicalAndExpression(opStream)
                result = when (right) {
                    is ParseResult.Success -> {
                        val loc = result.value.loc.spanTo(right.value.loc)
                        ParseResult.Success(
                            desugarBinaryOperatorParsed(
                                op.text,
                                result.value,
                                right.value,
                                loc
                            ),
                            right.remaining
                        )
                    }
                    is ParseResult.Failure -> right // RHS failed: propagate its terminating error instead of keeping the left and re-spinning on the unconsumed operator (fuzz: `a ||`/`a ==` etc. hung)
                }
            } else {
                break
            }
        }
        result
    }

    // Logical AND operator: &&
internal val MezzeParser.logicalAndExpression: Parser<ParsedExpr>
    get() = { stream ->
        var result: ParseResult<ParsedExpr> = pipeExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val op = currentStream.current()

            if (op?.type == TokenType.AND) {
                val opStream = skipNewlines(currentStream.advance())
                val right = pipeExpression(opStream)
                result = when (right) {
                    is ParseResult.Success -> {
                        val loc = result.value.loc.spanTo(right.value.loc)
                        ParseResult.Success(
                            desugarBinaryOperatorParsed(
                                op.text,
                                result.value,
                                right.value,
                                loc
                            ),
                            right.remaining
                        )
                    }
                    is ParseResult.Failure -> right // RHS failed: propagate its terminating error instead of keeping the left and re-spinning on the unconsumed operator (fuzz: `a ||`/`a ==` etc. hung)
                }
            } else {
                break
            }
        }
        result
    }

    // Count PipePlaceholder occurrences in an expression
internal fun countPipePlaceholders(expr: ParsedExpr): Int {
    return when (expr) {
        is ParsedPipePlaceholder -> 1
        is ParsedFunctionCall -> countPipePlaceholders(expr.function) + countPipePlaceholders(expr.arguments)
        is ParsedRecordLit -> expr.entries.sumOf { entry ->
            when (entry) {
                is ParsedRecordEntry.Field -> entry.value?.let { countPipePlaceholders(it) } ?: 0
                is ParsedRecordEntry.Spread -> countPipePlaceholders(entry.expr)
            }
        }
        is ParsedIf -> countPipePlaceholders(expr.condition) +
            countPipePlaceholders(expr.thenBranch) + countPipePlaceholders(expr.elseBranch)
        is ParsedLambda -> countPipePlaceholders(expr.body)
        is ParsedMatch -> countPipePlaceholders(expr.scrutinee) +
            expr.cases.sumOf { c ->
                countPipePlaceholders(c.body) + (c.guard?.let { countPipePlaceholders(it) } ?: 0)
            }
        is ParsedDo -> expr.statements.sumOf { stmt ->
            when (stmt) {
                is ParsedStatement.Let -> countPipePlaceholders(stmt.value)
                is ParsedStatement.Expr -> countPipePlaceholders(stmt.expr)
            }
        } + countPipePlaceholders(expr.result)
        is ParsedSeqLit -> expr.elements.sumOf { element ->
            when (element) {
                is ParsedSeqElement.Regular -> countPipePlaceholders(element.expr)
                is ParsedSeqElement.Spread -> countPipePlaceholders(element.expr)
            }
        }
        is ParsedPerform -> countPipePlaceholders(expr.body)
        is ParsedMemberAccess -> countPipePlaceholders(expr.receiver)
        is ParsedVariantConstruct -> expr.value?.let { countPipePlaceholders(it) } ?: 0
        is ParsedNewtypeConstruct -> countPipePlaceholders(expr.value)
        is ParsedParens -> countPipePlaceholders(expr.expr)
        is ParsedPipe -> countPipePlaceholders(expr.left) + countPipePlaceholders(expr.right)
        is ParsedBinaryOp -> countPipePlaceholders(expr.left) + countPipePlaceholders(expr.right)
        is ParsedEffectCall -> countPipePlaceholders(expr.arguments)
        is ParsedStringLit -> expr.parts.sumOf { part ->
            if (part is ParsedStringPart.Interpolation) countPipePlaceholders(part.expr) else 0
        }
        // Leaves carry no subexpression that could hold `@`. Listed explicitly
        // (no `else`) so the `when` is exhaustive: adding a ParsedExpr variant
        // that CAN contain `@` becomes a compile error instead of silently
        // returning 0 and mis-reporting "missing placeholder".
        is ParsedIntLit, is ParsedNatLit, is ParsedFloatLit, is ParsedFracLit,
        is ParsedCharLit, is ParsedIdentifier -> 0
    }
}

    // Find all PipePlaceholder locations in an expression
internal fun findPipePlaceholderLocations(expr: ParsedExpr): List<Loc> {
    return when (expr) {
        is ParsedPipePlaceholder -> listOf(expr.loc)
        is ParsedFunctionCall -> findPipePlaceholderLocations(expr.function) + findPipePlaceholderLocations(expr.arguments)
        is ParsedRecordLit -> expr.entries.flatMap { entry ->
            when (entry) {
                is ParsedRecordEntry.Field -> entry.value?.let { findPipePlaceholderLocations(it) } ?: emptyList()
                is ParsedRecordEntry.Spread -> findPipePlaceholderLocations(entry.expr)
            }
        }
        is ParsedIf -> findPipePlaceholderLocations(expr.condition) +
            findPipePlaceholderLocations(expr.thenBranch) + findPipePlaceholderLocations(expr.elseBranch)
        is ParsedLambda -> findPipePlaceholderLocations(expr.body)
        is ParsedMatch -> findPipePlaceholderLocations(expr.scrutinee) +
            expr.cases.flatMap { c ->
                findPipePlaceholderLocations(c.body) + (c.guard?.let { findPipePlaceholderLocations(it) } ?: emptyList())
            }
        is ParsedDo -> expr.statements.flatMap { stmt ->
            when (stmt) {
                is ParsedStatement.Let -> findPipePlaceholderLocations(stmt.value)
                is ParsedStatement.Expr -> findPipePlaceholderLocations(stmt.expr)
            }
        } + findPipePlaceholderLocations(expr.result)
        is ParsedSeqLit -> expr.elements.flatMap { element ->
            when (element) {
                is ParsedSeqElement.Regular -> findPipePlaceholderLocations(element.expr)
                is ParsedSeqElement.Spread -> findPipePlaceholderLocations(element.expr)
            }
        }
        is ParsedPerform -> findPipePlaceholderLocations(expr.body)
        is ParsedMemberAccess -> findPipePlaceholderLocations(expr.receiver)
        is ParsedVariantConstruct -> expr.value?.let { findPipePlaceholderLocations(it) } ?: emptyList()
        is ParsedNewtypeConstruct -> findPipePlaceholderLocations(expr.value)
        is ParsedParens -> findPipePlaceholderLocations(expr.expr)
        is ParsedPipe -> findPipePlaceholderLocations(expr.left) + findPipePlaceholderLocations(expr.right)
        is ParsedBinaryOp -> findPipePlaceholderLocations(expr.left) + findPipePlaceholderLocations(expr.right)
        is ParsedEffectCall -> findPipePlaceholderLocations(expr.arguments)
        is ParsedStringLit -> expr.parts.flatMap { part ->
            if (part is ParsedStringPart.Interpolation) findPipePlaceholderLocations(part.expr) else emptyList()
        }
        // Leaves — exhaustive, no `else` (see countPipePlaceholders).
        is ParsedIntLit, is ParsedNatLit, is ParsedFloatLit, is ParsedFracLit,
        is ParsedCharLit, is ParsedIdentifier -> emptyList()
    }
}

// Returns the Loc of a pipe placeholder `@` that sits directly in a member-access
// receiver position (`@.method`, `@.field`), or null if none. `@` may be an
// argument value (`f { x = @ }`, `f { x = @ + 1 }`) but not a method/field
// receiver — `x |> @.m {}` should be written `x.m {}` or `x |> f { arg = @ }`.
internal fun pipePlaceholderInReceiverPosition(expr: ParsedExpr): Loc? = when (expr) {
    is ParsedMemberAccess ->
        if (expr.receiver is ParsedPipePlaceholder) expr.receiver.loc
        else pipePlaceholderInReceiverPosition(expr.receiver)
    is ParsedFunctionCall ->
        pipePlaceholderInReceiverPosition(expr.function) ?: pipePlaceholderInReceiverPosition(expr.arguments)
    is ParsedRecordLit -> expr.entries.firstNotNullOfOrNull { entry ->
        when (entry) {
            is ParsedRecordEntry.Field -> entry.value?.let { pipePlaceholderInReceiverPosition(it) }
            is ParsedRecordEntry.Spread -> pipePlaceholderInReceiverPosition(entry.expr)
        }
    }
    is ParsedIf -> pipePlaceholderInReceiverPosition(expr.condition)
        ?: pipePlaceholderInReceiverPosition(expr.thenBranch)
        ?: pipePlaceholderInReceiverPosition(expr.elseBranch)
    is ParsedLambda -> pipePlaceholderInReceiverPosition(expr.body)
    is ParsedMatch -> pipePlaceholderInReceiverPosition(expr.scrutinee)
        ?: expr.cases.firstNotNullOfOrNull { c ->
            pipePlaceholderInReceiverPosition(c.body) ?: c.guard?.let { pipePlaceholderInReceiverPosition(it) }
        }
    is ParsedDo -> expr.statements.firstNotNullOfOrNull { stmt ->
        when (stmt) {
            is ParsedStatement.Let -> pipePlaceholderInReceiverPosition(stmt.value)
            is ParsedStatement.Expr -> pipePlaceholderInReceiverPosition(stmt.expr)
        }
    } ?: pipePlaceholderInReceiverPosition(expr.result)
    is ParsedSeqLit -> expr.elements.firstNotNullOfOrNull { element ->
        when (element) {
            is ParsedSeqElement.Regular -> pipePlaceholderInReceiverPosition(element.expr)
            is ParsedSeqElement.Spread -> pipePlaceholderInReceiverPosition(element.expr)
        }
    }
    is ParsedPerform -> pipePlaceholderInReceiverPosition(expr.body)
    is ParsedVariantConstruct -> expr.value?.let { pipePlaceholderInReceiverPosition(it) }
    is ParsedNewtypeConstruct -> pipePlaceholderInReceiverPosition(expr.value)
    is ParsedParens -> pipePlaceholderInReceiverPosition(expr.expr)
    is ParsedPipe -> pipePlaceholderInReceiverPosition(expr.left) ?: pipePlaceholderInReceiverPosition(expr.right)
    is ParsedBinaryOp -> pipePlaceholderInReceiverPosition(expr.left) ?: pipePlaceholderInReceiverPosition(expr.right)
    is ParsedEffectCall -> pipePlaceholderInReceiverPosition(expr.arguments)
    is ParsedStringLit -> expr.parts.firstNotNullOfOrNull { part ->
        if (part is ParsedStringPart.Interpolation) pipePlaceholderInReceiverPosition(part.expr) else null
    }
    // Leaves (including a bare `@`, which is a valid argument value, not a
    // receiver) — exhaustive, no `else`.
    is ParsedPipePlaceholder, is ParsedIntLit, is ParsedNatLit, is ParsedFloatLit,
    is ParsedFracLit, is ParsedCharLit, is ParsedIdentifier -> null
}

    // Pipe operator: |>
    // Low precedence, left-associative operator for function composition
    // Syntax: value |> function {param = @} where @ refers to the piped value
    // Creates ParsedPipe AST node (desugared later in typechecker)
internal val MezzeParser.pipeExpression: Parser<ParsedExpr>
    get() = { stream ->
        var result: ParseResult<ParsedExpr> = comparisonExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            // Multi-line pipe support: mirror dot-chain handling at
            // postfixExpression. After parsing the LHS, peek past
            // any newlines — if the next significant token is `|>`,
            // we keep going. Without this, a pipe like
            //
            //   [1, 2, 3]
            //     |> filter { p = @ }
            //     |> map { f = @ }
            //
            // would terminate the expression at the newline. The
            // continuation rule matches what dot chains already do
            // (see TokenType.NEWLINE branch in postfixExpression).
            val (probe, opAfterNewlines) = run {
                // Peek across the line break to the next stage. A stage may carry
                // an INLINE trailing comment — `|> f { x = @ }   # note` — so the
                // token after it is COMMENT, then NEWLINE, then `|>`. Skip both,
                // exactly as skipNewlines does; checking NEWLINE alone stopped the
                // chain at the comment, orphaning every stage after the first
                // commented one (and the enclosing do-block then mis-reported its
                // last `let` as the result). If no `|>` follows, nothing is
                // consumed — we return the original stream untouched, so a comment
                // that really belongs to the next statement is left for it.
                val cur = currentStream.current()?.type
                if (cur == TokenType.NEWLINE || cur == TokenType.COMMENT) {
                    var ahead = currentStream
                    while (ahead.current()?.type == TokenType.NEWLINE || ahead.current()?.type == TokenType.COMMENT) {
                        ahead = ahead.advance()
                    }
                    val a = ahead.current()
                    if (a?.type == TokenType.PIPE_OP) ahead to a else currentStream to currentStream.current()
                } else currentStream to currentStream.current()
            }

            // Any comments the peek stepped over sit between this stage and the
            // next `|>` — they trail the stage we just built (`result`). Collect
            // them so they can ride on that ParsedPipe rather than being dropped
            // (the peek only advances to find the operator; the tokens it skips
            // are otherwise lost). Harmless when the peek found no `|>`: probe ==
            // currentStream, so the range is empty.
            val op = opAfterNewlines
            val stageComments: List<ParsedComment> =
                if (op?.type == TokenType.PIPE_OP) collectCommentsBetween(currentStream, probe) else emptyList()

            if (op?.type == TokenType.PIPE_OP) {
                val opStream = probe.advance()
                // Allow newlines after `|>` too — same UX as a `.method`
                // chain where the receiver line ends and the next line
                // starts with the operator continuation.
                val rhsStream = skipNewlines(opStream)
                val right = comparisonExpression(rhsStream)
                result = when (right) {
                    is ParseResult.Success -> {
                        // Validate: exactly one @ in the right-hand side
                        val placeholderCount = countPipePlaceholders(right.value)

                        when {
                            placeholderCount == 0 -> {
                                val rightLoc = right.value.loc
                                ParseResult.Failure(
                                    "Pipe expression is missing '@' placeholder on the right-hand side",
                                    right.remaining.getSourcePosition(),
                                    rightLoc.startLine,
                                    rightLoc.startCol,
                                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0115_PIPE_MISSING_PLACEHOLDER,
                                    hints = listOf("add `@` where the piped value should go: `x |> f { arg = @ }`")
                                )
                            }
                            placeholderCount > 1 -> {
                                val locations = findPipePlaceholderLocations(right.value)
                                ParseResult.Failure(
                                    "Pipe expression has $placeholderCount '@' placeholders (exactly one allowed)",
                                    right.remaining.getSourcePosition(),
                                    locations.firstOrNull()?.startLine ?: op.line,
                                    locations.firstOrNull()?.startCol ?: op.column,
                                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0116_PIPE_MULTIPLE_PLACEHOLDERS,
                                    hints = listOf("each pipe should have exactly one `@`; bind the value first if you need it in multiple places")
                                )
                            }
                            pipePlaceholderInReceiverPosition(right.value) != null -> {
                                // `@` may be an argument value, not a method/field
                                // receiver: `x |> @.m {}` is rejected.
                                val recvLoc = pipePlaceholderInReceiverPosition(right.value)!!
                                ParseResult.Failure(
                                    "'@' cannot be used as a method or field receiver in a pipe",
                                    right.remaining.getSourcePosition(),
                                    recvLoc.startLine,
                                    recvLoc.startCol,
                                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0124_PIPE_PLACEHOLDER_AS_RECEIVER,
                                    hints = listOf(
                                        "call the method directly: `x.method {}`",
                                        "or pipe into a function that takes the value: `x |> f { arg = @ }`"
                                    )
                                )
                            }
                            else -> {
                                // Valid: exactly one @, in an argument position.
                                // The comments skipped before THIS `|>` trail the
                                // PREVIOUS stage (`result`) — attach them there so
                                // they print on that stage's line, not the new one.
                                val left = if (stageComments.isNotEmpty() && result.value is ParsedPipe)
                                    (result.value as ParsedPipe).copy(
                                        trailingComments = (result.value as ParsedPipe).trailingComments + stageComments
                                    )
                                else result.value
                                ParseResult.Success(
                                    ParsedPipe(
                                        left = left,
                                        right = right.value,
                                        loc = left.loc.spanTo(right.value.loc)
                                    ),
                                    right.remaining
                                )
                            }
                        }
                    }
                    is ParseResult.Failure ->
                        // The RHS after `|>` didn't parse. The old code returned
                        // `result` (the left) here, leaving `|>` UNCONSUMED — so the
                        // `while (result is Success)` loop saw PIPE_OP again and spun
                        // forever (fuzz: `a |>` hung the parser). Fail and stop. If the
                        // RHS made progress (advanced past the op) or committed, its
                        // error is more specific — keep it; else give the targeted
                        // "pipe needs a right-hand side" message.
                        if (right.isFatal || right.position > rhsStream.getSourcePosition()) right
                        else ParseResult.Failure(
                            "The `|>` pipe needs an expression on its right, " +
                                "e.g. `x |> f { arg = @ }`",
                            rhsStream.getSourcePosition(),
                            op.line,
                            op.column,
                            code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0130_PIPE_MISSING_RHS,
                            hints = listOf("pipe into a function call with `@` for the value: `|> f { arg = @ }`")
                        )
                }
            } else {
                break
            }
        }
        result
    }

    // Comparison operators: ==, !=, <, >, <=, >=
internal val MezzeParser.comparisonExpression: Parser<ParsedExpr>
    get() = { stream ->
        var result: ParseResult<ParsedExpr> = additiveExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val op = currentStream.current()

            if (op?.type == TokenType.EQ || op?.type == TokenType.NEQ ||
                op?.type == TokenType.LT || op?.type == TokenType.GT ||
                op?.type == TokenType.LTE || op?.type == TokenType.GTE) {
                val opStream = skipNewlines(currentStream.advance())
                val right = additiveExpression(opStream)
                result = when (right) {
                    is ParseResult.Success -> {
                        val loc = result.value.loc.spanTo(right.value.loc)
                        ParseResult.Success(
                            desugarBinaryOperatorParsed(
                                op.text,
                                result.value,
                                right.value,
                                loc
                            ),
                            right.remaining
                        )
                    }
                    is ParseResult.Failure -> right // RHS failed: propagate its terminating error instead of keeping the left and re-spinning on the unconsumed operator (fuzz: `a ||`/`a ==` etc. hung)
                }
            } else {
                break
            }
        }
        result
    }

    // Additive operators: +, -
internal val MezzeParser.additiveExpression: Parser<ParsedExpr>
    get() = { stream ->
        var result: ParseResult<ParsedExpr> = multiplicativeExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val op = currentStream.current()

            if (op?.type == TokenType.PLUS || op?.type == TokenType.MINUS) {
                val opStream = currentStream.advance()
                val nextToken = opStream.current()

                // `--`/`++` written adjacently (no gap: `x -- y`, `x ++ y`) is a
                // typo — reject it. But `x - -5` / `x + -5` with a SPACE is a
                // legit "subtract/add a negative operand": the second `-`/`+` is
                // the sign of the operand, folded by unaryExpression. Distinguish
                // by source adjacency, since the lexer discards the whitespace and
                // both forms produce the same MINUS MINUS token stream.
                val adjacent = nextToken != null &&
                    nextToken.line == op.line &&
                    nextToken.column == op.column + op.text.length

                if (nextToken?.type == op.type && adjacent) {
                    val doubleOp = op.text + nextToken.text
                    result = ParseResult.Failure(
                        "'$doubleOp' is not a valid operator in Mezze. " +
                        if (doubleOp == "++") "For string concatenation, use string interpolation: \"\$(s1)\$(s2)\""
                        else "Use '${op.text}' with two operands instead",
                        opStream.position,
                        nextToken.line,
                        nextToken.column,
                        // We've already consumed a valid LHS; this error
                        // describes the actual problem. Don't let oneOf
                        // backtrack and bury it under a shallower miss.
                        isFatal = true
                    )
                    break
                }

                val rhsStream = skipNewlines(opStream)
                val right = multiplicativeExpression(rhsStream)
                when (right) {
                    is ParseResult.Success -> {
                        val loc = result.value.loc.spanTo(right.value.loc)
                        result = ParseResult.Success(
                            desugarBinaryOperatorParsed(
                                op.text,
                                result.value,
                                right.value,
                                loc
                            ),
                            right.remaining
                        )
                    }
                    is ParseResult.Failure -> {
                        // Right side failed - give a better error message
                        result = ParseResult.Failure(
                            "Expected expression after '${op.text}' operator",
                            opStream.position,
                            op.line,
                            op.column
                        )
                        break
                    }
                }
            } else {
                break
            }
        }
        result
    }

    // Multiplicative operators: *, /, %
internal val MezzeParser.multiplicativeExpression: Parser<ParsedExpr>
    get() = { stream ->
        var result: ParseResult<ParsedExpr> = unaryExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val op = currentStream.current()

            if (op?.type == TokenType.STAR || op?.type == TokenType.SLASH || op?.type == TokenType.PERCENT) {
                val opStream = currentStream.advance()
                val nextToken = opStream.current()

                // Note: // is valid for fractions (e.g., 1//2), but that's handled by the lexer
                if (nextToken?.type == op.type) {
                    val doubleOp = op.text + nextToken.text
                    result = ParseResult.Failure(
                        "'$doubleOp' is not a valid operator in Mezze" +
                        if (doubleOp == "**") ". For exponentiation, use a function like math::pow"
                        else "",
                        opStream.position,
                        nextToken.line,
                        nextToken.column
                    )
                    break
                }

                val rhsStream = skipNewlines(opStream)
                val right = unaryExpression(rhsStream)
                when (right) {
                    is ParseResult.Success -> {
                        val loc = result.value.loc.spanTo(right.value.loc)
                        result = ParseResult.Success(
                            desugarBinaryOperatorParsed(
                                op.text,
                                result.value,
                                right.value,
                                loc
                            ),
                            right.remaining
                        )
                    }
                    is ParseResult.Failure -> {
                        // Right side failed - give a better error message
                        result = ParseResult.Failure(
                            "Expected expression after '${op.text}' operator",
                            opStream.position,
                            op.line,
                            op.column
                        )
                        break
                    }
                }
            } else {
                break
            }
        }
        result
    }

    // Unary operators: -, !
internal val MezzeParser.unaryExpression: Parser<ParsedExpr>
    get() = { stream ->
        val token = stream.current()
        val file = currentFile ?: "<unknown>"

        if (token?.type == TokenType.MINUS) {
            val opLoc = tokenLoc(token, file)
            val streamAfterMinus = skipNewlines(stream.advance())
            val literalToken = streamAfterMinus.current()

            // `-` is not a prefix operator: `-5` folds into a negative literal;
            // `-x` on a non-literal must use `x.neg {}`.
            // Fold `-N` into a signed literal, then run the postfix continuation
            // so members attach: `-5.abs {}`, `-5.to_float {}`, etc. (B9).
            when (literalToken?.type) {
                TokenType.INTEGER -> continuePostfix(ParseResult.Success(
                    ParsedIntLit(-literalToken.text.toLong(), opLoc),
                    streamAfterMinus.advance()
                ))
                TokenType.FLOAT -> continuePostfix(ParseResult.Success(
                    ParsedFloatLit(-literalToken.text.toDouble(), opLoc),
                    streamAfterMinus.advance()
                ))
                TokenType.FRACTION -> {
                    val parts = literalToken.text.split("//")
                    if (parts.size == 2) {
                        continuePostfix(ParseResult.Success(
                            ParsedFracLit(-parts[0].toLong(), parts[1].toLong(), opLoc),
                            streamAfterMinus.advance()
                        ))
                    } else {
                        streamAfterMinus.failure("Invalid fraction literal '${literalToken.text}'. Expected format: numerator//denominator (e.g., 3//4)")
                    }
                }
                else -> streamAfterMinus.failure(
                    "Unary `-` on a non-literal is not supported — use `x.neg {}` to negate an expression."
                )
            }
        } else {
            postfixExpression(stream)
        }
    }

    // Handles: f {x = 1}, obj.field, f {x = 1}.field, etc.
internal val MezzeParser.postfixExpression: Parser<ParsedExpr>
    get() = { stream -> continuePostfix(primaryExpression(stream)) }

    // Runs the postfix-continuation loop (`.field`, `.method {}`, call-args,
    // etc.) starting from an ALREADY-parsed seed expression. Extracted so a
    // signed numeric literal folded in unaryExpression (`-5`) can still grow
    // postfix members like `-5.abs {}` / `(-5).neg {}` instead of returning bare.
    internal fun MezzeParser.continuePostfix(seed: ParseResult<ParsedExpr>): ParseResult<ParsedExpr> {
        var result: ParseResult<ParsedExpr> = seed

        // Stream-advancement guard: every iteration must move the stream
        // forward. Without this guard, certain expression shapes (notably
        // `f { ... } || g { ... }` inside a record-field lambda body) used
        // to non-terminate in subtle ways via the recursive descent. The
        // primary fix is the `skipNewlines` added after each binary operator
        // (see logicalOrExpression et al.); this guard is a cheap safety
        // net so future shapes can't accidentally regress to a hang.
        var lastPosition = -1
        while (result is ParseResult.Success) {
            val currentStream = result.remaining
            val pos = currentStream.position
            if (pos == lastPosition) {
                break
            }
            lastPosition = pos
            val token = currentStream.current()

            when (token?.type) {
                TokenType.LBRACE -> {
                    // In a match guard the trailing `->` is the arm arrow, so a
                    // `{…}` here is always call arguments, never a lambda start.
                    if (inMatchGuard || !lookAheadForArrowAfterBrace(currentStream)) {
                        val args = recordLiteral(currentStream)
                        result = when (args) {
                            is ParseResult.Success -> {
                                val expr = result.value
                                when {
                                    expr is ParsedIdentifier && expr.localName.firstOrNull()?.isUpperCase() == true -> {
                                        ParseResult.Success(
                                            ParsedVariantConstruct(
                                                constructor = expr.localName,
                                                value = args.value,
                                                loc = expr.loc.spanTo(args.value.loc)
                                            ),
                                            args.remaining
                                        )
                                    }
                                    expr is ParsedVariantConstruct -> {
                                        ParseResult.Success(
                                            expr.copy(value = args.value, loc = expr.loc.spanTo(args.value.loc)),
                                            args.remaining
                                        )
                                    }
                                    else -> {
                                        ParseResult.Success(
                                            ParsedFunctionCall(
                                                result.value,
                                                args.value,
                                                loc = result.value.loc.spanTo(args.value.loc)
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
                    val dotStream = currentStream.advance()
                    if (dotStream.current()?.type == TokenType.LPAREN) {
                        val receiver = result.value
                        val typeName = when {
                            receiver is ParsedIdentifier -> receiver.localName
                            receiver is ParsedVariantConstruct && receiver.value == null -> receiver.constructor
                            else -> null
                        }
                        if (typeName != null) {
                            val afterLparen = skipNewlines(dotStream.advance())
                            val innerExpr = expression(afterLparen)
                            result = when (innerExpr) {
                                is ParseResult.Success -> {
                                    val rparen = tok(TokenType.RPAREN)(skipNewlines(innerExpr.remaining))
                                    when (rparen) {
                                        is ParseResult.Success -> {
                                            val file = currentFile ?: "<unknown>"
                                            ParseResult.Success(
                                                ParsedNewtypeConstruct(
                                                    typeName = typeName,
                                                    value = innerExpr.value,
                                                    loc = receiver.loc.spanTo(tokenLoc(rparen.value, file))
                                                ),
                                                rparen.remaining
                                            )
                                        }
                                        is ParseResult.Failure -> ParseResult.Failure(
                                            "Expected ')' to close newtype construction '$typeName.(...)'",
                                            rparen.position, rparen.line, rparen.column
                                        )
                                    }
                                }
                                is ParseResult.Failure -> ParseResult.Failure(
                                    "Expected expression inside newtype construction '$typeName.(...)'",
                                    innerExpr.position, innerExpr.line, innerExpr.column
                                )
                            }
                        } else {
                            result = ParseResult.Failure(
                                "Newtype construction '.(' requires a type name on the left, but got an expression. Did you mean to call a method? Use '.method_name {}' instead.",
                                currentStream.getSourcePosition(), currentStream.getSourceLine(), currentStream.getSourceColumn()
                            )
                        }
                    } else {
                        val member = memberIdentifierParser(dotStream)
                        result = when (member) {
                            is ParseResult.Success -> {
                                val file = currentFile ?: "<unknown>"
                                val memberTokenLoc = tokenLoc(member.value, file)
                                ParseResult.Success(
                                    ParsedMemberAccess(
                                        result.value,
                                        member.value.text,
                                        loc = result.value.loc.spanTo(memberTokenLoc),
                                        memberLoc = memberTokenLoc
                                    ),
                                    member.remaining
                                )
                            }
                            is ParseResult.Failure -> break  // Not a member access
                        }
                    }
                }
                TokenType.NEWLINE -> {
                    // Look past newlines for continuation with DOT (method chaining)
                    var ahead = currentStream
                    while (ahead.current()?.type == TokenType.NEWLINE) {
                        ahead = ahead.advance()
                    }
                    if (ahead.current()?.type == TokenType.DOT) {
                        // Continue parsing: the DOT on the next line continues this expression
                        val dotStream = ahead.advance()
                        val member = memberIdentifierParser(dotStream)
                        result = when (member) {
                            is ParseResult.Success -> {
                                val file = currentFile ?: "<unknown>"
                                val memberTokenLoc = tokenLoc(member.value, file)
                                ParseResult.Success(
                                    ParsedMemberAccess(
                                        result.value,
                                        member.value.text,
                                        loc = result.value.loc.spanTo(memberTokenLoc),
                                        memberLoc = memberTokenLoc
                                    ),
                                    member.remaining
                                )
                            }
                            is ParseResult.Failure -> break
                        }
                    } else {
                        break  // Newline not followed by DOT — end of expression
                    }
                }
                TokenType.LPAREN -> {
                    // BUG FIX: Reject () syntax for function calls
                    // Mezze uses {} for function calls, not ()
                    val funcExpr = result.value
                    val funcName = when (funcExpr) {
                        is ParsedIdentifier -> funcExpr.localName
                        is ParsedMemberAccess -> {
                            val receiverName = when (val receiver = funcExpr.receiver) {
                                is ParsedIdentifier -> receiver.localName
                                is ParsedVariantConstruct -> receiver.constructor
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
                        currentFile,
                        code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0118_NAMED_ARGS_REQUIRED,
                    )
                }
                TokenType.IDENTIFIER -> {
                    // BUG #10 FIX: Check for function call without {} syntax
                    // Only check when the previous expression is a simple identifier (like `my_func x`)
                    // or a variant constructor (like `Left 42`)
                    // For complex expressions (like `@.show {}` followed by newline and identifier),
                    // skip this check as the identifier is likely a separate statement
                    val funcExpr = result.value
                    if (funcExpr is ParsedIdentifier) {
                        val funcName = funcExpr.localName
                        val argName = token.text
                        // Throw exception directly to ensure the error message is shown
                        throw createParseException(
                            "Function calls must use {} syntax. Instead of '$funcName $argName', " +
                            "use '$funcName { ... }' with named arguments.",
                            currentStream,
                            currentSource,
                            currentFile,
                            code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0118_NAMED_ARGS_REQUIRED,
                        )
                    } else if (funcExpr is ParsedVariantConstruct) {
                        // BUG FIX: Reject variant constructor with bare argument like "Left 42"
                        val constructorName = funcExpr.constructor
                        val argName = token.text
                        throw createParseException(
                            "Variant constructors must use {} syntax. Instead of '$constructorName $argName', " +
                            "use '$constructorName { <field> = $argName }' with named arguments.",
                            currentStream,
                            currentSource,
                            currentFile,
                            code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0118_NAMED_ARGS_REQUIRED,
                        )
                    }
                    // For non-identifier expressions, treat following identifier as separate statement
                    break
                }
                TokenType.STRING, TokenType.INTEGER, TokenType.FLOAT, TokenType.FRACTION, TokenType.BOOLEAN -> {
                    val funcExpr = result.value
                    if (funcExpr is ParsedIdentifier || funcExpr is ParsedMemberAccess || funcExpr is ParsedVariantConstruct) {
                        val funcName = when (funcExpr) {
                            is ParsedIdentifier -> funcExpr.localName
                            is ParsedVariantConstruct -> funcExpr.constructor
                            is ParsedMemberAccess -> {
                                val receiverName = when (val receiver = funcExpr.receiver) {
                                    is ParsedIdentifier -> receiver.localName
                                    is ParsedVariantConstruct -> receiver.constructor
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
                        val errorMessage = if (funcExpr is ParsedVariantConstruct) {
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
                            currentFile,
                            code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0118_NAMED_ARGS_REQUIRED,
                        )
                    }
                    break
                }
                else -> break  // No more postfix operators
            }
        }

        return result
    }



    // @ placeholder for pipe operator - creates ParsedPipePlaceholder AST node
internal val MezzeParser.atPlaceholderExpression: Parser<ParsedExpr>
    get() = { stream ->
        val atToken = stream.current()
        val file = currentFile ?: "<unknown>"
        val token = tok(TokenType.AT)(stream)
        when (token) {
            is ParseResult.Success -> {
                val loc = atToken?.let { tokenLoc(it, file) } ?: tokenLoc(token.value, file)
                ParseResult.Success(
                    ParsedPipePlaceholder(loc = loc),
                    token.remaining
                )
            }
            is ParseResult.Failure -> token
        }
    }

internal val MezzeParser.identifierExpression: Parser<ParsedExpr>
    get() = { stream ->
        val first = identifierParser(stream)
        when (first) {
            is ParseResult.Success -> {
                val file = currentFile ?: "<unknown>"
                val startToken = first.value
                val segments = mutableListOf(first.value.text)
                var currentStream = first.remaining
                var failed: ParseResult.Failure? = null

                while (failed == null) {
                    val doubleColon = tok(TokenType.DOUBLE_COLON)(currentStream)
                    if (doubleColon is ParseResult.Success) {
                        val next = identifierParser(doubleColon.remaining)
                        when (next) {
                            is ParseResult.Success -> {
                                segments.add(next.value.text)
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
                    val localName = segments.last()
                    val isQualified = segments.size > 1

                    // Only for simple names (not qualified names with ::)
                    if (!isQualified && localName.firstOrNull()?.isUpperCase() == true) {
                        val loc = tokenLoc(startToken, file)
                        ParseResult.Success(
                            ParsedVariantConstruct(constructor = localName, value = null, loc = loc),
                            currentStream
                        )
                    } else {
                        val qualifier = if (isQualified) {
                            ParsedModulePath(segments.dropLast(1))
                        } else {
                            null
                        }
                        val lastToken = if (segments.size > 1) {
                            // The last identifier token in the stream (already consumed)
                            stream.tokens.getOrNull(currentStream.position - 1) ?: startToken
                        } else {
                            startToken
                        }
                        val loc = if (isQualified) spanLoc(startToken, lastToken, file) else tokenLoc(startToken, file)
                        ParseResult.Success(
                            ParsedIdentifier(localName = localName, qualifier = qualifier, loc = loc),
                            currentStream
                        )
                    }
                }
            }
            is ParseResult.Failure -> first
        }
    }

internal val MezzeParser.literalExpression: Parser<ParsedExpr>
    get() = { stream ->
        val token = stream.current()
        val file = currentFile ?: "<unknown>"
        when (token?.type) {
            TokenType.INTEGER -> {
                val loc = tokenLoc(token, file)
                val value = token.text.toLongOrNull()
                if (value == null) {
                    // Fatal: the token IS an integer, it's just out of range — no
                    // other alternative would parse it, so short-circuit the
                    // alternation with this targeted message instead of letting a
                    // shallower "Expected an expression" win.
                    ParseResult.Failure(
                        "Integer literal '${token.text}' is out of range for a 64-bit integer (max 9223372036854775807).",
                        stream.getSourcePosition(), stream.getSourceLine(), stream.getSourceColumn(),
                        isFatal = true,
                        code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0125_INTEGER_LITERAL_OUT_OF_RANGE,
                    )
                } else {
                    ParseResult.Success(ParsedIntLit(value, loc), stream.advance())
                }
            }
            TokenType.NAT -> {
                val loc = tokenLoc(token, file)
                val value = token.text.toLongOrNull()
                if (value == null) {
                    ParseResult.Failure(
                        "Natural-number literal '${token.text}n' is out of range for a 64-bit integer (max 9223372036854775807).",
                        stream.getSourcePosition(), stream.getSourceLine(), stream.getSourceColumn(),
                        isFatal = true,
                        code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0125_INTEGER_LITERAL_OUT_OF_RANGE,
                    )
                } else {
                    ParseResult.Success(ParsedNatLit(value, loc), stream.advance())
                }
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

internal val MezzeParser.parenExpression: Parser<ParsedExpr>
    get() = { stream ->
        val current = stream.current()
        val next = stream.peek(1)
        if (current?.type == TokenType.LPAREN && next?.type == TokenType.RPAREN) {
            // User wrote () - this is a common mistake from other languages
            val afterParen = stream.advance(2)
            val afterArrow = afterParen.current()
            if (afterArrow?.type == TokenType.ARROW) {
                // User wrote () -> ... which looks like a lambda
                stream.failure(
                    "Invalid lambda syntax: '()' is not valid. Use '{}' for a no-argument lambda.\n" +
                    "   Example: {} -> expression\n" +
                    "   Or with arguments: {x, y} -> expression"
                )
            } else {
                stream.failure(
                    "Empty parentheses '()' are not valid. Did you mean:\n" +
                    "   • '{}' for an empty record\n" +
                    "   • '{} -> expr' for a no-argument lambda"
                )
            }
        } else {
            when (val lparen = tok(TokenType.LPAREN)(stream)) {
                is ParseResult.Failure -> lparen
                is ParseResult.Success -> {
                    val file = currentFile ?: "<unknown>"
                    when (val innerResult = expression(lparen.remaining)) {
                        is ParseResult.Failure -> innerResult
                        is ParseResult.Success -> {
                            when (val rparen = tok(TokenType.RPAREN)(innerResult.remaining)) {
                                is ParseResult.Failure -> rparen
                                is ParseResult.Success -> {
                                    val loc = spanLoc(lparen.value, rparen.value, file)
                                    ParseResult.Success(
                                        ParsedParens(
                                            expr = innerResult.value,
                                            loc = loc
                                        ),
                                        rparen.remaining
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }


internal fun MezzeParser.desugarBinaryOperatorParsed(
    operator: String,
    left: ParsedExpr,
    right: ParsedExpr,
    loc: Loc
): ParsedExpr {
    return ParsedBinaryOp(OperatorMapping.parse(operator), left, right, loc)
}

internal fun MezzeParser.parseParsedInterpolatedString(
    content: String,
    remaining: TokenStream,
    loc: Loc
): ParseResult<ParsedExpr> {
    val parts = mutableListOf<ParsedStringPart>()
    var i = 0
    val sb = StringBuilder()

    // Track the source coordinates of content[i] as we walk. `loc` points at
    // the opening `"` of the string literal in the file; content[0] is one
    // character after the opening quote, so contentLine/Col start at
    // (loc.startLine, loc.startCol + 1). When we cross a literal newline in
    // `content` we increment the line and reset the column. This lets us
    // hand interpolated sub-expressions the right (line, column) so their
    // tokens (and downstream errors) report file-coordinates instead of
    // `1:1` relative to the slice. Fixes the "wrong location" half of #206.
    var contentLine = loc.startLine
    var contentCol = loc.startCol + 1

    while (i < content.length) {
        if (i + 1 < content.length && content[i] == '$' && content[i + 1] == '(') {
            if (sb.isNotEmpty()) {
                parts.add(ParsedStringPart.Literal(processEscapeSequences(sb.toString())))
                sb.clear()
            }
            var depth = 1
            var j = i + 2
            while (j < content.length && depth > 0) {
                if (content[j] == '(') depth++
                if (content[j] == ')') depth--
                j++
            }
            val exprText = content.substring(i + 2, j - 1)
            // letting the empty token stream hit some random "expected X"
            // deep in the expression parser (which surfaces confusing
            // messages like "Expected 'perform' but got end of file").
            if (exprText.isBlank()) {
                return remaining.failure(
                    "Empty interpolation \$(): nothing between the parens. " +
                    "Did you mean to put an expression there, or write \\\$() to escape the dollar sign?",
                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0110_EMPTY_INTERPOLATION
                )
            }
            // The expression text starts at content[i + 2] (after `$(`), so
            // its source-position is (contentLine, contentCol + 2).
            val exprStartLine = contentLine
            val exprStartCol = contentCol + 2
            // Parse the expression inside $(...). Narrowly catch only the
            // exceptions the inner Lexer/parser can actually raise; broader
            // catches mask real bugs (NPE etc.) as parser errors (#209).
            val exprResult: ParseResult<ParsedExpr> = try {
                val lexer = Lexer(exprText, startLine = exprStartLine, startColumn = exprStartCol)
                val tokens = lexer.tokenize()
                val exprStream = TokenStream(tokens)
                expression(exprStream)
            } catch (e: com.mezze.language.parser.internal.LexerException) {
                // Lexer error inside the slice — surface the real reason and
                // point at the start of the interpolation in the file.
                ParseResult.Failure(
                    message = "in string interpolation: ${e.message ?: "lexer error"}",
                    position = remaining.getSourcePosition(),
                    line = exprStartLine,
                    column = exprStartCol,
                )
            }
            when (exprResult) {
                is ParseResult.Success -> {
                    parts.add(ParsedStringPart.Interpolation(exprResult.value))
                }
                is ParseResult.Failure -> {
                    // Forward the nested failure verbatim — its line/column
                    // already carry file coordinates (we passed startLine /
                    // startColumn to the inner Lexer), and its code/hints
                    // describe the real problem. Just annotate that it
                    // happened inside an interpolation. Fixes #209.
                    return ParseResult.Failure(
                        message = "in string interpolation: ${exprResult.message}",
                        position = exprResult.position,
                        line = exprResult.line,
                        column = exprResult.column,
                        isFatal = exprResult.isFatal,
                        code = exprResult.code,
                        hints = exprResult.hints,
                    )
                }
            }
            for (k in i until j) {
                if (content[k] == '\n') { contentLine++; contentCol = 1 } else contentCol++
            }
            i = j
        } else if (content[i] == '\\' && i + 1 < content.length && content[i + 1] == '$') {
            sb.append('$')
            contentCol += 2
            i += 2
        } else {
            val ch = content[i]
            sb.append(ch)
            if (ch == '\n') { contentLine++; contentCol = 1 } else contentCol++
            i++
        }
    }
    if (sb.isNotEmpty()) {
        parts.add(ParsedStringPart.Literal(processEscapeSequences(sb.toString())))
    }

    return ParseResult.Success(ParsedStringLit(parts, loc), remaining)
}

// Helper function for keyword parsing
internal fun MezzeParser.keyword(word: String, type: TokenType): Parser<Token> =
    Parsers.token({ it.type == type && it.text == word }, word)
