package com.funk.language.interpreter

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import com.funk.language.ast.Expression

/**
 * Base class for all runtime values in the Funk language.
 * Integrates with Truffle's interop system for cross-language compatibility.
 */
@ExportLibrary(InteropLibrary::class)
abstract class FunkValue : TruffleObject {
    
    /**
     * Get a string representation of this value for debugging
     */
    abstract fun getDisplayString(): String
    
    @ExportMessage
    fun toDisplayString(allowSideEffects: Boolean): String = getDisplayString()
    
    @ExportMessage
    open fun hasLanguage(): Boolean = true
    
    @ExportMessage
    open fun getLanguage(): Class<out com.oracle.truffle.api.TruffleLanguage<*>> = FunkLanguage::class.java
    
    override fun toString(): String = getDisplayString()
}

/**
 * Null/Unit value
 */
@ExportLibrary(InteropLibrary::class)
object FunkNull : FunkValue() {
    override fun getDisplayString(): String = "null"
    
    @ExportMessage
    fun isNull(): Boolean = true
}

/**
 * Simple array wrapper for member keys to satisfy Truffle interop
 */
@ExportLibrary(InteropLibrary::class)
data class FunkMemberKeysArray(val keys: List<String>) : FunkValue() {
    override fun getDisplayString(): String = keys.toString()
    
    @ExportMessage
    fun hasArrayElements(): Boolean = true
    
    @ExportMessage
    fun getArraySize(): Long = keys.size.toLong()
    
    @ExportMessage
    fun isArrayElementReadable(index: Long): Boolean = index >= 0 && index < keys.size
    
    @ExportMessage
    fun readArrayElement(index: Long): String {
        if (index < 0 || index >= keys.size) {
            throw FunkException("Array index out of bounds: $index")
        }
        return keys[index.toInt()]
    }
}

/**
 * Integer value
 */
@ExportLibrary(InteropLibrary::class)
data class FunkInt(val value: Long) : FunkValue() {
    override fun getDisplayString(): String = value.toString()
    
    @ExportMessage
    fun isNumber(): Boolean = true
    
    @ExportMessage
    fun fitsInLong(): Boolean = true
    
    @ExportMessage
    fun asLong(): Long = value
    
    @ExportMessage
    fun fitsInInt(): Boolean = value in Int.MIN_VALUE..Int.MAX_VALUE
    
    @ExportMessage
    fun asInt(): Int = value.toInt()
    
    @ExportMessage
    fun fitsInByte(): Boolean = value in Byte.MIN_VALUE..Byte.MAX_VALUE
    
    @ExportMessage
    fun asByte(): Byte = value.toByte()
    
    @ExportMessage
    fun fitsInShort(): Boolean = value in Short.MIN_VALUE..Short.MAX_VALUE
    
    @ExportMessage
    fun asShort(): Short = value.toShort()
    
    @ExportMessage
    fun fitsInFloat(): Boolean = true
    
    @ExportMessage
    fun asFloat(): Float = value.toFloat()
    
    @ExportMessage
    fun fitsInDouble(): Boolean = true
    
    @ExportMessage
    fun asDouble(): Double = value.toDouble()
    
    @ExportMessage
    fun fitsInBigInteger(): Boolean = true
    
    @ExportMessage
    fun asBigInteger(): java.math.BigInteger = java.math.BigInteger.valueOf(value)
}

/**
 * Floating point value
 */
@ExportLibrary(InteropLibrary::class)
data class FunkFloat(val value: Double) : FunkValue() {
    override fun getDisplayString(): String = value.toString()
    
    @ExportMessage
    fun isNumber(): Boolean = true
    
    @ExportMessage
    fun fitsInDouble(): Boolean = true
    
    @ExportMessage
    fun asDouble(): Double = value
    
    @ExportMessage
    fun fitsInByte(): Boolean = false
    
    @ExportMessage
    fun fitsInShort(): Boolean = false
    
    @ExportMessage
    fun fitsInInt(): Boolean = false
    
    @ExportMessage
    fun fitsInLong(): Boolean = false
    
