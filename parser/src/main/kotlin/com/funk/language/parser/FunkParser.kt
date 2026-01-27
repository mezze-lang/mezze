package com.funk.language.parser

import com.funk.language.ast.*
import com.funk.language.parser.internal.*

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
    private var currentSource: String? = null
    private var currentFile: String? = null

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

    private fun keyword(word: String, type: TokenType): Parser<Token> =
        Parsers.token({ it.type == type && it.text == word }, word)

    // ===== CRITICAL: Lambda vs Record Disambiguation =====

    /**
     * This is the key to solving the ambiguity!
     *
     * We explicitly check for ARROW after RBRACE to distinguish:
     * - {x} -> body  (lambda)
     * - {x}          (record literal with punning)
     */
    private val lambdaOrRecordExpression: Parser<Expression> = { stream ->
        if (stream.current()?.type != TokenType.LBRACE) {
            ParseResult.Failure("Expected LBRACE", stream.getSourcePosition())
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

    /**
     * Lookahead function to check if there's an arrow after the closing brace.
     * This is the explicit disambiguation mechanism.
     */
    private fun lookAheadForArrowAfterBrace(stream: TokenStream): Boolean {
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

    // ===== LAMBDA EXPRESSION =====

    private val lambdaExpression: Parser<Expression> = { stream ->
        // Parse {params} -> body
        val lbrace = tok(TokenType.LBRACE)(stream)
        if (lbrace is ParseResult.Failure) {
            lbrace
        } else {
            val lbraceSuccess = lbrace as ParseResult.Success
            val lambdaLocation = tokenLocation(lbraceSuccess.value, currentFile)
            val stream1 = skipNewlines(lbraceSuccess.remaining)

            // Parse parameters - could be typed or untyped
            val paramsResult = lambdaParameters(stream1)

            when (paramsResult) {
                is ParseResult.Success -> {
                    val stream2 = skipNewlines(paramsResult.remaining)
                    val rbrace = tok(TokenType.RBRACE)(stream2)

                    when (rbrace) {
                        is ParseResult.Success -> {
                            val stream3 = skipNewlines((rbrace as ParseResult.Success).remaining)
                            val arrow = tok(TokenType.ARROW)(stream3)

                            when (arrow) {
                                is ParseResult.Success -> {
                                    val stream4 = skipNewlines((arrow as ParseResult.Success).remaining)
                                    val body = expression(stream4)

                                    when (body) {
                                        is ParseResult.Success -> ParseResult.Success(
                                            LambdaExpression(paramsResult.value, (body as ParseResult.Success).value, sourceLocation = lambdaLocation),
                                            body.remaining
                                        )
                                        is ParseResult.Failure -> body
                                    }
                                }
                                is ParseResult.Failure -> arrow
                            }
                        }
                        is ParseResult.Failure -> rbrace
                    }
                }
                is ParseResult.Failure -> paramsResult
            }
        }
    }

    private val lambdaParameters: Parser<LambdaParameters> = { stream ->
        // Try typed parameters first (they have colons)
        val typedResult = typedLambdaParams(stream)
        when (typedResult) {
            is ParseResult.Success -> typedResult
            is ParseResult.Failure -> untypedLambdaParams(stream)
        }
    }

    // Parser for a single lambda parameter entry with optional type annotation
    // Accepts: x, x: Int, x: String, etc.
    private val lambdaParameterEntry: Parser<Pair<String, TypeAnnotation?>> = { stream ->
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                val paramName = (ident as ParseResult.Success).value.text
                val stream1 = ident.remaining

                // Check if there's a colon (type annotation)
                if (stream1.current()?.type == TokenType.COLON) {
                    // Parse type annotation
                    val colon = tok(TokenType.COLON)(stream1)
                    when (colon) {
                        is ParseResult.Success -> {
                            val typeAnnotation = typeAnnotationParser((colon as ParseResult.Success).remaining)
                            when (typeAnnotation) {
                                is ParseResult.Success -> {
                                    ParseResult.Success(
                                        Pair(paramName, (typeAnnotation as ParseResult.Success).value),
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

    private val typedLambdaParams: Parser<LambdaParameters> = { stream ->
        // {x: Int, y, z: String,} - allows mixed typed/untyped parameters with optional trailing comma
        // At least one parameter must have a type annotation for this to be considered typed
        val entries = Parsers.sepEndBy(lambdaParameterEntry, tok(TokenType.COMMA))(stream)
        when (entries) {
            is ParseResult.Success -> {
                val entryList = (entries as ParseResult.Success).value
                if (entryList.isEmpty()) {
                    // No parameters found
                    ParseResult.Failure("Expected typed parameters", stream.getSourcePosition())
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
                        ParseResult.Failure("Expected at least one typed parameter", stream.getSourcePosition())
                    }
                }
            }
            is ParseResult.Failure -> entries
        }
    }

    private val untypedLambdaParams: Parser<LambdaParameters> = { stream ->
        // {x, y,} or {x, y, ...,} or {} - allows optional trailing comma
        val params = Parsers.sepEndBy(identifierParser, tok(TokenType.COMMA))(stream)
        when (params) {
            is ParseResult.Success -> {
                val names = (params as ParseResult.Success).value.map { it.text }
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

    private val recordLiteral: Parser<Expression> = { stream ->
        // {x = 1, y = 2} or {x, y} (punning) or {...rest} or {x = 1, ...} (anonymous open record)
        // Supports multiline with newlines between entries
        val lbrace = tok(TokenType.LBRACE)(stream)
        when (lbrace) {
            is ParseResult.Success -> {
                val recordLocation = tokenLocation(lbrace.value, currentFile)
                val stream1 = skipNewlines((lbrace as ParseResult.Success).remaining)

                // Use custom sepEndBy that skips newlines after commas and allows trailing commas
                val entries = sepEndByWithNewlines(
                    recordLiteralEntryParser,
                    tok(TokenType.COMMA)
                )(stream1)

                when (entries) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines((entries as ParseResult.Success).remaining)

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
                                (rbrace as ParseResult.Success).remaining
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

    // Custom sepBy that skips newlines after separators
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

    /**
     * Parse many items separated by newlines (no explicit separator required)
     * Used for ability operations and similar constructs
     */
    private fun <T> manyWithNewlines(parser: Parser<T>): Parser<List<T>> = { stream ->
        val results = mutableListOf<T>()
        var currentStream = skipNewlines(stream)
        var lastPosition = currentStream.position

        while (true) {
            when (val item = parser(currentStream)) {
                is ParseResult.Success -> {
                    results.add(item.value)
                    currentStream = skipNewlines(item.remaining)

                    // Safety check: ensure we're making progress
                    if (currentStream.position <= lastPosition) {
                        break // No progress, avoid infinite loop
                    }
                    lastPosition = currentStream.position
                }
                is ParseResult.Failure -> break
            }
        }

        ParseResult.Success(results, currentStream)
    }

    private val recordLiteralEntryParser: Parser<RecordLiteralEntry> = { stream ->
        // ... (spread), x = value, or x (punning)
        val current = stream.current()

        if (current?.type == TokenType.ELLIPSIS) {
            // Spread syntax
            val ellipsis = tok(TokenType.ELLIPSIS)(stream)
            when (ellipsis) {
                is ParseResult.Success -> {
                    val expr = expression((ellipsis as ParseResult.Success).remaining)
                    when (expr) {
                        is ParseResult.Success -> ParseResult.Success(
                            RecordSpreadEntry((expr as ParseResult.Success).value),
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
                    val stream1 = (ident as ParseResult.Success).remaining

                    // Check for ASSIGN
                    val assign = tok(TokenType.ASSIGN)(stream1)
                    when (assign) {
                        is ParseResult.Success -> {
                            // x = value
                            val expr = expression((assign as ParseResult.Success).remaining)
                            when (expr) {
                                is ParseResult.Success -> ParseResult.Success(
                                    RecordFieldEntry(ident.value.text, (expr as ParseResult.Success).value),
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

    private val arrayLiteral: Parser<Expression> = { stream ->
        // Parse: [element1, element2, ...]
        // Also supports spread: [1, ...rest, 3]
        val arrayLocation = stream.current()?.let { tokenLocation(it, currentFile) }  // Capture [ token location
        val lbracket = tok(TokenType.LBRACKET)(stream)
        when (lbracket) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(lbracket.remaining)

                // Check for empty array []
                val rbracket = tok(TokenType.RBRACKET)(stream1)
                if (rbracket is ParseResult.Success) {
                    ParseResult.Success(
                        ArrayLiteral(emptyList(), sourceLocation = arrayLocation),
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
                            val stream2 = skipNewlines(elements.remaining)
                            val rbracket2 = tok(TokenType.RBRACKET)(stream2)

                            when (rbracket2) {
                                is ParseResult.Success -> ParseResult.Success(
                                    ArrayLiteral(elements.value, sourceLocation = arrayLocation),
                                    rbracket2.remaining
                                )
                                is ParseResult.Failure -> rbracket2
                            }
                        }
                        is ParseResult.Failure -> elements
                    }
                }
            }
            is ParseResult.Failure -> lbracket
        }
    }

    private val arrayElementParser: Parser<Expression> = { stream ->
        // Array spread operator (...) is not yet implemented
        // For now, just parse regular expressions
        expression(stream)
    }

    // ===== PRIMARY EXPRESSION =====

    private val primaryExpression: Parser<Expression> = { stream ->
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

    private val ifExpression: Parser<Expression> = { stream ->
        // Parse: if <condition> then <thenBranch> else <elseBranch>
        val ifLocation = stream.current()?.let { tokenLocation(it, currentFile) }  // Capture 'if' location
        val ifToken = tok(TokenType.IF)(stream)
        when (ifToken) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(ifToken.remaining)
                val condition = expression(stream1)

                when (condition) {
                    is ParseResult.Success -> {
                        val stream2 = skipNewlines(condition.remaining)
                        val thenToken = tok(TokenType.THEN)(stream2)

                        when (thenToken) {
                            is ParseResult.Success -> {
                                val stream3 = skipNewlines(thenToken.remaining)
                                val thenBranch = expression(stream3)

                                when (thenBranch) {
                                    is ParseResult.Success -> {
                                        val stream4 = skipNewlines(thenBranch.remaining)
                                        val elseToken = tok(TokenType.ELSE)(stream4)

                                        when (elseToken) {
                                            is ParseResult.Success -> {
                                                val stream5 = skipNewlines(elseToken.remaining)
                                                val elseBranch = expression(stream5)

                                                when (elseBranch) {
                                                    is ParseResult.Success -> ParseResult.Success(
                                                        IfExpression(
                                                            condition.value,
                                                            thenBranch.value,
                                                            elseBranch.value,
                                                            sourceLocation = ifLocation
                                                        ),
                                                        elseBranch.remaining
                                                    )
                                                    is ParseResult.Failure -> elseBranch
                                                }
                                            }
                                            is ParseResult.Failure -> elseToken
                                        }
                                    }
                                    is ParseResult.Failure -> thenBranch
                                }
                            }
                            is ParseResult.Failure -> thenToken
                        }
                    }
                    is ParseResult.Failure -> condition
                }
            }
            is ParseResult.Failure -> ifToken
        }
    }

    // ===== PERFORM EXPRESSION =====

    private val performExpression: Parser<Expression> = { stream ->
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

    private val doExpression: Parser<Expression> = { stream ->
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
                    ParseResult.Failure("Expected result expression in do block", currentStream.getSourcePosition())
                }
            }
            is ParseResult.Failure -> {
                doToken
            }
        }
    }

    // ===== MATCH EXPRESSION =====

    private val matchExpression: Parser<Expression> = { stream ->
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

    private val matchCase: Parser<MatchCase> = { stream ->
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
    private val expression: Parser<Expression> = { stream ->
        logicalOrExpression(stream)
    }

    // Logical OR operator: ||
    private val logicalOrExpression: Parser<Expression> = { stream ->
        var result: ParseResult<Expression> = logicalAndExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = (result as ParseResult.Success).remaining
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
    private val logicalAndExpression: Parser<Expression> = { stream ->
        var result: ParseResult<Expression> = pipeExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = (result as ParseResult.Success).remaining
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
    private fun desugarPipe(pipeValue: Expression, target: Expression): Expression {
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
                elements = target.elements.map { desugarPipe(pipeValue, it) }
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
    private val pipeExpression: Parser<Expression> = { stream ->
        var result: ParseResult<Expression> = comparisonExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = (result as ParseResult.Success).remaining
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
    private val comparisonExpression: Parser<Expression> = { stream ->
        var result: ParseResult<Expression> = additiveExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = (result as ParseResult.Success).remaining
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
    private val additiveExpression: Parser<Expression> = { stream ->
        var result: ParseResult<Expression> = multiplicativeExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = (result as ParseResult.Success).remaining
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
    private val multiplicativeExpression: Parser<Expression> = { stream ->
        var result: ParseResult<Expression> = unaryExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = (result as ParseResult.Success).remaining
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
    private val unaryExpression: Parser<Expression> = { stream ->
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
                            ParseResult.Failure("Invalid fraction literal: ${literalToken.text}", streamAfterMinus.getSourcePosition())
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
    private val postfixExpression: Parser<Expression> = { stream ->
        var result: ParseResult<Expression> = primaryExpression(stream)

        while (result is ParseResult.Success) {
            val currentStream = (result as ParseResult.Success).remaining
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
                    val funcExpr = (result as ParseResult.Success).value
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
                    val funcExpr = (result as ParseResult.Success).value
                    if (funcExpr is Identifier || funcExpr is MemberAccess) {
                        var checkStream = currentStream.advance()  // Skip first newline
                        // Skip any additional newlines
                        while (checkStream.current()?.type == TokenType.NEWLINE) {
                            checkStream = checkStream.advance()
                        }
                        // Check if next non-newline token is LBRACE
                        if (checkStream.current()?.type == TokenType.LBRACE) {
                            // IMPORTANT: Only error if the { would be parsed as function arguments
                            // If it's a lambda (has -> after brace), don't error
                            // This prevents false positives in match expressions where we have:
                            //   { pattern } -> body
                            //   { pattern } -> body
                            // The body ends with an identifier, then newline, then { starts next pattern
                            if (!lookAheadForArrowAfterBrace(checkStream)) {
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
                    // For complex expressions (like `Show.show {val = @}` followed by newline and identifier),
                    // skip this check as the identifier is likely a separate statement
                    val funcExpr = (result as ParseResult.Success).value
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
                    }
                    // For non-identifier expressions, treat following identifier as separate statement
                    break
                }
                TokenType.STRING, TokenType.INTEGER, TokenType.FLOAT, TokenType.FRACTION, TokenType.BOOLEAN -> {
                    // Function call with literal argument without {} syntax
                    val funcExpr = (result as ParseResult.Success).value
                    if (funcExpr is Identifier || funcExpr is MemberAccess) {
                        val funcName = when (funcExpr) {
                            is Identifier -> funcExpr.name
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
                        throw createParseException(
                            "Function calls must use {} syntax. Instead of '$funcName $literalType', " +
                            "use '$funcName { <field> = $literalType }' with named arguments.",
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

    internal val identifierParser: Parser<Token> = tok(TokenType.IDENTIFIER)

    // @ placeholder for pipe operator
    private val atPlaceholderExpression: Parser<Expression> = { stream ->
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

    private val identifierExpression: Parser<Expression> = { stream ->
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

    private val literalExpression: Parser<Expression> = { stream ->
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
                    ParseResult.Failure("Invalid fraction literal: ${token.text}", stream.getSourcePosition())
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
            else -> ParseResult.Failure("Expected literal", stream.getSourcePosition())
        }
    }

    private val parenExpression: Parser<Expression> = { stream ->
        Parsers.between(
            tok(TokenType.LPAREN),
            expression,
            tok(TokenType.RPAREN)
        )(stream)
    }

    // ===== STRING INTERPOLATION HELPERS =====

    /**
     * Check if a string contains unescaped $( for interpolation.
     * Returns true if there's a $( that is NOT preceded by a backslash.
     */
    private fun hasUnescapedInterpolation(input: String): Boolean {
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
    private fun processEscapeSequences(input: String): String {
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
    private fun parseInterpolatedString(content: String, stream: TokenStream, location: SourceLocation? = null): ParseResult<Expression> {
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
