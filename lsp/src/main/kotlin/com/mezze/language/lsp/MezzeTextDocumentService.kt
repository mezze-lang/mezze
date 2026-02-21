package com.mezze.language.lsp

import com.mezze.language.ast.*
import com.mezze.language.ast.prettyPrintTypeWithAliases
import com.mezze.language.codebase.CodebaseManager
import com.mezze.language.codebase.Codebase
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

    // Codebase for project mode
    private var projectCodebase: Codebase? = null

    /**
     * State for an open document.
     */
    data class DocumentState(
        val uri: String,
        val content: String,
        val version: Int,
        val ast: Program?,
        val typeCheckResult: TypeCheckResult?,
        val typeAliases: Map<String, TypeAliasInfo>,  // For pretty printing types with aliases
        val diagnostics: List<Diagnostic>
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
     */
    private fun analyzeDocument(uri: String, content: String, version: Int): DocumentState {
        val diagnostics = mutableListOf<Diagnostic>()
        var ast: Program? = null
        var typeCheckResult: TypeCheckResult? = null
        var typeAliases: Map<String, TypeAliasInfo> = emptyMap()

        val filePath = uriToPath(uri)
        val fileName = filePath?.fileName?.toString()

        // Step 1: Parse
        try {
            ast = parser.parse(content, file = fileName)
        } catch (e: ParseException) {
            diagnostics.add(createDiagnostic(
                message = e.message ?: "Parse error",
                severity = DiagnosticSeverity.Error,
                line = e.line - 1,  // LSP is 0-indexed
                column = e.column - 1,
                endColumn = e.column + 10  // Approximate
            ))
            return DocumentState(uri, content, version, null, null, emptyMap(), diagnostics)
        }

        // Step 2: Type check
        try {
            val projectRoot = filePath?.parent ?: Paths.get(".")
            typeCheckResult = typeChecker.typeCheck(ast, projectRoot)
            // Get type aliases for pretty printing
            typeAliases = typeChecker.getTypeAliases()
        } catch (e: TypeCheckError) {
            diagnostics.add(createDiagnosticFromTypeError(e))
        } catch (e: Exception) {
            diagnostics.add(createDiagnostic(
                message = "Internal error: ${e.message}",
                severity = DiagnosticSeverity.Error,
                line = 0,
                column = 0
            ))
        }

        return DocumentState(uri, content, version, ast, typeCheckResult, typeAliases, diagnostics)
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

        // Use AST if available (works even with type errors)
        val ast = state.ast
        val result = state.typeCheckResult

        // Get type declarations for pretty printing variant types
        val typeDeclarations = (result?.program ?: ast)?.items?.filterIsInstance<TypeDeclaration>() ?: emptyList()

        // Helper to format type with aliases
        fun formatType(type: MezzeType): String {
            return prettyPrintTypeWithAliases(type, state.typeAliases, false, typeDeclarations)
        }

        // Helper to wrap in syntax-highlighted code block
        fun codeBlock(code: String): String = "```mezze\n$code\n```"

        // If we have type check result, use it for full type info
        if (result != null) {
            // 1. Look up top-level bindings with inferred types
            val binding = result.program.items
                .filterIsInstance<LetBinding>()
                .filter { it.isSimpleBinding }
                .find { it.variableName == word }

            if (binding != null && binding.inferredType != null) {
                return codeBlock("$word : ${formatType(binding.inferredType!!)}")
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

            // 6. Look for local bindings (function parameters, let bindings in expressions)
            val localType = findLocalBindingType(word, result.program, position.line + 1)
            if (localType != null) {
                return codeBlock("$word : ${formatType(localType)}")
            }
        }

        // Fallback: use AST for basic info (even when type check failed)
        val program = result?.program ?: ast ?: return null

        // 3. Look up type declarations
        val typeDecl = program.items
            .filterIsInstance<TypeDeclaration>()
            .find { it.name == word }

        if (typeDecl != null) {
            return codeBlock("type $word")
        }

        // 4. Look up effect declarations
        val effectDecl = program.items
            .filterIsInstance<EffectDeclaration>()
            .find { it.name == word }

        if (effectDecl != null) {
            return codeBlock("effect $word")
        }

        // 5. Look up ability declarations
        val abilityDecl = program.items
            .filterIsInstance<AbilityDeclaration>()
            .find { it.name == word }

        if (abilityDecl != null) {
            return codeBlock("ability $word")
        }

        // 7. Look up let bindings by name (without type info)
        val letBinding = program.items
            .filterIsInstance<LetBinding>()
            .filter { it.isSimpleBinding }
            .find { it.variableName == word }

        if (letBinding != null) {
            // Show function/binding without type if type check failed
            return codeBlock("let $word = ...")
        }

        return null
    }

    /**
     * Find a local binding's type by searching through the AST.
     * This handles function parameters and local let bindings.
     */
    private fun findLocalBindingType(name: String, program: Program, line: Int): MezzeType? {
        for (item in program.items) {
            if (item is LetBinding) {
                val type = searchExpressionForBinding(name, item.expression)
                if (type != null) return type
            }
        }
        return null
    }

    /**
     * Search an expression tree for a binding with the given name.
     * Returns the type if found in the inferred type of the expression context.
     */
    private fun searchExpressionForBinding(name: String, expr: Expression): MezzeType? {
        return when (expr) {
            is LambdaExpression -> {
                // Check lambda parameters - extract parameter names
                val paramNames = when (val params = expr.parameters) {
                    is UntypedParameters -> params.names
                    is TypedParameters -> params.recordType.entries.map { it.name }
                }

                // If the name matches a parameter, try to get the type from the lambda's inferred type
                if (name in paramNames) {
                    val funcType = expr.inferredType
                    if (funcType is MezzeType.FunctionType) {
                        val paramType = funcType.paramType
                        if (paramType is MezzeType.RecordType) {
                            return paramType.fields[name]
                        }
                    }
                }

                // Search body
                searchExpressionForBinding(name, expr.body)
            }
            is FunctionCall -> {
                searchExpressionForBinding(name, expr.function)
                    ?: searchExpressionForBinding(name, expr.arguments)
            }
            is IfExpression -> {
                searchExpressionForBinding(name, expr.condition)
                    ?: searchExpressionForBinding(name, expr.thenBranch)
                    ?: searchExpressionForBinding(name, expr.elseBranch)
            }
            is MatchExpression -> {
                // Check pattern bindings in match cases
                for (case in expr.cases) {
                    for (pattern in case.patterns) {
                        val patternType = searchPatternForBinding(name, pattern, expr.expression.inferredType)
                        if (patternType != null) return patternType
                    }
                    val bodyType = searchExpressionForBinding(name, case.expression)
                    if (bodyType != null) return bodyType
                }
                searchExpressionForBinding(name, expr.expression)
            }
            is DoExpression -> {
                // Check do block statements
                for (stmt in expr.statements) {
                    when (stmt) {
                        is LetStatement -> {
                            val pattern = stmt.pattern
                            if (pattern is VariablePattern && pattern.name == name) {
                                // LetStatement doesn't have inferredType, check the expression
                                return stmt.expression.inferredType
                            }
                            val exprType = searchExpressionForBinding(name, stmt.expression)
                            if (exprType != null) return exprType
                        }
                        is ExpressionStatement -> {
                            val exprType = searchExpressionForBinding(name, stmt.expression)
                            if (exprType != null) return exprType
                        }
                    }
                }
                // Also search the result expression
                searchExpressionForBinding(name, expr.result)
            }
            is RecordLiteral -> {
                for (entry in expr.entries) {
                    if (entry is RecordFieldEntry) {
                        val value = entry.value
                        if (value != null) {
                            val type = searchExpressionForBinding(name, value)
                            if (type != null) return type
                        }
                    }
                }
                null
            }
            is PerformExpression -> {
                searchExpressionForBinding(name, expr.expression)
            }
            else -> null
        }
    }

    /**
     * Search a pattern for a binding with the given name.
     * The scrutineeType is the type of the expression being matched against,
     * which helps us determine the type of bindings in the pattern.
     */
    private fun searchPatternForBinding(name: String, pattern: Pattern, scrutineeType: MezzeType?): MezzeType? {
        return when (pattern) {
            is VariablePattern -> if (pattern.name == name) scrutineeType else null
            is ConstructorPattern -> {
                // For constructor patterns, search the nested record pattern
                val recordPattern = pattern.recordPattern
                if (recordPattern != null) {
                    // Try to extract the variant's data type from the scrutinee
                    val dataType = if (scrutineeType is MezzeType.VariantType) {
                        scrutineeType.constructors[pattern.constructor]
                    } else null
                    searchPatternForBinding(name, recordPattern, dataType)
                } else null
            }
            is RecordPattern -> {
                // For record patterns, check each entry
                for (entry in pattern.entries) {
                    // If no nested pattern, the entry binds directly to its name
                    if (entry.pattern == null && entry.name == name) {
                        // Get the field type from the scrutinee
                        if (scrutineeType is MezzeType.RecordType) {
                            return scrutineeType.fields[name]
                        }
                    }
                    // If there's a nested pattern, search it
                    val nestedPattern = entry.pattern
                    if (nestedPattern != null) {
                        val fieldType = if (scrutineeType is MezzeType.RecordType) {
                            scrutineeType.fields[entry.name]
                        } else null
                        val type = searchPatternForBinding(name, nestedPattern, fieldType)
                        if (type != null) return type
                    }
                }
                null
            }
            is ArrayPattern -> {
                // For array patterns, the element type would be the array's element type
                val elementType = if (scrutineeType is MezzeType.ArrayType) {
                    scrutineeType.elementType
                } else null

                for (elem in pattern.elements) {
                    val type = searchPatternForBinding(name, elem, elementType)
                    if (type != null) return type
                }
                // Check rest pattern
                if (pattern.restPattern == name) {
                    return scrutineeType  // Rest is the same array type
                }
                null
            }
            is WildcardPattern -> null
            is LiteralPattern -> null
            is StringInterpolationPattern -> {
                // String interpolation captures are always String type
                for (part in pattern.parts) {
                    if (part is StringPatternCapture && part.variableName == name) {
                        return MezzeType.StringType
                    }
                }
                null
            }
        }
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

        // Look for let bindings
        for (item in result.program.items) {
            when (item) {
                is LetBinding -> {
                    val loc = item.expression.sourceLocation
                    if (item.isSimpleBinding && item.variableName == word && loc != null) {
                        return listOf(Location(
                            state.uri,
                            Range(
                                Position(loc.line - 1, loc.column - 1),
                                Position(loc.line - 1, loc.column + word.length - 1)
                            )
                        ))
                    }
                }
                is TypeDeclaration -> {
                    if (item.name == word && item.sourceLocation != null) {
                        val loc = item.sourceLocation!!
                        return listOf(Location(
                            state.uri,
                            Range(
                                Position(loc.line - 1, loc.column - 1),
                                Position(loc.line - 1, loc.column + word.length - 1)
                            )
                        ))
                    }
                }
                else -> {}
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
                }

                // Add stdlib suggestions
                items.addAll(getStdlibCompletions())
            }

            Either.forLeft(items)
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

        // Get type declarations for pretty printing
        val typeDeclarations = result.program.items.filterIsInstance<TypeDeclaration>()

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
            val typeStr = prettyPrintTypeWithAliases(fieldType, state.typeAliases, false, typeDeclarations)
            items.add(CompletionItem(fieldName).apply {
                kind = CompletionItemKind.Field
                detail = typeStr
                insertText = "$fieldName = "
                sortText = "1_${index.toString().padStart(3, '0')}"
            })
        }

        return items
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
        // Check local bindings
        val binding = result.program.items
            .filterIsInstance<LetBinding>()
            .filter { it.isSimpleBinding }
            .find { it.variableName == funcName }

        if (binding?.inferredType != null) {
            return binding.inferredType
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
        // Use AST or type check result - works even with type errors
        val program = state.typeCheckResult?.program ?: state.ast ?: return emptyList()
        val typeDeclarations = program.items.filterIsInstance<TypeDeclaration>()

        val items = mutableListOf<CompletionItem>()

        // Add let bindings (functions and values)
        program.items
            .filterIsInstance<LetBinding>()
            .filter { it.isSimpleBinding }
            .forEach { binding ->
                items.add(CompletionItem(binding.variableName).apply {
                    kind = CompletionItemKind.Function
                    detail = binding.inferredType?.let {
                        prettyPrintTypeWithAliases(it, state.typeAliases, false, typeDeclarations)
                    }
                })
            }

        // Add type declarations
        program.items
            .filterIsInstance<TypeDeclaration>()
            .forEach { typeDecl ->
                items.add(CompletionItem(typeDecl.name).apply {
                    kind = CompletionItemKind.Struct
                    detail = "type"
                })
            }

        // Add effect declarations
        program.items
            .filterIsInstance<EffectDeclaration>()
            .forEach { effectDecl ->
                items.add(CompletionItem(effectDecl.name).apply {
                    kind = CompletionItemKind.Interface
                    detail = "effect"
                })
            }

        // Add ability declarations
        program.items
            .filterIsInstance<AbilityDeclaration>()
            .forEach { abilityDecl ->
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
                detail = prettyPrintTypeWithAliases(import.type, state.typeAliases, false, typeDeclarations)
            })
        }

        return items
    }

    /**
     * Get local variable completions - variables in scope at the current position.
     * This includes function parameters, let bindings in do blocks, and pattern bindings.
     */
    private fun getLocalVariableCompletions(state: DocumentState, position: Position): List<CompletionItem> {
        val program = state.typeCheckResult?.program ?: state.ast ?: return emptyList()
        val items = mutableListOf<CompletionItem>()
        val line = position.line + 1  // Convert to 1-indexed

        // Find which let binding contains our position and collect its local variables
        for (item in program.items) {
            if (item is LetBinding) {
                val loc = item.expression.sourceLocation
                // Check if position is within this binding's expression
                if (loc != null && line >= loc.line) {
                    collectLocalVariables(item.expression, line, items, state)
                }
            }
        }

        return items.distinctBy { it.label }  // Remove duplicates
    }

    /**
     * Collect local variables from an expression that are in scope at the given line.
     */
    private fun collectLocalVariables(
        expr: Expression,
        line: Int,
        items: MutableList<CompletionItem>,
        state: DocumentState
    ) {
        val typeDeclarations = (state.typeCheckResult?.program ?: state.ast)?.items
            ?.filterIsInstance<TypeDeclaration>() ?: emptyList()

        when (expr) {
            is LambdaExpression -> {
                // Add lambda parameters
                val paramNames = when (val params = expr.parameters) {
                    is UntypedParameters -> params.names
                    is TypedParameters -> params.recordType.entries.map { it.name }
                }

                // Get parameter types from lambda's inferred type if available
                val paramTypes = if (expr.inferredType is MezzeType.FunctionType) {
                    val funcType = expr.inferredType as MezzeType.FunctionType
                    if (funcType.paramType is MezzeType.RecordType) {
                        (funcType.paramType as MezzeType.RecordType).fields
                    } else emptyMap()
                } else emptyMap()

                paramNames.forEach { name ->
                    items.add(CompletionItem(name).apply {
                        kind = CompletionItemKind.Variable
                        detail = paramTypes[name]?.let {
                            prettyPrintTypeWithAliases(it, state.typeAliases, false, typeDeclarations)
                        } ?: "parameter"
                    })
                }

                // Recurse into body
                collectLocalVariables(expr.body, line, items, state)
            }
            is DoExpression -> {
                // Add let bindings from do block
                for (stmt in expr.statements) {
                    when (stmt) {
                        is LetStatement -> {
                            if (stmt.pattern is VariablePattern) {
                                val name = (stmt.pattern as VariablePattern).name
                                items.add(CompletionItem(name).apply {
                                    kind = CompletionItemKind.Variable
                                    detail = stmt.expression.inferredType?.let {
                                        prettyPrintTypeWithAliases(it, state.typeAliases, false, typeDeclarations)
                                    }
                                })
                            }
                            collectLocalVariables(stmt.expression, line, items, state)
                        }
                        is ExpressionStatement -> {
                            collectLocalVariables(stmt.expression, line, items, state)
                        }
                    }
                }
                collectLocalVariables(expr.result, line, items, state)
            }
            is MatchExpression -> {
                collectLocalVariables(expr.expression, line, items, state)
                for (case in expr.cases) {
                    // Add pattern bindings
                    for (pattern in case.patterns) {
                        collectPatternBindings(pattern, items, state)
                    }
                    collectLocalVariables(case.expression, line, items, state)
                }
            }
            is IfExpression -> {
                collectLocalVariables(expr.condition, line, items, state)
                collectLocalVariables(expr.thenBranch, line, items, state)
                collectLocalVariables(expr.elseBranch, line, items, state)
            }
            is FunctionCall -> {
                collectLocalVariables(expr.function, line, items, state)
                collectLocalVariables(expr.arguments, line, items, state)
            }
            is RecordLiteral -> {
                for (entry in expr.entries) {
                    if (entry is RecordFieldEntry) {
                        val value = entry.value
                        if (value != null) {
                            collectLocalVariables(value, line, items, state)
                        }
                    }
                }
            }
            is PerformExpression -> {
                collectLocalVariables(expr.expression, line, items, state)
            }
            else -> {}
        }
    }

    /**
     * Collect variable bindings from a pattern.
     */
    private fun collectPatternBindings(
        pattern: Pattern,
        items: MutableList<CompletionItem>,
        state: DocumentState
    ) {
        when (pattern) {
            is VariablePattern -> {
                items.add(CompletionItem(pattern.name).apply {
                    kind = CompletionItemKind.Variable
                    detail = "pattern binding"
                })
            }
            is ConstructorPattern -> {
                pattern.recordPattern?.let { collectPatternBindings(it, items, state) }
            }
            is RecordPattern -> {
                for (entry in pattern.entries) {
                    // Shorthand {x} binds x
                    val nestedPattern = entry.pattern
                    if (nestedPattern == null) {
                        items.add(CompletionItem(entry.name).apply {
                            kind = CompletionItemKind.Variable
                            detail = "pattern binding"
                        })
                    } else {
                        collectPatternBindings(nestedPattern, items, state)
                    }
                }
            }
            is ArrayPattern -> {
                for (elem in pattern.elements) {
                    collectPatternBindings(elem, items, state)
                }
                pattern.restPattern?.let { restName ->
                    items.add(CompletionItem(restName).apply {
                        kind = CompletionItemKind.Variable
                        detail = "rest pattern"
                    })
                }
            }
            else -> {}
        }
    }

    private fun getStdlibCompletions(): List<CompletionItem> {
        // Common stdlib imports
        val suggestions = listOf(
            "std::effects::log::{println, Console}" to "Print to console",
            "std::int::{to_string, abs}" to "Integer utilities",
            "std::option::{map, get_or_else}" to "Option utilities",
            "std::collections::array::{length, map}" to "Array utilities"
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

            for (item in result.program.items) {
                val symbol = when (item) {
                    is LetBinding -> if (item.isSimpleBinding) createDocumentSymbol(
                        name = item.variableName,
                        kind = SymbolKind.Function,
                        location = item.expression.sourceLocation,
                        detail = item.inferredType?.let { prettyPrintType(it) }
                    ) else null
                    is TypeDeclaration -> createDocumentSymbol(
                        name = item.name,
                        kind = SymbolKind.Struct,
                        location = item.sourceLocation
                    )
                    is EffectDeclaration -> createDocumentSymbol(
                        name = item.name,
                        kind = SymbolKind.Interface,
                        location = item.sourceLocation
                    )
                    is AbilityDeclaration -> createDocumentSymbol(
                        name = item.name,
                        kind = SymbolKind.Interface,
                        location = item.sourceLocation
                    )
                    else -> null
                }

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

    // ========== Utilities ==========

    private fun uriToPath(uri: String): Path? {
        return try {
            Paths.get(URI.create(uri))
        } catch (e: Exception) {
            null
        }
    }
}
