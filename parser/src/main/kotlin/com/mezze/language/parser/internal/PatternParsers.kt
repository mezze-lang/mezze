package com.mezze.language.parser.internal

import com.mezze.language.parsedast.*
import com.mezze.language.parser.*

import com.mezze.language.parser.internal.lib.*
// Let binding pattern parser (for irrefutable patterns in let bindings)
internal val MezzeParser.letBindingPattern: Parser<ParsedPattern>
    get() = { stream ->
        when (val r = Parsers.oneOf(
            wildcardPattern,
            standaloneRecordPattern,
            variablePatternForLet
        )(stream)) {
            is ParseResult.Success -> r
            is ParseResult.Failure ->
                // `oneOf` surfaces ONE alternative's low-level failure here —
                // usually the wildcard's "Expected '_'", which misdiagnoses
                // `let 3 = x` (the real problem isn't a missing `_`). Replace it
                // with what a let can actually bind + what was there — but ONLY
                // when no alternative made progress past the first token. A
                // deeper failure (r.position advanced) means an alternative
                // committed and produced a SPECIFIC error — e.g. `let scope = …`
                // parsed the name then hit the reserved-name check, or a record
                // pattern failed mid-way — and that error is the right one to keep.
                if (r.isFatal || r.position > stream.getSourcePosition()) r
                else stream.failure(
                    "A `let` binds a name, a wildcard `_`, or a `{ … }` destructuring " +
                        "pattern, but got ${com.mezze.language.parser.tokenGotDescription(stream.current())}",
                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0128_INVALID_LET_BINDING_TARGET,
                    hints = listOf("name the binding, e.g. `let x = ...`")
                )
        }
    }

// Variable pattern for let bindings - simple identifier becomes ParsedVariablePattern
internal val MezzeParser.variablePatternForLet: Parser<ParsedPattern>
    get() = { stream ->
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                val name = ident.value.text
                val reservedError = checkReservedName(name)
                if (reservedError != null) {
                    ParseResult.Failure(
                        reservedError,
                        ident.remaining.getSourcePosition(),
                        ident.value.line,
                        ident.value.column
                    )
                } else {
                    val file = currentFile ?: "<unknown>"
                    ParseResult.Success(
                        ParsedVariablePattern(
                            name,
                            tokenLoc(ident.value, file)
                        ),
                        ident.remaining
                    )
                }
            }
            is ParseResult.Failure -> ident
        }
    }

