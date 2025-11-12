package com.funk.language.interpreter.nodes.control

import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.profiles.ConditionProfile
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkBool
import com.funk.language.interpreter.FunkNull

/**
 * If-then-else conditional node with branch profiling
 */
@NodeInfo(shortName = "if-then-else")
class IfNode(
    @field:Child private var conditionNode: FunkNode,
    @field:Child private var thenNode: FunkNode,
    @field:Child private var elseNode: FunkNode?
) : FunkNode() {
    
    @Suppress("DEPRECATION")
    private val conditionProfile = ConditionProfile.createCountingProfile()
    
    override fun execute(frame: VirtualFrame): FunkValue {
        val condition = conditionNode.execute(frame)
        
        val isTrue = when (condition) {
            is FunkBool -> condition.value
            else -> throw RuntimeException("Condition must be boolean, got ${condition::class.simpleName}")
        }
        
        return if (conditionProfile.profile(isTrue)) {
            thenNode.execute(frame)
        } else {
            elseNode?.execute(frame) ?: FunkNull
        }
    }
}

/**
 * Block node for executing multiple statements sequentially
 */
@NodeInfo(shortName = "block")
class BlockNode(@field:Children private val statements: Array<FunkNode>) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        var result: FunkValue = FunkNull
        
        for (statement in statements) {
            result = statement.execute(frame)
        }
        
        return result
    }
}

/**
 * Sequence node for executing statements and returning the last result
 */
@NodeInfo(shortName = "sequence")
class SequenceNode(@field:Children private val nodes: Array<FunkNode>) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        if (nodes.isEmpty()) {
            return FunkNull
        }
        
        var result: FunkValue = FunkNull
        for (node in nodes) {
            result = node.execute(frame)
        }
        
        return result
    }
}

/**
 * While loop node with loop profiling
 */
@NodeInfo(shortName = "while-loop")
class WhileNode(
    @field:Child private var conditionNode: FunkNode,
    @field:Child private var bodyNode: FunkNode
) : FunkNode() {
    
    @Suppress("DEPRECATION")
    private val conditionProfile = ConditionProfile.createCountingProfile()
    
    override fun execute(frame: VirtualFrame): FunkValue {
        var result: FunkValue = FunkNull
        
        while (true) {
            val condition = conditionNode.execute(frame)
            
            val shouldContinue = when (condition) {
                is FunkBool -> condition.value
                else -> throw RuntimeException("Loop condition must be boolean, got ${condition::class.simpleName}")
            }
            
            if (!conditionProfile.profile(shouldContinue)) {
                break
            }
            
            result = bodyNode.execute(frame)
        }
        
        return result
    }
}