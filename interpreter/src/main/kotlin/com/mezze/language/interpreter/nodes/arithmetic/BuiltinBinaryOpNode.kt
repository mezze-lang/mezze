package com.mezze.language.interpreter.nodes.arithmetic

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeFloat
import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeBool
import com.mezze.language.interpreter.MezzeNat
import com.mezze.language.interpreter.MezzeFrac
import com.mezze.language.interpreter.MezzeList
import com.mezze.language.interpreter.MezzeVariant

/**
 * Node for built-in binary operations.
 * Extracts left and right operands from a record and performs the operation.
 *
 * All record field access is done via @TruffleBoundary methods to avoid
 * Map operations in the JIT compilation path.
 */
@NodeInfo(shortName = "builtin-binary-op")
class BuiltinBinaryOpNode(
    @field:Child private var argumentsChild: MezzeNode,
    private val operation: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val argsValue = argumentsChild.execute(frame)
        // Delegate all logic to TruffleBoundary method
        return executeOperation(argsValue)
    }

    /**
     * Extract operands and execute the binary operation.
     * Marked as @TruffleBoundary because record field access involves Map operations.
     */
    @TruffleBoundary
    private fun executeOperation(argsValue: MezzeValue): MezzeValue {
        if (argsValue !is MezzeRecord) {
            throw RuntimeException("Binary operation arguments must be a record, got: ${argsValue::class.simpleName}")
        }

        val left = argsValue.fields["l"]
            ?: throw RuntimeException("Missing 'l' field in binary operation (available fields: ${argsValue.fields.keys})")
        val right = argsValue.fields["r"]
            ?: throw RuntimeException("Missing 'r' field in binary operation (available fields: ${argsValue.fields.keys})")

        return when (operation) {
            // Arithmetic operations
            "add" -> executeAdd(left, right)
            "subtract" -> executeSubtract(left, right)
            "multiply" -> executeMultiply(left, right)
            "divide" -> executeDivide(left, right)
            "modulo" -> executeModulo(left, right)
            // Comparison operations
            "equal" -> MezzeBool(structuralEquals(left, right))
            "notEqual" -> MezzeBool(!structuralEquals(left, right))
            "lessThan" -> executeLessThan(left, right)
            "greaterThan" -> executeGreaterThan(left, right)
            "lessEqual" -> executeLessEqual(left, right)
            "greaterEqual" -> executeGreaterEqual(left, right)
            // Logical operations
            "and" -> executeAnd(left, right)
            "or" -> executeOr(left, right)
            else -> throw RuntimeException("Unsupported binary operation: $operation")
        }
    }

    private fun executeAdd(left: MezzeValue, right: MezzeValue): MezzeValue {
        return when {
            left is MezzeInt && right is MezzeInt -> MezzeInt(left.value + right.value)
            left is MezzeFloat && right is MezzeFloat -> MezzeFloat(left.value + right.value)
            left is MezzeNat && right is MezzeNat -> MezzeNat(left.value + right.value)
            left is MezzeFrac && right is MezzeFrac -> {
                val newNumerator = left.numerator * right.denominator + right.numerator * left.denominator
                val newDenominator = left.denominator * right.denominator
                MezzeFrac(newNumerator, newDenominator).reduce()
            }
            else -> throw RuntimeException("Cannot add ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }

    private fun executeSubtract(left: MezzeValue, right: MezzeValue): MezzeValue {
        return when {
            left is MezzeInt && right is MezzeInt -> MezzeInt(left.value - right.value)
            left is MezzeFloat && right is MezzeFloat -> MezzeFloat(left.value - right.value)
            left is MezzeNat && right is MezzeNat -> MezzeInt(left.value - right.value) // Nat - Nat can be negative
            left is MezzeFrac && right is MezzeFrac -> {
                val newNumerator = left.numerator * right.denominator - right.numerator * left.denominator
                val newDenominator = left.denominator * right.denominator
                MezzeFrac(newNumerator, newDenominator).reduce()
            }
            else -> throw RuntimeException("Cannot subtract ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }

    private fun executeMultiply(left: MezzeValue, right: MezzeValue): MezzeValue {
        return when {
            left is MezzeInt && right is MezzeInt -> MezzeInt(left.value * right.value)
            left is MezzeFloat && right is MezzeFloat -> MezzeFloat(left.value * right.value)
            left is MezzeNat && right is MezzeNat -> MezzeNat(left.value * right.value)
            left is MezzeFrac && right is MezzeFrac -> {
                val newNumerator = left.numerator * right.numerator
                val newDenominator = left.denominator * right.denominator
                MezzeFrac(newNumerator, newDenominator).reduce()
            }
            else -> throw RuntimeException("Cannot multiply ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }

    private fun executeDivide(left: MezzeValue, right: MezzeValue): MezzeValue {
        return when {
            left is MezzeInt && right is MezzeInt -> MezzeInt(left.value / right.value)
            left is MezzeFloat && right is MezzeFloat -> MezzeFloat(left.value / right.value)
            left is MezzeNat && right is MezzeNat -> {
                if (right.value == 0L) throw ArithmeticException("Division by zero")
                MezzeNat(left.value / right.value)
            }
            left is MezzeFrac && right is MezzeFrac -> {
                if (right.numerator == 0L) throw ArithmeticException("Division by zero")
                val newNumerator = left.numerator * right.denominator
                val newDenominator = left.denominator * right.numerator
                MezzeFrac(newNumerator, newDenominator).reduce()
            }
            else -> throw RuntimeException("Cannot divide ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }

    private fun executeModulo(left: MezzeValue, right: MezzeValue): MezzeValue {
        return when {
            left is MezzeInt && right is MezzeInt -> {
                if (right.value == 0L) throw ArithmeticException("Modulo by zero")
                MezzeInt(left.value % right.value)
            }
            left is MezzeNat && right is MezzeNat -> {
                if (right.value == 0L) throw ArithmeticException("Modulo by zero")
                MezzeNat(left.value % right.value)
            }
            else -> throw RuntimeException("Cannot compute modulo for ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }

    private fun executeLessThan(left: MezzeValue, right: MezzeValue): MezzeValue {
        return when {
            left is MezzeInt && right is MezzeInt -> MezzeBool(left.value < right.value)
            left is MezzeFloat && right is MezzeFloat -> MezzeBool(left.value < right.value)
            left is MezzeNat && right is MezzeNat -> MezzeBool(left.value < right.value)
            left is MezzeFrac && right is MezzeFrac ->
                MezzeBool(left.numerator * right.denominator < right.numerator * left.denominator)
            else -> throw RuntimeException("Cannot compare ${left::class.simpleName} < ${right::class.simpleName}")
        }
    }

    private fun executeGreaterThan(left: MezzeValue, right: MezzeValue): MezzeValue {
        return when {
            left is MezzeInt && right is MezzeInt -> MezzeBool(left.value > right.value)
            left is MezzeFloat && right is MezzeFloat -> MezzeBool(left.value > right.value)
            left is MezzeNat && right is MezzeNat -> MezzeBool(left.value > right.value)
            left is MezzeFrac && right is MezzeFrac ->
                MezzeBool(left.numerator * right.denominator > right.numerator * left.denominator)
            else -> throw RuntimeException("Cannot compare ${left::class.simpleName} > ${right::class.simpleName}")
        }
    }

    private fun executeLessEqual(left: MezzeValue, right: MezzeValue): MezzeValue {
        return when {
            left is MezzeInt && right is MezzeInt -> MezzeBool(left.value <= right.value)
            left is MezzeFloat && right is MezzeFloat -> MezzeBool(left.value <= right.value)
            left is MezzeNat && right is MezzeNat -> MezzeBool(left.value <= right.value)
            left is MezzeFrac && right is MezzeFrac ->
                MezzeBool(left.numerator * right.denominator <= right.numerator * left.denominator)
            else -> throw RuntimeException("Cannot compare ${left::class.simpleName} <= ${right::class.simpleName}")
        }
    }

    private fun executeGreaterEqual(left: MezzeValue, right: MezzeValue): MezzeValue {
        return when {
            left is MezzeInt && right is MezzeInt -> MezzeBool(left.value >= right.value)
            left is MezzeFloat && right is MezzeFloat -> MezzeBool(left.value >= right.value)
            left is MezzeNat && right is MezzeNat -> MezzeBool(left.value >= right.value)
            left is MezzeFrac && right is MezzeFrac ->
                MezzeBool(left.numerator * right.denominator >= right.numerator * left.denominator)
            else -> throw RuntimeException("Cannot compare ${left::class.simpleName} >= ${right::class.simpleName}")
        }
    }

    private fun executeAnd(left: MezzeValue, right: MezzeValue): MezzeValue {
        return when {
            left is MezzeBool && right is MezzeBool -> MezzeBool(left.value && right.value)
            else -> throw RuntimeException("Cannot apply logical and to ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }

    private fun executeOr(left: MezzeValue, right: MezzeValue): MezzeValue {
        return when {
            left is MezzeBool && right is MezzeBool -> MezzeBool(left.value || right.value)
            else -> throw RuntimeException("Cannot apply logical or to ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }

    /**
     * Structural equality check for Mezze values.
     * Handles primitives, records, variants, lists, and nested structures.
     */
    private fun structuralEquals(left: MezzeValue, right: MezzeValue): Boolean {
        return when {
            // Primitive types
            left is MezzeInt && right is MezzeInt -> left.value == right.value
            left is MezzeFloat && right is MezzeFloat -> left.value == right.value
            left is MezzeString && right is MezzeString -> left.value == right.value
            left is MezzeBool && right is MezzeBool -> left.value == right.value
            left is MezzeNat && right is MezzeNat -> left.value == right.value

            // Fractions - compare using cross multiplication: a/b == c/d iff a*d == b*c
            left is MezzeFrac && right is MezzeFrac ->
                left.numerator * right.denominator == right.numerator * left.denominator

            // Lists - check length and all elements structurally
            left is MezzeList && right is MezzeList -> {
                val leftList = left.toKotlinList()
                val rightList = right.toKotlinList()
                if (leftList.size != rightList.size) {
                    false // Different lengths
                } else {
                    // Check each element recursively
                    leftList.zip(rightList).all { (leftElem: MezzeValue, rightElem: MezzeValue) ->
                        structuralEquals(leftElem, rightElem)
                    }
                }
            }

            // Records - check all fields structurally
            left is MezzeRecord && right is MezzeRecord -> {
                if (left.fields.keys != right.fields.keys) {
                    false // Different field sets
                } else {
                    // Check each field recursively
                    left.fields.all { (key, leftValue) ->
                        val rightValue = right.fields[key]
                        rightValue != null && structuralEquals(leftValue, rightValue)
                    }
                }
            }

            // Variants - check constructor and data
            left is MezzeVariant && right is MezzeVariant -> {
                if (left.constructor != right.constructor) {
                    false // Different constructors
                } else {
                    // Check data values recursively
                    when {
                        left.value == null && right.value == null -> true
                        left.value != null && right.value != null -> structuralEquals(left.value, right.value)
                        else -> false // One has data, other doesn't
                    }
                }
            }

            // Different types are never equal
            else -> false
        }
    }
}
