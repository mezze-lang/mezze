package com.mezze.language.parser.internal

import com.mezze.language.ast.*
import com.mezze.language.parser.*

import com.mezze.language.parser.internal.lib.*
/**
 * Pattern parsers for all pattern matching constructs.
 *
 * This file contains internal parsers for:
 * - Wildcard patterns (_)
 * - Variable patterns (x)
 * - Constructor patterns (Cons { head, tail })
 * - Record patterns ({ x, y = pattern })
 * - Literal patterns (42, "hello", true)
 * - Array patterns ([x, y, ...rest])
 * - Let binding patterns (irrefutable patterns only)
 * - Pattern utilities and validation
 *
 * All parsers return Pattern (sealed interface from AST).
 * All functions are internal - only used within the parser module.
 *
 * Part of the refactored parser module - previously in MezzeParser.kt.
 */

// Let binding pattern parser (for irrefutable patterns in let bindings)
internal val MezzeParser.letBindingPattern: Parser<Pattern>
    get() = { stream ->
        Parsers.oneOf(
            wildcardPattern,
            standaloneRecordPattern,
            variablePatternForLet
        )(stream)
    }

// Variable pattern for let bindings - simple identifier becomes VariablePattern
internal val MezzeParser.variablePatternForLet: Parser<Pattern>
    get() = { stream ->
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> ParseResult.Success(VariablePattern(ident.value.text), ident.remaining)
            is ParseResult.Failure -> ident
        }
    }

