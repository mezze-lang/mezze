package com.mezze.language.parser.internal

import com.mezze.language.parsedast.*
import com.mezze.language.parser.*

import com.mezze.language.parser.internal.lib.*
// Helper data class for effect row parsing result
private data class EffectRowParseResult(
    val effects: List<String>,
    val effectAnnotations: List<ParsedEffectAnnotation>,
    val isPolymorphic: Boolean,
    val isPerformed: Boolean,
    val stream: TokenStream
)

/**
 * Error message for `{it: 'm 'a}` — the user is applying a type variable to
 * another type. The lexer marks `'a` as IDENTIFIER, so the record parser
 * mistakes it for a missing-annotation case; surfacing this explicitly
 * points at the real problem.
 */
private fun typeVarJuxtaposedMessage(fieldName: String): String =
    "Type variable '$fieldName' cannot be applied to another type. " +
    "Type variables in Mezze can only stand alone — they cannot act as type constructors. " +
    "If you want to parameterize over a wrapper type, declare the relationship in the impl head: " +
    "`impl Foo for Result 'a 'e where { ... }` not `{it: 'm 'a}`."

/**
 * Build a fix-it hint for `{p} -> Ret <Eff> with C` rewriting the user's
 * own signature to the canonical `{p} with C -> Ret <Eff>` form. Returns a
 * list of hint lines suitable for `ParseResult.Failure.hints`.
 *
 * Slices the source between the relevant tokens; falls back to a generic
 * example if the source isn't available (e.g. tests that construct
 * TokenStreams directly).
 */
private fun buildWithAfterArrowFix(
    source: String?,
    paramStart: Token,
    arrowToken: Token,
    withToken: Token,
    tokensAfterWith: TokenStream,
): List<String> {
    // Generic fallback when we can't slice the original source.
    val generic = listOf(
        "move the 'with' clause to before the '->', e.g.:",
        "    { x: 'a, y: 'a } with NumAdd 'a -> 'a <Log>",
        "not:",
        "    { x: 'a, y: 'a } -> 'a <Log> with NumAdd 'a",
    )
    if (source == null) return generic

    // Slurp tokens for the with-clause: from `with` until we hit a terminator
    // (`=` for let-assignment, EOF, `,` at top level, `}`/`)` for nested
    // contexts). End offset = the start of the terminator token, or end of
    // source if no terminator found.
    var idx = tokensAfterWith.position
    val tokens = tokensAfterWith.tokens
    var endOffset = source.length
    while (idx < tokens.size) {
        val t = tokens[idx]
        when (t.type) {
            TokenType.ASSIGN, TokenType.RBRACE, TokenType.RPAREN, TokenType.COMMA -> {
                endOffset = t.position
                break
            }
            else -> idx++
        }
    }

    // Slice the original source. Bail to generic if any offsets are odd.
    return try {
        val paramSlice = source.substring(paramStart.position, arrowToken.position).trimEnd()
        val returnSlice = source.substring(arrowToken.position + 2, withToken.position).trim() // +2 to skip "->"
        val withSlice = source.substring(withToken.position, endOffset).trim()
        listOf(
            "rewrite as:",
            "    $paramSlice $withSlice -> $returnSlice",
        )
    } catch (_: StringIndexOutOfBoundsException) {
        generic
    }
}