// Array pattern parser
internal val MezzeParser.seqPattern: Parser<ParsedPattern>
    get() = { stream ->
        val lbracket = tok(TokenType.LBRACKET)(stream)
        when (lbracket) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(lbracket.remaining)
                val file = currentFile ?: "<unknown>"

                if (stream1.current()?.type == TokenType.RBRACKET) {
                    val loc = spanLoc(lbracket.value, stream1.current()!!, file)
                    ParseResult.Success(
                        ParsedSeqPattern(emptyList(), null, loc),
                        stream1.advance()
                    )
                } else {
                    // Use recursive parsing approach to avoid labeled returns in loops
                    fun parseSeqElements(
                        s: TokenStream,
                        accum: List<ParsedPattern>
                    ): ParseResult<Pair<List<ParsedPattern>, Pair<String?, TokenStream>>> {
                        return if (s.current()?.type == TokenType.ELLIPSIS) {
                            val ellipsisStream = s.advance()
                            val afterEllipsis = skipNewlines(ellipsisStream)

                            val restName = identifierParser(ellipsisStream)
                            when (restName) {
                                is ParseResult.Success -> {
                                    // Named rest pattern: [x, ...tail] or [x, ...tail,]
                                    val name = restName.value.text
                                    val remaining = skipNewlines(restName.remaining)
                                    // Allow optional trailing comma after rest pattern
                                    val afterRest = if (remaining.current()?.type == TokenType.COMMA) {
                                        skipNewlines(remaining.advance())
                                    } else {
                                        remaining
                                    }
                                    // Rest pattern must be last - check that ] follows
                                    if (afterRest.current()?.type != TokenType.RBRACKET) {
                                        afterRest.failure(
                                            "Rest pattern (...$name) must be the last element in a seq pattern. " +
                                            "Use [elem1, elem2, ...rest] to match elements at the start and capture the rest."
                                        )
                                    } else {
                                        ParseResult.Success(Pair(accum, Pair(name, afterRest)), afterRest)
                                    }
                                }
                                is ParseResult.Failure -> {
                                    // `..._` is the tail-ignoring rest: it matches the remaining
                                    // elements (so the pattern covers length >= elements.size) but
                                    // binds nothing. It parses as PLACEHOLDER, not an identifier, so
                                    // it lands here. We record it as the sentinel rest name "_" so the
                                    // AST can distinguish a fixed-length pattern (restPattern == null)
                                    // from an open one — exhaustiveness checking depends on this.
                                    if (afterEllipsis.current()?.type == TokenType.PLACEHOLDER) {
                                        val afterUnderscore = skipNewlines(afterEllipsis.advance())
                                        val afterRest = if (afterUnderscore.current()?.type == TokenType.COMMA) {
                                            skipNewlines(afterUnderscore.advance())
                                        } else {
                                            afterUnderscore
                                        }
                                        if (afterRest.current()?.type != TokenType.RBRACKET) {
                                            afterRest.failure(
                                                "Rest pattern (..._) must be the last element in a seq pattern. " +
                                                "Use [elem1, elem2, ..._] to match the start and ignore the rest."
                                            )
                                        } else {
                                            ParseResult.Success(Pair(accum, Pair("_", afterRest)), afterRest)
                                        }
                                    } else {
                                        // A bare anonymous `...` is not allowed: it makes a
                                        // fixed-length pattern indistinguishable from an open one.
                                        // Require a name (`...rest`) to capture the tail, or `..._`
                                        // to match-and-ignore it.
                                        ellipsisStream.failure(
                                            "A rest pattern needs a name: use [first, ...rest] to capture the " +
                                            "remaining elements, or [first, ..._] to match and ignore them. " +
                                            "A bare `...` is not allowed because it would hide the seq's length."
                                        )
                                    }
                                }
                            }
                        } else if (s.current()?.type == TokenType.RBRACKET) {
                            ParseResult.Success(Pair(accum, Pair(null, s)), s)
                        } else {
                            val pattern = patternParser(s)
                            when (pattern) {
                                is ParseResult.Success -> {
                                    val newAccum = accum + pattern.value
                                    val afterPattern = skipNewlines(pattern.remaining)

                                    when (afterPattern.current()?.type) {
                                        TokenType.COMMA -> {
                                            val afterComma = skipNewlines(afterPattern.advance())
                                            if (afterComma.current()?.type == TokenType.RBRACKET) {
                                                ParseResult.Success(Pair(newAccum, Pair(null, afterComma)), afterComma)
                                            } else {
                                                parseSeqElements(afterComma, newAccum)
                                            }
                                        }
                                        TokenType.RBRACKET -> {
                                            ParseResult.Success(Pair(newAccum, Pair(null, afterPattern)), afterPattern)
                                        }
                                        else -> afterPattern.failure(
                                            "Expected ',' to continue seq pattern or ']' to end it, but got ${tokenGotDescription(afterPattern.current())}"
                                        )
                                    }
                                }
                                is ParseResult.Failure -> pattern
                            }
                        }
                    }

                    val elementsResult = parseSeqElements(stream1, emptyList())
                    when (elementsResult) {
                        is ParseResult.Success -> {
                            val (elements, restAndStream) = elementsResult.value
                            val (restPat, currentStream) = restAndStream
                            val rbracket = tok(TokenType.RBRACKET)(currentStream)
                            when (rbracket) {
                                is ParseResult.Success -> {
                                    val loc = spanLoc(lbracket.value, rbracket.value, file)
                                    ParseResult.Success(
                                        ParsedSeqPattern(elements, restPat, loc),
                                        rbracket.remaining
                                    )
                                }
                                is ParseResult.Failure -> rbracket
                            }
                        }
                        is ParseResult.Failure -> elementsResult
                    }
                }
            }
            is ParseResult.Failure -> lbracket
        }
    }

// Pattern parser (main entry point)
internal val MezzeParser.patternParser: Parser<ParsedPattern>
    get() = { stream ->
        Parsers.oneOf(
            wildcardPattern,
            literalPattern,
            seqPattern,
            standaloneRecordPattern,
            constructorPattern,
            variablePattern
        )(stream)
    }

