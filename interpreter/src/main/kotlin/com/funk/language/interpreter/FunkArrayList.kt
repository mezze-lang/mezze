package com.funk.language.interpreter

import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import io.vavr.collection.Vector

/**
 * Funk ArrayList value backed by Vavr Vector for O(1) persistent operations.
 *
 * Vavr Vector provides:
 * - O(1) get, update, append, prepend (effectively constant time via bit-mapped tries)
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 *
 * All "mutating" operations return new FunkArrayList instances with shared structure.
 */
@ExportLibrary(InteropLibrary::class)
class FunkArrayList(
    val elements: Vector<FunkValue> = Vector.empty()
) : FunkValue() {

    constructor(initialElements: List<FunkValue>) : this(Vector.ofAll(initialElements))
    constructor(initialElements: Iterable<FunkValue>) : this(Vector.ofAll(initialElements))

    // ArrayList operations - all return new instances (immutable)
    fun size(): Int = elements.size()
    fun isEmpty(): Boolean = elements.isEmpty
    fun get(index: Int): FunkValue = elements.get(index)

    /**
     * Add element to the end - O(1) effective constant time
     * Returns a new FunkArrayList with the element appended
     */
    fun add(element: FunkValue): FunkArrayList = FunkArrayList(elements.append(element))

    /**
     * Set element at index - O(1) effective constant time
     * Returns a new FunkArrayList with the element updated
     */
    fun set(index: Int, element: FunkValue): FunkArrayList = FunkArrayList(elements.update(index, element))

    /**
     * Remove element at index - O(1) effective constant time
     * Returns a new FunkArrayList without the element
     */
    fun removeAt(index: Int): FunkArrayList = FunkArrayList(elements.removeAt(index))

    /**
     * Prepend element to the beginning - O(1) effective constant time
     * Returns a new FunkArrayList with the element prepended
     */
    fun prepend(element: FunkValue): FunkArrayList = FunkArrayList(elements.prepend(element))

    /**
     * Concatenate with another array - O(n) where n is size of other
     * Returns a new FunkArrayList with all elements from both
     */
    fun concat(other: FunkArrayList): FunkArrayList = FunkArrayList(elements.appendAll(other.elements))

    /**
     * Slice from start (inclusive) to end (exclusive)
     * Returns a new FunkArrayList with the specified range
     */
    fun slice(start: Int, end: Int): FunkArrayList = FunkArrayList(elements.slice(start, end))

    /**
     * Reverse the array
     * Returns a new FunkArrayList with elements in reverse order
     */
    fun reverse(): FunkArrayList = FunkArrayList(elements.reverse())

    // Functional operations
    fun map(transform: (FunkValue) -> FunkValue): FunkArrayList {
        return FunkArrayList(elements.map(transform))
    }

    fun filter(predicate: (FunkValue) -> Boolean): FunkArrayList {
        return FunkArrayList(elements.filter(predicate))
    }

    fun fold(initial: FunkValue, operation: (FunkValue, FunkValue) -> FunkValue): FunkValue {
        return elements.foldLeft(initial, operation)
    }

    fun find(predicate: (FunkValue) -> Boolean): FunkValue? {
        return elements.find(predicate).orNull
    }

    fun contains(element: FunkValue): Boolean = elements.contains(element)

    fun indexOf(element: FunkValue): Int = elements.indexOf(element)

    // Convert to Kotlin list for interop
    fun toList(): List<FunkValue> = elements.toJavaList()

    // Interop support
    @ExportMessage
    fun hasArrayElements(): Boolean = true

    @ExportMessage
    fun getArraySize(): Long = elements.size().toLong()

    @ExportMessage
    fun isArrayElementReadable(index: Long): Boolean = index >= 0 && index < elements.size()

    @ExportMessage
    fun readArrayElement(index: Long): FunkValue = elements.get(index.toInt())

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
        if (other !is FunkArrayList) return false
        return elements == other.elements
    }

    override fun hashCode(): Int = elements.hashCode()

    companion object {
        /** Create an empty array */
        fun empty(): FunkArrayList = FunkArrayList()

        /** Create a singleton array */
        fun of(element: FunkValue): FunkArrayList = FunkArrayList(Vector.of(element))

        /** Create an array from varargs */
        fun of(vararg elements: FunkValue): FunkArrayList = FunkArrayList(Vector.of(*elements))
    }
}