// Record type entry parser (helper for record type annotations)
internal val MezzeParser.recordTypeEntryParser: Parser<ParsedRecordTypeEntry>
    get() = { stream ->
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                val colon = tok(TokenType.COLON)((ident as ParseResult.Success).remaining)
                when (colon) {
                    is ParseResult.Success -> {
                        val typeAnnotation = typeAnnotationParser((colon as ParseResult.Success).remaining)
                        when (typeAnnotation) {
                            is ParseResult.Success -> {
                                val file = currentFile ?: "<unknown>"
                                val loc = spanLocTo(ident.value, typeAnnotation.remaining, file)
                                ParseResult.Success(
                                    ParsedRecordTypeEntry(ident.value.text, (typeAnnotation as ParseResult.Success).value, loc),
                                    typeAnnotation.remaining
                                )
                            }
                            is ParseResult.Failure -> typeAnnotation
                        }
                    }
                    is ParseResult.Failure -> {
                        val fieldName = ident.value.text
                        val nextToken = ident.remaining.current()
                        if (fieldName.startsWith("'")) {
                            ident.remaining.failure(
                                typeVarJuxtaposedMessage(fieldName),
                                code = com.mezze.language.parsedast.diagnostics.ErrorCode.UNCATEGORIZED
                            )
                        } else {
                            val message = when (nextToken?.type) {
                                TokenType.IDENTIFIER -> "Record type field '$fieldName' is missing a type annotation.\n" +
                                        "Expected format: { $fieldName: Type }"
                                TokenType.COMMA ->
                                    "Record type field '$fieldName' is missing a type annotation.\n" +
                                            "Expected format: { $fieldName: Type, ... }"
                                else ->
                                    "Record type field '$fieldName' is missing a type annotation. " +
                                            "Expected ':' followed by a type, but got ${tokenGotDescription(nextToken)}"
                            }
                            ident.remaining.failure(
                                message,
                                code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0117_RECORD_FIELD_MISSING_TYPE
                            )
                        }
                    }
                }
            }
            is ParseResult.Failure -> ident
        }
    }

// Type annotation parser (main entry point)
internal val MezzeParser.typeAnnotationParser: Parser<ParsedTypeAnnotation>
    get() = { stream ->
        // Anti-hang backstop: one unit of work per type re-entry (P0132).
        parseTick(stream)
        // Depth guard (shared with expressions): nested `[[[…]]]` / `Map K V` /
        // function types re-enter here, so deep nesting can blow the stack. Fail
        // cleanly past the limit instead of crashing (fuzz-found).
        if (nestDepth >= MezzeParser.MAX_NEST_DEPTH) {
            stream.failure(
                "Type is nested too deeply (over ${MezzeParser.MAX_NEST_DEPTH} levels) — " +
                    "usually runaway or unbalanced brackets.",
                code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0131_NESTING_TOO_DEEP,
                hints = listOf("check for unbalanced `[`/`{`/`(`, or introduce a `type` alias")
            )
        } else {
            nestDepth++
            try { functionTypeAnnotation(stream) } finally { nestDepth-- }
        }
    }

