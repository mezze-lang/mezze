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
