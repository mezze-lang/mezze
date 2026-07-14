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
) : Exception(message) {

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
