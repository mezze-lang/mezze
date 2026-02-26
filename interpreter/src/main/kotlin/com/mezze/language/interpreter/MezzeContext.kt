package com.mezze.language.interpreter

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.frame.MaterializedFrame
import com.oracle.truffle.api.nodes.RootNode
import java.util.concurrent.ConcurrentHashMap

/**
 * Execution context for the Mezze language.
 * Manages global state, built-in functions, and runtime environment.
 */
class MezzeContext(
    val language: MezzeLanguage,
    val env: TruffleLanguage.Env?
) {

    /**
     * Global variables and functions
     */
    private val globals = ConcurrentHashMap<String, MezzeValue>()

    /**
     * Local variables (for function scopes, blocks, etc.)
     */
    val locals = ConcurrentHashMap<String, MezzeValue>()

    /**
     * Built-in functions and effects
     */
    private val builtins = ConcurrentHashMap<String, MezzeValue>()

    /**
     * Effect handler for managing effect handlers and effect calls
     */
    val effectHandler = EffectHandler()


    // Module runtime removed for simplified stub implementation


    init {
        // Register effect handler globally so spawned threads can access it
        TruffleContextHolder.setEffectHandler(effectHandler)
        initializeBuiltins()
    }
    
    
    /**
     * Define a global variable or function
     */
    fun defineGlobal(name: String, value: MezzeValue) {
        globals[name] = value
    }
    
    /**
     * Look up a global variable or function
     */
    fun lookupGlobal(name: String): MezzeValue? {
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
        // Register all native functions using the existing system
        NativeFunctions.registerAll(builtins as MutableMap<String, MezzeValue>)
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
    fun defineLocal(name: String, value: MezzeValue) {
        locals[name] = value
    }
    
    /**
     * Create a child context for nested scopes
     */
    fun createChildContext(): MezzeContext {
        val child = MezzeContext(language, env)
        // Copy current locals and globals to child
        child.locals.putAll(this.locals)
        child.globals.putAll(this.globals)
        child.builtins.putAll(this.builtins)
        return child
    }
    
    /**
     * Capture current environment for closures
     */
    fun captureEnvironment(): Map<String, MezzeValue> {
        val captured = mutableMapOf<String, MezzeValue>()
        captured.putAll(locals)
        captured.putAll(globals)
        return captured
    }

    /**
     * Get all global variables (including builtins)
     */
    fun getAllGlobals(): Map<String, MezzeValue> {
        val all = mutableMapOf<String, MezzeValue>()
        all.putAll(builtins)
        all.putAll(globals)
        return all
    }

    /**
     * Get all local variables (for debugging)
     */
    fun getLocals(): Map<String, MezzeValue> {
        return locals.toMap()
    }

}
