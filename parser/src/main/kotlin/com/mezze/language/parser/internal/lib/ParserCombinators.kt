package com.mezze.language.parser.internal.lib

import com.mezze.language.parser.*
import com.mezze.language.parser.internal.Token
import com.mezze.language.parser.internal.TokenType

/**
 * Parser combinator framework for Mezze language.
 *
 * This provides a flexible, type-safe way to build parsers with explicit control
 * over disambiguation and lookahead.
 */

// Result of parsing
sealed class ParseResult<out T> {
    data class Success<T>(val value: T, val remaining: TokenStream) : ParseResult<T>()
    data class Failure(
        val message: String,
        val position: Int,
        val line: Int = 0,
        val column: Int = 0
    ) : ParseResult<Nothing>()
}

// Token stream with position tracking
data class TokenStream(
    val tokens: List<Token>,
    val position: Int = 0
) {
    fun current(): Token? = tokens.getOrNull(position)
    fun peek(offset: Int = 0): Token? = tokens.getOrNull(position + offset)
    fun advance(count: Int = 1): TokenStream = copy(position = position + count)
    fun isAtEnd(): Boolean = position >= tokens.size

    fun getSourcePosition(): Int = current()?.position ?: tokens.lastOrNull()?.position ?: 0
    fun getSourceLine(): Int = current()?.line ?: tokens.lastOrNull()?.line ?: 1
    fun getSourceColumn(): Int = current()?.column ?: tokens.lastOrNull()?.column ?: 1

    /**
     * Create a ParseResult.Failure with full location info from current stream position
     */
    fun failure(message: String): ParseResult.Failure = ParseResult.Failure(
        message,
        getSourcePosition(),
        getSourceLine(),
        getSourceColumn()
    )

    /**
     * Create a ParseResult.Failure at a specific token's location
     */
    fun failureAt(message: String, token: Token): ParseResult.Failure = ParseResult.Failure(
        message,
        token.position,
        token.line,
        token.column
    )
}

// Parser type
typealias Parser<T> = (TokenStream) -> ParseResult<T>

// Core combinators
object Parsers {

    // Always succeeds with given value
    fun <T> pure(value: T): Parser<T> = { stream ->
        ParseResult.Success(value, stream)
    }

    // Always fails
    fun <T> fail(message: String): Parser<T> = { stream ->
        stream.failure(message)
    }

    // Match a specific token type
    fun token(predicate: (Token) -> Boolean, description: String = "token"): Parser<Token> = { stream ->
        val current = stream.current()
        if (current != null && predicate(current)) {
            ParseResult.Success(current, stream.advance())
        } else {
            val gotDesc = describeToken(current)
            stream.failure("Expected $description but got $gotDesc")
        }
    }

    /**
     * Describe a token for error messages in a user-friendly way.
     */
    private fun describeToken(token: Token?): String = when {
        token == null -> "end of file"
        token.type == TokenType.IDENTIFIER -> "'${token.text}'"
        token.type == TokenType.INTEGER -> "number '${token.text}'"
        token.type == TokenType.NAT -> "number '${token.text}n'"
        token.type == TokenType.FLOAT -> "number '${token.text}'"
        token.type == TokenType.FRACTION -> "fraction '${token.text}'"
        token.type == TokenType.STRING -> "string"
        token.type == TokenType.NEWLINE -> "newline"
        token.type == TokenType.EOF -> "end of file"
        // For delimiters and operators, show the actual text
        token.type in listOf(
            TokenType.LPAREN, TokenType.RPAREN, TokenType.LBRACE, TokenType.RBRACE,
            TokenType.LBRACKET, TokenType.RBRACKET, TokenType.COMMA, TokenType.DOT,
            TokenType.COLON, TokenType.ARROW, TokenType.ASSIGN,
            TokenType.ELLIPSIS, TokenType.DOUBLE_COLON, TokenType.AT, TokenType.PIPE,
            TokenType.PIPE_OP, TokenType.PLUS, TokenType.MINUS, TokenType.STAR,
            TokenType.SLASH, TokenType.PERCENT, TokenType.EQ, TokenType.NEQ,
            TokenType.LT, TokenType.GT, TokenType.LTE, TokenType.GTE,
            TokenType.AND, TokenType.OR, TokenType.NOT, TokenType.PLACEHOLDER
        ) -> "'${token.text}'"
        // For keywords, show the keyword
        token.type in listOf(
            TokenType.LET, TokenType.TYPE, TokenType.NEWTYPE, TokenType.IF,
            TokenType.THEN, TokenType.ELSE, TokenType.MATCH, TokenType.IS,
            TokenType.DO, TokenType.EFFECT, TokenType.IMPL, TokenType.PERFORM,
            TokenType.ABILITY, TokenType.PUB, TokenType.FOR, TokenType.WHERE,
            TokenType.WITH, TokenType.IN, TokenType.USE, TokenType.HAVING, TokenType.FORALL
        ) -> "keyword '${token.text}'"
        else -> "'${token.text}'"
    }

    // Sequence: parse first, then second
    fun <A, B> seq(first: Parser<A>, second: Parser<B>): Parser<Pair<A, B>> = { stream ->
        when (val r1 = first(stream)) {
            is ParseResult.Success -> when (val r2 = second(r1.remaining)) {
                is ParseResult.Success -> ParseResult.Success(Pair(r1.value, r2.value), r2.remaining)
                is ParseResult.Failure -> r2
            }
            is ParseResult.Failure -> r1
        }
    }

