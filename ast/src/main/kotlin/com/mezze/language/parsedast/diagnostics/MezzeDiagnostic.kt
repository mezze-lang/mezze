package com.mezze.language.parsedast.diagnostics

import com.mezze.language.parsedast.Loc

/**
 * The single, canonical shape for a user-facing diagnostic produced anywhere
 * in the Mezze compiler. Parser, typechecker, validators, module loader,
 * LSP, and CLI all converge on this type.
 *
 * Designed to be:
 *
 * - **Non-nullable at the type level.** Every diagnostic has a [code], a
 *   [message], and a [loc]. There is no "we don't know where this happened"
 *   state — if the loc can't be determined at the call site, the call site
 *   is bugged and needs to be fixed, not the diagnostic shape.
 *
 * - **Structured.** [hints] is a list, [notes] is a list, [metadata] is a
 *   map. No String? optionals to forget about. Empty collections render to
 *   nothing.
 *
 * - **Code-addressable.** Every error has a stable [code] (`T0042`-style)
 *   so users can run `mez --explain T0042` for a long-form explanation,
 *   and LSP clients can keyed lookups for code actions.
 *
 * Rendering shape (see ErrorFormatter):
 *
 *     error[T0201]: <message>
 *       --> file:line:col
 *        |
 *      4 | <source line>
 *        |       ^^^^^
 *        |
 *        = hint: <hint 1>
 *        = hint: <hint 2>
 *        = note: <note message>
 *           --> file:line:col       (if note has a different loc)
 */
data class MezzeDiagnostic(
    /** Stable identifier — see [ErrorCode]. */
    val code: ErrorCode,
    /** One-line "what went wrong", lowercase, no trailing period (Rust-style). */
    val message: String,
    /** Primary source position. Non-nullable: every error fires somewhere. */
    val loc: Loc,
    /** Severity. Default ERROR — WARNING reserved for future use. */
    val severity: Severity = Severity.ERROR,
    /**
     * Zero or more "what to do next" suggestions. Each renders as a
     * `= hint: …` line. Empty list means no hint is offered.
     */
    val hints: List<String> = emptyList(),
    /**
     * Zero or more related spans elsewhere in the source — e.g.
     * "previous declaration was here" pointing at the first definition
     * of a duplicated name.
     */
    val notes: List<DiagnosticNote> = emptyList(),
    /**
     * Structured data the LSP can consume for code actions. Examples:
     * - `"availableFields" to listOf("name", "email")` — enables a
     *   did-you-mean quick-fix
     * - `"missingImport" to "std::collections::seq"` — enables a
     *   one-click auto-import action
     *
     * Not user-visible; LSP-only consumer for now.
     */
    val metadata: Map<String, Any> = emptyMap(),
)

/**
 * A related-span annotation on a diagnostic. The note's [loc] is required,
 * matching the parent diagnostic's contract: if you don't have a real
 * location, don't make a note.
 */
data class DiagnosticNote(
    val message: String,
    val loc: Loc,
)

enum class Severity { ERROR, WARNING }
