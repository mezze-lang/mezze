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
 * Character value
 */
@ExportLibrary(InteropLibrary::class)
data class FunkChar(val value: Char) : FunkValue() {
    override fun getDisplayString(): String = "'$value'"

    @ExportMessage
    fun isString(): Boolean = true

    @ExportMessage
    fun asString(): String = value.toString()

    @ExportMessage
    fun isNumber(): Boolean = true

    @ExportMessage
    fun fitsInInt(): Boolean = true

    @ExportMessage
    fun asInt(): Int = value.code

    @ExportMessage
    fun fitsInLong(): Boolean = true

    @ExportMessage
    fun asLong(): Long = value.code.toLong()

    @ExportMessage
    fun fitsInByte(): Boolean = value.code in Byte.MIN_VALUE..Byte.MAX_VALUE

    @ExportMessage
    fun asByte(): Byte = value.code.toByte()

    @ExportMessage
    fun fitsInShort(): Boolean = value.code in Short.MIN_VALUE..Short.MAX_VALUE

    @ExportMessage
    fun asShort(): Short = value.code.toShort()

    @ExportMessage
    fun fitsInFloat(): Boolean = true

    @ExportMessage
    fun asFloat(): Float = value.code.toFloat()

    @ExportMessage
    fun fitsInDouble(): Boolean = true

    @ExportMessage
    fun asDouble(): Double = value.code.toDouble()

    @ExportMessage
    fun fitsInBigInteger(): Boolean = true

    @ExportMessage
    fun asBigInteger(): java.math.BigInteger = java.math.BigInteger.valueOf(value.code.toLong())
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
 * Built-in List value (immutable, functional linked list)
 *
 * Implemented as classic cons cells for proper O(1) head/tail/cons operations
 * and structural sharing. This is the standard representation for functional
 * programming languages.
 *
 * User-facing: type List 'a = Cons { head: 'a, tail: List 'a } | Nil
 *
 * Performance characteristics:
 * - cons (prepend): O(1)
 * - head: O(1)
 * - tail: O(1)
 * - length: O(n)
 * - index/get: O(n)
 * - append: O(n) where n = length of first list
 * - Structural sharing: tail of a list can be shared between multiple lists
 */
sealed class FunkList : FunkValue() {
    abstract fun isEmpty(): Boolean
    abstract fun length(): Int
    abstract fun headOrNull(): FunkValue?
    abstract fun tailOrNull(): FunkList?

    /** Convert to Kotlin list (for interop) - O(n) */
    fun toKotlinList(): List<FunkValue> {
        val result = mutableListOf<FunkValue>()
        var current: FunkList = this
        while (current is FunkCons) {
            result.add(current.head)
            current = current.tail
        }
        return result
    }

    companion object {
        /** Create a FunkList from a Kotlin list - O(n) */
        fun fromKotlinList(elements: List<FunkValue>): FunkList {
            var result: FunkList = FunkNil
            for (i in elements.indices.reversed()) {
                result = FunkCons(elements[i], result)
            }
            return result
        }

        /** Create an empty list */
        fun empty(): FunkList = FunkNil

        /** Create a singleton list */
        fun of(element: FunkValue): FunkList = FunkCons(element, FunkNil)
    }
}

/**
 * Empty list (Nil)
 */
@ExportLibrary(InteropLibrary::class)
object FunkNil : FunkList() {
    override fun isEmpty(): Boolean = true
    override fun length(): Int = 0
    override fun headOrNull(): FunkValue? = null
    override fun tailOrNull(): FunkList? = null
    override fun getDisplayString(): String = "Nil"

    @ExportMessage
    fun hasArrayElements(): Boolean = true

    @ExportMessage
    fun getArraySize(): Long = 0

    @ExportMessage
    fun isArrayElementReadable(index: Long): Boolean = false

    @ExportMessage
    fun readArrayElement(index: Long): FunkValue {
        throw FunkException("List index out of bounds: $index (list is empty)")
    }

    @ExportMessage
    fun hasMembers(): Boolean = true

    @ExportMessage
    fun getMembers(includeInternal: Boolean): Any {
        return FunkMemberKeysArray(listOf("length", "isEmpty"))
    }

    @ExportMessage
    fun isMemberReadable(member: String): Boolean {
        return member in setOf("length", "isEmpty")
    }

