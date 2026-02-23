package com.mezze.language.parser

import com.mezze.language.ast.*
import com.mezze.language.parser.internal.*
import com.mezze.language.parser.internal.lib.*

/**
 * Mezze language parser using parser combinators.
 *
 * This parser explicitly handles the ambiguity between:
 * - Lambda expressions: {x} -> body
 * - Record literals: {x} or {x = value}
 *
 * By using explicit lookahead for the arrow token.
 *
 * ## Thread Safety
 *
 * **THREAD-SAFE FOR PARALLEL FILE PARSING**: Each parser instance maintains mutable state
 * during parsing, but global state (SyntaxMetadata) uses ThreadLocal storage.
 *
 * ### Safe Usage Patterns:
 *
 * ✅ **SAFE - Multiple threads, separate parser instances**:
 * ```kotlin
 * // Parse multiple files in parallel - SAFE!
 * files.parallelStream().map { file ->
 *     MezzeParser().parse(File(file).readText(), file = file)
 * }.toList()
 * ```
 *
 * ✅ **SAFE - Sequential parsing with same instance**:
 * ```kotlin
 * val parser = MezzeParser()
 * val prog1 = parser.parse(source1)  // OK
 * val prog2 = parser.parse(source2)  // OK - sequential
 * ```
 *
 * ❌ **UNSAFE - Concurrent parsing with same instance**:
 * ```kotlin
 * val parser = MezzeParser()
 * thread1 { parser.parse(source1) }  // Race on currentSource/currentFile!
 * thread2 { parser.parse(source2) }  // Race on warnings!
 * ```
 *
 * **Recommendation**: Use fresh `MezzeParser()` instance per thread for parallel parsing.
 *
 * ## Warnings
 *
 * Collects non-fatal warnings. Access them via `getWarnings()` after parsing.
 */