    @ExportMessage
    fun fitsInFloat(): Boolean = true
    
    @ExportMessage
    fun asFloat(): Float = value.toFloat()
    
    @ExportMessage
    fun fitsInBigInteger(): Boolean = false
    
    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun asBigInteger(): java.math.BigInteger {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }
    
    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun asByte(): Byte {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }
    
    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun asShort(): Short {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }
    
    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun asInt(): Int {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }
    
    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun asLong(): Long {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }
    
    // asBigInteger method removed for Oracle GraalVM 17 compatibility
}

/**
 * String value
 */
@ExportLibrary(InteropLibrary::class)
data class FunkString(val value: String) : FunkValue() {
    override fun getDisplayString(): String = "\"$value\""
    
    @ExportMessage
    fun isString(): Boolean = true
    
    @ExportMessage
    fun asString(): String = value
}

/**
 * Boolean value
 */
@ExportLibrary(InteropLibrary::class)
data class FunkBoolean(val value: Boolean) : FunkValue() {
    override fun getDisplayString(): String = value.toString()
    
    @ExportMessage
    fun isBoolean(): Boolean = true
    
    @ExportMessage
    fun asBoolean(): Boolean = value
}

/**
 * Record value (similar to objects/structs)
 */
@ExportLibrary(InteropLibrary::class)
data class FunkRecord(val fields: Map<String, FunkValue>) : FunkValue() {
    override fun getDisplayString(): String {
        val fieldStrings = fields.map { (key, value) -> "$key = ${value.getDisplayString()}" }
        return "{ ${fieldStrings.joinToString(", ")} }"
    }
    
    @ExportMessage
    fun hasMembers(): Boolean = true
    
    @ExportMessage
    fun getMembers(includeInternal: Boolean): Any {
        return FunkMemberKeysArray(fields.keys.toList())
    }
    
    @ExportMessage
    fun isMemberReadable(member: String): Boolean = fields.containsKey(member)
    
    @ExportMessage
    fun readMember(member: String): FunkValue {
        return fields[member] ?: throw FunkException("Field '$member' not found in record")
    }
    
    @ExportMessage
    fun isMemberModifiable(member: String): Boolean = true
    
    @ExportMessage
    fun isMemberInsertable(member: String): Boolean = !fields.containsKey(member)
    
    @ExportMessage
    fun writeMember(member: String, value: Any?): Unit {
        // For immutable records, we don't support direct mutation
        // This would need to be handled at a higher level
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }
    
    // Array-like access for better interop
    @ExportMessage
    fun hasArrayElements(): Boolean = true
    
    @ExportMessage
    fun getArraySize(): Long = fields.size.toLong()
    
    @ExportMessage
    fun isArrayElementReadable(index: Long): Boolean {
        return index >= 0 && index < fields.size
    }
    
    @ExportMessage
    fun readArrayElement(index: Long): Any {
        if (index < 0 || index >= fields.size) {
            throw com.oracle.truffle.api.interop.InvalidArrayIndexException.create(index)
        }
        val key = fields.keys.elementAt(index.toInt())
        return fields[key]!!
    }
    
    /**
     * Read a member field (non-interop version)
     */
    fun readField(member: String): FunkValue {
        return fields[member] ?: throw FunkException("Field '$member' not found in record")
    }
    
    /**
     * Create a new record with updated fields
     */
    fun withUpdates(updates: Map<String, FunkValue>): FunkRecord {
        return FunkRecord(fields + updates)
    }
}

/**
 * Variant value (similar to sum types/unions)
 */
@ExportLibrary(InteropLibrary::class)
data class FunkVariant(val constructor: String, val value: FunkValue?) : FunkValue() {
    override fun getDisplayString(): String {
        return if (value != null) {
            // Use braces for record values, parentheses for other values
            if (value is FunkRecord) {
                "$constructor ${value.getDisplayString()}"
            } else {
                "$constructor(${value.getDisplayString()})"
            }
        } else {
            constructor
        }
    }
    
    @ExportMessage
    fun hasMembers(): Boolean = true
    