// Function type annotation parser
internal val MezzeParser.functionTypeAnnotation: Parser<ParsedTypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        var result: ParseResult<ParsedTypeAnnotation> = primaryTypeAnnotation(stream)

        while (result is ParseResult.Success) {
            parseTick(result.remaining)  // anti-hang: bounded work per iteration
            val startToken = stream.current() ?: break
            var currentStream = result.remaining

            val abilityConstraints = mutableListOf<ParsedFnAbilityConstraint>()
            val streamBeforeWith = skipNewlines(currentStream)
            if (streamBeforeWith.current()?.type == TokenType.WITH) {
                var abilityStream = skipNewlines(streamBeforeWith.advance())

                while (true) {
                    val abilityNameResult = parseQualifiedName(abilityStream)
                    if (abilityNameResult !is ParseResult.Success) break
                    val abilityName = abilityNameResult.value
                    val constraintStart = abilityStream.current() ?: break
                    abilityStream = skipNewlines(abilityNameResult.remaining)

                    val typeArgs = mutableListOf<ParsedTypeAnnotation>()
                    while (true) {
                        parseTick(abilityStream)  // anti-hang: bounded work per iteration
                        val typeResult = atomicTypeAnnotation(abilityStream)
                        when (typeResult) {
                            is ParseResult.Success -> {
                                typeArgs.add(typeResult.value)
                                abilityStream = skipNewlines(typeResult.remaining)
                                val nextToken = abilityStream.current()
                                if (nextToken?.type == TokenType.ARROW ||
                                    nextToken?.type == TokenType.COMMA ||
                                    nextToken?.type == TokenType.WITH ||
                                    nextToken?.type == TokenType.HAVING ||
                                    nextToken == null) {
                                    break
                                }
                            }
                            is ParseResult.Failure -> break
                        }
                    }

                    val associatedTypeBindings = mutableMapOf<String, ParsedTypeAnnotation>()
                    var havingError: ParseResult.Failure? = null
                    if (abilityStream.current()?.type == TokenType.HAVING) {
                        val havingToken = abilityStream.current()!!
                        abilityStream = skipNewlines(abilityStream.advance())
                        while (true) {
                            val typeVarResult = identifierParser(abilityStream)
                            if (typeVarResult !is ParseResult.Success) break
                            val typeVarName = typeVarResult.value.text
                            if (!typeVarName.startsWith("'")) {
                                val tok = typeVarResult.value
                                havingError = ParseResult.Failure(
                                    message = "'having' expects a type variable binding, not a concrete type.\n" +
                                        "Write: having 'elem = $typeVarName",
                                    position = tok.position,
                                    line = tok.line,
                                    column = tok.column,
                                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0127_HAVING_NEEDS_BINDING,
                                    isFatal = true,
                                )
                                break
                            }
                            abilityStream = skipNewlines(typeVarResult.remaining)
                            if (abilityStream.current()?.type != TokenType.ASSIGN) {
                                val nextTok = abilityStream.current() ?: havingToken
                                havingError = ParseResult.Failure(
                                    message = "'having' requires a type binding: having $typeVarName = Type",
                                    position = nextTok.position,
                                    line = nextTok.line,
                                    column = nextTok.column,
                                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0127_HAVING_NEEDS_BINDING,
                                    isFatal = true,
                                )
                                break
                            }
                            abilityStream = skipNewlines(abilityStream.advance())
                            val typeResult = atomicTypeAnnotation(abilityStream)
                            if (typeResult !is ParseResult.Success) break
                            associatedTypeBindings[typeVarName] = typeResult.value
                            abilityStream = skipNewlines(typeResult.remaining)
                            if (abilityStream.current()?.type == TokenType.COMMA) {
                                val afterComma = skipNewlines(abilityStream.advance())
                                val nextToken = afterComma.current()
                                if (nextToken?.type == TokenType.IDENTIFIER && nextToken.text.startsWith("'")) {
                                    abilityStream = afterComma
                                    continue
                                } else {
                                    break
                                }
                            } else {
                                break
                            }
                        }
                    }
                    if (havingError != null) {
                        result = havingError as ParseResult<ParsedTypeAnnotation>
                        break
                    }

                    val constraintLoc = spanLocTo(constraintStart, abilityStream, file)
                    abilityConstraints.add(ParsedFnAbilityConstraint(abilityName, typeArgs, associatedTypeBindings, constraintLoc))

                    if (abilityStream.current()?.type == TokenType.COMMA) {
                        abilityStream = skipNewlines(abilityStream.advance())
                    } else {
                        break
                    }
                }

                currentStream = abilityStream
            }

            if (result is ParseResult.Failure) break

            // Allow the return-arrow to sit on a following line: a wrapped
            // signature (`… : { … }\n  -> Ret`, #78) is newline-insensitive
            // between the parameter type, `->`, effect row, and return type.
            // A `->` on the next line unambiguously continues the function
            // type — nothing else legitimately follows a type across a bare
            // newline in this position.
            val streamBeforeArrow = skipNewlines(currentStream)
            val arrow = streamBeforeArrow.current()

            if (arrow?.type == TokenType.ARROW) {
                val arrowStream = streamBeforeArrow.advance()
                // Route the return-type recursion through the depth-guarded entry
                // (not functionTypeAnnotation directly) so a curried `{}->{}->…->T`
                // is bounded by MAX_NEST_DEPTH instead of recursing unguarded.
                val returnType = typeAnnotationParser(arrowStream)

                val paramType = (result as ParseResult.Success).value
                result = when (returnType) {
                    is ParseResult.Success -> {
                        val streamAfterReturn = skipNewlines(returnType.remaining)
                        val ltToken = streamAfterReturn.current()

                        val effectRowResult = if (ltToken?.type == TokenType.LT) {
                            val afterLt = streamAfterReturn.advance()
                            val gtToken = afterLt.current()

                            if (gtToken?.type == TokenType.GT) {
                                EffectRowParseResult(emptyList(), emptyList(), false, false, afterLt.advance())
                            } else {
                                val firstToken = skipNewlines(afterLt).current()
                                if (firstToken?.type == TokenType.ELLIPSIS) {
                                    val afterEllipsis = skipNewlines(afterLt.advance())
                                    if (afterEllipsis.current()?.type == TokenType.GT) {
                                        EffectRowParseResult(emptyList(), emptyList(), true, false, afterEllipsis.advance())
                                    } else {
                                        EffectRowParseResult(emptyList(), emptyList(), false, false, returnType.remaining)
                                    }
                                } else if (firstToken?.type == TokenType.STAR) {
                                    val afterStar = skipNewlines(afterLt.advance())
                                    if (afterStar.current()?.type == TokenType.GT) {
                                        EffectRowParseResult(emptyList(), emptyList(), false, true, afterStar.advance())
                                    } else {
                                        EffectRowParseResult(emptyList(), emptyList(), false, false, returnType.remaining)
                                    }
                                } else {
                                    val simpleEffectNames = mutableListOf<String>()
                                    val effectAnnotations = mutableListOf<ParsedEffectAnnotation>()
                                    var current = skipNewlines(afterLt)
                                    var foundEllipsis = false
                                    var foundPerformed = false
                                    var effectHavingError: ParseResult.Failure? = null

                                    while (true) {
                                        val effectNameResult = parseQualifiedName(current)
                                        if (effectNameResult !is ParseResult.Success) break
                                        val effectName = effectNameResult.value
                                        val effectStart = current.current()
                                        current = skipNewlines(effectNameResult.remaining)

                                        if (current.current()?.type == TokenType.HAVING) {
                                            val havingTok = current.current()!!
                                            current = skipNewlines(current.advance())
                                            val associatedTypes = mutableMapOf<String, ParsedTypeAnnotation>()

                                            while (true) {
                                                val typeVarResult = identifierParser(current)
                                                if (typeVarResult !is ParseResult.Success) break
                                                val typeVarName = typeVarResult.value.text
                                                if (!typeVarName.startsWith("'")) {
                                                    val tok = typeVarResult.value
                                                    effectHavingError = ParseResult.Failure(
                                                        message = "'having' expects a type variable binding, not a concrete type.\n" +
                                                            "Write: having 'elem = $typeVarName",
                                                        position = tok.position,
                                                        line = tok.line,
                                                        column = tok.column,
                                                        code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0127_HAVING_NEEDS_BINDING,
                                                        isFatal = true,
                                                    )
                                                    break
                                                }
                                                current = skipNewlines(typeVarResult.remaining)
                                                if (current.current()?.type != TokenType.ASSIGN) {
                                                    val nextTok = current.current() ?: havingTok
                                                    effectHavingError = ParseResult.Failure(
                                                        message = "'having' requires a type binding: having $typeVarName = Type",
                                                        position = nextTok.position,
                                                        line = nextTok.line,
                                                        column = nextTok.column,
                                                        code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0127_HAVING_NEEDS_BINDING,
                                                        isFatal = true,
                                                    )
                                                    break
                                                }
                                                current = skipNewlines(current.advance())
                                                val typeResult = atomicTypeAnnotation(current)
                                                if (typeResult !is ParseResult.Success) break
                                                associatedTypes[typeVarName] = typeResult.value
                                                current = skipNewlines(typeResult.remaining)
                                                if (current.current()?.type == TokenType.COMMA) {
                                                    val afterComma = skipNewlines(current.advance())
                                                    val nextToken = afterComma.current()
                                                    if (nextToken?.type == TokenType.IDENTIFIER && nextToken.text.startsWith("'")) {
                                                        current = afterComma
                                                        continue
                                                    } else {
                                                        break
                                                    }
                                                } else {
                                                    break
                                                }
                                            }

                                            if (effectHavingError != null) break
                                            val annLoc = if (effectStart != null) spanLocTo(effectStart, current, file) else streamLoc(current, file)
                                            effectAnnotations.add(ParsedEffectAnnotation(effectName, associatedTypes, annLoc))
                                        } else {
                                            simpleEffectNames.add(effectName)
                                        }

                                        if (effectHavingError != null) break
                                        if (current.current()?.type == TokenType.COMMA) {
                                            current = skipNewlines(current.advance())
                                            if (current.current()?.type == TokenType.ELLIPSIS) {
                                                foundEllipsis = true
                                                current = skipNewlines(current.advance())
                                                break
                                            }
                                            if (current.current()?.type == TokenType.STAR) {
                                                foundPerformed = true
                                                current = skipNewlines(current.advance())
                                                break
                                            }
                                        } else {
                                            break
                                        }
                                    }

                                    if (effectHavingError != null) {
                                        result = effectHavingError as ParseResult<ParsedTypeAnnotation>
                                    }
                                    if (current.current()?.type == TokenType.GT) {
                                        EffectRowParseResult(simpleEffectNames, effectAnnotations, foundEllipsis, foundPerformed, current.advance())
                                    } else {
                                        EffectRowParseResult(emptyList(), emptyList(), false, false, returnType.remaining)
                                    }
                                }
                            }
                        } else {
                            EffectRowParseResult(emptyList(), emptyList(), false, false, returnType.remaining)
                        }

                        if (result is ParseResult.Failure) {
                            result
                        } else {

                        // `with` after the return type is rejected — constraints must
                        // come BEFORE the arrow. Catching this here gives a precise
                        // loc pointing at the misplaced `with` and a fix-it that
                        // rewrites the user's actual signature. Pre-arrow form is the
                        // canonical position; see #35.
                        val streamAfterWith = effectRowResult.stream
                        val streamBeforeWithAfter = skipNewlines(effectRowResult.stream)
                        val postArrowWithToken =
                            if (streamBeforeWithAfter.current()?.type == TokenType.WITH) {
                                streamBeforeWithAfter.current()
                            } else null

                        if (postArrowWithToken != null) {
                            val hint = buildWithAfterArrowFix(
                                source = currentSource,
                                paramStart = startToken,
                                arrowToken = arrow,
                                withToken = postArrowWithToken,
                                tokensAfterWith = streamBeforeWithAfter,
                            )
                            ParseResult.Failure(
                                message = "'with' clause must come BEFORE the arrow, not after the return type.",
                                position = postArrowWithToken.position,
                                line = postArrowWithToken.line,
                                column = postArrowWithToken.column,
                                code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0120_WITH_AFTER_ARROW,
                                isFatal = true,
                                hints = hint,
                            )
                        } else {
                            val effectRow = if (effectRowResult.effects.isNotEmpty() || effectRowResult.effectAnnotations.isNotEmpty() || effectRowResult.isPolymorphic || effectRowResult.isPerformed) {
                                ParsedEffectRow(
                                    effects = effectRowResult.effects,
                                    effectAnnotations = effectRowResult.effectAnnotations,
                                    isPolymorphic = effectRowResult.isPolymorphic,
                                    isPerformed = effectRowResult.isPerformed,
                                    loc = spanLocTo(startToken, streamAfterWith, file)
                                )
                            } else null

                            val fnLoc = spanLocTo(startToken, streamAfterWith, file)
                            ParseResult.Success(
                                ParsedFunctionType(paramType, returnType.value, effectRow, abilityConstraints, fnLoc),
                                streamAfterWith
                            )
                        }
                    } // else (result is not Failure)
                    }
                    is ParseResult.Failure -> {
                        // Normally a return-type parse failure means the `->` wasn't
                        // ours (it belongs to an enclosing lambda/match) — swallow it
                        // and keep the param type. But a DEFINITIVE limit failure
                        // (nesting too deep / work budget) must propagate: otherwise a
                        // runaway `{}->{}->…->T` gets silently truncated with no error
                        // and a huge type flows on to the typechecker.
                        if (returnType.code == com.mezze.language.parsedast.diagnostics.ErrorCode.P0131_NESTING_TOO_DEEP ||
                            returnType.code == com.mezze.language.parsedast.diagnostics.ErrorCode.P0132_PARSER_LIMIT_EXCEEDED) {
                            result = returnType
                        }
                        break
                    }
                }
            } else {
                break
            }
        }

        result
    }

