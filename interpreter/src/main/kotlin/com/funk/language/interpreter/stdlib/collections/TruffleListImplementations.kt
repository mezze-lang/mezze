package com.funk.language.interpreter.stdlib.collections

import com.funk.language.interpreter.*

/**
 * Native implementations for List operations
 * List is an immutable linked list backed by Kotlin's List for performance
 */
object TruffleListImplementations {

    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Try to get interpreter from context (stored with special key)
        val interpreter = (context["__interpreter_instance"] as? InterpreterReference)?.interpreter
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
            if (xs.elements.isEmpty()) {
                throw NativeFunctionException("__native_list_head", "cannot get head of empty list")
            }
            xs.elements.first()
        }

        context["__native_list_tail"] = NativeFunkFunction("__native_list_tail") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_tail", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_tail", "expects 'xs' as List")
            if (xs.elements.isEmpty()) {
                throw NativeFunctionException("__native_list_tail", "cannot get tail of empty list")
            }
            FunkList(xs.elements.drop(1))
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
            if (idx < 0 || idx >= xs.elements.size) {
                throw NativeFunctionException("__native_list_get", "index out of bounds: $idx")
            }
            xs.elements[idx]
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
                when (f) {
                    is SimpleFunkFunction -> {
                        if (interpreter == null) throw NativeFunctionException("Function callbacks", "Interpreter not available for SimpleFunkFunction")
                        // SimpleFunkFunction expects a single parameter, pass elem directly
                        val arg = if (f.parameters.size == 1) {
                            FunkRecord(mapOf(f.parameters[0] to elem))
                        } else {
                            FunkRecord(emptyMap())
                        }
                        interpreter.callFunctionWithRecord(f, arg, context)
                    }
                    is NativeFunkFunction -> f.implementation(listOf(elem))
                    else -> throw NativeFunctionException("__native_list_map", "f must be a function")
                }
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
                val result = when (p) {
                    is SimpleFunkFunction -> {
                        if (interpreter == null) throw NativeFunctionException("Predicate callbacks", "Interpreter not available for SimpleFunkFunction")
                        val arg = if (p.parameters.size == 1) {
                            FunkRecord(mapOf(p.parameters[0] to elem))
                        } else {
                            FunkRecord(emptyMap())
                        }
                        interpreter.callFunctionWithRecord(p, arg, context)
                    }
                    is NativeFunkFunction -> p.implementation(listOf(elem))
                    else -> throw NativeFunctionException("__native_list_filter", "p must be a function")
                }
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
                acc = when (f) {
                    is SimpleFunkFunction -> {
                        if (interpreter == null) throw NativeFunctionException("Function callbacks", "Interpreter not available for SimpleFunkFunction")
                        interpreter.callFunctionWithRecord(f, callArg, context)
                    }
                    is NativeFunkFunction -> f.implementation(listOf(callArg))
                    else -> throw NativeFunctionException("__native_list_fold_left", "f must be a function")
                }
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
                acc = when (f) {
                    is SimpleFunkFunction -> {
                        if (interpreter == null) throw NativeFunctionException("Function callbacks", "Interpreter not available for SimpleFunkFunction")
                        interpreter.callFunctionWithRecord(f, callArg, context)
                    }
                    is NativeFunkFunction -> f.implementation(listOf(callArg))
                    else -> throw NativeFunctionException("__native_list_fold_right", "f must be a function")
                }
            }
            acc
        }

        context["__native_list_reduce"] = NativeFunkFunction("__native_list_reduce") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_list_reduce", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkList
                ?: throw NativeFunctionException("__native_list_reduce", "expects 'xs' as List")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_list_reduce", "expects 'f' function")

            if (xs.elements.isEmpty()) {
                throw NativeFunctionException("__native_list_reduce", "cannot reduce empty list")
            }

            var acc = xs.elements.first()
            for (elem in xs.elements.drop(1)) {
                val callArg = FunkRecord(mapOf("acc" to acc, "x" to elem))
                acc = when (f) {
                    is SimpleFunkFunction -> {
                        if (interpreter == null) throw NativeFunctionException("Function callbacks", "Interpreter not available for SimpleFunkFunction")
                        interpreter.callFunctionWithRecord(f, callArg, context)
                    }
                    is NativeFunkFunction -> f.implementation(listOf(callArg))
                    else -> throw NativeFunctionException("__native_list_reduce", "f must be a function")
                }
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
                val res = when (p) {
                    is SimpleFunkFunction -> {
                        if (interpreter == null) throw NativeFunctionException("Predicate callbacks", "Interpreter not available for SimpleFunkFunction")
                        val arg = if (p.parameters.size == 1) {
                            FunkRecord(mapOf(p.parameters[0] to elem))
                        } else {
                            FunkRecord(emptyMap())
                        }
                        interpreter.callFunctionWithRecord(p, arg, context)
                    }
                    is NativeFunkFunction -> p.implementation(listOf(elem))
                    else -> throw NativeFunctionException("__native_list_any", "p must be a function")
                }
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
                val res = when (p) {
                    is SimpleFunkFunction -> {
                        if (interpreter == null) throw NativeFunctionException("Predicate callbacks", "Interpreter not available for SimpleFunkFunction")
                        val arg = if (p.parameters.size == 1) {
                            FunkRecord(mapOf(p.parameters[0] to elem))
                        } else {
                            FunkRecord(emptyMap())
                        }
                        interpreter.callFunctionWithRecord(p, arg, context)
                    }
                    is NativeFunkFunction -> p.implementation(listOf(elem))
                    else -> throw NativeFunctionException("__native_list_all", "p must be a function")
                }
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
                val result = when (f) {
                    is SimpleFunkFunction -> {
                        if (interpreter == null) throw NativeFunctionException("Function callbacks", "Interpreter not available for SimpleFunkFunction")
                        val arg = if (f.parameters.size == 1) {
                            FunkRecord(mapOf(f.parameters[0] to elem))
                        } else {
                            FunkRecord(emptyMap())
                        }
                        interpreter.callFunctionWithRecord(f, arg, context)
                    }
                    is NativeFunkFunction -> f.implementation(listOf(elem))
                    else -> throw NativeFunctionException("__native_list_flat_map", "f must be a function")
                }
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
                ?: throw NativeFunctionException("__native_list_from_array", "expects 'xs' as Array")
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
                val result = when (cmp) {
                    is SimpleFunkFunction -> {
                        if (interpreter == null) throw NativeFunctionException("Comparator callbacks", "Interpreter not available for SimpleFunkFunction")
                        interpreter.callFunctionWithRecord(cmp, callArg, context)
                    }
                    is NativeFunkFunction -> cmp.implementation(listOf(callArg))
                    else -> throw NativeFunctionException("__native_list_sort_by", "cmp must be a function")
                }
                (result as? FunkInt)?.value?.toInt()
                    ?: throw NativeFunctionException("__native_list_sort_by", "comparator must return Int")
            }
            FunkList(sorted)
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
