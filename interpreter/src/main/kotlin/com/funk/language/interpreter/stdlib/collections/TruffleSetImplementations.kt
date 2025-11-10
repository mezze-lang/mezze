package com.funk.language.interpreter.stdlib.collections

import com.funk.language.interpreter.*

/**
 * Truffle runtime implementations for Set collection operations
 */
object TruffleSetImplementations {

    fun registerAll(context: MutableMap<String, FunkValue>) {
        val interpreter = (context["__interpreter_instance"] as? InterpreterReference)?.interpreter

        // Create empty set
        context["__native_set_empty"] = NativeFunkFunction("__native_set_empty") { args ->
            FunkSet(emptySet())
        }

        // Insert element
        context["__native_set_insert"] = NativeFunkFunction("__native_set_insert") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_insert", "expects 's' as Set")
            val x = record.fields["x"] ?: throw NativeFunctionException("__native_set_insert", "expects 'x' element")

            FunkSet(s.elements + x)
        }

        // Remove element
        context["__native_set_remove"] = NativeFunkFunction("__native_set_remove") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_remove", "expects 's' as Set")
            val x = record.fields["x"] ?: throw NativeFunctionException("__native_set_remove", "expects 'x' element")

            FunkSet(s.elements - x)
        }

        // Check if element exists
        context["__native_set_contains"] = NativeFunkFunction("__native_set_contains") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_contains", "expects 's' as Set")
            val x = record.fields["x"] ?: throw NativeFunctionException("__native_set_contains", "expects 'x' element")

            FunkBool(s.elements.contains(x))
        }

        // Get size
        context["__native_set_size"] = NativeFunkFunction("__native_set_size") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_size", "expects 's' as Set")

            FunkInt(s.elements.size.toLong())
        }

        // Union
        context["__native_set_union"] = NativeFunkFunction("__native_set_union") { args ->
            val record = args[0] as FunkRecord
            val s1 = record.fields["s1"] as? FunkSet ?: throw NativeFunctionException("__native_set_union", "expects 's1' as Set")
            val s2 = record.fields["s2"] as? FunkSet ?: throw NativeFunctionException("__native_set_union", "expects 's2' as Set")

            FunkSet(s1.elements + s2.elements)
        }

        // Intersection
        context["__native_set_intersection"] = NativeFunkFunction("__native_set_intersection") { args ->
            val record = args[0] as FunkRecord
            val s1 = record.fields["s1"] as? FunkSet ?: throw NativeFunctionException("__native_set_intersection", "expects 's1' as Set")
            val s2 = record.fields["s2"] as? FunkSet ?: throw NativeFunctionException("__native_set_intersection", "expects 's2' as Set")

            FunkSet(s1.elements.intersect(s2.elements))
        }

        // Difference
        context["__native_set_difference"] = NativeFunkFunction("__native_set_difference") { args ->
            val record = args[0] as FunkRecord
            val s1 = record.fields["s1"] as? FunkSet ?: throw NativeFunctionException("__native_set_difference", "expects 's1' as Set")
            val s2 = record.fields["s2"] as? FunkSet ?: throw NativeFunctionException("__native_set_difference", "expects 's2' as Set")

            FunkSet(s1.elements - s2.elements)
        }

        // Is subset
        context["__native_set_is_subset"] = NativeFunkFunction("__native_set_is_subset") { args ->
            val record = args[0] as FunkRecord
            val s1 = record.fields["s1"] as? FunkSet ?: throw NativeFunctionException("__native_set_is_subset", "expects 's1' as Set")
            val s2 = record.fields["s2"] as? FunkSet ?: throw NativeFunctionException("__native_set_is_subset", "expects 's2' as Set")

            FunkBool(s2.elements.containsAll(s1.elements))
        }

        // Convert to list
        context["__native_set_to_list"] = NativeFunkFunction("__native_set_to_list") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_to_list", "expects 's' as Set")

            FunkList(s.elements.toList())
        }

        // Convert from list
        context["__native_set_from_list"] = NativeFunkFunction("__native_set_from_list") { args ->
            val record = args[0] as FunkRecord
            val xs = record.fields["xs"] as? FunkList ?: throw NativeFunctionException("__native_set_from_list", "expects 'xs' as List")

            FunkSet(xs.elements.toSet())
        }

        // Filter
        context["__native_set_filter"] = NativeFunkFunction("__native_set_filter") { args ->
            if (interpreter == null) {
                throw NativeFunctionException("__native_set_filter", "interpreter not available")
            }

            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_filter", "expects 's' as Set")
            val p = record.fields["p"] ?: throw NativeFunctionException("__native_set_filter", "expects 'p' predicate")

            val filtered = s.elements.filter { element ->
                val arg = if (element is FunkRecord) element else FunkRecord(mapOf("val" to element))
                val result = when (p) {
                    is NativeFunkFunction -> p.call(listOf(element))
                    else -> interpreter.callFunctionWithRecord(p, arg, context)
                }
                (result as FunkBool).value
            }.toSet()

            FunkSet(filtered)
        }

        // Map
        context["__native_set_map"] = NativeFunkFunction("__native_set_map") { args ->
            if (interpreter == null) {
                throw NativeFunctionException("__native_set_map", "interpreter not available")
            }

            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_map", "expects 's' as Set")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_set_map", "expects 'f' function")

            val mapped = s.elements.map { element ->
                val arg = if (element is FunkRecord) element else FunkRecord(mapOf("val" to element))
                when (f) {
                    is NativeFunkFunction -> f.call(listOf(element))
                    else -> interpreter.callFunctionWithRecord(f, arg, context)
                }
            }.toSet()

            FunkSet(mapped)
        }

        // Fold
        context["__native_set_fold"] = NativeFunkFunction("__native_set_fold") { args ->
            if (interpreter == null) {
                throw NativeFunctionException("__native_set_fold", "interpreter not available")
            }

            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_fold", "expects 's' as Set")
            val init = record.fields["init"] ?: throw NativeFunctionException("__native_set_fold", "expects 'init' value")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_set_fold", "expects 'f' function")

            var accumulator = init
            for (element in s.elements) {
                val callArg = FunkRecord(mapOf("acc" to accumulator, "elem" to element))
                accumulator = when (f) {
                    is NativeFunkFunction -> f.call(listOf(callArg))
                    else -> interpreter.callFunctionWithRecord(f, callArg, context)
                }
            }

            accumulator
        }

        // Insert all
        context["__native_set_insert_all"] = NativeFunkFunction("__native_set_insert_all") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_insert_all", "expects 's' as Set")
            val xs = record.fields["xs"] as? FunkList ?: throw NativeFunctionException("__native_set_insert_all", "expects 'xs' as List")

            FunkSet(s.elements + xs.elements.toSet())
        }

        // Remove all
        context["__native_set_remove_all"] = NativeFunkFunction("__native_set_remove_all") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_remove_all", "expects 's' as Set")
            val xs = record.fields["xs"] as? FunkList ?: throw NativeFunctionException("__native_set_remove_all", "expects 'xs' as List")

            FunkSet(s.elements - xs.elements.toSet())
        }
    }
}