    @ExportMessage
    fun readMember(member: String): Any {
        return when (member) {
            "length" -> FunkInt(0)
            "isEmpty" -> FunkBool(true)
            else -> throw FunkException("Property '$member' not found in Nil")
        }
    }
}

/**
 * Non-empty list cell (Cons)
 */
@ExportLibrary(InteropLibrary::class)
data class FunkCons(
    val head: FunkValue,
    val tail: FunkList
) : FunkList() {
    override fun isEmpty(): Boolean = false
    override fun headOrNull(): FunkValue = head
    override fun tailOrNull(): FunkList = tail

    // Cached length for performance (computed lazily)
    private var cachedLength: Int = -1

    override fun length(): Int {
        if (cachedLength < 0) {
            cachedLength = 1 + tail.length()
        }
        return cachedLength
    }

    override fun getDisplayString(): String {
        val elements = toKotlinList()
        return "Cons [${elements.joinToString(", ") { it.getDisplayString() }}]"
    }

    @ExportMessage
    fun hasArrayElements(): Boolean = true

    @ExportMessage
    fun getArraySize(): Long = length().toLong()

    @ExportMessage
    fun isArrayElementReadable(index: Long): Boolean = index >= 0 && index < length()

    @ExportMessage
    fun readArrayElement(index: Long): FunkValue {
        if (index < 0) {
            throw FunkException("List index out of bounds: $index")
        }
        var current: FunkList = this
        var i = index
        while (current is FunkCons) {
            if (i == 0L) return current.head
            current = current.tail
            i--
        }
        throw FunkException("List index out of bounds: $index")
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
            "length" -> FunkInt(length().toLong())
            "head" -> head
            "tail" -> tail
            "isEmpty" -> FunkBool(false)
            else -> throw FunkException("Property '$member' not found in Cons")
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
            "tail" -> FunkList.fromKotlinList(elements.drop(1))
            "last" -> elements.last()
            else -> throw FunkException("Property '$member' not found in NonEmptyList")
        }
    }
}

/**
 * Built-in Map value backed by Vavr HashMap for O(1) persistent operations.
 *
 * Vavr HashMap provides:
 * - O(1) get, put, remove (effectively constant via HAMT)
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 *
 * All "mutating" operations return new FunkMap instances with shared structure.
 */
@ExportLibrary(InteropLibrary::class)
data class FunkMap(val entries: io.vavr.collection.HashMap<FunkValue, FunkValue>) : FunkValue() {

    constructor(kotlinMap: Map<FunkValue, FunkValue>) : this(
        io.vavr.collection.HashMap.ofAll(kotlinMap)
    )

    fun size(): Int = entries.size()
    fun isEmpty(): Boolean = entries.isEmpty

    fun get(key: FunkValue): FunkValue? = entries.get(key).orNull

    fun put(key: FunkValue, value: FunkValue): FunkMap =
        FunkMap(entries.put(key, value))

    fun remove(key: FunkValue): FunkMap =
        FunkMap(entries.remove(key))

    fun containsKey(key: FunkValue): Boolean = entries.containsKey(key)

    fun keys(): List<FunkValue> = entries.keySet().toJavaList()

    fun values(): List<FunkValue> = entries.values().toJavaList()

    fun toKotlinMap(): Map<FunkValue, FunkValue> = entries.toJavaMap()

    override fun getDisplayString(): String {
        val entriesStr = entries.toJavaMap().entries.joinToString(", ") { (k, v) ->
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
            "size" -> FunkInt(entries.size().toLong())
            "keys" -> FunkList.fromKotlinList(keys())
            "values" -> FunkList.fromKotlinList(values())
            "isEmpty" -> FunkBool(entries.isEmpty)
            else -> throw FunkException("Property '$member' not found in Map")
        }
    }

    companion object {
        /** Create an empty map */
        fun empty(): FunkMap = FunkMap(io.vavr.collection.HashMap.empty())

        /** Create a map from a single entry */
        fun of(key: FunkValue, value: FunkValue): FunkMap =
            FunkMap(io.vavr.collection.HashMap.of(key, value))
    }
}

/**
 * Built-in Set value backed by Vavr HashSet for O(1) persistent operations.
 *
 * Vavr HashSet provides:
 * - O(1) add, remove, contains (effectively constant via HAMT)
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 *
 * All "mutating" operations return new FunkSet instances with shared structure.
 */
