package com.mezze.language.parser.internal

import com.mezze.language.parsedast.*
import com.mezze.language.ast.SyntaxMetadata
import com.mezze.language.parser.*
import com.mezze.language.parser.internal.lib.*


/**
 * Guard: a declaration keyword (`let`, `type`, `effect`, …) must be followed by
 * the name it introduces ON THE SAME LINE. `keywordRemaining` is the stream
 * right after the keyword token — if it begins with a NEWLINE, the name has been
 * pushed to a later line, which is almost always a typo (a stray keyword silently
 * absorbing the next line). Returns a Failure to reject; null if it's fine.
 *
 * The rest of Mezze's newline-insensitivity (wrapping expressions, `=` on the
 * next line) is untouched — this only forbids the keyword↔name gap.
 */
internal fun MezzeParser.declNameOnNewLineFailure(
    keyword: String,
    keywordRemaining: TokenStream,
): ParseResult.Failure? {
    if (keywordRemaining.current()?.type != TokenType.NEWLINE) return null
    return keywordRemaining.failure(
        "The name must be on the same line as `$keyword`.",
        code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0126_DECL_NAME_ON_NEW_LINE,
        hints = listOf("write the declaration on one line, e.g. `$keyword name …`"),
    )
}

internal fun <T> MezzeParser.parseCommaSeparatedOps(
    stream: TokenStream,
    opParser: Parser<T>,
    missingCommaMessage: String,
    // Attach a leading `## doc` block (collected just above the operation) to
    // the parsed op. Without this, member doc comments in ability/effect/impl/
    // performer blocks were silently discarded by the comment-skipping
    // `skipNewlines` at each op boundary — reformatting an .mz file dropped
    // every per-operation doc. Ops that carry no doc field pass `null` through.
    attachDoc: (T, String?) -> T = { op, _ -> op }
): ParseResult<Pair<List<T>, TokenStream>> {
    val operations = mutableListOf<T>()
    var currentStream = stream

    // A doc comment may lead the very first op too (the caller has already
    // skipped the `{` and its trailing newlines with plain `skipNewlines`,
    // but a `##` right after that survives here for the first op only if the
    // caller didn't eat it — so collect defensively before the first parse).
    val (afterFirstAnn, firstAnn) = skipNewlinesCollectAnnotations(currentStream)
    currentStream = afterFirstAnn

    when (val firstOp = opParser(currentStream)) {
        is ParseResult.Success -> {
            operations.add(attachDoc(firstOp.value, firstAnn.docComment))
            currentStream = skipNewlines(firstOp.remaining)

            while (true) {
                when (val comma = tok(TokenType.COMMA)(currentStream)) {
                    is ParseResult.Success -> {
                        // Collect a leading doc block for the NEXT op instead of
                        // discarding it. `skipNewlines` would swallow the `##`.
                        val (afterAnn, ann) = skipNewlinesCollectAnnotations(comma.remaining)
                        currentStream = afterAnn
                        if (currentStream.current()?.type == TokenType.RBRACE) {
                            break
                        }
                        when (val op = opParser(currentStream)) {
                            is ParseResult.Success -> {
                                operations.add(attachDoc(op.value, ann.docComment))
                                currentStream = skipNewlines(op.remaining)
                            }
                            is ParseResult.Failure -> {
                                return op as ParseResult.Failure
                            }
                        }
                    }
                    is ParseResult.Failure -> {
                        val next = currentStream.current()
                        if (next?.type == TokenType.IDENTIFIER) {
                            return currentStream.failure(missingCommaMessage)
                        }
                        break
                    }
                }
            }
        }
        is ParseResult.Failure -> {
        }
    }
    return ParseResult.Success(Pair(operations, currentStream), currentStream)
}

internal fun MezzeParser.parseLetBinding(stream: TokenStream): ParseResult<ParsedDecl> {
    return parseLetBindingWithVisibility(stream, Visibility.PRIVATE)
}