class MezzeParser(
    private val warningConfig: ParserWarningConfig = ParserWarningConfig()
) {
    // Store source and file during parsing for better error messages
    internal var currentSource: String?? = null
    internal var currentFile: String?? = null

    // Collect warnings during parsing
    private val warnings = mutableListOf<ParseWarning>()

    /**
     * Get all warnings collected during the last parse() call.
     */
    fun getWarnings(): List<ParseWarning> = warnings.toList()

    /**
     * Add a warning during parsing (internal use).
     */
    internal fun addWarning(
        message: String,
        location: SourceLocation,
        severity: WarningSeverity,
        category: WarningCategory,
        suggestion: String? = null,
        autoFixable: Boolean = false
    ) {
        if (warnings.size >= warningConfig.maxWarnings) return
        if (!warningConfig.shouldWarn(category)) return

        warnings.add(ParseWarning(
            message, location, severity, category, suggestion, autoFixable
        ))
    }

    /**
     * Result of parsing with error recovery.
     */
    data class ParseResultWithRecovery(
        val program: Program,
        val errors: List<ParseException>,
        val warnings: List<ParseWarning>
    ) {
        val hasErrors: Boolean get() = errors.isNotEmpty()
        val isSuccess: Boolean get() = errors.isEmpty()
    }

    fun parse(source: String, desugar: Boolean = true, file: String? = null): Program {
        // Store for error reporting in nested parser methods
        currentSource = source
        currentFile = file

        // Clear warnings from previous parse
        warnings.clear()

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

    /**
     * Parse with error recovery - continues parsing after errors and collects all errors.
     *
     * This is useful for IDEs/LSP to show all errors at once instead of stopping at the first one.
     * Returns a partial AST with successfully parsed items plus a list of all parse errors.
     */
    fun parseWithRecovery(source: String, desugar: Boolean = true, file: String? = null): ParseResultWithRecovery {
        currentSource = source
        currentFile = file
        warnings.clear()
        SyntaxMetadata.clear()

        val errors = mutableListOf<ParseException>()
        val items = mutableListOf<ProgramItem>()

        try {
            val lexer = Lexer(source)
            val tokens = lexer.tokenize()
            var stream = TokenStream(tokens)

            // Skip leading newlines
            stream = skipNewlines(stream)

            // Parse top-level items with recovery
            while (stream.current()?.type != TokenType.EOF) {
                when (val result = topLevelItem(stream)) {
                    is ParseResult.Success -> {
                        items.add(result.value)
                        stream = skipNewlines(result.remaining)
                    }
                    is ParseResult.Failure -> {
                        // Record the error
                        errors.add(ParseException(
                            result.message,
                            result.position,
                            result.line,
                            result.column,
                            source,
                            file
                        ))

                        // Synchronize: skip to next top-level declaration
                        stream = synchronizeToNextDeclaration(stream)
                    }
                }
            }

            val program = Program(items)
            val finalProgram = if (desugar && errors.isEmpty()) desugarProgram(program) else program

            return ParseResultWithRecovery(finalProgram, errors, warnings.toList())
        } finally {
            currentSource = null
            currentFile = null
        }
    }

    /**
     * Skip tokens until we find a likely start of a new top-level declaration.
     * This enables error recovery by jumping past problematic code.
     */
    private fun synchronizeToNextDeclaration(stream: TokenStream): TokenStream {
        var current = stream.advance() // Skip at least one token

        while (current.current()?.type != TokenType.EOF) {
            val token = current.current() ?: break

            // If we hit a newline followed by a declaration keyword, we've found a sync point
            if (token.type == TokenType.NEWLINE) {
                val afterNewline = skipNewlines(current)
                val nextToken = afterNewline.current()

                if (nextToken != null && isDeclarationStart(nextToken.type)) {
                    return afterNewline
                }
            }

            // Also sync if we directly hit a declaration keyword at start of line
            // (after skipping bad tokens)
            if (isDeclarationStart(token.type)) {
                // Check if this looks like a fresh declaration (not in middle of expression)
                // Heuristic: if previous was newline or we've skipped several tokens
                return current
            }

            current = current.advance()
        }

        return current
    }

    /**
     * Check if a token type typically starts a top-level declaration.
     */
    private fun isDeclarationStart(type: TokenType): Boolean {
        return type in setOf(
            TokenType.LET,
            TokenType.TYPE,
            TokenType.EFFECT,
            TokenType.ABILITY,
            TokenType.IMPL,
            TokenType.USE,
            TokenType.PUB
        )
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
                    failure = ParseResult.Failure(
                        result.message,
                        result.position,
                        result.line,
                        result.column
                    )
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
        Parsers.token({ it.type == type }, tokenDescription(type))
    internal fun <T, S> sepByWithNewlines(parser: Parser<T>, separator: Parser<S>): Parser<List<T>> = { stream ->
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

    /**
     * Parse a qualified name: module::name or just name
     * Returns the full qualified name as a String
     */
    internal fun parseQualifiedName(stream: TokenStream): ParseResult<String> {
        val first = identifierParser(stream)
        return when (first) {
            is ParseResult.Success -> {
                var name = first.value.text
                var currentStream = first.remaining

                // Check for :: to build qualified name
                while (true) {
                    val doubleColon = tok(TokenType.DOUBLE_COLON)(currentStream)
                    if (doubleColon is ParseResult.Success) {
                        val next = identifierParser(doubleColon.remaining)
                        when (next) {
                            is ParseResult.Success -> {
                                name += "::" + next.value.text
                                currentStream = next.remaining
                            }
                            is ParseResult.Failure -> {
                                // Found :: but no identifier after it - return error
                                return next
                            }
                        }
                    } else {
                        break
                    }
                }

                ParseResult.Success(name, currentStream)
            }
            is ParseResult.Failure -> first
        }
    }


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
                val exprParser = MezzeParser()
                try {
                    // We need to parse just the expression, so wrap it to make it a valid program
                    val exprProgram = exprParser.parse(exprContent, desugar = true, file = location?.file)
                    val expr = when (val firstItem = exprProgram.items.firstOrNull()) {
                        is TopLevelExpression -> firstItem.expression
                        is LetBinding -> firstItem.expression
                        else -> return stream.failure(
                            "Invalid expression in string interpolation: $exprContent"
                        )
                    }

                    // Fix source locations for better error reporting
                    val fixedExpr = fixInterpolationSourceLocation(expr, lineOffset, columnOffset, location?.file)
                    parts.add(ExpressionPart(fixedExpr))
                } catch (e: ParseException) {
                    return stream.failure(
                        "Failed to parse interpolated expression '$exprContent': ${e.message}"
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

    /**
     * Parse a string interpolation pattern.
     *
     * Unlike expression interpolation which allows full expressions inside $(),
     * pattern interpolation only allows variable names (identifiers).
     *
     * Examples:
     *   "hello $(name)"     -> matches "hello world" with name = "world"
     *   "$(a)-$(b)-$(c)"    -> matches "x-y-z" with a = "x", b = "y", c = "z"
     *   "prefix-$(x)-suffix" -> matches "prefix-foo-suffix" with x = "foo"
     *
     * Restrictions:
     *   - Only variable names allowed inside $(), not expressions
     *   - Adjacent captures without separator are not allowed
     *   - Each capture binds a String value
     */
    internal fun parseStringInterpolationPattern(
        content: String,
        stream: TokenStream,
        location: SourceLocation? = null
    ): ParseResult<Pattern> {
        val parts = mutableListOf<StringPatternPart>()
        var i = 0
        val currentStringPart = StringBuilder()
        var lastWasCapture = false

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
                lastWasCapture = false
            } else if (content[i] == '$' && i + 1 < content.length && content[i + 1] == '(') {
                // Found unescaped interpolation start
                // Check for adjacent captures (not allowed)
                if (lastWasCapture && currentStringPart.isEmpty()) {
                    return stream.failure(
                        "Adjacent captures without separator are not allowed in string patterns. " +
                        "Add a literal separator between captures, e.g., \"$(a)-$(b)\" instead of \"$(a)$(b)\"."
                    )
                }

                // Add any accumulated string part
                if (currentStringPart.isNotEmpty()) {
                    parts.add(StringPatternLiteral(currentStringPart.toString()))
                    currentStringPart.clear()
                }

                // Find the matching closing paren
                i += 2  // Skip "$("
                val varStart = i

                // Skip whitespace before variable name
                while (i < content.length && content[i].isWhitespace()) {
                    i++
                }

                // Parse variable name (identifier)
                val varNameStart = i
                if (i >= content.length || !(content[i].isLetter() || content[i] == '_')) {
                    return stream.failure(
                        "Expected variable name after '$(' in string pattern. " +
                        "Only variable names are allowed in pattern captures, not expressions."
                    )
                }

                while (i < content.length && (content[i].isLetterOrDigit() || content[i] == '_')) {
                    i++
                }
                val varName = content.substring(varNameStart, i)

                // Skip whitespace after variable name
                while (i < content.length && content[i].isWhitespace()) {
                    i++
                }

                // Expect closing paren
                if (i >= content.length || content[i] != ')') {
                    val found = if (i < content.length) "'${content[i]}'" else "end of string"
                    return stream.failure(
                        "Expected ')' after variable name '$varName' in string pattern capture, but found $found. " +
                        "Pattern captures must contain only a variable name, e.g., \"$(name)\"."
                    )
                }
                i++  // Skip ')'

                parts.add(StringPatternCapture(varName))
                lastWasCapture = true
            } else {
                currentStringPart.append(content[i])
                i++
                lastWasCapture = false
            }
        }

        // Add any remaining string part
        if (currentStringPart.isNotEmpty()) {
            parts.add(StringPatternLiteral(currentStringPart.toString()))
        }

        // If we only have one StringPatternLiteral, it's actually not a pattern (edge case after escape processing)
        if (parts.size == 1 && parts[0] is StringPatternLiteral) {
            return ParseResult.Success(
                LiteralPattern(StringLiteralValue((parts[0] as StringPatternLiteral).value)),
                stream
            )
        }

        return ParseResult.Success(
            StringInterpolationPattern(parts = parts, sourceLocation = location),
            stream
        )
    }
}

/**
 * Enhanced parse exception with detailed location and source context.
 *
 * Includes structured error types for programmatic handling and
 * recovery hints to help users fix errors quickly.
 */
class ParseException(
    val errorMessage: String,
    val position: Int,
    val line: Int = 0,
    val column: Int = 0,
    val sourceCode: String? = null,
    val file: String? = null,
    val errorType: ParseErrorType = ParseErrorType.GenericError(errorMessage),
    val hints: List<ParseHint> = emptyList(),
    val length: Int = 1
) : Exception(formatParseError(errorMessage, position, line, column, sourceCode, file, hints, length)) {

    /**
     * Check if this error is of a specific type.
     */
    inline fun <reified T : ParseErrorType> isType(): Boolean = errorType is T

    /**
     * Get typed error details if matches type, null otherwise.
     */
    inline fun <reified T : ParseErrorType> getTyped(): T? = errorType as? T

    companion object {
        private fun formatParseError(
            message: String,
            position: Int,
            line: Int,
            column: Int,
            sourceCode: String?,
            file: String?,
            hints: List<ParseHint> = emptyList(),
            length: Int = 1
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
                    val pointer = " ".repeat(maxOf(0, column - 1)) + "^".repeat(maxOf(1, length))
                    sb.appendLine("$padding | $pointer")
                    sb.appendLine("$padding |")
                }
            }

            // Add hints if available
            if (hints.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("💡 Hints:")
                hints.forEach { hint ->
                    val icon = when (hint.severity) {
                        HintSeverity.INFO -> "ℹ️"
                        HintSeverity.SUGGESTION -> "💡"
                        HintSeverity.WARNING -> "⚠️"
                        HintSeverity.TIP -> "✨"
                    }
                    sb.appendLine("   $icon ${hint.message}")
                    if (hint.code != null) {
                        val codeLines = hint.code.lines()
                        codeLines.forEach { codeLine ->
                            sb.appendLine("      $codeLine")
                        }
                    }
                    if (hint.docUrl != null) {
                        sb.appendLine("      📖 See: ${hint.docUrl}")
                    }
                }
            }

            return sb.toString().trim()
        }
    }
}

/**
 * Convert a TokenType to a user-friendly description for error messages.
 * Instead of "Expected RBRACE but got IDENTIFIER", we show "Expected '}' but got identifier".
 */
internal fun tokenDescription(type: TokenType): String = when (type) {
    // Delimiters - show the actual character
    TokenType.LPAREN -> "'('"
    TokenType.RPAREN -> "')'"
    TokenType.LBRACE -> "'{'"
    TokenType.RBRACE -> "'}'"
    TokenType.LBRACKET -> "'['"
    TokenType.RBRACKET -> "']'"
    TokenType.COMMA -> "','"
    TokenType.DOT -> "'.'"
    TokenType.COLON -> "':'"
    TokenType.SEMICOLON -> "';'"
    TokenType.ARROW -> "'->'"
    TokenType.ASSIGN -> "'='"
    TokenType.ELLIPSIS -> "'...'"
    TokenType.DOUBLE_COLON -> "'::'"
    TokenType.AT -> "'@'"
    TokenType.PIPE -> "'|'"
    TokenType.PIPE_OP -> "'|>'"
    TokenType.PLACEHOLDER -> "'_'"

    // Operators - show the actual operator
    TokenType.PLUS -> "'+'"
    TokenType.MINUS -> "'-'"
    TokenType.STAR -> "'*'"
    TokenType.SLASH -> "'/'"
    TokenType.PERCENT -> "'%'"
    TokenType.EQ -> "'=='"
    TokenType.NEQ -> "'!='"
    TokenType.LT -> "'<'"
    TokenType.GT -> "'>'"
    TokenType.LTE -> "'<='"
    TokenType.GTE -> "'>='"
    TokenType.AND -> "'&&'"
    TokenType.OR -> "'||'"
    TokenType.NOT -> "'!'"

    // Keywords - show the keyword
    TokenType.LET -> "'let'"
    TokenType.TYPE -> "'type'"
    TokenType.NEWTYPE -> "'newtype'"
    TokenType.IF -> "'if'"
    TokenType.THEN -> "'then'"
    TokenType.ELSE -> "'else'"
    TokenType.MATCH -> "'match'"
    TokenType.IS -> "'is'"
    TokenType.DO -> "'do'"
    TokenType.EFFECT -> "'effect'"
    TokenType.IMPL -> "'impl'"
    TokenType.PERFORM -> "'perform'"
    TokenType.ABILITY -> "'ability'"
    TokenType.PUB -> "'pub'"
    TokenType.FOR -> "'for'"
    TokenType.WHERE -> "'where'"
    TokenType.WITH -> "'with'"
    TokenType.IN -> "'in'"
    TokenType.USE -> "'use'"
    TokenType.HAVING -> "'having'"
    TokenType.FORALL -> "'forall'"

    // Literals and identifiers - describe what they are
    TokenType.INTEGER -> "integer"
    TokenType.NAT -> "natural number"
    TokenType.FLOAT -> "float"
    TokenType.FRACTION -> "fraction"
    TokenType.STRING -> "string"
    TokenType.BOOLEAN -> "boolean"
    TokenType.IDENTIFIER -> "identifier"

    // Special
    TokenType.NEWLINE -> "newline"
    TokenType.EOF -> "end of file"
    TokenType.ERROR -> "error"
}

/**
 * Convert a Token (or null) to a user-friendly description for "but got X" messages.
 */
internal fun tokenGotDescription(token: Token?): String = when {
    token == null -> "end of file"
    token.type == TokenType.IDENTIFIER -> "'${token.text}'"
    token.type == TokenType.INTEGER -> "number '${token.text}'"
    token.type == TokenType.FLOAT -> "number '${token.text}'"
    token.type == TokenType.STRING -> "string"
    token.type == TokenType.NEWLINE -> "newline"
    token.type == TokenType.EOF -> "end of file"
    else -> tokenDescription(token.type)
}