@ExportLibrary(InteropLibrary::class)
data class FunkSet(val elements: io.vavr.collection.HashSet<FunkValue>) : FunkValue() {

    constructor(kotlinSet: Set<FunkValue>) : this(
        io.vavr.collection.HashSet.ofAll(kotlinSet)
    )

    constructor(kotlinList: List<FunkValue>) : this(
        io.vavr.collection.HashSet.ofAll(kotlinList)
    )

    fun size(): Int = elements.size()
    fun isEmpty(): Boolean = elements.isEmpty

    fun contains(element: FunkValue): Boolean = elements.contains(element)

    fun add(element: FunkValue): FunkSet =
        FunkSet(elements.add(element))

    fun remove(element: FunkValue): FunkSet =
        FunkSet(elements.remove(element))

    fun union(other: FunkSet): FunkSet =
        FunkSet(elements.addAll(other.elements))

    fun intersect(other: FunkSet): FunkSet =
        FunkSet(elements.filter { other.elements.contains(it) })

    fun difference(other: FunkSet): FunkSet =
        FunkSet(elements.removeAll(other.elements))

    fun toKotlinSet(): Set<FunkValue> = elements.toJavaSet()

    fun toList(): List<FunkValue> = elements.toJavaList()

    override fun getDisplayString(): String = "{${elements.toJavaList().joinToString(", ") { it.getDisplayString() }}}"

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
            "size" -> FunkInt(elements.size().toLong())
            "isEmpty" -> FunkBool(elements.isEmpty)
            else -> throw FunkException("Property '$member' not found in Set")
        }
    }

    companion object {
        /** Create an empty set */
        fun empty(): FunkSet = FunkSet(io.vavr.collection.HashSet.empty())

        /** Create a singleton set */
        fun of(element: FunkValue): FunkSet =
            FunkSet(io.vavr.collection.HashSet.of(element))
    }
}

/**
 * Funk SortedMap - an immutable sorted map backed by Vavr TreeMap.
 *
 * Keys are maintained in sorted order based on their natural comparison.
 * Uses FunkValueComparator for consistent ordering across all FunkValue types.
 *
 * Performance characteristics:
 * - get, put, remove: O(log n)
 * - min, max: O(log n)
 * - size: O(1)
 */
@ExportLibrary(InteropLibrary::class)
data class FunkSortedMap(val entries: io.vavr.collection.TreeMap<FunkValue, FunkValue>) : FunkValue() {

    fun size(): Int = entries.size()
    fun isEmpty(): Boolean = entries.isEmpty

    fun get(key: FunkValue): FunkValue? = entries.get(key).orNull

    fun put(key: FunkValue, value: FunkValue): FunkSortedMap =
        FunkSortedMap(entries.put(key, value))

    fun remove(key: FunkValue): FunkSortedMap =
        FunkSortedMap(entries.remove(key))

    fun containsKey(key: FunkValue): Boolean = entries.containsKey(key)

    fun keys(): List<FunkValue> = entries.keySet().toJavaList()

    fun values(): List<FunkValue> = entries.values().toJavaList()

    fun minKey(): FunkValue? = if (entries.isEmpty) null else entries.head()._1

    fun maxKey(): FunkValue? = if (entries.isEmpty) null else entries.last()._1

    fun minEntry(): Pair<FunkValue, FunkValue>? =
        if (entries.isEmpty) null else entries.head().let { it._1 to it._2 }

    fun maxEntry(): Pair<FunkValue, FunkValue>? =
        if (entries.isEmpty) null else entries.last().let { it._1 to it._2 }

    fun toKotlinMap(): Map<FunkValue, FunkValue> = entries.toJavaMap()

    override fun getDisplayString(): String {
        val entriesStr = entries.toJavaMap().entries.joinToString(", ") { (k, v) ->
            "${k.getDisplayString()} = ${v.getDisplayString()}"
        }
        return "SortedMap { $entriesStr }"
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
            "size" -> FunkInt(entries.size().toLong())
            "keys" -> FunkList.fromKotlinList(keys())
            "values" -> FunkList.fromKotlinList(values())
            "isEmpty" -> FunkBool(entries.isEmpty)
            else -> throw FunkException("Property '$member' not found in SortedMap")
        }
    }

    companion object {
        private val comparator: Comparator<FunkValue> = FunkValueComparator()

        /** Create an empty sorted map */
        fun empty(): FunkSortedMap = FunkSortedMap(io.vavr.collection.TreeMap.empty(comparator))

        /** Create a sorted map from a single entry */
        fun of(key: FunkValue, value: FunkValue): FunkSortedMap =
            FunkSortedMap(io.vavr.collection.TreeMap.of(comparator, key, value))

        /** Create a sorted map from a Kotlin map */
        fun fromMap(map: Map<FunkValue, FunkValue>): FunkSortedMap {
            var result = empty()
            for ((k, v) in map) {
                result = result.put(k, v)
            }
            return result
        }
    }
}