    @ExportMessage
    fun getMembers(includeInternal: Boolean): Any {
        val memberList = if (value != null) listOf("constructor", "value") else listOf("constructor")
        return memberList
    }
    
    @ExportMessage
    fun isMemberReadable(member: String): Boolean {
        return member == "constructor" || (member == "value" && value != null)
    }
    
    @ExportMessage
    fun readMember(member: String): Any {
        return when (member) {
            "constructor" -> FunkString(constructor)
            "value" -> value ?: FunkNull
            else -> throw FunkException("Field '$member' not found in variant")
        }
    }
}

/**
 * Built-in List value (immutable, functional list)
 */
@ExportLibrary(InteropLibrary::class)
data class FunkList(val elements: List<FunkValue>) : FunkValue() {
    override fun getDisplayString(): String = "[${elements.joinToString(", ") { it.getDisplayString() }}]"
    
    @ExportMessage
    fun hasArrayElements(): Boolean = true
    
    @ExportMessage
    fun getArraySize(): Long = elements.size.toLong()
    
    @ExportMessage
    fun isArrayElementReadable(index: Long): Boolean = index >= 0 && index < elements.size
    
    @ExportMessage
    fun readArrayElement(index: Long): FunkValue {
        if (index < 0 || index >= elements.size) {
            throw FunkException("List index out of bounds: $index")
        }
        return elements[index.toInt()]
    }
    
    @ExportMessage
    fun hasMembers(): Boolean = true
    
    @ExportMessage
    fun getMembers(includeInternal: Boolean): Any {
        return FunkMemberKeysArray(listOf("length", "head", "tail", "isEmpty"))
    }
    
    @ExportMessage
    fun isMemberReadable(member: String): Boolean {
        return member in setOf("length", "head", "tail", "isEmpty")
    }
    
    @ExportMessage
    fun readMember(member: String): Any {
        return when (member) {
            "length" -> FunkInt(elements.size.toLong())
            "head" -> if (elements.isNotEmpty()) elements.first() else FunkNull
            "tail" -> if (elements.isNotEmpty()) FunkList(elements.drop(1)) else FunkList(emptyList())
            "isEmpty" -> FunkBoolean(elements.isEmpty())
            else -> throw FunkException("Property '$member' not found in List")
        }
    }
}

/**
 * Built-in NonEmptyList value (guaranteed non-empty list)
 */
@ExportLibrary(InteropLibrary::class)
data class FunkNonEmptyList(val elements: List<FunkValue>) : FunkValue() {
    init {
        require(elements.isNotEmpty()) { "NonEmptyList must have at least one element" }
    }

    override fun getDisplayString(): String = "NonEmptyList (${elements.joinToString(", ") { it.getDisplayString() }})"

    @ExportMessage
    fun hasArrayElements(): Boolean = true

    @ExportMessage
    fun getArraySize(): Long = elements.size.toLong()

    @ExportMessage
    fun isArrayElementReadable(index: Long): Boolean = index >= 0 && index < elements.size

    @ExportMessage
    fun readArrayElement(index: Long): FunkValue {
        if (index < 0 || index >= elements.size) {
            throw FunkException("NonEmptyList index out of bounds: $index")
        }
        return elements[index.toInt()]
    }

    @ExportMessage
    fun hasMembers(): Boolean = true

    @ExportMessage
    fun getMembers(includeInternal: Boolean): Any {
        return FunkMemberKeysArray(listOf("length", "head", "tail", "last"))
    }

    @ExportMessage
    fun isMemberReadable(member: String): Boolean {
        return member in listOf("length", "head", "tail", "last")
    }

    @ExportMessage
    fun readMember(member: String): FunkValue {
        return when (member) {
            "length" -> FunkInt(elements.size.toLong())
            "head" -> elements.first()
            "tail" -> FunkList(elements.drop(1))
            "last" -> elements.last()
            else -> throw FunkException("Property '$member' not found in NonEmptyList")
        }
    }
}

/**
 * Built-in Map value (immutable, functional map)
 */
