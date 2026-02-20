package com.mezze.language.interpreter

/**
 * Base exception class for all Mezze language runtime errors.
 * Provides structured error handling with context information.
 */
open class MezzeException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    
    /**
     * Get a user-friendly error message
     */
    open fun getUserMessage(): String = message ?: "Unknown error"
    
    /**
     * Get detailed error information for debugging
     */
    open fun getDetailedMessage(): String = buildString {
        append("Mezze Runtime Error: ${getUserMessage()}")
        cause?.message?.let { causeMessage ->
            append("\nCaused by: $causeMessage")
        }
    }
}

/**
 * Exception thrown when a native function is called with invalid arguments
 */
class NativeFunctionException(
    val functionName: String,
    val reason: String,
    cause: Throwable? = null
) : MezzeException("Native function '$functionName' error: $reason", cause) {
    
    override fun getUserMessage(): String = "Error in native function '$functionName': $reason"
}

/**
 * Exception thrown when an ability constraint is not satisfied
 */
class AbilityConstraintException(
    val typeName: String,
    val abilityName: String,
    val operation: String? = null
) : MezzeException(buildMessage(typeName, abilityName, operation)) {
    
    companion object {
        private fun buildMessage(typeName: String, abilityName: String, operation: String?): String {
            return if (operation != null) {
                "Type '$typeName' does not implement ability '$abilityName' required for operation '$operation'"
            } else {
                "Type '$typeName' does not implement required ability '$abilityName'"
            }
        }
    }
    
    override fun getUserMessage(): String = message ?: "Ability constraint not satisfied"
}

/**
 * Exception thrown when an effect is performed without a proper handler
 */
class EffectHandlerException(
    val effectName: String,
    val operationName: String? = null
) : MezzeException(buildMessage(effectName, operationName)) {
    
    companion object {
        private fun buildMessage(effectName: String, operationName: String?): String {
            return if (operationName != null) {
                "No handler found for effect '$effectName' operation '$operationName'"
            } else {
                "No handler found for effect '$effectName'"
            }
        }
    }
    
    override fun getUserMessage(): String = "Effect handler not found: $effectName${operationName?.let { ".$it" } ?: ""}"
}

/**
 * Exception thrown when arithmetic operations fail (overflow, division by zero, etc.)
 */
class ArithmeticException : MezzeException {
    val operation: String
    val leftOperand: String?
    val rightOperand: String?
    
    constructor(operation: String) : super("Arithmetic error in $operation") {
        this.operation = operation
        this.leftOperand = null
        this.rightOperand = null
    }
    
    constructor(operation: String, operands: String? = null) : super("Arithmetic error in $operation${operands?.let { " with operands: $it" } ?: ""}") {
        this.operation = operation
        this.leftOperand = null
        this.rightOperand = null
    }
    
    constructor(operation: String, leftOperand: String, rightOperand: String) : super("Arithmetic error in $operation with operands: $leftOperand, $rightOperand") {
        this.operation = operation
        this.leftOperand = leftOperand
        this.rightOperand = rightOperand
    }
    
    override fun getUserMessage(): String = "Arithmetic error: $operation"
}

/**
 * Exception thrown when type conversion fails
 */
class ConversionException(
    val fromType: String,
    val toType: String,
    val value: String? = null
) : MezzeException(buildMessage(fromType, toType, value)) {
    
    companion object {
        private fun buildMessage(fromType: String, toType: String, value: String?): String {
            return if (value != null) {
                "Cannot convert value '$value' from type '$fromType' to '$toType'"
            } else {
                "Cannot convert from type '$fromType' to '$toType'"
            }
        }
    }
    
    override fun getUserMessage(): String = "Type conversion failed: $fromType -> $toType"
}

/**
 * Exception thrown when I/O operations fail
 */
class IOException(
    val operation: String,
    val resource: String? = null,
    cause: Throwable? = null
) : MezzeException(buildMessage(operation, resource), cause) {
    
    companion object {
        private fun buildMessage(operation: String, resource: String?): String {
            return if (resource != null) {
                "I/O error during $operation on resource: $resource"
            } else {
                "I/O error during $operation"
            }
        }
    }
    
    override fun getUserMessage(): String = "I/O error: $operation${resource?.let { " ($it)" } ?: ""}"
}

/**
 * Exception thrown when a variable is not defined
 */
class UndefinedVariableException(
    val variableName: String
) : MezzeException("Undefined variable: $variableName") {
    
    override fun getUserMessage(): String = "Variable '$variableName' is not defined"
}

/**
 * Exception thrown when an effect handler is not found
 */
class HandlerNotFoundException(
    val effectName: String,
    val operationName: String? = null
) : MezzeException(buildMessage(effectName, operationName)) {

    companion object {
        private fun buildMessage(effectName: String, operationName: String?): String {
            return if (operationName != null) {
                "Handler not found for effect '$effectName' operation '$operationName'"
            } else {
                "Handler not found for effect '$effectName'"
            }
        }
    }

    override fun getUserMessage(): String = "Handler not found: $effectName${operationName?.let { ".$it" } ?: ""}"
}

/**
 * Exception thrown when the crash function is called.
 * This represents an unrecoverable error, similar to Rust's panic!.
 * crash is a built-in function that doesn't require imports.
 */
class MezzeCrashException(
    message: String
) : MezzeException(message) {

    override fun getUserMessage(): String = message ?: "Program crashed"
}