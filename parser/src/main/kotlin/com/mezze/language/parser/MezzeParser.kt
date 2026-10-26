package com.mezze.language.parser

import com.mezze.language.ast.SyntaxMetadata
import com.mezze.language.parsedast.*
import com.mezze.language.parser.internal.*
import com.mezze.language.parser.internal.lib.*

// Not thread-safe — use a fresh MezzeParser() per thread for parallel parsing.
class MezzeParser {
    internal var currentSource: String?? = null
    internal var currentFile: String?? = null

    /**
     * True while parsing a match-guard expression (`n if <expr> ->`). In that
     * context the `->` that follows the guard belongs to the match arm, not to
     * a lambda, so a `{…}` in postfix position (e.g. `(n == 0).not {}`) must be
     * read as call arguments — never as a lambda start. Postfix parsing checks
     * this to bypass [lookAheadForArrowAfterBrace], mirroring the dedicated
     * if-let guard parser.
     */
    internal var inMatchGuard: Boolean = false

    // Recursive-descent nesting depth (expressions + type annotations). Guards
    // against StackOverflowError on pathologically-nested input like
    // `((((((…))))))` — a user or generated code pasting deep nesting must get a
    // clean parse error, not a compiler crash (fuzz-found). Reset per parse.
    internal var nestDepth: Int = 0

    // ── Anti-hang backstop ────────────────────────────────────────────────
    // Hard invariant: the parser must NEVER hang on ANY input, in ANY frontend
    // (CLI/LSP/WASM). Progress guards in every loop kill true infinite loops in
    // O(1); this ceiling is the belt-and-suspenders backstop that turns any
    // residual or future non-progress (or exponential re-scan) into a clean
    // located P0132 error instead of a freeze. It is reset per parse in BOTH
    // entry points and ticked at the recursion entry points and inside every
    // repetition loop, so the backstop is reachable from any spin. Deliberately
    // a DETERMINISTIC step count scaled to input size — NO wall-clock: the same
    // input always trips (or doesn't) identically, independent of machine speed,
    // load, or GC pauses. Nothing in the parser is governed by real time.
    private var parseSteps: Long = 0
    private var parseStepCeiling: Long = Long.MAX_VALUE

    companion object {
        // Well under the ~700–1000-level recursive-descent depth that overflows a
        // default JVM stack; leaves huge margin over any real hand-written nesting.
        internal const val MAX_NEST_DEPTH = 256

        // Per-token step allowance. A well-formed token is consumed in O(1)
        // amortized ticks, so legitimate parses stay far under ceiling*tokens;
        // exponential re-scan or a non-progressing spin blows past it. Tuned
        // generous — false trips on real code must be impossible.
        internal const val STEPS_PER_TOKEN = 4096L
        internal const val MIN_STEP_BUDGET = 16_000_000L
    }

    /** Reset the anti-hang budget + nesting depth. Called by BOTH entry points. */
    private fun initParseBudget(tokenCount: Int) {
        nestDepth = 0
        parseSteps = 0
        parseStepCeiling = maxOf(MIN_STEP_BUDGET, tokenCount.toLong() * STEPS_PER_TOKEN)
    }

    /**
     * One unit of parser work. Call at every recursion entry point and inside
     * every repetition loop. Throws a located [ParseException] (P0132) — never
     * returns a spinning parser to its caller. Cheap and deterministic: an
     * increment + compare against a fixed, input-scaled ceiling (no clock).
     */
    internal fun parseTick(stream: TokenStream) {
        parseSteps++
        if (parseSteps > parseStepCeiling) {
            throw ParseException(
                "The parser gave up on this input after too much work — this is almost " +
                    "always malformed or pathological source (unbalanced or unterminated " +
                    "brackets/quotes, or a runaway construct), not a valid program.",
                stream.getSourcePosition(),
                stream.getSourceLine(),
                stream.getSourceColumn(),
                currentSource,
                currentFile,
                code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0132_PARSER_LIMIT_EXCEEDED,
                hints = listOf("check for unbalanced or unterminated `(`/`[`/`{`/`\"` near here")
            )
        }
    }

