package com.mezze.language.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * End-to-end smoke harness for the Mezze Language Server.
 *
 * Drives the real LSP through real JSON-RPC over piped streams — same
 * protocol the editor uses. No shortcuts, but in-process for speed.
 *
 * What this catches:
 * - Server crashes during initialize / didOpen
 * - Project store init regressions (the bug fixed in 8d377ec4 where
 *   connect() ran before initialize() and projectStore stayed null)
 * - $/setTrace handling (lsp4j default impl throws — our override
 *   should swallow it silently)
 * - Workspace symbol queries that depend on a populated decls index
 * - Diagnostics publication through the publishDiagnostics callback
 *
 * What this does NOT catch:
 * - Anything that requires a real editor's stateful interaction
 *   (e.g. typing speed, cursor position UX). Add specific tests for
 *   each feature instead of trying to simulate Cursor.
 */
class LspSmokeTest {

    private lateinit var serverProcess: ServerProcess
    private lateinit var client: TestClient
    private lateinit var server: LanguageServer

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        // Two pipes: client → server input, server → client output.
        val clientToServer = PipedOutputStream()
        val serverInput = PipedInputStream(clientToServer)
        val serverToClient = PipedOutputStream()
        val clientInput = PipedInputStream(serverToClient)

        // Start the LSP server on its own thread driven by these streams.
        serverProcess = ServerProcess(serverInput, serverToClient)
        serverProcess.start()

