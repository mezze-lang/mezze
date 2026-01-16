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
 * Bool value
 */
@ExportLibrary(InteropLibrary::class)
data class FunkBool(val value: Boolean) : FunkValue() {
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
            "isEmpty" -> FunkBool(elements.isEmpty())
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
            "isEmpty" -> FunkBool(entries.isEmpty())
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
            "isEmpty" -> FunkBool(elements.isEmpty())
            else -> throw FunkException("Property '$member' not found in Set")
        }
    }
}

/**
 * Function value for Truffle-based execution with closure support
 */
@ExportLibrary(InteropLibrary::class)
data class FunkTruffleFunction(
    val callTarget: CallTarget,
    val name: String? = null,
    val closureVariables: Map<String, FunkValue> = emptyMap(),
    val lexicalScope: com.oracle.truffle.api.frame.MaterializedFrame? = null,  // TRUFFLE BEST PRACTICE: Store lexical scope
    val lexicalScopeSlotMap: Map<String, Int> = emptyMap()  // Maps variable names to slot numbers in lexical scope frame
) : FunkValue() {
    override fun getDisplayString(): String = name ?: "<function>"

    @ExportMessage
    fun isExecutable(): Boolean = true

    @ExportMessage
    fun execute(arguments: Array<Any?>): Any {
        // CRITICAL: FunkFunctionRootNode expects 4 arguments for lexical scope support:
        // [0] = FunkRecord of parameters
        // [1] = Map<String, FunkValue> of closure variables (can be empty, for backward compat)
        // [2] = MaterializedFrame lexical scope (null if not needed)
        // [3] = Map<String, Int> lexical scope slot mapping (empty if not needed)

        // Convert interop arguments to FunkRecord if needed
        val argsRecord = when {
            arguments.isEmpty() -> FunkRecord(emptyMap())
            arguments[0] is FunkRecord -> arguments[0] as FunkRecord
            else -> {
                // If arguments is a single value, wrap it in a record with "value" key
                // This handles the case where continuation resume passes a value
                FunkRecord(mapOf("value" to (arguments[0] as? FunkValue ?: FunkNull)))
            }
        }

        return callTarget.call(argsRecord, closureVariables, lexicalScope, lexicalScopeSlotMap)
    }

    override fun toString(): String = "FunkTruffleFunction(${name ?: "anonymous"}, ${closureVariables.size} closure vars, lexicalScope=${lexicalScope != null})"
}

// OLD INTERPRETER CODE REMOVED:
// - FunkFunction (old map-based interpreter function)
// - FunkPartialFunction (old partial application)
// - RecursiveFunctionPlaceholder (old recursion handling)
//
// All functions now use FunkTruffleFunction with Truffle call targets.
// Partial application is handled by PartiallyAppliedFunction (wraps Truffle closures).
// Recursion is handled natively by Truffle's lexical scoping.

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
                is Boolean -> FunkBool(arg)
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
 * Suspended computation - a deferred computation with unhandled effects.
 *
 * CRITICAL: This is the lazy effects model - effects don't execute immediately!
 * Instead, they create a FunkSuspended containing:
 * 1. A thunk (FunkThunk) - the deferred computation to execute later
 * 2. Unhandled effects - which effects need handlers to execute the thunk
 * 3. Captured handlers - handlers already applied (for partial handling)
 *
 * Example flow:
 *   let a: String <Db> = Db.read {key = "name"}
 *   # Without Db handler → creates FunkSuspended(thunk=Db.read{...}, effects={Db})
 *   # The computation doesn't execute! It's deferred until perform.
 *
 *   perform DbHandler in do
 *     print {msg = a}  # NOW the thunk executes with DbHandler available
 *
 * Operations on FunkSuspended compose thunks:
 *   let b = "$(a) world"
 *   # Creates new FunkSuspended with composed thunk: concat(execute_a(), " world")
 */
