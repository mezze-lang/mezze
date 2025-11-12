package com.funk.language.interpreter

import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.TruffleObject
import com.oracle.truffle.api.library.ExportLibrary
import com.oracle.truffle.api.library.ExportMessage
import java.util.ArrayList

/**
 * Funk ArrayList value that wraps Java ArrayList for performance
 */
@ExportLibrary(InteropLibrary::class)
class FunkArrayList(
    val elements: ArrayList<FunkValue> = ArrayList()
) : FunkValue() {
    
    constructor(initialElements: List<FunkValue>) : this(ArrayList(initialElements))
    
    // ArrayList operations
    fun size(): Int = elements.size
    fun isEmpty(): Boolean = elements.isEmpty()
    fun get(index: Int): FunkValue = elements[index]
    fun add(element: FunkValue) { elements.add(element) }
    fun set(index: Int, element: FunkValue): FunkValue = elements.set(index, element)
    fun removeAt(index: Int): FunkValue = elements.removeAt(index)
    fun clear() { elements.clear() }
    
    // Functional operations
    fun map(transform: (FunkValue) -> FunkValue): FunkArrayList {
        return FunkArrayList(elements.map(transform))
    }
    
    fun filter(predicate: (FunkValue) -> Boolean): FunkArrayList {
        return FunkArrayList(elements.filter(predicate))
    }
    
    fun fold(initial: FunkValue, operation: (FunkValue, FunkValue) -> FunkValue): FunkValue {
        return elements.fold(initial, operation)
    }
    
    // Interop support
    @ExportMessage
    fun hasArrayElements(): Boolean = true
    
    @ExportMessage
    fun getArraySize(): Long = elements.size.toLong()
    
    @ExportMessage
    fun isArrayElementReadable(index: Long): Boolean = index >= 0 && index < elements.size
    
    @ExportMessage
    fun readArrayElement(index: Long): FunkValue = elements[index.toInt()]
    
    @ExportMessage
    fun isArrayElementModifiable(index: Long): Boolean = index >= 0 && index < elements.size
    
    @ExportMessage
    fun writeArrayElement(index: Long, value: Any): Unit {
        elements[index.toInt()] = value as FunkValue
    }
    
    @ExportMessage
    fun isArrayElementInsertable(index: Long): Boolean = index == elements.size.toLong()
    
    override fun getDisplayString(): String = "[${elements.joinToString(", ") { it.getDisplayString() }}]"
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FunkArrayList) return false
        return elements == other.elements
    }
    
    override fun hashCode(): Int = elements.hashCode()
}