    // Choice: try first parser, if it fails try second
    fun <T> choice(first: Parser<T>, second: Parser<T>): Parser<T> = { stream ->
        when (val r1 = first(stream)) {
            is ParseResult.Success -> r1
            is ParseResult.Failure -> second(stream)
        }
    }

    // Choice with multiple alternatives
    // Returns the error from the parser that made the most progress (consumed most input)
    fun <T> oneOf(vararg parsers: Parser<T>): Parser<T> = { stream ->
        var bestFailure: ParseResult.Failure? = null
        var result: ParseResult<T>? = null
        for (parser in parsers) {
            when (val r = parser(stream)) {
                is ParseResult.Success -> {
                    result = r
                    break
                }
                is ParseResult.Failure -> {
                    // Keep the failure that made the most progress
                    if (bestFailure == null || r.position > bestFailure.position) {
                        bestFailure = r
                    }
                }
            }
        }
        result ?: (bestFailure ?: stream.failure("No alternatives matched"))
    }

    // Map: transform the result
    fun <A, B> map(parser: Parser<A>, transform: (A) -> B): Parser<B> = { stream ->
        when (val result = parser(stream)) {
            is ParseResult.Success -> ParseResult.Success(transform(result.value), result.remaining)
            is ParseResult.Failure -> result
        }
    }

    // FlatMap: monadic bind
    fun <A, B> flatMap(parser: Parser<A>, f: (A) -> Parser<B>): Parser<B> = { stream ->
        when (val result = parser(stream)) {
            is ParseResult.Success -> f(result.value)(result.remaining)
            is ParseResult.Failure -> result
        }
    }

    // Optional: parse if possible, otherwise return null
    fun <T> optional(parser: Parser<T>): Parser<T?> = { stream ->
        when (val result = parser(stream)) {
            is ParseResult.Success -> result
            is ParseResult.Failure -> ParseResult.Success(null, stream)
        }
    }

    // Many: zero or more occurrences
    fun <T> many(parser: Parser<T>): Parser<List<T>> = { stream ->
        val results = mutableListOf<T>()
        var currentStream = stream

        while (true) {
            when (val result = parser(currentStream)) {
                is ParseResult.Success -> {
                    results.add(result.value)
                    currentStream = result.remaining
                }
                is ParseResult.Failure -> break
            }
        }

        ParseResult.Success(results, currentStream)
    }

    // Many1: one or more occurrences
    fun <T> many1(parser: Parser<T>): Parser<List<T>> = { stream ->
        when (val first = parser(stream)) {
            is ParseResult.Success -> {
                val rest = many(parser)(first.remaining)
                when (rest) {
                    is ParseResult.Success -> ParseResult.Success(listOf(first.value) + rest.value, rest.remaining)
                    is ParseResult.Failure -> rest
                }
            }
            is ParseResult.Failure -> first
        }
    }

    // Separated by: parse items separated by a separator
    fun <T, S> sepBy(parser: Parser<T>, separator: Parser<S>): Parser<List<T>> = { stream ->
        when (val first = parser(stream)) {
            is ParseResult.Success -> {
                val rest = many(seq(separator, parser))(first.remaining)
                when (rest) {
                    is ParseResult.Success -> {
                        val items = listOf(first.value) + rest.value.map { it.second }
                        ParseResult.Success(items, rest.remaining)
                    }
                    is ParseResult.Failure -> ParseResult.Success(listOf(first.value), first.remaining)
                }
            }
            is ParseResult.Failure -> ParseResult.Success(emptyList(), stream)
        }
    }

    // Separated by with optional trailing separator: parse items separated by a separator, allowing trailing separator
    fun <T, S> sepEndBy(parser: Parser<T>, separator: Parser<S>): Parser<List<T>> = { stream ->
        when (val first = parser(stream)) {
            is ParseResult.Success -> {
                val rest = many(seq(separator, parser))(first.remaining)
                when (rest) {
                    is ParseResult.Success -> {
                        val items = listOf(first.value) + rest.value.map { it.second }
                        // Allow optional trailing separator
                        val afterTrailing = optional(separator)(rest.remaining)
                        when (afterTrailing) {
                            is ParseResult.Success -> ParseResult.Success(items, afterTrailing.remaining)
                            is ParseResult.Failure -> ParseResult.Success(items, rest.remaining)
                        }
                    }
                    is ParseResult.Failure -> {
                        // Allow optional trailing separator after first item
                        val afterTrailing = optional(separator)(first.remaining)
                        when (afterTrailing) {
                            is ParseResult.Success -> ParseResult.Success(listOf(first.value), afterTrailing.remaining)
                            is ParseResult.Failure -> ParseResult.Success(listOf(first.value), first.remaining)
                        }
                    }
                }
            }
            is ParseResult.Failure -> ParseResult.Success(emptyList(), stream)
        }
    }

    // Lookahead: check if parser would succeed without consuming input
    fun <T> lookahead(parser: Parser<T>): Parser<Boolean> = { stream ->
        when (parser(stream)) {
            is ParseResult.Success -> ParseResult.Success(true, stream)  // Don't advance!
            is ParseResult.Failure -> ParseResult.Success(false, stream)
        }
    }

    // Between: parse something between two delimiters
    fun <L, T, R> between(left: Parser<L>, parser: Parser<T>, right: Parser<R>): Parser<T> = { stream ->
        flatMap(left) { _ ->
            flatMap(parser) { value ->
                map(right) { _ -> value }
            }
        }(stream)
    }
}

// Note: Extension functions (.then, .or, .map, .flatMap, .optional, .many, .many1)
// were removed as they were never used - the codebase uses Parsers.* methods directly.
