package com.mezze.language.parsedast.diagnostics

import com.mezze.language.parsedast.Loc

/**
 * Structured, machine-actionable suggestion for fixing a diagnostic.
 *
 * Lives next to [MezzeDiagnostic] so every error-producing site can
 * attach hints once, and every consumer (LSP code actions, future IDE
 * integrations, terminal interactive fixes) can dispatch by type
 * instead of regex-parsing the user-facing message.
 *
 * Design rules:
 *
 * - Always carry a [Loc] for the text the fix would modify, so the
 *   client doesn't have to re-derive the range from the diagnostic's
 *   primary span (often subtly different — e.g. the diagnostic points
 *   at the whole `let` while the rename should only touch the field
 *   name).
 *
 * - Suggestions are *unordered* and can be many. The client picks
 *   how to rank / display.
 *
 * - Never depend on the message wording. If a wording change would
 *   break code actions, you're routing data through the wrong channel.
 */
sealed class FixHint {
    /** The source span the fix would modify. */
    abstract val loc: Loc

    /**
     * Replace [loc] with one of [candidates]. Used for typos in field
     * access, method calls, variant constructors, variable references,
     * effect names — anywhere the typechecker computed a did-you-mean
     * candidate via Levenshtein.
     */
    data class Rename(
        override val loc: Loc,
        val candidates: List<String>,
    ) : FixHint()

    /**
     * Insert `use module::name` near the top of the current file.
     * [candidateModules] holds the module paths where [name] is
     * defined; client offers one quick fix per module.
     */
    data class Import(
        override val loc: Loc,
        val name: String,
        val candidateModules: List<String>,
    ) : FixHint()

    /**
     * Wrap the diagnostic span in a `perform <Handler> in do … end`
     * block. Used for V0501 (effect not handled) — the typechecker
     * knows which handlers are visible for the unhandled effect.
     */
    data class WrapInPerform(
        override val loc: Loc,
        val effectName: String,
        val candidatePerformers: List<String>,
    ) : FixHint()

    /**
     * Add an effect to a function-type annotation. Used for T0210 when
     * the body performs an effect the annotation forgot to declare.
     * [effectName] is the effect to insert. [insertionLoc] is where —
     * typically just after the return type.
     */
    data class AddEffectToAnnotation(
        override val loc: Loc,
        val effectName: String,
    ) : FixHint()

    /**
     * Add `pub` before a declaration in another module. Used for M0803
     * (not exported). [declModulePath] and [declName] identify the
     * decl to mark public; the LSP resolves the file via the store.
     * [loc] points at the import site (for diagnostic attachment); the
     * actual edit goes to the foreign module.
     */
    data class AddPub(
        override val loc: Loc,
        val declModulePath: String,
        val declName: String,
    ) : FixHint()
}