    fun parseParsedModule(source: String, file: String = "<unknown>"): ParsedModule {
        // Corpus capture (opt-in, off by default): when MEZZE_CORPUS_CAPTURE is
        // set, record every (source, accepted|rejected) pair so the parser test
        // suite can be replayed into the tree-sitter corpus. Zero cost otherwise.
        // The record is emitted in a finally so both the success and the throw
        // paths below are captured — the parser itself decides accept vs reject.
        if (CorpusCapture.enabled) {
            try {
                val m = parseParsedModuleImpl(source, file)
                CorpusCapture.record(source, accepted = true)
                return m
            } catch (e: Throwable) {
                CorpusCapture.record(source, accepted = false)
                throw e
            }
        }
        return parseParsedModuleImpl(source, file)
    }

    /**
     * Parse a standalone type, e.g. `{ xs: [Int] } -> Int <Log>`.
     *
     * The parser could already parse a type — every annotation goes through
     * `typeAnnotationParser` — but only ever as part of a declaration, so a caller
     * holding just a type had no way in. Type-directed search is one (the user
     * types a type and gets matching functions back); a REPL `:type` and a
     * doc-browser query are the same need.
     *
     * The alternative was to wrap the input in a synthetic declaration and dig the
     * annotation back out. That reads as a trick and it inherits the wrapper's own
     * syntax: an error in the type would be reported at a line and column that do
     * not exist in what the user typed.
     *
     * The whole input must be consumed. `Int -> Int junk` is a mistake, and
     * parsing the prefix would answer a question nobody asked.
     */
    fun parseTypeAnnotation(source: String, file: String = "<type>"): ParsedTypeAnnotation {
        currentSource = source
        currentFile = file
        SyntaxMetadata.clear()
        try {
            val tokens = Lexer(source).tokenize()
            initParseBudget(tokens.size)

            tokens.find { it.type == TokenType.ERROR }?.let { bad ->
                throw ParseException(
                    if (bad.text.length == 1) "Unexpected character '${bad.text}'" else bad.text,
                    bad.position, bad.line, bad.column, source, file,
                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0002_UNEXPECTED_CHARACTER,
                )
            }

            return when (val result = typeAnnotationParser(TokenStream(tokens))) {
                is ParseResult.Success -> {
                    val rest = result.remaining.peek()
                    if (rest != null && rest.type != TokenType.EOF) {
                        throw ParseException(
                            "Unexpected `${rest.text}` after the type",
                            rest.position, rest.line, rest.column, source, file,
                            hints = listOf("a type query is one type, with nothing following it"),
                        )
                    }
                    result.value
                }
                is ParseResult.Failure -> {
                    // A failure that consumed NOTHING means the input does not begin
                    // a type at all. The alternation's own message names whichever
                    // branch it happened to try last — `Expected '[' but got 'let'`
                    // for the input `let x = 1` — which tells the reader nothing
                    // true: `[` is one of several ways a type can start, not the
                    // expected one. Say what is actually wrong and keep the
                    // parser's detail as a hint rather than hiding it.
                    val first = tokens.firstOrNull()
                    val startedNothing = result.position == 0 && first != null &&
                        first.type != TokenType.EOF
                    throw ParseException(
                        if (startedNothing) "`${first!!.text}` cannot start a type" else result.message,
                        result.position, result.line, result.column, source, file,
                        code = result.code,
                        hints = if (startedNothing) {
                            listOf(
                                "a type is a name (`Int`), a variable (`'a`), a record " +
                                    "(`{ x: Int }`), a sequence (`[Int]`), a variant " +
                                    "(`|A, B|`) or an arrow between them",
                                result.message,
                            )
                        } else result.hints,
                    )
                }
            }
        } finally {
            currentSource = null
            currentFile = null
        }
    }

