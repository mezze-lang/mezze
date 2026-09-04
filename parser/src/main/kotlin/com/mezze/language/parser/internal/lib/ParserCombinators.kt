package com.mezze.language.parser.internal.lib

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parser.*
import com.mezze.language.parser.internal.Token
import com.mezze.language.parser.internal.TokenType

sealed class ParseResult<out T> {
    data class Success<T>(val value: T, val remaining: TokenStream) : ParseResult<T>()
    data class Failure(
        val message: String,
        val position: Int,
        val line: Int = 0,
        val column: Int = 0,
        // When true, oneOf stops trying further alternatives and propagates this
        // failure as-is. Used by parsers that have committed to a path and
        // produced a targeted error (e.g. "'++' is not a valid operator" after
        // consuming a successful left operand) — such errors describe the real
        // problem and should not be hidden by a competing alternative's
        // shallower "Expected X but got Y" message.
        val isFatal: Boolean = false,
        // Stable error code (Phase B migration). Defaults to UNCATEGORIZED so
        // existing call sites that haven't been migrated still compile.
        val code: ErrorCode = ErrorCode.UNCATEGORIZED,
        // Optional list of "= hint:" lines rendered after the source snippet.
        val hints: List<String> = emptyList()
    ) : ParseResult<Nothing>()
}

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

    fun failure(
        message: String,
        code: ErrorCode = ErrorCode.UNCATEGORIZED,
        hints: List<String> = emptyList()
    ): ParseResult.Failure = ParseResult.Failure(
        message,
        getSourcePosition(),
        getSourceLine(),
        getSourceColumn(),
        code = code,
        hints = hints
    )
}

typealias Parser<T> = (TokenStream) -> ParseResult<T>

object Parsers {

    fun token(predicate: (Token) -> Boolean, description: String = "token"): Parser<Token> = { stream ->
        val current = stream.current()
        if (current != null && predicate(current)) {
            ParseResult.Success(current, stream.advance())
        } else {
            val gotDesc = tokenGotDescription(current)
            stream.failure("Expected $description but got $gotDesc")
        }
    }

    fun <T> oneOf(vararg parsers: Parser<T>): Parser<T> = { stream ->
        var bestFailure: ParseResult.Failure? = null
        var fatal: ParseResult.Failure? = null
        var result: ParseResult<T>? = null
        for (parser in parsers) {
            when (val r = parser(stream)) {
                is ParseResult.Success -> {
                    result = r
                    break
                }
                is ParseResult.Failure -> {
                    if (r.isFatal) {
                        // Committed-path error — stop trying alternatives so
                        // we don't bury it under a competing "Expected X" miss.
                        fatal = r
                        break
                    }
                    if (bestFailure == null || r.position > bestFailure.position) {
                        bestFailure = r
                    }
                }
            }
        }
        result ?: fatal ?: bestFailure ?: stream.failure("No alternatives matched")
    }

    private fun <T> optional(parser: Parser<T>): Parser<T?> = { stream ->
        when (val result = parser(stream)) {
            is ParseResult.Success -> result
            is ParseResult.Failure -> ParseResult.Success(null, stream)
        }
    }

    private fun <A, B> seq(first: Parser<A>, second: Parser<B>): Parser<Pair<A, B>> = { stream ->
        when (val r1 = first(stream)) {
            is ParseResult.Success -> when (val r2 = second(r1.remaining)) {
                is ParseResult.Success -> ParseResult.Success(Pair(r1.value, r2.value), r2.remaining)
                is ParseResult.Failure -> r2
            }
            is ParseResult.Failure -> r1
        }
    }

    private fun <T> many(parser: Parser<T>): Parser<List<T>> = { stream ->
        val results = mutableListOf<T>()
        var currentStream = stream

        while (true) {
            when (val result = parser(currentStream)) {
                is ParseResult.Success -> {
                    // Progress guard: a sub-parser that succeeds WITHOUT consuming
                    // input (e.g. `optional`, or an empty-matching parser) would
                    // spin here forever. Stop the moment an iteration doesn't
                    // advance the stream — the anti-hang invariant for `many`.
                    if (result.remaining.position == currentStream.position) break
                    results.add(result.value)
                    currentStream = result.remaining
                }
                is ParseResult.Failure -> break
            }
        }

        ParseResult.Success(results, currentStream)
    }

    fun <T, S> sepEndBy(parser: Parser<T>, separator: Parser<S>): Parser<List<T>> = { stream ->
        when (val first = parser(stream)) {
            is ParseResult.Success -> {
                val rest = many(seq(separator, parser))(first.remaining)
                when (rest) {
                    is ParseResult.Success -> {
                        val items = listOf(first.value) + rest.value.map { it.second }
                        val afterTrailing = optional(separator)(rest.remaining)
                        when (afterTrailing) {
                            is ParseResult.Success -> ParseResult.Success(items, afterTrailing.remaining)
                            is ParseResult.Failure -> ParseResult.Success(items, rest.remaining)
                        }
                    }
                    is ParseResult.Failure -> {
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
}
