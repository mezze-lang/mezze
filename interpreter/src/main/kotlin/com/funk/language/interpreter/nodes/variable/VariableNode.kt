package com.funk.language.interpreter.nodes.variable

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkNull

/**
 * Node for reading variables from the frame (simplified without DSL)
 */
@NodeInfo(shortName = "read-var")
class ReadVariableNode(private val slot: Int) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        val value = frame.getObject(slot)
        return when (value) {
            is FunkValue -> value
            null -> FunkNull
            else -> throw RuntimeException("Invalid value type in frame slot: ${value::class}")
        }
    }
}

/**
 * Node for writing variables to the frame (simplified without DSL)
 */
@NodeInfo(shortName = "write-var")
class WriteVariableNode(
    private val slot: Int,
    @field:Child private var valueNode: FunkNode
) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        val value = valueNode.execute(frame)
        frame.setObject(slot, value)
        return value
    }
}

/**
 * Simple variable read node without specialization (for compatibility)
 */
@NodeInfo(shortName = "simple-read-var")
class SimpleReadVariableNode(private val slot: Int) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        val value = frame.getObject(slot)
        return when (value) {
            is FunkValue -> value
            null -> FunkNull
            else -> throw RuntimeException("Invalid value type in frame slot: ${value::class}")
        }
    }
}

/**
 * Simple variable write node without specialization (for compatibility)
 */
@NodeInfo(shortName = "simple-write-var")
class SimpleWriteVariableNode(
    private val slot: Int,
    @field:Child private var valueNode: FunkNode
) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        val value = valueNode.execute(frame)
        frame.setObject(slot, value)
        return value
    }
}