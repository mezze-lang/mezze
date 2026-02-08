package com.funk.language.interpreter.stdlib.collections

import com.funk.language.interpreter.*

/**
 * Truffle-optimized implementations for ArrayList operations.
 *
 * Phase 3 of Truffle migration: Collection operations optimized for JIT performance.
 * ArrayList is backed by Vavr Vector for O(1) persistent operations with structural sharing.
 *
 * Performance characteristics (via Vavr Vector's bit-mapped trie):
 * - get: O(1) effectively constant (log32 n)
 * - set: O(1) effectively constant (log32 n), returns new array
 * - append: O(1) effectively constant (log32 n), returns new array
 * - prepend: O(1) effectively constant (log32 n), returns new array
 * - remove: O(1) effectively constant (log32 n), returns new array
 * - slice: O(n) where n is slice size
 * - concat: O(m) where m is size of second array
 *
 * All operations are immutable and return new arrays with structural sharing.
 */
object TruffleArrayListImplementations {

    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Basic ArrayList operations
        context["__native_arraylist_empty"] = NativeFunkFunction("__native_arraylist_empty") { args ->
            FunkArrayList.empty()
        }

        context["__native_arraylist_length"] = NativeFunkFunction("__native_arraylist_length") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_length", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_length", "expects 'xs' as ArrayList")
            FunkInt(xs.size().toLong())
        }

        context["__native_arraylist_get"] = NativeFunkFunction("__native_arraylist_get") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_get", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_get", "expects 'xs' as ArrayList")
            val i = (record.fields["i"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_arraylist_get", "expects 'i' as Int")

            // Return Option - Some if index is valid, None otherwise
            if (i < 0 || i >= xs.size()) {
                FunkVariant("None", null)
            } else {
                FunkVariant("Some", FunkRecord(mapOf("val" to xs.get(i))))
            }
        }

        context["__native_arraylist_set"] = NativeFunkFunction("__native_arraylist_set") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_set", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_set", "expects 'xs' as ArrayList")
            val i = (record.fields["i"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_arraylist_set", "expects 'i' as Int")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_arraylist_set", "expects 'x' field")

            // Return Option - Some if index is valid, None otherwise
            if (i < 0 || i >= xs.size()) {
                FunkVariant("None", null)
            } else {
                // O(1) update via Vavr Vector - returns new array with structural sharing
                FunkVariant("Some", FunkRecord(mapOf("val" to xs.set(i, x))))
            }
        }

        context["__native_arraylist_add"] = NativeFunkFunction("__native_arraylist_add") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_add", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_add", "expects 'xs' as ArrayList")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_arraylist_add", "expects 'x' field")
            // O(1) append via Vavr Vector - returns new array with structural sharing
            xs.add(x)
        }

        context["__native_arraylist_remove"] = NativeFunkFunction("__native_arraylist_remove") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_remove", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_remove", "expects 'xs' as ArrayList")
            val i = (record.fields["i"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_arraylist_remove", "expects 'i' as Int")

            // Return Option - Some if index is valid, None otherwise
            if (i < 0 || i >= xs.size()) {
                FunkVariant("None", null)
            } else {
                // O(1) remove via Vavr Vector - returns new array with structural sharing
                FunkVariant("Some", FunkRecord(mapOf("val" to xs.removeAt(i))))
            }
        }

        context["__native_arraylist_concat"] = NativeFunkFunction("__native_arraylist_concat") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_concat", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_concat", "expects 'xs' as ArrayList")
            val ys = record.fields["ys"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_concat", "expects 'ys' as ArrayList")
            // O(m) concat via Vavr Vector where m is size of ys
            xs.concat(ys)
        }

        context["__native_arraylist_is_empty"] = NativeFunkFunction("__native_arraylist_is_empty") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_is_empty", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_is_empty", "expects 'xs' as ArrayList")
            FunkBool(xs.isEmpty())
        }

        // Higher-order functions
        context["__native_arraylist_map"] = NativeFunkFunction("__native_arraylist_map") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_map", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_map", "expects 'xs' as ArrayList")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_arraylist_map", "expects 'f' as Function")

            xs.map { element ->
                val argRecord = FunkRecord(mapOf("x" to element))
                when (f) {
                    is FunkTruffleFunction -> {
                        // CRITICAL: FunkFunctionRootNode expects 4 arguments:
                        // [0] = args record, [1] = closure variables map (legacy),
                        // [2] = MaterializedFrame (lexical scope), [3] = slot mapping
                        f.callTarget.call(
                            argRecord,
                            f.closureVariables,
                            f.lexicalScope,
                            f.lexicalScopeSlotMap
                        ) as FunkValue
                    }
                    is NativeFunkFunction -> {
                        f.call(listOf(argRecord))
                    }
                    else -> throw NativeFunctionException(
                        "__native_arraylist_map",
                        "f must be a function, got: ${f::class.simpleName}"
                    )
                }
            }
        }

        context["__native_arraylist_filter"] = NativeFunkFunction("__native_arraylist_filter") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_filter", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_filter", "expects 'xs' as ArrayList")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_arraylist_filter", "expects 'p' as Function")

            xs.filter { element ->
                val argRecord = FunkRecord(mapOf("x" to element))
                val result = when (p) {
                    is FunkTruffleFunction -> {
                        // CRITICAL: FunkFunctionRootNode expects 4 arguments
                        p.callTarget.call(
                            argRecord,
                            p.closureVariables,
                            p.lexicalScope,
                            p.lexicalScopeSlotMap
                        ) as FunkValue
                    }
                    is NativeFunkFunction -> {
                        p.call(listOf(argRecord))
                    }
                    else -> throw NativeFunctionException(
                        "__native_arraylist_filter",
                        "p must be a function, got: ${p::class.simpleName}"
                    )
                }
                (result as? FunkBool)?.value ?: false
            }
        }

        context["__native_arraylist_fold"] = NativeFunkFunction("__native_arraylist_fold") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_fold", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_fold", "expects 'xs' as ArrayList")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_arraylist_fold", "expects 'init' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_arraylist_fold", "expects 'f' as Function")

            xs.fold(init) { acc, element ->
                val argRecord = FunkRecord(mapOf("acc" to acc, "x" to element))
                when (f) {
                    is FunkTruffleFunction -> {
                        // CRITICAL: FunkFunctionRootNode expects 4 arguments
                        f.callTarget.call(
                            argRecord,
                            f.closureVariables,
                            f.lexicalScope,
                            f.lexicalScopeSlotMap
                        ) as FunkValue
                    }
                    is NativeFunkFunction -> {
                        f.call(listOf(argRecord))
                    }
                    else -> throw NativeFunctionException(
                        "__native_arraylist_fold",
                        "f must be a function, got: ${f::class.simpleName}"
                    )
                }
            }
        }

        context["__native_arraylist_reduce"] = NativeFunkFunction("__native_arraylist_reduce") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_reduce", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_reduce", "expects 'xs' as ArrayList")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_arraylist_reduce", "expects 'init' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_arraylist_reduce", "expects 'f' as Function")

            xs.fold(init) { acc, element ->
                val argRecord = FunkRecord(mapOf("a" to acc, "b" to element))
                when (f) {
                    is FunkTruffleFunction -> {
                        // CRITICAL: FunkFunctionRootNode expects 4 arguments
                        f.callTarget.call(
                            argRecord,
                            f.closureVariables,
                            f.lexicalScope,
                            f.lexicalScopeSlotMap
                        ) as FunkValue
                    }
                    is NativeFunkFunction -> {
                        f.call(listOf(argRecord))
                    }
                    else -> throw NativeFunctionException(
                        "__native_arraylist_reduce",
                        "f must be a function, got: ${f::class.simpleName}"
                    )
                }
            }
        }

        // Utility functions
        context["__native_arraylist_find"] = NativeFunkFunction("__native_arraylist_find") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_find", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_find", "expects 'xs' as ArrayList")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_arraylist_find", "expects 'p' as Function")

            val found = xs.find { element ->
                val argRecord = FunkRecord(mapOf("x" to element))
                val result = when (p) {
                    is FunkTruffleFunction -> {
                        // CRITICAL: FunkFunctionRootNode expects 4 arguments
                        p.callTarget.call(
                            argRecord,
                            p.closureVariables,
                            p.lexicalScope,
                            p.lexicalScopeSlotMap
                        ) as FunkValue
                    }
                    is NativeFunkFunction -> {
                        p.call(listOf(argRecord))
                    }
                    else -> throw NativeFunctionException(
                        "__native_arraylist_find",
                        "p must be a function, got: ${p::class.simpleName}"
                    )
                }
                (result as? FunkBool)?.value ?: false
            }

            if (found != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to found)))
            } else {
                FunkVariant("None", null)
            }
        }

        context["__native_arraylist_contains"] = NativeFunkFunction("__native_arraylist_contains") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_contains", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_contains", "expects 'xs' as ArrayList")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_arraylist_contains", "expects 'x' field")
            FunkBool(xs.contains(x))
        }

        context["__native_arraylist_index_of"] = NativeFunkFunction("__native_arraylist_index_of") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_index_of", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_index_of", "expects 'xs' as ArrayList")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_arraylist_index_of", "expects 'x' field")
            val index = xs.indexOf(x)
            if (index >= 0) {
                FunkVariant("Some", FunkInt(index.toLong()))
            } else {
                FunkVariant("None", null)
            }
        }

        context["__native_arraylist_reverse"] = NativeFunkFunction("__native_arraylist_reverse") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_reverse", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_reverse", "expects 'xs' as ArrayList")
            xs.reverse()
        }

        context["__native_arraylist_sort"] = NativeFunkFunction("__native_arraylist_sort") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_sort", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_sort", "expects 'xs' as ArrayList")
            // Note: This assumes elements are comparable (Int, Float, String, etc.)
            // Convert to list, sort, convert back to Vavr Vector
            FunkArrayList(xs.toList().sortedWith(compareBy { it.toString() }))
        }

        context["__native_arraylist_sort_by"] = NativeFunkFunction("__native_arraylist_sort_by") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_sort_by", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_sort_by", "expects 'xs' as ArrayList")
            val cmp = record.fields["cmp"]
                ?: throw NativeFunctionException("__native_arraylist_sort_by", "expects 'cmp' as Function")

            val sorted = xs.toList().sortedWith { a, b ->
                val argRecord = FunkRecord(mapOf("l" to a, "r" to b))
                val result = when (cmp) {
                    is FunkTruffleFunction -> {
                        // CRITICAL: FunkFunctionRootNode expects 4 arguments
                        cmp.callTarget.call(
                            argRecord,
                            cmp.closureVariables,
                            cmp.lexicalScope,
                            cmp.lexicalScopeSlotMap
                        ) as FunkValue
                    }
                    is NativeFunkFunction -> {
                        cmp.call(listOf(argRecord))
                    }
                    else -> throw NativeFunctionException(
                        "__native_arraylist_sort_by",
                        "cmp must be a function, got: ${cmp::class.simpleName}"
                    )
                }
                ((result as? FunkInt)?.value ?: 0L).toInt()
            }
            FunkArrayList(sorted)
        }

        context["__native_arraylist_slice"] = NativeFunkFunction("__native_arraylist_slice") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_slice", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_slice", "expects 'xs' as ArrayList")
            val start = (record.fields["start"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_arraylist_slice", "expects 'start' as Int")
            val end = (record.fields["end"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_arraylist_slice", "expects 'end' as Int")

            val safeStart = maxOf(0, minOf(start, xs.size()))
            val safeEnd = maxOf(0, minOf(end, xs.size()))

            if (safeStart > safeEnd) {
                FunkArrayList.empty()
            } else {
                xs.slice(safeStart, safeEnd)
            }
        }
    }
}
