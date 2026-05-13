package com.mezze.language.lsp

import org.eclipse.lsp4j.launch.LSPLauncher
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Entry point for the Mezze Language Server.
 *
 * The server communicates via stdin/stdout using the Language Server Protocol.
 *
 * Usage:
 *   mez lsp              # Start LSP server (stdin/stdout)
 *   mez lsp --stdio      # Explicit stdio mode
 *   mez lsp --version    # Print version and exit
 */
fun main(args: Array<String>) {
    when {
        args.contains("--version") || args.contains("-v") -> {
            println("Mezze Language Server v1.0.0")
            return
        }
        args.contains("--help") || args.contains("-h") -> {
            printHelp()
            return
        }
    }

    // Start the language server
    startServer(System.`in`, System.out)
}

private fun printHelp() {
    println("""
        Mezze Language Server

        USAGE:
          mez lsp [options]

        OPTIONS:
          --stdio      Use stdio for communication (default)
          --version    Print version and exit
          --help       Show this help message

        The language server provides:
          - Diagnostics (type errors, parse errors)
          - Hover information (types, documentation)
          - Go to definition
          - Find references
          - Code completion
          - Document symbols

        EXAMPLES:
          mez lsp              # Start server (for editor integration)
    """.trimIndent())
}

/**
 * Start the language server with the given input/output streams.
 *
 * [version] is reported back to the editor via `initialize`'s ServerInfo.
 * Callers from the CLI pass `BuildInfo.VERSION` so it matches
 * `mez --version`; tests / direct main() default to "dev".
 */
fun startServer(input: InputStream, output: OutputStream, version: String = "dev") {
    // Register the SQLite-backed store opener so PrecompiledStdlib can
    // hydrate from `stdlib.sqlite` without reflection. See CLI Main for
    // the full rationale (#168). Safe to call repeatedly when the LSP is
    // launched via `mez lsp` (CLI Main already registered).
    com.mezze.language.store.MezzeStoreOpener.register { file ->
        com.mezze.language.codebase.SQLiteMezzeStore.readOnly(file)
    }

    // Echo version + git sha to stderr at boot. Useful when an editor
    // (Cursor / VS Code / Neovim) doesn't surface the LSP-channel
    // logInfo() messages prominently — stderr typically lands in the
    // editor's "Output" panel under the LSP server name.
    System.err.println("[mezze-lsp] booting $version")
    val server = MezzeLanguageServer(version)

    val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "mezze-lsp-worker").apply {
            isDaemon = true
        }
    }

    val launcher = LSPLauncher.createServerLauncher(
        server,
        input,
        output,
        executor,
        null  // No message consumer wrapper
    )

    val client = launcher.remoteProxy
    server.connect(client)

    // Start listening
    launcher.startListening().get()
}
