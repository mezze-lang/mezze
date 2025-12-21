package com.funk.language.interpreter.stdlib.collections

import com.funk.language.interpreter.*

/**
 * Native implementations for List operations
 * List is an immutable linked list backed by Kotlin's List for performance
 */
object TruffleListImplementations {

    /**
     * Helper to call any function type (Truffle or Native)
     * Replaces the need for interpreter.callFunctionWithRecord
     */
    private fun callFunction(f: FunkValue, arg: FunkValue, operationName: String): FunkValue {
        return when (f) {
            is FunkTruffleFunction -> {
                // Call Truffle function directly via CallTarget
                // Pass closure variables along with arguments
                f.callTarget.call(arg, f.closureVariables) as FunkValue
            }
            is NativeFunkFunction -> {
                // Call native function
                f.implementation(listOf(arg))
            }
            else -> {
                throw NativeFunctionException(operationName, "Expected Truffle function or native function, got: ${f::class.simpleName}")
            }
        }
    }

    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Basic list operations
        context["__native_list_empty"] = NativeFunkFunction("__native_list_empty") { args ->
            FunkList(emptyList())
        }

        context["__native_list_cons"] = NativeFunkFunction("__native_list_cons") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_cons", "expects FunkRecord")
            val head = record.fields["head"]
                ?: throw NativeFunctionException("__native_list_cons", "expects 'head' field")
            val tail = record.fields["tail"] as? FunkList
                ?: throw NativeFunctionException("__native_list_cons", "expects 'tail' as List")
            FunkList(listOf(head) + tail.elements)
        }

        context["__native_list_is_empty"] = NativeFunkFunction("__native_list_is_empty") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_is_empty", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_is_empty", "expects 'xs' as List")
            FunkBool(xs.elements.isEmpty())
        }

        context["__native_list_head"] = NativeFunkFunction("__native_list_head") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_head", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_head", "expects 'xs' as List")

            // Return Option - Some if list is non-empty, None otherwise
            if (xs.elements.isEmpty()) {
                FunkVariant("None", null)
            } else {
                FunkVariant("Some", FunkRecord(mapOf("val" to xs.elements.first())))
            }
        }

        context["__native_list_tail"] = NativeFunkFunction("__native_list_tail") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_tail", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_tail", "expects 'xs' as List")

            // Return Option - Some if list is non-empty, None otherwise
            if (xs.elements.isEmpty()) {
                FunkVariant("None", null)
            } else {
                FunkVariant("Some", FunkRecord(mapOf("val" to FunkList(xs.elements.drop(1)))))
            }
        }

        context["__native_list_length"] = NativeFunkFunction("__native_list_length") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_length", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_length", "expects 'xs' as List")
            FunkInt(xs.elements.size.toLong())
        }

        // List construction
        context["__native_list_append"] = NativeFunkFunction("__native_list_append") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_append", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_append", "expects 'xs' as List")
            val ys = record.fields["ys"] as? FunkList
                ?: throw NativeFunctionException("__native_list_append", "expects 'ys' as List")
            FunkList(xs.elements + ys.elements)
        }

        // List access
        context["__native_list_get"] = NativeFunkFunction("__native_list_get") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_get", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_get", "expects 'xs' as List")
            val index = record.fields["index"] as? FunkInt
                ?: throw NativeFunctionException("__native_list_get", "expects 'index' as Int")

            val idx = index.value.toInt()
            // Return Option - Some if index is valid, None otherwise
            if (idx < 0 || idx >= xs.elements.size) {
                FunkVariant("None", null)
            } else {
                FunkVariant("Some", FunkRecord(mapOf("val" to xs.elements[idx])))
            }
        }

        context["__native_list_take"] = NativeFunkFunction("__native_list_take") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_take", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_take", "expects 'xs' as List")
            val n = record.fields["n"] as? FunkInt
                ?: throw NativeFunctionException("__native_list_take", "expects 'n' as Int")
            FunkList(xs.elements.take(n.value.toInt()))
        }

        context["__native_list_drop"] = NativeFunkFunction("__native_list_drop") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_drop", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_drop", "expects 'xs' as List")
            val n = record.fields["n"] as? FunkInt
                ?: throw NativeFunctionException("__native_list_drop", "expects 'n' as Int")
            FunkList(xs.elements.drop(n.value.toInt()))
        }

        // Higher-order functions
        context["__native_list_map"] = NativeFunkFunction("__native_list_map") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_map", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_map", "expects 'xs' as List")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_list_map", "expects 'f' function")

            val mapped = xs.elements.map { elem ->
                // Wrap in FunkRecord since all Funk function parameters are records
                val callArg = FunkRecord(mapOf("x" to elem))
                callFunction(f, callArg, "__native_list_map")
            }
            FunkList(mapped)
        }

        context["__native_list_filter"] = NativeFunkFunction("__native_list_filter") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_filter", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_filter", "expects 'xs' as List")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_list_filter", "expects 'p' predicate")

            val filtered = xs.elements.filter { elem ->
                // Wrap in FunkRecord since all Funk function parameters are records
                val callArg = FunkRecord(mapOf("x" to elem))
                val result = callFunction(p, callArg, "__native_list_filter")
                (result as? FunkBool)?.value
                    ?: throw NativeFunctionException("__native_list_filter", "predicate must return Boolean")
            }
            FunkList(filtered)
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
            for (elem in xs.elements) {
                val callArg = FunkRecord(mapOf("acc" to acc, "x" to elem))
                acc = callFunction(f, callArg, "__native_list_fold_left")
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

            var acc = init
            for (elem in xs.elements.reversed()) {
                val callArg = FunkRecord(mapOf("x" to elem, "acc" to acc))
                acc = callFunction(f, callArg, "__native_list_fold_right")
            }
            acc
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
            for (elem in xs.elements) {
                val callArg = FunkRecord(mapOf("a" to acc, "b" to elem))
                acc = callFunction(f, callArg, "__native_list_reduce")
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

            val result = xs.elements.any { elem ->
                // Wrap in FunkRecord since all Funk function parameters are records
                val callArg = FunkRecord(mapOf("x" to elem))
                val res = callFunction(p, callArg, "__native_list_any")
                (res as? FunkBool)?.value
                    ?: throw NativeFunctionException("__native_list_any", "predicate must return Boolean")
            }
            FunkBool(result)
        }

        context["__native_list_all"] = NativeFunkFunction("__native_list_all") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_all", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_all", "expects 'xs' as List")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_list_all", "expects 'p' predicate")

            val result = xs.elements.all { elem ->
                // Wrap in FunkRecord since all Funk function parameters are records
                val callArg = FunkRecord(mapOf("x" to elem))
                val res = callFunction(p, callArg, "__native_list_all")
                (res as? FunkBool)?.value
                    ?: throw NativeFunctionException("__native_list_all", "predicate must return Boolean")
            }
            FunkBool(result)
        }

        context["__native_list_contains"] = NativeFunkFunction("__native_list_contains") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_contains", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_contains", "expects 'xs' as List")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_list_contains", "expects 'x' value")

            // Use structural equality for comparison
            val result = xs.elements.any { elem -> compareValues(elem, x) }
            FunkBool(result)
        }

        // List transformations
        context["__native_list_reverse"] = NativeFunkFunction("__native_list_reverse") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_reverse", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_reverse", "expects 'xs' as List")
            FunkList(xs.elements.reversed())
        }

        context["__native_list_concat"] = NativeFunkFunction("__native_list_concat") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_concat", "expects FunkRecord")
            val xss = record.fields["xss"] as? FunkList
                ?: throw NativeFunctionException("__native_list_concat", "expects 'xss' as List")

            val flattened = xss.elements.flatMap { elem ->
                (elem as? FunkList)?.elements
                    ?: throw NativeFunctionException("__native_list_concat", "all elements must be Lists")
            }
            FunkList(flattened)
        }

        context["__native_list_flat_map"] = NativeFunkFunction("__native_list_flat_map") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_flat_map", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_flat_map", "expects 'xs' as List")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_list_flat_map", "expects 'f' function")

            val mapped = xs.elements.flatMap { elem ->
                // Wrap in FunkRecord since all Funk function parameters are records
                val callArg = FunkRecord(mapOf("x" to elem))
                val result = callFunction(f, callArg, "__native_list_flat_map")
                (result as? FunkList)?.elements
                    ?: throw NativeFunctionException("__native_list_flat_map", "f must return List")
            }
            FunkList(mapped)
        }

        context["__native_list_zip"] = NativeFunkFunction("__native_list_zip") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_zip", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_zip", "expects 'xs' as List")
            val ys = record.fields["ys"] as? FunkList
                ?: throw NativeFunctionException("__native_list_zip", "expects 'ys' as List")

            val zipped = xs.elements.zip(ys.elements).map { (fst, snd) ->
                FunkRecord(mapOf("fst" to fst, "snd" to snd))
            }
            FunkList(zipped)
        }

        context["__native_list_unzip"] = NativeFunkFunction("__native_list_unzip") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_unzip", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_unzip", "expects 'xs' as List")

            val fsts = mutableListOf<FunkValue>()
            val snds = mutableListOf<FunkValue>()

            for (elem in xs.elements) {
                val pair = elem as? FunkRecord
                    ?: throw NativeFunctionException("__native_list_unzip", "all elements must be records")
                val fst = pair.fields["fst"]
                    ?: throw NativeFunctionException("__native_list_unzip", "record must have 'fst' field")
                val snd = pair.fields["snd"]
                    ?: throw NativeFunctionException("__native_list_unzip", "record must have 'snd' field")
                fsts.add(fst)
                snds.add(snd)
            }

            FunkRecord(mapOf(
                "fsts" to FunkList(fsts),
                "snds" to FunkList(snds)
            ))
        }

        // Conversion functions
        context["__native_list_to_array"] = NativeFunkFunction("__native_list_to_array") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_to_array", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_to_array", "expects 'xs' as List")
            FunkArrayList(xs.elements.toMutableList())
        }

        context["__native_list_from_array"] = NativeFunkFunction("__native_list_from_array") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_from_array", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_list_from_array", "expects 'xs' as Array, got ${record.fields["xs"]?.let { it::class.simpleName } ?: "null"}")
            FunkList(xs.elements.toList())
        }

        // List utilities
        context["__native_list_range"] = NativeFunkFunction("__native_list_range") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_range", "expects FunkRecord")
            val start = record.fields["start"] as? FunkInt
                ?: throw NativeFunctionException("__native_list_range", "expects 'start' as Int")
            val end = record.fields["end"] as? FunkInt
                ?: throw NativeFunctionException("__native_list_range", "expects 'end' as Int")

            val range = (start.value until end.value).map { FunkInt(it) }
            FunkList(range)
        }

        context["__native_list_repeat"] = NativeFunkFunction("__native_list_repeat") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_repeat", "expects FunkRecord")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_list_repeat", "expects 'x' value")
            val n = record.fields["n"] as? FunkInt
                ?: throw NativeFunctionException("__native_list_repeat", "expects 'n' as Int")

            val repeated = List(n.value.toInt()) { x }
            FunkList(repeated)
        }

        context["__native_list_intersperse"] = NativeFunkFunction("__native_list_intersperse") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_intersperse", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_intersperse", "expects 'xs' as List")
            val sep = record.fields["sep"]
                ?: throw NativeFunctionException("__native_list_intersperse", "expects 'sep' value")

            if (xs.elements.isEmpty()) {
                return@NativeFunkFunction FunkList(emptyList())
            }

            val interspersed = mutableListOf<FunkValue>()
            xs.elements.forEachIndexed { index, elem ->
                interspersed.add(elem)
                if (index < xs.elements.size - 1) {
                    interspersed.add(sep)
                }
            }
            FunkList(interspersed)
        }

        // Sorting
        context["__native_list_sort"] = NativeFunkFunction("__native_list_sort") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_sort", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_sort", "expects 'xs' as List")

            val sorted = xs.elements.sortedWith { a, b ->
                compareValuesForSort(a, b)
            }
            FunkList(sorted)
        }

        context["__native_list_sort_by"] = NativeFunkFunction("__native_list_sort_by") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_sort_by", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_sort_by", "expects 'xs' as List")
            val cmp = record.fields["cmp"]
                ?: throw NativeFunctionException("__native_list_sort_by", "expects 'cmp' function")

            val sorted = xs.elements.sortedWith { a, b ->
                val callArg = FunkRecord(mapOf("l" to a, "r" to b))
                val result = callFunction(cmp, callArg, "__native_list_sort_by")
                (result as? FunkInt)?.value?.toInt()
                    ?: throw NativeFunctionException("__native_list_sort_by", "comparator must return Int")
            }
            FunkList(sorted)
        }

        // List equality
        context["__native_list_equals"] = NativeFunkFunction("__native_list_equals") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_equals", "expects FunkRecord")
            val l = record.fields["l"] as? FunkList
                ?: throw NativeFunctionException("__native_list_equals", "expects 'l' as List")
            val r = record.fields["r"] as? FunkList
                ?: throw NativeFunctionException("__native_list_equals", "expects 'r' as List")

            // Lists are equal if they have the same length and all elements are equal
            if (l.elements.size != r.elements.size) {
                return@NativeFunkFunction FunkBool(false)
            }

            val allEqual = l.elements.zip(r.elements).all { (elemL, elemR) ->
                compareValuesDeep(elemL, elemR)
            }
            FunkBool(allEqual)
        }
    }

    /**
     * Compare two FunkValues for equality (shallow - doesn't handle lists)
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
            a is FunkList && b is FunkList -> {
                // Lists are equal if same length and all elements are equal
                a.elements.size == b.elements.size &&
                a.elements.zip(b.elements).all { (elemA, elemB) ->
                    compareValuesDeep(elemA, elemB)
                }
            }
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
