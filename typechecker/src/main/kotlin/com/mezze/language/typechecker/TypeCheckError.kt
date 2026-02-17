package com.mezze.language.typechecker

import com.mezze.language.ast.Expression
import com.mezze.language.ast.SourceLocation

/**
 * Exception thrown when type checking fails.
 *
 * Supports multiple constructor signatures for compatibility:
 * - TypeCheckError(message, sourceLocation)
 * - TypeCheckError(message, expression, errorType, metadata)
 *
 * @param message The error message
 * @param sourceLocation Optional source location where the error occurred
 * @param expression Optional expression where the error occurred
 * @param errorType Optional error type categorization
 * @param metadata Optional additional metadata about the error
 */
class TypeCheckError(
    override val message: String,
    val sourceLocation: SourceLocation? = null,
    val expression: Expression? = null,
    val errorType: ErrorType = ErrorType.GENERAL,
    val metadata: Map<String, Any> = emptyMap()
) : Exception(message) {

    /**
     * Secondary constructor for (message, expression, errorType, metadata) signature.
     */
    constructor(
        message: String,
        expression: Expression?,
        errorType: ErrorType,
        metadata: Map<String, Any> = emptyMap()
    ) : this(
        message = message,
        sourceLocation = expression?.sourceLocation,
        expression = expression,
        errorType = errorType,
        metadata = metadata
    )


    override fun toString(): String {
        val location = sourceLocation ?: expression?.sourceLocation
        return if (location != null) {
            "TypeCheckError at $location: $message"
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
