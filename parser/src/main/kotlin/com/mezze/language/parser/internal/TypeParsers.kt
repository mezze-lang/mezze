package com.mezze.language.parser.internal

import com.mezze.language.parsedast.*
import com.mezze.language.parser.*

import com.mezze.language.parser.internal.lib.*
/**
 * Type annotation parsers for all type constructs.
 *
 * All parsers return ParsedTypeAnnotation (from parsedast package).
 * All functions are internal - only used within the parser module.
 */

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
        functionTypeAnnotation(stream)
    }

// Function type annotation parser
internal val MezzeParser.functionTypeAnnotation: Parser<ParsedTypeAnnotation>
    get() = { stream ->
        val file = currentFile ?: "<unknown>"
        var result: ParseResult<ParsedTypeAnnotation> = primaryTypeAnnotation(stream)

        while (result is ParseResult.Success) {
            val startToken = stream.current() ?: break
            var currentStream = result.remaining

            // Check for 'with' keyword BEFORE the arrow (ability constraints on parameter)
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
                    if (abilityStream.current()?.type == TokenType.HAVING) {
                        abilityStream = skipNewlines(abilityStream.advance())
                        while (true) {
                            val typeVarResult = identifierParser(abilityStream)
                            if (typeVarResult !is ParseResult.Success) break
                            val typeVarName = typeVarResult.value.text
                            if (!typeVarName.startsWith("'")) break
                            abilityStream = skipNewlines(typeVarResult.remaining)
                            if (abilityStream.current()?.type != TokenType.ASSIGN) break
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

            val arrow = currentStream.current()

            if (arrow?.type == TokenType.ARROW) {
                val arrowStream = currentStream.advance()
                val returnType = functionTypeAnnotation(arrowStream)

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

                                    while (true) {
                                        val effectNameResult = parseQualifiedName(current)
                                        if (effectNameResult !is ParseResult.Success) break
                                        val effectName = effectNameResult.value
                                        val effectStart = current.current()
                                        current = skipNewlines(effectNameResult.remaining)

                                        if (current.current()?.type == TokenType.HAVING) {
                                            current = skipNewlines(current.advance())
                                            val associatedTypes = mutableMapOf<String, ParsedTypeAnnotation>()

                                            while (true) {
                                                val typeVarResult = identifierParser(current)
                                                if (typeVarResult !is ParseResult.Success) break
                                                val typeVarName = typeVarResult.value.text
                                                if (!typeVarName.startsWith("'")) break
                                                current = skipNewlines(typeVarResult.remaining)
                                                if (current.current()?.type != TokenType.ASSIGN) break
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

                                            val annLoc = if (effectStart != null) spanLocTo(effectStart, current, file) else streamLoc(current, file)
                                            effectAnnotations.add(ParsedEffectAnnotation(effectName, associatedTypes, annLoc))
                                        } else {
                                            simpleEffectNames.add(effectName)
                                        }

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

                        // Also check for 'with' keyword AFTER the return type
                        var streamAfterWith = effectRowResult.stream
                        val streamBeforeWithAfter = skipNewlines(effectRowResult.stream)
                        if (streamBeforeWithAfter.current()?.type == TokenType.WITH) {
                            var abilityStreamAfter = skipNewlines(streamBeforeWithAfter.advance())

                            while (true) {
                                val abilityNameResult = parseQualifiedName(abilityStreamAfter)
                                if (abilityNameResult !is ParseResult.Success) break
                                val abilityName = abilityNameResult.value
                                val constraintStart = abilityStreamAfter.current()
                                abilityStreamAfter = skipNewlines(abilityNameResult.remaining)

                                val typeArgs = mutableListOf<ParsedTypeAnnotation>()
                                while (true) {
                                    val typeResult = atomicTypeAnnotation(abilityStreamAfter)
                                    when (typeResult) {
                                        is ParseResult.Success -> {
                                            typeArgs.add(typeResult.value)
                                            abilityStreamAfter = skipNewlines(typeResult.remaining)
                                            val nextToken = abilityStreamAfter.current()
                                            if (nextToken?.type == TokenType.ASSIGN ||
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

                                val associatedTypeBindingsAfter = mutableMapOf<String, ParsedTypeAnnotation>()
                                if (abilityStreamAfter.current()?.type == TokenType.HAVING) {
                                    abilityStreamAfter = skipNewlines(abilityStreamAfter.advance())
                                    while (true) {
                                        val typeVarResult = identifierParser(abilityStreamAfter)
                                        if (typeVarResult !is ParseResult.Success) break
                                        val typeVarName = typeVarResult.value.text
                                        if (!typeVarName.startsWith("'")) break
                                        abilityStreamAfter = skipNewlines(typeVarResult.remaining)
                                        if (abilityStreamAfter.current()?.type != TokenType.ASSIGN) break
                                        abilityStreamAfter = skipNewlines(abilityStreamAfter.advance())
                                        val typeResult = atomicTypeAnnotation(abilityStreamAfter)
                                        if (typeResult !is ParseResult.Success) break
                                        associatedTypeBindingsAfter[typeVarName] = typeResult.value
                                        abilityStreamAfter = skipNewlines(typeResult.remaining)
                                        if (abilityStreamAfter.current()?.type == TokenType.COMMA) {
                                            val afterComma = skipNewlines(abilityStreamAfter.advance())
                                            val nextToken = afterComma.current()
                                            if (nextToken?.type == TokenType.IDENTIFIER && nextToken.text.startsWith("'")) {
                                                abilityStreamAfter = afterComma
                                                continue
                                            } else {
                                                break
                                            }
                                        } else {
                                            break
                                        }
                                    }
                                }

                                val cLoc = if (constraintStart != null) spanLocTo(constraintStart, abilityStreamAfter, file) else streamLoc(abilityStreamAfter, file)
                                abilityConstraints.add(ParsedFnAbilityConstraint(abilityName, typeArgs, associatedTypeBindingsAfter, cLoc))

                                if (abilityStreamAfter.current()?.type == TokenType.COMMA) {
                                    abilityStreamAfter = skipNewlines(abilityStreamAfter.advance())
                                } else {
                                    break
                                }
                            }

                            streamAfterWith = abilityStreamAfter
                        }

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
                            ParsedFunctionType(result.value, returnType.value, effectRow, abilityConstraints, fnLoc),
                            streamAfterWith
                        )
                    }
                    is ParseResult.Failure -> break
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

/**
 * Parse atomic type annotations (identifiers only, no type applications).
 */
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

// Simple type argument parser (for type applications)
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
