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
    abstract fun toDisplayString(): String
    
    @ExportMessage
    open fun toDisplayString(allowSideEffects: Boolean): String = toDisplayString()
    
    @ExportMessage
    open fun hasLanguage(): Boolean = true
    
    @ExportMessage
    open fun getLanguage(): Class<*> = FunkLanguage::class.java
    
    override fun toString(): String = toDisplayString()
}

/**
 * Null/Unit value
 */
@ExportLibrary(InteropLibrary::class)
object FunkNull : FunkValue() {
    override fun toDisplayString(): String = "null"
    
    @ExportMessage
    fun isNull(): Boolean = true
}

/**
 * Integer value
 */
@ExportLibrary(InteropLibrary::class)
data class FunkInt(val value: Long) : FunkValue() {
    override fun toDisplayString(): String = value.toString()
    
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
}

/**
 * Floating point value
 */
@ExportLibrary(InteropLibrary::class)
data class FunkFloat(val value: Double) : FunkValue() {
    override fun toDisplayString(): String = value.toString()
    
    @ExportMessage
    fun isNumber(): Boolean = true
    
    @ExportMessage
    fun fitsInDouble(): Boolean = true
    
    @ExportMessage
    fun asDouble(): Double = value
}

/**
 * String value
 */
@ExportLibrary(InteropLibrary::class)
data class FunkString(val value: String) : FunkValue() {
    override fun toDisplayString(): String = "\"$value\""
    
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
    override fun toDisplayString(): String = value.toString()
    
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
    override fun toDisplayString(): String {
        val fieldStrings = fields.map { (key, value) -> "$key = ${value.toDisplayString()}" }
        return "{ ${fieldStrings.joinToString(", ")} }"
    }
    
    @ExportMessage
    fun hasMembers(): Boolean = true
    
    @ExportMessage
    fun getMembers(includeInternal: Boolean): Array<String> = fields.keys.toTypedArray()
    
    @ExportMessage
    fun isMemberReadable(member: String): Boolean = fields.containsKey(member)
    
    @ExportMessage
    fun readMember(member: String): FunkValue {
        return fields[member] ?: throw FunkException.runtimeError("Field '$member' not found in record")
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
    override fun toDisplayString(): String {
        return if (value != null) {
            "$constructor(${value.toDisplayString()})"
        } else {
            constructor
        }
    }
    
    @ExportMessage
    fun hasMembers(): Boolean = true
    
    @ExportMessage
    fun getMembers(includeInternal: Boolean): Array<String> {
        return if (value != null) arrayOf("constructor", "value") else arrayOf("constructor")
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
            else -> throw FunkException.runtimeError("Field '$member' not found in variant")
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
    override fun toDisplayString(): String = name ?: "<function>"
    
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
    val parameters: com.funk.language.parser.LambdaParameters,
    val body: com.funk.language.parser.Expression,
    val capturedEnvironment: Map<String, FunkValue>,
    val name: String? = null
) : FunkValue() {
    override fun toDisplayString(): String = name ?: "<function>"
    
    @ExportMessage
    fun isExecutable(): Boolean = true
    
    @ExportMessage
    fun execute(arguments: Array<Any?>): Any {
        // For interop, we'd need to convert arguments and call through interpreter
        throw FunkException.runtimeError("Direct interop execution not implemented")
    }
}
