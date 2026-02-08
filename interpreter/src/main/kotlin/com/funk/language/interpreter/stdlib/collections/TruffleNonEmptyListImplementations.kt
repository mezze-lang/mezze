package com.funk.language.interpreter.stdlib.collections

import com.funk.language.interpreter.*

/**
 * Truffle-optimized implementations for NonEmptyList operations.
 *
 * Phase 3 of Truffle migration: Collection operations optimized for JIT performance.
 * NonEmptyList is an immutable linked list guaranteed to have at least one element.
 *
 * Optimizations:
 * - Helper function for consistent Truffle function calling
 * - Inline Kotlin collection operations (map, fold, reverse, zip, etc.)
 * - Safe head/tail operations (no empty list checks needed)
 * - Custom comparators for sorting
 * - Type-safe reduction without initial value
 */
object TruffleNonEmptyListImplementations {

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
        // Basic non-empty list operations
        context["__native_nel_singleton"] = NativeFunkFunction("__native_nel_singleton") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_singleton", "expects FunkRecord")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_nel_singleton", "expects 'x' value")
            FunkNonEmptyList(listOf(x))
        }

        context["__native_nel_from_head_tail"] = NativeFunkFunction("__native_nel_from_head_tail") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_from_head_tail", "expects FunkRecord")
            val head = record.fields["head"]
                ?: throw NativeFunctionException("__native_nel_from_head_tail", "expects 'head' field")
            val tail = record.fields["tail"] as? FunkList
                ?: throw NativeFunctionException("__native_nel_from_head_tail", "expects 'tail' as List")
            FunkNonEmptyList(listOf(head) + tail.toKotlinList())
        }

        context["__native_nel_head"] = NativeFunkFunction("__native_nel_head") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_head", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_head", "expects 'xs' as NonEmptyList")
            xs.elements.first()
        }

        context["__native_nel_tail"] = NativeFunkFunction("__native_nel_tail") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_tail", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_tail", "expects 'xs' as NonEmptyList")
            FunkList.fromKotlinList(xs.elements.drop(1))
        }

        context["__native_nel_length"] = NativeFunkFunction("__native_nel_length") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_length", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_length", "expects 'xs' as NonEmptyList")
            FunkInt(xs.elements.size.toLong())
        }

        // Construction
        context["__native_nel_cons"] = NativeFunkFunction("__native_nel_cons") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_cons", "expects FunkRecord")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_nel_cons", "expects 'x' value")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_cons", "expects 'xs' as NonEmptyList")
            FunkNonEmptyList(listOf(x) + xs.elements)
        }

        context["__native_nel_append"] = NativeFunkFunction("__native_nel_append") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_append", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_append", "expects 'xs' as NonEmptyList")
            val ys = record.fields["ys"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_append", "expects 'ys' as NonEmptyList")
            FunkNonEmptyList(xs.elements + ys.elements)
        }

        context["__native_nel_prepend_list"] = NativeFunkFunction("__native_nel_prepend_list") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_prepend_list", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_nel_prepend_list", "expects 'xs' as List")
            val y = record.fields["y"]
                ?: throw NativeFunctionException("__native_nel_prepend_list", "expects 'y' value")
            FunkNonEmptyList(xs.toKotlinList() + listOf(y))
        }

        context["__native_nel_append_list"] = NativeFunkFunction("__native_nel_append_list") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_append_list", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_append_list", "expects 'xs' as NonEmptyList")
            val ys = record.fields["ys"] as? FunkList
                ?: throw NativeFunctionException("__native_nel_append_list", "expects 'ys' as List")
            FunkNonEmptyList(xs.elements + ys.toKotlinList())
        }

        // Access
        context["__native_nel_get"] = NativeFunkFunction("__native_nel_get") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_get", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_get", "expects 'xs' as NonEmptyList")
            val index = record.fields["index"] as? FunkInt
                ?: throw NativeFunctionException("__native_nel_get", "expects 'index' as Int")

            val idx = index.value.toInt()
            if (idx < 0 || idx >= xs.elements.size) {
                throw NativeFunctionException("__native_nel_get", "index out of bounds: $idx")
            }
            xs.elements[idx]
        }

        context["__native_nel_last"] = NativeFunkFunction("__native_nel_last") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_last", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_last", "expects 'xs' as NonEmptyList")
            xs.elements.last()
        }

        // Higher-order functions
        context["__native_nel_map"] = NativeFunkFunction("__native_nel_map") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_map", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_map", "expects 'xs' as NonEmptyList")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_nel_map", "expects 'f' function")

            val mapped = xs.elements.map { elem ->
                callFunction(f, elem, "__native_nel_map")
            }
            FunkNonEmptyList(mapped)
        }

        context["__native_nel_flat_map"] = NativeFunkFunction("__native_nel_flat_map") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_flat_map", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_flat_map", "expects 'xs' as NonEmptyList")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_nel_flat_map", "expects 'f' function")

            val mapped = xs.elements.flatMap { elem ->
                val result = callFunction(f, elem, "__native_nel_flat_map")
                (result as? FunkNonEmptyList)?.elements
                    ?: throw NativeFunctionException("__native_nel_flat_map", "f must return NonEmptyList")
            }
            FunkNonEmptyList(mapped)
        }

        context["__native_nel_fold_left"] = NativeFunkFunction("__native_nel_fold_left") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_fold_left", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_fold_left", "expects 'xs' as NonEmptyList")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_nel_fold_left", "expects 'init' value")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_nel_fold_left", "expects 'f' function")

            var acc = init
            for (elem in xs.elements) {
                val callArg = FunkRecord(mapOf("acc" to acc, "x" to elem))
                acc = callFunction(f, callArg, "__native_nel_fold_left")
            }
            acc
        }

        context["__native_nel_fold_right"] = NativeFunkFunction("__native_nel_fold_right") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_fold_right", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_fold_right", "expects 'xs' as NonEmptyList")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_nel_fold_right", "expects 'init' value")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_nel_fold_right", "expects 'f' function")

            var acc = init
            for (elem in xs.elements.reversed()) {
                val callArg = FunkRecord(mapOf("x" to elem, "acc" to acc))
                acc = callFunction(f, callArg, "__native_nel_fold_right")
            }
            acc
        }

        context["__native_nel_reduce"] = NativeFunkFunction("__native_nel_reduce") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_reduce", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_reduce", "expects 'xs' as NonEmptyList")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_nel_reduce", "expects 'f' function")

            var acc = xs.elements.first()
            for (elem in xs.elements.drop(1)) {
                val callArg = FunkRecord(mapOf("acc" to acc, "x" to elem))
                acc = callFunction(f, callArg, "__native_nel_reduce")
            }
            acc
        }

        // Predicates
        context["__native_nel_any"] = NativeFunkFunction("__native_nel_any") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_any", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_any", "expects 'xs' as NonEmptyList")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_nel_any", "expects 'p' predicate")

            val result = xs.elements.any { elem ->
                val res = callFunction(p, elem, "__native_nel_any")
                (res as? FunkBool)?.value
                    ?: throw NativeFunctionException("__native_nel_any", "predicate must return Boolean")
            }
            FunkBool(result)
        }

        context["__native_nel_all"] = NativeFunkFunction("__native_nel_all") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_all", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_all", "expects 'xs' as NonEmptyList")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_nel_all", "expects 'p' predicate")

            val result = xs.elements.all { elem ->
                val res = callFunction(p, elem, "__native_nel_all")
                (res as? FunkBool)?.value
                    ?: throw NativeFunctionException("__native_nel_all", "predicate must return Boolean")
            }
            FunkBool(result)
        }

        context["__native_nel_contains"] = NativeFunkFunction("__native_nel_contains") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_contains", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_contains", "expects 'xs' as NonEmptyList")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_nel_contains", "expects 'x' value")

            val result = xs.elements.any { elem -> compareValues(elem, x) }
            FunkBool(result)
        }

        // Transformations
        context["__native_nel_reverse"] = NativeFunkFunction("__native_nel_reverse") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_reverse", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_reverse", "expects 'xs' as NonEmptyList")
            FunkNonEmptyList(xs.elements.reversed())
        }

        context["__native_nel_zip"] = NativeFunkFunction("__native_nel_zip") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_zip", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_zip", "expects 'xs' as NonEmptyList")
            val ys = record.fields["ys"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_zip", "expects 'ys' as NonEmptyList")

            val zipped = xs.elements.zip(ys.elements).map { (fst, snd) ->
                FunkRecord(mapOf("fst" to fst, "snd" to snd))
            }
            FunkNonEmptyList(zipped)
        }

        context["__native_nel_intersperse"] = NativeFunkFunction("__native_nel_intersperse") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_intersperse", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_intersperse", "expects 'xs' as NonEmptyList")
            val sep = record.fields["sep"]
                ?: throw NativeFunctionException("__native_nel_intersperse", "expects 'sep' value")

            val interspersed = mutableListOf<FunkValue>()
            xs.elements.forEachIndexed { index, elem ->
                interspersed.add(elem)
                if (index < xs.elements.size - 1) {
                    interspersed.add(sep)
                }
            }
            FunkNonEmptyList(interspersed)
        }

        // Conversion functions
        context["__native_nel_to_list"] = NativeFunkFunction("__native_nel_to_list") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_to_list", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_to_list", "expects 'xs' as NonEmptyList")
            FunkList.fromKotlinList(xs.elements)
        }

        context["__native_nel_from_list"] = NativeFunkFunction("__native_nel_from_list") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_from_list", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_nel_from_list", "expects 'xs' as List")

            val kotlinList = xs.toKotlinList()
            if (kotlinList.isEmpty()) {
                throw NativeFunctionException("__native_nel_from_list", "cannot create NonEmptyList from empty list")
            }
            FunkNonEmptyList(kotlinList)
        }

        context["__native_nel_to_array"] = NativeFunkFunction("__native_nel_to_array") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_to_array", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_to_array", "expects 'xs' as NonEmptyList")
            FunkArrayList(xs.elements)
        }

        context["__native_nel_from_array"] = NativeFunkFunction("__native_nel_from_array") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_from_array", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_nel_from_array", "expects 'xs' as Array")

            if (xs.isEmpty()) {
                throw NativeFunctionException("__native_nel_from_array", "cannot create NonEmptyList from empty array")
            }
            FunkNonEmptyList(xs.toList())
        }

        // Sorting
        context["__native_nel_sort"] = NativeFunkFunction("__native_nel_sort") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_sort", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_sort", "expects 'xs' as NonEmptyList")

            val sorted = xs.elements.sortedWith { a, b ->
                compareValuesForSort(a, b)
            }
            FunkNonEmptyList(sorted)
        }

        context["__native_nel_sort_by"] = NativeFunkFunction("__native_nel_sort_by") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_nel_sort_by", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkNonEmptyList
                ?: throw NativeFunctionException("__native_nel_sort_by", "expects 'xs' as NonEmptyList")
            val cmp = record.fields["cmp"]
                ?: throw NativeFunctionException("__native_nel_sort_by", "expects 'cmp' function")

            val sorted = xs.elements.sortedWith { a, b ->
                val callArg = FunkRecord(mapOf("l" to a, "r" to b))
                val result = callFunction(cmp, callArg, "__native_nel_sort_by")
                (result as? FunkInt)?.value?.toInt()
                    ?: throw NativeFunctionException("__native_nel_sort_by", "comparator must return Int")
            }
            FunkNonEmptyList(sorted)
        }
    }

    /**
     * Compare two FunkValues for equality
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