// Standalone record pattern for matching on records directly (not wrapped in a constructor)
// Example: { name, age } -> ...
internal val MezzeParser.standaloneRecordPattern: Parser<ParsedPattern>
    get() = parser@{ stream ->
        val lbrace = tok(TokenType.LBRACE)(stream)
        when (lbrace) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(lbrace.remaining)
                val file = currentFile ?: "<unknown>"

                val firstToken = stream1.current()
                if (firstToken?.type == TokenType.IDENTIFIER) {
                    val reservedError = checkReservedName(firstToken.text)
                    if (reservedError != null) {
                        return@parser ParseResult.Failure(
                            reservedError,
                            stream1.getSourcePosition(),
                            stream1.getSourceLine(),
                            stream1.getSourceColumn()
                        )
                    }
                }

                val entries = sepEndByWithNewlines(
                    recordPatternEntryParser,
                    tok(TokenType.COMMA)
                )(stream1)

                when (entries) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(entries.remaining)

                        val afterComma = if (stream2.current()?.type == TokenType.COMMA) {
                            skipNewlines(stream2.advance())
                        } else {
                            stream2
                        }

                        val isOpen = afterComma.current()?.type == TokenType.ELLIPSIS

                        val stream3 = if (isOpen) {
                            skipNewlines(afterComma.advance())
                        } else {
                            afterComma
                        }

                        val rbrace = tok(TokenType.RBRACE)(stream3)

                        when (rbrace) {
                            is ParseResult.Success -> {
                                val loc = spanLoc(lbrace.value, rbrace.value, file)
                                ParseResult.Success(
                                    ParsedRecordPattern(entries.value, isOpen, loc),
                                    rbrace.remaining
                                )
                            }
                            is ParseResult.Failure -> rbrace
                        }
                    }
                    is ParseResult.Failure -> entries
                }
            }
            is ParseResult.Failure -> lbrace
        }
    }

// Wildcard pattern parser
internal val MezzeParser.wildcardPattern: Parser<ParsedPattern>
    get() = { stream ->
        val placeholder = tok(TokenType.PLACEHOLDER)(stream)
        when (placeholder) {
            is ParseResult.Success -> {
                val file = currentFile ?: "<unknown>"
                ParseResult.Success(
                    ParsedWildcardPattern(tokenLoc(placeholder.value, file)),
                    placeholder.remaining
                )
            }
            is ParseResult.Failure -> placeholder
        }
    }

// Literal pattern parser
internal val MezzeParser.literalPattern: Parser<ParsedPattern>
    get() = { stream ->
        val token = stream.current()
        val file = currentFile ?: "<unknown>"
        when (token?.type) {
            TokenType.INTEGER -> {
                val value = token.text.toLongOrNull()
                if (value != null) {
                    ParseResult.Success(
                        ParsedLiteralPattern(ParsedLiteralValue.IntValue(value), tokenLoc(token, file)),
                        stream.advance()
                    )
                } else {
                    stream.failure("Invalid integer literal '${token.text}'. Integer must be a valid number (e.g., 42, -10, 1_000)")
                }
            }
            TokenType.NAT -> {
                val value = token.text.toLongOrNull()
                if (value != null) {
                    ParseResult.Success(
                        ParsedLiteralPattern(ParsedLiteralValue.NatValue(value), tokenLoc(token, file)),
                        stream.advance()
                    )
                } else {
                    stream.failure("Invalid nat literal '${token.text}'. Natural numbers must be non-negative (e.g., 0n, 42n)")
                }
            }
            TokenType.STRING -> {
                val text = token.text
                val value = if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                    text.substring(1, text.length - 1)
                } else {
                    text
                }
                val loc = tokenLoc(token, file)

                if (hasUnescapedInterpolation(value)) {
                    parseStringInterpolationPattern(value, stream.advance(), loc)
                } else {
                    val processedValue = processEscapeSequences(value)
                    ParseResult.Success(
                        ParsedLiteralPattern(ParsedLiteralValue.StringValue(processedValue), loc),
                        stream.advance()
                    )
                }
            }
            TokenType.BOOLEAN -> {
                val value = token.text == "true"
                ParseResult.Success(
                    ParsedLiteralPattern(ParsedLiteralValue.BoolValue(value), tokenLoc(token, file)),
                    stream.advance()
                )
            }
            TokenType.FLOAT -> {
                val value = token.text.toDoubleOrNull()
                if (value != null) {
                    ParseResult.Success(
                        ParsedLiteralPattern(ParsedLiteralValue.FloatValue(value), tokenLoc(token, file)),
                        stream.advance()
                    )
                } else {
                    stream.failure("Invalid float literal '${token.text}'. Float must be a valid decimal (e.g., 3.14, 1.0e-5)")
                }
            }
            else -> stream.failure("Expected a literal pattern (number, string, or boolean), but got ${tokenGotDescription(token)}")
        }
    }