    /** One token of [lex]: its kind, its text, and where it is in the file. */
    data class LexedToken(val kind: String, val text: String, val line: Int, val column: Int, val endLine: Int, val endColumn: Int)

    /**
     * [source] as Mezze's lexer reads it, where [source] starts at [line] and
     * [column] of its file. Comments and newlines are kept: a macro may care.
     */
    fun lex(source: String, line: Int, column: Int): List<LexedToken> =
        com.mezze.language.parser.internal.Lexer(source, line, column).tokenize()
            .filter { it.type != com.mezze.language.parser.internal.TokenType.EOF }
            .map { t ->
                val loc = com.mezze.language.parser.internal.tokenLoc(t, "")
                LexedToken(t.type.name.lowercase(), t.text, loc.startLine, loc.startCol, loc.endLine, loc.endCol)
            }

    /**
     * [source] as Mezze — declarations when [declarations], else one expression —
     * where [source] is a slice of [file] starting at [line] and [column]: what a
     * macro reads part of its input with. Every location is in [file]'s own
     * coordinates, so a mistake points at the macro's input where it was written.
     *
     * The whole slice must be consumed.
     */
    fun parseCode(source: String, file: String, line: Int, column: Int, declarations: Boolean): ParsedCode {
        currentSource = source
        currentFile = file
        SyntaxMetadata.clear()
        try {
            val tokens = Lexer(source, line, column).tokenize()
            initParseBudget(tokens.size)
            tokens.find { it.type == TokenType.ERROR }?.let { bad ->
                throw ParseException(
                    if (bad.text.length == 1) "Unexpected character '${bad.text}'" else bad.text,
                    bad.position, bad.line, bad.column, source, file,
                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0002_UNEXPECTED_CHARACTER,
                )
            }
            val stream = TokenStream(tokens)
            if (declarations) {
                return when (val result = parsedModuleParser(stream)) {
                    is ParseResult.Success -> ParsedCode.Decls(resolveParsedImplDeclarations(result.value).decls)
                    is ParseResult.Failure -> throw ParseException(
                        result.message, result.position, result.line, result.column, source, file,
                        code = result.code, hints = result.hints,
                    )
                }
            }
            return when (val result = expression(skipNewlines(stream))) {
                is ParseResult.Success -> {
                    val rest = skipNewlines(result.remaining).current()
                    if (rest != null && rest.type != TokenType.EOF) {
                        throw ParseException(
                            "Unexpected `${rest.text}` after the expression",
                            rest.position, rest.line, rest.column, source, file,
                        )
                    }
                    ParsedCode.Expr(result.value)
                }
                is ParseResult.Failure -> throw ParseException(
                    result.message, result.position, result.line, result.column, source, file,
                    code = result.code, hints = result.hints,
                )
            }
        } finally {
            currentSource = null
            currentFile = null
        }
    }