/**
 * Funk SortedSet - an immutable sorted set backed by Vavr TreeSet.
 *
 * Elements are maintained in sorted order based on their natural comparison.
 * Uses FunkValueComparator for consistent ordering across all FunkValue types.
 *
 * Performance characteristics:
 * - add, remove, contains: O(log n)
 * - min, max: O(log n)
 * - size: O(1)
 */
@ExportLibrary(InteropLibrary::class)
data class FunkSortedSet(val elements: io.vavr.collection.TreeSet<FunkValue>) : FunkValue() {

    fun size(): Int = elements.size()
    fun isEmpty(): Boolean = elements.isEmpty

    fun contains(element: FunkValue): Boolean = elements.contains(element)

    fun add(element: FunkValue): FunkSortedSet =
        FunkSortedSet(elements.add(element))

    fun remove(element: FunkValue): FunkSortedSet =
        FunkSortedSet(elements.remove(element))

    fun union(other: FunkSortedSet): FunkSortedSet =
        FunkSortedSet(elements.addAll(other.elements))

    fun intersect(other: FunkSortedSet): FunkSortedSet =
        FunkSortedSet(elements.filter { other.elements.contains(it) })

    fun difference(other: FunkSortedSet): FunkSortedSet =
        FunkSortedSet(elements.removeAll(other.elements))

    fun min(): FunkValue? = if (elements.isEmpty) null else elements.head()

    fun max(): FunkValue? = if (elements.isEmpty) null else elements.last()

    fun toKotlinSet(): Set<FunkValue> = elements.toJavaSet()

    fun toList(): List<FunkValue> = elements.toJavaList()

    override fun getDisplayString(): String = "SortedSet {${elements.toJavaList().joinToString(", ") { it.getDisplayString() }}}"

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
            "size" -> FunkInt(elements.size().toLong())
            "isEmpty" -> FunkBool(elements.isEmpty)
            else -> throw FunkException("Property '$member' not found in SortedSet")
        }
    }

    companion object {
        private val comparator: Comparator<FunkValue> = FunkValueComparator()

        /** Create an empty sorted set */
        fun empty(): FunkSortedSet = FunkSortedSet(io.vavr.collection.TreeSet.empty(comparator))

        /** Create a singleton sorted set */
        fun of(element: FunkValue): FunkSortedSet =
            FunkSortedSet(io.vavr.collection.TreeSet.of(comparator, element))

        /** Create a sorted set from a list */
        fun fromList(list: List<FunkValue>): FunkSortedSet {
            var result = empty()
            for (e in list) {
                result = result.add(e)
            }
            return result
        }
    }
}

/**
 * Comparator for FunkValue types to enable sorted collections.
 * Provides a consistent total ordering across all value types.
 */
class FunkValueComparator : Comparator<FunkValue> {
    override fun compare(a: FunkValue, b: FunkValue): Int {
        // First compare by type
        val typeOrder = getTypeOrder(a) - getTypeOrder(b)
        if (typeOrder != 0) return typeOrder

        // Then compare by value within the same type
        return when (a) {
            is FunkInt -> (a.value).compareTo((b as FunkInt).value)
            is FunkFloat -> (a.value).compareTo((b as FunkFloat).value)
            is FunkString -> (a.value).compareTo((b as FunkString).value)
            is FunkChar -> (a.value).compareTo((b as FunkChar).value)
            is FunkBool -> (a.value).compareTo((b as FunkBool).value)
            is FunkNat -> (a.value).compareTo((b as FunkNat).value)
            is FunkFrac -> {
                val bFrac = b as FunkFrac
                // Compare fractions: a/b vs c/d => ad vs bc
                val left = a.numerator * bFrac.denominator
                val right = bFrac.numerator * a.denominator
                left.compareTo(right)
            }
            is FunkRecord -> {
                // Compare records by their string representation
                a.getDisplayString().compareTo((b as FunkRecord).getDisplayString())
            }
            is FunkVariant -> {
                val bVariant = b as FunkVariant
                val constructorCmp = a.constructor.compareTo(bVariant.constructor)
                if (constructorCmp != 0) constructorCmp
                else {
                    // Compare values if both have them
                    when {
                        a.value == null && bVariant.value == null -> 0
                        a.value == null -> -1
                        bVariant.value == null -> 1
                        else -> compare(a.value, bVariant.value)
                    }
                }
            }
            else -> a.getDisplayString().compareTo(b.getDisplayString())
        }
    }

