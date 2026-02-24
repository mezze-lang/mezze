package com.mezze.language.interpreter.nodes.literal

// NOTE: Core literal nodes (IntLiteralNode, BooleanLiteralNode, StringLiteralNode, NullLiteralNode)
// have been moved to Java for JIT compatibility. See:
// - com.mezze.language.interpreter.nodes.literal.IntLiteralNode (Java)
// - com.mezze.language.interpreter.nodes.literal.BooleanLiteralNode (Java)
// - com.mezze.language.interpreter.nodes.literal.StringLiteralNode (Java)
// - com.mezze.language.interpreter.nodes.literal.NullLiteralNode (Java)

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
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
import com.mezze.language.interpreter.MezzeNat
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
 * Natural number literal node
 */
@NodeInfo(shortName = "nat-literal")
class NatLiteralNode(private val value: Long) : LiteralNode() {

    override fun getValue(): MezzeValue {
        return MezzeNat(value)
    }

    override fun toString(): String = "NatLiteral(${value}n)"
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
 * Interpolated string node - evaluates parts and concatenates them.
 * Parts can be either StringLiteralNodes or expression nodes.
 */
@NodeInfo(shortName = "interpolated-string")
class InterpolatedStringNode(
    @field:Children private val partNodes: Array<MezzeNode>
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        // Collect string parts from child nodes
        val stringParts = Array(partNodes.size) { index ->
            val value = partNodes[index].execute(frame)
            valueToStringForInterpolation(value)
        }
        // Build final string via TruffleBoundary
        return buildInterpolatedString(stringParts)
    }

    /**
     * Convert a MezzeValue to a string for interpolation.
     * Only MezzeString values are allowed - other types must be explicitly converted first.
     * Marked as @TruffleBoundary because exception creation uses String operations.
     */
    @TruffleBoundary
    private fun valueToStringForInterpolation(value: MezzeValue): String {
        return when (value) {
            is MezzeString -> value.value
            else -> throw RuntimeException(
                "String interpolation requires a String value, but got ${value::class.simpleName}. " +
                "Use to_str{val = ...} to convert other types to String first."
            )
        }
    }

    /**
     * Build the final interpolated string.
     * Marked as @TruffleBoundary because StringBuilder operations may trigger blocklisted paths.
     */
    @TruffleBoundary
    private fun buildInterpolatedString(parts: Array<String>): MezzeString {
        val builder = StringBuilder()
        for (part in parts) {
            builder.append(part)
        }
        return MezzeString(builder.toString())
    }

    override fun toString(): String = "InterpolatedString(${partNodes.size} parts)"
}