internal fun MezzeParser.parseLetBindingWithVisibility(stream: TokenStream, visibility: Visibility): ParseResult<ParsedDecl> {
        //    or: let <pattern> = <expression>
        // Where <pattern> can be:
        //   - identifier (simple binding): let x = ...
        //   - record pattern (destructuring): let { x, y } = ...
        //   - wildcard: let _ = ... (discard value)
        val letToken = tok(TokenType.LET)(stream)
        return when (letToken) {
            is ParseResult.Success -> {
                val file = currentFile ?: "<unknown>"
                declNameOnNewLineFailure("let", letToken.remaining)?.let { return it }
                val stream1 = skipNewlines(letToken.remaining)

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
                                    is ParseResult.Success -> {
                                        // Span the entire decl: `let name = expr` covers from `let`
                                        // through the RHS so workspace-symbol clicks land on the
                                        // whole binding, and hover/find-decl can scope-walk inside.
                                        val loc = spanLocTo(letToken.value, expr.remaining, file)
                                        ParseResult.Success(
                                            ParsedLetDecl(
                                                pattern = pattern,
                                                typeAnnotation = typeAnnotation,
                                                value = expr.value,
                                                visibility = visibility,
                                                loc = loc
                                            ),
                                            expr.remaining
                                        )
                                    }
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

internal fun MezzeParser.parseConstrainedLambda(stream: TokenStream): ParseResult<ParsedDecl> {
        // Only try if we see a { (record type parameter)
        if (stream.current()?.type != TokenType.LBRACE) {
            return stream.failure("Not a constrained lambda")
        }

        val typeAnn = functionTypeAnnotation(stream)
        if (typeAnn !is ParseResult.Success) {
            return stream.failure("Expected function type annotation")
        }

        val stream1 = skipNewlines(typeAnn.remaining)
        val typeAnnotation = typeAnn.value

        if (typeAnnotation !is ParsedFunctionType) {
            return stream.failure("Expected function type annotation")
        }

        // The functionTypeAnnotation parser already parsed these
        if (typeAnnotation.abilityConstraints.isEmpty()) {
            return stream.failure("No ability constraints")
        }

        // Expect '='
        val assignToken = tok(TokenType.ASSIGN)(stream1)
        when (assignToken) {
            is ParseResult.Failure -> return assignToken
            is ParseResult.Success -> {
                val stream2 = skipNewlines(assignToken.remaining)

                val lambda = expression(stream2)
                when (lambda) {
                    is ParseResult.Failure -> return lambda
                    is ParseResult.Success -> {
                        // Check that it's actually a lambda
                        val lambdaExpr = lambda.value
                        if (lambdaExpr !is ParsedLambda) {
                            return stream2.failure("Expected lambda expression like {x} -> x + 1 after '='")
                        }

                        val constrainedLambda = lambdaExpr.copy(
                            returnType = typeAnnotation.returnType,
                            abilityConstraints = typeAnnotation.abilityConstraints
                        )

                        val file = currentFile ?: "<unknown>"
                        val loc = spanLocTo(assignToken.value, lambda.remaining, file)
                        return ParseResult.Success(
                            ParsedTopLevelExpr(constrainedLambda, loc = loc),
                            lambda.remaining
                        )
                    }
                }
            }
        }
    }


internal fun MezzeParser.parseEffectDeclaration(stream: TokenStream): ParseResult<ParsedDecl> {
        val effectToken = tok(TokenType.EFFECT)(stream)
        return when (effectToken) {
            is ParseResult.Success -> {
                declNameOnNewLineFailure("effect", effectToken.remaining)?.let { return it }
                val stream1 = skipNewlines(effectToken.remaining)
                val name = identifierParser(stream1)

                when (name) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(name.remaining)

                        val associatedTypes = mutableListOf<String>()
                        val streamAfterHaving = when (val havingToken = tok(TokenType.HAVING)(stream2)) {
                            is ParseResult.Success -> {
                                val stream2a = skipNewlines(havingToken.remaining)
                                var currentStream = stream2a

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

                        val whereToken = tok(TokenType.WHERE)(streamAfterHaving)
                        when (whereToken) {
                            is ParseResult.Success -> {
                                val stream3 = skipNewlines(whereToken.remaining)
                                val lbrace = tok(TokenType.LBRACE)(stream3)

                                when (lbrace) {
                                    is ParseResult.Success -> {
                                        // skipNewlinesOnly so a `## doc` leading the
                                        // FIRST op survives to parseCommaSeparatedOps.
                                        val stream4 = skipNewlinesOnly(lbrace.remaining)
                                        val opsResult = parseCommaSeparatedOps(
                                            stream4, effectOperationParser,
                                            "Missing ',' between effect operations. Effect operations must be comma-separated.",
                                            attachDoc = { op, doc -> if (doc != null) op.copy(doc = doc) else op }
                                        )
                                        when (opsResult) {
                                            is ParseResult.Failure -> return opsResult
                                            is ParseResult.Success -> {}
                                        }
                                        val (operations, opsStream) = (opsResult as ParseResult.Success).value

                                        val stream5 = skipNewlines(opsStream)
                                        val rbrace = tok(TokenType.RBRACE)(stream5)

                                        when (rbrace) {
                                            is ParseResult.Success -> {
                                                val file = currentFile ?: "<unknown>"
                                                val loc = spanLoc(effectToken.value, rbrace.value, file)
                                                ParseResult.Success(
                                                    ParsedEffectDecl(
                                                        name = name.value.text,
                                                        operations = operations,
                                                        associatedTypes = associatedTypes,
                                                        loc = loc
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

internal val MezzeParser.effectOperationParser: Parser<ParsedEffectOp> get() = { stream ->
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
                                val funcType = functionType.value as? ParsedFunctionType
                                if (funcType != null) {
                                    val effectList = funcType.effects?.effects ?: emptyList()
                                    val file = currentFile ?: "<unknown>"
                                    val loc = spanLoc(name.value, colon.value, file)
                                    ParseResult.Success(
                                        ParsedEffectOp(
                                            name.value.text,
                                            funcType.paramType,
                                            funcType.returnType,
                                            effectList,
                                            funcType.abilityConstraints,
                                            null,
                                            loc
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


internal fun MezzeParser.parseTypeDeclaration(stream: TokenStream, visibility: Visibility): ParseResult<ParsedDecl> {
        val typeToken = tok(TokenType.TYPE)(stream)
        return when (typeToken) {
            is ParseResult.Success -> {
                declNameOnNewLineFailure("type", typeToken.remaining)?.let { return it }
                val stream1 = skipNewlines(typeToken.remaining)
                val name = identifierParser(stream1)

                when (name) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(name.remaining)

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
                                    is ParseResult.Success -> {
                                        val file = currentFile ?: "<unknown>"
                                        val loc = spanLocTo(typeToken.value, typeDef.remaining, file)
                                        ParseResult.Success(
                                            ParsedTypeDecl(
                                                name = name.value.text,
                                                visibility = visibility,
                                                typeParams = typeParams,
                                                definition = typeDef.value,
                                                loc = loc
                                            ),
                                            typeDef.remaining
                                        )
                                    }
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

internal fun MezzeParser.parseNewtypeDeclaration(stream: TokenStream, visibility: Visibility): ParseResult<ParsedDecl> {
        val newtypeToken = tok(TokenType.NEWTYPE)(stream)
        return when (newtypeToken) {
            is ParseResult.Success -> {
                declNameOnNewLineFailure("newtype", newtypeToken.remaining)?.let { return it }
                val stream1 = skipNewlines(newtypeToken.remaining)
                val typeName = identifierParser(stream1)

                when (typeName) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(typeName.remaining)

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
                                val wrappedType = typeAnnotationParser(stream4)

                                when (wrappedType) {
                                    is ParseResult.Success -> {
                                        // Reject the OLD redundant-constructor form
                                        // `newtype UserId = UserId Int` — the leading
                                        // name is the newtype's own name repeated,
                                        // which is a legacy syntax we no longer accept.
                                        // Modern form is `newtype UserId = Int`.
                                        val declName = typeName.value.text
                                        val innerName: String? = when (val w = wrappedType.value) {
                                            is ParsedNamedType -> if (w.name == declName) declName else null
                                            is ParsedTypeApp -> if (w.constructor == declName) declName else null
                                            else -> null
                                        }
                                        if (innerName != null) {
                                            stream4.failure(
                                                "Old-syntax `newtype $declName = $declName …` is no longer accepted — " +
                                                    "the constructor name is now inferred from the declaration name. " +
                                                    "Rewrite as `newtype $declName = <wrapped-type>` (drop the leading `$declName`)."
                                            )
                                        } else {
                                            val file = currentFile ?: "<unknown>"
                                            val loc = spanLocTo(newtypeToken.value, wrappedType.remaining, file)
                                            val defLoc = spanLocTo(assign.value, wrappedType.remaining, file)
                                            ParseResult.Success(
                                                ParsedTypeDecl(
                                                    name = declName,
                                                    visibility = visibility,
                                                    typeParams = typeParams,
                                                    definition = ParsedNewtypeDef(
                                                        constructor = declName,
                                                        wrappedType = wrappedType.value,
                                                        loc = defLoc
                                                    ),
                                                    loc = loc
                                                ),
                                                wrappedType.remaining
                                            )
                                        }
                                    }
                                    is ParseResult.Failure -> wrappedType
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

internal val MezzeParser.typeDefinitionParser: Parser<ParsedTypeDef> get() = { stream ->
        val variantResult = variantTypeParser(stream)
        when (variantResult) {
            is ParseResult.Success -> variantResult
            is ParseResult.Failure -> {
                // If the error indicates invalid variant syntax, don't try record parsing
                // This catches cases like: | Constructor1 | Constructor2 (missing commas)
                if (variantResult.message.startsWith("Invalid variant syntax:")) {
                    variantResult
                } else {
                    val recordResult = recordTypeParser(stream)
                    // If record parsing succeeded but the next token is `->`,
                    // this was really the parameter of a function-typed alias
                    // (`type Producer 'a = {} -> 'a`). Discard the record
                    // parse and fall through to the alias annotation parser.
                    val recordConsumedByFuncArrow = recordResult is ParseResult.Success &&
                        recordResult.remaining.current()?.type == TokenType.ARROW
                    if (recordResult is ParseResult.Success && !recordConsumedByFuncArrow) {
                        recordResult
                    } else {
                        // Fall through to a TRUE type alias: `type MyInt = Int`,
                        // `type Ids = Seq Int`, `type Producer 'a = {} -> 'a`.
                        val firstTok = stream.current()
                        if (firstTok == null) recordResult
                        else {
                            val aliasResult = typeAnnotationParser(stream)
                            when (aliasResult) {
                                is ParseResult.Success -> {
                                    val file = currentFile ?: "<unknown>"
                                    val loc = spanLocTo(firstTok, aliasResult.remaining, file)
                                    ParseResult.Success(
                                        ParsedTypeAliasDef(aliasResult.value, loc),
                                        aliasResult.remaining
                                    )
                                }
                                // A definitive limit failure (nesting too deep / work
                                // budget) must propagate, not be masked by the earlier
                                // record attempt's shallower error.
                                is ParseResult.Failure ->
                                    if (aliasResult.code == com.mezze.language.parsedast.diagnostics.ErrorCode.P0131_NESTING_TOO_DEEP ||
                                        aliasResult.code == com.mezze.language.parsedast.diagnostics.ErrorCode.P0132_PARSER_LIMIT_EXCEEDED)
                                        aliasResult
                                    else recordResult
                            }
                        }
                    }
                }
            }
        }
    }

internal val MezzeParser.variantTypeParser: Parser<ParsedTypeDef> get() = { stream ->
        val pipe1 = tok(TokenType.PIPE)(stream)
        when (pipe1) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(pipe1.remaining)
                val file = currentFile ?: "<unknown>"

                val pipe2Check = tok(TokenType.PIPE)(stream1)
                if (pipe2Check is ParseResult.Success) {
                    val loc = spanLoc(pipe1.value, pipe2Check.value, file)
                    ParseResult.Success(
                        ParsedVariantDef(emptyList(), false, loc),
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
                                        afterPipe.failure(
                                            "Invalid variant syntax: found '${nextToken.text}' after closing '|'. " +
                                            "Variant constructors must be comma-separated within | ... |. " +
                                            "Example: type Option 'a = | Some { val: 'a }, None |",
                                            code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0111_INVALID_VARIANT_SYNTAX,
                                            hints = listOf("comma-separate constructors inside | ... |")
                                        )
                                    } else {
                                        val loc = spanLoc(pipe1.value, pipe2.value, file)
                                        ParseResult.Success(
                                            ParsedVariantDef(constructors.value, isOpen, loc),
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

internal val MezzeParser.variantConstructorParser: Parser<ParsedVariantCtorDef> get() = { stream ->
        val name = identifierParser(stream)
        when (name) {
            is ParseResult.Success -> {
                val file = currentFile ?: "<unknown>"
                val stream1 = skipNewlines(name.remaining)
                // Try record type first (for backwards compatibility), then any type annotation
                val dataType = Parsers.oneOf(
                    recordTypeAnnotationParser,
                    identifierTypeAnnotation  // Allows bare type variables like 'a
                )(stream1)

                when (dataType) {
                    is ParseResult.Success -> {
                        val loc = spanLocTo(name.value, dataType.remaining, file)
                        ParseResult.Success(
                            ParsedVariantCtorDef(name.value.text, dataType.value, loc = loc),
                            dataType.remaining
                        )
                    }
                    is ParseResult.Failure -> {
                        val loc = tokenLoc(name.value, file)
                        ParseResult.Success(
                            ParsedVariantCtorDef(name.value.text, null, loc = loc),
                            stream1
                        )
                    }
                }
            }
            is ParseResult.Failure -> name
        }
    }

internal val MezzeParser.recordTypeParser: Parser<ParsedTypeDef> get() = { stream ->
        val file = currentFile ?: "<unknown>"
        val recordType = recordTypeAnnotationParser(stream)
        when (recordType) {
            is ParseResult.Success -> {
                val value = recordType.value
                if (value is ParsedRecordType) {
                    val fields = value.entries.map { entry ->
                        ParsedRecordFieldDef(entry.name, entry.type, loc = entry.loc)
                    }
                    ParseResult.Success(
                        ParsedRecordDef(fields, value.isOpen, value.loc),
                        recordType.remaining
                    )
                } else {
                    stream.failure("Expected record type like { field: Type }")
                }
            }
            is ParseResult.Failure -> recordType
        }
    }


internal fun MezzeParser.parseImplDeclaration(stream: TokenStream): ParseResult<ParsedDecl> {
        val implToken = tok(TokenType.IMPL)(stream)
        return when (implToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(implToken.remaining)

                val effectRefResult = parseQualifiedRef(stream1)

                when (effectRefResult) {
                    is ParseResult.Success -> {
                        val effectRef = effectRefResult.value
                        val currentStream = effectRefResult.remaining
                        val stream2 = skipNewlines(currentStream)
                        val forToken = tok(TokenType.FOR)(stream2)

                        when (forToken) {
                            is ParseResult.Success -> {
                                val stream3 = skipNewlines(forToken.remaining)
                                val performerName = identifierParser(stream3)

                                when (performerName) {
                                    is ParseResult.Success -> {
                                        val stream4 = skipNewlines(performerName.remaining)

                                        val performerParamsResult = performerParamsParser(stream4)
                                        val (performerParams, streamAfterParams) = when (performerParamsResult) {
                                            is ParseResult.Success -> Pair(performerParamsResult.value, performerParamsResult.remaining)
                                            is ParseResult.Failure -> Pair(emptyList(), stream4)
                                        }

                                        val stream5 = skipNewlines(streamAfterParams)

                                        val effectNameForErrors = if (effectRef.isQualified) {
                                            "${effectRef.qualifier!!}::${effectRef.localName}"
                                        } else {
                                            effectRef.localName
                                        }
                                        val bindingsResult = parseAssociatedTypeBindings(stream5, effectNameForErrors, "impl")
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
                                                        // skipNewlinesOnly so a `## doc` above the FIRST op survives.
                                                        val stream7 = skipNewlinesOnly(lbrace.remaining)
                                                        val perfOpsResult = parseCommaSeparatedOps(
                                                            stream7, performerOperationParser,
                                                            "Missing ',' between effect impl operations. Operations must be comma-separated.",
                                                            attachDoc = { op, doc -> if (doc != null) op.copy(doc = doc) else op }
                                                        )
                                                        when (perfOpsResult) {
                                                            is ParseResult.Failure -> return perfOpsResult
                                                            is ParseResult.Success -> {}
                                                        }
                                                        val (operations, perfOpsStream) = (perfOpsResult as ParseResult.Success).value

                                                        val stream8 = skipNewlines(perfOpsStream)
                                                        val rbrace = tok(TokenType.RBRACE)(stream8)

                                                        when (rbrace) {
                                                            is ParseResult.Success -> {
                                                                val file = currentFile ?: "<unknown>"
                                                                val loc = spanLoc(implToken.value, rbrace.value, file)
                                                                ParseResult.Success(
                                                                    ParsedPerformerDecl(
                                                                        effect = effectRef,
                                                                        performerName = performerName.value.text,
                                                                        params = performerParams,
                                                                        associatedTypeBindings = associatedTypeBindings,
                                                                        operations = operations,
                                                                        loc = loc
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
                    is ParseResult.Failure -> effectRefResult
                }
            }
            is ParseResult.Failure -> implToken
        }
    }

internal val MezzeParser.performerParamsParser: Parser<List<ParsedPerformerParam>> get() = { stream ->
        val lbrace = tok(TokenType.LBRACE)(stream)
        when (lbrace) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(lbrace.remaining)
                // sepEndBy (not sepBy): a TRAILING COMMA before `}` is allowed, so a
                // handler's inline field list `impl E for H { a: Int, b: Int, }` matches
                // record literals/types (which already permit the trailing comma).
                val params = sepEndByWithNewlines(
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

internal val MezzeParser.performerParamParser: Parser<ParsedPerformerParam> get() = { stream ->
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
                            is ParseResult.Success -> {
                                val file = currentFile ?: "<unknown>"
                                val loc = spanLocTo(name.value, typeAnn.remaining, file)
                                ParseResult.Success(
                                    ParsedPerformerParam(name.value.text, typeAnn.value, loc),
                                    typeAnn.remaining
                                )
                            }
                            is ParseResult.Failure -> typeAnn
                        }
                    }
                    is ParseResult.Failure -> colon
                }
            }
            is ParseResult.Failure -> name
        }
    }

/** Local destructuring helper for [performerOperationParser]. Kotlin's built-in
 *  destructuring only supports up to 5 elements via componentN; a small typed
 *  holder keeps the assignment self-documenting.
 */
private data class PerfOpTypeParts(
    val paramType: ParsedTypeAnnotation,
    val returnType: ParsedTypeAnnotation,
    val effects: ParsedEffectRow?,
    val abilityConstraints: List<ParsedFnAbilityConstraint>,
)

internal val MezzeParser.performerOperationParser: Parser<ParsedPerformerOp> get() = { stream ->
        val name = identifierParser(stream)
        when (name) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(name.remaining)
                val colon = tok(TokenType.COLON)(stream1)

                when (colon) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(colon.remaining)
                        val functionType = typeAnnotationParser(stream2)

                        when (functionType) {
                            is ParseResult.Success -> {
                                // Bug B (#86): capture `with <Ability>` constraints so
                                // ability-impl operations reclassified from performers
                                // (`impl ToJson for 'a where { json: … with Encode … -> Json = … }`)
                                // don't lose their method-level `with` clauses.
                                val (paramType, returnType, effects, abilityConstraints) = when (val ft = functionType.value) {
                                    is ParsedFunctionType -> PerfOpTypeParts(ft.paramType, ft.returnType, ft.effects, ft.abilityConstraints)
                                    else -> {
                                        // If it's not a function type, treat it as param type with Unit return
                                        val file = currentFile ?: "<unknown>"
                                        val unitLoc = tokenLoc(name.value, file)
                                        PerfOpTypeParts(ft, ParsedNamedType("Unit", unitLoc), null, emptyList())
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
                                            is ParseResult.Success -> {
                                                val file = currentFile ?: "<unknown>"
                                                val loc = spanLocTo(name.value, impl.remaining, file)
                                                ParseResult.Success(
                                                    ParsedPerformerOp(
                                                        name = name.value.text,
                                                        paramType = paramType,
                                                        returnType = returnType,
                                                        implementation = impl.value,
                                                        effects = effects,  // Now preserving effect annotations
                                                        abilityConstraints = abilityConstraints,
                                                        loc = loc
                                                    ),
                                                    impl.remaining
                                                )
                                            }
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
                        val assign = tok(TokenType.ASSIGN)(stream1)
                        when (assign) {
                            is ParseResult.Success -> {
                                val stream2 = skipNewlines(assign.remaining)
                                val impl = expression(stream2)

                                when (impl) {
                                    is ParseResult.Success -> {
                                        val file = currentFile ?: "<unknown>"
                                        val loc = spanLocTo(name.value, impl.remaining, file)
                                        ParseResult.Success(
                                            ParsedPerformerOp(
                                                name = name.value.text,
                                                paramType = null,
                                                returnType = null,
                                                implementation = impl.value,
                                                effects = null,
                                                loc = loc
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


internal fun MezzeParser.parseAbilityDeclaration(stream: TokenStream): ParseResult<ParsedDecl> {
        val abilityToken = tok(TokenType.ABILITY)(stream)
        return when (abilityToken) {
            is ParseResult.Success -> {
                declNameOnNewLineFailure("ability", abilityToken.remaining)?.let { return it }
                val stream1 = skipNewlines(abilityToken.remaining)
                val name = identifierParser(stream1)

                when (name) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(name.remaining)

                        val genericParams = mutableListOf<String>()
                        var preForStream = stream2
                        while (true) {
                            val typeVar = preForStream.current()
                            if (typeVar?.type == TokenType.IDENTIFIER && typeVar.text.startsWith("'")) {
                                genericParams.add(typeVar.text)
                                preForStream = preForStream.advance()
                                preForStream = skipNewlines(preForStream)
                            } else {
                                break
                            }
                        }

                        // Require 'for' keyword before receiver type parameter
                        val forToken = tok(TokenType.FOR)(preForStream)
                        if (forToken is ParseResult.Failure) {
                            return preForStream.failure(
                                "Expected 'for' keyword in ability declaration. " +
                                "Syntax: ability ${name.value.text} for 'a where { ... }"
                            )
                        }
                        forToken as ParseResult.Success
                        val streamAfterFor = skipNewlines(forToken.remaining)

                        val receiverVar = streamAfterFor.current()
                        if (receiverVar?.type != TokenType.IDENTIFIER || !receiverVar.text.startsWith("'")) {
                            return streamAfterFor.failure(
                                "Expected type variable after 'for' keyword (e.g., 'a). " +
                                "Syntax: ability ${name.value.text} for 'a where { ... }"
                            )
                        }
                        val receiverTypeParam = receiverVar.text
                        var currentStream = skipNewlines(streamAfterFor.advance())

                        // Combine: all type params = generics + receiver (receiver last for backward compat)
                        val typeParams = genericParams + listOf(receiverTypeParam)

                        val associatedTypes = mutableListOf<String>()
                        val streamAfterHaving = when (val havingToken = tok(TokenType.HAVING)(currentStream)) {
                            is ParseResult.Success -> {
                                val streamH = skipNewlines(havingToken.remaining)
                                var havingStream = streamH

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
                                                                "Example: ability ${name.value.text} 'iter having 'item, 'iter where { ... }"
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

                        // Ability-level constraints aren't supported. The two surface
                        // forms users can mistakenly write here are:
                        //
                        //   1. `ability A for 'a with B 'a where { ... }`  — old "deps" form
                        //   2. `ability A for 'a where 'a: B where { ... }` — Rust/Haskell style
                        //
                        // Both used to parse and then get rejected later by the
                        // typechecker (V0410). Catch them at parse time so the error
                        // surfaces with a precise loc and a worked example of the
                        // canonical per-method `with` form. Audit task #212.
                        val stream3 = skipNewlines(streamAfterHaving)
                        val withToken = stream3.current()
                        if (withToken?.type == TokenType.WITH) {
                            return ParseResult.Failure(
                                message = "Ability '${name.value.text}' has an ability-level 'with' constraint, " +
                                    "but constraints belong on individual operations, not on the ability.",
                                position = withToken.position,
                                line = withToken.line,
                                column = withToken.column,
                                code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0119_ABILITY_LEVEL_CONSTRAINT,
                                isFatal = true,
                                hints = listOf(
                                    "move the constraint onto the operation(s) that need it, e.g.:",
                                    "    ability ${name.value.text} for $receiverTypeParam where {",
                                    "        operation_name: { it: $receiverTypeParam, ... } with <Ability> $receiverTypeParam -> ...",
                                    "    }",
                                ),
                            )
                        }

                        val streamBeforeWhere = stream3
                        val whereToken = tok(TokenType.WHERE)(streamBeforeWhere)

                        // Rust/Haskell-style bound: `ability A for 'a where 'a: B`.
                        // After 'where', if the next token is a type variable
                        // followed by ':', it's the wrong-syntax form (the
                        // canonical 'where' immediately precedes a '{' opening
                        // the operations block).
                        if (whereToken is ParseResult.Success) {
                            val afterWhere = skipNewlines(whereToken.remaining)
                            val maybeTypeVar = afterWhere.current()
                            if (maybeTypeVar?.type == TokenType.IDENTIFIER && maybeTypeVar.text.startsWith("'")) {
                                val afterTypeVar = skipNewlines(afterWhere.advance())
                                if (afterTypeVar.current()?.type == TokenType.COLON) {
                                    return ParseResult.Failure(
                                        message = "Ability '${name.value.text}' uses a `where ${maybeTypeVar.text}: …` bound, " +
                                            "but Mezze places constraints on individual operations via `with`, not at the ability level.",
                                        position = maybeTypeVar.position,
                                        line = maybeTypeVar.line,
                                        column = maybeTypeVar.column,
                                        code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0119_ABILITY_LEVEL_CONSTRAINT,
                                        isFatal = true,
                                        hints = listOf(
                                            "move the constraint onto the operation(s) that need it, e.g.:",
                                            "    ability ${name.value.text} for $receiverTypeParam where {",
                                            "        operation_name: { it: $receiverTypeParam, ... } with <Ability> $receiverTypeParam -> ...",
                                            "    }",
                                        ),
                                    )
                                }
                            }
                        }

                        when (whereToken) {
                            is ParseResult.Success -> {
                                val stream4 = skipNewlines(whereToken.remaining)
                                val lbrace = tok(TokenType.LBRACE)(stream4)

                                when (lbrace) {
                                    is ParseResult.Success -> {
                                        // skipNewlinesOnly so a `## doc` above the FIRST op survives.
                                        val stream5 = skipNewlinesOnly(lbrace.remaining)
                                        val abilityOpsResult = parseCommaSeparatedOps(
                                            stream5, abilityOperationParser,
                                            "Missing ',' between ability methods. Ability methods must be comma-separated.",
                                            attachDoc = { op, doc -> if (doc != null) op.copy(doc = doc) else op }
                                        )
                                        when (abilityOpsResult) {
                                            is ParseResult.Failure -> return abilityOpsResult
                                            is ParseResult.Success -> {}
                                        }
                                        val (operations, abilityOpsStream) = (abilityOpsResult as ParseResult.Success).value

                                        val stream6 = skipNewlines(abilityOpsStream)
                                        val rbrace = tok(TokenType.RBRACE)(stream6)

                                        when (rbrace) {
                                            is ParseResult.Success -> {
                                                val file = currentFile ?: "<unknown>"
                                                val loc = spanLoc(abilityToken.value, rbrace.value, file)
                                                ParseResult.Success(
                                                    ParsedAbilityDecl(
                                                        name = name.value.text,
                                                        typeParams = typeParams,
                                                        receiverTypeParam = receiverTypeParam,
                                                        // Associated types from `having` clause.
                                                        associatedTypes = associatedTypes,
                                                        operations = operations,
                                                        loc = loc
                                                    ),
                                                    rbrace.remaining
                                                )
                                            }
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

internal val MezzeParser.abilityOperationParser: Parser<ParsedAbilityOp> get() = { stream ->
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
                                val funcType = functionType.value as? ParsedFunctionType
                                if (funcType != null) {
                                    val file = currentFile ?: "<unknown>"
                                    val loc = spanLocTo(name.value, functionType.remaining, file)
                                    // Validation of effects (no <...> or <*>) is done in typechecker
                                    ParseResult.Success(
                                        ParsedAbilityOp(
                                            name.value.text,
                                            funcType.paramType,
                                            funcType.returnType,
                                            funcType.effects,
                                            funcType.abilityConstraints,
                                            null,
                                            loc
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


internal fun MezzeParser.parseImplAbilityDeclaration(stream: TokenStream): ParseResult<ParsedDecl> {
        // Also supports qualified: impl module::Encode for Int where { operations }
        val implToken = tok(TokenType.IMPL)(stream)
        return when (implToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(implToken.remaining)

                // Anonymous inherent impl on a structural receiver:
                //   impl [T] where { ... }
                // The parseQualifiedRef path below requires an identifier
                // at the receiver, so [T] must dispatch here first. Once
                // implTargetTypeAnnotation consumes the [T], we expect
                // `where` and thread through the same operations-block
                // parser as the identifier form. #impl-list-inherent.
                if (stream1.current()?.type == TokenType.LBRACKET) {
                    val typeResult = implTargetTypeAnnotation(stream1)
                    when (typeResult) {
                        is ParseResult.Success -> {
                            val afterType = skipNewlines(typeResult.remaining)
                            val whereForList = tok(TokenType.WHERE)(afterType)
                            return when (whereForList) {
                                is ParseResult.Success -> {
                                    parseImplAbilityOperationsBlock(
                                        implToken, null,
                                        listOf(typeResult.value),
                                        emptyMap(), whereForList
                                    )
                                }
                                is ParseResult.Failure -> whereForList
                            }
                        }
                        is ParseResult.Failure -> return typeResult
                    }
                }

                val firstRefResult = parseQualifiedRef(stream1)

                when (firstRefResult) {
                    is ParseResult.Success -> {
                        val firstRef = firstRefResult.value
                        val stream2 = skipNewlines(firstRefResult.remaining)

                        val forToken = tok(TokenType.FOR)(stream2)

                        when (forToken) {
                            is ParseResult.Success -> {
                                // Named impl: impl AbilityName for TargetType where { ... }
                                val abilityRef = firstRef
                                val streamAfterFor = skipNewlines(forToken.remaining)

                                parseImplAbilityBody(implToken, abilityRef, streamAfterFor)
                            }
                            is ParseResult.Failure -> {
                                val nextToken = stream2.current()
                                if (nextToken?.type == TokenType.WHERE) {
                                    // Anonymous impl: impl Type where { ... }
                                    val file = currentFile ?: "<unknown>"
                                    val targetType = ParsedNamedType(firstRef.localName, firstRef.loc)
                                    val targetTypes = listOf(targetType)

                                    val whereToken = tok(TokenType.WHERE)(stream2)
                                    when (whereToken) {
                                        is ParseResult.Success -> {
                                            parseImplAbilityOperationsBlock(implToken, null, targetTypes, emptyMap(), whereToken)
                                        }
                                        is ParseResult.Failure -> whereToken
                                    }
                                } else {
                                    // Could be ability impl with type args:
                                    //   impl Eq 'a for ['a] where { ... }                           (type variable)
                                    //   impl Into Str for Int where { ... }                          (concrete type)
                                    //   impl TryInto Int |ConversionError {msg: Str}| for Str where  (variant type)
                                    //   impl TryInto Int {msg: Str} for Str where                    (record type)
                                    // Any type annotation is allowed between the ability name and 'for'.
                                    var argStream = stream2
                                    val abilityTypeArgs = mutableListOf<ParsedTypeAnnotation>()
                                    while (true) {
                                        parseTick(argStream)  // anti-hang: bounded work per iteration
                                        // Stop if we see 'for' — that ends the type args
                                        val nextTok = argStream.current()
                                        if (nextTok?.type == TokenType.FOR) break

                                        val typeResult = implTargetTypeAnnotation(argStream)
                                        when (typeResult) {
                                            is ParseResult.Success -> {
                                                abilityTypeArgs.add(typeResult.value)
                                                argStream = skipNewlines(typeResult.remaining)
                                            }
                                            is ParseResult.Failure -> break
                                        }
                                    }
                                    if (abilityTypeArgs.isNotEmpty()) {
                                        val forToken2 = tok(TokenType.FOR)(argStream)
                                        when (forToken2) {
                                            is ParseResult.Success -> {
                                                // Generic ability impl with type args
                                                val abilityRef = firstRef
                                                val streamAfterFor = skipNewlines(forToken2.remaining)
                                                parseImplAbilityBody(implToken, abilityRef, streamAfterFor, abilityTypeArgs)
                                            }
                                            is ParseResult.Failure -> {
                                                // e.g., impl Option 'a where { ... }
                                                val whereToken2 = tok(TokenType.WHERE)(argStream)
                                                when (whereToken2) {
                                                    is ParseResult.Success -> {
                                                        val file = currentFile ?: "<unknown>"
                                                        val loc = spanLocTo(firstRef.loc.let {
                                                            argStream.tokens.getOrNull(0) ?: argStream.tokens.last()
                                                        }, argStream, file)
                                                        val targetType = ParsedTypeApp(
                                                            firstRef.localName,
                                                            abilityTypeArgs,
                                                            loc
                                                        )
                                                        parseImplAbilityOperationsBlock(implToken, null, listOf(targetType), emptyMap(), whereToken2)
                                                    }
                                                    is ParseResult.Failure -> forToken2
                                                }
                                            }
                                        }
                                    } else {
                                        // Not 'for', not 'where', no type args — fail so effect impl parser can try
                                        forToken
                                    }
                                }
                            }
                        }
                    }
                    is ParseResult.Failure -> firstRefResult
                }
            }
            is ParseResult.Failure -> implToken
        }
    }

    private fun MezzeParser.parseImplAbilityBody(
        implToken: ParseResult.Success<Token>,
        abilityRef: ParsedQualifiedRef,
        streamAfterFor: TokenStream,
        abilityTypeArgs: List<ParsedTypeAnnotation> = emptyList()
    ): ParseResult<ParsedDecl> {
        // A capitalized type name followed by type variables ('a, 'b) or structural types
        // ([T], {fields}) is parsed as a type application (e.g., Option 'a → ParsedTypeApp).
        // A capitalized type name followed by another capitalized type name means separate target types
        // (e.g., String Int for multi-parameter abilities).
        val targetTypes = mutableListOf<ParsedTypeAnnotation>()
        var currentStream = streamAfterFor

        val firstType = implTargetTypeAnnotation(currentStream)
        return when (firstType) {
            is ParseResult.Success -> {
                targetTypes.add(firstType.value)
                currentStream = skipNewlines(firstType.remaining)

                while (true) {
                    parseTick(currentStream)  // anti-hang: bounded work per iteration
                    val nextToken = currentStream.current()
                    if (nextToken?.type == TokenType.WHERE || nextToken?.type == TokenType.HAVING) {
                        break
                    }
                    val nextType = implTargetTypeAnnotation(currentStream)
                    when (nextType) {
                        is ParseResult.Success -> {
                            targetTypes.add(nextType.value)
                            currentStream = skipNewlines(nextType.remaining)
                        }
                        is ParseResult.Failure -> break
                    }
                }

                val stream3 = skipNewlines(currentStream)

                val bindingsResult = parseAssociatedTypeBindings(stream3, abilityRef.localName, "impl")
                val (associatedTypeBindings, streamAfterHaving) = when (bindingsResult) {
                    is ParseResult.Success -> Pair(bindingsResult.value.bindings, bindingsResult.value.stream)
                    is ParseResult.Failure -> return bindingsResult
                }

                val stream3d = skipNewlines(streamAfterHaving)
                val whereToken = tok(TokenType.WHERE)(stream3d)

                when (whereToken) {
                    is ParseResult.Success -> {
                        parseImplAbilityOperationsBlock(implToken, abilityRef, targetTypes, associatedTypeBindings, whereToken, abilityTypeArgs)
                    }
                    is ParseResult.Failure -> whereToken
                }
            }
            is ParseResult.Failure -> firstType
        }
    }

    private fun MezzeParser.parseImplAbilityOperationsBlock(
        implToken: ParseResult.Success<Token>,
        abilityRef: ParsedQualifiedRef?,
        targetTypes: List<ParsedTypeAnnotation>,
        associatedTypeBindings: Map<String, ParsedTypeAnnotation>,
        whereToken: ParseResult.Success<Token>,
        abilityTypeArgs: List<ParsedTypeAnnotation> = emptyList()
    ): ParseResult<ParsedDecl> {
        val stream4 = skipNewlines(whereToken.remaining)
        val lbrace = tok(TokenType.LBRACE)(stream4)

        return when (lbrace) {
            is ParseResult.Success -> {
                // skipNewlinesOnly so a `## doc` above the FIRST op survives.
                val stream5 = skipNewlinesOnly(lbrace.remaining)

                val implOpsResult = parseCommaSeparatedOps(
                    stream5, implAbilityOperationParser,
                    "Missing ',' between ability impl methods. Methods must be comma-separated.",
                    attachDoc = { op, doc -> if (doc != null) op.copy(doc = doc) else op }
                )
                when (implOpsResult) {
                    is ParseResult.Failure -> return implOpsResult
                    is ParseResult.Success -> {}
                }
                val (operations, implOpsStream) = (implOpsResult as ParseResult.Success).value

                val stream6 = skipNewlines(implOpsStream)
                val rbrace = tok(TokenType.RBRACE)(stream6)

                when (rbrace) {
                    is ParseResult.Success -> {
                        val implTarget = if (abilityRef != null) {
                            ParsedImplTarget.Named(abilityRef)
                        } else {
                            ParsedImplTarget.Inherent
                        }
                        val file = currentFile ?: "<unknown>"
                        val loc = spanLoc(implToken.value, rbrace.value, file)
                        // (`'a`, `'b`, …) that appears in abilityTypeArgs or
                        // targetTypes is an implicit quantifier of the impl.
                        // E.g., `impl Eq 'a for Option 'a` → forallVars = ["'a"].
                        val implicitForallVars = collectTypeVarNames(abilityTypeArgs + targetTypes)
                        ParseResult.Success(
                            ParsedImplAbilityDecl(
                                target = implTarget,
                                targetTypes = targetTypes,
                                abilityTypeArgs = abilityTypeArgs,
                                forallVars = implicitForallVars,
                                associatedTypeBindings = associatedTypeBindings,
                                operations = operations,
                                loc = loc
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

/**
 * Parse a type annotation for impl target types.
 * Like typeApplicationAnnotation, but only consumes type variable args ('a, 'b)
 * and structural types ([T], {fields}) after a capitalized constructor.
 * Capitalized identifiers (String, Int) are NOT consumed as args — they are
 * separate target types for multi-parameter abilities.
 */
internal val MezzeParser.implTargetTypeAnnotation: Parser<ParsedTypeAnnotation>
    get() = { stream ->
        val first = simpleTypeArgument(stream)
        when (first) {
            is ParseResult.Success -> {
                val firstAnnotation = first.value
                // Only try to collect type variable args for capitalized type constructors
                if (firstAnnotation is ParsedNamedType && firstAnnotation.name[0].isUpperCase()) {
                    val args = mutableListOf<ParsedTypeAnnotation>()
                    var currentStream = first.remaining
                    while (true) {
                        parseTick(currentStream)  // anti-hang: bounded work per iteration
                        val nextTok = currentStream.current()
                        // Only consume type variables ('a), seq types ([T]), and record types ({fields})
                        // Do NOT consume capitalized identifiers — those are separate target types
                        if (nextTok != null && nextTok.type == TokenType.IDENTIFIER && nextTok.text.startsWith("'")) {
                            val argResult = simpleTypeArgument(currentStream)
                            when (argResult) {
                                is ParseResult.Success -> {
                                    args.add(argResult.value)
                                    currentStream = argResult.remaining
                                }
                                is ParseResult.Failure -> break
                            }
                        } else if (nextTok != null && nextTok.type == TokenType.LBRACKET) {
                            // Seq type like ['a]
                            val argResult = seqTypeAnnotation(currentStream)
                            when (argResult) {
                                is ParseResult.Success -> {
                                    args.add(argResult.value)
                                    currentStream = argResult.remaining
                                }
                                is ParseResult.Failure -> break
                            }
                        } else {
                            break
                        }
                    }
                    if (args.isNotEmpty()) {
                        val file = currentFile ?: "<unknown>"
                        val loc = spanLocTo(stream.current()!!, currentStream, file)
                        ParseResult.Success(ParsedTypeApp(firstAnnotation.name, args, loc), currentStream)
                    } else {
                        first // No args, return as simple identifier
                    }
                } else {
                    first // Not a capitalized identifier — return as-is (seq type, record, etc.)
                }
            }
            is ParseResult.Failure -> first
        }
    }

internal val MezzeParser.implAbilityOperationParser: Parser<ParsedImplOp> get() = { stream ->
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
                                val funcType = functionType.value as? ParsedFunctionType
                                if (funcType != null) {
                                    val stream3 = skipNewlines(functionType.remaining)
                                    val assign = tok(TokenType.ASSIGN)(stream3)

                                    when (assign) {
                                        is ParseResult.Success -> {
                                            val stream4 = skipNewlines(assign.remaining)
                                            val impl = expression(stream4)

                                            when (impl) {
                                                is ParseResult.Success -> {
                                                    val file = currentFile ?: "<unknown>"
                                                    val loc = spanLocTo(name.value, impl.remaining, file)
                                                    ParseResult.Success(
                                                        ParsedImplOp(
                                                            name.value.text,
                                                            funcType.paramType,
                                                            funcType.returnType,
                                                            impl.value,
                                                            funcType.abilityConstraints,
                                                            loc = loc
                                                        ),
                                                        impl.remaining
                                                    )
                                                }
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
                        val assign = tok(TokenType.ASSIGN)(stream1)
                        when (assign) {
                            is ParseResult.Success -> {
                                val stream2 = skipNewlines(assign.remaining)
                                val impl = expression(stream2)

                                when (impl) {
                                    is ParseResult.Success -> {
                                        val file = currentFile ?: "<unknown>"
                                        val loc = spanLocTo(name.value, impl.remaining, file)
                                        ParseResult.Success(
                                            ParsedImplOp(
                                                name.value.text,
                                                null,  // Type will be inferred by typechecker
                                                null,  // Type will be inferred by typechecker
                                                impl.value,
                                                emptyList(),
                                                loc = loc
                                            ),
                                            impl.remaining
                                        )
                                    }
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


internal fun MezzeParser.parseUseDeclaration(stream: TokenStream, visibility: Visibility): ParseResult<ParsedDecl> {
        //   or:  use std::math::{trig, random}
        //   or:  use std::math::basic as m
        val useToken = tok(TokenType.USE)(stream)
        return when (useToken) {
            is ParseResult.Success -> {
                val file = currentFile ?: "<unknown>"
                val stream1 = skipNewlines(useToken.remaining)

                val pathSegments = mutableListOf<String>()
                var currentStream = stream1

                val firstSegment = identifierParser(currentStream)
                when (firstSegment) {
                    is ParseResult.Success -> {
                        pathSegments.add(firstSegment.value.text)
                        currentStream = firstSegment.remaining

                        @Suppress("ControlFlowWithEmptyBody")
                        while (true) {
                            val doubleColon = tok(TokenType.DOUBLE_COLON)(currentStream)
                            when (doubleColon) {
                                is ParseResult.Success -> {
                                    val nextStream = skipNewlines(doubleColon.remaining)

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
                                                        val modulePath = ParsedModulePath(pathSegments)
                                                        val loc = spanLoc(useToken.value, rbrace.value, file)
                                                        val path = ParsedUsePath.Selective(modulePath, items.value, loc)
                                                        ParseResult.Success(
                                                            if (visibility == Visibility.PUBLIC) {
                                                                ParsedPubUseDecl(path, null, loc = loc)
                                                            } else {
                                                                ParsedUseDecl(path, null, loc = loc)
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
                                        is ParseResult.Failure -> {
                                            val currentToken = nextStream.current()
                                            val keywords = listOf(
                                                TokenType.EFFECT, TokenType.TYPE, TokenType.ABILITY,
                                                TokenType.IMPL, TokenType.LET, TokenType.MATCH,
                                                TokenType.IF, TokenType.DO, TokenType.PERFORM
                                            )
                                            if (currentToken != null && currentToken.type in keywords) {
                                                return nextStream.failure(
                                                    "'${currentToken.text}' is a reserved keyword and cannot be used in module paths.\n" +
                                                    "   The module is likely named differently. Try:\n" +
                                                    "   • 'std::effects::...' instead of 'std::effect::...'\n" +
                                                    "   • Check the module documentation for the correct path"
                                                )
                                            }
                                            return nextSegment
                                        }
                                    }
                                }
                                is ParseResult.Failure -> {
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

                                    val modulePath = ParsedModulePath(pathSegments)
                                    val loc = spanLocTo(useToken.value, currentStream, file)
                                    val path = ParsedUsePath.Module(modulePath, loc)
                                    return ParseResult.Success(
                                        if (visibility == Visibility.PUBLIC) {
                                            ParsedPubUseDecl(path, alias, loc = loc)
                                        } else {
                                            ParsedUseDecl(path, alias, loc = loc)
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

internal val MezzeParser.importItemParser: Parser<ParsedImportItem> get() = { stream ->
        val name = identifierParser(stream)
        when (name) {
            is ParseResult.Success -> {
                val file = currentFile ?: "<unknown>"
                val stream1 = skipNewlines(name.remaining)
                val asKeyword = keyword("as", TokenType.IDENTIFIER)(stream1)

                when (asKeyword) {
                    is ParseResult.Success -> {
                        // Has alias
                        val stream2 = skipNewlines(asKeyword.remaining)
                        val aliasName = identifierParser(stream2)

                        when (aliasName) {
                            is ParseResult.Success -> {
                                val loc = spanLoc(name.value, aliasName.value, file)
                                ParseResult.Success(
                                    ParsedImportItem(name.value.text, aliasName.value.text, loc),
                                    aliasName.remaining
                                )
                            }
                            is ParseResult.Failure -> aliasName
                        }
                    }
                    is ParseResult.Failure -> {
                        val loc = tokenLoc(name.value, file)
                        ParseResult.Success(
                            ParsedImportItem(name.value.text, null, loc),
                            stream1
                        )
                    }
                }
            }
            is ParseResult.Failure -> name
        }
    }

/**
 * Collect every type variable name (`'a`, `'b`, …) that appears anywhere
 * in the given type annotations. Preserves first-occurrence order, no
 * duplicates. Used to derive implicit `forallVars` on `impl` decls — the
 * surface syntax doesn't list quantifiers explicitly; whatever type vars
 * appear in the abilityTypeArgs / targetTypes ARE the quantifiers.
 */
internal fun collectTypeVarNames(annotations: List<ParsedTypeAnnotation>): List<String> {
    val seen = LinkedHashSet<String>()
    for (ann in annotations) collectTypeVarNamesInto(ann, seen)
    return seen.toList()
}

internal fun collectTypeVarNamesInto(ann: ParsedTypeAnnotation, out: MutableSet<String>) {
    when (ann) {
        is ParsedNamedType -> if (ann.name.startsWith("'")) out.add(ann.name)
        is ParsedQualifiedType -> Unit
        is ParsedTypeApp -> for (a in ann.args) collectTypeVarNamesInto(a, out)
        is ParsedFunctionType -> {
            collectTypeVarNamesInto(ann.paramType, out)
            collectTypeVarNamesInto(ann.returnType, out)
        }
        is ParsedRecordType -> for (e in ann.entries) collectTypeVarNamesInto(e.type, out)
        is ParsedVariantType -> for (c in ann.constructors) c.dataType?.let { collectTypeVarNamesInto(it, out) }
        is ParsedSeqType -> collectTypeVarNamesInto(ann.elementType, out)
        is ParsedEffectRow, is ParsedEffectAnnotation -> Unit
    }
}
