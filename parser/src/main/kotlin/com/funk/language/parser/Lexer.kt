package com.funk.language.parser

/**
 * Lexer for Funk language.
 * Converts source code into a stream of tokens.
 */

data class Token(
    val type: TokenType,
    val text: String,
    val position: Int,  // Character position in source
    val line: Int,      // Line number
    val column: Int     // Column number
)

enum class TokenType {
    // Keywords
    LET, TYPE, IF, THEN, ELSE, MATCH, IS, DO, EFFECT, IMPL, PERFORM, ABILITY, PUB, FOR, WHERE, WITH, IN, USE,

    // Literals
    INTEGER, FLOAT, FRACTION, STRING, BOOLEAN,

    // Identifiers
    IDENTIFIER,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ, NEQ, LT, GT, LTE, GTE,
    AND, OR, NOT,
    PIPE, PIPE_OP, DOUBLE_COLON,  // PIPE is |, PIPE_OP is |>

    // Delimiters
    LPAREN, RPAREN,
    LBRACE, RBRACE,
    LBRACKET, RBRACKET,
    COMMA, DOT, COLON, SEMICOLON,
    ARROW, ASSIGN, ELLIPSIS,
    PLACEHOLDER,  // _ as expression placeholder
    AT,  // @ for pipe operator placeholder

    // Special
    NEWLINE, EOF, ERROR
}

class Lexer(private val source: String) {
    private var position = 0
    private var line = 1
    private var column = 1

    private val keywords = mapOf(
        "let" to TokenType.LET,
        "type" to TokenType.TYPE,
        "if" to TokenType.IF,
        "then" to TokenType.THEN,
        "else" to TokenType.ELSE,
        "match" to TokenType.MATCH,
        "is" to TokenType.IS,
        "do" to TokenType.DO,
        "effect" to TokenType.EFFECT,
        "impl" to TokenType.IMPL,
        "perform" to TokenType.PERFORM,
        "ability" to TokenType.ABILITY,
        "pub" to TokenType.PUB,
        "for" to TokenType.FOR,
        "where" to TokenType.WHERE,
        "with" to TokenType.WITH,
        "in" to TokenType.IN,
        "use" to TokenType.USE,
        "true" to TokenType.BOOLEAN,
        "false" to TokenType.BOOLEAN
    )

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()

        while (!isAtEnd()) {
            skipWhitespaceAndComments()
            if (isAtEnd()) break

            val token = nextToken()
            if (token.type != TokenType.ERROR) {
                tokens.add(token)
            } else {
                throw LexerException("Unexpected character '${token.text}' at line $line, column $column")
            }
        }

