package com.mezze.language.interpreter.stdlib.collections

import com.mezze.language.interpreter.*
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary

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
     * Marked as @TruffleBoundary to prevent JIT compilation issues.
     */
    @TruffleBoundary
    private fun callFunction(f: MezzeValue, arg: MezzeValue, operationName: String): MezzeValue {
        return when (f) {
            is MezzeTruffleFunction -> {
                // Call Truffle function directly via CallTarget
                // Pass closure variables along with arguments
                f.callTarget.call(arg, f.closureVariables) as MezzeValue
            }
            is NativeMezzeFunction -> {
                // Call native function
                f.implementation(listOf(arg))
            }
            else -> {
                throw NativeFunctionException(operationName, "Expected Truffle function or native function, got: ${f::class.simpleName}")
            }
        }
    }

    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Basic non-empty list operations
        context["__native_nel_singleton"] = NativeMezzeFunction("__native_nel_singleton") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_singleton", "expects MezzeRecord")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_nel_singleton", "expects 'x' value")
            MezzeNonEmptyList(listOf(x))
        }

        context["__native_nel_from_head_tail"] = NativeMezzeFunction("__native_nel_from_head_tail") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_from_head_tail", "expects MezzeRecord")
            val head = record.fields["head"]
                ?: throw NativeFunctionException("__native_nel_from_head_tail", "expects 'head' field")
            val tail = record.fields["tail"] as? MezzeList
                ?: throw NativeFunctionException("__native_nel_from_head_tail", "expects 'tail' as List")
            MezzeNonEmptyList(listOf(head) + tail.toKotlinList())
        }

        context["__native_nel_head"] = NativeMezzeFunction("__native_nel_head") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_head", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_head", "expects 'xs' as NonEmptyList")
            xs.elements.first()
        }

        context["__native_nel_tail"] = NativeMezzeFunction("__native_nel_tail") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_tail", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_tail", "expects 'xs' as NonEmptyList")
            MezzeList.fromKotlinList(xs.elements.drop(1))
        }

        context["__native_nel_length"] = NativeMezzeFunction("__native_nel_length") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_length", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_length", "expects 'xs' as NonEmptyList")
            MezzeInt(xs.elements.size.toLong())
        }

        // Construction
        context["__native_nel_cons"] = NativeMezzeFunction("__native_nel_cons") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_cons", "expects MezzeRecord")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_nel_cons", "expects 'x' value")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_cons", "expects 'xs' as NonEmptyList")
            MezzeNonEmptyList(listOf(x) + xs.elements)
        }

        context["__native_nel_append"] = NativeMezzeFunction("__native_nel_append") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_append", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_append", "expects 'xs' as NonEmptyList")
            val ys = record.fields["ys"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_append", "expects 'ys' as NonEmptyList")
            MezzeNonEmptyList(xs.elements + ys.elements)
        }

        context["__native_nel_prepend_list"] = NativeMezzeFunction("__native_nel_prepend_list") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_prepend_list", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_nel_prepend_list", "expects 'xs' as List")
            val y = record.fields["y"]
                ?: throw NativeFunctionException("__native_nel_prepend_list", "expects 'y' value")
            MezzeNonEmptyList(xs.toKotlinList() + listOf(y))
        }

        context["__native_nel_append_list"] = NativeMezzeFunction("__native_nel_append_list") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_append_list", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_append_list", "expects 'xs' as NonEmptyList")
            val ys = record.fields["ys"] as? MezzeList
                ?: throw NativeFunctionException("__native_nel_append_list", "expects 'ys' as List")
            MezzeNonEmptyList(xs.elements + ys.toKotlinList())
        }

        // Access
        context["__native_nel_get"] = NativeMezzeFunction("__native_nel_get") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_get", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_get", "expects 'xs' as NonEmptyList")
            val index = record.fields["index"] as? MezzeInt
                ?: throw NativeFunctionException("__native_nel_get", "expects 'index' as Int")

            val idx = index.value.toInt()
            if (idx < 0 || idx >= xs.elements.size) {
                throw NativeFunctionException("__native_nel_get", "index out of bounds: $idx")
            }
            xs.elements[idx]
        }

        context["__native_nel_last"] = NativeMezzeFunction("__native_nel_last") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_last", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_last", "expects 'xs' as NonEmptyList")
            xs.elements.last()
        }

        // Higher-order functions
        context["__native_nel_map"] = NativeMezzeFunction("__native_nel_map") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_map", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_map", "expects 'xs' as NonEmptyList")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_nel_map", "expects 'f' function")

            val mapped = xs.elements.map { elem ->
                val callArg = MezzeRecord(mapOf("x" to elem))
                callFunction(f, callArg, "__native_nel_map")
            }
            MezzeNonEmptyList(mapped)
        }

        context["__native_nel_flat_map"] = NativeMezzeFunction("__native_nel_flat_map") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_flat_map", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_flat_map", "expects 'xs' as NonEmptyList")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_nel_flat_map", "expects 'f' function")

            val mapped = xs.elements.flatMap { elem ->
                val callArg = MezzeRecord(mapOf("x" to elem))
                val result = callFunction(f, callArg, "__native_nel_flat_map")
                (result as? MezzeNonEmptyList)?.elements
                    ?: throw NativeFunctionException("__native_nel_flat_map", "f must return NonEmptyList")
            }
            MezzeNonEmptyList(mapped)
        }

        context["__native_nel_fold_left"] = NativeMezzeFunction("__native_nel_fold_left") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_fold_left", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_fold_left", "expects 'xs' as NonEmptyList")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_nel_fold_left", "expects 'init' value")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_nel_fold_left", "expects 'f' function")

            var acc = init
            for (elem in xs.elements) {
                val callArg = MezzeRecord(mapOf("acc" to acc, "x" to elem))
                acc = callFunction(f, callArg, "__native_nel_fold_left")
            }
            acc
        }

        context["__native_nel_fold_right"] = NativeMezzeFunction("__native_nel_fold_right") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_fold_right", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_fold_right", "expects 'xs' as NonEmptyList")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_nel_fold_right", "expects 'init' value")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_nel_fold_right", "expects 'f' function")

            var acc = init
            for (elem in xs.elements.reversed()) {
                val callArg = MezzeRecord(mapOf("x" to elem, "acc" to acc))
                acc = callFunction(f, callArg, "__native_nel_fold_right")
            }
            acc
        }

        context["__native_nel_reduce"] = NativeMezzeFunction("__native_nel_reduce") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_reduce", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_reduce", "expects 'xs' as NonEmptyList")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_nel_reduce", "expects 'f' function")

            var acc = xs.elements.first()
            for (elem in xs.elements.drop(1)) {
                val callArg = MezzeRecord(mapOf("acc" to acc, "x" to elem))
                acc = callFunction(f, callArg, "__native_nel_reduce")
            }
            acc
        }

        // Predicates
        context["__native_nel_any"] = NativeMezzeFunction("__native_nel_any") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_any", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_any", "expects 'xs' as NonEmptyList")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_nel_any", "expects 'p' predicate")

            val result = xs.elements.any { elem ->
                val callArg = MezzeRecord(mapOf("x" to elem))
                val res = callFunction(p, callArg, "__native_nel_any")
                (res as? MezzeBool)?.value
                    ?: throw NativeFunctionException("__native_nel_any", "predicate must return Boolean")
            }
            MezzeBool(result)
        }

        context["__native_nel_all"] = NativeMezzeFunction("__native_nel_all") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_all", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_all", "expects 'xs' as NonEmptyList")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_nel_all", "expects 'p' predicate")

            val result = xs.elements.all { elem ->
                val callArg = MezzeRecord(mapOf("x" to elem))
                val res = callFunction(p, callArg, "__native_nel_all")
                (res as? MezzeBool)?.value
                    ?: throw NativeFunctionException("__native_nel_all", "predicate must return Boolean")
            }
            MezzeBool(result)
        }

        context["__native_nel_contains"] = NativeMezzeFunction("__native_nel_contains") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_contains", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_contains", "expects 'xs' as NonEmptyList")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_nel_contains", "expects 'x' value")

            val result = xs.elements.any { elem -> compareValues(elem, x) }
            MezzeBool(result)
        }

        // Transformations
        context["__native_nel_reverse"] = NativeMezzeFunction("__native_nel_reverse") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_reverse", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_reverse", "expects 'xs' as NonEmptyList")
            MezzeNonEmptyList(xs.elements.reversed())
        }

        context["__native_nel_zip"] = NativeMezzeFunction("__native_nel_zip") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_zip", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_zip", "expects 'xs' as NonEmptyList")
            val ys = record.fields["ys"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_zip", "expects 'ys' as NonEmptyList")

            val zipped = xs.elements.zip(ys.elements).map { (fst, snd) ->
                MezzeRecord(mapOf("fst" to fst, "snd" to snd))
            }
            MezzeNonEmptyList(zipped)
        }

        context["__native_nel_intersperse"] = NativeMezzeFunction("__native_nel_intersperse") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_intersperse", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_intersperse", "expects 'xs' as NonEmptyList")
            val sep = record.fields["sep"]
                ?: throw NativeFunctionException("__native_nel_intersperse", "expects 'sep' value")

            val interspersed = mutableListOf<MezzeValue>()
            xs.elements.forEachIndexed { index, elem ->
                interspersed.add(elem)
                if (index < xs.elements.size - 1) {
                    interspersed.add(sep)
                }
            }
            MezzeNonEmptyList(interspersed)
        }

        // Conversion functions
        context["__native_nel_to_list"] = NativeMezzeFunction("__native_nel_to_list") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_to_list", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_to_list", "expects 'xs' as NonEmptyList")
            MezzeList.fromKotlinList(xs.elements)
        }

        context["__native_nel_from_list"] = NativeMezzeFunction("__native_nel_from_list") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_from_list", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeList
                ?: throw NativeFunctionException("__native_nel_from_list", "expects 'xs' as List")

            val kotlinList = xs.toKotlinList()
            if (kotlinList.isEmpty()) {
                throw NativeFunctionException("__native_nel_from_list", "cannot create NonEmptyList from empty list")
            }
            MezzeNonEmptyList(kotlinList)
        }

        context["__native_nel_to_array"] = NativeMezzeFunction("__native_nel_to_array") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_to_array", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_to_array", "expects 'xs' as NonEmptyList")
            MezzeArrayList(xs.elements)
        }

        context["__native_nel_from_array"] = NativeMezzeFunction("__native_nel_from_array") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_from_array", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_nel_from_array", "expects 'xs' as Array")

            if (xs.isEmpty()) {
                throw NativeFunctionException("__native_nel_from_array", "cannot create NonEmptyList from empty array")
            }
            MezzeNonEmptyList(xs.toList())
        }

        // Sorting
        context["__native_nel_sort"] = NativeMezzeFunction("__native_nel_sort") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_sort", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_sort", "expects 'xs' as NonEmptyList")

            val sorted = xs.elements.sortedWith { a, b ->
                compareValuesForSort(a, b)
            }
            MezzeNonEmptyList(sorted)
        }

        context["__native_nel_sort_by"] = NativeMezzeFunction("__native_nel_sort_by") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_nel_sort_by", "expects MezzeRecord")
            val xs = record.fields["xs"] as? MezzeNonEmptyList
                ?: throw NativeFunctionException("__native_nel_sort_by", "expects 'xs' as NonEmptyList")
            val cmp = record.fields["cmp"]
                ?: throw NativeFunctionException("__native_nel_sort_by", "expects 'cmp' function")

            val sorted = xs.elements.sortedWith { a, b ->
                val callArg = MezzeRecord(mapOf("l" to a, "r" to b))
                val result = callFunction(cmp, callArg, "__native_nel_sort_by")
                (result as? MezzeInt)?.value?.toInt()
                    ?: throw NativeFunctionException("__native_nel_sort_by", "comparator must return Int")
            }
            MezzeNonEmptyList(sorted)
        }
    }

    /**
     * Compare two MezzeValues for equality
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
