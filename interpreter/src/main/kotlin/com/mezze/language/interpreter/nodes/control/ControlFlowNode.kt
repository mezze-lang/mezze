package com.mezze.language.interpreter.nodes.control

import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.profiles.ConditionProfile
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeBool
import com.mezze.language.interpreter.MezzeNull

/**
 * If-then-else conditional node with branch profiling
 */
@NodeInfo(shortName = "if-then-else")
class IfNode(
    @field:Child private var conditionNode: MezzeNode,
    @field:Child private var thenNode: MezzeNode,
    @field:Child private var elseNode: MezzeNode?
) : MezzeNode() {
    
    @Suppress("DEPRECATION")
    private val conditionProfile = ConditionProfile.createCountingProfile()
    
    override fun execute(frame: VirtualFrame): MezzeValue {
        val condition = conditionNode.execute(frame)
        
        val isTrue = when (condition) {
            is MezzeBool -> condition.value
            else -> throw RuntimeException("Condition must be boolean, got ${condition::class.simpleName}")
        }
        
        return if (conditionProfile.profile(isTrue)) {
            thenNode.execute(frame)
        } else {
            elseNode?.execute(frame) ?: MezzeNull
        }
    }
}

/**
 * Block node for executing multiple statements sequentially
 */
@NodeInfo(shortName = "block")
class BlockNode(@field:Children private val statements: Array<MezzeNode>) : MezzeNode() {
    
    override fun execute(frame: VirtualFrame): MezzeValue {
        var result: MezzeValue = MezzeNull
        
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
class SequenceNode(@field:Children private val nodes: Array<MezzeNode>) : MezzeNode() {
    
    override fun execute(frame: VirtualFrame): MezzeValue {
        if (nodes.isEmpty()) {
            return MezzeNull
        }
        
        var result: MezzeValue = MezzeNull
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
    @field:Child private var conditionNode: MezzeNode,
    @field:Child private var bodyNode: MezzeNode
) : MezzeNode() {
    
    @Suppress("DEPRECATION")
    private val conditionProfile = ConditionProfile.createCountingProfile()
    
    override fun execute(frame: VirtualFrame): MezzeValue {
        var result: MezzeValue = MezzeNull
        
        while (true) {
            val condition = conditionNode.execute(frame)
            
            val shouldContinue = when (condition) {
                is MezzeBool -> condition.value
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