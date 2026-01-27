package com.funk.language.parser

import com.funk.language.ast.*
import com.funk.language.parser.internal.*
import com.funk.language.parser.internal.lib.*

/**
 * Funk language parser using parser combinators.
 *
 * This parser explicitly handles the ambiguity between:
 * - Lambda expressions: {x} -> body
 * - Record literals: {x} or {x = value}
 *
 * By using explicit lookahead for the arrow token.
 */
class FunkParser {
    // Store source and file during parsing for better error messages
    internal var currentSource: String?? = null
    internal var currentFile: String?? = null

    fun parse(source: String, desugar: Boolean = true, file: String? = null): Program {
        // Store for error reporting in nested parser methods
        currentSource = source
        currentFile = file

        // Clear syntax metadata from previous parses
        SyntaxMetadata.clear()

        try {
            val lexer = Lexer(source)
            val tokens = lexer.tokenize()
            val stream = TokenStream(tokens)

            return when (val result = programParser(stream)) {
                is ParseResult.Success -> if (desugar) desugarProgram(result.value) else result.value
                is ParseResult.Failure -> throw ParseException(
                    result.message,
                    result.position,
                    result.line,
                    result.column,
                    source,
                    file
                )
            }
        } finally {
            currentSource = null
            currentFile = null
        }
    }

    // ===== TOP LEVEL =====

    private val programParser: Parser<Program> = { stream ->
        // Skip leading newlines
        var currentStream = skipNewlines(stream)
        val items = mutableListOf<ProgramItem>()
        var failure: ParseResult.Failure? = null

        // Parse top-level items, skipping newlines between them
        while (currentStream.current()?.type != TokenType.EOF && failure == null) {
            when (val result = topLevelItem(currentStream)) {
                is ParseResult.Success -> {
                    items.add(result.value)
                    currentStream = skipNewlines(result.remaining)
                }
                is ParseResult.Failure -> {
                    failure = ParseResult.Failure(result.message, result.position)
                }
            }
        }

        failure ?: ParseResult.Success(Program(items), currentStream)
    }

    // Declaration parsers (defined later to avoid forward reference)
    private lateinit var effectDeclaration: Parser<ProgramItem>
    private lateinit var implDeclaration: Parser<ProgramItem>
    private lateinit var implAbilityDeclaration: Parser<ProgramItem>
    private lateinit var abilityDeclaration: Parser<ProgramItem>
    private lateinit var typeDeclaration: Parser<ProgramItem>
    private lateinit var newtypeDeclaration: Parser<ProgramItem>
    private lateinit var useDeclaration: Parser<ProgramItem>
    private lateinit var letDeclaration: Parser<ProgramItem>
    private lateinit var topLevelExpression: Parser<ProgramItem>
    private lateinit var topLevelItem: Parser<ProgramItem>

    // ===== LET BINDING (defined before init block) =====

    private fun parseLetBinding(stream: TokenStream): ParseResult<ProgramItem> {
        return parseLetBindingWithVisibility(stream, Visibility.PRIVATE)
    }