// Seq type annotation parser
internal val MezzeParser.seqTypeAnnotation: Parser<ParsedTypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        val lbracket = tok(TokenType.LBRACKET)(stream)
        when (lbracket) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(lbracket.remaining)
                val elementType = typeAnnotationParser(stream1)
                when (elementType) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(elementType.remaining)
                        val rbracket = tok(TokenType.RBRACKET)(stream2)
                        when (rbracket) {
                            is ParseResult.Success -> {
                                val loc = spanLoc(lbracket.value, rbracket.value, file)
                                ParseResult.Success(ParsedSeqType(elementType.value, loc), rbracket.remaining)
                            }
                            is ParseResult.Failure -> rbracket
                        }
                    }
                    is ParseResult.Failure -> elementType
                }
            }
            is ParseResult.Failure -> lbracket
        }
    }

// Primary type annotation parser
internal val MezzeParser.primaryTypeAnnotation: Parser<ParsedTypeAnnotation>
    get() = { stream ->
        Parsers.oneOf(
            seqTypeAnnotation,
            parenTypeAnnotation,
            recordTypeAnnotationParser,
            variantTypeAnnotationParser,
            typeApplicationAnnotation,
            identifierTypeAnnotation
        )(stream)
    }

internal val MezzeParser.atomicTypeAnnotation: Parser<ParsedTypeAnnotation>
    get() = { stream ->
        Parsers.oneOf(
            seqTypeAnnotation,
            parenTypeAnnotation,
            recordTypeAnnotationParser,
            identifierTypeAnnotation
        )(stream)
    }