@ExportLibrary(InteropLibrary::class)
data class FunkSuspended(
    val computation: FunkThunk,              // Deferred computation to execute
    val unhandledEffects: Set<String>,       // Effects that need handlers
    val capturedHandlers: Map<String, FunkValue> = emptyMap()  // Handlers already applied
) : FunkValue() {
    override fun getDisplayString(): String {
        val effectList = unhandledEffects.sorted().joinToString(", ")
        return "<suspended <$effectList>>"
    }

    @ExportMessage
    fun isExecutable(): Boolean = false

    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun execute(arguments: Array<Any?>): Any {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }

    /**
     * Attempting to use a suspended value directly should fail with helpful error
     */
    fun unwrap(): FunkValue {
        throw FunkException(
            "Cannot use suspended computation with unhandled effects: ${unhandledEffects.sorted().joinToString(", ")}. " +
            "Use 'perform' to provide handlers for these effects first."
        )
    }

    /**
     * Execute the suspended computation with handlers available in context.
     * This is called by perform when all handlers are available.
     */
    fun executeWith(
        handlers: Map<String, FunkValue>,
        context: FunkContext,
        frame: com.oracle.truffle.api.frame.VirtualFrame
    ): FunkValue {
        // Merge captured handlers with new handlers
        val allHandlers = capturedHandlers + handlers

        // Check if all required effects are now handled
        val stillUnhandled = unhandledEffects - allHandlers.keys
        if (stillUnhandled.isNotEmpty()) {
            // Still missing handlers - return new FunkSuspended with remaining effects
            return FunkSuspended(
                computation = computation,
                unhandledEffects = stillUnhandled,
                capturedHandlers = allHandlers
            )
        }

        // All handlers available - execute the thunk!
        // Push handlers onto effect handler stack
        val effectHandler = context.effectHandler
        val handlersRecord = FunkRecord(allHandlers)
        effectHandler.pushHandlers(handlersRecord)

        try {
            // Execute the thunk's function with its arguments
            return when (val func = computation.function) {
                is FunkTruffleFunction -> {
                    func.callTarget.call(computation.arguments, func.closureVariables, func.lexicalScope, func.lexicalScopeSlotMap) as FunkValue
                }
                is NativeFunkFunction -> {
                    func.call(listOf(computation.arguments))
                }
                else -> {
                    throw FunkException("Cannot execute thunk with function type: ${func::class.simpleName}")
                }
            }
        } finally {
            effectHandler.popHandlers()
        }
    }
}

/**
 * Thunk - a lazy function call with its arguments captured.
 * Used to defer execution of functions with unhandled effects.
 *
 * Unlike FunkPartialFunction (which is for partial application),
 * FunkThunk represents a complete function call that's ready to execute
 * but is waiting for effect handlers to be available.
 *
 * Example:
 *   perform Console in demo {}
 *   - demo is a function with <Log, FileSystem> effects
 *   - Console handles Log, but FileSystem is unhandled
 *   - Instead of executing demo{} immediately, we create FunkThunk(demo, {})
 *   - The thunk is wrapped in FunkSuspended with unhandledEffects={FileSystem}
 *   - When FileSystem handler is provided, the thunk executes
 */
@ExportLibrary(InteropLibrary::class)
data class FunkThunk(
    val function: FunkValue,
    val arguments: FunkValue
) : FunkValue() {
    override fun getDisplayString(): String = "<thunk>"

    @ExportMessage
    fun isExecutable(): Boolean = false  // Not directly executable - must be unwrapped first

    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun execute(arguments: Array<Any?>): Any {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }
}

/**
 * Opaque continuation delimiter handle.
 *
 * This is an OPAQUE type that users cannot create, inspect, or manipulate directly.
 * Only __native_continuation_delimit can create these handles.
 * They provide type safety by preventing forgery or mixing of continuations.
 *
 * The handle ID is used to match delimiters with their corresponding suspend/resume operations.
 */
@ExportLibrary(InteropLibrary::class)
data class FunkCont(
    val delimiterId: String  // Opaque delimiter ID
) : FunkValue() {
    override fun getDisplayString(): String = "<cont:$delimiterId>"

    @ExportMessage
    fun isExecutable(): Boolean = false

    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun execute(arguments: Array<Any?>): Any {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }
}

