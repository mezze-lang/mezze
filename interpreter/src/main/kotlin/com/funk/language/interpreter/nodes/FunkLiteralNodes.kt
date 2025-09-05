package com.funk.language.interpreter.nodes

import com.oracle.truffle.api.frame.VirtualFrame
import com.funk.language.interpreter.*

/**
 * Node for null/unit literal values
 */
class FunkNullLiteralNode : FunkExpressionNode() {
    override fun executeGeneric(frame: VirtualFrame): FunkValue = FunkNull
}

/**
 * Node for integer literal values
 */
class FunkIntLiteralNode(private val value: Long) : FunkExpressionNode() {
    override fun executeGeneric(frame: VirtualFrame): FunkValue = FunkInt(value)
}

/**
 * Node for floating point literal values
 */
class FunkFloatLiteralNode(private val value: Double) : FunkExpressionNode() {
    override fun executeGeneric(frame: VirtualFrame): FunkValue = FunkFloat(value)
}

/**
 * Node for string literal values
 */
class FunkStringLiteralNode(private val value: String) : FunkExpressionNode() {
    override fun executeGeneric(frame: VirtualFrame): FunkValue = FunkString(value)
}

/**
 * Node for boolean literal values
 */
class FunkBooleanLiteralNode(private val value: Boolean) : FunkExpressionNode() {
    override fun executeGeneric(frame: VirtualFrame): FunkValue = FunkBoolean(value)
}
