package com.mezze.language.interpreter.stdlib.collections

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for SortedSet operations.
 *
 * SortedSet is backed by Vavr TreeSet for O(log n) operations with elements maintained in sorted order.
 *
 * Performance characteristics (via Vavr TreeSet's Red-Black Tree):
 * - add, remove, contains: O(log n)
 * - min, max: O(log n)
 * - size: O(1)
 *
 * All operations are immutable and return new sets with structural sharing.
 */
object TruffleSortedSetImplementations {

    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Create empty sorted set
        context["__native_sorted_set_empty"] = NativeMezzeFunction("__native_sorted_set_empty") { _ ->
            MezzeSortedSet.empty()
        }

        // Create singleton sorted set
        context["__native_sorted_set_singleton"] = NativeMezzeFunction("__native_sorted_set_singleton") { args ->
            val record = args[0] as MezzeRecord
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_sorted_set_singleton", "expects 'x' field")

            MezzeSortedSet.of(x)
        }

        // Add element - O(log n)
        context["__native_sorted_set_add"] = NativeMezzeFunction("__native_sorted_set_add") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_add", "expects 's' as SortedSet")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_sorted_set_add", "expects 'x' field")

            s.add(x)
        }

        // Remove element - O(log n)
        context["__native_sorted_set_remove"] = NativeMezzeFunction("__native_sorted_set_remove") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_remove", "expects 's' as SortedSet")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_sorted_set_remove", "expects 'x' field")

            s.remove(x)
        }

        // Check if element exists - O(log n)
        context["__native_sorted_set_contains"] = NativeMezzeFunction("__native_sorted_set_contains") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_contains", "expects 's' as SortedSet")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_sorted_set_contains", "expects 'x' field")

            MezzeBool(s.contains(x))
        }

        // Get size - O(1)
        context["__native_sorted_set_size"] = NativeMezzeFunction("__native_sorted_set_size") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_size", "expects 's' as SortedSet")

            MezzeInt(s.size().toLong())
        }

        // Check if empty - O(1)
        context["__native_sorted_set_is_empty"] = NativeMezzeFunction("__native_sorted_set_is_empty") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_is_empty", "expects 's' as SortedSet")

            MezzeBool(s.isEmpty())
        }

        // Union of two sorted sets
        context["__native_sorted_set_union"] = NativeMezzeFunction("__native_sorted_set_union") { args ->
            val record = args[0] as MezzeRecord
            val s1 = record.fields["s1"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_union", "expects 's1' as SortedSet")
            val s2 = record.fields["s2"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_union", "expects 's2' as SortedSet")

            s1.union(s2)
        }

        // Intersection of two sorted sets
        context["__native_sorted_set_intersect"] = NativeMezzeFunction("__native_sorted_set_intersect") { args ->
            val record = args[0] as MezzeRecord
            val s1 = record.fields["s1"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_intersect", "expects 's1' as SortedSet")
            val s2 = record.fields["s2"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_intersect", "expects 's2' as SortedSet")

            s1.intersect(s2)
        }

        // Difference of two sorted sets (s1 - s2)
        context["__native_sorted_set_difference"] = NativeMezzeFunction("__native_sorted_set_difference") { args ->
            val record = args[0] as MezzeRecord
            val s1 = record.fields["s1"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_difference", "expects 's1' as SortedSet")
            val s2 = record.fields["s2"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_difference", "expects 's2' as SortedSet")

            s1.difference(s2)
        }

        // Get minimum element (returns Option) - O(log n)
        context["__native_sorted_set_min"] = NativeMezzeFunction("__native_sorted_set_min") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_min", "expects 's' as SortedSet")

            val min = s.min()
            if (min != null) {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to min)))
            } else {
                MezzeVariant("None", null)
            }
        }

        // Get maximum element (returns Option) - O(log n)
        context["__native_sorted_set_max"] = NativeMezzeFunction("__native_sorted_set_max") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_max", "expects 's' as SortedSet")

            val max = s.max()
            if (max != null) {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to max)))
            } else {
                MezzeVariant("None", null)
            }
        }

        // Convert to list (in sorted order)
        context["__native_sorted_set_to_list"] = NativeMezzeFunction("__native_sorted_set_to_list") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_to_list", "expects 's' as SortedSet")

            MezzeList.fromKotlinList(s.toList())
        }

        // Create from list
        context["__native_sorted_set_from_list"] = NativeMezzeFunction("__native_sorted_set_from_list") { args ->
            val record = args[0] as MezzeRecord
            val list = record.fields["list"]
                ?: throw NativeFunctionException("__native_sorted_set_from_list", "expects 'list' field")

            val elements = when (list) {
                is MezzeList -> list.toKotlinList()
                is MezzeArrayList -> list.toList()
                else -> throw NativeFunctionException("__native_sorted_set_from_list", "expects 'list' as List")
            }

            MezzeSortedSet.fromList(elements)
        }

        // Convert from regular Set to SortedSet
        context["__native_sorted_set_from_set"] = NativeMezzeFunction("__native_sorted_set_from_set") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSet
                ?: throw NativeFunctionException("__native_sorted_set_from_set", "expects 's' as Set")

            MezzeSortedSet.fromList(s.toList())
        }

        // Convert SortedSet to regular Set
        context["__native_sorted_set_to_set"] = NativeMezzeFunction("__native_sorted_set_to_set") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_to_set", "expects 's' as SortedSet")

            MezzeSet(s.toKotlinSet())
        }

        // Equality check
        context["__native_sorted_set_equals"] = NativeMezzeFunction("__native_sorted_set_equals") { args ->
            val record = args[0] as MezzeRecord
            val s1 = record.fields["s1"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_equals", "expects 's1' as SortedSet")
            val s2 = record.fields["s2"] as? MezzeSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_equals", "expects 's2' as SortedSet")

            // Two sorted sets are equal if they have the same elements
            MezzeBool(s1.elements == s2.elements)
        }
    }
}
