package com.funk.language.interpreter.nodes.comparison

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.FunkFloat
import com.funk.language.interpreter.FunkNat
import com.funk.language.interpreter.FunkFrac
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.FunkBool

/**
 * Base class for binary comparison operations with manual specializations
 */
abstract class BinaryComparisonNode(
    @field:Child protected var leftNode: FunkNode,
    @field:Child protected var rightNode: FunkNode
) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        val left = leftNode.execute(frame)
        val right = rightNode.execute(frame)
        return executeComparison(left, right)
    }
    
    protected abstract fun executeComparison(left: FunkValue, right: FunkValue): FunkBool
}

/**
 * Equality comparison node with manual type specializations
 */
@NodeInfo(shortName = "equal")
class EqualNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryComparisonNode(leftNode, rightNode) {
    
    override fun executeComparison(left: FunkValue, right: FunkValue): FunkBool {
        return when {
            left is FunkInt && right is FunkInt -> FunkBool(left.value == right.value)
            left is FunkFloat && right is FunkFloat -> FunkBool(left.value == right.value)
            left is FunkNat && right is FunkNat -> FunkBool(left.value == right.value)
            left is FunkFrac && right is FunkFrac -> {
                // Compare fractions: a/b == c/d iff ad == bc
                FunkBool(left.numerator * right.denominator == right.numerator * left.denominator)
            }
            left is FunkString && right is FunkString -> FunkBool(left.value == right.value)
            left is FunkBool && right is FunkBool -> FunkBool(left.value == right.value)
            else -> FunkBool(false) // Different types are never equal
        }
    }
}

/**
 * Less than comparison node with manual type specializations
 */
@NodeInfo(shortName = "less-than")
class LessThanNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryComparisonNode(leftNode, rightNode) {
    
    override fun executeComparison(left: FunkValue, right: FunkValue): FunkBool {
        return when {
            left is FunkInt && right is FunkInt -> FunkBool(left.value < right.value)
            left is FunkFloat && right is FunkFloat -> FunkBool(left.value < right.value)
            left is FunkNat && right is FunkNat -> FunkBool(left.value < right.value)
            left is FunkFrac && right is FunkFrac -> {
                // Compare fractions: a/b < c/d iff ad < bc (assuming positive denominators)
                FunkBool(left.numerator * right.denominator < right.numerator * left.denominator)
            }
            left is FunkString && right is FunkString -> FunkBool(left.value < right.value)
            else -> throw IllegalArgumentException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
}

/**
 * Greater than comparison node with manual type specializations
 */
@NodeInfo(shortName = "greater-than")
class GreaterThanNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryComparisonNode(leftNode, rightNode) {
    
    override fun executeComparison(left: FunkValue, right: FunkValue): FunkBool {
        return when {
            left is FunkInt && right is FunkInt -> FunkBool(left.value > right.value)
            left is FunkFloat && right is FunkFloat -> FunkBool(left.value > right.value)
            left is FunkNat && right is FunkNat -> FunkBool(left.value > right.value)
            left is FunkFrac && right is FunkFrac -> {
                // Compare fractions: a/b > c/d iff ad > bc (assuming positive denominators)
                FunkBool(left.numerator * right.denominator > right.numerator * left.denominator)
            }
            left is FunkString && right is FunkString -> FunkBool(left.value > right.value)
            else -> throw IllegalArgumentException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
}

/**
 * Less than or equal comparison node with manual type specializations
 */
@NodeInfo(shortName = "less-equal")
class LessEqualNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryComparisonNode(leftNode, rightNode) {
    
    override fun executeComparison(left: FunkValue, right: FunkValue): FunkBool {
        return when {
            left is FunkInt && right is FunkInt -> FunkBool(left.value <= right.value)
            left is FunkFloat && right is FunkFloat -> FunkBool(left.value <= right.value)
            left is FunkNat && right is FunkNat -> FunkBool(left.value <= right.value)
            left is FunkFrac && right is FunkFrac -> {
                FunkBool(left.numerator * right.denominator <= right.numerator * left.denominator)
            }
            left is FunkString && right is FunkString -> FunkBool(left.value <= right.value)
            else -> throw IllegalArgumentException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
}

/**
 * Greater than or equal comparison node with manual type specializations
 */
@NodeInfo(shortName = "greater-equal")
class GreaterEqualNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryComparisonNode(leftNode, rightNode) {
    
    override fun executeComparison(left: FunkValue, right: FunkValue): FunkBool {
        return when {
            left is FunkInt && right is FunkInt -> FunkBool(left.value >= right.value)
            left is FunkFloat && right is FunkFloat -> FunkBool(left.value >= right.value)
            left is FunkNat && right is FunkNat -> FunkBool(left.value >= right.value)
            left is FunkFrac && right is FunkFrac -> {
                FunkBool(left.numerator * right.denominator >= right.numerator * left.denominator)
            }
            left is FunkString && right is FunkString -> FunkBool(left.value >= right.value)
            else -> throw IllegalArgumentException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
}