// Array pattern parser
internal val MezzeParser.arrayPattern: Parser<Pattern>
    get() = { stream ->
        // Parse: [] or [x] or [x, y] or [x, ...rest]
        val lbracket = tok(TokenType.LBRACKET)(stream)
        when (lbracket) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(lbracket.remaining)

                // Check for empty array pattern []
                if (stream1.current()?.type == TokenType.RBRACKET) {
                    ParseResult.Success(
                        ArrayPattern(emptyList(), null),
                        stream1.advance()
                    )
                } else {
                    // Use recursive parsing approach to avoid labeled returns in loops
                    fun parseArrayElements(
                        s: TokenStream,
                        accum: List<Pattern>
                    ): ParseResult<Pair<List<Pattern>, Pair<String?, TokenStream>>> {
                        // Check for rest pattern: ...name or just ... (anonymous)
                        return if (s.current()?.type == TokenType.ELLIPSIS) {
                            val ellipsisStream = s.advance()
                            val afterEllipsis = skipNewlines(ellipsisStream)

                            // Try to parse identifier after ...
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
                                    ParseResult.Success(Pair(accum, Pair(name, afterRest)), afterRest)
                                }
                                is ParseResult.Failure -> {
                                    // No identifier after ... - this is an anonymous rest pattern: [x, ...] or [x, ...,]
                                    // Check if we're at ] or , which indicates end of pattern
                                    when (afterEllipsis.current()?.type) {
                                        TokenType.RBRACKET -> {
                                            // Anonymous rest pattern is valid
                                            ParseResult.Success(Pair(accum, Pair(null, afterEllipsis)), afterEllipsis)
                                        }
                                        TokenType.COMMA -> {
                                            // Anonymous rest pattern with trailing comma: [x, ...,]
                                            val afterComma = skipNewlines(afterEllipsis.advance())
                                            ParseResult.Success(Pair(accum, Pair(null, afterComma)), afterComma)
                                        }
                                        else -> {
                                            // Expected identifier or end of pattern
                                            ellipsisStream.failure(
                                                "After '...' in array pattern, expected a name for the rest or ']' to end the pattern. Examples: [first, ...rest] or [first, ...]"
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (s.current()?.type == TokenType.RBRACKET) {
                            // End of array pattern
                            ParseResult.Success(Pair(accum, Pair(null, s)), s)
                        } else {
                            // Parse pattern element
                            val pattern = patternParser(s)
                            when (pattern) {
                                is ParseResult.Success -> {
                                    val newAccum = accum + pattern.value
                                    val afterPattern = skipNewlines(pattern.remaining)

                                    when (afterPattern.current()?.type) {
                                        TokenType.COMMA -> {
                                            val afterComma = skipNewlines(afterPattern.advance())
                                            // Check if closing bracket (trailing comma)
                                            if (afterComma.current()?.type == TokenType.RBRACKET) {
                                                ParseResult.Success(Pair(newAccum, Pair(null, afterComma)), afterComma)
                                            } else {
                                                // Continue parsing
                                                parseArrayElements(afterComma, newAccum)
                                            }
                                        }
                                        TokenType.RBRACKET -> {
                                            ParseResult.Success(Pair(newAccum, Pair(null, afterPattern)), afterPattern)
                                        }
                                        else -> afterPattern.failure(
                                            "Expected ',' to continue array pattern or ']' to end it, but got ${tokenGotDescription(afterPattern.current())}"
                                        )
                                    }
                                }
                                is ParseResult.Failure -> pattern
                            }
                        }
                    }

                    val elementsResult = parseArrayElements(stream1, emptyList())
                    when (elementsResult) {
                        is ParseResult.Success -> {
                            val (elements, restAndStream) = elementsResult.value
                            val (restPat, currentStream) = restAndStream
                            // Parse closing bracket
                            val rbracket = tok(TokenType.RBRACKET)(currentStream)
                            when (rbracket) {
                                is ParseResult.Success -> ParseResult.Success(
                                    ArrayPattern(elements, restPat),
                                    rbracket.remaining
                                )
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
internal val MezzeParser.patternParser: Parser<Pattern>
    get() = { stream ->
        Parsers.oneOf(
            wildcardPattern,
            literalPattern,
            arrayPattern,
            standaloneRecordPattern,
            constructorPattern,
            variablePattern
        )(stream)
    }

// Standalone record pattern for matching on records directly (not wrapped in a constructor)
// Example: { name, age } -> ...
internal val MezzeParser.standaloneRecordPattern: Parser<Pattern>
    get() = { stream ->
        // Only parse if we see a LBRACE that's not preceded by an uppercase identifier
        // Since this is tried after wildcard and literal patterns, we can safely try parsing
        val lbrace = tok(TokenType.LBRACE)(stream)
        when (lbrace) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(lbrace.remaining)
                val entries = sepEndByWithNewlines(
                    recordPatternEntryParser,
                    tok(TokenType.COMMA)
                )(stream1)

                when (entries) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(entries.remaining)

                        // Check for optional comma before ellipsis
                        val afterComma = if (stream2.current()?.type == TokenType.COMMA) {
                            skipNewlines(stream2.advance())
                        } else {
                            stream2
                        }

                        // Check for ellipsis (open record marker: ...)
                        val isOpen = afterComma.current()?.type == TokenType.ELLIPSIS

                        val stream3 = if (isOpen) {
                            skipNewlines(afterComma.advance())
                        } else {
                            afterComma
                        }

                        val rbrace = tok(TokenType.RBRACE)(stream3)

                        when (rbrace) {
                            is ParseResult.Success -> ParseResult.Success(
                                RecordPattern(entries.value, isOpen),
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

// Wildcard pattern parser
internal val MezzeParser.wildcardPattern: Parser<Pattern>
    get() = { stream ->
        val placeholder = tok(TokenType.PLACEHOLDER)(stream)
        when (placeholder) {
            is ParseResult.Success -> ParseResult.Success(WildcardPattern(), placeholder.remaining)
            is ParseResult.Failure -> placeholder
        }
    }

// Literal pattern parser
internal val MezzeParser.literalPattern: Parser<Pattern>
    get() = { stream ->
        val token = stream.current()
        when (token?.type) {
            TokenType.INTEGER -> {
                val value = token.text.toLongOrNull()
                if (value != null) {
                    ParseResult.Success(
                        LiteralPattern(IntLiteralValue(value)),
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
                        LiteralPattern(NatLiteralValue(value)),
                        stream.advance()
                    )
                } else {
                    stream.failure("Invalid nat literal '${token.text}'. Natural numbers must be non-negative (e.g., 0n, 42n)")
                }
            }
            TokenType.STRING -> {
                // String token includes quotes, strip them
                val text = token.text
                val value = if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                    text.substring(1, text.length - 1)
                } else {
                    text
                }
                val location = tokenLocation(token, currentFile)

                // Check if this is a string interpolation pattern (has unescaped $()
                if (hasUnescapedInterpolation(value)) {
                    parseStringInterpolationPattern(value, stream.advance(), location)
                } else {
                    // Simple string literal pattern - process escape sequences
                    val processedValue = processEscapeSequences(value)
                    ParseResult.Success(
                        LiteralPattern(StringLiteralValue(processedValue)),
                        stream.advance()
                    )
                }
            }
            TokenType.BOOLEAN -> {
                val value = token.text == "true"
                ParseResult.Success(
                    LiteralPattern(BooleanLiteralValue(value)),
                    stream.advance()
                )
            }
            TokenType.FLOAT -> {
                val value = token.text.toDoubleOrNull()
                if (value != null) {
                    ParseResult.Success(
                        LiteralPattern(FloatLiteralValue(value)),
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
internal val MezzeParser.variablePattern: Parser<Pattern>
    get() = { stream ->
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                // Check if next token is LBRACE - if so, it's a constructor pattern
                val next = ident.remaining.current()
                if (next?.type == TokenType.LBRACE) {
                    // User likely meant a constructor pattern - guide them to use uppercase
                    val suggestion = if (ident.value.text.firstOrNull()?.isLowerCase() == true) {
                        " Constructor names must start with uppercase (e.g., Some, None)"
                    } else ""
                    stream.failure("'${ident.value.text} {...}' is a constructor pattern, not a variable pattern.$suggestion")
                } else {
                    ParseResult.Success(VariablePattern(ident.value.text), ident.remaining)
                }
            }
            is ParseResult.Failure -> ident
        }
    }

// Constructor pattern parser
internal val MezzeParser.constructorPattern: Parser<Pattern>
    get() = { stream ->
        // Parse: Constructor { field1 = pattern1, field2 = pattern2 }
        // or: Constructor variableName (shorthand for single-field constructors)
        // or: Constructor (no pattern)
        // Note: Constructors are globally available (not module-qualified) once the type is imported
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                // Check if constructor name starts with uppercase (convention)
                val isConstructor = ident.value.text.firstOrNull()?.isUpperCase() == true

                if (!isConstructor) {
                    stream.failure("Expected constructor name starting with uppercase letter, but got '${ident.value.text}'. Constructors must start with uppercase (e.g., Some, None, Ok, Err)")
                } else {
                    val stream1 = ident.remaining
                    val next = stream1.current()

                    when {
                        next?.type == TokenType.LBRACE -> {
                            // Has record pattern: Some {value = x}
                            val recordPat = recordPatternParser(stream1)
                            when (recordPat) {
                                is ParseResult.Success -> ParseResult.Success(
                                    ConstructorPattern(ident.value.text, recordPat.value),
                                    recordPat.remaining
                                )
                                is ParseResult.Failure -> recordPat
                            }
                        }
                        next?.type == TokenType.IDENTIFIER -> {
                            // Shorthand pattern: Some x (means Some {value = x})
                            val varPat = identifierParser(stream1)
                            when (varPat) {
                                is ParseResult.Success -> ParseResult.Success(
                                    ConstructorPattern(
                                        ident.value.text,
                                        RecordPattern(listOf(
                                            RecordPatternEntry("value", VariablePattern(varPat.value.text))
                                        ), false)  // Shorthand patterns are always closed
                                    ),
                                    varPat.remaining
                                )
                                is ParseResult.Failure -> varPat
                            }
                        }
                        else -> {
                            // No pattern: None
                            ParseResult.Success(
                                ConstructorPattern(ident.value.text, null),
                                stream1
                            )
                        }
                    }
                }
            }
            is ParseResult.Failure -> ident
        }
    }

// Record pattern parser
internal val MezzeParser.recordPatternParser: Parser<RecordPattern>
    get() = { stream ->
        // Parse: { field1 = pattern1, field2, ... }
        val lbrace = tok(TokenType.LBRACE)(stream)
        when (lbrace) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(lbrace.remaining)
                val entries = sepEndByWithNewlines(
                    recordPatternEntryParser,
                    tok(TokenType.COMMA)
                )(stream1)

                when (entries) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(entries.remaining)

                        // Check for optional comma before ellipsis (same logic as recordTypeAnnotationParser)
                        val afterComma = if (stream2.current()?.type == TokenType.COMMA) {
                            skipNewlines(stream2.advance())
                        } else {
                            stream2
                        }

                        // Check for ellipsis (open record marker: ...)
                        val isOpen = afterComma.current()?.type == TokenType.ELLIPSIS

                        val stream3 = if (isOpen) {
                            skipNewlines(afterComma.advance())  // Skip the ellipsis token
                        } else {
                            afterComma
                        }

                        val rbrace = tok(TokenType.RBRACE)(stream3)

                        when (rbrace) {
                            is ParseResult.Success -> ParseResult.Success(
                                RecordPattern(entries.value, isOpen),
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

// Record pattern entry parser
internal val MezzeParser.recordPatternEntryParser: Parser<RecordPatternEntry>
    get() = { stream ->
        // Parse: field = pattern or field (punning)
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                val stream1 = ident.remaining
                val assign = tok(TokenType.ASSIGN)(stream1)

                when (assign) {
                    is ParseResult.Success -> {
                        // field = pattern
                        val pattern = patternParser(assign.remaining)
                        when (pattern) {
                            is ParseResult.Success -> ParseResult.Success(
                                RecordPatternEntry(ident.value.text, pattern.value),
                                pattern.remaining
                            )
                            is ParseResult.Failure -> pattern
                        }
                    }
                    is ParseResult.Failure -> {
                        // Punning: field
                        ParseResult.Success(
                            RecordPatternEntry(ident.value.text, null),
                            stream1
                        )
                    }
                }
            }
            is ParseResult.Failure -> ident
        }
    }
