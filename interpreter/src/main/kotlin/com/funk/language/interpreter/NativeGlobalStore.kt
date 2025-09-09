package com.funk.language.interpreter

import java.util.concurrent.ConcurrentHashMap

/**
 * Global variable store that works with Native Image compilation.
 * 
 * This is a workaround for the issue where Native Image compilation
 * breaks Truffle context sharing between nodes, causing variables
 * defined in one let binding to be inaccessible in subsequent bindings.
 */
object NativeGlobalStore {
    
    /**
     * Global variables storage that persists across Native Image compilation
     */
    private val globals = ConcurrentHashMap<String, FunkValue>()
    
    /**
     * Store a global variable
     */
    fun defineGlobal(name: String, value: FunkValue) {
        globals[name] = value
    }
    
    /**
     * Retrieve a global variable
     */
    fun lookupGlobal(name: String): FunkValue? {
        return globals[name]
    }
    
    /**
     * Check if a global variable exists
     */
    fun hasGlobal(name: String): Boolean {
        return globals.containsKey(name)
    }
    
    /**
     * Clear all globals (for testing or new program execution)
     */
    fun clear() {
        globals.clear()
    }
    
    /**
     * Get all global variable names (for debugging)
     */
    fun getAllGlobals(): Set<String> {
        return globals.keys.toSet()
    }
}
