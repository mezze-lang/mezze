package com.funk.language.interpreter

/**
 * Custom exception for Funk language runtime errors.
 * Simple exception class that works in both JVM and native image contexts.
 */
class FunkException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    
    companion object {
        /**
         * Create a runtime error exception
         */
        fun runtimeError(message: String): FunkException {
            return FunkException("Runtime Error: $message")
        }
        
        /**
         * Create a type error exception
         */
        fun typeError(message: String): FunkException {
            return FunkException("Type Error: $message")
        }
        
        /**
         * Create an unbound variable error
         */
        fun unboundVariable(name: String): FunkException {
            return FunkException("Unbound variable: $name")
        }
        
        /**
         * Create a pattern match failure exception
         */
        fun patternMatchFailure(): FunkException {
            return FunkException("Pattern match failure: no matching case found")
        }
        
        /**
         * Create an effect error exception
         */
        fun effectError(message: String): FunkException {
            return FunkException("Effect Error: $message")
        }
    }
}
