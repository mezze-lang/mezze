package com.funk.language.interpreter.stdlib.collections

import com.funk.language.interpreter.*

/**
 * Truffle-optimized implementations for List operations.
 *
 * List is an immutable linked list using classic cons cells (FunkCons/FunkNil)
 * for proper O(1) head/tail/cons operations and structural sharing.
 *
 * Performance characteristics:
 * - cons (prepend): O(1)
 * - head: O(1)
 * - tail: O(1)
 * - length: O(n)
 * - index/get: O(n)
 * - append: O(n) where n = length of first list
 */
object TruffleListImplementations {

    /**
     * Helper to call any function type (Truffle or Native)
     */
    private fun callFunction(f: FunkValue, arg: FunkValue, operationName: String): FunkValue {
        return when (f) {
            is FunkTruffleFunction -> {
                f.callTarget.call(
                    arg,
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as FunkValue
            }
            is NativeFunkFunction -> {
                f.implementation(listOf(arg))
            }
            else -> {
                throw NativeFunctionException(operationName, "Expected Truffle function or native function, got: ${f::class.simpleName}")
            }
        }
    }

    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Basic list operations
        context["__native_list_empty"] = NativeFunkFunction("__native_list_empty") { _ ->
            FunkNil
        }

        context["__native_list_cons"] = NativeFunkFunction("__native_list_cons") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_cons", "expects FunkRecord")
            val head = record.fields["head"]
                ?: throw NativeFunctionException("__native_list_cons", "expects 'head' field")
            val tail = record.fields["tail"] as? FunkList
                ?: throw NativeFunctionException("__native_list_cons", "expects 'tail' as List")
            FunkCons(head, tail)
        }

