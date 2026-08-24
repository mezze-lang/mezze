package com.mezze.language.lsp

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
        val loc = error.loc
        // #98: append span-less advice strings (error.hints) to the message.
        val message = if (error.hints.isEmpty()) error.message
            else error.message + error.hints.joinToString("") { "\n  hint: $it" }
        val diag = createDiagnostic(
            message = message,
            severity = DiagnosticSeverity.Error,
            line = (loc?.startLine ?: 1) - 1,
            column = (loc?.startCol ?: 1) - 1,
            endLine = loc?.endLine?.minus(1),
            endColumn = loc?.endCol,
            // Prefer the stable ErrorCode (T0201, V0601, etc.) — the same
            // value `mez --explain` understands, so editors can wire the
            // diagnostic's code to `mez --explain $CODE` or a docs URL.
            // Falls back to the legacy ErrorType name when the error site
            // hasn't been migrated yet (UNCATEGORIZED). GENERAL still
            // suppressed because `mezze(GENERAL)` is pure UI noise.
            code = diagnosticCode(error.code, error.errorType)
        )
        // Diagnostic.data is a free-form payload lsp4j ships to the client
        // verbatim. We use it as a channel for structured code-action info —
        // both the legacy `metadata` map (used by some existing fixes) and
        // the new typed `fixHints` produced by error sites that want to
        // offer one-click fixes. Encoding fixHints as plain maps avoids
        // pulling kotlinx-serialization into the LSP wire format.
        val data = mutableMapOf<String, Any?>()
        if (error.metadata.isNotEmpty()) data.putAll(error.metadata)
        if (error.fixHints.isNotEmpty()) data["fixHints"] = encodeFixHints(error.fixHints)
        if (data.isNotEmpty()) diag.data = data
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
        val loc = error.loc
        // #98: the typechecker computes advice strings (e.g. "Did you mean
        // `x`?", "import it: `use …`") in error.hints. They carry no span, so
        // RelatedInformation doesn't fit — append them to the message where the
        // user actually reads them. fixHints (with edits) still drive the
        // codeAction quick-fixes separately.
        val baseMessage = error.message ?: "Type error"
        val message = if (error.hints.isEmpty()) baseMessage
            else baseMessage + error.hints.joinToString("") { "\n  hint: $it" }
        val diag = createDiagnostic(
            message = message,
            severity = DiagnosticSeverity.Error,
            line = (loc?.startLine ?: 1) - 1,
            column = (loc?.startCol ?: 1) - 1,
            endLine = loc?.endLine?.minus(1),
            endColumn = loc?.endCol,
            code = diagnosticCode(error.code, error.errorType)
        )
        // Carry metadata + fixHints to the client so codeAction can offer
        // one-click QuickFixes. See createDiagnosticFromRecoveryError for
        // the matching encode path.
        val data = mutableMapOf<String, Any?>()
        if (error.metadata.isNotEmpty()) data.putAll(error.metadata)
        if (error.fixHints.isNotEmpty()) data["fixHints"] = encodeFixHints(error.fixHints)
        if (data.isNotEmpty()) diag.data = data
        return diag
    }

    /**
     * Choose the most useful identifier for an LSP [Diagnostic.code]:
     * prefer the stable ErrorCode display value (e.g. `T0201`) when one
     * is assigned, fall back to the legacy ErrorType name, suppress the
     * meaningless `GENERAL` bucket entirely.
     */
    private fun diagnosticCode(
        code: com.mezze.language.parsedast.diagnostics.ErrorCode,
        errorType: ErrorType,
    ): String? {
        val display = code.display()
        if (display != "E0000") return display  // E0000 == UNCATEGORIZED sentinel
        return errorType.name.takeUnless { it == ErrorType.GENERAL.name }
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
     * Find hover information at a position.
     * Works even when type checking fails - uses AST for basic info.
     * Returns markdown with syntax highlighting.
     */
    private fun findHoverInfo(state: DocumentState, position: Position): String? {
        // Get the word at the position
        val lines = state.content.lines()
        if (position.line >= lines.size) return null

        val line = lines[position.line]

        // Don't hover inside a comment. `## # Heading` etc. is prose, not code —
        // extracting a "word" there and resolving it as an identifier produced
        // spurious type popups (e.g. `{} <Log>`) over comment text.
        if (isInsideLineComment(line, position.character)) return null

        val word = extractWordAt(line, position.character)
        if (word.isNullOrEmpty()) return null

        val result = state.typeCheckResult

        // Impl hover: if the cursor is on the HEADER of an impl decl (the `impl`
        // keyword, the ability/effect name, or the target type) — as opposed to
        // deep in an op body — show which ability/effect it implements, its ops,
        // and a link to the decl. Header = the line the impl's loc starts on.
        run {
            val pm = state.parsedModule ?: return@run
            val lineNo = position.line + 1
            // Header detection is robust to comment-shifted decl locs: the cursor
            // line must be inside the decl's range AND its trimmed text starts
            // with `impl` (the header line), not a `name = …` op-body line.
            val onImplHeaderLine = line.trimStart().startsWith("impl")
            for (decl in pm.decls) {
                if (lineNo < decl.loc.startLine || lineNo > decl.loc.endLine) continue
                when (decl) {
                    is com.mezze.language.parsedast.ParsedImplAbilityDecl -> {
                        if (onImplHeaderLine &&
                            (word == "impl" || word == decl.abilityName ||
                                decl.targetTypes.any { formatTypeAnnotation(it).substringBefore(' ') == word })
                        ) {
                            val abilityDecl = pm.decls.filterIsInstance<ParsedAbilityDecl>()
                                .find { it.name == decl.abilityName }
                                ?: result?.importedAbilities?.get(decl.abilityName)
                            val fromModule = result?.resolvedImports
                                ?.find { it.localName == decl.abilityName && it.kind == ImportKind.ABILITY }
                                ?.modulePath
                            return formatAbilityImplHover(decl, abilityDecl, fromModule)
                        }
                    }
                    is ParsedPerformerDecl -> {
                        if (onImplHeaderLine &&
                            (word == "impl" || word == decl.effect.localName || word == decl.performerName)
                        ) {
                            val effectDecl = pm.decls.filterIsInstance<ParsedEffectDecl>()
                                .find { it.name == decl.effect.localName }
                                ?: result?.importedEffects?.get(decl.effect.localName)
                            val fromModule = result?.resolvedImports
                                ?.find { it.localName == decl.effect.localName && it.kind == ImportKind.EFFECT }
                                ?.modulePath
                            return formatEffectImplHover(decl, effectDecl, fromModule)
                        }
                    }
                    else -> {}
                }
            }
        }

        // Perform-handler hover: the cursor is on the performer name in
        // `perform Console in do …`. Without this, the typed-AST lookup below
        // resolves `Console` as a nullary constructor and shows a bare `{}`
        // type. Instead, show which effect(s) this performer handles and a
        // link to the impl. Gated on the word being immediately preceded by
        // `perform ` on the line (a comma-separated handler list also counts,
        // e.g. `perform A, B in …`).
        run {
            val beforeWord = line.substring(0, position.character.coerceIn(0, line.length))
            val atHandlerPos = Regex("\\bperform\\b[^)]*$").containsMatchIn(beforeWord) &&
                !Regex("\\bin\\b").containsMatchIn(beforeWord.substringAfterLast("perform"))
            if (!atHandlerPos) return@run
            val hover = performerHover(state, result, word) ?: return@run
            return hover
        }

        // Qualified effect-op call hover: the cursor is on `Log` or `log` in
        // `Log.log { … }`. The typed-AST lookup below resolves the whole call to
        // its `{}` return type, hiding what the reader wants — the effect and
        // the op's signature. Detect the `<Effect>.<op>` shape textually and:
        //   • on the effect qualifier → the effect decl (name + ops + link),
        //   • on the op name          → that op's signature `op: P -> R <E>`.
        run {
            // Find the word's actual span around the cursor (which may sit in
            // the middle of the word). Then `beforeWord`/`after` are the text
            // strictly before/after the whole word on this line.
            val cur = position.character.coerceIn(0, line.length)
            var ws = cur; while (ws > 0 && (line[ws - 1].isLetterOrDigit() || line[ws - 1] == '_')) ws--
            var we = cur; while (we < line.length && (line[we].isLetterOrDigit() || line[we] == '_')) we++
            val beforeWord = line.substring(0, ws)
            val after = line.substring(we)

            // Case A — cursor on the op name: preceded by `<Effect>.` on the line.
            val qualMatch = Regex("([A-Z][A-Za-z0-9_]*)\\.$").find(beforeWord)
            if (qualMatch != null) {
                val effectName = qualMatch.groupValues[1]
                val effectDecl = effectDeclByName(state, result, effectName)
                val op = effectDecl?.operations?.find { it.name == word }
                if (effectDecl != null && op != null) {
                    val row = if (op.effects.isNotEmpty()) " <${op.effects.joinToString(", ")}>" else ""
                    val ty = "${formatTypeAnnotation(op.paramType)} -> ${formatTypeAnnotation(op.returnType)}$row"
                    val link = resolveExistingFileUri(effectDecl.loc.file)?.let { u ->
                        "\n\nop of effect [`$effectName`]($u#L${effectDecl.loc.startLine},${effectDecl.loc.startCol})"
                    } ?: "\n\nop of effect `$effectName`"
                    return appendDoc("```mezze\n$word: $ty\n```" + link, op.doc, null)
                }
            }

            // Case B — cursor on the qualifier: `word` is a known effect OR
            // ability, immediately followed by `.<op>` on the line → its decl.
            if (Regex("^\\.[a-z_]").containsMatchIn(after)) {
                val effectDecl = effectDeclByName(state, result, word)
                if (effectDecl != null) {
                    val fromModule = result?.resolvedImports
                        ?.find { it.localName == word && it.kind == ImportKind.EFFECT }?.modulePath
                    return formatEffectHover(effectDecl, fromModule)
                }
                val abilityDecl = abilityDeclByName(state, result, word)
                if (abilityDecl != null) {
                    val fromModule = result?.resolvedImports
                        ?.find { it.localName == word && it.kind == ImportKind.ABILITY }?.modulePath
                    return formatAbilityHover(abilityDecl, fromModule)
                }
            }
        }

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
                            val abilityHover = result?.let { renderAbilityMethodHover(expr.name, it, state, expr.type) }
                            val hover = abilityHover ?: codeBlock("${expr.name} : $typeStr")
                            val (doc, meta) = resolveDoc(state, result, expr.name)
                            appendDoc(hover, doc, meta)
                        }
                        is com.mezze.language.typedast.TypedFieldAccess -> {
                            // A method-in-a-chain accessed as `.eq` keeps a
                            // polymorphic solved type (dispatch not yet a call),
                            // so pass the RECEIVER type too — renderAbilityMethodHover
                            // specializes the generic op by the receiver when the
                            // solved method type is still a var.
                            val abilityHover = result?.let {
                                renderAbilityMethodHover(expr.field, it, state, expr.type, expr.record.type)
                            }
                            abilityHover ?: codeBlock(".${expr.field} : $typeStr")
                        }
                        is com.mezze.language.typedast.TypedCall -> codeBlock("$typeStr")
                        else -> codeBlock(typeStr)
                    }
                }
                is TypedAstLookup.LookupResult.Binding -> {
                    // Effect / ability / type decl names: findAtPosition returns
                    // a Binding with a SYNTHETIC fresh VarType (there's no real
                    // value-level type for a decl name), which would render the
                    // meaningless `Log : 'a`. Route to the proper structural
                    // hover instead (`effect Log where { … }`).
                    val effectDeclHit = state.parsedModule?.decls
                        ?.filterIsInstance<ParsedEffectDecl>()?.find { it.name == lookup.name }
                        ?: result?.importedEffects?.get(lookup.name)
                    if (effectDeclHit != null) {
                        val fromModule = result?.resolvedImports
                            ?.find { it.localName == lookup.name && it.kind == ImportKind.EFFECT }?.modulePath
                        return formatEffectHover(effectDeclHit, fromModule)
                    }
                    val abilityDeclHit = state.parsedModule?.decls
                        ?.filterIsInstance<ParsedAbilityDecl>()?.find { it.name == lookup.name }
                        ?: result?.importedAbilities?.get(lookup.name)
                    if (abilityDeclHit != null) {
                        val fromModule = result?.resolvedImports
                            ?.find { it.localName == lookup.name && it.kind == ImportKind.ABILITY }?.modulePath
                        return formatAbilityHover(abilityDeclHit, fromModule)
                    }
                    val typeStr = formatType(lookup.type)
                    val (doc, meta) = resolveDoc(state, result, lookup.name)
                    // A synthetic VarType named after a type decl (e.g. `Log`)
                    // renders as a bare type var — show `type Log` instead.
                    if (lookup.type is MezzeType.VarType &&
                        state.parsedModule?.decls?.filterIsInstance<ParsedTypeDecl>()?.any { it.name == lookup.name } == true) {
                        return appendDoc(codeBlock("type ${lookup.name}"), doc, meta)
                    }
                    return appendDoc(codeBlock("${lookup.name} : $typeStr"), doc, meta)
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
                // Prefer the user's explicit annotation when present: it
                // reflects intent and stays stable when the body has a type
                // error (in which case the inferred scheme is the wrong type).
                val annotated = state.parsedModule?.decls
                    ?.filterIsInstance<ParsedLetDecl>()
                    ?.firstOrNull { it.isSimpleBinding && it.variableName == word }
                    ?.typeAnnotation
                    ?.let {
                        try { com.mezze.language.typechecker.util.TypeAnnotationConverter.convert(it) }
                        catch (_: Exception) { null }
                    }
                val bindingType = annotated ?: typedBinding.scheme.type
                if (bindingType !is MezzeType.VarType) {
                    val doc = findDocForDecl(state.parsedModule, word)
                    val meta = findMetadataForDecl(state.parsedModule, word)
                    // Surface the scheme's ability constraints (`with Eq 'a`) —
                    // they're part of the signature but the bare type doesn't
                    // show them. Only when we didn't fall back to the annotation
                    // (which already carries any `with` clause verbatim).
                    val constraints = if (annotated == null)
                        renderSchemeConstraints(typedBinding.scheme, result.typedModule)
                    else ""
                    return appendDoc(codeBlock("$word : ${formatType(bindingType)}$constraints"), doc, meta)
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
                val (doc, meta) = resolveDoc(state, result, word)
                return appendDoc(codeBlock("$kindPrefix$word : $typeStr") + fromModule, doc, meta)
            }
        }

        // Fallback: use parsedModule for basic info (even when type check failed)
        val parsedModule = state.parsedModule

        // 3. Look up type declarations from parsed module
        val typeDecl = parsedModule?.decls?.filterIsInstance<ParsedTypeDecl>()?.find { it.name == word }
        if (typeDecl != null) {
            // For transparent aliases show the aliased type: `type MyInt = Int`
            // is more informative than just `type MyInt` at hover.
            val header = when (val def = typeDecl.definition) {
                is com.mezze.language.parsedast.ParsedTypeAliasDef ->
                    "type $word = ${formatTypeAnnotation(def.aliasedType)}"
                is com.mezze.language.parsedast.ParsedNewtypeDef ->
                    "newtype $word = ${formatTypeAnnotation(def.wrappedType)}"
                else -> "type $word"
            }
            return appendDoc(codeBlock(header), typeDecl.docComment, typeDecl.metadata)
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
            return appendDoc(codeBlock("let $word = ..."), letDecl.docComment, letDecl.metadata)
        }

        return null
    }

    /**
     * Look up the doc comment for a named declaration from the current file's ParsedModule.
     * Returns the doc string or null.
     */
    private fun findDocForDecl(parsedModule: ParsedModule?, name: String): String? {
        if (parsedModule == null) return null
        for (decl in parsedModule.decls) {
            val declName = when (decl) {
                is ParsedLetDecl -> if (decl.isSimpleBinding) decl.variableName else null
                is ParsedTypeDecl -> decl.name
                is ParsedAbilityDecl -> decl.name
                is ParsedEffectDecl -> decl.name
                is ParsedPerformerDecl -> decl.performerName
                else -> null
            }
            if (declName == name) return decl.docComment
        }
        return null
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
     * Resolve doc + metadata for `name` at hover time, walking from
     * narrowest to widest scope:
     *
     *   1. The user's own parsed module (local decls).
     *   2. The parsed module the user imported `name` from — looked up in
     *      ModuleTypeChecker's in-memory cache. This honors the *specific*
     *      import source, so same-named symbols across modules (`min` in
     *      std::int vs std::collections::seq) resolve to the right doc,
     *      and modules StdlibPrecompiler never indexed (effects, codec,
     *      etc.) work without a build-time re-walk.
     *   3. The on-disk store, for cold-start lookups when nothing has
     *      been parsed yet.
     */
    private fun resolveDoc(
        state: DocumentState,
        result: TypeCheckResult?,
        name: String
    ): Pair<String?, ParsedMetadata?> {
        // (1) user's file
        findDocForDecl(state.parsedModule, name)?.let { doc ->
            return doc to findMetadataForDecl(state.parsedModule, name)
        }
        // (2) imported module's parsed AST
        val importPath = result?.resolvedImports
            ?.find { it.localName == name }
            ?.modulePath
            ?.takeIf { it.isNotEmpty() }
        if (importPath != null) {
            val parsed = com.mezze.language.typechecker.module.ModuleTypeChecker
                .getAllCachedModules()[importPath]?.parsedModule
            if (parsed != null) {
                findDocForDecl(parsed, name)?.let { doc ->
                    return doc to findMetadataForDecl(parsed, name)
                }
            }
        }
        // (3) on-disk store
        return findDocFromStore(name) to null
    }

    /**
     * Append a doc comment and deprecation warning to hover markdown.
     */
    private fun appendDoc(hover: String, doc: String?, metadata: ParsedMetadata? = null): String {
        val parts = mutableListOf(hover)

        // Deprecation warning
        if (metadata?.isDeprecated == true) {
            val deprecation = buildString {
                append("**Deprecated**")
                metadata.since?.let { append(" since $it") }
                metadata.note?.let { append(" — $it") }
            }
            parts.add(deprecation)
        }

        // Doc comment — normalized so multi-block markdown (headings, lists,
        // fenced code, tables) renders correctly in the client's renderer.
        if (!doc.isNullOrBlank()) {
            parts.add(renderDocMarkdown(doc))
        }

        return parts.joinToString("\n\n---\n\n")
    }

    /**
     * Normalize a `##`-sourced doc comment (stored as `\n`-joined lines) into
     * well-formed markdown. The parser joins doc lines with a single `\n`, but
     * markdown needs a blank line between BLOCK elements — otherwise a heading
     * runs into the following paragraph, a list glues to the text above it, and
     * a fenced code block never opens. We insert `\n\n` at block boundaries
     * while keeping consecutive lines of the SAME block (list items, code-fence
     * interior, table rows, paragraph soft-wraps) together.
     *
     * Client-agnostic: emits standard CommonMark/GFM text, so it renders the
     * same in VS Code (markdown-it), Zed, and any other LSP client.
     */
    private fun renderDocMarkdown(doc: String): String {
        val lines = doc.split("\n")
        val out = StringBuilder()
        var inFence = false
        var prevKind: DocLineKind? = null

        for ((i, raw) in lines.withIndex()) {
            val line = raw
            val trimmed = line.trimStart()
            val kind = classifyDocLine(trimmed, inFence)

            // A fence toggles code-block mode; the toggle line itself is FENCE.
            if (trimmed.startsWith("```")) inFence = !inFence

            if (i > 0) {
                // Blank line between two different block kinds, or when either
                // side is a heading/fence/blockquote/table/hr boundary. Runs of
                // the same block kind (LIST↔LIST, TEXT↔TEXT inside a paragraph,
                // CODE↔CODE inside a fence) stay on adjacent lines.
                val needBlank = when {
                    inFence || prevKind == DocLineKind.CODE -> false        // inside a fence: keep verbatim
                    kind == DocLineKind.BLANK || prevKind == DocLineKind.BLANK -> false // author already spaced it
                    kind == prevKind && kind == DocLineKind.LIST -> false   // adjacent list items
                    kind == prevKind && kind == DocLineKind.TABLE -> false  // adjacent table rows
                    kind == prevKind && kind == DocLineKind.TEXT -> false   // paragraph soft-wrap
                    else -> true
                }
                out.append(if (needBlank) "\n\n" else "\n")
            }
            out.append(line)
            prevKind = kind
        }
        return out.toString()
    }

    private enum class DocLineKind { HEADING, LIST, FENCE, CODE, QUOTE, TABLE, HR, BLANK, TEXT }

    private fun classifyDocLine(trimmed: String, inFence: Boolean): DocLineKind = when {
        inFence -> DocLineKind.CODE
        trimmed.isEmpty() -> DocLineKind.BLANK
        trimmed.startsWith("```") -> DocLineKind.FENCE
        trimmed.startsWith("#") -> DocLineKind.HEADING
        trimmed.matches(Regex("""^([-*+]\s|\d+[.)]\s).*""")) -> DocLineKind.LIST
        trimmed.startsWith(">") -> DocLineKind.QUOTE
        trimmed.startsWith("|") -> DocLineKind.TABLE
        trimmed.matches(Regex("""^(-{3,}|\*{3,}|_{3,})$""")) -> DocLineKind.HR
        else -> DocLineKind.TEXT
    }

    /**
     * Find the metadata for a named declaration from the current file's ParsedModule.
     */
    private fun findMetadataForDecl(parsedModule: ParsedModule?, name: String): ParsedMetadata? {
        if (parsedModule == null) return null
        for (decl in parsedModule.decls) {
            val declName = when (decl) {
                is ParsedLetDecl -> if (decl.isSimpleBinding) decl.variableName else null
                is ParsedTypeDecl -> decl.name
                is ParsedAbilityDecl -> decl.name
                is ParsedEffectDecl -> decl.name
                is ParsedPerformerDecl -> decl.performerName
                else -> null
            }
            if (declName == name) return decl.metadata
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
    /** True if [type] still contains any unresolved type variable. */
    private fun typeHasVar(type: MezzeType): Boolean =
        com.mezze.language.ast.collectTypeVarsInOrder(type).isNotEmpty()

    /**
     * Replace every VarType named [varName] in [type] with [replacement],
     * recursively. Used to specialize an ability op's generic annotation to a
     * concrete receiver — e.g. `Eq for 'a`'s `eq : { it: 'a, other: 'a } -> Bool`
     * becomes `{ it: Int, other: Int } -> Bool` when the receiver is `Int`.
     */
    private fun replaceNamedVar(type: MezzeType, varName: String, replacement: MezzeType): MezzeType {
        val bare = varName.trimStart('\'')
        if (type is MezzeType.VarType && type.variable.name.trimStart('\'') == bare) return replacement
        return type.transformChildren { replaceNamedVar(it, varName, replacement) }
    }

    /**
     * Specialize an ability op's `param -> return` signature to a concrete
     * receiver [receiver] by substituting the ability's receiver type param.
     * Returns null if the annotation can't be converted (falls back to generic).
     */
    private fun specializeAbilityOp(
        op: ParsedAbilityOp,
        receiverTypeParam: String?,
        receiver: MezzeType,
        aliases: Map<String, TypeAliasInfo>,
    ): String? {
        if (receiverTypeParam == null) return null
        return try {
            val conv = com.mezze.language.typechecker.util.TypeAnnotationConverter
            val param = replaceNamedVar(conv.convert(op.paramType), receiverTypeParam, receiver)
            val ret = replaceNamedVar(conv.convert(op.returnType), receiverTypeParam, receiver)
            "${prettyPrintTypeWithAliases(param, aliases)} -> ${prettyPrintTypeWithAliases(ret, aliases)}"
        } catch (_: Exception) { null }
    }

    private fun renderAbilityMethodHover(
        methodName: String,
        result: TypeCheckResult,
        state: DocumentState,
        solvedType: MezzeType? = null,
        receiverType: MezzeType? = null,
    ): String? {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"

        // At a concrete call site the method's solved type is specialized to the
        // receiver (`to_str : { it: Int } -> Str`), which is far more useful than
        // the ability decl's generic `{ it: 'a } -> Str`. Prefer it whenever the
        // solved type is a fully-resolved FunctionType (no leftover tyvars) — a
        // still-polymorphic type means dispatch wasn't pinned yet, so fall back
        // to the generic decl signature below.
        val specialized = (solvedType?.let { if (it is MezzeType.SuspendType) it.valueType else it })
            ?.takeIf { it is MezzeType.FunctionType && !typeHasVar(it) }
            ?.let { prettyPrintTypeWithAliases(it, state.typeAliases) }

        // When the method's OWN solved type is still polymorphic (e.g. `.eq` in a
        // chain, accessed before the call pins dispatch), we can still specialize
        // via the RECEIVER if it's concrete — substitute the ability's receiver
        // type param with the receiver in the generic op annotation.
        val concreteReceiver = (receiverType?.let { if (it is MezzeType.SuspendType) it.valueType else it })
            ?.takeIf { it !is MezzeType.VarType && !typeHasVar(it) }

        // 1. User-defined / file-level abilities
        val localAbilities = state.parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>() ?: emptyList()
        val candidates = mutableListOf<Pair<ParsedAbilityDecl, ParsedAbilityOp>>()
        for (ability in result.importedAbilities.values + localAbilities) {
            ability.operations.firstOrNull { it.name == methodName }?.let {
                candidates.add(ability to it)
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
                    for (declRow in store.getDeclsByKind(com.mezze.language.store.DeclKind.ABILITY)) {
                        val blob = store.getBlob(declRow.hash) ?: continue
                        val bytes = blob.typed ?: continue
                        val typedAbility = try {
                            com.mezze.language.store.StoreSerialization.deserializeTypedAbilityDecl(bytes)
                        } catch (_: Throwable) { continue }
                        val typedOp = typedAbility.operations.firstOrNull { it.name == methodName }
                            ?: continue
                        // Prefer the specialized call-site type; else specialize
                        // the generic op type by the concrete receiver; else the
                        // ability decl's generic op signature verbatim.
                        val rv = typedAbility.receiverTypeParam
                        val bySig = if (specialized != null) specialized
                            else if (concreteReceiver != null && rv != null) {
                                val p = replaceNamedVar(typedOp.paramType, rv, concreteReceiver)
                                val r = replaceNamedVar(typedOp.returnType, rv, concreteReceiver)
                                "${prettyPrintTypeWithAliases(p, state.typeAliases)} -> ${prettyPrintTypeWithAliases(r, state.typeAliases)}"
                            } else {
                                "${prettyPrintTypeWithAliases(typedOp.paramType, state.typeAliases)} -> " +
                                    prettyPrintTypeWithAliases(typedOp.returnType, state.typeAliases)
                            }
                        return codeBlock("${typedAbility.name}.${typedOp.name} : $bySig")
                    }
                } catch (_: Throwable) { /* store may not be open */ }
            }
        }
        if (candidates.isEmpty()) return null

        // Render the first candidate. If multiple abilities share a method
        // name (rare — e.g. NumAdd.add vs Seq.add), the receiver type at
        // dispatch picks one; we don't have that info here, so just show
        // the first match. Listing all could be a follow-up.
        val (ability, op) = candidates.first()
        val bySig = specialized
            ?: (concreteReceiver?.let { specializeAbilityOp(op, ability.receiverTypeParam, it, state.typeAliases) })
            ?: "${formatTypeAnnotation(op.paramType)} -> ${formatTypeAnnotation(op.returnType)}"
        val sig = "${ability.name}.${op.name} : $bySig"
        val doc = op.doc?.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: ""
        return codeBlock(sig) + doc
    }

    /**
     * Format hover info for an ability declaration, showing all operations.
     */
    private fun formatAbilityHover(decl: ParsedAbilityDecl, fromModule: String?): String {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"

        // Normalize a type-param token to exactly one leading apostrophe
        // (parser may store it with or without the `'`).
        fun tv(name: String) = "'" + name.trimStart('\'')
        // `typeParams` already includes the receiver (parser appends it), so
        // render `ability Name <extra generics> for 'receiver` without
        // repeating the receiver in both places.
        val receiver = decl.receiverTypeParam
        val generics = decl.typeParams.filter { it.trimStart('\'') != receiver?.trimStart('\'') }
        val genericsStr = if (generics.isNotEmpty()) " " + generics.joinToString(" ") { tv(it) } else ""
        val forClause = receiver?.let { " for ${tv(it)}" } ?: ""
        val header = "ability ${decl.name}$genericsStr$forClause"

        val ops = decl.operations.joinToString("\n") { op ->
            "  ${op.name}: ${formatTypeAnnotation(op.paramType)} -> ${formatTypeAnnotation(op.returnType)}"
        }

        val body = if (ops.isNotEmpty()) " where {\n$ops\n}" else ""
        val moduleInfo = if (!fromModule.isNullOrEmpty()) "\n\n*from* `$fromModule`" else ""

        return appendDoc(codeBlock("$header$body") + moduleInfo, decl.docComment, decl.metadata)
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

        return appendDoc(codeBlock("$header$body") + moduleInfo, decl.docComment, decl.metadata)
    }

    /**
     * Hover for an ability impl (`impl Eq for Int where { … }`). Shows which
     * ability it implements + the target type, each op with its type (the impl's
     * own annotation when present, else the ability decl's op signature so the
     * method's shape is always visible), and a link to where the ability is
     * defined. `abilityDecl` is the resolved `ability` decl (may be null if the
     * ability is imported / unresolved — then op types fall back to the impl's).
     */
    private fun formatAbilityImplHover(
        decl: com.mezze.language.parsedast.ParsedImplAbilityDecl,
        abilityDecl: ParsedAbilityDecl?,
        fromModule: String?,
    ): String {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"
        val abilityName = decl.abilityName ?: "<ability>"
        val targets = decl.targetTypes.joinToString(", ") { formatTypeAnnotation(it) }
        val header = "impl $abilityName" + if (targets.isNotEmpty()) " for $targets" else ""

        // Prefer the ability decl's op signatures (always typed); fall back to the
        // impl op's own annotation.
        val abilityOps = abilityDecl?.operations?.associateBy { it.name } ?: emptyMap()
        val ops = decl.operations.joinToString("\n") { op ->
            val sig = abilityOps[op.name]
            val param = op.paramType ?: sig?.paramType
            val ret = op.returnType ?: sig?.returnType
            val ty = if (param != null && ret != null)
                "${formatTypeAnnotation(param)} -> ${formatTypeAnnotation(ret)}" else "…"
            "  ${op.name}: $ty"
        }
        val body = if (ops.isNotEmpty()) " where {\n$ops\n}" else ""
        val link = fromModule?.takeIf { it.isNotEmpty() }
            ?.let { "\n\nability `$abilityName` defined in `$it`" }
            ?: "\n\nimplements ability `$abilityName`"
        return appendDoc(codeBlock("$header$body") + link, decl.docComment, decl.metadata)
    }

    /**
     * Hover for an effect impl / performer (`impl State for Counter where { … }`).
     * Same shape as the ability-impl hover but for effects.
     */
    private fun formatEffectImplHover(
        decl: ParsedPerformerDecl,
        effectDecl: ParsedEffectDecl?,
        fromModule: String?,
    ): String {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"
        val effectName = decl.effect.localName
        val header = "impl $effectName for ${decl.performerName}"

        val effectOps = effectDecl?.operations?.associateBy { it.name } ?: emptyMap()
        val ops = decl.operations.joinToString("\n") { op ->
            val sig = effectOps[op.name]
            val param = op.paramType ?: sig?.paramType
            val ret = op.returnType ?: sig?.returnType
            val ty = if (param != null && ret != null)
                "${formatTypeAnnotation(param)} -> ${formatTypeAnnotation(ret)}" else "…"
            "  ${op.name}: $ty"
        }
        val body = if (ops.isNotEmpty()) " where {\n$ops\n}" else ""
        val link = fromModule?.takeIf { it.isNotEmpty() }
            ?.let { "\n\neffect `$effectName` defined in `$it`" }
            ?: "\n\nimplements effect `$effectName`"
        return appendDoc(codeBlock("$header$body") + link, decl.docComment, decl.metadata)
    }

    /**
     * Hover text for the target type used at a `perform X in …` site (`X` is
     * the type an `impl <Effect> for X` handles — there is no "performer"
     * keyword in Mezze; it's just an impl target). Renders each impl as real
     * Mezze — `impl <Effect> for X where { op: Param -> Ret }` — plus a
     * clickable link to the effect decl. Returns null if `name` isn't the
     * target of any known effect impl (so hover falls through).
     */
    /**
     * Resolve an effect decl by name, from anywhere it might be reachable:
     * local decls, then by-name + transitively-loaded effects (allEffects),
     * then — for an effect whose ONLY its performer was imported (`Log` behind
     * `use …::{ Console }`, so it's in neither map) — a scan of every parsed
     * module. The last fallback recovers op signatures + decl loc.
     */
    private fun effectDeclByName(state: DocumentState, result: TypeCheckResult?, effectName: String): ParsedEffectDecl? =
        state.parsedModule?.decls?.filterIsInstance<ParsedEffectDecl>()?.find { it.name == effectName }
            ?: result?.allEffects?.get(effectName)
            ?: com.mezze.language.typechecker.ModuleResolver.getAllParsedModules().values
                .firstNotNullOfOrNull { m ->
                    m.decls.filterIsInstance<ParsedEffectDecl>().find { it.name == effectName }
                }

    /** Ability-decl analogue of [effectDeclByName] (local → imported → scan). */
    private fun abilityDeclByName(state: DocumentState, result: TypeCheckResult?, abilityName: String): ParsedAbilityDecl? =
        state.parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>()?.find { it.name == abilityName }
            ?: result?.importedAbilities?.get(abilityName)
            ?: com.mezze.language.typechecker.ModuleResolver.getAllParsedModules().values
                .firstNotNullOfOrNull { m ->
                    m.decls.filterIsInstance<ParsedAbilityDecl>().find { it.name == abilityName }
                }

    private fun performerHover(state: DocumentState, result: TypeCheckResult?, name: String): String? {
        fun codeBlock(code: String): String = "```mezze\n$code\n```"
        val pm = state.parsedModule

        fun effectDeclFor(effectName: String): ParsedEffectDecl? = effectDeclByName(state, result, effectName)

        // Clickable markdown link to the effect decl; falls back to the effect
        // reference in the impl header (`perf.effect.loc`, always in the impl's
        // own source) if the decl has no usable loc.
        fun effectLink(perf: ParsedPerformerDecl): String {
            val effectName = perf.effect.localName
            val localDecl = effectDeclFor(effectName)
            val loc = localDecl?.loc?.takeIf { !it.file.isNullOrEmpty() } ?: perf.effect.loc
            // A locally-declared effect (found in THIS document's parsed module)
            // links to the current document directly — its loc.file may not
            // resolve on disk (unsaved buffer), and resolveExistingFileUri would
            // then drop the link to plain text.
            val defUri = if (localDecl != null) state.uri else resolveExistingFileUri(loc.file)
            if (defUri != null) {
                return "[`$effectName`]($defUri#L${loc.startLine},${loc.startCol})"
            }
            return "`$effectName`"
        }

        // Render one impl as Mezze: `impl <Effect> for <name> where { ops }`.
        // Each op shows `op: Param -> Ret`, preferring the impl's own annotation
        // else the effect decl's op sig (imported impl ops carry null types).
        fun renderImpl(perf: ParsedPerformerDecl): String {
            val effectName = perf.effect.localName
            val sigByName = effectDeclFor(effectName)?.operations?.associateBy { it.name } ?: emptyMap()
            val opLines = perf.operations.joinToString("\n") { op ->
                val sig = sigByName[op.name]
                val param = op.paramType ?: sig?.paramType
                val ret = op.returnType ?: sig?.returnType
                if (param != null && ret != null)
                    "  ${op.name}: ${formatTypeAnnotation(param)} -> ${formatTypeAnnotation(ret)}"
                else "  ${op.name}"
            }
            return if (opLines.isBlank()) "impl $effectName for $name"
                else "impl $effectName for $name where {\n$opLines\n}"
        }

        // Local impls whose target is this name.
        val localImpls = pm?.decls?.filterIsInstance<ParsedPerformerDecl>()
            ?.filter { it.performerName == name } ?: emptyList()
        val perfs = if (localImpls.isNotEmpty()) localImpls
            // Imported performer. Performers live in TypeCheckResult.importedImpls
            // (keyed by name), NOT resolvedImports — the ResolvedImport builder
            // has no PERFORMER case, so a `use …::{ Console }` performer is
            // absent from resolvedImports entirely.
            else result?.importedImpls?.get(name)?.let { listOf(it) } ?: emptyList()
        if (perfs.isEmpty()) return null

        val body = perfs.joinToString("\n\n") { renderImpl(it) }
        val links = perfs
            .distinctBy { it.effect.localName }
            .joinToString("\n") { "handles effect ${effectLink(it)}" }
        return codeBlock(body) + "\n\n$links"
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

    /**
     * True if `character` sits at or after the start of a line comment on
     * `line`. Mezze line comments begin with `#` (covering `#`, `##` doc, and
     * `#{ }` metadata). A `#` inside a double-quoted string doesn't start a
     * comment, so we track string state while scanning. Used to suppress hover
     * (and could gate other position features) over comment prose.
     */
    private fun isInsideLineComment(line: String, character: Int): Boolean {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && inString -> { i++ }  // skip escaped char in a string
                c == '"' -> inString = !inString
                c == '#' && !inString -> return character >= i
            }
            i++
        }
        return false
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
                effectDeclByName(state, result, qual)?.operations?.find { it.name == word }
                    ?.let { return listOf(locToLocationCross(it.loc)) }
                abilityDeclByName(state, result, qual)?.operations?.find { it.name == word }
                    ?.let { return listOf(locToLocationCross(it.loc)) }
            }
            // Cursor on the qualifier (`<Effect|Ability>.<op>`) → its decl.
            if (Regex("^\\.[a-z_]").containsMatchIn(after)) {
                effectDeclByName(state, result, word)?.let { return listOf(locToLocationCross(it.loc)) }
                abilityDeclByName(state, result, word)?.let { return listOf(locToLocationCross(it.loc)) }
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
        // Fall back to v2 typed decls' locs when parsedModule is null
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
        // Hydrated-cache fallback: walk the v2 TypedModule's decls.
        val v2 = cached.typedModule ?: return null
        for (decl in v2.decls) {
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

            // No code completion inside a comment — a `#` note or `##` doc line
            // is prose, so suggesting identifiers/keywords there is noise. Mirror
            // the hover guard (isInsideLineComment is string-aware, so `#` inside
            // a string literal still completes).
            if (state != null) {
                val lines = state.content.lines()
                val line = lines.getOrNull(position.line)
                if (line != null && isInsideLineComment(line, position.character)) {
                    return@supplyAsync Either.forLeft<List<CompletionItem>, CompletionList>(emptyList())
                }
            }

            val items = mutableListOf<CompletionItem>()

            // Detect dot-context BEFORE asking getDotCompletions, so we can
            // short-circuit when the dot completions are empty (e.g. the
            // receiver type couldn't be inferred). Without this, the
            // fallback paths below (`getStdlibCompletions`, bindings,
            // keywords) get a shot at matching the partial method name
            // after the dot — and happily suggest things like
            // `use std::int::{to_string, abs}` for `name.to_`, which then
            // inserts the entire `use ...` blob over the user's cursor.
            val isDotContext = state != null && run {
                val lines = state.content.lines()
                if (position.line >= lines.size) false
                else {
                    val before = lines[position.line].substring(0, minOf(position.character, lines[position.line].length))
                    Regex("""\.\w*$""").containsMatchIn(before)
                }
            }

            // Import-path context: the cursor is inside a `use …` line, before
            // any `::{ … }` selective block. Complete module paths from the
            // store's registered modules. Checked before dot/keyword paths so
            // typing `use std::se` never falls through to binding/keyword
            // suggestions. Regex is on the half-typed *line buffer* (an
            // incomplete import), not on committed code.
            val useCompletions = if (state != null) getImportPathCompletions(state, position) else null
            if (useCompletions != null) {
                return@supplyAsync Either.forLeft<List<CompletionItem>, CompletionList>(useCompletions)
            }

            // Ability/effect NAME in a `with …` constraint or `<…>` effect row.
            // Checked before the dot/keyword paths so a partial name there is
            // completed with declared ability/effect names, not bindings.
            val abilityEffectNames = if (state != null) getAbilityEffectNameCompletions(state, position) else null
            if (abilityEffectNames != null) {
                return@supplyAsync Either.forLeft<List<CompletionItem>, CompletionList>(abilityEffectNames)
            }

            // Op names at a field-start position inside an impl body.
            val implOps = if (state != null) getImplBodyOpCompletions(state, position) else null
            if (implOps != null && implOps.isNotEmpty()) {
                return@supplyAsync Either.forLeft<List<CompletionItem>, CompletionList>(implOps)
            }

            val dotCompletions = if (state != null) {
                getDotCompletions(state, position)
            } else {
                emptyList()
            }

            if (dotCompletions.isNotEmpty()) {
                items.addAll(dotCompletions)
                Either.forLeft<List<CompletionItem>, CompletionList>(items)
            } else if (isDotContext) {
                // Dot context but no candidates — return empty rather than
                // falling through to keyword/binding/stdlib paths whose
                // matches would be applied to the post-dot range, polluting
                // the editor with garbage suggestions.
                Either.forLeft<List<CompletionItem>, CompletionList>(emptyList())
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
                    // Add keywords — gated by whether we're at a fresh
                    // top-level position. Top-level keywords (effect/ability/
                    // impl/type) only make sense as the start of a new decl;
                    // surfacing them mid-expression spams the menu.
                    val atTopLevel = state != null && isAtTopLevelStart(state, position)
                    items.addAll(getKeywordCompletions(atTopLevel))

                    // Add bindings from current document (top-level + imports)
                    if (state != null) {
                        items.addAll(getBindingCompletions(state))

                        // Add local variables in scope at this position
                        items.addAll(getLocalVariableCompletions(state, position))

                        // Add variant constructors from visible types
                        items.addAll(getVariantConstructorCompletions(state))

                        // Stdlib free-function completions with auto-import.
                        // Picking one inserts the function name AND a `use
                        // mod::fn` line at the right spot in the file.
                        items.addAll(getStdlibAutoImportCompletions(state, position))
                    }
                }

                // Context filter: in a TYPE-ANNOTATION position (`let f : |`,
                // `{x: |}`, `-> |`), values/functions/keywords are noise — the
                // user is naming a type. Keep only type-shaped items (types,
                // effects, abilities, variant type names) + their auto-imports.
                val inTypeAnnotation = state != null && isInTypeAnnotationPosition(state, position)
                val filtered = if (inTypeAnnotation) {
                    // Only TYPE names belong in a `: T` slot. Abilities/effects
                    // (Interface) go in `with …`/`<…>`, not here — and value
                    // bindings, functions, variant constructors, and expression
                    // keywords are all noise. Keep type-shaped kinds only.
                    items.filter { it.kind.let { k ->
                        k == CompletionItemKind.Struct || k == CompletionItemKind.Enum ||
                        k == CompletionItemKind.Class || k == CompletionItemKind.TypeParameter
                    } }
                } else items

                // Global expected-type ranking pass. Promotes items whose
                // return type / carrier type unifies with the expected type
                // at the cursor (taken from typechecker's expectedTypeAt
                // map). Items already carrying a sortText keep their order
                // among themselves but get a class-prefix that floats matches
                // to the top of the menu overall.
                // Base relevance tiers by kind/source: locals & params first,
                // then in-scope funcs/values, then types/variants, then
                // keywords, then penalized (auto-import) items. Applied only to
                // items that haven't set their own sortText (auto-import,
                // postfix, snippets keep theirs). The expected-type pass then
                // promotes matches ABOVE their tier by prepending "0_".
                // Type-directed GENERATION: when the cursor sits at a position
                // with a known expected type, synthesize the construct that
                // produces it (Ok/Err, a full record literal, a lambda stub)
                // pre-filled — so the user doesn't have to type the skeleton.
                // These are prepended and float to the very top (sortText "0").
                // Suppressed in a type-annotation slot (we want type NAMES there,
                // not value constructors).
                val withGenerated = if (state != null && !inTypeAnnotation) {
                    generateExpectedTypeItems(state, position) + filtered
                } else filtered

                val tiered = applyBaseRelevanceTiers(withGenerated)
                val ranked = if (state != null) {
                    rankItemsByExpectedTypeGeneric(tiered, state, position)
                } else tiered

                Either.forLeft(ranked)
            }
        }
    }

    /**
     * Assign a base relevance tier (a `sortText` prefix) to each item that
     * doesn't already carry one, so the menu orders by how likely the item is
     * what the user wants at a fresh identifier position:
     *
     *   1_ local variables + lambda params (closest scope)
     *   2_ in-scope values / functions (top-level decls, imports)
     *   3_ types / variant constructors / effects / abilities
     *   4_ keywords
     *   (items with their own sortText — auto-import "zz_", postfix "zzz_",
     *    snippet "0" — are left untouched so they keep their intended rank)
     *
     * The expected-type pass runs after this and prepends "0_" to promote
     * matches above every tier. Ties within a tier stay label-sorted.
     */
    private fun applyBaseRelevanceTiers(items: List<CompletionItem>): List<CompletionItem> {
        return items.map { item ->
            if (item.sortText != null) return@map item  // keep an explicit rank
            // Locals/params are pinned to tier "1" at their source
            // (getLocalVariableCompletions) via an explicit sortText, so any
            // bare Variable reaching here is a top-level value binding (tier 2).
            val tier = when (item.kind) {
                CompletionItemKind.Field -> "1"        // record-field arg names
                CompletionItemKind.Variable,
                CompletionItemKind.Function, CompletionItemKind.Method -> "2"
                CompletionItemKind.Struct, CompletionItemKind.EnumMember,
                CompletionItemKind.Enum, CompletionItemKind.Interface,
                CompletionItemKind.Class, CompletionItemKind.Constructor -> "3"
                CompletionItemKind.Keyword -> "4"
                else -> "2"
            }
            item.apply { sortText = "${tier}_${label}" }
        }
    }

    /**
     * Cheap expected-type ranking that augments items in-place. We classify
     * each item label against:
     *   - Local let bindings (typed map lookup → return type)
     *   - Imported bindings
     *   - Variant constructors (Bool, Option, Result, and user variants —
     *     match if expected is a TypeApplication of the variant's type name)
     *
     * Items that match get sortText prefix `"0_"`, non-matches `"5_"`. If
     * an item already has a sortText we preserve it; lexicographic order
     * within a rank class keeps existing intra-group orderings.
     */
    /**
     * The typechecker's expected type at the cursor, if any — the smallest
     * `expectedTypeAt` entry whose loc contains (pos), with a little trailing
     * slack so a cursor just past the slot's end still counts. Shared by the
     * expected-type ranking pass and type-directed generation.
     */
    private fun expectedTypeAtCursor(state: DocumentState, pos: Position): MezzeType? {
        val result = state.typeCheckResult ?: return null
        val expectedTypeAt = result.expectedTypeAt
        if (expectedTypeAt.isEmpty()) return null
        val cursorLine = pos.line + 1
        val cursorCol = pos.character + 1
        val slack = 3
        return expectedTypeAt
            .filterKeys { loc ->
                val sb = loc.startLine < cursorLine ||
                    (loc.startLine == cursorLine && loc.startCol <= cursorCol)
                val ea = loc.endLine > cursorLine ||
                    (loc.endLine == cursorLine && loc.endCol + slack >= cursorCol)
                sb && ea
            }
            .entries
            .minByOrNull { (loc, _) -> (loc.endLine - loc.startLine) * 1_000_000 + (loc.endCol - loc.startCol) }
            ?.value
    }

    /**
     * Type-directed generation: given the expected type at the cursor, produce
     * completion items that CONSTRUCT a value of that type, pre-filled with
     * snippet tabstops. Ranking already floats matching candidates; this goes
     * a step further and emits the skeleton the user would otherwise type.
     *
     *   expected `Result 'a 'e` → `Ok { $1 }`, `Err { error = $1 }`
     *   expected `Option 'a`    → `Some { $1 }`, `None`
     *   expected a user variant → one item per constructor (payload pre-filled)
     *   expected a record type  → the full `{ f1 = $1, f2 = $2 }` literal
     *   expected a function type → `{param} -> $1` lambda stub
     *
     * All items sort to the very top ("0"). Returns empty when the expected
     * type isn't a shape we can helpfully build (tyvar, scalar, etc.).
     */
    private fun generateExpectedTypeItems(state: DocumentState, pos: Position): List<CompletionItem> {
        val expected = expectedTypeAtCursor(state, pos) ?: return emptyList()
        return generateItemsForType(state, expected)
    }

    /**
     * The type→construction-items core, callable directly with a concrete
     * [expected] type (used both by the cursor-based path and by field-value
     * completion, which knows the field's type from the call's record param).
     */
    private fun generateItemsForType(state: DocumentState, expected: MezzeType): List<CompletionItem> {
        val out = mutableListOf<CompletionItem>()

        fun snippetItem(label: String, snippet: String, detail: String, order: Int): CompletionItem =
            CompletionItem(label).apply {
                kind = CompletionItemKind.Snippet
                insertText = snippet
                insertTextFormat = InsertTextFormat.Snippet
                this.detail = detail
                sortText = "0_gen_${order}_$label"
            }

        // A tabstop that shows the element type where helpful, e.g. `${1:Int}`.
        fun tab(idx: Int, t: MezzeType): String {
            val hint = concreteTypeHint(t)
            return if (hint != null) "\${${idx}:$hint}" else "\$$idx"
        }

        when (expected) {
            is MezzeType.TypeApplication -> when (expected.constructor) {
                "Result" -> {
                    val okT = expected.args.getOrNull(0)
                    val errT = expected.args.getOrNull(1)
                    out += snippetItem("Ok", "Ok { ${if (okT != null) tab(1, okT) else "\$1"} }", "construct Result", 0)
                    out += snippetItem("Err", "Err { error = ${if (errT != null) tab(1, errT) else "\$1"} }", "construct Result", 1)
                }
                "Option" -> {
                    val someT = expected.args.getOrNull(0)
                    out += snippetItem("Some", "Some { ${if (someT != null) tab(1, someT) else "\$1"} }", "construct Option", 0)
                    out += snippetItem("None", "None", "construct Option", 1)
                }
                else -> generateForNamedType(state, expected.constructor, out)
            }
            is MezzeType.VariantType -> {
                expected.typeName?.let { generateForNamedType(state, it, out) }
            }
            is MezzeType.RecordType -> {
                if (expected.fields.isNotEmpty()) {
                    val body = expected.fields.entries.toList().mapIndexed { idx, (fn, ft) ->
                        "$fn = ${renderFieldPlaceholderPublic(fn, ft, idx + 1)}"
                    }.joinToString(", ")
                    out += snippetItem("{ … } record", "{ $body }", "fill record literal", 0)
                }
            }
            is MezzeType.FunctionType -> {
                val params = (expected.paramType as? MezzeType.RecordType)?.fields?.keys?.toList()
                    ?: listOf("x")
                out += snippetItem("{ … } -> …", "{${params.joinToString(", ")}} -> \$1", "fill lambda", 0)
            }
            is MezzeType.BoolType -> {
                out += snippetItem("True", "True", "Bool", 0)
                out += snippetItem("False", "False", "Bool", 1)
            }
            // Not usefully constructible from a bare expected type.
            is MezzeType.VarType, is MezzeType.IntType, is MezzeType.FloatType,
            is MezzeType.StringType, is MezzeType.CharType, is MezzeType.NatType,
            is MezzeType.FracType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
            is MezzeType.NewtypeType, is MezzeType.ListType,
            is MezzeType.SeqType, is MezzeType.MapType, is MezzeType.SetType,
            is MezzeType.SortedMapType, is MezzeType.SortedSetType,
            is MezzeType.SuspendType, is MezzeType.EffectRow -> {}
        }
        return out
    }

    /**
     * Emit a construction item per constructor of a locally-declared variant
     * type. `Color` → `Red`, `Green`, `Blue`; a payload-carrying constructor
     * gets a `{ $1 }` skeleton. No-op if the type isn't a variant decl in this
     * module (cross-module constructor generation would need the store index).
     */
    private fun generateForNamedType(state: DocumentState, typeName: String, out: MutableList<CompletionItem>) {
        val decl = state.parsedModule?.decls?.filterIsInstance<ParsedTypeDecl>()?.find { it.name == typeName }
        val def = decl?.definition as? com.mezze.language.parsedast.ParsedVariantDef ?: return
        def.constructors.forEachIndexed { idx, ctor ->
            // A constructor's payload is a type annotation; when it's a record
            // (the common case), pre-fill each field as a named tabstop.
            val recordEntries = (ctor.dataType as? com.mezze.language.parsedast.ParsedRecordType)?.entries
            val snippet = when {
                recordEntries != null && recordEntries.isNotEmpty() -> {
                    val body = recordEntries.mapIndexed { i, e -> "${e.name} = \${${i + 1}:${e.name}}" }
                        .joinToString(", ")
                    "${ctor.name} { $body }"
                }
                ctor.dataType != null -> "${ctor.name} { \$1 }"  // non-record payload
                else -> ctor.name                                 // no payload
            }
            out += CompletionItem(ctor.name).apply {
                kind = CompletionItemKind.Snippet
                insertText = snippet
                insertTextFormat = InsertTextFormat.Snippet
                detail = "construct $typeName"
                sortText = "0_gen_${idx}_${ctor.name}"
            }
        }
    }

    /** Public shim so generation can reuse the typed field-placeholder renderer. */
    private fun renderFieldPlaceholderPublic(fieldName: String, fieldType: MezzeType, idx: Int): String =
        renderFieldPlaceholder(fieldName, fieldType, idx)

    private fun rankItemsByExpectedTypeGeneric(
        items: List<CompletionItem>,
        state: DocumentState,
        pos: Position,
    ): List<CompletionItem> {
        val expected = expectedTypeAtCursor(state, pos) ?: return items

        val expectedVariantName = (expected as? MezzeType.TypeApplication)?.constructor
            ?: (expected as? MezzeType.BoolType)?.let { "Bool" }

        // Build a label → "is a match" lookup. Constructors that belong to
        // the expected variant family count; everything else gets the
        // default rank.
        // Core-variant constructor names come from ast/BuiltinTypes (single
        // source of truth) so this can't drift from the compiler's own set.
        val knownVariants = mutableMapOf(
            BuiltinTypes.TRUE to "Bool", BuiltinTypes.FALSE to "Bool",
            BuiltinTypes.SOME to "Option", BuiltinTypes.NONE to "Option",
            BuiltinTypes.OK to "Result", BuiltinTypes.ERR to "Result",
        )
        state.parsedModule?.decls?.filterIsInstance<ParsedTypeDecl>()?.forEach { td ->
            val def = td.definition
            if (def is com.mezze.language.parsedast.ParsedVariantDef) {
                for (c in def.constructors) knownVariants[c.name] = td.name
            }
        }

        return items.map { item ->
            val matches = expectedVariantName != null &&
                knownVariants[item.label] == expectedVariantName
            if (matches) {
                // Float to top. Preserve the item's secondary order key when
                // present, falling back to label.
                val secondary = item.sortText ?: item.label
                item.apply { sortText = "0_${secondary}" }
            } else item
        }
    }

    /**
     * Surface stdlib `let` decls (free functions, not ability methods) in
     * the completion menu. Each item carries an `additionalTextEdits` that
     * inserts the corresponding `use module::name` line so accepting the
     * completion gives the user both the call site AND the import.
     *
     * Filter rules:
     *  - Only fires when the user has typed ≥ 2 chars on the current line.
     *    Below that the menu would explode with hundreds of stdlib names.
     *  - Skip names that are already in scope (imported, locally declared,
     *    or part of the file's top-level bindings) — those already appear
     *    via [getBindingCompletions] without the extra import noise.
     *  - Skip names that don't share a prefix with the user's token (a
     *    cheap filter so we don't ship the entire stdlib over the wire).
     */
    /**
     * Completions for module paths inside a `use …` line. Returns null when the
     * cursor is NOT in an import-path context (so the caller falls through to
     * ordinary completion); returns a (possibly empty) list when it is.
     *
     * Sourced from the store's registered modules ([MezzeStore.getAllModules]),
     * which covers stdlib + workspace files. Handles the two shapes:
     *  - `use std::se` → complete whole module paths starting with `std::se`.
     *  - `use std::seq::{ ` (inside the selective block) → NOT an import-PATH
     *    context; hand back null so the item-name completion path can run.
     *
     * The prefix is read from the half-typed line buffer (an incomplete import),
     * which is the appropriate place for a text scan — it isn't committed code.
     */
    private fun getImportPathCompletions(state: DocumentState, position: Position): List<CompletionItem>? {
        val line = state.content.lines().getOrNull(position.line) ?: return null
        val before = line.substring(0, minOf(position.character, line.length))
        // Match `use ` / `pub use ` then capture the partial path typed so far.
        // Bail (return null) once a `::{` selective block has opened — that's
        // item-name territory, handled elsewhere.
        val m = Regex("""^\s*(?:pub\s+)?use\s+([\w:]*)$""").find(before) ?: return null
        val typed = m.groupValues[1]   // e.g. "std::se" or "" or "std::"

        // Module paths come from two sources, unioned so both worlds complete:
        //  - the store's registered ModuleRows (workspace `.mz` files), and
        //  - PrecompiledStdlib's typed modules (stdlib is hydrated in-memory and
        //    never gets ModuleRows in the store).
        val fromStore = try { projectStore?.getAllModules()?.map { it.path } ?: emptyList() } catch (_: Exception) { emptyList() }
        // PrecompiledStdlib exposes each module's *file* path (e.g.
        // `../stdlib/std/int.mz`); derive the dotted module path (`std::int`)
        // by taking everything after the `stdlib/` root, dropping `.mz`, and
        // joining segments with `::`.
        val fromStdlib = try {
            com.mezze.language.typechecker.PrecompiledStdlib.stdlibTypedModules.values
                .mapNotNull { filePathToModulePath(it.path) }
        } catch (_: Exception) { emptyList() }
        val modules = (fromStore + fromStdlib).filter { it.isNotBlank() }.distinct()
        if (modules.isEmpty()) return emptyList()

        // The replacement covers just the already-typed path text, so accepting
        // an item overwrites `std::se`, not the whole line.
        val replaceStart = Position(position.line, position.character - typed.length)
        val replaceRange = Range(replaceStart, position)

        val items = modules.asSequence()
            .filter { it.isNotBlank() && it.startsWith(typed) }
            .distinct()
            .sorted()
            .take(50)
            .map { path ->
                CompletionItem(path).apply {
                    kind = CompletionItemKind.Module
                    detail = "module"
                    textEdit = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(TextEdit(replaceRange, path))
                    // Sort by path so `std::int` precedes `std::seq`.
                    sortText = path
                }
            }
            .toList()
        return items
    }

    /**
     * Convert a module's *file* path to its dotted module path:
     * `../stdlib/std/int.mz` → `std::int`, `src/foo/bar.mz` → `foo::bar`.
     * Returns null for a path we can't confidently map (no `.mz`, or no
     * recognizable `stdlib/` / `src/` root). The segment after the root
     * anchor is where the module path begins.
     */
    private fun filePathToModulePath(filePath: String): String? {
        if (!filePath.endsWith(".mz")) return null
        val norm = filePath.replace('\\', '/')
        val segments = norm.removeSuffix(".mz").split('/').filter { it.isNotBlank() && it != ".." && it != "." }
        // Anchor on the last `stdlib` or `src` segment; the module path is
        // everything after it.
        val anchor = segments.indexOfLast { it == "stdlib" || it == "src" }
        val relevant = if (anchor >= 0) segments.drop(anchor + 1) else segments
        return if (relevant.isEmpty()) null else relevant.joinToString("::")
    }

    private fun getStdlibAutoImportCompletions(
        state: DocumentState,
        position: Position,
    ): List<CompletionItem> {
        val store = projectStore ?: return emptyList()
        val prefix = identifierPrefixAt(state, position) ?: return emptyList()
        if (prefix.length < 2) return emptyList()

        // Names already visible — don't suggest them again with an import.
        val visibleNames = mutableSetOf<String>()
        state.typeCheckResult?.resolvedImports?.forEach { visibleNames.add(it.localName) }
        state.parsedModule?.decls?.forEach { decl ->
            when (decl) {
                is com.mezze.language.parsedast.ParsedLetDecl ->
                    if (decl.isSimpleBinding) visibleNames.add(decl.variableName)
                is com.mezze.language.parsedast.ParsedTypeDecl -> visibleNames.add(decl.name)
                is com.mezze.language.parsedast.ParsedAbilityDecl -> visibleNames.add(decl.name)
                is com.mezze.language.parsedast.ParsedEffectDecl -> visibleNames.add(decl.name)
                else -> {}
            }
        }

        val items = mutableListOf<CompletionItem>()
        val seen = mutableSetOf<String>()

        // Shared builder for an auto-import completion of one store decl.
        // `itemKind`/`detailKind` tag the menu entry; a `use mod::{name}`
        // side-edit is attached so accepting also imports it.
        fun addAutoImport(
            declRow: com.mezze.language.store.DeclRow,
            itemKind: CompletionItemKind,
            detailKind: String,
        ): Boolean {
            val name = declRow.declName
            if (name in visibleNames) return false
            if (!name.startsWith(prefix, ignoreCase = false) && !name.contains(prefix, ignoreCase = true)) return false
            if (name.startsWith("_")) return false  // mangled/private convention
            if (!seen.add("${declRow.modulePath}::$name")) return false
            items.add(CompletionItem(name).apply {
                kind = itemKind
                detail = "$detailKind from ${declRow.modulePath}"
                declRow.doc?.takeIf { it.isNotBlank() }?.let {
                    documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, renderDocMarkdown(it)))
                }
                sortText = "zz_$name"  // penalize below locally-visible names
                insertText = name
                buildAutoImportEdit(state, declRow.modulePath, name)?.let {
                    additionalTextEdits = listOf(it)
                }
            })
            return true
        }

        try {
            for (declRow in store.getDeclsByKind(com.mezze.language.store.DeclKind.LET)) {
                addAutoImport(declRow, CompletionItemKind.Function, "fn")
                if (items.size >= 30) break
            }
            // Types, effects, and abilities are also importable by name — a
            // type annotation `: Us…` should offer `UserId` with its `use`.
            for ((kind, itemKind, label) in listOf(
                Triple(com.mezze.language.store.DeclKind.TYPE, CompletionItemKind.Struct, "type"),
                Triple(com.mezze.language.store.DeclKind.EFFECT, CompletionItemKind.Interface, "effect"),
                Triple(com.mezze.language.store.DeclKind.ABILITY, CompletionItemKind.Interface, "ability"),
            )) {
                if (items.size >= 40) break
                for (declRow in store.getDeclsByKind(kind)) {
                    addAutoImport(declRow, itemKind, label)
                    if (items.size >= 40) break
                }
            }
        } catch (e: Exception) {
            // Best-effort. Don't fail the whole completion if the store
            // hiccups (e.g. read-during-restart).
            server.logError("Stdlib auto-import completion failed: ${e.message}")
        }
        return items
    }

    /**
     * Read the identifier-shaped prefix at the cursor. Returns null when
     * the cursor isn't on an identifier (e.g. inside a string literal).
     */
    private fun identifierPrefixAt(state: DocumentState, pos: Position): String? {
        val line = state.content.split('\n').getOrNull(pos.line) ?: return null
        val col = pos.character.coerceIn(0, line.length)
        var s = col
        while (s > 0 && line[s - 1].let { it.isLetterOrDigit() || it == '_' }) s--
        return if (col > s) line.substring(s, col) else null
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

        // Field-VALUE position: `foo { field = |` (possibly with a partial
        // value typed). The user is writing the field's value, not naming a
        // field — so offer type-directed GENERATION for that field's type
        // (Ok/Err, a nested record, constructors, a lambda stub) rather than
        // the field-name list. `fieldValuePrefix` finds the field whose value
        // we're inside on the current partial line.
        val activeField = fieldValueContext(beforeCursor, recordFields.map { it.first })
        if (activeField != null) {
            val fieldType = recordFields.firstOrNull { it.first == activeField }?.second
            if (fieldType != null) {
                val gen = generateItemsForType(state, fieldType)
                if (gen.isNotEmpty()) return gen
            }
        }

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
     * If the cursor sits at a field-VALUE position inside a record-arg call
     * (`… { field = ⟨here⟩`), return the field's name; else null. We look at
     * the text after the last `{` or `,` (a field separator) and check it
     * matches `<name> = <partial value>` where `<name>` is a known field. The
     * value part may be empty (just after `=`) or a partial expression — either
     * way the user is writing that field's value.
     */
    private fun fieldValueContext(beforeCursor: String, fieldNames: List<String>): String? {
        // The current field segment starts after the last `{` or `,`.
        val segStart = maxOf(beforeCursor.lastIndexOf('{'), beforeCursor.lastIndexOf(','))
        if (segStart < 0) return null
        val seg = beforeCursor.substring(segStart + 1)
        // Must contain a top-level `=` (the field-name/value divider). If there's
        // no `=`, we're still typing the field NAME, not its value.
        val eq = seg.indexOf('=')
        if (eq < 0) return null
        // Guard against `==`, `<=`, `>=`, `!=` inside an already-started value —
        // those mean we're past the field-name `=` and deep in an expression;
        // still a value position, so that's fine. But a lone `=` divider must be
        // the field assignment: the char before it shouldn't be a comparison op.
        val name = seg.substring(0, eq).trim()
        return if (name in fieldNames) name else null
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
    /**
     * Snippet for a top-level function or imported function call:
     * `foo { field1 = ${1:field1}, field2 = ${2:field2} }`. Returns null
     * if the binding isn't a function (no wrap needed — just insert the
     * name). Mirrors the `buildMethodSnippet` shape but uses ALL fields,
     * not the method-shape stripping `it`.
     */
    internal fun callInsertSnippet(name: String, type: MezzeType): String? {
        val fnType = type as? MezzeType.FunctionType ?: return null
        val paramRecord = fnType.paramType as? MezzeType.RecordType
        if (paramRecord == null) return "$name { $0 }"
        if (paramRecord.fields.isEmpty()) return "$name {}"
        val body = paramRecord.fields.entries.toList().mapIndexed { idx, (fieldName, fieldType) ->
            "$fieldName = ${renderFieldPlaceholder(fieldName, fieldType, idx + 1)}"
        }.joinToString(", ")
        return "$name { $body }"
    }

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
        val fnType = fieldType as? MezzeType.FunctionType
        if (fnType == null) {
            // Plain field: label the tabstop with the field name, and — when the
            // type is a concrete named type — append it so the user sees what's
            // expected (`${1:msg: Str}`). Skip for tyvars / structural types
            // where the annotation would be noise (`'a`, `{ … }`).
            val hint = concreteTypeHint(fieldType)
            return if (hint != null) "\${${idx}:$fieldName: $hint}" else "\${${idx}:$fieldName}"
        }
        val innerParams = (fnType.paramType as? MezzeType.RecordType)
            ?.fields?.keys?.toList()
            ?: listOf("x")
        return "\${${idx}:{${innerParams.joinToString(", ")}} -> $0}"
    }

    /**
     * A short type name to show inside a snippet placeholder, or null when the
     * type isn't a helpfully-nameable concrete type. Exhaustive over MezzeType
     * (no `else`, per the codebase's no-silent-fallthrough rule) so a new type
     * variant forces an explicit decision here. The `null` cases are types that
     * would add noise rather than signal in a one-line placeholder (tyvars,
     * functions, records, variants, containers, effect rows).
     */
    private fun concreteTypeHint(type: MezzeType): String? = concreteTypeHint(type, depth = 0)

    /**
     * [depth] bounds how deep we recurse into container element types so a
     * `List (Map Str (Set Int))` doesn't produce a wall of text in a one-line
     * placeholder. Beyond the limit, or for a genuinely un-nameable element,
     * the container renders with a `_` element (`List _`) — still signals the
     * shape without the noise.
     */
    private fun concreteTypeHint(type: MezzeType, depth: Int): String? {
        // Element rendering for containers: nameable → its name; too deep or
        // un-nameable → `_` so the container shape still shows.
        fun elem(t: MezzeType): String = if (depth >= 2) "_" else (concreteTypeHint(t, depth + 1) ?: "_")
        return when (type) {
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "Str"
            is MezzeType.BoolType -> "Bool"
            is MezzeType.CharType -> "Char"
            is MezzeType.NatType -> "Nat"
            is MezzeType.FracType -> "Frac"
            is MezzeType.BytesType -> "Bytes"
            is MezzeType.ByteBufferType -> "ByteBuffer"
            is MezzeType.TypeApplication ->
                // e.g. `Option Str`, `Result Int Str`. Render the arg vector too
                // so the user sees the full shape; drop it once it gets deep.
                if (type.args.isEmpty()) type.constructor
                else "${type.constructor} ${type.args.joinToString(" ") { elem(it) }}"
            is MezzeType.NewtypeType -> concreteTypeHint(type.wrappedType, depth)
            // Containers — a short shape is helpful signal (`List Int`, not the
            // opaque field name). Element rendering is depth-bounded above.
            is MezzeType.ListType -> "List ${elem(type.elementType)}"
            is MezzeType.SeqType -> "Seq ${elem(type.elementType)}"
            is MezzeType.SetType -> "Set ${elem(type.elementType)}"
            is MezzeType.SortedSetType -> "SortedSet ${elem(type.elementType)}"
            is MezzeType.MapType -> "Map ${elem(type.keyType)} ${elem(type.valueType)}"
            is MezzeType.SortedMapType -> "SortedMap ${elem(type.keyType)} ${elem(type.valueType)}"
            // A named variant alias (`type Color = | Red, … |`) renders as its
            // name; an anonymous/inline union would be noise.
            is MezzeType.VariantType -> type.typeName
            // Structural / polymorphic — a bare name would mislead, so fall back
            // to just the field name (null = no type hint appended).
            is MezzeType.VarType,
            is MezzeType.FunctionType,
            is MezzeType.RecordType,
            is MezzeType.SuspendType,
            is MezzeType.EffectRow -> null
        }
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
     * Return type of an inherent impl op, derived from the post-solve typed
     * impl. Used by the type-aware completion ranker to know what each
     * candidate produces (`to_str` -> `Str`, `to_float` -> `Frac`, etc.).
     */
    private fun lookupTypedImplOpReturnType(
        precompiled: com.mezze.language.typechecker.PrecompiledStdlib,
        targetTypeName: String,
        opName: String
    ): MezzeType? {
        for (impl in precompiled.stdlibAbilityImpls) {
            val target = impl.targetTypes.firstOrNull() ?: continue
            if (getTypeName(target) != targetTypeName) continue
            val opBody = impl.operations[opName] ?: continue
            val lambda = opBody as? com.mezze.language.typedast.TypedLambda ?: continue
            return lambda.body.type
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
            findReceiverType(receiverName, result, position, state)
        } else {
            findReceiverTypeForExpressionAt(state, position, result)
        } ?: run {
            server.logInfo("[dot-completion] receiver type lookup returned null — returning empty")
            return emptyList()
        }
        // Unwrap SuspendType
        val baseType = when (receiverType) {
            is MezzeType.SuspendType -> receiverType.valueType
            else -> receiverType
        }
        server.logInfo("[dot-completion] receiverType=$baseType (typeName=${getTypeName(baseType) ?: "<none>"})")

        val items = mutableListOf<CompletionItem>()
        val typeName = getTypeName(baseType)
        val addedMethods = mutableSetOf<String>()  // Deduplicate
        // Side-map for type-aware ranking: candidate label → its return type.
        // Used by rankByExpectedType to unify against the expected type at
        // the cursor. Populated wherever we know the candidate's return type
        // (ability methods, inherent impls). Candidates without a known
        // return type are ranked as "non-match" (fall to the bottom).
        val candidateReturnTypes = mutableMapOf<String, MezzeType>()

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

        // NEVER dump the whole method universe. If we couldn't resolve the
        // receiver to a concrete named type (it's a bare tyvar mid-edit, or an
        // unnameable shape), do NOT fall through to the by-name method lists
        // below — with `targetTypeName == null` their `!= null && ...` filters
        // don't fire and EVERY method from EVERY type leaks (fold, Seq.*,
        // Map.*, …). That's actively misleading, so return nothing here rather
        // than a garbage list. (Real editing recovers the type from the
        // last-good module — see findReceiverType — so this only bites the
        // genuinely-unknowable case, where empty is correct.)
        if (targetTypeName == null) {
            server.logInfo("[dot-completion] receiver type unresolved ($baseType) — returning no methods (never dump all)")
            return emptyList()
        }

        // 1. Ability methods whose ability is in scope (imported or local) AND
        //    whose impl exists for this receiver type.
        for (abilityDecl in allAbilities) {
            if (!isBuiltinAbilityForType(abilityDecl.name, baseType)) continue
            for (op in abilityDecl.operations) {
                if (op.name in addedMethods) continue
                val paramStr = formatTypeAnnotation(op.paramType)
                if (!paramStr.contains("it")) continue
                val hints = paramHintsFromAnnotation(op.paramType, stripIt = true)
                // Specialize the generic op signature to THIS receiver when we
                // can (`{ it: Int } -> Str` beats `{ it: 'a } -> Str`). Falls
                // back to the generic annotation if conversion fails.
                val sigDoc = specializeAbilityOp(op, abilityDecl.receiverTypeParam, baseType, state.typeAliases)
                    ?: "${formatTypeAnnotation(op.paramType)} -> ${formatTypeAnnotation(op.returnType)}"
                items.add(CompletionItem(op.name).apply {
                    kind = CompletionItemKind.Method
                    detail = "${abilityDecl.name}.${op.name}"
                    documentation = Either.forLeft(sigDoc)
                    insertText = buildMethodSnippet(op.name, hints)
                    insertTextFormat = InsertTextFormat.Snippet
                })
                addedMethods.add(op.name)
                try {
                    candidateReturnTypes[op.name] =
                        com.mezze.language.typechecker.util.TypeAnnotationConverter.convert(op.returnType)
                } catch (_: Exception) { /* unconvertible — skip for ranking */ }
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
                try {
                    op.returnType?.let { rt ->
                        candidateReturnTypes[op.name] =
                            com.mezze.language.typechecker.util.TypeAnnotationConverter.convert(rt)
                    }
                } catch (_: Exception) {}
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
                    // For section 3 (prelude inherent impls) the parsed
                    // ImplOp.returnType is usually null — types are inferred.
                    // Pull the solved return type from the typed impl body.
                    val typedReturnType = lookupTypedImplOpReturnType(
                        precompiledForInherent, implTargetName, op.name
                    )
                    // Show the specialized signature in the dropdown doc. The
                    // typed impl body's param/return are ALREADY concrete for
                    // this receiver (`{ it: Int } -> Str`), so no substitution
                    // needed — just pretty-print them.
                    val sigDoc = if (typedParamType != null && typedReturnType != null) {
                        "${prettyPrintTypeWithAliases(typedParamType, state.typeAliases)} -> " +
                            prettyPrintTypeWithAliases(typedReturnType, state.typeAliases)
                    } else null
                    items.add(CompletionItem(op.name).apply {
                        kind = CompletionItemKind.Method
                        detail = "$implTargetName.${op.name}"
                        if (sigDoc != null) documentation = Either.forLeft(sigDoc)
                        insertText = buildMethodSnippet(op.name, hints)
                        insertTextFormat = InsertTextFormat.Snippet
                    })
                    addedMethods.add(op.name)
                    if (typedReturnType != null) {
                        candidateReturnTypes[op.name] = typedReturnType
                    } else {
                        // Fall back to parsed annotation (rare for inherent prelude impls).
                        try {
                            op.returnType?.let { rt ->
                                candidateReturnTypes[op.name] =
                                    com.mezze.language.typechecker.util.TypeAnnotationConverter.convert(rt)
                            }
                        } catch (_: Exception) {}
                    }
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
                val abilityDecls = store.getDeclsByKind(com.mezze.language.store.DeclKind.ABILITY)
                val abilityModuleByHash = abilityDecls.associate { it.hash to it.modulePath }
                val abilityNameByHash = abilityDecls.associate { it.hash to it.declName }

                val implDecls = store.getDeclsByKind(com.mezze.language.store.DeclKind.IMPL)
                var step4Added = 0
                for (implRow in implDecls) {
                    val implBlob = store.getBlob(implRow.hash) ?: continue
                    if (implBlob.kind != com.mezze.language.store.BlobKind.ABILITY_IMPL) continue
                    val implTyped = try {
                        val bytes = implBlob.typed ?: continue
                        com.mezze.language.store.StoreSerialization.deserializeTypedAbilityImpl(bytes)
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
                        com.mezze.language.store.StoreSerialization.deserializeTypedAbilityDecl(bytes)
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

        // Postfix completions: `x.some` → `Some { x }`, `x.ok` → `Ok { x }`,
        // etc. Only for a named-identifier receiver (we need its source text to
        // build the rewrite), and only when the partial matches a postfix
        // trigger. Each item's textEdit replaces the whole `x.partial` span.
        if (receiverName != null && identifierMatch != null) {
            val startCol = position.character - identifierMatch.value.length
            val replaceRange = Range(
                Position(position.line, startCol.coerceAtLeast(0)),
                Position(position.line, position.character),
            )
            items.addAll(postfixCompletions(receiverName, partialMethod, replaceRange))
        }

        // Filter by partial method name if the user already started typing.
        // Postfix items carry their own textEdit, so match them by label too.
        val filtered = if (partialMethod.isNotEmpty()) {
            items.filter { it.label.startsWith(partialMethod, ignoreCase = true) }
        } else {
            items
        }

        // A `.` inside string interpolation (`"…$( x. )…"`) demands a Str: the
        // interpolation hole is unified with StringType. The typechecker doesn't
        // record that as an `expectedTypeAt` entry, but we can see it
        // syntactically — if the receiver sits inside an unclosed `$(` on this
        // line, prefer methods that produce a string. `to_str`/`to_dbg_str`
        // (and any other Str-returning method) then float to the top.
        val expectedOverride = if (isInsideStringInterpolation(beforeCursor)) MezzeType.StringType else null

        // Type-aware ranking. The typechecker pre-computes `expectedTypeAt`
        // for every source position where a constraint is emitted. Look up
        // the smallest enclosing loc at the cursor; if we find an expected
        // type, score each candidate by unifying its return type against
        // that expected. Matches get sortText "0_<label>", non-matches
        // "5_<label>". Editors sort lexicographically on sortText, so
        // matches float to the top.
        val rankedItems = rankByExpectedType(
            items = filtered,
            cursorLine = position.line + 1,
            cursorCol = position.character + 1,
            result = result,
            candidateReturnTypes = candidateReturnTypes,
            expectedOverride = expectedOverride,
        )

        server.logInfo("[dot-completion] returning ${rankedItems.size} method completions for type '${getTypeName(baseType) ?: baseType::class.simpleName}': ${rankedItems.take(20).map { it.label }}${if (rankedItems.size > 20) "..." else ""}")
        return rankedItems
    }

    /**
     * Postfix-style completions on a named receiver: `x.some` → `Some { x }`.
     * Each item's [TextEdit] replaces the whole `receiver.partial` span (passed
     * as [replaceRange]) with the expansion, so accepting it rewrites the value
     * in place. Snippet-form so the cursor lands at `$0`.
     *
     * The set is intentionally small and value-shaped (constructor wrapping,
     * pattern-match scaffolding) — the things you reach for often enough that
     * typing `x.` and picking is faster than wrapping by hand.
     */
    private fun postfixCompletions(receiver: String, partial: String, replaceRange: Range): List<CompletionItem> {
        data class Pf(val trigger: String, val expansion: String, val detail: String)
        val templates = listOf(
            Pf("some", "Some { $receiver }", "wrap in Some"),
            Pf("ok", "Ok { $receiver }", "wrap in Ok"),
            Pf("err", "Err { error = $receiver }", "wrap in Err"),
            Pf("match", "match $receiver is\n| \${1:pattern} -> \${2:result}", "match on this value"),
            Pf("let", "let \${1:name} = $receiver", "bind to a let"),
        )
        return templates
            .filter { partial.isEmpty() || it.trigger.startsWith(partial, ignoreCase = true) }
            .map { pf ->
                CompletionItem(pf.trigger).apply {
                    kind = CompletionItemKind.Snippet
                    detail = "postfix: ${pf.detail}"
                    // Rank postfix below real methods sharing the prefix.
                    sortText = "zzz_${pf.trigger}"
                    insertTextFormat = InsertTextFormat.Snippet
                    textEdit = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(TextEdit(replaceRange, pf.expansion))
                }
            }
    }

    /**
     * Type-aware completion ranking. For each candidate whose return type is
     * known, unify it with the expected type at the cursor; matches sort
     * first. Falls through to identity ordering when there's no expected
     * type (typechecker didn't pin one) or no candidate return type.
     */
    private fun rankByExpectedType(
        items: List<CompletionItem>,
        cursorLine: Int,
        cursorCol: Int,
        result: TypeCheckResult,
        candidateReturnTypes: Map<String, MezzeType>,
        expectedOverride: MezzeType? = null,
    ): List<CompletionItem> {
        val expectedTypeAt = result.expectedTypeAt
        server.logInfo("[rank] cursor=$cursorLine:$cursorCol expectedTypeAt size=${expectedTypeAt.size} candidateReturnTypes size=${candidateReturnTypes.size} override=$expectedOverride")
        // An explicit override (e.g. Str inside string interpolation) wins over
        // the position-derived expected type — the syntax is unambiguous.
        if (expectedOverride != null) {
            return rankAgainst(items, expectedOverride, candidateReturnTypes)
        }
        if (expectedTypeAt.isEmpty()) return items

        // Find the smallest enclosing Loc that contains the cursor.
        //
        // We loosen the end-check by `slack` chars on the same line to
        // accommodate completion at e.g. `x.` where the cursor sits just
        // past the recorded expression body — without slack, the body's
        // loc (`x`) ends before the cursor and we miss the enclosing
        // annotation hint.
        val slack = 3
        val expectedEntry = expectedTypeAt
            .filterKeys { loc ->
                val startsBefore = loc.startLine < cursorLine ||
                    (loc.startLine == cursorLine && loc.startCol <= cursorCol)
                val endsAfter = loc.endLine > cursorLine ||
                    (loc.endLine == cursorLine && loc.endCol + slack >= cursorCol)
                startsBefore && endsAfter
            }
            .entries
            .minByOrNull { (loc, _) ->
                // Span size — smaller = tighter enclosing scope.
                (loc.endLine - loc.startLine) * 1_000_000 + (loc.endCol - loc.startCol)
            }
        server.logInfo("[rank] enclosing entry=${expectedEntry?.key} expected=${expectedEntry?.value ?: "<none>"}")
        val expected = expectedEntry?.value ?: return items
        return rankAgainst(items, expected, candidateReturnTypes)
    }

    /**
     * Score each candidate against `expected`: a candidate whose known return
     * type unifies with `expected` sorts first (`0_`), others last (`5_`).
     * When `expected` is Str, `to_dbg_str` is also floated to the top — it
     * renders ANY value to a string, so in a string context it's always a
     * sensible pick even for a non-Str receiver.
     */
    private fun rankAgainst(
        items: List<CompletionItem>,
        expected: MezzeType,
        candidateReturnTypes: Map<String, MezzeType>,
    ): List<CompletionItem> {
        val expectsStr = expected is MezzeType.StringType
        // Methods that return Str by contract, regardless of receiver — the
        // universal string renderers. Their return type isn't always captured
        // in candidateReturnTypes (the precompiled inherent-impl body type can
        // come back as an unresolved tyvar), so recognize them by name so they
        // still float to the top of an interpolation / Str-expected context.
        val strRenderers = setOf("to_str", "to_dbg_str")
        return items.map { item ->
            val candType = candidateReturnTypes[item.label]
            val matches = when {
                expectsStr && item.label in strRenderers -> true
                candType == null -> false
                // A candidate whose return type is still a free var would unify
                // with ANYTHING (Int.abs : Int -> 'a at this stage), so it would
                // spuriously "match" every expected type and defeat the ranking.
                // Require the candidate's return type to be concrete.
                candType is MezzeType.VarType -> false
                else -> com.mezze.language.typechecker.solving.unify(candType, expected) is
                    com.mezze.language.typechecker.solving.UnificationResult.Success
            }
            item.apply { sortText = "${if (matches) "0" else "5"}_${label}" }
        }
    }

    /**
     * True when the character position at the end of `beforeCursor` sits inside
     * an unclosed string-interpolation hole `"…$( … <cursor>`. We track quote
     * state so a `$(` inside a completed string (or after the closing `)`)
     * doesn't count. Purely lexical — good enough to know the hole wants a Str.
     */
    private fun isInsideStringInterpolation(beforeCursor: String): Boolean {
        var inString = false
        var depth = 0          // open `$(` interpolation parens
        var i = 0
        while (i < beforeCursor.length) {
            val c = beforeCursor[i]
            when {
                c == '\\' && inString -> i++   // skip escaped char
                c == '"' -> inString = !inString
                inString && c == '$' && i + 1 < beforeCursor.length && beforeCursor[i + 1] == '(' -> {
                    depth++; i++
                }
                depth > 0 && c == ')' -> depth--
            }
            i++
        }
        return depth > 0
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
    private fun findReceiverType(name: String, result: TypeCheckResult, position: Position, state: DocumentState): MezzeType? {
        val currentUri = state.uri
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

        // Check local bindings (do-block lets, match/lambda bindings) in the
        // current typed module.
        val local = findLocalBindingType(name, result.typedModule, position.line + 1)
        // A CONCRETE local hit wins immediately. But a VarType hit is useless
        // for dot-completion (it can't be filtered/ranked) — it happens when the
        // receiver line is mid-edit (`x.`), a parse error corrupts the enclosing
        // decl's inference, and the local's stored type stays an unresolved
        // tyvar. In that case, prefer the last-good module where the same
        // binding was fully solved (`x : Int`). Same reason as #159 — but there
        // the current lookup returned null; here it returns a VarType.
        if (local != null && local !is MezzeType.VarType) return local

        // #159: the receiver line the user is editing (`y.`) is often a HARD
        // parse error inside a do-block ("must end with a result expression"),
        // so the enclosing decl is missing OR only partially inferred (a bare
        // tyvar) in the current typed module. Fall back to the last successful
        // typed module — before the `.` was typed, `let y = …` was valid and
        // carried y's concrete type. This is what lights up `y.` completion
        // mid-edit for do-block / match / lambda locals.
        val lastGood = lastSuccessfulResult[currentUri]?.typedModule
        if (lastGood != null && lastGood !== result.typedModule) {
            findLocalBindingType(name, lastGood, position.line + 1)
                ?.takeIf { it !is MezzeType.VarType }
                ?.let { return it }
        }

        // Last resort — strip-and-reparse. On a COLD broken `x.` (opened
        // already broken, no editing history so nothing is cached), the dangling
        // `.` is a parse error and no typed info for `x` exists anywhere. But the
        // fix is mechanical: delete the `.` the user just typed, re-typecheck
        // that snapshot in memory, and read `x`'s type from it — `"…$(x)…"` is
        // only a TYPE error (x IS Int), so it parses and types fine. Best-effort:
        // any failure yields null and we fall through to the VarType/empty path.
        stripDotAndResolveReceiver(name, state, position)
            ?.takeIf { it !is MezzeType.VarType }
            ?.let { return it }

        // Nothing concrete anywhere — return the VarType hit (if any) so the
        // caller still knows the binding EXISTS, even if unresolved.
        return local
    }

    /**
     * #159/interp cold-broken recovery: delete the `.` at the cursor from the
     * current buffer, re-typecheck that snapshot in memory, and resolve the
     * receiver `name`'s type from the fresh module. Returns null on any failure
     * (still a parse error, name not found, etc.). Cheap enough for a completion
     * request — only runs when every cached lookup already failed.
     */
    private fun stripDotAndResolveReceiver(
        name: String,
        state: DocumentState,
        position: Position,
    ): MezzeType? {
        return try {
            val lines = state.content.lines().toMutableList()
            val li = position.line
            if (li !in lines.indices) return null
            val line = lines[li]
            // The `.` is the char immediately before the cursor (we're at `x.|`).
            val dotIdx = position.character - 1
            if (dotIdx < 0 || dotIdx >= line.length || line[dotIdx] != '.') return null
            // COMPLETE the dangling dot into a valid, side-effect-free method
            // call so the module TYPECHECKS (Success), not just parses. This
            // matters because a forward-referenced polymorphic receiver
            // (`let x = help{…}`) is only baked to its concrete type in a fully
            // SOLVED module — a mere type error (e.g. `$(x)` needing Str) leaves
            // it an unresolved tyvar. `to_dbg_str {}` works on ANY type and
            // returns Str (so it satisfies string-interpolation too), making the
            // synthesized edit typecheck regardless of the receiver.
            lines[li] = line.substring(0, dotIdx) + ".to_dbg_str {}" + line.substring(dotIdx + 1)
            val stripped = lines.joinToString("\n")

            val fileName = uriToPath(state.uri)?.fileName?.toString()
            val parsed = parser.parseWithRecovery(stripped, file = fileName).module
            val workspaceRoot = server.getWorkspaceRoot()
            val projectRoot = if (workspaceRoot != null) Paths.get(workspaceRoot)
                else uriToPath(state.uri)?.parent ?: Paths.get(".")
            val typed = when (val r = typeChecker.checkParsedModule(parsed, projectRoot, null, enablePrelude = true)) {
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success -> r.typedModule
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Failure -> r.typedModule
            } ?: return null

            // Prefer the type at the RECEIVER'S USE SITE. The stripped `$(x)`
            // still USES `x` (right where the dot was), and that TypedVar carries
            // the RESOLVED type (Int) — whereas the do-block `let x = help{…}`
            // binding stores an unresolved tyvar for a forward-ref poly call
            // (#159/#160 family). Look up the type at the char just before where
            // the dot was (dotIdx, now the last char of `x`).
            val useLine = li + 1
            val useCol = dotIdx  // 1-based col of the receiver's last char (`x` ends just before the dot)
            (TypedAstLookup.findAtPosition(typed, useLine, useCol) as? TypedAstLookup.LookupResult.Expr)
                ?.expr?.type
                ?.let { if (it is MezzeType.SuspendType) it.valueType else it }
                ?.takeIf { it !is MezzeType.VarType }
                ?.let { return it }

            // Fallbacks: top-level decl, then local binding's stored type.
            typed.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
                .firstOrNull { it.name == name }
                ?.scheme?.type
                ?.takeIf { it !is MezzeType.VarType }
                ?.let { return it }
            findLocalBindingType(name, typed, position.line + 1)?.takeIf { it !is MezzeType.VarType }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Expression-chain analogue of [stripDotAndResolveReceiver]. For a
     * dangling dot after a call/index/group (`x.is_odd {}.`, `nums.first {}.`)
     * the receiver has no single name to look up — so we COMPLETE the dot into
     * a valid, side-effect-free `.to_dbg_str {}` call, typecheck the whole
     * module to Success, then read the type of the enclosing expression that
     * ENDS at the original closing delimiter (the receiver chain's result).
     *
     * This recovers the type even when the current buffer is a hard parse
     * error (dangling `.`) or a type error (chain result isn't Str inside an
     * interpolation) — the same states that leave the live typed module
     * unusable for this lookup.
     */
    private fun stripDotAndResolveExpressionReceiver(
        state: DocumentState,
        position: Position,
    ): MezzeType? {
        return try {
            val lines = state.content.lines().toMutableList()
            val li = position.line
            if (li !in lines.indices) return null
            val line = lines[li]
            val dotIdx = position.character - 1
            if (dotIdx < 0 || dotIdx >= line.length || line[dotIdx] != '.') return null
            val closerIdx = dotIdx - 1
            if (closerIdx < 0) return null
            val closer = line[closerIdx]
            if (closer != '}' && closer != ')' && closer != ']') return null

            // Complete the dangling dot into a valid Str-returning call so the
            // module TYPECHECKS (Success). `to_dbg_str {}` works on any type.
            lines[li] = line.substring(0, dotIdx) + ".to_dbg_str {}" + line.substring(dotIdx + 1)
            val stripped = lines.joinToString("\n")

            val fileName = uriToPath(state.uri)?.fileName?.toString()
            val parsed = parser.parseWithRecovery(stripped, file = fileName).module
            val workspaceRoot = server.getWorkspaceRoot()
            val projectRoot = if (workspaceRoot != null) Paths.get(workspaceRoot)
                else uriToPath(state.uri)?.parent ?: Paths.get(".")
            val typed = when (val r = typeChecker.checkParsedModule(parsed, projectRoot, null, enablePrelude = true)) {
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success -> r.typedModule
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Failure -> r.typedModule
            } ?: return null

            // The receiver chain still ends at the same closing delimiter
            // (col closerIdx, 0-based → closerIdx+1 1-based). Read the widest
            // expr ending there — the call whose result we want.
            val endLine = li + 1
            val endCol = closerIdx + 1
            TypedAstLookup.findExprEndingAt(typed, endLine, endCol)
                ?.type
                ?.let { if (it is MezzeType.SuspendType) it.valueType else it }
                ?.takeIf { it !is MezzeType.VarType }
        } catch (_: Exception) {
            null
        }
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

        val charBeforeDot = dotIdx - 1
        // Is the receiver a call/index/group ending in a closing delimiter
        // (`x.is_odd {}.`, `nums.first {}.`, `(a + b).`, `xs[0].`)? If so the
        // receiver type is the ENCLOSING expression that ENDS at that
        // delimiter — its result type — NOT the innermost node there (which
        // findAtPosition returns: the argument record `{ it }` or the group's
        // inner expr). findExprEndingAt picks the widest expr ending at the
        // delimiter (the call), giving us the call's result type.
        val endsInCloser = charBeforeDot >= 0 &&
            line[charBeforeDot].let { it == '}' || it == ')' || it == ']' }

        // 1. Typed-AST lookup.
        val typedModule = result.typedModule ?: lastSuccessfulResult[state.uri]?.typedModule
        if (typedModule != null && charBeforeDot >= 0) {
            val lookupLine = position.line + 1
            val lookupCol = charBeforeDot + 1
            if (endsInCloser) {
                val enclosing = TypedAstLookup.findExprEndingAt(typedModule, lookupLine, lookupCol)
                val t = enclosing?.type
                if (t != null && t !is MezzeType.VarType) return t
                // Enclosing call resolved to a tyvar (mid-edit / forward-ref /
                // type-error state) — complete-the-dot to recover it, same as
                // the bare-name path.
                stripDotAndResolveExpressionReceiver(state, position)?.let { return it }
            } else {
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

    /**
     * Keyword completions. Each keyword inserts a snippet with tabstops so
     * the user can fill the holes in order with Tab. We gate categories by
     * whether the cursor sits at a top-level position (fresh statement at
     * column 0) vs inside an expression — typing `effect` mid-expression
     * makes no sense, so we don't surface it there.
     *
     * Top-level keywords:  let / pub / use / type / effect / ability / impl
     * Expression keywords: if / match / do / perform / let-binding
     * Modifier keywords:   as / where / with / for / in / then / else / is
     *
     * The modifier set is exposed as plain identifiers — they don't have
     * meaningful snippet forms standalone.
     */
    /**
     * Conservative check for "cursor at the start of a fresh top-level
     * statement". Used to gate top-level-only keyword completions
     * (effect / ability / impl / type / use / pub). We say yes when:
     *  - the line is at indentation 0 (column 0 prefix is whitespace-free)
     *  - the prefix on the cursor line contains only word chars (so we're
     *    on a bare identifier-typing position, not after `.` or inside
     *    `f { ... }`)
     *  - the line isn't inside a do…end block — best-effort by checking
     *    that there's no unmatched `do` on the lines above without a
     *    matching `end`. For now we accept some false-positives — a
     *    keyword item on the menu mid-block is just noise the user can
     *    ignore.
     */
    private fun isAtTopLevelStart(state: DocumentState, pos: Position): Boolean {
        val lines = state.content.split('\n')
        val line = lines.getOrNull(pos.line) ?: return false
        val cursor = pos.character.coerceIn(0, line.length)
        val before = line.substring(0, cursor)
        // Lines that start with whitespace are inside something — not
        // top-level.
        if (before.isNotEmpty() && before.first().isWhitespace()) return false
        // Allow letters / underscores in the prefix (the user is typing the
        // keyword name itself). Bail on `.`, `{`, `(`, `=` etc.
        if (before.any { !it.isLetterOrDigit() && it != '_' }) return false
        return true
    }

    /**
     * Best-effort: is the cursor in a TYPE-ANNOTATION position, where only type
     * names make sense — after `:` (field/param type, `let f : …`) or `->`
     * (return type)? We scan the current line's prefix back over the identifier
     * being typed and any type-application args, and check the token that
     * introduced this type slot.
     *
     * Conservative by design — only fires on clear cases so we never wrongly
     * hide value completions:
     *   - `let f : Us|`            → yes (`:` at top-level decl)
     *   - `{ x: In|, y: … }`       → yes (record/param field type)
     *   - `-> Boo|`                → yes (return type)
     *   - `let x = foo :|`         → NO (that's a value; `:` after `=` is rare/none)
     *   - inside a string / after `=` on the line → NO
     */
    private fun isInTypeAnnotationPosition(state: DocumentState, pos: Position): Boolean {
        val line = state.content.split('\n').getOrNull(pos.line) ?: return false
        val cursor = pos.character.coerceIn(0, line.length)
        var before = line.substring(0, cursor)
        // Not inside a string, and not on the value side of an `=` on this line
        // (a record field VALUE or a let body — those want value completions).
        if (before.count { it == '"' } % 2 == 1) return false
        // Strip the identifier + any `.`/type-arg chars currently being typed so
        // we can look at the introducing token.
        before = before.trimEnd { it.isLetterOrDigit() || it == '_' || it == '\'' || it == '.' || it == ' ' }
        // A `->` introduces a return type ONLY inside a type annotation — i.e.
        // when a type colon appears earlier on the line (`let f : {} -> Ret`).
        // A bare `{} -> body` is a LAMBDA whose `->` precedes an EXPRESSION, not
        // a type; classifying that as a type slot wrongly hides value completions
        // (e.g. `let main = {} -> hel|` where the user wants `helper`).
        if (before.endsWith("->")) {
            val colonIdx = before.indexOf(':')
            val hasTypeColon = colonIdx >= 0 && (colonIdx == 0 || before[colonIdx - 1] != ':')
            val hasEquals = before.contains('=')  // `=` means a value body, not a sig
            return hasTypeColon && !hasEquals
        }
        // A `:` introduces a field/param/binding type — but only when it's a
        // type colon, not `::` (module qualifier) and not preceded by `=` on the
        // line (value context). Also exclude the `?:`/ternary-ish shapes (none
        // in Mezze, but cheap to guard).
        if (before.endsWith(":") && !before.endsWith("::")) {
            // If there's an `=` after the most recent `{` (i.e. we're inside a
            // record VALUE like `{ x = foo : … }`), don't treat as a type slot.
            val lastBrace = before.lastIndexOf('{')
            val eqAfterBrace = before.lastIndexOf('=') > lastBrace
            // `let f : …` / `{ x: … }` have no `=` before the `:` on the field.
            return !eqAfterBrace || lastBrace < 0
        }
        return false
    }

    /**
     * Op-name completions inside an impl body. When the cursor is on a fresh
     * field-start line inside `impl X for Y where { … }`, suggest the ops of
     * the ability/effect X that aren't implemented yet, as `op = { … } -> …`
     * snippets. Returns null when the cursor isn't in an impl body (so the
     * caller falls through to the normal completion paths).
     */
    private fun getImplBodyOpCompletions(state: DocumentState, position: Position): List<CompletionItem>? {
        val pm = state.parsedModule ?: return null
        val lines = state.content.lines()
        val lineText = lines.getOrNull(position.line) ?: return null
        // Only at a field-start position: nothing but an identifier prefix
        // before the cursor on this line (no `=`, `:`, `{`, `(` yet).
        val before = lineText.substring(0, position.character.coerceIn(0, lineText.length))
        if (!Regex("^\\s*[a-z_][a-zA-Z0-9_]*$|^\\s*$").matches(before)) return null
        val prefix = before.trim()
        val lineNo = position.line + 1

        // Find the enclosing impl decl by loc range, resolve its ability/effect
        // ops, and drop the ones already written in the body.
        for (decl in pm.decls) {
            val (declLoc, opDecls, implementedNames) = when (decl) {
                is com.mezze.language.parsedast.ParsedImplAbilityDecl -> {
                    val ability = pm.decls.filterIsInstance<ParsedAbilityDecl>()
                        .find { it.name == decl.abilityName } ?: continue
                    Triple(decl.loc, ability.operations.map { it.name }, decl.operations.map { it.name }.toSet())
                }
                is ParsedPerformerDecl -> {
                    val effect = pm.decls.filterIsInstance<ParsedEffectDecl>()
                        .find { it.name == decl.effect.localName } ?: continue
                    Triple(decl.loc, effect.operations.map { it.name }, decl.operations.map { it.name }.toSet())
                }
                else -> continue
            }
            if (lineNo < declLoc.startLine || lineNo > declLoc.endLine) continue

            val items = mutableListOf<CompletionItem>()
            for (opName in opDecls) {
                if (opName in implementedNames) continue
                if (prefix.isNotEmpty() && !opName.startsWith(prefix)) continue
                items.add(CompletionItem(opName).apply {
                    kind = CompletionItemKind.Method
                    detail = "impl op"
                    insertText = "$opName = {\${1:it}} -> \${0:body}"
                    insertTextFormat = InsertTextFormat.Snippet
                })
            }
            return items
        }
        return null
    }

    /**
     * Ability/effect NAME completions in a type signature — after `with ` (an
     * ability constraint) or inside an unclosed `<…>` (an effect row). Returns
     * null when the cursor isn't in either position. Suggests locally-declared
     * and imported ability/effect names.
     */
    private fun getAbilityEffectNameCompletions(state: DocumentState, position: Position): List<CompletionItem>? {
        val lines = state.content.lines()
        val lineText = lines.getOrNull(position.line) ?: return null
        val before = lineText.substring(0, position.character.coerceIn(0, lineText.length))
        // Trailing partial name (may be empty right after `with `/`<`).
        val prefix = Regex("[A-Za-z_][A-Za-z0-9_]*$").find(before)?.value ?: ""
        val head = before.dropLast(prefix.length)
        val inConstraint = Regex("\\bwith\\b[^<>]*$").containsMatchIn(head)
        val inEffectRow = head.lastIndexOf('<') > head.lastIndexOf('>')
        if (!inConstraint && !inEffectRow) return null

        val pm = state.parsedModule
        val names = LinkedHashSet<String>()
        pm?.decls?.forEach {
            when (it) {
                is ParsedAbilityDecl -> names.add(it.name)
                is ParsedEffectDecl -> names.add(it.name)
                else -> {}
            }
        }
        state.typeCheckResult?.resolvedImports?.forEach {
            if (it.kind == ImportKind.ABILITY || it.kind == ImportKind.EFFECT) names.add(it.localName)
        }
        state.typeCheckResult?.importedAbilities?.keys?.forEach { names.add(it) }

        return names
            .filter { prefix.isEmpty() || it.startsWith(prefix) }
            .map { name ->
                CompletionItem(name).apply {
                    kind = CompletionItemKind.Interface
                    detail = if (inConstraint) "ability" else "effect"
                }
            }
    }

    private fun getKeywordCompletions(atTopLevel: Boolean): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()

        fun snippet(label: String, body: String, detail: String) {
            items.add(CompletionItem(label).apply {
                kind = CompletionItemKind.Keyword
                this.detail = detail
                insertText = body
                insertTextFormat = InsertTextFormat.Snippet
            })
        }
        fun plain(label: String) {
            items.add(CompletionItem(label).apply { kind = CompletionItemKind.Keyword })
        }

        if (atTopLevel) {
            snippet("let",
                "let \${1:name} = \${2:value}",
                "let binding")
            snippet("pub let",
                "pub let \${1:name} = \${2:value}",
                "exported let")
            snippet("use",
                "use \${1:module::path}",
                "import")
            snippet("type",
                "type \${1:Name} = { \${2:field}: \${3:Type} }",
                "type declaration")
            snippet("effect",
                "effect \${1:Name} {\n    \${2:op}: \${3:ParamType} -> \${4:ReturnType}\n}",
                "effect declaration")
            snippet("ability",
                "ability \${1:Name} for \${2:'a} where {\n    \${3:method}: \${2:'a} -> \${4:ReturnType}\n}",
                "ability declaration")
            snippet("impl",
                "impl \${1:Ability} for \${2:Type} where {\n    \${3:method} = {\${4:it}} -> \${5:body}\n}",
                "ability impl")
            snippet("impl effect",
                "impl \${1:Effect} for \${2:Performer} where {\n    \${3:op} = {\${4:args}} -> \${5:body}\n}",
                "effect performer impl")
        }

        // Expression-form keyword snippets. These can appear in let-RHS,
        // do-block statements, lambda bodies, etc.
        snippet("if",
            "if \${1:cond} then \${2:then_expr} else \${3:else_expr}",
            "conditional")
        snippet("match",
            "match \${1:expr} is\n| \${2:pattern} -> \${3:result}",
            "pattern match")
        snippet("do",
            "do\n    \$0\nend",
            "do block")
        snippet("perform",
            "perform \${1:handler} in do\n    \$0\nend",
            "perform with handler")
        // Bare modifiers — no snippet bodies make sense for these standalone.
        listOf("as", "where", "with", "for", "in", "then", "else", "is").forEach(::plain)

        return items
    }

    private fun getBindingCompletions(state: DocumentState): List<CompletionItem> {
        val typedModule = state.typeCheckResult?.typedModule
        val parsedModule = state.parsedModule
        val items = mutableListOf<CompletionItem>()

        // Add let bindings (functions and values) from TypedModule
        typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.forEach { decl ->
                val ty = decl.scheme.type
                items.add(CompletionItem(decl.name).apply {
                    kind = if (ty is MezzeType.FunctionType) CompletionItemKind.Function else CompletionItemKind.Variable
                    detail = prettyPrintTypeWithAliases(ty, state.typeAliases)
                    // Resolve key: the client calls completionItem/resolve with
                    // this back, and we look up the doc-comment lazily (see
                    // resolveCompletionItem). Only local-file decls carry it —
                    // stdlib items resolve by name via the store.
                    data = completionResolveKey(state.uri, decl.name)
                    val snippet = callInsertSnippet(decl.name, ty)
                    if (snippet != null) {
                        insertText = snippet
                        insertTextFormat = InsertTextFormat.Snippet
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
                if (import.kind == ImportKind.FUNCTION) {
                    val snippet = callInsertSnippet(import.localName, import.type)
                    if (snippet != null) {
                        insertText = snippet
                        insertTextFormat = InsertTextFormat.Snippet
                    }
                }
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
                // Pin locals/params to the top tier explicitly — they're the
                // closest scope and almost always what the user means. (A bare
                // Variable kind alone would tie with top-level value bindings.)
                sortText = "1_${b.name}"
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

        // If it's a variant type, suggest constructors. We extend each item's
        // insertText with ` -> ${N}` so accepting the completion drops a full
        // match arm rather than a bare pattern — the next tabstop is the body
        // hole, ready for the user to fill in.
        val patterns = getVariantConstructorCompletionsForType(scrutineeType, state)
        return patterns.map { item ->
            // Don't extend the trailing wildcard "_" item — it already has
            // its own `-> ${1:}` form.
            if (item.label == "_") return@map item
            val original = item.insertText ?: item.label
            // Renumber tabstops in the original snippet so the body tabstop
            // ends up last. We pick `9` as a body tabstop number that won't
            // collide with the typical 1..3 used inside payload patterns.
            item.apply {
                insertText = "$original -> \${0}"
                insertTextFormat = InsertTextFormat.Snippet
            }
        }
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
     * Unwrap wrapper types (like SuspendType) to get the underlying type.
     */
    private fun unwrapType(type: MezzeType): MezzeType {
        return when (type) {
            is MezzeType.SuspendType -> unwrapType(type.valueType)
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

    /** Build the data payload the client hands back to completionItem/resolve. */
    private fun completionResolveKey(uri: String, name: String): Map<String, String> =
        mapOf("uri" to uri, "name" to name)

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

        val doc = findDocForDecl(state.parsedModule, name) ?: findDocFromStore(name)
        val sig = item.detail?.takeIf { it.isNotBlank() && it != "type" && it != "effect" && it != "ability" }
        val body = buildString {
            if (sig != null) append("```mezze\n$name : $sig\n```")
            if (doc != null) {
                if (isNotEmpty()) append("\n\n")
                append(renderDocMarkdown(doc))
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
                        val target = decl.targetTypes.joinToString(", ") { formatTypeAnnotation(it) }
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
                    collectReferencesInModule(typedModule, decl.name, uri, locs)
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
            val word = extractWordAt(lines[position.line], position.character) ?: return@supplyAsync emptyList()

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
            val word = extractWordAt(lines[params.position.line], params.position.character)
                ?: return@supplyAsync null
            if (word in mezzeKeywordsForRenameGate) return@supplyAsync null
            val typedModule = (state.typeCheckResult ?: lastSuccessfulResult[uri])?.typedModule
                ?: return@supplyAsync null

            val locs = mutableListOf<Location>()
            collectReferencesInModule(typedModule, word, uri, locs)
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
                        com.mezze.language.typechecker.validation.UnusedImportLinter
                            .unusedItemsFor(state.parsedModule!!, useDecl).toSet()
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
            return if (member.member == "not") member.receiver else null
        }

        val replacement: String? = when (node) {
            // (b.not {}).not {} -> b   (unwrap the double negation)
            is ParsedFunctionCall -> {
                val inner = boolNotReceiver(node)?.let { boolNotReceiver(it) }
                if (inner != null) srcOf(unwrapParens(inner).loc) else null
            }

            // x == True -> x ; x == False -> x.not {}  (either side)
            is ParsedBinaryOp -> if (node.op == "==") {
                when {
                    isBoolLit(node.right, "True") -> srcOf(node.left.loc)
                    isBoolLit(node.left, "True") -> srcOf(node.right.loc)
                    isBoolLit(node.right, "False") -> "(${srcOf(node.left.loc)}).not {}"
                    isBoolLit(node.left, "False") -> "(${srcOf(node.right.loc)}).not {}"
                    else -> null
                }
            } else null

            // if b then True else False -> b
            is ParsedIf -> if (isBoolLit(node.thenBranch, "True") && isBoolLit(node.elseBranch, "False"))
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
                val itParam = if (paramType != null && formatTypeAnnotation(paramType).contains("it")) "it" else "it"
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
     * boundaries (multi-line extractions are punted to V2). The selected
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

    /**
     * Render a type scheme's ability constraints as a ` with Eq 'a, Ord 'b`
     * suffix (empty string when there are none). Ability names come from the
     * constraint's hash — resolved first against the current typed module's
     * ability decls, then the store's ability index (covers stdlib). A
     * constraint whose ability we can't name is skipped rather than shown as a
     * raw hash.
     */
    private fun renderSchemeConstraints(
        scheme: com.mezze.language.typedast.TypeScheme,
        typedModule: com.mezze.language.typedast.TypedModule?,
    ): String {
        if (scheme.abilityConstraints.isEmpty()) return ""
        val nameByHash = HashMap<Any, String>()
        typedModule?.abilityDecls?.forEach { nameByHash[it.hash] = it.name }
        try {
            projectStore?.getDeclsByKind(com.mezze.language.store.DeclKind.ABILITY)?.forEach {
                nameByHash.putIfAbsent(it.hash, it.declName)
            }
        } catch (_: Exception) {}

        val rendered = scheme.abilityConstraints.mapNotNull { c ->
            val name = nameByHash[c.abilityHash] ?: return@mapNotNull null
            val args = if (c.typeArgs.isNotEmpty())
                " " + c.typeArgs.joinToString(" ") { prettyPrintType(it) }
            else ""
            "$name$args"
        }
        return if (rendered.isEmpty()) "" else " with ${rendered.joinToString(", ")}"
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

    private fun typeConstructorName(t: com.mezze.language.ast.MezzeType): String? = when (t) {
        is com.mezze.language.ast.MezzeType.TypeApplication -> t.constructor
        is com.mezze.language.ast.MezzeType.RecordType -> null
        is com.mezze.language.ast.MezzeType.FunctionType -> null
        else -> t::class.simpleName?.removeSuffix("Type")
    }

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

            // 1c. Effect inlay hints — the uniquely-Mezze one. When a top-level
            //     function's inferred type carries an effect row (it's
            //     effectful), show `<Io, Exception>` as a ghost hint after its
            //     signature line. This surfaces effects the user never wrote —
            //     no generic LSP can do this; it needs Mezze's effect inference.
            for (decl in typedModule.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()) {
                val parsedDecl = state.parsedModule?.decls
                    ?.filterIsInstance<com.mezze.language.parsedast.ParsedLetDecl>()
                    ?.find { it.isSimpleBinding && it.variableName == decl.name }
                // Skip when the signature is written out — the effects are then
                // already visible in source.
                if (parsedDecl?.typeAnnotation != null) continue
                emitEffectRowHint(decl, sourceLines, hints)
            }

            // 2. Per-segment chain hints + nested-let hints inside do/blocks.
            for (decl in typedModule.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()) {
                collectPipeHints(decl.value, hints, state.typeAliases, sourceLines)
            }

            hints
        }
    }

    /**
     * Emit a `<Io, Exception>` effect-row inlay hint for an effectful top-level
     * function. The effect row lives on the function type (FunctionType.effects)
     * — or on a SuspendType-wrapped body. Pure functions (empty/closed row)
     * get nothing. Anchored at the end of the decl's signature line, before the
     * body begins.
     */
    private fun emitEffectRowHint(
        decl: com.mezze.language.typedast.TypedLetDecl,
        sourceLines: List<String>,
        hints: MutableList<InlayHint>,
    ) {
        val fnType = decl.scheme.type as? MezzeType.FunctionType ?: return
        val row = fnType.effects ?: return
        if (row.isPure) return
        val names = row.allEffectNames.filter { it.isNotBlank() }.sorted()
        if (names.isEmpty()) return
        val label = "<${names.joinToString(", ")}>"

        // Anchor at end of the signature line: the line holding `->` on the
        // decl's first line, or the decl's start line as a fallback.
        val startIdx = decl.loc.startLine - 1
        val sigLineIdx = (startIdx until minOf(sourceLines.size, startIdx + 3))
            .firstOrNull { sourceLines.getOrNull(it)?.contains("->") == true } ?: startIdx
        val lineText = sourceLines.getOrNull(sigLineIdx) ?: return
        val col = lineText.length

        hints.add(InlayHint().apply {
            position = Position(sigLineIdx, col)
            setLabel(" $label")
            kind = InlayHintKind.Type
            paddingLeft = true
        })
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
            val oldName = extractWordAt(lines[position.line], position.character) ?: return@supplyAsync null

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