    private fun parseParsedModuleImpl(source: String, file: String): ParsedModule {
        currentSource = source
        currentFile = file

        // Clear syntax metadata from previous parses
        SyntaxMetadata.clear()

        try {
            val lexer = Lexer(source)
            val tokens = lexer.tokenize()
            initParseBudget(tokens.size)

            // In strict mode, fail on lexer errors. The error token's text
            // is the real lexer message (e.g. "Unterminated string"); for
            // single-character ERROR tokens the text is that character, so
            // we prefix with "Unexpected character" only in that case.
            val firstError = tokens.find { it.type == com.mezze.language.parser.internal.TokenType.ERROR }
            if (firstError != null) {
                val isUnterminatedString = firstError.text.startsWith("Unterminated string")
                val msg = if (firstError.text.length == 1) {
                    "Unexpected character '${firstError.text}'"
                } else {
                    firstError.text
                }
                val lexCode = if (isUnterminatedString)
                    com.mezze.language.parsedast.diagnostics.ErrorCode.P0001_UNTERMINATED_STRING
                else
                    com.mezze.language.parsedast.diagnostics.ErrorCode.P0002_UNEXPECTED_CHARACTER
                val lexHints = if (isUnterminatedString)
                    listOf("add a closing `\"` to terminate the string literal")
                else
                    emptyList()
                throw ParseException(
                    msg,
                    firstError.position,
                    firstError.line,
                    firstError.column,
                    source,
                    file,
                    code = lexCode,
                    hints = lexHints
                )
            }

            val stream = TokenStream(tokens)

            return when (val result = parsedModuleParser(stream)) {
                is ParseResult.Success -> resolveParsedImplDeclarations(result.value)
                is ParseResult.Failure -> throw ParseException(
                    result.message,
                    result.position,
                    result.line,
                    result.column,
                    source,
                    file,
                    code = result.code,
                    hints = result.hints
                )
            }
        } catch (e: StackOverflowError) {
            // Ultimate anti-crash backstop (strict path): deep input can overflow
            // a recursive walk (descent or post-parse hasher) before a depth guard
            // trips. Surface it as a clean located P0131 rather than crashing.
            throw ParseException(
                "Input is nested too deeply for the parser to process safely.",
                0, 1, 1, source, file,
                code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0131_NESTING_TOO_DEEP,
                hints = listOf("check for runaway or unbalanced `(`/`[`/`{`/`->` nesting")
            )
        } finally {
            currentSource = null
            currentFile = null
        }
    }

    data class ParseResultWithRecovery(
        val module: ParsedModule,
        val errors: List<ParseException>
    ) {
        val hasErrors: Boolean get() = errors.isNotEmpty()
        val isSuccess: Boolean get() = errors.isEmpty()
    }

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
            initParseBudget(tokens.size)

            // Lexer-level errors (unterminated strings, unknown characters)
            // are reported up front with the real lexer message rather than
            // being silently skipped downstream and surfacing as a generic
            // "Do block must end with a result expression". Each error keeps
            // its position so editors can underline the offending span.
            for (tok in tokens) {
                if (tok.type == TokenType.ERROR) {
                    val isUnterminatedString = tok.text.startsWith("Unterminated string")
                    val lexCode = if (isUnterminatedString)
                        com.mezze.language.parsedast.diagnostics.ErrorCode.P0001_UNTERMINATED_STRING
                    else
                        com.mezze.language.parsedast.diagnostics.ErrorCode.P0002_UNEXPECTED_CHARACTER
                    val lexHints = if (isUnterminatedString)
                        listOf("add a closing `\"` to terminate the string literal")
                    else
                        emptyList()
                    errors.add(ParseException(
                        tok.text,  // e.g. "Unterminated string"
                        tok.position,
                        tok.line,
                        tok.column,
                        source,
                        file,
                        code = lexCode,
                        hints = lexHints
                    ))
                }
            }

            var stream = TokenStream(tokens)

            val (startStream, leadingBlock) = skipNewlinesCollectAnnotations(stream)
            stream = startStream
            var pendingBlock = leadingBlock

