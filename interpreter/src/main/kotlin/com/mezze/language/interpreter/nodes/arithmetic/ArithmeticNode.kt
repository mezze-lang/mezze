package com.mezze.language.interpreter.nodes.arithmetic

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.profiles.ConditionProfile
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeFloat
import com.mezze.language.interpreter.MezzeNat
import com.mezze.language.interpreter.MezzeFrac

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
    @field:Child protected var leftNode: MezzeNode,
    @field:Child protected var rightNode: MezzeNode
) : MezzeNode() {

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

    override fun execute(frame: VirtualFrame): MezzeValue {
        val left = leftNode.execute(frame)
        val right = rightNode.execute(frame)
        return executeWithProfile(left, right)
    }

    /**
     * Execute with type profiling for JIT optimization.
     * The profile stabilizes after a few executions, enabling constant folding.
     */
    private fun executeWithProfile(left: MezzeValue, right: MezzeValue): MezzeValue {
        // Fast path: use profiled type if stable (monomorphic)
        val currentProfile = typeProfile
        if (currentProfile != null && currentProfile != ArithmeticTypeProfile.POLYMORPHIC) {
            return when (currentProfile) {
                ArithmeticTypeProfile.INT_INT -> {
                    if (intProfile.profile(left is MezzeInt && right is MezzeInt)) {
                        executeIntInt(left as MezzeInt, right as MezzeInt)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ArithmeticTypeProfile.FLOAT_FLOAT -> {
                    if (floatProfile.profile(left is MezzeFloat && right is MezzeFloat)) {
                        executeFloatFloat(left as MezzeFloat, right as MezzeFloat)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ArithmeticTypeProfile.NAT_NAT -> {
                    if (natProfile.profile(left is MezzeNat && right is MezzeNat)) {
                        executeNatNat(left as MezzeNat, right as MezzeNat)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ArithmeticTypeProfile.FRAC_FRAC -> {
                    if (fracProfile.profile(left is MezzeFrac && right is MezzeFrac)) {
                        executeFracFrac(left as MezzeFrac, right as MezzeFrac)
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
    private fun updateProfileAndExecute(left: MezzeValue, right: MezzeValue): MezzeValue {
        val newProfile = when {
            left is MezzeInt && right is MezzeInt -> ArithmeticTypeProfile.INT_INT
            left is MezzeFloat && right is MezzeFloat -> ArithmeticTypeProfile.FLOAT_FLOAT
            left is MezzeNat && right is MezzeNat -> ArithmeticTypeProfile.NAT_NAT
            left is MezzeFrac && right is MezzeFrac -> ArithmeticTypeProfile.FRAC_FRAC
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
    private fun executePolymorphic(left: MezzeValue, right: MezzeValue): MezzeValue {
        return when {
            intProfile.profile(left is MezzeInt && right is MezzeInt) ->
                executeIntInt(left as MezzeInt, right as MezzeInt)
            floatProfile.profile(left is MezzeFloat && right is MezzeFloat) ->
                executeFloatFloat(left as MezzeFloat, right as MezzeFloat)
            natProfile.profile(left is MezzeNat && right is MezzeNat) ->
                executeNatNat(left as MezzeNat, right as MezzeNat)
            fracProfile.profile(left is MezzeFrac && right is MezzeFrac) ->
                executeFracFrac(left as MezzeFrac, right as MezzeFrac)
            else -> throwUnsupportedTypes(left, right)
        }
    }

    @TruffleBoundary
    protected fun throwUnsupportedTypes(left: MezzeValue, right: MezzeValue): Nothing {
        throw IllegalArgumentException("Cannot perform arithmetic on ${left::class.simpleName} and ${right::class.simpleName}")
    }

    @TruffleBoundary
    protected fun throwOverflow(operation: String, left: Long, right: Long): Nothing {
        throw ArithmeticException("Integer overflow in $operation: $left and $right")
    }

    // Type-specialized methods - subclasses override these
    protected abstract fun executeIntInt(left: MezzeInt, right: MezzeInt): MezzeValue
    protected abstract fun executeFloatFloat(left: MezzeFloat, right: MezzeFloat): MezzeValue
    protected abstract fun executeNatNat(left: MezzeNat, right: MezzeNat): MezzeValue
    protected abstract fun executeFracFrac(left: MezzeFrac, right: MezzeFrac): MezzeValue
}

/**
 * Addition node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "add")
class AddNode(leftNode: MezzeNode, rightNode: MezzeNode) : BinaryArithmeticNode(leftNode, rightNode) {

    override fun executeIntInt(left: MezzeInt, right: MezzeInt): MezzeValue {
        try {
            return MezzeInt(Math.addExact(left.value, right.value))
        } catch (e: ArithmeticException) {
            throwOverflow("addition", left.value, right.value)
        }
    }

    override fun executeFloatFloat(left: MezzeFloat, right: MezzeFloat): MezzeValue {
        return MezzeFloat(left.value + right.value)
    }

    override fun executeNatNat(left: MezzeNat, right: MezzeNat): MezzeValue {
        return MezzeNat(left.value + right.value)
    }

    override fun executeFracFrac(left: MezzeFrac, right: MezzeFrac): MezzeValue {
        val newNumerator = left.numerator * right.denominator + right.numerator * left.denominator
        val newDenominator = left.denominator * right.denominator
        return MezzeFrac(newNumerator, newDenominator).reduce()
    }
}

/**
 * Subtraction node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "subtract")
class SubtractNode(leftNode: MezzeNode, rightNode: MezzeNode) : BinaryArithmeticNode(leftNode, rightNode) {

    override fun executeIntInt(left: MezzeInt, right: MezzeInt): MezzeValue {
        try {
            return MezzeInt(Math.subtractExact(left.value, right.value))
        } catch (e: ArithmeticException) {
            throwOverflow("subtraction", left.value, right.value)
        }
    }

    override fun executeFloatFloat(left: MezzeFloat, right: MezzeFloat): MezzeValue {
        return MezzeFloat(left.value - right.value)
    }

    override fun executeNatNat(left: MezzeNat, right: MezzeNat): MezzeValue {
        // Nat - Nat can be negative, so return Int
        return MezzeInt(left.value - right.value)
    }

    override fun executeFracFrac(left: MezzeFrac, right: MezzeFrac): MezzeValue {
        val newNumerator = left.numerator * right.denominator - right.numerator * left.denominator
        val newDenominator = left.denominator * right.denominator
        return MezzeFrac(newNumerator, newDenominator).reduce()
    }
}

/**
 * Multiplication node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "multiply")
class MultiplyNode(leftNode: MezzeNode, rightNode: MezzeNode) : BinaryArithmeticNode(leftNode, rightNode) {

    override fun executeIntInt(left: MezzeInt, right: MezzeInt): MezzeValue {
        try {
            return MezzeInt(Math.multiplyExact(left.value, right.value))
        } catch (e: ArithmeticException) {
            throwOverflow("multiplication", left.value, right.value)
        }
    }

    override fun executeFloatFloat(left: MezzeFloat, right: MezzeFloat): MezzeValue {
        return MezzeFloat(left.value * right.value)
    }

    override fun executeNatNat(left: MezzeNat, right: MezzeNat): MezzeValue {
        return MezzeNat(left.value * right.value)
    }

    override fun executeFracFrac(left: MezzeFrac, right: MezzeFrac): MezzeValue {
        val newNumerator = left.numerator * right.numerator
        val newDenominator = left.denominator * right.denominator
        return MezzeFrac(newNumerator, newDenominator).reduce()
    }
}

/**
 * Division node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "divide")
class DivideNode(leftNode: MezzeNode, rightNode: MezzeNode) : BinaryArithmeticNode(leftNode, rightNode) {

    override fun executeIntInt(left: MezzeInt, right: MezzeInt): MezzeValue {
        if (right.value == 0L) throw ArithmeticException("Division by zero")
        return MezzeInt(left.value / right.value)
    }

    override fun executeFloatFloat(left: MezzeFloat, right: MezzeFloat): MezzeValue {
        if (right.value == 0.0) throw ArithmeticException("Division by zero")
        return MezzeFloat(left.value / right.value)
    }

    override fun executeNatNat(left: MezzeNat, right: MezzeNat): MezzeValue {
        if (right.value == 0L) throw ArithmeticException("Division by zero")
        return MezzeNat(left.value / right.value)
    }

    override fun executeFracFrac(left: MezzeFrac, right: MezzeFrac): MezzeValue {
        if (right.numerator == 0L) throw ArithmeticException("Division by zero")
        val newNumerator = left.numerator * right.denominator
        val newDenominator = left.denominator * right.numerator
        return MezzeFrac(newNumerator, newDenominator).reduce()
    }
}