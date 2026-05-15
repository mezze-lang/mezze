package com.mezze.language.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Drives the LSP through real JSON-RPC and asserts the behaviour of one
 * feature per test. The tests use only stdlib-free user code so that they
 * pass on a JVM that doesn't have stdlib.sqlite on its classpath. Anything
 * stdlib-dependent (auto-import code actions across stdlib, prelude-aware
 * hover for built-ins, etc.) is exercised manually against the installed
 * `mez` binary — see GotoDefinitionTest for the same cutoff.
 */
class LspFeaturesTest {

    @TempDir lateinit var tempDir: Path

    private lateinit var server: LanguageServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var sIn: PipedInputStream
    private lateinit var sOut: PipedOutputStream
    private lateinit var cIn: PipedInputStream
    private lateinit var cOut: PipedOutputStream
    private val client = CaptureClient()

    @BeforeEach
    fun setUp() {
        cOut = PipedOutputStream()
        sIn = PipedInputStream(cOut)
        sOut = PipedOutputStream()
        cIn = PipedInputStream(sOut)
        executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "lsp-features-server").apply { isDaemon = true }
        }
        executor.submit {
            try { startServer(sIn, sOut, "features-test") } catch (_: Throwable) {}
        }
        // We previously passed our own daemon executor to Launcher.Builder
        // here, hoping to let the gradle worker JVM exit. That broke
        // request/response: lsp4j's single-thread executor handles BOTH
        // reading from the pipe and dispatching incoming notifications,
        // and we were deadlocking when a long-running test produced
        // publishDiagnostics that backed up against a busy synchronous
        // request. Use the stock launcher — JVM exit is handled by
        // ForceExitListener (resources/META-INF/services/...).
        val launcher: Launcher<LanguageServer> =
            LSPLauncher.createClientLauncher(client, cIn, cOut)
        server = launcher.remoteProxy
        launcher.startListening()
    }

    @AfterEach
    fun tearDown() {
        try { server.shutdown().get(2, TimeUnit.SECONDS) } catch (_: Throwable) {}
        try { server.exit() } catch (_: Throwable) {}
        try { sIn.close() } catch (_: Throwable) {}
        try { sOut.close() } catch (_: Throwable) {}
        executor.shutdownNow()
    }

    // ===== Hover ======================================================

    @Test
    fun `hover on local let renders its inferred type`() {
        val (uri, _) = openSingleFile("""
            let helper = {x: Int} -> x + 1
        """.trimIndent())

        // Hover over `helper` at its declaration: line 0, char 4 (start of name).
        val hover = hoverAt(uri, line = 0, character = 4)
        assertNotNull(hover, "expected a Hover for the let name")
        val content = hoverText(hover!!)
        assertTrue(
            content.contains("Int"),
            "expected `Int` in hover content (function takes Int and returns Int), got: $content"
        )
    }

    @Test
    fun `hover on lambda param shows the parameter's type`() {
        val (uri, _) = openSingleFile("""
            let helper = {x: Int} -> x + 1
        """.trimIndent())

        // 0         1         2
        // 0123456789012345678901234567
        // let helper = {x: Int} -> x + 1
        //               ^ char 14 — the `x` param
        val hover = hoverAt(uri, line = 0, character = 14)
        assertNotNull(hover, "expected hover on the lambda param")
        val content = hoverText(hover!!)
        assertTrue(
            content.contains("Int"),
            "expected lambda param hover to mention Int, got: $content"
        )
    }

    // ===== Hover doc comments ==========================================

    @Test
    fun `hover on def-site of doc-commented let surfaces the doc`() {
        // Regression for #300: hovering on a top-level `let` binding's name
        // returned only the type — `## doc` lines were dropped because the
        // TypedAstLookup.LookupResult.Binding branch didn't read docComment.
        val (uri, _) = openSingleFile("""
            ## Greet by name.
            ## Multi-line is preserved.
            let greet = {x: Int} -> x + 1
        """.trimIndent())

        // Cursor on `greet` at line 2, char 4 (the `g` of `let greet`).
        val hover = hoverAt(uri, line = 2, character = 4)
        assertNotNull(hover, "expected hover on `let greet` def-site")
        val content = hoverText(hover!!)
        assertTrue(
            content.contains("Greet by name."),
            "expected doc-comment text in hover, got:\n$content"
        )
        assertTrue(
            content.contains("Multi-line is preserved."),
            "expected multi-line doc preserved, got:\n$content"
        )
    }

    @Test
    fun `hover on use-site of doc-commented let surfaces the doc`() {
        // Regression for #300/#301: the use-site goes through a different
        // code path (TypedAstLookup → TypedVar) than the def-site. Both
        // need to surface the doc; before the fix, only the type rendered.
        val (uri, _) = openSingleFile("""
            ## Add one.
            let inc = {x: Int} -> x + 1
            let main = {} -> inc { x = 4 }
        """.trimIndent())

        // 0         1         2
        // 0123456789012345678901234567
        // let main = {} -> inc { x = 4 }
        //                  ^ char 17 — the `i` of the `inc` use
        val hover = hoverAt(uri, line = 2, character = 17)
        assertNotNull(hover, "expected hover on `inc` use-site")
        val content = hoverText(hover!!)
        assertTrue(
            content.contains("Add one."),
            "expected doc-comment text on use-site hover, got:\n$content"
        )
    }

    // Disabled for the same reason as the cross-module go-to-definition
    // test below: the LSP test JVM has no stdlib.sqlite on the classpath,
    // and any cross-module `use` chains through prelude setup which fails
    // without stdlib. Verified manually against ~/.mezze/bin/mez (see
    // /tmp/lsp-probe.py) — `use std::int::{min}` hover correctly shows
    // "Return the smaller of two integers." (Int's doc) instead of Seq's
    // "Returns the minimum element..." which was the buggy behavior.
    @org.junit.jupiter.api.Disabled("requires stdlib on test classpath")
    @Test
    fun `hover on cross-module import resolves doc via cached parsed module`() {
        // Regression for #301: imports were going through findDocFromStore
        // which (a) misses modules StdlibPrecompiler never indexed and (b)
        // picks the alphabetically-first match for ambiguous names. The
        // fix walks ModuleTypeChecker.getAllCachedModules()[importPath] —
        // a Map lookup against the in-memory typecheck cache — so the doc
        // comes from the *specific* module the user imported from.
        val ws = mkProject(
            "app.mz" to """
                use util::increment
                let main = {} -> increment { n = 4 }
            """.trimIndent(),
            "util.mz" to """
                ## Increment by one.
                pub let increment = {n: Int} -> n + 1
            """.trimIndent()
        )
        openProject(ws)
        val appUri = ws.resolve("src/app.mz").toUri().toString()
        openFile(appUri, ws.resolve("src/app.mz").toFile().readText())

        // Cursor on `increment` at line 1, char 17 (use-site in app.mz).
        val hover = hoverAt(appUri, line = 1, character = 17)
        assertNotNull(hover, "expected hover on cross-module `increment`")
        val content = hoverText(hover!!)
        assertTrue(
            content.contains("Increment by one."),
            "expected doc to come from util.mz (the module the user imported from), got:\n$content"
        )
    }

    // ===== Inlay hints =================================================

    @Test
    fun `inlay hints add a type label to multi-line let`() {
        // Multi-line lambda body — the LSP should emit a trailing `: T` hint.
        val (uri, source) = openSingleFile("""
            let main = {x: Int} ->
              x + 1
        """.trimIndent())

        val hints = inlayHints(uri, source)
        // We don't assert exact positions — just that *some* type-kind hint
        // is emitted. The exact rendering is exercised in the inlayHints
        // unit test for the segment-by-segment pipe case below.
        assertTrue(
            hints.any { it.kind == InlayHintKind.Type },
            "expected at least one Type inlay hint for a multi-line let, got: ${hints.map { it.kind to labelText(it) }}"
        )
    }

    // ===== References / Document highlight =============================

    @Test
    fun `references finds all occurrences of a let in the same file`() {
        val (uri, _) = openSingleFile("""
            let helper = {x: Int} -> x + 1
            let main = {} -> helper { x = 4 }
        """.trimIndent())

        // Cursor on the call-site `helper` (line 1, char ~17).
        val refs = server.textDocumentService.references(ReferenceParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(1, 17)
            context = ReferenceContext(true)
        }).get(5, TimeUnit.SECONDS)

        assertNotNull(refs)
        assertTrue(
            refs.size >= 2,
            "expected at least 2 references for `helper` (decl + use), got ${refs.size}: $refs"
        )
    }

    @Test
    fun `documentHighlight returns the same-symbol occurrences`() {
        val (uri, _) = openSingleFile("""
            let main = {x: Int, y: Int} -> x + x + y
        """.trimIndent())

        // Click on the first `x` in the body. Body is at line 0:
        // 0         1         2         3         4
        // 0123456789012345678901234567890123456789012
        // let main = {x: Int, y: Int} -> x + x + y
        //                                ^ char 31
        val highlights = server.textDocumentService.documentHighlight(DocumentHighlightParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(0, 31)
        }).get(5, TimeUnit.SECONDS)

        assertNotNull(highlights)
        // We expect at least both `x` uses to be highlighted — plus possibly
        // the param decl. The provider may or may not include the decl, so
        // we just check that more than one highlight came back.
        assertTrue(
            highlights.size >= 2,
            "expected at least 2 highlights for `x` (two uses), got ${highlights.size}: $highlights"
        )
    }

    // ===== Document symbol (outline) ===================================

    @Test
    fun `documentSymbol returns top-level lets in the file`() {
        val (uri, _) = openSingleFile("""
            let alpha = {x: Int} -> x
            let beta = {} -> 7
        """.trimIndent())

        val symbols = server.textDocumentService.documentSymbol(DocumentSymbolParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        }).get(5, TimeUnit.SECONDS)

        val names = symbols.map { e ->
            if (e.isLeft) e.left.name else e.right.name
        }
        assertTrue(names.contains("alpha"), "expected `alpha` in outline, got: $names")
        assertTrue(names.contains("beta"), "expected `beta` in outline, got: $names")
    }

    // ===== Diagnostics =================================================

    @Test
    fun `valid stdlib-free file produces zero diagnostics`() {
        val (uri, _) = openSingleFile("""
            let helper = {x: Int} -> x + 1
        """.trimIndent())

        // Wait for the debounced typecheck.
        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags, "expected a publishDiagnostics for the file")
        assertTrue(
            diags!!.diagnostics.isEmpty(),
            "expected zero diagnostics for valid file, got: ${diags.diagnostics.map { it.message }}"
        )
    }

    @Test
    fun `parse-error file gets a non-empty diagnostic`() {
        val ws = mkProject("app.mz" to "let broken = {} -> ::: nonsense")
        openProject(ws)
        val uri = ws.resolve("src/app.mz").toUri().toString()
        openFile(uri, ws.resolve("src/app.mz").toFile().readText())

        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags, "expected publishDiagnostics for malformed source")
        assertTrue(
            diags!!.diagnostics.isNotEmpty(),
            "expected at least one diagnostic for `::: nonsense` source"
        )
    }

    @Test
    fun `parse-error diagnostic carries PARSE_ERROR code`() {
        // Regression for #313: clients need a stable code to distinguish
        // syntax errors from type errors without parsing the message text.
        val ws = mkProject("app.mz" to "let broken = {} -> ::: nonsense")
        openProject(ws)
        val uri = ws.resolve("src/app.mz").toUri().toString()
        openFile(uri, ws.resolve("src/app.mz").toFile().readText())

        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags, "expected publishDiagnostics for malformed source")
        val parseDiag = diags!!.diagnostics.firstOrNull()
            ?: fail("expected at least one diagnostic")
        val code = parseDiag.code?.left
        assertEquals("PARSE_ERROR", code, "parse-error diagnostic should carry code=PARSE_ERROR, got $code")
    }

    @Test
    fun `type-error diagnostic carries errorType code and metadata`() {
        // Regression for #313 step 7: the typechecker's rich errorType +
        // metadata (e.g. CLOSED_RECORD_FIELD_MISMATCH with "availableFields"
        // hints) must reach LSP clients via Diagnostic.code and .data,
        // not just be collapsed into the message string. Without this
        // forwarding, editors can't offer e.g. typo-fix code actions.
        val (uri, _) = openSingleFile("""
            let r = {x = 1, y = 2}
            let bad = r.z
        """.trimIndent())

        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags, "expected publishDiagnostics for type-error file")
        val typeDiag = diags!!.diagnostics.firstOrNull { it.code?.left != "PARSE_ERROR" }
            ?: fail("expected a type-error diagnostic among ${diags.diagnostics.map { it.message }}")
        val code = typeDiag.code?.left
        assertNotNull(code, "type-error diagnostic should carry a non-null code")
        assertNotEquals("GENERAL", code, "expected specific errorType code (e.g. UNDEFINED_VARIABLE / TYPE_MISMATCH), not GENERAL — got: ${typeDiag.message}")
    }

    // ===== Code actions ================================================

    @Test
    fun `field typo produces a Replace-with rename code action`() {
        // Regression for #178: V0703 FieldNotFound now emits structured
        // FixHint.Rename payloads alongside the diagnostic. The LSP code
        // action handler should decode those and surface a one-click
        // "Replace with `y`" QuickFix in VS Code's lightbulb.
        val (uri, _) = openSingleFile("""
            let r = {x = 1, y = 2}
            let bad = r.z
        """.trimIndent())

        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags, "expected publishDiagnostics for type-error file")
        // Field-not-found surfaces as T0205 from ExpressionConstraints
        // (closed record field check) — V0703 is the ValidationError path,
        // hit by different syntactic shapes. Both wire fixHints now.
        val fieldDiag = diags!!.diagnostics.firstOrNull {
            val c = it.code?.left
            c == "T0205" || c == "V0703" || c == "T0303"
        } ?: fail("expected a field-not-found diagnostic among ${diags.diagnostics.map { it.code?.left to it.message }}")

        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = fieldDiag.range
            context = CodeActionContext(listOf(fieldDiag))
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val titles = actions.mapNotNull { e -> if (e.isRight) e.right.title else null }
        assertTrue(
            titles.any { it.contains("Replace with") && (it.contains("`x`") || it.contains("`y`")) },
            "expected a 'Replace with `x`' or 'Replace with `y`' code action, got: $titles"
        )
    }

    @Test
    fun `undefined variable typo produces a Replace-with rename code action`() {
        // Regression for #178: V0702 VariableNotInScope must emit a
        // structured FixHint.Rename when there's a Levenshtein-close
        // peer binding in scope. This caught a real bug — at one point
        // the typechecker built the undefined-var candidate list before
        // peer top-level lets were registered, so `greeting` was missing
        // from the suggestion pool for `greetin`. Keeping the test as a
        // regression guard ensures the candidate pool covers peer lets.
        val (uri, _) = openSingleFile("""
            let greeting = {} -> 1
            let bad = greetin
        """.trimIndent())

        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags, "expected publishDiagnostics for undefined-var file")
        val varDiag = diags!!.diagnostics.firstOrNull { it.code?.left == "V0702" }
            ?: fail("expected a V0702 diagnostic among ${diags.diagnostics.map { it.code?.left to it.message }}")

        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = varDiag.range
            context = CodeActionContext(listOf(varDiag))
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val titles = actions.mapNotNull { e -> if (e.isRight) e.right.title else null }
        assertTrue(
            titles.any { it.contains("Replace with") && it.contains("`greeting`") },
            "expected a 'Replace with `greeting`' code action, got titles=$titles; " +
            "diag.code=${varDiag.code?.left} diag.data=${varDiag.data} " +
            "diag.message=${varDiag.message}"
        )
    }

    // ===== Cross-module go-to-definition (in-memory fallback) =========

    // Disabled for the same reason as
    // GotoDefinitionTest.`cmd-click on cross-module import …`: the LSP test
    // JVM lacks stdlib.sqlite, so even a sibling-module `use foo::bar`
    // fails to typecheck (the import resolver chains through prelude
    // setup). Verified manually against ~/.mezze/bin/mez:
    // cmd-click on `println` from `std::effects::log::println` now lands
    // on log.mz line 82 instead of the local `use` line.
    @org.junit.jupiter.api.Disabled("requires stdlib on test classpath")
    @Test
    fun `cmd-click on cross-module use lands in the other module's source`() {
        // Regression: cmd-click on a name imported from another module
        // (`println` from `std::effects::log` in real usage) used to fall
        // through to the import line because the name's hash wasn't in
        // the project store's decls table. The in-memory ModuleTypeChecker
        // cache always has it though — that's the path under test here.
        // Two-file user-defined project stands in for the stdlib case
        // since the LSP test JVM has no stdlib.sqlite on classpath.
        val ws = mkProject(
            "app.mz" to """
                use math::id_int
                let main = {} -> id_int { n = 4 }
            """.trimIndent(),
            "math.mz" to """
                pub let id_int = {n: Int} -> n
            """.trimIndent()
        )
        openProject(ws)
        val appUri = ws.resolve("src/app.mz").toUri().toString()
        val mathUri = ws.resolve("src/math.mz").toUri().toString()
        // Open both files so the typechecker sees math.mz too.
        openFile(mathUri, ws.resolve("src/math.mz").toFile().readText())
        openFile(appUri, ws.resolve("src/app.mz").toFile().readText())

        // Position on `id_int` use site (line 1, char ~17).
        // 0         1         2
        // 0123456789012345678901
        // let main = {} -> id_int { n = 4 }
        //                  ^ char 17
        val locs = server.textDocumentService.definition(DefinitionParams().apply {
            textDocument = TextDocumentIdentifier(appUri)
            position = Position(1, 17)
        }).get(5, TimeUnit.SECONDS).let { e ->
            if (e.isLeft) e.left else e.right.map { Location(it.targetUri, it.targetRange) }
        }

        assertTrue(locs.isNotEmpty(), "expected at least one Location for cross-module use")
        val loc = locs.first()
        assertEquals(
            mathUri, loc.uri,
            "expected URI to be math.mz (the source module), not the use line in app.mz. " +
            "Got URI=${loc.uri} range=${loc.range}"
        )
    }

    // ===== Selection range =============================================

    @Test
    fun `selectionRange returns a non-empty chain for an inner expression`() {
        val (uri, _) = openSingleFile("""
            let main = {x: Int} -> x + 1
        """.trimIndent())

        // Position inside the body — on `x` of `x + 1` at line 0 col 23.
        val ranges = server.textDocumentService.selectionRange(SelectionRangeParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            positions = listOf(Position(0, 23))
        }).get(5, TimeUnit.SECONDS)

        assertNotNull(ranges)
        assertTrue(ranges.isNotEmpty(), "expected at least one SelectionRange")
        // Innermost range must contain the cursor position.
        val innermost = ranges.first().range
        assertTrue(
            innermost.start.line <= 0 && innermost.end.line >= 0,
            "innermost range must include the cursor line, got: $innermost"
        )
    }

    // ===== Folding =====================================================

    @Test
    fun `foldingRange folds a brace block that spans multiple lines`() {
        // Folding is brace-based: a `{` and `}` on different lines yield
        // a FoldingRange. Single-line `{x: Int}` does NOT fold.
        val (uri, _) = openSingleFile("""
            let main = {
              x: Int
            } -> x + 1
        """.trimIndent())

        val ranges = server.textDocumentService.foldingRange(FoldingRangeRequestParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        }).get(5, TimeUnit.SECONDS)

        assertNotNull(ranges)
        assertTrue(
            ranges.isNotEmpty(),
            "expected at least one folding range for the multi-line `{ ... }` block"
        )
    }

    // ===== Rename ======================================================

    @Test
    fun `rename of a same-file let updates all use sites`() {
        val (uri, _) = openSingleFile("""
            let helper = {x: Int} -> x + 1
            let main = {} -> helper { x = 4 }
        """.trimIndent())

        val edit = server.textDocumentService.rename(RenameParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(0, 4)  // on `helper` at the decl
            newName = "renamed"
        }).get(5, TimeUnit.SECONDS)

        assertNotNull(edit, "expected a WorkspaceEdit for rename")
        val edits = edit!!.changes?.get(uri).orEmpty()
        // Two occurrences: the decl and the call site.
        assertTrue(
            edits.size >= 2,
            "expected at least 2 TextEdits (decl + use), got ${edits.size}: ${edits.map { it.range to it.newText }}"
        )
        assertTrue(
            edits.all { it.newText == "renamed" },
            "all edits should write the new name, got: ${edits.map { it.newText }}"
        )
    }

    // ===== Formatting ==================================================

    @Test
    fun `formatting returns text edits for ill-formatted source`() {
        // Extra spaces around the `=` and `->` that the formatter normalises.
        val (uri, _) = openSingleFile("""
            let    main    =    {x:    Int}    ->    x    +    1
        """.trimIndent())

        val edits = server.textDocumentService.formatting(DocumentFormattingParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            options = FormattingOptions(2, true)
        }).get(5, TimeUnit.SECONDS)

        assertNotNull(edits)
        assertTrue(
            edits.isNotEmpty(),
            "expected at least one TextEdit from formatter on ugly source"
        )
    }

    // ===== Completion ==================================================

    @Test
    fun `completion at a bare identifier prefix returns local bindings`() {
        // The user is typing `hel` mid-file — completion should suggest
        // the existing `helper` decl. The trailing newline is required so
        // the cursor position has actual characters before it on its line.
        val source = """
            let helper = {x: Int} -> x + 1
            let main = {} -> hel
        """.trimIndent()
        val (uri, _) = openSingleFile(source)

        // Position right after `hel` on line 1.
        // 0         1         2
        // 0123456789012345678901
        // let main = {} -> hel
        //                     ^ char 20
        val resp = server.textDocumentService.completion(CompletionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(1, 20)
        }).get(5, TimeUnit.SECONDS)

        val items = if (resp.isLeft) resp.left else resp.right.items
        // Don't assert exact content — completion is heuristic-heavy and
        // may include keywords. Just ensure the local `helper` shows up.
        assertTrue(
            items.any { it.label == "helper" },
            "expected `helper` in completion items, got: ${items.map { it.label }.take(20)}"
        )
    }

    // ===== Resource hygiene ============================================

    @Test
    fun `didClose evicts document state and the cached typecheck`() {
        // Reproduces the leak fix — before this patch, `lastSuccessfulResult`
        // kept the closed file's TypedModule alive forever and `documents`
        // was the only thing evicted. After: hover/definition/etc. on a
        // closed file return nothing instead of serving stale cached state.
        val (uri, _) = openSingleFile("""
            let helper = {x: Int} -> x + 1
        """.trimIndent())

        // Sanity: hover works while the file is open.
        val before = hoverAt(uri, line = 0, character = 4)
        assertNotNull(before, "precondition: hover must work on an open file")

        // Close the file.
        server.textDocumentService.didClose(DidCloseTextDocumentParams(
            TextDocumentIdentifier(uri)
        ))
        // Give the close handler time to run and the publishDiagnostics
        // notification to settle.
        Thread.sleep(150)

        // After close, the LSP must not serve cached state for this URI.
        val after = hoverAt(uri, line = 0, character = 4)
        assertNull(
            after,
            "expected hover to return null after didClose (would indicate cached state leaked)"
        )
    }

    // ===== Helpers =====================================================

    private fun mkProject(vararg files: Pair<String, String>): Path {
        val ws = tempDir.resolve("ws").also { it.toFile().mkdirs() }
        ws.resolve("Mezze.toml").toFile().writeText("""
            [package]
            name = "lsp_features_test"
            version = "0.0.1"

            [[app]]
            name = "lsp_features_test"
            path = "src/app.mz"
            entry = "main"

            [dependencies]

            [dev-dependencies]
        """.trimIndent())
        val src = ws.resolve("src").also { it.toFile().mkdirs() }
        for ((name, content) in files) {
            src.resolve(name).toFile().writeText(content + "\n")
        }
        return ws
    }

    private fun openProject(ws: Path) {
        server.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(ws.toUri().toString(), "test"))
        }).get(10, TimeUnit.SECONDS)
        server.initialized(InitializedParams())
    }

    private fun openFile(uri: String, content: String) {
        server.textDocumentService.didOpen(DidOpenTextDocumentParams(
            TextDocumentItem(uri, "mezze", 1, content)
        ))
        // Allow the debounced typecheck to settle.
        Thread.sleep(800)
    }

    /** Open a single-file project and return (uri, source). */
    private fun openSingleFile(source: String): Pair<String, String> {
        val ws = mkProject("app.mz" to source)
        openProject(ws)
        val path = ws.resolve("src/app.mz")
        val uri = path.toUri().toString()
        openFile(uri, path.toFile().readText())
        return uri to path.toFile().readText()
    }

    private fun hoverAt(uri: String, line: Int, character: Int): Hover? {
        return server.textDocumentService.hover(HoverParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(line, character)
        }).get(5, TimeUnit.SECONDS)
    }

    /** Flatten a Hover's contents to a string irrespective of which lsp4j shape it took. */
    private fun hoverText(h: Hover): String {
        val c = h.contents ?: return ""
        return when {
            c.isLeft -> c.left.joinToString("\n") { e ->
                if (e.isLeft) e.left else e.right.value
            }
            c.isRight -> c.right.value
            else -> ""
        }
    }

    private fun inlayHints(uri: String, source: String): List<InlayHint> {
        val lines = source.split("\n")
        val endLine = lines.size
        val endChar = lines.lastOrNull()?.length ?: 0
        return server.textDocumentService.inlayHint(InlayHintParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = Range(Position(0, 0), Position(endLine, endChar))
        }).get(5, TimeUnit.SECONDS) ?: emptyList()
    }

    /** Inlay-hint label can be a String or a list of LabelPart — flatten to text. */
    private fun labelText(h: InlayHint): String {
        val l = h.label ?: return ""
        return if (l.isLeft) l.left else l.right.joinToString("") { it.value ?: "" }
    }

    /** Captures publishDiagnostics + log notifications for assertions. */
    private inner class CaptureClient : LanguageClient {
        val logs = ConcurrentLinkedQueue<MessageParams>()
        val diagnostics = ConcurrentLinkedQueue<PublishDiagnosticsParams>()

        override fun telemetryEvent(o: Any?) {}
        override fun publishDiagnostics(p: PublishDiagnosticsParams) {
            diagnostics.add(p)
        }
        override fun showMessage(p: MessageParams) {}
        override fun showMessageRequest(p: ShowMessageRequestParams):
            CompletableFuture<MessageActionItem?> = CompletableFuture.completedFuture(null)
        override fun logMessage(p: MessageParams) { logs.add(p) }

        fun awaitDiagnostics(uri: String, timeoutMs: Long): PublishDiagnosticsParams? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val match = diagnostics.lastOrNull { it.uri == uri }
                if (match != null) return match
                Thread.sleep(50)
            }
            return null
        }
    }
}
