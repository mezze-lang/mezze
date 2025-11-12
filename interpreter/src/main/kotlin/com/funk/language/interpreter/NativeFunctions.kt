package com.funk.language.interpreter

import com.funk.language.interpreter.stdlib.TruffleNativeImplementations

/**
 * Registry of native functions available in the Funk runtime
 * Updated for new ability-based architecture with hidden native functions
 */
object NativeFunctions {
    
    /**
     * Register all native functions in the given context
     * This includes both legacy functions (for ability implementations) and hidden __native_ functions
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Register all Truffle standard library runtime implementations
        TruffleNativeImplementations.registerAll(context)
        
        // Apply native protection - filter out __native_ functions from user access
        applyNativeProtection(context)
    }
    
    /**
     * Apply native protection to hide __native_ functions from user access
     */
    private fun applyNativeProtection(context: MutableMap<String, FunkValue>) {
        // Note: The actual hiding mechanism would be implemented in the interpreter's
        // variable lookup logic, not by removing from context (since abilities need them)
    }
    
    /**
     * Get version information about the native functions
     */
    fun getVersion(): String = "1.0.0"
    
    /**
     * Get list of all available runtime functions by category (user-accessible only)
     */
    fun getRuntimeFunctionsByCategory(): Map<String, List<String>> = mapOf(
        "Legacy Arithmetic" to listOf("add_int", "subtract_int", "multiply_int", "divide_int"),
        "Legacy Array" to listOf("arrayLength", "arrayGet", "arraySet", "arrayAdd"),
        "Legacy Conversion" to listOf("intToString", "natToInt", "intToNat"),
        "Note" to listOf("All __native_* functions are hidden from user code")
    )
    
    /**
     * Check if a function name is accessible to user code
     */
    fun isUserAccessible(functionName: String): Boolean {
        return !NativeProtection.isHiddenFromUser(functionName)
    }
}