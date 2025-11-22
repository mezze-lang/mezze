package com.funk.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.nodes.Node.Children
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

/**
 * Interpolated string node - evaluates parts and concatenates them.
 * Parts can be either StringLiteralNodes or expression nodes.
 */
@NodeInfo(shortName = "interpolated-string")
class InterpolatedStringNode(
    @field:Children private val partNodes: Array<FunkNode>
) : FunkNode() {

    override fun execute(frame: VirtualFrame): FunkValue {
        val builder = StringBuilder()
        for (partNode in partNodes) {
            val value = partNode.execute(frame)
            builder.append(valueToStringForInterpolation(value))
        }
        return FunkString(builder.toString())
    }

    /**
     * Convert a FunkValue to a string for interpolation.
     * Only FunkString values are allowed - other types must be explicitly converted first.
     */
    private fun valueToStringForInterpolation(value: FunkValue): String {
        return when (value) {
            is FunkString -> value.value
            else -> throw RuntimeException(
                "String interpolation requires a String value, but got ${value::class.simpleName}. " +
                "Use to_str{val = ...} to convert other types to String first."
            )
        }
    }

    override fun toString(): String = "InterpolatedString(${partNodes.size} parts)"
}