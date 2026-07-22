package com.mezze.language.parser.internal

import com.mezze.language.parser.*

data class Token(
    val type: TokenType,
    val text: String,
    val position: Int,  // Character position in source
    val line: Int,      // Line number
    val column: Int     // Column number
)

enum class TokenType {
    // Keywords
    LET, TYPE, NEWTYPE, IF, THEN, ELSE, MATCH, IS, DO, EFFECT, IMPL, PERFORM, ABILITY, PUB, FOR, WHERE, WITH, IN, USE, HAVING,

    // Literals
    INTEGER, NAT, FLOAT, FRACTION, STRING, BOOLEAN,

    // Identifiers
    IDENTIFIER,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ, NEQ, LT, GT, LTE, GTE,
    AND, OR, NOT,
    PIPE, PIPE_OP, DOUBLE_COLON,  // PIPE is |, PIPE_OP is |>
    BIND,  // <- for do-block bind statements (Tryable.try desugar)

    // Delimiters
    LPAREN, RPAREN,
    LBRACE, RBRACE,
    LBRACKET, RBRACKET,
    COMMA, DOT, COLON,
    ARROW, ASSIGN, ELLIPSIS,
    PLACEHOLDER,  // _ as expression placeholder
    AT,  // @ for pipe operator placeholder

    // Special
    NEWLINE, EOF, ERROR,

    // Comments (for formatter)
    COMMENT
}

private object InternedTokens {
    // Single-character tokens
    const val PLUS = "+"
    const val MINUS = "-"
    const val STAR = "*"
    const val SLASH = "/"
    const val PERCENT = "%"
    const val LT = "<"
    const val GT = ">"
    const val NOT = "!"
    const val PIPE = "|"
    const val AT = "@"
    const val LPAREN = "("
    const val RPAREN = ")"
    const val LBRACE = "{"
    const val RBRACE = "}"
    const val LBRACKET = "["
    const val RBRACKET = "]"
    const val COMMA = ","
    const val DOT = "."
    const val COLON = ":"
    const val ASSIGN = "="
    const val NEWLINE = "\n"
    const val PLACEHOLDER = "_"
    const val EMPTY = ""

    // Multi-character operators
    const val ARROW = "->"
    const val BIND = "<-"
    const val DOUBLE_COLON = "::"
    const val EQ = "=="
    const val NEQ = "!="
    const val LTE = "<="
    const val GTE = ">="
    const val AND = "&&"
    const val PIPE_OP = "|>"
    const val OR = "||"
    const val ELLIPSIS = "..."

    // Keywords (interned)
    const val LET = "let"
    const val TYPE = "type"
    const val NEWTYPE = "newtype"
    const val IF = "if"
    const val THEN = "then"
    const val ELSE = "else"
    const val MATCH = "match"
    const val IS = "is"
    const val DO = "do"
    const val EFFECT = "effect"
    const val IMPL = "impl"
    const val PERFORM = "perform"
    const val ABILITY = "ability"
    const val PUB = "pub"
    const val FOR = "for"
    const val WHERE = "where"
    const val WITH = "with"
    const val IN = "in"
    const val USE = "use"
    const val HAVING = "having"

    // Common identifiers
    const val TRUE = "True"
    const val FALSE = "False"
    const val SOME = "Some"
    const val NONE = "None"
    const val OK = "Ok"
    const val ERR = "Err"

    // Map for quick lookup of interned identifiers
    val commonIdentifiers = setOf(TRUE, FALSE, SOME, NONE, OK, ERR)
}

/**
 * @param startLine 1-based line of the first character of [source] in its
 *   containing file. Used when the lexer is re-invoked on a sub-slice (e.g.
 *   the contents of a `$(...)` interpolation) so token locs come out with
 *   the original file coordinates instead of `1:1`. Defaults to 1 for the
 *   normal top-level file path.
 * @param startColumn 1-based column of the first character of [source].
 *   Same rationale as [startLine]. Note: the column offset only applies to
 *   the first line of the slice — after the first newline, subsequent
 *   columns reset to 1 (the slice is now in its own line space).
 */