        // Hook up the client side — captures notifications coming back.
        client = TestClient()
        val launcher: Launcher<LanguageServer> = LSPLauncher.createClientLauncher(
            client, clientInput, clientToServer
        )
        server = launcher.remoteProxy
        launcher.startListening()
    }

    @AfterEach
    fun tearDown() {
        try {
            server.shutdown().get(2, TimeUnit.SECONDS)
            server.exit()
        } catch (_: Throwable) { /* best-effort */ }
        serverProcess.stop()
    }

    @Test
    fun `initialize completes and reports the workspace root`() {
        val workspace = makeMezzeProject()

        val result = server.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(workspace.toUri().toString(), "test"))
        }).get(10, TimeUnit.SECONDS)

        assertNotNull(result, "initialize must return a result")
        assertNotNull(result.serverInfo, "serverInfo must be present")
        assertEquals("Mezze Language Server", result.serverInfo.name)
        // Capabilities we care about for downstream tests
        assertTrue(result.capabilities.workspaceSymbolProvider.left, "workspaceSymbolProvider must be enabled")
        assertTrue(result.capabilities.documentSymbolProvider.left, "documentSymbolProvider must be enabled")

        // Initial info logs include the actual workspace path (regression
        // for the bug where it logged null first).
        // Drain a moment for the initial logInfo() messages.
        Thread.sleep(200)
        val workspaceLogged = client.logMessages.any { it.message.contains(workspace.toString()) }
        assertTrue(workspaceLogged, "expected at least one log line containing $workspace, got: ${client.logMessages.map { it.message }}")

        // No log line should report the workspace as null after initialize.
        // (The pre-fix bug had `Workspace root for codebase: null` here.)
        val nullWorkspaceLog = client.logMessages.firstOrNull {
            it.message.contains("Workspace root for codebase: null")
        }
        assertNull(nullWorkspaceLog, "must not log null workspace root after initialize")
    }

    @Test
    fun `setTrace notification does not crash the server`() {
        server.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(makeMezzeProject().toUri().toString(), "test"))
        }).get(10, TimeUnit.SECONDS)

        // Send the notification that lsp4j's default impl rejects.
        // Our override should swallow this silently.
        server.setTrace(SetTraceParams(TraceValue.Off))
        Thread.sleep(100)

        // If the server crashed, the next request would fail or hang.
        // Use a benign request that doesn't depend on any state.
        val symbols = server.workspaceService
            .symbol(WorkspaceSymbolParams(""))
            .get(5, TimeUnit.SECONDS)
        assertNotNull(symbols, "server must still respond after setTrace")
    }

    @Test
    fun `didOpen on a malformed file publishes a parse diagnostic`() {
        val workspace = makeMezzeProject()
        val mzFile = workspace.resolve("src/app.mz").toFile()

        server.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(workspace.toUri().toString(), "test"))
        }).get(10, TimeUnit.SECONDS)
        server.initialized(InitializedParams())

        // Replace with deliberately broken syntax.
        mzFile.writeText("let main = {} -> ::: broken syntax here")

        server.textDocumentService.didOpen(DidOpenTextDocumentParams(
            TextDocumentItem(mzFile.toURI().toString(), "mezze", 1, mzFile.readText())
        ))

        // Wait for the publishDiagnostics notification (debounced — give it time).
        val diags = client.awaitDiagnostics(mzFile.toURI().toString(), timeoutMs = 5000)
        assertNotNull(diags, "expected diagnostics for malformed file within timeout")
        assertTrue(diags!!.diagnostics.isNotEmpty(),
            "expected at least one diagnostic for broken file, got empty")
    }

    @Test
    fun `workspace symbol query returns results after didOpen`() {
        val workspace = makeMezzeProject()
        val mzFile = workspace.resolve("src/app.mz").toFile()
        // Note: not using a kotlin triple-quoted string-template here because
        // Mezze's `${...}` interpolation collides with kotlin's. Build the
        // file content from a plain String so kotlin doesn't try to resolve
        // `name` in its own scope.
        val mzSource = "use std::effects::log::{println, Console}\n\n" +
            "let greet = { name = \"world\" } -> \"Hello \" \n\n" +
            "let main = {} -> perform Console in do\n" +
            "    println { msg = greet { name = \"world\" } }\n"
        mzFile.writeText(mzSource)

        server.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(workspace.toUri().toString(), "test"))
        }).get(10, TimeUnit.SECONDS)
        server.initialized(InitializedParams())

        server.textDocumentService.didOpen(DidOpenTextDocumentParams(
            TextDocumentItem(mzFile.toURI().toString(), "mezze", 1, mzFile.readText())
        ))

        // Diagnostics arrive via debounced async path; analyze fully populates decls.
        client.awaitDiagnostics(mzFile.toURI().toString(), timeoutMs = 5000)

        // Workspace symbol query for "greet" — only present if projectStore
        // is open and decls index was populated. The pre-fix bug left
        // projectStore null, so this would return [].
        val sym = server.workspaceService
            .symbol(WorkspaceSymbolParams("greet"))
            .get(5, TimeUnit.SECONDS)
        // Server returns Either<List<SymbolInformation>, List<WorkspaceSymbol>>.
        // We only need names for the assertion — pull them out of whichever branch.
        // (lsp4j WorkspaceSymbol uses a Java field named `name`; calling
        // getName() explicitly avoids Kotlin's accessor-synth ambiguity.)
        val names: List<String> = if (sym.isLeft) {
            sym.left.map { it.name }
        } else {
            sym.right.map { it.getName() }
        }
        assertTrue(names.isNotEmpty(),
            "workspace symbol search for 'greet' returned empty — is projectStore open?")
        assertTrue(names.any { it == "greet" },
            "expected a symbol named 'greet', got: $names")
    }

    // ===== Helpers =====================================================

    /**
     * Creates a minimal Mezze.toml + src/ skeleton in tempDir and returns
     * the workspace path. Tests that need an open projectStore must use
     * this — without a Mezze.toml, the LSP runs in in-memory mode.
     */
    private fun makeMezzeProject(): Path {
        val workspace = tempDir.resolve("ws").also { it.toFile().mkdirs() }
        workspace.resolve("Mezze.toml").toFile().writeText("""
            [package]
            name = "lsp_smoke_test"
            version = "0.0.1"

            [[app]]
            name = "lsp_smoke_test"
            path = "src/app.mz"
            entry = "main"

            [dependencies]

            [dev-dependencies]
        """.trimIndent())
        val srcDir = workspace.resolve("src").also { it.toFile().mkdirs() }
        srcDir.resolve("app.mz").toFile().writeText("""
            use std::effects::log::{println, Console}

            let main = {} -> perform Console in do
                println { msg = "hello" }
        """.trimIndent())
        return workspace
    }

    /**
     * Wraps the LanguageServer instance + its driver thread.
     * Lifetime tied to a single test method.
     */
    private class ServerProcess(
        private val input: PipedInputStream,
        private val output: PipedOutputStream
    ) {
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "lsp-smoke-server").apply { isDaemon = true }
        }
        private var future: CompletableFuture<Void>? = null

        fun start() {
            future = CompletableFuture.runAsync({
                try {
                    startServer(input, output, "smoke-test")
                } catch (_: Throwable) { /* server shutdown */ }
            }, executor)
        }

        fun stop() {
            try { input.close() } catch (_: Throwable) { }
            try { output.close() } catch (_: Throwable) { }
            executor.shutdownNow()
        }
    }

    /**
     * Captures notifications from the server. Tests query it for what
     * they expect to have happened.
     */
    private class TestClient : LanguageClient {
        val logMessages = ConcurrentLinkedQueue<MessageParams>()
        val publishedDiagnostics = ConcurrentLinkedQueue<PublishDiagnosticsParams>()

        override fun telemetryEvent(`object`: Any?) { /* unused */ }

        override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
            publishedDiagnostics.add(diagnostics)
        }

        override fun showMessage(messageParams: MessageParams) { /* unused */ }
        override fun showMessageRequest(requestParams: ShowMessageRequestParams):
            CompletableFuture<MessageActionItem?> = CompletableFuture.completedFuture(null)

        override fun logMessage(message: MessageParams) {
            logMessages.add(message)
        }

        /** Wait until a diagnostics publication for [uri] arrives, or null on timeout. */
        fun awaitDiagnostics(uri: String, timeoutMs: Long): PublishDiagnosticsParams? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val match = publishedDiagnostics.firstOrNull { it.uri == uri }
                if (match != null) return match
                Thread.sleep(50)
            }
            return null
        }
    }
}