        context["__native_list_is_empty"] = NativeFunkFunction("__native_list_is_empty") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_is_empty", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_is_empty", "expects 'xs' as List")
            FunkBool(xs.isEmpty())
        }

        context["__native_list_head"] = NativeFunkFunction("__native_list_head") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_head", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_head", "expects 'xs' as List")

            val head = xs.headOrNull()
            if (head == null) {
                FunkVariant("None", null)
            } else {
                FunkVariant("Some", FunkRecord(mapOf("val" to head)))
            }
        }

        context["__native_list_tail"] = NativeFunkFunction("__native_list_tail") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_tail", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_tail", "expects 'xs' as List")

            val tail = xs.tailOrNull()
            if (tail == null) {
                FunkVariant("None", null)
            } else {
                FunkVariant("Some", FunkRecord(mapOf("val" to tail)))
            }
        }

        context["__native_list_length"] = NativeFunkFunction("__native_list_length") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_length", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_length", "expects 'xs' as List")
            FunkInt(xs.length().toLong())
        }

        // Deconstruction - uncons returns Option { head: 'a, tail: List 'a }
        context["__native_list_uncons"] = NativeFunkFunction("__native_list_uncons") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_uncons", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_uncons", "expects 'xs' as List")

            when (xs) {
                is FunkNil -> FunkVariant("None", null)
                is FunkCons -> FunkVariant("Some", FunkRecord(mapOf(
                    "val" to FunkRecord(mapOf(
                        "head" to xs.head,
                        "tail" to xs.tail
                    ))
                )))
            }
        }

        // List construction
        context["__native_list_append"] = NativeFunkFunction("__native_list_append") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_append", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_append", "expects 'xs' as List")
            val ys = record.fields["ys"] as? FunkList
                ?: throw NativeFunctionException("__native_list_append", "expects 'ys' as List")

            // O(n) where n = length of xs - rebuild xs with ys as the tail
            fun go(list: FunkList): FunkList = when (list) {
                is FunkNil -> ys
                is FunkCons -> FunkCons(list.head, go(list.tail))
            }
            go(xs)
        }

        // List access
        context["__native_list_get"] = NativeFunkFunction("__native_list_get") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_get", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_get", "expects 'xs' as List")
            val index = record.fields["index"] as? FunkInt
                ?: throw NativeFunctionException("__native_list_get", "expects 'index' as Int")

            var idx = index.value.toInt()
            var current: FunkList = xs
            while (current is FunkCons) {
                if (idx == 0) {
                    return@NativeFunkFunction FunkVariant("Some", FunkRecord(mapOf("val" to current.head)))
                }
                current = current.tail
                idx--
            }
            FunkVariant("None", null)
        }

        context["__native_list_take"] = NativeFunkFunction("__native_list_take") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_take", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_take", "expects 'xs' as List")
            val n = record.fields["n"] as? FunkInt
                ?: throw NativeFunctionException("__native_list_take", "expects 'n' as Int")

            fun go(list: FunkList, remaining: Int): FunkList = when {
                remaining <= 0 -> FunkNil
                list is FunkNil -> FunkNil
                list is FunkCons -> FunkCons(list.head, go(list.tail, remaining - 1))
                else -> FunkNil
            }
            go(xs, n.value.toInt())
        }

        context["__native_list_drop"] = NativeFunkFunction("__native_list_drop") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_drop", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_drop", "expects 'xs' as List")
            val n = record.fields["n"] as? FunkInt
                ?: throw NativeFunctionException("__native_list_drop", "expects 'n' as Int")

            var current: FunkList = xs
            var remaining = n.value.toInt()
            while (remaining > 0 && current is FunkCons) {
                current = current.tail
                remaining--
            }
            current
        }

        // Higher-order functions
        context["__native_list_map"] = NativeFunkFunction("__native_list_map") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_map", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_map", "expects 'xs' as List")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_list_map", "expects 'f' function")

            fun go(list: FunkList): FunkList = when (list) {
                is FunkNil -> FunkNil
                is FunkCons -> {
                    val callArg = FunkRecord(mapOf("x" to list.head))
                    val mapped = callFunction(f, callArg, "__native_list_map")
                    FunkCons(mapped, go(list.tail))
                }
            }
            go(xs)
        }

        context["__native_list_filter"] = NativeFunkFunction("__native_list_filter") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_filter", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_filter", "expects 'xs' as List")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_list_filter", "expects 'p' predicate")

            fun go(list: FunkList): FunkList = when (list) {
                is FunkNil -> FunkNil
                is FunkCons -> {
                    val callArg = FunkRecord(mapOf("x" to list.head))
                    val result = callFunction(p, callArg, "__native_list_filter")
                    val keep = (result as? FunkBool)?.value
                        ?: throw NativeFunctionException("__native_list_filter", "predicate must return Boolean")
                    if (keep) {
                        FunkCons(list.head, go(list.tail))
                    } else {
                        go(list.tail)
                    }
                }
            }
            go(xs)
        }

        context["__native_list_fold_left"] = NativeFunkFunction("__native_list_fold_left") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_fold_left", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_fold_left", "expects 'xs' as List")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_list_fold_left", "expects 'init' value")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_list_fold_left", "expects 'f' function")

            var acc = init
            var current: FunkList = xs
            while (current is FunkCons) {
                val callArg = FunkRecord(mapOf("acc" to acc, "x" to current.head))
                acc = callFunction(f, callArg, "__native_list_fold_left")
                current = current.tail
            }
            acc
        }

        context["__native_list_fold_right"] = NativeFunkFunction("__native_list_fold_right") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_fold_right", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_fold_right", "expects 'xs' as List")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_list_fold_right", "expects 'init' value")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_list_fold_right", "expects 'f' function")

            // Fold right: process from end - use recursion
            fun go(list: FunkList): FunkValue = when (list) {
                is FunkNil -> init
                is FunkCons -> {
                    val tailResult = go(list.tail)
                    val callArg = FunkRecord(mapOf("x" to list.head, "acc" to tailResult))
                    callFunction(f, callArg, "__native_list_fold_right")
                }
            }
            go(xs)
        }

        context["__native_list_reduce"] = NativeFunkFunction("__native_list_reduce") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_reduce", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_reduce", "expects 'xs' as List")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_list_reduce", "expects 'init' value")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_list_reduce", "expects 'f' function")

            var acc = init
            var current: FunkList = xs
            while (current is FunkCons) {
                val callArg = FunkRecord(mapOf("a" to acc, "b" to current.head))
                acc = callFunction(f, callArg, "__native_list_reduce")
                current = current.tail
            }
            acc
        }

        // List predicates
        context["__native_list_any"] = NativeFunkFunction("__native_list_any") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_any", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_any", "expects 'xs' as List")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_list_any", "expects 'p' predicate")

            var current: FunkList = xs
            while (current is FunkCons) {
                val callArg = FunkRecord(mapOf("x" to current.head))
                val result = callFunction(p, callArg, "__native_list_any")
                val matches = (result as? FunkBool)?.value
                    ?: throw NativeFunctionException("__native_list_any", "predicate must return Boolean")
                if (matches) return@NativeFunkFunction FunkBool(true)
                current = current.tail
            }
            FunkBool(false)
        }

        context["__native_list_all"] = NativeFunkFunction("__native_list_all") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_all", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_all", "expects 'xs' as List")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_list_all", "expects 'p' predicate")

            var current: FunkList = xs
            while (current is FunkCons) {
                val callArg = FunkRecord(mapOf("x" to current.head))
                val result = callFunction(p, callArg, "__native_list_all")
                val matches = (result as? FunkBool)?.value
                    ?: throw NativeFunctionException("__native_list_all", "predicate must return Boolean")
                if (!matches) return@NativeFunkFunction FunkBool(false)
                current = current.tail
            }
            FunkBool(true)
        }

        context["__native_list_contains"] = NativeFunkFunction("__native_list_contains") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_contains", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_contains", "expects 'xs' as List")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_list_contains", "expects 'x' value")

            var current: FunkList = xs
            while (current is FunkCons) {
                if (compareValues(current.head, x)) {
                    return@NativeFunkFunction FunkBool(true)
                }
                current = current.tail
            }
            FunkBool(false)
        }

        // List transformations
        context["__native_list_reverse"] = NativeFunkFunction("__native_list_reverse") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_reverse", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_reverse", "expects 'xs' as List")

            // O(n) iterative reverse
            var result: FunkList = FunkNil
            var current: FunkList = xs
            while (current is FunkCons) {
                result = FunkCons(current.head, result)
                current = current.tail
            }
            result
        }

        context["__native_list_concat"] = NativeFunkFunction("__native_list_concat") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_concat", "expects FunkRecord")
            val xss = record.fields["xss"] as? FunkList
                ?: throw NativeFunctionException("__native_list_concat", "expects 'xss' as List")

            // Flatten list of lists
            var result: FunkList = FunkNil
            val lists = mutableListOf<FunkList>()

            // Collect all inner lists
            var current: FunkList = xss
            while (current is FunkCons) {
                val innerList = current.head as? FunkList
                    ?: throw NativeFunctionException("__native_list_concat", "all elements must be Lists")
                lists.add(innerList)
                current = current.tail
            }

            // Append in reverse order for efficiency
            for (i in lists.indices.reversed()) {
                var innerCurrent: FunkList = lists[i]
                val innerElements = mutableListOf<FunkValue>()
                while (innerCurrent is FunkCons) {
                    innerElements.add(innerCurrent.head)
                    innerCurrent = innerCurrent.tail
                }
                for (j in innerElements.indices.reversed()) {
                    result = FunkCons(innerElements[j], result)
                }
            }
            result
        }

        context["__native_list_flat_map"] = NativeFunkFunction("__native_list_flat_map") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_flat_map", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_flat_map", "expects 'xs' as List")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_list_flat_map", "expects 'f' function")

            // Collect all results then flatten
            val results = mutableListOf<FunkList>()
            var current: FunkList = xs
            while (current is FunkCons) {
                val callArg = FunkRecord(mapOf("x" to current.head))
                val result = callFunction(f, callArg, "__native_list_flat_map")
                val innerList = result as? FunkList
                    ?: throw NativeFunctionException("__native_list_flat_map", "f must return List")
                results.add(innerList)
                current = current.tail
            }

            // Flatten
            var finalResult: FunkList = FunkNil
            for (i in results.indices.reversed()) {
                var innerCurrent: FunkList = results[i]
                val innerElements = mutableListOf<FunkValue>()
                while (innerCurrent is FunkCons) {
                    innerElements.add(innerCurrent.head)
                    innerCurrent = innerCurrent.tail
                }
                for (j in innerElements.indices.reversed()) {
                    finalResult = FunkCons(innerElements[j], finalResult)
                }
            }
            finalResult
        }

        context["__native_list_zip"] = NativeFunkFunction("__native_list_zip") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_zip", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_zip", "expects 'xs' as List")
            val ys = record.fields["ys"] as? FunkList
                ?: throw NativeFunctionException("__native_list_zip", "expects 'ys' as List")

            fun go(l1: FunkList, l2: FunkList): FunkList = when {
                l1 is FunkCons && l2 is FunkCons -> {
                    val pair = FunkRecord(mapOf("fst" to l1.head, "snd" to l2.head))
                    FunkCons(pair, go(l1.tail, l2.tail))
                }
                else -> FunkNil
            }
            go(xs, ys)
        }

        context["__native_list_unzip"] = NativeFunkFunction("__native_list_unzip") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_unzip", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_unzip", "expects 'xs' as List")

            val fsts = mutableListOf<FunkValue>()
            val snds = mutableListOf<FunkValue>()

            var current: FunkList = xs
            while (current is FunkCons) {
                val pair = current.head as? FunkRecord
                    ?: throw NativeFunctionException("__native_list_unzip", "all elements must be records")
                val fst = pair.fields["fst"]
                    ?: throw NativeFunctionException("__native_list_unzip", "record must have 'fst' field")
                val snd = pair.fields["snd"]
                    ?: throw NativeFunctionException("__native_list_unzip", "record must have 'snd' field")
                fsts.add(fst)
                snds.add(snd)
                current = current.tail
            }

            FunkRecord(mapOf(
                "fsts" to FunkList.fromKotlinList(fsts),
                "snds" to FunkList.fromKotlinList(snds)
            ))
        }

        // Conversion functions
        context["__native_list_to_array"] = NativeFunkFunction("__native_list_to_array") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_to_array", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_to_array", "expects 'xs' as List")
            FunkArrayList(xs.toKotlinList().toMutableList())
        }

        context["__native_list_from_array"] = NativeFunkFunction("__native_list_from_array") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_from_array", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_list_from_array", "expects 'xs' as Array, got ${record.fields["xs"]?.let { it::class.simpleName } ?: "null"}")
            FunkList.fromKotlinList(xs.toList())
        }

        // List utilities
        context["__native_list_range"] = NativeFunkFunction("__native_list_range") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_range", "expects FunkRecord")
            val start = record.fields["start"] as? FunkInt
                ?: throw NativeFunctionException("__native_list_range", "expects 'start' as Int")
            val end = record.fields["end"] as? FunkInt
                ?: throw NativeFunctionException("__native_list_range", "expects 'end' as Int")

            // Build list in reverse, then it's already correct order
            var result: FunkList = FunkNil
            for (i in (end.value - 1) downTo start.value) {
                result = FunkCons(FunkInt(i), result)
            }
            result
        }

        context["__native_list_repeat"] = NativeFunkFunction("__native_list_repeat") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_repeat", "expects FunkRecord")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_list_repeat", "expects 'x' value")
            val n = record.fields["n"] as? FunkInt
                ?: throw NativeFunctionException("__native_list_repeat", "expects 'n' as Int")

            var result: FunkList = FunkNil
            repeat(n.value.toInt()) {
                result = FunkCons(x, result)
            }
            result
        }

        context["__native_list_intersperse"] = NativeFunkFunction("__native_list_intersperse") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_intersperse", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_intersperse", "expects 'xs' as List")
            val sep = record.fields["sep"]
                ?: throw NativeFunctionException("__native_list_intersperse", "expects 'sep' value")

            fun go(list: FunkList): FunkList = when (list) {
                is FunkNil -> FunkNil
                is FunkCons -> when (list.tail) {
                    is FunkNil -> FunkCons(list.head, FunkNil)
                    is FunkCons -> FunkCons(list.head, FunkCons(sep, go(list.tail)))
                }
            }
            go(xs)
        }

        // Sorting - convert to Kotlin list, sort, convert back
        context["__native_list_sort"] = NativeFunkFunction("__native_list_sort") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_sort", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_sort", "expects 'xs' as List")

            val sorted = xs.toKotlinList().sortedWith { a, b ->
                compareValuesForSort(a, b)
            }
            FunkList.fromKotlinList(sorted)
        }

        context["__native_list_sort_by"] = NativeFunkFunction("__native_list_sort_by") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_sort_by", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_sort_by", "expects 'xs' as List")
            val cmp = record.fields["cmp"]
                ?: throw NativeFunctionException("__native_list_sort_by", "expects 'cmp' function")

            val sorted = xs.toKotlinList().sortedWith { a, b ->
                val callArg = FunkRecord(mapOf("l" to a, "r" to b))
                val result = callFunction(cmp, callArg, "__native_list_sort_by")
                (result as? FunkInt)?.value?.toInt()
                    ?: throw NativeFunctionException("__native_list_sort_by", "comparator must return Int")
            }
            FunkList.fromKotlinList(sorted)
        }

        // List equality
        context["__native_list_equals"] = NativeFunkFunction("__native_list_equals") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_equals", "expects FunkRecord")
            val l = record.fields["l"] as? FunkList
                ?: throw NativeFunctionException("__native_list_equals", "expects 'l' as List")
            val r = record.fields["r"] as? FunkList
                ?: throw NativeFunctionException("__native_list_equals", "expects 'r' as List")

            fun go(l1: FunkList, l2: FunkList): Boolean = when {
                l1 is FunkNil && l2 is FunkNil -> true
                l1 is FunkCons && l2 is FunkCons ->
                    compareValuesDeep(l1.head, l2.head) && go(l1.tail, l2.tail)
                else -> false
            }
            FunkBool(go(l, r))
        }
    }

    /**
     * Compare two FunkValues for equality (shallow)
     */
    private fun compareValues(a: FunkValue, b: FunkValue): Boolean {
        return when {
            a is FunkInt && b is FunkInt -> a.value == b.value
            a is FunkFloat && b is FunkFloat -> a.value == b.value
            a is FunkString && b is FunkString -> a.value == b.value
            a is FunkBool && b is FunkBool -> a.value == b.value
            a is FunkNull && b is FunkNull -> true
            else -> false
        }
    }

    /**
     * Compare two FunkValues for deep equality (handles lists recursively)
     */
    private fun compareValuesDeep(a: FunkValue, b: FunkValue): Boolean {
        return when {
            a is FunkInt && b is FunkInt -> a.value == b.value
            a is FunkFloat && b is FunkFloat -> a.value == b.value
            a is FunkString && b is FunkString -> a.value == b.value
            a is FunkBool && b is FunkBool -> a.value == b.value
            a is FunkNull && b is FunkNull -> true
            a is FunkNil && b is FunkNil -> true
            a is FunkCons && b is FunkCons ->
                compareValuesDeep(a.head, b.head) && compareValuesDeep(a.tail, b.tail)
            else -> false
        }
    }

    /**
     * Compare two FunkValues for sorting
     */
    private fun compareValuesForSort(a: FunkValue, b: FunkValue): Int {
        return when {
            a is FunkInt && b is FunkInt -> a.value.compareTo(b.value)
            a is FunkFloat && b is FunkFloat -> a.value.compareTo(b.value)
            a is FunkString && b is FunkString -> a.value.compareTo(b.value)
            a is FunkBool && b is FunkBool -> a.value.compareTo(b.value)
            else -> 0
        }
    }
}