// Parenthesized type annotation parser
internal val MezzeParser.parenTypeAnnotation: Parser<ParsedTypeAnnotation>
    get() = { stream ->
        val lparen = tok(TokenType.LPAREN)(stream)
        when (lparen) {
            is ParseResult.Success -> {
                val typeAnn = typeAnnotationParser(lparen.remaining)
                when (typeAnn) {
                    is ParseResult.Success -> {
                        val rparen = tok(TokenType.RPAREN)(typeAnn.remaining)
                        when (rparen) {
                            is ParseResult.Success -> ParseResult.Success(typeAnn.value, rparen.remaining)
                            is ParseResult.Failure -> rparen
                        }
                    }
                    is ParseResult.Failure -> typeAnn
                }
            }
            is ParseResult.Failure -> lparen
        }
    }

// Record type annotation parser
internal val MezzeParser.recordTypeAnnotationParser: Parser<ParsedTypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        val lbrace = tok(TokenType.LBRACE)(stream)
        when (lbrace) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(lbrace.remaining)
                val entries = sepEndByWithNewlines(
                    recordTypeEntryParser,
                    tok(TokenType.COMMA)
                )(stream1)

                when (entries) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(entries.remaining)

                        if (entries.value.isEmpty() && stream2.current()?.type == TokenType.IDENTIFIER) {
                            val fieldName = stream2.current()?.text ?: "field"
                            val message = "Record type field '$fieldName' is missing a type annotation.\n" +
                                    "Expected format: { $fieldName: Type }"
                            stream2.failure(
                                message,
                                code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0117_RECORD_FIELD_MISSING_TYPE
                            )
                        } else {
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
                                    ParseResult.Success(ParsedRecordType(entries.value, isOpen, loc), rbrace.remaining)
                                }
                                is ParseResult.Failure -> {
                                    val currentToken = stream3.current()
                                    when (currentToken?.type) {
                                        TokenType.IDENTIFIER -> {
                                            val fieldName = currentToken.text
                                            if (fieldName.startsWith("'")) {
                                                stream3.failure(
                                                    typeVarJuxtaposedMessage(fieldName),
                                                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.UNCATEGORIZED
                                                )
                                            } else {
                                                stream3.failure(
                                                    "Record type field '$fieldName' is missing a type annotation (expected `$fieldName: Type`)",
                                                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0117_RECORD_FIELD_MISSING_TYPE
                                                )
                                            }
                                        }
                                        TokenType.COMMA -> stream3.failure("Unexpected comma in record type. Record type fields must have format: { field: Type }")
                                        else -> stream3.failure("Expected '}' to close record type, but got ${tokenGotDescription(currentToken)}. Record type format: { field: Type }")
                                    }
                                }
                            }
                        }
                    }
                    is ParseResult.Failure -> entries
                }
            }
            is ParseResult.Failure -> lbrace
        }
    }