        tokens.add(Token(TokenType.EOF, "", position, line, column))
        return tokens
    }

    private fun nextToken(): Token {
        val start = position
        val startLine = line
        val startColumn = column

        val char = current()

        // Handle newlines explicitly
        // Note: advance() already increments line and resets column for newlines
        if (char == '\n') {
            advance()
            return Token(TokenType.NEWLINE, "\n", start, startLine, startColumn)
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

        // Multi-character operators
        if (char == '-' && peek() == '>') {
            advance(2)
            return Token(TokenType.ARROW, "->", start, startLine, startColumn)
        }

        if (char == ':' && peek() == ':') {
            advance(2)
            return Token(TokenType.DOUBLE_COLON, "::", start, startLine, startColumn)
        }

        if (char == '=' && peek() == '=') {
            advance(2)
            return Token(TokenType.EQ, "==", start, startLine, startColumn)
        }

        if (char == '!' && peek() == '=') {
            advance(2)
            return Token(TokenType.NEQ, "!=", start, startLine, startColumn)
        }

        if (char == '<' && peek() == '=') {
            advance(2)
            return Token(TokenType.LTE, "<=", start, startLine, startColumn)
        }

        if (char == '>' && peek() == '=') {
            advance(2)
            return Token(TokenType.GTE, ">=", start, startLine, startColumn)
        }

        if (char == '&' && peek() == '&') {
            advance(2)
            return Token(TokenType.AND, "&&", start, startLine, startColumn)
        }

        if (char == '|' && peek() == '>') {
            advance(2)
            return Token(TokenType.PIPE_OP, "|>", start, startLine, startColumn)
        }

        if (char == '|' && peek() == '|') {
            advance(2)
            return Token(TokenType.OR, "||", start, startLine, startColumn)
        }

        if (char == '.' && peek() == '.' && peek(2) == '.') {
            advance(3)
            return Token(TokenType.ELLIPSIS, "...", start, startLine, startColumn)
        }

        // Single character tokens
        val type = when (char) {
            '+' -> TokenType.PLUS
            '-' -> TokenType.MINUS
            '*' -> TokenType.STAR
            '/' -> TokenType.SLASH
            '%' -> TokenType.PERCENT
            '<' -> TokenType.LT
            '>' -> TokenType.GT
            '!' -> TokenType.NOT
            '|' -> TokenType.PIPE
            '@' -> TokenType.AT
            '(' -> TokenType.LPAREN
            ')' -> TokenType.RPAREN
            '{' -> TokenType.LBRACE
            '}' -> TokenType.RBRACE
            '[' -> TokenType.LBRACKET
            ']' -> TokenType.RBRACKET
            ',' -> TokenType.COMMA
            '.' -> TokenType.DOT
            ':' -> TokenType.COLON
            ';' -> TokenType.SEMICOLON
            '=' -> TokenType.ASSIGN
            else -> TokenType.ERROR
        }

        advance()
        return Token(type, char.toString(), start, startLine, startColumn)
    }

    private fun lexNumber(start: Int, startLine: Int, startColumn: Int): Token {
        while (!isAtEnd() && current().isDigit()) {
            advance()
        }

        // Check for fraction (e.g., 1//2)
        if (!isAtEnd() && position + 1 < source.length && current() == '/' && peek() == '/' && position + 2 < source.length && source[position + 2].isDigit()) {
            advance()  // consume first '/'
            advance()  // consume second '/'
            while (!isAtEnd() && current().isDigit()) {
                advance()
            }
            return Token(TokenType.FRACTION, source.substring(start, position), start, startLine, startColumn)
        }

        // Check for float
        if (!isAtEnd() && current() == '.' && peek().isDigit()) {
            advance()  // consume '.'
            while (!isAtEnd() && current().isDigit()) {
                advance()
            }
            return Token(TokenType.FLOAT, source.substring(start, position), start, startLine, startColumn)
        }

        return Token(TokenType.INTEGER, source.substring(start, position), start, startLine, startColumn)
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
                // Handle string interpolation $()
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

    /**
     * Lex the content inside $() interpolation, handling nested parentheses and strings.
     * This method is called after consuming "$(" and should consume until the matching ")".
     */
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
                    // Handle nested string inside interpolation
                    lexNestedString()
                }
                current() == '#' -> {
                    // Skip comments inside interpolation
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

    /**
     * Lex a nested string inside an interpolation (e.g., the "yes" in $(if {c} then "yes" else "no"))
     */
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
                // Handle nested interpolation inside nested string
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

        // Check for _ placeholder
        if (text == "_") {
            return Token(TokenType.PLACEHOLDER, text, start, startLine, startColumn)
        }

        val type = keywords[text] ?: TokenType.IDENTIFIER
        return Token(type, text, start, startLine, startColumn)
    }

    private fun skipWhitespaceAndComments() {
        while (!isAtEnd()) {
            val char = current()

            // Skip spaces and tabs (but not newlines - we want to track them)
            if (char == ' ' || char == '\t' || char == '\r') {
                advance()
                continue
            }

            // Skip comments
            if (char == '#') {
                while (!isAtEnd() && current() != '\n') {
                    advance()
                }
                continue
            }

            break
        }
    }

    private fun current(): Char = if (position < source.length) source[position] else '\u0000'
    private fun peek(offset: Int = 1): Char = if (position + offset < source.length) source[position + offset] else '\u0000'

    private fun advance(count: Int = 1) {
        for (i in 0 until count) {
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
