package com.mezze.language.parser.internal

import com.mezze.language.ast.*
import com.mezze.language.parser.*

import com.mezze.language.parser.internal.lib.*
/**
 * Type annotation parsers for all type constructs.
 *
 * This file contains internal parsers for:
 * - Type annotations (main entry point)
 * - Function types ({ x: Int } -> String)
 * - Record types ({ x: Int, y: String })
 * - Variant types (Result 'a 'e)
 * - Array types ([Int])
 * - Effect row annotations (effects, effect variables)
 * - Ability constraints (with NumAdd 'a, Eq 'b)
 * - Type parameters and bounds
 *
 * All parsers return TypeAnnotation (sealed interface from AST).
 * All functions are internal - only used within the parser module.
 *
 * Part of the refactored parser module - previously in MezzeParser.kt.
 */

// Helper data class for effect row parsing result
private data class EffectRowParseResult(
    val effects: List<String>,
    val effectAnnotations: List<EffectAnnotation>,
    val isPolymorphic: Boolean,
    val isPerformed: Boolean,
    val stream: TokenStream
)

// Record type entry parser (helper for record type annotations)
internal val MezzeParser.recordTypeEntryParser: Parser<RecordTypeEntry>
    get() = { stream ->
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                val colon = tok(TokenType.COLON)((ident as ParseResult.Success).remaining)
                when (colon) {
                    is ParseResult.Success -> {
                        val typeAnnotation = typeAnnotationParser((colon as ParseResult.Success).remaining)
                        when (typeAnnotation) {
                            is ParseResult.Success -> ParseResult.Success(
                                RecordTypeEntry(ident.value.text, (typeAnnotation as ParseResult.Success).value),
                                typeAnnotation.remaining
                            )
                            is ParseResult.Failure -> typeAnnotation
                        }
                    }
                    is ParseResult.Failure -> {
                        // Provide a helpful error message when type annotation is missing
                        val fieldName = ident.value.text
                        val nextToken = ident.remaining.current()
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
                        ident.remaining.failure(message)
                    }
                }
            }
            is ParseResult.Failure -> ident
        }
    }

// Type annotation parser (main entry point)
internal val MezzeParser.typeAnnotationParser: Parser<TypeAnnotation>
    get() = { stream ->
        // Parse function types: T1 -> T2 -> T3
        functionTypeAnnotation(stream)
    }