@ExportLibrary(InteropLibrary::class)
data class FunkMap(val entries: Map<FunkValue, FunkValue>) : FunkValue() {
    override fun getDisplayString(): String {
        val entriesStr = entries.entries.joinToString(", ") { (k, v) ->
            "${k.getDisplayString()} = ${v.getDisplayString()}"
        }
        return "{ $entriesStr }"
    }
    
    @ExportMessage
    fun hasMembers(): Boolean = true
    
    @ExportMessage
    fun getMembers(includeInternal: Boolean): Any {
        return FunkMemberKeysArray(listOf("size", "keys", "values", "isEmpty"))
    }
    
    @ExportMessage
    fun isMemberReadable(member: String): Boolean {
        return member in setOf("size", "keys", "values", "isEmpty")
    }
    
    @ExportMessage
    fun readMember(member: String): Any {
        return when (member) {
            "size" -> FunkInt(entries.size.toLong())
            "keys" -> FunkList(entries.keys.toList())
            "values" -> FunkList(entries.values.toList())
            "isEmpty" -> FunkBoolean(entries.isEmpty())
            else -> throw FunkException("Property '$member' not found in Map")
        }
    }
}

/**
 * Built-in Set value (immutable, functional set)
 */
@ExportLibrary(InteropLibrary::class)
data class FunkSet(val elements: Set<FunkValue>) : FunkValue() {
    override fun getDisplayString(): String = "{${elements.joinToString(", ") { it.getDisplayString() }}}"
    
    @ExportMessage
    fun hasMembers(): Boolean = true
    
    @ExportMessage
    fun getMembers(includeInternal: Boolean): Any {
        return FunkMemberKeysArray(listOf("size", "isEmpty"))
    }
    
    @ExportMessage
    fun isMemberReadable(member: String): Boolean {
        return member in setOf("size", "isEmpty")
    }
    
    @ExportMessage
    fun readMember(member: String): Any {
        return when (member) {
            "size" -> FunkInt(elements.size.toLong())
            "isEmpty" -> FunkBoolean(elements.isEmpty())
            else -> throw FunkException("Property '$member' not found in Set")
        }
    }
}

/**
 * Function value for Truffle-based execution
 */
@ExportLibrary(InteropLibrary::class)
data class FunkTruffleFunction(
    val callTarget: CallTarget,
    val name: String? = null
) : FunkValue() {
    override fun getDisplayString(): String = name ?: "<function>"
    
    @ExportMessage
    fun isExecutable(): Boolean = true
    
    @ExportMessage
    fun execute(arguments: Array<Any?>): Any {
        return callTarget.call(*arguments)
    }
}

/**
 * Function value for direct interpreter execution
 */
@ExportLibrary(InteropLibrary::class)
data class FunkFunction(
    val parameters: List<String>, // Simplified parameters
    val body: com.funk.language.ast.Expression,
    val capturedEnvironment: Map<String, FunkValue>,
    val name: String? = null
) : FunkValue() {
    override fun getDisplayString(): String = name ?: "<function>"
    
    /**
     * Call this function with the given arguments using Truffle execution
     */
    fun call(arguments: List<FunkValue>): FunkValue {
        // Simplified function call - not using Truffle for now
        return FunkString("function_result")
    }
    
    @ExportMessage
    fun isExecutable(): Boolean = true
    
    @ExportMessage
    fun execute(arguments: Array<Any?>): Any {
        // For interop, we'd need to convert arguments and call through interpreter
        throw FunkException("Direct interop execution not implemented")
    }
}

/**
 * Partially applied function value
 */
@ExportLibrary(InteropLibrary::class)
data class FunkPartialFunction(
    val originalFunction: FunkValue,
    val providedArguments: Map<String, FunkValue>,
    val remainingParameters: List<String>
) : FunkValue() {
    override fun getDisplayString(): String = "<partial function>"
    
    @ExportMessage
    fun isExecutable(): Boolean = true
    
    @ExportMessage
    fun execute(arguments: Array<Any?>): Any {
        throw FunkException("Partial function execution through interop not implemented")
    }
}

