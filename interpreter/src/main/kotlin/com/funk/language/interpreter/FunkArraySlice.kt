package com.funk.language.interpreter

import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage

/**
 * Funk Array Slice - a view into an existing FunkArrayList.
 *
 * This provides O(1) slicing operations without copying the underlying data.
 * Used primarily for array pattern matching where we need to extract the "tail"
 * of an array without copying all elements.
 *
 * The slice is immutable and shares the underlying array data with the original.
 *
 * Example:
 *   match [1, 2, 3, 4, 5] is
 *       [head, ...tail] -> tail  // tail is a slice pointing to elements 2-5
 */
@ExportLibrary(InteropLibrary::class)
class FunkArraySlice(
    private val backing: FunkArrayList,
    private val startIndex: Int,
    private val endIndex: Int  // exclusive
) : FunkValue() {

    init {
        require(startIndex >= 0) { "startIndex must be non-negative" }
        require(endIndex >= startIndex) { "endIndex must be >= startIndex" }
        require(endIndex <= backing.elements.size()) { "endIndex must be <= backing size" }
    }

    companion object {
        /**
         * Create a slice from an array starting at the given index to the end.
         */
        fun fromIndex(array: FunkArrayList, startIndex: Int): FunkArraySlice {
            return FunkArraySlice(array, startIndex, array.elements.size())
        }

        /**
         * Create a slice from an array with specific bounds.
         */
        fun fromBounds(array: FunkArrayList, startIndex: Int, endIndex: Int): FunkArraySlice {
            return FunkArraySlice(array, startIndex, endIndex)
        }

        /**
         * Create a FunkArraySlice or FunkArrayList from any array-like value.
         * If the value is already a slice, creates a sub-slice.
         * If the value is an ArrayList, creates a slice into it.
         */
        fun sliceFrom(value: FunkValue, startIndex: Int): FunkValue {
            return when (value) {
                is FunkArraySlice -> value.slice(startIndex)
                is FunkArrayList -> fromIndex(value, startIndex)
                else -> throw RuntimeException("Cannot slice non-array value: ${value::class.simpleName}")
            }
        }
    }

    // Size of the slice
    fun size(): Int = endIndex - startIndex
    fun isEmpty(): Boolean = size() == 0

    // Get element at index (relative to slice start)
    fun get(index: Int): FunkValue {
        if (index < 0 || index >= size()) {
            throw IndexOutOfBoundsException("Index $index out of bounds for slice of size ${size()}")
        }
        return backing.elements.get(startIndex + index)
    }

    /**
     * Create a sub-slice starting from the given index within this slice.
     * This is O(1) - just adjusts the offset.
     */
    fun slice(fromIndex: Int): FunkArraySlice {
        if (fromIndex < 0 || fromIndex > size()) {
            throw IndexOutOfBoundsException("Cannot slice from index $fromIndex in slice of size ${size()}")
        }
        return FunkArraySlice(backing, startIndex + fromIndex, endIndex)
    }

    /**
     * Create a sub-slice with specific bounds within this slice.
     * This is O(1) - just adjusts the offsets.
     */
    fun slice(fromIndex: Int, toIndex: Int): FunkArraySlice {
        if (fromIndex < 0 || toIndex > size() || fromIndex > toIndex) {
            throw IndexOutOfBoundsException("Invalid slice bounds [$fromIndex, $toIndex) for slice of size ${size()}")
        }
        return FunkArraySlice(backing, startIndex + fromIndex, startIndex + toIndex)
    }

    /**
     * Materialize this slice into a new FunkArrayList.
     * This is O(k) where k is the slice size.
     * Use this when you need to modify the array or pass it to code that expects FunkArrayList.
     */
    fun toArrayList(): FunkArrayList {
        val elements = ArrayList<FunkValue>(size())
        for (i in startIndex until endIndex) {
            elements.add(backing.elements.get(i))
        }
        return FunkArrayList(elements)
    }

    /**
     * Get elements as a list (copies the data).
     */
    fun toList(): List<FunkValue> {
        return (startIndex until endIndex).map { backing.elements.get(it) }
    }

    // Interop support - expose as array-like object
    @ExportMessage
    fun hasArrayElements(): Boolean = true

    @ExportMessage
    fun getArraySize(): Long = size().toLong()

    @ExportMessage
    fun isArrayElementReadable(index: Long): Boolean = index >= 0 && index < size()

    @ExportMessage
    fun readArrayElement(index: Long): FunkValue = get(index.toInt())

    @ExportMessage
    fun isArrayElementModifiable(index: Long): Boolean = false  // Slices are read-only views

    @ExportMessage
    fun isArrayElementInsertable(index: Long): Boolean = false  // Slices are read-only views

    @ExportMessage
    @Throws(com.oracle.truffle.api.interop.UnsupportedMessageException::class)
    fun writeArrayElement(index: Long, value: Any?) {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create()
    }

    override fun getDisplayString(): String {
        val elementsStr = (startIndex until endIndex)
            .take(10)
            .map { backing.elements.get(it).getDisplayString() }
            .joinToString(", ")
        val suffix = if (size() > 10) ", ..." else ""
        return "[$elementsStr$suffix]"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return when (other) {
            is FunkArraySlice -> {
                if (size() != other.size()) return false
                for (i in 0 until size()) {
                    if (get(i) != other.get(i)) return false
                }
                true
            }
            is FunkArrayList -> {
                if (size() != other.elements.size()) return false
                for (i in 0 until size()) {
                    if (get(i) != other.elements.get(i)) return false
                }
                true
            }
            else -> false
        }
    }

    override fun hashCode(): Int {
        var result = 1
        for (i in startIndex until endIndex) {
            result = 31 * result + backing.elements.get(i).hashCode()
        }
        return result
    }
}
