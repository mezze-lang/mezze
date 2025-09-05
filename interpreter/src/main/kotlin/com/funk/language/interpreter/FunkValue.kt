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
            throw FunkException.runtimeError("Array index out of bounds: $index")
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
    fun fitsInBigInteger(): Boolean = false
    
    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun asBigInteger(): java.math.BigInteger {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }
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
    
    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun asBigInteger(): java.math.BigInteger {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }
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
// Temporarily disable InteropLibrary to isolate the issue
// @ExportLibrary(InteropLibrary::class)
data class FunkRecord(val fields: Map<String, FunkValue>) : FunkValue() {
    override fun getDisplayString(): String {
        val fieldStrings = fields.map { (key, value) -> "$key = ${value.getDisplayString()}" }
        return "{ ${fieldStrings.joinToString(", ")} }"
    }
    
    // Temporarily disable all interop methods
    // @ExportMessage
    // fun hasMembers(): Boolean = false
    
    // @ExportMessage
    // fun getMembers(includeInternal: Boolean): Any {
    //     throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    // }
    
    // @ExportMessage
    // fun isMemberReadable(member: String): Boolean = fields.containsKey(member)
    
    // @ExportMessage
    // fun readMember(member: String): FunkValue {
    //     return fields[member] ?: throw FunkException.runtimeError("Field '$member' not found in record")
    // }
    
    /**
     * Read a member field (non-interop version)
     */
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
    val parameters: com.funk.language.parser.LambdaParameters,
    val body: com.funk.language.parser.Expression,
    val capturedEnvironment: Map<String, FunkValue>,
    val name: String? = null
) : FunkValue() {
    override fun getDisplayString(): String = name ?: "<function>"
    
    /**
     * Call this function with the given arguments using Truffle execution
     */
    fun call(arguments: FunkValue, context: com.funk.language.interpreter.FunkContext): FunkValue {
        // Save current locals to restore later
        val savedLocals = context.locals.toMap()
        
        try {
            // Add captured environment to context
            capturedEnvironment.forEach { (name, value) ->
                context.defineLocal(name, value)
            }
            
            // Bind parameters to the current context
            when (parameters) {
                is com.funk.language.parser.TypedParameters -> {
                    // For typed parameters, we expect a record argument
                    if (arguments is FunkRecord) {
                        bindRecordParameters(parameters.recordType, arguments, context)
                    } else {
                        throw FunkException.typeError("Expected record argument for typed function parameters")
                    }
                }
                is com.funk.language.parser.UntypedParameters -> {
                    // For untyped parameters, we expect a record argument
                    if (arguments is FunkRecord) {
                        bindUntypedParameters(parameters.names, arguments, context)
                    } else {
                        throw FunkException.typeError("Expected record argument for untyped function parameters")
                    }
                }
            }
            
            // Execute function body using Truffle
            val converter = com.funk.language.interpreter.ASTToTruffleConverter()
            val bodyNode = converter.convertExpression(body)
            
            // Create a temporary frame for execution
            val frameDescriptor = com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build()
            val frame = com.oracle.truffle.api.Truffle.getRuntime().createVirtualFrame(arrayOf(), frameDescriptor)
            
            return bodyNode.executeGeneric(frame)
        } finally {
            // Restore original locals
            context.locals.clear()
            context.locals.putAll(savedLocals)
        }
    }
    
    private fun bindRecordParameters(recordType: com.funk.language.parser.RecordType, arguments: FunkRecord, context: com.funk.language.interpreter.FunkContext) {
        for (entry in recordType.entries) {
            val value = arguments.fields[entry.name] 
                ?: throw FunkException.runtimeError("Missing argument: ${entry.name}")
            context.defineLocal(entry.name, value)
        }
    }
    
    private fun bindUntypedParameters(paramNames: List<String>, arguments: FunkRecord, context: com.funk.language.interpreter.FunkContext) {
        for (paramName in paramNames) {
            val value = arguments.fields[paramName] 
                ?: throw FunkException.runtimeError("Missing argument: $paramName")
            context.defineLocal(paramName, value)
        }
    }
    
    @ExportMessage
    fun isExecutable(): Boolean = true
    
    @ExportMessage
    fun execute(arguments: Array<Any?>): Any {
        // For interop, we'd need to convert arguments and call through interpreter
        throw FunkException.runtimeError("Direct interop execution not implemented")
    }
}
