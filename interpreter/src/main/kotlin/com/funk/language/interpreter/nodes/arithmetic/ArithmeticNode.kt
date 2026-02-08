package com.funk.language.interpreter.nodes.arithmetic

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.profiles.ConditionProfile
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.FunkFloat
import com.funk.language.interpreter.FunkNat
import com.funk.language.interpreter.FunkFrac

/**
 * Enum representing the stable type combination at a call site.
 * Used for inline caching - JIT can specialize based on observed types.
 */
enum class ArithmeticTypeProfile {
    INT_INT,
    FLOAT_FLOAT,
    NAT_NAT,
    FRAC_FRAC,
    POLYMORPHIC  // Multiple types seen at this site
}

/**
 * Base class for binary arithmetic operations with type profiling for JIT optimization.
 *
 * Uses Truffle's compilation-final type profile to enable:
 * - Monomorphic dispatch: If always same types, JIT inlines the specific case
 * - Inline caching: Type check becomes a constant after warmup
 * - Branch elimination: Unused type cases are compiled out
 */
abstract class BinaryArithmeticNode(
    @field:Child protected var leftNode: FunkNode,
    @field:Child protected var rightNode: FunkNode
) : FunkNode() {

    /**
     * Stable type profile for this call site.
     * @CompilationFinal means JIT treats this as constant after stabilization.
     * Initially null, set on first execution, becomes POLYMORPHIC if types vary.
     */
    @CompilationFinal
    private var typeProfile: ArithmeticTypeProfile? = null

    /**
     * Condition profiles for fast-path type checks.
     * These help the JIT predict which branch will be taken.
     */
    private val intProfile = ConditionProfile.create()
    private val floatProfile = ConditionProfile.create()
    private val natProfile = ConditionProfile.create()
    private val fracProfile = ConditionProfile.create()

    override fun execute(frame: VirtualFrame): FunkValue {
        val left = leftNode.execute(frame)
        val right = rightNode.execute(frame)
        return executeWithProfile(left, right)
    }

    /**
     * Execute with type profiling for JIT optimization.
     * The profile stabilizes after a few executions, enabling constant folding.
     */
    private fun executeWithProfile(left: FunkValue, right: FunkValue): FunkValue {
        // Fast path: use profiled type if stable (monomorphic)
        val currentProfile = typeProfile
        if (currentProfile != null && currentProfile != ArithmeticTypeProfile.POLYMORPHIC) {
            return when (currentProfile) {
                ArithmeticTypeProfile.INT_INT -> {
                    if (intProfile.profile(left is FunkInt && right is FunkInt)) {
                        executeIntInt(left as FunkInt, right as FunkInt)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ArithmeticTypeProfile.FLOAT_FLOAT -> {
                    if (floatProfile.profile(left is FunkFloat && right is FunkFloat)) {
                        executeFloatFloat(left as FunkFloat, right as FunkFloat)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ArithmeticTypeProfile.NAT_NAT -> {
                    if (natProfile.profile(left is FunkNat && right is FunkNat)) {
                        executeNatNat(left as FunkNat, right as FunkNat)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ArithmeticTypeProfile.FRAC_FRAC -> {
                    if (fracProfile.profile(left is FunkFrac && right is FunkFrac)) {
                        executeFracFrac(left as FunkFrac, right as FunkFrac)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ArithmeticTypeProfile.POLYMORPHIC -> executePolymorphic(left, right)
            }
        }

        // First execution or polymorphic: determine and cache type profile
        return updateProfileAndExecute(left, right)
    }

    /**
     * Update the type profile based on observed types.
     * Called on first execution or when types don't match the cached profile.
     */
    private fun updateProfileAndExecute(left: FunkValue, right: FunkValue): FunkValue {
        val newProfile = when {
            left is FunkInt && right is FunkInt -> ArithmeticTypeProfile.INT_INT
            left is FunkFloat && right is FunkFloat -> ArithmeticTypeProfile.FLOAT_FLOAT
            left is FunkNat && right is FunkNat -> ArithmeticTypeProfile.NAT_NAT
            left is FunkFrac && right is FunkFrac -> ArithmeticTypeProfile.FRAC_FRAC
            else -> ArithmeticTypeProfile.POLYMORPHIC
        }

        // Update profile: if already set and different, mark as polymorphic
        if (typeProfile == null) {
            typeProfile = newProfile
        } else if (typeProfile != newProfile) {
            typeProfile = ArithmeticTypeProfile.POLYMORPHIC
        }

        return executePolymorphic(left, right)
    }

    /**
     * Polymorphic path - used when multiple types are seen at this call site.
     * Still uses condition profiles for branch prediction.
     */
    private fun executePolymorphic(left: FunkValue, right: FunkValue): FunkValue {
        return when {
            intProfile.profile(left is FunkInt && right is FunkInt) ->
                executeIntInt(left as FunkInt, right as FunkInt)
            floatProfile.profile(left is FunkFloat && right is FunkFloat) ->
                executeFloatFloat(left as FunkFloat, right as FunkFloat)
            natProfile.profile(left is FunkNat && right is FunkNat) ->
                executeNatNat(left as FunkNat, right as FunkNat)
            fracProfile.profile(left is FunkFrac && right is FunkFrac) ->
                executeFracFrac(left as FunkFrac, right as FunkFrac)
            else -> throw IllegalArgumentException("Cannot perform arithmetic on ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }

    // Type-specialized methods - subclasses override these
    protected abstract fun executeIntInt(left: FunkInt, right: FunkInt): FunkValue
    protected abstract fun executeFloatFloat(left: FunkFloat, right: FunkFloat): FunkValue
    protected abstract fun executeNatNat(left: FunkNat, right: FunkNat): FunkValue
    protected abstract fun executeFracFrac(left: FunkFrac, right: FunkFrac): FunkValue
}

/**
 * Addition node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "add")
class AddNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryArithmeticNode(leftNode, rightNode) {

    override fun executeIntInt(left: FunkInt, right: FunkInt): FunkValue {
        try {
            return FunkInt(Math.addExact(left.value, right.value))
        } catch (e: ArithmeticException) {
            throw ArithmeticException("Integer overflow in addition: ${left.value} + ${right.value}")
        }
    }

    override fun executeFloatFloat(left: FunkFloat, right: FunkFloat): FunkValue {
        return FunkFloat(left.value + right.value)
    }

    override fun executeNatNat(left: FunkNat, right: FunkNat): FunkValue {
        return FunkNat(left.value + right.value)
    }

    override fun executeFracFrac(left: FunkFrac, right: FunkFrac): FunkValue {
        val newNumerator = left.numerator * right.denominator + right.numerator * left.denominator
        val newDenominator = left.denominator * right.denominator
        return FunkFrac(newNumerator, newDenominator).reduce()
    }
}

/**
 * Subtraction node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "subtract")
class SubtractNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryArithmeticNode(leftNode, rightNode) {

    override fun executeIntInt(left: FunkInt, right: FunkInt): FunkValue {
        try {
            return FunkInt(Math.subtractExact(left.value, right.value))
        } catch (e: ArithmeticException) {
            throw ArithmeticException("Integer overflow in subtraction: ${left.value} - ${right.value}")
        }
    }

    override fun executeFloatFloat(left: FunkFloat, right: FunkFloat): FunkValue {
        return FunkFloat(left.value - right.value)
    }

    override fun executeNatNat(left: FunkNat, right: FunkNat): FunkValue {
        // Nat - Nat can be negative, so return Int
        return FunkInt(left.value - right.value)
    }

    override fun executeFracFrac(left: FunkFrac, right: FunkFrac): FunkValue {
        val newNumerator = left.numerator * right.denominator - right.numerator * left.denominator
        val newDenominator = left.denominator * right.denominator
        return FunkFrac(newNumerator, newDenominator).reduce()
    }
}

/**
 * Multiplication node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "multiply")
class MultiplyNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryArithmeticNode(leftNode, rightNode) {

    override fun executeIntInt(left: FunkInt, right: FunkInt): FunkValue {
        try {
            return FunkInt(Math.multiplyExact(left.value, right.value))
        } catch (e: ArithmeticException) {
            throw ArithmeticException("Integer overflow in multiplication: ${left.value} * ${right.value}")
        }
    }

    override fun executeFloatFloat(left: FunkFloat, right: FunkFloat): FunkValue {
        return FunkFloat(left.value * right.value)
    }

    override fun executeNatNat(left: FunkNat, right: FunkNat): FunkValue {
        return FunkNat(left.value * right.value)
    }

    override fun executeFracFrac(left: FunkFrac, right: FunkFrac): FunkValue {
        val newNumerator = left.numerator * right.numerator
        val newDenominator = left.denominator * right.denominator
        return FunkFrac(newNumerator, newDenominator).reduce()
    }
}

/**
 * Division node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "divide")
class DivideNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryArithmeticNode(leftNode, rightNode) {

    override fun executeIntInt(left: FunkInt, right: FunkInt): FunkValue {
        if (right.value == 0L) throw ArithmeticException("Division by zero")
        return FunkInt(left.value / right.value)
    }

    override fun executeFloatFloat(left: FunkFloat, right: FunkFloat): FunkValue {
        if (right.value == 0.0) throw ArithmeticException("Division by zero")
        return FunkFloat(left.value / right.value)
    }

    override fun executeNatNat(left: FunkNat, right: FunkNat): FunkValue {
        if (right.value == 0L) throw ArithmeticException("Division by zero")
        return FunkNat(left.value / right.value)
    }

    override fun executeFracFrac(left: FunkFrac, right: FunkFrac): FunkValue {
        if (right.numerator == 0L) throw ArithmeticException("Division by zero")
        val newNumerator = left.numerator * right.denominator
        val newDenominator = left.denominator * right.numerator
        return FunkFrac(newNumerator, newDenominator).reduce()
    }
}