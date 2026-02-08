package com.funk.language.interpreter.stdlib.collections

import com.funk.language.interpreter.*

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

    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Create empty sorted set
        context["__native_sorted_set_empty"] = NativeFunkFunction("__native_sorted_set_empty") { _ ->
            FunkSortedSet.empty()
        }

        // Create singleton sorted set
        context["__native_sorted_set_singleton"] = NativeFunkFunction("__native_sorted_set_singleton") { args ->
            val record = args[0] as FunkRecord
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_sorted_set_singleton", "expects 'x' field")

            FunkSortedSet.of(x)
        }

        // Add element - O(log n)
        context["__native_sorted_set_add"] = NativeFunkFunction("__native_sorted_set_add") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_add", "expects 's' as SortedSet")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_sorted_set_add", "expects 'x' field")

            s.add(x)
        }

        // Remove element - O(log n)
        context["__native_sorted_set_remove"] = NativeFunkFunction("__native_sorted_set_remove") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_remove", "expects 's' as SortedSet")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_sorted_set_remove", "expects 'x' field")

            s.remove(x)
        }

        // Check if element exists - O(log n)
        context["__native_sorted_set_contains"] = NativeFunkFunction("__native_sorted_set_contains") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_contains", "expects 's' as SortedSet")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_sorted_set_contains", "expects 'x' field")

            FunkBool(s.contains(x))
        }

        // Get size - O(1)
        context["__native_sorted_set_size"] = NativeFunkFunction("__native_sorted_set_size") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_size", "expects 's' as SortedSet")

            FunkInt(s.size().toLong())
        }

        // Check if empty - O(1)
        context["__native_sorted_set_is_empty"] = NativeFunkFunction("__native_sorted_set_is_empty") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_is_empty", "expects 's' as SortedSet")

            FunkBool(s.isEmpty())
        }

        // Union of two sorted sets
        context["__native_sorted_set_union"] = NativeFunkFunction("__native_sorted_set_union") { args ->
            val record = args[0] as FunkRecord
            val s1 = record.fields["s1"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_union", "expects 's1' as SortedSet")
            val s2 = record.fields["s2"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_union", "expects 's2' as SortedSet")

            s1.union(s2)
        }

        // Intersection of two sorted sets
        context["__native_sorted_set_intersect"] = NativeFunkFunction("__native_sorted_set_intersect") { args ->
            val record = args[0] as FunkRecord
            val s1 = record.fields["s1"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_intersect", "expects 's1' as SortedSet")
            val s2 = record.fields["s2"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_intersect", "expects 's2' as SortedSet")

            s1.intersect(s2)
        }

        // Difference of two sorted sets (s1 - s2)
        context["__native_sorted_set_difference"] = NativeFunkFunction("__native_sorted_set_difference") { args ->
            val record = args[0] as FunkRecord
            val s1 = record.fields["s1"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_difference", "expects 's1' as SortedSet")
            val s2 = record.fields["s2"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_difference", "expects 's2' as SortedSet")

            s1.difference(s2)
        }

        // Get minimum element (returns Option) - O(log n)
        context["__native_sorted_set_min"] = NativeFunkFunction("__native_sorted_set_min") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_min", "expects 's' as SortedSet")

            val min = s.min()
            if (min != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to min)))
            } else {
                FunkVariant("None", null)
            }
        }

        // Get maximum element (returns Option) - O(log n)
        context["__native_sorted_set_max"] = NativeFunkFunction("__native_sorted_set_max") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_max", "expects 's' as SortedSet")

            val max = s.max()
            if (max != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to max)))
            } else {
                FunkVariant("None", null)
            }
        }

        // Convert to list (in sorted order)
        context["__native_sorted_set_to_list"] = NativeFunkFunction("__native_sorted_set_to_list") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_to_list", "expects 's' as SortedSet")

            FunkList.fromKotlinList(s.toList())
        }

        // Create from list
        context["__native_sorted_set_from_list"] = NativeFunkFunction("__native_sorted_set_from_list") { args ->
            val record = args[0] as FunkRecord
            val list = record.fields["list"]
                ?: throw NativeFunctionException("__native_sorted_set_from_list", "expects 'list' field")

            val elements = when (list) {
                is FunkList -> list.toKotlinList()
                is FunkArrayList -> list.toList()
                else -> throw NativeFunctionException("__native_sorted_set_from_list", "expects 'list' as List")
            }

            FunkSortedSet.fromList(elements)
        }

        // Convert from regular Set to SortedSet
        context["__native_sorted_set_from_set"] = NativeFunkFunction("__native_sorted_set_from_set") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet
                ?: throw NativeFunctionException("__native_sorted_set_from_set", "expects 's' as Set")

            FunkSortedSet.fromList(s.toList())
        }

        // Convert SortedSet to regular Set
        context["__native_sorted_set_to_set"] = NativeFunkFunction("__native_sorted_set_to_set") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_to_set", "expects 's' as SortedSet")

            FunkSet(s.toKotlinSet())
        }

        // Equality check
        context["__native_sorted_set_equals"] = NativeFunkFunction("__native_sorted_set_equals") { args ->
            val record = args[0] as FunkRecord
            val s1 = record.fields["s1"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_equals", "expects 's1' as SortedSet")
            val s2 = record.fields["s2"] as? FunkSortedSet
                ?: throw NativeFunctionException("__native_sorted_set_equals", "expects 's2' as SortedSet")

            // Two sorted sets are equal if they have the same elements
            FunkBool(s1.elements == s2.elements)
        }
    }
}
