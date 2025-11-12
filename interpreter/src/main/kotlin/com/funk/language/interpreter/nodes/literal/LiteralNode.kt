package com.funk.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.FunkFloat
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.FunkBool
import com.funk.language.interpreter.FunkNull

/**
 * Base class for literal value nodes
 */
abstract class LiteralNode : FunkNode() {
    
    /**
     * Literals don't need frame access - they return constant values
     */
    abstract fun getValue(): FunkValue
    
    override fun execute(frame: VirtualFrame): FunkValue {
        return getValue()
    }
}

/**
 * Integer literal node
 */
@NodeInfo(shortName = "int-literal")
class IntLiteralNode(private val value: Long) : LiteralNode() {
    
    override fun getValue(): FunkValue {
        return FunkInt(value)
    }
    
    override fun toString(): String = "IntLiteral($value)"
}

/**
 * Float literal node
 */
@NodeInfo(shortName = "float-literal")
class FloatLiteralNode(private val value: Double) : LiteralNode() {
    
    override fun getValue(): FunkValue {
        return FunkFloat(value)
    }
    
    override fun toString(): String = "FloatLiteral($value)"
}

/**
 * String literal node
 */
@NodeInfo(shortName = "string-literal")
class StringLiteralNode(private val value: String) : LiteralNode() {
    
    override fun getValue(): FunkValue {
        return FunkString(value)
    }
    
    override fun toString(): String = "StringLiteral(\"$value\")"
}

/**
 * Boolean literal node
 */
@NodeInfo(shortName = "boolean-literal")
class BooleanLiteralNode(private val value: Boolean) : LiteralNode() {
    
    override fun getValue(): FunkValue {
        return FunkBool(value)
    }
    
    override fun toString(): String = "BooleanLiteral($value)"
}

/**
 * Null literal node
 */
@NodeInfo(shortName = "null-literal")
class NullLiteralNode : LiteralNode() {
    
    override fun getValue(): FunkValue {
        return FunkNull
    }
    
    override fun toString(): String = "NullLiteral"
}