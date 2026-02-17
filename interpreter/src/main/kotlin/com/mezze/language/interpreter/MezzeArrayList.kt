package com.mezze.language.interpreter

import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import io.vavr.collection.Vector

/**
 * Mezze ArrayList value backed by Vavr Vector for O(1) persistent operations.
 *
 * Vavr Vector provides:
 * - O(1) get, update, append, prepend (effectively constant time via bit-mapped tries)
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 *
 * All "mutating" operations return new MezzeArrayList instances with shared structure.
 */
@ExportLibrary(InteropLibrary::class)
class MezzeArrayList(
    val elements: Vector<MezzeValue> = Vector.empty()
) : MezzeValue() {

    constructor(initialElements: List<MezzeValue>) : this(Vector.ofAll(initialElements))
    constructor(initialElements: Iterable<MezzeValue>) : this(Vector.ofAll(initialElements))

    // ArrayList operations - all return new instances (immutable)
    fun size(): Int = elements.size()
    fun isEmpty(): Boolean = elements.isEmpty
    fun get(index: Int): MezzeValue = elements.get(index)

    /**
     * Add element to the end - O(1) effective constant time
     * Returns a new MezzeArrayList with the element appended
     */
    fun add(element: MezzeValue): MezzeArrayList = MezzeArrayList(elements.append(element))

    /**
     * Set element at index - O(1) effective constant time
     * Returns a new MezzeArrayList with the element updated
     */
    fun set(index: Int, element: MezzeValue): MezzeArrayList = MezzeArrayList(elements.update(index, element))

    /**
     * Remove element at index - O(1) effective constant time
     * Returns a new MezzeArrayList without the element
     */
    fun removeAt(index: Int): MezzeArrayList = MezzeArrayList(elements.removeAt(index))

    /**
     * Prepend element to the beginning - O(1) effective constant time
     * Returns a new MezzeArrayList with the element prepended
     */
    fun prepend(element: MezzeValue): MezzeArrayList = MezzeArrayList(elements.prepend(element))

    /**
     * Concatenate with another array - O(n) where n is size of other
     * Returns a new MezzeArrayList with all elements from both
     */
    fun concat(other: MezzeArrayList): MezzeArrayList = MezzeArrayList(elements.appendAll(other.elements))

    /**
     * Slice from start (inclusive) to end (exclusive)
     * Returns a new MezzeArrayList with the specified range
     */
    fun slice(start: Int, end: Int): MezzeArrayList = MezzeArrayList(elements.slice(start, end))

    /**
     * Reverse the array
     * Returns a new MezzeArrayList with elements in reverse order
     */
    fun reverse(): MezzeArrayList = MezzeArrayList(elements.reverse())

    // Functional operations
    fun map(transform: (MezzeValue) -> MezzeValue): MezzeArrayList {
        return MezzeArrayList(elements.map(transform))
    }

    fun filter(predicate: (MezzeValue) -> Boolean): MezzeArrayList {
        return MezzeArrayList(elements.filter(predicate))
    }

    fun fold(initial: MezzeValue, operation: (MezzeValue, MezzeValue) -> MezzeValue): MezzeValue {
        return elements.foldLeft(initial, operation)
    }

    fun find(predicate: (MezzeValue) -> Boolean): MezzeValue? {
        return elements.find(predicate).orNull
    }

    fun contains(element: MezzeValue): Boolean = elements.contains(element)

    fun indexOf(element: MezzeValue): Int = elements.indexOf(element)

    // Convert to Kotlin list for interop
    fun toList(): List<MezzeValue> = elements.toJavaList()

    // Interop support
    @ExportMessage
    fun hasArrayElements(): Boolean = true

    @ExportMessage
    fun getArraySize(): Long = elements.size().toLong()

    @ExportMessage
    fun isArrayElementReadable(index: Long): Boolean = index >= 0 && index < elements.size()

    @ExportMessage
    fun readArrayElement(index: Long): MezzeValue = elements.get(index.toInt())

    @ExportMessage
    fun isArrayElementModifiable(index: Long): Boolean = false  // Immutable - modifications return new arrays

    @ExportMessage
    fun writeArrayElement(index: Long, value: Any): Unit {
        // Vavr Vector is immutable - this operation is not supported at runtime
        // Modifications should use set() which returns a new array
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }

    @ExportMessage
    fun isArrayElementInsertable(index: Long): Boolean = false  // Immutable

    override fun getDisplayString(): String = "[${elements.toJavaList().joinToString(", ") { it.getDisplayString() }}]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MezzeArrayList) return false
        return elements == other.elements
    }

    override fun hashCode(): Int = elements.hashCode()

    companion object {
        /** Create an empty array */
        fun empty(): MezzeArrayList = MezzeArrayList()

        /** Create a singleton array */
        fun of(element: MezzeValue): MezzeArrayList = MezzeArrayList(Vector.of(element))

        /** Create an array from varargs */
        fun of(vararg elements: MezzeValue): MezzeArrayList = MezzeArrayList(Vector.of(*elements))
    }
}
