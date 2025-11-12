package com.funk.language.interpreter.nodes

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.funk.language.interpreter.FunkLanguage
import com.funk.language.interpreter.FunkContext
import com.funk.language.interpreter.FunkValue

/**
 * Base class for all Funk Truffle nodes.
 * Provides common functionality and access to language context.
 */
abstract class FunkNode : Node() {
    
    /**
     * Execute this node and return a FunkValue
     */
    abstract fun execute(frame: VirtualFrame): FunkValue
    
    /**
     * Get the current Funk language instance
     */
    protected fun getLanguage(): FunkLanguage {
        return FunkLanguage.getCurrentLanguage(this)
    }
    
    /**
     * Get the current Funk context
     */
    protected fun getContext(): FunkContext {
        return FunkLanguage.getCurrentContext(this)
    }
    
    /**
     * Execute a child node safely
     */
    protected fun executeChild(child: FunkNode?, frame: VirtualFrame): FunkValue {
        return child?.execute(frame) ?: throw RuntimeException("Null child node")
    }
    
    /**
     * Execute multiple child nodes
     */
    protected fun executeChildren(children: Array<FunkNode>, frame: VirtualFrame): Array<FunkValue> {
        return children.map { it.execute(frame) }.toTypedArray()
    }
}