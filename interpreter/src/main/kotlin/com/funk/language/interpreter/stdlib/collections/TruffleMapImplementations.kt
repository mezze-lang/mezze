package com.funk.language.interpreter.stdlib.collections

import com.funk.language.interpreter.*

/**
 * Truffle runtime implementations for Map collection operations
 */
object TruffleMapImplementations {

    fun registerAll(context: MutableMap<String, FunkValue>) {
        val interpreter = (context["__interpreter_instance"] as? InterpreterReference)?.interpreter

        // Create empty map
        context["__native_map_empty"] = NativeFunkFunction("__native_map_empty") { args ->
            FunkMap(emptyMap())
        }

        // Insert key-value pair
        context["__native_map_insert"] = NativeFunkFunction("__native_map_insert") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkMap ?: throw NativeFunctionException("__native_map_insert", "expects 'm' as Map")
            val k = record.fields["k"] ?: throw NativeFunctionException("__native_map_insert", "expects 'k' field")
            val v = record.fields["v"] ?: throw NativeFunctionException("__native_map_insert", "expects 'v' field")

            FunkMap(m.entries + (k to v))
        }

        // Get value by key (returns Option)
        context["__native_map_get"] = NativeFunkFunction("__native_map_get") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkMap ?: throw NativeFunctionException("__native_map_get", "expects 'm' as Map")
            val k = record.fields["k"] ?: throw NativeFunctionException("__native_map_get", "expects 'k' field")

