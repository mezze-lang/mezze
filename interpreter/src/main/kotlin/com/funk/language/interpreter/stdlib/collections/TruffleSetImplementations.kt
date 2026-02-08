package com.funk.language.interpreter.stdlib.collections

import com.funk.language.interpreter.*

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

    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Create empty set
        context["__native_set_empty"] = NativeFunkFunction("__native_set_empty") { args ->
            FunkSet.empty()
        }

        // Insert element - O(1)
        context["__native_set_insert"] = NativeFunkFunction("__native_set_insert") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_insert", "expects 's' as Set")
            val x = record.fields["x"] ?: throw NativeFunctionException("__native_set_insert", "expects 'x' element")

            s.add(x)
        }

        // Remove element - O(1)
        context["__native_set_remove"] = NativeFunkFunction("__native_set_remove") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_remove", "expects 's' as Set")
            val x = record.fields["x"] ?: throw NativeFunctionException("__native_set_remove", "expects 'x' element")

            s.remove(x)
        }

        // Check if element exists - O(1)
        context["__native_set_contains"] = NativeFunkFunction("__native_set_contains") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_contains", "expects 's' as Set")
            val x = record.fields["x"] ?: throw NativeFunctionException("__native_set_contains", "expects 'x' element")

            FunkBool(s.contains(x))
        }

        // Get size
        context["__native_set_size"] = NativeFunkFunction("__native_set_size") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_size", "expects 's' as Set")

            FunkInt(s.size().toLong())
        }

        // Union
        context["__native_set_union"] = NativeFunkFunction("__native_set_union") { args ->
            val record = args[0] as FunkRecord
            val s1 = record.fields["s1"] as? FunkSet ?: throw NativeFunctionException("__native_set_union", "expects 's1' as Set")
            val s2 = record.fields["s2"] as? FunkSet ?: throw NativeFunctionException("__native_set_union", "expects 's2' as Set")

            s1.union(s2)
        }

        // Intersection
        context["__native_set_intersection"] = NativeFunkFunction("__native_set_intersection") { args ->
            val record = args[0] as FunkRecord
            val s1 = record.fields["s1"] as? FunkSet ?: throw NativeFunctionException("__native_set_intersection", "expects 's1' as Set")
            val s2 = record.fields["s2"] as? FunkSet ?: throw NativeFunctionException("__native_set_intersection", "expects 's2' as Set")

            s1.intersect(s2)
        }

        // Difference
        context["__native_set_difference"] = NativeFunkFunction("__native_set_difference") { args ->
            val record = args[0] as FunkRecord
            val s1 = record.fields["s1"] as? FunkSet ?: throw NativeFunctionException("__native_set_difference", "expects 's1' as Set")
            val s2 = record.fields["s2"] as? FunkSet ?: throw NativeFunctionException("__native_set_difference", "expects 's2' as Set")

            s1.difference(s2)
        }

        // Is subset
        context["__native_set_is_subset"] = NativeFunkFunction("__native_set_is_subset") { args ->
            val record = args[0] as FunkRecord
            val s1 = record.fields["s1"] as? FunkSet ?: throw NativeFunctionException("__native_set_is_subset", "expects 's1' as Set")
            val s2 = record.fields["s2"] as? FunkSet ?: throw NativeFunctionException("__native_set_is_subset", "expects 's2' as Set")

            // s1 is subset of s2 if all elements of s1 are in s2
            FunkBool(s1.elements.forAll { s2.elements.contains(it) })
        }

        // Convert to list
        context["__native_set_to_list"] = NativeFunkFunction("__native_set_to_list") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_to_list", "expects 's' as Set")

            FunkList.fromKotlinList(s.toList())
        }

        // Convert from list
        context["__native_set_from_list"] = NativeFunkFunction("__native_set_from_list") { args ->
            val record = args[0] as FunkRecord
            val xs = record.fields["xs"] as? FunkList ?: throw NativeFunctionException("__native_set_from_list", "expects 'xs' as List")

            FunkSet(xs.toKotlinList())
        }

        // Filter
        context["__native_set_filter"] = NativeFunkFunction("__native_set_filter") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_filter", "expects 's' as Set")
            val p = record.fields["p"] ?: throw NativeFunctionException("__native_set_filter", "expects 'p' predicate")

            val filtered = s.toList().filter { element ->
                val arg = if (element is FunkRecord) element else FunkRecord(mapOf("val" to element))
                val result = when (p) {
                    is NativeFunkFunction -> p.call(listOf(element))
                    is FunkTruffleFunction -> p.callTarget.call(arg, p.closureVariables, p.lexicalScope, p.lexicalScopeSlotMap) as FunkValue
                    else -> throw NativeFunctionException("__native_set_filter", "unsupported function type: ${p::class.simpleName}")
                }
                (result as FunkBool).value
            }

            FunkSet(filtered)
        }

        // Map
        context["__native_set_map"] = NativeFunkFunction("__native_set_map") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_map", "expects 's' as Set")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_set_map", "expects 'f' function")

            val mapped = s.toList().map { element ->
                val arg = if (element is FunkRecord) element else FunkRecord(mapOf("val" to element))
                when (f) {
                    is NativeFunkFunction -> f.call(listOf(element))
                    is FunkTruffleFunction -> f.callTarget.call(arg, f.closureVariables, f.lexicalScope, f.lexicalScopeSlotMap) as FunkValue
                    else -> throw NativeFunctionException("__native_set_map", "unsupported function type: ${f::class.simpleName}")
                }
            }

            FunkSet(mapped)
        }

        // Fold
        context["__native_set_fold"] = NativeFunkFunction("__native_set_fold") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_fold", "expects 's' as Set")
            val init = record.fields["init"] ?: throw NativeFunctionException("__native_set_fold", "expects 'init' value")
            val f = record.fields["f"] ?: throw NativeFunctionException("__native_set_fold", "expects 'f' function")

            var accumulator = init
            for (element in s.toList()) {
                val callArg = FunkRecord(mapOf("acc" to accumulator, "elem" to element))
                accumulator = when (f) {
                    is NativeFunkFunction -> f.call(listOf(callArg))
                    is FunkTruffleFunction -> f.callTarget.call(callArg, f.closureVariables, f.lexicalScope, f.lexicalScopeSlotMap) as FunkValue
                    else -> throw NativeFunctionException("__native_set_fold", "unsupported function type: ${f::class.simpleName}")
                }
            }

            accumulator
        }

        // Insert all
        context["__native_set_insert_all"] = NativeFunkFunction("__native_set_insert_all") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_insert_all", "expects 's' as Set")
            val xs = record.fields["xs"] as? FunkList ?: throw NativeFunctionException("__native_set_insert_all", "expects 'xs' as List")

            FunkSet(s.elements.addAll(xs.toKotlinList()))
        }

        // Remove all
        context["__native_set_remove_all"] = NativeFunkFunction("__native_set_remove_all") { args ->
            val record = args[0] as FunkRecord
            val s = record.fields["s"] as? FunkSet ?: throw NativeFunctionException("__native_set_remove_all", "expects 's' as Set")
            val xs = record.fields["xs"] as? FunkList ?: throw NativeFunctionException("__native_set_remove_all", "expects 'xs' as List")

            FunkSet(s.elements.removeAll(xs.toKotlinList()))
        }
    }
}
