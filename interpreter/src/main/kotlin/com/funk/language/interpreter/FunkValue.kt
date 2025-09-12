package com.funk.language.interpreter

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage

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
    
    // BigInteger methods removed for Oracle GraalVM 17 compatibility
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
    
    // BigInteger methods removed for Oracle GraalVM 17 compatibility
    
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
            "$constructor(${value.getDisplayString()})"
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