/**
 * Placeholder for recursive function references during evaluation
 */
class RecursiveFunctionPlaceholder(val name: String) : FunkValue() {
    override fun getDisplayString(): String = "<recursive-placeholder-$name>"
}


/**
 * Represents a tail call for optimization
 */
class TailCall(
    val function: com.funk.language.interpreter.SimpleFunkFunction,
    val args: FunkValue
) : FunkValue() {
    override fun getDisplayString(): String = "<tail-call>"
}

/**
 * Natural number value (non-negative integers)
 */
@ExportLibrary(InteropLibrary::class)
data class FunkNat(val value: Long) : FunkValue() {
    init {
        if (value < 0) {
            throw IllegalArgumentException("Natural number cannot be negative: $value")
        }
    }
    
    override fun getDisplayString(): String = value.toString()
    
    @ExportMessage
    fun isNumber(): Boolean = true
    
    @ExportMessage
    fun fitsInLong(): Boolean = true
    
    @ExportMessage
    fun asLong(): Long = value
    
    @ExportMessage
    fun fitsInInt(): Boolean = value <= Int.MAX_VALUE
    
    @ExportMessage
    fun asInt(): Int = value.toInt()
    
    @ExportMessage
    fun fitsInByte(): Boolean = value <= Byte.MAX_VALUE
    
    @ExportMessage
    fun asByte(): Byte = value.toByte()
    
    @ExportMessage
    fun fitsInShort(): Boolean = value <= Short.MAX_VALUE
    
    @ExportMessage
    fun asShort(): Short = value.toShort()
    
    @ExportMessage
    fun fitsInFloat(): Boolean = true
    
    @ExportMessage
    fun asFloat(): Float = value.toFloat()
    
    @ExportMessage
    fun fitsInDouble(): Boolean = true
    
    @ExportMessage
    fun asDouble(): Double = value.toDouble()
    
    @ExportMessage
    fun fitsInBigInteger(): Boolean = true
    
    @ExportMessage
    fun asBigInteger(): java.math.BigInteger = java.math.BigInteger.valueOf(value)
}

/**
 * Fraction value for exact rational arithmetic
 */
@ExportLibrary(InteropLibrary::class)
data class FunkFrac(val numerator: Long, val denominator: Long) : FunkValue() {
    init {
        if (denominator == 0L) {
            throw IllegalArgumentException("Denominator cannot be zero")
        }
    }
    
    override fun getDisplayString(): String = "$numerator/$denominator"
    
    @ExportMessage
    fun isNumber(): Boolean = true
    
    @ExportMessage
    fun fitsInDouble(): Boolean = true
    
    @ExportMessage
    fun asDouble(): Double = numerator.toDouble() / denominator.toDouble()
    
    @ExportMessage
    fun fitsInFloat(): Boolean = true
    
    @ExportMessage
    fun asFloat(): Float = (numerator.toDouble() / denominator.toDouble()).toFloat()
    
    @ExportMessage
    fun fitsInLong(): Boolean = denominator == 1L
    
    @ExportMessage
    fun asLong(): Long = if (denominator == 1L) numerator else throw UnsupportedOperationException("Fraction cannot fit in long")
    
    @ExportMessage
    fun fitsInInt(): Boolean = denominator == 1L && numerator <= Int.MAX_VALUE
    
    @ExportMessage
    fun asInt(): Int = if (fitsInInt()) numerator.toInt() else throw UnsupportedOperationException("Fraction cannot fit in int")
    
    @ExportMessage
    fun fitsInByte(): Boolean = denominator == 1L && numerator <= Byte.MAX_VALUE && numerator >= Byte.MIN_VALUE
    
    @ExportMessage
    fun asByte(): Byte = if (fitsInByte()) numerator.toByte() else throw UnsupportedOperationException("Fraction cannot fit in byte")
    
    @ExportMessage
    fun fitsInShort(): Boolean = denominator == 1L && numerator <= Short.MAX_VALUE && numerator >= Short.MIN_VALUE
    