class Lexer(
    private val source: String,
    startLine: Int = 1,
    startColumn: Int = 1,
) {
    private var position = 0
    private var line = startLine
    private var column = startColumn

    companion object {
        // Static keywords map shared across all Lexer instances
        private val keywords = mapOf(
            InternedTokens.LET to TokenType.LET,
            InternedTokens.TYPE to TokenType.TYPE,
            InternedTokens.NEWTYPE to TokenType.NEWTYPE,
            InternedTokens.IF to TokenType.IF,
            InternedTokens.THEN to TokenType.THEN,
            InternedTokens.ELSE to TokenType.ELSE,
            InternedTokens.MATCH to TokenType.MATCH,
            InternedTokens.IS to TokenType.IS,
            InternedTokens.DO to TokenType.DO,
            InternedTokens.EFFECT to TokenType.EFFECT,
            InternedTokens.IMPL to TokenType.IMPL,
            InternedTokens.PERFORM to TokenType.PERFORM,
            InternedTokens.ABILITY to TokenType.ABILITY,
            InternedTokens.PUB to TokenType.PUB,
            InternedTokens.FOR to TokenType.FOR,
            InternedTokens.WHERE to TokenType.WHERE,
            InternedTokens.WITH to TokenType.WITH,
            InternedTokens.IN to TokenType.IN,
            InternedTokens.USE to TokenType.USE,
            InternedTokens.HAVING to TokenType.HAVING
            // Note: Booleans are True/False (uppercase variant constructors), not keywords
        )
    }

    fun tokenize(): List<Token> {
        // Pre-allocate with estimated capacity (source length / 5 is a rough estimate)
        val estimatedTokens = (source.length / 5).coerceAtLeast(16)
        val tokens = ArrayList<Token>(estimatedTokens)

        while (!isAtEnd()) {
            skipWhitespace()
            if (isAtEnd()) break

            val token = nextToken()
            tokens.add(token)  // Always add, including ERROR tokens — parser handles recovery
        }

        tokens.add(Token(TokenType.EOF, InternedTokens.EMPTY, position, line, column))
        return tokens
    }

    private fun nextToken(): Token {
        val start = position
        val startLine = line
        val startColumn = column

        val char = current()

        // Note: advance() already increments line and resets column for newlines
        if (char == '\n') {
            advance()
            return Token(TokenType.NEWLINE, InternedTokens.NEWLINE, start, startLine, startColumn)
        }

        // Numbers
        if (char.isDigit()) {
            return lexNumber(start, startLine, startColumn)
        }

        // Strings
        if (char == '"') {
            return lexString(start, startLine, startColumn)
        }

        // Type variables: 'a, 'b, etc.
        if (char == '\'' && !isAtEnd() && (peek().isLetter() || peek() == '_')) {
            return lexTypeVariable(start, startLine, startColumn)
        }

        // Identifiers and keywords
        if (char.isLetter() || char == '_') {
            return lexIdentifierOrKeyword(start, startLine, startColumn)
        }

        // Multi-character operators (use interned strings)
        if (char == '-' && peek() == '>') {
            advance(2)
            return Token(TokenType.ARROW, InternedTokens.ARROW, start, startLine, startColumn)
        }

        if (char == ':' && peek() == ':') {
            advance(2)
            return Token(TokenType.DOUBLE_COLON, InternedTokens.DOUBLE_COLON, start, startLine, startColumn)
        }

        if (char == '=' && peek() == '=') {
            advance(2)
            return Token(TokenType.EQ, InternedTokens.EQ, start, startLine, startColumn)
        }

        if (char == '!' && peek() == '=') {
            advance(2)
            return Token(TokenType.NEQ, InternedTokens.NEQ, start, startLine, startColumn)
        }

        if (char == '<' && peek() == '=') {
            advance(2)
            return Token(TokenType.LTE, InternedTokens.LTE, start, startLine, startColumn)
        }

        if (char == '<' && peek() == '-') {
            advance(2)
            return Token(TokenType.BIND, InternedTokens.BIND, start, startLine, startColumn)
        }

        if (char == '>' && peek() == '=') {
            advance(2)
            return Token(TokenType.GTE, InternedTokens.GTE, start, startLine, startColumn)
        }

        if (char == '&' && peek() == '&') {
            advance(2)
            return Token(TokenType.AND, InternedTokens.AND, start, startLine, startColumn)
        }

        if (char == '|' && peek() == '>') {
            advance(2)
            return Token(TokenType.PIPE_OP, InternedTokens.PIPE_OP, start, startLine, startColumn)
        }

        if (char == '|' && peek() == '|') {
            advance(2)
            return Token(TokenType.OR, InternedTokens.OR, start, startLine, startColumn)
        }

        if (char == '.' && peek() == '.' && peek(2) == '.') {
            advance(3)
            return Token(TokenType.ELLIPSIS, InternedTokens.ELLIPSIS, start, startLine, startColumn)
        }

        // Comments - emit as COMMENT tokens for formatter
        if (char == '#') {
            return lexComment(start, startLine, startColumn)
        }

        // Single character tokens (use interned strings)
        val (type, text) = when (char) {
            '+' -> TokenType.PLUS to InternedTokens.PLUS
            '-' -> TokenType.MINUS to InternedTokens.MINUS
            '*' -> TokenType.STAR to InternedTokens.STAR
            '/' -> TokenType.SLASH to InternedTokens.SLASH
            '%' -> TokenType.PERCENT to InternedTokens.PERCENT
            '<' -> TokenType.LT to InternedTokens.LT
            '>' -> TokenType.GT to InternedTokens.GT
            '!' -> TokenType.NOT to InternedTokens.NOT
            '|' -> TokenType.PIPE to InternedTokens.PIPE
            '@' -> TokenType.AT to InternedTokens.AT
            '(' -> TokenType.LPAREN to InternedTokens.LPAREN
            ')' -> TokenType.RPAREN to InternedTokens.RPAREN
            '{' -> TokenType.LBRACE to InternedTokens.LBRACE
            '}' -> TokenType.RBRACE to InternedTokens.RBRACE
            '[' -> TokenType.LBRACKET to InternedTokens.LBRACKET
            ']' -> TokenType.RBRACKET to InternedTokens.RBRACKET
            ',' -> TokenType.COMMA to InternedTokens.COMMA
            '.' -> TokenType.DOT to InternedTokens.DOT
            ':' -> TokenType.COLON to InternedTokens.COLON
            '=' -> TokenType.ASSIGN to InternedTokens.ASSIGN
            else -> TokenType.ERROR to char.toString()
        }

        advance()
        return Token(type, text, start, startLine, startColumn)
    }

    private fun lexNumber(start: Int, startLine: Int, startColumn: Int): Token {
        while (!isAtEnd() && (current().isDigit() || current() == '_')) {
            advance()
        }

        if (!isAtEnd() && position + 1 < source.length && current() == '/' && peek() == '/' && position + 2 < source.length && source[position + 2].isDigit()) {
            advance()  // consume first '/'
            advance()  // consume second '/'
            while (!isAtEnd() && (current().isDigit() || current() == '_')) {
                advance()
            }
            val text = source.substring(start, position).replace("_", "")
            return Token(TokenType.FRACTION, text, start, startLine, startColumn)
        }

        if (!isAtEnd() && current() == '.' && peek().isDigit()) {
            advance()  // consume '.'
            while (!isAtEnd() && (current().isDigit() || current() == '_')) {
                advance()
            }
            val text = source.substring(start, position).replace("_", "")
            return Token(TokenType.FLOAT, text, start, startLine, startColumn)
        }

        // Check for Nat suffix 'n'
        // 'n' must be the end of the token (followed by non-identifier character or EOF)
        if (!isAtEnd() && current() == 'n') {
            val nextChar = peek()  // Look ahead to see what follows 'n'
            val isEndOfToken = isAtEnd() || !(nextChar.isLetterOrDigit() || nextChar == '_')
            if (isEndOfToken) {
                advance()  // consume 'n'
                val text = source.substring(start, position - 1).replace("_", "")  // Remove underscores and exclude 'n'
                return Token(TokenType.NAT, text, start, startLine, startColumn)
            }
        }

        val text = source.substring(start, position).replace("_", "")
        return Token(TokenType.INTEGER, text, start, startLine, startColumn)
    }

    private fun lexString(start: Int, startLine: Int, startColumn: Int): Token {
        advance()  // skip opening quote

        while (!isAtEnd() && current() != '"') {
            if (current() == '\n') {
                line++
                column = 1
            }
            if (current() == '\\') {
                advance()  // skip escape character
                if (!isAtEnd()) advance()
            } else if (current() == '$' && peek() == '(') {
                advance()  // skip $
                advance()  // skip (
                lexInterpolationContent()
            } else {
                advance()
            }
        }

        if (isAtEnd()) {
            return Token(TokenType.ERROR, "Unterminated string", start, startLine, startColumn)
        }

        advance()  // skip closing quote
        return Token(TokenType.STRING, source.substring(start, position), start, startLine, startColumn)
    }

    private fun lexInterpolationContent() {
        var parenDepth = 1  // We've already consumed the opening (

        while (!isAtEnd() && parenDepth > 0) {
            when {
                current() == '(' -> {
                    parenDepth++
                    advance()
                }
                current() == ')' -> {
                    parenDepth--
                    if (parenDepth > 0) advance()  // Don't advance past the final )
                }
                current() == '"' -> {
                    lexNestedString()
                }
                current() == '#' -> {
                    while (!isAtEnd() && current() != '\n') {
                        advance()
                    }
                }
                current() == '\n' -> {
                    line++
                    column = 1
                    advance()
                }
                else -> advance()
            }
        }

        if (parenDepth == 0) {
            advance()  // consume the final )
        }
    }

    private fun lexNestedString() {
        advance()  // skip opening quote

        while (!isAtEnd() && current() != '"') {
            if (current() == '\n') {
                line++
                column = 1
            }
            if (current() == '\\') {
                advance()  // skip escape character
                if (!isAtEnd()) advance()
            } else if (current() == '$' && peek() == '(') {
                advance()  // skip $
                advance()  // skip (
                lexInterpolationContent()
            } else {
                advance()
            }
        }

        if (!isAtEnd()) {
            advance()  // skip closing quote
        }
    }

    private fun lexTypeVariable(start: Int, startLine: Int, startColumn: Int): Token {
        // Type variable: 'a, 'b, 'myType, etc.
        advance()  // Skip the initial '
        while (!isAtEnd() && (current().isLetterOrDigit() || current() == '_')) {
            advance()
        }

        val text = source.substring(start, position)
        return Token(TokenType.IDENTIFIER, text, start, startLine, startColumn)
    }

    private fun lexIdentifierOrKeyword(start: Int, startLine: Int, startColumn: Int): Token {
        while (!isAtEnd() && (current().isLetterOrDigit() || current() == '_')) {
            advance()
        }

        val text = source.substring(start, position)

        if (text == "_") {
            return Token(TokenType.PLACEHOLDER, InternedTokens.PLACEHOLDER, start, startLine, startColumn)
        }

        val keywordType = keywords[text]
        if (keywordType != null) {
            val internedText = when (keywordType) {
                TokenType.LET -> InternedTokens.LET
                TokenType.TYPE -> InternedTokens.TYPE
                TokenType.NEWTYPE -> InternedTokens.NEWTYPE
                TokenType.IF -> InternedTokens.IF
                TokenType.THEN -> InternedTokens.THEN
                TokenType.ELSE -> InternedTokens.ELSE
                TokenType.MATCH -> InternedTokens.MATCH
                TokenType.IS -> InternedTokens.IS
                TokenType.DO -> InternedTokens.DO
                TokenType.EFFECT -> InternedTokens.EFFECT
                TokenType.IMPL -> InternedTokens.IMPL
                TokenType.PERFORM -> InternedTokens.PERFORM
                TokenType.ABILITY -> InternedTokens.ABILITY
                TokenType.PUB -> InternedTokens.PUB
                TokenType.FOR -> InternedTokens.FOR
                TokenType.WHERE -> InternedTokens.WHERE
                TokenType.WITH -> InternedTokens.WITH
                TokenType.IN -> InternedTokens.IN
                TokenType.USE -> InternedTokens.USE
                TokenType.HAVING -> InternedTokens.HAVING
                else -> text
            }
            return Token(keywordType, internedText, start, startLine, startColumn)
        }

        val internedText = if (text in InternedTokens.commonIdentifiers) {
            when (text) {
                "True" -> InternedTokens.TRUE
                "False" -> InternedTokens.FALSE
                "Some" -> InternedTokens.SOME
                "None" -> InternedTokens.NONE
                "Ok" -> InternedTokens.OK
                "Err" -> InternedTokens.ERR
                else -> text
            }
        } else {
            text
        }

        return Token(TokenType.IDENTIFIER, internedText, start, startLine, startColumn)
    }

    private fun lexComment(start: Int, startLine: Int, startColumn: Int): Token {
        // `#`-lines have three well-formed shapes; anything else is a lex error:
        //   `# text`   line comment    — `#` MUST be followed by a space/tab
        //                                (or be empty to EOL: a bare `#`).
        //   `## text`  doc comment     — `##` MUST be followed by a space/tab
        //                                (or be empty to EOL: a bare `##`).
        //   `#{ … }`   metadata        — MUST have a closing `}` on the line.
        // `#foo`, `##foo`, and an unclosed `#{broken` are typos, not comments,
        // and are rejected with a clear message rather than silently swallowed.
        val second = peek(1)   // char after the first '#'

        // Metadata: `#{ … }` — require a closing `}` before end of line.
        if (second == '{') {
            var i = position + 2
            var closed = false
            while (i < source.length && source[i] != '\n') {
                if (source[i] == '}') { closed = true; break }
                i++
            }
            if (!closed) {
                // Consume the line so error recovery resyncs at the next line.
                while (!isAtEnd() && current() != '\n') advance()
                return Token(
                    TokenType.ERROR,
                    "Unclosed metadata block: `#{ … }` must have a closing `}` on the same line",
                    start, startLine, startColumn
                )
            }
            // Well-formed metadata: consume to EOL, classified downstream.
            while (!isAtEnd() && current() != '\n') advance()
            return Token(TokenType.COMMENT, source.substring(start, position), start, startLine, startColumn)
        }

        // Doc comment `##` — the char after `##` must be a space/tab or EOL.
        if (second == '#') {
            val third = peek(2)
            val docOk = isCommentSep(third)
            if (!docOk) {
                while (!isAtEnd() && current() != '\n') advance()
                return Token(
                    TokenType.ERROR,
                    "A doc comment must have a space after `##` (write `## $third…` not `##$third…`)",
                    start, startLine, startColumn
                )
            }
            while (!isAtEnd() && current() != '\n') advance()
            return Token(TokenType.COMMENT, source.substring(start, position), start, startLine, startColumn)
        }

        // Line comment `#` — the char after `#` must be a space/tab or EOL.
        val lineOk = isCommentSep(second)
        if (!lineOk) {
            while (!isAtEnd() && current() != '\n') advance()
            return Token(
                TokenType.ERROR,
                "A comment must have a space after `#` (write `# $second…` not `#$second…`)",
                start, startLine, startColumn
            )
        }
        while (!isAtEnd() && current() != '\n') advance()
        return Token(TokenType.COMMENT, source.substring(start, position), start, startLine, startColumn)
    }

    private fun skipWhitespace() {
        while (!isAtEnd()) {
            val char = current()

            // Don't skip comments - they are now emitted as COMMENT tokens
            if (char == ' ' || char == '\t' || char == '\r') {
                advance()
                continue
            }

            break
        }
    }

    // A valid separator after # / ## in a comment: whitespace or EOL/EOF.
    // \u0000 is the peek()/current() EOF sentinel (a bare # or ## at end of
    // file is a legal empty comment).
    private fun isCommentSep(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u0000'

    private fun current(): Char = if (position < source.length) source[position] else '\u0000'
    private fun peek(offset: Int = 1): Char = if (position + offset < source.length) source[position + offset] else '\u0000'

    private fun advance(count: Int = 1) {
        repeat(count) {
            if (position < source.length) {
                if (source[position] == '\n') {
                    line++
                    column = 1
                } else {
                    column++
                }
                position++
            }
        }
    }

    private fun isAtEnd(): Boolean = position >= source.length
}

class LexerException(message: String) : Exception(message)
