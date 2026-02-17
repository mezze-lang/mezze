package com.mezze.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.nodes.Node.Children
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeFloat
import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeBool
import com.mezze.language.interpreter.MezzeFrac
import com.mezze.language.interpreter.MezzeNull

/**
 * Base class for literal value nodes
 */
abstract class LiteralNode : MezzeNode() {
    
    /**
     * Literals don't need frame access - they return constant values
     */
    abstract fun getValue(): MezzeValue
    
    override fun execute(frame: VirtualFrame): MezzeValue {
        return getValue()
    }
}

/**
 * Integer literal node
 */
@NodeInfo(shortName = "int-literal")
class IntLiteralNode(private val value: Long) : LiteralNode() {
    
    override fun getValue(): MezzeValue {
        return MezzeInt(value)
    }
    
    override fun toString(): String = "IntLiteral($value)"
}

/**
 * Float literal node
 */
@NodeInfo(shortName = "float-literal")
class FloatLiteralNode(private val value: Double) : LiteralNode() {
    
    override fun getValue(): MezzeValue {
        return MezzeFloat(value)
    }
    
    override fun toString(): String = "FloatLiteral($value)"
}

/**
 * String literal node
 */
@NodeInfo(shortName = "string-literal")
class StringLiteralNode(private val value: String) : LiteralNode() {
    
    override fun getValue(): MezzeValue {
        return MezzeString(value)
    }
    
    override fun toString(): String = "StringLiteral(\"$value\")"
}

/**
 * Boolean literal node
 */
@NodeInfo(shortName = "boolean-literal")
class BooleanLiteralNode(private val value: Boolean) : LiteralNode() {

    override fun getValue(): MezzeValue {
        return MezzeBool(value)
    }

    override fun toString(): String = "BooleanLiteral($value)"
}

/**
 * Fraction literal node
 */
@NodeInfo(shortName = "fraction-literal")
class FractionLiteralNode(private val numerator: Long, private val denominator: Long) : LiteralNode() {

    override fun getValue(): MezzeValue {
        return MezzeFrac(numerator, denominator)
    }

    override fun toString(): String = "FractionLiteral($numerator/$denominator)"
}

/**
 * Null literal node
 */
@NodeInfo(shortName = "null-literal")
class NullLiteralNode : LiteralNode() {

    override fun getValue(): MezzeValue {
        return MezzeNull
    }

    override fun toString(): String = "NullLiteral"
}

/**
 * Interpolated string node - evaluates parts and concatenates them.
 * Parts can be either StringLiteralNodes or expression nodes.
 */
@NodeInfo(shortName = "interpolated-string")
class InterpolatedStringNode(
    @field:Children private val partNodes: Array<MezzeNode>
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val builder = StringBuilder()
        for (partNode in partNodes) {
            val value = partNode.execute(frame)
            builder.append(valueToStringForInterpolation(value))
        }
        return MezzeString(builder.toString())
    }

    /**
     * Convert a MezzeValue to a string for interpolation.
     * Only MezzeString values are allowed - other types must be explicitly converted first.
     */
    private fun valueToStringForInterpolation(value: MezzeValue): String {
        return when (value) {
            is MezzeString -> value.value
            else -> throw RuntimeException(
                "String interpolation requires a String value, but got ${value::class.simpleName}. " +
                "Use to_str{val = ...} to convert other types to String first."
            )
        }
    }

    override fun toString(): String = "InterpolatedString(${partNodes.size} parts)"
}