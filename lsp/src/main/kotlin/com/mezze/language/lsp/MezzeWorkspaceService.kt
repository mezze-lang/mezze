package com.mezze.language.lsp

import com.mezze.language.codebase.CodebaseSerialization
import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.STDLIB_NAMESPACE_PREFIX
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedTypeDecl
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
     * Source of truth: the project's `cache.sqlite`. We walk every module
     * in the `modules` table, load its serialized [TypedModule] from
     * `typed_modules`, and emit one [SymbolInformation] per top-level decl
     * (let bindings, types, abilities, effects, performers).
     *
     * Stdlib modules (`std::*`) are skipped — they're not user-editable and
     * including them would drown out project symbols.
     *
     * Filtering: case-insensitive substring match on the symbol name.
     * Empty query → return everything. Hard-cap to 500 results so a wide
     * project doesn't blow up the editor.
     */
    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        return CompletableFuture.supplyAsync {
            val tds = server.textDocumentService as? MezzeTextDocumentService
                ?: return@supplyAsync Either.forLeft<List<SymbolInformation>, List<WorkspaceSymbol>>(emptyList())
            val store = tds.getProjectStore() ?: return@supplyAsync Either.forLeft(emptyList())

            val query = params.query.trim()
            val needle = query.lowercase()
            val results = mutableListOf<SymbolInformation>()
            val limit = 500

            for (mod in store.getAllModules()) {
                if (results.size >= limit) break
                if (mod.path.startsWith(STDLIB_NAMESPACE_PREFIX)) continue
                val filePath = mod.filePath ?: continue
                val blob = store.getTypedModule(mod.path, mod.sourceHash) ?: continue
                val typed = try {
                    CodebaseSerialization.deserializeTypedModule(blob)
                } catch (_: Exception) {
                    continue
                }
                collectSymbols(typed, mod.path, filePath, needle, results, limit)
            }

            Either.forLeft<List<SymbolInformation>, List<WorkspaceSymbol>>(results)
        }
    }

    private fun collectSymbols(
        typed: TypedModule,
        modulePath: String,
        filePath: String,
        needle: String,
        out: MutableList<SymbolInformation>,
        limit: Int
    ) {
        // The module's filePath comes from the modules table (always
        // absolute). Loc.file inside TypedDecls can be relative on the
        // entry-file path, so we trust the modules row for the URI and
        // only use Loc for line/col.
        val uri = File(filePath).toURI().toString()

        @Suppress("DEPRECATION") // SymbolInformation is the form most editors render
        fun emit(name: String, kind: SymbolKind, loc: Loc) {
            if (out.size >= limit) return
            if (needle.isNotEmpty() && !name.lowercase().contains(needle)) return
            val location = Location(uri, Range(
                Position(loc.startLine - 1, loc.startCol - 1),
                Position(loc.endLine - 1, loc.endCol)
            ))
            out.add(SymbolInformation(name, kind, location, modulePath))
        }

        for (decl in typed.decls) {
            when (decl) {
                is TypedLetDecl -> emit(decl.name, SymbolKind.Function, decl.loc)
                is TypedTypeDecl -> emit(decl.name, SymbolKind.Class, decl.loc)
            }
        }
        for (a in typed.abilityDecls) emit(a.name, SymbolKind.Interface, a.loc)
        for (e in typed.effectDecls) emit(e.name, SymbolKind.Event, e.loc)
        for (p in typed.performerDecls) emit(p.name, SymbolKind.Operator, p.loc)
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
     */
    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        return CompletableFuture.supplyAsync {
            when (params.command) {
                "mezze.clearCache" -> {
                    server.logInfo("Clearing cache...")
                    // TODO: Clear codebase and re-analyze
                }
                "mezze.restart" -> {
                    server.logInfo("Restarting server...")
                    // TODO: Implement restart
                }
                else -> {
                    server.logWarning("Unknown command: ${params.command}")
                }
            }
            null
        }
    }
}
