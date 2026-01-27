package com.funk.language.parser.internal

import com.funk.language.ast.TypeAnnotation
import com.funk.language.parser.FunkParser
import com.funk.language.parser.internal.lib.*

/**
 * Helper for parsing associated type bindings in `having` clauses.
 *
 * Parses: having 'type1 = Type1, 'type2 = Type2, ...
 *
 * Returns a pair of (bindings map, remaining stream).
 * Extracted to eliminate code duplication across declaration parsers.
 */
internal data class AssociatedTypeBindingsResult(
    val bindings: Map<String, TypeAnnotation>,
    val stream: TokenStream
)

/**
 * Parse optional `having` clause with associated type bindings.
 *
 * @param stream The token stream to parse from
 * @param contextName The name of the declaration (for error messages)
 * @param contextType The type of declaration ("effect", "impl", "ability")
 * @return ParseResult containing the bindings and updated stream, or failure
 */
internal fun FunkParser.parseAssociatedTypeBindings(
    stream: TokenStream,
    contextName: String,
    contextType: String
): ParseResult<AssociatedTypeBindingsResult> {
    val havingToken = tok(TokenType.HAVING)(stream)

    return when (havingToken) {
        is ParseResult.Success -> {
            val streamAfterHaving = skipNewlines(havingToken.remaining)
            var currentStream = streamAfterHaving
            val bindings = mutableMapOf<String, TypeAnnotation>()

            // Parse first binding: 'typeVar = TypeAnnotation
            val firstTypeVar = identifierParser(currentStream)
            when (firstTypeVar) {
                is ParseResult.Failure -> {
                    return streamAfterHaving.failure(
                        "Expected type variable after 'having' keyword in $contextType declaration. " +
                        "Example: $contextType $contextName having 'state = Int where { ... }"
                    )
                }
                is ParseResult.Success -> {
                    // Validate it's a type variable (starts with ')
                    if (!firstTypeVar.value.text.startsWith("'")) {
                        return currentStream.failure(
                            "Associated type must be a type variable starting with '. " +
                            "Expected something like 'state, but got: ${firstTypeVar.value.text}"
                        )
                    }

                    val streamAfterVar = skipNewlines(firstTypeVar.remaining)
                    val eqToken = tok(TokenType.ASSIGN)(streamAfterVar)

                    when (eqToken) {
                        is ParseResult.Failure -> {
                            return streamAfterVar.failure(
                                "Expected '=' after type variable in having clause. " +
                                "Example: having 'state = Int"
                            )
                        }
                        is ParseResult.Success -> {
                            val streamAfterEq = skipNewlines(eqToken.remaining)
                            val typeAnn = typeAnnotationParser(streamAfterEq)

                            when (typeAnn) {
                                is ParseResult.Failure -> {
                                    return streamAfterEq.failure(
                                        "Expected type annotation after '=' in having clause. " +
                                        "Example: having 'state = Int"
                                    )
                                }
                                is ParseResult.Success -> {
                                    bindings[firstTypeVar.value.text] = typeAnn.value
                                    currentStream = skipNewlines(typeAnn.remaining)

                                    // Parse additional bindings separated by commas
                                    while (true) {
                                        val comma = tok(TokenType.COMMA)(currentStream)
                                        when (comma) {
                                            is ParseResult.Failure -> break  // No more bindings
                                            is ParseResult.Success -> {
                                                val streamAfterComma = skipNewlines(comma.remaining)
                                                val nextTypeVar = identifierParser(streamAfterComma)

                                                when (nextTypeVar) {
                                                    is ParseResult.Failure -> {
                                                        return streamAfterComma.failure(
                                                            "Expected type variable after ',' in having clause. " +
                                                            "Example: having 'state = Int, 'error = String"
                                                        )
                                                    }
                                                    is ParseResult.Success -> {
                                                        if (!nextTypeVar.value.text.startsWith("'")) {
                                                            return streamAfterComma.failure(
                                                                "Associated type must be a type variable starting with '. " +
                                                                "Expected something like 'error, but got: ${nextTypeVar.value.text}"
                                                            )
                                                        }

                                                        val streamAfterNextVar = skipNewlines(nextTypeVar.remaining)
                                                        val nextEq = tok(TokenType.ASSIGN)(streamAfterNextVar)

                                                        when (nextEq) {
                                                            is ParseResult.Failure -> {
                                                                return streamAfterNextVar.failure(
                                                                    "Expected '=' after type variable in having clause. " +
                                                                    "Example: having 'state = Int, 'error = String"
                                                                )
                                                            }
                                                            is ParseResult.Success -> {
                                                                val streamAfterNextEq = skipNewlines(nextEq.remaining)
                                                                val nextTypeAnn = typeAnnotationParser(streamAfterNextEq)

                                                                when (nextTypeAnn) {
                                                                    is ParseResult.Failure -> {
                                                                        return streamAfterNextEq.failure(
                                                                            "Expected type annotation after '=' in having clause. " +
                                                                            "Example: having 'state = Int, 'error = String"
                                                                        )
                                                                    }
                                                                    is ParseResult.Success -> {
                                                                        bindings[nextTypeVar.value.text] = nextTypeAnn.value
                                                                        currentStream = skipNewlines(nextTypeAnn.remaining)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    ParseResult.Success(
                                        AssociatedTypeBindingsResult(bindings, currentStream),
                                        currentStream
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        is ParseResult.Failure -> {
            // No 'having' clause, return empty bindings
            ParseResult.Success(
                AssociatedTypeBindingsResult(emptyMap(), stream),
                stream
            )
        }
    }
}
