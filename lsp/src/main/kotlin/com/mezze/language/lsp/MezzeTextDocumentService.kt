package com.mezze.language.lsp

import com.mezze.language.langservice.lookup.TypedAstLookup
import com.mezze.language.langservice.lookup.TypedAstScopeWalker
import com.mezze.language.langservice.hover.HoverInfo
import com.mezze.language.langservice.diagnostics.JsonDiagnosticFormatter
import com.mezze.language.langservice.diagnostics.DiagnosticJson
import com.mezze.language.langservice.diagnostics.Severity
import com.mezze.language.langservice.completion.CompletionEngine
import com.mezze.language.langservice.completion.CompletionContext
import com.mezze.language.langservice.completion.CompletionCandidate
import com.mezze.language.langservice.completion.CompletionKind
import com.mezze.language.langservice.completion.NeutralTextEdit

import com.mezze.language.ast.*
import com.mezze.language.ast.prettyPrintTypeWithAliases
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typechecker.ResolvedImport
import com.mezze.language.typechecker.ImportKind
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.diagnostics.display
import com.mezze.language.store.MezzeStore
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.codebase.StoreHydration
import com.mezze.language.store.StoreSerialization
import com.mezze.language.formatter.Formatter
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.parsedast.diagnostics.WarningCode
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
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
    // Shared Formatter — used by `formatting()` and by code-action helpers
    // (e.g. auto-import edit) so inserted text matches user-visible style.
    private val formatter = Formatter()
    private val typeChecker = TypeChecker()

    // Document state
    private val documents = ConcurrentHashMap<String, DocumentState>()

    // Last successful type check result per document (survives parse errors
    // for dot-completion). Bounded LRU so a long editing session that hops
    // across many files doesn't pin every TypedModule (each entry can be
    // multi-MB once stdlib imports get pulled in).
    //
    // Eviction order: insertion-by-access. The map is wrapped in a
    // synchronizedMap because LinkedHashMap isn't thread-safe; access is
    // infrequent enough that contention is fine. Reads through `[uri]` go
    // through `get`, which we override to also bump access order.
    private val LAST_SUCCESSFUL_LRU_CAP = 32
    private val lastSuccessfulResult = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, TypeCheckResult>(16, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, TypeCheckResult>?
            ): Boolean = size > LAST_SUCCESSFUL_LRU_CAP
        }
    )

    // didChange debouncing. Each keystroke cancels the previous pending
    // analysis and schedules a fresh one DEBOUNCE_MS in the future. The
    // single-thread scheduler keeps order — a slow analysis cannot get
    // overtaken by a faster later one.
    private val analysisScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mezze-lsp-analysis").apply { isDaemon = true }
    }
    private val pendingAnalysis = ConcurrentHashMap<String, ScheduledFuture<*>>()
    // Debounce after the last keystroke before re-analyzing. Balances two
    // costs: a full re-check (constraint solve + imports) is O(100s of ms) so
    // too-frequent is laggy, but a long window leaves position-bearing features
    // (CodeLens, inlay hints) anchored to the PRE-edit AST and visibly out of
    // sync until the re-check + refreshPositionFeatures() fires. 250ms is a
    // middle ground; the skip-unchanged guard keeps no-op edits free.
    // Display code for the "impl missing method" error (V0404) — used to
    // dispatch the auto-implement code action. Computed from the enum so it
    // can't drift from the source of truth.
    private val V0404_MISSING_METHOD_CODE: String =
        com.mezze.language.parsedast.diagnostics.ErrorCode.V0404_IMPL_MISSING_METHOD.display()

    // T0204 covers both variant extra-fields AND missing-fields; the branch that
    // offers "Add missing field(s)" guards on the message so it only fires for
    // the missing case. Computed from the enum so it can't drift.
    private val T0204_VARIANT_ARITY_CODE: String =
        com.mezze.language.parsedast.diagnostics.ErrorCode.T0204_VARIANT_CONSTRUCTOR_ARITY.display()

    // Configurable via workspace/didChangeConfiguration under the `mezze` key.
    // Defaults match the historical hardcoded behavior; a client that never
    // sends settings sees no change.
    @Volatile private var DEBOUNCE_MS: Long = 250
    @Volatile var inlayHintsEnabled: Boolean = true
        private set
    @Volatile var codeLensEnabled: Boolean = true
        private set

    /**
     * Apply a settings snapshot from the client (the `mezze` section of a
     * `workspace/didChangeConfiguration` payload). Unknown / absent keys keep
     * their current value. Called on the workspace-service thread; fields are
     * @Volatile so the analysis thread sees updates without extra locking.
     */
    fun applyConfiguration(settings: Map<String, Any?>) {
        (settings["debounceMs"] as? Number)?.let {
            // Clamp to a sane range; 0 would busy-recheck, huge values strand
            // position features on a stale AST.
            DEBOUNCE_MS = it.toLong().coerceIn(0L, 5000L)
        }
        (settings["inlayHints"] as? Map<*, *>)?.get("enabled")?.let {
            if (it is Boolean) inlayHintsEnabled = it
        }
        (settings["codeLens"] as? Map<*, *>)?.get("enabled")?.let {
            if (it is Boolean) codeLensEnabled = it
        }
    }
    // Content last handed to analyzeDocument per URI — lets the debounce skip a
    // redundant re-check when an edit round-trips to identical text (VS Code
    // sometimes emits didChange for no textual change, e.g. some cursor ops).
    private val lastAnalyzedContent = ConcurrentHashMap<String, String>()

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
     * Ask the editor to re-pull the position-bearing, whole-document features
     * (CodeLens, inlay hints, semantic tokens) after a re-analysis. Their
     * anchors come from the typed AST; when the AST is refreshed post-edit the
     * editor must re-request them or it keeps rendering pre-edit positions.
     * No-op / best-effort if the client isn't connected (tests) or doesn't
     * support a given refresh.
     */
    private fun refreshPositionFeatures() {
        if (!::client.isInitialized) return
        try { client.refreshCodeLenses() } catch (_: Exception) {}
        try { client.refreshInlayHints() } catch (_: Exception) {}
        try { client.refreshSemanticTokens() } catch (_: Exception) {}
    }

    private val progressCounter = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Run [body] with a `$/progress` spinner in the editor titled [title], so a
     * multi-hundred-ms re-check doesn't look frozen. Best-effort: if the client
     * isn't connected (tests) or progress fails, just run [body]. The Begin/End
     * are always paired via try/finally.
     */
    private fun <T> withProgress(title: String, body: () -> T): T {
        if (!::client.isInitialized) return body()
        val token = Either.forRight<String, Int>((progressCounter.incrementAndGet()).toInt())
        var created = false
        try {
            client.createProgress(WorkDoneProgressCreateParams(token)).get()
            created = true
            val begin = WorkDoneProgressBegin()
            begin.title = title
            begin.cancellable = false
            client.notifyProgress(ProgressParams(token, Either.forLeft(begin)))
        } catch (_: Exception) { /* progress unsupported — run without it */ }
        try {
            return body()
        } finally {
            if (created) {
                try { client.notifyProgress(ProgressParams(token, Either.forLeft(WorkDoneProgressEnd()))) } catch (_: Exception) {}
            }
        }
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
            com.mezze.language.typechecker.PrecompiledStdlib.setStore(projectStore!!)
            com.mezze.language.typechecker.ModuleResolver.setStore(projectStore!!)
            projectRootDir = projectRoot
            val (pkg, entry) = readMezzeTomlEntries(projectRoot)
            packageName = pkg
            entryFilePath = entry?.let { File(projectRoot, it).absolutePath }
            server.logInfo("Using project store at ${SQLiteMezzeStore.resolveSrcFile(projectRoot).absolutePath} (package=$packageName, entry=$entry)")

            // Stdlib hydration removed (#233): the previous code populated
            // ModuleTypeChecker.globalCache from cache.sqlite for every
            // `std::*` module — but used `cacheTypedModule` which sets
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
            server.logInfo("Project store opened: ${SQLiteMezzeStore.resolveSrcFile(rootFile).absolutePath}")
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
     * `mezze.clearCache`: wipe `.mez/store/src.sqlite`, `stdlib.sqlite`,
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
        com.mezze.language.typechecker.PrecompiledStdlib.setStore(projectStore!!)
        com.mezze.language.typechecker.ModuleResolver.setStore(projectStore!!)
        server.logInfo("Cleared cache at ${com.mezze.language.store.ProjectLayout.projectDir(root).absolutePath}")
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
        com.mezze.language.typechecker.PrecompiledStdlib.setStore(projectStore!!)
        com.mezze.language.typechecker.ModuleResolver.setStore(projectStore!!)
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
        lastAnalyzedContent[uri] = content

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

        // Capture the slot reference so the running task can self-evict
        // even if a later didChange overwrites the entry. Without this,
        // completed ScheduledFutures pin their captured `content` String
        // and the analyzeDocument closure forever — a real leak across
        // long editing sessions.
        lateinit var taskRef: ScheduledFuture<*>
        taskRef = analysisScheduler.schedule({
            try {
                // Skip the (expensive) re-check if the text is byte-identical to
                // what we last analyzed — a no-op edit shouldn't re-solve.
                if (lastAnalyzedContent[uri] == content) return@schedule
                val state = withProgress("Mezze: type-checking") {
                    analyzeDocument(uri, content, version, persistToStore = false)
                }
                lastAnalyzedContent[uri] = content
                documents[uri] = state
                publishDiagnostics(uri, state.diagnostics)
                // The re-check produced a fresh typed AST with up-to-date
                // positions. Tell the editor to re-request the position-bearing
                // features so their anchors don't drift against edited text —
                // without this, CodeLens/inlay-hints kept showing stale
                // positions (from the pre-edit AST) until an incidental
                // re-request, which read as "references go out of sync".
                refreshPositionFeatures()
            } finally {
                pendingAnalysis.remove(uri, taskRef)
            }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        pendingAnalysis[uri] = taskRef
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        server.logInfo("Document closed: $uri")

        // Cancel any in-flight analysis for this URI so its captured
        // content + closure can be GC'd promptly.
        pendingAnalysis.remove(uri)?.cancel(false)

        // Remove document state
        documents.remove(uri)

        // Drop the cached TypeCheckResult too — it pins a whole
        // TypedModule (multi-MB once stdlib imports are resolved) and was
        // the largest source of LSP-side memory growth across long
        // editing sessions.
        lastSuccessfulResult.remove(uri)
        lastAnalyzedContent.remove(uri)

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
                lastAnalyzedContent[uri] = content
                publishDiagnostics(uri, newState.diagnostics)
                refreshPositionFeatures()

                // Cross-file staleness: the saved module's exports may have
                // changed, but ModuleTypeChecker.globalCache is keyed by module
                // path and served on a path hit BEFORE re-checking — so open
                // dependents (files that `use` this module) would keep showing
                // diagnostics/hover computed against the OLD exports until a
                // full restart. Invalidate this module's cache entry and
                // re-analyze every open dependent so they pick up the change.
                invalidateAndReanalyzeDependents(uri)
            }
        }
    }

    /**
     * After a file is saved, drop its stale entry from the typechecker's global
     * module cache and re-analyze every OTHER open document that imports it, so
     * cross-file edits are reflected without a manual `mezze.restart`.
     */
    private fun invalidateAndReanalyzeDependents(savedUri: String) {
        try {
            val savedPath = moduleUriToPath(savedUri) ?: return
            // Drop the saved module's cached typed result so dependents that
            // re-typecheck below resolve its exports freshly.
            com.mezze.language.typechecker.module.ModuleTypeChecker
                .invalidateModulePaths(listOf(savedPath))

            // A dependent is any OTHER open doc whose resolved imports name the
            // saved module. Re-analyze each; its diagnostics/hover then reflect
            // the saved file's new exports.
            val dependents = documents.entries.filter { (depUri, depState) ->
                depUri != savedUri &&
                    depState.typeCheckResult?.resolvedImports?.any { it.modulePath == savedPath } == true
            }
            for ((depUri, depState) in dependents) {
                val fresh = analyzeDocument(depUri, depState.content, depState.version, persistToStore = false)
                documents[depUri] = fresh
                publishDiagnostics(depUri, fresh.diagnostics)
            }
            if (dependents.isNotEmpty()) {
                server.logInfo("Re-analyzed ${dependents.size} open dependent(s) of $savedPath after save")
                refreshPositionFeatures()
            }
        } catch (e: Exception) {
            server.logError("Cross-file re-analysis after save failed: ${e.message}")
        }
    }

    /**
     * Derive the `a::b::c` module path for an open document's URI, matching the
     * derivation `mez dev` / the store use. Returns null for files outside the
     * project's src/ (scratch files) or when project context isn't set up.
     */
    private fun moduleUriToPath(uri: String): String? {
        val filePath = uriToPath(uri) ?: return null
        val rootDir = projectRootDir ?: return null
        val pkgName = packageName ?: return null
        return com.mezze.language.codebase.ModulePath
            .derive(rootDir, filePath.toFile(), pkgName, entryFilePath)
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

        // Collect all parse errors as diagnostics. Tag with a stable `code`
        // so clients can branch (e.g. show a different inline hint for syntax
        // vs. type errors) without parsing the message. See #313.
        parseResult.errors.forEach { e ->
            val codeDisplay = e.code.display()
            diagnostics.add(createDiagnostic(
                message = e.errorMessage,
                severity = DiagnosticSeverity.Error,
                line = e.line - 1,  // LSP is 0-indexed
                column = e.column - 1,
                endColumn = e.column + e.length,
                // Prefer the stable P0xxx code; fall back to the legacy
                // "PARSE_ERROR" tag for un-migrated sites (UNCATEGORIZED).
                code = if (codeDisplay == "E0000") "PARSE_ERROR" else codeDisplay
            ))
        }

        // If there are parse errors, still try to type check the partial AST
        // This allows showing both parse errors and any type errors in successfully parsed code

        // Step 2: Type check
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
                        // Unused var / unused import render as greyed-out
                        // (DiagnosticTag.Unnecessary) at Hint severity, like most
                        // editors do for dead code; everything else is a Warning.
                        val code = warning.code
                        val isUnused = code == WarningCode.W0101_UNUSED_VARIABLE ||
                            code == WarningCode.W0102_UNUSED_IMPORT
                        val diag = createDiagnostic(
                            message = warning.message,
                            severity = if (isUnused) DiagnosticSeverity.Hint else DiagnosticSeverity.Warning,
                            line = line,
                            column = column,
                            endLine = endLine,
                            endColumn = endColumn,
                            // Pass the stable WarningCode (W0101, W0102, …) so the
                            // codeAction handler can offer a code-specific quick-fix.
                            code = code?.display()
                        )
                        if (isUnused) diag.tags = listOf(DiagnosticTag.Unnecessary)
                        diagnostics.add(diag)
                    }

                    typeCheckResult = TypeCheckResult(
                        resolvedImports = buildResolvedImports(result.importContext),
                        importedAbilities = result.importContext?.importedAbilities ?: emptyMap(),
                        importedEffects = result.importContext?.importedEffects ?: emptyMap(),
                        importedImpls = result.importContext?.importedImpls ?: emptyMap(),
                        allEffects = result.importContext?.let { ic ->
                            ic.importedEffects + ic.effectsByHash.values.associateBy { it.name }
                        } ?: emptyMap(),
                        // Critical: hover, inlay hints, find-references, rename and
                        // every other position-based feature read typedModule from
                        // this object. Without it, the entire LSP appears to "work"
                        // but produces no type info.
                        typedModule = result.typedModule,
                        loweredFunctions = result.loweredFunctions ?: emptyList(),
                        warnings = result.warnings.map {
                            com.mezze.language.typechecker.TypeCheckWarning(it.message, it.loc, it.code)
                        },
                        expectedTypeAt = result.expectedTypeAt,
                    )
                    // #159: a parse error inside a decl (e.g. a half-typed `y.`
                    // in a do-block) makes the parser recover to an EMPTY
                    // ParsedModule, which typechecks as Success with a 0-decl
                    // typedModule. Caching that as "last successful" CLOBBERS the
                    // real prior result, so mid-edit features (dot-completion,
                    // hover) that fall back to lastSuccessfulResult find nothing.
                    // Only cache when the new result carries at least as many
                    // top-level decls as the one we'd replace — a degenerate
                    // recovery module never overwrites a richer prior snapshot.
                    val newDeclCount = typeCheckResult!!.typedModule?.decls?.size ?: 0
                    val prevDeclCount = lastSuccessfulResult[uri]?.typedModule?.decls?.size ?: -1
                    if (newDeclCount >= prevDeclCount) {
                        lastSuccessfulResult[uri] = typeCheckResult!!
                    }

                    // Unused-import diagnostics now come from the typechecker's
                    // W0102 warning (UnusedImportLinter), published in the loop
                    // above with a Hint severity + Unnecessary tag. The LSP used
                    // to recompute them here — that produced duplicate
                    // diagnostics and could drift from the compiler's own
                    // unused-import rule. Removed.

                    // Deprecation diagnostics: mark deprecated declarations
                    diagnostics.addAll(deprecationDiagnostics(parsedModule))

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
                                        com.mezze.language.typechecker.TypeCheckWarning(it.message, it.loc, it.code)
                                    },
                                    importedAbilities = result.importContext?.importedAbilities ?: emptyMap(),
                                    importedEffects = result.importContext?.importedEffects ?: emptyMap(),
                                    importedImpls = result.importContext?.importedImpls ?: emptyMap(),
                                    allEffects = result.importContext?.let { ic ->
                                        ic.importedEffects + ic.effectsByHash.values.associateBy { it.name }
                                    } ?: emptyMap(),
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
                    // Route through createDiagnosticFromRecoveryError so
                    // errorType + metadata reach the client (see #313).
                    result.errors.forEach { error ->
                        diagnostics.add(createDiagnosticFromRecoveryError(error))
                    }
                    // Even on Failure, the typechecker may emit a partial
                    // typedModule for the decls that DID solve. Hand it to
                    // the LSP state so hover/inlay/cmd-click on surviving
                    // decls keep working — without this, one broken decl
                    // wipes out type info for the whole file.
                    val partialTyped = result.typedModule
                    if (partialTyped != null) {
                        // #159: carry the same import-derived context the Success
                        // path builds (abilities/effects/impls/resolved imports),
                        // so completion (`x.` ability methods) and hover keep
                        // working mid-edit — with no prior successful result to
                        // fall back on, this is the ONLY source of those.
                        val ic = result.importContext
                        typeCheckResult = TypeCheckResult(
                            resolvedImports = buildResolvedImports(ic),
                            importedAbilities = ic?.importedAbilities ?: emptyMap(),
                            importedEffects = ic?.importedEffects ?: emptyMap(),
                            importedImpls = ic?.importedImpls ?: emptyMap(),
                            allEffects = ic?.let {
                                it.importedEffects + it.effectsByHash.values.associateBy { e -> e.name }
                            } ?: emptyMap(),
                            typedModule = partialTyped,
                            expectedTypeAt = result.expectedTypeAt,
                        )
                        // Cache this partial result as a fallback too. A TYPE
                        // error (not a parse error) — e.g. `"…$(x)…"` where x:Int
                        // fails the Str-interpolation check — still parses and
                        // fully types the module: x IS Int here. When the user
                        // then types `.` and the file becomes a PARSE error
                        // (empty module), this cached result is the only place
                        // dot-completion can recover x's type. Same >=-decl guard
                        // as the Success path so a degenerate recovery module
                        // never clobbers a richer prior snapshot.
                        val newDeclCount = partialTyped.decls.size
                        val prevDeclCount = lastSuccessfulResult[uri]?.typedModule?.decls?.size ?: -1
                        if (newDeclCount > 0 && newDeclCount >= prevDeclCount) {
                            lastSuccessfulResult[uri] = typeCheckResult!!
                        }
                    }
                }
            }

        } catch (e: TypeCheckError) {
            // Fallback for unexpected exceptions
            diagnostics.add(createDiagnosticFromTypeError(e))
        } catch (e: com.mezze.language.typechecker.ModuleError) {
            // Module-level failures (missing module, private import, typo'd
            // export name, etc.) used to fall through to the catch-all
            // below and surface as "Internal error: ..." with no position
            // or code. Route them through a dedicated diagnostic builder
            // so VS Code can show the typed error code AND the structured
            // FixHints (Rename / AddPub) we attach in ModuleResolver.kt.
            diagnostics.add(createDiagnosticFromModuleError(e))
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
            val typeParams = typeDecl.typeParams.map { MezzeType.VarType(TypeVar.freshWithName(it)) }
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
     * Generate diagnostics for declarations marked with `#{ deprecated: True }`.
     * The diagnostic uses DiagnosticTag.Deprecated so editors render strikethrough.
     */
    private fun deprecationDiagnostics(parsed: com.mezze.language.parsedast.ParsedModule): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        for (decl in parsed.decls) {
            val meta = decl.metadata ?: continue
            if (!meta.isDeprecated) continue

            val name = when (decl) {
                is ParsedLetDecl -> if (decl.isSimpleBinding) decl.variableName else null
                is ParsedTypeDecl -> decl.name
                is ParsedAbilityDecl -> decl.name
                is ParsedEffectDecl -> decl.name
                is ParsedPerformerDecl -> decl.performerName
                else -> null
            } ?: continue

            val message = buildString {
                append("'$name' is deprecated")
                meta.since?.let { append(" since $it") }
                meta.note?.let { append(". $it") }
            }

            val diag = createDiagnostic(
                message = message,
                severity = DiagnosticSeverity.Hint,
                line = decl.loc.startLine - 1,
                column = decl.loc.startCol - 1,
                endLine = decl.loc.endLine - 1,
                endColumn = decl.loc.endCol
            )
            diag.tags = listOf(DiagnosticTag.Deprecated)
            out.add(diag)
        }
        return out
    }

    /**
     * Create a diagnostic from a recovery error.
     *
     * `code` carries `errorType.name` so clients can branch on diagnostic
     * kind (e.g. CLOSED_RECORD_FIELD_MISMATCH) without parsing the message.
     * `data` forwards the typechecker's `metadata` map — fields like
     * "availableFields" / "field" let editors offer typo fixes without
     * re-deriving them from the message text. (#313 step 7.)
     */
    private fun createDiagnosticFromRecoveryError(error: TypeChecker.TypeCheckError): Diagnostic {
        // Route through the shared JsonDiagnosticFormatter so the editor squiggle
        // is derived exactly like `mez check --json`. Code-action payload
        // (metadata + fixHints) is attached separately — see attachCodeActionData.
        val diag = JsonDiagnosticFormatter.fromTypeCheckError(error)
            .toLspDiagnostic(codeFallback = errorTypeFallback(error.errorType))
        attachCodeActionData(diag, error.metadata, error.fixHints)
        return diag
    }

    /**
     * Convert typed [FixHint]s to the plain-map wire form `codeAction`
     * decodes back. Reflects the on-the-wire JSON shape:
     *
     *     { "kind": "Rename", "loc": {...}, "candidates": ["xs"] }
     *     { "kind": "Import", "loc": {...}, "name": "xs",
     *       "candidateModules": ["std::list"] }
     */
    private fun encodeFixHints(
        hints: List<com.mezze.language.parsedast.diagnostics.FixHint>
    ): List<Map<String, Any?>> = hints.map { hint ->
        val loc = mapOf(
            "startLine" to hint.loc.startLine,
            "startCol" to hint.loc.startCol,
            "endLine" to hint.loc.endLine,
            "endCol" to hint.loc.endCol,
        )
        when (hint) {
            is com.mezze.language.parsedast.diagnostics.FixHint.Rename -> mapOf(
                "kind" to "Rename",
                "loc" to loc,
                "candidates" to hint.candidates,
            )
            is com.mezze.language.parsedast.diagnostics.FixHint.Import -> mapOf(
                "kind" to "Import",
                "loc" to loc,
                "name" to hint.name,
                "candidateModules" to hint.candidateModules,
            )
            is com.mezze.language.parsedast.diagnostics.FixHint.WrapInPerform -> mapOf(
                "kind" to "WrapInPerform",
                "loc" to loc,
                "effectName" to hint.effectName,
                "candidatePerformers" to hint.candidatePerformers,
            )
            is com.mezze.language.parsedast.diagnostics.FixHint.AddEffectToAnnotation -> mapOf(
                "kind" to "AddEffectToAnnotation",
                "loc" to loc,
                "effectName" to hint.effectName,
            )
            is com.mezze.language.parsedast.diagnostics.FixHint.AddPub -> mapOf(
                "kind" to "AddPub",
                "loc" to loc,
                "declModulePath" to hint.declModulePath,
                "declName" to hint.declName,
            )
            is com.mezze.language.parsedast.diagnostics.FixHint.FillMatchArms -> mapOf(
                "kind" to "FillMatchArms",
                "loc" to loc,
                "missingConstructors" to hint.missingConstructors,
            )
            is com.mezze.language.parsedast.diagnostics.FixHint.RemoveRange -> mapOf(
                "kind" to "RemoveRange",
                "loc" to loc,
                "label" to hint.label,
            )
            is com.mezze.language.parsedast.diagnostics.FixHint.WrapInConstructor -> mapOf(
                "kind" to "WrapInConstructor",
                "loc" to loc,
                "constructor" to hint.constructor,
            )
            is com.mezze.language.parsedast.diagnostics.FixHint.WrapInMethodCall -> mapOf(
                "kind" to "WrapInMethodCall",
                "loc" to loc,
                "methods" to hint.methods,
            )
        }
    }

    /**
     * Publish diagnostics to the client.
     */
    private fun publishDiagnostics(uri: String, diagnostics: List<Diagnostic>) {
        client.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
    }

    /**
     * Build a diagnostic for a ModuleError thrown during typecheck. We
     * stash structured FixHints (typo Rename / cross-module AddPub) into
     * `Diagnostic.data` the same way TypeCheckError diagnostics do — the
     * codeAction handler then surfaces one-click lightbulb fixes.
     */
    private fun createDiagnosticFromModuleError(error: com.mezze.language.typechecker.ModuleError): Diagnostic {
        val loc = error.errorLoc
        val diag = createDiagnostic(
            message = error.message ?: "Module error",
            severity = DiagnosticSeverity.Error,
            // No loc => point at the file's first character; better than the
            // old (0,0) catch-all because at least the code/data are useful.
            line = (loc?.startLine ?: 1) - 1,
            column = (loc?.startCol ?: 1) - 1,
            endLine = loc?.endLine?.minus(1),
            endColumn = loc?.endCol,
            code = error.code.display().takeUnless { it == "E0000" },
        )
        if (error.fixHints.isNotEmpty()) {
            diag.data = mapOf("fixHints" to encodeFixHints(error.fixHints))
        }
        return diag
    }

    /**
     * Create a diagnostic from a type error.
     */
    private fun createDiagnosticFromTypeError(error: TypeCheckError): Diagnostic {
        // #98: the typechecker's span-less advice strings (error.hints) are
        // appended to the message by toLspDiagnostic — the shared model keeps
        // them in a separate `hints` field, but LSP has no hints channel.
        // fixHints (with edits) still drive codeAction quick-fixes via data.
        val diag = JsonDiagnosticFormatter.fromTypeCheckError(error)
            .toLspDiagnostic(codeFallback = errorTypeFallback(error.errorType))
        attachCodeActionData(diag, error.metadata, error.fixHints)
        return diag
    }

    /**
     * Adapt the transport-neutral [DiagnosticJson] (the shared source of truth
     * that also drives `mez check --json`) into an lsp4j [Diagnostic], so the
     * editor squiggle is derived exactly like the CLI's JSON diagnostics. Reuses
     * [createDiagnostic] for range clamping so that logic stays in one place.
     *
     * Two LSP-specific touches live here, not in the shared model: hints are
     * appended to the message (LSP has no separate hints channel), and `code`
     * falls back to the legacy ErrorType name for unmigrated UNCATEGORIZED sites
     * (the shared model leaves those null). DiagnosticJson.code (ErrorCode when
     * assigned, else null) plus this fallback reproduces the old diagnosticCode.
     */
    private fun DiagnosticJson.toLspDiagnostic(codeFallback: String? = null): Diagnostic {
        val loc = location
        val fullMessage = if (hints.isEmpty()) message
            else message + hints.joinToString("") { "\n  hint: $it" }
        return createDiagnostic(
            message = fullMessage,
            severity = if (severity == Severity.WARNING) DiagnosticSeverity.Warning else DiagnosticSeverity.Error,
            line = (loc?.startLine ?: 1) - 1,
            column = (loc?.startCol ?: 1) - 1,
            endLine = loc?.endLine?.minus(1),
            endColumn = loc?.endCol,
            code = code ?: codeFallback,
        )
    }

    /**
     * Legacy ErrorType name used as a [Diagnostic.code] fallback when the shared
     * model produced no code (an unmigrated UNCATEGORIZED site). GENERAL is
     * suppressed because `mezze(GENERAL)` is pure UI noise.
     */
    private fun errorTypeFallback(errorType: ErrorType): String? =
        errorType.name.takeUnless { it == ErrorType.GENERAL.name }

    /**
     * Attach structured code-action payload to [Diagnostic.data]: the legacy
     * `metadata` map plus typed `fixHints` encoded as the plain-map wire form
     * the codeAction handler decodes. lsp4j ships `data` to the client verbatim;
     * encoding fixHints as maps keeps kotlinx-serialization off the wire.
     */
    private fun attachCodeActionData(
        diag: Diagnostic,
        metadata: Map<String, Any>,
        fixHints: List<com.mezze.language.parsedast.diagnostics.FixHint>,
    ) {
        val data = mutableMapOf<String, Any?>()
        if (metadata.isNotEmpty()) data.putAll(metadata)
        if (fixHints.isNotEmpty()) data["fixHints"] = encodeFixHints(fixHints)
        if (data.isNotEmpty()) diag.data = data
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
                val markdown = MarkupContent(MarkupKind.MARKDOWN, withGoToDefLink(hoverInfo, state, position))
                Hover(markdown)
            } else {
                null
            }
        }
    }



    /**
     * View of an open document that the shared [HoverInfo] can read.
     *
     * The hooks are what only an editor can provide: the document URI, on-disk
     * resolution for definition links, and the project store for cross-module
     * docs. A caller without them still gets types, ability specialisation and
     * in-file docs.
     */
    private fun DocumentState.toHoverContext() = HoverInfo.Context(
        content = content,
        typeCheckResult = typeCheckResult,
        typeAliases = typeAliases,
        parsedModule = parsedModule,
        lastSuccessful = lastSuccessfulResult[uri],
        selfUri = uri,
        resolveFileUri = ::resolveExistingFileUri,
        docFromStore = ::findDocFromStore,
        storeAbilityDecls = ::storeAbilityDecls,
    )

    /**
     * Hover markdown for a position, via the shared [HoverInfo].
     *
     * The rendering itself lives in :typechecker so the CLI and the WASM
     * playground get the same answer. What stays here is what only an editor
     * can supply: the document URI, on-disk file resolution for definition
     * links, and the project store for cross-module docs.
     */
    private fun findHoverInfo(state: DocumentState, position: Position): String? =
        HoverInfo.at(state.toHoverContext(), position.line, position.character)

    /**
     * Typed ability decls from the project store, for ops whose owning ability
     * is not in the open file. Empty when there is no store or it cannot be
     * read — hover then falls back to what the current module knows.
     */
    private fun storeAbilityDecls(): List<com.mezze.language.typedast.TypedAbilityDecl> {
        val store = projectStore ?: return emptyList()
        return try {
            store.getDeclsByKind(com.mezze.language.store.DeclKind.ABILITY).mapNotNull { row ->
                val bytes = store.getBlob(row.hash)?.typed ?: return@mapNotNull null
                try {
                    com.mezze.language.store.StoreSerialization.deserializeTypedAbilityDecl(bytes)
                } catch (_: Throwable) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Append a "Go to definition →" link to the hover markdown when a real
     * definition exists somewhere OTHER than the hovered position (so hovering
     * a decl's own name doesn't show a self-link). Uses a plain `file://…#Ln`
     * link (not a `command:` link) because LSP hover markdown is untrusted in
     * VS Code and `command:` links wouldn't fire — file links do, and jump to
     * the exact line. Best-effort: on any failure, return the hover unchanged.
     */
    private fun withGoToDefLink(hover: String, state: DocumentState, position: Position): String {
        return try {
            val defs = findDefinitions(state, position)
            val target = defs.firstOrNull { loc ->
                loc.uri != state.uri ||
                    position.line < loc.range.start.line || position.line > loc.range.end.line ||
                    position.character < loc.range.start.character || position.character > loc.range.end.character
            } ?: return hover
            // VS Code renders file links with a line fragment (#L<1-based>).
            val line1 = target.range.start.line + 1
            val col1 = target.range.start.character + 1
            "$hover\n\n[Go to definition →](${target.uri}#L$line1,$col1)"
        } catch (_: Exception) { hover }
    }



    /**
     * Look up the doc comment for a declaration by name from the store.
     * Used for imported symbols where the ParsedModule isn't available.
     */
    private fun findDocFromStore(name: String): String? {
        val store = projectStore ?: return null
        val decls = store.searchDeclsByName(name)
        return decls.firstOrNull { it.declName == name }?.doc
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
    /** True if [type] still contains any unresolved type variable. */







    /**
     * Hover text for the target type used at a `perform X in …` site (`X` is
     * the type an `impl <Effect> for X` handles — there is no "performer"
     * keyword in Mezze; it's just an impl target). Renders each impl as real
     * Mezze — `impl <Effect> for X where { op: Param -> Ret }` — plus a
     * clickable link to the effect decl. Returns null if `name` isn't the
     * target of any known effect impl (so hover falls through).
     */
    /** Ability-decl analogue of [effectDeclByName] (local → imported → scan). */






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
        val word = HoverInfo.extractWordAt(line, position.character) ?: return emptyList()
        val result = state.typeCheckResult ?: lastSuccessfulResult[state.uri]

        // 0. Ability / effect NAME used in a constraint (`with Eq 'a`) or an
        // effect row (`<State, Log>`) → its decl. The typed-AST lookup below
        // would otherwise resolve the enclosing binding on the same line and
        // shadow the ability/effect. Only kicks in when the word is a known
        // ability/effect name AND sits in a `with …`/`<…>` position.
        run {
            val pm = state.parsedModule ?: return@run
            val beforeWord = line.substring(0, position.character.coerceIn(0, line.length))
            val inConstraint = Regex("\\bwith\\b").containsMatchIn(beforeWord)
            val inEffectRow = beforeWord.lastIndexOf('<') > beforeWord.lastIndexOf('>')
            if (!inConstraint && !inEffectRow) return@run
            pm.decls.filterIsInstance<ParsedAbilityDecl>().find { it.name == word }
                ?.let { return listOf(locToLocation(state.uri, it.loc)) }
            pm.decls.filterIsInstance<ParsedEffectDecl>().find { it.name == word }
                ?.let { return listOf(locToLocation(state.uri, it.loc)) }
        }

        // 0b. Performer NAME at a `perform X in …` site → the `impl <Effect>
        // for X` decl (local), else the imported performer's source. The
        // typed-AST lookup below would otherwise resolve `X` as a nullary
        // constructor and jump nowhere useful. Gated on the word sitting in a
        // perform-handler position (after `perform`, before `in`).
        run {
            val pm = state.parsedModule ?: return@run
            val beforeWord = line.substring(0, position.character.coerceIn(0, line.length))
            val atHandlerPos = Regex("\\bperform\\b[^)]*$").containsMatchIn(beforeWord) &&
                !Regex("\\bin\\b").containsMatchIn(beforeWord.substringAfterLast("perform"))
            if (!atHandlerPos) return@run
            // Local impl for this performer.
            pm.decls.filterIsInstance<ParsedPerformerDecl>().find { it.performerName == word }
                ?.let { return listOf(locToLocation(state.uri, it.loc)) }
            // Imported performer. Performers live in TypeCheckResult.importedImpls
            // (keyed by name), not resolvedImports. The decl's own loc carries
            // the defining file, so jump straight there.
            val importedPerf = result?.importedImpls?.get(word)
            if (importedPerf != null) {
                // Only jump to the decl's own file when it resolves to a file
                // that EXISTS (stdlib decls carry stale/relative loc.file that
                // would otherwise make the editor offer to create a new file);
                // else fall back to the import statement in this document.
                resolveExistingFileUri(importedPerf.loc.file)?.let { defUri ->
                    return listOf(locToLocation(defUri, importedPerf.loc))
                }
                findImportLoc(state, word)?.let { return listOf(it) }
            }
        }

        // 0c. Qualified effect-op call `Log.log { … }`. The typed-AST lookup
        // resolves the whole call to its return value, so goto-def lands nowhere
        // useful. On the op name → the op's sig in the effect decl; on the effect
        // qualifier → the effect decl. Effect decl comes from effectDeclByName
        // (local, allEffects, or a parsed-module scan for perform-only imports).
        run {
            val cur = position.character.coerceIn(0, line.length)
            var ws = cur; while (ws > 0 && (line[ws - 1].isLetterOrDigit() || line[ws - 1] == '_')) ws--
            var we = cur; while (we < line.length && (line[we].isLetterOrDigit() || line[we] == '_')) we++
            val beforeWord = line.substring(0, ws)
            val after = line.substring(we)

            // Cursor on the op/method name (`<Effect|Ability>.<op>`).
            val qualMatch = Regex("([A-Z][A-Za-z0-9_]*)\\.$").find(beforeWord)
            if (qualMatch != null) {
                val qual = qualMatch.groupValues[1]
                HoverInfo.effectDeclByName(state.toHoverContext(), result, qual)?.operations?.find { it.name == word }
                    ?.let { return listOf(locToLocationCross(it.loc)) }
                HoverInfo.abilityDeclByName(state.toHoverContext(), result, qual)?.operations?.find { it.name == word }
                    ?.let { return listOf(locToLocationCross(it.loc)) }
            }
            // Cursor on the qualifier (`<Effect|Ability>.<op>`) → its decl.
            if (Regex("^\\.[a-z_]").containsMatchIn(after)) {
                HoverInfo.effectDeclByName(state.toHoverContext(), result, word)?.let { return listOf(locToLocationCross(it.loc)) }
                HoverInfo.abilityDeclByName(state.toHoverContext(), result, word)?.let { return listOf(locToLocationCross(it.loc)) }
            }
        }

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
                            // An ability OPERATION with no receiver. Which impl runs
                            // is decided by the dictionary the caller supplies, so
                            // there is no single implementation to jump to — go to the
                            // ability's DECLARATION, which is the thing the reference
                            // actually names. Matched by content hash.
                            is com.mezze.language.typedast.BindingRef.AbilityOp -> {
                                val decl = typedModule.abilityDecls
                                    .firstOrNull { it.hash == binding.abilityHash }
                                if (decl != null) return listOf(locToLocation(state.uri, decl.loc))
                            }
                            // Local let bindings / lambda params / match pattern
                            // bindings in the current file. Resolve the binder
                            // STRUCTURALLY from the cursor var's de Bruijn
                            // coordinate (BindingRef.Local), not by first name
                            // match — otherwise a shadowed local jumps to the
                            // wrong (outer) binder. (L2)
                            is com.mezze.language.typedast.BindingRef.Local -> {
                                val key = resolveBinderKeyAtPosition(typedModule, position)
                                if (key is BinderKey.LocalBinder) {
                                    return listOf(locToLocation(state.uri, key.loc))
                                }
                                // Coordinate didn't resolve (e.g. producer-scheme
                                // edge or a synthetic Local(0,0) placeholder) —
                                // fall back to the legacy name search.
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
                                // Hash wasn't in the decls table (decls only
                                // covers prelude-transitively-loaded stdlib
                                // modules — effects/codecs/etc. miss). Fall
                                // back to the in-memory ModuleTypeChecker
                                // cache, which has every transitively
                                // imported module already parsed and typed.
                                // We need a (modulePath, name) — recover it
                                // from the resolved-imports map.
                                val import = result?.resolvedImports?.find { it.localName == expr.name }
                                if (import != null) {
                                    val mem = findCrossModuleDefInMemory(import.modulePath, import.originalName)
                                    if (mem != null) return listOf(mem)
                                }
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

        // 1b. Impl-method → the op's signature in the ability/effect decl.
        // Cursor on an op NAME inside an impl body (`show = { … }`) jumps to
        // `show: { it: 'a } -> Str` in the ability decl (or the effect op).
        val parsedModuleForImpl = state.parsedModule
        if (parsedModuleForImpl != null) {
            val lineNo = position.line + 1
            for (decl in parsedModuleForImpl.decls) {
                if (lineNo < decl.loc.startLine || lineNo > decl.loc.endLine) continue
                when (decl) {
                    is com.mezze.language.parsedast.ParsedImplAbilityDecl -> {
                        val op = decl.operations.find { it.name == word && it.loc.startLine == lineNo }
                        if (op != null) {
                            val abilityDecl = parsedModuleForImpl.decls
                                .filterIsInstance<ParsedAbilityDecl>().find { it.name == decl.abilityName }
                            val sig = abilityDecl?.operations?.find { it.name == word }
                            if (sig != null) return listOf(locToLocation(state.uri, sig.loc))
                        }
                    }
                    is ParsedPerformerDecl -> {
                        val op = decl.operations.find { it.name == word && it.loc.startLine == lineNo }
                        if (op != null) {
                            val effectDecl = parsedModuleForImpl.decls
                                .filterIsInstance<ParsedEffectDecl>().find { it.name == decl.effect.localName }
                            val sig = effectDecl?.operations?.find { it.name == word }
                            if (sig != null) return listOf(locToLocation(state.uri, sig.loc))
                        }
                    }
                    else -> {}
                }
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
        // first (via store), then the in-memory typechecker cache, and
        // only as a last resort fall back to the import line.
        if (result != null) {
            val import = result.resolvedImports.find { it.localName == word }
            if (import != null) {
                val target = findCrossModuleDefLocationByName(import.modulePath, import.originalName)
                if (target != null) return listOf(target)
                val mem = findCrossModuleDefInMemory(import.modulePath, import.originalName)
                if (mem != null) return listOf(mem)
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
     * Last-resort lookup against the typechecker's in-memory module cache.
     * The decls/usages tables only cover what StdlibPrecompiler eagerly
     * walked (prelude-transitive set — about 24 modules); effect modules,
     * codec/concurrent helpers etc. never made it in. But once the LSP has
     * typechecked the user's file, every transitive import — including
     * `std::effects::log` for a `use std::effects::log::println` — sits in
     * `ModuleTypeChecker.getAllCachedModules()` already parsed and typed.
     * Just walk it. No disk read, no re-parse.
     *
     * Returns null if the module isn't cached (the user hasn't actually
     * imported anything from there yet) or no decl with that name exists.
     */
    private fun findCrossModuleDefInMemory(modulePath: String, declName: String): Location? {
        val cached = com.mezze.language.typechecker.module.ModuleTypeChecker
            .getAllCachedModules()[modulePath] ?: return null
        // Prefer the parsed module's locs (full source spans) when available.
        // Fall back to the typed decls' locs when parsedModule is null
        // (hydrated-from-cache path skips parsing).
        val uri = resolveModulePathToUri(modulePath) ?: return null
        val parsed = cached.parsedModule
        if (parsed != null) {
            for (decl in parsed.decls) {
                val (name, loc) = when (decl) {
                    is ParsedLetDecl -> if (decl.isSimpleBinding) Pair(decl.variableName, decl.loc) else continue
                    is ParsedTypeDecl -> Pair(decl.name, decl.loc)
                    is ParsedAbilityDecl -> Pair(decl.name, decl.loc)
                    is ParsedEffectDecl -> Pair(decl.name, decl.loc)
                    else -> continue
                }
                if (name == declName) {
                    return Location(uri, Range(
                        Position(loc.startLine - 1, loc.startCol - 1),
                        Position(loc.endLine - 1, loc.endCol)
                    ))
                }
            }
        }
        // Hydrated-cache fallback: walk the TypedModule's decls.
        val typed = cached.typedModule ?: return null
        for (decl in typed.decls) {
            val (name, loc) = when (decl) {
                is com.mezze.language.typedast.TypedLetDecl -> Pair(decl.name, decl.loc)
                is com.mezze.language.typedast.TypedTypeDecl -> Pair(decl.name, decl.loc)
                is com.mezze.language.typedast.TypedAbilityDecl -> Pair(decl.name, decl.loc)
                is com.mezze.language.typedast.TypedEffectDecl -> Pair(decl.name, decl.loc)
                else -> continue
            }
            if (name == declName) {
                return Location(uri, Range(
                    Position(loc.startLine - 1, loc.startCol - 1),
                    Position(loc.endLine - 1, loc.endCol)
                ))
            }
        }
        return null
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
    /**
     * The running toolchain's own `stdlib/` dir, if this `mez lsp` is a versioned
     * install (`~/.mezze/toolchains/<v>/bin/mez` → `…/<v>/stdlib`). `mez lsp` runs
     * in-process in the CLI binary, so the running executable is the active
     * (pinned) toolchain — we self-locate its stdlib the same way MezzeHome finds
     * its lib. Null for a dev/JVM launch or a flat install, where the shared
     * `~/.mezze/stdlib` fallback applies.
     */
    private val toolchainStdlibDir: java.io.File? by lazy {
        val exe = runCatching {
            ProcessHandle.current().info().command().orElse(null)?.let { java.io.File(it) }
        }.getOrNull()
        val binDir = exe?.parentFile ?: return@lazy null
        val stdlib = java.io.File(binDir.parentFile ?: binDir, "stdlib")
        stdlib.takeIf { it.isDirectory }
    }

    private fun resolveModulePathToUri(modulePath: String): String? {
        val segments = modulePath.split("::")
        if (segments.isEmpty()) return null

        val candidates = mutableListOf<java.io.File>()

        val ws = server.getWorkspaceRoot()
        if (segments[0] == "std") {
            val rest = segments.drop(1).joinToString("/") + ".mz"
            // 1. The ACTIVE toolchain's own stdlib (toolchains/<v>/stdlib), so a
            //    project pinned to an older toolchain cmd-clicks into THAT
            //    version's stdlib source, not whichever the flat copy last saw.
            toolchainStdlibDir?.let { candidates += java.io.File(it, "std/$rest") }
            // 2. ~/.mezze/stdlib/std/<rest> — flat, shared fallback.
            val home = System.getProperty("user.home")
            if (home != null) {
                candidates += java.io.File("$home/.mezze/stdlib/std/$rest")
            }
            // 3. <workspace>/stdlib/std/<rest>
            if (ws != null) {
                candidates += java.io.File(ws, "stdlib/std/$rest")
            }
        } else {
            val rel = segments.joinToString("/") + ".mz"
            if (ws != null) {
                candidates += java.io.File(ws, "src/$rel")
                candidates += java.io.File(ws, rel)

                // Dependency source: <ws>/.deps/mezze/<pkg>-*/… mirrors
                // ModuleResolver, so cmd-click on `use greet::{x}` /
                // `use greet::sub::{x}` lands in the dep's real (symlinked) source.
                val pkg = segments[0]
                val depDirs = java.io.File(ws, ".deps/mezze").listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("$pkg-") }
                    ?: emptyList()
                for (depDir in depDirs) {
                    if (segments.size == 1) {
                        // Bare `use <pkg>` → the dep's [lib] entry.
                        com.mezze.language.typechecker.depLibEntryFile(depDir.toPath())
                            ?.let { candidates += it.toFile() }
                    } else {
                        val rest = segments.drop(1).joinToString("/") + ".mz"
                        candidates += java.io.File(depDir, "src/$rest")
                        candidates += java.io.File(depDir, rest)
                    }
                }
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

    // ==================================================================
    // Scope-aware local-binding resolution (L1/L2 — BindingRef-driven).
    //
    // The typed AST every TypedVar carries a `BindingRef` — for in-file
    // locals a `Local(depth, index)` de Bruijn coordinate produced by
    // TypedAstBuilder.resolveBinding. Resolving/matching locals by BARE
    // NAME (the old findLocalBindingLoc / collectReferencesInExpr) picks
    // the WRONG binder under shadowing. These helpers mirror the producer's
    // scheme exactly so navigation/highlight/count follow the real binder.
    //
    // Producer scheme (TypedAstBuilder):
    //   * scope stack of (names, isLambdaBoundary) frames.
    //   * lambda params push a frame with isLambdaBoundary = true.
    //   * a do-block pushes ONE non-lambda frame only when the stack is
    //     empty (top-level block); its `let`s are APPENDED to the current
    //     innermost frame (they do NOT open a new frame).
    //   * a match case pushes a non-lambda frame with its pattern bindings.
    //   * depth = number of lambda-boundary frames strictly INNER to the
    //     binder's frame (relative to the use site); index = position of
    //     the name within its frame's name list (first occurrence).
    //
    // TypedAstBuilder never emits Param/Capture in a TypedVar.binding (those
    // arise only in the post-lowering mono/IR layer, which the LSP does not
    // consume) — so for the LSP a local is always Local(depth, index).
    // ==================================================================

    /** Canonical identity of the binder a reference resolves to. */
    private sealed interface BinderKey {
        /** An in-file local binder, identified by its unique source Loc. */
        data class LocalBinder(val loc: Loc) : BinderKey
        /** A top-level / cross-module binder, identified by content hash. */
        data class TopLevel(val hash: com.mezze.language.typedast.Hash) : BinderKey
    }

    private class ScopeFrame(val isLambdaBoundary: Boolean) {
        val names = mutableListOf<String>()
        val locs = mutableListOf<Loc>()
        fun add(name: String, loc: Loc) { names.add(name); locs.add(loc) }
    }

    /**
     * Walks one decl's expression tree with a scope stack mirroring
     * TypedAstBuilder.resolveBinding. Invokes [onVar] for every TypedVar with
     * the binder Loc it resolves to (null when the reference is not an in-file
     * Local — e.g. ByHash / Native / an out-of-range coordinate).
     */
    private fun walkWithScopes(
        root: com.mezze.language.typedast.TypedExpr,
        onVar: (com.mezze.language.typedast.TypedVar, Loc?) -> Unit
    ) {
        val stack = ArrayDeque<ScopeFrame>()

        fun resolveLocalLoc(depth: Int, index: Int): Loc? {
            // Walk out from the innermost frame, counting lambda boundaries,
            // until we've crossed `depth` of them — that frame holds the binder.
            var crossed = 0
            for (i in stack.indices.reversed()) {
                val frame = stack[i]
                if (crossed == depth) {
                    // Producer resolves `index` against the frame that HOLDS the
                    // name; the first frame at this depth boundary that contains
                    // a slot at `index` is the one. In practice the binder frame
                    // is exactly this one.
                    return frame.locs.getOrNull(index)
                }
                if (frame.isLambdaBoundary) crossed++
            }
            return null
        }

        fun visit(e: com.mezze.language.typedast.TypedExpr) {
            when (e) {
                is com.mezze.language.typedast.TypedVar -> {
                    val loc = when (val b = e.binding) {
                        is com.mezze.language.typedast.BindingRef.Local ->
                            resolveLocalLoc(b.depth, b.index)
                        else -> null
                    }
                    onVar(e, loc)
                }
                is com.mezze.language.typedast.TypedLambda -> {
                    val frame = ScopeFrame(isLambdaBoundary = true)
                    for (p in e.params) frame.add(p.name, p.loc)
                    stack.addLast(frame)
                    visit(e.body)
                    stack.removeLast()
                }
                is com.mezze.language.typedast.TypedBlock -> {
                    // The producer pushes a fresh non-lambda frame for a do-block
                    // ONLY when the scope stack is empty (a top-level block); an
                    // inner do-block appends its lets to the current frame.
                    val pushed = stack.isEmpty()
                    if (pushed) stack.addLast(ScopeFrame(isLambdaBoundary = false))
                    val frame = stack.last()
                    for (stmt in e.statements) {
                        when (stmt) {
                            is com.mezze.language.typedast.TypedStatement.Let -> {
                                visit(stmt.value)
                                if (stmt.name !in frame.names) frame.add(stmt.name, stmt.loc)
                            }
                            is com.mezze.language.typedast.TypedStatement.Expr -> visit(stmt.expr)
                        }
                    }
                    visit(e.result)
                    if (pushed) stack.removeLast()
                }
                is com.mezze.language.typedast.TypedMatch -> {
                    visit(e.scrutinee)
                    for (case in e.cases) {
                        val frame = ScopeFrame(isLambdaBoundary = false)
                        for (b in case.bindings) frame.add(b.name, b.loc)
                        stack.addLast(frame)
                        for (ilb in case.ifLetBindings) visit(ilb.expr)
                        case.guard?.let { visit(it) }
                        visit(case.body)
                        stack.removeLast()
                    }
                }
                is com.mezze.language.typedast.TypedIf -> {
                    visit(e.condition); visit(e.thenBranch); visit(e.elseBranch)
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
                is com.mezze.language.typedast.TypedStringLit -> {
                    for (part in e.parts) {
                        if (part is com.mezze.language.typedast.TypedStringPart.Interpolation) visit(part.expr)
                    }
                }
                else -> { /* leaves */ }
            }
        }
        visit(root)
    }

    /**
     * The [BinderKey] the TypedVar at [position] refers to, or null if the
     * cursor is not on an in-file local/top-level var occurrence. Locates the
     * enclosing top-level decl, then resolves the var's BindingRef structurally.
     */
    private fun resolveBinderKeyAtPosition(
        module: com.mezze.language.typedast.TypedModule,
        position: Position
    ): BinderKey? {
        val line = position.line + 1
        val col = position.character + 1
        for (decl in module.decls) {
            if (decl !is com.mezze.language.typedast.TypedLetDecl) continue
            var result: BinderKey? = null
            walkWithScopes(decl.value) { v, binderLoc ->
                if (result != null) return@walkWithScopes
                if (!locContains(v.loc, line, col)) return@walkWithScopes
                result = binderKeyForVar(v, binderLoc)
            }
            if (result != null) return result
        }
        return null
    }

    /** Map a resolved TypedVar (+ its binder loc) to a canonical BinderKey. */
    private fun binderKeyForVar(
        v: com.mezze.language.typedast.TypedVar,
        binderLoc: Loc?
    ): BinderKey? = when (val b = v.binding) {
        is com.mezze.language.typedast.BindingRef.Local ->
            binderLoc?.let { BinderKey.LocalBinder(it) }
        is com.mezze.language.typedast.BindingRef.ByHash -> BinderKey.TopLevel(b.hash)
        else -> null
    }

    private fun locContains(loc: Loc, line: Int, col: Int): Boolean {
        if (line < loc.startLine || line > loc.endLine) return false
        if (line == loc.startLine && col < loc.startCol) return false
        if (line == loc.endLine && col > loc.endCol) return false
        return true
    }

    /**
     * Collect every occurrence (definition site + uses) that co-refers with
     * [target], resolving each TypedVar's binder structurally rather than by
     * bare name. Name equality is used only as a cheap pre-filter.
     */
    private fun collectBindingOccurrences(
        module: com.mezze.language.typedast.TypedModule,
        target: BinderKey,
        name: String,
        uri: String,
        locations: MutableList<Location>
    ) {
        for (decl in module.decls) {
            if (decl !is com.mezze.language.typedast.TypedLetDecl) continue
            // A top-level decl whose hash matches is itself a def-site occurrence.
            if (target is BinderKey.TopLevel && decl.hash == target.hash && decl.name == name) {
                locations.add(locToLocation(uri, decl.loc))
            }
            // The binder's own definition site (for a local target).
            if (target is BinderKey.LocalBinder && target.loc.file == decl.loc.file) {
                // The binder loc lives inside this decl's value tree; the walk
                // below emits references, and the binder loc itself is added when
                // it coincides with a use. Definition sites (let/param/pattern)
                // are surfaced via the dedicated def-loc emission below.
            }
            walkWithScopes(decl.value) { v, binderLoc ->
                if (v.name != name) return@walkWithScopes
                val key = binderKeyForVar(v, binderLoc) ?: return@walkWithScopes
                if (key == target) locations.add(locToLocation(uri, v.loc))
            }
        }
        // For a local target, also include the binder's definition site itself.
        if (target is BinderKey.LocalBinder) {
            if (locations.none { sameRange(it, uri, target.loc) }) {
                locations.add(0, locToLocation(uri, target.loc))
            }
        }
    }

    private fun sameRange(location: Location, uri: String, loc: Loc): Boolean {
        val expected = locToLocation(uri, loc)
        return location.uri == expected.uri && location.range == expected.range
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

    /**
     * Like [locToLocation] but resolves the URI from the loc's OWN `file` —
     * used when jumping to a decl that lives in another (e.g. stdlib) source
     * file. Falls back to the current document's URI if the loc carries no file.
     */
    private fun locToLocationCross(loc: Loc, fallbackUri: String? = null): Location {
        val uri = resolveExistingFileUri(loc.file) ?: fallbackUri ?: ""
        return locToLocation(uri, loc)
    }

    /**
     * Turn a decl's own `loc.file` into a `file://` URI that points at a file
     * that ACTUALLY EXISTS on disk — or null if it can't. A decl loaded from the
     * stdlib often carries a `loc.file` that is a stale JAR-extraction temp path
     * (deleted between sessions) or a relative `stdlib/std/…` path; naively
     * `File(f).toURI()`-ing those yields a URI to a non-existent file, and the
     * editor then offers to CREATE it on cmd-click (bug report: goto-def on a
     * stdlib symbol "tries to make a new file"). We instead:
     *   1. accept an already-`file:`-scheme URI only if that file exists,
     *   2. accept an absolute/relative path only if it exists,
     *   3. otherwise recover a stable stdlib source path from the `std::…`
     *      segment of the file path via resolveModulePathToUri.
     * Returns null when nothing resolves — callers then fall back to the current
     * document rather than a phantom path.
     */
    private fun resolveExistingFileUri(file: String?): String? {
        if (file.isNullOrEmpty() || file == "built-in") return null
        // A decl defined in a currently-OPEN document is always linkable, even
        // if its buffer is unsaved (so the file may not exist on disk yet). This
        // covers local decls in the file being edited — without it, a link to a
        // same-file effect/ability decl would be dropped to plain text.
        val asUri = if (file.startsWith("file:")) file else File(file).toURI().toString()
        if (documents.containsKey(asUri)) return asUri
        // Already a file: URI — keep only if the target exists.
        if (file.startsWith("file:")) {
            return try {
                if (File(java.net.URI(file)).isFile) file else null
            } catch (_: Exception) { null }
        }
        // A plain path — keep only if it exists on disk.
        File(file).takeIf { it.isFile }?.let { return it.toURI().toString() }
        // Recover a stable stdlib path: pull the `std/…/foo.mz` tail and map it
        // back to a `std::…` module path the resolver understands.
        val normalized = file.replace('\\', '/')
        val stdIdx = normalized.indexOf("std/")
        if (stdIdx >= 0) {
            val rel = normalized.substring(stdIdx).removeSuffix(".mz")   // std/effects/log
            val modulePath = rel.split('/').joinToString("::")          // std::effects::log
            resolveModulePathToUri(modulePath)?.let { return it }
        }
        return null
    }

    /**
     * True if `name` is declared as an ability or effect in this parsed module.
     * Used to decide whether references/rename should treat the word as an
     * ability/effect NAME (which the typed AST doesn't carry as a reference)
     * rather than a value binding.
     */
    private fun isAbilityOrEffectName(pm: com.mezze.language.parsedast.ParsedModule?, name: String): Boolean {
        if (pm == null) return false
        return pm.decls.any {
            (it is ParsedAbilityDecl && it.name == name) ||
            (it is ParsedEffectDecl && it.name == name)
        }
    }

    /**
     * Every source occurrence of an ability/effect NAME across one document,
     * as [Range]s. Covers the four syntactic positions the name can appear in:
     *
     *  1. the `ability X`/`effect X` declaration header,
     *  2. impl headers — `impl X for …` (ability) and `impl … for … <X>` /
     *     the effect in a `ParsedPerformerDecl`,
     *  3. `with X 'a` ability constraints in a type signature,
     *  4. `<X, …>` effect rows in a type signature.
     *
     * (1) and (2) come from parsed-decl locs; (3) and (4) are found by a
     * constrained textual scan that only accepts a hit when it sits in a
     * `with …` clause or an unclosed `<…>` row — the same gate goto-def uses,
     * so we never match the name inside strings, comments, or plain code.
     */
    private fun findAbilityEffectNameRanges(
        content: String,
        pm: com.mezze.language.parsedast.ParsedModule?,
        name: String
    ): List<Range> {
        if (pm == null) return emptyList()
        val ranges = LinkedHashSet<Range>()
        val nameLen = name.length

        fun addAt(startLine1: Int, startCol1: Int) {
            ranges.add(Range(
                Position(startLine1 - 1, startCol1 - 1),
                Position(startLine1 - 1, startCol1 - 1 + nameLen)
            ))
        }

        // (1) + (2): declaration + impl headers, from parsed locs. The name is
        // the first identifier after the keyword, so scan the header line for
        // the first `\bname\b` occurrence within the decl's loc range.
        val lines = content.lines()
        fun addFirstOnLine(line1: Int) {
            val idx0 = line1 - 1
            if (idx0 < 0 || idx0 >= lines.size) return
            val col = Regex("\\b${Regex.escape(name)}\\b").find(lines[idx0])?.range?.first ?: return
            addAt(line1, col + 1)
        }
        for (decl in pm.decls) {
            when (decl) {
                is ParsedAbilityDecl -> if (decl.name == name) addFirstOnLine(decl.loc.startLine)
                is ParsedEffectDecl -> if (decl.name == name) addFirstOnLine(decl.loc.startLine)
                is com.mezze.language.parsedast.ParsedImplAbilityDecl ->
                    if (decl.abilityName == name) addFirstOnLine(decl.loc.startLine)
                is ParsedPerformerDecl ->
                    if (decl.effect.localName == name) addFirstOnLine(decl.loc.startLine)
                else -> {}
            }
        }

        // (3) + (4): `with X` constraints and `<X>` effect rows anywhere in the
        // file. Scan every occurrence of the word; keep it only when the text
        // before it on that line is a `with …` clause or an unclosed `<…>`.
        val wordRe = Regex("\\b${Regex.escape(name)}\\b")
        lines.forEachIndexed { idx0, lineText ->
            for (m in wordRe.findAll(lineText)) {
                val before = lineText.substring(0, m.range.first)
                val inConstraint = Regex("\\bwith\\b").containsMatchIn(before)
                val inEffectRow = before.lastIndexOf('<') > before.lastIndexOf('>')
                if (inConstraint || inEffectRow) addAt(idx0 + 1, m.range.first + 1)
            }
        }

        return ranges.toList()
    }

    // ========== Completion ==========

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            val state = documents[uri]

            server.logInfo("[completion] request uri=${uri.substringAfterLast('/')} pos=${position.line}:${position.character} trigger='${params.context?.triggerCharacter}'")

            // Completion is computed by the transport-neutral CompletionEngine in
            // :langservice; the LSP only builds a CompletionContext (wiring the
            // document state + the editor-flow hooks: `use`-line formatting,
            // strip-dot receiver recovery, logging) and adapts the neutral
            // CompletionCandidates back into lsp4j CompletionItems. When the doc
            // isn't open there is no buffer to complete against.
            if (state == null) {
                return@supplyAsync Either.forLeft<List<CompletionItem>, CompletionList>(emptyList())
            }
            val ctx = CompletionContext(
                uri = uri,
                content = state.content,
                line = position.line,
                character = position.character,
                typeCheckResult = state.typeCheckResult,
                typeAliases = state.typeAliases,
                parsedModule = state.parsedModule,
                lastSuccessful = lastSuccessfulResult[uri],
                store = projectStore,
                parser = parser,
                typeChecker = typeChecker,
                workspaceRoot = server.getWorkspaceRoot(),
                uriToPath = { uriToPath(it) },
                formatUseSnippet = { formatter.formatSnippet(it) },
                log = { server.logInfo(it) },
            )
            Either.forLeft<List<CompletionItem>, CompletionList>(
                CompletionEngine.complete(ctx).map { it.toCompletionItem() }
            )
        }
    }

    // ===== neutral CompletionCandidate -> lsp4j CompletionItem adapter =====
    // The only place the completion path touches lsp4j: the engine (in
    // :langservice) produces transport-neutral candidates; this maps them to the
    // wire form. CompletionKind lines up 1:1 with CompletionItemKind by name.

    private fun CompletionKind.toLsp(): CompletionItemKind = when (this) {
        CompletionKind.Field -> CompletionItemKind.Field
        CompletionKind.Variable -> CompletionItemKind.Variable
        CompletionKind.Function -> CompletionItemKind.Function
        CompletionKind.Method -> CompletionItemKind.Method
        CompletionKind.Struct -> CompletionItemKind.Struct
        CompletionKind.EnumMember -> CompletionItemKind.EnumMember
        CompletionKind.Interface -> CompletionItemKind.Interface
        CompletionKind.Class -> CompletionItemKind.Class
        CompletionKind.Keyword -> CompletionItemKind.Keyword
        CompletionKind.Module -> CompletionItemKind.Module
        CompletionKind.Snippet -> CompletionItemKind.Snippet
        CompletionKind.Enum -> CompletionItemKind.Enum
        CompletionKind.Constructor -> CompletionItemKind.Constructor
        CompletionKind.TypeParameter -> CompletionItemKind.TypeParameter
    }

    private fun NeutralTextEdit.toLsp(): TextEdit =
        TextEdit(Range(Position(startLine, startCol), Position(endLine, endCol)), newText)

    private fun CompletionCandidate.toCompletionItem(): CompletionItem {
        val item = CompletionItem(label)
        item.kind = kind.toLsp()
        detail?.let { item.detail = it }
        documentation?.let { doc ->
            item.documentation = if (documentationMarkdown) {
                Either.forRight(MarkupContent(MarkupKind.MARKDOWN, doc))
            } else {
                Either.forLeft(doc)
            }
        }
        insertText?.let { item.insertText = it }
        if (snippet) item.insertTextFormat = InsertTextFormat.Snippet
        sortText?.let { item.sortText = it }
        if (additionalEdits.isNotEmpty()) item.additionalTextEdits = additionalEdits.map { it.toLsp() }
        textEdit?.let { item.textEdit = Either.forLeft(it.toLsp()) }
        resolveData?.let { item.data = it }
        return item
    }

    /**
     * Lazily fill in a completion item's documentation when the client asks for
     * details. The item carries a resolve key (`{uri, name}`) in its `data`;
     * we look the decl's doc-comment up in that document's parsed module (and
     * fall back to the store), plus surface the type signature already in
     * `detail` as a fenced code block. Items that already carry documentation
     * (e.g. stdlib auto-import, which had the doc in hand) pass through
     * untouched.
     */
    override fun resolveCompletionItem(item: CompletionItem): CompletableFuture<CompletionItem> {
        if (item.documentation != null) return CompletableFuture.completedFuture(item)
        val data = dataAsMap(item.data) ?: return CompletableFuture.completedFuture(item)
        val uri = data["uri"] as? String ?: return CompletableFuture.completedFuture(item)
        val name = data["name"] as? String ?: return CompletableFuture.completedFuture(item)
        val state = documents[uri] ?: return CompletableFuture.completedFuture(item)

        val doc = HoverInfo.findDocForDecl(state.parsedModule, name) ?: findDocFromStore(name)
        val sig = item.detail?.takeIf { it.isNotBlank() && it != "type" && it != "effect" && it != "ability" }
        val body = buildString {
            if (sig != null) append("```mezze\n$name : $sig\n```")
            if (doc != null) {
                if (isNotEmpty()) append("\n\n")
                append(HoverInfo.renderDocMarkdown(doc))
            }
        }
        if (body.isNotBlank()) {
            item.documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, body))
        }
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

            // Add type symbols from the parsed module, with record fields /
            // variant constructors as CHILD symbols so the outline / breadcrumb
            // shows the type's shape (#100).
            state.parsedModule?.decls?.filterIsInstance<ParsedTypeDecl>()?.forEach { decl ->
                val (kind, children) = when (val def = decl.definition) {
                    is com.mezze.language.parsedast.ParsedRecordDef -> SymbolKind.Struct to
                        def.fields.mapNotNull { f ->
                            createDocumentSymbol(f.name, SymbolKind.Field, f.loc)
                        }
                    is com.mezze.language.parsedast.ParsedVariantDef -> SymbolKind.Enum to
                        def.constructors.mapNotNull { c ->
                            createDocumentSymbol(c.name, SymbolKind.EnumMember, c.loc)
                        }
                    is com.mezze.language.parsedast.ParsedNewtypeDef -> SymbolKind.Struct to emptyList()
                    is com.mezze.language.parsedast.ParsedTypeAliasDef -> SymbolKind.TypeParameter to emptyList()
                }
                val symbol = createDocumentSymbol(decl.name, kind, decl.loc, children = children)
                if (symbol != null) symbols.add(Either.forRight(symbol))
            }

            // Add effect symbols with their operations as CHILD symbols, so the
            // outline/breadcrumb shows the effect's shape (`State > get, set`).
            state.parsedModule?.decls?.filterIsInstance<ParsedEffectDecl>()?.forEach { decl ->
                val children = decl.operations.mapNotNull { op ->
                    createDocumentSymbol(op.name, SymbolKind.Method, op.loc)
                }
                val symbol = createDocumentSymbol(decl.name, SymbolKind.Interface, decl.loc, children = children)
                if (symbol != null) symbols.add(Either.forRight(symbol))
            }

            // Add ability symbols with their operations as CHILD symbols.
            state.parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>()?.forEach { decl ->
                val children = decl.operations.mapNotNull { op ->
                    createDocumentSymbol(op.name, SymbolKind.Method, op.loc)
                }
                val symbol = createDocumentSymbol(decl.name, SymbolKind.Interface, decl.loc, children = children)
                if (symbol != null) symbols.add(Either.forRight(symbol))
            }

            // Add impls to the outline (`impl Show for Int`, `impl State for
            // Counter`) with their implemented ops as children — Class symbols
            // so they read as a distinct group under the breadcrumb.
            state.parsedModule?.decls?.forEach { decl ->
                when (decl) {
                    is com.mezze.language.parsedast.ParsedImplAbilityDecl -> {
                        val target = decl.targetTypes.joinToString(", ") { HoverInfo.formatTypeAnnotation(it) }
                        val name = "impl ${decl.abilityName ?: "?"}" + if (target.isNotEmpty()) " for $target" else ""
                        val children = decl.operations.mapNotNull { createDocumentSymbol(it.name, SymbolKind.Method, it.loc) }
                        createDocumentSymbol(name, SymbolKind.Class, decl.loc, children = children)
                            ?.let { symbols.add(Either.forRight(it)) }
                    }
                    is ParsedPerformerDecl -> {
                        val name = "impl ${decl.effect.localName} for ${decl.performerName}"
                        val children = decl.operations.mapNotNull { createDocumentSymbol(it.name, SymbolKind.Method, it.loc) }
                        createDocumentSymbol(name, SymbolKind.Class, decl.loc, children = children)
                            ?.let { symbols.add(Either.forRight(it)) }
                    }
                    else -> {}
                }
            }

            symbols
        }
    }

    private fun createDocumentSymbol(
        name: String,
        kind: SymbolKind,
        location: Loc?,
        detail: String? = null,
        children: List<DocumentSymbol> = emptyList()
    ): DocumentSymbol? {
        if (location == null) return null

        val range = Range(
            Position(location.startLine - 1, location.startCol - 1),
            Position(location.startLine - 1, location.startCol + name.length - 1)
        )

        return DocumentSymbol(name, kind, range, range, detail, children)
    }

    // ========== Code Lens ==========

    /**
     * #109: code lenses over the typed decls.
     *  - Each top-level function → "N references" (from the store's usages
     *    index, keyed by the decl's content hash).
     *  - Each ability declaration → "N implementations" (count of TypedAbilityImpl
     *    whose abilityHash matches).
     * Counts are cheap (one store query / a group-by), so computed eagerly here
     * rather than deferred to codeLensResolve. Clicking a lens runs the standard
     * find-references / go-to-implementation command with the decl's position.
     */
    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> {
        return CompletableFuture.supplyAsync {
            if (!codeLensEnabled) return@supplyAsync emptyList()
            val uri = params.textDocument.uri
            val state = documents[uri] ?: return@supplyAsync emptyList()
            val typedModule = (state.typeCheckResult ?: lastSuccessfulResult[uri])?.typedModule
                ?: return@supplyAsync emptyList()
            val lenses = mutableListOf<CodeLens>()
            val store = projectStore
            val sourceLines = state.content.lines()

            // The CodeLens command runs the client's reference/implementation
            // provider AT the command's position, which resolves the symbol
            // under that position. A decl's `loc` starts at the `let`/`ability`
            // keyword — clicking there resolves no symbol, so the peek opened
            // empty (looked like "nothing happens"). Anchor the lens at the
            // NAME token instead: scan the decl's start line for the name.
            fun anchorFor(loc: com.mezze.language.parsedast.Loc, name: String): Position {
                val lineIdx = loc.startLine - 1
                val line = sourceLines.getOrNull(lineIdx)
                if (line != null) {
                    // Find `name` as a whole word at/after the decl start column.
                    val from = (loc.startCol - 1).coerceIn(0, line.length)
                    var idx = line.indexOf(name, from)
                    // Guard against matching a substring inside a longer ident.
                    while (idx >= 0) {
                        val before = if (idx == 0) ' ' else line[idx - 1]
                        val after = line.getOrElse(idx + name.length) { ' ' }
                        val isBoundary = !before.isLetterOrDigit() && before != '_' &&
                                         !after.isLetterOrDigit() && after != '_'
                        if (isBoundary) return Position(lineIdx, idx)
                        idx = line.indexOf(name, idx + 1)
                    }
                }
                return Position(lineIdx, (loc.startCol - 1).coerceAtLeast(0))
            }

            // Only emit a lens when the count is > 0 — a "0 references" lens
            // is dead weight that opens an empty peek when clicked. This also
            // naturally suppresses ALL reference lenses in single-file mode
            // (no project store → count is always 0 → nothing to click into).
            fun lensAt(loc: com.mezze.language.parsedast.Loc, name: String, count: Int, singular: String, plural: String, cmd: String) {
                if (count <= 0) return
                val anchor = anchorFor(loc, name)
                // The lens is DISPLAYED at the decl's start line, but its command
                // points at the NAME position so the provider resolves.
                val range = Range(Position(loc.startLine - 1, (loc.startCol - 1).coerceAtLeast(0)),
                                  Position(loc.startLine - 1, (loc.startCol - 1).coerceAtLeast(0)))
                val title = if (count == 1) "1 $singular" else "$count $plural"
                lenses.add(CodeLens(range, Command(title, cmd, listOf(uri, anchor)), null))
            }

            // "N references" over each top-level function. Count from the
            // project store (cross-file) OR, when the store has nothing (no
            // project / not yet indexed / single-file mode), fall back to an
            // in-file TypedVar walk — the SAME source `references()` uses when
            // clicked, so the count matches the peek. Previously the count was
            // store-only, so single-file mode showed a misleading "0
            // references" on functions that clicking would happily peek.
            for (decl in typedModule.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()) {
                val storeCount = store?.getUsages(decl.hash)?.size ?: 0
                val count = if (storeCount > 0) storeCount else {
                    val locs = mutableListOf<Location>()
                    // Count references to THIS top-level binder (by hash), not
                    // every same-named occurrence — a local `x` shadowing a
                    // top-level `x` must not inflate the top-level's count. (L1)
                    collectBindingOccurrences(
                        typedModule, BinderKey.TopLevel(decl.hash), decl.name, uri, locs
                    )
                    locs.size
                }
                lensAt(decl.loc, decl.name, count, "reference", "references", "mezze.showReferences")
            }

            // "N implementations" over each ability declaration.
            val implsByAbility = typedModule.abilityImpls.groupingBy { it.abilityHash }.eachCount()
            for (ability in typedModule.abilityDecls) {
                val count = implsByAbility[ability.hash] ?: 0
                lensAt(ability.loc, ability.name, count, "implementation", "implementations", "mezze.showImplementations")
            }

            // "N performers" over each effect declaration — the effect-graph
            // navigation aid. Counts in-file performers (this module's
            // performerDecls grouped by effectHash) unioned with the store's
            // cross-module performer index.
            val perfsByEffect = typedModule.performerDecls.groupingBy { it.effectHash }.eachCount()
            for (effect in typedModule.effectDecls) {
                val local = perfsByEffect[effect.hash] ?: 0
                val cross = try { store?.getEffectPerformers(effect.hash)?.size ?: 0 } catch (_: Exception) { 0 }
                val count = maxOf(local, cross)
                lensAt(effect.loc, effect.name, count, "performer", "performers", "mezze.showPerformers")
            }

            lenses
        }
    }

    override fun resolveCodeLens(unresolved: CodeLens): CompletableFuture<CodeLens> =
        CompletableFuture.completedFuture(unresolved)

    // ========== References ==========

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            val state = documents[uri] ?: return@supplyAsync emptyList()

            val lines = state.content.lines()
            if (position.line >= lines.size) return@supplyAsync emptyList()
            val word = HoverInfo.extractWordAt(lines[position.line], position.character) ?: return@supplyAsync emptyList()

            val locations = mutableListOf<Location>()

            // Ability / effect NAME references. These names live in decl
            // headers, impl headers, `with X` constraints, and `<X>` effect
            // rows — none of which the typed AST carries as references, so the
            // hash-based path below returns nothing. Handle them here across
            // every open document. (Declaration is always included; abilities/
            // effects don't have a "use site vs. def site" distinction worth
            // stripping.)
            if (isAbilityOrEffectName(state.parsedModule, word)) {
                for ((docUri, docState) in documents) {
                    for (range in findAbilityEffectNameRanges(docState.content, docState.parsedModule, word)) {
                        locations.add(Location(docUri, range))
                    }
                }
                return@supplyAsync locations
            }

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
            } ?: typedModule
                ?.takeIf { cursorOnSymbolOccurrence(it, position, word) }
                ?.decls
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
            //
            // Guard: only run the by-name walk when the cursor ACTUALLY sits on
            // an occurrence of that symbol in the typed AST. Without this,
            // `extractWordAt` happily grabs any word — including plain prose in a
            // half-typed / invalid file — and if it coincidentally matches a
            // binding name elsewhere in the (last-good) module we'd report those
            // as "references" for text that isn't a reference at all.
            if (locations.isEmpty() && typedModule != null &&
                cursorOnSymbolOccurrence(typedModule, position, word)) {
                val target = resolveBinderKeyAtPosition(typedModule, position)
                if (target != null) {
                    collectBindingOccurrences(typedModule, target, word, uri, locations)
                } else {
                    collectReferencesInModule(typedModule, word, uri, locations)
                }
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
            val word = HoverInfo.extractWordAt(lines[params.position.line], params.position.character)
                ?: return@supplyAsync emptyList()
            val typedModule = (state.typeCheckResult ?: lastSuccessfulResult[uri])?.typedModule
                ?: return@supplyAsync emptyList()

            val locs = mutableListOf<Location>()
            // Resolve the binder under the cursor and highlight only occurrences
            // that co-refer with it — a shadowed local must not light up the
            // outer binding (and vice-versa). Falls back to the name walk when
            // the cursor isn't on a resolvable var (e.g. a decl name). (L1)
            val target = resolveBinderKeyAtPosition(typedModule, params.position)
            if (target != null) {
                collectBindingOccurrences(typedModule, target, word, uri, locs)
            } else {
                collectReferencesInModule(typedModule, word, uri, locs)
            }

            locs.map { loc ->
                DocumentHighlight(loc.range, DocumentHighlightKind.Read)
            }
        }
    }

    /**
     * Linked editing: the same-file, same-symbol occurrence ranges of the
     * identifier under the cursor, so the editor edits them all together as
     * the user types (rename-as-you-type). Reuses the same scope-aware
     * TypedVar walk documentHighlight uses. Returns null on a keyword or when
     * there's nothing to link.
     */
    override fun linkedEditingRange(
        params: LinkedEditingRangeParams
    ): CompletableFuture<LinkedEditingRanges?> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val state = documents[uri] ?: return@supplyAsync null
            val lines = state.content.lines()
            if (params.position.line >= lines.size) return@supplyAsync null
            val word = HoverInfo.extractWordAt(lines[params.position.line], params.position.character)
                ?: return@supplyAsync null
            if (word in mezzeKeywordsForRenameGate) return@supplyAsync null
            val typedModule = (state.typeCheckResult ?: lastSuccessfulResult[uri])?.typedModule
                ?: return@supplyAsync null

            val locs = mutableListOf<Location>()
            // Link only the occurrences that co-refer with the binder under the
            // cursor (structural, shadow-safe). (L1)
            val target = resolveBinderKeyAtPosition(typedModule, params.position)
            if (target != null) {
                collectBindingOccurrences(typedModule, target, word, uri, locs)
            } else {
                collectReferencesInModule(typedModule, word, uri, locs)
            }
            // Need at least the def + one use (or two occurrences) to be useful.
            if (locs.size < 2) return@supplyAsync null
            LinkedEditingRanges(locs.map { it.range })
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
        while (start > 0 && HoverInfo.isIdentifierChar(line[start - 1])) start--
        var end = col
        while (end < line.length && HoverInfo.isIdentifierChar(line[end])) end++
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
                if (edge.kind != com.mezze.language.store.EdgeKind.CALLS) continue
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
                if (edge.kind != com.mezze.language.store.EdgeKind.CALLS) continue
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
        // selectionRange must be the NAME (editors reveal/select it and expect
        // to land on the identifier), not the whole decl which starts at the
        // `let`/`type` keyword — same anchor bug fixed for CodeLens (#128).
        val selection = nameRangeOnDeclStartLine(filePath, decl.startLine, decl.startCol, decl.declName) ?: range
        val item = CallHierarchyItem(
            decl.declName,
            declKindToSymbolKindForCallHierarchy(decl.declKind),
            itemUri,
            range,
            selection
        )
        item.detail = decl.modulePath
        item.data = hash.hex
        return item
    }

    /**
     * Locate a decl's NAME token on its start line and return a Range covering
     * just the name. `startLine`/`startCol` are 1-based (store convention).
     * Reads the file line lazily; returns null on any I/O or not-found so
     * callers fall back to the whole-decl range.
     */
    private fun nameRangeOnDeclStartLine(filePath: String, startLine: Int, startCol: Int, name: String): Range? {
        return try {
            val line = File(filePath).bufferedReader().useLines { seq ->
                seq.drop(startLine - 1).firstOrNull()
            } ?: return null
            val from = (startCol - 1).coerceIn(0, line.length)
            var idx = line.indexOf(name, from)
            while (idx >= 0) {
                val before = if (idx == 0) ' ' else line[idx - 1]
                val after = line.getOrElse(idx + name.length) { ' ' }
                if (!before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_') {
                    return Range(Position(startLine - 1, idx), Position(startLine - 1, idx + name.length))
                }
                idx = line.indexOf(name, idx + 1)
            }
            null
        } catch (_: Exception) { null }
    }

    private fun declKindToSymbolKindForCallHierarchy(kind: com.mezze.language.store.DeclKind): SymbolKind =
        when (kind) {
            com.mezze.language.store.DeclKind.LET -> SymbolKind.Function
            com.mezze.language.store.DeclKind.TYPE -> SymbolKind.Class
            com.mezze.language.store.DeclKind.ABILITY -> SymbolKind.Interface
            com.mezze.language.store.DeclKind.EFFECT -> SymbolKind.Event
            com.mezze.language.store.DeclKind.PERFORMER -> SymbolKind.Operator
            com.mezze.language.store.DeclKind.IMPL -> SymbolKind.Method
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
                if (edge.kind != com.mezze.language.store.EdgeKind.IMPLEMENTS) continue
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
                if (edge.kind != com.mezze.language.store.EdgeKind.IMPLEMENTS) continue
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
        val selection = nameRangeOnDeclStartLine(filePath, decl.startLine, decl.startCol, decl.declName) ?: range
        val item = TypeHierarchyItem(
            decl.declName,
            declKindToSymbolKindForCallHierarchy(decl.declKind),
            itemUri,
            range,
            selection
        )
        item.detail = decl.modulePath
        item.data = hash.hex
        return item
    }

    /** Collect all references to a name in the TypedModule. */
    /**
     * True when the cursor genuinely sits on an occurrence of the symbol `word`
     * in the typed AST — either a `TypedVar` use site or a `TypedLetDecl`
     * definition name at that position. Guards the by-name references fallback
     * so it never fires for arbitrary text (e.g. prose in an invalid/half-typed
     * file) that just happens to share a name with some binding.
     */
    private fun cursorOnSymbolOccurrence(
        module: com.mezze.language.typedast.TypedModule,
        position: Position,
        word: String,
    ): Boolean {
        val line = position.line + 1
        val col = position.character + 1
        when (val r = TypedAstLookup.findAtPosition(module, line, col)) {
            is TypedAstLookup.LookupResult.Expr ->
                if ((r.expr as? com.mezze.language.typedast.TypedVar)?.name == word) return true
            else -> {}
        }
        // Definition-site: cursor within a top-level decl's own name span.
        return module.decls.any { d ->
            d is com.mezze.language.typedast.TypedLetDecl && d.name == word &&
                line == d.loc.startLine && col >= d.loc.startCol && col <= d.loc.endCol + 1
        }
    }

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

    /**
     * The direct sub-expressions of a [TypedExpr] — the same recursion
     * [collectReferencesInExpr] uses, factored out so semantic-token walking
     * (and any future typed walk) can recurse without re-listing the cases.
     * Conservative where the reference walker is: string-interpolation parts
     * and match guards/if-let bindings are treated as leaves.
     */
    private fun typedChildren(expr: com.mezze.language.typedast.TypedExpr): List<com.mezze.language.typedast.TypedExpr> =
        when (expr) {
            is com.mezze.language.typedast.TypedCall -> listOf(expr.function, expr.arg)
            is com.mezze.language.typedast.TypedLambda -> listOf(expr.body)
            is com.mezze.language.typedast.TypedRecord -> expr.entries.map { entry ->
                when (entry) {
                    is com.mezze.language.typedast.TypedRecordEntry.Field -> entry.value
                    is com.mezze.language.typedast.TypedRecordEntry.Spread -> entry.expr
                }
            }
            is com.mezze.language.typedast.TypedFieldAccess -> listOf(expr.record)
            is com.mezze.language.typedast.TypedBlock -> expr.statements.map { stmt ->
                when (stmt) {
                    is com.mezze.language.typedast.TypedStatement.Let -> stmt.value
                    is com.mezze.language.typedast.TypedStatement.Expr -> stmt.expr
                }
            } + expr.result
            is com.mezze.language.typedast.TypedMatch ->
                listOf(expr.scrutinee) + expr.cases.map { it.body }
            is com.mezze.language.typedast.TypedIf ->
                listOf(expr.condition, expr.thenBranch, expr.elseBranch)
            is com.mezze.language.typedast.TypedPipe -> listOf(expr.left, expr.right)
            is com.mezze.language.typedast.TypedPerform -> listOf(expr.body)
            is com.mezze.language.typedast.TypedVariantConstruct -> listOfNotNull(expr.payload)
            is com.mezze.language.typedast.TypedSeq -> expr.elements.map { elem ->
                when (elem) {
                    is com.mezze.language.typedast.TypedSeqElement.Regular -> elem.expr
                    is com.mezze.language.typedast.TypedSeqElement.Spread -> elem.expr
                }
            }
            // A string literal's `$( … )` interpolation holes are real
            // sub-expressions — the semantic-token walker must descend into them
            // or the interpolated code renders with no semantic coloring (falls
            // back to the flat string scope). Same class of gap as the parsed
            // ParsedStringLit.children() bug (#160).
            is com.mezze.language.typedast.TypedStringLit -> expr.parts.mapNotNull { part ->
                (part as? com.mezze.language.typedast.TypedStringPart.Interpolation)?.expr
            }
            else -> emptyList()
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

            for (diagnostic in params.context.diagnostics) {
                // #95: structured warning quick-fixes, dispatched on the stable
                // WarningCode carried on the diagnostic. Runs first — these
                // warnings carry no FixHint, so the `?: continue` below would
                // otherwise skip them entirely.
                actions.addAll(warningQuickFixes(uri, state, diagnostic))

                // Structured FixHints come through the LSP via Diagnostic.data
                // as a Map (lsp4j serializes it generically). We stored them
                // under the "fixHints" key in publishDiagnostics — pull them
                // back out here and dispatch by type. No message regex; the
                // typechecker is the source of truth for what fix to offer.
                val hints = extractFixHints(diagnostic) ?: continue
                for (hint in hints) {
                    when (hint) {
                        is FixHintData.Rename -> {
                            for (candidate in hint.candidates) {
                                val range = locToRange(hint.loc)
                                val edit = TextEdit(range, candidate)
                                val action = CodeAction("Replace with `$candidate`")
                                action.kind = CodeActionKind.QuickFix
                                action.isPreferred = hint.candidates.size == 1
                                action.diagnostics = listOf(diagnostic)
                                action.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))
                                actions.add(Either.forRight(action))
                            }
                        }
                        is FixHintData.Import -> {
                            for (module in hint.candidateModules) {
                                val edit = buildAutoImportEdit(state, module, hint.name) ?: continue
                                val action = CodeAction("Import `${hint.name}` from `$module`")
                                action.kind = CodeActionKind.QuickFix
                                action.diagnostics = listOf(diagnostic)
                                action.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))
                                actions.add(Either.forRight(action))
                            }
                        }
                        is FixHintData.WrapInPerform -> {
                            // One action per candidate performer. Each action
                            // wraps the expression at `hint.loc` in
                            // `perform <handler> in do … end`. If we can't
                            // compute the slice (e.g. loc out of range) the
                            // action still appears with a clear title — the
                            // user can fall back to manual editing.
                            for (performer in hint.candidatePerformers) {
                                val action = CodeAction("Wrap with `perform $performer in do … end`")
                                action.kind = CodeActionKind.QuickFix
                                action.diagnostics = listOf(diagnostic)
                                action.edit = buildWrapInPerformEdit(uri, hint.loc, performer)
                                actions.add(Either.forRight(action))
                            }
                        }
                        is FixHintData.AddEffectToAnnotation -> {
                            // Splice the effect into the enclosing function's
                            // return-type annotation. If the let has no
                            // annotation we can't infer one without rerunning
                            // typecheck — bail and surface as title-only.
                            val action = CodeAction("Add `<${hint.effectName}>` to return type")
                            action.kind = CodeActionKind.QuickFix
                            action.diagnostics = listOf(diagnostic)
                            action.edit = buildAddEffectToAnnotationEdit(uri, hint.loc, hint.effectName)
                            actions.add(Either.forRight(action))
                        }
                        is FixHintData.AddPub -> {
                            // Cross-file edit: locate the decl in its source
                            // module and prepend `pub ` to its declaration
                            // keyword. We use the resolved module URI + a
                            // best-effort line scan, falling back to a
                            // title-only action when we can't find it.
                            val action = CodeAction(
                                "Add `pub` to `${hint.declName}` in `${hint.declModulePath}`"
                            )
                            action.kind = CodeActionKind.QuickFix
                            action.diagnostics = listOf(diagnostic)
                            val edit = buildAddPubEdit(hint.declModulePath, hint.declName)
                            if (edit != null) action.edit = edit
                            actions.add(Either.forRight(action))
                        }
                        is FixHintData.FillMatchArms -> {
                            val edit = buildFillMatchArmsEdit(state, hint.loc, hint.missingConstructors)
                            if (edit != null) {
                                val n = hint.missingConstructors.size
                                val action = CodeAction("Add missing match ${if (n == 1) "arm" else "arms"} (${hint.missingConstructors.joinToString(", ")})")
                                action.kind = CodeActionKind.QuickFix
                                action.isPreferred = true
                                action.diagnostics = listOf(diagnostic)
                                action.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))
                                actions.add(Either.forRight(action))
                            }
                        }
                        is FixHintData.RemoveRange -> {
                            val edit = buildRemoveRangeEdit(state, hint.loc)
                            val action = CodeAction(hint.label)
                            action.kind = CodeActionKind.QuickFix
                            action.isPreferred = true
                            action.diagnostics = listOf(diagnostic)
                            action.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))
                            actions.add(Either.forRight(action))
                        }
                        is FixHintData.WrapInConstructor -> {
                            // Wrap the mismatched value: `expr` → `Ctor { expr }`.
                            val inner = sliceLspRange(state.content, hint.loc)
                            if (inner.isNotBlank()) {
                                val edit = TextEdit(hint.loc, "${hint.constructor} { $inner }")
                                val action = CodeAction("Wrap in `${hint.constructor}`")
                                action.kind = CodeActionKind.QuickFix
                                action.isPreferred = true
                                action.diagnostics = listOf(diagnostic)
                                action.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))
                                actions.add(Either.forRight(action))
                            }
                        }
                        is FixHintData.WrapInMethodCall -> {
                            // Append a conversion: `expr` → `expr.<method> {}`.
                            // One action per candidate method (to_str preferred).
                            val inner = sliceLspRange(state.content, hint.loc)
                            if (inner.isNotBlank()) {
                                hint.methods.forEachIndexed { idx, method ->
                                    val edit = TextEdit(hint.loc, "$inner.$method {}")
                                    val action = CodeAction("Append `.$method {}`")
                                    action.kind = CodeActionKind.QuickFix
                                    action.isPreferred = idx == 0
                                    action.diagnostics = listOf(diagnostic)
                                    action.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))
                                    actions.add(Either.forRight(action))
                                }
                            }
                        }
                    }
                }
            }

            // Refactoring code actions — these run on the cursor's range,
            // not on a diagnostic. The LSP client passes the user-selected
            // range (or a zero-width cursor) via params.range; we add the
            // refactorings that make sense for whatever sits at that range.
            actions.addAll(buildRefactorActions(uri, state, params.range))

            // Source action: organize imports. Offered whenever the client
            // asks for source.organizeImports (or requests all kinds), and
            // only when it would actually change something.
            val only = params.context.only
            val wantsOrganize = only == null || only.isEmpty() ||
                only.any { CodeActionKind.SourceOrganizeImports.startsWith(it) || it.startsWith(CodeActionKind.SourceOrganizeImports) }
            if (wantsOrganize) {
                buildOrganizeImportsAction(uri, state)?.let { actions.add(Either.forRight(it)) }
            }

            actions
        }
    }

    /**
     * #95: quick-fixes for the structured linter warnings, keyed off the
     * WarningCode carried on the diagnostic. The diagnostic's own range already
     * spans the offending token, so edits are computed from it — no message
     * parsing, no AST re-walk.
     *   - W0101 unused variable → "Prefix with `_`" (insert `_` at the name).
     *   - W0102 unused import   → "Remove import" (delete the `use` line).
     */
    private fun warningQuickFixes(
        uri: String,
        state: DocumentState,
        diagnostic: Diagnostic
    ): List<Either<Command, CodeAction>> {
        val codeEither = diagnostic.code
        val code = if (codeEither != null && codeEither.isLeft) codeEither.left else return emptyList()
        val out = mutableListOf<Either<Command, CodeAction>>()

        fun quickFix(title: String, edits: List<TextEdit>): CodeAction {
            val action = CodeAction(title)
            action.kind = CodeActionKind.QuickFix
            action.isPreferred = true
            action.diagnostics = listOf(diagnostic)
            action.edit = WorkspaceEdit(mapOf(uri to edits))
            return action
        }

        when (code) {
            WarningCode.W0101_UNUSED_VARIABLE.display() -> {
                // Insert `_` at the start of the flagged name (zero-width edit).
                val at = Range(diagnostic.range.start, diagnostic.range.start)
                out.add(Either.forRight(quickFix("Prefix with `_`", listOf(TextEdit(at, "_")))))
            }
            WarningCode.W0102_UNUSED_IMPORT.display() -> {
                val startLine = diagnostic.range.start.line
                // Find the `use` decl on this line and which of its items are
                // unused. If SOME (not all) items of a selective import are
                // unused, remove just those and keep the rest — deleting the
                // whole line would drop still-used imports. Otherwise fall back
                // to deleting the line.
                val useDecl = state.parsedModule?.decls
                    ?.filterIsInstance<com.mezze.language.parsedast.ParsedUseDecl>()
                    ?.firstOrNull { it.loc.startLine - 1 == startLine }
                val path = useDecl?.path
                if (useDecl != null && path is com.mezze.language.parsedast.ParsedUsePath.Selective) {
                    val unused = try {
                        // Same ability name → ops map the typechecker's linter uses.
                        // Without it this fix removes an ability imported purely so
                        // its methods dispatch (`j.from_json {}`), whose name appears
                        // nowhere — and the build then fails on the very edit we
                        // offered as a fix.
                        val abilityOps: Map<String, Set<String>> =
                            (((state.typeCheckResult as? com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success)
                                ?.importContext?.importedAbilities ?: emptyMap()).values +
                                (state.parsedModule?.decls
                                    ?.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>()
                                    ?: emptyList()))
                                .associate { decl -> decl.name to decl.operations.map { it.name }.toSet() }
                        com.mezze.language.typechecker.validation.UnusedImportLinter
                            .unusedItemsFor(state.parsedModule!!, useDecl, abilityOps).toSet()
                    } catch (_: Exception) { emptySet() }
                    val kept = path.items.filter { (it.alias ?: it.name) !in unused }
                    if (unused.isNotEmpty() && kept.isNotEmpty()) {
                        // Rebuild `use mod::{ a, b }` with only the kept items.
                        val itemsText = kept.joinToString(", ") { it.alias?.let { a -> "${it.name} as $a" } ?: it.name }
                        val rebuilt = "use ${path.modulePath}::{ $itemsText }"
                        val lineLen = state.content.lines().getOrNull(startLine)?.length ?: 0
                        val lineRange = Range(Position(startLine, 0), Position(startLine, lineLen))
                        val title = if (unused.size == 1) "Remove unused import '${unused.single()}'"
                                    else "Remove ${unused.size} unused imports"
                        out.add(Either.forRight(quickFix(title, listOf(TextEdit(lineRange, rebuilt)))))
                        return out
                    }
                }
                // All items unused (or not selective) → delete the whole line.
                val delRange = Range(Position(startLine, 0), Position(startLine + 1, 0))
                out.add(Either.forRight(quickFix("Remove unused import", listOf(TextEdit(delRange, "")))))
            }
            WarningCode.W0301_DISCARDED_VALUE.display() -> {
                // A bare, effect-free statement — remove its whole line.
                val startLine = diagnostic.range.start.line
                val delRange = Range(Position(startLine, 0), Position(startLine + 1, 0))
                out.add(Either.forRight(quickFix("Remove discarded expression", listOf(TextEdit(delRange, "")))))
            }
            WarningCode.W0303_REDUNDANT_BOOLEAN.display() -> {
                // Rewrite from the PARSED AST (never source-text regex): find the
                // redundant-boolean node at the diagnostic loc and build the
                // replacement from the real sub-expression's source slice.
                val fix = simplifyRedundantBoolean(state, diagnostic.range)
                if (fix != null) {
                    out.add(Either.forRight(quickFix("Simplify to `${fix.second}`",
                        listOf(TextEdit(fix.first, fix.second)))))
                }
            }
            // #V0404: an ability impl is missing required methods. Offer to
            // insert stub bodies for each one (`method = { params } -> ...`),
            // derived from the ability declaration's operation signatures.
            V0404_MISSING_METHOD_CODE -> {
                val fix = buildAutoImplementAbilityFix(state, diagnostic.range)
                if (fix != null) {
                    out.add(Either.forRight(quickFix(fix.first, fix.second)))
                }
            }
            // A variant constructor is missing required fields (`Point { x = 1 }`
            // where Point needs `{x, y}`). Raised as T0204 (same code as the
            // extra-fields case, so buildAddMissingFieldsFix guards on the
            // "missing field(s):" message and returns null otherwise). The
            // message carries the missing names; insert `field = ???` for each.
            T0204_VARIANT_ARITY_CODE -> {
                val fix = buildAddMissingFieldsFix(state, diagnostic)
                if (fix != null) {
                    out.add(Either.forRight(quickFix(fix.first, fix.second)))
                }
            }
        }
        return out
    }

    /**
     * Build the "Add missing field(s)" edit for a MISSING_FIELD diagnostic on a
     * variant/record literal. The missing names come from the message tail
     * `missing field(s): a, b`. We find the record literal covering the
     * diagnostic loc and splice `, name = ???` for each missing field before its
     * closing `}` (or `name = ???` as the first field when the record is empty).
     * `???` is the Mezze hole so the typechecker keeps flagging it until filled.
     */
    private fun buildAddMissingFieldsFix(
        state: DocumentState,
        diagnostic: Diagnostic,
    ): Pair<String, List<TextEdit>>? {
        val msg = diagnostic.message
        val marker = "missing field(s): "
        val idx = msg.indexOf(marker)
        if (idx < 0) return null
        val missing = msg.substring(idx + marker.length)
            .substringBefore('.')            // stop at a trailing sentence period
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (missing.isEmpty()) return null

        // The record literal to splice into. The diagnostic is raised at the
        // whole `Ctor { … }` construct loc, so the inner record doesn't *cover*
        // the range — find the covering ParsedVariantConstruct and take its
        // record payload. (Fall back to a directly-covering record for a bare
        // record literal, though variant constructors are the T0204 case.)
        val parsed = state.parsedModule ?: return null
        val construct = findParsedExprCovering(parsed, diagnostic.range) { it is ParsedVariantConstruct }
            as? ParsedVariantConstruct
        val record = (construct?.value as? ParsedRecordLit)
            ?: (findParsedExprCovering(parsed, diagnostic.range) { it is ParsedRecordLit } as? ParsedRecordLit)
            ?: return null

        val newFields = missing.joinToString("") { name -> "$name = ???, " }
        // Insert just after the opening `{`. locToRange gives the record's span;
        // the `{` is its first char.
        val recRange = locToRange(record.loc)
        val open = Position(recRange.start.line, recRange.start.character + 1)
        // If the record already has fields, the inserted text ends with `, ` to
        // separate from the first existing field; if empty, trim the trailing
        // separator so we don't leave `{ x = ???,  }`.
        val hasFields = record.entries.isNotEmpty()
        val insertText = if (hasFields) newFields else newFields.trimEnd().removeSuffix(",")
        val title = if (missing.size == 1) "Add missing field `${missing.single()}`"
                    else "Add missing fields: ${missing.joinToString(", ")}"
        return title to listOf(TextEdit(Range(open, open), insertText))
    }

    /**
     * Auto-implement the methods missing from an ability impl. Locates the
     * [ParsedImplAbilityDecl] covering [range], resolves its ability declaration
     * (local module first, then the project store), computes which operations
     * are unimplemented, and returns a (title, edit) that inserts a stub for
     * each just before the impl's closing `}`.
     *
     * Each stub is `name = { p1, p2 } -> ...` where the parameter names come
     * from the ability op's record parameter type (falling back to `it` for a
     * single non-record param). `...` is the Mezze "hole" placeholder, so the
     * user sees a clear TODO the typechecker will flag until filled in.
     */
    private fun buildAutoImplementAbilityFix(
        state: DocumentState,
        range: Range,
    ): Pair<String, List<TextEdit>>? {
        val parsedModule = state.parsedModule ?: return null
        val diagLine = range.start.line // 0-based

        // The impl covering the diagnostic line. V0404 is raised at the impl's
        // own loc, so start-line match is enough; fall back to any impl whose
        // span contains the line.
        val impl = parsedModule.decls
            .filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>()
            .firstOrNull { it.loc.startLine - 1 == diagLine }
            ?: parsedModule.decls
                .filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>()
                .firstOrNull { diagLine in (it.loc.startLine - 1)..(it.loc.endLine - 1) }
            ?: return null

        val abilityName = impl.abilityName ?: return null

        // Resolve the ability declaration: local module, then store.
        val abilityDecl = findAbilityDeclByName(state, abilityName) ?: return null

        val implemented = impl.operations.map { it.name }.toSet()
        val missing = abilityDecl.operations.filter { it.name !in implemented }
        if (missing.isEmpty()) return null

        // Indentation: match the first existing operation's column, else the
        // impl's column + 2.
        val implIndent = " ".repeat((impl.loc.startCol - 1).coerceAtLeast(0))
        val bodyIndent = impl.operations.firstOrNull()?.let {
            " ".repeat((it.loc.startCol - 1).coerceAtLeast(0))
        } ?: (implIndent + "  ")

        // Each stub is comma-terminated — Mezze separates impl operations with
        // commas and allows a trailing one before `}`, so this stays valid
        // whether or not more ops follow. The body is a bare `unimplemented`:
        // it PARSES (so every other LSP feature keeps working on the file) and
        // surfaces a single, clear "undefined variable" error pointing exactly
        // at the hole the user must fill in.
        val stubs = missing.joinToString("\n") { op ->
            "$bodyIndent${op.name} = { ${paramNamesFor(op)} } -> unimplemented,"
        }

        val lines = state.content.lines()
        val endLineIdx = impl.loc.endLine - 1
        if (endLineIdx !in lines.indices) return null

        val edits = mutableListOf<TextEdit>()

        // Existing ops need a comma before the new ones. Find the last existing
        // operation and, if its source line doesn't already end with a comma,
        // append one. (The parser drops trailing commas, so we can't tell from
        // the AST alone — inspect the source line at the op's end.)
        impl.operations.maxByOrNull { it.loc.endLine }?.let { lastOp ->
            val opLineIdx = lastOp.loc.endLine - 1
            val lineText = lines.getOrNull(opLineIdx)
            if (lineText != null && !lineText.trimEnd().endsWith(",")) {
                val col = lineText.trimEnd().length
                edits.add(TextEdit(Range(Position(opLineIdx, col), Position(opLineIdx, col)), ","))
            }
        }

        // Insert the stubs on a fresh line before the closing `}` line.
        val insertPos = Position(endLineIdx, 0)
        edits.add(TextEdit(Range(insertPos, insertPos), "$stubs\n"))

        val title = if (missing.size == 1) "Implement missing method '${missing.single().name}'"
                    else "Implement ${missing.size} missing methods"
        return title to edits
    }

    /**
     * Parameter names for an ability op stub. If the op's parameter type is a
     * record (`{ it: 'a, r: 'a }`), use its field names in order. Otherwise use
     * a single `it` — the conventional receiver name.
     */
    private fun paramNamesFor(op: com.mezze.language.parsedast.ParsedAbilityOp): String {
        val pt = op.paramType
        return if (pt is com.mezze.language.parsedast.ParsedRecordType && pt.entries.isNotEmpty()) {
            pt.entries.joinToString(", ") { it.name }
        } else {
            "it"
        }
    }

    /**
     * Find an ability declaration by name: the open document's module first,
     * then any module in the project store. Returns null if unknown.
     */
    private fun findAbilityDeclByName(
        state: DocumentState,
        name: String,
    ): com.mezze.language.parsedast.ParsedAbilityDecl? {
        state.parsedModule?.decls
            ?.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>()
            ?.firstOrNull { it.name == name }
            ?.let { return it }

        // Fall back to any module already parsed+typed this session (stdlib
        // abilities like Eq/Ord/Show land here once referenced).
        for (moduleResult in com.mezze.language.typechecker.module.ModuleTypeChecker
                .getAllCachedModules().values) {
            moduleResult.parsedModule?.decls
                ?.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>()
                ?.firstOrNull { it.name == name }
                ?.let { return it }
        }
        return null
    }

    /**
     * #106: simplify a W0303 redundant-boolean expression using the PARSED AST
     * the LSP already holds (no regex). Handles `!(!b)` -> `b`,
     * `x == True`/`True == x` -> `x`, `x == False`/`False == x` -> `(x).not {}`, and
     * `if b then True else False` -> `b`. Returns the (range, replacement) or
     * null when the shape isn't one we rewrite. The inner sub-expression is
     * copied verbatim from source via its own loc — so nested/multi-line
     * expressions survive intact.
     */
    private fun simplifyRedundantBoolean(state: DocumentState, range: Range): Pair<Range, String>? {
        val parsed = state.parsedModule ?: return null
        // The W0303 diagnostic spans the WHOLE redundant expression, so find the
        // node whose loc matches that span (not the innermost node at the start,
        // which would be the left operand).
        val node = findParsedExprCovering(parsed, range) ?: return null
        val content = state.content

        fun srcOf(loc: com.mezze.language.parsedast.Loc): String = sliceSource(content, loc)
        fun isBoolLit(e: ParsedExpr, name: String) =
            e is ParsedVariantConstruct && e.value == null && e.constructor == name

        // The inner receiver of a `<recv>.not {}` method call, or null.
        fun boolNotReceiver(e: ParsedExpr): ParsedExpr? {
            val call = unwrapParens(e) as? ParsedFunctionCall ?: return null
            val member = call.function as? ParsedMemberAccess ?: return null
            return if (member.member == BuiltinTypes.NOT_METHOD) member.receiver else null
        }

        val replacement: String? = when (node) {
            // (b.not {}).not {} -> b   (unwrap the double negation)
            is ParsedFunctionCall -> {
                val inner = boolNotReceiver(node)?.let { boolNotReceiver(it) }
                if (inner != null) srcOf(unwrapParens(inner).loc) else null
            }

            // x == True -> x ; x == False -> x.not {}  (either side)
            is ParsedBinaryOp -> if (node.op.isEquality) {
                when {
                    isBoolLit(node.right, BuiltinTypes.TRUE) -> srcOf(node.left.loc)
                    isBoolLit(node.left, BuiltinTypes.TRUE) -> srcOf(node.right.loc)
                    isBoolLit(node.right, BuiltinTypes.FALSE) -> "(${srcOf(node.left.loc)}).${BuiltinTypes.NOT_METHOD} {}"
                    isBoolLit(node.left, BuiltinTypes.FALSE) -> "(${srcOf(node.right.loc)}).${BuiltinTypes.NOT_METHOD} {}"
                    else -> null
                }
            } else null

            // if b then True else False -> b
            is ParsedIf -> if (isBoolLit(node.thenBranch, BuiltinTypes.TRUE) && isBoolLit(node.elseBranch, BuiltinTypes.FALSE))
                srcOf(node.condition.loc) else null

            else -> null
        }
        if (replacement == null) return null
        return locToRange(node.loc) to replacement
    }

    /** Verbatim source slice for a [Loc] (1-based lines/cols). */
    private fun sliceSource(content: String, loc: com.mezze.language.parsedast.Loc): String {
        val lines = content.split('\n')
        if (loc.startLine == loc.endLine) {
            val line = lines.getOrNull(loc.startLine - 1) ?: return ""
            return line.substring(
                (loc.startCol - 1).coerceIn(0, line.length),
                (loc.endCol).coerceIn(0, line.length)
            )
        }
        val sb = StringBuilder()
        for (ln in loc.startLine..loc.endLine) {
            val line = lines.getOrNull(ln - 1) ?: continue
            when (ln) {
                loc.startLine -> sb.append(line.substring((loc.startCol - 1).coerceIn(0, line.length)))
                loc.endLine -> sb.append("\n").append(line.substring(0, loc.endCol.coerceIn(0, line.length)))
                else -> sb.append("\n").append(line)
            }
        }
        return sb.toString()
    }

    private fun unwrapParens(e: ParsedExpr): ParsedExpr =
        if (e is ParsedParens) unwrapParens(e.expr) else e

    /**
     * Find the SMALLEST [ParsedExpr] whose loc fully covers [range] (both sides
     * 1-based-adjusted here). Walks the parsed module's decl bodies structurally
     * via the shared [children]. Used to map a diagnostic's span back to the
     * exact AST node the linter flagged.
     */
    private fun findParsedExprCovering(
        module: ParsedModule,
        range: Range,
        predicate: ((ParsedExpr) -> Boolean)? = null
    ): ParsedExpr? {
        val rStartLine = range.start.line + 1
        val rStartCol = range.start.character + 1
        val rEndLine = range.end.line + 1
        val rEndCol = range.end.character
        fun covers(loc: com.mezze.language.parsedast.Loc): Boolean {
            val startsBefore = loc.startLine < rStartLine ||
                (loc.startLine == rStartLine && loc.startCol <= rStartCol)
            val endsAfter = loc.endLine > rEndLine ||
                (loc.endLine == rEndLine && loc.endCol >= rEndCol)
            return startsBefore && endsAfter
        }
        var best: ParsedExpr? = null
        fun visit(e: ParsedExpr) {
            if (covers(e.loc) && (predicate == null || predicate(e))) best = e
            for (child in e.children()) visit(child)
        }
        for (decl in module.decls) {
            when (decl) {
                is ParsedLetDecl -> visit(decl.value)
                is ParsedTopLevelExpr -> visit(decl.expression)
                else -> {}
            }
        }
        return best
    }

    /**
     * Compute the refactoring code actions available at [range]. Today we
     * surface:
     *   - "Extract to let" when [range] covers a non-trivial expression
     *     inside a function body. Replaces the slice with a fresh name
     *     and inserts `let <name> = <slice>` on the line above (at the
     *     same indent).
     *   - "Inline binding" when the cursor sits on a `let <name> = <value>`
     *     and there's at least one use of `<name>` in the same enclosing
     *     scope. Replaces each use with `<value>` and deletes the binding.
     *   - "Convert if to match" when the cursor is on an `if` expression.
     *     Rewrites `if c then t else e` as `match c is | True -> t | False -> e`.
     *
     * These are intentionally text-level — we use simple range/regex tests
     * over the document content rather than walking the typed AST. The
     * tradeoff: edge cases (multi-line expressions, nested scopes) get
     * handled with a best-effort heuristic, but the common cases work
     * and the implementation stays under a few hundred lines.
     */
    private fun buildRefactorActions(uri: String, state: DocumentState, range: Range): List<Either<Command, CodeAction>> {
        val out = mutableListOf<Either<Command, CodeAction>>()
        val extract = buildExtractToLetAction(uri, state, range)
        if (extract != null) out.add(Either.forRight(extract))
        val extractFn = buildExtractToFunctionAction(uri, state, range)
        if (extractFn != null) out.add(Either.forRight(extractFn))
        val inline = buildInlineBindingAction(uri, state, range)
        if (inline != null) out.add(Either.forRight(inline))
        val ifToMatch = buildConvertIfToMatchAction(uri, state, range)
        if (ifToMatch != null) out.add(Either.forRight(ifToMatch))
        val addAnnotation = buildAddTypeAnnotationAction(uri, state, range)
        if (addAnnotation != null) out.add(Either.forRight(addAnnotation))
        val implMembers = buildImplementMembersAction(uri, state, range)
        if (implMembers != null) out.add(Either.forRight(implMembers))
        return out
    }

    /**
     * "Implement missing members": when the cursor sits inside an `impl Ability
     * for X where { … }` (or a performer `impl Effect for X`) that is missing
     * some of the ability/effect's ops, offer a code action that inserts a
     * correctly-shaped stub (`op = { it } -> {}`) for every unimplemented op.
     * Mezze-specific ergonomics — the single biggest win for an effect language.
     *
     * Returns null when the cursor isn't in such an impl, or nothing is missing.
     */
    private fun buildImplementMembersAction(uri: String, state: DocumentState, range: Range): CodeAction? {
        val pm = state.parsedModule ?: return null
        val lineNo = range.start.line + 1  // Loc is 1-indexed

        for (decl in pm.decls) {
            // Resolve (impl loc, all op names of the ability/effect, op decls for
            // param shape, already-implemented names) for an impl/performer decl.
            val info: Triple<Loc, List<Pair<String, ParsedTypeAnnotation?>>, Set<String>> = when (decl) {
                is com.mezze.language.parsedast.ParsedImplAbilityDecl -> {
                    val ability = pm.decls.filterIsInstance<ParsedAbilityDecl>()
                        .find { it.name == decl.abilityName } ?: continue
                    Triple(
                        decl.loc,
                        ability.operations.map { it.name to it.paramType },
                        decl.operations.map { it.name }.toSet(),
                    )
                }
                is ParsedPerformerDecl -> {
                    val effect = pm.decls.filterIsInstance<ParsedEffectDecl>()
                        .find { it.name == decl.effect.localName } ?: continue
                    Triple(
                        decl.loc,
                        effect.operations.map { it.name to it.paramType },
                        decl.operations.map { it.name }.toSet(),
                    )
                }
                else -> continue
            }

            val (declLoc, allOps, implemented) = info
            if (lineNo < declLoc.startLine || lineNo > declLoc.endLine) continue

            val missing = allOps.filter { it.first !in implemented }
            if (missing.isEmpty()) return null

            // Insert the stubs on their own lines just before the impl's closing
            // `}` (declLoc.endLine, 1-indexed → 0-indexed endLine-1). Match the
            // indentation of the last body line for tidy output.
            val lines = state.content.lines()
            val closeLine0 = declLoc.endLine - 1
            if (closeLine0 !in lines.indices) return null
            val bodyIndent = run {
                // Indentation of the line before the closing brace, else 2 spaces.
                val prev = lines.getOrNull(closeLine0 - 1) ?: ""
                val ws = prev.takeWhile { it == ' ' }
                if (ws.isNotEmpty() && prev.isNotBlank()) ws else "  "
            }
            // A trailing comma is needed after each op if the block already has
            // ops (Mezze impl ops are comma-separated). Best-effort: prefix each
            // new op with a comma when there are already-implemented ops.
            val needsLeadingComma = implemented.isNotEmpty()
            val stubs = missing.mapIndexed { idx, (opName, paramType) ->
                val itParam = if (paramType != null && HoverInfo.formatTypeAnnotation(paramType).contains("it")) "it" else "it"
                val sep = if (needsLeadingComma || idx > 0) "," else ""
                "$sep\n$bodyIndent$opName = { $itParam } -> {}"
            }.joinToString("")

            // Insert right before the closing brace's line. We put it at the end
            // of the line preceding the `}` (column = that line's length).
            val insertLine = closeLine0 - 1
            val insertCol = lines.getOrNull(insertLine)?.length ?: 0
            val at = Range(Position(insertLine, insertCol), Position(insertLine, insertCol))
            val edit = TextEdit(at, stubs)

            val title = if (missing.size == 1)
                "Implement missing member '${missing.single().first}'"
            else "Implement ${missing.size} missing members"
            val action = CodeAction(title)
            action.kind = CodeActionKind.QuickFix
            action.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))
            return action
        }
        return null
    }

    /**
     * Build the "Organize imports" source action.
     *
     * Rebuilds the file's leading `use` block: groups by module path, merges
     * selective imports of the same module (`use foo::a` + `use foo::b` →
     * `use foo::{ a, b }`), drops exact duplicates, and sorts by path. Purely
     * AST-driven — every `use` decl and its items come from the parsed module,
     * never a text regex.
     *
     * Conservative: only touches a *contiguous* run of imports at the very top
     * of the file (the common case). Aliased imports (`use x as y`) and
     * `pub use` are preserved verbatim on their own lines rather than merged,
     * since merging changes their meaning. Returns null when nothing would
     * change (already organized, or fewer than the reorg threshold).
     */
    private fun buildOrganizeImportsAction(uri: String, state: DocumentState): CodeAction? {
        val parsed = state.parsedModule ?: return null
        val decls = parsed.decls

        // The leading contiguous run of plain `use` decls. Stop at the first
        // non-import (or a pub-use / aliased import, which we don't reorganize).
        val block = decls.takeWhile { it is ParsedUseDecl }.filterIsInstance<ParsedUseDecl>()
        if (block.size < 2) return null

        // Merge: module path → sorted, deduped set of (name, alias) items, plus
        // a flag for a bare `use module` (no selective list).
        data class Group(var bareModule: Boolean = false, val items: LinkedHashSet<Pair<String, String?>> = LinkedHashSet())
        val groups = linkedMapOf<String, Group>()
        // Aliased whole-module imports (`use foo as f`) can't be merged — keep
        // them verbatim, sorted, after the mergeable groups.
        val aliased = mutableListOf<String>()

        for (d in block) {
            if (d.alias != null) {
                aliased.add("use ${renderUsePath(d.path)} as ${d.alias}")
                continue
            }
            when (val p = d.path) {
                is ParsedUsePath.Module -> {
                    val key = p.path.segments.joinToString("::")
                    groups.getOrPut(key) { Group() }.bareModule = true
                }
                is ParsedUsePath.Selective -> {
                    val key = p.modulePath.segments.joinToString("::")
                    val g = groups.getOrPut(key) { Group() }
                    for (item in p.items) g.items.add(item.name to item.alias)
                }
            }
        }

        // Render the organized block, sorted by module path.
        val lines = mutableListOf<String>()
        for (key in groups.keys.sorted()) {
            val g = groups.getValue(key)
            if (g.bareModule) lines.add("use $key")
            if (g.items.isNotEmpty()) {
                val items = g.items.sortedBy { it.first }.joinToString(", ") { (name, alias) ->
                    if (alias != null) "$name as $alias" else name
                }
                lines.add("use $key::{ $items }")
            }
        }
        lines.addAll(aliased.sorted())
        val organized = lines.joinToString("\n") + "\n"

        // The span to replace: from the first import's start to the line after
        // the last import (whole lines, so we drop any now-merged blank gaps).
        val firstLoc = block.first().loc
        val lastLoc = block.last().loc
        val replaceRange = Range(
            Position(firstLoc.startLine - 1, 0),
            Position(lastLoc.endLine, 0),
        )

        // No-op guard: if the current text already equals the organized block,
        // don't offer the action.
        val current = sliceRange(state.content, replaceRange)
        if (current == organized) return null

        return CodeAction("Organize imports").apply {
            kind = CodeActionKind.SourceOrganizeImports
            edit = WorkspaceEdit(mapOf(uri to listOf(TextEdit(replaceRange, organized))))
        }
    }

    /** Render a [ParsedUsePath] back to source text (module or selective form). */
    private fun renderUsePath(path: ParsedUsePath): String = when (path) {
        is ParsedUsePath.Module -> path.path.segments.joinToString("::")
        is ParsedUsePath.Selective -> {
            val items = path.items.joinToString(", ") { item ->
                if (item.alias != null) "${item.name} as ${item.alias}" else item.name
            }
            "${path.modulePath.segments.joinToString("::")}::{ $items }"
        }
    }

    /** The substring of [content] covered by a whole-line LSP [range]. */
    private fun sliceRange(content: String, range: Range): String {
        val lines = content.split('\n')
        val sb = StringBuilder()
        for (ln in range.start.line until range.end.line) {
            lines.getOrNull(ln)?.let { sb.append(it).append('\n') }
        }
        return sb.toString()
    }

    /**
     * The exact text an LSP [range] (0-indexed, character-precise) covers,
     * including the single-line case. Used to slice the inner expression when
     * wrapping it in a constructor.
     */
    private fun sliceLspRange(content: String, range: Range): String {
        val lines = content.split('\n')
        if (range.start.line == range.end.line) {
            val line = lines.getOrNull(range.start.line) ?: return ""
            return line.substring(
                range.start.character.coerceIn(0, line.length),
                range.end.character.coerceIn(0, line.length),
            )
        }
        val sb = StringBuilder()
        for (ln in range.start.line..range.end.line) {
            val line = lines.getOrNull(ln) ?: continue
            when (ln) {
                range.start.line -> sb.append(line.substring(range.start.character.coerceIn(0, line.length)))
                range.end.line -> sb.append("\n").append(line.substring(0, range.end.character.coerceIn(0, line.length)))
                else -> sb.append("\n").append(line)
            }
        }
        return sb.toString()
    }

    /**
     * Build the "Add type annotation" refactoring action.
     *
     * Fires when the cursor sits on the name-line of a top-level
     * `let <name> = <value>` that has no annotation yet. We take the type the
     * typechecker already inferred (the matching TypedLetDecl's scheme) and
     * splice it in as `let <name>: <Type> = …`. Purely typechecker-powered —
     * the type is computed, we just surface it. Skipped when the type still
     * contains an unresolved variable (renders as `'a`), since annotating with
     * a bare tyvar rarely helps and can over-constrain.
     */
    private fun buildAddTypeAnnotationAction(uri: String, state: DocumentState, range: Range): CodeAction? {
        val parsed = state.parsedModule ?: return null
        val cursorLine = range.start.line + 1

        // The top-level let under the cursor, without an existing annotation.
        val decl = parsed.decls.filterIsInstance<ParsedLetDecl>().firstOrNull { d ->
            d.typeAnnotation == null &&
                (d.pattern as? ParsedVariablePattern)?.loc?.startLine == cursorLine
        } ?: return null
        val pat = decl.pattern as? ParsedVariablePattern ?: return null

        val typedModule = (state.typeCheckResult ?: lastSuccessfulResult[uri])?.typedModule ?: return null
        val typed = typedModule.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            .firstOrNull { it.name == pat.name } ?: return null

        val rendered = prettyPrintTypeWithAliases(typed.scheme.type, state.typeAliases)
        // Don't offer an annotation we can't cleanly write: unresolved tyvars,
        // or an error/suspended marker leaking through.
        if (rendered.isBlank() || rendered.contains("'") || rendered.contains("?") ||
            rendered.contains("<error") || rendered.contains("suspended")) return null

        // Insert `: <Type>` right after the binding name. The name's loc ends at
        // (endLine, endCol); LSP positions are 0-indexed.
        val insertAt = Position(pat.loc.endLine - 1, pat.loc.endCol)
        val edit = TextEdit(Range(insertAt, insertAt), ": $rendered")

        return CodeAction("Add type annotation `: $rendered`").apply {
            kind = CodeActionKind.RefactorRewrite
            this.edit = WorkspaceEdit(mapOf(uri to listOf(edit)))
        }
    }

    /**
     * Build the "Extract to let" refactoring action.
     *
     * Fires when the user has a non-empty selection that doesn't span line
     * boundaries (multi-line extractions are not handled yet). The selected
     * text becomes `let extracted = <slice>`; the slice is replaced with
     * `extracted`. Cursor lands on `extracted` so the user can rename.
     */
    private fun buildExtractToLetAction(uri: String, state: DocumentState, range: Range): CodeAction? {
        if (range.start == range.end) return null
        if (range.start.line != range.end.line) return null
        val line = state.content.split('\n').getOrNull(range.start.line) ?: return null
        val slice = line.substring(
            range.start.character.coerceIn(0, line.length),
            range.end.character.coerceIn(0, line.length),
        )
        if (slice.isBlank()) return null
        // Don't extract pure identifiers — there's nothing to factor out.
        if (slice.matches(Regex("\\s*\\w+\\s*"))) return null

        val indent = line.substring(0, line.length - line.trimStart().length)
        val letLine = "${indent}let extracted = $slice\n"
        val action = CodeAction("Extract to let").apply {
            kind = CodeActionKind.RefactorExtract
            edit = WorkspaceEdit(mapOf(uri to listOf(
                // Insert the new let above the current line.
                TextEdit(Range(Position(range.start.line, 0), Position(range.start.line, 0)), letLine),
                // Replace the slice with the name. The insertion above has
                // already shifted line numbers in absolute terms, but LSP
                // applies edits atomically against the pre-edit document —
                // the two ranges here are both expressed in the original
                // line coordinates and don't overlap.
                TextEdit(range, "extracted"),
            )))
        }
        return action
    }

    /**
     * Build the "Extract to function" refactoring action.
     *
     * Lifts the selected expression into a new top-level
     * `let extracted = { <free vars> } -> <slice>` and replaces the selection
     * with a call `extracted { <free vars> }`. Free variables are the
     * identifiers in the selection that are bound by an ENCLOSING lambda (its
     * params) — those must become function parameters so the extracted function
     * is self-contained. Top-level/stdlib references need no param.
     *
     * Single-line selections only for now (the slice is textual). Falls back to
     * a zero-arg thunk `{} -> slice` when the selection captures no locals.
     */
    private fun buildExtractToFunctionAction(uri: String, state: DocumentState, range: Range): CodeAction? {
        if (range.start == range.end || range.start.line != range.end.line) return null
        val parsed = state.parsedModule ?: return null
        val line = state.content.split('\n').getOrNull(range.start.line) ?: return null
        val slice = line.substring(
            range.start.character.coerceIn(0, line.length),
            range.end.character.coerceIn(0, line.length),
        )
        if (slice.isBlank()) return null
        // A bare identifier isn't worth extracting into a function.
        if (slice.matches(Regex("\\s*\\w+\\s*"))) return null

        // The parsed sub-expression covering the selection.
        val covering = findParsedExprCovering(parsed, range) ?: return null

        // Identifiers used inside the selection.
        val used = LinkedHashSet<String>()
        fun collectIds(e: ParsedExpr) {
            if (e is ParsedIdentifier) used.add(e.localName)
            for (c in e.children()) collectIds(c)
        }
        collectIds(covering)

        // Params of any lambda whose body region contains the selection — these
        // are the in-scope locals the slice may reference (its free vars).
        val enclosingParams = LinkedHashSet<String>()
        fun lambdaParamNames(l: ParsedLambda): List<String> = when (val p = l.params) {
            is ParsedLambdaParams.Untyped -> p.names
            is ParsedLambdaParams.Typed -> p.fields.map { it.name }
        }
        fun visitForEnclosing(e: ParsedExpr) {
            if (e is ParsedLambda) {
                val b = e.body.loc
                val within = (b.startLine < range.start.line + 1 ||
                    (b.startLine == range.start.line + 1 && b.startCol <= range.start.character + 1)) &&
                    (b.endLine > range.end.line + 1 ||
                        (b.endLine == range.end.line + 1 && b.endCol >= range.end.character))
                if (within) enclosingParams.addAll(lambdaParamNames(e))
            }
            for (c in e.children()) visitForEnclosing(c)
        }
        for (decl in parsed.decls) {
            when (decl) {
                is ParsedLetDecl -> visitForEnclosing(decl.value)
                is ParsedTopLevelExpr -> visitForEnclosing(decl.expression)
                else -> {}
            }
        }

        val freeVars = used.filter { it in enclosingParams }
        val paramList = if (freeVars.isEmpty()) "{}" else "{ ${freeVars.joinToString(", ")} }"
        val argList = if (freeVars.isEmpty()) "{}" else "{ ${freeVars.joinToString(", ") { "$it = $it" }} }"

        // New top-level function inserted before the enclosing decl.
        val enclosingDeclLine = parsed.decls.filterIsInstance<ParsedLetDecl>()
            .firstOrNull { d ->
                val l = d.loc
                (l.startLine - 1) <= range.start.line && (l.endLine - 1) >= range.end.line
            }?.loc?.startLine?.minus(1) ?: return null

        val fnText = "let extracted = $paramList -> $slice\n\n"
        return CodeAction("Extract to function").apply {
            kind = CodeActionKind.RefactorExtract
            edit = WorkspaceEdit(mapOf(uri to listOf(
                TextEdit(Range(Position(enclosingDeclLine, 0), Position(enclosingDeclLine, 0)), fnText),
                TextEdit(range, "extracted $argList"),
            )))
        }
    }

    /**
     * Build the "Inline binding" refactoring action.
     *
     * Fires when the cursor sits on a `let <name> = <value>` line whose
     * value is a simple expression. We replace every same-file occurrence
     * of `<name>` (using a word-boundary regex) with `<value>` and delete
     * the original let line. Conservative: skips when `<value>` spans
     * multiple lines or contains complex structure.
     */
    private fun buildInlineBindingAction(uri: String, state: DocumentState, range: Range): CodeAction? {
        // Find the do-block `let` binding under the cursor in the PARSED AST.
        val parsed = state.parsedModule ?: return null
        val (name, valueExpr, letLoc) = findLetBindingAt(parsed, range) ?: return null

        // Only inline simple values — a lambda / block / record-literal value
        // needs real substitution semantics beyond a textual splice.
        if (valueExpr is ParsedLambda || valueExpr is ParsedDo || valueExpr is ParsedRecordLit) return null
        val value = sliceSource(state.content, valueExpr.loc)

        // Reference sites come from the TYPED AST (scope-aware TypedVar walk),
        // not a text `\bname\b` regex that would also hit comments/strings and
        // shadowed bindings.
        val typedModule = state.typeCheckResult?.typedModule ?: return null
        val refs = mutableListOf<Location>()
        for (d in typedModule.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()) {
            collectReferencesInExpr(d.value, name, uri, refs)
        }
        // Only the uses (exclude the binding's own def-site range, and any hit
        // that overlaps the let line itself).
        val useEdits = refs
            .filter { it.range.start.line != (letLoc.startLine - 1) }
            .map { TextEdit(it.range, value) }
        if (useEdits.isEmpty()) return null // never used → that's remove-dead-code, not inline

        val edits = mutableListOf<TextEdit>()
        // Delete the whole let line (including trailing newline).
        edits.add(TextEdit(Range(Position(letLoc.startLine - 1, 0), Position(letLoc.startLine, 0)), ""))
        edits.addAll(useEdits)

        return CodeAction("Inline binding `$name`").apply {
            kind = CodeActionKind.RefactorInline
            edit = WorkspaceEdit(mapOf(uri to edits))
        }
    }

    /**
     * Find the do-block `let <name> = <value>` binding whose line the cursor
     * [range] sits on, in the parsed AST. Returns (name, valueExpr, letLoc).
     */
    private fun findLetBindingAt(
        module: ParsedModule,
        range: Range
    ): Triple<String, ParsedExpr, com.mezze.language.parsedast.Loc>? {
        val cursorLine = range.start.line + 1
        var found: Triple<String, ParsedExpr, com.mezze.language.parsedast.Loc>? = null
        fun visit(e: ParsedExpr) {
            if (e is ParsedDo) {
                for (stmt in e.statements) {
                    if (stmt is ParsedStatement.Let) {
                        val pat = stmt.pattern
                        if (pat is ParsedVariablePattern && stmt.loc.startLine == cursorLine) {
                            found = Triple(pat.name, stmt.value, stmt.loc)
                        }
                    }
                }
            }
            for (child in e.children()) visit(child)
        }
        for (decl in module.decls) {
            when (decl) {
                is ParsedLetDecl -> visit(decl.value)
                is ParsedTopLevelExpr -> visit(decl.expression)
                else -> {}
            }
        }
        return found
    }

    /**
     * Build the "Convert if to match" refactoring action, AST-driven.
     *
     * Finds the [ParsedIf] enclosing the cursor in state.parsedModule and
     * rewrites `if c then t else e` as `match c is  True -> t  False -> e`,
     * slicing each of c/t/e verbatim from its own loc — so nested and
     * multi-line branches survive intact (the old text-regex version could
     * only handle single-line).
     */
    private fun buildConvertIfToMatchAction(uri: String, state: DocumentState, range: Range): CodeAction? {
        val parsed = state.parsedModule ?: return null
        val node = findParsedExprCovering(parsed, range) { it is ParsedIf } as? ParsedIf ?: return null
        val content = state.content
        val indent = " ".repeat((node.loc.startCol - 1).coerceAtLeast(0))
        val cond = sliceSource(content, node.condition.loc)
        val thenBranch = sliceSource(content, node.thenBranch.loc)
        val elseBranch = sliceSource(content, node.elseBranch.loc)
        val replacement = buildString {
            append("match "); append(cond); append(" is\n")
            append(indent); append("  True -> "); append(thenBranch); append("\n")
            append(indent); append("  False -> "); append(elseBranch)
        }
        return CodeAction("Convert if to match").apply {
            kind = CodeActionKind.RefactorRewrite
            edit = WorkspaceEdit(mapOf(uri to listOf(
                TextEdit(locToRange(node.loc), replacement),
            )))
        }
    }

    /**
     * #105: build the edit that appends a `<Ctor> -> ???` arm for each missing
     * constructor to a non-exhaustive match. [matchLoc] is the whole match
     * expression. Arms are indented to match the existing arms (detected from
     * the first arm line, or match-indent + 2 as a fallback), inserted after the
     * match's last line.
     */
    private fun buildFillMatchArmsEdit(
        state: DocumentState,
        matchLoc: Range,
        missingConstructors: List<String>
    ): TextEdit? {
        if (missingConstructors.isEmpty()) return null
        val lines = state.content.split('\n')
        val startLineIdx = matchLoc.start.line
        val endLineIdx = matchLoc.end.line
        val matchLine = lines.getOrNull(startLineIdx) ?: return null
        val matchIndent = matchLine.takeWhile { it == ' ' }.length

        // Arm indent: from the first non-blank line after the `match … is` line
        // that looks like an arm; else match-indent + 2.
        val armIndent = ((startLineIdx + 1)..endLineIdx)
            .asSequence()
            .mapNotNull { lines.getOrNull(it) }
            .firstOrNull { it.isNotBlank() }
            ?.takeWhile { it == ' ' }?.length
            ?: (matchIndent + 2)
        val pad = " ".repeat(armIndent)

        val newArms = missingConstructors.joinToString("") { ctor -> "\n$pad$ctor -> ???" }

        // Insert at the end of the match's last line.
        val lastLine = lines.getOrNull(endLineIdx) ?: return null
        val at = Position(endLineIdx, lastLine.length)
        return TextEdit(Range(at, at), newArms)
    }

    /**
     * #107: build the edit that deletes the span at [loc] and its dangling
     * separator, so the result stays well-formed. Two shapes:
     *
     *  - A record field (`{ a = 1, a = 2 }`): the field sits mid-line with
     *    other text around it, so we widen to eat a trailing `, ` (or a
     *    leading `, ` when it's the last field) and leave the rest of the
     *    line intact.
     *  - A match arm: nothing but indentation precedes it on its start line,
     *    so we delete whole lines — from column 0 of the start line through
     *    the start of the line *after* the end line — removing the arm and
     *    its own newline.
     */
    private fun buildRemoveRangeEdit(state: DocumentState, loc: Range): TextEdit {
        val lines = state.content.split('\n')
        val startLine = lines.getOrNull(loc.start.line)
        val precedingIsBlank = startLine != null &&
            startLine.substring(0, loc.start.character.coerceAtMost(startLine.length)).isBlank()

        // Arm shape: only whitespace before the span on its line → line delete.
        if (precedingIsBlank) {
            val delStart = Position(loc.start.line, 0)
            // Delete through the start of the next line so the arm's newline
            // goes too; if it's the last line, fall back to end-of-line.
            val delEnd = if (loc.end.line + 1 < lines.size)
                Position(loc.end.line + 1, 0)
            else
                Position(loc.end.line, (lines.getOrNull(loc.end.line)?.length ?: loc.end.character))
            return TextEdit(Range(delStart, delEnd), "")
        }

        // Field shape: widen the end to swallow a trailing `,` + spaces.
        val endLineText = lines.getOrNull(loc.end.line) ?: ""
        var endCol = loc.end.character
        while (endCol < endLineText.length && endLineText[endCol] == ' ') endCol++
        if (endCol < endLineText.length && endLineText[endCol] == ',') {
            endCol++
            while (endCol < endLineText.length && endLineText[endCol] == ' ') endCol++
            return TextEdit(Range(loc.start, Position(loc.end.line, endCol)), "")
        }
        // No trailing comma (last field) — eat a leading `, ` instead.
        val startLineText = lines.getOrNull(loc.start.line) ?: ""
        var startCol = loc.start.character
        while (startCol > 0 && startLineText[startCol - 1] == ' ') startCol--
        if (startCol > 0 && startLineText[startCol - 1] == ',') {
            startCol--
            while (startCol > 0 && startLineText[startCol - 1] == ' ') startCol--
            return TextEdit(Range(Position(loc.start.line, startCol), loc.end), "")
        }
        // No separator either side — delete the span verbatim.
        return TextEdit(loc, "")
    }

    /**
     * Pull structured [FixHintData] entries out of the diagnostic's
     * `data` field. We put them there in publishDiagnostics by reading
     * `TypeCheckError.fixHints`. Returns null when the diagnostic has
     * no hints attached (most do not).
     */
    private fun extractFixHints(diagnostic: Diagnostic): List<FixHintData>? {
        val data = diagnostic.data ?: return null
        // diagnostic.data round-trips through JSON-RPC as gson JsonElement on
        // the receive side, but is a plain Map on the same-VM publish side.
        // Normalize both into Map<String, Any?> via dataAsMap before reading.
        val map = dataAsMap(data) ?: return null
        val raw = map["fixHints"] ?: return null
        val rawList = dataAsList(raw) ?: return null
        return rawList.mapNotNull { entryAny ->
            val entry = dataAsMap(entryAny) ?: return@mapNotNull null
            val kind = entry["kind"] as? String ?: return@mapNotNull null
            val loc = decodeLoc(entry["loc"]) ?: return@mapNotNull null
            when (kind) {
                "Rename" -> {
                    val candidates = dataAsList(entry["candidates"])?.mapNotNull { it as? String }
                        ?: return@mapNotNull null
                    FixHintData.Rename(loc, candidates)
                }
                "Import" -> {
                    val name = entry["name"] as? String ?: return@mapNotNull null
                    val modules = dataAsList(entry["candidateModules"])?.mapNotNull { it as? String }
                        ?: return@mapNotNull null
                    FixHintData.Import(loc, name, modules)
                }
                "WrapInPerform" -> {
                    val effectName = entry["effectName"] as? String ?: return@mapNotNull null
                    val performers = dataAsList(entry["candidatePerformers"])?.mapNotNull { it as? String }
                        ?: emptyList()
                    FixHintData.WrapInPerform(loc, effectName, performers)
                }
                "AddEffectToAnnotation" -> {
                    val effectName = entry["effectName"] as? String ?: return@mapNotNull null
                    FixHintData.AddEffectToAnnotation(loc, effectName)
                }
                "AddPub" -> {
                    val modulePath = entry["declModulePath"] as? String ?: return@mapNotNull null
                    val declName = entry["declName"] as? String ?: return@mapNotNull null
                    FixHintData.AddPub(loc, modulePath, declName)
                }
                "FillMatchArms" -> {
                    val ctors = dataAsList(entry["missingConstructors"])?.mapNotNull { it as? String }
                        ?: return@mapNotNull null
                    FixHintData.FillMatchArms(loc, ctors)
                }
                "RemoveRange" -> {
                    val label = entry["label"] as? String ?: return@mapNotNull null
                    FixHintData.RemoveRange(loc, label)
                }
                "WrapInConstructor" -> {
                    val ctor = entry["constructor"] as? String ?: return@mapNotNull null
                    FixHintData.WrapInConstructor(loc, ctor)
                }
                "WrapInMethodCall" -> {
                    @Suppress("UNCHECKED_CAST")
                    val methods = (entry["methods"] as? List<*>)?.mapNotNull { it as? String }
                        ?: return@mapNotNull null
                    FixHintData.WrapInMethodCall(loc, methods)
                }
                else -> null
            }
        }
    }

    private fun decodeLoc(raw: Any?): Range? {
        val map = dataAsMap(raw) ?: return null
        val sl = (map["startLine"] as? Number)?.toInt() ?: return null
        val sc = (map["startCol"] as? Number)?.toInt() ?: return null
        val el = (map["endLine"] as? Number)?.toInt() ?: return null
        val ec = (map["endCol"] as? Number)?.toInt() ?: return null
        return Range(Position(sl - 1, sc - 1), Position(el - 1, ec))
    }

    /** Accept either an in-process Map or a gson JsonObject. */
    @Suppress("UNCHECKED_CAST")
    private fun dataAsMap(raw: Any?): Map<String, Any?>? {
        if (raw == null) return null
        if (raw is Map<*, *>) return raw as Map<String, Any?>
        // gson JsonObject path — entrySet returns Map.Entry<String, JsonElement>.
        if (raw is com.google.gson.JsonObject) {
            val out = mutableMapOf<String, Any?>()
            for ((k, v) in raw.entrySet()) out[k] = unwrapJsonElement(v)
            return out
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun dataAsList(raw: Any?): List<Any?>? {
        if (raw == null) return null
        if (raw is List<*>) return raw
        if (raw is com.google.gson.JsonArray) {
            return raw.map { unwrapJsonElement(it) }
        }
        return null
    }

    private fun unwrapJsonElement(e: com.google.gson.JsonElement?): Any? {
        if (e == null || e.isJsonNull) return null
        return when {
            e.isJsonPrimitive -> {
                val p = e.asJsonPrimitive
                when {
                    p.isString -> p.asString
                    p.isBoolean -> p.asBoolean
                    p.isNumber -> p.asNumber  // Number — callers use .toInt() etc
                    else -> null
                }
            }
            e.isJsonObject -> {
                val obj = e.asJsonObject
                val out = mutableMapOf<String, Any?>()
                for ((k, v) in obj.entrySet()) out[k] = unwrapJsonElement(v)
                out
            }
            e.isJsonArray -> e.asJsonArray.map { unwrapJsonElement(it) }
            else -> null
        }
    }

    private fun locToRange(range: Range): Range = range

    private sealed class FixHintData {
        data class Rename(val loc: Range, val candidates: List<String>) : FixHintData()
        data class Import(val loc: Range, val name: String, val candidateModules: List<String>) : FixHintData()
        data class WrapInPerform(val loc: Range, val effectName: String, val candidatePerformers: List<String>) : FixHintData()
        data class AddEffectToAnnotation(val loc: Range, val effectName: String) : FixHintData()
        data class AddPub(val loc: Range, val declModulePath: String, val declName: String) : FixHintData()
        data class FillMatchArms(val loc: Range, val missingConstructors: List<String>) : FixHintData()
        data class RemoveRange(val loc: Range, val label: String) : FixHintData()
        data class WrapInConstructor(val loc: Range, val constructor: String) : FixHintData()
        data class WrapInMethodCall(val loc: Range, val methods: List<String>) : FixHintData()
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

        // Route through the Formatter so the inserted text gets the same
        // spacing / style the user would get from `mez fmt`. Today a bare
        // `use a::b` round-trips unchanged, but routing here means the day
        // we tweak the formatter style (e.g. `use { a as b }` width), the
        // quick-fix lands consistent without a second change site.
        val formatted = formatter.formatSnippet("use $modulePath::$name\n")
        val newLine = if (formatted.endsWith("\n")) formatted else formatted + "\n"

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

    /**
     * Build the cross-file edit for an `AddPub` code action: prepend `pub `
     * to `declName`'s declaration line in the file backing `declModulePath`.
     *
     * Strategy is intentionally line-based, not AST-based, because we need
     * to edit a file that may not be open in the editor (so no parsed AST
     * exists for it). We:
     *   1. Resolve the module path to a file on disk
     *   2. Read its bytes (or the open document if cached in `documents`)
     *   3. Scan for the first line whose first non-whitespace token is one
     *      of `let|type|effect|ability|impl` followed by `declName`
     *   4. Insert `pub ` at that line's first non-whitespace column
     *
     * Returns null if any step fails — the lightbulb falls back to the
     * title-only action.
     */
    private fun buildAddPubEdit(modulePath: String, declName: String): WorkspaceEdit? {
        val uri = resolveModuleUri(modulePath) ?: return null
        // Prefer the in-memory copy if the file is already open in VS Code
        // (matches whatever unsaved edits the user has).
        val openText = documents[uri]?.content
        val text = openText ?: try {
            java.io.File(java.net.URI(uri)).readText()
        } catch (_: Exception) { return null }

        val lines = text.split('\n')
        // Match: optional leading whitespace, then the decl keyword, then
        // a space, then declName as a whole identifier. We deliberately do
        // not match `pub …` lines (those are already public).
        val declKeywords = listOf("let", "type", "effect", "ability", "impl")
        val pattern = Regex("^(\\s*)(${declKeywords.joinToString("|")})\\s+${Regex.escape(declName)}\\b")
        for ((idx, line) in lines.withIndex()) {
            val m = pattern.find(line) ?: continue
            val leadingWs = m.groupValues[1]
            val insertCol = leadingWs.length  // column where the keyword starts
            val pos = Position(idx, insertCol)
            val edit = TextEdit(Range(pos, pos), "pub ")
            return WorkspaceEdit(mapOf(uri to listOf(edit)))
        }
        return null
    }

    /**
     * Build the in-file edit for an `AddEffectToAnnotation` action: find
     * the enclosing function declaration whose return-type annotation needs
     * to mention `effectName`, then either:
     *   - replace `-> ReturnType` with `-> ReturnType <effectName>` if no
     *     effect row exists yet
     *   - or replace `-> ReturnType <ExistingEffects>` with
     *     `-> ReturnType <ExistingEffects, effectName>`
     *
     * We do this by scanning lines around the diagnostic loc for an arrow
     * (`->`) inside what looks like the enclosing decl's type annotation.
     * Falls back to null when we can't pin it down — better to skip the
     * edit than to mangle the source.
     */
    private fun buildAddEffectToAnnotationEdit(
        uri: String,
        loc: Range,
        effectName: String,
    ): WorkspaceEdit? {
        val state = documents[uri] ?: return null
        val parsed = state.parsedModule ?: return null
        // Find the enclosing top-level let-decl whose body contains `loc`.
        val enclosing = parsed.decls
            .filterIsInstance<com.mezze.language.parsedast.ParsedLetDecl>()
            .firstOrNull { decl ->
                val s = decl.loc
                val containsStart = (s.startLine - 1 < loc.start.line) ||
                    (s.startLine - 1 == loc.start.line && s.startCol - 1 <= loc.start.character)
                val containsEnd = (s.endLine - 1 > loc.end.line) ||
                    (s.endLine - 1 == loc.end.line && s.endCol >= loc.end.character)
                containsStart && containsEnd
            } ?: return null

        // Pull the annotation's source range. ParsedLetDecl exposes a
        // `typeAnnotation` whose loc we use. If absent, we can't add an
        // effect row (we'd have to synthesize the whole type) — bail.
        val ann = enclosing.typeAnnotation ?: return null
        val annStartLine = ann.loc.startLine - 1
        val annEndLine = ann.loc.endLine - 1
        val annEndCol = ann.loc.endCol
        // Read the annotation text from the document.
        val lines = state.content.split('\n')
        if (annEndLine !in lines.indices) return null
        val tailLine = lines[annEndLine]
        val pos = Position(annEndLine, minOf(annEndCol, tailLine.length))
        // If the annotation already ends with `>` we splice into the existing
        // effect row; otherwise we append a fresh `<effectName>`. The
        // existence check is line-local — good enough for typical Mezze.
        val containingChar = tailLine.getOrNull(pos.character - 1)
        val insertion = if (containingChar == '>') {
            // splice: move one char before `>` and prepend `, effectName`
            val splicePos = Position(annEndLine, pos.character - 1)
            return WorkspaceEdit(mapOf(uri to listOf(
                TextEdit(Range(splicePos, splicePos), ", $effectName")
            )))
        } else {
            " <$effectName>"
        }
        return WorkspaceEdit(mapOf(uri to listOf(
            TextEdit(Range(pos, pos), insertion)
        )))
    }

    /**
     * Build the in-file edit for a `WrapInPerform` action: wrap the
     * expression at `loc` in `perform <handler> in do … end` for the first
     * candidate performer the diagnostic offered. We replace the source
     * slice at `loc` rather than trying to find an enclosing expression —
     * Mezze's `perform … in do <single_expr> end` shape is the common one
     * and parses identically to `do <expr> end`.
     */
    private fun buildWrapInPerformEdit(
        uri: String,
        loc: Range,
        handler: String,
    ): WorkspaceEdit? {
        val state = documents[uri] ?: return null
        val lines = state.content.split('\n')
        if (loc.start.line !in lines.indices || loc.end.line !in lines.indices) return null
        val original = extractSlice(state.content, loc) ?: return null
        val wrapped = "perform $handler in do\n    $original\nend"
        return WorkspaceEdit(mapOf(uri to listOf(TextEdit(loc, wrapped))))
    }

    /** Read the text inside [loc] from [source] (LSP positions, 0-based). */
    private fun extractSlice(source: String, loc: Range): String? {
        val lines = source.split('\n')
        if (loc.start.line == loc.end.line) {
            val line = lines.getOrNull(loc.start.line) ?: return null
            if (loc.start.character > line.length || loc.end.character > line.length) return null
            return line.substring(loc.start.character, loc.end.character)
        }
        val sb = StringBuilder()
        for (li in loc.start.line..loc.end.line) {
            val line = lines.getOrNull(li) ?: return null
            when (li) {
                loc.start.line -> sb.append(line.substring(loc.start.character))
                loc.end.line -> sb.append(line.substring(0, minOf(loc.end.character, line.length)))
                else -> sb.append(line)
            }
            if (li != loc.end.line) sb.append('\n')
        }
        return sb.toString()
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
            var paramCount = 0
            if (funcType.paramType is MezzeType.RecordType) {
                val recordType = funcType.paramType as MezzeType.RecordType
                sigInfo.parameters = recordType.fields.map { (name, type) ->
                    ParameterInformation("$name: ${prettyPrintTypeWithAliases(type, state.typeAliases)}")
                }
                paramCount = recordType.fields.size
            }

            // Which argument is the cursor in? Count top-level commas inside the
            // `{ … }` call block before the cursor. `f { a = 1, b = |` → index 1.
            // Commas nested in braces/brackets/parens don't advance the arg. The
            // regex guaranteed no `}` between the call's `{` and the cursor, so
            // the relevant opening brace is the last `{` before the cursor.
            val activeParam = activeArgumentIndex(beforeCursor, beforeCursor.lastIndexOf('{'))
                .coerceIn(0, maxOf(0, paramCount - 1))

            SignatureHelp(listOf(sigInfo), 0, activeParam)
        }
    }

    /**
     * The zero-based argument index the cursor is in, given the text up to the
     * cursor and the offset of the call's opening `{`. Counts commas at
     * brace-depth 1 (i.e. directly inside the call's record), ignoring commas
     * nested one level deeper in `{}` / `[]` / `()`.
     */
    private fun activeArgumentIndex(beforeCursor: String, openBraceOffset: Int): Int {
        var depth = 0
        var commas = 0
        var i = openBraceOffset
        while (i < beforeCursor.length) {
            when (beforeCursor[i]) {
                '{', '[', '(' -> depth++
                '}', ']', ')' -> depth--
                ',' -> if (depth == 1) commas++
            }
            i++
        }
        return commas
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

    // ========== On-Type Formatting ==========
    //
    // Fires whenever the user types one of the configured trigger chars.
    // We use it to re-format the file when a record/match closer (`}`) is
    // typed. Mezze's formatter is whole-file by design — the parser needs
    // a complete decl tree — so we run the full pipeline and trust the
    // editor to apply only the diff. If parse fails (mid-edit), bail.

    override fun onTypeFormatting(
        params: DocumentOnTypeFormattingParams
    ): CompletableFuture<List<TextEdit>> {
        val uri = params.textDocument.uri
        val state = documents[uri]
            ?: return CompletableFuture.completedFuture(emptyList())
        // Act on `}` (block just closed) and `\n` (Enter). Both run the
        // whole-file formatter, which no-ops when the buffer doesn't parse
        // (the common mid-edit case), so incomplete lines don't reflow.
        if (params.ch != "}" && params.ch != "\n") return CompletableFuture.completedFuture(emptyList())
        return CompletableFuture.supplyAsync {
            try {
                val formatted = formatter.format(state.content)
                if (formatted == state.content) return@supplyAsync emptyList()
                val lines = state.content.lines()
                listOf(TextEdit(
                    Range(
                        Position(0, 0),
                        Position(lines.size - 1, lines.lastOrNull()?.length ?: 0),
                    ),
                    formatted,
                ))
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    // ========== Range Formatting ==========
    //
    // Mezze's formatter operates on full modules — it has to parse a complete
    // top-level decl tree to know what to print. For range formatting we
    // pragmatically format the whole document, then only return the slice of
    // edits that fall inside the requested range. If the user's range
    // partially overlaps a decl, the conservative thing is to widen the
    // returned edit to the decl boundary (the formatter can't produce a
    // half-decl). For now we just return the full-document edit when the
    // range is non-empty — VS Code handles that fine.

    override fun rangeFormatting(
        params: DocumentRangeFormattingParams
    ): CompletableFuture<List<TextEdit>> {
        val uri = params.textDocument.uri
        val state = documents[uri]
            ?: return CompletableFuture.completedFuture(emptyList())

        return CompletableFuture.supplyAsync {
            try {
                val formatted = formatter.format(state.content)
                if (formatted == state.content) return@supplyAsync emptyList()
                val lines = state.content.lines()
                listOf(TextEdit(
                    Range(Position(0, 0), Position(lines.size - 1, lines.lastOrNull()?.length ?: 0)),
                    formatted,
                ))
            } catch (e: Exception) {
                server.logError("Range formatting failed: ${e.message}")
                emptyList()
            }
        }
    }

    // ========== Prepare Rename ==========
    //
    // VS Code calls this before invoking `rename`. Returning null disables
    // the rename UI for the cursor position (e.g. on keywords or numeric
    // literals where rename makes no sense). We delegate the actual range
    // computation to the identifier token at the cursor — the rename impl
    // itself does symbol-resolution; this is just gating UX.

    override fun prepareRename(
        params: PrepareRenameParams
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>?> {
        val uri = params.textDocument.uri
        val state = documents[uri]
            ?: return CompletableFuture.completedFuture(null)
        val position = params.position
        return CompletableFuture.supplyAsync {
            val line = state.content.split('\n').getOrNull(position.line) ?: return@supplyAsync null
            val col = position.character
            if (col < 0 || col > line.length) return@supplyAsync null
            // Find the identifier token (word characters, including ':' for
            // qualified names) that brackets the cursor.
            var start = col
            while (start > 0 && line[start - 1].let { it.isLetterOrDigit() || it == '_' }) start--
            var end = col
            while (end < line.length && line[end].let { it.isLetterOrDigit() || it == '_' }) end++
            if (end == start) return@supplyAsync null  // not on an identifier
            val word = line.substring(start, end)
            // Reject keywords — renaming `let` makes no sense.
            if (word in mezzeKeywordsForRenameGate) return@supplyAsync null
            Either3.forFirst(Range(Position(position.line, start), Position(position.line, end)))
        }
    }

    // ========== Document Links ==========
    //
    // Turns `use foo::bar` import paths into clickable links pointing at the
    // module file. The path → file resolution reuses the parser's already-
    // resolved import context when we have one; otherwise falls back to
    // a relative search rooted at the workspace.

    override fun documentLink(
        params: DocumentLinkParams
    ): CompletableFuture<List<DocumentLink>> {
        val uri = params.textDocument.uri
        val state = documents[uri]
            ?: return CompletableFuture.completedFuture(emptyList())
        val parsed = state.parsedModule
            ?: return CompletableFuture.completedFuture(emptyList())

        return CompletableFuture.supplyAsync {
            val links = mutableListOf<DocumentLink>()
            for (decl in parsed.decls.filterIsInstance<com.mezze.language.parsedast.ParsedUseDecl>()) {
                val modulePathStr = when (val p = decl.path) {
                    is com.mezze.language.parsedast.ParsedUsePath.Module -> p.path.segments.joinToString("::")
                    is com.mezze.language.parsedast.ParsedUsePath.Selective -> p.modulePath.segments.joinToString("::")
                }
                val target = resolveModuleUri(modulePathStr) ?: continue
                val range = locToRange(decl.loc)
                val link = DocumentLink(range, target)
                links.add(link)
            }
            links
        }
    }

    // ========== Go to Type Definition ==========
    //
    // On a value at the cursor, jump to the declaration of its *type*. For
    // `let xs: Seq<Int>` clicking on `xs` jumps to `Seq`'s decl, not `xs`'s
    // binding. Reuses the same hover-style type lookup the inlay-hint code
    // already does, then converts the resolved type to a definition site.

    override fun typeDefinition(
        params: TypeDefinitionParams
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        val uri = params.textDocument.uri
        val state = documents[uri]
            ?: return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        return CompletableFuture.supplyAsync {
            val typeName = resolveTypeNameAt(state, params.position)
                ?: return@supplyAsync Either.forLeft<List<Location>, List<LocationLink>>(emptyList())
            val loc = findTypeDeclLocation(state, typeName)
                ?: return@supplyAsync Either.forLeft<List<Location>, List<LocationLink>>(emptyList())
            Either.forLeft(listOf(loc))
        }
    }

    // ========== Go to Implementation ==========
    //
    // On an ability method name at the cursor, surface every `impl` that
    // implements that method, across the project. Falls back to single-
    // file scope when MezzeStore isn't available.

    override fun implementation(
        params: ImplementationParams
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        val uri = params.textDocument.uri
        val state = documents[uri]
            ?: return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        return CompletableFuture.supplyAsync {
            val name = resolveIdentifierAt(state, params.position)
                ?: return@supplyAsync Either.forLeft<List<Location>, List<LocationLink>>(emptyList())
            val locs = findImplementationsForMethod(state, name)
            Either.forLeft(locs)
        }
    }

    // ----- helpers for the new handlers ------------------------------------

    private val mezzeKeywordsForRenameGate = setOf(
        "let", "do", "end", "if", "then", "else", "match", "with", "for", "in",
        "as", "pub", "use", "type", "effect", "impl", "ability", "perform",
        "where", "scope", "of", "this",
    )

    /**
     * Best-effort resolve a relative module path string (e.g. `foo::bar`)
     * to a `file://...` URI under the workspace root. Mezze packages live
     * at `src/foo/bar.mz` by convention; we don't recurse into `dist/`.
     */
    private fun resolveModuleUri(modulePath: String): String? {
        val root = server.getWorkspaceRoot() ?: return null
        val rel = modulePath.replace("::", java.io.File.separator) + ".mz"
        val candidate = java.io.File(root, "src" + java.io.File.separator + rel)
        return if (candidate.exists()) candidate.toURI().toString() else null
    }

    private fun locToRange(loc: com.mezze.language.parsedast.Loc): Range = Range(
        Position(maxOf(0, loc.startLine - 1), maxOf(0, loc.startCol - 1)),
        Position(maxOf(0, loc.endLine - 1), maxOf(0, loc.endCol)),
    )

    /** Pull the identifier token at a position out of the source. */
    private fun resolveIdentifierAt(state: DocumentState, pos: Position): String? {
        val line = state.content.split('\n').getOrNull(pos.line) ?: return null
        val col = pos.character
        if (col < 0 || col > line.length) return null
        var s = col; while (s > 0 && line[s - 1].let { it.isLetterOrDigit() || it == '_' }) s--
        var e = col; while (e < line.length && line[e].let { it.isLetterOrDigit() || it == '_' }) e++
        return if (e > s) line.substring(s, e) else null
    }

    /** Use the latest TypedAST to recover the *type name* of the symbol at the cursor. */
    private fun resolveTypeNameAt(state: DocumentState, pos: Position): String? {
        val name = resolveIdentifierAt(state, pos) ?: return null

        // Prefer the WRITTEN annotation: `let c: Color = …` names `Color`
        // literally, whereas the inferred type is a nameless VariantType (the
        // declared name isn't carried on MezzeType). Fall back to the inferred
        // type's constructor for types that do carry a name (TypeApplication).
        state.parsedModule?.decls?.filterIsInstance<ParsedLetDecl>()
            ?.firstOrNull { it.isSimpleBinding && it.variableName == name }
            ?.typeAnnotation
            ?.let { annotationTypeName(it) }
            ?.let { return it }

        val typed = (state.typeCheckResult ?: lastSuccessfulResult[state.uri])?.typedModule ?: return null
        for (decl in typed.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()) {
            if (decl.name == name) return typeConstructorName(decl.scheme.type)
        }
        return null
    }

    /** The head type NAME of a written annotation (`Color`, `Option`, qualified `codec::Entry`), or null. */
    private fun annotationTypeName(ann: com.mezze.language.parsedast.ParsedTypeAnnotation): String? = when (ann) {
        is com.mezze.language.parsedast.ParsedNamedType -> ann.name
        is com.mezze.language.parsedast.ParsedTypeApp -> ann.constructor
        is com.mezze.language.parsedast.ParsedQualifiedType -> ann.typeName
        else -> null
    }

    // The name of the DECLARATION this type refers to, for go-to-definition.
    // This used to end in `t::class.simpleName.removeSuffix("Type")`, which
    // renders MezzeType.StringType as `String` — a type name Mezze does not
    // have, so the lookup it fed searched for a declaration that cannot exist.
    private fun typeConstructorName(t: com.mezze.language.ast.MezzeType): String? =
        com.mezze.language.ast.TypeNames.nominal(t)

    /** Find the file+range where a type with the given name is declared. */
    private fun findTypeDeclLocation(state: DocumentState, typeName: String): Location? {
        // Local file first.
        val localParsed = state.parsedModule
        if (localParsed != null) {
            for (decl in localParsed.decls.filterIsInstance<com.mezze.language.parsedast.ParsedTypeDecl>()) {
                if (decl.name == typeName) return Location(state.uri, locToRange(decl.loc))
            }
        }
        // Cross-module: query the store's TYPE decl index (covers stdlib +
        // any indexed workspace module) and resolve the owning module's file.
        val store = projectStore ?: return null
        val row = try {
            store.getDeclsByKind(com.mezze.language.store.DeclKind.TYPE)
                .firstOrNull { it.declName == typeName }
        } catch (_: Exception) { null } ?: return null
        val uri = resolveModulePathToUri(row.modulePath) ?: return null
        return Location(uri, Range(
            Position(row.startLine - 1, row.startCol - 1),
            Position(row.endLine - 1, row.endCol),
        ))
    }

    /** Surface impl blocks across the project that implement `methodName`. */
    private fun findImplementationsForMethod(state: DocumentState, methodName: String): List<Location> {
        val out = mutableListOf<Location>()
        // Local file first — precise arm locs.
        val localParsed = state.parsedModule
        if (localParsed != null) {
            // #147: the name is an ABILITY decl → all impls of that ability;
            // an EFFECT decl → all its performers. `find-implementations` on
            // `Show`/`State` (the decl name) lists every `impl Show for …` /
            // `impl State for …`. Local file + all open docs.
            val isAbility = localParsed.decls.filterIsInstance<ParsedAbilityDecl>().any { it.name == methodName }
            val isEffect = localParsed.decls.filterIsInstance<ParsedEffectDecl>().any { it.name == methodName }
            if (isAbility || isEffect) {
                for ((docUri, docState) in documents) {
                    val pm = docState.parsedModule ?: continue
                    if (isAbility) pm.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>()
                        .filter { it.abilityName == methodName }
                        .forEach { out.add(Location(docUri, locToRange(it.loc))) }
                    if (isEffect) pm.decls.filterIsInstance<ParsedPerformerDecl>()
                        .filter { it.effect.localName == methodName }
                        .forEach { out.add(Location(docUri, locToRange(it.loc))) }
                }
                if (out.isNotEmpty()) return out
            }

            for (impl in localParsed.decls.filterIsInstance<com.mezze.language.parsedast.ParsedImplAbilityDecl>()) {
                for (op in impl.operations) {
                    if (op.name == methodName) out.add(Location(state.uri, locToRange(op.loc)))
                }
            }
        }

        // Cross-file: TypedAbilityImpl.operations is keyed by method name, so we
        // match directly and jump to the impl's loc. Two sources, unioned:
        //  - every OPEN document's typed module (covers workspace files the user
        //    has open — these aren't in ModuleTypeChecker's global cache), and
        //  - ModuleTypeChecker's cache (covers stdlib + transitively-typed deps).
        // Each impl's owning file: the open document's own URI, or the module
        // path resolved to a workspace/stdlib file.
        fun scanImpls(typedModule: com.mezze.language.typedast.TypedModule?, ownerUri: String?) {
            if (typedModule == null) return
            for (impl in typedModule.abilityImpls) {
                if (!impl.operations.containsKey(methodName)) continue
                val uri = ownerUri ?: resolveModulePathToUri(typedModule.path) ?: continue
                val loc = Location(uri, locToRange(impl.loc))
                if (out.none { it.uri == loc.uri && it.range == loc.range }) out.add(loc)
            }
        }
        try {
            for ((docUri, docState) in documents) {
                scanImpls((docState.typeCheckResult ?: lastSuccessfulResult[docUri])?.typedModule, docUri)
            }
            for ((modulePath, moduleResult) in com.mezze.language.typechecker.module.ModuleTypeChecker.getAllCachedModules()) {
                scanImpls(moduleResult.typedModule, resolveModulePathToUri(modulePath))
            }
        } catch (_: Exception) { /* cache miss — return what we have */ }
        return out
    }

    // ========== Inlay Hints ==========

    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> {
        return CompletableFuture.supplyAsync {
            if (!inlayHintsEnabled) return@supplyAsync emptyList()
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

            // 1c. Effects are NOT a separate hint layer. Effects live on the
            //     function ARROW, so the un-annotated `let` type hint above
            //     (`emitLetNameHint`) already renders them as part of the type —
            //     e.g. `greet : {name: Str} -> {} <Logger>`. A separate effect
            //     ghost-hint would just double that. One source of truth: the
            //     type hint. (See docs/effect-model.md — effects on arrows.)

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
    /**
     * #100: inlay `: T` hints on UN-annotated lambda parameters. For
     * `{ a, b } -> …` we show each param's inferred type; a param already
     * written as `a: Int` in source is skipped (checked by peeking at the char
     * after the name, so no parsed/typed matching is needed).
     */
    private fun emitParamHints(
        lambda: com.mezze.language.typedast.TypedLambda,
        sourceLines: List<String>,
        typeAliases: Map<String, TypeAliasInfo>,
        hints: MutableList<InlayHint>
    ) {
        // If the param list is written with annotations (`{x: Int} -> …`), the
        // whole list already has explicit types — don't hint any of its params.
        // Detected by scanning the source of the lambda's opening line up to the
        // arrow for a `:` (params rarely wrap across lines).
        val headLineIdx = lambda.loc.startLine - 1
        val headLine = sourceLines.getOrNull(headLineIdx) ?: return
        val arrowAt = headLine.indexOf("->")
        val paramRegion = if (arrowAt >= 0) headLine.substring(0, arrowAt) else headLine
        if (paramRegion.contains(':')) return

        for (param in lambda.params) {
            if (param.name.startsWith("_")) continue // intentionally-ignored param
            val typeStr = prettyPrintTypeWithAliases(param.type, typeAliases)
            if (isUninformativeType(typeStr)) continue

            if (param.loc.startLine != param.loc.endLine) continue
            val lineIdx = param.loc.startLine - 1
            // Anchor the hint right after the bare name (the param's whole span
            // when untyped, which is the only case we reach here).
            val anchorCol = param.loc.endCol
            val hint = InlayHint()
            hint.position = Position(lineIdx, anchorCol)
            hint.setLabel(": $typeStr")
            hint.kind = InlayHintKind.Type
            hint.textEdits = listOf(
                TextEdit(Range(Position(lineIdx, anchorCol), Position(lineIdx, anchorCol)), ": $typeStr")
            )
            hints.add(hint)
        }
    }

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
        var lineIdx = (anchorLine - 1).coerceAtLeast(0).coerceAtMost(sourceLines.lastIndex)
        var col = anchorCol
        // A multi-line body's loc can over-extend past its trailing blank lines
        // onto the FIRST column of a following line (anchorCol == 0). Anchoring
        // there drops a stray `: T` on top of the next decl (e.g. an `impl`
        // header). Walk back to the last line that actually has content and
        // anchor at its end instead.
        if (col == 0) {
            var i = lineIdx - 1
            while (i >= 0 && sourceLines[i].isBlank()) i--
            if (i < 0) return
            lineIdx = i
            col = sourceLines[i].length
        }
        // Never anchor a trailing type hint on a line that begins a new
        // declaration — the body doesn't extend there.
        val target = sourceLines.getOrNull(lineIdx)?.trimStart() ?: return
        if (Regex("^(pub\\s+)?(let|impl|type|newtype|effect|ability|use)\\b").containsMatchIn(target)) return
        val hint = InlayHint()
        hint.position = Position(lineIdx, col)
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
            is com.mezze.language.typedast.TypedLambda -> {
                if (sourceLines.isNotEmpty()) emitParamHints(expr, sourceLines, typeAliases, hints)
                collectPipeHints(expr.body, hints, typeAliases, sourceLines)
            }
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
            val oldName = HoverInfo.extractWordAt(lines[position.line], position.character) ?: return@supplyAsync null

            // Ability / effect NAME rename. Same positions references() covers
            // (decl header, impl headers, `with X`, `<X>` rows), across every
            // open document. Done before the hash path — these names carry no
            // hash, so that path would return an empty edit.
            if (isAbilityOrEffectName(state.parsedModule, oldName)) {
                val editsByName = LinkedHashMap<String, MutableList<TextEdit>>()
                for ((docUri, docState) in documents) {
                    for (range in findAbilityEffectNameRanges(docState.content, docState.parsedModule, oldName)) {
                        editsByName.getOrPut(docUri) { mutableListOf() }.add(TextEdit(range, newName))
                    }
                }
                if (editsByName.isEmpty()) return@supplyAsync null
                return@supplyAsync WorkspaceEdit(editsByName.mapValues { it.value.toList() })
            }

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

    /** An absolute (0-indexed) semantic token before delta-encoding. */
    private data class SemToken(val line: Int, val col: Int, val len: Int, val type: Int, val mods: Int)

    /**
     * Collect all semantic tokens for a document as absolute positions. Shared
     * by semanticTokensFull and semanticTokensRange. Two passes:
     *  1. the binding NAME of each top-level let (from the parsed pattern loc),
     *  2. every variable reference + constructor in the typed AST, classified by
     *     what it resolved to (BindingRef → param/local/function, variant ctor →
     *     enum member). All locs are exact.
     */
    private fun collectSemanticTokens(state: DocumentState): List<SemToken> {
        val parsedModule = state.parsedModule ?: return emptyList()
        // Legend indices (must match the order in server capabilities).
        val FUNCTION = 1; val VARIABLE = 2; val ENUM_MEMBER = 10; val PARAMETER = 9
        val NAMESPACE = 11; val PROPERTY = 8; val TYPE = 3
        val DEFINITION = 2  // modifier bit
        val srcLines = state.content.lines()

        val toks = mutableListOf<SemToken>()
        fun emit(loc: com.mezze.language.parsedast.Loc, type: Int, mods: Int = 0) {
            // Single-line spans only (length encoding can't cross lines).
            if (loc.startLine != loc.endLine) return
            val len = loc.endCol - (loc.startCol - 1)
            if (len <= 0) return
            toks.add(SemToken(loc.startLine - 1, loc.startCol - 1, len, type, mods))
        }

        // A qualified var reference's loc spans the WHOLE `std::int::name`
        // (the parser keeps the qualifier inside the loc). Scan across that span
        // and color each `ident` that is immediately followed by `::` as a
        // Namespace segment (the final `name` keeps its function/variable color
        // from the main emit). Single-line only.
        fun emitQualifierNamespaces(loc: com.mezze.language.parsedast.Loc) {
            if (loc.startLine != loc.endLine) return
            val line = srcLines.getOrNull(loc.startLine - 1) ?: return
            val start = (loc.startCol - 1).coerceIn(0, line.length)
            val end = (loc.endCol).coerceIn(start, line.length)      // exclusive
            var i = start
            while (i < end) {
                if (!(line[i].isLetter() || line[i] == '_')) { i++; continue }
                var j = i
                while (j < end && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                // Namespace segment iff immediately followed by `::`.
                if (j + 1 < line.length && line[j] == ':' && line[j + 1] == ':') {
                    toks.add(SemToken(loc.startLine - 1, i, j - i, NAMESPACE, 0))
                    i = j + 2
                } else {
                    i = j  // final segment — leave to the main emit
                }
            }
        }

        // The loc of just the last `::`-separated segment of a (possibly)
        // qualified reference. For an unqualified name this is the loc itself.
        fun lastSegmentLoc(loc: com.mezze.language.parsedast.Loc): com.mezze.language.parsedast.Loc {
            if (loc.startLine != loc.endLine) return loc
            val line = srcLines.getOrNull(loc.startLine - 1) ?: return loc
            val start = (loc.startCol - 1).coerceIn(0, line.length)
            val end = (loc.endCol).coerceIn(start, line.length)
            val text = line.substring(start, end)
            val lastSep = text.lastIndexOf("::")
            if (lastSep < 0) return loc
            val nameStart = start + lastSep + 2
            return loc.copy(startCol = nameStart + 1)  // startCol is 1-based
        }

        for (decl in parsedModule.decls) {
            if (decl is ParsedLetDecl) {
                (decl.pattern as? ParsedVariablePattern)?.let { emit(it.loc, FUNCTION, DEFINITION) }
            }
        }

        val typedModule = (state.typeCheckResult ?: lastSuccessfulResult[state.uri])?.typedModule
        if (typedModule != null) {
            fun walk(e: com.mezze.language.typedast.TypedExpr) {
                when (e) {
                    is com.mezze.language.typedast.TypedVar -> {
                        val type = when (e.binding) {
                            is com.mezze.language.typedast.BindingRef.Param -> PARAMETER
                            is com.mezze.language.typedast.BindingRef.ByHash,
                            is com.mezze.language.typedast.BindingRef.Native -> FUNCTION
                            else -> VARIABLE
                        }
                        // For a qualified ref, color the module segments as
                        // Namespace and only the FINAL name segment as its kind,
                        // so the two token sets don't overlap.
                        emitQualifierNamespaces(e.loc)
                        emit(lastSegmentLoc(e.loc), type)
                    }
                    is com.mezze.language.typedast.TypedVariantConstruct -> emit(e.loc, ENUM_MEMBER)
                    else -> {}
                }
                for (child in typedChildren(e)) walk(child)
            }
            for (d in typedModule.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()) {
                walk(d.value)
            }
        }

        // Record-shaped field names → `property`, UNIFORMLY across every `{ … }`
        // form: record literals, record types, lambda params, record patterns,
        // and ability/effect/impl bodies. Each is a list of `name : field`; an
        // ability op or effect op IS a record field, so it colors identically to
        // a field in a record literal. The field NAME is at the entry loc's
        // start; we emit exactly `name.length` chars so the value keeps its own
        // color. Driven off the PARSED module — these are syntactic positions the
        // parser knows precisely (no type info needed).
        fun emitFieldName(loc: com.mezze.language.parsedast.Loc, name: String) {
            if (loc.startLine != loc.endLine) return
            if (name.isEmpty()) return
            toks.add(SemToken(loc.startLine - 1, loc.startCol - 1, name.length, PROPERTY, 0))
        }

        // A concrete type name (`Int`, `Promise`, `Str`) → `type`. A leading
        // `'` marks a type VARIABLE (`'a`) — left to tree-sitter's
        // @type.parameter (syntactically identifiable), not colored here, so we
        // never miscolor a skolem as a concrete type.
        fun emitTypeName(loc: com.mezze.language.parsedast.Loc, name: String) {
            if (loc.startLine != loc.endLine || name.isEmpty() || name.startsWith("'")) return
            toks.add(SemToken(loc.startLine - 1, loc.startCol - 1, name.length, TYPE, 0))
        }

        fun walkTypeFields(t: com.mezze.language.parsedast.ParsedTypeAnnotation) {
            when (t) {
                is com.mezze.language.parsedast.ParsedNamedType -> emitTypeName(t.loc, t.name)
                is com.mezze.language.parsedast.ParsedRecordType ->
                    t.entries.forEach { emitFieldName(it.loc, it.name); walkTypeFields(it.type) }
                is com.mezze.language.parsedast.ParsedFunctionType -> {
                    walkTypeFields(t.paramType); walkTypeFields(t.returnType)
                }
                is com.mezze.language.parsedast.ParsedTypeApp -> {
                    // The constructor (`Promise` in `Promise 'a`) is the type
                    // name; its loc starts at the app loc's start.
                    emitTypeName(t.loc, t.constructor)
                    t.args.forEach { walkTypeFields(it) }
                }
                is com.mezze.language.parsedast.ParsedSeqType -> walkTypeFields(t.elementType)
                else -> {}
            }
        }

        fun walkPatternFields(p: com.mezze.language.parsedast.ParsedPattern) {
            when (p) {
                is com.mezze.language.parsedast.ParsedRecordPattern ->
                    p.entries.forEach { emitFieldName(it.loc, it.name); it.pattern?.let(::walkPatternFields) }
                is com.mezze.language.parsedast.ParsedSeqPattern -> p.elements.forEach { walkPatternFields(it) }
                else -> {}
            }
        }

        fun walkExprFields(e: com.mezze.language.parsedast.ParsedExpr) {
            when (e) {
                is com.mezze.language.parsedast.ParsedRecordLit ->
                    e.entries.forEach { entry ->
                        when (entry) {
                            is com.mezze.language.parsedast.ParsedRecordEntry.Field -> {
                                emitFieldName(entry.loc, entry.name)
                                entry.value?.let(::walkExprFields)
                            }
                            is com.mezze.language.parsedast.ParsedRecordEntry.Spread -> walkExprFields(entry.expr)
                        }
                    }
                is com.mezze.language.parsedast.ParsedLambda -> {
                    // Lambda params ARE a record of params → their names are fields.
                    (e.params as? com.mezze.language.parsedast.ParsedLambdaParams.Typed)?.fields
                        ?.forEach { emitFieldName(it.loc, it.name); walkTypeFields(it.type) }
                    walkExprFields(e.body)
                }
                is com.mezze.language.parsedast.ParsedMatch ->
                    // A match arm's pattern is record-shaped too: `{ x, y } ->`.
                    e.cases.forEach { c -> c.patterns.forEach { walkPatternFields(it) } }
                is com.mezze.language.parsedast.ParsedDo ->
                    // `let { x, y } = e` inside a do-block binds a record pattern.
                    e.statements.forEach { st ->
                        if (st is com.mezze.language.parsedast.ParsedStatement.Let) walkPatternFields(st.pattern)
                    }
                else -> {}
            }
            for (child in e.children()) walkExprFields(child)
        }

        for (decl in parsedModule.decls) {
            when (decl) {
                is ParsedLetDecl -> {
                    walkPatternFields(decl.pattern)  // `let { x, y } = …`
                    decl.typeAnnotation?.let { walkTypeFields(it) }
                    walkExprFields(decl.value)
                }
                is com.mezze.language.parsedast.ParsedAbilityDecl ->
                    decl.operations.forEach { op ->
                        emitFieldName(op.loc, op.name)
                        walkTypeFields(op.paramType); walkTypeFields(op.returnType)
                    }
                is com.mezze.language.parsedast.ParsedEffectDecl ->
                    decl.operations.forEach { op ->
                        emitFieldName(op.loc, op.name)
                        walkTypeFields(op.paramType); walkTypeFields(op.returnType)
                    }
                else -> {}
            }
        }

        return toks
    }

    /** Sort + delta-encode absolute tokens into the flat 5-int LSP stream, deduping exact overlaps. */
    private fun deltaEncodeSemanticTokens(toks: List<SemToken>): List<Int> {
        val sorted = toks.sortedWith(compareBy({ it.line }, { it.col }))
        val data = mutableListOf<Int>()
        var prevLine = 0; var prevCol = 0; var lastLine = -1; var lastCol = -1
        for (t in sorted) {
            if (t.line == lastLine && t.col == lastCol) continue
            val deltaLine = t.line - prevLine
            val deltaCol = if (deltaLine == 0) t.col - prevCol else t.col
            data.add(deltaLine); data.add(deltaCol); data.add(t.len); data.add(t.type); data.add(t.mods)
            prevLine = t.line; prevCol = t.col; lastLine = t.line; lastCol = t.col
        }
        return data
    }

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> {
        return CompletableFuture.supplyAsync {
            val state = documents[params.textDocument.uri] ?: return@supplyAsync SemanticTokens(emptyList())
            SemanticTokens(deltaEncodeSemanticTokens(collectSemanticTokens(state)))
        }
    }

    /**
     * #122: viewport-only semantic tokens. Same tokens as the full request,
     * filtered to those starting within the requested range — the editor asks
     * for this to avoid re-tokenizing the whole file on every scroll.
     */
    override fun semanticTokensRange(params: SemanticTokensRangeParams): CompletableFuture<SemanticTokens> {
        return CompletableFuture.supplyAsync {
            val state = documents[params.textDocument.uri] ?: return@supplyAsync SemanticTokens(emptyList())
            val r = params.range
            val inRange = collectSemanticTokens(state).filter { t ->
                t.line >= r.start.line && t.line <= r.end.line
            }
            SemanticTokens(deltaEncodeSemanticTokens(inRange))
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
