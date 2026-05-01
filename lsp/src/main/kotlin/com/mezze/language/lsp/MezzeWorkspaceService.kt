package com.mezze.language.lsp

import com.mezze.language.codebase.DeclKind
import com.mezze.language.parsedast.STDLIB_NAMESPACE_PREFIX
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.WorkspaceService
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * Handles workspace-level operations for the Mezze Language Server.
 *
 * This service manages:
 * - Workspace symbol search
 * - Configuration changes
 * - Watched file changes
 */
class MezzeWorkspaceService(
    private val server: MezzeLanguageServer
) : WorkspaceService {

    private lateinit var client: LanguageClient

    /**
     * Connect to the language client.
     */
    fun connect(client: LanguageClient) {
        this.client = client
    }

    /**
     * Search for symbols across the workspace.
     *
     * Backed by the v7 `decls` index. Single SQL query over
     * `searchDeclsByName(prefix)` returns up to 200 matching rows
     * across cache + stdlib + deps; we filter stdlib client-side so
     * project symbols don't get drowned out, then format each row as a
     * [SymbolInformation].
     *
     * Replaces the old "walk every module, deserialize TypedModule
     * blobs, scan decls" path — that was O(modules × decls)
     * deserialisation per query. Now it's one indexed lookup.
     */
    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        return CompletableFuture.supplyAsync {
            val tds = server.textDocumentService as? MezzeTextDocumentService
                ?: return@supplyAsync Either.forLeft<List<SymbolInformation>, List<WorkspaceSymbol>>(emptyList())
            val store = tds.getProjectStore() ?: return@supplyAsync Either.forLeft(emptyList())

            // Resolve module → file_path via the modules table. One read,
            // cached for this query. Stdlib modules' filePaths point at
            // build-time temp dirs; we skip them anyway.
            val moduleFilePaths = store.getAllModules()
                .filterNot { it.path.startsWith(STDLIB_NAMESPACE_PREFIX) }
                .associate { it.path to it.filePath }

            val query = params.query.trim()
            val results = mutableListOf<SymbolInformation>()
            val limit = 500

            // searchDeclsByName does a `decl_name LIKE ? || '%'` lookup.
            // Empty query string matches all (' || '%' = '%').
            for (row in store.searchDeclsByName(query)) {
                if (results.size >= limit) break
                if (row.modulePath.startsWith(STDLIB_NAMESPACE_PREFIX)) continue
                val filePath = moduleFilePaths[row.modulePath] ?: continue
                val uri = File(filePath).toURI().toString()
                val location = Location(uri, Range(
                    Position(row.startLine - 1, row.startCol - 1),
                    Position(row.endLine - 1, row.endCol)
                ))
                @Suppress("DEPRECATION")
                results.add(SymbolInformation(
                    row.declName,
                    declKindToSymbolKind(row.declKind),
                    location,
                    row.modulePath
                ))
            }

            Either.forLeft<List<SymbolInformation>, List<WorkspaceSymbol>>(results)
        }
    }

    private fun declKindToSymbolKind(kind: DeclKind): SymbolKind = when (kind) {
        DeclKind.LET -> SymbolKind.Function
        DeclKind.TYPE -> SymbolKind.Class
        DeclKind.ABILITY -> SymbolKind.Interface
        DeclKind.EFFECT -> SymbolKind.Event
        DeclKind.PERFORMER -> SymbolKind.Operator
        DeclKind.IMPL -> SymbolKind.Method
    }

    /**
     * Handle configuration changes.
     */
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        server.logInfo("Configuration changed")
        // TODO: Handle configuration updates
    }

    /**
     * Handle watched file changes.
     */
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        for (change in params.changes) {
            val uri = change.uri
            val type = change.type

            when (type) {
                FileChangeType.Created -> {
                    server.logInfo("File created: $uri")
                }
                FileChangeType.Changed -> {
                    server.logInfo("File changed: $uri")
                    // If it's Mezze.toml, we might need to reload project structure
                    if (uri.endsWith("Mezze.toml")) {
                        server.logInfo("Project configuration changed, consider reloading")
                    }
                }
                FileChangeType.Deleted -> {
                    server.logInfo("File deleted: $uri")
                }
                else -> {}
            }
        }
    }

    /**
     * Execute a workspace command.
     *
     * Both supported commands route into [MezzeTextDocumentService] —
     * that's where the project store, open documents, and diagnostics
     * publication live. We just dispatch.
     */
    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return CompletableFuture.supplyAsync {
            val tds = server.textDocumentService as? MezzeTextDocumentService
            when (params.command) {
                "mezze.clearCache" -> {
                    server.logInfo("Clearing cache...")
                    tds?.clearCache()
                }
                "mezze.restart" -> {
                    server.logInfo("Restarting server...")
                    tds?.restart()
                }
                else -> {
                    server.logWarning("Unknown command: ${params.command}")
                }
            }
            null
        }
    }
}
