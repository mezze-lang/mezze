package com.mezze.language.lsp

import com.mezze.language.ast.*
import com.mezze.language.ast.prettyPrintTypeWithAliases
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typechecker.ResolvedImport
import com.mezze.language.typechecker.ImportKind
import com.mezze.language.parsedast.*
import com.mezze.language.codebase.MezzeStore
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.codebase.StoreHydration
import com.mezze.language.codebase.CodebaseSerialization
import com.mezze.language.formatter.Formatter
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.TypeCheckError
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Handles text document operations for the Mezze Language Server.
 *
 * This service manages:
 * - Document synchronization (open, change, close)
 * - Diagnostics (parse errors, type errors)
 * - Hover information
 * - Go to definition
 * - Find references
 * - Code completion
 * - Document symbols
 */
class MezzeTextDocumentService(
    private val server: MezzeLanguageServer
) : TextDocumentService {

    private lateinit var client: LanguageClient
    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    // Document state
    private val documents = ConcurrentHashMap<String, DocumentState>()

    // Last successful type check result per document (survives parse errors for dot-completion)
    private val lastSuccessfulResult = ConcurrentHashMap<String, TypeCheckResult>()

    // didChange debouncing. Each keystroke cancels the previous pending
    // analysis and schedules a fresh one DEBOUNCE_MS in the future. The
    // single-thread scheduler keeps order — a slow analysis cannot get
    // overtaken by a faster later one.
    private val analysisScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mezze-lsp-analysis").apply { isDaemon = true }
    }
    private val pendingAnalysis = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val DEBOUNCE_MS: Long = 150

    // Store for project mode (will be rebuilt in LSP task #106)
    private var projectStore: MezzeStore? = null

    // Project root directory and Mezze.toml package name. Cached at init so
    // every LSP write to cache.sqlite uses the same modulePath derivation as
    // `mez dev` (so concurrent tools share rows in typed_modules).
    private var projectRootDir: File? = null
    private var packageName: String? = null
    /** Absolute path of the first [[app]] entry's file from Mezze.toml. The
     *  module derivation rule treats this file as the package's main module.
     *  Null if Mezze.toml has no [[app]] block. */
    private var entryFilePath: String? = null

    /** Read-only accessor for the workspace service. Null until [connect] runs
     *  with a project that has Mezze.toml. */
    fun getProjectStore(): MezzeStore? = projectStore

    /** Read-only accessor for the workspace service. Null until [connect] runs. */
    fun getProjectRoot(): File? = projectRootDir

    /**
     * State for an open document.
     */
    data class DocumentState(
        val uri: String,
        val content: String,
        val version: Int,
        val typeCheckResult: TypeCheckResult?,
        val typeAliases: Map<String, TypeAliasInfo>,  // For pretty printing types with aliases
        val diagnostics: List<Diagnostic>,
        val parsedModule: ParsedModule? = null
    )

    /**
     * Connect to the language client.
     *
     * The lsp4j launcher calls this *before* the client sends `initialize`,
     * so server.getWorkspaceRoot() is null here. We can't open the project
     * store yet — defer that to [initializeProjectStore], called from
     * MezzeLanguageServer.initialize() once workspaceRoot is populated.
     */
    fun connect(client: LanguageClient) {
        this.client = client
    }

    /**
     * Open the project store now that workspaceRoot is known. Idempotent —
     * second call is a no-op once the store is open.
     *
     * Called from MezzeLanguageServer.initialize() after the InitializeParams
     * have been parsed.
     */
    fun initializeProjectStore() {
        if (projectStore != null) return  // already opened
        val workspaceRoot = server.getWorkspaceRoot()
        server.logInfo("Workspace root for codebase: $workspaceRoot")
        if (workspaceRoot == null) {
            server.logInfo("No workspace root — running in-memory only.")
            return
        }
        val rootFile = File(workspaceRoot)
        server.logInfo("Root file exists: ${rootFile.exists()}, isDirectory: ${rootFile.isDirectory}")
        server.logInfo("Looking for Mezze.toml at: ${File(rootFile, "Mezze.toml").absolutePath}")
        server.logInfo("Mezze.toml exists: ${File(rootFile, "Mezze.toml").exists()}")
        val projectRoot = SQLiteMezzeStore.findProjectRoot(rootFile)
        if (projectRoot == null) {
            server.logInfo("No Mezze.toml found, using in-memory mode")
            return
        }
        try {
            projectStore = SQLiteMezzeStore.openProject(projectRoot)
            projectRootDir = projectRoot
            val (pkg, entry) = readMezzeTomlEntries(projectRoot)
            packageName = pkg
            entryFilePath = entry?.let { File(projectRoot, it).absolutePath }
            server.logInfo("Using project store at ${SQLiteMezzeStore.resolveCacheFile(projectRoot).absolutePath} (package=$packageName, entry=$entry)")

            // Stdlib hydration removed (#233): the previous code populated
            // ModuleTypeChecker.globalCache from cache.sqlite for every
            // `std::*` module — but used `hydrateFromV2Module` which sets
            // `parsedModule = null` on the wrapper. Downstream code in
            // `TypeChecker.checkParsedModule` walks
            // `getAllCachedModules().parsedModule.decls` to harvest
            // transitive `ParsedAbilityDecl` / `ParsedEffectDecl` (lines
            // ~317 and ~336). With null parsedModule, these loops yield
            // nothing — and built-in abilities like NumMul/NumAdd
            // disappear from the resolution context. The user's
            // `xs |> seq::map { ... f = { x } -> x * 2 }` then fails
            // with "Cannot use '*' with Int" while `mez check` (which
            // never hydrates) typechecks fine.
            //
            // PrecompiledStdlib's fast-path mergeInto already injects
            // every prelude binding/ability/effect into the typing
            // context directly — no globalCache pre-warm needed.
            // User modules still hydrate lazily via ModuleResolver on
            // first import.
            val store = projectStore!!
            server.logInfo("Project store opened: ${SQLiteMezzeStore.resolveCacheFile(rootFile).absolutePath}")
        } catch (e: Exception) {
            server.logError("Failed to create store: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Shutdown and cleanup resources.
     */
    fun shutdown() {
        analysisScheduler.shutdownNow()
        pendingAnalysis.clear()
        projectStore?.close()
        documents.clear()
    }

    /**
     * `mezze.clearCache`: wipe `.mz/cache.sqlite`, `stdlib.sqlite`,
     * `deps.sqlite` and reopen the store. Re-seeds stdlib from the JAR
     * resource on reopen. Re-analyzes every open document so diagnostics
     * stay live.
     *
     * Cheaper than `restart` — does not flush the typechecker's process-
     * global module cache.
     */
    fun clearCache() {
        val root = projectRootDir ?: return
        try {
            projectStore?.close()
        } catch (_: Exception) { /* ignore */ }
        projectStore = null
        SQLiteMezzeStore.wipeProjectFiles(root)
        projectStore = SQLiteMezzeStore.openProject(root)
        server.logInfo("Cleared cache at ${SQLiteMezzeStore.resolveProjectDir(root).absolutePath}")
        reanalyzeOpenDocuments()
    }

    /**
     * `mezze.restart`: full LSP-side reset short of process-restart.
     * Wipes cache + flushes typechecker globalCache + clears
     * in-memory document state, then re-runs `connect`-style hydration
     * and re-analyzes every open document.
     *
     * Use this when the user suspects stale state in the typechecker
     * (e.g., an aborted in-flight rename).
     */
    fun restart() {
        val root = projectRootDir ?: return
        try { projectStore?.close() } catch (_: Exception) { }
        projectStore = null
        SQLiteMezzeStore.wipeProjectFiles(root)
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
        lastSuccessfulResult.clear()
        projectStore = SQLiteMezzeStore.openProject(root)
        server.logInfo("Restarted LSP state for ${root.absolutePath}")
        reanalyzeOpenDocuments()
    }

    private fun reanalyzeOpenDocuments() {
        val snapshot = documents.toMap()
        for ((uri, state) in snapshot) {
            // Re-analyze with persistToStore=true so the freshly-empty
            // cache picks up everything the user has open right now.
            val fresh = analyzeDocument(uri, state.content, state.version, persistToStore = true)
            documents[uri] = fresh
            publishDiagnostics(uri, fresh.diagnostics)
        }
    }

    // ========== Document Synchronization ==========

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        val content = params.textDocument.text
        val version = params.textDocument.version

        server.logInfo("Document opened: $uri")

        // didOpen's `content` is the on-disk file (the editor just loaded
        // it), so persisting matches the "cache reflects files" rule.
        val state = analyzeDocument(uri, content, version, persistToStore = true)
        documents[uri] = state

        // Publish diagnostics
        publishDiagnostics(uri, state.diagnostics)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        val version = params.textDocument.version

        // For full sync, we get the entire content
        val content = params.contentChanges.firstOrNull()?.text ?: return

        // Update the in-memory bytes immediately so hover/completion
        // see current content. Analysis runs on the debounce timer.
        // We deliberately don't clear typeCheckResult — readers like
        // hover prefer it over lastSuccessfulResult, and a slightly
        // stale type is better UX than a sudden "no info" until the
        // debounce fires.
        val previous = documents[uri]
        if (previous != null) {
            documents[uri] = previous.copy(content = content, version = version)
        }

        // Cancel any pending analysis for this URI — every keystroke
        // bumps the debounce window. The latest task wins.
        pendingAnalysis.remove(uri)?.cancel(false)

        val task = analysisScheduler.schedule({
            val state = analyzeDocument(uri, content, version, persistToStore = false)
            documents[uri] = state
            publishDiagnostics(uri, state.diagnostics)
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        pendingAnalysis[uri] = task
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        server.logInfo("Document closed: $uri")

        // Remove document state
        documents.remove(uri)

        // Clear diagnostics
        publishDiagnostics(uri, emptyList())
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        val uri = params.textDocument.uri
        server.logInfo("Document saved: $uri")

        // Re-analyze on save if content provided. didSave's `content` is
        // what just landed on disk — safe to persist into the store so
        // `mez dev` and the next LSP cold-open share rows.
        params.text?.let { content ->
            val state = documents[uri]
            if (state != null) {
                val newState = analyzeDocument(uri, content, state.version, persistToStore = true)
                documents[uri] = newState
                publishDiagnostics(uri, newState.diagnostics)
            }
        }
    }

    // ========== Analysis ==========

    /**
     * Analyze a document and return its state with diagnostics.
     *
     * Uses multi-error recovery to collect ALL errors and provide
     * partial type information even when there are errors.
     *
     * @param persistToStore when true, write the parsed/typed result into
     *   `cache.sqlite`. Only safe when [content] matches what's on disk —
     *   i.e. didSave (and didOpen, where the editor's initial buffer is the
     *   on-disk file). Mid-edit didChange events MUST pass false; otherwise
     *   the cache holds rows for source bytes that never existed on disk.
     */
    private fun analyzeDocument(uri: String, content: String, version: Int, persistToStore: Boolean = false): DocumentState {
        val diagnostics = mutableListOf<Diagnostic>()
        var typeCheckResult: TypeCheckResult? = null
        var typeAliases: Map<String, TypeAliasInfo> = emptyMap()

        val filePath = uriToPath(uri)
        val fileName = filePath?.fileName?.toString()

        // Step 1: Parse with error recovery to get ALL parse errors
        val parseResult = parser.parseWithRecovery(content, file = fileName)
        val parsedModule = parseResult.module

        // Collect all parse errors as diagnostics
        parseResult.errors.forEach { e ->
            diagnostics.add(createDiagnostic(
                message = e.errorMessage,
                severity = DiagnosticSeverity.Error,
                line = e.line - 1,  // LSP is 0-indexed
                column = e.column - 1,
                endColumn = e.column + e.length
            ))
        }

        // If there are parse errors, still try to type check the partial AST
        // This allows showing both parse errors and any type errors in successfully parsed code

        // Step 2: Type check using v2 pipeline
        try {
            val workspaceRoot = server.getWorkspaceRoot()
            val projectRoot = if (workspaceRoot != null) {
                Paths.get(workspaceRoot)
            } else {
                filePath?.parent ?: Paths.get(".")
            }

            val result = typeChecker.checkParsedModule(parsedModule, projectRoot, null, enablePrelude = true)

            typeAliases = typeChecker.getTypeAliases()

            when (result) {
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success -> {
                    // Collect warnings — same Loc-to-LSP-range conversion
                    // as the error path below; without endLine/endColumn the
                    // squiggle collapses to one character.
                    result.warnings.forEach { warning ->
                        val loc = warning.loc
                        val line = loc?.startLine?.minus(1) ?: 0
                        val column = loc?.startCol?.minus(1) ?: 0
                        val endLine = loc?.endLine?.minus(1)
                        val endColumn = loc?.endCol
                        diagnostics.add(createDiagnostic(
                            message = warning.message,
                            severity = DiagnosticSeverity.Warning,
                            line = line,
                            column = column,
                            endLine = endLine,
                            endColumn = endColumn
                        ))
                    }

                    typeCheckResult = TypeCheckResult(
                        resolvedImports = buildResolvedImports(result.importContext),
                        importedAbilities = result.importContext?.importedAbilities ?: emptyMap(),
                        importedEffects = result.importContext?.importedEffects ?: emptyMap(),
                        // Critical: hover, inlay hints, find-references, rename and
                        // every other position-based feature read typedModule from
                        // this object. Without it, the entire LSP appears to "work"
                        // but produces no type info.
                        typedModule = result.typedModule,
                        loweredFunctions = result.loweredFunctions ?: emptyList(),
                        warnings = result.warnings.map {
                            com.mezze.language.typechecker.TypeCheckWarning(it.message, it.loc)
                        }
                    )
                    lastSuccessfulResult[uri] = typeCheckResult!!

                    // Unused-import diagnostics. We do this here (vs. in the
                    // typechecker) because the typechecker treats imports as
                    // additive context — it doesn't know whether a binding
                    // got referenced. Walking the typed module gives us the
                    // exact set of names the user mentioned.
                    val typedForUnused = result.typedModule
                    if (typedForUnused != null) {
                        diagnostics.addAll(
                            unusedImportDiagnostics(parsedModule, typedForUnused)
                        )
                    }

                    // Write typed bytes + per-decl blobs into the shared store
                    // so concurrent `mez dev` (and a future LSP restart) see
                    // them. Same path StorePopulator uses for the CLI.
                    //
                    // Caveats:
                    // - Module path comes from typedModule.path (filename for
                    //   LSP-loaded files; this is wrong for multi-file projects
                    //   sharing the same package — tracked in #169).
                    val store = projectStore
                    val typedMod = result.typedModule
                    val rootDir = projectRootDir
                    val pkgName = packageName
                    // Diagnose silent typecheck failures: when typedMod is null but
                    // we still got a Success branch, the typechecker swallowed an
                    // error (e.g. stdlib import didn't resolve). Surface that loudly
                    // so the editor's user knows their hover/inlay aren't working.
                    if (persistToStore && typedMod == null) {
                        server.logError(
                            "Typecheck produced no TypedModule (typedModule is null on Success branch). " +
                            "Hover/inlay/symbols will be empty. Likely cause: a `use` import didn't resolve, " +
                            "or stdlib failed to load. Check earlier log lines for stdlib hydration counts."
                        )
                    }
                    if (persistToStore && store != null && typedMod != null && filePath != null && rootDir != null && pkgName != null) {
                        // Use ModulePath.derive so the modulePath we write
                        // matches what `mez dev` writes for the same file —
                        // `(modulePath, sourceHash)` rows are then shared.
                        // If the file lives outside the project's src/, skip
                        // (caller is editing a scratch file; not our concern).
                        val derived = com.mezze.language.codebase.ModulePath
                            .derive(rootDir, filePath.toFile(), pkgName, entryFilePath)
                        if (derived == null) {
                            server.logInfo(
                                "persistToStore: file is outside project src/ — skipping store write. " +
                                "rootDir=${rootDir.absolutePath} file=$filePath pkg=$pkgName entry=$entryFilePath"
                            )
                        }
                        if (derived != null) {
                            try {
                                val sourceHash = sha256Hex(content)
                                val publicResult = com.mezze.language.typechecker.TypeCheckResult(
                                    resolvedImports = buildResolvedImports(result.importContext),
                                    warnings = result.warnings.map {
                                        com.mezze.language.typechecker.TypeCheckWarning(it.message, it.loc)
                                    },
                                    importedAbilities = result.importContext?.importedAbilities ?: emptyMap(),
                                    importedEffects = result.importContext?.importedEffects ?: emptyMap(),
                                    loweredFunctions = result.loweredFunctions ?: emptyList(),
                                    typedModule = typedMod,
                                    performerRecords = result.loweredPerformers
                                )
                                val depParsedModules = com.mezze.language.typechecker.ModuleResolver.getAllParsedModules()
                                StorePopulator.populate(
                                    store,
                                    publicResult,
                                    modulePath = derived,
                                    filePath = filePath.toString(),
                                    sourceHash = sourceHash,
                                    entrySource = content,
                                    parsedModule = parsedModule,
                                    loadedParsedModules = depParsedModules
                                )
                            } catch (e: Exception) {
                                // Non-fatal: store write failures shouldn't block diagnostics.
                                server.logError("Failed to populate store from LSP typecheck: ${e.message}")
                            }
                        }
                    }
                }
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Failure -> {
                    result.errors.forEach { error ->
                        // Mezze Loc is 1-indexed inclusive on both ends.
                        // LSP wants 0-indexed half-open [start, end), so:
                        //   start = (line-1, col-1)
                        //   end   = (endLine-1, endCol)        ← exclusive
                        // Passing only `line + column` shrinks the squiggle to
                        // a single character — see #228 / user report.
                        val loc = error.loc
                        val line = loc?.startLine?.minus(1) ?: 0
                        val column = loc?.startCol?.minus(1) ?: 0
                        val endLine = loc?.endLine?.minus(1)
                        val endColumn = loc?.endCol
                        diagnostics.add(createDiagnostic(
                            message = error.message,
                            severity = DiagnosticSeverity.Error,
                            line = line,
                            column = column,
                            endLine = endLine,
                            endColumn = endColumn
                        ))
                    }
                    // Even on Failure, the typechecker may emit a partial
                    // typedModule for the decls that DID solve. Hand it to
                    // the LSP state so hover/inlay/cmd-click on surviving
                    // decls keep working — without this, one broken decl
                    // wipes out type info for the whole file.
                    val partialTyped = result.typedModule
                    if (partialTyped != null) {
                        typeCheckResult = TypeCheckResult(
                            typedModule = partialTyped
                        )
                    }
                }
            }

        } catch (e: TypeCheckError) {
            // Fallback for unexpected exceptions
            diagnostics.add(createDiagnosticFromTypeError(e))
        } catch (e: Exception) {
            diagnostics.add(createDiagnostic(
                message = "Internal error: ${e.message}",
                severity = DiagnosticSeverity.Error,
                line = 0,
                column = 0
            ))
        }

        return DocumentState(uri, content, version, typeCheckResult, typeAliases, diagnostics, parsedModule)
    }

    /**
     * Build resolved imports list from import context.
     */
    private fun buildResolvedImports(importContext: com.mezze.language.typechecker.module.ModuleImportContext?): List<ResolvedImport> {
        if (importContext == null) return emptyList()

        val imports = mutableListOf<ResolvedImport>()

        // Add imported bindings (functions/values)
        for ((name, binding) in importContext.importedBindings) {
            imports.add(ResolvedImport(
                localName = name,
                originalName = binding.originalName,
                modulePath = binding.modulePath,
                type = binding.type,
                kind = ImportKind.FUNCTION
            ))
        }

        // Add imported types
        for ((name, typeDecl) in importContext.importedTypes) {
            val typeParams = typeDecl.typeParams.map { MezzeType.VarType(TypeVar(it)) }
            imports.add(ResolvedImport(
                localName = name,
                originalName = name,
                modulePath = "",
                type = MezzeType.TypeApplication(name, typeParams),
                kind = ImportKind.TYPE
            ))
        }

        // Add imported abilities
        for ((name, _) in importContext.importedAbilities) {
            imports.add(ResolvedImport(
                localName = name,
                originalName = name,
                modulePath = "",
                type = MezzeType.TypeApplication(name, emptyList()),
                kind = ImportKind.ABILITY
            ))
        }

        // Add imported effects
        for ((name, _) in importContext.importedEffects) {
            imports.add(ResolvedImport(
                localName = name,
                originalName = name,
                modulePath = "",
                type = MezzeType.TypeApplication(name, emptyList()),
                kind = ImportKind.EFFECT
            ))
        }

        return imports
    }

    /**
     * Compute unused-import diagnostics by diffing the parsed module's
     * `use` decls against the set of names referenced by the typed module.
     *
     * For each `use` we determine the *local* name(s) it brings into scope:
     *   - `use foo::bar`             → "bar"
     *   - `use foo::bar as baz`      → "baz"
     *   - `use foo::{a, b as c}`     → ["a", "c"]
     *
     * We then collect every name referenced anywhere in the typed module's
     * decl bodies — TypedVar names and TypedQualifiedRef.localName matches.
     * If a use's local name is missing from that set, it's unused.
     *
     * Returned diagnostics carry `DiagnosticTag.Unnecessary` so editors
     * can render them dimmed instead of as bright errors.
     */
    private fun unusedImportDiagnostics(
        parsed: com.mezze.language.parsedast.ParsedModule,
        typed: com.mezze.language.typedast.TypedModule
    ): List<Diagnostic> {
        val referenced = collectReferencedNames(typed).toMutableSet()
        // TypedVar drops the qualifier — `seq::filter` ends up as
        // TypedVar(name = "filter"). The unused-import check then never
        // sees `seq` as referenced, even though the user clearly is using
        // the import. Walk the parsed AST to recover qualifier short
        // names (the first segment of each `::`-qualified ref).
        collectParsedQualifiers(parsed, referenced)
        val out = mutableListOf<Diagnostic>()

        for (decl in parsed.decls) {
            if (decl !is com.mezze.language.parsedast.ParsedUseDecl) continue
            val unusedItems = unusedItemsIn(decl, referenced)
            if (unusedItems.isEmpty()) continue

            val message = if (unusedItems.size == 1 && unusedItems.single() == localNameOf(decl)) {
                "Unused import '${unusedItems.single()}'"
            } else {
                "Unused import: ${unusedItems.joinToString(", ") { "'$it'" }}"
            }
            val diag = createDiagnostic(
                message = message,
                severity = DiagnosticSeverity.Hint,
                line = decl.loc.startLine - 1,
                column = decl.loc.startCol - 1,
                endLine = decl.loc.endLine - 1,
                endColumn = decl.loc.endCol
            )
            diag.tags = listOf(DiagnosticTag.Unnecessary)
            out.add(diag)
        }
        return out
    }

    /**
     * For a `use` decl, return the subset of items that are unused.
     * For a non-selective `use foo::bar` (one local name) returns either
     * empty or a singleton.
     */
    private fun unusedItemsIn(
        decl: com.mezze.language.parsedast.ParsedUseDecl,
        referenced: Set<String>
    ): List<String> {
        return when (val path = decl.path) {
            is com.mezze.language.parsedast.ParsedUsePath.Module -> {
                val local = decl.alias ?: path.path.shortName
                if (local in referenced) emptyList() else listOf(local)
            }
            is com.mezze.language.parsedast.ParsedUsePath.Selective -> {
                path.items.mapNotNull { item ->
                    val local = item.alias ?: item.name
                    if (local in referenced) null else local
                }
            }
        }
    }

    /**
     * Walk the parsed module's let/expr trees collecting every qualifier's
     * short name. For `seq::filter { ... }` the parsed identifier is
     * `ParsedIdentifier(localName = "filter", qualifier = ["seq"])` —
     * we add `"seq"` to the referenced set so a `use std::collections::seq`
     * is correctly counted as used.
     */
    private fun collectParsedQualifiers(
        parsed: com.mezze.language.parsedast.ParsedModule,
        referenced: MutableSet<String>
    ) {
        fun walk(expr: com.mezze.language.parsedast.ParsedExpr) {
            when (expr) {
                is com.mezze.language.parsedast.ParsedIdentifier -> {
                    expr.qualifier?.segments?.firstOrNull()?.let { referenced.add(it) }
                }
                is com.mezze.language.parsedast.ParsedFunctionCall -> {
                    walk(expr.function); walk(expr.arguments)
                }
                is com.mezze.language.parsedast.ParsedLambda -> walk(expr.body)
                is com.mezze.language.parsedast.ParsedRecordLit -> {
                    for (entry in expr.entries) when (entry) {
                        is com.mezze.language.parsedast.ParsedRecordEntry.Field -> entry.value?.let { walk(it) }
                        is com.mezze.language.parsedast.ParsedRecordEntry.Spread -> walk(entry.expr)
                    }
                }
                is com.mezze.language.parsedast.ParsedMemberAccess -> walk(expr.receiver)
                is com.mezze.language.parsedast.ParsedPipe -> { walk(expr.left); walk(expr.right) }
                is com.mezze.language.parsedast.ParsedIf -> {
                    walk(expr.condition); walk(expr.thenBranch); walk(expr.elseBranch)
                }
                is com.mezze.language.parsedast.ParsedMatch -> {
                    walk(expr.scrutinee)
                    for (case in expr.cases) {
                        case.guard?.let { walk(it) }
                        walk(case.body)
                    }
                }
                is com.mezze.language.parsedast.ParsedSeqLit -> for (e in expr.elements) when (e) {
                    is com.mezze.language.parsedast.ParsedSeqElement.Regular -> walk(e.expr)
                    is com.mezze.language.parsedast.ParsedSeqElement.Spread -> walk(e.expr)
                }
                is com.mezze.language.parsedast.ParsedDo -> walk(expr.result)
                is com.mezze.language.parsedast.ParsedPerform -> walk(expr.body)
                is com.mezze.language.parsedast.ParsedEffectCall -> walk(expr.arguments)
                is com.mezze.language.parsedast.ParsedParens -> walk(expr.expr)
                is com.mezze.language.parsedast.ParsedBinaryOp -> { walk(expr.left); walk(expr.right) }
                is com.mezze.language.parsedast.ParsedUnaryOp -> walk(expr.operand)
                is com.mezze.language.parsedast.ParsedVariantConstruct -> expr.value?.let { walk(it) }
                else -> { /* literals + nodes that carry no Identifier */ }
            }
        }
        for (decl in parsed.decls) {
            if (decl is com.mezze.language.parsedast.ParsedLetDecl) walk(decl.value)
        }
    }

    /** The single binding name of a non-selective `use`, else null. */
    private fun localNameOf(decl: com.mezze.language.parsedast.ParsedUseDecl): String? =
        when (val path = decl.path) {
            is com.mezze.language.parsedast.ParsedUsePath.Module -> decl.alias ?: path.path.shortName
            else -> null
        }

    /**
     * Walk every typed decl, accumulating every name that could possibly
     * resolve to an import. We're permissive on purpose — false negatives
     * (failing to flag an unused import) are fine, false positives
     * (flagging something that's actually used) are not.
     */
    private fun collectReferencedNames(typed: com.mezze.language.typedast.TypedModule): Set<String> {
        val names = HashSet<String>()
        for (decl in typed.decls) {
            when (decl) {
                is com.mezze.language.typedast.TypedLetDecl -> walkExpr(decl.value, names)
                else -> { /* type/ability/effect decls — referenced names live in
                            their parsed form via type annotations; we approximate
                            by treating those decls as nothing-referenced. The
                            TypedAst surface for type expressions doesn't currently
                            carry resolved name strings we can scan, and false
                            negatives are the safe direction here. */ }
            }
        }
        return names
    }

    private fun walkExpr(expr: com.mezze.language.typedast.TypedExpr, into: MutableSet<String>) {
        when (expr) {
            is com.mezze.language.typedast.TypedVar -> into.add(expr.name)
            is com.mezze.language.typedast.TypedCall -> {
                walkExpr(expr.function, into); walkExpr(expr.arg, into)
            }
            is com.mezze.language.typedast.TypedLambda -> walkExpr(expr.body, into)
            is com.mezze.language.typedast.TypedBlock -> {
                for (stmt in expr.statements) when (stmt) {
                    is com.mezze.language.typedast.TypedStatement.Let -> walkExpr(stmt.value, into)
                    is com.mezze.language.typedast.TypedStatement.Expr -> walkExpr(stmt.expr, into)
                }
                walkExpr(expr.result, into)
            }
            is com.mezze.language.typedast.TypedIf -> {
                walkExpr(expr.condition, into); walkExpr(expr.thenBranch, into); walkExpr(expr.elseBranch, into)
            }
            is com.mezze.language.typedast.TypedMatch -> {
                walkExpr(expr.scrutinee, into)
                for (case in expr.cases) {
                    case.guard?.let { walkExpr(it, into) }
                    walkExpr(case.body, into)
                    for (b in case.ifLetBindings) walkExpr(b.expr, into)
                }
            }
            is com.mezze.language.typedast.TypedRecord -> {
                for (entry in expr.entries) when (entry) {
                    is com.mezze.language.typedast.TypedRecordEntry.Field -> walkExpr(entry.value, into)
                    is com.mezze.language.typedast.TypedRecordEntry.Spread -> walkExpr(entry.expr, into)
                }
            }
            is com.mezze.language.typedast.TypedRecordUpdate -> {
                walkExpr(expr.record, into)
                for (entry in expr.updates) when (entry) {
                    is com.mezze.language.typedast.TypedRecordEntry.Field -> walkExpr(entry.value, into)
                    is com.mezze.language.typedast.TypedRecordEntry.Spread -> walkExpr(entry.expr, into)
                }
            }
            is com.mezze.language.typedast.TypedFieldAccess -> walkExpr(expr.record, into)
            is com.mezze.language.typedast.TypedSeq -> {
                for (e in expr.elements) when (e) {
                    is com.mezze.language.typedast.TypedSeqElement.Regular -> walkExpr(e.expr, into)
                    is com.mezze.language.typedast.TypedSeqElement.Spread -> walkExpr(e.expr, into)
                }
            }
            is com.mezze.language.typedast.TypedPipe -> {
                walkExpr(expr.left, into); walkExpr(expr.right, into)
            }
            is com.mezze.language.typedast.TypedPerform -> {
                walkExpr(expr.body, into)
                for (h in expr.handlers) {
                    into.add(h.performerName)
                    h.params?.let { walkExpr(it, into) }
                }
            }
            is com.mezze.language.typedast.TypedPerformOp -> {
                walkExpr(expr.arg, into)
                into.add(expr.operationName)
            }
            is com.mezze.language.typedast.TypedVariantConstruct -> expr.payload?.let { walkExpr(it, into) }
            is com.mezze.language.typedast.TypedStringLit -> {
                for (part in expr.parts) {
                    if (part is com.mezze.language.typedast.TypedStringPart.Interpolation) {
                        walkExpr(part.expr, into)
                    }
                }
            }
            else -> { /* literals + the rest carry no name references */ }
        }
    }

    /**
     * Create a diagnostic from a recovery error.
     */
    private fun createDiagnosticFromRecoveryError(error: TypeChecker.TypeCheckError): Diagnostic {
        val loc = error.loc
        return createDiagnostic(
            message = error.message,
            severity = DiagnosticSeverity.Error,
            line = (loc?.startLine ?: 1) - 1,
            column = (loc?.startCol ?: 1) - 1,
            endLine = loc?.endLine?.minus(1),
            endColumn = loc?.endCol,
            code = error.phase
        )
    }

    /**
     * Publish diagnostics to the client.
     */
    private fun publishDiagnostics(uri: String, diagnostics: List<Diagnostic>) {
        client.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
    }

    /**
     * Create a diagnostic from a type error.
     */
    private fun createDiagnosticFromTypeError(error: TypeCheckError): Diagnostic {
        val loc = error.loc
        return createDiagnostic(
            message = error.message ?: "Type error",
            severity = DiagnosticSeverity.Error,
            line = (loc?.startLine ?: 1) - 1,
            column = (loc?.startCol ?: 1) - 1,
            endLine = loc?.endLine?.minus(1),
            endColumn = loc?.endCol,
            code = error.errorType.name
        )
    }

    /**
     * Create a diagnostic.
     */
    private fun createDiagnostic(
        message: String,
        severity: DiagnosticSeverity,
        line: Int,
        column: Int,
        endColumn: Int? = null,
        endLine: Int? = null,
        code: String? = null
    ): Diagnostic {
        val startLine = maxOf(0, line)
        val effectiveEndLine = maxOf(startLine, endLine ?: line)
        val range = Range(
            Position(startLine, maxOf(0, column)),
            Position(effectiveEndLine, maxOf(0, endColumn ?: (column + 1)))
        )
        return Diagnostic(range, message, severity, "mezze").apply {
            if (code != null) {
                this.code = Either.forLeft(code)
            }
        }
    }

    // ========== Hover ==========

    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            val state = documents[uri] ?: return@supplyAsync null

            // Find identifier or expression at position
            // Works even without type check result (shows basic info from AST)
            val hoverInfo = findHoverInfo(state, position)
            if (hoverInfo != null) {
                val markdown = MarkupContent(MarkupKind.MARKDOWN, hoverInfo)
                Hover(markdown)
            } else {
                null
            }
        }
    }

    /**
     * Find hover information at a position.
     * Works even when type checking fails - uses AST for basic info.
     * Returns markdown with syntax highlighting.
     */
    private fun findHoverInfo(state: DocumentState, position: Position): String? {
        // Get the word at the position
        val lines = state.content.lines()
        if (position.line >= lines.size) return null

        val line = lines[position.line]
        val word = extractWordAt(line, position.character)
        if (word.isNullOrEmpty()) return null

        val result = state.typeCheckResult

        // Helper to format type with aliases
        fun formatType(type: MezzeType): String {
            return prettyPrintTypeWithAliases(type, state.typeAliases)
        }

        // Helper to wrap in syntax-highlighted code block
        fun codeBlock(code: String): String = "```mezze\n$code\n```"

        // PRIORITY: Use TypedAST position-based lookup for precise hover
        // This finds the exact expression at the cursor position, including
        // subexpressions in lambda bodies, record fields, etc.
        val typedModule = result?.typedModule ?: lastSuccessfulResult[state.uri]?.typedModule
        if (typedModule != null) {
            val line = position.line + 1  // LSP is 0-indexed, Loc is 1-indexed
            val col = position.character + 1
            when (val lookup = TypedAstLookup.findAtPosition(typedModule, line, col)) {
                is TypedAstLookup.LookupResult.Expr -> {
                    val expr = lookup.expr
                    val typeStr = formatType(expr.type)
                    return when (expr) {
                        is com.mezze.language.typedast.TypedVar -> {
                            val abilityHover = result?.let { renderAbilityMethodHover(expr.name, it, state) }
                            abilityHover ?: codeBlock("${expr.name} : $typeStr")
                        }
                        is com.mezze.language.typedast.TypedFieldAccess -> {
                            // Method-call syntax often surfaces here as a
                            // pseudo field-access; if the field name matches
                            // an ability operation in scope, prefer the
                            // richer `Ability.method : T` hover.
                            val abilityHover = result?.let { renderAbilityMethodHover(expr.field, it, state) }
                            abilityHover ?: codeBlock(".${expr.field} : $typeStr")
                        }
                        is com.mezze.language.typedast.TypedCall -> codeBlock("$typeStr")
                        else -> codeBlock(typeStr)
                    }
                }
                is TypedAstLookup.LookupResult.Binding -> {
                    val typeStr = formatType(lookup.type)
                    return codeBlock("${lookup.name} : $typeStr")
                }
                is TypedAstLookup.LookupResult.NotFound -> {} // Fall through to word-based lookup
            }

            // Local-binding hover (#124): when the cursor is on a NAME token —
            // either a let-binding's declared name (`let x = …` ← here), or
            // the param name in `{ x } -> …`, or a use of `x` further down —
            // search the scope at this position for a matching local. If we
            // get a hit, show its type; otherwise fall through to the
            // top-level / import paths below.
            val localScope = TypedAstScopeWalker.bindingsAtPosition(typedModule, line, col)
            // Iterate in reverse so innermost (shadowing) wins.
            val localHit = localScope.asReversed().firstOrNull { it.name == word }
            if (localHit != null) {
                val kindLabel = when (localHit.kind) {
                    TypedAstScopeWalker.ScopeBinding.Kind.LAMBDA_PARAM -> "param"
                    TypedAstScopeWalker.ScopeBinding.Kind.LET_LOCAL -> "let"
                    TypedAstScopeWalker.ScopeBinding.Kind.MATCH_BINDING -> "match"
                    TypedAstScopeWalker.ScopeBinding.Kind.IF_LET_BINDING -> "if let"
                }
                return codeBlock("$kindLabel ${localHit.name} : ${formatType(localHit.type)}")
            }
        }

        // If we have type check result, use it for full type info
        if (result != null) {
            // 1. Look up top-level bindings with inferred types via TypedModule
            val typedBinding = result.typedModule?.decls
                ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
                ?.find { it.name == word }

            if (typedBinding != null) {
                val bindingType = typedBinding.scheme.type
                if (bindingType !is MezzeType.VarType) {
                    return codeBlock("$word : ${formatType(bindingType)}")
                }
            }

            // 2. Look up in resolved imports
            val resolvedImport = result.resolvedImports.find { it.localName == word }
            if (resolvedImport != null) {
                val typeStr = formatType(resolvedImport.type)
                val kindPrefix = when (resolvedImport.kind) {
                    ImportKind.EFFECT -> "effect "
                    ImportKind.ABILITY -> "ability "
                    ImportKind.TYPE -> "type "
                    ImportKind.PERFORMER -> "performer "
                    ImportKind.FUNCTION -> ""
                }
                val fromModule = if (resolvedImport.modulePath.isNotEmpty()) {
                    "\n\n*from* `${resolvedImport.modulePath}`"
                } else ""
                return codeBlock("$kindPrefix$word : $typeStr") + fromModule
            }
        }

        // Fallback: use parsedModule for basic info (even when type check failed)
        val parsedModule = state.parsedModule

        // 3. Look up type declarations from parsed module
        val typeDecl = parsedModule?.decls?.filterIsInstance<ParsedTypeDecl>()?.find { it.name == word }
        if (typeDecl != null) {
            return codeBlock("type $word")
        }

        // 4-5. Look up ability declarations from parsed module or imports
        val abilityDecl = parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>()?.find { it.name == word }
            ?: result?.importedAbilities?.get(word)

        if (abilityDecl != null) {
            val fromModule = result?.resolvedImports
                ?.find { it.localName == word && it.kind == ImportKind.ABILITY }
                ?.modulePath
            return formatAbilityHover(abilityDecl, fromModule)
        }

        // 5b. Look up effect declarations from parsed module or imports
        val effectDecl = parsedModule?.decls?.filterIsInstance<ParsedEffectDecl>()?.find { it.name == word }
            ?: result?.importedEffects?.get(word)

        if (effectDecl != null) {
            val fromModule = result?.resolvedImports
                ?.find { it.localName == word && it.kind == ImportKind.EFFECT }
                ?.modulePath
            return formatEffectHover(effectDecl, fromModule)
        }

        // 7. Look up let bindings from parsed module
        val letDecl = parsedModule?.decls?.filterIsInstance<ParsedLetDecl>()
            ?.filter { it.isSimpleBinding }
            ?.find { it.variableName == word }

        if (letDecl != null) {
            return codeBlock("let $word = ...")
        }

        return null
    }

    /**
     * Render hover info for a method-name token (`.first`, `.is_some`,
     * etc.) in a dot-call. Looks up the method name in:
     *   1. user-defined abilities in scope (importedAbilities + local decls)
     *   2. PrecompiledStdlib's prelude abilities (Sized, Eq, Ord, …)
     * Returns `Ability.method : { it: 'a, … } -> RetT` plus the docstring
     * when present. Returns null when no ability operation matches —
     * caller falls back to the generic hover.
     */
    private fun renderAbilityMethodHover(
        methodName: String,
        result: TypeCheckResult,
        state: DocumentState
    ): String? {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"

        // 1. User-defined / file-level abilities
        val localAbilities = state.parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>() ?: emptyList()
        val candidates = mutableListOf<Pair<String, ParsedAbilityOp>>()
        for (ability in result.importedAbilities.values + localAbilities) {
            ability.operations.firstOrNull { it.name == methodName }?.let {
                candidates.add(ability.name to it)
            }
        }
        // 2. Stdlib + transitively-imported abilities — the prelude strips
        // bindings/types/abilities from its returned context, so we have
        // to query the project store for ABILITY decls and look at their
        // operations directly. Same pattern as dot-completion step 4.
        if (candidates.isEmpty()) {
            val store = projectStore
            if (store != null) {
                try {
                    for (declRow in store.getDeclsByKind(com.mezze.language.codebase.DeclKind.ABILITY)) {
                        val blob = store.getBlob(declRow.hash) ?: continue
                        val bytes = blob.typed ?: continue
                        val typedAbility = try {
                            com.mezze.language.codebase.CodebaseSerialization.deserializeTypedAbilityDecl(bytes)
                        } catch (_: Throwable) { continue }
                        val typedOp = typedAbility.operations.firstOrNull { it.name == methodName }
                            ?: continue
                        // Render the typed signature directly — no parsed
                        // annotation needed.
                        val sig = "${typedAbility.name}.${typedOp.name} : " +
                            "${prettyPrintTypeWithAliases(typedOp.paramType, state.typeAliases)} -> " +
                            prettyPrintTypeWithAliases(typedOp.returnType, state.typeAliases)
                        return codeBlock(sig)
                    }
                } catch (_: Throwable) { /* store may not be open */ }
            }
        }
        if (candidates.isEmpty()) return null

        // Render the first candidate. If multiple abilities share a method
        // name (rare — e.g. NumAdd.add vs Seq.add), the receiver type at
        // dispatch picks one; we don't have that info here, so just show
        // the first match. Listing all could be a follow-up.
        val (abilityName, op) = candidates.first()
        val sig = "$abilityName.${op.name} : ${formatTypeAnnotation(op.paramType)} -> ${formatTypeAnnotation(op.returnType)}"
        val doc = op.doc?.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: ""
        return codeBlock(sig) + doc
    }

    /**
     * Format hover info for an ability declaration, showing all operations.
     */
    private fun formatAbilityHover(decl: ParsedAbilityDecl, fromModule: String?): String {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"

        val typeParams = decl.typeParams.joinToString(" ") { "'$it" }
        val forClause = decl.receiverTypeParam?.let { " for '$it" } ?: ""
        val header = "ability ${decl.name} $typeParams$forClause"

        val ops = decl.operations.joinToString("\n") { op ->
            "  ${op.name}: ${formatTypeAnnotation(op.paramType)} -> ${formatTypeAnnotation(op.returnType)}"
        }

        val body = if (ops.isNotEmpty()) " where {\n$ops\n}" else ""
        val moduleInfo = if (!fromModule.isNullOrEmpty()) "\n\n*from* `$fromModule`" else ""

        return codeBlock("$header$body") + moduleInfo
    }

    /**
     * Format hover info for an effect declaration, showing all operations.
     */
    private fun formatEffectHover(decl: ParsedEffectDecl, fromModule: String?): String {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"

        val header = "effect ${decl.name}"

        val ops = decl.operations.joinToString("\n") { op ->
            "  ${op.name}: ${formatTypeAnnotation(op.paramType)} -> ${formatTypeAnnotation(op.returnType)}"
        }

        val body = if (ops.isNotEmpty()) " where {\n$ops\n}" else ""
        val moduleInfo = if (!fromModule.isNullOrEmpty()) "\n\n*from* `$fromModule`" else ""

        return codeBlock("$header$body") + moduleInfo
    }

    /**
     * Format a ParsedTypeAnnotation to a readable string.
     */
    private fun formatTypeAnnotation(type: ParsedTypeAnnotation): String {
        return when (type) {
            is ParsedNamedType -> type.name
            is ParsedRecordType -> {
                if (type.entries.isEmpty()) "{}"
                else "{ ${type.entries.joinToString(", ") { "${it.name}: ${formatTypeAnnotation(it.type)}" }} }"
            }
            is ParsedFunctionType -> {
                val constraints = if (type.abilityConstraints.isNotEmpty()) {
                    " with ${type.abilityConstraints.joinToString(", ") { c ->
                        c.abilityName + if (c.typeArgs.isNotEmpty()) " ${c.typeArgs.joinToString(" ") { formatTypeAnnotation(it) }}" else ""
                    }}"
                } else ""
                "${formatTypeAnnotation(type.paramType)}$constraints -> ${formatTypeAnnotation(type.returnType)}"
            }
            is ParsedTypeApp -> {
                if (type.args.isEmpty()) type.constructor
                else "${type.constructor} ${type.args.joinToString(" ") { formatTypeAnnotation(it) }}"
            }
            is ParsedSeqType -> "[${formatTypeAnnotation(type.elementType)}]"
            is ParsedVariantType -> "| ${type.constructors.joinToString(", ") { it.name }} |"
            is ParsedQualifiedType -> "${type.modulePath}::${type.typeName}"
        }
    }

    /**
     * Find a local binding's type by searching through the typed module.
     * Stubbed — needs ParsedExpr scope walking to resolve local let bindings.
     */
    private fun findLocalBindingType(name: String, typedModule: com.mezze.language.typedast.TypedModule?, line: Int): MezzeType? {
        if (typedModule == null) return null
        // Find the top-level decl whose loc spans the cursor line.
        for (decl in typedModule.decls) {
            if (decl is com.mezze.language.typedast.TypedLetDecl &&
                line >= decl.loc.startLine && line <= decl.loc.endLine
            ) {
                val found = searchLocalBindingInExpr(decl.value, name, line)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Walk a typed expression looking for a binding of [name] introduced before [cursorLine].
     * Returns the most-recently-shadowing match (largest startLine ≤ cursorLine).
     */
    private fun searchLocalBindingInExpr(
        expr: com.mezze.language.typedast.TypedExpr,
        name: String,
        cursorLine: Int
    ): MezzeType? {
        var best: Pair<Int, MezzeType>? = null
        fun consider(loc: Loc, type: MezzeType) {
            if (loc.startLine > cursorLine) return
            val cur = best
            if (cur == null || loc.startLine > cur.first) best = loc.startLine to type
        }
        fun visit(e: com.mezze.language.typedast.TypedExpr) {
            // Cursor must lie inside this expression's span (use endLine + 1 to be lenient on trailing edits).
            if (cursorLine < e.loc.startLine || cursorLine > e.loc.endLine + 1) return
            when (e) {
                is com.mezze.language.typedast.TypedBlock -> {
                    for (stmt in e.statements) {
                        when (stmt) {
                            is com.mezze.language.typedast.TypedStatement.Let -> {
                                if (stmt.name == name) consider(stmt.loc, stmt.type)
                                visit(stmt.value)
                            }
                            is com.mezze.language.typedast.TypedStatement.Expr -> visit(stmt.expr)
                        }
                    }
                    visit(e.result)
                }
                is com.mezze.language.typedast.TypedLambda -> {
                    for (p in e.params) {
                        if (p.name == name) consider(p.loc, p.type)
                    }
                    visit(e.body)
                }
                is com.mezze.language.typedast.TypedIf -> { visit(e.condition); visit(e.thenBranch); visit(e.elseBranch) }
                is com.mezze.language.typedast.TypedMatch -> {
                    visit(e.scrutinee)
                    for (case in e.cases) {
                        for (b in case.bindings) {
                            if (b.name == name) consider(b.loc, b.type)
                        }
                        for (ilb in case.ifLetBindings) visit(ilb.expr)
                        case.guard?.let { visit(it) }
                        visit(case.body)
                    }
                }
                is com.mezze.language.typedast.TypedCall -> { visit(e.function); visit(e.arg) }
                is com.mezze.language.typedast.TypedRecord -> {
                    for (entry in e.entries) when (entry) {
                        is com.mezze.language.typedast.TypedRecordEntry.Field -> visit(entry.value)
                        is com.mezze.language.typedast.TypedRecordEntry.Spread -> visit(entry.expr)
                    }
                }
                is com.mezze.language.typedast.TypedFieldAccess -> visit(e.record)
                is com.mezze.language.typedast.TypedRecordUpdate -> {
                    visit(e.record)
                    for (entry in e.updates) when (entry) {
                        is com.mezze.language.typedast.TypedRecordEntry.Field -> visit(entry.value)
                        is com.mezze.language.typedast.TypedRecordEntry.Spread -> visit(entry.expr)
                    }
                }
                is com.mezze.language.typedast.TypedVariantConstruct -> e.payload?.let { visit(it) }
                is com.mezze.language.typedast.TypedSeq -> {
                    for (el in e.elements) when (el) {
                        is com.mezze.language.typedast.TypedSeqElement.Regular -> visit(el.expr)
                        is com.mezze.language.typedast.TypedSeqElement.Spread -> visit(el.expr)
                    }
                }
                is com.mezze.language.typedast.TypedPerform -> {
                    for (h in e.handlers) h.params?.let { visit(it) }
                    visit(e.body)
                }
                is com.mezze.language.typedast.TypedPerformOp -> visit(e.arg)
                is com.mezze.language.typedast.TypedPipe -> { visit(e.left); visit(e.right) }
                else -> { /* leaves: literals, TypedVar, TypedStringLit, etc. */ }
            }
        }
        visit(expr)
        return best?.second
    }

    /**
     * Extract the word at a character position in a line.
     */
    private fun extractWordAt(line: String, character: Int): String? {
        if (character > line.length) return null

        var start = character
        var end = character

        // Find start of word
        while (start > 0 && isIdentifierChar(line[start - 1])) {
            start--
        }

        // Find end of word
        while (end < line.length && isIdentifierChar(line[end])) {
            end++
        }

        return if (start < end) line.substring(start, end) else null
    }

    private fun isIdentifierChar(c: Char): Boolean {
        return c.isLetterOrDigit() || c == '_' || c == '\''
    }

    // ========== Go to Definition ==========

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            val state = documents[uri] ?: return@supplyAsync Either.forLeft(emptyList())

            val locations = findDefinitions(state, position)
            Either.forLeft(locations)
        }
    }

    private fun findDefinitions(state: DocumentState, position: Position): List<Location> {
        val lines = state.content.lines()
        if (position.line >= lines.size) return emptyList()

        val line = lines[position.line]
        val word = extractWordAt(line, position.character) ?: return emptyList()
        val result = state.typeCheckResult ?: lastSuccessfulResult[state.uri]

        // 1. Try TypedAST position lookup — finds the exact expression at cursor
        val typedModule = result?.typedModule
        if (typedModule != null) {
            val lookupLine = position.line + 1
            val lookupCol = position.character + 1
            val lookup = TypedAstLookup.findAtPosition(typedModule, lookupLine, lookupCol)
            when (lookup) {
                is TypedAstLookup.LookupResult.Expr -> {
                    val expr = lookup.expr
                    if (expr is com.mezze.language.typedast.TypedVar) {
                        // Variable reference — find where it was defined.
                        when (val binding = expr.binding) {
                            // Local let bindings in the current file: walk
                            // the typed module looking for `let <name> = …`.
                            is com.mezze.language.typedast.BindingRef.Local -> {
                                val defLoc = findLocalBindingLoc(typedModule, expr.name)
                                if (defLoc != null) return listOf(locToLocation(state.uri, defLoc))
                            }
                            // Cross-module / stdlib reference. The `hash` is
                            // the content hash of the imported decl. Look it
                            // up in the project store, resolve the module
                            // path to a file, and emit a Location at the
                            // decl's source range. Falls back to the import
                            // line if anything is missing (e.g. a fresh
                            // workspace with empty store).
                            is com.mezze.language.typedast.BindingRef.ByHash -> {
                                val target = findCrossModuleDefLocation(binding.hash)
                                if (target != null) return listOf(target)
                                val importLoc = findImportLoc(state, expr.name)
                                if (importLoc != null) return listOf(importLoc)
                            }
                            // Lambda param (`{x} -> x + 1`) and captures:
                            // both are name-based bindings inside the
                            // current file. The same searcher used for
                            // `Local` walks lambda params and match-pattern
                            // bindings, so we just reuse it.
                            is com.mezze.language.typedast.BindingRef.Param,
                            is com.mezze.language.typedast.BindingRef.Capture -> {
                                val defLoc = findLocalBindingLoc(typedModule, expr.name)
                                if (defLoc != null) return listOf(locToLocation(state.uri, defLoc))
                            }
                            // Native bindings have no source location.
                            is com.mezze.language.typedast.BindingRef.Native -> {}
                        }
                    }
                }
                is TypedAstLookup.LookupResult.Binding -> {
                    return listOf(locToLocation(state.uri, lookup.loc))
                }
                is TypedAstLookup.LookupResult.NotFound -> {}
            }
        }

        // 2. Fallback: search parsed module declarations by name
        val parsedModule = state.parsedModule
        if (parsedModule != null) {
            for (decl in parsedModule.decls) {
                val (name, loc) = when (decl) {
                    is ParsedLetDecl -> if (decl.isSimpleBinding) Pair(decl.variableName, decl.loc) else continue
                    is ParsedTypeDecl -> Pair(decl.name, decl.loc)
                    is ParsedAbilityDecl -> Pair(decl.name, decl.loc)
                    is ParsedEffectDecl -> Pair(decl.name, decl.loc)
                    else -> continue
                }
                if (name == word) return listOf(locToLocation(state.uri, loc))
            }
        }

        // 3. Check imports — try the actual decl in its source module
        // first (via store), fall back to the import line.
        if (result != null) {
            val import = result.resolvedImports.find { it.localName == word }
            if (import != null) {
                val target = findCrossModuleDefLocationByName(import.modulePath, import.originalName)
                if (target != null) return listOf(target)
                val importLoc = findImportLoc(state, word)
                if (importLoc != null) return listOf(importLoc)
            }
        }

        return emptyList()
    }

    /**
     * Translate an imported decl's content hash into a Location that
     * points at the decl's source range. Looks the hash up in the project
     * store (which has stdlib decls populated by StdlibPrecompiler) and
     * resolves the module path to a file the editor can open.
     */
    private fun findCrossModuleDefLocation(hash: com.mezze.language.typedast.Hash): Location? {
        val store = projectStore ?: return null
        val row = store.findDeclByHash(hash) ?: return null
        val uri = resolveModulePathToUri(row.modulePath) ?: return null
        return Location(uri, Range(
            Position(row.startLine - 1, row.startCol - 1),
            Position(row.endLine - 1, row.endCol)
        ))
    }

    /**
     * Same as findCrossModuleDefLocation but starting from a name+module
     * (used by the resolved-imports fallback when we don't have a hash
     * handy — e.g. for a `use` of a name we never type-checked a
     * reference to).
     */
    private fun findCrossModuleDefLocationByName(modulePath: String, declName: String): Location? {
        val store = projectStore ?: return null
        val row = store.getDeclsForModule(modulePath).firstOrNull { it.declName == declName } ?: return null
        val uri = resolveModulePathToUri(row.modulePath) ?: return null
        return Location(uri, Range(
            Position(row.startLine - 1, row.startCol - 1),
            Position(row.endLine - 1, row.endCol)
        ))
    }

    /**
     * Resolve a `::` module path to a `file://` URI the client can open.
     *
     * Search order:
     *   1. `~/.mezze/stdlib/std/.../foo.mz` — installed by gradle
     *      `installStdlibSources`. Stable across sessions.
     *   2. `<workspace>/stdlib/std/.../foo.mz` — checked-in stdlib (we
     *      ourselves are in this repo while developing the language).
     *   3. `<workspace>/src/.../foo.mz` and `<workspace>/.../foo.mz` for
     *      user-defined modules.
     *   4. JAR-extracted temp file via ModuleResolver.readStdlibFile —
     *      last resort. Each extraction is a fresh temp; the editor
     *      will still open it but the path won't survive a restart.
     *
     * Returns null when none of those resolve. Prefer the stable disk
     * paths so cmd-click stays consistent across sessions and so
     * find-references can show meaningful URIs.
     */
    private fun resolveModulePathToUri(modulePath: String): String? {
        val segments = modulePath.split("::")
        if (segments.isEmpty()) return null

        val candidates = mutableListOf<java.io.File>()

        val ws = server.getWorkspaceRoot()
        if (segments[0] == "std") {
            val rest = segments.drop(1).joinToString("/") + ".mz"
            // 1. ~/.mezze/stdlib/std/<rest>
            val home = System.getProperty("user.home")
            if (home != null) {
                candidates += java.io.File("$home/.mezze/stdlib/std/$rest")
            }
            // 2. <workspace>/stdlib/std/<rest>
            if (ws != null) {
                candidates += java.io.File(ws, "stdlib/std/$rest")
            }
        } else {
            val rel = segments.joinToString("/") + ".mz"
            if (ws != null) {
                candidates += java.io.File(ws, "src/$rel")
                candidates += java.io.File(ws, rel)
            }
        }

        for (f in candidates) {
            if (f.isFile) return f.toURI().toString()
        }

        // 4. Last resort: extract from the JAR. Each call writes a fresh
        // temp file — only useful when the install step didn't run.
        if (segments[0] == "std") {
            val resourcePath = "stdlib/std/" + segments.drop(1).joinToString("/") + ".mz"
            val txt = com.mezze.language.typechecker.ModuleResolver.readStdlibFile(resourcePath)
            if (txt != null) {
                val temp = java.nio.file.Files.createTempFile("mezze-stdlib-", ".mz")
                java.nio.file.Files.writeString(temp, txt)
                temp.toFile().deleteOnExit()
                return temp.toUri().toString()
            }
        }
        return null
    }

    /** Find the Loc of a local binding by name in the TypedModule. */
    private fun findLocalBindingLoc(module: com.mezze.language.typedast.TypedModule, name: String): Loc? {
        // Check top-level let bindings
        for (decl in module.decls) {
            if (decl is com.mezze.language.typedast.TypedLetDecl && decl.name == name) {
                return decl.loc
            }
        }
        // Search nested let bindings + lambda params + match pattern bindings
        for (decl in module.decls) {
            if (decl is com.mezze.language.typedast.TypedLetDecl) {
                val found = searchBindingLocInExpr(decl.value, name)
                if (found != null) return found
            }
        }
        return null
    }

    private fun searchBindingLocInExpr(expr: com.mezze.language.typedast.TypedExpr, name: String): Loc? {
        var found: Loc? = null
        fun visit(e: com.mezze.language.typedast.TypedExpr) {
            if (found != null) return
            when (e) {
                is com.mezze.language.typedast.TypedBlock -> {
                    for (stmt in e.statements) {
                        if (found != null) return
                        when (stmt) {
                            is com.mezze.language.typedast.TypedStatement.Let -> {
                                if (stmt.name == name) { found = stmt.loc; return }
                                visit(stmt.value)
                            }
                            is com.mezze.language.typedast.TypedStatement.Expr -> visit(stmt.expr)
                        }
                    }
                    if (found == null) visit(e.result)
                }
                is com.mezze.language.typedast.TypedLambda -> {
                    for (p in e.params) if (p.name == name) { found = p.loc; return }
                    visit(e.body)
                }
                is com.mezze.language.typedast.TypedIf -> { visit(e.condition); visit(e.thenBranch); visit(e.elseBranch) }
                is com.mezze.language.typedast.TypedMatch -> {
                    visit(e.scrutinee)
                    for (case in e.cases) {
                        for (b in case.bindings) if (b.name == name) { found = b.loc; return }
                        for (ilb in case.ifLetBindings) visit(ilb.expr)
                        case.guard?.let { visit(it) }
                        visit(case.body)
                    }
                }
                is com.mezze.language.typedast.TypedCall -> { visit(e.function); visit(e.arg) }
                is com.mezze.language.typedast.TypedRecord -> {
                    for (entry in e.entries) when (entry) {
                        is com.mezze.language.typedast.TypedRecordEntry.Field -> visit(entry.value)
                        is com.mezze.language.typedast.TypedRecordEntry.Spread -> visit(entry.expr)
                    }
                }
                is com.mezze.language.typedast.TypedFieldAccess -> visit(e.record)
                is com.mezze.language.typedast.TypedRecordUpdate -> {
                    visit(e.record)
                    for (entry in e.updates) when (entry) {
                        is com.mezze.language.typedast.TypedRecordEntry.Field -> visit(entry.value)
                        is com.mezze.language.typedast.TypedRecordEntry.Spread -> visit(entry.expr)
                    }
                }
                is com.mezze.language.typedast.TypedVariantConstruct -> e.payload?.let { visit(it) }
                is com.mezze.language.typedast.TypedSeq -> {
                    for (el in e.elements) when (el) {
                        is com.mezze.language.typedast.TypedSeqElement.Regular -> visit(el.expr)
                        is com.mezze.language.typedast.TypedSeqElement.Spread -> visit(el.expr)
                    }
                }
                is com.mezze.language.typedast.TypedPerform -> {
                    for (h in e.handlers) h.params?.let { visit(it) }
                    visit(e.body)
                }
                is com.mezze.language.typedast.TypedPerformOp -> visit(e.arg)
                is com.mezze.language.typedast.TypedPipe -> { visit(e.left); visit(e.right) }
                else -> { /* leaves */ }
            }
        }
        visit(expr)
        return found
    }

    /** Find the Location of an imported symbol (points to the use declaration or source file). */
    private fun findImportLoc(state: DocumentState, name: String): Location? {
        val parsedModule = state.parsedModule ?: return null
        // Find the use declaration that imports this name
        for (decl in parsedModule.decls) {
            when (decl) {
                is ParsedUseDecl -> {
                    val path = decl.path
                    if (path is ParsedUsePath.Selective) {
                        if (path.items.any { it.name == name || it.alias == name }) {
                            return locToLocation(state.uri, decl.loc)
                        }
                    }
                }
                else -> {}
            }
        }
        return null
    }

    /** Convert a Loc to an LSP Location. */
    private fun locToLocation(uri: String, loc: Loc): Location {
        return Location(uri, Range(
            Position(loc.startLine - 1, loc.startCol - 1),
            Position(loc.endLine - 1, loc.endCol)
        ))
    }

    // ========== Completion ==========

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            val state = documents[uri]

            server.logInfo("[completion] request uri=${uri.substringAfterLast('/')} pos=${position.line}:${position.character} trigger='${params.context?.triggerCharacter}'")

            val items = mutableListOf<CompletionItem>()

            // Check if we're after a dot (method completion: x.|)
            val dotCompletions = if (state != null) {
                getDotCompletions(state, position)
            } else {
                emptyList()
            }

            if (dotCompletions.isNotEmpty()) {
                items.addAll(dotCompletions)
                Either.forLeft<List<CompletionItem>, CompletionList>(items)
            }
            // Check if we're inside a match expression (after 'match X is')
            else if (state != null && getMatchPatternCompletions(state, position).let { matchCompletions ->
                if (matchCompletions.isNotEmpty()) { items.addAll(matchCompletions); true } else false
            }) {
                Either.forLeft<List<CompletionItem>, CompletionList>(items)
            } else {
                // Check if we're inside a function call (after '{')
                val argCompletions = if (state != null) {
                    getFunctionArgumentCompletions(state, position)
                } else {
                    emptyList()
                }

                if (argCompletions.isNotEmpty()) {
                    // If we're completing function arguments, prioritize those
                    items.addAll(argCompletions)
                } else {
                    // Standard completions
                    // Add keywords
                    items.addAll(getKeywordCompletions())

                    // Add bindings from current document (top-level + imports)
                    if (state != null) {
                        items.addAll(getBindingCompletions(state))

                        // Add local variables in scope at this position
                        items.addAll(getLocalVariableCompletions(state, position))

                        // Add variant constructors from visible types
                        items.addAll(getVariantConstructorCompletions(state))
                    }

                    // Add stdlib suggestions
                    items.addAll(getStdlibCompletions())
                }

                Either.forLeft(items)
            }
        }
    }

    /**
     * Get function argument completions when inside a record-style function call.
     * E.g., `my_func { |` should complete with field names like `name = `, `age = `
     */
    private fun getFunctionArgumentCompletions(state: DocumentState, position: Position): List<CompletionItem> {
        val lines = state.content.lines()
        if (position.line >= lines.size) return emptyList()

        val line = lines[position.line]
        val beforeCursor = line.substring(0, minOf(position.character, line.length))

        // Look for pattern: `identifier {` before cursor
        // We need to find the function being called
        val funcCallMatch = findFunctionCallContext(beforeCursor, lines, position.line)
            ?: return emptyList()

        val funcName = funcCallMatch.functionName
        val result = state.typeCheckResult ?: return emptyList()

        // Find the function's type
        val funcType = findFunctionType(funcName, result) ?: return emptyList()

        // If it's a function with a record parameter, extract the field names
        val recordFields = extractRecordParameterFields(funcType)
        if (recordFields.isEmpty()) return emptyList()

        val items = mutableListOf<CompletionItem>()

        // First item: complete ALL fields at once (most useful). For
        // function-typed fields, render a lambda skeleton with the inner
        // record's field names as the lambda's params (e.g.
        // `p: {x: 'a} -> Bool` becomes `${1:{x} -> $0}`).
        val allFieldsSnippet = recordFields.mapIndexed { index, (fieldName, fieldType) ->
            val placeholder = renderFieldPlaceholder(fieldName, fieldType, index + 1)
            "$fieldName = $placeholder"
        }.joinToString(", ")

        val allFieldsPreview = recordFields.joinToString(", ") { (name, _) -> "$name = ..." }

        items.add(CompletionItem("Fill all fields").apply {
            kind = CompletionItemKind.Snippet
            detail = allFieldsPreview
            insertText = allFieldsSnippet
            insertTextFormat = InsertTextFormat.Snippet
            sortText = "0"  // Show first
        })

        // Also add individual field completions
        recordFields.forEachIndexed { index, (fieldName, fieldType) ->
            val typeStr = prettyPrintTypeWithAliases(fieldType, state.typeAliases)
            items.add(CompletionItem(fieldName).apply {
                kind = CompletionItemKind.Field
                detail = typeStr
                insertText = "$fieldName = "
                sortText = "1_${index.toString().padStart(3, '0')}"
            })

            // If the field type is a variant, also suggest "fieldName = Constructor" shortcuts
            val variantCompletions = getVariantCompletionsForFieldType(fieldType, fieldName, state, index)
            items.addAll(variantCompletions)
        }

        return items
    }

    /**
     * Get variant constructor completions for a specific field in a function call.
     * E.g., for `my_func { status = | }`, if status is type `| Active, Inactive |`,
     * suggest just the constructor name (not `status = Constructor` since user already typed `status = `).
     */
    private fun getVariantCompletionsForFieldType(
        fieldType: MezzeType,
        fieldName: String,
        state: DocumentState,
        fieldIndex: Int
    ): List<CompletionItem> {
        val variantType = when (fieldType) {
            is MezzeType.VariantType -> fieldType
            is MezzeType.TypeApplication -> findVariantTypeFromApplication(fieldType, state)
            else -> null
        } ?: return emptyList()

        // Just suggest the constructor names - when user types "field = " and triggers completion,
        // they want the value, not "field = value" again
        return variantType.constructors.entries.mapIndexed { constructorIndex, (constructorName, payload) ->
            CompletionItem(constructorName).apply {
                kind = CompletionItemKind.EnumMember
                sortText = "2_${fieldIndex.toString().padStart(3, '0')}_${constructorIndex.toString().padStart(3, '0')}"
                detail = "$fieldName: ${prettyPrintTypeWithAliases(fieldType, state.typeAliases)}"

                if (payload != null) {
                    // Constructor has payload
                    val payloadStr = when (payload) {
                        is MezzeType.RecordType -> {
                            val fields = payload.fields.keys.mapIndexed { i, field ->
                                "$field = \${${i + 1}:}"
                            }.joinToString(", ")
                            " { $fields }"
                        }
                        else -> " { \${1:value} }"
                    }
                    insertText = "$constructorName$payloadStr"
                    insertTextFormat = InsertTextFormat.Snippet
                } else {
                    insertText = constructorName
                }
            }
        }
    }

    /**
     * Find the function being called in a record-style call context.
     */
    private fun findFunctionCallContext(beforeCursor: String, lines: List<String>, currentLine: Int): FunctionCallContext? {
        // Simple pattern: look for `identifier {` before the cursor
        // This can be on the same line or previous lines

        // First, check if we're inside braces by counting
        var braceDepth = 0
        var searchText = beforeCursor

        // If we don't have an open brace on this line, look at previous lines
        for (i in currentLine downTo 0) {
            val line = if (i == currentLine) beforeCursor else lines[i]
            for (char in line.reversed()) {
                when (char) {
                    '}' -> braceDepth++
                    '{' -> {
                        braceDepth--
                        if (braceDepth < 0) {
                            // Found the opening brace, now find the identifier before it
                            val upToBrace = if (i == currentLine) {
                                line.substring(0, line.lastIndexOf('{'))
                            } else {
                                lines.subList(0, i + 1).joinToString("\n").substringBeforeLast('{')
                            }
                            val funcName = extractIdentifierBefore(upToBrace)
                            if (funcName != null) {
                                return FunctionCallContext(funcName)
                            }
                            return null
                        }
                    }
                }
            }
        }
        return null
    }

    private data class FunctionCallContext(val functionName: String)

    /**
     * Extract the identifier immediately before a position (skipping whitespace).
     */
    private fun extractIdentifierBefore(text: String): String? {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return null

        var end = trimmed.length
        var start = end

        // Find end of identifier
        while (start > 0 && isIdentifierChar(trimmed[start - 1])) {
            start--
        }

        return if (start < end) trimmed.substring(start, end) else null
    }

    /**
     * Find the type of a function by name.
     */
    private fun findFunctionType(funcName: String, result: TypeCheckResult): MezzeType? {
        // Check local bindings via TypedModule
        val typedBinding = result.typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.find { it.name == funcName }

        if (typedBinding != null) {
            val bindingType = typedBinding.scheme.type
            if (bindingType !is MezzeType.VarType) return bindingType
        }

        // Check imports
        val import = result.resolvedImports.find { it.localName == funcName }
        if (import != null) {
            return import.type
        }

        return null
    }

    /**
     * Extract record field names and types from a function's parameter type.
     */
    private fun extractRecordParameterFields(funcType: MezzeType): List<Pair<String, MezzeType>> {
        // Function type is { paramType } -> returnType <effects>
        val recordType = when (funcType) {
            is MezzeType.FunctionType -> funcType.paramType
            else -> return emptyList()
        }

        // The parameter should be a record type
        return when (recordType) {
            is MezzeType.RecordType -> {
                recordType.fields.map { (name, type) -> name to type }
            }
            else -> emptyList()
        }
    }

    /**
     * Build the LSP snippet body for a method call given its name and the
     * field-name-with-shape-hint list. Produces `name {field1 = ${1:hint1},
     * field2 = ${2:hint2}}`. For function-typed fields, the placeholder is a
     * lambda skeleton (`{x} -> $0` or `{x, y} -> $0` if the function's param
     * record names are known).
     *
     * Pass `paramNames` already stripped of `it` for method-call shape; for
     * regular function calls, pass all field names.
     *
     * If `paramNames` is empty, returns just `name {}`.
     */
    internal fun buildMethodSnippet(
        methodName: String,
        paramNames: List<MethodParamHint>
    ): String {
        if (paramNames.isEmpty()) return "$methodName {}"
        val body = paramNames.mapIndexed { idx, hint ->
            val placeholder = when (hint) {
                is MethodParamHint.Plain -> "\${${idx + 1}:${hint.name}}"
                is MethodParamHint.Callback -> {
                    val params = hint.callbackParams.joinToString(", ")
                    "\${${idx + 1}:{$params} -> $0}"
                }
            }
            "${hint.name} = $placeholder"
        }.joinToString(", ")
        return "$methodName {$body}"
    }

    /**
     * Build the snippet placeholder for a single field given its name +
     * inferred MezzeType. Used by `getFunctionArgumentCompletions` to render
     * the right body for "Fill all fields". Function-typed fields produce a
     * lambda skeleton placeholder; everything else gets a named placeholder.
     */
    private fun renderFieldPlaceholder(fieldName: String, fieldType: MezzeType, idx: Int): String {
        val fnType = fieldType as? MezzeType.FunctionType ?: return "\${${idx}:${fieldName}}"
        val innerParams = (fnType.paramType as? MezzeType.RecordType)
            ?.fields?.keys?.toList()
            ?: listOf("x")
        return "\${${idx}:{${innerParams.joinToString(", ")}} -> $0}"
    }

    /** Hint about a method parameter for snippet placeholder rendering. */
    internal sealed interface MethodParamHint {
        val name: String
        data class Plain(override val name: String) : MethodParamHint
        /** Field is a function — emit `{p1, p2} -> $0` placeholder.
         *  callbackParams are the inner lambda's param names (e.g. `["x"]`
         *  for a predicate `{x: 'a} -> Bool`). */
        data class Callback(override val name: String, val callbackParams: List<String>) : MethodParamHint
    }

    /**
     * Build param hints from a parsed type annotation (for ability ops where
     * paramType is a record annotation). Returns a list excluding `it` if
     * `stripIt = true` (method-call shape).
     */
    internal fun paramHintsFromAnnotation(
        paramType: ParsedTypeAnnotation?,
        stripIt: Boolean = true
    ): List<MethodParamHint> {
        val record = paramType as? com.mezze.language.parsedast.ParsedRecordType ?: return emptyList()
        return record.entries
            .filter { !stripIt || it.name != "it" }
            .map { entry ->
                val fnType = entry.type as? com.mezze.language.parsedast.ParsedFunctionType
                if (fnType != null) {
                    val innerParams = (fnType.paramType as? com.mezze.language.parsedast.ParsedRecordType)
                        ?.entries?.map { it.name }
                        ?: listOf("x")
                    MethodParamHint.Callback(entry.name, innerParams)
                } else {
                    MethodParamHint.Plain(entry.name)
                }
            }
    }

    /**
     * Build param hints from a typed MezzeType (for the unimported-ability
     * step that deserializes TypedAbilityDecl). Same shape rules.
     */
    internal fun paramHintsFromMezzeType(
        paramType: MezzeType,
        stripIt: Boolean = true
    ): List<MethodParamHint> {
        val record = paramType as? MezzeType.RecordType ?: return emptyList()
        return record.fields
            .filter { (name, _) -> !stripIt || name != "it" }
            .map { (name, type) ->
                val fnType = type as? MezzeType.FunctionType
                if (fnType != null) {
                    val innerParams = (fnType.paramType as? MezzeType.RecordType)
                        ?.fields?.keys?.toList()
                        ?: listOf("x")
                    MethodParamHint.Callback(name, innerParams)
                } else {
                    MethodParamHint.Plain(name)
                }
            }
    }

    /**
     * Look up an inherent impl operation's post-solve param-record type
     * from PrecompiledStdlib's TypedAbilityImpls. Match the impl by its
     * target-type-name (using this file's existing getTypeName helper) and
     * read the operation lambda's typed params — for callback fields, the
     * function-typed param's inner record names give us the real lambda
     * skeleton (`{x} -> $0`, `{acc, x} -> $0`, etc.) without convention.
     */
    private fun lookupTypedImplOpParamType(
        precompiled: com.mezze.language.typechecker.PrecompiledStdlib,
        targetTypeName: String,
        opName: String
    ): MezzeType? {
        for (impl in precompiled.stdlibAbilityImpls) {
            val target = impl.targetTypes.firstOrNull() ?: continue
            if (getTypeName(target) != targetTypeName) continue

            val opBody = impl.operations[opName] ?: continue
            val lambda = opBody as? com.mezze.language.typedast.TypedLambda ?: continue
            // Each TypedParam carries its solved type. Build a record so
            // callers can apply the standard paramHintsFromMezzeType path.
            val fields = lambda.params.associate { it.name to it.type }
            return MezzeType.RecordType(fields = fields, rowVar = null)
        }
        return null
    }

    /**
     * Build hints from a ParsedImplOp by inspecting the implementation
     * lambda's params. Inherent impls in stdlib usually omit explicit param
     * types, but the lambda `{ it, p } -> ...` still names them. We strip
     * `it` for method-call shape; types fall back to Plain (we can't know
     * if `p` is a callback without the inferred type).
     *
     * For callback inference inside inherent impls, prefer the matching
     * ability op's annotation when available — handled by callers.
     */
    private fun paramHintsFromImplOpLambda(op: ParsedImplOp): List<MethodParamHint> {
        val lambda = op.implementation as? com.mezze.language.parsedast.ParsedLambda ?: return emptyList()
        val names = when (val p = lambda.params) {
            is com.mezze.language.parsedast.ParsedLambdaParams.Untyped -> p.names
            is com.mezze.language.parsedast.ParsedLambdaParams.Typed -> p.fields.map { it.name }
        }
        // No type info — every field gets a Plain placeholder. Callers that
        // have access to a typed methodType (e.g. via PrecompiledStdlib's
        // TypedAbilityImpl) should layer over via paramHintsFromMezzeType,
        // which produces real Callback hints with the inner lambda's actual
        // param names from the function-typed field.
        return names.filter { it != "it" }.map { MethodParamHint.Plain(it) }
    }

    /**
     * Get dot-completions: when user types `x.`, suggest methods available on x's type.
     * Includes ability methods (eq, neq, lt, add, ...) and inherent methods.
     */
    private fun getDotCompletions(state: DocumentState, position: Position): List<CompletionItem> {
        val lines = state.content.lines()
        if (position.line >= lines.size) return emptyList()

        val line = lines[position.line]
        val beforeCursor = line.substring(0, minOf(position.character, line.length))
        // Log each step of dot-completion to window/logMessage so the
        // user can paste the LSP output channel for diagnosis when
        // completions look wrong.
        server.logInfo("[dot-completion] pos=${position.line}:${position.character} beforeCursor=${beforeCursor.takeLast(60).let { "...$it" }}")

        // Two cases for the receiver expression:
        //
        //   1. Plain identifier: `xs.` — match `(\w+)\.(\w*)$`. Look up the
        //      identifier in the typed module's binding scope.
        //
        //   2. Arbitrary expression: `[1,2,3].`, `"hi".`, `(expr).`, `xs[0].`,
        //      `nums.first {}.` — scan back from the dot to find the
        //      receiver-expression boundary (matched by bracket depth), then
        //      ask the typed AST what type lives just before the dot via a
        //      position-based lookup. This piggybacks on the same
        //      `TypedAstLookup.findAtPosition` machinery hover already uses,
        //      so any expression Mezze can typecheck is also dot-completable.
        val identifierMatch = Regex("""(\w+)\.(\w*)$""").find(beforeCursor)
        val partialMethod: String
        val receiverName: String?
        if (identifierMatch != null) {
            receiverName = identifierMatch.groupValues[1]
            partialMethod = identifierMatch.groupValues[2]
            server.logInfo("[dot-completion] path=identifier receiver='$receiverName' partial='$partialMethod'")
        } else {
            // Expression-receiver path: confirm there's a dot somewhere right
            // before the cursor, optionally followed by a partial method
            // identifier.
            val exprDotMatch = Regex("""\.(\w*)$""").find(beforeCursor) ?: run {
                server.logInfo("[dot-completion] no dot match — returning empty")
                return emptyList()
            }
            receiverName = null
            partialMethod = exprDotMatch.groupValues[1]
            server.logInfo("[dot-completion] path=expression partial='$partialMethod'")
        }

        // Use current type check result, or fall back to last successful one
        val result = state.typeCheckResult
            ?: lastSuccessfulResult[state.uri]
            ?: run {
                server.logInfo("[dot-completion] no typeCheckResult — returning empty")
                return emptyList()
            }
        server.logInfo("[dot-completion] typeCheckResult=${if (state.typeCheckResult != null) "current" else "lastSuccessful"}")

        fun formatType(type: MezzeType): String {
            return prettyPrintTypeWithAliases(type, state.typeAliases)
        }

        // Find the receiver's type — either by binding-scope lookup (named
        // identifier) or by typed-AST position lookup (any expression).
        val receiverType = if (receiverName != null) {
            findReceiverType(receiverName, result, position)
        } else {
            findReceiverTypeForExpressionAt(state, position, result)
        } ?: run {
            server.logInfo("[dot-completion] receiver type lookup returned null — returning empty")
            return emptyList()
        }
        // Unwrap SuspendedType
        val baseType = when (receiverType) {
            is MezzeType.SuspendedType -> receiverType.valueType
            else -> receiverType
        }
        server.logInfo("[dot-completion] receiverType=$baseType (typeName=${getTypeName(baseType) ?: "<none>"})")

        val items = mutableListOf<CompletionItem>()
        val typeName = getTypeName(baseType)
        val addedMethods = mutableSetOf<String>()  // Deduplicate

        // 1. Collect ability methods available for this type
        // Use both imported abilities (from prelude) and local program abilities
        val localAbilities = state.parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>() ?: emptyList()
        val allAbilities = result.importedAbilities.values + localAbilities

        // Dot-completion rules (kept deliberately narrow so the list stays
        // useful — see issue #219):
        //
        //   - If the receiver is a record  → show ONLY its fields.
        //   - Otherwise                    → show ability methods + inherent
        //                                     impl methods that target this
        //                                     receiver type.
        //
        // We deliberately do NOT mix in `seq::length`-style qualified-import
        // bindings, "(auto-import)" suggestions, keyword fallbacks, etc. The
        // user just wants "what can I call on `x.`".
        if (baseType is MezzeType.RecordType) {
            server.logInfo("[dot-completion] record receiver: fields=${baseType.fields.keys}")
            for ((fieldName, fieldType) in baseType.fields) {
                items.add(CompletionItem(fieldName).apply {
                    kind = CompletionItemKind.Field
                    detail = formatType(fieldType)
                })
            }
            val final = if (partialMethod.isNotEmpty()) {
                items.filter { it.label.startsWith(partialMethod, ignoreCase = true) }
            } else items
            server.logInfo("[dot-completion] returning ${final.size} field completions: ${final.map { it.label }}")
            return final
        }

        val targetTypeName = getTypeName(baseType)

        // 1. Ability methods whose ability is in scope (imported or local) AND
        //    whose impl exists for this receiver type.
        for (abilityDecl in allAbilities) {
            if (!isBuiltinAbilityForType(abilityDecl.name, baseType)) continue
            for (op in abilityDecl.operations) {
                if (op.name in addedMethods) continue
                val paramStr = formatTypeAnnotation(op.paramType)
                if (!paramStr.contains("it")) continue
                val hints = paramHintsFromAnnotation(op.paramType, stripIt = true)
                items.add(CompletionItem(op.name).apply {
                    kind = CompletionItemKind.Method
                    detail = "${abilityDecl.name}.${op.name}"
                    documentation = Either.forLeft("${formatTypeAnnotation(op.paramType)} -> ${formatTypeAnnotation(op.returnType)}")
                    insertText = buildMethodSnippet(op.name, hints)
                    insertTextFormat = InsertTextFormat.Snippet
                })
                addedMethods.add(op.name)
            }
        }

        // 2. Inherent methods from the local file's `impl Type where { ... }`.
        val parsedImpls = state.parsedModule?.decls?.filterIsInstance<ParsedImplAbilityDecl>() ?: emptyList()
        for (impl in parsedImpls) {
            if (impl.target !is ParsedImplTarget.Inherent) continue
            val implTarget = impl.targetTypes.firstOrNull()?.let { ann ->
                when (ann) {
                    is com.mezze.language.parsedast.ParsedNamedType -> ann.name
                    is com.mezze.language.parsedast.ParsedTypeApp -> ann.constructor
                    else -> null
                }
            } ?: continue
            if (targetTypeName != null && implTarget != targetTypeName) continue
            for (op in impl.operations) {
                if (op.name in addedMethods) continue
                // Param hints: prefer explicit op.paramType annotation, fall
                // back to lambda-param names from the implementation body.
                val hints = paramHintsFromAnnotation(op.paramType, stripIt = true)
                    .ifEmpty { paramHintsFromImplOpLambda(op) }
                items.add(CompletionItem(op.name).apply {
                    kind = CompletionItemKind.Method
                    detail = "$implTarget.${op.name}"
                    insertText = buildMethodSnippet(op.name, hints)
                    insertTextFormat = InsertTextFormat.Snippet
                })
                addedMethods.add(op.name)
            }
        }

        // 3. Inherent methods from the prelude (`impl Seq 'a where { ... }`,
        //    `impl Str where { ... }`, etc.). These match the receiver type
        //    by name. Ability impls in the prelude are skipped here — only
        //    inherent impls appear, since ability methods are already covered
        //    by step 1 (when their ability decl is in scope).
        try {
            val precompiledForInherent = com.mezze.language.typechecker.PrecompiledStdlib
            for ((_, impl) in precompiledForInherent.preludeImportContext.importedAbilityImpls) {
                if (!impl.isInherent) continue
                val implTargetName = impl.targetTypes.firstOrNull()?.let { ann ->
                    when (ann) {
                        is com.mezze.language.parsedast.ParsedNamedType -> ann.name
                        is com.mezze.language.parsedast.ParsedTypeApp -> ann.constructor
                        else -> null
                    }
                } ?: continue
                if (targetTypeName != null && implTargetName != targetTypeName) continue
                for (op in impl.operations) {
                    if (op.name in addedMethods) continue
                    // Prefer the post-solve typed impl op's param type — it
                    // has callback functions fully typed (with inner record
                    // field names), letting buildMethodSnippet emit a real
                    // lambda skeleton like `{x} -> $0` instead of a bare
                    // placeholder. Falls back to the parsed annotation, then
                    // to lambda-name extraction.
                    val typedParamType = lookupTypedImplOpParamType(
                        precompiledForInherent, implTargetName, op.name
                    )
                    val hints = when {
                        typedParamType != null -> paramHintsFromMezzeType(typedParamType, stripIt = true)
                        else -> paramHintsFromAnnotation(op.paramType, stripIt = true)
                            .ifEmpty { paramHintsFromImplOpLambda(op) }
                    }
                    items.add(CompletionItem(op.name).apply {
                        kind = CompletionItemKind.Method
                        detail = "$implTargetName.${op.name}"
                        insertText = buildMethodSnippet(op.name, hints)
                        insertTextFormat = InsertTextFormat.Snippet
                    })
                    addedMethods.add(op.name)
                }
            }
        } catch (_: Exception) { /* prelude may not be loaded in tests */ }

        // 4. Unimported ability methods — walk ability impls across the whole
        //    project (incl. stdlib + user code) and surface their methods when
        //    the impl's target type matches the receiver. Each suggestion gets
        //    `additionalTextEdits` to insert `use <module>::{<Ability>}` on
        //    accept.
        //
        //    We deserialize ability_impl blobs to read structured `targetTypes`
        //    (the AbilityImplRow.targetType string is MezzeType.toString() and
        //    not stable for primitives — see #214). Cap the work per request
        //    via early type-name pre-filter.
        try {
            val store = projectStore
            if (store != null && targetTypeName != null) {
                val importedAbilityNames = result.importedAbilities.keys + localAbilities.map { it.name }.toSet()

                // Index abilities + impls once per dot-completion request.
                val abilityDecls = store.getDeclsByKind(com.mezze.language.codebase.DeclKind.ABILITY)
                val abilityModuleByHash = abilityDecls.associate { it.hash to it.modulePath }
                val abilityNameByHash = abilityDecls.associate { it.hash to it.declName }

                val implDecls = store.getDeclsByKind(com.mezze.language.codebase.DeclKind.IMPL)
                var step4Added = 0
                for (implRow in implDecls) {
                    val implBlob = store.getBlob(implRow.hash) ?: continue
                    if (implBlob.kind != com.mezze.language.codebase.BlobKind.ABILITY_IMPL) continue
                    val implTyped = try {
                        val bytes = implBlob.typed ?: continue
                        com.mezze.language.codebase.CodebaseSerialization.deserializeTypedAbilityImpl(bytes)
                    } catch (_: Exception) { continue }

                    val abilityName = abilityNameByHash[implTyped.abilityHash] ?: continue
                    if (abilityName in importedAbilityNames) continue   // already covered by step 1
                    val abilityModule = abilityModuleByHash[implTyped.abilityHash] ?: continue

                    // Target-type filter: receiver's type-constructor name must
                    // match the impl's first target type.
                    val target = implTyped.targetTypes.firstOrNull() ?: continue
                    val implTargetName = getTypeName(target) ?: continue
                    if (implTargetName != targetTypeName) continue

                    // Look up the ability decl to learn its operations.
                    val abilityHashRow = abilityDecls.firstOrNull { it.hash == implTyped.abilityHash } ?: continue
                    val abilityBlob = store.getBlob(abilityHashRow.hash) ?: continue
                    val abilityDecl = try {
                        val bytes = abilityBlob.typed ?: continue
                        com.mezze.language.codebase.CodebaseSerialization.deserializeTypedAbilityDecl(bytes)
                    } catch (_: Exception) { continue }

                    for (op in abilityDecl.operations) {
                        if (op.name in addedMethods) continue
                        val hints = paramHintsFromMezzeType(op.paramType, stripIt = true)
                        val item = CompletionItem(op.name).apply {
                            kind = CompletionItemKind.Method
                            detail = "$abilityName.${op.name}  (auto-import $abilityModule)"
                            insertText = buildMethodSnippet(op.name, hints)
                            insertTextFormat = InsertTextFormat.Snippet
                            sortText = "z_${op.name}"  // sort below already-imported methods
                        }
                        val edit = buildAutoImportEdit(state, abilityModule, abilityName)
                        if (edit != null) item.additionalTextEdits = listOf(edit)
                        items.add(item)
                        addedMethods.add(op.name)
                        step4Added++
                    }
                }
                server.logInfo("[dot-completion] step4 added=$step4Added unimported-ability methods")
            }
        } catch (e: Exception) {
            server.logInfo("[dot-completion] unimported-ability scan failed: ${e.message}")
        }

        // Filter by partial method name if the user already started typing
        val final = if (partialMethod.isNotEmpty()) {
            items.filter { it.label.startsWith(partialMethod, ignoreCase = true) }
        } else {
            items
        }
        server.logInfo("[dot-completion] returning ${final.size} method completions for type '${getTypeName(baseType) ?: baseType::class.simpleName}': ${final.take(20).map { it.label }}${if (final.size > 20) "..." else ""}")
        return final
    }

    /** Check if a MezzeType matches a TypedAST type (simplified matching). */
    private fun matchesType(mezzeType: MezzeType, typedType: MezzeType): Boolean {
        return when {
            mezzeType is MezzeType.IntType && typedType is MezzeType.IntType -> true
            mezzeType is MezzeType.FloatType && typedType is MezzeType.FloatType -> true
            mezzeType is MezzeType.StringType && typedType is MezzeType.StringType -> true
            mezzeType is MezzeType.BoolType && typedType is MezzeType.BoolType -> true
            mezzeType is MezzeType.NatType && typedType is MezzeType.NatType -> true
            mezzeType is MezzeType.CharType && typedType is MezzeType.CharType -> true
            mezzeType is MezzeType.SeqType && typedType is MezzeType.SeqType -> true
            mezzeType is MezzeType.ListType && typedType is MezzeType.ListType -> true
            mezzeType is MezzeType.VarType || typedType is MezzeType.VarType -> true // polymorphic — always matches
            else -> false
        }
    }

    /** Get the simple type name for ability matching. */
    private fun getTypeName(type: MezzeType): String? {
        return when (type) {
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "Str"
            is MezzeType.BoolType -> "Bool"
            is MezzeType.NatType -> "Nat"
            is MezzeType.FracType -> "Frac"
            is MezzeType.CharType -> "Char"
            is MezzeType.BytesType -> "Bytes"
            is MezzeType.ListType -> "List"
            is MezzeType.SeqType -> "Seq"
            is MezzeType.SetType -> "Set"
            is MezzeType.SortedSetType -> "SortedSet"
            is MezzeType.MapType -> "Map"
            is MezzeType.SortedMapType -> "SortedMap"
            is MezzeType.TypeApplication -> type.constructor
            is MezzeType.VariantType -> type.constructors.keys.firstOrNull()
            else -> null
        }
    }

    /** Check if a type has automatic builtin implementation for an ability. */
    private fun isBuiltinAbilityForType(abilityName: String, type: MezzeType): Boolean {
        return com.mezze.language.ast.BuiltinAbilities.typeHasAutomaticImplementation(type, abilityName)
    }


    /** Find the type of a receiver identifier at a given position. */
    private fun findReceiverType(name: String, result: TypeCheckResult, position: Position): MezzeType? {
        // Check top-level bindings via TypedModule
        val typedBinding = result.typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.find { it.name == name }
        if (typedBinding != null) {
            val bindingType = typedBinding.scheme.type
            if (bindingType !is MezzeType.VarType) return bindingType
        }

        // Check resolved imports
        val resolvedImport = result.resolvedImports.find { it.localName == name }
        if (resolvedImport != null) return resolvedImport.type

        // Check local bindings
        return findLocalBindingType(name, result.typedModule, position.line + 1)
    }

    /**
     * Find the receiver type for an arbitrary expression-receiver dot
     * completion (`[1,2,3].`, `"hi".`, `(x + y).`, `xs[0].`, `nums.first {}.`).
     *
     * Strategy:
     *   1. Try the typed AST (existing or last successful) — works when the
     *      document typechecks at least up to the receiver.
     *   2. Fall back to a syntactic shape match for the common literal forms
     *      (seq, str, int, float, bool, char). This covers the case where
     *      the file is mid-edit and not parseable yet — completion still
     *      lights up immediately for `[1,2,3].`.
     */
    private fun findReceiverTypeForExpressionAt(
        state: DocumentState,
        position: Position,
        result: TypeCheckResult
    ): MezzeType? {
        val lines = state.content.lines()
        if (position.line >= lines.size) return null
        val line = lines[position.line]
        val beforeCursor = line.substring(0, minOf(position.character, line.length))
        val dotIdx = beforeCursor.lastIndexOf('.')
        if (dotIdx < 0) return null

        // 1. Typed-AST lookup at column-just-before-the-dot.
        val typedModule = result.typedModule ?: lastSuccessfulResult[state.uri]?.typedModule
        if (typedModule != null) {
            val charBeforeDot = dotIdx - 1
            if (charBeforeDot >= 0) {
                val lookupLine = position.line + 1
                val lookupCol = charBeforeDot + 1
                val lookup = TypedAstLookup.findAtPosition(typedModule, lookupLine, lookupCol)
                if (lookup is TypedAstLookup.LookupResult.Expr) {
                    val t = lookup.expr.type
                    if (t !is MezzeType.VarType) return t
                }
            }
        }

        // 2. Syntactic fallback for literal receivers. Walk back from the dot
        // matching balanced delimiters; the resulting prefix is the receiver
        // text. We don't try to parse it — just classify by what it starts
        // with. Keeps completion responsive while the user is mid-edit.
        val receiverText = scanReceiverExpression(beforeCursor, dotIdx) ?: return null
        return classifyLiteralType(receiverText)
    }

    /**
     * Walk backwards from `dotIdx` matching `[]`, `()`, `{}`, and `""`/'`'`
     * pairs to find where the receiver expression begins. Returns the
     * receiver text (everything from the start of the expression up to but
     * not including the dot), or null if we can't find a clean boundary.
     */
    private fun scanReceiverExpression(line: String, dotIdx: Int): String? {
        var i = dotIdx - 1
        // Skip whitespace immediately before the dot (multi-line chains like
        // `nums\n  .first` aren't handled here — that's the typed-AST path).
        while (i >= 0 && line[i].isWhitespace()) i--
        if (i < 0) return null

        // If the receiver ends with a closing bracket, walk back to the match.
        val ch = line[i]
        if (ch == ']' || ch == ')' || ch == '}') {
            val open = when (ch) { ']' -> '['; ')' -> '('; '}' -> '{'; else -> return null }
            var depth = 1
            i--
            while (i >= 0 && depth > 0) {
                when (line[i]) {
                    ch -> depth++
                    open -> depth--
                }
                if (depth == 0) break
                i--
            }
            if (depth != 0 || i < 0) return null
            return line.substring(i, dotIdx).trim()
        }
        // String literal ending in `"` — scan back to the matching `"`.
        if (ch == '"') {
            var j = i - 1
            while (j >= 0 && line[j] != '"') j--
            if (j < 0) return null
            return line.substring(j, dotIdx).trim()
        }
        // Identifier or number — walk back over word chars / digits / dots.
        var j = i
        while (j >= 0 && (line[j].isLetterOrDigit() || line[j] == '_')) j--
        return line.substring(j + 1, dotIdx).trim()
    }

    /**
     * Classify a receiver text by syntactic shape. Used as the offline
     * fallback when the typed AST hasn't caught up to the user's edit.
     * Only handles the common literal cases — anything more complex falls
     * out of dot-completion (back to keyword completions, same as before).
     */
    private fun classifyLiteralType(receiverText: String): MezzeType? {
        if (receiverText.isEmpty()) return null
        val first = receiverText.first()
        return when {
            first == '[' && receiverText.endsWith("]") -> {
                // Seq literal: [...]. Without parsing, we can't know the
                // element type — synthesize SeqType with a fresh TypeVar so
                // dispatch picks Seq's inherent methods and the call site's
                // unification figures out the element later.
                MezzeType.SeqType(MezzeType.VarType(com.mezze.language.ast.TypeVar.fresh()))
            }
            first == '"' && receiverText.endsWith("\"") -> MezzeType.StringType
            first == '\'' && receiverText.endsWith("'") -> MezzeType.CharType
            first.isDigit() || (first == '-' && receiverText.length > 1 && receiverText[1].isDigit()) -> {
                if (receiverText.contains('.')) MezzeType.FloatType else MezzeType.IntType
            }
            receiverText == "true" || receiverText == "false" || receiverText == "True" || receiverText == "False" -> MezzeType.BoolType
            else -> null
        }
    }

    private fun getKeywordCompletions(): List<CompletionItem> {
        val keywords = listOf(
            "let", "type", "effect", "ability", "impl",
            "match", "is", "if", "then", "else",
            "do", "perform", "with", "where", "for", "in",
            "use", "pub", "as"
        )

        return keywords.map { keyword ->
            CompletionItem(keyword).apply {
                kind = CompletionItemKind.Keyword
            }
        }
    }

    private fun getBindingCompletions(state: DocumentState): List<CompletionItem> {
        val typedModule = state.typeCheckResult?.typedModule
        val parsedModule = state.parsedModule
        val items = mutableListOf<CompletionItem>()

        // Add let bindings (functions and values) from TypedModule
        typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.forEach { decl ->
                items.add(CompletionItem(decl.name).apply {
                    kind = CompletionItemKind.Function
                    detail = decl.scheme.type.let {
                        prettyPrintTypeWithAliases(it, state.typeAliases)
                    }
                })
            }

        // Add type declarations from parsed module
        parsedModule?.decls?.filterIsInstance<ParsedTypeDecl>()?.forEach { typeDecl ->
            items.add(CompletionItem(typeDecl.name).apply {
                kind = CompletionItemKind.Struct
                detail = "type"
            })
        }

        // Add effect declarations from parsed module
        parsedModule?.decls?.filterIsInstance<ParsedEffectDecl>()?.forEach { effectDecl ->
            items.add(CompletionItem(effectDecl.name).apply {
                kind = CompletionItemKind.Interface
                detail = "effect"
            })
        }

        // Add ability declarations from parsed module
        parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>()?.forEach { abilityDecl ->
            items.add(CompletionItem(abilityDecl.name).apply {
                kind = CompletionItemKind.Interface
                detail = "ability"
            })
        }

        // Add imports (if type check succeeded)
        state.typeCheckResult?.resolvedImports?.forEach { import ->
            items.add(CompletionItem(import.localName).apply {
                kind = when (import.kind) {
                    ImportKind.FUNCTION -> CompletionItemKind.Function
                    ImportKind.TYPE -> CompletionItemKind.Struct
                    ImportKind.EFFECT -> CompletionItemKind.Interface
                    ImportKind.ABILITY -> CompletionItemKind.Interface
                    ImportKind.PERFORMER -> CompletionItemKind.Class
                }
                detail = prettyPrintTypeWithAliases(import.type, state.typeAliases)
            })
        }

        return items
    }

    /**
     * Get local variable completions — every binding in scope at the cursor:
     * lambda params, do-block lets, match-bound names, if-let bindings.
     * (Top-level decls and imports are added separately by [getBindingCompletions].)
     */
    private fun getLocalVariableCompletions(state: DocumentState, position: Position): List<CompletionItem> {
        val typedModule = state.typeCheckResult?.typedModule
            ?: lastSuccessfulResult[state.uri]?.typedModule
            ?: return emptyList()
        val line = position.line + 1
        val col = position.character + 1
        val scope = TypedAstScopeWalker.bindingsAtPosition(typedModule, line, col)
        if (scope.isEmpty()) return emptyList()

        // Inner-most shadow wins: build a Map keyed by name, later writes
        // overwrite earlier ones (the walker emits outer-first).
        val byName = LinkedHashMap<String, TypedAstScopeWalker.ScopeBinding>()
        for (b in scope) byName[b.name] = b

        return byName.values.map { b ->
            CompletionItem(b.name).apply {
                kind = CompletionItemKind.Variable
                detail = prettyPrintTypeWithAliases(b.type, state.typeAliases)
            }
        }
    }

    /**
     * Get variant constructor completions for match patterns.
     * Detects when cursor is after 'match X is' and suggests constructors based on X's type.
     */
    private fun getMatchPatternCompletions(state: DocumentState, position: Position): List<CompletionItem> {
        val lines = state.content.lines()
        if (position.line >= lines.size) return emptyList()

        // Look backwards to find 'match X is' pattern
        val matchContext = findMatchContext(lines, position) ?: return emptyList()

        // Find the type of the scrutinee
        val result = state.typeCheckResult ?: return emptyList()
        val scrutineeType = findExpressionType(matchContext.scrutinee, result, state)
            ?: return emptyList()

        // If it's a variant type, suggest constructors
        return getVariantConstructorCompletionsForType(scrutineeType, state)
    }

    /**
     * Context information for a match expression being edited.
     */
    private data class MatchContext(
        val scrutinee: String,  // The expression being matched (e.g., "tree", "option")
        val existingPatterns: Set<String>  // Patterns already used in this match
    )

    /**
     * Find the match expression context at the current position.
     * Returns the scrutinee expression and any already-matched patterns.
     */
    private fun findMatchContext(lines: List<String>, position: Position): MatchContext? {
        // Build the text from current line back to find 'match ... is'
        val currentLineText = lines[position.line].substring(0, minOf(position.character, lines[position.line].length))

        // Search backwards for 'match X is' pattern, but stop at top-level declarations
        var searchText = ""
        for (i in position.line downTo maxOf(0, position.line - 20)) {
            val line = if (i == position.line) currentLineText else lines[i]

            // Stop if we hit a top-level declaration (means we're not inside the match anymore)
            if (i < position.line && line.trimStart().let {
                it.startsWith("let ") || it.startsWith("type ") || it.startsWith("effect ") ||
                it.startsWith("ability ") || it.startsWith("impl ") || it.startsWith("pub ") ||
                it.startsWith("use ")
            }) {
                break
            }

            searchText = line + "\n" + searchText
        }

        // Look for 'match <expr> is' pattern
        // The pattern might be: match expr is, match (expr) is, match func { ... } is, etc.
        val matchRegex = Regex("""match\s+(\S+(?:\s*\{[^}]*\})?)\s+is""", RegexOption.DOT_MATCHES_ALL)
        val matchResult = matchRegex.findAll(searchText).lastOrNull() ?: return null

        val scrutinee = matchResult.groupValues[1].trim()

        // Check if cursor is actually after the 'is' keyword - if not, we're not in pattern position
        val matchEndIndex = matchResult.range.last
        val textAfterMatch = searchText.substring(matchEndIndex + 1)

        // If the cursor is on the same line as 'match X is' but before completing a pattern,
        // and there's no newline or arrow yet, we're in pattern position
        // Otherwise, check that we're actually in a pattern area (after a newline or arrow)
        val currentLineInSearch = searchText.lines().lastOrNull() ?: ""
        val isInPatternPosition = textAfterMatch.contains("\n") ||
                                   textAfterMatch.trim().isEmpty() ||
                                   currentLineInSearch.trim().let {
                                       it.isEmpty() ||
                                       it.matches(Regex("""^[A-Z][a-zA-Z0-9_]*.*""")) ||
                                       it.endsWith("->")
                                   }

        if (!isInPatternPosition) return null

        // Find existing patterns in this match (to avoid suggesting them again)
        // Look for patterns like: Constructor ->, Constructor { ... } ->
        val existingPatterns = mutableSetOf<String>()
        val afterMatch = searchText.substringAfter("is", "")
        val patternRegex = Regex("""^\s*([A-Z][a-zA-Z0-9_]*)(?:\s*\{[^}]*\})?\s*->""", RegexOption.MULTILINE)
        patternRegex.findAll(afterMatch).forEach { result ->
            existingPatterns.add(result.groupValues[1])
        }

        return MatchContext(scrutinee, existingPatterns)
    }

    /**
     * Find the type of an expression by name (variable lookup).
     * Searches top-level bindings, imports, and function parameters.
     */
    private fun findExpressionType(exprName: String, result: TypeCheckResult, state: DocumentState): MezzeType? {
        // Clean up the expression name (might have braces or other syntax)
        val cleanName = exprName.trim().split(Regex("""\s*\{""")).firstOrNull()?.trim() ?: exprName

        // Check top-level bindings via TypedModule
        val typedBinding = result.typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.find { it.name == cleanName }

        if (typedBinding != null) {
            val bindingType = typedBinding.scheme.type
            if (bindingType !is MezzeType.VarType) return unwrapType(bindingType)
        }

        // Check imports
        val import = result.resolvedImports.find { it.localName == cleanName }
        if (import != null) {
            return unwrapType(import.type)
        }

        // Search for the variable in function parameters via TypedModule
        // This handles cases like: let f = {x: MyType} -> match x is ...
        result.typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.forEach { decl ->
                val paramType = findParameterTypeInTypedBinding(cleanName, decl)
                if (paramType != null) {
                    return unwrapType(paramType)
                }
            }

        return null
    }

    /**
     * Find a parameter type in a typed let binding's function type.
     */
    private fun findParameterTypeInTypedBinding(paramName: String, decl: com.mezze.language.typedast.TypedLetDecl): MezzeType? {
        val funcType = decl.scheme.type
        if (funcType is MezzeType.FunctionType) {
            val paramType = funcType.paramType
            if (paramType is MezzeType.RecordType) {
                return paramType.fields[paramName]
            }
        }
        return null
    }

    /**
     * Unwrap wrapper types (like SuspendedType) to get the underlying type.
     */
    private fun unwrapType(type: MezzeType): MezzeType {
        return when (type) {
            is MezzeType.SuspendedType -> unwrapType(type.valueType)
            else -> type
        }
    }

    /**
     * Get variant constructor completions for a specific variant type.
     */
    private fun getVariantConstructorCompletionsForType(type: MezzeType, state: DocumentState): List<CompletionItem> {
        val variantType = when (type) {
            is MezzeType.VariantType -> type
            is MezzeType.TypeApplication -> {
                // Type application like Option<Int> - need to find the underlying variant
                findVariantTypeFromApplication(type, state)
            }
            else -> null
        } ?: return emptyList()

        val items = mutableListOf<CompletionItem>()

        variantType.constructors.entries.forEachIndexed { index, (name, payload) ->
            val item = CompletionItem(name).apply {
                kind = CompletionItemKind.EnumMember
                sortText = index.toString().padStart(3, '0')

                if (payload != null) {
                    // Constructor has payload - suggest with destructuring
                    val payloadStr = when (payload) {
                        is MezzeType.RecordType -> {
                            val fields = payload.fields.keys.mapIndexed { i, field ->
                                "$field = \${${i + 1}:}"
                            }.joinToString(", ")
                            " { $fields }"
                        }
                        else -> {
                            val typeStr = prettyPrintTypeWithAliases(payload, state.typeAliases)
                            " { \${1:value}: $typeStr }"
                        }
                    }
                    detail = "with payload"
                    insertText = name + payloadStr
                    insertTextFormat = InsertTextFormat.Snippet
                } else {
                    // No payload
                    detail = "unit constructor"
                    insertText = name
                }
            }
            items.add(item)
        }

        // Add wildcard pattern at the end
        items.add(CompletionItem("_").apply {
            kind = CompletionItemKind.Keyword
            detail = "wildcard (matches anything)"
            insertText = "_ -> \${1:}"
            insertTextFormat = InsertTextFormat.Snippet
            sortText = "zzz"  // Show last
        })

        return items
    }

    /**
     * Find the variant type from a type application (e.g., Option<Int> -> | Some, None |)
     */
    private fun findVariantTypeFromApplication(typeApp: MezzeType.TypeApplication, state: DocumentState): MezzeType.VariantType? {
        val typeName = typeApp.constructor
        val parsedModule = state.parsedModule ?: return null

        val typeDecl = parsedModule.decls.filterIsInstance<ParsedTypeDecl>().find { it.name == typeName }
        return when (val def = typeDecl?.definition) {
            is ParsedVariantDef -> {
                val constructors = def.constructors.associate { it.name to null as MezzeType? }
                MezzeType.VariantType(constructors, null)
            }
            else -> null
        }
    }

    /**
     * Get all visible variant constructors as completions.
     * Useful for general code completion where a variant value is expected.
     */
    private fun getVariantConstructorCompletions(state: DocumentState): List<CompletionItem> {
        val parsedModule = state.parsedModule
        val items = mutableListOf<CompletionItem>()

        // Add constructors from local type declarations
        if (parsedModule != null) {
            for (decl in parsedModule.decls.filterIsInstance<ParsedTypeDecl>()) {
                when (val def = decl.definition) {
                    is ParsedVariantDef -> {
                        for (constructor in def.constructors) {
                            items.add(CompletionItem(constructor.name).apply {
                                kind = CompletionItemKind.EnumMember
                                detail = decl.name

                                if (constructor.dataType != null) {
                                    insertText = "${constructor.name} { \${1:} }"
                                    insertTextFormat = InsertTextFormat.Snippet
                                } else {
                                    insertText = constructor.name
                                }
                            })
                        }
                    }
                    else -> {}
                }
            }
        }

        // Add Bool constructors (built-in)
        items.add(CompletionItem("True").apply {
            kind = CompletionItemKind.EnumMember
            detail = "Bool"
        })
        items.add(CompletionItem("False").apply {
            kind = CompletionItemKind.EnumMember
            detail = "Bool"
        })

        // Add Option constructors (commonly used)
        items.add(CompletionItem("Some").apply {
            kind = CompletionItemKind.EnumMember
            detail = "Option"
            insertText = "Some { value = \${1:} }"
            insertTextFormat = InsertTextFormat.Snippet
        })
        items.add(CompletionItem("None").apply {
            kind = CompletionItemKind.EnumMember
            detail = "Option"
        })

        // Add Result constructors (commonly used)
        items.add(CompletionItem("Ok").apply {
            kind = CompletionItemKind.EnumMember
            detail = "Result"
            insertText = "Ok { value = \${1:} }"
            insertTextFormat = InsertTextFormat.Snippet
        })
        items.add(CompletionItem("Err").apply {
            kind = CompletionItemKind.EnumMember
            detail = "Result"
            insertText = "Err { error = \${1:} }"
            insertTextFormat = InsertTextFormat.Snippet
        })

        return items
    }

    private fun getStdlibCompletions(): List<CompletionItem> {
        // Common stdlib imports
        val suggestions = listOf(
            "std::effects::log::{println, Console}" to "Print to console",
            "std::int::{to_string, abs}" to "Integer utilities",
            "std::option::{map, get_or_else}" to "Option utilities",
            "std::collections::seq::{length, map}" to "Seq utilities"
        )

        return suggestions.map { (import, desc) ->
            CompletionItem("use $import").apply {
                kind = CompletionItemKind.Module
                detail = desc
                insertText = "use $import"
            }
        }
    }

    override fun resolveCompletionItem(item: CompletionItem): CompletableFuture<CompletionItem> {
        return CompletableFuture.completedFuture(item)
    }

    // ========== Document Symbols ==========

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val state = documents[uri] ?: return@supplyAsync emptyList()

            val result = state.typeCheckResult ?: return@supplyAsync emptyList()

            val symbols = mutableListOf<Either<SymbolInformation, DocumentSymbol>>()

            // Add let binding symbols from TypedModule
            result.typedModule?.decls
                ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
                ?.forEach { decl ->
                    val symbol = createDocumentSymbol(
                        name = decl.name,
                        kind = SymbolKind.Function,
                        location = decl.loc,
                        detail = prettyPrintType(decl.scheme.type)
                    )
                    if (symbol != null) {
                        symbols.add(Either.forRight(symbol))
                    }
                }

            // Add effect symbols from parsed module
            state.parsedModule?.decls?.filterIsInstance<ParsedEffectDecl>()?.forEach { decl ->
                val symbol = createDocumentSymbol(
                    name = decl.name,
                    kind = SymbolKind.Interface,
                    location = decl.loc
                )
                if (symbol != null) {
                    symbols.add(Either.forRight(symbol))
                }
            }

            // Add ability symbols from parsed module
            state.parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>()?.forEach { decl ->
                val symbol = createDocumentSymbol(
                    name = decl.name,
                    kind = SymbolKind.Interface,
                    location = decl.loc
                )
                if (symbol != null) {
                    symbols.add(Either.forRight(symbol))
                }
            }

            symbols
        }
    }

    private fun createDocumentSymbol(
        name: String,
        kind: SymbolKind,
        location: Loc?,
        detail: String? = null
    ): DocumentSymbol? {
        if (location == null) return null

        val range = Range(
            Position(location.startLine - 1, location.startCol - 1),
            Position(location.startLine - 1, location.startCol + name.length - 1)
        )

        return DocumentSymbol(name, kind, range, range, detail, emptyList())
    }

    // ========== References ==========

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            val state = documents[uri] ?: return@supplyAsync emptyList()

            val lines = state.content.lines()
            if (position.line >= lines.size) return@supplyAsync emptyList()
            val word = extractWordAt(lines[position.line], position.character) ?: return@supplyAsync emptyList()

            val locations = mutableListOf<Location>()

            // Resolve the hash of the decl under cursor. Two paths:
            // (a) on a TypedVar.ByHash use site → use that hash.
            // (b) on a TypedLetDecl definition → use TypedLetDecl.hash.
            val typedModule = (state.typeCheckResult ?: lastSuccessfulResult[uri])?.typedModule
            val targetHash: com.mezze.language.typedast.Hash? = run {
                if (typedModule == null) return@run null
                val lookupLine = position.line + 1
                val lookupCol = position.character + 1
                when (val r = TypedAstLookup.findAtPosition(typedModule, lookupLine, lookupCol)) {
                    is TypedAstLookup.LookupResult.Expr -> {
                        val e = r.expr
                        if (e is com.mezze.language.typedast.TypedVar) {
                            (e.binding as? com.mezze.language.typedast.BindingRef.ByHash)?.hash
                        } else null
                    }
                    else -> null
                }
            } ?: typedModule?.decls
                ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
                ?.firstOrNull { it.name == word }
                ?.hash

            // Cross-file find-references via the v7 usages index. One
            // SELECT WHERE target_hash=? returns every use site across
            // the project (slice E populates this on every compile).
            val store = projectStore
            if (targetHash != null && store != null) {
                if (params.context?.isIncludeDeclaration != false) {
                    // Add the definition's own loc as the first hit.
                    val defDecl = typedModule?.decls
                        ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
                        ?.firstOrNull { it.hash == targetHash }
                    if (defDecl != null) locations.add(locToLocation(uri, defDecl.loc))
                }
                for (row in store.getUsages(targetHash)) {
                    val rowUri = File(row.filePath).toURI().toString()
                    locations.add(Location(rowUri, Range(
                        Position(row.startLine - 1, row.startCol - 1),
                        Position(row.endLine - 1, row.endCol)
                    )))
                }
            }

            // Fallback: in-file TypedVar walk (for locals with no hash, or
            // when the usages table hasn't been populated yet — e.g., the
            // file hasn't been saved since the project was opened).
            //
            // We deliberately do NOT regex-scan the source bytes as a
            // last resort; that produced false positives by matching
            // inside strings and comments, and trampling unrelated
            // bindings that happen to share the name.
            if (locations.isEmpty() && typedModule != null) {
                collectReferencesInModule(typedModule, word, uri, locations)
            }

            locations
        }
    }

    // ========== Document Highlight ==========

    /**
     * Same-file occurrence highlight. The editor sends this as the
     * cursor moves; response should be cheap and exclude cross-file
     * matches (those go through `references`).
     *
     * Strategy: reuse the in-file TypedAST walker. We don't bother with
     * the usages index here — same-file is the only contract, and the
     * walker handles it precisely.
     */
    override fun documentHighlight(
        params: DocumentHighlightParams
    ): CompletableFuture<List<DocumentHighlight>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val state = documents[uri] ?: return@supplyAsync emptyList()
            val lines = state.content.lines()
            if (params.position.line >= lines.size) return@supplyAsync emptyList()
            val word = extractWordAt(lines[params.position.line], params.position.character)
                ?: return@supplyAsync emptyList()
            val typedModule = (state.typeCheckResult ?: lastSuccessfulResult[uri])?.typedModule
                ?: return@supplyAsync emptyList()

            val locs = mutableListOf<Location>()
            collectReferencesInModule(typedModule, word, uri, locs)

            locs.map { loc ->
                DocumentHighlight(loc.range, DocumentHighlightKind.Read)
            }
        }
    }

    // ========== Selection Range ==========

    /**
     * "Expand selection" support. Returns a ladder of nested ranges from
     * innermost to outermost so the editor can ctrl-w / cmd-up through
     * progressively larger selections.
     *
     * Today we build the ladder lexically rather than from the AST:
     * word → line → enclosing decl → file. The AST-rich version (record
     * field → record literal → call → outer call → block) is a future
     * upgrade — this hits the 80% case immediately.
     */
    override fun selectionRange(
        params: SelectionRangeParams
    ): CompletableFuture<List<SelectionRange>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val state = documents[uri] ?: return@supplyAsync emptyList()
            val lines = state.content.lines()
            val parsed = state.parsedModule

            params.positions.mapNotNull { pos ->
                if (pos.line >= lines.size) return@mapNotNull null
                val line = lines[pos.line]
                val ladder = mutableListOf<Range>()

                // Innermost: the word under the cursor (if any).
                val wordRange = wordRangeAt(line, pos.line, pos.character)
                if (wordRange != null) ladder.add(wordRange)

                // The line itself (excluding trailing newline).
                ladder.add(Range(Position(pos.line, 0), Position(pos.line, line.length)))

                // Smallest enclosing parsed decl, if we have a parsed module.
                val enclosingDecl = parsed?.decls
                    ?.filter { decl ->
                        val l = decl.loc
                        (pos.line + 1) in l.startLine..l.endLine
                    }
                    ?.minByOrNull { it.loc.endLine - it.loc.startLine }
                if (enclosingDecl != null) {
                    val l = enclosingDecl.loc
                    ladder.add(Range(
                        Position(l.startLine - 1, l.startCol - 1),
                        Position(l.endLine - 1, l.endCol)
                    ))
                }

                // Whole file as the outermost step.
                val lastIdx = (lines.size - 1).coerceAtLeast(0)
                ladder.add(Range(
                    Position(0, 0),
                    Position(lastIdx, lines.lastOrNull()?.length ?: 0)
                ))

                // Fold the list into a linked SelectionRange chain. Each
                // SelectionRange's `parent` is the next outermost.
                var chain: SelectionRange? = null
                for (range in ladder.asReversed()) {
                    chain = SelectionRange(range, chain)
                }
                chain
            }
        }
    }

    /** Identifier-bounded range at [col] in [line], or null if not on a word. */
    private fun wordRangeAt(line: String, lineIdx: Int, col: Int): Range? {
        if (col > line.length) return null
        var start = col
        while (start > 0 && isIdentifierChar(line[start - 1])) start--
        var end = col
        while (end < line.length && isIdentifierChar(line[end])) end++
        if (start == end) return null
        return Range(Position(lineIdx, start), Position(lineIdx, end))
    }

    // ========== Call Hierarchy ==========
    //
    // Three-step protocol:
    //   prepareCallHierarchy → returns the CallHierarchyItem for the cursor
    //   incomingCalls(item)  → who calls this?  (edges WHERE to_hash = item.hash)
    //   outgoingCalls(item)  → who does this call?  (edges WHERE from_hash = item.hash)
    //
    // We embed the target's content hash in the item's `data` field as a
    // hex string so the follow-up calls can find it without re-resolving.

    override fun prepareCallHierarchy(
        params: CallHierarchyPrepareParams
    ): CompletableFuture<List<CallHierarchyItem>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val state = documents[uri] ?: return@supplyAsync emptyList()
            val typedModule = (state.typeCheckResult ?: lastSuccessfulResult[uri])?.typedModule
                ?: return@supplyAsync emptyList()

            val pos = params.position
            val lookupLine = pos.line + 1
            val lookupCol = pos.character + 1

            // Same hash-resolution dance as references / rename.
            val hash: com.mezze.language.typedast.Hash? = run {
                when (val r = TypedAstLookup.findAtPosition(typedModule, lookupLine, lookupCol)) {
                    is TypedAstLookup.LookupResult.Expr -> {
                        val e = r.expr
                        if (e is com.mezze.language.typedast.TypedVar) {
                            (e.binding as? com.mezze.language.typedast.BindingRef.ByHash)?.hash
                        } else null
                    }
                    else -> null
                }
            } ?: typedModule.decls
                .filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
                .firstOrNull { decl ->
                    val l = decl.loc
                    lookupLine in l.startLine..l.endLine
                }?.hash

            if (hash == null) return@supplyAsync emptyList()
            val item = makeCallHierarchyItem(hash) ?: return@supplyAsync emptyList()
            listOf(item)
        }
    }

    override fun callHierarchyIncomingCalls(
        params: CallHierarchyIncomingCallsParams
    ): CompletableFuture<List<CallHierarchyIncomingCall>> {
        return CompletableFuture.supplyAsync {
            val targetHex = params.item.data as? String ?: return@supplyAsync emptyList()
            val store = projectStore ?: return@supplyAsync emptyList()
            val targetHash = com.mezze.language.typedast.Hash(targetHex)

            val out = mutableListOf<CallHierarchyIncomingCall>()
            for (edge in store.getEdgesTo(targetHash)) {
                if (edge.kind != com.mezze.language.codebase.EdgeKind.CALLS) continue
                val callerItem = makeCallHierarchyItem(edge.fromHash) ?: continue
                // `fromRanges` is the list of call-site ranges inside the
                // caller's body. We resolve them via the usages table —
                // each row whose target_hash == this target and whose
                // file_path matches the caller's file is a call site.
                val callerFile = callerItem.uri
                val callerFilePath = java.net.URI(callerFile).path
                val ranges = store.getUsages(targetHash)
                    .filter { it.filePath == callerFilePath }
                    .map { Range(
                        Position(it.startLine - 1, it.startCol - 1),
                        Position(it.endLine - 1, it.endCol)
                    ) }
                if (ranges.isEmpty()) {
                    // Fallback to the caller's own range; LSP requires at
                    // least one range so the editor has something to jump to.
                    out.add(CallHierarchyIncomingCall(callerItem, listOf(callerItem.range)))
                } else {
                    out.add(CallHierarchyIncomingCall(callerItem, ranges))
                }
            }
            out
        }
    }

    override fun callHierarchyOutgoingCalls(
        params: CallHierarchyOutgoingCallsParams
    ): CompletableFuture<List<CallHierarchyOutgoingCall>> {
        return CompletableFuture.supplyAsync {
            val callerHex = params.item.data as? String ?: return@supplyAsync emptyList()
            val store = projectStore ?: return@supplyAsync emptyList()
            val callerHash = com.mezze.language.typedast.Hash(callerHex)
            val callerFilePath = java.net.URI(params.item.uri).path

            val out = mutableListOf<CallHierarchyOutgoingCall>()
            for (edge in store.getEdgesFrom(callerHash)) {
                if (edge.kind != com.mezze.language.codebase.EdgeKind.CALLS) continue
                val calleeItem = makeCallHierarchyItem(edge.toHash) ?: continue
                val ranges = store.getUsages(edge.toHash)
                    .filter { it.filePath == callerFilePath }
                    .map { Range(
                        Position(it.startLine - 1, it.startCol - 1),
                        Position(it.endLine - 1, it.endCol)
                    ) }
                out.add(CallHierarchyOutgoingCall(
                    calleeItem,
                    if (ranges.isEmpty()) listOf(calleeItem.range) else ranges
                ))
            }
            out
        }
    }

    /**
     * Materialize a CallHierarchyItem for [hash] by joining decls + modules.
     * Returns null if the hash isn't in the decls index (e.g., synthetic
     * top-level wrapper, or the decl was removed).
     */
    private fun makeCallHierarchyItem(hash: com.mezze.language.typedast.Hash): CallHierarchyItem? {
        val store = projectStore ?: return null
        val decl = store.findDeclByHash(hash) ?: return null
        val module = store.getModule(decl.modulePath) ?: return null
        val filePath = module.filePath ?: return null
        val itemUri = File(filePath).toURI().toString()
        val range = Range(
            Position(decl.startLine - 1, decl.startCol - 1),
            Position(decl.endLine - 1, decl.endCol)
        )
        val item = CallHierarchyItem(
            decl.declName,
            declKindToSymbolKindForCallHierarchy(decl.declKind),
            itemUri,
            range,
            range
        )
        item.detail = decl.modulePath
        item.data = hash.hex
        return item
    }

    private fun declKindToSymbolKindForCallHierarchy(kind: com.mezze.language.codebase.DeclKind): SymbolKind =
        when (kind) {
            com.mezze.language.codebase.DeclKind.LET -> SymbolKind.Function
            com.mezze.language.codebase.DeclKind.TYPE -> SymbolKind.Class
            com.mezze.language.codebase.DeclKind.ABILITY -> SymbolKind.Interface
            com.mezze.language.codebase.DeclKind.EFFECT -> SymbolKind.Event
            com.mezze.language.codebase.DeclKind.PERFORMER -> SymbolKind.Operator
            com.mezze.language.codebase.DeclKind.IMPL -> SymbolKind.Method
        }

    // ========== Type Hierarchy ==========
    //
    // For Mezze, "type hierarchy" maps to two relationships:
    //   - An ability has a set of impls (subtypes).
    //   - An impl points back at the ability it implements (supertype).
    // We get both directly from the `ability_impls` table.
    //
    // Records and variants don't form a subtype lattice in Mezze, so on
    // those nodes we return the bare item with empty up/down sets.

    override fun prepareTypeHierarchy(
        params: TypeHierarchyPrepareParams
    ): CompletableFuture<List<TypeHierarchyItem>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val state = documents[uri] ?: return@supplyAsync emptyList()
            val typedModule = (state.typeCheckResult ?: lastSuccessfulResult[uri])?.typedModule
                ?: return@supplyAsync emptyList()

            val pos = params.position
            val lookupLine = pos.line + 1

            // Find an enclosing decl at the cursor across every TypedModule
            // bucket — let, type, ability, ability_impl, effect, performer.
            // Each carries its own hash + loc. We pick the smallest match.
            data class HashedLoc(val hash: com.mezze.language.typedast.Hash, val loc: com.mezze.language.parsedast.Loc)
            val candidates = mutableListOf<HashedLoc>()
            for (d in typedModule.decls) when (d) {
                is com.mezze.language.typedast.TypedLetDecl -> candidates.add(HashedLoc(d.hash, d.loc))
                is com.mezze.language.typedast.TypedTypeDecl -> candidates.add(HashedLoc(d.hash, d.loc))
                else -> {}
            }
            for (a in typedModule.abilityDecls) candidates.add(HashedLoc(a.hash, a.loc))
            for (a in typedModule.abilityImpls) candidates.add(HashedLoc(a.hash, a.loc))
            for (e in typedModule.effectDecls) candidates.add(HashedLoc(e.hash, e.loc))
            for (p in typedModule.performerDecls) candidates.add(HashedLoc(p.hash, p.loc))

            val match = candidates
                .filter { lookupLine in it.loc.startLine..it.loc.endLine }
                .minByOrNull { it.loc.endLine - it.loc.startLine }
                ?: return@supplyAsync emptyList()

            val item = makeTypeHierarchyItem(match.hash) ?: return@supplyAsync emptyList()
            listOf(item)
        }
    }

    override fun typeHierarchySupertypes(
        params: TypeHierarchySupertypesParams
    ): CompletableFuture<List<TypeHierarchyItem>> {
        return CompletableFuture.supplyAsync {
            val hex = params.item.data as? String ?: return@supplyAsync emptyList()
            val store = projectStore ?: return@supplyAsync emptyList()
            val hash = com.mezze.language.typedast.Hash(hex)

            // If `hash` is an impl, its supertype is the ability it implements.
            // We discover that by looking up the impl's blob and reading its
            // ability_hash via the ability_impls index. There's no single
            // "lookup impl by impl hash"; we fall back to scanning impls
            // for any ability and matching on impl hash.
            val out = mutableListOf<TypeHierarchyItem>()
            // Walk the IMPLEMENTS edges from this hash — each target is a
            // supertype (ability or effect).
            for (edge in store.getEdgesFrom(hash)) {
                if (edge.kind != com.mezze.language.codebase.EdgeKind.IMPLEMENTS) continue
                makeTypeHierarchyItem(edge.toHash)?.let { out.add(it) }
            }
            out
        }
    }

    override fun typeHierarchySubtypes(
        params: TypeHierarchySubtypesParams
    ): CompletableFuture<List<TypeHierarchyItem>> {
        return CompletableFuture.supplyAsync {
            val hex = params.item.data as? String ?: return@supplyAsync emptyList()
            val store = projectStore ?: return@supplyAsync emptyList()
            val hash = com.mezze.language.typedast.Hash(hex)

            val out = mutableListOf<TypeHierarchyItem>()

            // Direct: ability → all impls of it.
            for (impl in store.getAbilityImpls(hash)) {
                makeTypeHierarchyItem(impl.hash)?.let { out.add(it) }
            }

            // Generic: every IMPLEMENTS edge pointing at this hash.
            // (Captures effect → performer relationships.)
            for (edge in store.getEdgesTo(hash)) {
                if (edge.kind != com.mezze.language.codebase.EdgeKind.IMPLEMENTS) continue
                makeTypeHierarchyItem(edge.fromHash)?.let { out.add(it) }
            }
            out.distinctBy { it.data as String }
        }
    }

    private fun makeTypeHierarchyItem(hash: com.mezze.language.typedast.Hash): TypeHierarchyItem? {
        val store = projectStore ?: return null
        val decl = store.findDeclByHash(hash) ?: return null
        val module = store.getModule(decl.modulePath) ?: return null
        val filePath = module.filePath ?: return null
        val itemUri = File(filePath).toURI().toString()
        val range = Range(
            Position(decl.startLine - 1, decl.startCol - 1),
            Position(decl.endLine - 1, decl.endCol)
        )
        val item = TypeHierarchyItem(
            decl.declName,
            declKindToSymbolKindForCallHierarchy(decl.declKind),
            itemUri,
            range,
            range
        )
        item.detail = decl.modulePath
        item.data = hash.hex
        return item
    }

    /** Collect all references to a name in the TypedModule. */
    private fun collectReferencesInModule(module: com.mezze.language.typedast.TypedModule, name: String, uri: String, locations: MutableList<Location>) {
        for (decl in module.decls) {
            if (decl is com.mezze.language.typedast.TypedLetDecl) {
                // Include the definition itself
                if (decl.name == name) {
                    locations.add(locToLocation(uri, decl.loc))
                }
                // Search expression tree for references
                collectReferencesInExpr(decl.value, name, uri, locations)
            }
        }
    }

    /** Recursively find all TypedVar references to a name. */
    private fun collectReferencesInExpr(expr: com.mezze.language.typedast.TypedExpr, name: String, uri: String, locations: MutableList<Location>) {
        when (expr) {
            is com.mezze.language.typedast.TypedVar -> {
                if (expr.name == name) locations.add(locToLocation(uri, expr.loc))
            }
            is com.mezze.language.typedast.TypedCall -> {
                collectReferencesInExpr(expr.function, name, uri, locations)
                collectReferencesInExpr(expr.arg, name, uri, locations)
            }
            is com.mezze.language.typedast.TypedLambda -> collectReferencesInExpr(expr.body, name, uri, locations)
            is com.mezze.language.typedast.TypedRecord -> {
                for (entry in expr.entries) when (entry) {
                    is com.mezze.language.typedast.TypedRecordEntry.Field -> collectReferencesInExpr(entry.value, name, uri, locations)
                    is com.mezze.language.typedast.TypedRecordEntry.Spread -> collectReferencesInExpr(entry.expr, name, uri, locations)
                }
            }
            is com.mezze.language.typedast.TypedFieldAccess -> collectReferencesInExpr(expr.record, name, uri, locations)
            is com.mezze.language.typedast.TypedBlock -> {
                for (stmt in expr.statements) when (stmt) {
                    is com.mezze.language.typedast.TypedStatement.Let -> collectReferencesInExpr(stmt.value, name, uri, locations)
                    is com.mezze.language.typedast.TypedStatement.Expr -> collectReferencesInExpr(stmt.expr, name, uri, locations)
                }
                collectReferencesInExpr(expr.result, name, uri, locations)
            }
            is com.mezze.language.typedast.TypedMatch -> {
                collectReferencesInExpr(expr.scrutinee, name, uri, locations)
                for (case in expr.cases) collectReferencesInExpr(case.body, name, uri, locations)
            }
            is com.mezze.language.typedast.TypedIf -> {
                collectReferencesInExpr(expr.condition, name, uri, locations)
                collectReferencesInExpr(expr.thenBranch, name, uri, locations)
                collectReferencesInExpr(expr.elseBranch, name, uri, locations)
            }
            is com.mezze.language.typedast.TypedPipe -> {
                collectReferencesInExpr(expr.left, name, uri, locations)
                collectReferencesInExpr(expr.right, name, uri, locations)
            }
            is com.mezze.language.typedast.TypedPerform -> collectReferencesInExpr(expr.body, name, uri, locations)
            is com.mezze.language.typedast.TypedVariantConstruct -> expr.payload?.let { collectReferencesInExpr(it, name, uri, locations) }
            is com.mezze.language.typedast.TypedSeq -> {
                for (elem in expr.elements) when (elem) {
                    is com.mezze.language.typedast.TypedSeqElement.Regular -> collectReferencesInExpr(elem.expr, name, uri, locations)
                    is com.mezze.language.typedast.TypedSeqElement.Spread -> collectReferencesInExpr(elem.expr, name, uri, locations)
                }
            }
            else -> {} // leaf nodes
        }
    }

    // ========== Folding Ranges ==========

    override fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<List<FoldingRange>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val state = documents[uri] ?: return@supplyAsync emptyList()

            val ranges = mutableListOf<FoldingRange>()

            // Simple brace-based folding
            val lines = state.content.lines()
            val braceStack = mutableListOf<Int>()

            lines.forEachIndexed { lineIndex, line ->
                for (char in line) {
                    when (char) {
                        '{' -> braceStack.add(lineIndex)
                        '}' -> {
                            if (braceStack.isNotEmpty()) {
                                val start = braceStack.removeLast()
                                if (lineIndex > start) {
                                    ranges.add(FoldingRange(start, lineIndex).apply {
                                        kind = FoldingRangeKind.Region
                                    })
                                }
                            }
                        }
                    }
                }
            }

            ranges
        }
    }

    // ========== Code Actions ==========

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val state = documents[uri] ?: return@supplyAsync emptyList()
            val actions = mutableListOf<Either<Command, CodeAction>>()

            // Auto-import: walk the diagnostics looking for unresolved-name errors.
            // For each, query the decls index across cache + stdlib + deps for an
            // exact name match and offer one quickfix per matching module.
            val store = projectStore
            if (store != null) {
                val seen = HashSet<Pair<String, String>>()  // (modulePath, name) — dedupe
                for (diagnostic in params.context.diagnostics) {
                    val msg = diagnostic.message ?: continue
                    val undefinedName = extractUndefinedName(msg) ?: continue
                    if (undefinedName.isEmpty()) continue

                    // searchDeclsByName uses LIKE prefix; filter to exact match here.
                    // Skip the file we're editing — that's where the error came
                    // from, importing from yourself is nonsensical.
                    val currentModulePath = state.parsedModule?.path
                    val candidates = store.searchDeclsByName(undefinedName)
                        .filter { it.declName == undefinedName }
                        .filter { it.modulePath != currentModulePath }

                    for (decl in candidates) {
                        if (!seen.add(decl.modulePath to undefinedName)) continue
                        val edit = buildAutoImportEdit(state, decl.modulePath, undefinedName) ?: continue
                        val action = CodeAction("Import '$undefinedName' from ${decl.modulePath}")
                        action.kind = CodeActionKind.QuickFix
                        action.diagnostics = listOf(diagnostic)
                        action.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))
                        actions.add(Either.forRight(action))
                    }
                }
            }

            actions
        }
    }

    /**
     * Pull the unresolved-name token out of a typechecker error message.
     * The typechecker emits a few variants, all roughly:
     *   "Undefined variable or function: 'foo'"
     *   "X is not defined or not imported"
     * We match anything single-quoted first (preferred), else anything
     * after the last colon, else null.
     */
    private fun extractUndefinedName(msg: String): String? {
        if (!(msg.contains("Undefined variable")
                || msg.contains("not defined")
                || msg.contains("not imported"))) {
            return null
        }
        Regex("'([\\w_]+)'").find(msg)?.let { return it.groupValues[1] }
        Regex(":\\s*([\\w_]+)\\s*$").find(msg)?.let { return it.groupValues[1] }
        return null
    }

    /**
     * Build a TextEdit that inserts `use <module>::<name>` in the right place:
     *
     *   - If the file already has `use` decls, append after the last one,
     *     preserving alphabetical order within the run.
     *   - If there are no `use` decls but there are leading comments / a
     *     module preamble, insert above the first non-comment top decl.
     *   - Else insert at the very top.
     *
     * We never try to merge into an existing `use mod::{a, b}` braced list —
     * that's a formatter problem, not ours.
     */
    private fun buildAutoImportEdit(state: DocumentState, modulePath: String, name: String): TextEdit? {
        val parsed = state.parsedModule ?: return null
        val useDecls = parsed.decls.filterIsInstance<com.mezze.language.parsedast.ParsedUseDecl>()

        val newLine = "use $modulePath::$name\n"

        if (useDecls.isNotEmpty()) {
            // Insert at start of the line just after the last `use` decl.
            val last = useDecls.maxBy { it.loc.endLine }
            val insertLine = last.loc.endLine  // 1-based — that's the next line in 0-based LSP
            return TextEdit(Range(Position(insertLine, 0), Position(insertLine, 0)), newLine)
        }

        // No imports yet — find the first non-use decl and insert above it.
        val firstDecl = parsed.decls.firstOrNull()
        if (firstDecl != null) {
            val line = firstDecl.loc.startLine - 1  // 1-based -> 0-based
            return TextEdit(Range(Position(line, 0), Position(line, 0)), newLine + "\n")
        }

        return TextEdit(Range(Position(0, 0), Position(0, 0)), newLine + "\n")
    }

    // ========== Signature Help ==========

    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp?> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            val state = documents[uri] ?: return@supplyAsync null
            val result = state.typeCheckResult ?: lastSuccessfulResult[uri] ?: return@supplyAsync null

            // Find the function being called by looking at the text before the cursor
            val lines = state.content.lines()
            if (position.line >= lines.size) return@supplyAsync null
            val line = lines[position.line]
            val beforeCursor = line.substring(0, minOf(position.character, line.length))

            // Look for "functionName {" pattern
            val funcMatch = Regex("""(\w+)\s*\{[^}]*$""").find(beforeCursor) ?: return@supplyAsync null
            val funcName = funcMatch.groupValues[1]

            // Find the function's type
            val funcType = findFunctionType(funcName, result, state) ?: return@supplyAsync null

            if (funcType !is MezzeType.FunctionType) return@supplyAsync null

            val paramStr = prettyPrintTypeWithAliases(funcType.paramType, state.typeAliases)
            val returnStr = prettyPrintTypeWithAliases(funcType.returnType, state.typeAliases)

            val sigInfo = SignatureInformation("$funcName $paramStr -> $returnStr")

            // Add parameter info if param is a record
            if (funcType.paramType is MezzeType.RecordType) {
                val recordType = funcType.paramType as MezzeType.RecordType
                sigInfo.parameters = recordType.fields.map { (name, type) ->
                    ParameterInformation("$name: ${prettyPrintTypeWithAliases(type, state.typeAliases)}")
                }
            }

            SignatureHelp(listOf(sigInfo), 0, 0)
        }
    }

    /** Find a function's type by name from the type check result. */
    private fun findFunctionType(name: String, result: TypeCheckResult, state: DocumentState): MezzeType? {
        // Check typed bindings
        val typedBinding = result.typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.find { it.name == name }
        if (typedBinding != null) return typedBinding.scheme.type

        // Check imports
        val import = result.resolvedImports.find { it.localName == name }
        if (import != null) return import.type

        return null
    }

    // ========== Document Formatting ==========

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
        val uri = params.textDocument.uri
        val state = documents[uri]
            ?: return CompletableFuture.completedFuture(emptyList())

        return CompletableFuture.supplyAsync {
            try {
                val formatter = Formatter()
                val formatted = formatter.format(state.content)

                // If content unchanged, return empty list
                if (formatted == state.content) {
                    return@supplyAsync emptyList()
                }

                // Return a single edit that replaces the entire document
                val lines = state.content.lines()
                val lastLine = lines.size - 1
                val lastChar = lines.lastOrNull()?.length ?: 0

                listOf(
                    TextEdit(
                        Range(
                            Position(0, 0),
                            Position(lastLine, lastChar)
                        ),
                        formatted
                    )
                )
            } catch (e: Exception) {
                server.logError("Formatting failed: ${e.message}")
                emptyList()
            }
        }
    }

    // ========== Inlay Hints ==========

    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val state = documents[uri] ?: return@supplyAsync emptyList()
            val result = state.typeCheckResult ?: lastSuccessfulResult[uri] ?: return@supplyAsync emptyList()
            val typedModule = result.typedModule ?: return@supplyAsync emptyList()

            val hints = mutableListOf<InlayHint>()

            // 1. Let bindings without type annotations — show inferred type
            val sourceLines = state.content.split('\n')

            // 1. Top-level let-bindings — show `: T` after the binding
            //    name when no explicit type annotation; show trailing chain
            //    hint regardless (the chain body type is informative even
            //    when the binding's full type IS spelled out).
            for (decl in typedModule.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()) {
                val parsedDecl = state.parsedModule?.decls
                    ?.filterIsInstance<com.mezze.language.parsedast.ParsedLetDecl>()
                    ?.find { it.isSimpleBinding && it.variableName == decl.name }
                val hasAnnotation = parsedDecl?.typeAnnotation != null

                if (!hasAnnotation) {
                    emitLetNameHint(
                        name = decl.name,
                        type = decl.scheme.type,
                        declStartLine = decl.loc.startLine,
                        sourceLines = sourceLines,
                        typeAliases = state.typeAliases,
                        hints = hints
                    )
                }

                // 1b. Multi-line lambda body — show return type at the end
                //     of the body's last line. Useful even when the let has
                //     an explicit annotation: lets the user see the chain
                //     result type at the bottom of the chain without
                //     scrolling back to the `let` line.
                val value = decl.value
                if (value is com.mezze.language.typedast.TypedLambda) {
                    val body = value.body
                    // Skip the trailing-body hint when the body is an
                    // *imperative* shape — TypedPerform (`perform X in
                    // do { … }`) or a top-level TypedBlock (`do { … }`).
                    // These always end in Unit (the last statement
                    // implicitly returns `{}`), so a `: {}` inlay at
                    // the bottom is structural noise. Pipe chains, dot
                    // chains, and value-returning bodies still get the
                    // trailing hint — that's where the chain's result
                    // type is informative.
                    val isImperativeBody = body is com.mezze.language.typedast.TypedPerform ||
                        body is com.mezze.language.typedast.TypedBlock
                    if (body.loc.endLine > decl.loc.startLine && !isImperativeBody) {
                        emitTrailingTypeHint(
                            type = body.type,
                            anchorLine = body.loc.endLine,
                            anchorCol = body.loc.endCol,
                            sourceLines = sourceLines,
                            typeAliases = state.typeAliases,
                            hints = hints
                        )
                    }
                }
            }

            // 2. Per-segment chain hints + nested-let hints inside do/blocks.
            for (decl in typedModule.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()) {
                collectPipeHints(decl.value, hints, state.typeAliases, sourceLines)
            }

            hints
        }
    }

    /**
     * Emit a `: T` inlay hint anchored right after the binding name on the
     * decl's start line (Scala / Metals convention — renders as
     * `let foo : T = …`). The name is located by regexing for
     * `(pub )?let <name>` on the source line; if it can't be found
     * (pretty-printer rewrite in flight, multi-line decl name, etc.) we
     * fall back to end-of-line.
     *
     * Skips wholly unresolved type variables (`'a` alone) and Unit (`{}`).
     * Partial polymorphic types like `Option 'a` are still shown — the
     * constructor name is informative.
     */
    private fun emitLetNameHint(
        name: String,
        type: com.mezze.language.ast.MezzeType,
        declStartLine: Int,
        sourceLines: List<String>,
        typeAliases: Map<String, TypeAliasInfo>,
        hints: MutableList<InlayHint>
    ) {
        val typeStr = prettyPrintTypeWithAliases(type, typeAliases)
        if (isUninformativeType(typeStr)) return

        val lineIdx = (declStartLine - 1).coerceAtLeast(0).coerceAtMost(sourceLines.lastIndex)
        val line = sourceLines.getOrNull(lineIdx) ?: ""
        val pattern = Regex("""\b(?:pub\s+)?let\s+${Regex.escape(name)}\b""")
        val m = pattern.find(line)
        val anchorCol = if (m != null) m.range.last + 1 else line.length
        val hint = InlayHint()
        hint.position = Position(lineIdx, anchorCol)
        hint.setLabel(": $typeStr")
        hint.kind = InlayHintKind.Type
        hint.paddingLeft = true
        // Make the hint actionable: clients with inlay textEdits support
        // (VSCode does it on cmd+click / "Insert annotation") will insert
        // `: T` at the anchor when the user accepts. The no-space-before
        // colon matches the formatter's `let foo: T` style and the rest
        // of Mezze's type-annotation syntax (record fields, params, etc.).
        // Skip when the regex didn't match — without a known position, an
        // edit might land in the wrong place.
        if (m != null) {
            hint.textEdits = listOf(
                TextEdit(
                    Range(Position(lineIdx, anchorCol), Position(lineIdx, anchorCol)),
                    ": $typeStr"
                )
            )
        }
        hints.add(hint)
    }

    /**
     * Detect `let <name> : ...` or `let <name>: ...` in source. Used to
     * suppress nested-let inlay hints when the user already wrote an
     * explicit annotation. Cheap text scan — no parsed-AST plumbing.
     */
    private fun hasExplicitAnnotation(sourceLines: List<String>, name: String, declStartLine: Int): Boolean {
        val lineIdx = (declStartLine - 1).coerceAtLeast(0).coerceAtMost(sourceLines.lastIndex)
        val line = sourceLines.getOrNull(lineIdx) ?: return false
        val pattern = Regex("""\blet\s+${Regex.escape(name)}\s*:""")
        return pattern.containsMatchIn(line)
    }

    /**
     * Skip uninformative type strings — Unit, Unit-with-effects, and
     * Unit-returning entry-point shapes. Showing them as inlays clutters
     * the editor without adding signal.
     *
     *   `{}`             → exact Unit
     *   `{} <…>`         → Unit with effects (e.g. for_each result)
     *   `{} -> {}`       → entry-point shape `{}` -> `{}`
     *   `{} -> {} <…>`   → entry-point with effects (most `main`s)
     *   `'a` (lone var)  → wholly unresolved
     */
    private fun isUninformativeType(typeStr: String): Boolean {
        // The only thing genuinely worth hiding is a bare unresolved
        // type variable like `'a` — the user gets nothing actionable
        // from it. Everything else (including `{}`, `{} -> {}`, and
        // entry-function shapes) is information the user explicitly
        // wants surfaced as an inlay.
        if (typeStr.matches(Regex("'[a-z][a-zA-Z0-9_]*"))) return true
        return false
    }

    /**
     * Emit a `: T` inlay at the end of a multi-line expression's last line —
     * used for the trailing type of a multi-line lambda body / chain so the
     * user can see the result type without scrolling to the let line.
     *
     * No textEdits — this is a read-only info hint, not an annotation point.
     */
    private fun emitTrailingTypeHint(
        type: com.mezze.language.ast.MezzeType,
        anchorLine: Int,
        anchorCol: Int,
        sourceLines: List<String>,
        typeAliases: Map<String, TypeAliasInfo>,
        hints: MutableList<InlayHint>
    ) {
        val typeStr = prettyPrintTypeWithAliases(type, typeAliases)
        if (isUninformativeType(typeStr)) return
        val lineIdx = (anchorLine - 1).coerceAtLeast(0).coerceAtMost(sourceLines.lastIndex)
        val hint = InlayHint()
        hint.position = Position(lineIdx, anchorCol)
        hint.setLabel(": $typeStr")
        hint.kind = InlayHintKind.Type
        hint.paddingLeft = true
        hints.add(hint)
    }

    private fun collectPipeHints(
        expr: com.mezze.language.typedast.TypedExpr,
        hints: MutableList<InlayHint>,
        typeAliases: Map<String, TypeAliasInfo>,
        sourceLines: List<String> = emptyList()
    ) {
        when (expr) {
            is com.mezze.language.typedast.TypedPipe -> {
                // Show type at end of left side if it's on a different line from right
                if (expr.left.loc.endLine != expr.right.loc.startLine) {
                    val typeStr = prettyPrintTypeWithAliases(expr.left.type, typeAliases)
                    // Use the same uninformative filter as the rest of the
                    // file (bare unresolved typevars only). Polymorphic
                    // shapes like `Option 'a` or `[Int]` are useful — they
                    // tell the user exactly what's flowing through the pipe.
                    if (!isUninformativeType(typeStr)) {
                        val hint = InlayHint()
                        hint.position = Position(expr.left.loc.endLine - 1, expr.left.loc.endCol)
                        hint.setLabel(": $typeStr")
                        hint.kind = InlayHintKind.Type
                        hint.paddingLeft = true
                        hints.add(hint)
                    }
                }
                collectPipeHints(expr.left, hints, typeAliases, sourceLines)
                collectPipeHints(expr.right, hints, typeAliases, sourceLines)
            }
            is com.mezze.language.typedast.TypedBlock -> {
                for (stmt in expr.statements) {
                    when (stmt) {
                        is com.mezze.language.typedast.TypedStatement.Let -> {
                            // Same UX as top-level lets — anchor `: T` after the
                            // binding name on the let-line. Skip when the source
                            // line already shows an explicit annotation
                            // (`let x: Int = ...`), otherwise the inlay duplicates
                            // what the user already wrote.
                            if (sourceLines.isNotEmpty() &&
                                !hasExplicitAnnotation(sourceLines, stmt.name, stmt.loc.startLine)
                            ) {
                                emitLetNameHint(
                                    name = stmt.name,
                                    type = stmt.type,
                                    declStartLine = stmt.loc.startLine,
                                    sourceLines = sourceLines,
                                    typeAliases = typeAliases,
                                    hints = hints
                                )
                            }
                            collectPipeHints(stmt.value, hints, typeAliases, sourceLines)
                        }
                        is com.mezze.language.typedast.TypedStatement.Expr -> collectPipeHints(stmt.expr, hints, typeAliases, sourceLines)
                    }
                }
                collectPipeHints(expr.result, hints, typeAliases, sourceLines)
            }
            is com.mezze.language.typedast.TypedCall -> {
                // Multi-line method/dot chain: when the call's argument record
                // contains the receiver as a field and that receiver lives on
                // an earlier line than the call itself, emit a hint at the end
                // of the receiver's line. Same UX as pipes — each chain segment
                // gets its intermediate type annotated.
                //
                // For chains like `nums\n.first {}\n.is_some {}` we want a hint
                // at the end of every segment line. The typed AST nests as
                // TypedCall(is_some, {it = TypedCall(first, {it = nums})}). The
                // recursion below visits both the outer receiver-field value
                // (which is the inner call) and walks deeper into the inner
                // call's own arg record — producing one hint per segment.
                val arg = expr.arg
                if (arg is com.mezze.language.typedast.TypedRecord) {
                    for (entry in arg.entries) {
                        if (entry is com.mezze.language.typedast.TypedRecordEntry.Field) {
                            val v = entry.value
                            if (v.loc.endLine < expr.loc.endLine) {
                                val rawType = v.type
                                val resolvedType: MezzeType =
                                    if (isPlaceholderType(rawType) && v is com.mezze.language.typedast.TypedCall) {
                                        val ft = v.function.type as? MezzeType.FunctionType
                                        ft?.returnType ?: rawType
                                    } else rawType
                                val typeStr = prettyPrintTypeWithAliases(resolvedType, typeAliases)
                                if (!isUninformativeType(typeStr) && typeStr != "unknown") {
                                    val hint = InlayHint()
                                    hint.position = Position(v.loc.endLine - 1, v.loc.endCol)
                                    hint.setLabel(": $typeStr")
                                    hint.kind = InlayHintKind.Type
                                    hint.paddingLeft = true
                                    hints.add(hint)
                                }
                            }
                        }
                    }
                }
                collectPipeHints(expr.function, hints, typeAliases, sourceLines)
                collectPipeHints(expr.arg, hints, typeAliases, sourceLines)
            }
            is com.mezze.language.typedast.TypedRecord -> {
                // Recurse into record entry values so chained method calls
                // hidden inside arg records (the receiver-field of a method
                // call) still get visited.
                for (entry in expr.entries) {
                    if (entry is com.mezze.language.typedast.TypedRecordEntry.Field) {
                        collectPipeHints(entry.value, hints, typeAliases, sourceLines)
                    }
                }
            }
            is com.mezze.language.typedast.TypedFieldAccess -> collectPipeHints(expr.record, hints, typeAliases, sourceLines)
            is com.mezze.language.typedast.TypedLambda -> collectPipeHints(expr.body, hints, typeAliases, sourceLines)
            is com.mezze.language.typedast.TypedMatch -> {
                collectPipeHints(expr.scrutinee, hints, typeAliases, sourceLines)
                for (case in expr.cases) collectPipeHints(case.body, hints, typeAliases, sourceLines)
            }
            is com.mezze.language.typedast.TypedIf -> {
                collectPipeHints(expr.condition, hints, typeAliases, sourceLines)
                collectPipeHints(expr.thenBranch, hints, typeAliases, sourceLines)
                collectPipeHints(expr.elseBranch, hints, typeAliases, sourceLines)
            }
            is com.mezze.language.typedast.TypedPerform -> collectPipeHints(expr.body, hints, typeAliases, sourceLines)
            else -> {} // leaf nodes
        }
    }

    /**
     * True if `t` is an unresolved-type placeholder that the TypedAstBuilder
     * left behind (e.g. `unknown`, `ret`, `op_*`). These shouldn't be shown
     * as inlay hints — fall back to other type sources where possible.
     */
    private fun isPlaceholderType(t: MezzeType): Boolean {
        if (t !is MezzeType.VarType) return false
        val n = t.variable.name
        return n == "unknown" || n == "ret" || n.startsWith("op_")
    }

    // ========== Rename ==========

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            val newName = params.newName
            val state = documents[uri] ?: return@supplyAsync null

            val lines = state.content.lines()
            if (position.line >= lines.size) return@supplyAsync null
            val oldName = extractWordAt(lines[position.line], position.character) ?: return@supplyAsync null

            val typedModule = (state.typeCheckResult ?: lastSuccessfulResult[uri])?.typedModule

            // Step 1: resolve the hash under the cursor — same logic as
            // references(). If we get a hash we can do a workspace-wide
            // rename via the usages table.
            val targetHash: com.mezze.language.typedast.Hash? = run {
                if (typedModule == null) return@run null
                val lookupLine = position.line + 1
                val lookupCol = position.character + 1
                when (val r = TypedAstLookup.findAtPosition(typedModule, lookupLine, lookupCol)) {
                    is TypedAstLookup.LookupResult.Expr -> {
                        val e = r.expr
                        if (e is com.mezze.language.typedast.TypedVar) {
                            (e.binding as? com.mezze.language.typedast.BindingRef.ByHash)?.hash
                        } else null
                    }
                    else -> null
                }
            } ?: typedModule?.decls
                ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
                ?.firstOrNull { it.name == oldName }
                ?.hash

            // Step 2: collect every edit location. Top-level names with a
            // resolved hash use the usages index for cross-file rename;
            // local names (no hash) stay in-file.
            //
            // We group edits by file URI in a single map so the returned
            // WorkspaceEdit covers every file at once — that's what
            // distinguishes this from the old single-file rename.
            val editsByFile = LinkedHashMap<String, MutableList<TextEdit>>()
            val store = projectStore
            if (targetHash != null && store != null) {
                // Definition site lives in the typed module of the file
                // that owns the decl; for now we add the def site of the
                // current file if the hash matches a local decl.
                val defDecl = typedModule?.decls
                    ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
                    ?.firstOrNull { it.hash == targetHash }
                if (defDecl != null) {
                    val nameLen = oldName.length
                    val edit = TextEdit(
                        Range(
                            Position(defDecl.loc.startLine - 1, defDecl.loc.startCol - 1),
                            Position(defDecl.loc.startLine - 1, defDecl.loc.startCol - 1 + nameLen)
                        ),
                        newName
                    )
                    editsByFile.getOrPut(uri) { mutableListOf() }.add(edit)
                }

                for (row in store.getUsages(targetHash)) {
                    val rowUri = File(row.filePath).toURI().toString()
                    val edit = TextEdit(
                        Range(
                            Position(row.startLine - 1, row.startCol - 1),
                            Position(row.endLine - 1, row.endCol)
                        ),
                        newName
                    )
                    editsByFile.getOrPut(rowUri) { mutableListOf() }.add(edit)
                }
            }

            // Step 3: in-file TypedAST walk for use sites the usages index
            // can't supply. Two scenarios converge here:
            //   • Local bindings have no hash, so `targetHash` is null and
            //     no usages-index lookup happened.
            //   • Top-level decls have a hash, but the project hasn't
            //     persisted the usages table for this revision yet (e.g.
            //     the file was opened but never saved). In that case the
            //     def edit was added above but call sites were missed.
            //
            // Mirroring the references() fallback: walk the in-memory
            // TypedModule and add any TypedVar uses of `oldName` (or the
            // resolved hash) that aren't already in editsByFile.
            if (typedModule != null) {
                val existing = editsByFile[uri].orEmpty().map { it.range }.toSet()
                val locs = mutableListOf<Location>()
                collectReferencesInModule(typedModule, oldName, uri, locs)
                for (loc in locs) {
                    if (loc.range !in existing) {
                        editsByFile.getOrPut(uri) { mutableListOf() }
                            .add(TextEdit(loc.range, newName))
                    }
                }
            }

            if (editsByFile.isEmpty()) return@supplyAsync null

            WorkspaceEdit(editsByFile.mapValues { it.value.toList() })
        }
    }

    // ========== Semantic Tokens ==========

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val state = documents[uri] ?: return@supplyAsync SemanticTokens(emptyList())

            val data = mutableListOf<Int>()
            val parsedModule = state.parsedModule ?: return@supplyAsync SemanticTokens(emptyList())

            // Token type indices (must match the legend order in server capabilities)
            val KEYWORD = 0
            val FUNCTION = 1
            val VARIABLE = 2
            val TYPE = 3
            val STRING = 4
            val NUMBER = 5
            val COMMENT = 6
            val OPERATOR = 7
            val PROPERTY = 8
            val PARAMETER = 9
            val ENUM_MEMBER = 10

            // Modifier bits
            val DECLARATION = 1
            val DEFINITION = 2

            var prevLine = 0
            var prevCol = 0

            // Helper to add a token (LSP semantic tokens are delta-encoded)
            fun addToken(line: Int, col: Int, length: Int, tokenType: Int, modifiers: Int = 0) {
                val deltaLine = line - prevLine
                val deltaCol = if (deltaLine == 0) col - prevCol else col
                data.add(deltaLine)
                data.add(deltaCol)
                data.add(length)
                data.add(tokenType)
                data.add(modifiers)
                prevLine = line
                prevCol = col
            }

            // Walk parsed module and emit tokens
            for (decl in parsedModule.decls) {
                // Comments
                when (decl) {
                    is ParsedLetDecl -> {
                        for (comment in decl.leadingComments) {
                            addToken(comment.loc.startLine - 1, comment.loc.startCol - 1,
                                comment.loc.endCol - comment.loc.startCol + 1, COMMENT)
                        }
                        // "let" keyword
                        addToken(decl.loc.startLine - 1, decl.loc.startCol - 1,
                            if (decl.visibility == com.mezze.language.parsedast.Visibility.PUBLIC) 3 else 3, KEYWORD)
                        // Binding name
                        if (decl.isSimpleBinding) {
                            val nameLen = decl.variableName.length
                            // Approximate position: after "let " or "pub let "
                            val nameCol = if (decl.visibility == com.mezze.language.parsedast.Visibility.PUBLIC)
                                decl.loc.startCol + 7  // "pub let "
                            else
                                decl.loc.startCol + 3  // "let "
                            addToken(decl.loc.startLine - 1, nameCol, nameLen, FUNCTION, DECLARATION or DEFINITION)
                        }
                    }
                    is ParsedTypeDecl -> {
                        addToken(decl.loc.startLine - 1, decl.loc.startCol - 1, 4, KEYWORD) // "type"
                    }
                    is ParsedEffectDecl -> {
                        addToken(decl.loc.startLine - 1, decl.loc.startCol - 1, 6, KEYWORD) // "effect"
                    }
                    is ParsedAbilityDecl -> {
                        addToken(decl.loc.startLine - 1, decl.loc.startCol - 1, 7, KEYWORD) // "ability"
                    }
                    is ParsedUseDecl -> {
                        addToken(decl.loc.startLine - 1, decl.loc.startCol - 1, 3, KEYWORD) // "use"
                    }
                    else -> {}
                }
            }

            SemanticTokens(data)
        }
    }

    // ========== Utilities ==========

    private fun uriToPath(uri: String): Path? {
        return try {
            Paths.get(URI.create(uri))
        } catch (e: Exception) {
            null
        }
    }

    /** SHA-256 over the source bytes; matches DevRunner's sourceHash so
     *  cache.sqlite rows written by either tool share the same key. */
    private fun sha256Hex(content: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Read `[package].name` and the first `[[app]].path` from Mezze.toml
     * without pulling in a TOML library (avoids a cyclic LSP↔CLI
     * dependency). Both are simple keys; a focused regex is enough.
     *
     * Returns (packageName, firstAppPath). Either may be null if the file
     * is missing or the key isn't found — the LSP still works, it just
     * can't write cross-tool-shareable modulePaths for those cases.
     */
    private fun readMezzeTomlEntries(projectRoot: File): Pair<String?, String?> {
        val toml = File(projectRoot, "Mezze.toml").takeIf { it.exists() }
            ?: return null to null
        val text = try { toml.readText() } catch (_: Exception) { return null to null }

        val packageBlock = Regex("""\[package\][^\[]*""", RegexOption.MULTILINE)
            .find(text)?.value
        val pkg = packageBlock?.let {
            Regex("""name\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1)
        }

        // First [[app]] block's `path = "..."`.
        val appBlock = Regex("""\[\[app\]\][^\[]*""", RegexOption.MULTILINE)
            .find(text)?.value
        val entry = appBlock?.let {
            Regex("""path\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1)
        }

        return pkg to entry
    }
}
