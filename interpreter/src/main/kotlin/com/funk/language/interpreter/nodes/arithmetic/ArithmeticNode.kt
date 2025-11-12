package com.funk.language.interpreter.nodes.arithmetic

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.FunkFloat
import com.funk.language.interpreter.FunkNat
import com.funk.language.interpreter.FunkFrac

/**
 * Base class for binary arithmetic operations with manual specializations
 */
abstract class BinaryArithmeticNode(
    @field:Child protected var leftNode: FunkNode,
    @field:Child protected var rightNode: FunkNode
) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        val left = leftNode.execute(frame)
        val right = rightNode.execute(frame)
        return executeArithmetic(left, right)
    }
    
    protected abstract fun executeArithmetic(left: FunkValue, right: FunkValue): FunkValue
}

/**
 * Addition node with manual type specializations
 */
@NodeInfo(shortName = "add")
class AddNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryArithmeticNode(leftNode, rightNode) {
    
    override fun executeArithmetic(left: FunkValue, right: FunkValue): FunkValue {
        return when {
            left is FunkInt && right is FunkInt -> {
                try {
                    FunkInt(Math.addExact(left.value, right.value))
                } catch (e: ArithmeticException) {
                    throw ArithmeticException("Integer overflow in addition: ${left.value} + ${right.value}")
                }
            }
            left is FunkFloat && right is FunkFloat -> {
                FunkFloat(left.value + right.value)
            }
            left is FunkNat && right is FunkNat -> {
                FunkNat(left.value + right.value)
            }
            left is FunkFrac && right is FunkFrac -> {
                val newNumerator = left.numerator * right.denominator + right.numerator * left.denominator
                val newDenominator = left.denominator * right.denominator
                FunkFrac(newNumerator, newDenominator).reduce()
            }
            else -> throw IllegalArgumentException("Cannot add ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
}

/**
 * Subtraction node with manual type specializations
 */
@NodeInfo(shortName = "subtract")
class SubtractNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryArithmeticNode(leftNode, rightNode) {
    
    override fun executeArithmetic(left: FunkValue, right: FunkValue): FunkValue {
        return when {
            left is FunkInt && right is FunkInt -> {
                try {
                    FunkInt(Math.subtractExact(left.value, right.value))
                } catch (e: ArithmeticException) {
                    throw ArithmeticException("Integer overflow in subtraction: ${left.value} - ${right.value}")
                }
            }
            left is FunkFloat && right is FunkFloat -> {
                FunkFloat(left.value - right.value)
            }
            left is FunkNat && right is FunkNat -> {
                // Nat - Nat can be negative, so return Int
                FunkInt(left.value - right.value)
            }
            left is FunkFrac && right is FunkFrac -> {
                val newNumerator = left.numerator * right.denominator - right.numerator * left.denominator
                val newDenominator = left.denominator * right.denominator
                FunkFrac(newNumerator, newDenominator).reduce()
            }
            else -> throw IllegalArgumentException("Cannot subtract ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
}

/**
 * Multiplication node with manual type specializations
 */
@NodeInfo(shortName = "multiply")
class MultiplyNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryArithmeticNode(leftNode, rightNode) {
    
    override fun executeArithmetic(left: FunkValue, right: FunkValue): FunkValue {
        return when {
            left is FunkInt && right is FunkInt -> {
                try {
                    FunkInt(Math.multiplyExact(left.value, right.value))
                } catch (e: ArithmeticException) {
                    throw ArithmeticException("Integer overflow in multiplication: ${left.value} * ${right.value}")
                }
            }
            left is FunkFloat && right is FunkFloat -> {
                FunkFloat(left.value * right.value)
            }
            left is FunkNat && right is FunkNat -> {
                FunkNat(left.value * right.value)
            }
            left is FunkFrac && right is FunkFrac -> {
                val newNumerator = left.numerator * right.numerator
                val newDenominator = left.denominator * right.denominator
                FunkFrac(newNumerator, newDenominator).reduce()
            }
            else -> throw IllegalArgumentException("Cannot multiply ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
}

/**
 * Division node with manual type specializations
 */
@NodeInfo(shortName = "divide")
class DivideNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryArithmeticNode(leftNode, rightNode) {
    
    override fun executeArithmetic(left: FunkValue, right: FunkValue): FunkValue {
        return when {
            left is FunkInt && right is FunkInt -> {
                if (right.value == 0L) throw ArithmeticException("Division by zero")
                FunkInt(left.value / right.value)
            }
            left is FunkFloat && right is FunkFloat -> {
                if (right.value == 0.0) throw ArithmeticException("Division by zero")
                FunkFloat(left.value / right.value)
            }
            left is FunkNat && right is FunkNat -> {
                if (right.value == 0L) throw ArithmeticException("Division by zero")
                FunkNat(left.value / right.value)
            }
            left is FunkFrac && right is FunkFrac -> {
                if (right.numerator == 0L) throw ArithmeticException("Division by zero")
                val newNumerator = left.numerator * right.denominator
                val newDenominator = left.denominator * right.numerator
                FunkFrac(newNumerator, newDenominator).reduce()
            }
            else -> throw IllegalArgumentException("Cannot divide ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
}