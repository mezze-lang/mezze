package com.mezze.language.parser

import com.mezze.language.ast.SyntaxMetadata
import com.mezze.language.parsedast.*
import com.mezze.language.parser.internal.*
import com.mezze.language.parser.internal.lib.*

/**
 * Mezze language parser using parser combinators.
 *
 * Disambiguates lambda expressions ({x} -> body) from record literals ({x = value})
 * using explicit lookahead for the arrow token.
 *
 * Not thread-safe for concurrent use of the same instance. Use a fresh MezzeParser()
 * per thread for parallel parsing.
 */
class MezzeParser {
    // Store source and file during parsing for better error messages
    internal var currentSource: String?? = null
    internal var currentFile: String?? = null

    companion object

    /**
     * Parse source into ParsedModule (v2 AST — clean, no nullable typechecker fields).
     *
     * This is the primary (and only) parse entry point.
     */
    fun parseParsedModule(source: String, file: String = "<unknown>"): ParsedModule {
        // Store for error reporting in nested parser methods
        currentSource = source
        currentFile = file

        // Clear syntax metadata from previous parses
        SyntaxMetadata.clear()

        try {
            val lexer = Lexer(source)
            val tokens = lexer.tokenize()

            // In strict mode, fail on lexer errors
            val firstError = tokens.find { it.type == com.mezze.language.parser.internal.TokenType.ERROR }
            if (firstError != null) {
                throw ParseException(
                    "Unexpected character '${firstError.text}'",
                    firstError.position,
                    firstError.line,
                    firstError.column,
                    source,
                    file
                )
            }

            val stream = TokenStream(tokens)

            return when (val result = parsedModuleParser(stream)) {
                is ParseResult.Success -> com.mezze.language.parsedast.ParsedDeclHasher.computeAllHashes(
                    resolveParsedImplDeclarations(result.value)
                )
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
     * Result of parsing with error recovery.
     */
    data class ParseResultWithRecovery(
        val module: ParsedModule,
        val errors: List<ParseException>
    ) {
        val hasErrors: Boolean get() = errors.isNotEmpty()
        val isSuccess: Boolean get() = errors.isEmpty()
    }

    /**
     * Parse with error recovery - continues parsing after errors and collects all errors.
     *
     * This is useful for IDEs/LSP to show all errors at once instead of stopping at the first one.
     * Returns a partial AST with successfully parsed items plus a list of all parse errors.
     */
    fun parseWithRecovery(source: String, file: String? = null): ParseResultWithRecovery {
        currentSource = source
        currentFile = file
        SyntaxMetadata.clear()

        val errors = mutableListOf<ParseException>()
        val decls = mutableListOf<ParsedDecl>()
        val orphanComments = mutableListOf<ParsedComment>()

        try {
            val lexer = Lexer(source)
            val tokens = lexer.tokenize()
            var stream = TokenStream(tokens)

            // Skip leading newlines and collect annotations
            val (startStream, leadingBlock) = skipNewlinesCollectAnnotations(stream)
            stream = startStream
            var pendingBlock = leadingBlock

            // Parse top-level items with recovery
            while (stream.current()?.type != TokenType.EOF) {
                when (val result = parsedTopLevelItem(stream)) {
                    is ParseResult.Success -> {
                        val decl = attachAnnotationsToDecl(result.value, pendingBlock)
                        decls.add(decl)
                        pendingBlock = AnnotationBlock(emptyList(), null, null)

                        // Check for trailing comment
                        val afterDecl = result.remaining
                        val nextToken = afterDecl.current()
                        if (nextToken?.type == TokenType.COMMENT && nextToken.line == getLastDeclLine(decl)) {
                            val text = nextToken.text
                            val isDoc = text.startsWith("##")
                            val commentFile = currentFile ?: "<unknown>"
                            val loc = Loc(commentFile, nextToken.line, nextToken.column, nextToken.line, nextToken.column + text.length - 1)
                            decls[decls.size - 1] = attachTrailingComment(decls.last(), ParsedComment(text, isDoc, loc))
                            stream = afterDecl.advance()
                        } else {
                            stream = afterDecl
                        }

                        val (nextStream, betweenBlock) = skipNewlinesCollectAnnotations(stream)
                        pendingBlock = betweenBlock
                        stream = nextStream
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
                        pendingBlock = AnnotationBlock(emptyList(), null, null)
                    }
                }
            }

            orphanComments.addAll(pendingBlock.regularComments)

            // Mirror parseParsedModule: resolve impl decls AND compute
            // content hashes. The LSP path lives or dies by these hashes —
            // ability impl lookup (NumMul, NumAdd, Eq, etc.) is keyed on
            // ParsedImplAbilityDecl.contentHash, which is null until
            // computeAllHashes runs. Without it, a file like
            //   use std::collections::seq
            //   let f = { x } -> x * 2
            // typechecks fine via `mez check` (which calls
            // parseParsedModule) but fails in the LSP with
            //   "Cannot use '*' with Int. To enable this, implement:
            //    impl NumMul for Int where { ... }"
            // because the prelude's NumMul-for-Int impl has no hash to
            // match against. (#233)
            val parsedModule = com.mezze.language.parsedast.ParsedDeclHasher.computeAllHashes(
                resolveParsedImplDeclarations(ParsedModule(file ?: "<unknown>", decls, orphanComments))
            )

            return ParseResultWithRecovery(parsedModule, errors)
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


    private val parsedModuleParser: Parser<ParsedModule> = { stream ->
        // Skip leading newlines and collect annotations
        val (startStream, leadingBlock) = skipNewlinesCollectAnnotations(stream)
        var currentStream = startStream
        val decls = mutableListOf<ParsedDecl>()
        val orphanComments = mutableListOf<ParsedComment>()
        var pendingBlock = leadingBlock
        var failure: ParseResult.Failure? = null

        // Parse top-level items, collecting annotations between them
        while (currentStream.current()?.type != TokenType.EOF && failure == null) {
            when (val result = parsedTopLevelItem(currentStream)) {
                is ParseResult.Success -> {
                    // Attach pending annotations to this declaration
                    val decl = attachAnnotationsToDecl(result.value, pendingBlock)
                    decls.add(decl)
                    pendingBlock = AnnotationBlock(emptyList(), null, null)

                    // Check for trailing comment on the same line
                    val afterDecl = result.remaining
                    val nextToken = afterDecl.current()
                    if (nextToken?.type == TokenType.COMMENT && nextToken.line == getLastDeclLine(decl)) {
                        val text = nextToken.text
                        val isDoc = text.startsWith("##")
                        val file = currentFile ?: "<unknown>"
                        val loc = Loc(file, nextToken.line, nextToken.column, nextToken.line, nextToken.column + text.length - 1)
                        val trailing = ParsedComment(text, isDoc, loc)
                        decls[decls.size - 1] = attachTrailingComment(decls.last(), trailing)
                        currentStream = afterDecl.advance()
                    } else {
                        currentStream = afterDecl
                    }

                    // Collect annotations before next declaration
                    val (nextStream, betweenBlock) = skipNewlinesCollectAnnotations(currentStream)
                    pendingBlock = betweenBlock
                    currentStream = nextStream
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

        // Any remaining pending comments are orphans
        orphanComments.addAll(pendingBlock.regularComments)

        failure ?: ParseResult.Success(
            ParsedModule(currentFile ?: "<unknown>", decls, orphanComments),
            currentStream
        )
    }

    private fun getLastDeclLine(decl: ParsedDecl): Int = decl.loc.endLine

    private fun attachCommentsToDecl(decl: ParsedDecl, comments: List<ParsedComment>): ParsedDecl {
        if (comments.isEmpty()) return decl
        return when (decl) {
            is ParsedLetDecl -> decl.copy(leadingComments = comments)
            is ParsedTypeDecl -> decl.copy(leadingComments = comments)
            is ParsedEffectDecl -> decl.copy(leadingComments = comments)
            is ParsedAbilityDecl -> decl.copy(leadingComments = comments)
            is ParsedImplAbilityDecl -> decl.copy(leadingComments = comments)
            is ParsedPerformerDecl -> decl.copy(leadingComments = comments)
            is ParsedUseDecl -> decl.copy(leadingComments = comments)
            is ParsedPubUseDecl -> decl.copy(leadingComments = comments)
            is ParsedTopLevelExpr -> decl.copy(leadingComments = comments)
        }
    }

    private fun attachAnnotationsToDecl(decl: ParsedDecl, block: AnnotationBlock): ParsedDecl {
        val (comments, doc, meta) = block
        if (comments.isEmpty() && doc == null && meta == null) return decl
        return when (decl) {
            is ParsedLetDecl -> decl.copy(leadingComments = comments, docComment = doc, metadata = meta)
            is ParsedTypeDecl -> decl.copy(leadingComments = comments, docComment = doc, metadata = meta)
            is ParsedEffectDecl -> decl.copy(leadingComments = comments, docComment = doc, metadata = meta)
            is ParsedAbilityDecl -> decl.copy(leadingComments = comments, docComment = doc, metadata = meta)
            is ParsedImplAbilityDecl -> decl.copy(leadingComments = comments, docComment = doc, metadata = meta)
            is ParsedPerformerDecl -> decl.copy(leadingComments = comments, docComment = doc, metadata = meta)
            is ParsedUseDecl -> decl.copy(leadingComments = comments, docComment = doc, metadata = meta)
            is ParsedPubUseDecl -> decl.copy(leadingComments = comments, docComment = doc, metadata = meta)
            is ParsedTopLevelExpr -> decl.copy(leadingComments = comments, docComment = doc, metadata = meta)
        }
    }

    private fun attachTrailingComment(decl: ParsedDecl, comment: ParsedComment): ParsedDecl {
        return when (decl) {
            is ParsedLetDecl -> decl.copy(trailingComment = comment)
            is ParsedTypeDecl -> decl.copy(trailingComment = comment)
            is ParsedEffectDecl -> decl.copy(trailingComment = comment)
            is ParsedAbilityDecl -> decl.copy(trailingComment = comment)
            is ParsedImplAbilityDecl -> decl.copy(trailingComment = comment)
            is ParsedPerformerDecl -> decl.copy(trailingComment = comment)
            is ParsedUseDecl -> decl
            is ParsedPubUseDecl -> decl
            is ParsedTopLevelExpr -> decl
        }
    }

    // Declaration parsers (defined later to avoid forward reference)
    private lateinit var parsedEffectDeclaration: Parser<ParsedDecl>
    private lateinit var parsedImplDeclaration: Parser<ParsedDecl>
    private lateinit var parsedImplAbilityDeclaration: Parser<ParsedDecl>
    private lateinit var parsedAbilityDeclaration: Parser<ParsedDecl>
    private lateinit var parsedTypeDeclaration: Parser<ParsedDecl>
    private lateinit var parsedNewtypeDeclaration: Parser<ParsedDecl>
    private lateinit var parsedUseDeclaration: Parser<ParsedDecl>
    private lateinit var parsedLetDeclaration: Parser<ParsedDecl>
    private lateinit var parsedTopLevelExpression: Parser<ParsedDecl>
    private lateinit var parsedTopLevelItem: Parser<ParsedDecl>


    init {
        // Initialize parsers after all properties are available
        parsedEffectDeclaration = { stream -> parseEffectDeclaration(stream) }
        parsedImplDeclaration = { stream -> parseImplDeclaration(stream) }
        parsedImplAbilityDeclaration = { stream -> parseImplAbilityDeclaration(stream) }
        parsedAbilityDeclaration = { stream -> parseAbilityDeclaration(stream) }
        parsedTypeDeclaration = { stream -> parseTypeDeclaration(stream, Visibility.PRIVATE) }
        parsedNewtypeDeclaration = { stream -> parseNewtypeDeclaration(stream, Visibility.PRIVATE) }
        parsedUseDeclaration = { stream -> parseUseDeclaration(stream, Visibility.PRIVATE) }
        parsedLetDeclaration = { stream -> parseLetBinding(stream) }  // Lambda wrapper
        parsedTopLevelExpression = { stream ->
            when (val result = expression(stream)) {
                is ParseResult.Success -> {
                    val file = currentFile ?: "<unknown>"
                    val loc = spanLocTo(stream.current() ?: stream.tokens.last(), result.remaining, file)
                    ParseResult.Success(ParsedTopLevelExpr(result.value, loc = loc), result.remaining)
                }
                is ParseResult.Failure -> result
            }
        }

        // Initialize parsedTopLevelItem AFTER all other parsers are initialized
        parsedTopLevelItem = parsedTopLevelItem@{ stream ->
            // Check for optional 'pub' modifier
            val pubToken = tok(TokenType.PUB)(stream)
            val (visibility, streamAfterPub, pubLocation) = when (pubToken) {
                is ParseResult.Success -> Triple(Visibility.PUBLIC, pubToken.remaining, pubToken.value)
                is ParseResult.Failure -> Triple(Visibility.PRIVATE, stream, null)
            }

            // Parse the actual declaration with visibility
            val streamAfterPubSkipNl = skipNewlines(streamAfterPub)

            // Check for pub before declarations that don't support it
            if (pubLocation != null) {
                val nextToken = streamAfterPubSkipNl.current()
                when (nextToken?.type) {
                    TokenType.EFFECT -> {
                        return@parsedTopLevelItem ParseResult.Failure(
                            "'pub' is not allowed on effect declarations. " +
                            "Effects are always public because they must be usable in type signatures across modules. " +
                            "Remove 'pub' and just write: effect ${peekIdentifier(streamAfterPubSkipNl.advance())} where { ... }",
                            pubLocation.position,
                            pubLocation.line,
                            pubLocation.column
                        )
                    }
                    TokenType.ABILITY -> {
                        return@parsedTopLevelItem ParseResult.Failure(
                            "'pub' is not allowed on ability declarations. " +
                            "Abilities are always public because they must be usable in type signatures across modules. " +
                            "Remove 'pub' and just write: ability ${peekIdentifier(streamAfterPubSkipNl.advance())} where { ... }",
                            pubLocation.position,
                            pubLocation.line,
                            pubLocation.column
                        )
                    }
                    TokenType.IMPL -> {
                        return@parsedTopLevelItem ParseResult.Failure(
                            "'pub' is not allowed on impl declarations. " +
                            "Effect impls are always exported so the performer name can be imported for 'perform'. " +
                            "Ability impls are always exported so they can be discovered for constraint resolution. " +
                            "Remove 'pub' and just write: impl ...",
                            pubLocation.position,
                            pubLocation.line,
                            pubLocation.column
                        )
                    }
                    else -> { /* continue with normal parsing */ }
                }
            }

            val declResult = Parsers.oneOf(
                { s -> parseEffectDeclaration(s) },
                { s -> parseTypeDeclaration(s, visibility) },
                { s -> parseNewtypeDeclaration(s, visibility) },
                { s -> parseAbilityDeclaration(s) },
                { s -> parseImplDeclaration(s) },          // Try effect impl first (same syntax as ability impl)
                { s -> parseImplAbilityDeclaration(s) },  // Then ability impl
                { s -> parseUseDeclaration(s, visibility) },
                { s -> parseLetBindingWithVisibility(s, visibility) },
                { s -> parseConstrainedLambda(s) },  // Try constrained lambda before general expression
                parsedTopLevelExpression
            )(streamAfterPubSkipNl)

            declResult
        }
    }

    // Helper to peek at the identifier after a keyword for better error messages
    private fun peekIdentifier(stream: TokenStream): String {
        val s = skipNewlines(stream)
        return s.current()?.text ?: "..."
    }


    internal fun skipNewlines(stream: TokenStream): TokenStream {
        var current = stream
        while (true) {
            val token = current.current() ?: break
            when (token.type) {
                TokenType.NEWLINE -> current = current.advance()
                TokenType.COMMENT -> current = current.advance()
                TokenType.ERROR -> current = current.advance()  // Skip lexer errors in recovery mode
                else -> break
            }
        }
        return current
    }

    /**
     * Result of collecting comments, doc strings, and metadata between declarations.
     */
    internal data class AnnotationBlock(
        val regularComments: List<ParsedComment>,
        val docComment: String?,
        val metadata: ParsedMetadata?
    )

    /**
     * Skip newlines and collect COMMENT tokens, applying Roc-style reset rules:
     *
     * - `##` doc comment lines are collected into a doc string
     * - `#{}` metadata is parsed into a ParsedMetadata
     * - `#` regular comments are collected for the formatter
     * - A blank line (consecutive NEWLINEs) or a regular `#` comment RESETS
     *   the doc and metadata accumulators — only the last contiguous block survives
     * - Ordering enforced: `##` before `#{}`, error if `##` after `#{}`
     * - Only one `#{}` block allowed
     */
    internal fun skipNewlinesCollectComments(stream: TokenStream): Pair<TokenStream, List<ParsedComment>> {
        val (finalStream, _) = skipNewlinesCollectAnnotations(stream)
        // For backward compatibility with callers that still use the old signature,
        // return all comments as a flat list. The module parser uses skipNewlinesCollectAnnotations directly.
        return Pair(finalStream, emptyList())
    }

    /**
     * Collects annotations (doc comments, metadata) and regular comments between declarations.
     * Implements Roc-style reset: blank lines and regular comments reset doc/metadata.
     */
    internal fun skipNewlinesCollectAnnotations(stream: TokenStream): Pair<TokenStream, AnnotationBlock> {
        var current = stream
        val file = currentFile ?: "<unknown>"

        // Accumulators for the current contiguous annotation block
        var docLines = mutableListOf<String>()
        var metadata: ParsedMetadata? = null
        var metadataLoc: Loc? = null
        var hasSeenMetadata = false

        // All regular comments (for formatter preservation)
        val regularComments = mutableListOf<ParsedComment>()

        var lastWasNewline = false

        while (true) {
            val token = current.current() ?: break
            when (token.type) {
                TokenType.NEWLINE -> {
                    if (lastWasNewline) {
                        // Blank line (two consecutive newlines) → reset doc and metadata
                        docLines = mutableListOf()
                        metadata = null
                        metadataLoc = null
                        hasSeenMetadata = false
                    }
                    lastWasNewline = true
                    current = current.advance()
                }
                TokenType.COMMENT -> {
                    lastWasNewline = false
                    val text = token.text
                    val loc = Loc(file, token.line, token.column, token.line, token.column + text.length - 1)

                    when {
                        MetadataParser.isMetadata(text) -> {
                            // #{...} metadata block
                            if (hasSeenMetadata) {
                                // Error: second #{} block — for now, just overwrite (parser error in strict mode)
                                // TODO: emit parse error for multiple #{} blocks
                            }
                            metadata = MetadataParser.parse(text, loc)
                            metadataLoc = loc
                            hasSeenMetadata = true
                        }
                        text.startsWith("##") -> {
                            // Doc comment line
                            if (hasSeenMetadata) {
                                // Error: ## after #{} — for now, reset and start fresh
                                // TODO: emit parse error for doc after metadata
                                docLines = mutableListOf()
                                metadata = null
                                metadataLoc = null
                                hasSeenMetadata = false
                            }
                            // Strip the ## prefix and optional leading space
                            val docText = text.removePrefix("##").removePrefix(" ")
                            docLines.add(docText)
                        }
                        else -> {
                            // Regular # comment → reset doc and metadata
                            docLines = mutableListOf()
                            metadata = null
                            metadataLoc = null
                            hasSeenMetadata = false
                            // Preserve for formatter
                            regularComments.add(ParsedComment(text, false, loc))
                        }
                    }
                    current = current.advance()
                }
                else -> break
            }
        }

        val docComment = if (docLines.isNotEmpty()) docLines.joinToString("\n") else null
        return Pair(current, AnnotationBlock(regularComments, docComment, metadata))
    }

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
     * Parser for member names after `.` in member access expressions.
     * Accepts identifiers and keywords that are valid as member names (e.g., `for` in `Show.for`).
     */
    internal val memberIdentifierParser: Parser<Token> =
        Parsers.token(
            { it.type == TokenType.IDENTIFIER || it.type == TokenType.FOR },
            "identifier or keyword member"
        )

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
        loc: Loc
    ): ParseResult<ParsedPattern> {
        val parts = mutableListOf<ParsedStringPatternPart>()
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
                    parts.add(ParsedStringPatternPart.Literal(currentStringPart.toString()))
                    currentStringPart.clear()
                }

                // Find the matching closing paren
                i += 2  // Skip "$("

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

                parts.add(ParsedStringPatternPart.Capture(varName))
                lastWasCapture = true
            } else {
                currentStringPart.append(content[i])
                i++
                lastWasCapture = false
            }
        }

        // Add any remaining string part
        if (currentStringPart.isNotEmpty()) {
            parts.add(ParsedStringPatternPart.Literal(currentStringPart.toString()))
        }

        // If we only have one literal part, it's actually not a pattern (edge case after escape processing)
        if (parts.size == 1 && parts[0] is ParsedStringPatternPart.Literal) {
            return ParseResult.Success(
                ParsedLiteralPattern(
                    ParsedLiteralValue.StringValue((parts[0] as ParsedStringPatternPart.Literal).value),
                    loc
                ),
                stream
            )
        }

        return ParseResult.Success(
            ParsedStringInterpPattern(parts = parts, loc = loc),
            stream
        )
    }
}

class ParseException(
    val errorMessage: String,
    val position: Int,
    val line: Int = 0,
    val column: Int = 0,
    val sourceCode: String? = null,
    val file: String? = null,
    val length: Int = 1
) : Exception(formatParseError(errorMessage, position, line, column, sourceCode, file, length)) {

    companion object {
        private fun formatParseError(
            message: String,
            @Suppress("UNUSED_PARAMETER") position: Int,
            line: Int,
            column: Int,
            sourceCode: String?,
            file: String?,
            length: Int = 1
        ): String {
            val sb = StringBuilder()

            sb.appendLine("Parse error: $message")

            if (line > 0) {
                val fileInfo = file ?: "<input>"
                sb.appendLine("   --> $fileInfo:$line:$column")
            }

            if (sourceCode != null && line > 0) {
                val lines = sourceCode.lines()
                if (line in 1..lines.size) {
                    val errorLineContent = lines[line - 1]
                    val lineNumStr = line.toString()
                    val padding = " ".repeat(lineNumStr.length)

                    sb.appendLine("$padding |")
                    sb.appendLine("$lineNumStr | $errorLineContent")

                    val pointer = " ".repeat(maxOf(0, column - 1)) + "^".repeat(maxOf(1, length))
                    sb.appendLine("$padding | $pointer")
                    sb.appendLine("$padding |")
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
    TokenType.COMMENT -> "comment"
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
