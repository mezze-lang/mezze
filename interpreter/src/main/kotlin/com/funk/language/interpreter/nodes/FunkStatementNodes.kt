package com.funk.language.interpreter.nodes

import com.oracle.truffle.api.frame.VirtualFrame
import com.funk.language.interpreter.*

/**
 * Node for let binding statements
 */
class FunkLetBindingNode(
    private val variableName: String,
    private var expressionNode: FunkExpressionNode
) : FunkStatementNode() {
    
    override fun executeVoid(frame: VirtualFrame) {
        val value = expressionNode.executeGeneric(frame)
        val context = getContext()
        
        // Use defineGlobal for top-level let bindings so they're accessible across statements
        context.defineGlobal(variableName, value)
        
        // WORKAROUND: Also store in native-compatible global store for Native Image
        // This ensures variables are accessible even if Truffle context sharing is broken
        NativeGlobalStore.defineGlobal(variableName, value)
    }
}

/**
 * Node for expression statements
 */
class FunkExpressionStatementNode(
    private var expressionNode: FunkExpressionNode
) : FunkStatementNode() {
    
    override fun executeVoid(frame: VirtualFrame) {
        // Execute the expression but discard the result
        expressionNode.executeGeneric(frame)
    }
}

/**
 * Node for resume statements (effect system)
 */
class FunkResumeStatementNode(
    private var expressionNode: FunkExpressionNode
) : FunkStatementNode() {
    
    override fun executeVoid(frame: VirtualFrame) {
        // For now, just execute the expression (proper effect handling would be more complex)
        expressionNode.executeGeneric(frame)
    }
}
