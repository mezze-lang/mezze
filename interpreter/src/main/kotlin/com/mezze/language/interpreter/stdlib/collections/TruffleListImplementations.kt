package com.mezze.language.interpreter.stdlib.collections

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for List operations.
 *
 * List is an immutable linked list using classic cons cells (MezzeCons/MezzeNil)
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
    private fun callFunction(f: MezzeValue, arg: MezzeValue, operationName: String): MezzeValue {
        return when (f) {
            is MezzeTruffleFunction -> {
                f.callTarget.call(
                    arg,
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as MezzeValue
            }
            is NativeMezzeFunction -> {
                f.implementation(listOf(arg))
            }
            else -> {
                throw NativeFunctionException(operationName, "Expected Truffle function or native function, got: ${f::class.simpleName}")
            }
        }
    }

    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Basic list operations
        context["__native_list_empty"] = NativeMezzeFunction("__native_list_empty") { _ ->
            MezzeNil
        }

        context["__native_list_cons"] = NativeMezzeFunction("__native_list_cons") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_cons", "expects MezzeRecord")
            val head = record.fields["head"]
                ?: throw NativeFunctionException("__native_list_cons", "expects 'head' field")
            val tail = record.fields["tail"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_cons", "expects 'tail' as List")
            MezzeCons(head, tail)
        }

        context["__native_list_is_empty"] = NativeMezzeFunction("__native_list_is_empty") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_is_empty", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_is_empty", "expects 'xs' as List")
            MezzeBool(xs.isEmpty())
        }

        context["__native_list_head"] = NativeMezzeFunction("__native_list_head") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_head", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_head", "expects 'xs' as List")

            val head = xs.headOrNull()
            if (head == null) {
                mezzeOptionNone()
            } else {
                mezzeOptionSome(head)
            }
        }

        context["__native_list_tail"] = NativeMezzeFunction("__native_list_tail") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_tail", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_tail", "expects 'xs' as List")

            val tail = xs.tailOrNull()
            if (tail == null) {
                mezzeOptionNone()
            } else {
                mezzeOptionSome(tail)
            }
        }

        context["__native_list_length"] = NativeMezzeFunction("__native_list_length") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_length", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_length", "expects 'xs' as List")
            MezzeInt(xs.length().toLong())
        }

        // Deconstruction - uncons returns Option { head: 'a, tail: List 'a }
        context["__native_list_uncons"] = NativeMezzeFunction("__native_list_uncons") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_uncons", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_uncons", "expects 'xs' as List")

            when (xs) {
                is MezzeNil -> mezzeOptionNone()
                is MezzeCons -> MezzeVariant("Some", MezzeRecord(mapOf(
                    "val" to MezzeRecord(mapOf(
                        "head" to xs.head,
                        "tail" to xs.tail
                    ))
                )))
            }
        }

        // List construction
        context["__native_list_append"] = NativeMezzeFunction("__native_list_append") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_append", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_append", "expects 'xs' as List")
            val ys = record.fields["ys"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_append", "expects 'ys' as List")

            // O(n) where n = length of xs - iterative version to avoid stack overflow
            // First collect all elements of xs, then build result from end
            val elements = mutableListOf<MezzeValue>()
            var current: MezzeList = xs
            while (current is MezzeCons) {
                elements.add(current.head)
                current = current.tail
            }
            // Build result from end: start with ys, prepend elements in reverse
            var result: MezzeList = ys
            for (i in elements.indices.reversed()) {
                result = MezzeCons(elements[i], result)
            }
            result
        }

        // List access
        context["__native_list_get"] = NativeMezzeFunction("__native_list_get") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_get", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_get", "expects 'xs' as List")
            val index = record.fields["index"] as? MezzeInt
                ?: throw NativeFunctionException("__native_list_get", "expects 'index' as Int")

            var idx = index.value.toInt()
            var current: MezzeList = xs
            while (current is MezzeCons) {
                if (idx == 0) {
                    return@NativeMezzeFunction mezzeOptionSome(current.head)
                }
                current = current.tail
                idx--
            }
            mezzeOptionNone()
        }

        context["__native_list_take"] = NativeMezzeFunction("__native_list_take") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_take", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_take", "expects 'xs' as List")
            val n = record.fields["n"] as? MezzeInt
                ?: throw NativeFunctionException("__native_list_take", "expects 'n' as Int")

            // Iterative version to avoid stack overflow
            val elements = mutableListOf<MezzeValue>()
            var current: MezzeList = xs
            var remaining = n.value.toInt()
            while (remaining > 0 && current is MezzeCons) {
                elements.add(current.head)
                current = current.tail
                remaining--
            }
            // Build result from end
            var result: MezzeList = MezzeNil
            for (i in elements.indices.reversed()) {
                result = MezzeCons(elements[i], result)
            }
            result
        }

        context["__native_list_drop"] = NativeMezzeFunction("__native_list_drop") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_drop", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_drop", "expects 'xs' as List")
            val n = record.fields["n"] as? MezzeInt
                ?: throw NativeFunctionException("__native_list_drop", "expects 'n' as Int")

            var current: MezzeList = xs
            var remaining = n.value.toInt()
            while (remaining > 0 && current is MezzeCons) {
                current = current.tail
                remaining--
            }
            current
        }

        // Higher-order functions
        context["__native_list_map"] = NativeMezzeFunction("__native_list_map") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_map", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_map", "expects 'xs' as List")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_list_map", "expects 'f' function")

            // Iterative version to avoid stack overflow
            val mapped = mutableListOf<MezzeValue>()
            var current: MezzeList = xs
            while (current is MezzeCons) {
                val callArg = MezzeRecord(mapOf("x" to current.head))
                mapped.add(callFunction(f, callArg, "__native_list_map"))
                current = current.tail
            }
            // Build result from end
            var result: MezzeList = MezzeNil
            for (i in mapped.indices.reversed()) {
                result = MezzeCons(mapped[i], result)
            }
            result
        }

        context["__native_list_filter"] = NativeMezzeFunction("__native_list_filter") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_filter", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_filter", "expects 'xs' as List")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_list_filter", "expects 'p' predicate")

            // Iterative version to avoid stack overflow
            val filtered = mutableListOf<MezzeValue>()
            var current: MezzeList = xs
            while (current is MezzeCons) {
                val callArg = MezzeRecord(mapOf("x" to current.head))
                val result = callFunction(p, callArg, "__native_list_filter")
                val keep = (result as? MezzeBool)?.value
                    ?: throw NativeFunctionException("__native_list_filter", "predicate must return Boolean")
                if (keep) {
                    filtered.add(current.head)
                }
                current = current.tail
            }
            // Build result from end
            var result: MezzeList = MezzeNil
            for (i in filtered.indices.reversed()) {
                result = MezzeCons(filtered[i], result)
            }
            result
        }

        context["__native_list_fold_left"] = NativeMezzeFunction("__native_list_fold_left") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_fold_left", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_fold_left", "expects 'xs' as List")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_list_fold_left", "expects 'init' value")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_list_fold_left", "expects 'f' function")

            var acc = init
            var current: MezzeList = xs
            while (current is MezzeCons) {
                val callArg = MezzeRecord(mapOf("acc" to acc, "x" to current.head))
                acc = callFunction(f, callArg, "__native_list_fold_left")
                current = current.tail
            }
            acc
        }

        context["__native_list_fold_right"] = NativeMezzeFunction("__native_list_fold_right") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_fold_right", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_fold_right", "expects 'xs' as List")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_list_fold_right", "expects 'init' value")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_list_fold_right", "expects 'f' function")

            // Fold right: process from end - iterative version using explicit stack
            // First collect all elements, then fold from the end
            val elements = mutableListOf<MezzeValue>()
            var current: MezzeList = xs
            while (current is MezzeCons) {
                elements.add(current.head)
                current = current.tail
            }
            // Fold from right (end of list)
            var acc = init
            for (i in elements.indices.reversed()) {
                val callArg = MezzeRecord(mapOf("x" to elements[i], "acc" to acc))
                acc = callFunction(f, callArg, "__native_list_fold_right")
            }
            acc
        }

        context["__native_list_reduce"] = NativeMezzeFunction("__native_list_reduce") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_reduce", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_reduce", "expects 'xs' as List")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_list_reduce", "expects 'f' function")

            // reduce uses the first element as initial accumulator, returns Option
            if (xs is MezzeCons) {
                var acc = xs.head
                var current: MezzeList = xs.tail
                while (current is MezzeCons) {
                    val callArg = MezzeRecord(mapOf("acc" to acc, "x" to current.head))
                    acc = callFunction(f, callArg, "__native_list_reduce")
                    current = current.tail
                }
                MezzeVariant("Some", MezzeRecord(mapOf("val" to acc)))
            } else {
                MezzeVariant("None", null)
            }
        }

        // List predicates
        context["__native_list_any"] = NativeMezzeFunction("__native_list_any") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_any", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_any", "expects 'xs' as List")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_list_any", "expects 'p' predicate")

            var current: MezzeList = xs
            while (current is MezzeCons) {
                val callArg = MezzeRecord(mapOf("x" to current.head))
                val result = callFunction(p, callArg, "__native_list_any")
                val matches = (result as? MezzeBool)?.value
                    ?: throw NativeFunctionException("__native_list_any", "predicate must return Boolean")
                if (matches) return@NativeMezzeFunction MezzeBool(true)
                current = current.tail
            }
            MezzeBool(false)
        }

        context["__native_list_all"] = NativeMezzeFunction("__native_list_all") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_all", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_all", "expects 'xs' as List")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_list_all", "expects 'p' predicate")

            var current: MezzeList = xs
            while (current is MezzeCons) {
                val callArg = MezzeRecord(mapOf("x" to current.head))
                val result = callFunction(p, callArg, "__native_list_all")
                val matches = (result as? MezzeBool)?.value
                    ?: throw NativeFunctionException("__native_list_all", "predicate must return Boolean")
                if (!matches) return@NativeMezzeFunction MezzeBool(false)
                current = current.tail
            }
            MezzeBool(true)
        }

        context["__native_list_contains"] = NativeMezzeFunction("__native_list_contains") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_contains", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_contains", "expects 'xs' as List")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_list_contains", "expects 'x' value")

            var current: MezzeList = xs
            while (current is MezzeCons) {
                if (compareValues(current.head, x)) {
                    return@NativeMezzeFunction MezzeBool(true)
                }
                current = current.tail
            }
            MezzeBool(false)
        }

        // List transformations
        context["__native_list_reverse"] = NativeMezzeFunction("__native_list_reverse") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_reverse", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_reverse", "expects 'xs' as List")

            // O(n) iterative reverse
            var result: MezzeList = MezzeNil
            var current: MezzeList = xs
            while (current is MezzeCons) {
                result = MezzeCons(current.head, result)
                current = current.tail
            }
            result
        }

        context["__native_list_concat"] = NativeMezzeFunction("__native_list_concat") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_concat", "expects MezzeRecord")
            val xss = record.fields["xss"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_concat", "expects 'xss' as List")

            // Flatten list of lists
            var result: MezzeList = MezzeNil
            val lists = mutableListOf<MezzeList>()

            // Collect all inner lists
            var current: MezzeList = xss
            while (current is MezzeCons) {
                val innerList = current.head as? MezzeList
                    ?: throw NativeFunctionException("__native_list_concat", "all elements must be Lists")
                lists.add(innerList)
                current = current.tail
            }

            // Append in reverse order for efficiency
            for (i in lists.indices.reversed()) {
                var innerCurrent: MezzeList = lists[i]
                val innerElements = mutableListOf<MezzeValue>()
                while (innerCurrent is MezzeCons) {
                    innerElements.add(innerCurrent.head)
                    innerCurrent = innerCurrent.tail
                }
                for (j in innerElements.indices.reversed()) {
                    result = MezzeCons(innerElements[j], result)
                }
            }
            result
        }

        context["__native_list_flat_map"] = NativeMezzeFunction("__native_list_flat_map") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_flat_map", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_flat_map", "expects 'xs' as List")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_list_flat_map", "expects 'f' function")

            // Collect all results then flatten
            val results = mutableListOf<MezzeList>()
            var current: MezzeList = xs
            while (current is MezzeCons) {
                val callArg = MezzeRecord(mapOf("x" to current.head))
                val result = callFunction(f, callArg, "__native_list_flat_map")
                val innerList = result as? MezzeList
                    ?: throw NativeFunctionException("__native_list_flat_map", "f must return List")
                results.add(innerList)
                current = current.tail
            }

            // Flatten
            var finalResult: MezzeList = MezzeNil
            for (i in results.indices.reversed()) {
                var innerCurrent: MezzeList = results[i]
                val innerElements = mutableListOf<MezzeValue>()
                while (innerCurrent is MezzeCons) {
                    innerElements.add(innerCurrent.head)
                    innerCurrent = innerCurrent.tail
                }
                for (j in innerElements.indices.reversed()) {
                    finalResult = MezzeCons(innerElements[j], finalResult)
                }
            }
            finalResult
        }

        context["__native_list_zip"] = NativeMezzeFunction("__native_list_zip") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_zip", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_zip", "expects 'xs' as List")
            val ys = record.fields["ys"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_zip", "expects 'ys' as List")

            // Iterative version to avoid stack overflow
            val pairs = mutableListOf<MezzeValue>()
            var l1: MezzeList = xs
            var l2: MezzeList = ys
            while (l1 is MezzeCons && l2 is MezzeCons) {
                pairs.add(MezzeRecord(mapOf("fst" to l1.head, "snd" to l2.head)))
                l1 = l1.tail
                l2 = l2.tail
            }
            // Build result from end
            var result: MezzeList = MezzeNil
            for (i in pairs.indices.reversed()) {
                result = MezzeCons(pairs[i], result)
            }
            result
        }

        context["__native_list_unzip"] = NativeMezzeFunction("__native_list_unzip") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_unzip", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_unzip", "expects 'xs' as List")

            val fsts = mutableListOf<MezzeValue>()
            val snds = mutableListOf<MezzeValue>()

            var current: MezzeList = xs
            while (current is MezzeCons) {
                val pair = current.head as? MezzeRecord
                    ?: throw NativeFunctionException("__native_list_unzip", "all elements must be records")
                val fst = pair.fields["fst"]
                    ?: throw NativeFunctionException("__native_list_unzip", "record must have 'fst' field")
                val snd = pair.fields["snd"]
                    ?: throw NativeFunctionException("__native_list_unzip", "record must have 'snd' field")
                fsts.add(fst)
                snds.add(snd)
                current = current.tail
            }

            MezzeRecord(mapOf(
                "fsts" to MezzeList.fromKotlinList(fsts),
                "snds" to MezzeList.fromKotlinList(snds)
            ))
        }

        // Conversion functions
        context["__native_list_to_array"] = NativeMezzeFunction("__native_list_to_array") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_to_array", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_to_array", "expects 'xs' as List")
            MezzeArrayList(xs.toKotlinList().toMutableList())
        }

        context["__native_list_from_array"] = NativeMezzeFunction("__native_list_from_array") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_from_array", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_list_from_array", "expects 'xs' as Array, got ${record.fields["xs"]?.let { it::class.simpleName } ?: "null"}")
            MezzeList.fromKotlinList(xs.toList())
        }

        // List utilities
        context["__native_list_range"] = NativeMezzeFunction("__native_list_range") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_range", "expects MezzeRecord")
            val start = record.fields["start"] as? MezzeInt
                ?: throw NativeFunctionException("__native_list_range", "expects 'start' as Int")
            val end = record.fields["end"] as? MezzeInt
                ?: throw NativeFunctionException("__native_list_range", "expects 'end' as Int")

            // Build list in reverse, then it's already correct order
            var result: MezzeList = MezzeNil
            for (i in (end.value - 1) downTo start.value) {
                result = MezzeCons(MezzeInt(i), result)
            }
            result
        }

        context["__native_list_repeat"] = NativeMezzeFunction("__native_list_repeat") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_repeat", "expects MezzeRecord")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_list_repeat", "expects 'x' value")
            val n = record.fields["n"] as? MezzeInt
                ?: throw NativeFunctionException("__native_list_repeat", "expects 'n' as Int")

            var result: MezzeList = MezzeNil
            repeat(n.value.toInt()) {
                result = MezzeCons(x, result)
            }
            result
        }

        context["__native_list_intersperse"] = NativeMezzeFunction("__native_list_intersperse") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_intersperse", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_intersperse", "expects 'xs' as List")
            val sep = record.fields["sep"]
                ?: throw NativeFunctionException("__native_list_intersperse", "expects 'sep' value")

            // Iterative version to avoid stack overflow
            val elements = mutableListOf<MezzeValue>()
            var current: MezzeList = xs
            while (current is MezzeCons) {
                elements.add(current.head)
                current = current.tail
            }
            if (elements.isEmpty()) {
                MezzeNil
            } else {
                // Build result with separators
                var result: MezzeList = MezzeCons(elements.last(), MezzeNil)
                for (i in (elements.size - 2) downTo 0) {
                    result = MezzeCons(elements[i], MezzeCons(sep, result))
                }
                result
            }
        }

        // Sorting - convert to Kotlin list, sort, convert back
        context["__native_list_sort"] = NativeMezzeFunction("__native_list_sort") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_sort", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_sort", "expects 'xs' as List")

            val sorted = xs.toKotlinList().sortedWith { a, b ->
                compareValuesForSort(a, b)
            }
            MezzeList.fromKotlinList(sorted)
        }

        context["__native_list_sort_by"] = NativeMezzeFunction("__native_list_sort_by") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_sort_by", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_sort_by", "expects 'xs' as List")
            val cmp = record.fields["cmp"]
                ?: throw NativeFunctionException("__native_list_sort_by", "expects 'cmp' function")

            val sorted = xs.toKotlinList().sortedWith { a, b ->
                val callArg = MezzeRecord(mapOf("a" to a, "b" to b))
                val result = callFunction(cmp, callArg, "__native_list_sort_by")
                (result as? MezzeInt)?.value?.toInt()
                    ?: throw NativeFunctionException("__native_list_sort_by", "comparator must return Int")
            }
            MezzeList.fromKotlinList(sorted)
        }

        // Find first element matching predicate
        context["__native_list_find"] = NativeMezzeFunction("__native_list_find") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_find", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_find", "expects 'xs' as List")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_list_find", "expects 'p' predicate")

            var current: MezzeList = xs
            while (current is MezzeCons) {
                val callArg = MezzeRecord(mapOf("x" to current.head))
                val result = callFunction(p, callArg, "__native_list_find")
                val matches = (result as? MezzeBool)?.value
                    ?: throw NativeFunctionException("__native_list_find", "predicate must return Boolean")
                if (matches) return@NativeMezzeFunction mezzeOptionSome(current.head)
                current = current.tail
            }
            mezzeOptionNone()
        }

        // Find index of first element matching predicate
        context["__native_list_find_index"] = NativeMezzeFunction("__native_list_find_index") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_find_index", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_find_index", "expects 'xs' as List")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_list_find_index", "expects 'p' predicate")

            var current: MezzeList = xs
            var index = 0L
            while (current is MezzeCons) {
                val callArg = MezzeRecord(mapOf("x" to current.head))
                val result = callFunction(p, callArg, "__native_list_find_index")
                val matches = (result as? MezzeBool)?.value
                    ?: throw NativeFunctionException("__native_list_find_index", "predicate must return Boolean")
                if (matches) return@NativeMezzeFunction mezzeOptionSome(MezzeInt(index))
                current = current.tail
                index++
            }
            mezzeOptionNone()
        }

        // Partition list into matching and non-matching elements
        context["__native_list_partition"] = NativeMezzeFunction("__native_list_partition") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_partition", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_partition", "expects 'xs' as List")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_list_partition", "expects 'p' predicate")

            val matching = mutableListOf<MezzeValue>()
            val notMatching = mutableListOf<MezzeValue>()

            var current: MezzeList = xs
            while (current is MezzeCons) {
                val callArg = MezzeRecord(mapOf("x" to current.head))
                val result = callFunction(p, callArg, "__native_list_partition")
                val matches = (result as? MezzeBool)?.value
                    ?: throw NativeFunctionException("__native_list_partition", "predicate must return Boolean")
                if (matches) {
                    matching.add(current.head)
                } else {
                    notMatching.add(current.head)
                }
                current = current.tail
            }

            MezzeRecord(mapOf(
                "matching" to MezzeList.fromKotlinList(matching),
                "not_matching" to MezzeList.fromKotlinList(notMatching)
            ))
        }

        // Get last element of list
        context["__native_list_last"] = NativeMezzeFunction("__native_list_last") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_last", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_last", "expects 'xs' as List")

            var last: MezzeValue? = null
            var current: MezzeList = xs
            while (current is MezzeCons) {
                last = current.head
                current = current.tail
            }
            if (last != null) {
                mezzeOptionSome(last)
            } else {
                mezzeOptionNone()
            }
        }

        // Get all elements except the last
        context["__native_list_init"] = NativeMezzeFunction("__native_list_init") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_init", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_init", "expects 'xs' as List")

            val elements = mutableListOf<MezzeValue>()
            var current: MezzeList = xs
            while (current is MezzeCons) {
                elements.add(current.head)
                current = current.tail
            }

            if (elements.isEmpty()) {
                MezzeNil
            } else {
                // Build list of all elements except last
                var result: MezzeList = MezzeNil
                for (i in (elements.size - 2) downTo 0) {
                    result = MezzeCons(elements[i], result)
                }
                result
            }
        }

        // List equality
        context["__native_list_equals"] = NativeMezzeFunction("__native_list_equals") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_list_equals", "expects MezzeRecord")
            val l = record.fields["l"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_equals", "expects 'l' as List")
            val r = record.fields["r"] as? MezzeList
                ?: throw NativeFunctionException("__native_list_equals", "expects 'r' as List")

            // Iterative version to avoid stack overflow
            var l1: MezzeList = l
            var l2: MezzeList = r
            while (true) {
                when {
                    l1 is MezzeNil && l2 is MezzeNil -> return@NativeMezzeFunction MezzeBool(true)
                    l1 is MezzeCons && l2 is MezzeCons -> {
                        if (!compareValuesDeep(l1.head, l2.head)) {
                            return@NativeMezzeFunction MezzeBool(false)
                        }
                        l1 = l1.tail
                        l2 = l2.tail
                    }
                    else -> return@NativeMezzeFunction MezzeBool(false)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            MezzeBool(false)
        }
    }

    /**
     * Compare two MezzeValues for equality (shallow)
     */
    private fun compareValues(a: MezzeValue, b: MezzeValue): Boolean {
        return when {
            a is MezzeInt && b is MezzeInt -> a.value == b.value
            a is MezzeFloat && b is MezzeFloat -> a.value == b.value
            a is MezzeString && b is MezzeString -> a.value == b.value
            a is MezzeBool && b is MezzeBool -> a.value == b.value
            a is MezzeNull && b is MezzeNull -> true
            else -> false
        }
    }

    /**
     * Compare two MezzeValues for deep equality (handles lists recursively)
     */
    private fun compareValuesDeep(a: MezzeValue, b: MezzeValue): Boolean {
        return when {
            a is MezzeInt && b is MezzeInt -> a.value == b.value
            a is MezzeFloat && b is MezzeFloat -> a.value == b.value
            a is MezzeString && b is MezzeString -> a.value == b.value
            a is MezzeBool && b is MezzeBool -> a.value == b.value
            a is MezzeNull && b is MezzeNull -> true
            a is MezzeNil && b is MezzeNil -> true
            a is MezzeCons && b is MezzeCons ->
                compareValuesDeep(a.head, b.head) && compareValuesDeep(a.tail, b.tail)
            else -> false
        }
    }

    /**
     * Compare two MezzeValues for sorting
     */
    private fun compareValuesForSort(a: MezzeValue, b: MezzeValue): Int {
        return when {
            a is MezzeInt && b is MezzeInt -> a.value.compareTo(b.value)
            a is MezzeFloat && b is MezzeFloat -> a.value.compareTo(b.value)
            a is MezzeString && b is MezzeString -> a.value.compareTo(b.value)
            a is MezzeBool && b is MezzeBool -> a.value.compareTo(b.value)
            else -> 0
        }
    }
}
