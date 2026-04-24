package com.mezze.language.lsp

import com.mezze.language.ast.*
import com.mezze.language.ast.prettyPrintTypeWithAliases
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typechecker.ResolvedImport
import com.mezze.language.typechecker.ImportKind
import com.mezze.language.parsedast.*
import com.mezze.language.codebase.CodebaseManager
import com.mezze.language.codebase.CodebasePopulator
import com.mezze.language.codebase.Codebase
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

    // Codebase for project mode
    private var projectCodebase: Codebase? = null

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
            if (CodebaseManager.isInProject(rootFile)) {
                try {
                    projectCodebase = CodebaseManager.forProjectRoot(rootFile)
                    server.logInfo("Using project codebase at ${CodebaseManager.getCodebasePath(rootFile)}")
                } catch (e: Exception) {
                    server.logError("Failed to create codebase: ${e.message}")
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
        projectCodebase?.close()
        documents.clear()
    }

    // ========== Document Synchronization ==========

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        val content = params.textDocument.text
        val version = params.textDocument.version

        server.logInfo("Document opened: $uri")

        // Analyze the document
        val state = analyzeDocument(uri, content, version)
        documents[uri] = state

        // Publish diagnostics
        publishDiagnostics(uri, state.diagnostics)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        val version = params.textDocument.version

        // For full sync, we get the entire content
        val content = params.contentChanges.firstOrNull()?.text ?: return

        // Re-analyze the document
        val state = analyzeDocument(uri, content, version)
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

        // Re-analyze on save if content provided
        params.text?.let { content ->
            val state = documents[uri]
            if (state != null) {
                val newState = analyzeDocument(uri, content, state.version)
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
     */
    private fun analyzeDocument(uri: String, content: String, version: Int): DocumentState {
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
                        val line = warning.sourceLocation?.line?.minus(1) ?: 0
                        val column = warning.sourceLocation?.column?.minus(1) ?: 0
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
                }
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Failure -> {
                    result.errors.forEach { error ->
                        val line = error.sourceLocation?.line?.minus(1) ?: 0
                        val column = error.sourceLocation?.column?.minus(1) ?: 0
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
        val loc = error.sourceLocation
        return createDiagnostic(
            message = error.message,
            severity = DiagnosticSeverity.Error,
            line = (loc?.line ?: 1) - 1,
            column = (loc?.column ?: 1) - 1,
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
        val loc = error.sourceLocation
        return createDiagnostic(
            message = error.message ?: "Type error",
            severity = DiagnosticSeverity.Error,
            line = (loc?.line ?: 1) - 1,
            column = (loc?.column ?: 1) - 1,
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
     * TODO: Rewrite for v2 ParsedExpr when LSP is redesigned.
     */
    private fun findLocalBindingType(name: String, typedModule: com.mezze.language.typedast.TypedModule?, line: Int): MezzeType? {
        // Stubbed — needs v2 ParsedExpr rewrite for local scope walking.
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
        // Get the word at position
        val lines = state.content.lines()
        if (position.line >= lines.size) return emptyList()

        val line = lines[position.line]
        val word = extractWordAt(line, position.character) ?: return emptyList()

        // Find the definition in the AST
        val result = state.typeCheckResult ?: return emptyList()

        // Look for definitions in parsed module
        val parsedModule = state.parsedModule
        if (parsedModule != null) {
            for (decl in parsedModule.decls) {
                when (decl) {
                    is ParsedLetDecl -> {
                        if (decl.isSimpleBinding && decl.variableName == word) {
                            val loc = decl.loc
                            return listOf(Location(
                                state.uri,
                                Range(
                                    Position(loc.startLine - 1, loc.startCol - 1),
                                    Position(loc.startLine - 1, loc.startCol + word.length - 1)
                                )
                            ))
                        }
                    }
                    is ParsedTypeDecl -> {
                        if (decl.name == word) {
                            val loc = decl.loc
                            return listOf(Location(
                                state.uri,
                                Range(
                                    Position(loc.startLine - 1, loc.startCol - 1),
                                    Position(loc.startLine - 1, loc.startCol + word.length - 1)
                                )
                            ))
                        }
                    }
                    else -> {}
                }
            }
        }

        return emptyList()
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

        // Filter by partial method name if user already typed some characters after the dot
        return if (partialMethod.isNotEmpty()) {
            items.filter { it.label.startsWith(partialMethod, ignoreCase = true) }
        } else {
            items
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
     * Get local variable completions - variables in scope at the current position.
     * TODO: Rewrite for v2 ParsedExpr when LSP is redesigned.
     */
    private fun getLocalVariableCompletions(state: DocumentState, position: Position): List<CompletionItem> {
        // Stubbed — v1 Expression tree walking removed. Needs v2 ParsedExpr rewrite.
        return emptyList()
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
                    val loc = SourceLocation(decl.loc.startLine, decl.loc.startCol, decl.loc.file)
                    val symbol = createDocumentSymbol(
                        name = decl.name,
                        kind = SymbolKind.Function,
                        location = loc,
                        detail = prettyPrintType(decl.scheme.type)
                    )
                    if (symbol != null) {
                        symbols.add(Either.forRight(symbol))
                    }
                }

            // Add effect symbols from parsed module
            state.parsedModule?.decls?.filterIsInstance<ParsedEffectDecl>()?.forEach { decl ->
                val loc = SourceLocation(decl.loc.startLine, decl.loc.startCol, decl.loc.file)
                val symbol = createDocumentSymbol(
                    name = decl.name,
                    kind = SymbolKind.Interface,
                    location = loc
                )
                if (symbol != null) {
                    symbols.add(Either.forRight(symbol))
                }
            }

            // Add ability symbols from parsed module
            state.parsedModule?.decls?.filterIsInstance<ParsedAbilityDecl>()?.forEach { decl ->
                val loc = SourceLocation(decl.loc.startLine, decl.loc.startCol, decl.loc.file)
                val symbol = createDocumentSymbol(
                    name = decl.name,
                    kind = SymbolKind.Interface,
                    location = loc
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
        location: SourceLocation?,
        detail: String? = null
    ): DocumentSymbol? {
        if (location == null) return null

        val range = Range(
            Position(location.line - 1, location.column - 1),
            Position(location.line - 1, location.column + name.length - 1)
        )

        return DocumentSymbol(name, kind, range, range, detail, emptyList())
    }

    // ========== References ==========

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            val state = documents[uri] ?: return@supplyAsync emptyList()

            // Get the word at position
            val lines = state.content.lines()
            if (position.line >= lines.size) return@supplyAsync emptyList()

            val line = lines[position.line]
            val word = extractWordAt(line, position.character) ?: return@supplyAsync emptyList()

            // Find all occurrences of the word
            val locations = mutableListOf<Location>()

            lines.forEachIndexed { lineIndex, lineContent ->
                var index = 0
                while (true) {
                    val found = lineContent.indexOf(word, index)
                    if (found < 0) break

                    // Check it's a whole word
                    val before = if (found > 0) lineContent[found - 1] else ' '
                    val after = if (found + word.length < lineContent.length) lineContent[found + word.length] else ' '

                    if (!isIdentifierChar(before) && !isIdentifierChar(after)) {
                        locations.add(Location(
                            uri,
                            Range(
                                Position(lineIndex, found),
                                Position(lineIndex, found + word.length)
                            )
                        ))
                    }

                    index = found + 1
                }
            }

            locations
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
        return CompletableFuture.completedFuture(emptyList())
    }

    // ========== Signature Help ==========

    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp?> {
        return CompletableFuture.completedFuture(null)
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

    // ========== Utilities ==========

    private fun uriToPath(uri: String): Path? {
        return try {
            Paths.get(URI.create(uri))
        } catch (e: Exception) {
            null
        }
    }
}
