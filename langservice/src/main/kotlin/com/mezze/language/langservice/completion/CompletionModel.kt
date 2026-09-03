package com.mezze.language.langservice.completion

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeAliasInfo
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.store.MezzeStore
import com.mezze.language.typechecker.TypeCheckResult

/**
 * Transport-neutral completion model. The [CompletionEngine] computes candidates
 * against a [CompletionContext] and returns [CompletionCandidate]s; each front-end
 * (LSP, and later CLI/WASM) adapts them to its own wire form. This module never
 * mentions lsp4j — that is the whole point of living in :langservice.
 */

/**
 * The subset of editor completion-item kinds the engine produces or compares
 * against. Named to line up 1:1 with lsp4j `CompletionItemKind` so the LSP
 * adapter is a trivial `enumValueOf`-style map. `Enum`, `Constructor`, and
 * `TypeParameter` are never assigned to a candidate but appear in relevance
 * tiering / type-annotation filtering, so they are part of the vocabulary.
 */
enum class CompletionKind {
    Field, Variable, Function, Method, Struct, EnumMember, Interface, Class,
    Keyword, Module, Snippet, Enum, Constructor, TypeParameter,
}

/**
 * A neutral text edit with 0-based, half-open LSP-style coordinates. Used for
 * completion side effects (auto-import insertions) and replacement edits
 * (import-path / postfix completions).
 */
data class NeutralTextEdit(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val newText: String,
)

/**
 * A single completion candidate. Mutable and default-valued so producers read
 * like the lsp4j builders they replace:
 *
 *     candidate("foo") { kind = CompletionKind.Function; detail = "..."; snippet = true }
 *
 * [documentationMarkdown] distinguishes the two lsp4j documentation shapes the
 * old code used (plain string vs. a MarkupContent MARKDOWN block) so the adapter
 * can reproduce each exactly. [resolveData] carries the lazy-resolve key the LSP
 * reads back in resolveCompletionItem.
 */
class CompletionCandidate(var label: String) {
    var kind: CompletionKind = CompletionKind.Variable
    var detail: String? = null
    var documentation: String? = null
    var documentationMarkdown: Boolean = false
    var insertText: String? = null
    var snippet: Boolean = false
    var sortText: String? = null
    var additionalEdits: List<NeutralTextEdit> = emptyList()
    var textEdit: NeutralTextEdit? = null
    var resolveData: Map<String, String>? = null
}

/** Builder mirroring `CompletionItem(label).apply { … }`. */
inline fun candidate(label: String, build: CompletionCandidate.() -> Unit = {}): CompletionCandidate =
    CompletionCandidate(label).apply(build)

/**
 * Everything the [CompletionEngine] needs, with no editor-transport types.
 *
 * The first block is plain document data (the fields the old DocumentState
 * exposed that completion actually read). The hooks after it are the handful of
 * things that are genuinely editor-flow concerns — reparsing a mid-edit buffer
 * to recover a receiver type, formatting a `use` line, logging — which the LSP
 * supplies so the engine stays transport-neutral.
 */
class CompletionContext(
    val uri: String,
    val content: String,
    /** 0-based cursor line. */
    val line: Int,
    /** 0-based cursor column. */
    val character: Int,
    val typeCheckResult: TypeCheckResult?,
    val typeAliases: Map<String, TypeAliasInfo>,
    val parsedModule: ParsedModule?,
    /** Last good typecheck for this uri — fallback while the buffer is mid-edit. */
    val lastSuccessful: TypeCheckResult?,
    val store: MezzeStore?,
    /**
     * The following four let the engine recover a receiver's type by reparsing
     * the buffer with the trailing `.` completed to `.to_dbg {}` and rechecking
     * it (strip-dot recovery). The LSP owns the parser / typechecker instances
     * and the workspace/uri context; the engine only borrows them.
     */
    val parser: com.mezze.language.parser.MezzeParser,
    val typeChecker: com.mezze.language.typechecker.TypeChecker,
    val workspaceRoot: String?,
    val uriToPath: (String) -> java.nio.file.Path?,
    /** Format a `use a::b::c` line (the LSP owns the Formatter instance). */
    val formatUseSnippet: (String) -> String = { it },
    val log: (String) -> Unit = {},
)
