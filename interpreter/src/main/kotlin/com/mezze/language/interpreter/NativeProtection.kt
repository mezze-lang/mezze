package com.mezze.language.interpreter

/**
 * Protection mechanism to prevent user code from defining functions starting with __native
 * This ensures all __native functions remain hidden implementation details
 */
object NativeProtection {
    
    /**
     * Check if a function name is reserved for native implementations
     */
    fun isReservedNativeName(name: String): Boolean {
        return name.startsWith("__native")
    }
    
    /**
     * Validate that user code cannot define reserved native functions
     * Throws an exception if the name is reserved
     */
    fun validateUserFunctionName(name: String) {
        if (isReservedNativeName(name)) {
            throw RuntimeException(
                "Function name '$name' is reserved for native implementations. " +
                "User code cannot define functions starting with '__native'."
            )
        }
    }
    
    /**
     * Check if a function should be hidden from user access
     */
    fun isHiddenFromUser(name: String): Boolean {
        return isReservedNativeName(name)
    }
    
    /**
     * Get error message for reserved name usage
     */
    fun getReservedNameError(name: String): String {
        return "Error: '$name' is a reserved native function name. " +
               "Functions starting with '__native' are internal implementation details " +
               "and cannot be accessed or defined in user code."
    }
}