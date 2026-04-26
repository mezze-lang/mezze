package com.mezze.interpreter.converter

import com.mezze.interpreter.nodes.MezzeNode
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.nodes.ExplodeLoop

/**
 * Node for string interpolation — assembles parts into a single string.
 */
@NodeInfo(shortName = "interpolated-string")
class InterpolatedStringNode(
    @field:Children private val parts: Array<MezzeNode>
) : MezzeNode() {

    @CompilationFinal
    private val partCount: Int = parts.size

    @ExplodeLoop
    override fun execute(frame: VirtualFrame): Any {
        if (partCount == 0) return ""
        if (partCount == 1) {
            val value = parts[0].execute(frame)
            return valueToString(value)
        }

        // Collect values first (JIT can unroll this)
        val values = arrayOfNulls<Any>(partCount)
        for (i in 0 until partCount) {
            values[i] = parts[i].execute(frame)
        }

        // Assemble string (boundary is outside the hot loop)
        return assembleString(values)
    }

    private fun valueToString(value: Any): String {
        return when (value) {
            is String -> value
            is Long -> value.toString()
            is Double -> value.toString()
            is Boolean -> value.toString()
            else -> slowToString(value)
        }
    }

    @TruffleBoundary
    private fun slowToString(value: Any): String {
        return value.toString()
    }

    @TruffleBoundary
    private fun assembleString(values: Array<Any?>): String {
        val sb = StringBuilder()
        for (value in values) {
            sb.append(value?.toString() ?: "null")
        }
        return sb.toString()
    }
}
