package com.funk.language.interpreter.nodes

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RootNode
import com.oracle.truffle.api.frame.FrameDescriptor
import com.funk.language.interpreter.FunkLanguage
import com.funk.language.interpreter.FunkContext
import com.funk.language.interpreter.FunkValue

/**
 * Base class for all Funk AST nodes in the Truffle interpreter.
 * Provides common functionality for accessing language and context.
 */
abstract class FunkNode : Node() {
    
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
}

/**
 * Base class for expression nodes that produce values.
 */
abstract class FunkExpressionNode : FunkNode() {
    
    /**
     * Execute this expression and return the result
     */
    abstract fun executeGeneric(frame: VirtualFrame): FunkValue
    
    /**
     * Execute and expect a specific type (with type checking)
     */
    inline fun <reified T : FunkValue> executeTyped(frame: VirtualFrame): T {
        val result = executeGeneric(frame)
        return if (result is T) {
            result
        } else {
            throw com.funk.language.interpreter.FunkException.typeError(
                "Expected ${T::class.simpleName} but got ${result::class.simpleName}",
                this
            )
        }
    }
}

/**
 * Base class for statement nodes that perform side effects.
 */
abstract class FunkStatementNode : FunkNode() {
    
    /**
     * Execute this statement
     */
    abstract fun executeVoid(frame: VirtualFrame)
}

/**
 * Root node for Funk programs and functions.
 * This is the entry point for execution.
 */
class FunkRootNode(
    language: FunkLanguage,
    frameDescriptor: FrameDescriptor,
    private val bodyNode: FunkExpressionNode,
    private val name: String = "<program>"
) : RootNode(language, frameDescriptor) {
    
    override fun execute(frame: VirtualFrame): Any {
        try {
            return bodyNode.executeGeneric(frame)
        } catch (e: com.funk.language.interpreter.FunkException) {
            throw e
        } catch (e: Exception) {
            throw com.funk.language.interpreter.FunkException.runtimeError(
                "Unexpected error: ${e.message}",
                bodyNode
            )
        }
    }
    
    override fun getName(): String = name
    
    override fun toString(): String = "FunkRootNode($name)"
}