// Variable pattern parser
internal val MezzeParser.variablePattern: Parser<ParsedPattern>
    get() = { stream ->
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                val next = ident.remaining.current()
                if (next?.type == TokenType.LBRACE) {
                    val suggestion = if (ident.value.text.firstOrNull()?.isLowerCase() == true) {
                        " Constructor names must start with uppercase (e.g., Some, None)"
                    } else ""
                    stream.failure("'${ident.value.text} {...}' is a constructor pattern, not a variable pattern.$suggestion")
                } else {
                    val name = ident.value.text
                    val reservedError = checkReservedName(name)
                    if (reservedError != null) {
                        ParseResult.Failure(
                            reservedError,
                            ident.remaining.getSourcePosition(),
                            ident.value.line,
                            ident.value.column
                        )
                    } else {
                        val file = currentFile ?: "<unknown>"
                        ParseResult.Success(
                            ParsedVariablePattern(
                                name,
                                tokenLoc(ident.value, file)
                            ),
                            ident.remaining
                        )
                    }
                }
            }
            is ParseResult.Failure -> ident
        }
    }

// Constructor pattern parser
internal val MezzeParser.constructorPattern: Parser<ParsedPattern>
    get() = { stream ->
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                val isConstructor = ident.value.text.firstOrNull()?.isUpperCase() == true
                val file = currentFile ?: "<unknown>"

                if (!isConstructor) {
                    stream.failure("Expected constructor name starting with uppercase letter, but got '${ident.value.text}'. Constructors must start with uppercase (e.g., Some, None, Ok, Err)")
                } else {
                    val stream1 = ident.remaining
                    val next = stream1.current()

                    when {
                        // Newtype pattern: Name.(inner_pattern)
                        next?.type == TokenType.DOT && stream1.advance().current()?.type == TokenType.LPAREN -> {
                            val afterDotLparen = stream1.advance().advance()
                            val innerPat = patternParser(afterDotLparen)
                            when (innerPat) {
                                is ParseResult.Success -> {
                                    val rparen = tok(TokenType.RPAREN)(skipNewlines(innerPat.remaining))
                                    when (rparen) {
                                        is ParseResult.Success -> {
                                            val loc = spanLoc(ident.value, rparen.value, file)
                                            ParseResult.Success(
                                                ParsedNewtypePattern(ident.value.text, innerPat.value, loc),
                                                rparen.remaining
                                            )
                                        }
                                        is ParseResult.Failure -> ParseResult.Failure(
                                            "Expected ')' to close newtype pattern '${ident.value.text}.(...)'",
                                            rparen.position, rparen.line, rparen.column
                                        )
                                    }
                                }
                                is ParseResult.Failure -> ParseResult.Failure(
                                    "Expected pattern inside newtype destructuring '${ident.value.text}.(...)'",
                                    innerPat.position, innerPat.line, innerPat.column
                                )
                            }
                        }
                        next?.type == TokenType.LBRACE -> {
                            // Has record pattern: Some {value = x}
                            val recordPat = recordPatternParser(stream1)
                            when (recordPat) {
                                is ParseResult.Success -> {
                                    val loc = spanLoc(ident.value, stream1.tokens.getOrNull(recordPat.remaining.position - 1) ?: ident.value, file)
                                    ParseResult.Success(
                                        ParsedConstructorPattern(ident.value.text, recordPat.value, loc),
                                        recordPat.remaining
                                    )
                                }
                                is ParseResult.Failure -> recordPat
                            }
                        }
                        next?.type == TokenType.IDENTIFIER -> {
                            // Shorthand pattern: Some x (means Some {value = x})
                            val varPat = identifierParser(stream1)
                            when (varPat) {
                                is ParseResult.Success -> {
                                    val name = varPat.value.text
                                    val reservedError = checkReservedName(name)
                                    if (reservedError != null) {
                                        ParseResult.Failure(
                                            reservedError,
                                            varPat.remaining.getSourcePosition(),
                                            varPat.value.line,
                                            varPat.value.column
                                        )
                                    } else {
                                        val varLoc = tokenLoc(varPat.value, file)
                                        val innerPattern = ParsedRecordPattern(
                                            listOf(
                                                ParsedRecordPatternEntry("value", ParsedVariablePattern(name, varLoc), varLoc)
                                            ),
                                            false,
                                            varLoc
                                        )
                                        val ctorLoc = spanLoc(ident.value, varPat.value, file)
                                        ParseResult.Success(
                                            ParsedConstructorPattern(
                                                ident.value.text,
                                                innerPattern,
                                                ctorLoc
                                            ),
                                            varPat.remaining
                                        )
                                    }
                                }
                                is ParseResult.Failure -> varPat
                            }
                        }
                        else -> {
                            ParseResult.Success(
                                ParsedConstructorPattern(ident.value.text, null, tokenLoc(ident.value, file)),
                                stream1
                            )
                        }
                    }
                }
            }
            is ParseResult.Failure -> ident
        }
    }

