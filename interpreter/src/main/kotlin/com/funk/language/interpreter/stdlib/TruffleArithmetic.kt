package com.funk.language.interpreter.stdlib

import com.funk.language.interpreter.*
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Truffle-optimized arithmetic operations for Funk language
 * This uses Truffle's InteropLibrary for proper optimization
 */
object TruffleArithmetic {
    
    private val interop = InteropLibrary.getUncached()
    
    /**
     * Addition operation using Truffle interop
     */
    fun add(left: FunkValue, right: FunkValue): FunkValue {
        return when {
            left is FunkInt && right is FunkInt -> {
                // Use Truffle's overflow-safe arithmetic
                try {
                    FunkInt(Math.addExact(left.value, right.value))
                } catch (e: ArithmeticException) {
                    throw ArithmeticException("Integer overflow in addition")
                }
            }
            left is FunkFloat && right is FunkFloat -> {
                FunkFloat(left.value + right.value)
            }
            left is FunkNat && right is FunkNat -> {
                FunkNat(left.value + right.value)
            }
            left is FunkFrac && right is FunkFrac -> {
                // Add fractions: a/b + c/d = (ad + bc)/(bd)
                val newNumerator = left.numerator * right.denominator + right.numerator * left.denominator
                val newDenominator = left.denominator * right.denominator
                FunkFrac(newNumerator, newDenominator).reduce()
            }
            else -> throw IllegalArgumentException("Cannot add ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
    
    /**
     * Subtraction operation using Truffle interop
     */
    fun subtract(left: FunkValue, right: FunkValue): FunkValue {
        return when {
            left is FunkInt && right is FunkInt -> {
                try {
                    FunkInt(Math.subtractExact(left.value, right.value))
                } catch (e: ArithmeticException) {
                    throw ArithmeticException("Integer overflow in subtraction")
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
                // Subtract fractions: a/b - c/d = (ad - bc)/(bd)
                val newNumerator = left.numerator * right.denominator - right.numerator * left.denominator
                val newDenominator = left.denominator * right.denominator
                FunkFrac(newNumerator, newDenominator).reduce()
            }
            else -> throw IllegalArgumentException("Cannot subtract ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
    
    /**
     * Multiplication operation using Truffle interop
     */
    fun multiply(left: FunkValue, right: FunkValue): FunkValue {
        return when {
            left is FunkInt && right is FunkInt -> {
                try {
                    FunkInt(Math.multiplyExact(left.value, right.value))
                } catch (e: ArithmeticException) {
                    throw ArithmeticException("Integer overflow in multiplication")
                }
            }
            left is FunkFloat && right is FunkFloat -> {
                FunkFloat(left.value * right.value)
            }
            left is FunkNat && right is FunkNat -> {
                FunkNat(left.value * right.value)
            }
            left is FunkFrac && right is FunkFrac -> {
                // Multiply fractions: a/b * c/d = (ac)/(bd)
                val newNumerator = left.numerator * right.numerator
                val newDenominator = left.denominator * right.denominator
                FunkFrac(newNumerator, newDenominator).reduce()
            }
            else -> throw IllegalArgumentException("Cannot multiply ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
    
    /**
     * Division operation using Truffle interop
     */
    fun divide(left: FunkValue, right: FunkValue): FunkValue {
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
                // Divide fractions: a/b ÷ c/d = (ad)/(bc)
                val newNumerator = left.numerator * right.denominator
                val newDenominator = left.denominator * right.numerator
                FunkFrac(newNumerator, newDenominator).reduce()
            }
            else -> throw IllegalArgumentException("Cannot divide ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
    
    /**
     * Equality comparison using Truffle interop
     */
    fun equal(left: FunkValue, right: FunkValue): FunkBool {
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
            else -> FunkBool(false)
        }
    }
    
    /**
     * Less than comparison using Truffle interop
     */
    fun lessThan(left: FunkValue, right: FunkValue): FunkBool {
        return when {
            left is FunkInt && right is FunkInt -> FunkBool(left.value < right.value)
            left is FunkFloat && right is FunkFloat -> FunkBool(left.value < right.value)
            left is FunkNat && right is FunkNat -> FunkBool(left.value < right.value)
            left is FunkFrac && right is FunkFrac -> {
                // Compare fractions: a/b < c/d iff ad < bc (assuming positive denominators)
                FunkBool(left.numerator * right.denominator < right.numerator * left.denominator)
            }
            else -> throw IllegalArgumentException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
    
    /**
     * Greater than comparison using Truffle interop
     */
    fun greaterThan(left: FunkValue, right: FunkValue): FunkBool {
        return when {
            left is FunkInt && right is FunkInt -> FunkBool(left.value > right.value)
            left is FunkFloat && right is FunkFloat -> FunkBool(left.value > right.value)
            left is FunkNat && right is FunkNat -> FunkBool(left.value > right.value)
            left is FunkFrac && right is FunkFrac -> {
                // Compare fractions: a/b > c/d iff ad > bc (assuming positive denominators)
                FunkBool(left.numerator * right.denominator > right.numerator * left.denominator)
            }
            else -> throw IllegalArgumentException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
}
