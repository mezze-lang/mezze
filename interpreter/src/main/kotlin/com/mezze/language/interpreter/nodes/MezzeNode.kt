package com.mezze.language.interpreter.nodes

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.mezze.language.interpreter.MezzeLanguage
import com.mezze.language.interpreter.MezzeContext
import com.mezze.language.interpreter.MezzeValue

/**
 * Base class for all Mezze Truffle nodes.
 * Provides common functionality and access to language context.
 */
abstract class MezzeNode : Node() {
    
    /**
     * Execute this node and return a MezzeValue
     */
    abstract fun execute(frame: VirtualFrame): MezzeValue
    
    /**
     * Get the current Mezze language instance
     */
    protected fun getLanguage(): MezzeLanguage {
        return MezzeLanguage.getCurrentLanguage(this)
    }
    
    /**
     * Get the current Mezze context
     */
    protected fun getContext(): MezzeContext {
        return MezzeLanguage.getCurrentContext(this)
    }
    
    /**
     * Execute a child node safely
     */
    protected fun executeChild(child: MezzeNode?, frame: VirtualFrame): MezzeValue {
        return child?.execute(frame) ?: throw RuntimeException("Null child node")
    }
    
    /**
     * Execute multiple child nodes
     */
    protected fun executeChildren(children: Array<MezzeNode>, frame: VirtualFrame): Array<MezzeValue> {
        return children.map { it.execute(frame) }.toTypedArray()
    }
}