    private fun getTypeOrder(value: FunkValue): Int = when (value) {
        is FunkNull -> 0
        is FunkBool -> 1
        is FunkInt -> 2
        is FunkNat -> 3
        is FunkFloat -> 4
        is FunkFrac -> 5
        is FunkChar -> 6
        is FunkString -> 7
        is FunkRecord -> 8
        is FunkVariant -> 9
        is FunkList, is FunkCons -> 10
        is FunkArrayList -> 11
        is FunkMap -> 12
        is FunkSet -> 13
        is FunkSortedMap -> 14
        is FunkSortedSet -> 15
        else -> 99
    }
}

/**
 * Funk Deque - an immutable double-ended queue.
 *
 * Implemented using two lists (front and back) for amortized O(1) operations
 * on both ends. This is the classic Banker's Queue / Okasaki-style deque.
 *
 * - front: Elements at the front (head is first to dequeue from front)
 * - back: Elements at the back (head is first to dequeue from back, stored in reverse)
 *
 * When one side becomes empty, we rebalance by reversing the other side.
 */
@ExportLibrary(InteropLibrary::class)
data class FunkDeque(
    val front: List<FunkValue>,
    val back: List<FunkValue>
) : FunkValue() {

    fun size(): Int = front.size + back.size
    fun isEmpty(): Boolean = front.isEmpty() && back.isEmpty()

    /**
     * Rebalance for front operations - move back to front if front is empty.
     */
    private fun rebalanceForFront(): FunkDeque {
        return if (front.isEmpty() && back.isNotEmpty()) {
            FunkDeque(back.reversed(), emptyList())
        } else {
            this
        }
    }

    /**
     * Rebalance for back operations - move front to back if back is empty.
     */
    private fun rebalanceForBack(): FunkDeque {
        return if (back.isEmpty() && front.isNotEmpty()) {
            FunkDeque(emptyList(), front.reversed())
        } else {
            this
        }
    }

    fun pushFront(x: FunkValue): FunkDeque =
        FunkDeque(listOf(x) + front, back)

    fun pushBack(x: FunkValue): FunkDeque =
        FunkDeque(front, listOf(x) + back)

    /**
     * Remove and return the front element.
     * Returns null if the deque is empty.
     */
    fun popFront(): Pair<FunkValue, FunkDeque>? {
        val balanced = rebalanceForFront()
        return if (balanced.front.isEmpty()) null
        else balanced.front.first() to FunkDeque(balanced.front.drop(1), balanced.back)
    }

    /**
     * Remove and return the back element.
     * Returns null if the deque is empty.
     */
    fun popBack(): Pair<FunkDeque, FunkValue>? {
        val balanced = rebalanceForBack()
        return if (balanced.back.isEmpty()) null
        else FunkDeque(balanced.front, balanced.back.drop(1)) to balanced.back.first()
    }

    fun peekFront(): FunkValue? {
        // If front is non-empty, return its first element
        // Otherwise, if back is non-empty, return its last element (which is the front of deque)
        return if (front.isNotEmpty()) {
            front.first()
        } else if (back.isNotEmpty()) {
            back.last()
        } else {
            null
        }
    }

    fun peekBack(): FunkValue? {
        // If back is non-empty, return its first element (back is stored in reverse)
        // Otherwise, if front is non-empty, return its last element
        return if (back.isNotEmpty()) {
            back.first()
        } else if (front.isNotEmpty()) {
            front.last()
        } else {
            null
        }
    }

    fun toList(): List<FunkValue> = front + back.reversed()

    override fun getDisplayString(): String {
        val elements = toList()
        return "Deque(${elements.joinToString(", ") { it.getDisplayString() }})"
    }

    @ExportMessage
    fun hasMembers(): Boolean = true

    @ExportMessage
    fun getMembers(includeInternal: Boolean): Any {
        return FunkMemberKeysArray(listOf("length", "isEmpty"))
    }

    @ExportMessage
    fun isMemberReadable(member: String): Boolean {
        return member in setOf("length", "isEmpty")
    }

    @ExportMessage
    fun readMember(member: String): Any {
        return when (member) {
            "length" -> FunkInt(size().toLong())
            "isEmpty" -> FunkBool(isEmpty())
            else -> throw FunkException("Property '$member' not found in Deque")
        }
    }
}

