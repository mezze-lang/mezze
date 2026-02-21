package com.mezze.language.interpreter.stdlib.collections

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for Set operations.
 *
 * Phase 3 of Truffle migration: Collection operations optimized for JIT performance.
 * Set is backed by Vavr HashSet for O(1) persistent operations with structural sharing.
 *
 * Performance characteristics (via Vavr HashSet's HAMT):
 * - add: O(1) effectively constant, returns new set
 * - remove: O(1) effectively constant, returns new set
 * - contains: O(1) effectively constant
 *
 * All operations are immutable and return new sets with structural sharing.
 */
object TruffleSetImplementations {

    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Create empty set
        context["__native_set_empty"] = NativeMezzeFunction("__native_set_empty") { args ->
            MezzeSet.empty()
        }

        // Insert element - O(1)
        context["__native_set_insert"] = NativeMezzeFunction("__native_set_insert") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSet ?: throw NativeFunctionException("__native_set_insert", "expects 's' as Set")
            val x = record.fields["x"] ?: throw NativeFunctionException("__native_set_insert", "expects 'x' element")

            s.add(x)
        }

        // Remove element - O(1)
        context["__native_set_remove"] = NativeMezzeFunction("__native_set_remove") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSet ?: throw NativeFunctionException("__native_set_remove", "expects 's' as Set")
            val x = record.fields["x"] ?: throw NativeFunctionException("__native_set_remove", "expects 'x' element")

            s.remove(x)
        }

        // Check if element exists - O(1)
        context["__native_set_contains"] = NativeMezzeFunction("__native_set_contains") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSet ?: throw NativeFunctionException("__native_set_contains", "expects 's' as Set")
            val x = record.fields["x"] ?: throw NativeFunctionException("__native_set_contains", "expects 'x' element")

            MezzeBool(s.contains(x))
        }

        // Get size
        context["__native_set_size"] = NativeMezzeFunction("__native_set_size") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSet ?: throw NativeFunctionException("__native_set_size", "expects 's' as Set")

            MezzeInt(s.size().toLong())
        }

        // Union
        context["__native_set_union"] = NativeMezzeFunction("__native_set_union") { args ->
            val record = args[0] as MezzeRecord
            val s1 = record.fields["s1"] as? MezzeSet ?: throw NativeFunctionException("__native_set_union", "expects 's1' as Set")
            val s2 = record.fields["s2"] as? MezzeSet ?: throw NativeFunctionException("__native_set_union", "expects 's2' as Set")

            s1.union(s2)
        }

        // Intersection
        context["__native_set_intersection"] = NativeMezzeFunction("__native_set_intersection") { args ->
            val record = args[0] as MezzeRecord
            val s1 = record.fields["s1"] as? MezzeSet ?: throw NativeFunctionException("__native_set_intersection", "expects 's1' as Set")
            val s2 = record.fields["s2"] as? MezzeSet ?: throw NativeFunctionException("__native_set_intersection", "expects 's2' as Set")

            s1.intersect(s2)
        }

        // Difference
        context["__native_set_difference"] = NativeMezzeFunction("__native_set_difference") { args ->
            val record = args[0] as MezzeRecord
            val s1 = record.fields["s1"] as? MezzeSet ?: throw NativeFunctionException("__native_set_difference", "expects 's1' as Set")
            val s2 = record.fields["s2"] as? MezzeSet ?: throw NativeFunctionException("__native_set_difference", "expects 's2' as Set")

            s1.difference(s2)
        }

        // Is subset
        context["__native_set_is_subset"] = NativeMezzeFunction("__native_set_is_subset") { args ->
            val record = args[0] as MezzeRecord
            val s1 = record.fields["s1"] as? MezzeSet ?: throw NativeFunctionException("__native_set_is_subset", "expects 's1' as Set")
            val s2 = record.fields["s2"] as? MezzeSet ?: throw NativeFunctionException("__native_set_is_subset", "expects 's2' as Set")

            // s1 is subset of s2 if all elements of s1 are in s2
            MezzeBool(s1.elements.forAll { s2.elements.contains(it) })
        }

        // Convert to list
        context["__native_set_to_list"] = NativeMezzeFunction("__native_set_to_list") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSet ?: throw NativeFunctionException("__native_set_to_list", "expects 's' as Set")

            MezzeList.fromKotlinList(s.toList())
        }

        // Convert from list
        context["__native_set_from_list"] = NativeMezzeFunction("__native_set_from_list") { args ->
            val record = args[0] as MezzeRecord
            val xs = record.fields["xs"] as? MezzeList ?: throw NativeFunctionException("__native_set_from_list", "expects 'xs' as List")

            MezzeSet(xs.toKotlinList())
        }

        // Convert to array
        context["__native_set_to_array"] = NativeMezzeFunction("__native_set_to_array") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSet ?: throw NativeFunctionException("__native_set_to_array", "expects 's' as Set")

            MezzeArrayList(s.toList())
        }

        // Convert from array
        context["__native_set_from_array"] = NativeMezzeFunction("__native_set_from_array") { args ->
            val record = args[0] as MezzeRecord
            val xs = record.fields["xs"] as? MezzeArrayList ?: throw NativeFunctionException("__native_set_from_array", "expects 'xs' as Array")

            MezzeSet(xs.elements.toJavaList())
        }

        // Filter
        context["__native_set_filter"] = NativeMezzeFunction("__native_set_filter") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSet ?: throw NativeFunctionException("__native_set_filter", "expects 's' as Set")
            val p = record.fields["p"] ?: throw NativeFunctionException("__native_set_filter", "expects 'p' predicate")

            // Use Vavr's filter directly - no toList() copy needed
            val filtered = s.elements.filter { element ->
                val arg = if (element is MezzeRecord) element else MezzeRecord(mapOf("val" to element))
                val result = when (p) {
                    is NativeMezzeFunction -> p.call(listOf(arg))
                    is MezzeTruffleFunction -> p.callTarget.call(arg, p.closureVariables, p.lexicalScope, p.lexicalScopeSlotMap) as MezzeValue
                    else -> throw NativeFunctionException("__native_set_filter", "unsupported function type: ${p::class.simpleName}")
                }
                (result as MezzeBool).value
            }

            MezzeSet(filtered)
        }

        // Map
        context["__native_set_map"] = NativeMezzeFunction("__native_set_map") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSet ?: throw NativeFunctionException("__native_set_map", "expects 's' as Set")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_set_map", "expects 'f' function")

            // Use Vavr's map directly - no toList() copy needed
            val mapped = s.elements.map { element ->
                val arg = if (element is MezzeRecord) element else MezzeRecord(mapOf("val" to element))
                when (f) {
                    is NativeMezzeFunction -> f.call(listOf(arg))
                    is MezzeTruffleFunction -> f.callTarget.call(arg, f.closureVariables, f.lexicalScope, f.lexicalScopeSlotMap) as MezzeValue
                    else -> throw NativeFunctionException("__native_set_map", "unsupported function type: ${f::class.simpleName}")
                }
            }

            MezzeSet(io.vavr.collection.HashSet.ofAll(mapped))
        }

        // Fold
        context["__native_set_fold"] = NativeMezzeFunction("__native_set_fold") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSet ?: throw NativeFunctionException("__native_set_fold", "expects 's' as Set")
            val init = record.fields["init"] ?: throw NativeFunctionException("__native_set_fold", "expects 'init' value")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_set_fold", "expects 'f' function")

            // Use Vavr's foldLeft directly - no toList() copy needed
            s.elements.foldLeft(init) { accumulator, element ->
                val callArg = MezzeRecord(mapOf("acc" to accumulator, "x" to element))
                when (f) {
                    is NativeMezzeFunction -> f.call(listOf(callArg))
                    is MezzeTruffleFunction -> f.callTarget.call(callArg, f.closureVariables, f.lexicalScope, f.lexicalScopeSlotMap) as MezzeValue
                    else -> throw NativeFunctionException("__native_set_fold", "unsupported function type: ${f::class.simpleName}")
                }
            }
        }

        // Insert all
        context["__native_set_insert_all"] = NativeMezzeFunction("__native_set_insert_all") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSet ?: throw NativeFunctionException("__native_set_insert_all", "expects 's' as Set")
            val xs = record.fields["xs"] as? MezzeArrayList ?: throw NativeFunctionException("__native_set_insert_all", "expects 'xs' as Array")

            MezzeSet(s.elements.addAll(xs.elements.toJavaList()))
        }

        // Remove all
        context["__native_set_remove_all"] = NativeMezzeFunction("__native_set_remove_all") { args ->
            val record = args[0] as MezzeRecord
            val s = record.fields["s"] as? MezzeSet ?: throw NativeFunctionException("__native_set_remove_all", "expects 's' as Set")
            val xs = record.fields["xs"] as? MezzeArrayList ?: throw NativeFunctionException("__native_set_remove_all", "expects 'xs' as Array")

            MezzeSet(s.elements.removeAll(xs.elements.toJavaList()))
        }

        // Equality check
        context["__native_set_equals"] = NativeMezzeFunction("__native_set_equals") { args ->
            val record = args[0] as MezzeRecord
            val s1 = record.fields["s1"] as? MezzeSet ?: throw NativeFunctionException("__native_set_equals", "expects 's1' as Set")
            val s2 = record.fields["s2"] as? MezzeSet ?: throw NativeFunctionException("__native_set_equals", "expects 's2' as Set")

            // Two sets are equal if they have the same size and s1 is a subset of s2
            val sameSize = s1.size() == s2.size()
            val isSubset = s1.elements.forAll { s2.elements.contains(it) }
            MezzeBool(sameSize && isSubset)
        }
    }
}
