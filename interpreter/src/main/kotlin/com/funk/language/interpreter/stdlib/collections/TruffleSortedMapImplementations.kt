package com.funk.language.interpreter.stdlib.collections

import com.funk.language.interpreter.*

/**
 * Truffle-optimized implementations for SortedMap operations.
 *
 * SortedMap is backed by Vavr TreeMap for O(log n) operations with keys maintained in sorted order.
 *
 * Performance characteristics (via Vavr TreeMap's Red-Black Tree):
 * - get, put, remove: O(log n)
 * - min, max: O(log n)
 * - containsKey: O(log n)
 * - size: O(1)
 *
 * All operations are immutable and return new maps with structural sharing.
 */
object TruffleSortedMapImplementations {

    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Create empty sorted map
        context["__native_sorted_map_empty"] = NativeFunkFunction("__native_sorted_map_empty") { _ ->
            FunkSortedMap.empty()
        }

        // Insert key-value pair - O(log n)
        context["__native_sorted_map_insert"] = NativeFunkFunction("__native_sorted_map_insert") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_insert", "expects 'm' as SortedMap")
            val k = record.fields["k"]
                ?: throw NativeFunctionException("__native_sorted_map_insert", "expects 'k' field")
            val v = record.fields["v"]
                ?: throw NativeFunctionException("__native_sorted_map_insert", "expects 'v' field")

            m.put(k, v)
        }

        // Get value by key (returns Option) - O(log n)
        context["__native_sorted_map_get"] = NativeFunkFunction("__native_sorted_map_get") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_get", "expects 'm' as SortedMap")
            val k = record.fields["k"]
                ?: throw NativeFunctionException("__native_sorted_map_get", "expects 'k' field")

            val value = m.get(k)
            if (value != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to value)))
            } else {
                FunkVariant("None", null)
            }
        }

        // Remove key - O(log n)
        context["__native_sorted_map_remove"] = NativeFunkFunction("__native_sorted_map_remove") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_remove", "expects 'm' as SortedMap")
            val k = record.fields["k"]
                ?: throw NativeFunctionException("__native_sorted_map_remove", "expects 'k' field")

            m.remove(k)
        }

        // Check if key exists - O(log n)
        context["__native_sorted_map_contains_key"] = NativeFunkFunction("__native_sorted_map_contains_key") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_contains_key", "expects 'm' as SortedMap")
            val k = record.fields["k"]
                ?: throw NativeFunctionException("__native_sorted_map_contains_key", "expects 'k' field")

            FunkBool(m.containsKey(k))
        }

        // Get size - O(1)
        context["__native_sorted_map_size"] = NativeFunkFunction("__native_sorted_map_size") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_size", "expects 'm' as SortedMap")

            FunkInt(m.size().toLong())
        }

        // Check if empty - O(1)
        context["__native_sorted_map_is_empty"] = NativeFunkFunction("__native_sorted_map_is_empty") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_is_empty", "expects 'm' as SortedMap")

            FunkBool(m.isEmpty())
        }

        // Get all keys (in sorted order)
        context["__native_sorted_map_keys"] = NativeFunkFunction("__native_sorted_map_keys") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_keys", "expects 'm' as SortedMap")

            FunkList.fromKotlinList(m.keys())
        }

        // Get all values (in key-sorted order)
        context["__native_sorted_map_values"] = NativeFunkFunction("__native_sorted_map_values") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_values", "expects 'm' as SortedMap")

            FunkList.fromKotlinList(m.values())
        }

        // Get all entries (in key-sorted order)
        context["__native_sorted_map_entries"] = NativeFunkFunction("__native_sorted_map_entries") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_entries", "expects 'm' as SortedMap")

            val entries = m.toKotlinMap().map { (k, v) ->
                FunkRecord(mapOf("key" to k, "value" to v))
            }
            FunkList.fromKotlinList(entries)
        }

        // Get minimum key (returns Option) - O(log n)
        context["__native_sorted_map_min_key"] = NativeFunkFunction("__native_sorted_map_min_key") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_min_key", "expects 'm' as SortedMap")

            val minKey = m.minKey()
            if (minKey != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to minKey)))
            } else {
                FunkVariant("None", null)
            }
        }

        // Get maximum key (returns Option) - O(log n)
        context["__native_sorted_map_max_key"] = NativeFunkFunction("__native_sorted_map_max_key") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_max_key", "expects 'm' as SortedMap")

            val maxKey = m.maxKey()
            if (maxKey != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to maxKey)))
            } else {
                FunkVariant("None", null)
            }
        }

        // Get minimum entry (returns Option {key, value}) - O(log n)
        context["__native_sorted_map_min_entry"] = NativeFunkFunction("__native_sorted_map_min_entry") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_min_entry", "expects 'm' as SortedMap")

            val minEntry = m.minEntry()
            if (minEntry != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to FunkRecord(mapOf("key" to minEntry.first, "value" to minEntry.second)))))
            } else {
                FunkVariant("None", null)
            }
        }

        // Get maximum entry (returns Option {key, value}) - O(log n)
        context["__native_sorted_map_max_entry"] = NativeFunkFunction("__native_sorted_map_max_entry") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_max_entry", "expects 'm' as SortedMap")

            val maxEntry = m.maxEntry()
            if (maxEntry != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to FunkRecord(mapOf("key" to maxEntry.first, "value" to maxEntry.second)))))
            } else {
                FunkVariant("None", null)
            }
        }

        // Convert from regular Map to SortedMap
        context["__native_sorted_map_from_map"] = NativeFunkFunction("__native_sorted_map_from_map") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkMap
                ?: throw NativeFunctionException("__native_sorted_map_from_map", "expects 'm' as Map")

            FunkSortedMap.fromMap(m.toKotlinMap())
        }

        // Convert SortedMap to regular Map
        context["__native_sorted_map_to_map"] = NativeFunkFunction("__native_sorted_map_to_map") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_to_map", "expects 'm' as SortedMap")

            FunkMap(m.toKotlinMap())
        }

        // Equality check
        context["__native_sorted_map_equals"] = NativeFunkFunction("__native_sorted_map_equals") { args ->
            val record = args[0] as FunkRecord
            val m1 = record.fields["m1"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_equals", "expects 'm1' as SortedMap")
            val m2 = record.fields["m2"] as? FunkSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_equals", "expects 'm2' as SortedMap")

            // Two sorted maps are equal if they have the same entries
            FunkBool(m1.entries == m2.entries)
        }
    }
}