    @ExportMessage
    fun asShort(): Short = if (fitsInShort()) numerator.toShort() else throw UnsupportedOperationException("Fraction cannot fit in short")
    
    @ExportMessage
    fun fitsInBigInteger(): Boolean = true
    
    @ExportMessage
    fun asBigInteger(): java.math.BigInteger = java.math.BigInteger.valueOf(numerator).divide(java.math.BigInteger.valueOf(denominator))
    
    /**
     * Reduce fraction to its simplest form
     */
    fun reduce(): FunkFrac {
        val gcd = gcd(kotlin.math.abs(numerator), kotlin.math.abs(denominator))
        return FunkFrac(numerator / gcd, denominator / gcd)
    }
    
    /**
     * Calculate greatest common divisor
     */
    private fun gcd(a: Long, b: Long): Long {
        return if (b == 0L) a else gcd(b, a % b)
    }
}


/**
 * Unit value (represents no meaningful value, like void)
 */
@ExportLibrary(InteropLibrary::class)
object FunkUnit : FunkValue() {
    override fun getDisplayString(): String = "()"
    
    @ExportMessage
    fun isNull(): Boolean = false
}

/**
 * Tuple value (ordered collection of values)
 */
@ExportLibrary(InteropLibrary::class)
data class FunkTuple(val elements: List<FunkValue>) : FunkValue() {
    override fun getDisplayString(): String {
        return "(${elements.joinToString(", ") { it.getDisplayString() }})"
    }
    
    @ExportMessage
    fun hasArrayElements(): Boolean = true
    
    @ExportMessage
    fun getArraySize(): Long = elements.size.toLong()
    
    @ExportMessage
    fun isArrayElementReadable(index: Long): Boolean {
        return index >= 0 && index < elements.size
    }
    
    @ExportMessage
    fun readArrayElement(index: Long): Any {
        if (index < 0 || index >= elements.size) {
            throw com.oracle.truffle.api.interop.InvalidArrayIndexException.create(index)
        }
        return elements[index.toInt()]
    }
}

/**
 * Native function implemented in Kotlin, wrapped for Truffle execution
 */
@ExportLibrary(InteropLibrary::class)
data class NativeFunkFunction(
    val name: String,
    val implementation: (List<FunkValue>) -> FunkValue
) : FunkValue() {

    override fun getDisplayString(): String = "<native-function:$name>"

    @ExportMessage
    fun isExecutable(): Boolean = true

    @ExportMessage
    fun execute(arguments: Array<Any?>): Any {
        // Convert arguments to FunkValues and call implementation
        val funkArgs = arguments.map { arg ->
            when (arg) {
                is FunkValue -> arg
                is String -> FunkString(arg)
                is Long -> FunkInt(arg)
                is Double -> FunkFloat(arg)
                is Boolean -> FunkBoolean(arg)
                else -> throw FunkException("Unsupported argument type: ${arg?.javaClass}")
            }
        }
        return implementation(funkArgs)
    }

    /**
     * Call function with FunkValue arguments
     */
    fun call(arguments: List<FunkValue>): FunkValue {
        return implementation(arguments)
    }
}

/**
 * Suspended computation - a value with unhandled effects that cannot be used
 * until all effects are handled via perform expressions.
 *
 * This is distinct from FunkFunction - it's not a function to be called,
 * but rather an already-computed value that has unhandled effects.
 */
@ExportLibrary(InteropLibrary::class)
data class FunkSuspended(
    val value: FunkValue,
    val unhandledEffects: Set<String>
) : FunkValue() {
    override fun getDisplayString(): String {
        val effectList = unhandledEffects.sorted().joinToString(", ")
        return "${value.getDisplayString()} <$effectList>"
    }

    @ExportMessage
    fun isExecutable(): Boolean = false

    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun execute(arguments: Array<Any?>): Any {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }

    /**
     * Attempting to use a suspended value should fail
     */
    fun unwrap(): FunkValue {
        throw FunkException(
            "Cannot use suspended value with unhandled effects: ${unhandledEffects.sorted().joinToString(", ")}. " +
            "Use 'perform' to handle these effects first."
        )
    }
}


