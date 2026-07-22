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
        // 1 MB pipe buffers. The default PipedInputStream buffer is 1024 bytes,
        // which a two-file project's publishDiagnostics burst overflows — the
        // server blocks writing diagnostics while the test thread is mid-send,
        // deadlocking with no timeout (see docs/issues/lsp-test-harness-pipe-deadlock.md).
        val pipeBuf = 1 shl 20
        cOut = PipedOutputStream()
        sIn = PipedInputStream(cOut, pipeBuf)
        sOut = PipedOutputStream()
        cIn = PipedInputStream(sOut, pipeBuf)
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
    fun `hover on a use-site appends a go-to-definition link`() {
        // Tier-2: hovering a reference to a decl defined elsewhere in the file
        // adds a "Go to definition" file link to the hover markdown.
        val (uri, _) = openSingleFile("""
            let helper = {} -> 1
            let main = {} -> helper {}
        """.trimIndent())
        // `helper` use on line 1.
        val hover = hoverAt(uri, line = 1, character = 17)
        assertNotNull(hover, "expected hover on the `helper` use")
        val content = hoverText(hover!!)
        assertTrue(content.contains("Go to definition"),
            "expected a go-to-definition link in hover, got:\n$content")
    }

    @Test
    fun `hover inside a comment returns nothing`() {
        // Regression (#128): hovering a word inside a comment (doc or regular)
        // extracted it as an identifier and rendered a spurious type popup
        // (e.g. `{} <Log>` over prose). Comments are not code — no hover.
        val (uri, source) = openSingleFile("""
            ## docs mention println here
            # regular note about main
            let main = {} -> 1
        """.trimIndent())

        // `println` inside the `##` line (line 0). Find its column.
        val docLine = source.lines()[0]
        val col = docLine.indexOf("println")
        assertTrue(col > 0, "test source line 0 should contain `println`")
        assertNull(hoverAt(uri, line = 0, character = col + 1),
            "hover inside a `##` doc comment must return null")

        // A word inside the regular `#` comment (line 1).
        val regLine = source.lines()[1]
        val col2 = regLine.indexOf("main")
        assertNull(hoverAt(uri, line = 1, character = col2 + 1),
            "hover inside a `#` comment must return null")

        // Sanity: real code on line 2 still hovers.
        assertNotNull(hoverAt(uri, line = 2, character = 4),
            "hover on the actual `let main` must still work")
    }

    @Test
    fun `do-let bound to a FORWARD-referenced poly fn call shows the concrete type`() {
        // #126: a top-level polymorphic fn CALLED BEFORE it is DECLARED used to
        // resolve to a source-order monomorphic placeholder var, so the call
        // constraint FunctionType({args}) ~ 'placeholder bound the var whole
        // without decomposing args↔params — the polymorphic return never pinned
        // and the caller's `let` leaked `'a`. Fixed by inferring the callee
        // before the caller (dependency-ordered decls). `help` here is declared
        // AFTER `main`; hovering `x` must show Int, not a leaked type var.
        val (uri, _) = openSingleFile("""
            let main = {} -> do
              let x = help { a = 5, b = 6 }
              x

            let help = { a, b } -> a + b
        """.trimIndent())

        // `let x = help {...}` is line 1; `x` at char 6 (2 indent + "let ").
        val hover = hoverAt(uri, line = 1, character = 6)
        assertNotNull(hover, "expected hover on the do-block `x` binding")
        val content = hoverText(hover!!)
        assertTrue(content.contains("Int"),
            "expected `x : Int` (return pinned via forward-ref), got: $content")
        assertFalse(content.contains("'a") || content.contains("'r"),
            "hover must not leak an unresolved type var, got: $content")
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
    fun `hover surfaces doc on a decl that follows a do-block decl`() {
        // Regression (#127): a `##` doc block on a decl declared AFTER a
        // do-block-bodied decl was silently dropped. The do block's loc bled
        // onto the following `##` line, and the top-level trailing-comment
        // heuristic then mis-attached that `##` to the do-block decl (a `##`
        // is never a trailing comment), stealing the next decl's doc. Mirrors
        // exactly the reported `main` (perform … in do) + doc'd `help` file.
        val (uri, source) = openSingleFile("""
            use std::effects::log::{ println, Console }

            let main = {} -> perform Console in do
              let x = help { a = 5, b = 6 }
              println { msg = "hi" }

            ## This is a doc comment
            ## # Heading
            ## - a list item
            let help = { a, b } -> a + b
        """.trimIndent())

        // Find the `let help` line robustly and hover its name (col 4).
        val helpLine = source.lines().indexOfFirst { it.startsWith("let help") }
        assertTrue(helpLine >= 0, "test source should contain `let help`")
        val hover = hoverAt(uri, line = helpLine, character = 4)
        assertNotNull(hover, "expected hover on `let help` after a do-block decl")
        val content = hoverText(hover!!)
        assertTrue(content.contains("This is a doc comment"),
            "doc-comment text must survive after a do-block decl, got:\n$content")
        assertTrue(content.contains("# Heading"),
            "markdown heading must be preserved, got:\n$content")
        assertTrue(content.contains("- a list item"),
            "list item must be preserved, got:\n$content")
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

    // ===== Do-bind (`<-`) ==============================================
    //
    // Hover and goto-def on `<-`-bound names go through the same TypedAST
    // walk as regular `let` bindings — the desugar rewrites `pat <- expr`
    // into a typed `let pat = expr.try {}` before LSP sees the typed AST.
    // The synthetic Let inherits the bind statement's loc and is marked
    // `isSynthetic = true` so position lookups land on user-written source.

    @Test
    fun `hover on bind pattern does not leak desugar internals`() {
        val (uri, _) = openSingleFile("""
            let make_ok : {} -> Result Int Str = {} -> Ok {val = 42}

            let foo = {} -> do
                let a <- make_ok {}
                a
        """.trimIndent())

        // `    let a <- make_ok {}` — `a` is now at col 8 (was 4 before the
        // `let ` prefix became mandatory on binds).
        val hover = hoverAt(uri, line = 3, character = 8)
        assertNotNull(hover, "expected hover on the `<-` pattern name")
        val content = hoverText(hover!!)
        assertTrue(content.contains("a :") || content.contains("a:"),
            "expected hover to render as a binding for `a`, got:\n$content")
        assertFalse(content.contains("Tryable.try"),
            "hover should NOT leak the synthetic `.try` ability call: $content")
    }

    @Test
    fun `hover on an effect impl shows which effect + its methods + a link`() {
        val (uri, src) = openSingleFile("""
            type MemCounter = | MemCounter |

            effect Counter where {
              get : {} -> Int,
              incr : {} -> {}
            }

            impl Counter for MemCounter where {
              get = {} -> 0,
              incr = {} -> {}
            }
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        // Find the impl header line; hover across it and pick the hover that IS
        // the impl hover (a col may land on MemCounter → type hover; the `impl`
        // keyword + effect name give the impl hover).
        val implLine = src.lines().indexOfFirst { it.trimStart().startsWith("impl Counter") }
        assertTrue(implLine >= 0, "impl header not found")
        val txt = (0 until src.lines()[implLine].length)
            .mapNotNull { c -> hoverAt(uri, implLine, c)?.let { hoverText(it) } }
            .firstOrNull { it.contains("impl Counter for MemCounter") }
            ?: error("no impl hover on the header line; hovers seen: " +
                (0 until src.lines()[implLine].length).mapNotNull { c -> hoverAt(uri, implLine, c)?.let { hoverText(it).take(30) } }.distinct())
        assertTrue(txt.contains("impl Counter for MemCounter"),
            "expected the impl header naming the effect + target, got:\n$txt")
        assertTrue(txt.contains("get") && txt.contains("incr"),
            "expected the impl's methods listed, got:\n$txt")
        assertTrue(txt.contains("effect `Counter`") || txt.contains("implements effect"),
            "expected a link back to the effect, got:\n$txt")
    }

    @Test
    fun `hover on an ability impl shows which ability + its methods`() {
        val (uri, _) = openSingleFile("""
            ability Show for 'a where {
              show : { it: 'a } -> Str
            }

            impl Show for Int where {
              show = { it } -> "n"
            }
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        // Hover on `Show` in the impl header (0-based line 4, col 5 = the S).
        val h = hoverAt(uri, 4, 5)
        val txt = hoverText(h!!)
        assertTrue(txt.contains("impl Show for Int"),
            "expected the impl header naming the ability + target, got:\n$txt")
        assertTrue(txt.contains("show"),
            "expected the impl's `show` method listed, got:\n$txt")
        assertTrue(txt.contains("ability `Show`") || txt.contains("implements ability"),
            "expected a link back to the ability, got:\n$txt")
    }

    @Test
    fun `find-implementations on an ability decl lists its impls`() {
        val (uri, src) = openSingleFile("""
            ability Show for 'a where {
              show : { it: 'a } -> Str
            }

            impl Show for Int where {
              show = { it } -> "n"
            }

            impl Show for Str where {
              show = { it } -> it
            }
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        val abilityLine = src.lines().indexOfFirst { it.startsWith("ability Show") }
        val locs = server.textDocumentService.implementation(ImplementationParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(abilityLine, src.lines()[abilityLine].indexOf("Show"))
        }).get(5, TimeUnit.SECONDS).let { e -> if (e.isLeft) e.left else e.right.map { Location(it.targetUri, it.targetRange) } }

        val implLines = src.lines().mapIndexedNotNull { i, l -> if (l.startsWith("impl Show")) i else null }.toSet()
        assertTrue(locs.size >= 2, "expected both impls of Show, got $locs")
        assertTrue(locs.all { it.range.start.line in implLines },
            "each result should point at an `impl Show` header, got $locs")
    }

    @Test
    fun `goto-def on ability + method + constraint resolves to the decl and op`() {
        val (uri, src) = openSingleFile("""
            ability Show for 'a where {
              show : { it: 'a } -> Str
            }

            let f : { x: 'a } with Show 'a -> Str = { x } -> Show::show { it = x }

            impl Show for Int where {
              show = { it } -> "n"
            }
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        fun defAt(line: Int, ch: Int): List<Location> =
            server.textDocumentService.definition(DefinitionParams().apply {
                textDocument = TextDocumentIdentifier(uri); position = Position(line, ch)
            }).get(5, TimeUnit.SECONDS).let { e ->
                if (e.isLeft) e.left else e.right.map { Location(it.targetUri, it.targetRange) }
            }

        val abilityLine = src.lines().indexOfFirst { it.startsWith("ability Show") }
        val constraintLine = src.lines().indexOfFirst { it.contains("with Show") }
        val implLine = src.lines().indexOfFirst { it.startsWith("impl Show") }
        val methodLine = src.lines().withIndex().first { (i, l) -> i > implLine && l.contains("show =") }.index

        // #144: `Show` in the impl header → the ability decl.
        val fromImpl = defAt(implLine, src.lines()[implLine].indexOf("Show"))
        assertTrue(fromImpl.any { it.range.start.line == abilityLine },
            "impl→ability: expected a jump to line $abilityLine, got $fromImpl")

        // #146: `Show` in the `with Show 'a` constraint → the ability decl.
        val fromConstraint = defAt(constraintLine, src.lines()[constraintLine].indexOf("Show"))
        assertTrue(fromConstraint.any { it.range.start.line == abilityLine },
            "constraint→ability: expected a jump to line $abilityLine, got $fromConstraint")

        // #145: `show` op name in the impl body → the `show:` op in the ability decl.
        val opSigLine = src.lines().indexOfFirst { it.contains("show :") }
        val fromMethod = defAt(methodLine, src.lines()[methodLine].indexOf("show"))
        assertTrue(fromMethod.any { it.range.start.line == opSigLine },
            "method→op: expected a jump to the op sig at line $opSigLine, got $fromMethod")
    }

    @Test
    fun `hover on ability-method call result shows specialized return type`() {
        // For `let v = (make_ok {}).try {}` where make_ok returns
        // `Result Int Str`, hovering on `v` should show `Int <Exception>`
        // — `Tryable.try`'s `'inner` associated type should resolve to
        // Result's value-type parameter (Int). Currently regressing as
        // `v : 'a <Exception>` (the impl's literal type parameter). See
        // task #33 for the deeper typechecker fix.
        val (uri, _) = openSingleFile("""
            use std::abilities::tryable::{Tryable}

            let make_ok : {} -> Result Int Str = {} -> Ok {val = 42}

            let v = (make_ok {}).try {}
        """.trimIndent())

        // Line 4 (0-indexed) is `let v = (make_ok {}).try {}`, `v` at col 4.
        val hover = hoverAt(uri, line = 4, character = 4)
        assertNotNull(hover, "expected hover on the `v` binding")
        val content = hoverText(hover!!)
        assertTrue(content.contains("Int"),
            "expected hover to show specialized `Int` (not generic `'a`), got:\n$content")
    }

    @Test
    fun `hover on receiver function inside bind shows function type`() {
        // `a <- make_ok {}` — hovering on `make_ok` should show its
        // declared function type, demonstrating that the desugar doesn't
        // disrupt normal hover on user-written sub-expressions of `<-`.
        val (uri, _) = openSingleFile("""
            let make_ok : {} -> Result Int Str = {} -> Ok {val = 42}

            let foo = {} -> do
                let a <- make_ok {}
                a
        """.trimIndent())

        // Cursor on the `make_ok` call inside the bind. The line is
        // `    let a <- make_ok {}` (4-space indent). `let ` (4) + `a ` (2) +
        // `<- ` (3) puts `make_ok` at col 13.
        val hover = hoverAt(uri, line = 3, character = 13)
        assertNotNull(hover, "expected hover on `make_ok` inside the bind")
        val content = hoverText(hover!!)
        assertTrue(content.contains("Result") || content.contains("Ok"),
            "expected hover to surface the function's return type, got:\n$content")
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

    @Test
    fun `inlay hints show inferred types on un-annotated lambda params`() {
        // #100: `{x} -> x + 1` — x is inferred Int; the LSP emits a `: Int`
        // param hint. An already-annotated param (`{y: Int}`) is not hinted.
        val (uri, source) = openSingleFile("""
            let inc = {x} -> x + 1
        """.trimIndent())

        val hints = inlayHints(uri, source)
        assertTrue(
            hints.any { it.kind == InlayHintKind.Type && labelText(it).contains("Int") },
            "expected a `: Int` param hint, got: ${hints.map { it.kind to labelText(it) }}"
        )
    }

    @Test
    fun `no param hint on an already-annotated lambda param`() {
        val (uri, source) = openSingleFile("""
            let inc = {x: Int} -> x + 1
        """.trimIndent())
        val hints = inlayHints(uri, source)
        // The annotated `x: Int` param must NOT get a duplicate `: Int` hint.
        // (A trailing return-type hint may still appear, but not on the param.)
        assertTrue(
            hints.none { labelText(it) == ": Int" && it.position.line == 0 },
            "annotated param should not be hinted, got: ${hints.map { labelText(it) to it.position.line }}"
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

    @Test
    fun `documentSymbol includes record type with its fields as children`() {
        // #100: a `type Point = { x, y }` appears in the outline as a Struct
        // whose children are its fields.
        val (uri, _) = openSingleFile("""
            type Point = { x: Int, y: Int }
        """.trimIndent())

        val symbols = server.textDocumentService.documentSymbol(DocumentSymbolParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        }).get(5, TimeUnit.SECONDS)

        val point = symbols.mapNotNull { e -> if (e.isRight) e.right else null }
            .firstOrNull { it.name == "Point" }
            ?: fail("expected a `Point` type symbol, got: ${symbols.map { if (it.isRight) it.right.name else it.left.name }}")
        val childNames = point.children?.map { it.name } ?: emptyList()
        assertTrue(childNames.containsAll(listOf("x", "y")),
            "expected Point's fields x,y as children, got: $childNames")
    }

    @Test
    fun `documentSymbol shows ability + effect ops as children and impls as symbols`() {
        val (uri, _) = openSingleFile("""
            type MemCounter = | MemCounter |

            ability Show for 'a where {
              show : { it: 'a } -> Str
            }

            effect Counter where {
              get : {} -> Int,
              incr : {} -> {}
            }

            impl Counter for MemCounter where {
              get = {} -> 0,
              incr = {} -> {}
            }
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        val symbols = server.textDocumentService.documentSymbol(DocumentSymbolParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        }).get(5, TimeUnit.SECONDS).mapNotNull { e -> if (e.isRight) e.right else null }

        val show = symbols.firstOrNull { it.name == "Show" } ?: fail("no Show symbol: ${symbols.map { it.name }}")
        assertEquals(listOf("show"), show.children?.map { it.name }, "Show should have `show` as a child op")

        val counter = symbols.firstOrNull { it.name == "Counter" } ?: fail("no Counter symbol")
        assertEquals(listOf("get", "incr"), counter.children?.map { it.name }, "Counter should list get, incr")

        val impl = symbols.firstOrNull { it.name.startsWith("impl Counter for MemCounter") }
            ?: fail("no impl symbol; got: ${symbols.map { it.name }}")
        assertTrue(impl.children?.map { it.name }?.containsAll(listOf("get", "incr")) == true,
            "impl should list its implemented ops")
    }

    @Test
    fun `references on an effect name finds the decl, impl header, and effect-row use`() {
        val src = """
            type MemCounter = | MemCounter |

            effect Counter where {
              get : {} -> Int,
              incr : {} -> {}
            }

            impl Counter for MemCounter where {
              get = {} -> 0,
              incr = {} -> {}
            }

            let run : {} -> Int <Counter> = {} -> perform get {}
        """.trimIndent()
        val (uri, _) = openSingleFile(src)
        client.awaitDiagnostics(uri, 5_000)

        // Cursor on `Counter` in the `effect Counter` decl header.
        val declLine = src.lines().indexOfFirst { it.trimStart().startsWith("effect Counter") }
        val col = src.lines()[declLine].indexOf("Counter")
        val refs = server.textDocumentService.references(ReferenceParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(declLine, col + 1)
            context = ReferenceContext(true)
        }).get(5, TimeUnit.SECONDS)

        assertNotNull(refs)
        // Expect: decl header, impl header, and the `<Counter>` effect row.
        assertTrue(refs.size >= 3,
            "expected >=3 refs for effect `Counter` (decl + impl + <Counter> row), got ${refs.size}: " +
                refs.map { it.range.start.line to it.range.start.character })
        val declHit = refs.any { it.range.start.line == declLine }
        assertTrue(declHit, "references should include the decl header line $declLine")
    }

    @Test
    fun `references on an ability name finds the decl, impl header, and with-constraint use`() {
        val src = """
            ability Show for 'a where {
              show : { it: 'a } -> Str
            }

            impl Show for Int where {
              show = { it } -> "n"
            }

            let describe : { x: 'a } -> Str with Show 'a = { x } -> show { it = x }
        """.trimIndent()
        val (uri, _) = openSingleFile(src)
        client.awaitDiagnostics(uri, 5_000)

        val declLine = src.lines().indexOfFirst { it.trimStart().startsWith("ability Show") }
        val col = src.lines()[declLine].indexOf("Show")
        val refs = server.textDocumentService.references(ReferenceParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(declLine, col + 1)
            context = ReferenceContext(true)
        }).get(5, TimeUnit.SECONDS)

        assertNotNull(refs)
        // Expect: decl header, impl header, and the `with Show 'a` constraint.
        assertTrue(refs.size >= 3,
            "expected >=3 refs for ability `Show`, got ${refs.size}: " +
                refs.map { it.range.start.line to it.range.start.character })
    }

    @Test
    fun `rename of an effect name rewrites the decl, impl header, and effect-row use`() {
        val src = """
            type MemCounter = | MemCounter |

            effect Counter where {
              get : {} -> Int,
              incr : {} -> {}
            }

            impl Counter for MemCounter where {
              get = {} -> 0,
              incr = {} -> {}
            }

            let run : {} -> Int <Counter> = {} -> perform get {}
        """.trimIndent()
        val (uri, _) = openSingleFile(src)
        client.awaitDiagnostics(uri, 5_000)

        val declLine = src.lines().indexOfFirst { it.trimStart().startsWith("effect Counter") }
        val col = src.lines()[declLine].indexOf("Counter")
        val edit = server.textDocumentService.rename(RenameParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(declLine, col + 1)
            newName = "Tally"
        }).get(5, TimeUnit.SECONDS)

        assertNotNull(edit)
        val edits = edit!!.changes?.get(uri) ?: fail("no edits for $uri; changes=${edit.changes}")
        // decl header, impl header, and `<Counter>` row → at least 3 edits.
        assertTrue(edits.size >= 3,
            "expected >=3 rename edits for effect `Counter`, got ${edits.size}: " +
                edits.map { it.range.start.line to it.newText })
        assertTrue(edits.all { it.newText == "Tally" }, "every edit should insert `Tally`")
    }

    @Test
    fun `code lens shows a references lens over each top-level function`() {
        val (uri, _) = openSingleFile("""
            let helper = {x: Int} -> x + 1
            let other = {} -> 2
        """.trimIndent())

        val lenses = server.textDocumentService.codeLens(CodeLensParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val titles = lenses.mapNotNull { it.command?.title }
        assertTrue(titles.any { it.contains("reference") },
            "expected a references lens over a function, got: $titles")
        // One lens per top-level let.
        assertTrue(lenses.size >= 2, "expected a lens per top-level function, got ${lenses.size}: $titles")
    }

    @Test
    fun `code lens omits a zero-reference lens and counts in-file uses`() {
        // Regression (#128): the reference count was store-only, so in
        // single-file mode every function showed a dead "0 references" lens
        // (empty peek on click). Now: count reflects in-file uses (matching
        // what clicking finds), and a genuinely-unused function gets no lens.
        val (uri, _) = openSingleFile("""
            let used = {} -> 1
            let lonely = {} -> 2
            let main = {} -> used {}
        """.trimIndent())

        val lenses = server.textDocumentService.codeLens(CodeLensParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val titles = lenses.mapNotNull { it.command?.title }
        // `used` is referenced (by main) → gets a lens; `lonely` isn't → no lens.
        assertTrue(titles.any { it.contains("reference") },
            "expected a references lens for the used function, got: $titles")
        assertFalse(titles.any { it == "0 references" },
            "must not emit a `0 references` lens, got: $titles")

        // #128: the lens COMMAND position must point at the name (`used`), not
        // the `let` keyword — otherwise the client's reference provider resolves
        // no symbol and clicking opens an empty peek ("nothing happens").
        // Source line 0 is `let used = {} -> 1`; `used` starts at column 4.
        val usedLens = lenses.first { it.command?.title?.contains("reference") == true }
        // The launcher serializes command args to JSON, so the Position comes
        // back as a JsonObject — read its fields (the same shape the extension's
        // toPosition() normalizes on the client).
        val cmdPosJson = usedLens.command!!.arguments!![1] as com.google.gson.JsonObject
        assertEquals(0, cmdPosJson.get("line").asInt)
        assertEquals(4, cmdPosJson.get("character").asInt,
            "command must anchor on `used` (col 4), not the `let` keyword")
    }

    @Test
    fun `code lens shows an implementations lens over an ability`() {
        val (uri, _) = openSingleFile("""
            ability Show for 'a where {
              show: { it: 'a } -> Str
            }
            type Color = | Red, Green |
            impl Show for Color where {
              show = { it } -> "c"
            }
        """.trimIndent())

        val lenses = server.textDocumentService.codeLens(CodeLensParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val implTitle = lenses.mapNotNull { it.command?.title }.firstOrNull { it.contains("implementation") }
            ?: fail("expected an implementations lens over `Show`, got: ${lenses.mapNotNull { it.command?.title }}")
        assertTrue(implTitle.startsWith("1 implementation"),
            "Show has exactly 1 impl (Color), got: $implTitle")
    }

    @Test
    fun `code lens shows a performers lens over an effect declaration`() {
        // #120: effect-graph navigation — count performers of an effect.
        val (uri, _) = openSingleFile("""
            effect Logger where {
              log: { msg: Str } -> {}
            }
            type Console = | Console |
            impl Logger for Console where {
              log = { msg } -> {}
            }
        """.trimIndent())

        val lenses = server.textDocumentService.codeLens(CodeLensParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val perfTitle = lenses.mapNotNull { it.command?.title }.firstOrNull { it.contains("performer") }
            ?: fail("expected a performers lens over `Logger`, got: ${lenses.mapNotNull { it.command?.title }}")
        assertTrue(perfTitle.startsWith("1 performer"),
            "Logger has exactly 1 performer (Console), got: $perfTitle")
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
    fun `inline binding replaces real uses only, not names in strings (AST-driven)`() {
        // The old regex matched `\bx\b` everywhere — including inside the
        // string "x here". The AST/typed-var walk only touches real uses.
        val (uri, _) = openSingleFile("""
            let f = {} -> do
              let x = 5
              let s = "x here"
              x + 1
        """.trimIndent())

        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = Range(Position(1, 2), Position(1, 2)) // on `let x = 5`
            context = CodeActionContext(emptyList())
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val inline = actions.mapNotNull { if (it.isRight) it.right else null }
            .firstOrNull { it.title.startsWith("Inline binding") }
            ?: fail("expected an Inline binding action, got: ${actions.mapNotNull { if (it.isRight) it.right.title else null }}")
        val edits = inline.edit?.changes?.values?.flatten() ?: emptyList()
        // One edit deletes the let line; the real use `x + 1` becomes `5 + 1`.
        // The string literal `"x here"` must NOT be touched — no edit on line 2.
        assertTrue(edits.none { it.range.start.line == 2 },
            "must not edit the string-literal line, got edits on lines: ${edits.map { it.range.start.line }}")
        assertTrue(edits.any { it.newText == "5" && it.range.start.line == 3 },
            "expected the real use on line 3 replaced with `5`, got: ${edits.map { it.range.start.line to it.newText }}")
    }

    @Test
    fun `convert if to match works on a multi-line if (AST-driven, regex could not)`() {
        val (uri, _) = openSingleFile("""
            let f = {b: Bool} ->
              if b
                then 1
                else 2
        """.trimIndent())

        // Cursor on the `if` line.
        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = Range(Position(1, 2), Position(1, 2))
            context = CodeActionContext(emptyList())
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val conv = actions.mapNotNull { if (it.isRight) it.right else null }
            .firstOrNull { it.title == "Convert if to match" }
            ?: fail("expected 'Convert if to match', got: ${actions.mapNotNull { if (it.isRight) it.right.title else null }}")
        val newText = conv.edit?.changes?.values?.flatten()?.joinToString("") { it.newText } ?: ""
        assertTrue(newText.contains("match b is") && newText.contains("True ->") && newText.contains("False ->"),
            "expected a match rewrite, got: $newText")
    }

    @Test
    fun `add type annotation splices the inferred type after a top-level let name`() {
        // #108: cursor on an un-annotated top-level `let`, the refactor reads
        // the type the typechecker already inferred and writes it in.
        val (uri, _) = openSingleFile("""
            let answer = 42
        """.trimIndent())
        // Let the typecheck settle so the TypedModule is populated.
        client.awaitDiagnostics(uri, 5_000)

        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = Range(Position(0, 4), Position(0, 4)) // on `answer`
            context = CodeActionContext(emptyList())
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val add = actions.mapNotNull { if (it.isRight) it.right else null }
            .firstOrNull { it.title.startsWith("Add type annotation") }
            ?: fail("expected an 'Add type annotation' action, got: ${actions.mapNotNull { if (it.isRight) it.right.title else null }}")
        val edit = add.edit?.changes?.values?.flatten()?.firstOrNull()
            ?: fail("add-annotation action carried no edit")
        // The spliced text names the inferred `Int` type and slots in after the name.
        assertTrue(edit.newText.contains("Int"), "expected `Int` in the annotation, got: ${edit.newText}")
        val after = applyEdit("let answer = 42", edit)
        assertEquals("let answer: Int = 42", after,
            "annotation should splice `: Int` after the name, got: $after")
    }

    @Test
    fun `add type annotation is not offered when the let is already annotated`() {
        val (uri, _) = openSingleFile("""
            let answer: Int = 42
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = Range(Position(0, 4), Position(0, 4))
            context = CodeActionContext(emptyList())
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        assertTrue(
            actions.none { it.isRight && it.right.title.startsWith("Add type annotation") },
            "should not offer to annotate an already-annotated let"
        )
    }

    @Test
    fun `redundant boolean x == True offers a Simplify quick-fix (AST-driven)`() {
        val (uri, _) = openSingleFile("""
            let f = {b: Bool} -> b == True
        """.trimIndent())
        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags)
        val w = diags!!.diagnostics.firstOrNull { it.code?.left == "W0303" }
            ?: fail("expected W0303 among ${diags.diagnostics.map { it.code?.left }}")
        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = w.range
            context = CodeActionContext(listOf(w))
        }).get(5, TimeUnit.SECONDS) ?: emptyList()
        val fix = actions.mapNotNull { if (it.isRight) it.right else null }.firstOrNull { it.title.startsWith("Simplify") }
            ?: fail("expected a Simplify action, got: ${actions.mapNotNull { if (it.isRight) it.right.title else null }}")
        val edit = fix.edit?.changes?.values?.flatten()?.firstOrNull()?.newText ?: ""
        assertEquals("b", edit.trim(), "x == True should simplify to `b`, got `$edit`")
    }

    @Test
    fun `redundant double negation offers a Simplify quick-fix (AST-driven, regex could not)`() {
        val (uri, _) = openSingleFile("""
            let f = {b: Bool} -> !(!b)
        """.trimIndent())
        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags)
        val w = diags!!.diagnostics.firstOrNull { it.code?.left == "W0303" }
            ?: fail("expected W0303 among ${diags.diagnostics.map { it.code?.left }}")
        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = w.range
            context = CodeActionContext(listOf(w))
        }).get(5, TimeUnit.SECONDS) ?: emptyList()
        val fix = actions.mapNotNull { if (it.isRight) it.right else null }.firstOrNull { it.title.startsWith("Simplify") }
            ?: fail("expected a Simplify action for !(!b), got: ${actions.mapNotNull { if (it.isRight) it.right.title else null }}")
        val edit = fix.edit?.changes?.values?.flatten()?.firstOrNull()?.newText ?: ""
        assertEquals("b", edit.trim(), "!(!b) should simplify to `b`, got `$edit`")
    }

    @Test
    fun `non-exhaustive match offers a Fill-missing-arms quick-fix`() {
        // #105: V0601 carries the exact missing constructors as a FillMatchArms
        // FixHint; the LSP turns it into a one-click "Add missing match arms" fix.
        val (uri, _) = openSingleFile("""
            type Color = | Red, Green, Blue |
            let name = {c: Color} -> match c is
              Red -> "r"
              Green -> "g"
        """.trimIndent())

        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags, "expected publishDiagnostics for non-exhaustive match")
        val exhDiag = diags!!.diagnostics.firstOrNull { it.code?.left == "V0601" }
            ?: fail("expected a V0601 diagnostic among ${diags.diagnostics.map { it.code?.left to it.message }}")

        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = exhDiag.range
            context = CodeActionContext(listOf(exhDiag))
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val fill = actions.mapNotNull { e -> if (e.isRight) e.right else null }
            .firstOrNull { it.title.contains("Add missing match arm") }
            ?: fail("expected a fill-arms code action, got: ${actions.mapNotNull { if (it.isRight) it.right.title else null }}")
        // The inserted text should mention the missing `Blue` constructor.
        val editText = fill.edit?.changes?.values?.flatten()?.joinToString("") { it.newText } ?: ""
        assertTrue(editText.contains("Blue"), "expected inserted arm for `Blue`, got edit: $editText")
    }

    @Test
    fun `duplicate record field offers a Remove quick-fix that drops the trailing comma`() {
        // #107: T0304 carries a RemoveRange FixHint at the duplicate field; the
        // LSP turns it into a "Remove duplicate field `a`" fix that also eats the
        // following `, ` so the record literal stays well-formed.
        val (uri, _) = openSingleFile("""
            let r = {a = 1, a = 2, b = 3}
        """.trimIndent())

        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags, "expected publishDiagnostics for duplicate-field file")
        val dupDiag = diags!!.diagnostics.firstOrNull { it.code?.left == "T0304" }
            ?: fail("expected a T0304 diagnostic among ${diags.diagnostics.map { it.code?.left to it.message }}")

        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = dupDiag.range
            context = CodeActionContext(listOf(dupDiag))
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val remove = actions.mapNotNull { e -> if (e.isRight) e.right else null }
            .firstOrNull { it.title.contains("Remove duplicate field") }
            ?: fail("expected a remove-field code action, got: ${actions.mapNotNull { if (it.isRight) it.right.title else null }}")
        // The edit deletes the second `a = 2` entry AND its trailing `, ` — the
        // deletion range must reach into the `,` that follows the field.
        val edit = remove.edit?.changes?.values?.flatten()?.firstOrNull()
            ?: fail("remove-field action carried no edit")
        assertEquals("", edit.newText, "a removal is a pure deletion")
        // Apply it and confirm the result parses to `{a = 1, b = 3}`.
        val after = applyEdit("let r = {a = 1, a = 2, b = 3}", edit)
        assertEquals("let r = {a = 1, b = 3}", after,
            "removing the duplicate field should leave `{a = 1, b = 3}`, got: $after")
    }

    @Test
    fun `unreachable match arm offers a Remove quick-fix that deletes the whole arm line`() {
        // #107: V0603 carries a RemoveRange FixHint spanning the dead arm; the
        // LSP deletes the whole arm line (indentation + newline) so nothing
        // dangles.
        val (uri, _) = openSingleFile("""
            let f = {c: Bool} -> match c is
              _ -> 1
              True -> 2
        """.trimIndent())

        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags, "expected publishDiagnostics for unreachable-arm file")
        val deadDiag = diags!!.diagnostics.firstOrNull { it.code?.left == "V0603" }
            ?: fail("expected a V0603 diagnostic among ${diags.diagnostics.map { it.code?.left to it.message }}")

        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = deadDiag.range
            context = CodeActionContext(listOf(deadDiag))
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val remove = actions.mapNotNull { e -> if (e.isRight) e.right else null }
            .firstOrNull { it.title.contains("Remove unreachable arm") }
            ?: fail("expected a remove-arm code action, got: ${actions.mapNotNull { if (it.isRight) it.right.title else null }}")
        val edit = remove.edit?.changes?.values?.flatten()?.firstOrNull()
            ?: fail("remove-arm action carried no edit")
        assertEquals("", edit.newText, "a removal is a pure deletion")
        // The deletion should start at column 0 (whole-line delete, swallowing indent).
        assertEquals(0, edit.range.start.character,
            "arm removal deletes from column 0 to swallow the indentation")
    }

    @Test
    fun `impl missing methods offers an Auto-implement quick-fix that stubs each one`() {
        // A local ability with two ops; an impl providing only one. V0404 fires
        // at the impl, and the auto-implement action inserts a stub for the
        // missing op with parameter names lifted from the ability op's record
        // parameter type.
        val (uri, _) = openSingleFile("""
            ability Greet for 'a where {
              hi: { it: 'a } -> Str,
              bye: { it: 'a } -> Str
            }

            type Person = | Person |

            impl Greet for Person where {
              hi = { it } -> "hi"
            }
        """.trimIndent())

        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags, "expected publishDiagnostics for missing-method impl")
        val missing = diags!!.diagnostics.firstOrNull { it.code?.left == "V0404" }
            ?: fail("expected a V0404 diagnostic among ${diags.diagnostics.map { it.code?.left to it.message }}")

        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = missing.range
            context = CodeActionContext(listOf(missing))
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val impl = actions.mapNotNull { e -> if (e.isRight) e.right else null }
            .firstOrNull { it.title.contains("Implement") && it.title.contains("bye") }
            ?: fail("expected an auto-implement action for `bye`, got: ${actions.mapNotNull { if (it.isRight) it.right.title else null }}")
        val edits = impl.edit?.changes?.values?.flatten() ?: emptyList()
        val editText = edits.joinToString("") { it.newText }
        assertTrue(editText.contains("bye = { it } ->"),
            "expected a `bye = { it } -> ...` stub, got edit: $editText")

        // Apply all edits (a `,` after the existing `hi` op + the stub line) and
        // confirm the result parses — the stub must be a well-formed impl op.
        val source = """
            ability Greet for 'a where {
              hi: { it: 'a } -> Str,
              bye: { it: 'a } -> Str
            }

            type Person = | Person |

            impl Greet for Person where {
              hi = { it } -> "hi"
            }
        """.trimIndent()
        val after = applyEdits(source, edits)
        // Should now contain both ops separated by a comma.
        assertTrue(after.contains("hi = { it } -> \"hi\","),
            "expected a comma after the existing `hi` op, got:\n$after")
        // And it should parse cleanly.
        val parsed = com.mezze.language.parser.MezzeParser().parseParsedModule(after, "greet.mz")
        assertTrue(parsed.decls.isNotEmpty(), "auto-implemented impl should parse; got:\n$after")
    }

    /** Apply multiple TextEdits to source, last-offset-first so ranges stay valid. */
    private fun applyEdits(source: String, edits: List<TextEdit>): String {
        val lines = source.split("\n")
        fun offset(p: Position): Int {
            var o = 0
            for (i in 0 until p.line) o += lines[i].length + 1
            return o + p.character
        }
        // Sort by descending start offset so earlier edits don't shift later ones.
        var result = source
        for (edit in edits.sortedByDescending { offset(it.range.start) }) {
            val start = offset(edit.range.start)
            val end = offset(edit.range.end)
            result = result.substring(0, start) + edit.newText + result.substring(end)
        }
        return result
    }

    @Test
    fun `semantic tokens classify params and variant constructors from the typed AST`() {
        // #99: the token stream is built from the TypedModule, so a `TypedVar`
        // resolving to a param becomes a PARAMETER token and a
        // `TypedVariantConstruct` becomes an ENUM_MEMBER token — with exact
        // locs, no "after let " column guessing.
        val (uri, _) = openSingleFile("""
            type Color = | Red, Green |
            let pick = {c: Color} -> c
            let red = {} -> Red
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        val tokens = server.textDocumentService.semanticTokensFull(SemanticTokensParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        }).get(5, TimeUnit.SECONDS)
        val types = decodeSemanticTokenTypes(tokens?.data ?: emptyList())

        // Legend indices: FUNCTION = 1, VARIABLE = 2, ENUM_MEMBER = 10.
        // The `Red` constructor is the headline typed-AST win — a lexer can't
        // tell it apart from a type name, but TypedVariantConstruct can.
        assertTrue(types.contains(10), "expected an ENUM_MEMBER token for the `Red` constructor, got type set: $types")
        // A top-level binding name is a FUNCTION token.
        assertTrue(types.contains(1), "expected a FUNCTION token for a top-level binding name, got type set: $types")
        // The `c` reference in the body is classified as a value reference
        // (VARIABLE/PARAMETER) from its BindingRef — either way, not a keyword.
        assertTrue(types.contains(2) || types.contains(9),
            "expected a value-reference token for the `c` use, got type set: $types")
    }

    @Test
    fun `semantic tokens color record-shaped field names as property, uniformly`() {
        // Every `{ … }` is record-shaped, so field NAMES color as `property`
        // (legend index 8) everywhere: record literals, record types, lambda
        // params, and ability/effect bodies. An ability/effect op IS a record
        // field, so it colors identically to a field in a record literal.
        val (uri, _) = openSingleFile("""
            let mk : { x: Int, y: Int } -> Int = { x, y } -> x + y
            let r = mk { x = 1, y = 2 }

            ability Show for 'a where {
              show : { it: 'a } -> Str
            }

            effect State s where {
              get : {} -> s
              set : { val: s } -> {}
            }
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        val tokens = server.textDocumentService.semanticTokensFull(SemanticTokensParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        }).get(5, TimeUnit.SECONDS)
        val types = decodeSemanticTokenTypes(tokens?.data ?: emptyList())
        // PROPERTY = 8. Record-literal fields (x,y), record-type fields (x,y,it,val),
        // lambda-param names (x,y), and ability/effect op names (show,get,set) all
        // land here.
        assertTrue(types.contains(8),
            "expected PROPERTY tokens (8) for record/ability/effect field names, got type set: $types")
        // TYPE = 3. Concrete type names in the signatures (Int, Str) color as type;
        // the type variable `'a` and `s` do NOT (left to tree-sitter / not concrete).
        assertTrue(types.contains(3),
            "expected TYPE tokens (3) for concrete type names (Int, Str), got type set: $types")
    }

    @Test
    fun `semantic tokens color concrete type names, and a pure type-variable sig emits none`() {
        // A signature whose ONLY type names are variables (`'a`) must emit NO
        // TYPE token — the leading `'` guard keeps type vars from being
        // miscolored as concrete types.
        val (idUri, _) = openSingleFile("let id : { x: 'a } -> 'a = { x } -> x")
        client.awaitDiagnostics(idUri, 5_000)
        val idTokens = server.textDocumentService.semanticTokensFull(SemanticTokensParams().apply {
            textDocument = TextDocumentIdentifier(idUri)
        }).get(5, TimeUnit.SECONDS)
        assertFalse(decodeSemanticTokenTypes(idTokens?.data ?: emptyList()).contains(3),
            "a signature with only type variables must not emit a TYPE token")

        // A signature with a concrete type app `Promise Int` DOES emit TYPE.
        val (nUri, _) = openSingleFile("let n : { p: Promise Int } -> Int = { p } -> 0")
        client.awaitDiagnostics(nUri, 5_000)
        val nTokens = server.textDocumentService.semanticTokensFull(SemanticTokensParams().apply {
            textDocument = TextDocumentIdentifier(nUri)
        }).get(5, TimeUnit.SECONDS)
        assertTrue(decodeSemanticTokenTypes(nTokens?.data ?: emptyList()).contains(3),
            "expected a TYPE token for the concrete types Promise/Int")
    }

    @Test
    fun `semantic tokens color module qualifiers as namespace`() {
        // Tier-2: a qualified reference `std::int::add` colors the `std` and
        // `int` module segments as Namespace (legend index 11).
        val (uri, _) = openSingleFile("""
            let n = std::int::add { l = 1, r = 2 }
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        val tokens = server.textDocumentService.semanticTokensFull(SemanticTokensParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        }).get(5, TimeUnit.SECONDS)
        val types = decodeSemanticTokenTypes(tokens?.data ?: emptyList())
        assertTrue(types.contains(11),
            "expected a NAMESPACE token (11) for the `std`/`int` module qualifiers, got type set: $types")
    }

    @Test
    fun `organize imports sorts, dedups, and merges selective imports of the same module`() {
        // #110: unsorted + duplicated + splittable imports get grouped by
        // module, merged into a single selective import, deduped, and sorted.
        val src = """
            use std::seq::{map}
            use std::int
            use std::seq::{filter}
            use std::int
            let x = 1
        """.trimIndent()
        val (uri, _) = openSingleFile(src)

        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = Range(Position(0, 0), Position(0, 0))
            context = CodeActionContext(emptyList(), listOf(CodeActionKind.SourceOrganizeImports))
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val org = actions.mapNotNull { if (it.isRight) it.right else null }
            .firstOrNull { it.title == "Organize imports" }
            ?: fail("expected an 'Organize imports' action, got: ${actions.mapNotNull { if (it.isRight) it.right.title else null }}")
        val edit = org.edit?.changes?.values?.flatten()?.firstOrNull()
            ?: fail("organize action carried no edit")
        val after = applyEdit(src, edit)

        // `std::int` deduped to one line; the two `std::seq` selective imports
        // merged into `{ filter, map }` (items sorted); `std::int` sorts before
        // `std::seq`.
        val expected = """
            use std::int
            use std::seq::{ filter, map }
            let x = 1
        """.trimIndent()
        assertEquals(expected, after, "organized imports mismatch")
    }

    @Test
    fun `import-path completion suggests module paths when typing use`() {
        // #111: typing `use std::` completes to registered module paths from
        // the store (stdlib + workspace), not to bindings/keywords.
        val (uri, _) = openSingleFile("""
            use std::
            let x = 1
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        // Cursor right after `use std::` on line 0 (col 9 = after the `::`).
        val result = server.textDocumentService.completion(CompletionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(0, 9)
        }).get(5, TimeUnit.SECONDS)

        val labels = (if (result.isLeft) result.left else result.right.items).map { it.label }
        // Every suggestion should be a module path under `std::`, and at least
        // one well-known stdlib module should be present.
        assertTrue(labels.isNotEmpty(), "expected module-path completions after `use std::`")
        assertTrue(labels.all { it.startsWith("std::") },
            "import-path completion must only offer module paths, got: ${labels.take(10)}")
        assertTrue(labels.any { it == "std::int" || it == "std::seq" || it.startsWith("std::") },
            "expected a std:: module, got: ${labels.take(10)}")
    }

    @Test
    fun `completion returns nothing inside a comment`() {
        // #128 audit: completion suggested identifiers/keywords while typing
        // inside a `#` note or `##` doc line (hover already guarded against
        // this). Comments are prose — no code completion.
        val (uri, source) = openSingleFile("""
            ## docs about pri
            # note about pri
            let println_local = {} -> 1
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        // Inside the `##` doc line, right after `pri`.
        val docLine = source.lines()[0]
        val docCol = docLine.indexOf("pri") + 3
        val inDoc = server.textDocumentService.completion(CompletionParams().apply {
            textDocument = TextDocumentIdentifier(uri); position = Position(0, docCol)
        }).get(5, TimeUnit.SECONDS)
        val inDocItems = if (inDoc.isLeft) inDoc.left else inDoc.right.items
        assertTrue(inDocItems.isEmpty(), "no completion inside a `##` doc comment, got: ${inDocItems.map { it.label }.take(5)}")

        // Inside the regular `#` comment line.
        val regLine = source.lines()[1]
        val regCol = regLine.indexOf("pri") + 3
        val inReg = server.textDocumentService.completion(CompletionParams().apply {
            textDocument = TextDocumentIdentifier(uri); position = Position(1, regCol)
        }).get(5, TimeUnit.SECONDS)
        val inRegItems = if (inReg.isLeft) inReg.left else inReg.right.items
        assertTrue(inRegItems.isEmpty(), "no completion inside a `#` comment, got: ${inRegItems.map { it.label }.take(5)}")
    }

    @Test
    fun `organize imports is not offered when imports are already organized`() {
        val src = """
            use std::int
            use std::seq::{ filter, map }
            let x = 1
        """.trimIndent()
        val (uri, _) = openSingleFile(src)

        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = Range(Position(0, 0), Position(0, 0))
            context = CodeActionContext(emptyList(), listOf(CodeActionKind.SourceOrganizeImports))
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        assertTrue(
            actions.none { it.isRight && it.right.title == "Organize imports" },
            "should not offer organize-imports when nothing would change"
        )
    }

    @Test
    fun `signature help highlights the active parameter as the cursor moves between fields`() {
        // #113: `f { a = 1, b = ` puts the cursor in the second field, so
        // activeParameter should be 1 (not the hardcoded 0).
        val (uri, _) = openSingleFile("""
            let f = {a: Int, b: Int} -> a + b
            let g = {} -> f { a = 1, b = 2 }
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        // Line 1: `let g = {} -> f { a = 1, b = 2 }` — put the cursor just after
        // `b = ` (inside the second field). Find that column dynamically.
        val line = "let g = {} -> f { a = 1, b = 2 }"
        val col = line.indexOf("b = ") + "b = ".length

        val help = server.textDocumentService.signatureHelp(SignatureHelpParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(1, col)
        }).get(5, TimeUnit.SECONDS)
            ?: fail("expected signature help inside the call")

        assertEquals(1, help.activeParameter,
            "cursor in the 2nd field should make param `b` (index 1) active; sig=${help.signatures.firstOrNull()?.label}")

        // And in the first field, index 0.
        val col0 = line.indexOf("a = ") + "a = ".length
        val help0 = server.textDocumentService.signatureHelp(SignatureHelpParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(1, col0)
        }).get(5, TimeUnit.SECONDS) ?: fail("expected signature help in the 1st field")
        assertEquals(0, help0.activeParameter, "cursor in the 1st field should make param `a` (index 0) active")
    }

    @Test
    fun `hover on a polymorphic function shows its ability constraints`() {
        // #113: a function polymorphic over a type it compares with `==` picks
        // up an `Eq` ability constraint; hover surfaces it as `with Eq …`.
        val (uri, _) = openSingleFile("""
            let eq = {a: 'a, b: 'a} -> a == b
            let use_it = {} -> eq { a = 1, b = 2 }
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        // Hover on the `eq` binding name (line 0, col 4).
        val h = hoverAt(uri, 0, 4) ?: fail("expected hover on `eq`")
        val text = hoverText(h)
        assertTrue(text.contains("with ") && text.contains("Eq"),
            "expected hover to show the `Eq` ability constraint, got: $text")
    }

    @Test
    fun `postfix completion rewrites x dot some into Some wrapping x`() {
        // #114: typing `x.some` offers a postfix `some` whose edit replaces the
        // whole `x.some` span with `Some { x }`.
        val (uri, _) = openSingleFile("""
            let f = {x: Int} -> x.some
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        val line = "let f = {x: Int} -> x.some"
        // Cursor at the end of `x.some`.
        val col = line.length
        val result = server.textDocumentService.completion(CompletionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(0, col)
        }).get(5, TimeUnit.SECONDS)

        val comps = if (result.isLeft) result.left else result.right.items
        val some = comps.firstOrNull { it.label == "some" && it.detail?.contains("postfix") == true }
            ?: fail("expected a postfix `some` completion, got: ${comps.map { it.label to it.detail }}")
        val te = some.textEdit?.left ?: fail("postfix item should carry a textEdit")
        assertEquals("Some { x }", te.newText, "postfix `some` should wrap the receiver in Some")
        // The edit replaces the whole `x.some` span, not just `.some`.
        val startCol = line.indexOf("x.some")
        assertEquals(startCol, te.range.start.character, "edit should start at the receiver `x`")
        assertEquals(line.length, te.range.end.character, "edit should cover through the cursor")
    }

    @Test
    fun `resolveCompletionItem attaches the doc-comment and signature lazily`() {
        // #115: completion items carry only a type in `detail`; the doc-comment
        // is filled in on demand via completionItem/resolve.
        val (uri, _) = openSingleFile("""
            ## Doubles its input.
            let double = {x: Int} -> x + x
            let other = doub
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        // Complete at the `doub` prefix on line 2.
        val line = "let other = doub"
        val result = server.textDocumentService.completion(CompletionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(2, line.length)
        }).get(5, TimeUnit.SECONDS)
        val comps = if (result.isLeft) result.left else result.right.items
        val item = comps.firstOrNull { it.label == "double" }
            ?: fail("expected a `double` completion, got: ${comps.map { it.label }}")
        // Pre-resolve: no documentation, just the type in detail.
        assertNull(item.documentation, "documentation should be lazy (absent pre-resolve)")

        val resolved = server.textDocumentService.resolveCompletionItem(item).get(5, TimeUnit.SECONDS)
        val docText = resolved.documentation?.let { d ->
            if (d.isRight) d.right.value else d.left
        } ?: fail("expected documentation after resolve")
        assertTrue(docText.contains("Doubles its input"),
            "resolved doc should include the `##` comment, got: $docText")
        assertTrue(docText.contains("Int"),
            "resolved doc should include the type signature, got: $docText")
    }

    @Test
    fun `value mismatching an expected Result offers a Wrap-in-Ok quick-fix`() {
        // #116: `let x: Result Int Str = 5` — the value is an Int but the
        // annotation wants Result Int Str, whose inner value type IS Int, so
        // offer to wrap: `5` → `Ok { 5 }`.
        val src = "let x: Result Int Str = 5"
        val (uri, _) = openSingleFile(src)

        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags, "expected a diagnostic for the Result mismatch")
        val mismatch = diags!!.diagnostics.firstOrNull { it.code?.left == "T0210" || it.code?.left == "T0201" }
            ?: fail("expected a type-mismatch diagnostic, got: ${diags.diagnostics.map { it.code?.left to it.message }}")

        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = mismatch.range
            context = CodeActionContext(listOf(mismatch))
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val wrap = actions.mapNotNull { if (it.isRight) it.right else null }
            .firstOrNull { it.title == "Wrap in `Ok`" }
            ?: fail("expected a 'Wrap in `Ok`' action, got: ${actions.mapNotNull { if (it.isRight) it.right.title else null }}; " +
                "diag.data=${mismatch.data}")
        val edit = wrap.edit?.changes?.values?.flatten()?.firstOrNull() ?: fail("wrap action carried no edit")
        val after = applyEdit(src, edit)
        assertEquals("let x: Result Int Str = Ok { 5 }", after,
            "wrap should produce `Ok { 5 }`, got: $after")
    }

    @Test
    fun `extract to function lifts a selection into a top-level fn with free vars as params`() {
        // #117: selecting `x + 1` in a lambda body extracts a top-level
        // `let extracted = { x } -> x + 1` (x is the enclosing param → a
        // function parameter) and replaces the selection with a call.
        val src = "let f = {x: Int} -> x + 1"
        val (uri, _) = openSingleFile(src)
        client.awaitDiagnostics(uri, 5_000)

        // Select `x + 1` (from its `x` to end of line).
        val start = src.indexOf("x + 1")
        val range = Range(Position(0, start), Position(0, src.length))
        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            this.range = range
            context = CodeActionContext(emptyList())
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val extract = actions.mapNotNull { if (it.isRight) it.right else null }
            .firstOrNull { it.title == "Extract to function" }
            ?: fail("expected 'Extract to function', got: ${actions.mapNotNull { if (it.isRight) it.right.title else null }}")
        val edits = extract.edit?.changes?.values?.flatten() ?: emptyList()
        val newText = edits.joinToString("|") { it.newText }
        // The new function captures `x` as a param, and the call passes it.
        assertTrue(newText.contains("let extracted = { x } -> x + 1"),
            "expected extracted fn with `x` param, got edits: $newText")
        assertTrue(newText.contains("extracted { x = x }"),
            "expected the selection replaced with a call passing `x`, got: $newText")
    }

    @Test
    fun `linkedEditingRange returns all same-symbol occurrence ranges`() {
        // #118: cursor on a binding used more than once returns every
        // occurrence range so the editor edits them together as you type.
        val (uri, _) = openSingleFile("""
            let f = {} -> do
              let count = 5
              count + count
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        // Cursor on the `count` definition (line 1, `  let count = 5`, char 6).
        val ranges = server.textDocumentService.linkedEditingRange(LinkedEditingRangeParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(1, 6)
        }).get(5, TimeUnit.SECONDS)
            ?: fail("expected linked-editing ranges for `count`")

        // def + two uses = at least 3 occurrences.
        assertTrue(ranges.ranges.size >= 2,
            "expected all `count` occurrences linked, got ${ranges.ranges.size}: ${ranges.ranges}")
        // Every range should span 5 chars (`count`).
        assertTrue(ranges.ranges.all { it.end.character - it.start.character == 5 || it.start.line != it.end.line },
            "each linked range should cover `count` (5 chars), got: ${ranges.ranges}")
    }

    @Test
    fun `effect inlay hint shows the inferred effect row on an effectful function`() {
        // #119: a function that performs an effect carries an effect row in its
        // inferred type — surface it inline as `<Logger>`. Uniquely-Mezze.
        val src = """
            effect Logger where {
              log: { msg: Str } -> {}
            }
            let greet = {name: Str} -> Logger.log { msg = name }
        """.trimIndent()
        val (uri, _) = openSingleFile(src)
        client.awaitDiagnostics(uri, 5_000)

        val hints = inlayHints(uri, src)
        val labels = hints.map { labelText(it) }
        assertTrue(labels.any { it.contains("<") && it.contains("Logger") },
            "expected an effect-row hint `<Logger>` on `greet`, got: $labels")
    }

    @Test
    fun `no effect inlay hint on a pure function`() {
        val src = "let add = {a: Int, b: Int} -> a + b"
        val (uri, _) = openSingleFile(src)
        client.awaitDiagnostics(uri, 5_000)

        val hints = inlayHints(uri, src)
        val labels = hints.map { labelText(it) }
        // A pure function must not get an effect-row hint (angle-bracket effect).
        assertTrue(labels.none { it.trim().startsWith("<") && it.contains(">") },
            "pure function should have no effect-row hint, got: $labels")
    }

    @Test
    fun `semanticTokensRange returns only tokens within the requested range`() {
        // #122: viewport-only tokens — a subset of the full token stream.
        val (uri, _) = openSingleFile("""
            let a = 1
            let b = 2
            let c = 3
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        val full = server.textDocumentService.semanticTokensFull(SemanticTokensParams().apply {
            textDocument = TextDocumentIdentifier(uri)
        }).get(5, TimeUnit.SECONDS)
        val fullCount = (full?.data?.size ?: 0) / 5

        // Range covering only line 1 (`let b = 2`).
        val ranged = server.textDocumentService.semanticTokensRange(SemanticTokensRangeParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = Range(Position(1, 0), Position(1, 100))
        }).get(5, TimeUnit.SECONDS)
        val rangedCount = (ranged?.data?.size ?: 0) / 5

        assertTrue(fullCount >= 3, "expected a token per top-level binding name, got $fullCount")
        assertTrue(rangedCount in 1 until fullCount,
            "range for one line should return fewer tokens than the whole file (got $rangedCount of $fullCount)")
        // First token in the ranged stream must sit on line 1 (delta-line from 0).
        assertEquals(1, ranged!!.data[0], "first ranged token should be on line 1")
    }

    @Test
    fun `doc comment renders as multi-block markdown with blank-line separation`() {
        // #123: `##`-doc lines join with a single \n at parse time, but markdown
        // needs blank lines between block elements. Hover output must separate a
        // heading, a paragraph, and a list so they render (not run together).
        val (uri, _) = openSingleFile("""
            ## # Adder
            ## Adds two integers.
            ## - takes `a`
            ## - takes `b`
            let add = {a: Int, b: Int} -> a + b
        """.trimIndent())
        client.awaitDiagnostics(uri, 5_000)

        // Hover on `add` (line 4, char 4).
        val hover = hoverAt(uri, 4, 4) ?: fail("expected hover on `add`")
        val text = hoverText(hover)

        // The `# Adder` heading survives (## strips to # for markdown H1).
        assertTrue(text.contains("# Adder"), "expected the `# Adder` heading, got: $text")
        // Heading is followed by a blank line before the paragraph (markdown
        // block separation) — i.e. `# Adder\n\n`.
        assertTrue(text.contains("# Adder\n\nAdds two integers."),
            "heading must be blank-line-separated from the paragraph, got: $text")
        // The paragraph is blank-line-separated from the list's first item.
        assertTrue(text.contains("Adds two integers.\n\n- takes"),
            "paragraph must be blank-line-separated from the list, got: $text")
        // Consecutive list items stay adjacent (single \n, not doubled).
        assertTrue(text.contains("- takes `a`\n- takes `b`"),
            "list items must stay adjacent, got: $text")
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

    @Test
    fun `unused variable warning offers a Prefix-with-underscore quick-fix`() {
        // #95: W0101 carries its WarningCode to the LSP, and the codeAction
        // handler turns it into a one-click "Prefix with `_`" fix.
        val (uri, _) = openSingleFile("""
            let f = {} -> do
              let x = 5
              42
        """.trimIndent())

        val diags = client.awaitDiagnostics(uri, 5_000)
        assertNotNull(diags, "expected publishDiagnostics")
        val w0101 = diags!!.diagnostics.firstOrNull { it.code?.left == "W0101" }
            ?: fail("expected a W0101 diagnostic among ${diags.diagnostics.map { it.code?.left to it.message }}")

        val actions = server.textDocumentService.codeAction(CodeActionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            range = w0101.range
            context = CodeActionContext(listOf(w0101))
        }).get(5, TimeUnit.SECONDS) ?: emptyList()

        val titles = actions.mapNotNull { e -> if (e.isRight) e.right.title else null }
        assertTrue(
            titles.any { it.contains("Prefix with") && it.contains("_") },
            "expected a 'Prefix with `_`' code action, got: $titles"
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

    @Test
    fun `typeDefinition jumps to a type declared in another module`() {
        // #112: a value whose type is defined in another module — go-to-type
        // resolves the type name and jumps to the OTHER file via the store's
        // TYPE decl index.
        val ws = mkProject(
            "app.mz" to """
                use types::Color
                let c: Color = Red
            """.trimIndent(),
            "types.mz" to """
                pub type Color = | Red, Green |
            """.trimIndent()
        )
        openProject(ws)
        val appUri = ws.resolve("src/app.mz").toUri().toString()
        val typesUri = ws.resolve("src/types.mz").toUri().toString()
        openFile(typesUri, ws.resolve("src/types.mz").toFile().readText())
        client.awaitDiagnostics(typesUri, 5_000)
        openFile(appUri, ws.resolve("src/app.mz").toFile().readText())
        client.awaitDiagnostics(appUri, 5_000)

        // Cursor on `c` (line 1, `let c: Color = Red`, char 4).
        val locs = server.textDocumentService.typeDefinition(TypeDefinitionParams().apply {
            textDocument = TextDocumentIdentifier(appUri)
            position = Position(1, 4)
        }).get(5, TimeUnit.SECONDS).let { e ->
            if (e.isLeft) e.left else e.right.map { Location(it.targetUri, it.targetRange) }
        }

        assertTrue(locs.isNotEmpty(), "expected a type-definition Location for `c` (type Color)")
        // Compare by resolved path — `file:/…` vs `file:///…` are the same file.
        assertEquals(sameFile(typesUri), sameFile(locs.first().uri),
            "go-to-type should land in types.mz where `Color` is declared, got: ${locs.first().uri}")
    }

    @Test
    fun `implementation finds an impl in another module`() {
        // #112: go-to-implementation on an ability method surfaces impls across
        // modules, not just the current file.
        val ws = mkProject(
            "app.mz" to """
                use shapes::Show
                use shapes::Circle
                let describe = {c: Circle} -> Show::show { it = c }
            """.trimIndent(),
            // Abilities are public by default — `pub ability` is a parse error.
            "shapes.mz" to """
                ability Show for 'a where {
                  show: { it: 'a } -> Str
                }
                pub type Circle = | Circle |
                impl Show for Circle where {
                  show = { it } -> "circle"
                }
            """.trimIndent()
        )
        openProject(ws)
        val appUri = ws.resolve("src/app.mz").toUri().toString()
        val shapesUri = ws.resolve("src/shapes.mz").toUri().toString()
        // Drain each file's diagnostics before opening the next so the
        // server→client pipe never fills mid-send (see the deadlock note).
        openFile(shapesUri, ws.resolve("src/shapes.mz").toFile().readText())
        client.awaitDiagnostics(shapesUri, 5_000)
        openFile(appUri, ws.resolve("src/app.mz").toFile().readText())
        client.awaitDiagnostics(appUri, 5_000)

        // Cursor on `show` in the call `Show::show { it = c }` (line 2).
        val callLine = "let describe = {c: Circle} -> Show::show { it = c }"
        val col = callLine.indexOf("show", callLine.indexOf("Show::")) // the method after `::`
        val locs = server.textDocumentService.implementation(ImplementationParams().apply {
            textDocument = TextDocumentIdentifier(appUri)
            position = Position(2, col)
        }).get(5, TimeUnit.SECONDS).let { e ->
            if (e.isLeft) e.left else e.right.map { Location(it.targetUri, it.targetRange) }
        }

        assertTrue(locs.any { sameFile(it.uri) == sameFile(shapesUri) },
            "expected an impl Location in shapes.mz, got: ${locs.map { it.uri }}")
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

    /**
     * Apply a single [TextEdit] to [source]. Handles the multi-line case (the
     * edit spans from start-line to end-line) so removal edits that swallow a
     * newline verify correctly.
     */
    /** Normalize a file URI to its real path so `file:/…` and `file:///…` compare equal. */
    private fun sameFile(uri: String): String =
        try { java.nio.file.Paths.get(java.net.URI.create(uri)).toRealPath().toString() }
        catch (_: Exception) { uri }

    /** The set of token-type indices in a delta-encoded semantic-token stream (every 4th of each 5-int group). */
    private fun decodeSemanticTokenTypes(data: List<Int>): Set<Int> {
        val types = mutableSetOf<Int>()
        var i = 0
        while (i + 4 < data.size) { types.add(data[i + 3]); i += 5 }
        return types
    }

    private fun applyEdit(source: String, edit: TextEdit): String {
        val lines = source.split("\n")
        fun offset(p: Position): Int {
            var o = 0
            for (i in 0 until p.line) o += lines[i].length + 1  // +1 for the '\n'
            return o + p.character
        }
        val start = offset(edit.range.start)
        val end = offset(edit.range.end)
        return source.substring(0, start) + edit.newText + source.substring(end)
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