// Function type annotation parser
internal val MezzeParser.functionTypeAnnotation: Parser<TypeAnnotation>
    get() = { stream ->
        // Parse: paramType with Abilities -> returnType <effects> or just primaryType
        var result: ParseResult<TypeAnnotation> = primaryTypeAnnotation(stream)

        while (result is ParseResult.Success) {
            var currentStream = result.remaining

            // Check for 'with' keyword BEFORE the arrow (ability constraints on parameter)
            val abilityConstraints = mutableListOf<FunctionAbilityConstraint>()
            val streamBeforeWith = skipNewlines(currentStream)
            if (streamBeforeWith.current()?.type == TokenType.WITH) {
                // Skip the 'with' keyword
                var abilityStream = skipNewlines(streamBeforeWith.advance())

                // Parse ability constraints: AbilityName TypeArg1 TypeArg2... or module::AbilityName TypeArg1...
                // Also supports: AbilityName TypeArg having 'item = Int, 'elem = String
                while (true) {
                    // Parse qualified ability name (e.g., module::MyAbility or just MyAbility)
                    val abilityNameResult = parseQualifiedName(abilityStream)
                    if (abilityNameResult !is ParseResult.Success) break
                    val abilityName = abilityNameResult.value
                    abilityStream = skipNewlines(abilityNameResult.remaining)

                    // Parse type arguments (space-separated types)
                    // CRITICAL FIX: Use atomicTypeAnnotation instead of primaryTypeAnnotation
                    // to avoid parsing type applications (e.g., "Float Int" as a single type)
                    // We want "Convert Float Int" to be parsed as Convert with 2 args, not 1
                    val typeArgs = mutableListOf<TypeAnnotation>()
                    while (true) {
                        val typeResult = atomicTypeAnnotation(abilityStream)
                        when (typeResult) {
                            is ParseResult.Success -> {
                                typeArgs.add(typeResult.value)
                                abilityStream = skipNewlines(typeResult.remaining)

                                // Stop if we see ARROW, comma, WITH, or HAVING
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

                    // Check for 'having' keyword (associated types)
                    val associatedTypeBindings = mutableMapOf<String, TypeAnnotation>()
                    if (abilityStream.current()?.type == TokenType.HAVING) {
                        abilityStream = skipNewlines(abilityStream.advance())

                        // Parse associated type bindings: 'typeVar = Type
                        while (true) {
                            val typeVarResult = identifierParser(abilityStream)
                            if (typeVarResult !is ParseResult.Success) break
                            val typeVarName = typeVarResult.value.text
                            if (!typeVarName.startsWith("'")) break  // Not a type variable
                            abilityStream = skipNewlines(typeVarResult.remaining)

                            // Expect '='
                            if (abilityStream.current()?.type != TokenType.ASSIGN) break
                            abilityStream = skipNewlines(abilityStream.advance())

                            // Parse the type annotation (atomic to avoid consuming too much)
                            val typeResult = atomicTypeAnnotation(abilityStream)
                            if (typeResult !is ParseResult.Success) break
                            associatedTypeBindings[typeVarName] = typeResult.value
                            abilityStream = skipNewlines(typeResult.remaining)

                            // Check for comma
                            if (abilityStream.current()?.type == TokenType.COMMA) {
                                val afterComma = skipNewlines(abilityStream.advance())
                                val nextToken = afterComma.current()

                                // Lookahead disambiguation:
                                // If next token is an identifier starting with ', it's another binding
                                // If next token is uppercase identifier, it's a new ability
                                if (nextToken?.type == TokenType.IDENTIFIER && nextToken.text.startsWith("'")) {
                                    // Another type variable binding
                                    abilityStream = afterComma
                                    continue
                                } else {
                                    // New ability - don't consume comma here
                                    break
                                }
                            } else {
                                break
                            }
                        }
                    }

                    abilityConstraints.add(FunctionAbilityConstraint(abilityName, typeArgs, associatedTypeBindings))

                    // Check for comma (more constraints)
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
                        // Check for effect row: <> or <EffectName> or <Effect1, Effect2> or <...> or <Effect1, ...>
                        val streamAfterReturn = skipNewlines(returnType.remaining)
                        val ltToken = streamAfterReturn.current()

                        val effectRowResult = if (ltToken?.type == TokenType.LT) {
                            // Parse effect row
                            val afterLt = streamAfterReturn.advance()
                            val gtToken = afterLt.current()

                            if (gtToken?.type == TokenType.GT) {
                                // Empty effect row <>
                                EffectRowParseResult(emptyList(), emptyList(), false, false, afterLt.advance())
                            } else {
                                // Check for purely polymorphic <...>
                                val firstToken = skipNewlines(afterLt).current()
                                if (firstToken?.type == TokenType.ELLIPSIS) {
                                    val afterEllipsis = skipNewlines(afterLt.advance())
                                    if (afterEllipsis.current()?.type == TokenType.GT) {
                                        // Polymorphic effect row <...>
                                        EffectRowParseResult(emptyList(), emptyList(), true, false, afterEllipsis.advance())
                                    } else {
                                        EffectRowParseResult(emptyList(), emptyList(), false, false, returnType.remaining)  // Invalid, keep original
                                    }
                                } else if (firstToken?.type == TokenType.STAR) {
                                    // Performed marker only <*>
                                    val afterStar = skipNewlines(afterLt.advance())
                                    if (afterStar.current()?.type == TokenType.GT) {
                                        EffectRowParseResult(emptyList(), emptyList(), false, true, afterStar.advance())
                                    } else {
                                        EffectRowParseResult(emptyList(), emptyList(), false, false, returnType.remaining)  // Invalid, keep original
                                    }
                                } else {
                                    // Has effect names <Effect1, Effect2> or <module::Effect1, ...> or <Effect1, *>
                                    // Also supports: <Effect1 having 'a = Int, Effect2>
                                    val simpleEffectNames = mutableListOf<String>()
                                    val effectAnnotations = mutableListOf<EffectAnnotation>()
                                    var current = skipNewlines(afterLt)
                                    var foundEllipsis = false
                                    var foundPerformed = false

                                    // Parse comma-separated effects (potentially with 'having' clauses)
                                    while (true) {
                                        // Parse qualified effect name (e.g., module::Log or just Log)
                                        val effectNameResult = parseQualifiedName(current)
                                        if (effectNameResult !is ParseResult.Success) break
                                        val effectName = effectNameResult.value
                                        current = skipNewlines(effectNameResult.remaining)

                                        // Check for 'having' keyword (associated types)
                                        if (current.current()?.type == TokenType.HAVING) {
                                            current = skipNewlines(current.advance())
                                            val associatedTypes = mutableMapOf<String, TypeAnnotation>()

                                            // Parse associated type bindings: 'typeVar = Type
                                            while (true) {
                                                val typeVarResult = identifierParser(current)
                                                if (typeVarResult !is ParseResult.Success) break
                                                val typeVarName = typeVarResult.value.text
                                                if (!typeVarName.startsWith("'")) break  // Not a type variable
                                                current = skipNewlines(typeVarResult.remaining)

                                                // Expect '='
                                                if (current.current()?.type != TokenType.ASSIGN) break
                                                current = skipNewlines(current.advance())

                                                // Parse the type annotation (atomic to avoid consuming too much)
                                                val typeResult = atomicTypeAnnotation(current)
                                                if (typeResult !is ParseResult.Success) break
                                                associatedTypes[typeVarName] = typeResult.value
                                                current = skipNewlines(typeResult.remaining)

                                                // Check for comma
                                                if (current.current()?.type == TokenType.COMMA) {
                                                    val afterComma = skipNewlines(current.advance())
                                                    val nextToken = afterComma.current()

                                                    // Lookahead disambiguation:
                                                    // If next token is an identifier starting with ', it's another binding
                                                    // If next token is uppercase identifier, it's a new effect
                                                    // If next token is ... or *, it's a row extension/performed marker
                                                    if (nextToken?.type == TokenType.IDENTIFIER && nextToken.text.startsWith("'")) {
                                                        // Another type variable binding
                                                        current = afterComma
                                                        continue
                                                    } else {
                                                        // New effect or special marker - don't consume comma here
                                                        break
                                                    }
                                                } else {
                                                    break
                                                }
                                            }

                                            effectAnnotations.add(EffectAnnotation(effectName, associatedTypes))
                                        } else {
                                            // Simple effect without associated types
                                            simpleEffectNames.add(effectName)
                                        }

                                        // Check for comma (next effect)
                                        if (current.current()?.type == TokenType.COMMA) {
                                            current = skipNewlines(current.advance())

                                            // Check if next is ... (effect extension)
                                            if (current.current()?.type == TokenType.ELLIPSIS) {
                                                foundEllipsis = true
                                                current = skipNewlines(current.advance())
                                                break
                                            }
                                            // Check if next is * (performed marker) - must be last
                                            if (current.current()?.type == TokenType.STAR) {
                                                foundPerformed = true
                                                current = skipNewlines(current.advance())
                                                break
                                            }
                                        } else {
                                            break
                                        }
                                    }

                                    // Expect closing >
                                    if (current.current()?.type == TokenType.GT) {
                                        EffectRowParseResult(simpleEffectNames, effectAnnotations, foundEllipsis, foundPerformed, current.advance())
                                    } else {
                                        EffectRowParseResult(emptyList(), emptyList(), false, false, returnType.remaining)  // No closing >, keep original
                                    }
                                }
                            }
                        } else {
                            EffectRowParseResult(emptyList(), emptyList(), false, false, returnType.remaining)
                        }

                        // Also check for 'with' keyword AFTER the return type (for backward compatibility)
                        // Ability constraints can appear either before or after the arrow
                        // Also supports: AbilityName TypeArg having 'item = Int, 'elem = String
                        var streamAfterWith = effectRowResult.stream
                        val streamBeforeWithAfter = skipNewlines(effectRowResult.stream)
                        if (streamBeforeWithAfter.current()?.type == TokenType.WITH) {
                            // Skip the 'with' keyword
                            var abilityStreamAfter = skipNewlines(streamBeforeWithAfter.advance())

                            // Parse ability constraints: AbilityName TypeArg1 TypeArg2... or module::AbilityName TypeArg1...
                            while (true) {
                                // Parse qualified ability name (e.g., module::MyAbility or just MyAbility)
                                val abilityNameResult = parseQualifiedName(abilityStreamAfter)
                                if (abilityNameResult !is ParseResult.Success) break
                                val abilityName = abilityNameResult.value
                                abilityStreamAfter = skipNewlines(abilityNameResult.remaining)

                                // Parse type arguments (space-separated types)
                                // CRITICAL FIX: Use atomicTypeAnnotation instead of primaryTypeAnnotation
                                // to avoid parsing type applications (e.g., "Float Int" as a single type)
                                val typeArgs = mutableListOf<TypeAnnotation>()
                                while (true) {
                                    val typeResult = atomicTypeAnnotation(abilityStreamAfter)
                                    when (typeResult) {
                                        is ParseResult.Success -> {
                                            typeArgs.add(typeResult.value)
                                            abilityStreamAfter = skipNewlines(typeResult.remaining)

                                            // Stop if we see =, comma, WITH, or HAVING (another constraint)
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

                                // Check for 'having' keyword (associated types)
                                val associatedTypeBindingsAfter = mutableMapOf<String, TypeAnnotation>()
                                if (abilityStreamAfter.current()?.type == TokenType.HAVING) {
                                    abilityStreamAfter = skipNewlines(abilityStreamAfter.advance())

                                    // Parse associated type bindings: 'typeVar = Type
                                    while (true) {
                                        val typeVarResult = identifierParser(abilityStreamAfter)
                                        if (typeVarResult !is ParseResult.Success) break
                                        val typeVarName = typeVarResult.value.text
                                        if (!typeVarName.startsWith("'")) break  // Not a type variable
                                        abilityStreamAfter = skipNewlines(typeVarResult.remaining)

                                        // Expect '='
                                        if (abilityStreamAfter.current()?.type != TokenType.ASSIGN) break
                                        abilityStreamAfter = skipNewlines(abilityStreamAfter.advance())

                                        // Parse the type annotation (atomic to avoid consuming too much)
                                        val typeResult = atomicTypeAnnotation(abilityStreamAfter)
                                        if (typeResult !is ParseResult.Success) break
                                        associatedTypeBindingsAfter[typeVarName] = typeResult.value
                                        abilityStreamAfter = skipNewlines(typeResult.remaining)

                                        // Check for comma
                                        if (abilityStreamAfter.current()?.type == TokenType.COMMA) {
                                            val afterComma = skipNewlines(abilityStreamAfter.advance())
                                            val nextToken = afterComma.current()

                                            // Lookahead disambiguation:
                                            // If next token is an identifier starting with ', it's another binding
                                            // If next token is uppercase identifier, it's a new ability
                                            if (nextToken?.type == TokenType.IDENTIFIER && nextToken.text.startsWith("'")) {
                                                // Another type variable binding
                                                abilityStreamAfter = afterComma
                                                continue
                                            } else {
                                                // New ability - don't consume comma here
                                                break
                                            }
                                        } else {
                                            break
                                        }
                                    }
                                }

                                abilityConstraints.add(FunctionAbilityConstraint(abilityName, typeArgs, associatedTypeBindingsAfter))

                                // Check for comma (more constraints)
                                if (abilityStreamAfter.current()?.type == TokenType.COMMA) {
                                    abilityStreamAfter = skipNewlines(abilityStreamAfter.advance())
                                } else {
                                    break
                                }
                            }

                            streamAfterWith = abilityStreamAfter
                        }

                        ParseResult.Success(
                            FunctionTypeAnnotation(
                                result.value,
                                returnType.value,
                                if (effectRowResult.effects.isNotEmpty() || effectRowResult.effectAnnotations.isNotEmpty() || effectRowResult.isPolymorphic || effectRowResult.isPerformed) {
                                    EffectRowAnnotation(
                                        effects = effectRowResult.effects,
                                        effectAnnotations = effectRowResult.effectAnnotations,
                                        isPolymorphic = effectRowResult.isPolymorphic,
                                        isPerformed = effectRowResult.isPerformed
                                    )
                                } else null,
                                abilityConstraints
                            ),
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
internal val MezzeParser.seqTypeAnnotation: Parser<TypeAnnotation>
    get() = { stream ->
        // Parse: [ElementType]
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
                            is ParseResult.Success -> ParseResult.Success(
                                SeqTypeAnnotation(elementType.value),
                                rbracket.remaining
                            )
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
internal val MezzeParser.primaryTypeAnnotation: Parser<TypeAnnotation>
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
 * Used for ability constraint type arguments to avoid parsing "Float Int" as a type application.
 */
internal val MezzeParser.atomicTypeAnnotation: Parser<TypeAnnotation>
    get() = { stream ->
        Parsers.oneOf(
            seqTypeAnnotation,      // Allow [Int], [Float], etc.
            parenTypeAnnotation,       // Allow (Int), (Float), etc.
            recordTypeAnnotationParser, // Allow {x: Int}, etc.
            identifierTypeAnnotation   // Allow Int, Float, String, 'a, etc.
            // NOTE: Deliberately exclude typeApplicationAnnotation to prevent "Float Int" from being parsed as Float(Int)
        )(stream)
    }

// Parenthesized type annotation parser
internal val MezzeParser.parenTypeAnnotation: Parser<TypeAnnotation>
    get() = { stream ->
        // Parse: (Type)
        val lparen = tok(TokenType.LPAREN)(stream)
        when (lparen) {
            is ParseResult.Success -> {
                val typeAnn = typeAnnotationParser(lparen.remaining)
                when (typeAnn) {
                    is ParseResult.Success -> {
                        val rparen = tok(TokenType.RPAREN)(typeAnn.remaining)
                        when (rparen) {
                            is ParseResult.Success -> ParseResult.Success(
                                typeAnn.value,
                                rparen.remaining
                            )
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
internal val MezzeParser.recordTypeAnnotationParser: Parser<TypeAnnotation>
    get() = { stream ->
        // Parse: {field1: Type1, field2: Type2} or {field1: Type1, ...}
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

                        // If we parsed zero entries but see an identifier, it means we failed to parse a record type entry
                        // This happens when someone writes { fieldName } instead of { fieldName: Type }
                        if (entries.value.isEmpty() && stream2.current()?.type == TokenType.IDENTIFIER) {
                            val fieldName = stream2.current()?.text ?: "field"
                            val message = "Record type field '$fieldName' is missing a type annotation.\n" +
                                    "Expected format: { $fieldName: Type }"
                            stream2.failure(message)
                        } else {
                            // Check for optional comma before ellipsis
                            val afterComma = if (stream2.current()?.type == TokenType.COMMA) {
                                skipNewlines(stream2.advance())
                            } else {
                                stream2
                            }

                            // Check for ellipsis (open record marker: ...)
                            val restField = if (afterComma.current()?.type == TokenType.ELLIPSIS) {
                                "..."  // Marker for open record
                            } else {
                                null  // Closed record
                            }

                            val stream3 = if (restField != null) {
                                skipNewlines(afterComma.advance())  // Skip the ellipsis token
                            } else {
                                afterComma
                            }

                            val rbrace = tok(TokenType.RBRACE)(stream3)

                            when (rbrace) {
                                is ParseResult.Success -> ParseResult.Success(
                                    RecordTypeAnnotation(entries.value, restField),
                                    rbrace.remaining
                                )
                                is ParseResult.Failure -> {
                                    // Provide helpful error when closing brace is not found
                                    val currentToken = stream3.current()
                                    when (currentToken?.type) {
                                        TokenType.IDENTIFIER -> {
                                            // Most likely a field without type annotation
                                            val fieldName = currentToken.text
                                            val message = "Record type field '$fieldName' is missing a type annotation.\n" +
                                                    "Expected format: { $fieldName: Type }"
                                            stream3.failure(message)
                                        }
                                        TokenType.COMMA -> {
                                            // Trailing comma or malformed entry after comma
                                            val message = "Unexpected comma in record type. " +
                                                    "Record type fields must have format: { field: Type }"
                                            stream3.failure(message)
                                        }
                                        else -> {
                                            // Generic error
                                            val message = "Expected '}' to close record type, but got ${tokenGotDescription(currentToken)}. " +
                                                    "Record type format: { field: Type }"
                                            stream3.failure(message)
                                        }
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
internal val MezzeParser.variantTypeAnnotationParser: Parser<TypeAnnotation>
    get() = { stream ->
        // Parse: | Constructor1 { field: Type }, Constructor2, ... |
        val pipe1 = tok(TokenType.PIPE)(stream)
        when (pipe1) {
            is ParseResult.Success -> {
                val stream1 = skipNewlines(pipe1.remaining)

                // Parse constructors separated by commas
                val constructors = mutableListOf<VariantTypeConstructor>()
                var currentStream = stream1
                var error: ParseResult.Failure? = null

                while (currentStream.current()?.type == TokenType.IDENTIFIER && error == null) {
                    val constructorName = identifierParser(currentStream)
                    when (constructorName) {
                        is ParseResult.Success -> {
                            val stream2 = skipNewlines(constructorName.remaining)

                            // Check for optional data type (record type)
                            if (stream2.current()?.type == TokenType.LBRACE) {
                                val recordType = recordTypeAnnotationParser(stream2)
                                when (recordType) {
                                    is ParseResult.Success -> {
                                        constructors.add(VariantTypeConstructor(constructorName.value.text, recordType.value))
                                        currentStream = skipNewlines(recordType.remaining)
                                    }
                                    is ParseResult.Failure -> {
                                        error = recordType
                                    }
                                }
                            } else {
                                constructors.add(VariantTypeConstructor(constructorName.value.text, null))
                                currentStream = skipNewlines(stream2)
                            }

                            // Check for comma (more constructors) only if no error
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

                // Return error if encountered during parsing
                if (error != null) {
                    error
                } else {
                    // Check for ellipsis (...) to indicate open variant
                    val isOpen = if (currentStream.current()?.type == TokenType.ELLIPSIS) {
                        currentStream = skipNewlines(currentStream.advance())
                        true
                    } else {
                        false
                    }

                    // Expect closing pipe
                    val pipe2 = tok(TokenType.PIPE)(currentStream)
                    when (pipe2) {
                        is ParseResult.Success -> ParseResult.Success(
                            VariantTypeAnnotation(constructors, isOpen),
                            pipe2.remaining
                        )
                        is ParseResult.Failure -> pipe2
                    }
                }
            }
            is ParseResult.Failure -> pipe1
        }
    }

// Simple type argument parser (for type applications)
internal val MezzeParser.simpleTypeArgument: Parser<TypeAnnotation>
    get() = { stream ->
        // Parse simple type arguments (identifiers, parens, variants, arrays, records, not type applications)
        Parsers.oneOf(
            variantTypeAnnotationParser,
            seqTypeAnnotation,
            parenTypeAnnotation,
            recordTypeAnnotationParser,
            identifierTypeAnnotation
        )(stream)
    }

// Type application annotation parser
// Handles: Constructor Arg1 Arg2 ... (e.g., Option Int, List String)
// Also handles: module::Type Arg1 Arg2 ... (e.g., codec::Entry String Json)
internal val MezzeParser.typeApplicationAnnotation: Parser<TypeAnnotation>
    get() = { stream ->
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> {
                // Check for qualified path (module::Type)
                var currentStream = ident.remaining
                val pathSegments = mutableListOf(ident.value.text)

                while (true) {
                    val doubleColon = tok(TokenType.DOUBLE_COLON)(currentStream)
                    if (doubleColon is ParseResult.Success) {
                        val nextIdent = identifierParser(doubleColon.remaining)
                        if (nextIdent is ParseResult.Success) {
                            pathSegments.add(nextIdent.value.text)
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

                // Check if this is a type constructor (capitalized)
                if (typeName[0].isUpperCase()) {
                    // Try to parse type arguments
                    val args = mutableListOf<TypeAnnotation>()

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

                    if (isQualified) {
                        // Qualified type: codec::Entry String Json
                        ParseResult.Success(
                            QualifiedTypeAnnotation(modulePath, typeName, args),
                            currentStream
                        )
                    } else if (args.isNotEmpty()) {
                        ParseResult.Success(
                            TypeApplicationAnnotation(typeName, args),
                            currentStream
                        )
                    } else {
                        // No args, just an identifier type
                        ParseResult.Success(
                            IdentifierTypeAnnotation(typeName, SourceLocation(ident.value.line, ident.value.column, length = typeName.length)),
                            currentStream
                        )
                    }
                } else {
                    // Lowercase identifier, just a type variable or simple type
                    ParseResult.Success(
                        IdentifierTypeAnnotation(typeName, SourceLocation(ident.value.line, ident.value.column, length = typeName.length)),
                        currentStream
                    )
                }
            }
            is ParseResult.Failure -> ident
        }
    }

// Identifier type annotation parser
internal val MezzeParser.identifierTypeAnnotation: Parser<TypeAnnotation>
    get() = { stream ->
        val ident = identifierParser(stream)
        when (ident) {
            is ParseResult.Success -> ParseResult.Success(
                IdentifierTypeAnnotation(ident.value.text, SourceLocation(ident.value.line, ident.value.column, length = ident.value.text.length)),
                ident.remaining
            )
            is ParseResult.Failure -> ident
        }
    }