// Variant type annotation parser
internal val MezzeParser.variantTypeAnnotationParser: Parser<ParsedTypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        val pipe1 = tok(TokenType.PIPE)(stream)
        when (pipe1) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(pipe1.remaining)

                val constructors = mutableListOf<ParsedVariantTypeCtor>()
                var currentStream = stream1
                var error: ParseResult.Failure? = null

                while (currentStream.current()?.type == TokenType.IDENTIFIER && error == null) {
                    val constructorName = identifierParser(currentStream)
                    when (constructorName) {
                        is ParseResult.Success -> {
                            val stream2 = skipNewlines(constructorName.remaining)

                            if (stream2.current()?.type == TokenType.LBRACE) {
                                val recordType = recordTypeAnnotationParser(stream2)
                                when (recordType) {
                                    is ParseResult.Success -> {
                                        val ctorLoc = spanLocTo(constructorName.value, recordType.remaining, file)
                                        constructors.add(ParsedVariantTypeCtor(constructorName.value.text, recordType.value, ctorLoc))
                                        currentStream = skipNewlines(recordType.remaining)
                                    }
                                    is ParseResult.Failure -> {
                                        error = recordType
                                    }
                                }
                            } else {
                                val ctorLoc = tokenLoc(constructorName.value, file)
                                constructors.add(ParsedVariantTypeCtor(constructorName.value.text, null, ctorLoc))
                                currentStream = skipNewlines(stream2)
                            }

                            if (error == null) {
                                if (currentStream.current()?.type == TokenType.COMMA) {
                                    currentStream = skipNewlines(currentStream.advance())
                                } else {
                                    break
                                }
                            }
                        }
                        is ParseResult.Failure -> {
                            error = constructorName
                        }
                    }
                }

                if (error != null) {
                    error
                } else {
                    val isOpen = if (currentStream.current()?.type == TokenType.ELLIPSIS) {
                        currentStream = skipNewlines(currentStream.advance())
                        true
                    } else {
                        false
                    }

                    val pipe2 = tok(TokenType.PIPE)(currentStream)
                    when (pipe2) {
                        is ParseResult.Success -> {
                            val loc = spanLoc(pipe1.value, pipe2.value, file)
                            ParseResult.Success(ParsedVariantType(constructors, isOpen, loc), pipe2.remaining)
                        }
                        is ParseResult.Failure -> pipe2
                    }
                }
            }
            is ParseResult.Failure -> pipe1
        }
    }

