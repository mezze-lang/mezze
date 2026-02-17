package com.mezze.language.interpreter

import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage

/**
 * Mezze Array Slice - a view into an existing MezzeArrayList.
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
class MezzeArraySlice(
    private val backing: MezzeArrayList,
    private val startIndex: Int,
    private val endIndex: Int  // exclusive
) : MezzeValue() {

    init {
        require(startIndex >= 0) { "startIndex must be non-negative" }
        require(endIndex >= startIndex) { "endIndex must be >= startIndex" }
        require(endIndex <= backing.elements.size()) { "endIndex must be <= backing size" }
    }

    companion object {
        /**
         * Create a slice from an array starting at the given index to the end.
         */
        fun fromIndex(array: MezzeArrayList, startIndex: Int): MezzeArraySlice {
            return MezzeArraySlice(array, startIndex, array.elements.size())
        }

        /**
         * Create a slice from an array with specific bounds.
         */
        fun fromBounds(array: MezzeArrayList, startIndex: Int, endIndex: Int): MezzeArraySlice {
            return MezzeArraySlice(array, startIndex, endIndex)
        }

        /**
         * Create a MezzeArraySlice or MezzeArrayList from any array-like value.
         * If the value is already a slice, creates a sub-slice.
         * If the value is an ArrayList, creates a slice into it.
         */
        fun sliceFrom(value: MezzeValue, startIndex: Int): MezzeValue {
            return when (value) {
                is MezzeArraySlice -> value.slice(startIndex)
                is MezzeArrayList -> fromIndex(value, startIndex)
                else -> throw RuntimeException("Cannot slice non-array value: ${value::class.simpleName}")
            }
        }
    }

    // Size of the slice
    fun size(): Int = endIndex - startIndex
    fun isEmpty(): Boolean = size() == 0

    // Get element at index (relative to slice start)
    fun get(index: Int): MezzeValue {
        if (index < 0 || index >= size()) {
            throw IndexOutOfBoundsException("Index $index out of bounds for slice of size ${size()}")
        }
        return backing.elements.get(startIndex + index)
    }

    /**
     * Create a sub-slice starting from the given index within this slice.
     * This is O(1) - just adjusts the offset.
     */
    fun slice(fromIndex: Int): MezzeArraySlice {
        if (fromIndex < 0 || fromIndex > size()) {
            throw IndexOutOfBoundsException("Cannot slice from index $fromIndex in slice of size ${size()}")
        }
        return MezzeArraySlice(backing, startIndex + fromIndex, endIndex)
    }

    /**
     * Create a sub-slice with specific bounds within this slice.
     * This is O(1) - just adjusts the offsets.
     */
    fun slice(fromIndex: Int, toIndex: Int): MezzeArraySlice {
        if (fromIndex < 0 || toIndex > size() || fromIndex > toIndex) {
            throw IndexOutOfBoundsException("Invalid slice bounds [$fromIndex, $toIndex) for slice of size ${size()}")
        }
        return MezzeArraySlice(backing, startIndex + fromIndex, startIndex + toIndex)
    }

    /**
     * Materialize this slice into a new MezzeArrayList.
     * This is O(k) where k is the slice size.
     * Use this when you need to modify the array or pass it to code that expects MezzeArrayList.
     */
    fun toArrayList(): MezzeArrayList {
        val elements = ArrayList<MezzeValue>(size())
        for (i in startIndex until endIndex) {
            elements.add(backing.elements.get(i))
        }
        return MezzeArrayList(elements)
    }

    /**
     * Get elements as a list (copies the data).
     */
    fun toList(): List<MezzeValue> {
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
    fun readArrayElement(index: Long): MezzeValue = get(index.toInt())

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
            is MezzeArraySlice -> {
                if (size() != other.size()) return false
                for (i in 0 until size()) {
                    if (get(i) != other.get(i)) return false
                }
                true
            }
            is MezzeArrayList -> {
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
