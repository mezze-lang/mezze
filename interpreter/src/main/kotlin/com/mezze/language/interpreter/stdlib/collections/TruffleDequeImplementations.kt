package com.mezze.language.interpreter.stdlib.collections

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for Deque operations.
 *
 * Deque (double-ended queue) is an immutable collection that supports
 * efficient O(1) amortized operations on both ends.
 *
 * Implementation uses two lists (front and back) for the Banker's Queue pattern:
 * - front: Elements at the front (head is first to dequeue)
 * - back: Elements at the back (stored in reverse order)
 *
 * When one side becomes empty, the other is reversed to maintain balance.
 */
object TruffleDequeImplementations {

    /**
     * Helper to call any function type (Truffle or Native)
     */
    private fun callFunction(f: MezzeValue, arg: MezzeValue, operationName: String): MezzeValue {
        return when (f) {
            is MezzeTruffleFunction -> {
                f.callTarget.call(
                    arg,
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as MezzeValue
            }
            is NativeMezzeFunction -> {
                f.implementation(listOf(arg))
            }
            else -> {
                throw NativeFunctionException(operationName,
                    "Expected Truffle function or native function, got: ${f::class.simpleName}")
            }
        }
    }

    fun registerAll(context: MutableMap<String, MezzeValue>) {

        // ========================================
        // Construction
        // ========================================

        // Create empty deque
        context["__native_deque_empty"] = NativeMezzeFunction("__native_deque_empty") { args ->
            MezzeDeque(emptyList(), emptyList())
        }

        // Create singleton deque
        context["__native_deque_singleton"] = NativeMezzeFunction("__native_deque_singleton") { args ->
            val record = args[0] as MezzeRecord
            val x = record.fields["x"] ?: throw NativeFunctionException("__native_deque_singleton", "expects 'x' element")
            MezzeDeque(listOf(x), emptyList())
        }

        // Create from list
        context["__native_deque_from_list"] = NativeMezzeFunction("__native_deque_from_list") { args ->
            val record = args[0] as MezzeRecord
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_deque_from_list", "expects 'xs' as List")
            MezzeDeque(xs.toKotlinList(), emptyList())
        }

        // ========================================
        // Add elements
        // ========================================

        // Push to front
        context["__native_deque_push_front"] = NativeMezzeFunction("__native_deque_push_front") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_push_front", "expects 'd' as Deque")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_deque_push_front", "expects 'x' element")
            d.pushFront(x)
        }

