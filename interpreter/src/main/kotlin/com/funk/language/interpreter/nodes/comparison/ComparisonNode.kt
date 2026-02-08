package com.funk.language.interpreter.nodes.comparison

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
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.FunkBool
import com.funk.language.interpreter.FunkChar
import com.funk.language.interpreter.FunkBytes
import com.funk.language.interpreter.FunkVariant
import com.funk.language.interpreter.FunkArrayList
import com.funk.language.interpreter.FunkList

/**
 * Enum representing the stable type combination at a comparison call site.
 * Used for inline caching - JIT can specialize based on observed types.
 */
enum class ComparisonTypeProfile {
    INT_INT,
    FLOAT_FLOAT,
    NAT_NAT,
    FRAC_FRAC,
    STRING_STRING,
    BOOL_BOOL,
    CHAR_CHAR,
    POLYMORPHIC  // Multiple types or complex types (collections, variants)
}

/**
 * Base class for binary comparison operations with type profiling for JIT optimization.
 *
 * Uses Truffle's compilation-final type profile to enable:
 * - Monomorphic dispatch: If always same types, JIT inlines the specific case
 * - Inline caching: Type check becomes a constant after warmup
 * - Branch elimination: Unused type cases are compiled out
 */
abstract class BinaryComparisonNode(
    @field:Child protected var leftNode: FunkNode,
    @field:Child protected var rightNode: FunkNode
) : FunkNode() {

    /**
     * Stable type profile for this call site.
     * @CompilationFinal means JIT treats this as constant after stabilization.
     */
    @CompilationFinal
    private var typeProfile: ComparisonTypeProfile? = null

    /**
     * Condition profiles for fast-path type checks.
     * These help the JIT predict which branch will be taken.
     */
    private val intProfile = ConditionProfile.create()
    private val floatProfile = ConditionProfile.create()
    private val natProfile = ConditionProfile.create()
    private val fracProfile = ConditionProfile.create()
    private val stringProfile = ConditionProfile.create()
    private val boolProfile = ConditionProfile.create()
    private val charProfile = ConditionProfile.create()

    override fun execute(frame: VirtualFrame): FunkValue {
        val left = leftNode.execute(frame)
        val right = rightNode.execute(frame)
        return executeWithProfile(left, right)
    }

    /**
     * Execute with type profiling for JIT optimization.
     */
    private fun executeWithProfile(left: FunkValue, right: FunkValue): FunkBool {
        val currentProfile = typeProfile
        if (currentProfile != null && currentProfile != ComparisonTypeProfile.POLYMORPHIC) {
            return when (currentProfile) {
                ComparisonTypeProfile.INT_INT -> {
                    if (intProfile.profile(left is FunkInt && right is FunkInt)) {
                        executeIntInt(left as FunkInt, right as FunkInt)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ComparisonTypeProfile.FLOAT_FLOAT -> {
                    if (floatProfile.profile(left is FunkFloat && right is FunkFloat)) {
                        executeFloatFloat(left as FunkFloat, right as FunkFloat)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ComparisonTypeProfile.NAT_NAT -> {
                    if (natProfile.profile(left is FunkNat && right is FunkNat)) {
                        executeNatNat(left as FunkNat, right as FunkNat)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ComparisonTypeProfile.FRAC_FRAC -> {
                    if (fracProfile.profile(left is FunkFrac && right is FunkFrac)) {
                        executeFracFrac(left as FunkFrac, right as FunkFrac)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ComparisonTypeProfile.STRING_STRING -> {
                    if (stringProfile.profile(left is FunkString && right is FunkString)) {
                        executeStringString(left as FunkString, right as FunkString)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ComparisonTypeProfile.BOOL_BOOL -> {
                    if (boolProfile.profile(left is FunkBool && right is FunkBool)) {
                        executeBoolBool(left as FunkBool, right as FunkBool)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ComparisonTypeProfile.CHAR_CHAR -> {
                    if (charProfile.profile(left is FunkChar && right is FunkChar)) {
                        executeCharChar(left as FunkChar, right as FunkChar)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ComparisonTypeProfile.POLYMORPHIC -> executePolymorphic(left, right)
            }
        }

        return updateProfileAndExecute(left, right)
    }

    /**
     * Update the type profile based on observed types.
     */
    private fun updateProfileAndExecute(left: FunkValue, right: FunkValue): FunkBool {
        val newProfile = when {
            left is FunkInt && right is FunkInt -> ComparisonTypeProfile.INT_INT
            left is FunkFloat && right is FunkFloat -> ComparisonTypeProfile.FLOAT_FLOAT
            left is FunkNat && right is FunkNat -> ComparisonTypeProfile.NAT_NAT
            left is FunkFrac && right is FunkFrac -> ComparisonTypeProfile.FRAC_FRAC
            left is FunkString && right is FunkString -> ComparisonTypeProfile.STRING_STRING
            left is FunkBool && right is FunkBool -> ComparisonTypeProfile.BOOL_BOOL
            left is FunkChar && right is FunkChar -> ComparisonTypeProfile.CHAR_CHAR
            else -> ComparisonTypeProfile.POLYMORPHIC
        }

        if (typeProfile == null) {
            typeProfile = newProfile
        } else if (typeProfile != newProfile) {
            typeProfile = ComparisonTypeProfile.POLYMORPHIC
        }

        return executePolymorphic(left, right)
    }

    /**
     * Polymorphic path - handles all types including complex ones.
     */
    private fun executePolymorphic(left: FunkValue, right: FunkValue): FunkBool {
        return when {
            intProfile.profile(left is FunkInt && right is FunkInt) ->
                executeIntInt(left as FunkInt, right as FunkInt)
            floatProfile.profile(left is FunkFloat && right is FunkFloat) ->
                executeFloatFloat(left as FunkFloat, right as FunkFloat)
            natProfile.profile(left is FunkNat && right is FunkNat) ->
                executeNatNat(left as FunkNat, right as FunkNat)
            fracProfile.profile(left is FunkFrac && right is FunkFrac) ->
                executeFracFrac(left as FunkFrac, right as FunkFrac)
            stringProfile.profile(left is FunkString && right is FunkString) ->
                executeStringString(left as FunkString, right as FunkString)
            boolProfile.profile(left is FunkBool && right is FunkBool) ->
                executeBoolBool(left as FunkBool, right as FunkBool)
            charProfile.profile(left is FunkChar && right is FunkChar) ->
                executeCharChar(left as FunkChar, right as FunkChar)
            else -> executeComplex(left, right)
        }
    }

    // Type-specialized methods for primitive types - subclasses override these
    protected abstract fun executeIntInt(left: FunkInt, right: FunkInt): FunkBool
    protected abstract fun executeFloatFloat(left: FunkFloat, right: FunkFloat): FunkBool
    protected abstract fun executeNatNat(left: FunkNat, right: FunkNat): FunkBool
    protected abstract fun executeFracFrac(left: FunkFrac, right: FunkFrac): FunkBool
    protected abstract fun executeStringString(left: FunkString, right: FunkString): FunkBool
    protected abstract fun executeBoolBool(left: FunkBool, right: FunkBool): FunkBool
    protected abstract fun executeCharChar(left: FunkChar, right: FunkChar): FunkBool

    // Complex types (Bytes, Variants, Collections) - fallback path
    protected abstract fun executeComplex(left: FunkValue, right: FunkValue): FunkBool
}

/**
 * Equality comparison node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "equal")
class EqualNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryComparisonNode(leftNode, rightNode) {

    override fun executeIntInt(left: FunkInt, right: FunkInt): FunkBool =
        FunkBool(left.value == right.value)

    override fun executeFloatFloat(left: FunkFloat, right: FunkFloat): FunkBool =
        FunkBool(left.value == right.value)

    override fun executeNatNat(left: FunkNat, right: FunkNat): FunkBool =
        FunkBool(left.value == right.value)

    override fun executeFracFrac(left: FunkFrac, right: FunkFrac): FunkBool {
        // Compare fractions: a/b == c/d iff ad == bc
        return FunkBool(left.numerator * right.denominator == right.numerator * left.denominator)
    }

    override fun executeStringString(left: FunkString, right: FunkString): FunkBool =
        FunkBool(left.value == right.value)

    override fun executeBoolBool(left: FunkBool, right: FunkBool): FunkBool =
        FunkBool(left.value == right.value)

    override fun executeCharChar(left: FunkChar, right: FunkChar): FunkBool =
        FunkBool(left.value == right.value)

    override fun executeComplex(left: FunkValue, right: FunkValue): FunkBool {
        return when {
            left is FunkBytes && right is FunkBytes ->
                FunkBool(left.bytes.contentEquals(right.bytes))
            left is FunkVariant && right is FunkVariant -> {
                if (left.constructor != right.constructor) {
                    FunkBool(false)
                } else if (left.value == null && right.value == null) {
                    FunkBool(true)
                } else if (left.value == null || right.value == null) {
                    FunkBool(false)
                } else {
                    executeComplex(left.value, right.value)
                }
            }
            left is FunkArrayList && right is FunkArrayList -> {
                val leftList = left.toList()
                val rightList = right.toList()
                if (leftList.size != rightList.size) {
                    FunkBool(false)
                } else {
                    val allEqual = leftList.zip(rightList).all { (elemL: FunkValue, elemR: FunkValue) ->
                        compareValuesForEquality(elemL, elemR)
                    }
                    FunkBool(allEqual)
                }
            }
            left is FunkList && right is FunkList -> {
                val leftList = left.toKotlinList()
                val rightList = right.toKotlinList()
                if (leftList.size != rightList.size) {
                    FunkBool(false)
                } else {
                    val allEqual = leftList.zip(rightList).all { (elemL: FunkValue, elemR: FunkValue) ->
                        compareValuesForEquality(elemL, elemR)
                    }
                    FunkBool(allEqual)
                }
            }
            else -> FunkBool(false) // Different types are never equal
        }
    }

    private fun compareValuesForEquality(left: FunkValue, right: FunkValue): Boolean {
        return when {
            left is FunkInt && right is FunkInt -> left.value == right.value
            left is FunkFloat && right is FunkFloat -> left.value == right.value
            left is FunkNat && right is FunkNat -> left.value == right.value
            left is FunkFrac && right is FunkFrac ->
                left.numerator * right.denominator == right.numerator * left.denominator
            left is FunkString && right is FunkString -> left.value == right.value
            left is FunkBool && right is FunkBool -> left.value == right.value
            left is FunkChar && right is FunkChar -> left.value == right.value
            left is FunkBytes && right is FunkBytes -> left.bytes.contentEquals(right.bytes)
            left is FunkVariant && right is FunkVariant ->
                executeComplex(left, right).value
            left is FunkArrayList && right is FunkArrayList ->
                executeComplex(left, right).value
            left is FunkList && right is FunkList ->
                executeComplex(left, right).value
            else -> false
        }
    }
}

/**
 * Less than comparison node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "less-than")
class LessThanNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryComparisonNode(leftNode, rightNode) {

    override fun executeIntInt(left: FunkInt, right: FunkInt): FunkBool =
        FunkBool(left.value < right.value)

    override fun executeFloatFloat(left: FunkFloat, right: FunkFloat): FunkBool =
        FunkBool(left.value < right.value)

    override fun executeNatNat(left: FunkNat, right: FunkNat): FunkBool =
        FunkBool(left.value < right.value)

    override fun executeFracFrac(left: FunkFrac, right: FunkFrac): FunkBool {
        // Compare fractions: a/b < c/d iff ad < bc (assuming positive denominators)
        return FunkBool(left.numerator * right.denominator < right.numerator * left.denominator)
    }

    override fun executeStringString(left: FunkString, right: FunkString): FunkBool =
        FunkBool(left.value < right.value)

    override fun executeBoolBool(left: FunkBool, right: FunkBool): FunkBool =
        FunkBool(!left.value && right.value) // False < True

    override fun executeCharChar(left: FunkChar, right: FunkChar): FunkBool =
        FunkBool(left.value < right.value)

    override fun executeComplex(left: FunkValue, right: FunkValue): FunkBool {
        return when {
            left is FunkBytes && right is FunkBytes ->
                FunkBool(compareBytesLexicographic(left, right) < 0)
            left is FunkVariant && right is FunkVariant ->
                FunkBool(compareVariants(left, right) < 0)
            left is FunkArrayList && right is FunkArrayList ->
                FunkBool(compareArrays(left, right) < 0)
            left is FunkList && right is FunkList ->
                FunkBool(compareLists(left, right) < 0)
            else -> throw IllegalArgumentException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
}

/**
 * Greater than comparison node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "greater-than")
class GreaterThanNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryComparisonNode(leftNode, rightNode) {

    override fun executeIntInt(left: FunkInt, right: FunkInt): FunkBool =
        FunkBool(left.value > right.value)

    override fun executeFloatFloat(left: FunkFloat, right: FunkFloat): FunkBool =
        FunkBool(left.value > right.value)

    override fun executeNatNat(left: FunkNat, right: FunkNat): FunkBool =
        FunkBool(left.value > right.value)

    override fun executeFracFrac(left: FunkFrac, right: FunkFrac): FunkBool {
        // Compare fractions: a/b > c/d iff ad > bc (assuming positive denominators)
        return FunkBool(left.numerator * right.denominator > right.numerator * left.denominator)
    }

    override fun executeStringString(left: FunkString, right: FunkString): FunkBool =
        FunkBool(left.value > right.value)

    override fun executeBoolBool(left: FunkBool, right: FunkBool): FunkBool =
        FunkBool(left.value && !right.value) // True > False

    override fun executeCharChar(left: FunkChar, right: FunkChar): FunkBool =
        FunkBool(left.value > right.value)

    override fun executeComplex(left: FunkValue, right: FunkValue): FunkBool {
        return when {
            left is FunkBytes && right is FunkBytes ->
                FunkBool(compareBytesLexicographic(left, right) > 0)
            left is FunkVariant && right is FunkVariant ->
                FunkBool(compareVariants(left, right) > 0)
            left is FunkArrayList && right is FunkArrayList ->
                FunkBool(compareArrays(left, right) > 0)
            left is FunkList && right is FunkList ->
                FunkBool(compareLists(left, right) > 0)
            else -> throw IllegalArgumentException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
}

/**
 * Less than or equal comparison node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "less-equal")
class LessEqualNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryComparisonNode(leftNode, rightNode) {

    override fun executeIntInt(left: FunkInt, right: FunkInt): FunkBool =
        FunkBool(left.value <= right.value)

    override fun executeFloatFloat(left: FunkFloat, right: FunkFloat): FunkBool =
        FunkBool(left.value <= right.value)

    override fun executeNatNat(left: FunkNat, right: FunkNat): FunkBool =
        FunkBool(left.value <= right.value)

    override fun executeFracFrac(left: FunkFrac, right: FunkFrac): FunkBool =
        FunkBool(left.numerator * right.denominator <= right.numerator * left.denominator)

    override fun executeStringString(left: FunkString, right: FunkString): FunkBool =
        FunkBool(left.value <= right.value)

    override fun executeBoolBool(left: FunkBool, right: FunkBool): FunkBool =
        FunkBool(!left.value || right.value) // False <= True, equal is ok

    override fun executeCharChar(left: FunkChar, right: FunkChar): FunkBool =
        FunkBool(left.value <= right.value)

    override fun executeComplex(left: FunkValue, right: FunkValue): FunkBool {
        return when {
            left is FunkBytes && right is FunkBytes ->
                FunkBool(compareBytesLexicographic(left, right) <= 0)
            left is FunkVariant && right is FunkVariant ->
                FunkBool(compareVariants(left, right) <= 0)
            left is FunkArrayList && right is FunkArrayList ->
                FunkBool(compareArrays(left, right) <= 0)
            left is FunkList && right is FunkList ->
                FunkBool(compareLists(left, right) <= 0)
            else -> throw IllegalArgumentException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
}

/**
 * Greater than or equal comparison node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "greater-equal")
class GreaterEqualNode(leftNode: FunkNode, rightNode: FunkNode) : BinaryComparisonNode(leftNode, rightNode) {

    override fun executeIntInt(left: FunkInt, right: FunkInt): FunkBool =
        FunkBool(left.value >= right.value)

    override fun executeFloatFloat(left: FunkFloat, right: FunkFloat): FunkBool =
        FunkBool(left.value >= right.value)

    override fun executeNatNat(left: FunkNat, right: FunkNat): FunkBool =
        FunkBool(left.value >= right.value)

    override fun executeFracFrac(left: FunkFrac, right: FunkFrac): FunkBool =
        FunkBool(left.numerator * right.denominator >= right.numerator * left.denominator)

    override fun executeStringString(left: FunkString, right: FunkString): FunkBool =
        FunkBool(left.value >= right.value)

    override fun executeBoolBool(left: FunkBool, right: FunkBool): FunkBool =
        FunkBool(left.value || !right.value) // True >= False, equal is ok

    override fun executeCharChar(left: FunkChar, right: FunkChar): FunkBool =
        FunkBool(left.value >= right.value)

    override fun executeComplex(left: FunkValue, right: FunkValue): FunkBool {
        return when {
            left is FunkBytes && right is FunkBytes ->
                FunkBool(compareBytesLexicographic(left, right) >= 0)
            left is FunkVariant && right is FunkVariant ->
                FunkBool(compareVariants(left, right) >= 0)
            left is FunkArrayList && right is FunkArrayList ->
                FunkBool(compareArrays(left, right) >= 0)
            left is FunkList && right is FunkList ->
                FunkBool(compareLists(left, right) >= 0)
            else -> throw IllegalArgumentException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
        }
    }
}

// Helper functions for lexicographic comparison

/**
 * Compare two bytes arrays lexicographically
 * Returns: negative if left < right, 0 if equal, positive if left > right
 */
private fun compareBytesLexicographic(left: FunkBytes, right: FunkBytes): Int {
    val leftBytes = left.bytes
    val rightBytes = right.bytes
    val minLen = minOf(leftBytes.size, rightBytes.size)

    for (i in 0 until minLen) {
        val cmp = (leftBytes[i].toInt() and 0xFF) - (rightBytes[i].toInt() and 0xFF)
        if (cmp != 0) return cmp
    }
    return leftBytes.size - rightBytes.size
}

/**
 * Compare two variants - first by constructor name, then by value
 * For Ordering type: LT < EQ < GT
 */
private fun compareVariants(left: FunkVariant, right: FunkVariant): Int {
    // Special handling for Ordering type
    val orderingRank = mapOf("LT" to 0, "EQ" to 1, "GT" to 2)
    if (left.constructor in orderingRank && right.constructor in orderingRank) {
        return orderingRank[left.constructor]!! - orderingRank[right.constructor]!!
    }

    // Generic variant comparison: compare by constructor name first
    val constructorCmp = left.constructor.compareTo(right.constructor)
    if (constructorCmp != 0) return constructorCmp

    // If same constructor, compare values
    return when {
        left.value == null && right.value == null -> 0
        left.value == null -> -1
        right.value == null -> 1
        else -> compareValues(left.value, right.value)
    }
}

/**
 * Compare two arrays lexicographically
 */
private fun compareArrays(left: FunkArrayList, right: FunkArrayList): Int {
    val leftList = left.toList()
    val rightList = right.toList()
    val minLen = minOf(leftList.size, rightList.size)

    for (i in 0 until minLen) {
        val cmp = compareValues(leftList[i], rightList[i])
        if (cmp != 0) return cmp
    }
    return leftList.size - rightList.size
}

/**
 * Compare two lists lexicographically
 */
private fun compareLists(left: FunkList, right: FunkList): Int {
    val leftList = left.toKotlinList()
    val rightList = right.toKotlinList()
    val minLen = minOf(leftList.size, rightList.size)

    for (i in 0 until minLen) {
        val cmp = compareValues(leftList[i], rightList[i])
        if (cmp != 0) return cmp
    }
    return leftList.size - rightList.size
}

/**
 * Generic value comparison for nested structures
 * Returns: negative if left < right, 0 if equal, positive if left > right
 */
private fun compareValues(left: FunkValue, right: FunkValue): Int {
    return when {
        left is FunkInt && right is FunkInt -> left.value.compareTo(right.value)
        left is FunkFloat && right is FunkFloat -> left.value.compareTo(right.value)
        left is FunkNat && right is FunkNat -> left.value.compareTo(right.value)
        left is FunkFrac && right is FunkFrac -> {
            val lhs = left.numerator * right.denominator
            val rhs = right.numerator * left.denominator
            lhs.compareTo(rhs)
        }
        left is FunkString && right is FunkString -> left.value.compareTo(right.value)
        left is FunkChar && right is FunkChar -> left.value.compareTo(right.value)
        left is FunkBool && right is FunkBool -> left.value.compareTo(right.value)
        left is FunkBytes && right is FunkBytes -> compareBytesLexicographic(left, right)
        left is FunkVariant && right is FunkVariant -> compareVariants(left, right)
        left is FunkArrayList && right is FunkArrayList -> compareArrays(left, right)
        left is FunkList && right is FunkList -> compareLists(left, right)
        else -> throw IllegalArgumentException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
    }
}