package com.funk.language.interpreter

/**
 * Base exception class for Funk language runtime errors
 */
open class FunkException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Exception thrown when a variable is not found
 */
class UndefinedVariableException(variableName: String) : FunkException("Undefined variable: $variableName")

/**
 * Exception thrown when a function is not found
 */
class UndefinedFunctionException(functionName: String) : FunkException("Undefined function: $functionName")

/**
 * Exception thrown when an effect handler is not found
 */
class HandlerNotFoundException(handlerName: String) : FunkException("Handler not found: $handlerName")

/**
 * Exception thrown when an effect operation is not found
 */
class OperationNotFoundException(effectName: String, operationName: String) : 
    FunkException("Operation $operationName not found in effect $effectName")

/**
 * Exception thrown when there are type mismatches at runtime
 */
class TypeMismatchException(expected: String, actual: String) : 
    FunkException("Type mismatch: expected $expected, got $actual")

/**
 * Exception thrown when arithmetic operations fail
 */
class ArithmeticException(operation: String, leftType: String, rightType: String) : 
    FunkException("Cannot apply arithmetic operator '$operation' to $leftType and $rightType")

/**
 * Exception thrown when native function calls fail
 */
class NativeFunctionException(functionName: String, reason: String) : 
    FunkException("Native function '$functionName' failed: $reason")

/**
 * Exception thrown when effect calls fail
 */
class EffectCallException(effectName: String, reason: String) : 
    FunkException("Effect call '$effectName' failed: $reason")