    private fun parseLetBindingWithVisibility(stream: TokenStream, visibility: Visibility): ParseResult<ProgramItem> {
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
                                stream1.getSourcePosition()
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
    private fun parseConstrainedLambda(stream: TokenStream): ParseResult<ProgramItem> {
        // Only try if we see a { (record type parameter)
        if (stream.current()?.type != TokenType.LBRACE) {
            return ParseResult.Failure("Not a constrained lambda", stream.getSourcePosition())
        }

        // Try to parse function type annotation
        val typeAnn = functionTypeAnnotation(stream)
        if (typeAnn is ParseResult.Failure) {
            return typeAnn
        }

        val stream1 = skipNewlines((typeAnn as ParseResult.Success).remaining)
        val typeAnnotation = typeAnn.value

        // Check if it's actually a function type
        if (typeAnnotation !is FunctionTypeAnnotation) {
            return ParseResult.Failure("Expected function type annotation", stream.getSourcePosition())
        }

        // Check if it has ability constraints (from the 'with' keyword)
        // The functionTypeAnnotation parser already parsed these
        if (typeAnnotation.abilityConstraints.isEmpty()) {
            // No constraints, not what we're looking for
            return ParseResult.Failure("No ability constraints", stream.getSourcePosition())
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
                            return ParseResult.Failure("Expected lambda expression", stream2.getSourcePosition())
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

    private fun parseEffectDeclaration(stream: TokenStream, visibility: Visibility): ParseResult<ProgramItem> {
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
                                                "Associated type must be a type variable starting with '. " +
                                                "Expected something like 'state, but got: ${typeVar.value.text}",
                                                currentStream.getSourcePosition()
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
                                                                    "Associated type must be a type variable starting with '. " +
                                                                    "Expected something like 'error, but got: ${nextTypeVar.value.text}",
                                                                    streamAfterComma.getSourcePosition()
                                                                )
                                                            }
                                                            associatedTypes.add(nextTypeVar.value.text)
                                                            currentStream = skipNewlines(nextTypeVar.remaining)
                                                        }
                                                        is ParseResult.Failure -> {
                                                            return ParseResult.Failure(
                                                                "Expected type variable after ',' in having clause. " +
                                                                "Example: effect ${name.value.text} having 'state, 'error where { ... }",
                                                                streamAfterComma.getSourcePosition()
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
                                        return ParseResult.Failure(
                                            "Expected type variable after 'having' keyword in effect declaration. " +
                                            "Example: effect ${name.value.text} having 'state where { ... }",
                                            stream2a.getSourcePosition()
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
                                        // Parse operations separated by newlines or commas (both supported)
                                        val operations = mutableListOf<EffectOperation>()
                                        var currentStream = stream4

                                        while (true) {
                                            when (val op = effectOperationParser(currentStream)) {
                                                is ParseResult.Success -> {
                                                    operations.add(op.value)
                                                    currentStream = skipNewlines(op.remaining)

                                                    // Optionally consume a comma
                                                    when (val comma = tok(TokenType.COMMA)(currentStream)) {
                                                        is ParseResult.Success -> {
                                                            currentStream = skipNewlines(comma.remaining)
                                                        }
                                                        is ParseResult.Failure -> {
                                                            // No comma, that's fine - continue to next operation or end
                                                        }
                                                    }
                                                }
                                                is ParseResult.Failure -> break
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
                                                        associatedTypes  // Pass the list of associated types
                                                    ),
                                                    rbrace.remaining
                                                )
                                            }
                                            is ParseResult.Failure -> ParseResult.Failure(
                                                "Expected '}' to close effect declaration '${name.value.text}'. " +
                                                "Make sure all effect operations are properly formatted: operationName: { params } -> ReturnType <${name.value.text}>",
                                                stream5.getSourcePosition()
                                            )
                                        }
                                    }
                                    is ParseResult.Failure -> ParseResult.Failure(
                                        "Expected '{' after 'where' in effect declaration '${name.value.text}'. " +
                                        "Syntax: effect ${name.value.text} where { operations }",
                                        stream3.getSourcePosition()
                                    )
                                }
                            }
                            is ParseResult.Failure -> ParseResult.Failure(
                                "Effect declarations require 'where' keyword. " +
                                "Expected: effect ${name.value.text} where { operations }. " +
                                "Each operation must declare the effect in its type signature: operationName: { params } -> ReturnType <${name.value.text}>",
                                streamAfterHaving.getSourcePosition()
                            )
                        }
                    }
                    is ParseResult.Failure -> name
                }
            }
            is ParseResult.Failure -> effectToken
        }
    }

    private val effectOperationParser: Parser<EffectOperation> = { stream ->
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
                                    ParseResult.Failure(
                                        "Expected function type for effect operation '${name.value.text}'. " +
                                        "Effect operations must have the format: operationName: { params } -> ReturnType <EffectName>",
                                        stream2.getSourcePosition()
                                    )
                                }
                            }
                            is ParseResult.Failure -> ParseResult.Failure(
                                "Invalid type annotation for effect operation '${name.value.text}'. " +
                                "Expected format: ${name.value.text}: { params } -> ReturnType <EffectName>. " +
                                "Error: ${functionType.message}",
                                functionType.position
                            )
                        }
                    }
                    is ParseResult.Failure -> ParseResult.Failure(
                        "Expected ':' after effect operation name '${name.value.text}'. " +
                        "Effect operations must have the format: ${name.value.text}: { params } -> ReturnType <EffectName>",
                        stream1.getSourcePosition()
                    )
                }
            }
            is ParseResult.Failure -> name
        }
    }

    // ===== TYPE DECLARATIONS =====

    private fun parseTypeDeclaration(stream: TokenStream, visibility: Visibility): ParseResult<ProgramItem> {
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
                                            inferredType = null
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

    private fun parseNewtypeDeclaration(stream: TokenStream, visibility: Visibility): ParseResult<ProgramItem> {
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

    private val typeDefinitionParser: Parser<TypeDefinition> = { stream ->
        // Try variant type first (| ... |), then record type
        val variantResult = variantTypeParser(stream)
        when (variantResult) {
            is ParseResult.Success -> variantResult
            is ParseResult.Failure -> {
                // Try record type
                recordTypeParser(stream)
            }
        }
    }

    private val variantTypeParser: Parser<TypeDefinition> = { stream ->
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
                                is ParseResult.Success -> ParseResult.Success(
                                    VariantDefinition(constructors.value, isOpen),
                                    pipe2.remaining
                                )
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

    private val variantConstructorParser: Parser<VariantConstructorDef> = { stream ->
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

    private val recordTypeParser: Parser<TypeDefinition> = { stream ->
        val recordType = recordTypeAnnotationParser(stream)
        when (recordType) {
            is ParseResult.Success -> {
                val recordTypeAnn = recordType.value as RecordTypeAnnotation
                val fields = recordTypeAnn.entries.map { entry ->
                    RecordFieldDef(entry.name, entry.type)
                }
                val isOpen = recordTypeAnn.restField != null
                ParseResult.Success(
                    RecordDefinition(fields, isOpen),
                    recordType.remaining
                )
            }
            is ParseResult.Failure -> recordType
        }
    }

    // ===== IMPL DECLARATIONS =====

    private fun parseImplDeclaration(stream: TokenStream): ParseResult<ProgramItem> {
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

                                        // Check for optional 'having' keyword for multiple associated type bindings
                                        val associatedTypeBindings = mutableMapOf<String, TypeAnnotation>()
                                        val streamAfterHaving = when (val havingToken = tok(TokenType.HAVING)(stream5)) {
                                            is ParseResult.Success -> {
                                                val stream5a = skipNewlines(havingToken.remaining)
                                                var currentStream = stream5a

                                                // Parse first binding: 'state = Int
                                                when (val typeVar = identifierParser(currentStream)) {
                                                    is ParseResult.Success -> {
                                                        // Validate it's a type variable (starts with ')
                                                        if (!typeVar.value.text.startsWith("'")) {
                                                            return ParseResult.Failure(
                                                                "Associated type must be a type variable starting with '. " +
                                                                "Expected something like 'state, but got: ${typeVar.value.text}",
                                                                currentStream.getSourcePosition()
                                                            )
                                                        }
                                                        val stream5b = skipNewlines(typeVar.remaining)
                                                        // Expect = sign
                                                        when (val eq = tok(TokenType.ASSIGN)(stream5b)) {
                                                            is ParseResult.Success -> {
                                                                val stream5c = skipNewlines(eq.remaining)
                                                                // Parse the type annotation
                                                                when (val typeAnnotation = typeAnnotationParser(stream5c)) {
                                                                    is ParseResult.Success -> {
                                                                        associatedTypeBindings[typeVar.value.text] = typeAnnotation.value
                                                                        currentStream = skipNewlines(typeAnnotation.remaining)

                                                                        // Parse additional bindings separated by commas
                                                                        while (true) {
                                                                            when (val comma = tok(TokenType.COMMA)(currentStream)) {
                                                                                is ParseResult.Success -> {
                                                                                    val streamAfterComma = skipNewlines(comma.remaining)
                                                                                    when (val nextTypeVar = identifierParser(streamAfterComma)) {
                                                                                        is ParseResult.Success -> {
                                                                                            if (!nextTypeVar.value.text.startsWith("'")) {
                                                                                                return ParseResult.Failure(
                                                                                                    "Associated type must be a type variable starting with '. " +
                                                                                                    "Expected something like 'error, but got: ${nextTypeVar.value.text}",
                                                                                                    streamAfterComma.getSourcePosition()
                                                                                                )
                                                                                            }
                                                                                            val streamAfterVar = skipNewlines(nextTypeVar.remaining)
                                                                                            when (val nextEq = tok(TokenType.ASSIGN)(streamAfterVar)) {
                                                                                                is ParseResult.Success -> {
                                                                                                    val streamAfterEq = skipNewlines(nextEq.remaining)
                                                                                                    when (val nextTypeAnnotation = typeAnnotationParser(streamAfterEq)) {
                                                                                                        is ParseResult.Success -> {
                                                                                                            associatedTypeBindings[nextTypeVar.value.text] = nextTypeAnnotation.value
                                                                                                            currentStream = skipNewlines(nextTypeAnnotation.remaining)
                                                                                                        }
                                                                                                        is ParseResult.Failure -> {
                                                                                                            return ParseResult.Failure(
                                                                                                                "Expected type annotation after '=' in having clause. " +
                                                                                                                "Example: having 'state = Int, 'error = String",
                                                                                                                streamAfterEq.getSourcePosition()
                                                                                                            )
                                                                                                        }
                                                                                                    }
                                                                                                }
                                                                                                is ParseResult.Failure -> {
                                                                                                    return ParseResult.Failure(
                                                                                                        "Expected '=' after type variable in having clause. " +
                                                                                                        "Example: having 'state = Int, 'error = String",
                                                                                                        streamAfterVar.getSourcePosition()
                                                                                                    )
                                                                                                }
                                                                                            }
                                                                                        }
                                                                                        is ParseResult.Failure -> {
                                                                                            return ParseResult.Failure(
                                                                                                "Expected type variable after ',' in having clause. " +
                                                                                                "Example: having 'state = Int, 'error = String",
                                                                                                streamAfterComma.getSourcePosition()
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
                                                                        return ParseResult.Failure(
                                                                            "Expected type annotation after '=' in having clause. " +
                                                                            "Example: having 'state = Int",
                                                                            stream5c.getSourcePosition()
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            is ParseResult.Failure -> {
                                                                return ParseResult.Failure(
                                                                    "Expected '=' after type variable in having clause. " +
                                                                    "Example: having 'state = Int",
                                                                    stream5b.getSourcePosition()
                                                                )
                                                            }
                                                        }
                                                    }
                                                    is ParseResult.Failure -> {
                                                        return ParseResult.Failure(
                                                            "Expected type variable after 'having' keyword in impl declaration. " +
                                                            "Example: impl ${effectName.value.text} for ${performerName.value.text} having 'state = Int where { ... }",
                                                            stream5a.getSourcePosition()
                                                        )
                                                    }
                                                }
                                            }
                                            is ParseResult.Failure -> stream5
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
                                                        // Parse operations separated by newlines or commas (both supported)
                                                        val operations = mutableListOf<PerformerOperation>()
                                                        var currentStream = stream7

                                                        while (true) {
                                                            when (val op = performerOperationParser(currentStream)) {
                                                                is ParseResult.Success -> {
                                                                    operations.add(op.value)
                                                                    currentStream = skipNewlines(op.remaining)

                                                                    // Optionally consume a comma
                                                                    when (val comma = tok(TokenType.COMMA)(currentStream)) {
                                                                        is ParseResult.Success -> {
                                                                            currentStream = skipNewlines(comma.remaining)
                                                                        }
                                                                        is ParseResult.Failure -> {
                                                                            // No comma, that's fine - continue to next operation or end
                                                                        }
                                                                    }
                                                                }
                                                                is ParseResult.Failure -> break
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
                                                                        inferredType = null
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

    private val performerParamsParser: Parser<List<PerformerParameter>> = { stream ->
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

    private val performerParamParser: Parser<PerformerParameter> = { stream ->
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

    private val performerOperationParser: Parser<PerformerOperation> = { stream ->
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
                                // Extract param and return types from the function type
                                val (paramType, returnType) = when (val ft = functionType.value) {
                                    is FunctionTypeAnnotation -> Pair(ft.paramType, ft.returnType)
                                    else -> {
                                        // If it's not a function type, treat it as param type with Unit return
                                        Pair(ft, IdentifierTypeAnnotation("Unit"))
                                    }
                                }

                                val stream3 = skipNewlines(functionType.remaining)
                                // TODO: Parse optional effect row
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
                                                    effects = null
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

    private fun parseAbilityDeclaration(stream: TokenStream, visibility: Visibility): ParseResult<ProgramItem> {
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
                                                "Associated type must be a type variable starting with '. " +
                                                "Expected something like 'item, but got: ${typeVar.value.text}",
                                                havingStream.getSourcePosition()
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
                                                                    "Associated type must be a type variable starting with '. " +
                                                                    "Expected something like 'iter, but got: ${nextTypeVar.value.text}",
                                                                    streamAfterComma.getSourcePosition()
                                                                )
                                                            }
                                                            associatedTypes.add(nextTypeVar.value.text)
                                                            havingStream = skipNewlines(nextTypeVar.remaining)
                                                        }
                                                        is ParseResult.Failure -> {
                                                            return ParseResult.Failure(
                                                                "Expected type variable after ',' in having clause. " +
                                                                "Example: ability ${name.value.text} having 'item, 'iter where { ... }",
                                                                streamAfterComma.getSourcePosition()
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
                                        return ParseResult.Failure(
                                            "Expected type variable after 'having' keyword in ability declaration. " +
                                            "Example: ability ${name.value.text} 'iter having 'item where { ... }",
                                            streamH.getSourcePosition()
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
                                        // Parse operations separated by newlines or commas (both supported)
                                        val operations = mutableListOf<AbilityOperation>()
                                        var currentStream = stream5

                                        while (true) {
                                            when (val op = abilityOperationParser(currentStream)) {
                                                is ParseResult.Success -> {
                                                    operations.add(op.value)
                                                    currentStream = skipNewlines(op.remaining)

                                                    // Optionally consume a comma
                                                    when (val comma = tok(TokenType.COMMA)(currentStream)) {
                                                        is ParseResult.Success -> {
                                                            currentStream = skipNewlines(comma.remaining)
                                                        }
                                                        is ParseResult.Failure -> {
                                                            // No comma, that's fine - continue to next operation or end
                                                        }
                                                    }
                                                }
                                                is ParseResult.Failure -> break
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
                                                    operations
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

    private val abilityOperationParser: Parser<AbilityOperation> = { stream ->
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
                                    ParseResult.Failure("Expected function type for ability operation", stream2.getSourcePosition())
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

    private fun parseImplAbilityDeclaration(stream: TokenStream): ParseResult<ProgramItem> {
        // Parse: impl Encoder Int where { operations }
        val implToken = tok(TokenType.IMPL)(stream)
        return when (implToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(implToken.remaining)
                val abilityName = identifierParser(stream1)

                when (abilityName) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(abilityName.remaining)

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

                                // Check for optional 'having' keyword for multiple associated type bindings
                                val associatedTypeBindings = mutableMapOf<String, TypeAnnotation>()
                                val streamAfterHaving = when (val havingToken = tok(TokenType.HAVING)(stream3)) {
                                    is ParseResult.Success -> {
                                        val stream3a = skipNewlines(havingToken.remaining)
                                        var havingStream = stream3a

                                        // Parse first binding: 'item = 'a
                                        when (val typeVar = identifierParser(havingStream)) {
                                            is ParseResult.Success -> {
                                                // Validate it's a type variable (starts with ')
                                                if (!typeVar.value.text.startsWith("'")) {
                                                    return ParseResult.Failure(
                                                        "Associated type must be a type variable starting with '. " +
                                                        "Expected something like 'item, but got: ${typeVar.value.text}",
                                                        havingStream.getSourcePosition()
                                                    )
                                                }
                                                val stream3b = skipNewlines(typeVar.remaining)
                                                // Expect = sign
                                                when (val eq = tok(TokenType.ASSIGN)(stream3b)) {
                                                    is ParseResult.Success -> {
                                                        val stream3c = skipNewlines(eq.remaining)
                                                        // Parse the type annotation
                                                        when (val typeAnnotation = typeAnnotationParser(stream3c)) {
                                                            is ParseResult.Success -> {
                                                                associatedTypeBindings[typeVar.value.text] = typeAnnotation.value
                                                                havingStream = skipNewlines(typeAnnotation.remaining)

                                                                // Parse additional bindings separated by commas
                                                                while (true) {
                                                                    when (val comma = tok(TokenType.COMMA)(havingStream)) {
                                                                        is ParseResult.Success -> {
                                                                            val streamAfterComma = skipNewlines(comma.remaining)
                                                                            when (val nextTypeVar = identifierParser(streamAfterComma)) {
                                                                                is ParseResult.Success -> {
                                                                                    if (!nextTypeVar.value.text.startsWith("'")) {
                                                                                        return ParseResult.Failure(
                                                                                            "Associated type must be a type variable starting with '. " +
                                                                                            "Expected something like 'iter, but got: ${nextTypeVar.value.text}",
                                                                                            streamAfterComma.getSourcePosition()
                                                                                        )
                                                                                    }
                                                                                    val streamAfterVar = skipNewlines(nextTypeVar.remaining)
                                                                                    when (val nextEq = tok(TokenType.ASSIGN)(streamAfterVar)) {
                                                                                        is ParseResult.Success -> {
                                                                                            val streamAfterEq = skipNewlines(nextEq.remaining)
                                                                                            when (val nextTypeAnnotation = typeAnnotationParser(streamAfterEq)) {
                                                                                                is ParseResult.Success -> {
                                                                                                    associatedTypeBindings[nextTypeVar.value.text] = nextTypeAnnotation.value
                                                                                                    havingStream = skipNewlines(nextTypeAnnotation.remaining)
                                                                                                }
                                                                                                is ParseResult.Failure -> {
                                                                                                    return ParseResult.Failure(
                                                                                                        "Expected type annotation after '=' in having clause. " +
                                                                                                        "Example: having 'item = 'a, 'iter = MyIter",
                                                                                                        streamAfterEq.getSourcePosition()
                                                                                                    )
                                                                                                }
                                                                                            }
                                                                                        }
                                                                                        is ParseResult.Failure -> {
                                                                                            return ParseResult.Failure(
                                                                                                "Expected '=' after type variable in having clause. " +
                                                                                                "Example: having 'item = 'a, 'iter = MyIter",
                                                                                                streamAfterVar.getSourcePosition()
                                                                                            )
                                                                                        }
                                                                                    }
                                                                                }
                                                                                is ParseResult.Failure -> {
                                                                                    return ParseResult.Failure(
                                                                                        "Expected type variable after ',' in having clause. " +
                                                                                        "Example: having 'item = 'a, 'iter = MyIter",
                                                                                        streamAfterComma.getSourcePosition()
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
                                                                return ParseResult.Failure(
                                                                    "Expected type annotation after '=' in having clause. " +
                                                                    "Example: having 'item = 'a",
                                                                    stream3c.getSourcePosition()
                                                                )
                                                            }
                                                        }
                                                    }
                                                    is ParseResult.Failure -> {
                                                        return ParseResult.Failure(
                                                            "Expected '=' after type variable in having clause. " +
                                                            "Example: having 'item = 'a",
                                                            stream3b.getSourcePosition()
                                                        )
                                                    }
                                                }
                                            }
                                            is ParseResult.Failure -> {
                                                return ParseResult.Failure(
                                                    "Expected type variable after 'having' keyword in impl declaration. " +
                                                    "Example: impl ${abilityName.value.text} (Vec 'a) having 'item = 'a where { ... }",
                                                    stream3a.getSourcePosition()
                                                )
                                            }
                                        }
                                    }
                                    is ParseResult.Failure -> stream3
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

                                                // Parse operations separated by newlines or commas (both supported)
                                                val operations = mutableListOf<ImplAbilityOperation>()
                                                var currentStream = stream5

                                                while (true) {
                                                    when (val op = implAbilityOperationParser(currentStream)) {
                                                        is ParseResult.Success -> {
                                                            operations.add(op.value)
                                                            currentStream = skipNewlines(op.remaining)

                                                            // Optionally consume a comma
                                                            when (val comma = tok(TokenType.COMMA)(currentStream)) {
                                                                is ParseResult.Success -> {
                                                                    currentStream = skipNewlines(comma.remaining)
                                                                }
                                                                is ParseResult.Failure -> {
                                                                    // No comma, that's fine - continue to next operation or end
                                                                }
                                                            }
                                                        }
                                                        is ParseResult.Failure -> break
                                                    }
                                                }

                                                val stream6 = skipNewlines(currentStream)
                                                val rbrace = tok(TokenType.RBRACE)(stream6)

                                                when (rbrace) {
                                                    is ParseResult.Success -> ParseResult.Success(
                                                        ImplAbilityDeclaration(
                                                            abilityName.value.text,
                                                            targetTypes,
                                                            emptyList(),  // forallVars - deferred for later
                                                            associatedTypeBindings,
                                                            operations
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
                    is ParseResult.Failure -> abilityName
                }
            }
            is ParseResult.Failure -> implToken
        }
    }

    private val implAbilityOperationParser: Parser<ImplAbilityOperation> = { stream ->
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
                                    ParseResult.Failure("Expected function type for ability operation", stream2.getSourcePosition())
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

    private fun parseUseDeclaration(stream: TokenStream, visibility: Visibility): ParseResult<ProgramItem> {
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
                        ParseResult.Failure("Unexpected error in use declaration", stream.getSourcePosition())
                    }
                    is ParseResult.Failure -> firstSegment
                }
            }
            is ParseResult.Failure -> useToken
        }
    }

    private val importItemParser: Parser<ImportItem> = { stream ->
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

    init {
        // Initialize parsers after all properties are available
        effectDeclaration = { stream -> parseEffectDeclaration(stream, Visibility.PRIVATE) }
        implDeclaration = { stream -> parseImplDeclaration(stream) }
        implAbilityDeclaration = { stream -> parseImplAbilityDeclaration(stream) }
        abilityDeclaration = { stream -> parseAbilityDeclaration(stream, Visibility.PRIVATE) }
        typeDeclaration = { stream -> parseTypeDeclaration(stream, Visibility.PRIVATE) }
        newtypeDeclaration = { stream -> parseNewtypeDeclaration(stream, Visibility.PRIVATE) }
        useDeclaration = { stream -> parseUseDeclaration(stream, Visibility.PRIVATE) }
        letDeclaration = { stream -> parseLetBinding(stream) }  // Lambda wrapper
        topLevelExpression = { stream -> Parsers.map(expression) { TopLevelExpression(it) }(stream) }

        // Initialize topLevelItem AFTER all other parsers are initialized
        topLevelItem = { stream ->
            // Check for optional 'pub' modifier
            val pubToken = tok(TokenType.PUB)(stream)
            val (visibility, streamAfterPub) = when (pubToken) {
                is ParseResult.Success -> Pair(Visibility.PUBLIC, pubToken.remaining)
                is ParseResult.Failure -> Pair(Visibility.PRIVATE, stream)
            }

            // Parse the actual declaration with visibility
            val streamAfterPubSkipNl = skipNewlines(streamAfterPub)
            val declResult = Parsers.oneOf(
                { s -> parseEffectDeclaration(s, visibility) },
                { s -> parseTypeDeclaration(s, visibility) },
                { s -> parseNewtypeDeclaration(s, visibility) },
                { s -> parseAbilityDeclaration(s, visibility) },
                { s -> parseImplAbilityDeclaration(s) },  // Try ability impl first
                { s -> parseImplDeclaration(s) },          // Then effect impl
                { s -> parseUseDeclaration(s, visibility) },
                { s -> parseLetBindingWithVisibility(s, visibility) },
                { s -> parseConstrainedLambda(s) },  // Try constrained lambda before general expression
                topLevelExpression
            )(streamAfterPubSkipNl)

            declResult
        }
    }

    // ===== HELPERS =====

    internal fun skipNewlines(stream: TokenStream): TokenStream {
        var current = stream
        while (current.current()?.type == TokenType.NEWLINE) {
            current = current.advance()
        }
        return current
    }

    private val newlineParser: Parser<Unit> = Parsers.map(
        Parsers.many1(Parsers.token({ it.type == TokenType.NEWLINE }, "newline"))
    ) { Unit }

    internal fun tok(type: TokenType): Parser<Token> =
        Parsers.token({ it.type == type }, type.name)
    private fun <T, S> sepByWithNewlines(parser: Parser<T>, separator: Parser<S>): Parser<List<T>> = { stream ->
        when (val first = parser(stream)) {
            is ParseResult.Success -> {
                val results = mutableListOf(first.value)
                var currentStream = first.remaining

                while (true) {
                    // Try to match separator
                    when (val sep = separator(currentStream)) {
                        is ParseResult.Success -> {
                            // Skip newlines after separator
                            val afterSep = skipNewlines(sep.remaining)
                            // Try to match next item
                            when (val next = parser(afterSep)) {
                                is ParseResult.Success -> {
                                    results.add(next.value)
                                    currentStream = next.remaining
                                }
                                is ParseResult.Failure -> break // No more items
                            }
                        }
                        is ParseResult.Failure -> break // No separator, done
                    }
                }

                ParseResult.Success(results, currentStream)
            }
            is ParseResult.Failure -> ParseResult.Success(emptyList(), stream)
        }
    }

    // Custom sepEndBy that skips newlines after separators and allows trailing separator
    internal fun <T, S> sepEndByWithNewlines(parser: Parser<T>, separator: Parser<S>): Parser<List<T>> = { stream ->
        when (val first = parser(stream)) {
            is ParseResult.Success -> {
                val results = mutableListOf(first.value)
                var currentStream = first.remaining

                while (true) {
                    // Try to match separator
                    when (val sep = separator(currentStream)) {
                        is ParseResult.Success -> {
                            // Skip newlines after separator
                            val afterSep = skipNewlines(sep.remaining)
                            // Try to match next item
                            when (val next = parser(afterSep)) {
                                is ParseResult.Success -> {
                                    results.add(next.value)
                                    currentStream = next.remaining
                                }
                                is ParseResult.Failure -> {
                                    // Trailing separator is allowed - update position past separator
                                    currentStream = afterSep
                                    break
                                }
                            }
                        }
                        is ParseResult.Failure -> break // No separator, done
                    }
                }

                ParseResult.Success(results, currentStream)
            }
            is ParseResult.Failure -> ParseResult.Success(emptyList(), stream)
        }
    }


    internal val identifierParser: Parser<Token> = tok(TokenType.IDENTIFIER)


    // ===== STRING INTERPOLATION HELPERS =====

    /**
     * Check if a string contains unescaped $( for interpolation.
     * Returns true if there's a $( that is NOT preceded by a backslash.
     */
    internal fun hasUnescapedInterpolation(input: String): Boolean {
        var i = 0
        while (i < input.length - 1) {
            if (input[i] == '\\') {
                // Skip the next character (it's escaped)
                i += 2
            } else if (input[i] == '$' && input[i + 1] == '(') {
                return true
            } else {
                i++
            }
        }
        return false
    }

    /**
     * Process escape sequences in a string (e.g., \n, \t, \\, \")
     */
    internal fun processEscapeSequences(input: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < input.length) {
            if (input[i] == '\\' && i + 1 < input.length) {
                when (input[i + 1]) {
                    'n' -> { result.append('\n'); i += 2 }
                    't' -> { result.append('\t'); i += 2 }
                    'r' -> { result.append('\r'); i += 2 }
                    '\\' -> { result.append('\\'); i += 2 }
                    '"' -> { result.append('"'); i += 2 }
                    '$' -> { result.append('$'); i += 2 }  // Allow escaping $ to prevent interpolation
                    else -> { result.append(input[i]); i++ }
                }
            } else {
                result.append(input[i])
                i++
            }
        }
        return result.toString()
    }

    /**
     * Parse an interpolated string, extracting string parts and expression parts.
     * The input is the raw string content (escape sequences not yet processed).
     */
    internal fun parseInterpolatedString(content: String, stream: TokenStream, location: SourceLocation? = null): ParseResult<Expression> {
        val parts = mutableListOf<StringInterpolationPart>()
        var i = 0
        val currentStringPart = StringBuilder()

        while (i < content.length) {
            if (content[i] == '\\' && i + 1 < content.length) {
                // Handle escape sequence
                when (content[i + 1]) {
                    'n' -> { currentStringPart.append('\n'); i += 2 }
                    't' -> { currentStringPart.append('\t'); i += 2 }
                    'r' -> { currentStringPart.append('\r'); i += 2 }
                    '\\' -> { currentStringPart.append('\\'); i += 2 }
                    '"' -> { currentStringPart.append('"'); i += 2 }
                    '$' -> { currentStringPart.append('$'); i += 2 }  // Escaped $, not interpolation
                    else -> { currentStringPart.append(content[i]); i++ }
                }
            } else if (content[i] == '$' && i + 1 < content.length && content[i + 1] == '(') {
                // Found unescaped interpolation start
                // First, add any accumulated string part
                if (currentStringPart.isNotEmpty()) {
                    parts.add(StringPart(currentStringPart.toString()))
                    currentStringPart.clear()
                }

                // Find the matching closing paren
                i += 2  // Skip "$("
                val exprStart = i
                var parenDepth = 1

                while (i < content.length && parenDepth > 0) {
                    when {
                        content[i] == '\\' && i + 1 < content.length -> {
                            // Skip escaped character
                            i += 2
                        }
                        content[i] == '(' -> {
                            parenDepth++
                            i++
                        }
                        content[i] == ')' -> {
                            parenDepth--
                            if (parenDepth > 0) i++
                        }
                        content[i] == '"' -> {
                            // Skip over nested string
                            i++
                            while (i < content.length && content[i] != '"') {
                                if (content[i] == '\\' && i + 1 < content.length) {
                                    i += 2
                                } else {
                                    i++
                                }
                            }
                            if (i < content.length) i++  // Skip closing quote
                        }
                        else -> i++
                    }
                }

                val exprContent = content.substring(exprStart, i)
                if (i < content.length) i++  // Skip the closing )

                // Calculate line and column offset for error reporting
                var lineOffset = location?.line ?: 1
                var columnOffset = (location?.column ?: 1) + 1  // +1 for opening quote

                // Count newlines and track column before the interpolation
                for (j in 0 until exprStart) {
                    if (content[j] == '\n') {
                        lineOffset++
                        columnOffset = 1
                    } else {
                        columnOffset++
                    }
                }
                columnOffset += 2  // Add 2 for "$("

                // Parse the expression content
                val exprParser = FunkParser()
                try {
                    // We need to parse just the expression, so wrap it to make it a valid program
                    val exprProgram = exprParser.parse(exprContent, desugar = true, file = location?.file)
                    val expr = when (val firstItem = exprProgram.items.firstOrNull()) {
                        is TopLevelExpression -> firstItem.expression
                        is LetBinding -> firstItem.expression
                        else -> return ParseResult.Failure(
                            "Invalid expression in string interpolation: $exprContent",
                            stream.getSourcePosition()
                        )
                    }

                    // Fix source locations for better error reporting
                    val fixedExpr = fixInterpolationSourceLocation(expr, lineOffset, columnOffset, location?.file)
                    parts.add(ExpressionPart(fixedExpr))
                } catch (e: ParseException) {
                    return ParseResult.Failure(
                        "Failed to parse interpolated expression '$exprContent': ${e.message}",
                        stream.getSourcePosition()
                    )
                }
            } else {
                currentStringPart.append(content[i])
                i++
            }
        }

        // Add any remaining string part
        if (currentStringPart.isNotEmpty()) {
            parts.add(StringPart(currentStringPart.toString()))
        }

        // If we only have one StringPart, it's actually not interpolated (edge case after escape processing)
        if (parts.size == 1 && parts[0] is StringPart) {
            return ParseResult.Success(
                StringLiteral((parts[0] as StringPart).value, sourceLocation = location),
                stream
            )
        }

        return ParseResult.Success(
            StringLiteral(value = "", parts = parts, sourceLocation = location),
            stream
        )
    }
}

/**
 * Enhanced parse exception with detailed location and source context
 */
class ParseException(
    val errorMessage: String,
    val position: Int,
    val line: Int = 0,
    val column: Int = 0,
    val sourceCode: String? = null,
    val file: String? = null
) : Exception(formatParseError(errorMessage, position, line, column, sourceCode, file)) {

    companion object {
        private fun formatParseError(
            message: String,
            position: Int,
            line: Int,
            column: Int,
            sourceCode: String?,
            file: String?
        ): String {
            val sb = StringBuilder()

            sb.appendLine("Parse error: $message")

            // Add location info
            if (line > 0) {
                val fileInfo = file ?: "<input>"
                sb.appendLine("   --> $fileInfo:$line:$column")
            }

            // Add code snippet if available
            if (sourceCode != null && line > 0) {
                val lines = sourceCode.lines()
                if (line in 1..lines.size) {
                    val errorLineContent = lines[line - 1]
                    val lineNumStr = line.toString()
                    val padding = " ".repeat(lineNumStr.length)

                    sb.appendLine("$padding |")
                    sb.appendLine("$lineNumStr | $errorLineContent")

                    // Show error pointer
                    val pointer = " ".repeat(maxOf(0, column - 1)) + "^"
                    sb.appendLine("$padding | $pointer")
                    sb.appendLine("$padding |")
                }
            }

            return sb.toString().trim()
        }
    }
}