        // Push to back
        context["__native_deque_push_back"] = NativeMezzeFunction("__native_deque_push_back") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_push_back", "expects 'd' as Deque")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_deque_push_back", "expects 'x' element")
            d.pushBack(x)
        }

        // ========================================
        // Remove elements (returns Option)
        // ========================================

        // Pop from front - returns Option {head: 'a, tail: Deque 'a}
        context["__native_deque_pop_front"] = NativeMezzeFunction("__native_deque_pop_front") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_pop_front", "expects 'd' as Deque")

            val result = d.popFront()
            if (result != null) {
                val (head, tail) = result
                MezzeVariant("Some", MezzeRecord(mapOf(
                    "val" to MezzeRecord(mapOf("head" to head, "tail" to tail))
                )))
            } else {
                MezzeVariant("None", null)
            }
        }

        // Pop from back - returns Option {init: Deque 'a, last: 'a}
        context["__native_deque_pop_back"] = NativeMezzeFunction("__native_deque_pop_back") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_pop_back", "expects 'd' as Deque")

            val result = d.popBack()
            if (result != null) {
                val (init, last) = result
                MezzeVariant("Some", MezzeRecord(mapOf(
                    "val" to MezzeRecord(mapOf("init" to init, "last" to last))
                )))
            } else {
                MezzeVariant("None", null)
            }
        }

        // ========================================
        // Peek (without removing)
        // ========================================

        // Peek front - returns Option 'a
        context["__native_deque_peek_front"] = NativeMezzeFunction("__native_deque_peek_front") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_peek_front", "expects 'd' as Deque")

            val front = d.peekFront()
            if (front != null) {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to front)))
            } else {
                MezzeVariant("None", null)
            }
        }

        // Peek back - returns Option 'a
        context["__native_deque_peek_back"] = NativeMezzeFunction("__native_deque_peek_back") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_peek_back", "expects 'd' as Deque")

            val back = d.peekBack()
            if (back != null) {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to back)))
            } else {
                MezzeVariant("None", null)
            }
        }

        // ========================================
        // Queries
        // ========================================

        // Is empty
        context["__native_deque_is_empty"] = NativeMezzeFunction("__native_deque_is_empty") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_is_empty", "expects 'd' as Deque")
            MezzeBool(d.isEmpty())
        }

        // Length
        context["__native_deque_length"] = NativeMezzeFunction("__native_deque_length") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_length", "expects 'd' as Deque")
            MezzeInt(d.size().toLong())
        }

        // ========================================
        // Conversions
        // ========================================

        // To list
        context["__native_deque_to_list"] = NativeMezzeFunction("__native_deque_to_list") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_to_list", "expects 'd' as Deque")
            MezzeList.fromKotlinList(d.toList())
        }

        // To array
        context["__native_deque_to_array"] = NativeMezzeFunction("__native_deque_to_array") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_to_array", "expects 'd' as Deque")
            MezzeArrayList(ArrayList(d.toList()))
        }

        // ========================================
        // Higher-order functions
        // ========================================

        // Map
        context["__native_deque_map"] = NativeMezzeFunction("__native_deque_map") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_map", "expects 'd' as Deque")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_deque_map", "expects 'f' function")

            val elements = d.toList()
            val mapped = elements.map { element ->
                val arg = MezzeRecord(mapOf("x" to element))
                callFunction(f, arg, "__native_deque_map")
            }

            MezzeDeque(mapped, emptyList())
        }

        // Filter
        context["__native_deque_filter"] = NativeMezzeFunction("__native_deque_filter") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_filter", "expects 'd' as Deque")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_deque_filter", "expects 'p' predicate")

            val elements = d.toList()
            val filtered = elements.filter { element ->
                val arg = MezzeRecord(mapOf("x" to element))
                val result = callFunction(p, arg, "__native_deque_filter")
                (result as MezzeBool).value
            }

            MezzeDeque(filtered, emptyList())
        }

        // Fold (left to right, front to back)
        context["__native_deque_fold"] = NativeMezzeFunction("__native_deque_fold") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_fold", "expects 'd' as Deque")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_deque_fold", "expects 'init' value")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_deque_fold", "expects 'f' function")

            val elements = d.toList()
            var accumulator = init
            for (element in elements) {
                val arg = MezzeRecord(mapOf("acc" to accumulator, "x" to element))
                accumulator = callFunction(f, arg, "__native_deque_fold")
            }

            accumulator
        }

        // Reverse
        context["__native_deque_reverse"] = NativeMezzeFunction("__native_deque_reverse") { args ->
            val record = args[0] as MezzeRecord
            val d = record.fields["d"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_reverse", "expects 'd' as Deque")
            // Swap front and back (since back is already reversed)
            MezzeDeque(d.back, d.front)
        }

        // Concatenate two deques
        context["__native_deque_concat"] = NativeMezzeFunction("__native_deque_concat") { args ->
            val record = args[0] as MezzeRecord
            val d1 = record.fields["d1"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_concat", "expects 'd1' as Deque")
            val d2 = record.fields["d2"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_concat", "expects 'd2' as Deque")

            // Concatenate: all elements of d1 followed by all elements of d2
            val combined = d1.toList() + d2.toList()
            MezzeDeque(combined, emptyList())
        }

        // Equality check
        context["__native_deque_equals"] = NativeMezzeFunction("__native_deque_equals") { args ->
            val record = args[0] as MezzeRecord
            val d1 = record.fields["d1"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_equals", "expects 'd1' as Deque")
            val d2 = record.fields["d2"] as? MezzeDeque
                ?: throw NativeFunctionException("__native_deque_equals", "expects 'd2' as Deque")

            // Two deques are equal if they have the same elements in the same order
            val list1 = d1.toList()
            val list2 = d2.toList()
            MezzeBool(list1 == list2)
        }
    }
}
