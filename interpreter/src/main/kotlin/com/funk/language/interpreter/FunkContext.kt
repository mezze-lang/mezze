package com.funk.language.interpreter

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.frame.MaterializedFrame
import com.oracle.truffle.api.nodes.RootNode
import java.util.concurrent.ConcurrentHashMap

/**
 * Execution context for the Funk language.
 * Manages global state, built-in functions, and runtime environment.
 */
class FunkContext(
    val language: FunkLanguage,
    val env: TruffleLanguage.Env?
) {
    
    /**
     * Global variables and functions
     */
    private val globals = ConcurrentHashMap<String, FunkValue>()
    
    /**
     * Local variables (for function scopes, blocks, etc.)
     */
    val locals = ConcurrentHashMap<String, FunkValue>()
    
    /**
     * Built-in functions and effects
     */
    private val builtins = ConcurrentHashMap<String, FunkValue>()
    
    
    /**
     * Module runtime for handling imports and exports
     */
    val moduleRuntime = ModuleRuntime()
    
    
    init {
        initializeBuiltins()
    }
    
    
    /**
     * Define a global variable or function
     */
    fun defineGlobal(name: String, value: FunkValue) {
        globals[name] = value
    }
    
    /**
     * Look up a global variable or function
     */
    fun lookupGlobal(name: String): FunkValue? {
        return locals[name] ?: globals[name] ?: builtins[name]
    }
    
    /**
     * Check if a global exists
     */
    fun hasGlobal(name: String): Boolean {
        return globals.containsKey(name) || builtins.containsKey(name)
    }
    
    /**
     * Initialize built-in functions and effects
     */
    private fun initializeBuiltins() {
        // Basic arithmetic and comparison operations will be handled by specialized nodes
        // Built-in effects and functions will be added here
        
        // Register built-in effects
        // Built-in effects initialization removed
    }
    
    /**
     * Create a new frame for function execution
     */
    fun createFrame(frameDescriptor: FrameDescriptor): VirtualFrame {
        // TODO: Implement proper frame creation
        throw UnsupportedOperationException("Frame creation not yet implemented")
    }
    
    /**
     * Define a local variable (for function scopes, blocks, etc.)
     */
    fun defineLocal(name: String, value: FunkValue) {
        locals[name] = value
    }
    
    /**
     * Create a child context for nested scopes
     */
    fun createChildContext(): FunkContext {
        val child = FunkContext(language, env)
        // Copy current locals and globals to child
        child.locals.putAll(this.locals)
        child.globals.putAll(this.globals)
        child.builtins.putAll(this.builtins)
        return child
    }
    
    /**
     * Capture current environment for closures
     */
    fun captureEnvironment(): Map<String, FunkValue> {
        val captured = mutableMapOf<String, FunkValue>()
        captured.putAll(locals)
        captured.putAll(globals)
        return captured
    }
    
}