internal val MezzeParser.simpleTypeArgument: Parser<ParsedTypeAnnotation>
    get() = { stream ->
        Parsers.oneOf(
            variantTypeAnnotationParser,
            seqTypeAnnotation,
            parenTypeAnnotation,
            recordTypeAnnotationParser,
            identifierTypeAnnotation
        )(stream)
    }

// Type application annotation parser
internal val MezzeParser.typeApplicationAnnotation: Parser<ParsedTypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                var currentStream = ident.remaining
                val pathSegments = mutableListOf(ident.value.text)
                var lastToken = ident.value

                while (true) {
                    val doubleColon = tok(TokenType.DOUBLE_COLON)(currentStream)
                    if (doubleColon is ParseResult.Success) {
                        val nextIdent = identifierParser(doubleColon.remaining)
                        if (nextIdent is ParseResult.Success) {
                            pathSegments.add(nextIdent.value.text)
                            lastToken = nextIdent.value
                            currentStream = nextIdent.remaining
                        } else {
                            break
                        }
                    } else {
                        break
                    }
                }

                val isQualified = pathSegments.size > 1
                val typeName = pathSegments.last()
                val modulePath = if (isQualified) pathSegments.dropLast(1) else emptyList()

                if (typeName[0].isUpperCase()) {
                    val args = mutableListOf<ParsedTypeAnnotation>()

                    while (true) {
                        parseTick(currentStream)  // anti-hang: bounded work per iteration
                        val argResult = simpleTypeArgument(currentStream)
                        when (argResult) {
                            is ParseResult.Success -> {
                                args.add(argResult.value)
                                currentStream = argResult.remaining
                            }
                            is ParseResult.Failure -> break
                        }
                    }

                    val loc = spanLocTo(ident.value, currentStream, file)
                    if (isQualified) {
                        ParseResult.Success(ParsedQualifiedType(modulePath, typeName, args, loc), currentStream)
                    } else if (args.isNotEmpty()) {
                        ParseResult.Success(ParsedTypeApp(typeName, args, loc), currentStream)
                    } else {
                        ParseResult.Success(ParsedNamedType(typeName, tokenLoc(ident.value, file)), currentStream)
                    }
                } else {
                    ParseResult.Success(ParsedNamedType(typeName, tokenLoc(ident.value, file)), currentStream)
                }
            }
            is ParseResult.Failure -> ident
        }
    }

// Identifier type annotation parser
internal val MezzeParser.identifierTypeAnnotation: Parser<ParsedTypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> ParseResult.Success(
                ParsedNamedType(ident.value.text, tokenLoc(ident.value, file)),
                ident.remaining
            )
            is ParseResult.Failure -> ident
        }
    }
