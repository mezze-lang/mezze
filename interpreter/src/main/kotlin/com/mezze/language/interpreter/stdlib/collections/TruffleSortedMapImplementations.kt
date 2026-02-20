package com.mezze.language.interpreter.stdlib.collections

import com.mezze.language.interpreter.*

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

    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Create empty sorted map
        context["__native_sorted_map_empty"] = NativeMezzeFunction("__native_sorted_map_empty") { _ ->
            MezzeSortedMap.empty()
        }

        // Insert key-value pair - O(log n)
        context["__native_sorted_map_insert"] = NativeMezzeFunction("__native_sorted_map_insert") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_insert", "expects 'm' as SortedMap")
            val k = record.fields["k"]
                ?: throw NativeFunctionException("__native_sorted_map_insert", "expects 'k' field")
            val v = record.fields["v"]
                ?: throw NativeFunctionException("__native_sorted_map_insert", "expects 'v' field")

            m.put(k, v)
        }

        // Get value by key (returns Option) - O(log n)
        context["__native_sorted_map_get"] = NativeMezzeFunction("__native_sorted_map_get") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_get", "expects 'm' as SortedMap")
            val k = record.fields["k"]
                ?: throw NativeFunctionException("__native_sorted_map_get", "expects 'k' field")

            val value = m.get(k)
            if (value != null) {
                mezzeOptionSome(value)
            } else {
                mezzeOptionNone()
            }
        }

        // Remove key - O(log n)
        context["__native_sorted_map_remove"] = NativeMezzeFunction("__native_sorted_map_remove") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_remove", "expects 'm' as SortedMap")
            val k = record.fields["k"]
                ?: throw NativeFunctionException("__native_sorted_map_remove", "expects 'k' field")

            m.remove(k)
        }

        // Check if key exists - O(log n)
        context["__native_sorted_map_contains_key"] = NativeMezzeFunction("__native_sorted_map_contains_key") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_contains_key", "expects 'm' as SortedMap")
            val k = record.fields["k"]
                ?: throw NativeFunctionException("__native_sorted_map_contains_key", "expects 'k' field")

            MezzeBool(m.containsKey(k))
        }

        // Get size - O(1)
        context["__native_sorted_map_size"] = NativeMezzeFunction("__native_sorted_map_size") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_size", "expects 'm' as SortedMap")

            MezzeInt(m.size().toLong())
        }

        // Check if empty - O(1)
        context["__native_sorted_map_is_empty"] = NativeMezzeFunction("__native_sorted_map_is_empty") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_is_empty", "expects 'm' as SortedMap")

            MezzeBool(m.isEmpty())
        }

        // Get all keys (in sorted order)
        context["__native_sorted_map_keys"] = NativeMezzeFunction("__native_sorted_map_keys") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_keys", "expects 'm' as SortedMap")

            MezzeList.fromKotlinList(m.keys())
        }

        // Get all values (in key-sorted order)
        context["__native_sorted_map_values"] = NativeMezzeFunction("__native_sorted_map_values") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_values", "expects 'm' as SortedMap")

            MezzeList.fromKotlinList(m.values())
        }

        // Get all entries (in key-sorted order)
        context["__native_sorted_map_entries"] = NativeMezzeFunction("__native_sorted_map_entries") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_entries", "expects 'm' as SortedMap")

            val entries = m.toKotlinMap().map { (k, v) ->
                MezzeRecord(mapOf("key" to k, "value" to v))
            }
            MezzeList.fromKotlinList(entries)
        }

        // Get minimum key (returns Option) - O(log n)
        context["__native_sorted_map_min_key"] = NativeMezzeFunction("__native_sorted_map_min_key") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_min_key", "expects 'm' as SortedMap")

            val minKey = m.minKey()
            if (minKey != null) {
                mezzeOptionSome(minKey)
            } else {
                mezzeOptionNone()
            }
        }

        // Get maximum key (returns Option) - O(log n)
        context["__native_sorted_map_max_key"] = NativeMezzeFunction("__native_sorted_map_max_key") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_max_key", "expects 'm' as SortedMap")

            val maxKey = m.maxKey()
            if (maxKey != null) {
                mezzeOptionSome(maxKey)
            } else {
                mezzeOptionNone()
            }
        }

        // Get minimum entry (returns Option {key, value}) - O(log n)
        context["__native_sorted_map_min_entry"] = NativeMezzeFunction("__native_sorted_map_min_entry") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_min_entry", "expects 'm' as SortedMap")

            val minEntry = m.minEntry()
            if (minEntry != null) {
                mezzeOptionSome(MezzeRecord(mapOf("key" to minEntry.first, "value" to minEntry.second)))
            } else {
                mezzeOptionNone()
            }
        }

        // Get maximum entry (returns Option {key, value}) - O(log n)
        context["__native_sorted_map_max_entry"] = NativeMezzeFunction("__native_sorted_map_max_entry") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_max_entry", "expects 'm' as SortedMap")

            val maxEntry = m.maxEntry()
            if (maxEntry != null) {
                mezzeOptionSome(MezzeRecord(mapOf("key" to maxEntry.first, "value" to maxEntry.second)))
            } else {
                mezzeOptionNone()
            }
        }

        // Convert from regular Map to SortedMap
        context["__native_sorted_map_from_map"] = NativeMezzeFunction("__native_sorted_map_from_map") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeMap
                ?: throw NativeFunctionException("__native_sorted_map_from_map", "expects 'm' as Map")

            MezzeSortedMap.fromMap(m.toKotlinMap())
        }

        // Convert SortedMap to regular Map
        context["__native_sorted_map_to_map"] = NativeMezzeFunction("__native_sorted_map_to_map") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_to_map", "expects 'm' as SortedMap")

            MezzeMap(m.toKotlinMap())
        }

        // Equality check
        context["__native_sorted_map_equals"] = NativeMezzeFunction("__native_sorted_map_equals") { args ->
            val record = args[0] as MezzeRecord
            val m1 = record.fields["m1"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_equals", "expects 'm1' as SortedMap")
            val m2 = record.fields["m2"] as? MezzeSortedMap
                ?: throw NativeFunctionException("__native_sorted_map_equals", "expects 'm2' as SortedMap")

            // Two sorted maps are equal if they have the same entries
            MezzeBool(m1.entries == m2.entries)
        }
    }
}