internal val MezzeParser.recordPatternParser: Parser<ParsedRecordPattern>
    get() = { stream ->
        val lbrace = tok(TokenType.LBRACE)(stream)
        when (lbrace) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(lbrace.remaining)
                val file = currentFile ?: "<unknown>"
                val entries = sepEndByWithNewlines(
                    recordPatternEntryParser,
                    tok(TokenType.COMMA)
                )(stream1)

                when (entries) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(entries.remaining)

                        val afterComma = if (stream2.current()?.type == TokenType.COMMA) {
                            skipNewlines(stream2.advance())
                        } else {
                            stream2
                        }

                        val isOpen = afterComma.current()?.type == TokenType.ELLIPSIS

                        val stream3 = if (isOpen) {
                            skipNewlines(afterComma.advance())
                        } else {
                            afterComma
                        }

                        val rbrace = tok(TokenType.RBRACE)(stream3)

                        when (rbrace) {
                            is ParseResult.Success -> {
                                val loc = spanLoc(lbrace.value, rbrace.value, file)
                                ParseResult.Success(
                                    ParsedRecordPattern(entries.value, isOpen, loc),
                                    rbrace.remaining
                                )
                            }
                            is ParseResult.Failure -> rbrace
                        }
                    }
                    is ParseResult.Failure -> entries
                }
            }
            is ParseResult.Failure -> lbrace
        }
    }

internal val MezzeParser.recordPatternEntryParser: Parser<ParsedRecordPatternEntry>
    get() = { stream ->
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                val fieldName = ident.value.text
                val reservedError = checkReservedName(fieldName)
                if (reservedError != null) {
                    ParseResult.Failure(
                        reservedError,
                        ident.remaining.getSourcePosition(),
                        ident.value.line,
                        ident.value.column
                    )
                } else {
                    val stream1 = ident.remaining
                    val assign = tok(TokenType.ASSIGN)(stream1)
                    val file = currentFile ?: "<unknown>"
                    val fieldLoc = tokenLoc(ident.value, file)

                    when (assign) {
                        is ParseResult.Success -> {
                            // field = pattern
                            val pattern = patternParser(assign.remaining)
                            when (pattern) {
                                is ParseResult.Success -> ParseResult.Success(
                                    ParsedRecordPatternEntry(fieldName, pattern.value, fieldLoc),
                                    pattern.remaining
                                )
                                is ParseResult.Failure -> pattern
                            }
                        }
                        is ParseResult.Failure -> {
                            // Punning: field
                            ParseResult.Success(
                                ParsedRecordPatternEntry(fieldName, null, fieldLoc),
                                stream1
                            )
                        }
                    }
                }
            }
            is ParseResult.Failure -> ident
        }
    }
