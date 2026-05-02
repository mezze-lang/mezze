package com.mezze.language.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import java.util.concurrent.CompletableFuture

/**
 * The main Mezze Language Server implementation.
 *
 * This server provides IDE features for the Mezze language:
 * - Diagnostics (parse errors, type errors)
 * - Hover (show types on hover)
 * - Go to definition
 * - Find references
 * - Code completion
 * - Document symbols
 */
class MezzeLanguageServer(
    /**
     * Version reported in [ServerInfo] during `initialize`. The CLI
     * passes its `BuildInfo.VERSION` so editor-visible version matches
     * `mez --version`. Falls back to "dev" when launched directly
     * without going through the CLI.
     */
    private val serverVersion: String = "dev"
) : LanguageServer, LanguageClientAware {

    private lateinit var client: LanguageClient
    private val textDocumentService = MezzeTextDocumentService(this)
    private val workspaceService = MezzeWorkspaceService(this)
    private var workspaceRoot: String? = null

    /**
     * Connect to the language client.
     */
    override fun connect(client: LanguageClient) {
        this.client = client
        textDocumentService.connect(client)
        workspaceService.connect(client)
    }

    /**
     * Get the connected client.
     */
    fun getClient(): LanguageClient = client

    /**
     * Get the workspace root path.
     */
    fun getWorkspaceRoot(): String? = workspaceRoot

    /**
     * Initialize the server with client capabilities.
     */
    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        // Store workspace root (prefer workspaceFolders, fallback to deprecated rootUri/rootPath)
        @Suppress("DEPRECATION")
        workspaceRoot = params.workspaceFolders?.firstOrNull()?.uri?.removePrefix("file://")
            ?: params.rootUri?.removePrefix("file://")
            ?: params.rootPath

        // Log initialization. Version goes first so it's easy to spot
        // when verifying which build the editor's running.
        logInfo("Mezze Language Server $serverVersion initializing...")
        logInfo("Workspace root: $workspaceRoot")

        // Define server capabilities
        val capabilities = ServerCapabilities().apply {
            // Text document sync - full sync for now, incremental later
            textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)

            // Hover support
            hoverProvider = Either.forLeft(true)

            // Completion support
            completionProvider = CompletionOptions().apply {
                triggerCharacters = listOf(".", ":", "{", "<")
                resolveProvider = true
            }

            // Go to definition
            definitionProvider = Either.forLeft(true)

            // Find references
            referencesProvider = Either.forLeft(true)

            // Document highlight (cursor-on-symbol same-file occurrence highlight)
            documentHighlightProvider = Either.forLeft(true)

            // Call hierarchy (incoming + outgoing call exploration via the v7 edges table)
            callHierarchyProvider = Either.forLeft(true)

            // Selection range (smart "expand selection" — nested ranges from innermost out)
            selectionRangeProvider = Either.forLeft(true)

            // Type hierarchy (ability ↔ impl exploration)
            typeHierarchyProvider = Either.forLeft(true)

            // Document symbols (outline)
            documentSymbolProvider = Either.forLeft(true)

            // Workspace symbols
            workspaceSymbolProvider = Either.forLeft(true)

            // Code actions (quick fixes)
            codeActionProvider = Either.forLeft(true)

            // Signature help
            signatureHelpProvider = SignatureHelpOptions().apply {
                triggerCharacters = listOf("{", ",")
            }

            // Document formatting
            documentFormattingProvider = Either.forLeft(true)

            // Folding ranges
            foldingRangeProvider = Either.forLeft(true)

            // Inlay hints (types on pipes, chains, let bindings)
            inlayHintProvider = Either.forLeft(true)

            // Rename support
            renameProvider = Either.forLeft(true)

            // Semantic tokens (syntax highlighting)
            // Token types and modifiers are defined by the LSP spec:
            // https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#semanticTokenTypes
            semanticTokensProvider = SemanticTokensWithRegistrationOptions().apply {
                legend = SemanticTokensLegend(
                    listOf(
                        SemanticTokenTypes.Keyword,
                        SemanticTokenTypes.Function,
                        SemanticTokenTypes.Variable,
                        SemanticTokenTypes.Type,
                        SemanticTokenTypes.String,
                        SemanticTokenTypes.Number,
                        SemanticTokenTypes.Comment,
                        SemanticTokenTypes.Operator,
                        SemanticTokenTypes.Property,
                        SemanticTokenTypes.Parameter,
                        SemanticTokenTypes.EnumMember   // for variant constructors
                    ),
                    listOf(
                        SemanticTokenModifiers.Declaration,
                        SemanticTokenModifiers.Definition,
                        SemanticTokenModifiers.Readonly
                    )
                )
                full = Either.forLeft(true)
            }

            // Workspace commands. Clients invoke via workspace/executeCommand.
            // Both wipe state and re-analyze; restart additionally flushes the
            // typechecker's process-global module cache and reloads stdlib.
            executeCommandProvider = ExecuteCommandOptions(
                listOf("mezze.clearCache", "mezze.restart")
            )
        }

        val serverInfo = ServerInfo("Mezze Language Server", serverVersion)

        // Open the project store now that workspaceRoot is known. The lsp4j
        // launcher calls connect() before initialize(), so the text doc
        // service couldn't open the store during connect — do it here.
        textDocumentService.initializeProjectStore()

        return CompletableFuture.completedFuture(
            InitializeResult(capabilities, serverInfo)
        )
    }

    /**
     * `$/setTrace` notification from the client.
     *
     * Default lsp4j 0.21.x impl throws UnsupportedOperationException — every
     * trace toggle would dump a stack trace into the client's log. We don't
     * differentiate trace levels (all logging is logInfo/logError), so make
     * this a quiet no-op.
     */
    override fun setTrace(params: SetTraceParams) {
        // intentionally empty
    }

    /**
     * Called after the client has received the initialize result.
     */
    override fun initialized(params: InitializedParams) {
        logInfo("Mezze Language Server initialized successfully")

        // Register for file watching
        val watchers = listOf(
            FileSystemWatcher(Either.forLeft("**/*.mz")),
            FileSystemWatcher(Either.forLeft("**/Mezze.toml"))
        )

        val registrationOptions = DidChangeWatchedFilesRegistrationOptions(watchers)
        val registration = Registration(
            "mezze-file-watcher",
            "workspace/didChangeWatchedFiles",
            registrationOptions
        )

        client.registerCapability(RegistrationParams(listOf(registration)))
    }

    /**
     * Shutdown the server.
     */
    override fun shutdown(): CompletableFuture<Any> {
        logInfo("Mezze Language Server shutting down...")
        textDocumentService.shutdown()
        return CompletableFuture.completedFuture(null)
    }

    /**
     * Exit the server process.
     */
    override fun exit() {
        logInfo("Mezze Language Server exiting")
        System.exit(0)
    }

    /**
     * Get the text document service.
     */
    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    /**
     * Get the workspace service.
     */
    override fun getWorkspaceService(): WorkspaceService = workspaceService

    /**
     * Log an info message to the client.
     */
    fun logInfo(message: String) {
        if (::client.isInitialized) {
            client.logMessage(MessageParams(MessageType.Info, message))
        }
    }

    /**
     * Log a warning message to the client.
     */
    fun logWarning(message: String) {
        if (::client.isInitialized) {
            client.logMessage(MessageParams(MessageType.Warning, message))
        }
    }

    /**
     * Log an error message to the client.
     */
    fun logError(message: String) {
        if (::client.isInitialized) {
            client.logMessage(MessageParams(MessageType.Error, message))
        }
    }
}
