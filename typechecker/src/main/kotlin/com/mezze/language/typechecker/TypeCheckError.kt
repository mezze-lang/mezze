package com.mezze.language.typechecker

import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.FixHint

class TypeCheckError(
    override val message: String,
    val loc: Loc? = null,
    val errorType: ErrorType = ErrorType.GENERAL,
    val metadata: Map<String, Any> = emptyMap(),
    /** Stable error code — see ErrorCode. */
    val code: ErrorCode = ErrorCode.UNCATEGORIZED,
    /** Zero or more "what to do next" hints. */
    val hints: List<String> = emptyList(),
        val fixHints: List<FixHint> = emptyList(),
    /**
     * The underlying throwable, when this diagnostic REPORTS one rather than
     * describing the program — an internal compiler failure (I0003). The CLI
     * prints a cause's stack trace only under `--verbose`, so carrying it here
     * is what makes that flag able to show anything at all; without it the
     * detail lived in a `MEZZE_DEBUG` print nobody has set.
     */
    cause: Throwable? = null,
    /**
     * Other problems found at the same time, when a check can report several.
     *
     * Impl conformance is the case this exists for: an impl missing a method,
     * carrying an extra one AND missing an associated-type binding is three
     * separate mistakes, and throwing at the first made the user fix them one
     * compile at a time. The effect-side twin of that check accumulated already;
     * the ability side did not.
     *
     * Carried HERE rather than in a separate multi-error exception on purpose.
     * Eight places catch `TypeCheckError`, and a new throwable type would escape
     * every one that was not updated. This way the first problem is reported
     * exactly as before and a reporter that wants the rest opts in by reading
     * this list.
     */
    val additional: List<TypeCheckError> = emptyList(),
) : Exception(message, cause) {

    /** This error and everything found alongside it, first one first. */
    fun all(): List<TypeCheckError> = listOf(this) + additional

    override fun toString(): String {
        return if (loc != null) {
            "TypeCheckError at $loc: $message"
        } else {
            "TypeCheckError: $message"
        }
    }
}

enum class ErrorType {
    GENERAL,
    TYPE_MISMATCH,
    UNDEFINED_VARIABLE,
    UNDEFINED_TYPE,
    UNDEFINED_CONSTRUCTOR,
    DUPLICATE_DECLARATION,
    INVALID_PATTERN,
    EXHAUSTIVENESS_ERROR,
    INEXHAUSTIVE_MATCH,
    ABILITY_ERROR,
    EFFECT_ERROR,
    MODULE_ERROR,
    SYNTAX_ERROR,
    CONSTRAINT_VIOLATION,
    MISSING_FIELD,
    INTERNAL_ERROR
}
