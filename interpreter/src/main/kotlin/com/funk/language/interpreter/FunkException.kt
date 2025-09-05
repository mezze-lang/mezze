package com.funk.language.interpreter

import com.oracle.truffle.api.exception.AbstractTruffleException
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.interop.ExceptionType
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.oracle.truffle.api.nodes.Node

/**
 * Custom exception for Funk language runtime errors.
 * Integrates with Truffle's exception handling system.
 */
@ExportLibrary(InteropLibrary::class)
class FunkException(
    message: String,
    cause: Throwable? = null,
    location: Node? = null
) : AbstractTruffleException(message, cause, UNLIMITED_STACK_TRACE, location), TruffleObject {
    
    @ExportMessage
    fun isException(): Boolean = true
    
    @ExportMessage
    fun getExceptionType(): ExceptionType = ExceptionType.RUNTIME_ERROR
    
    @ExportMessage
    fun getExceptionMessage(): String = message ?: "Funk runtime error"
    
    @ExportMessage
    fun hasExceptionCause(): Boolean = cause != null
    
    @ExportMessage
    fun getExceptionCause(): Any? = cause
    
    @ExportMessage
    fun hasExceptionStackTrace(): Boolean = stackTrace.isNotEmpty()
    
    @ExportMessage
    fun getExceptionStackTrace(): Any = stackTrace
    
    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun throwException(): RuntimeException {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }
    
    @ExportMessage
    fun hasExceptionMessage(): Boolean = true
    
    companion object {
        /**
         * Create a runtime error exception
         */
        fun runtimeError(message: String, location: Node? = null): FunkException {
            return FunkException("Runtime Error: $message", null, location)
        }
        
        /**
         * Create a type error exception
         */
        fun typeError(message: String, location: Node? = null): FunkException {
            return FunkException("Type Error: $message", null, location)
        }
        
        /**
         * Create an unbound variable error
         */
        fun unboundVariable(name: String, location: Node? = null): FunkException {
            return FunkException("Unbound variable: $name", null, location)
        }
        
        /**
         * Create a pattern match failure exception
         */
        fun patternMatchFailure(location: Node? = null): FunkException {
            return FunkException("Pattern match failure: no matching case found", null, location)
        }
        
        /**
         * Create an effect error exception
         */
        fun effectError(message: String, location: Node? = null): FunkException {
            return FunkException("Effect Error: $message", null, location)
        }
    }
}