/**
 * Bytes - a primitive type for binary data.
 *
 * Provides compact storage and efficient operations for working with
 * raw byte sequences, essential for IO, networking, and binary protocols.
 *
 * Backed by ByteArray for O(1) indexing. All operations are immutable.
 */
@ExportLibrary(InteropLibrary::class)
data class FunkBytes(
    val bytes: ByteArray
) : FunkValue() {

    companion object {
        fun empty(): FunkBytes = FunkBytes(ByteArray(0))

        fun fromString(s: String): FunkBytes = FunkBytes(s.toByteArray(Charsets.UTF_8))

        fun fromArray(arr: List<Int>): FunkBytes {
            val bytes = ByteArray(arr.size)
            for (i in arr.indices) {
                bytes[i] = arr[i].toByte()
            }
            return FunkBytes(bytes)
        }

        fun fromHex(hex: String): FunkBytes {
            require(hex.length % 2 == 0) { "Hex string must have even length" }
            val bytes = ByteArray(hex.length / 2)
            for (i in bytes.indices) {
                bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return FunkBytes(bytes)
        }

        fun fromBase64(encoded: String): FunkBytes {
            val bytes = java.util.Base64.getDecoder().decode(encoded)
            return FunkBytes(bytes)
        }
    }

    fun length(): Int = bytes.size
    fun isEmpty(): Boolean = bytes.isEmpty()

    fun get(index: Int): Int? {
        return if (index >= 0 && index < bytes.size) {
            bytes[index].toInt() and 0xFF  // Unsigned byte value 0-255
        } else null
    }

    fun slice(start: Int, end: Int): FunkBytes {
        val actualEnd = minOf(end, bytes.size)
        val actualStart = maxOf(start, 0)
        if (actualStart >= actualEnd) return empty()
        return FunkBytes(bytes.copyOfRange(actualStart, actualEnd))
    }

    fun take(n: Int): FunkBytes = slice(0, n)

    fun drop(n: Int): FunkBytes = slice(n, bytes.size)

    fun concat(other: FunkBytes): FunkBytes {
        val result = ByteArray(bytes.size + other.bytes.size)
        System.arraycopy(bytes, 0, result, 0, bytes.size)
        System.arraycopy(other.bytes, 0, result, bytes.size, other.bytes.size)
        return FunkBytes(result)
    }

    fun reverse(): FunkBytes {
        val reversed = ByteArray(bytes.size)
        for (i in bytes.indices) {
            reversed[bytes.size - 1 - i] = bytes[i]
        }
        return FunkBytes(reversed)
    }

    fun repeat(n: Int): FunkBytes {
        if (n <= 0) return empty()
        val result = ByteArray(bytes.size * n)
        for (i in 0 until n) {
            System.arraycopy(bytes, 0, result, i * bytes.size, bytes.size)
        }
        return FunkBytes(result)
    }

    fun indexOf(pattern: FunkBytes): Int {
        if (pattern.bytes.isEmpty()) return 0
        if (pattern.bytes.size > bytes.size) return -1

        outer@ for (i in 0..(bytes.size - pattern.bytes.size)) {
            for (j in pattern.bytes.indices) {
                if (bytes[i + j] != pattern.bytes[j]) continue@outer
            }
            return i
        }
        return -1
    }

    fun contains(pattern: FunkBytes): Boolean = indexOf(pattern) >= 0

    fun startsWith(prefix: FunkBytes): Boolean {
        if (prefix.bytes.size > bytes.size) return false
        for (i in prefix.bytes.indices) {
            if (bytes[i] != prefix.bytes[i]) return false
        }
        return true
    }

    fun endsWith(suffix: FunkBytes): Boolean {
        if (suffix.bytes.size > bytes.size) return false
        val offset = bytes.size - suffix.bytes.size
        for (i in suffix.bytes.indices) {
            if (bytes[offset + i] != suffix.bytes[i]) return false
        }
        return true
    }

    fun toStringValue(): String = String(bytes, Charsets.UTF_8)

    fun toArray(): List<Int> = bytes.map { it.toInt() and 0xFF }

    fun toHex(): String = bytes.joinToString("") { "%02x".format(it) }

    fun toBase64(): String = java.util.Base64.getEncoder().encodeToString(bytes)

    // Truffle interop - expose as array-like for iteration
    @ExportMessage
    fun hasArrayElements(): Boolean = true

    @ExportMessage
    fun getArraySize(): Long = bytes.size.toLong()

    @ExportMessage
    fun isArrayElementReadable(index: Long): Boolean = index >= 0 && index < bytes.size.toLong()

    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.InvalidArrayIndexException::class)
    fun readArrayElement(index: Long): Any {
        if (index < 0 || index >= bytes.size.toLong()) {
            throw com.oracle.truffle.api.interop.InvalidArrayIndexException.create(index)
        }
        return FunkInt((bytes[index.toInt()].toInt() and 0xFF).toLong())
    }

    override fun getDisplayString(): String {
        val preview = if (bytes.size <= 20) {
            bytes.joinToString("") {
                val c = it.toInt() and 0xFF
                if (c in 32..126) c.toChar().toString() else "\\x%02x".format(c)
            }
        } else {
            bytes.take(20).joinToString("") {
                val c = it.toInt() and 0xFF
                if (c in 32..126) c.toChar().toString() else "\\x%02x".format(c)
            } + "..."
        }
        return "b\"$preview\""
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FunkBytes) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * ByteBuffer - mutable byte buffer for efficient streaming/building.
 *
 * Backed by ByteArrayOutputStream for O(1) amortized appends.
 * Presents a functional API but mutates internally for efficiency.
 *
 * Use cases:
 * - Streaming JSON/CSV serialization
 * - Building large byte sequences incrementally
 * - Avoiding 2x memory peak of collect-then-concat
 *
 * Note: This is intentionally mutable under the hood. The functional API
 * (returning the buffer from each operation) is for chaining convenience,
 * but the same buffer instance is mutated. For true immutability, use Bytes.
 */
@ExportLibrary(InteropLibrary::class)
class FunkByteBuffer(
    val stream: java.io.ByteArrayOutputStream
) : FunkValue() {

    companion object {
        fun withCapacity(capacity: Int): FunkByteBuffer =
            FunkByteBuffer(java.io.ByteArrayOutputStream(capacity))

        fun empty(): FunkByteBuffer = withCapacity(256)
    }

    /** O(1) amortized - appends bytes to internal buffer */
    fun write(bytes: FunkBytes): FunkByteBuffer {
        stream.write(bytes.bytes)
        return this
    }

    /** O(1) - appends single byte (0-255) */
    fun writeByte(b: Int): FunkByteBuffer {
        stream.write(b and 0xFF)
        return this
    }

    /** O(n) where n = str.length - appends UTF-8 encoded string */
    fun writeString(s: String): FunkByteBuffer {
        stream.write(s.toByteArray(Charsets.UTF_8))
        return this
    }

    /** O(n) - copies buffer contents to new immutable Bytes */
    fun toBytes(): FunkBytes = FunkBytes(stream.toByteArray())

    /** O(1) - current size in bytes */
    fun size(): Int = stream.size()

    /** O(1) - resets buffer to empty (reuses internal array) */
    fun clear(): FunkByteBuffer {
        stream.reset()
        return this
    }

    override fun getDisplayString(): String = "<ByteBuffer size=${stream.size()}>"

    // Truffle interop - expose size as readable property
    @ExportMessage
    fun hasMembers(): Boolean = true

    @ExportMessage
    fun getMembers(includeInternal: Boolean): Any =
        FunkArrayList(listOf(FunkString("size")))

    @ExportMessage
    fun isMemberReadable(member: String): Boolean = member == "size"

    @ExportMessage
    fun readMember(member: String): Any = when (member) {
        "size" -> FunkInt(stream.size().toLong())
        else -> throw FunkException("Unknown member: $member")
    }

    // Note: equals/hashCode based on identity since this is mutable
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
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