            while (stream.current()?.type != TokenType.EOF) {
                when (val result = parsedTopLevelItem(stream)) {
                    is ParseResult.Success -> {
                        val decl = attachAnnotationsToDecl(result.value, pendingBlock)
                        decls.add(decl)
                        pendingBlock = AnnotationBlock(emptyList(), null, null)

                        val afterDecl = result.remaining
                        val nextToken = afterDecl.current()
                        // Same-line comment right after the code is a trailing
                        // comment — but ONLY a regular `#` one. A `##` doc block
                        // is always the LEADING doc of the FOLLOWING decl, never
                        // trailing; capturing it here (which mis-fired when a
                        // do-block decl's loc bled onto the next line's `##`)
                        // silently stole the next decl's doc comment.
                        if (nextToken?.type == TokenType.COMMENT &&
                            nextToken.line == getLastDeclLine(decl) &&
                            !nextToken.text.startsWith("##")) {
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
                        errors.add(ParseException(
                            result.message,
                            result.position,
                            result.line,
                            result.column,
                            source,
                            file,
                            code = result.code,
                            hints = result.hints
                        ))

                        // Synchronize: skip to next top-level declaration
                        stream = synchronizeToNextDeclaration(stream)
                        pendingBlock = AnnotationBlock(emptyList(), null, null)
                    }
                }
            }

            orphanComments.addAll(pendingBlock.regularComments)

            // Mirror parseParsedModule. No identities: a declaration's identity
            // includes what its names resolve to, so it is stamped once the
            // module's imports are known (DeclIdentity), never at parse time.
            val parsedModule =
                resolveParsedImplDeclarations(ParsedModule(file ?: "<unknown>", decls, orphanComments, moduleDocOf(leadingBlock, decls.firstOrNull())))

            return ParseResultWithRecovery(parsedModule, errors)
        } catch (e: ParseException) {
            // The anti-hang backstop (P0132) — and any other hard parse abort —
            // must NOT escape the recovery API (the LSP/check/completion path
            // reuses one long-lived parser and expects a collected error list,
            // never a throw). Record it and return whatever declarations parsed
            // cleanly before the abort, so the frontend shows a located
            // diagnostic instead of falling over.
            errors.add(e)
            val partial =
                resolveParsedImplDeclarations(ParsedModule(file ?: "<unknown>", decls, orphanComments))
            return ParseResultWithRecovery(partial, errors)
        } catch (e: StackOverflowError) {
            // Ultimate anti-crash backstop: pathologically deep input can overflow
            // the parser's recursive descent before a depth guard trips. Convert it
            // to a located P0131 instead of crashing the frontend, and return an
            // empty module carrying just the diagnostic.
            errors.add(ParseException(
                "Input is nested too deeply for the parser to process safely.",
                0, 1, 1, source, file,
                code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0131_NESTING_TOO_DEEP,
                hints = listOf("check for runaway or unbalanced `(`/`[`/`{`/`->` nesting")
            ))
            return ParseResultWithRecovery(
                ParsedModule(file ?: "<unknown>", emptyList(), emptyList()), errors
            )
        } finally {
            currentSource = null
            currentFile = null
        }
    }

    private fun synchronizeToNextDeclaration(stream: TokenStream): TokenStream {
        var current = stream.advance() // Skip at least one token

        while (current.current()?.type != TokenType.EOF) {
            val token = current.current() ?: break

            // Only consider keywords at COLUMN 1 as top-level declaration boundaries.
            // Without this, a recovery from a lambda-body parse error (e.g. P0121's
            // `{p} -> let x = p + 1`) would re-enter parsing at the indented `let x = ...`
            // and successfully parse it as a new top-level decl — surfacing a cascading
            // V0702 "Undefined p" error against the indented body. Top-level decls in
            // Mezze always start at column 1; indented `let`/`use`/etc. are bodies.
            if (token.type == TokenType.NEWLINE) {
                val afterNewline = skipNewlines(current)
                val nextToken = afterNewline.current()

                if (nextToken != null &&
                    isDeclarationStart(nextToken.type) &&
                    nextToken.column == 1) {
                    return afterNewline
                }
            }

            // Also sync if we directly hit a declaration keyword at column 1
            // (after skipping bad tokens within a broken inner scope).
            if (isDeclarationStart(token.type) && token.column == 1) {
                return current
            }

            current = current.advance()
        }

        return current
    }

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


    internal val parsedModuleParser: Parser<ParsedModule> = { stream ->
        val (startStream, leadingBlock) = skipNewlinesCollectAnnotations(stream)
        var currentStream = startStream
        val decls = mutableListOf<ParsedDecl>()
        val orphanComments = mutableListOf<ParsedComment>()
        var pendingBlock = leadingBlock
        var failure: ParseResult.Failure? = null

        while (currentStream.current()?.type != TokenType.EOF && failure == null) {
            when (val result = parsedTopLevelItem(currentStream)) {
                is ParseResult.Success -> {
                    // Attach pending annotations to this declaration
                    val decl = attachAnnotationsToDecl(result.value, pendingBlock)
                    decls.add(decl)
                    pendingBlock = AnnotationBlock(emptyList(), null, null)

                    val afterDecl = result.remaining
                    val nextToken = afterDecl.current()
                    // Only a regular `#` same-line comment trails a decl; a `##`
                    // doc block leads the NEXT decl (see the sibling site above).
                    if (nextToken?.type == TokenType.COMMENT &&
                        nextToken.line == getLastDeclLine(decl) &&
                        !nextToken.text.startsWith("##")) {
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

                    val (nextStream, betweenBlock) = skipNewlinesCollectAnnotations(currentStream)
                    pendingBlock = betweenBlock
                    currentStream = nextStream
                }
                is ParseResult.Failure -> {
                    failure = ParseResult.Failure(
                        result.message,
                        result.position,
                        result.line,
                        result.column,
                        isFatal = result.isFatal,
                        code = result.code,
                        hints = result.hints
                    )
                }
            }
        }

        // Any remaining pending comments are orphans
        orphanComments.addAll(pendingBlock.regularComments)

        failure ?: ParseResult.Success(
            ParsedModule(currentFile ?: "<unknown>", decls, orphanComments, moduleDocOf(leadingBlock, decls.firstOrNull())),
            currentStream
        )
    }

    private fun getLastDeclLine(decl: ParsedDecl): Int = decl.loc.endLine

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

        parsedTopLevelItem = parsedTopLevelItem@{ stream ->
            val pubToken = tok(TokenType.PUB)(stream)
            val (visibility, streamAfterPub, pubLocation) = when (pubToken) {
                is ParseResult.Success -> Triple(Visibility.PUBLIC, pubToken.remaining, pubToken.value)
                is ParseResult.Failure -> Triple(Visibility.PRIVATE, stream, null)
            }

            // `pub` must sit on the same line as the decl it modifies —
            // `pub\nlet` is a typo, same rule as `let\nname` (#P0126).
            if (pubLocation != null && streamAfterPub.current()?.type == TokenType.NEWLINE) {
                return@parsedTopLevelItem streamAfterPub.failure(
                    "`pub` must be on the same line as the declaration it modifies.",
                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.P0126_DECL_NAME_ON_NEW_LINE,
                    hints = listOf("write `pub let …` / `pub type …` on one line"),
                )
            }

            val streamAfterPubSkipNl = skipNewlines(streamAfterPub)

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
     * Like [skipNewlines] but does NOT consume COMMENT tokens — it stops at the
     * first comment so a following collector ([skipNewlinesCollectAnnotations])
     * can capture it. Used between in-block statements/arms so comments there
     * survive to the formatter instead of being silently dropped.
     */
    internal fun skipNewlinesOnly(stream: TokenStream): TokenStream {
        var current = stream
        while (true) {
            val token = current.current() ?: break
            when (token.type) {
                TokenType.NEWLINE -> current = current.advance()
                TokenType.ERROR -> current = current.advance()
                else -> break
            }
        }
        return current
    }

    /**
     * Collect the COMMENT tokens in the half-open range [from, to). Used by the
     * multi-line pipe parser to keep the inline comment on a stage
     * (`|> f { @ }   # note`) — the continuation peek advances over it to reach
     * the next `|>`, and without capturing it here the comment would be lost.
     * `##` doc comments are marked as such (matching [skipNewlinesCollectAnnotations]).
     */
    internal fun collectCommentsBetween(from: TokenStream, to: TokenStream): List<ParsedComment> {
        if (to.position <= from.position) return emptyList()
        val out = mutableListOf<ParsedComment>()
        var cur = from
        while (cur.position < to.position) {
            val tok = cur.current() ?: break
            if (tok.type == TokenType.COMMENT) {
                val f = currentFile ?: "<unknown>"
                val loc = Loc(f, tok.line, tok.column, tok.line, tok.column + tok.text.length - 1)
                out.add(ParsedComment(tok.text, tok.text.startsWith("##"), loc))
            }
            cur = cur.advance()
        }
        return out
    }

    internal data class AnnotationBlock(
        val regularComments: List<ParsedComment>,
        val docComment: String?,
        val metadata: ParsedMetadata?,
        /** The first `##` run that a blank line or `#` comment cut off from what follows. */
        val firstDetachedDoc: String? = null,
    )

    /**
     * The module doc, from the annotations before the first item (`leading`) and
     * that item: the first `##` run, when it does not document the item — set
     * apart by a blank line, or followed by a `use`.
     */
    private fun moduleDocOf(leading: AnnotationBlock, firstDecl: ParsedDecl?): String? =
        leading.firstDetachedDoc
            ?: leading.docComment?.takeIf { firstDecl is ParsedUseDecl || firstDecl is ParsedPubUseDecl }

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
    internal fun skipNewlinesCollectAnnotations(stream: TokenStream): Pair<TokenStream, AnnotationBlock> {
        var current = stream
        val file = currentFile ?: "<unknown>"

        // Accumulators for the current contiguous annotation block
        var docLines = mutableListOf<String>()
        // Raw `## …` tokens backing the current docLines block. When the block
        // is RESET (a blank line or a regular `#` comment breaks the run), these
        // are NOT the doc of the following decl — but they are still real
        // documentation the author wrote, and dropping them silently corrupts
        // the file. Flush them into regularComments (which the formatter prints
        // verbatim) instead of discarding. Only the LAST, un-reset block becomes
        // the attached docComment. (Fixes module-top doc separated from the
        // first decl by a blank line vanishing on reformat.)
        var docRaw = mutableListOf<ParsedComment>()
        var metadata: ParsedMetadata? = null
        var metadataLoc: Loc? = null
        var hasSeenMetadata = false

        // All regular comments (for formatter preservation)
        val regularComments = mutableListOf<ParsedComment>()

        // Reset the current annotation block, preserving any doc lines it held
        // as verbatim comments so they survive the round-trip.
        var firstDetachedDoc: String? = null
        fun resetBlock() {
            if (firstDetachedDoc == null && docLines.isNotEmpty()) firstDetachedDoc = docLines.joinToString("\n")
            regularComments.addAll(docRaw)
            docLines = mutableListOf()
            docRaw = mutableListOf()
            metadata = null
            metadataLoc = null
            hasSeenMetadata = false
        }

        var lastWasNewline = false

        while (true) {
            val token = current.current() ?: break
            when (token.type) {
                TokenType.NEWLINE -> {
                    if (lastWasNewline) {
                        // Blank line (two consecutive newlines) → reset doc and metadata
                        resetBlock()
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
                                resetBlock()
                            }
                            // Strip the ## prefix and optional leading space
                            val docText = text.removePrefix("##").removePrefix(" ")
                            docLines.add(docText)
                            docRaw.add(ParsedComment(text, true, loc))
                        }
                        else -> {
                            resetBlock()
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
        return Pair(current, AnnotationBlock(regularComments, docComment, metadata, firstDetachedDoc))
    }

    internal fun tok(type: TokenType): Parser<Token> =
        Parsers.token({ it.type == type }, tokenDescription(type))
    internal fun <T, S> sepByWithNewlines(parser: Parser<T>, separator: Parser<S>): Parser<List<T>> = { stream ->
        when (val first = parser(stream)) {
            is ParseResult.Success -> {
                val results = mutableListOf(first.value)
                var currentStream = first.remaining

                while (true) {
                    when (val sep = separator(currentStream)) {
                        is ParseResult.Success -> {
                            val afterSep = skipNewlines(sep.remaining)
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
                var fatal: ParseResult.Failure? = null

                while (true) {
                    when (val sep = separator(currentStream)) {
                        is ParseResult.Success -> {
                            val afterSep = skipNewlines(sep.remaining)
                            when (val next = parser(afterSep)) {
                                is ParseResult.Success -> {
                                    results.add(next.value)
                                    currentStream = next.remaining
                                }
                                is ParseResult.Failure -> {
                                    // A FATAL entry failure means the parser committed
                                    // to this position and produced a targeted error
                                    // (e.g. `field:` should be `field =`) — surface it
                                    // rather than treating it as a trailing separator.
                                    if (next.isFatal) fatal = next
                                    // Trailing separator is allowed - update position past separator
                                    currentStream = afterSep
                                    break
                                }
                            }
                        }
                        is ParseResult.Failure -> break // No separator, done
                    }
                }

                fatal ?: ParseResult.Success(results, currentStream)
            }
            // A fatal first-entry failure is a committed, targeted error; propagate
            // it rather than masking it as "zero entries".
            is ParseResult.Failure -> if (first.isFatal) first else ParseResult.Success(emptyList(), stream)
        }
    }


    internal val identifierParser: Parser<Token> = tok(TokenType.IDENTIFIER)

    internal val memberIdentifierParser: Parser<Token> =
        Parsers.token(
            { it.type == TokenType.IDENTIFIER || it.type == TokenType.FOR },
            "identifier or keyword member"
        )

    internal fun parseQualifiedName(stream: TokenStream): ParseResult<String> {
        val first = identifierParser(stream)
        return when (first) {
            is ParseResult.Success -> {
                var name = first.value.text
                var currentStream = first.remaining

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



    internal fun hasUnescapedInterpolation(input: String): Boolean {
        var i = 0
        while (i < input.length - 1) {
            if (input[i] == '\\') {
                i += 2
            } else if (input[i] == '$' && input[i + 1] == '(') {
                return true
            } else {
                i++
            }
        }
        return false
    }

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
                if (lastWasCapture && currentStringPart.isEmpty()) {
                    return stream.failure(
                        "Adjacent captures without separator are not allowed in string patterns. " +
                        "Add a literal separator between captures, e.g., \"$(a)-$(b)\" instead of \"$(a)$(b)\"."
                    )
                }

                if (currentStringPart.isNotEmpty()) {
                    parts.add(ParsedStringPatternPart.Literal(currentStringPart.toString()))
                    currentStringPart.clear()
                }

                i += 2  // Skip "$("

                while (i < content.length && content[i].isWhitespace()) {
                    i++
                }

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
    val length: Int = 1,
    val code: com.mezze.language.parsedast.diagnostics.ErrorCode =
        com.mezze.language.parsedast.diagnostics.ErrorCode.UNCATEGORIZED,
    val hints: List<String> = emptyList()
) : Exception(formatParseError(errorMessage, position, line, column, sourceCode, file, length, hints)) {

    companion object {
        private fun formatParseError(
            message: String,
            @Suppress("UNUSED_PARAMETER") position: Int,
            line: Int,
            column: Int,
            sourceCode: String?,
            file: String?,
            length: Int = 1,
            hints: List<String> = emptyList(),
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

            for (hint in hints) {
                sb.appendLine("  = hint: $hint")
            }

            return sb.toString().trim()
        }
    }
}

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
    TokenType.QUESTION -> "'?'"
    TokenType.COLON -> "':'"
    TokenType.ARROW -> "'->'"
    TokenType.ASSIGN -> "'='"
    TokenType.ELLIPSIS -> "'...'"
    TokenType.DOUBLE_COLON -> "'::'"
    TokenType.AT -> "'@'"
    TokenType.BACKTICK -> "'`'"
    TokenType.TILDE -> "'~'"
    TokenType.MACRO_INPUT -> "a macro call's input"
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