            val value = m.entries[k]
            if (value != null) {
                // Some { val = value }
                FunkVariant("Some", FunkRecord(mapOf("val" to value)))
            } else {
                // None
                FunkVariant("None", null)
            }
        }

        // Remove key
        context["__native_map_remove"] = NativeFunkFunction("__native_map_remove") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkMap ?: throw NativeFunctionException("__native_map_remove", "expects 'm' as Map")
            val k = record.fields["k"] ?: throw NativeFunctionException("__native_map_remove", "expects 'k' field")

            FunkMap(m.entries - k)
        }

        // Check if key exists
        context["__native_map_contains_key"] = NativeFunkFunction("__native_map_contains_key") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkMap ?: throw NativeFunctionException("__native_map_contains_key", "expects 'm' as Map")
            val k = record.fields["k"] ?: throw NativeFunctionException("__native_map_contains_key", "expects 'k' field")

            FunkBool(m.entries.containsKey(k))
        }

        // Get size
        context["__native_map_size"] = NativeFunkFunction("__native_map_size") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkMap ?: throw NativeFunctionException("__native_map_size", "expects 'm' as Map")

            FunkInt(m.entries.size.toLong())
        }

        // Get all keys
        context["__native_map_keys"] = NativeFunkFunction("__native_map_keys") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkMap ?: throw NativeFunctionException("__native_map_keys", "expects 'm' as Map")

            FunkList(m.entries.keys.toList())
        }

        // Get all values
        context["__native_map_values"] = NativeFunkFunction("__native_map_values") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkMap ?: throw NativeFunctionException("__native_map_values", "expects 'm' as Map")

            FunkList(m.entries.values.toList())
        }

        // Get all entries (commented out in stdlib)
        context["__native_map_entries"] = NativeFunkFunction("__native_map_entries") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkMap ?: throw NativeFunctionException("__native_map_entries", "expects 'm' as Map")

            // Return list of {key, value} records
            val entries = m.entries.map { (k, v) ->
                FunkRecord(mapOf("key" to k, "value" to v))
            }
            FunkList(entries)
        }

        // Map values
        context["__native_map_map_values"] = NativeFunkFunction("__native_map_map_values") { args ->
            if (interpreter == null) {
                throw NativeFunctionException("__native_map_map_values", "interpreter not available")
            }

            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkMap ?: throw NativeFunctionException("__native_map_map_values", "expects 'm' as Map")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_map_map_values", "expects 'f' function")

            val newEntries = m.entries.map { (k, v) ->
                val arg = if (v is FunkRecord) v else FunkRecord(mapOf("val" to v))
                val result = when (f) {
                    is NativeFunkFunction -> f.call(listOf(v))
                    else -> interpreter.callFunctionWithRecord(f, arg, context)
                }
                k to result
            }.toMap()

            FunkMap(newEntries)
        }

        // Filter
        context["__native_map_filter"] = NativeFunkFunction("__native_map_filter") { args ->
            if (interpreter == null) {
                throw NativeFunctionException("__native_map_filter", "interpreter not available")
            }

            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkMap ?: throw NativeFunctionException("__native_map_filter", "expects 'm' as Map")
            val p = record.fields["p"] ?: throw NativeFunctionException("__native_map_filter", "expects 'p' predicate")

            val filteredEntries = m.entries.filter { (k, v) ->
                val entry = FunkRecord(mapOf("key" to k, "value" to v))
                val result = when (p) {
                    is NativeFunkFunction -> p.call(listOf(entry))
                    else -> interpreter.callFunctionWithRecord(p, entry, context)
                }
                (result as FunkBool).value
            }.toMap()

            FunkMap(filteredEntries)
        }

        // Fold
        context["__native_map_fold"] = NativeFunkFunction("__native_map_fold") { args ->
            if (interpreter == null) {
                throw NativeFunctionException("__native_map_fold", "interpreter not available")
            }

            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkMap ?: throw NativeFunctionException("__native_map_fold", "expects 'm' as Map")
            val init = record.fields["init"] ?: throw NativeFunctionException("__native_map_fold", "expects 'init' value")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_map_fold", "expects 'f' function")

            var accumulator = init
            for ((k, v) in m.entries) {
                val entry = FunkRecord(mapOf("key" to k, "value" to v))
                val callArg = FunkRecord(mapOf("acc" to accumulator, "entry" to entry))
                accumulator = when (f) {
                    is NativeFunkFunction -> f.call(listOf(callArg))
                    else -> interpreter.callFunctionWithRecord(f, callArg, context)
                }
            }

            accumulator
        }

        // From keys
        context["__native_map_from_keys"] = NativeFunkFunction("__native_map_from_keys") { args ->
            if (interpreter == null) {
                throw NativeFunctionException("__native_map_from_keys", "interpreter not available")
            }

            val record = args[0] as FunkRecord
            val ks = record.fields["ks"] as? FunkList ?: throw NativeFunctionException("__native_map_from_keys", "expects 'ks' as List")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_map_from_keys", "expects 'f' function")

            val entries = ks.elements.associateWith { k ->
                val arg = if (k is FunkRecord) k else FunkRecord(mapOf("val" to k))
                when (f) {
                    is NativeFunkFunction -> f.call(listOf(k))
                    else -> interpreter.callFunctionWithRecord(f, arg, context)
                }
            }

            FunkMap(entries)
        }

        // Merge (right overwrites left)
        context["__native_map_merge"] = NativeFunkFunction("__native_map_merge") { args ->
            val record = args[0] as FunkRecord
            val m1 = record.fields["m1"] as? FunkMap ?: throw NativeFunctionException("__native_map_merge", "expects 'm1' as Map")
            val m2 = record.fields["m2"] as? FunkMap ?: throw NativeFunctionException("__native_map_merge", "expects 'm2' as Map")

            FunkMap(m1.entries + m2.entries)
        }

        // Merge with function
        context["__native_map_merge_with"] = NativeFunkFunction("__native_map_merge_with") { args ->
            if (interpreter == null) {
                throw NativeFunctionException("__native_map_merge_with", "interpreter not available")
            }

            val record = args[0] as FunkRecord
            val m1 = record.fields["m1"] as? FunkMap ?: throw NativeFunctionException("__native_map_merge_with", "expects 'm1' as Map")
            val m2 = record.fields["m2"] as? FunkMap ?: throw NativeFunctionException("__native_map_merge_with", "expects 'm2' as Map")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_map_merge_with", "expects 'f' function")

            val result = m1.entries.toMutableMap()
            for ((k, v2) in m2.entries) {
                if (k in result) {
                    val v1 = result[k]!!
                    val callArg = FunkRecord(mapOf("v1" to v1, "v2" to v2))
                    result[k] = when (f) {
                        is NativeFunkFunction -> f.call(listOf(callArg))
                        else -> interpreter.callFunctionWithRecord(f, callArg, context)
                    }
                } else {
                    result[k] = v2
                }
            }

            FunkMap(result.toMap())
        }

        // Remove all keys
        context["__native_map_remove_all"] = NativeFunkFunction("__native_map_remove_all") { args ->
            val record = args[0] as FunkRecord
            val m = record.fields["m"] as? FunkMap ?: throw NativeFunctionException("__native_map_remove_all", "expects 'm' as Map")
            val ks = record.fields["ks"] as? FunkList ?: throw NativeFunctionException("__native_map_remove_all", "expects 'ks' as List")

            var result = m.entries
            for (k in ks.elements) {
                result = result - k
            }

            FunkMap(result)
        }
    }
}
