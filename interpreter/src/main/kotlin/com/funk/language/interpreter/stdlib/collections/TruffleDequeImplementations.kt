package com.funk.language.interpreter.stdlib.collections

import com.funk.language.interpreter.*

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
    private fun callFunction(f: FunkValue, arg: FunkValue, operationName: String): FunkValue {
        return when (f) {
            is FunkTruffleFunction -> {
                f.callTarget.call(
                    arg,
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as FunkValue
            }
            is NativeFunkFunction -> {
                f.implementation(listOf(arg))
            }
            else -> {
                throw NativeFunctionException(operationName,
                    "Expected Truffle function or native function, got: ${f::class.simpleName}")
            }
        }
    }

    fun registerAll(context: MutableMap<String, FunkValue>) {

        // ========================================
        // Construction
        // ========================================

        // Create empty deque
        context["__native_deque_empty"] = NativeFunkFunction("__native_deque_empty") { args ->
            FunkDeque(emptyList(), emptyList())
        }

        // Create singleton deque
        context["__native_deque_singleton"] = NativeFunkFunction("__native_deque_singleton") { args ->
            val record = args[0] as FunkRecord
            val x = record.fields["x"] ?: throw NativeFunctionException("__native_deque_singleton", "expects 'x' element")
            FunkDeque(listOf(x), emptyList())
        }

        // Create from list
        context["__native_deque_from_list"] = NativeFunkFunction("__native_deque_from_list") { args ->
            val record = args[0] as FunkRecord
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_deque_from_list", "expects 'xs' as List")
            FunkDeque(xs.toKotlinList(), emptyList())
        }

        // ========================================
        // Add elements
        // ========================================

        // Push to front
        context["__native_deque_push_front"] = NativeFunkFunction("__native_deque_push_front") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_push_front", "expects 'd' as Deque")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_deque_push_front", "expects 'x' element")
            d.pushFront(x)
        }

        // Push to back
        context["__native_deque_push_back"] = NativeFunkFunction("__native_deque_push_back") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_push_back", "expects 'd' as Deque")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_deque_push_back", "expects 'x' element")
            d.pushBack(x)
        }

        // ========================================
        // Remove elements (returns Option)
        // ========================================

        // Pop from front - returns Option {head: 'a, tail: Deque 'a}
        context["__native_deque_pop_front"] = NativeFunkFunction("__native_deque_pop_front") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_pop_front", "expects 'd' as Deque")

            val result = d.popFront()
            if (result != null) {
                val (head, tail) = result
                FunkVariant("Some", FunkRecord(mapOf(
                    "val" to FunkRecord(mapOf("head" to head, "tail" to tail))
                )))
            } else {
                FunkVariant("None", null)
            }
        }

        // Pop from back - returns Option {init: Deque 'a, last: 'a}
        context["__native_deque_pop_back"] = NativeFunkFunction("__native_deque_pop_back") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_pop_back", "expects 'd' as Deque")

            val result = d.popBack()
            if (result != null) {
                val (init, last) = result
                FunkVariant("Some", FunkRecord(mapOf(
                    "val" to FunkRecord(mapOf("init" to init, "last" to last))
                )))
            } else {
                FunkVariant("None", null)
            }
        }

        // ========================================
        // Peek (without removing)
        // ========================================

        // Peek front - returns Option 'a
        context["__native_deque_peek_front"] = NativeFunkFunction("__native_deque_peek_front") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_peek_front", "expects 'd' as Deque")

            val front = d.peekFront()
            if (front != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to front)))
            } else {
                FunkVariant("None", null)
            }
        }

        // Peek back - returns Option 'a
        context["__native_deque_peek_back"] = NativeFunkFunction("__native_deque_peek_back") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_peek_back", "expects 'd' as Deque")

            val back = d.peekBack()
            if (back != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to back)))
            } else {
                FunkVariant("None", null)
            }
        }

        // ========================================
        // Queries
        // ========================================

        // Is empty
        context["__native_deque_is_empty"] = NativeFunkFunction("__native_deque_is_empty") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_is_empty", "expects 'd' as Deque")
            FunkBool(d.isEmpty())
        }

        // Length
        context["__native_deque_length"] = NativeFunkFunction("__native_deque_length") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_length", "expects 'd' as Deque")
            FunkInt(d.size().toLong())
        }

        // ========================================
        // Conversions
        // ========================================

        // To list
        context["__native_deque_to_list"] = NativeFunkFunction("__native_deque_to_list") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_to_list", "expects 'd' as Deque")
            FunkList.fromKotlinList(d.toList())
        }

        // To array
        context["__native_deque_to_array"] = NativeFunkFunction("__native_deque_to_array") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_to_array", "expects 'd' as Deque")
            FunkArrayList(ArrayList(d.toList()))
        }

        // ========================================
        // Higher-order functions
        // ========================================

        // Map
        context["__native_deque_map"] = NativeFunkFunction("__native_deque_map") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_map", "expects 'd' as Deque")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_deque_map", "expects 'f' function")

            val elements = d.toList()
            val mapped = elements.map { element ->
                val arg = FunkRecord(mapOf("x" to element))
                callFunction(f, arg, "__native_deque_map")
            }

            FunkDeque(mapped, emptyList())
        }

        // Filter
        context["__native_deque_filter"] = NativeFunkFunction("__native_deque_filter") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_filter", "expects 'd' as Deque")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_deque_filter", "expects 'p' predicate")

            val elements = d.toList()
            val filtered = elements.filter { element ->
                val arg = FunkRecord(mapOf("x" to element))
                val result = callFunction(p, arg, "__native_deque_filter")
                (result as FunkBool).value
            }

            FunkDeque(filtered, emptyList())
        }

        // Fold (left to right, front to back)
        context["__native_deque_fold"] = NativeFunkFunction("__native_deque_fold") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_fold", "expects 'd' as Deque")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_deque_fold", "expects 'init' value")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_deque_fold", "expects 'f' function")

            val elements = d.toList()
            var accumulator = init
            for (element in elements) {
                val arg = FunkRecord(mapOf("acc" to accumulator, "x" to element))
                accumulator = callFunction(f, arg, "__native_deque_fold")
            }

            accumulator
        }

        // Reverse
        context["__native_deque_reverse"] = NativeFunkFunction("__native_deque_reverse") { args ->
            val record = args[0] as FunkRecord
            val d = record.fields["d"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_reverse", "expects 'd' as Deque")
            // Swap front and back (since back is already reversed)
            FunkDeque(d.back, d.front)
        }

        // Concatenate two deques
        context["__native_deque_concat"] = NativeFunkFunction("__native_deque_concat") { args ->
            val record = args[0] as FunkRecord
            val d1 = record.fields["d1"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_concat", "expects 'd1' as Deque")
            val d2 = record.fields["d2"] as? FunkDeque
                ?: throw NativeFunctionException("__native_deque_concat", "expects 'd2' as Deque")

            // Concatenate: all elements of d1 followed by all elements of d2
            val combined = d1.toList() + d2.toList()
            FunkDeque(combined, emptyList())
        }
    }
}
