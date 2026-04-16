package com.mezze.language.typechecker

import com.mezze.language.ast.SourceLocation

/**
 * Exception thrown when type checking fails.
 *
 * @param message The error message
 * @param sourceLocation Optional source location where the error occurred
 * @param errorType Optional error type categorization
 * @param metadata Optional additional metadata about the error
 */
class TypeCheckError(
    override val message: String,
    val sourceLocation: SourceLocation? = null,
    val errorType: ErrorType = ErrorType.GENERAL,
    val metadata: Map<String, Any> = emptyMap()
) : Exception(message) {

    override fun toString(): String {
        return if (sourceLocation != null) {
            "TypeCheckError at $sourceLocation: $message"
        } else {
            "TypeCheckError: $message"
        }
    }
}

/**
 * Enum for categorizing type check errors.
 */
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
