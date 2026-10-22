package com.mezze.language.parser.internal

import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.internal.lib.*

/**
 * A quote: `` `( … ) ``.
 *
 * The quoted code is ordinary Mezze, parsed by the ordinary parsers. Splices are
 * rewritten to names first — `~x` to the name `~x`, `~( … )` to `~(N)` with the
 * expression kept in [ParsedQuote.splices] — because a name is accepted
 * everywhere a splice can stand, so not one other parser needs to know about
 * splices. [QuoteBuilder] then turns the result into the code that builds it.
 *
 * What is quoted is declarations when it starts like one (`let`, `type`, `impl`,
 * …), and one expression otherwise.
 */
internal val MezzeParser.quoteExpression: Parser<ParsedExpr>
    get() = parser@{ stream ->
        val tick = stream.current()
        if (tick?.type != TokenType.BACKTICK) return@parser stream.failure("Expected '`'")
        val file = currentFile ?: "<unknown>"
        val tokens = stream.tokens
        val open = stream.position + 1
        if (tokens.getOrNull(open)?.type != TokenType.LPAREN) {
            throw malformed("a quote is a backtick followed by parentheses: `( … )", stream.advance(1))
        }
        val close = matchingParen(tokens, open)
            ?: throw malformed("this quote's `(` has no matching `)`", stream.advance(1))

        val splices = mutableListOf<ParsedExpr>()
        val rewritten = ArrayList<Token>(close - open)
        var i = open + 1
        while (i < close) {
            val t = tokens[i]
            when (t.type) {
                TokenType.BACKTICK -> throw malformed("quotes do not nest", TokenStream(tokens, i))
                TokenType.TILDE -> {
                    val next = tokens.getOrNull(i + 1)
                    when (next?.type) {
                        TokenType.IDENTIFIER -> {
                            rewritten += Token(TokenType.IDENTIFIER, QuoteBuilder.SPLICE + next.text, t.position, t.line, t.column)
                            i += 2
                        }
                        TokenType.LPAREN -> {
                            val end = matchingParen(tokens, i + 1)?.takeIf { it < close }
                                ?: throw malformed("this splice's `(` has no matching `)`", TokenStream(tokens, i + 1))
                            splices += spliceExpression(tokens, i + 2, end)
                            rewritten += Token(TokenType.IDENTIFIER, "${QuoteBuilder.SPLICE}(${splices.size - 1})", t.position, t.line, t.column)
                            i = end + 1
                        }
                        else -> throw malformed(
                            "`~` splices a name, `~x`, or an expression, `~( … )`",
                            TokenStream(tokens, i + 1),
                        )
                    }
                }
                else -> { rewritten += t; i++ }
            }
        }
        val closing = tokens[close]
        rewritten += Token(TokenType.EOF, "", closing.position, closing.line, closing.column)

        val inner = TokenStream(rewritten)
        val first = rewritten.firstOrNull { it.type != TokenType.NEWLINE && it.type != TokenType.COMMENT }
        val code: ParsedCode = if (first != null && first.type in DECLARATION_STARTS) {
            when (val module = parsedModuleParser(inner)) {
                is ParseResult.Success -> ParsedCode.Decls(resolveParsedImplDeclarations(module.value).decls)
                is ParseResult.Failure -> return@parser module.copy(isFatal = true)
            }
        } else {
            when (val e = expression(skipNewlines(inner))) {
                is ParseResult.Success -> {
                    val rest = skipNewlines(e.remaining)
                    if (rest.current()?.type != TokenType.EOF) {
                        throw malformed("a quote holds one expression, or declarations; this is more", rest)
                    }
                    ParsedCode.Expr(e.value)
                }
                is ParseResult.Failure -> return@parser e.copy(isFatal = true)
            }
        }
        val loc = spanLoc(tick, closing, file)
        ParseResult.Success(
            ParsedQuote(code, splices, QuoteBuilder(splices, loc).build(code), loc),
            stream.advance(close - stream.position + 1),
        )
    }

/** `~( … )`: the expression between [from] and [until], which must be all of it. */
private fun MezzeParser.spliceExpression(tokens: List<Token>, from: Int, until: Int): ParsedExpr {
    val end = tokens[until]
    val slice = tokens.subList(from, until) + Token(TokenType.EOF, "", end.position, end.line, end.column)
    if (slice.any { it.type == TokenType.TILDE }) {
        throw malformed("a splice is ordinary code: `~` inside `~( … )` means nothing", TokenStream(tokens, from))
    }
    return when (val e = expression(skipNewlines(TokenStream(slice)))) {
        is ParseResult.Success -> {
            val rest = skipNewlines(e.remaining)
            if (rest.current()?.type != TokenType.EOF) throw malformed("`~( … )` splices one expression", rest)
            e.value
        }
        is ParseResult.Failure -> throw com.mezze.language.parser.ParseException(
            e.message, e.position, e.line, e.column, currentSource, currentFile, code = e.code, hints = e.hints,
        )
    }
}

/**
 * `name!( … )` after a (possibly qualified) name: a macro call, whose input is
 * the raw text the lexer captured. Null when no macro input follows.
 */
internal fun MezzeParser.macroCallAfter(name: ParsedIdentifier, stream: TokenStream): ParseResult.Success<ParsedExpr>? {
    val input = stream.current()?.takeIf { it.type == TokenType.MACRO_INPUT } ?: return null
    val file = currentFile ?: "<unknown>"
    val inputLoc = tokenLoc(input, file)
    // The call ends at the `)` just after the input.
    val end = Loc(file, inputLoc.endLine, inputLoc.endCol + 1, inputLoc.endLine, inputLoc.endCol + 1)
    return ParseResult.Success(
        ParsedMacroCall(name, input.text, inputLoc, null, name.loc.spanTo(end)),
        stream.advance(),
    )
}

/** The index of the `)` matching the `(` at [open], or null. */
private fun matchingParen(tokens: List<Token>, open: Int): Int? {
    var depth = 0
    for (i in open until tokens.size) {
        when (tokens[i].type) {
            TokenType.LPAREN -> depth++
            TokenType.RPAREN -> { depth--; if (depth == 0) return i }
            TokenType.EOF -> return null
            else -> {}
        }
    }
    return null
}

private fun MezzeParser.malformed(message: String, at: TokenStream) =
    createParseException(message, at, currentSource, currentFile, code = ErrorCode.P0136_MALFORMED_QUOTE)

private val DECLARATION_STARTS = setOf(
    TokenType.LET, TokenType.TYPE, TokenType.NEWTYPE, TokenType.EFFECT, TokenType.ABILITY,
    TokenType.IMPL, TokenType.USE, TokenType.PUB,
)
