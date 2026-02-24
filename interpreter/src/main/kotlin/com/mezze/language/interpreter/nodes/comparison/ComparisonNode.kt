package com.mezze.language.interpreter.nodes.comparison

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
import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeBool
import com.mezze.language.interpreter.MezzeChar
import com.mezze.language.interpreter.MezzeBytes
import com.mezze.language.interpreter.MezzeVariant
import com.mezze.language.interpreter.MezzeArrayList
import com.mezze.language.interpreter.MezzeList

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
    @field:Child protected var leftNode: MezzeNode,
    @field:Child protected var rightNode: MezzeNode
) : MezzeNode() {

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

    override fun execute(frame: VirtualFrame): MezzeValue {
        val left = leftNode.execute(frame)
        val right = rightNode.execute(frame)
        return executeWithProfile(left, right)
    }

    /**
     * Execute with type profiling for JIT optimization.
     */
    private fun executeWithProfile(left: MezzeValue, right: MezzeValue): MezzeBool {
        val currentProfile = typeProfile
        if (currentProfile != null && currentProfile != ComparisonTypeProfile.POLYMORPHIC) {
            return when (currentProfile) {
                ComparisonTypeProfile.INT_INT -> {
                    if (intProfile.profile(left is MezzeInt && right is MezzeInt)) {
                        executeIntInt(left as MezzeInt, right as MezzeInt)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ComparisonTypeProfile.FLOAT_FLOAT -> {
                    if (floatProfile.profile(left is MezzeFloat && right is MezzeFloat)) {
                        executeFloatFloat(left as MezzeFloat, right as MezzeFloat)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ComparisonTypeProfile.NAT_NAT -> {
                    if (natProfile.profile(left is MezzeNat && right is MezzeNat)) {
                        executeNatNat(left as MezzeNat, right as MezzeNat)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ComparisonTypeProfile.FRAC_FRAC -> {
                    if (fracProfile.profile(left is MezzeFrac && right is MezzeFrac)) {
                        executeFracFrac(left as MezzeFrac, right as MezzeFrac)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ComparisonTypeProfile.STRING_STRING -> {
                    if (stringProfile.profile(left is MezzeString && right is MezzeString)) {
                        executeStringString(left as MezzeString, right as MezzeString)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ComparisonTypeProfile.BOOL_BOOL -> {
                    if (boolProfile.profile(left is MezzeBool && right is MezzeBool)) {
                        executeBoolBool(left as MezzeBool, right as MezzeBool)
                    } else {
                        updateProfileAndExecute(left, right)
                    }
                }
                ComparisonTypeProfile.CHAR_CHAR -> {
                    if (charProfile.profile(left is MezzeChar && right is MezzeChar)) {
                        executeCharChar(left as MezzeChar, right as MezzeChar)
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
    private fun updateProfileAndExecute(left: MezzeValue, right: MezzeValue): MezzeBool {
        val newProfile = when {
            left is MezzeInt && right is MezzeInt -> ComparisonTypeProfile.INT_INT
            left is MezzeFloat && right is MezzeFloat -> ComparisonTypeProfile.FLOAT_FLOAT
            left is MezzeNat && right is MezzeNat -> ComparisonTypeProfile.NAT_NAT
            left is MezzeFrac && right is MezzeFrac -> ComparisonTypeProfile.FRAC_FRAC
            left is MezzeString && right is MezzeString -> ComparisonTypeProfile.STRING_STRING
            left is MezzeBool && right is MezzeBool -> ComparisonTypeProfile.BOOL_BOOL
            left is MezzeChar && right is MezzeChar -> ComparisonTypeProfile.CHAR_CHAR
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
    private fun executePolymorphic(left: MezzeValue, right: MezzeValue): MezzeBool {
        return when {
            intProfile.profile(left is MezzeInt && right is MezzeInt) ->
                executeIntInt(left as MezzeInt, right as MezzeInt)
            floatProfile.profile(left is MezzeFloat && right is MezzeFloat) ->
                executeFloatFloat(left as MezzeFloat, right as MezzeFloat)
            natProfile.profile(left is MezzeNat && right is MezzeNat) ->
                executeNatNat(left as MezzeNat, right as MezzeNat)
            fracProfile.profile(left is MezzeFrac && right is MezzeFrac) ->
                executeFracFrac(left as MezzeFrac, right as MezzeFrac)
            stringProfile.profile(left is MezzeString && right is MezzeString) ->
                executeStringString(left as MezzeString, right as MezzeString)
            boolProfile.profile(left is MezzeBool && right is MezzeBool) ->
                executeBoolBool(left as MezzeBool, right as MezzeBool)
            charProfile.profile(left is MezzeChar && right is MezzeChar) ->
                executeCharChar(left as MezzeChar, right as MezzeChar)
            else -> executeComplex(left, right)
        }
    }

    // Type-specialized methods for primitive types - subclasses override these
    protected abstract fun executeIntInt(left: MezzeInt, right: MezzeInt): MezzeBool
    protected abstract fun executeFloatFloat(left: MezzeFloat, right: MezzeFloat): MezzeBool
    protected abstract fun executeNatNat(left: MezzeNat, right: MezzeNat): MezzeBool
    protected abstract fun executeFracFrac(left: MezzeFrac, right: MezzeFrac): MezzeBool
    protected abstract fun executeStringString(left: MezzeString, right: MezzeString): MezzeBool
    protected abstract fun executeBoolBool(left: MezzeBool, right: MezzeBool): MezzeBool
    protected abstract fun executeCharChar(left: MezzeChar, right: MezzeChar): MezzeBool

    // Complex types (Bytes, Variants, Collections) - fallback path
    protected abstract fun executeComplex(left: MezzeValue, right: MezzeValue): MezzeBool

    /**
     * Throw comparison error for incompatible types.
     * Marked as TruffleBoundary to keep string interpolation out of JIT path.
     */
    @TruffleBoundary
    protected fun throwCannotCompare(left: MezzeValue, right: MezzeValue): Nothing {
        throw IllegalArgumentException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
    }
}

/**
 * Equality comparison node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "equal")
class EqualNode(leftNode: MezzeNode, rightNode: MezzeNode) : BinaryComparisonNode(leftNode, rightNode) {

    override fun executeIntInt(left: MezzeInt, right: MezzeInt): MezzeBool =
        MezzeBool(left.value == right.value)

    override fun executeFloatFloat(left: MezzeFloat, right: MezzeFloat): MezzeBool =
        MezzeBool(left.value == right.value)

    override fun executeNatNat(left: MezzeNat, right: MezzeNat): MezzeBool =
        MezzeBool(left.value == right.value)

    override fun executeFracFrac(left: MezzeFrac, right: MezzeFrac): MezzeBool {
        // Compare fractions: a/b == c/d iff ad == bc
        return MezzeBool(left.numerator * right.denominator == right.numerator * left.denominator)
    }

    override fun executeStringString(left: MezzeString, right: MezzeString): MezzeBool =
        MezzeBool(left.value == right.value)

    override fun executeBoolBool(left: MezzeBool, right: MezzeBool): MezzeBool =
        MezzeBool(left.value == right.value)

    override fun executeCharChar(left: MezzeChar, right: MezzeChar): MezzeBool =
        MezzeBool(left.value == right.value)

    override fun executeComplex(left: MezzeValue, right: MezzeValue): MezzeBool {
        return when {
            left is MezzeBytes && right is MezzeBytes ->
                MezzeBool(left.bytes.contentEquals(right.bytes))
            left is MezzeVariant && right is MezzeVariant -> {
                if (left.constructor != right.constructor) {
                    MezzeBool(false)
                } else if (left.value == null && right.value == null) {
                    MezzeBool(true)
                } else if (left.value == null || right.value == null) {
                    MezzeBool(false)
                } else {
                    executeComplex(left.value, right.value)
                }
            }
            left is MezzeArrayList && right is MezzeArrayList -> {
                val leftList = left.toList()
                val rightList = right.toList()
                if (leftList.size != rightList.size) {
                    MezzeBool(false)
                } else {
                    val allEqual = leftList.zip(rightList).all { (elemL: MezzeValue, elemR: MezzeValue) ->
                        compareValuesForEquality(elemL, elemR)
                    }
                    MezzeBool(allEqual)
                }
            }
            left is MezzeList && right is MezzeList -> {
                val leftList = left.toKotlinList()
                val rightList = right.toKotlinList()
                if (leftList.size != rightList.size) {
                    MezzeBool(false)
                } else {
                    val allEqual = leftList.zip(rightList).all { (elemL: MezzeValue, elemR: MezzeValue) ->
                        compareValuesForEquality(elemL, elemR)
                    }
                    MezzeBool(allEqual)
                }
            }
            else -> MezzeBool(false) // Different types are never equal
        }
    }

    private fun compareValuesForEquality(left: MezzeValue, right: MezzeValue): Boolean {
        return when {
            left is MezzeInt && right is MezzeInt -> left.value == right.value
            left is MezzeFloat && right is MezzeFloat -> left.value == right.value
            left is MezzeNat && right is MezzeNat -> left.value == right.value
            left is MezzeFrac && right is MezzeFrac ->
                left.numerator * right.denominator == right.numerator * left.denominator
            left is MezzeString && right is MezzeString -> left.value == right.value
            left is MezzeBool && right is MezzeBool -> left.value == right.value
            left is MezzeChar && right is MezzeChar -> left.value == right.value
            left is MezzeBytes && right is MezzeBytes -> left.bytes.contentEquals(right.bytes)
            left is MezzeVariant && right is MezzeVariant ->
                executeComplex(left, right).value
            left is MezzeArrayList && right is MezzeArrayList ->
                executeComplex(left, right).value
            left is MezzeList && right is MezzeList ->
                executeComplex(left, right).value
            else -> false
        }
    }
}

/**
 * Less than comparison node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "less-than")
class LessThanNode(leftNode: MezzeNode, rightNode: MezzeNode) : BinaryComparisonNode(leftNode, rightNode) {

    override fun executeIntInt(left: MezzeInt, right: MezzeInt): MezzeBool =
        MezzeBool(left.value < right.value)

    override fun executeFloatFloat(left: MezzeFloat, right: MezzeFloat): MezzeBool =
        MezzeBool(left.value < right.value)

    override fun executeNatNat(left: MezzeNat, right: MezzeNat): MezzeBool =
        MezzeBool(left.value < right.value)

    override fun executeFracFrac(left: MezzeFrac, right: MezzeFrac): MezzeBool {
        // Compare fractions: a/b < c/d iff ad < bc (assuming positive denominators)
        return MezzeBool(left.numerator * right.denominator < right.numerator * left.denominator)
    }

    override fun executeStringString(left: MezzeString, right: MezzeString): MezzeBool =
        MezzeBool(left.value < right.value)

    override fun executeBoolBool(left: MezzeBool, right: MezzeBool): MezzeBool =
        MezzeBool(!left.value && right.value) // False < True

    override fun executeCharChar(left: MezzeChar, right: MezzeChar): MezzeBool =
        MezzeBool(left.value < right.value)

    override fun executeComplex(left: MezzeValue, right: MezzeValue): MezzeBool {
        return when {
            left is MezzeBytes && right is MezzeBytes ->
                MezzeBool(compareBytesLexicographic(left, right) < 0)
            left is MezzeVariant && right is MezzeVariant ->
                MezzeBool(compareVariants(left, right) < 0)
            left is MezzeArrayList && right is MezzeArrayList ->
                MezzeBool(compareArrays(left, right) < 0)
            left is MezzeList && right is MezzeList ->
                MezzeBool(compareLists(left, right) < 0)
            else -> throwCannotCompare(left, right)
        }
    }
}

/**
 * Greater than comparison node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "greater-than")
class GreaterThanNode(leftNode: MezzeNode, rightNode: MezzeNode) : BinaryComparisonNode(leftNode, rightNode) {

    override fun executeIntInt(left: MezzeInt, right: MezzeInt): MezzeBool =
        MezzeBool(left.value > right.value)

    override fun executeFloatFloat(left: MezzeFloat, right: MezzeFloat): MezzeBool =
        MezzeBool(left.value > right.value)

    override fun executeNatNat(left: MezzeNat, right: MezzeNat): MezzeBool =
        MezzeBool(left.value > right.value)

    override fun executeFracFrac(left: MezzeFrac, right: MezzeFrac): MezzeBool {
        // Compare fractions: a/b > c/d iff ad > bc (assuming positive denominators)
        return MezzeBool(left.numerator * right.denominator > right.numerator * left.denominator)
    }

    override fun executeStringString(left: MezzeString, right: MezzeString): MezzeBool =
        MezzeBool(left.value > right.value)

    override fun executeBoolBool(left: MezzeBool, right: MezzeBool): MezzeBool =
        MezzeBool(left.value && !right.value) // True > False

    override fun executeCharChar(left: MezzeChar, right: MezzeChar): MezzeBool =
        MezzeBool(left.value > right.value)

    override fun executeComplex(left: MezzeValue, right: MezzeValue): MezzeBool {
        return when {
            left is MezzeBytes && right is MezzeBytes ->
                MezzeBool(compareBytesLexicographic(left, right) > 0)
            left is MezzeVariant && right is MezzeVariant ->
                MezzeBool(compareVariants(left, right) > 0)
            left is MezzeArrayList && right is MezzeArrayList ->
                MezzeBool(compareArrays(left, right) > 0)
            left is MezzeList && right is MezzeList ->
                MezzeBool(compareLists(left, right) > 0)
            else -> throwCannotCompare(left, right)
        }
    }
}

/**
 * Less than or equal comparison node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "less-equal")
class LessEqualNode(leftNode: MezzeNode, rightNode: MezzeNode) : BinaryComparisonNode(leftNode, rightNode) {

    override fun executeIntInt(left: MezzeInt, right: MezzeInt): MezzeBool =
        MezzeBool(left.value <= right.value)

    override fun executeFloatFloat(left: MezzeFloat, right: MezzeFloat): MezzeBool =
        MezzeBool(left.value <= right.value)

    override fun executeNatNat(left: MezzeNat, right: MezzeNat): MezzeBool =
        MezzeBool(left.value <= right.value)

    override fun executeFracFrac(left: MezzeFrac, right: MezzeFrac): MezzeBool =
        MezzeBool(left.numerator * right.denominator <= right.numerator * left.denominator)

    override fun executeStringString(left: MezzeString, right: MezzeString): MezzeBool =
        MezzeBool(left.value <= right.value)

    override fun executeBoolBool(left: MezzeBool, right: MezzeBool): MezzeBool =
        MezzeBool(!left.value || right.value) // False <= True, equal is ok

    override fun executeCharChar(left: MezzeChar, right: MezzeChar): MezzeBool =
        MezzeBool(left.value <= right.value)

    override fun executeComplex(left: MezzeValue, right: MezzeValue): MezzeBool {
        return when {
            left is MezzeBytes && right is MezzeBytes ->
                MezzeBool(compareBytesLexicographic(left, right) <= 0)
            left is MezzeVariant && right is MezzeVariant ->
                MezzeBool(compareVariants(left, right) <= 0)
            left is MezzeArrayList && right is MezzeArrayList ->
                MezzeBool(compareArrays(left, right) <= 0)
            left is MezzeList && right is MezzeList ->
                MezzeBool(compareLists(left, right) <= 0)
            else -> throwCannotCompare(left, right)
        }
    }
}

/**
 * Greater than or equal comparison node with type-specialized methods for JIT optimization.
 */
@NodeInfo(shortName = "greater-equal")
class GreaterEqualNode(leftNode: MezzeNode, rightNode: MezzeNode) : BinaryComparisonNode(leftNode, rightNode) {

    override fun executeIntInt(left: MezzeInt, right: MezzeInt): MezzeBool =
        MezzeBool(left.value >= right.value)

    override fun executeFloatFloat(left: MezzeFloat, right: MezzeFloat): MezzeBool =
        MezzeBool(left.value >= right.value)

    override fun executeNatNat(left: MezzeNat, right: MezzeNat): MezzeBool =
        MezzeBool(left.value >= right.value)

    override fun executeFracFrac(left: MezzeFrac, right: MezzeFrac): MezzeBool =
        MezzeBool(left.numerator * right.denominator >= right.numerator * left.denominator)

    override fun executeStringString(left: MezzeString, right: MezzeString): MezzeBool =
        MezzeBool(left.value >= right.value)

    override fun executeBoolBool(left: MezzeBool, right: MezzeBool): MezzeBool =
        MezzeBool(left.value || !right.value) // True >= False, equal is ok

    override fun executeCharChar(left: MezzeChar, right: MezzeChar): MezzeBool =
        MezzeBool(left.value >= right.value)

    override fun executeComplex(left: MezzeValue, right: MezzeValue): MezzeBool {
        return when {
            left is MezzeBytes && right is MezzeBytes ->
                MezzeBool(compareBytesLexicographic(left, right) >= 0)
            left is MezzeVariant && right is MezzeVariant ->
                MezzeBool(compareVariants(left, right) >= 0)
            left is MezzeArrayList && right is MezzeArrayList ->
                MezzeBool(compareArrays(left, right) >= 0)
            left is MezzeList && right is MezzeList ->
                MezzeBool(compareLists(left, right) >= 0)
            else -> throwCannotCompare(left, right)
        }
    }
}

// Helper functions for lexicographic comparison

/**
 * Compare two bytes arrays lexicographically
 * Returns: negative if left < right, 0 if equal, positive if left > right
 */
private fun compareBytesLexicographic(left: MezzeBytes, right: MezzeBytes): Int {
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
private fun compareVariants(left: MezzeVariant, right: MezzeVariant): Int {
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
private fun compareArrays(left: MezzeArrayList, right: MezzeArrayList): Int {
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
private fun compareLists(left: MezzeList, right: MezzeList): Int {
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
private fun compareValues(left: MezzeValue, right: MezzeValue): Int {
    return when {
        left is MezzeInt && right is MezzeInt -> left.value.compareTo(right.value)
        left is MezzeFloat && right is MezzeFloat -> left.value.compareTo(right.value)
        left is MezzeNat && right is MezzeNat -> left.value.compareTo(right.value)
        left is MezzeFrac && right is MezzeFrac -> {
            val lhs = left.numerator * right.denominator
            val rhs = right.numerator * left.denominator
            lhs.compareTo(rhs)
        }
        left is MezzeString && right is MezzeString -> left.value.compareTo(right.value)
        left is MezzeChar && right is MezzeChar -> left.value.compareTo(right.value)
        left is MezzeBool && right is MezzeBool -> left.value.compareTo(right.value)
        left is MezzeBytes && right is MezzeBytes -> compareBytesLexicographic(left, right)
        left is MezzeVariant && right is MezzeVariant -> compareVariants(left, right)
        left is MezzeArrayList && right is MezzeArrayList -> compareArrays(left, right)
        left is MezzeList && right is MezzeList -> compareLists(left, right)
        else -> throwCompareError(left, right)
    }
}

/**
 * Throws a comparison error for incompatible types.
 * Top-level boundary function for use in private helper functions.
 */
@TruffleBoundary
private fun throwCompareError(left: MezzeValue, right: MezzeValue): Nothing {
    throw IllegalArgumentException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
}