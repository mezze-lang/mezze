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
     */
    fun connect(client: LanguageClient) {
        this.client = client

        // Initialize codebase if in project mode
        val workspaceRoot = server.getWorkspaceRoot()
        server.logInfo("Workspace root for codebase: $workspaceRoot")
        if (workspaceRoot != null) {
            val rootFile = File(workspaceRoot)
            server.logInfo("Root file exists: ${rootFile.exists()}, isDirectory: ${rootFile.isDirectory}")
            server.logInfo("Looking for Mezze.toml at: ${File(rootFile, "Mezze.toml").absolutePath}")
            server.logInfo("Mezze.toml exists: ${File(rootFile, "Mezze.toml").exists()}")
            val projectRoot = SQLiteMezzeStore.findProjectRoot(rootFile)
            if (projectRoot != null) {
                try {
                    projectStore = SQLiteMezzeStore.forProject(projectRoot)
                    projectRootDir = projectRoot

                    // Slice C: copy bundled stdlib seed into project cache
                    // so LSP queries (workspace symbols, find-references)
                    // see stdlib uniformly. Idempotent on subsequent opens.
                    com.mezze.language.codebase.StdlibSeed.seedIfNeeded(projectStore!!)

                    // Install parsed-modules cache hook so ModuleResolver skips
                    // the parser when the store has a row matching this file's
                    // bytes. Falls through to a fresh parse on miss.
                    com.mezze.language.parser.ParsedModuleCacheHolder.set(
                        com.mezze.language.codebase.StoreParsedModuleCache(projectStore!!)
                    )
                    val (pkg, entry) = readMezzeTomlEntries(projectRoot)
                    packageName = pkg
                    entryFilePath = entry?.let { File(projectRoot, it).absolutePath }
                    server.logInfo("Using project store at ${SQLiteMezzeStore.resolveCacheFile(projectRoot).absolutePath} (package=$packageName, entry=$entry)")

                    // Hydrate typechecker cache from the shared store. If
                    // `mez dev` (or a previous LSP session) typed modules
                    // here, we get them for free — the next typecheck call
                    // hits cache instead of re-typechecking.
                    val store = projectStore!!
                    val stats = StoreHydration.hydrateTypedModules(store) { modulePath, blob ->
                        try {
                            val v2 = CodebaseSerialization.deserializeTypedModule(blob)
                            com.mezze.language.typechecker.module.ModuleTypeChecker
                                .hydrateFromV2Module(modulePath, v2)
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    server.logInfo(
                        "Hydrated typed modules from store: ${stats.hits} hit, ${stats.misses} miss (${stats.elapsedMs}ms)"
                    )
                } catch (e: Exception) {
                    server.logError("Failed to create store: ${e.message}")
                    e.printStackTrace()
                }
            } else {
                server.logInfo("No Mezze.toml found, using in-memory mode")
            }
        }
    }

    /**
     * Shutdown and cleanup resources.
     */
    fun shutdown() {
        projectStore?.close()
        documents.clear()
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

        // didChange ships the in-memory edit buffer — these bytes have
        // NOT been written to disk yet. Diagnostics, hover, completions
        // all run from the in-memory result; the store stays untouched
        // until the user saves.
        val state = analyzeDocument(uri, content, version, persistToStore = false)
        documents[uri] = state

        // Publish diagnostics
        publishDiagnostics(uri, state.diagnostics)
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

            val result = typeChecker.checkParsedModule(parsedModule, projectRoot, null)

            typeAliases = typeChecker.getTypeAliases()

            when (result) {
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success -> {
                    // Collect warnings
                    result.warnings.forEach { warning ->
                        val line = warning.loc?.startLine?.minus(1) ?: 0
                        val column = warning.loc?.startCol?.minus(1) ?: 0
                        diagnostics.add(createDiagnostic(
                            message = warning.message,
                            severity = DiagnosticSeverity.Warning,
                            line = line,
                            column = column
                        ))
                    }

                    typeCheckResult = TypeCheckResult(
                        resolvedImports = buildResolvedImports(result.importContext),
                        importedAbilities = result.importContext?.importedAbilities ?: emptyMap(),
                        importedEffects = result.importContext?.importedEffects ?: emptyMap()
                    )
                    lastSuccessfulResult[uri] = typeCheckResult!!

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
                    if (persistToStore && store != null && typedMod != null && filePath != null && rootDir != null && pkgName != null) {
                        // Use ModulePath.derive so the modulePath we write
                        // matches what `mez dev` writes for the same file —
                        // `(modulePath, sourceHash)` rows are then shared.
                        // If the file lives outside the project's src/, skip
                        // (caller is editing a scratch file; not our concern).
                        val derived = com.mezze.language.codebase.ModulePath
                            .derive(rootDir, filePath.toFile(), pkgName, entryFilePath)
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
                        val line = error.loc?.startLine?.minus(1) ?: 0
                        val column = error.loc?.startCol?.minus(1) ?: 0
                        diagnostics.add(createDiagnostic(
                            message = error.message,
                            severity = DiagnosticSeverity.Error,
                            line = line,
                            column = column
                        ))
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
     * Create a diagnostic from a recovery error.
     */
    private fun createDiagnosticFromRecoveryError(error: TypeChecker.TypeCheckError): Diagnostic {
        val loc = error.loc
        return createDiagnostic(
            message = error.message,
            severity = DiagnosticSeverity.Error,
            line = (loc?.startLine ?: 1) - 1,
            column = (loc?.startCol ?: 1) - 1,
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
        code: String? = null
    ): Diagnostic {
        val range = Range(
            Position(maxOf(0, line), maxOf(0, column)),
            Position(maxOf(0, line), maxOf(0, endColumn ?: (column + 1)))
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
                        is com.mezze.language.typedast.TypedVar -> codeBlock("${expr.name} : $typeStr")
                        is com.mezze.language.typedast.TypedFieldAccess -> codeBlock(".${expr.field} : $typeStr")
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
        return null
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
                        // Variable reference — find where it was defined
                        when (val binding = expr.binding) {
                            is com.mezze.language.typedast.BindingRef.Local -> {
                                // Local variable — find the let binding in current file
                                val defLoc = findLocalBindingLoc(typedModule, expr.name)
                                if (defLoc != null) return listOf(locToLocation(state.uri, defLoc))
                            }
                            is com.mezze.language.typedast.BindingRef.ByHash -> {
                                // Imported — find the import declaration
                                val importLoc = findImportLoc(state, expr.name)
                                if (importLoc != null) return listOf(importLoc)
                            }
                            is com.mezze.language.typedast.BindingRef.Param -> {
                                // Lambda parameter — find the enclosing lambda
                                // For now, point to current position
                            }
                            is com.mezze.language.typedast.BindingRef.Capture -> {}
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

        // 3. Check imports — jump to the import source
        if (result != null) {
            val import = result.resolvedImports.find { it.localName == word }
            if (import != null) {
                val importLoc = findImportLoc(state, word)
                if (importLoc != null) return listOf(importLoc)
            }
        }

        return emptyList()
    }

    /** Find the Loc of a local binding by name in the TypedModule. */
    private fun findLocalBindingLoc(module: com.mezze.language.typedast.TypedModule, name: String): Loc? {
        // Check top-level let bindings
        for (decl in module.decls) {
            if (decl is com.mezze.language.typedast.TypedLetDecl && decl.name == name) {
                return decl.loc
            }
        }
        // TODO: search let bindings in do blocks (nested scope)
        return null
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

        // First item: complete ALL fields at once (most useful)
        val allFieldsSnippet = recordFields.mapIndexed { index, (fieldName, _) ->
            "$fieldName = \${${index + 1}:}"
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
     * Get dot-completions: when user types `x.`, suggest methods available on x's type.
     * Includes ability methods (eq, neq, lt, add, ...) and inherent methods.
     */
    private fun getDotCompletions(state: DocumentState, position: Position): List<CompletionItem> {
        val lines = state.content.lines()
        if (position.line >= lines.size) return emptyList()

        val line = lines[position.line]
        val beforeCursor = line.substring(0, minOf(position.character, line.length))

        // Check if cursor is after a dot: `identifier.` or `identifier.partial`
        val dotMatch = Regex("""(\w+)\.(\w*)$""").find(beforeCursor) ?: return emptyList()
        val receiverName = dotMatch.groupValues[1]
        val partialMethod = dotMatch.groupValues[2]  // May be empty

        // Use current type check result, or fall back to last successful one
        val result = state.typeCheckResult
            ?: lastSuccessfulResult[state.uri]
            ?: return emptyList()

        fun formatType(type: MezzeType): String {
            return prettyPrintTypeWithAliases(type, state.typeAliases)
        }

        // Find the receiver's type
        val receiverType = findReceiverType(receiverName, result, position) ?: return emptyList()
        // Unwrap SuspendedType
        val baseType = when (receiverType) {
            is MezzeType.SuspendedType -> receiverType.valueType
            else -> receiverType
        }

        val items = mutableListOf<CompletionItem>()
        val typeName = getTypeName(baseType)
        val addedMethods = mutableSetOf<String>()  // Deduplicate

        // 1. Collect ability methods available for this type
        // Use both imported abilities (from prelude) and local program abilities
        val localAbilities = state.parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>() ?: emptyList()
        val allAbilities = result.importedAbilities.values + localAbilities

        // Check builtin ability impls
        for (abilityDecl in allAbilities) {
            if (!isBuiltinAbilityForType(abilityDecl.name, baseType)) continue

            for (op in abilityDecl.operations) {
                if (op.name in addedMethods) continue
                // Use v2 parsed operation types
                val paramStr = formatTypeAnnotation(op.paramType)
                if (!paramStr.contains("it")) continue

                items.add(CompletionItem(op.name).apply {
                    kind = CompletionItemKind.Method
                    detail = "${abilityDecl.name}.${op.name}"
                    documentation = Either.forLeft("${formatTypeAnnotation(op.paramType)} -> ${formatTypeAnnotation(op.returnType)}")
                    insertText = "${op.name} {}"
                    insertTextFormat = InsertTextFormat.Snippet
                })
                addedMethods.add(op.name)
            }
        }

        // 2. Collect inherent methods from parsed module
        val parsedImpls = state.parsedModule?.decls?.filterIsInstance<ParsedImplAbilityDecl>() ?: emptyList()
        for (impl in parsedImpls) {
            if (impl.target !is ParsedImplTarget.Inherent) continue
            for (op in impl.operations) {
                if (op.name in addedMethods) continue
                items.add(CompletionItem(op.name).apply {
                    kind = CompletionItemKind.Method
                    detail = "inherent method"
                    insertText = "${op.name} {}"
                    insertTextFormat = InsertTextFormat.Snippet
                })
                addedMethods.add(op.name)
            }
        }

        // 3. Record field access
        if (baseType is MezzeType.RecordType) {
            for ((fieldName, fieldType) in baseType.fields) {
                if (fieldName in addedMethods) continue
                items.add(CompletionItem(fieldName).apply {
                    kind = CompletionItemKind.Field
                    detail = formatType(fieldType)
                })
            }
        }

        // 4. Unimported abilities — show methods from abilities that exist in stdlib
        //    but are NOT currently imported, with auto-import text edit
        val importedAbilityNames = allAbilities.map { it.name }.toSet()
        try {
            val precompiled = com.mezze.language.typechecker.PrecompiledStdlib
            for ((_, module) in precompiled.stdlibTypedModules) {
                for (abilityDecl in module.abilityDecls) {
                    if (abilityDecl.name in importedAbilityNames) continue
                    // Check if this ability has an impl for the receiver type
                    val hasImpl = module.abilityImpls.any { impl ->
                        impl.abilityHash == abilityDecl.hash &&
                        impl.targetTypes.any { targetType ->
                            matchesType(baseType, targetType)
                        }
                    } || isBuiltinAbilityForType(abilityDecl.name, baseType)

                    if (!hasImpl) continue

                    // Find the module path for the import
                    val modulePath = module.path

                    for (op in abilityDecl.operations) {
                        if (op.name in addedMethods) continue
                        val opParamStr = prettyPrintType(op.paramType)
                        if (!opParamStr.contains("it")) continue

                        // Determine the use statement to add
                        val importPath = "${modulePath}::${abilityDecl.name}"

                        items.add(CompletionItem(op.name).apply {
                            kind = CompletionItemKind.Method
                            detail = "${abilityDecl.name}.${op.name} (auto-import)"
                            documentation = Either.forLeft("${prettyPrintType(op.paramType)} -> ${prettyPrintType(op.returnType)}\n\nRequires: use $importPath")
                            insertText = "${op.name} {}"
                            insertTextFormat = InsertTextFormat.Snippet
                            // Lower sort priority — imported abilities come first
                            sortText = "z_${op.name}"
                            // Add auto-import as additional text edit
                            val importEdit = TextEdit(
                                Range(Position(0, 0), Position(0, 0)),
                                "use $importPath\n"
                            )
                            // Don't auto-import — user should explicitly accept via code action
                            // additionalTextEdits = listOf(importEdit)
                        })
                        addedMethods.add(op.name)
                    }
                }
            }
        } catch (_: Exception) {
            // PrecompiledStdlib may not be available in all contexts
        }

        // Filter by partial method name if user already typed some characters after the dot
        return if (partialMethod.isNotEmpty()) {
            items.filter { it.label.startsWith(partialMethod, ignoreCase = true) }
        } else {
            items
        }
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

            // Try semantic references via TypedAST — find all TypedVar with same name
            val typedModule = (state.typeCheckResult ?: lastSuccessfulResult[uri])?.typedModule
            if (typedModule != null) {
                collectReferencesInModule(typedModule, word, uri, locations)
            }

            // If TypedAST didn't find anything, fall back to text search
            if (locations.isEmpty()) {
                lines.forEachIndexed { lineIndex, lineContent ->
                    var index = 0
                    while (true) {
                        val found = lineContent.indexOf(word, index)
                        if (found < 0) break
                        val before = if (found > 0) lineContent[found - 1] else ' '
                        val after = if (found + word.length < lineContent.length) lineContent[found + word.length] else ' '
                        if (!isIdentifierChar(before) && !isIdentifierChar(after)) {
                            locations.add(Location(uri, Range(
                                Position(lineIndex, found),
                                Position(lineIndex, found + word.length)
                            )))
                        }
                        index = found + 1
                    }
                }
            }

            locations
        }
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

            // Check diagnostics for auto-import suggestions
            for (diagnostic in params.context.diagnostics) {
                val msg = diagnostic.message ?: continue

                // "Undefined variable or function: X" → suggest import
                if (msg.contains("Undefined variable") || msg.contains("not defined or not imported")) {
                    val nameMatch = Regex("'(\\w+)'|: (\\w+)$").find(msg)
                    val undefinedName = nameMatch?.groupValues?.firstOrNull { it.isNotEmpty() && it != msg }

                    if (undefinedName != null) {
                        // Search stdlib for matching exports
                        try {
                            val precompiled = com.mezze.language.typechecker.PrecompiledStdlib
                            for ((_, module) in precompiled.stdlibTypedModules) {
                                val hasExport = module.exports.containsKey(undefinedName)
                                if (hasExport) {
                                    val importText = "use ${module.path}::$undefinedName\n"
                                    val action = CodeAction("Import '$undefinedName' from ${module.path}")
                                    action.kind = CodeActionKind.QuickFix
                                    action.diagnostics = listOf(diagnostic)
                                    action.edit = WorkspaceEdit(mapOf(
                                        uri to listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), importText))
                                    ))
                                    actions.add(Either.forRight(action))
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            actions
        }
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
            for (decl in typedModule.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()) {
                // Only show hint if there's no explicit type annotation in the source
                val parsedDecl = state.parsedModule?.decls
                    ?.filterIsInstance<com.mezze.language.parsedast.ParsedLetDecl>()
                    ?.find { it.isSimpleBinding && it.variableName == decl.name }

                if (parsedDecl?.typeAnnotation == null) {
                    val typeStr = prettyPrintTypeWithAliases(decl.scheme.type, state.typeAliases)
                    // Skip unresolved type variables
                    if (!typeStr.contains("'") && typeStr != "{}") {
                        val loc = decl.loc
                        val hint = InlayHint()
                        hint.position = Position(loc.startLine - 1, loc.endCol)
                        hint.setLabel(": $typeStr")
                        hint.kind = InlayHintKind.Type
                        hint.paddingLeft = true
                        hints.add(hint)
                    }
                }
            }

            // 2. Pipe chain segments — show intermediate types
            // Walk the TypedAST looking for TypedPipe on separate lines
            for (decl in typedModule.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()) {
                collectPipeHints(decl.value, hints, state.typeAliases)
            }

            hints
        }
    }

    private fun collectPipeHints(
        expr: com.mezze.language.typedast.TypedExpr,
        hints: MutableList<InlayHint>,
        typeAliases: Map<String, TypeAliasInfo>
    ) {
        when (expr) {
            is com.mezze.language.typedast.TypedPipe -> {
                // Show type at end of left side if it's on a different line from right
                if (expr.left.loc.endLine != expr.right.loc.startLine) {
                    val typeStr = prettyPrintTypeWithAliases(expr.left.type, typeAliases)
                    if (!typeStr.contains("'")) {
                        val hint = InlayHint()
                        hint.position = Position(expr.left.loc.endLine - 1, expr.left.loc.endCol)
                        hint.setLabel(": $typeStr")
                        hint.kind = InlayHintKind.Type
                        hint.paddingLeft = true
                        hints.add(hint)
                    }
                }
                collectPipeHints(expr.left, hints, typeAliases)
                collectPipeHints(expr.right, hints, typeAliases)
            }
            is com.mezze.language.typedast.TypedBlock -> {
                for (stmt in expr.statements) {
                    when (stmt) {
                        is com.mezze.language.typedast.TypedStatement.Let -> collectPipeHints(stmt.value, hints, typeAliases)
                        is com.mezze.language.typedast.TypedStatement.Expr -> collectPipeHints(stmt.expr, hints, typeAliases)
                    }
                }
                collectPipeHints(expr.result, hints, typeAliases)
            }
            is com.mezze.language.typedast.TypedCall -> {
                collectPipeHints(expr.function, hints, typeAliases)
                collectPipeHints(expr.arg, hints, typeAliases)
            }
            is com.mezze.language.typedast.TypedLambda -> collectPipeHints(expr.body, hints, typeAliases)
            is com.mezze.language.typedast.TypedMatch -> {
                collectPipeHints(expr.scrutinee, hints, typeAliases)
                for (case in expr.cases) collectPipeHints(case.body, hints, typeAliases)
            }
            is com.mezze.language.typedast.TypedIf -> {
                collectPipeHints(expr.condition, hints, typeAliases)
                collectPipeHints(expr.thenBranch, hints, typeAliases)
                collectPipeHints(expr.elseBranch, hints, typeAliases)
            }
            is com.mezze.language.typedast.TypedPerform -> collectPipeHints(expr.body, hints, typeAliases)
            else -> {} // leaf nodes
        }
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

            // Find all references to this name using semantic search
            val locations = mutableListOf<Location>()
            val typedModule = (state.typeCheckResult ?: lastSuccessfulResult[uri])?.typedModule
            if (typedModule != null) {
                collectReferencesInModule(typedModule, oldName, uri, locations)
            }

            // If no semantic refs found, fall back to text search
            if (locations.isEmpty()) {
                lines.forEachIndexed { lineIndex, lineContent ->
                    var index = 0
                    while (true) {
                        val found = lineContent.indexOf(oldName, index)
                        if (found < 0) break
                        val before = if (found > 0) lineContent[found - 1] else ' '
                        val after = if (found + oldName.length < lineContent.length) lineContent[found + oldName.length] else ' '
                        if (!isIdentifierChar(before) && !isIdentifierChar(after)) {
                            locations.add(Location(uri, Range(
                                Position(lineIndex, found),
                                Position(lineIndex, found + oldName.length)
                            )))
                        }
                        index = found + 1
                    }
                }
            }

            if (locations.isEmpty()) return@supplyAsync null

            val edits = locations.map { loc ->
                TextEdit(loc.range, newName)
            }

            WorkspaceEdit(mapOf(uri to edits))
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
