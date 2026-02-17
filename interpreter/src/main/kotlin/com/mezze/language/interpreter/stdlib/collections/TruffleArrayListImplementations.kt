package com.mezze.language.interpreter.stdlib.collections

import com.mezze.language.interpreter.*

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

    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Basic ArrayList operations
        context["__native_arraylist_empty"] = NativeMezzeFunction("__native_arraylist_empty") { args ->
            MezzeArrayList.empty()
        }

        context["__native_arraylist_length"] = NativeMezzeFunction("__native_arraylist_length") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_length", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_length", "expects 'xs' as ArrayList")
            MezzeInt(xs.size().toLong())
        }

        context["__native_arraylist_get"] = NativeMezzeFunction("__native_arraylist_get") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_get", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_get", "expects 'xs' as ArrayList")
            val i = (record.fields["i"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_arraylist_get", "expects 'i' as Int")

            // Return Option - Some if index is valid, None otherwise
            if (i < 0 || i >= xs.size()) {
                MezzeVariant("None", null)
            } else {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to xs.get(i))))
            }
        }

        context["__native_arraylist_set"] = NativeMezzeFunction("__native_arraylist_set") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_set", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_set", "expects 'xs' as ArrayList")
            val i = (record.fields["i"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_arraylist_set", "expects 'i' as Int")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_arraylist_set", "expects 'x' field")

            // Return Option - Some if index is valid, None otherwise
            if (i < 0 || i >= xs.size()) {
                MezzeVariant("None", null)
            } else {
                // O(1) update via Vavr Vector - returns new array with structural sharing
                MezzeVariant("Some", MezzeRecord(mapOf("val" to xs.set(i, x))))
            }
        }

        context["__native_arraylist_add"] = NativeMezzeFunction("__native_arraylist_add") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_add", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_add", "expects 'xs' as ArrayList")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_arraylist_add", "expects 'x' field")
            // O(1) append via Vavr Vector - returns new array with structural sharing
            xs.add(x)
        }

        context["__native_arraylist_remove"] = NativeMezzeFunction("__native_arraylist_remove") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_remove", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_remove", "expects 'xs' as ArrayList")
            val i = (record.fields["i"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_arraylist_remove", "expects 'i' as Int")

            // Return Option - Some if index is valid, None otherwise
            if (i < 0 || i >= xs.size()) {
                MezzeVariant("None", null)
            } else {
                // O(1) remove via Vavr Vector - returns new array with structural sharing
                MezzeVariant("Some", MezzeRecord(mapOf("val" to xs.removeAt(i))))
            }
        }

        context["__native_arraylist_concat"] = NativeMezzeFunction("__native_arraylist_concat") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_concat", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_concat", "expects 'xs' as ArrayList")
            val ys = record.fields["ys"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_concat", "expects 'ys' as ArrayList")
            // O(m) concat via Vavr Vector where m is size of ys
            xs.concat(ys)
        }

        context["__native_arraylist_is_empty"] = NativeMezzeFunction("__native_arraylist_is_empty") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_is_empty", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_is_empty", "expects 'xs' as ArrayList")
            MezzeBool(xs.isEmpty())
        }

        // Higher-order functions
        context["__native_arraylist_map"] = NativeMezzeFunction("__native_arraylist_map") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_map", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_map", "expects 'xs' as ArrayList")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_arraylist_map", "expects 'f' as Function")

            xs.map { element ->
                val argRecord = MezzeRecord(mapOf("x" to element))
                when (f) {
                    is MezzeTruffleFunction -> {
                        // CRITICAL: MezzeFunctionRootNode expects 4 arguments:
                        // [0] = args record, [1] = closure variables map (legacy),
                        // [2] = MaterializedFrame (lexical scope), [3] = slot mapping
                        f.callTarget.call(
                            argRecord,
                            f.closureVariables,
                            f.lexicalScope,
                            f.lexicalScopeSlotMap
                        ) as MezzeValue
                    }
                    is NativeMezzeFunction -> {
                        f.call(listOf(argRecord))
                    }
                    else -> throw NativeFunctionException(
                        "__native_arraylist_map",
                        "f must be a function, got: ${f::class.simpleName}"
                    )
                }
            }
        }

        context["__native_arraylist_filter"] = NativeMezzeFunction("__native_arraylist_filter") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_filter", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_filter", "expects 'xs' as ArrayList")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_arraylist_filter", "expects 'p' as Function")

            xs.filter { element ->
                val argRecord = MezzeRecord(mapOf("x" to element))
                val result = when (p) {
                    is MezzeTruffleFunction -> {
                        // CRITICAL: MezzeFunctionRootNode expects 4 arguments
                        p.callTarget.call(
                            argRecord,
                            p.closureVariables,
                            p.lexicalScope,
                            p.lexicalScopeSlotMap
                        ) as MezzeValue
                    }
                    is NativeMezzeFunction -> {
                        p.call(listOf(argRecord))
                    }
                    else -> throw NativeFunctionException(
                        "__native_arraylist_filter",
                        "p must be a function, got: ${p::class.simpleName}"
                    )
                }
                (result as? MezzeBool)?.value ?: false
            }
        }

        context["__native_arraylist_fold"] = NativeMezzeFunction("__native_arraylist_fold") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_fold", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_fold", "expects 'xs' as ArrayList")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_arraylist_fold", "expects 'init' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_arraylist_fold", "expects 'f' as Function")

            xs.fold(init) { acc, element ->
                val argRecord = MezzeRecord(mapOf("acc" to acc, "x" to element))
                when (f) {
                    is MezzeTruffleFunction -> {
                        // CRITICAL: MezzeFunctionRootNode expects 4 arguments
                        f.callTarget.call(
                            argRecord,
                            f.closureVariables,
                            f.lexicalScope,
                            f.lexicalScopeSlotMap
                        ) as MezzeValue
                    }
                    is NativeMezzeFunction -> {
                        f.call(listOf(argRecord))
                    }
                    else -> throw NativeFunctionException(
                        "__native_arraylist_fold",
                        "f must be a function, got: ${f::class.simpleName}"
                    )
                }
            }
        }

        context["__native_arraylist_reduce"] = NativeMezzeFunction("__native_arraylist_reduce") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_reduce", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_reduce", "expects 'xs' as ArrayList")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_arraylist_reduce", "expects 'init' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_arraylist_reduce", "expects 'f' as Function")

            xs.fold(init) { acc, element ->
                val argRecord = MezzeRecord(mapOf("a" to acc, "b" to element))
                when (f) {
                    is MezzeTruffleFunction -> {
                        // CRITICAL: MezzeFunctionRootNode expects 4 arguments
                        f.callTarget.call(
                            argRecord,
                            f.closureVariables,
                            f.lexicalScope,
                            f.lexicalScopeSlotMap
                        ) as MezzeValue
                    }
                    is NativeMezzeFunction -> {
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
        context["__native_arraylist_find"] = NativeMezzeFunction("__native_arraylist_find") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_find", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_find", "expects 'xs' as ArrayList")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_arraylist_find", "expects 'p' as Function")

            val found = xs.find { element ->
                val argRecord = MezzeRecord(mapOf("x" to element))
                val result = when (p) {
                    is MezzeTruffleFunction -> {
                        // CRITICAL: MezzeFunctionRootNode expects 4 arguments
                        p.callTarget.call(
                            argRecord,
                            p.closureVariables,
                            p.lexicalScope,
                            p.lexicalScopeSlotMap
                        ) as MezzeValue
                    }
                    is NativeMezzeFunction -> {
                        p.call(listOf(argRecord))
                    }
                    else -> throw NativeFunctionException(
                        "__native_arraylist_find",
                        "p must be a function, got: ${p::class.simpleName}"
                    )
                }
                (result as? MezzeBool)?.value ?: false
            }

            if (found != null) {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to found)))
            } else {
                MezzeVariant("None", null)
            }
        }

        context["__native_arraylist_contains"] = NativeMezzeFunction("__native_arraylist_contains") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_contains", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_contains", "expects 'xs' as ArrayList")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_arraylist_contains", "expects 'x' field")
            MezzeBool(xs.contains(x))
        }

        context["__native_arraylist_index_of"] = NativeMezzeFunction("__native_arraylist_index_of") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_index_of", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_index_of", "expects 'xs' as ArrayList")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_arraylist_index_of", "expects 'x' field")
            val index = xs.indexOf(x)
            if (index >= 0) {
                MezzeVariant("Some", MezzeInt(index.toLong()))
            } else {
                MezzeVariant("None", null)
            }
        }

        context["__native_arraylist_reverse"] = NativeMezzeFunction("__native_arraylist_reverse") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_reverse", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_reverse", "expects 'xs' as ArrayList")
            xs.reverse()
        }

        context["__native_arraylist_sort"] = NativeMezzeFunction("__native_arraylist_sort") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_sort", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_sort", "expects 'xs' as ArrayList")
            // Note: This assumes elements are comparable (Int, Float, String, etc.)
            // Convert to list, sort, convert back to Vavr Vector
            MezzeArrayList(xs.toList().sortedWith(compareBy { it.toString() }))
        }

        // Equality check
        context["__native_arraylist_equals"] = NativeMezzeFunction("__native_arraylist_equals") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_equals", "expects MezzeRecord")
            val l = record.fields["l"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_equals", "expects 'l' as ArrayList")
            val r = record.fields["r"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_equals", "expects 'r' as ArrayList")

            if (l.size() != r.size()) {
                MezzeBool(false)
            } else {
                val lList = l.toList()
                val rList = r.toList()
                MezzeBool(lList.indices.all { i -> compareValuesDeep(lList[i], rList[i]) })
            }
        }

        context["__native_arraylist_sort_by"] = NativeMezzeFunction("__native_arraylist_sort_by") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_sort_by", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_sort_by", "expects 'xs' as ArrayList")
            val cmp = record.fields["cmp"]
                ?: throw NativeFunctionException("__native_arraylist_sort_by", "expects 'cmp' as Function")

            val sorted = xs.toList().sortedWith { a, b ->
                val argRecord = MezzeRecord(mapOf("l" to a, "r" to b))
                val result = when (cmp) {
                    is MezzeTruffleFunction -> {
                        // CRITICAL: MezzeFunctionRootNode expects 4 arguments
                        cmp.callTarget.call(
                            argRecord,
                            cmp.closureVariables,
                            cmp.lexicalScope,
                            cmp.lexicalScopeSlotMap
                        ) as MezzeValue
                    }
                    is NativeMezzeFunction -> {
                        cmp.call(listOf(argRecord))
                    }
                    else -> throw NativeFunctionException(
                        "__native_arraylist_sort_by",
                        "cmp must be a function, got: ${cmp::class.simpleName}"
                    )
                }
                ((result as? MezzeInt)?.value ?: 0L).toInt()
            }
            MezzeArrayList(sorted)
        }

        context["__native_arraylist_slice"] = NativeMezzeFunction("__native_arraylist_slice") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_arraylist_slice", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_arraylist_slice", "expects 'xs' as ArrayList")
            val start = (record.fields["start"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_arraylist_slice", "expects 'start' as Int")
            val end = (record.fields["end"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_arraylist_slice", "expects 'end' as Int")

            val safeStart = maxOf(0, minOf(start, xs.size()))
            val safeEnd = maxOf(0, minOf(end, xs.size()))

            if (safeStart > safeEnd) {
                MezzeArrayList.empty()
            } else {
                xs.slice(safeStart, safeEnd)
            }
        }
    }

    /**
     * Compare two MezzeValues for deep equality
     */
    private fun compareValuesDeep(a: MezzeValue, b: MezzeValue): Boolean {
        return when {
            a is MezzeInt && b is MezzeInt -> a.value == b.value
            a is MezzeFloat && b is MezzeFloat -> a.value == b.value
            a is MezzeString && b is MezzeString -> a.value == b.value
            a is MezzeBool && b is MezzeBool -> a.value == b.value
            a is MezzeChar && b is MezzeChar -> a.value == b.value
            a is MezzeNull && b is MezzeNull -> true
            a is MezzeNil && b is MezzeNil -> true
            a is MezzeCons && b is MezzeCons ->
                compareValuesDeep(a.head, b.head) && compareValuesDeep(a.tail, b.tail)
            a is MezzeArrayList && b is MezzeArrayList -> {
                if (a.size() != b.size()) false
                else {
                    val aList = a.toList()
                    val bList = b.toList()
                    aList.indices.all { i -> compareValuesDeep(aList[i], bList[i]) }
                }
            }
            a is MezzeRecord && b is MezzeRecord -> {
                a.fields.keys == b.fields.keys &&
                    a.fields.all { (k, v) -> compareValuesDeep(v, b.fields[k]!!) }
            }
            a is MezzeVariant && b is MezzeVariant -> {
                a.constructor == b.constructor &&
                    ((a.value == null && b.value == null) ||
                        (a.value != null && b.value != null && compareValuesDeep(a.value, b.value)))
            }
            else -> a == b
        }
    }
}