/**
 * Continuation value - represents a captured continuation (the rest of a computation).
 * A continuation is essentially a function that can be called with a value to resume
 * the computation from where it was captured.
 *
 * Used by the Continuation effect for custom control flow (generators, backtracking, async).
 */
@ExportLibrary(InteropLibrary::class)
data class FunkContinuation(
    val resumeFunction: FunkValue,  // The function to call to resume the continuation
    val capturePoint: String  // For debugging - location where continuation was captured
) : FunkValue() {
    override fun getDisplayString(): String = "<continuation@$capturePoint>"

    @ExportMessage
    fun isExecutable(): Boolean = true

    @ExportMessage
    fun execute(arguments: Array<Any?>): Any {
        // Delegate to the resume function (which is a FunkValue function)
        if (resumeFunction is FunkTruffleFunction) {
            return resumeFunction.execute(arguments)
        }
        throw FunkException("Continuation resume function is not executable")
    }
}

/**
 * Suspended continuation value - represents a computation that has been suspended
 * via Continuation.suspend(). This signals to the effect handler that execution
 * should pause here.
 *
 * Different from FunkSuspended (which is for unhandled effects), this is specifically
 * for continuation-based control flow where we explicitly suspend and resume.
 */
@ExportLibrary(InteropLibrary::class)
data class FunkContinuationSuspended(
    val continuation: FunkContinuation,
    val suspendedValue: FunkValue = FunkNull  // Optional value at suspension point
) : FunkValue() {
    override fun getDisplayString(): String = "<suspended-continuation>"

    @ExportMessage
    fun isExecutable(): Boolean = false

    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun execute(arguments: Array<Any?>): Any {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }
}

/**
 * Promise value - represents an async computation result that may not be available yet.
 *
 * Used by the Concurrency effect to spawn computations on virtual threads.
 * Wraps a CompletableFuture that will eventually contain the result.
 */
@ExportLibrary(InteropLibrary::class)
data class FunkPromise(
    val future: java.util.concurrent.CompletableFuture<FunkValue>,
    val promiseId: String = java.util.UUID.randomUUID().toString()
) : FunkValue() {
    override fun getDisplayString(): String {
        return if (future.isDone) {
            try {
                val value = future.get()
                "<promise:resolved($value)>"
            } catch (e: Exception) {
                "<promise:rejected(${e.message})>"
            }
        } else {
            "<promise:pending>"
        }
    }

    @ExportMessage
    fun isExecutable(): Boolean = false

    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun execute(arguments: Array<Any?>): Any {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }

    /**
     * Block until the promise resolves and return the value
     */
    fun await(): FunkValue {
        return try {
            // Use a generous timeout to handle heavy test load
            // Under normal conditions, virtual threads complete quickly
            // But under heavy load (880 tests), scheduler can be slow
            future.get(30, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw FunkException("Promise timed out after 30 seconds - possible deadlock or thread starvation")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw FunkException("Promise rejected: ${e.cause?.message ?: e.message}")
        }
    }

    /**
     * Check if the promise is resolved
     */
    fun isDone(): Boolean = future.isDone

    /**
     * Check if the promise was rejected
     */
    fun isRejected(): Boolean = future.isCompletedExceptionally
}

// OLD INTERPRETER CODE REMOVED:
// - InterpreterReference (legacy wrapper for old interpreter)
//
// No longer needed - all execution goes through Truffle, not the old interpreter.

/**
 * Thread handle value - represents a handle to a Java thread (particularly virtual threads).
 *
 * Used by the Loom effect for low-level thread coordination via park/unpark operations.
 * This provides explicit control over thread scheduling for custom synchronization patterns.
 *
 * CRITICAL: This is an OPAQUE type - users cannot create these directly.
 * They can only obtain thread handles via __native_loom_current_thread().
 */
@ExportLibrary(InteropLibrary::class)
data class FunkThread(
    val thread: Thread,
    val threadId: String = thread.toString()
) : FunkValue() {
    override fun getDisplayString(): String = "<thread:$threadId>"

    @ExportMessage
    fun isExecutable(): Boolean = false

    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun execute(arguments: Array<Any?>): Any {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }
}


