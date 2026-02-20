package com.mezze.language.interpreter.stdlib.collections

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for Map operations.
 *
 * Phase 3 of Truffle migration: Collection operations optimized for JIT performance.
 * Map is backed by Vavr HashMap for O(1) persistent operations with structural sharing.
 *
 * Performance characteristics (via Vavr HashMap's HAMT):
 * - get: O(1) effectively constant
 * - put: O(1) effectively constant, returns new map
 * - remove: O(1) effectively constant, returns new map
 * - containsKey: O(1) effectively constant
 *
 * All operations are immutable and return new maps with structural sharing.
 */
object TruffleMapImplementations {

    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Create empty map
        context["__native_map_empty"] = NativeMezzeFunction("__native_map_empty") { args ->
            MezzeMap.empty()
        }

        // Insert key-value pair - O(1)
        context["__native_map_insert"] = NativeMezzeFunction("__native_map_insert") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeMap ?: throw NativeFunctionException("__native_map_insert", "expects 'm' as Map")
            val k = record.fields["k"] ?: throw NativeFunctionException("__native_map_insert", "expects 'k' field")
            val v = record.fields["v"] ?: throw NativeFunctionException("__native_map_insert", "expects 'v' field")

            m.put(k, v)
        }

        // Get value by key (returns Option) - O(1)
        context["__native_map_get"] = NativeMezzeFunction("__native_map_get") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeMap ?: throw NativeFunctionException("__native_map_get", "expects 'm' as Map")
            val k = record.fields["k"] ?: throw NativeFunctionException("__native_map_get", "expects 'k' field")

            val value = m.get(k)
            if (value != null) {
                mezzeOptionSome(value)
            } else {
                mezzeOptionNone()
            }
        }

        // Remove key - O(1)
        context["__native_map_remove"] = NativeMezzeFunction("__native_map_remove") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeMap ?: throw NativeFunctionException("__native_map_remove", "expects 'm' as Map")
            val k = record.fields["k"] ?: throw NativeFunctionException("__native_map_remove", "expects 'k' field")

            m.remove(k)
        }

        // Check if key exists - O(1)
        context["__native_map_contains_key"] = NativeMezzeFunction("__native_map_contains_key") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeMap ?: throw NativeFunctionException("__native_map_contains_key", "expects 'm' as Map")
            val k = record.fields["k"] ?: throw NativeFunctionException("__native_map_contains_key", "expects 'k' field")

            MezzeBool(m.containsKey(k))
        }

        // Get size
        context["__native_map_size"] = NativeMezzeFunction("__native_map_size") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeMap ?: throw NativeFunctionException("__native_map_size", "expects 'm' as Map")

            MezzeInt(m.size().toLong())
        }

        // Get all keys
        context["__native_map_keys"] = NativeMezzeFunction("__native_map_keys") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeMap ?: throw NativeFunctionException("__native_map_keys", "expects 'm' as Map")

            MezzeList.fromKotlinList(m.keys())
        }

        // Get all values
        context["__native_map_values"] = NativeMezzeFunction("__native_map_values") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeMap ?: throw NativeFunctionException("__native_map_values", "expects 'm' as Map")

            MezzeList.fromKotlinList(m.values())
        }

        // Get all entries
        context["__native_map_entries"] = NativeMezzeFunction("__native_map_entries") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeMap ?: throw NativeFunctionException("__native_map_entries", "expects 'm' as Map")

            // Return list of {key, value} records - iterate Vavr directly without toKotlinMap()
            val entries = m.entries.map { tuple ->
                MezzeRecord(mapOf("key" to tuple._1, "value" to tuple._2))
            }.toJavaList()
            MezzeList.fromKotlinList(entries)
        }

        // Map values
        context["__native_map_map_values"] = NativeMezzeFunction("__native_map_map_values") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeMap ?: throw NativeFunctionException("__native_map_map_values", "expects 'm' as Map")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_map_map_values", "expects 'f' function")

            // Use Vavr's mapValues directly - applies function to each value
            val newMap = m.entries.mapValues { v ->
                val arg = if (v is MezzeRecord) v else MezzeRecord(mapOf("val" to v))
                when (f) {
                    is NativeMezzeFunction -> f.call(listOf(v))
                    is MezzeTruffleFunction -> f.callTarget.call(arg, f.closureVariables, f.lexicalScope, f.lexicalScopeSlotMap) as MezzeValue
                    else -> throw NativeFunctionException("__native_map_map_values", "unsupported function type: ${f::class.simpleName}")
                }
            }

            MezzeMap(newMap)
        }

        // Filter
        context["__native_map_filter"] = NativeMezzeFunction("__native_map_filter") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeMap ?: throw NativeFunctionException("__native_map_filter", "expects 'm' as Map")
            val p = record.fields["p"] ?: throw NativeFunctionException("__native_map_filter", "expects 'p' predicate")

            // Use Vavr's filter directly - no copy needed
            val filteredMap = m.entries.filter { tuple ->
                val entry = MezzeRecord(mapOf("k" to tuple._1, "v" to tuple._2))
                val result = when (p) {
                    is NativeMezzeFunction -> p.call(listOf(entry))
                    is MezzeTruffleFunction -> p.callTarget.call(entry, p.closureVariables, p.lexicalScope, p.lexicalScopeSlotMap) as MezzeValue
                    else -> throw NativeFunctionException("__native_map_filter", "unsupported function type: ${p::class.simpleName}")
                }
                (result as MezzeBool).value
            }

            MezzeMap(filteredMap)
        }

        // Fold
        context["__native_map_fold"] = NativeMezzeFunction("__native_map_fold") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeMap ?: throw NativeFunctionException("__native_map_fold", "expects 'm' as Map")
            val init = record.fields["init"] ?: throw NativeFunctionException("__native_map_fold", "expects 'init' value")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_map_fold", "expects 'f' function")

            // Use Vavr's foldLeft directly - no copy needed
            m.entries.foldLeft(init) { accumulator, tuple ->
                val callArg = MezzeRecord(mapOf("acc" to accumulator, "k" to tuple._1, "v" to tuple._2))
                when (f) {
                    is NativeMezzeFunction -> f.call(listOf(callArg))
                    is MezzeTruffleFunction -> f.callTarget.call(callArg, f.closureVariables, f.lexicalScope, f.lexicalScopeSlotMap) as MezzeValue
                    else -> throw NativeFunctionException("__native_map_fold", "unsupported function type: ${f::class.simpleName}")
                }
            }
        }

        // From keys
        context["__native_map_from_keys"] = NativeMezzeFunction("__native_map_from_keys") { args ->
            val record = args[0] as MezzeRecord
            val ks = record.fields["ks"] as? MezzeList ?: throw NativeFunctionException("__native_map_from_keys", "expects 'ks' as List")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_map_from_keys", "expects 'f' function")

            val entries = ks.toKotlinList().associateWith { k ->
                val arg = if (k is MezzeRecord) k else MezzeRecord(mapOf("val" to k))
                when (f) {
                    is NativeMezzeFunction -> f.call(listOf(k))
                    is MezzeTruffleFunction -> f.callTarget.call(arg, f.closureVariables, f.lexicalScope, f.lexicalScopeSlotMap) as MezzeValue
                    else -> throw NativeFunctionException("__native_map_from_keys", "unsupported function type: ${f::class.simpleName}")
                }
            }

            MezzeMap(entries)
        }

        // Merge (right overwrites left) - uses Vavr merge
        context["__native_map_merge"] = NativeMezzeFunction("__native_map_merge") { args ->
            val record = args[0] as MezzeRecord
            val m1 = record.fields["m1"] as? MezzeMap ?: throw NativeFunctionException("__native_map_merge", "expects 'm1' as Map")
            val m2 = record.fields["m2"] as? MezzeMap ?: throw NativeFunctionException("__native_map_merge", "expects 'm2' as Map")

            // Vavr merge - right overwrites left
            MezzeMap(m1.entries.merge(m2.entries))
        }

        // Merge with function
        context["__native_map_merge_with"] = NativeMezzeFunction("__native_map_merge_with") { args ->
            val record = args[0] as MezzeRecord
            val m1 = record.fields["m1"] as? MezzeMap ?: throw NativeFunctionException("__native_map_merge_with", "expects 'm1' as Map")
            val m2 = record.fields["m2"] as? MezzeMap ?: throw NativeFunctionException("__native_map_merge_with", "expects 'm2' as Map")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_map_merge_with", "expects 'f' function")

            // Merge with conflict resolution - use field names that match stdlib
            val merged = m1.entries.merge(m2.entries) { v1, v2 ->
                val callArg = MezzeRecord(mapOf("l" to v1, "r" to v2))
                when (f) {
                    is NativeMezzeFunction -> f.call(listOf(callArg))
                    is MezzeTruffleFunction -> f.callTarget.call(callArg, f.closureVariables, f.lexicalScope, f.lexicalScopeSlotMap) as MezzeValue
                    else -> throw NativeFunctionException("__native_map_merge_with", "unsupported function type: ${f::class.simpleName}")
                }
            }

            MezzeMap(merged)
        }

        // Remove all keys
        context["__native_map_remove_all"] = NativeMezzeFunction("__native_map_remove_all") { args ->
            val record = args[0] as MezzeRecord
            val m = record.fields["m"] as? MezzeMap ?: throw NativeFunctionException("__native_map_remove_all", "expects 'm' as Map")
            val ks = record.fields["ks"] as? MezzeList ?: throw NativeFunctionException("__native_map_remove_all", "expects 'ks' as List")

            var result = m.entries
            for (k in ks.toKotlinList()) {
                result = result.remove(k)
            }

            MezzeMap(result)
        }

        // Equality check
        context["__native_map_equals"] = NativeMezzeFunction("__native_map_equals") { args ->
            val record = args[0] as MezzeRecord
            val m1 = record.fields["m1"] as? MezzeMap ?: throw NativeFunctionException("__native_map_equals", "expects 'm1' as Map")
            val m2 = record.fields["m2"] as? MezzeMap ?: throw NativeFunctionException("__native_map_equals", "expects 'm2' as Map")

            // Two maps are equal if they have the same size and all key-value pairs match
            // Use Vavr's forAll directly - no copy needed
            if (m1.size() != m2.size()) {
                MezzeBool(false)
            } else {
                val allMatch = m1.entries.forAll { tuple ->
                    val v2 = m2.get(tuple._1)
                    v2 != null && tuple._2 == v2
                }
                MezzeBool(allMatch)
            }
        }
    }
}
