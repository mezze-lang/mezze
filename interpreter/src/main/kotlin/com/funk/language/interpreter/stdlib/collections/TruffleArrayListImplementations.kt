package com.funk.language.interpreter.stdlib.collections

import com.funk.language.interpreter.*

/**
 * Native implementations for ArrayList operations
 * ArrayList is a mutable array-based collection backed by Java ArrayList
 */
object TruffleArrayListImplementations {

    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Basic ArrayList operations
        context["__native_arraylist_empty"] = NativeFunkFunction("__native_arraylist_empty") { args ->
            FunkArrayList()
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
            if (i < 0 || i >= xs.size()) {
                throw NativeFunctionException("__native_arraylist_get", "index out of bounds: $i")
            }
            xs.get(i)
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
            if (i < 0 || i >= xs.size()) {
                throw NativeFunctionException("__native_arraylist_set", "index out of bounds: $i")
            }
            // Create a new ArrayList with the updated element (immutable)
            val newElements = xs.elements.toMutableList()
            newElements[i] = x
            FunkArrayList(newElements)
        }

        context["__native_arraylist_add"] = NativeFunkFunction("__native_arraylist_add") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_add", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_add", "expects 'xs' as ArrayList")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_arraylist_add", "expects 'x' field")
            // Create a new ArrayList with the added element (immutable)
            val newElements = xs.elements.toMutableList()
            newElements.add(x)
            FunkArrayList(newElements)
        }

        context["__native_arraylist_remove"] = NativeFunkFunction("__native_arraylist_remove") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_remove", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_remove", "expects 'xs' as ArrayList")
            val i = (record.fields["i"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_arraylist_remove", "expects 'i' as Int")
            if (i < 0 || i >= xs.size()) {
                throw NativeFunctionException("__native_arraylist_remove", "index out of bounds: $i")
            }
            // Create a new ArrayList without the element (immutable)
            val newElements = xs.elements.toMutableList()
            newElements.removeAt(i)
            FunkArrayList(newElements)
        }

        context["__native_arraylist_concat"] = NativeFunkFunction("__native_arraylist_concat") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_concat", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_concat", "expects 'xs' as ArrayList")
            val ys = record.fields["ys"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_concat", "expects 'ys' as ArrayList")
            FunkArrayList(xs.elements + ys.elements)
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
            val f = record.fields["f"] as? FunkFunction
                ?: throw NativeFunctionException("__native_arraylist_map", "expects 'f' as Function")

            val interpreter = (context["__interpreter_instance"] as? InterpreterReference)?.interpreter
                ?: throw NativeFunctionException("__native_arraylist_map", "interpreter not available")

            val mapped = xs.elements.map { element ->
                interpreter.callFunctionWithRecord(f, FunkRecord(mapOf("x" to element)), context.toMutableMap())
            }
            FunkArrayList(mapped)
        }

        context["__native_arraylist_filter"] = NativeFunkFunction("__native_arraylist_filter") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_filter", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_filter", "expects 'xs' as ArrayList")
            val p = record.fields["p"] as? FunkFunction
                ?: throw NativeFunctionException("__native_arraylist_filter", "expects 'p' as Function")

            val interpreter = (context["__interpreter_instance"] as? InterpreterReference)?.interpreter
                ?: throw NativeFunctionException("__native_arraylist_filter", "interpreter not available")

            val filtered = xs.elements.filter { element ->
                val result = interpreter.callFunctionWithRecord(p, FunkRecord(mapOf("x" to element)), context.toMutableMap())
                (result as? FunkBool)?.value ?: false
            }
            FunkArrayList(filtered)
        }

        context["__native_arraylist_fold"] = NativeFunkFunction("__native_arraylist_fold") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_fold", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_fold", "expects 'xs' as ArrayList")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_arraylist_fold", "expects 'init' field")
            val f = record.fields["f"] as? FunkFunction
                ?: throw NativeFunctionException("__native_arraylist_fold", "expects 'f' as Function")

            val interpreter = (context["__interpreter_instance"] as? InterpreterReference)?.interpreter
                ?: throw NativeFunctionException("__native_arraylist_fold", "interpreter not available")

            xs.elements.fold(init) { acc, element ->
                interpreter.callFunctionWithRecord(f, FunkRecord(mapOf("acc" to acc, "x" to element)), context.toMutableMap())
            }
        }

        context["__native_arraylist_reduce"] = NativeFunkFunction("__native_arraylist_reduce") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_reduce", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_reduce", "expects 'xs' as ArrayList")
            val f = record.fields["f"] as? FunkFunction
                ?: throw NativeFunctionException("__native_arraylist_reduce", "expects 'f' as Function")

            if (xs.isEmpty()) {
                throw NativeFunctionException("__native_arraylist_reduce", "cannot reduce empty arraylist")
            }

            val interpreter = (context["__interpreter_instance"] as? InterpreterReference)?.interpreter
                ?: throw NativeFunctionException("__native_arraylist_reduce", "interpreter not available")

            xs.elements.drop(1).fold(xs.elements.first()) { acc, element ->
                interpreter.callFunctionWithRecord(f, FunkRecord(mapOf("acc" to acc, "x" to element)), context.toMutableMap())
            }
        }

        // Utility functions
        context["__native_arraylist_find"] = NativeFunkFunction("__native_arraylist_find") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_find", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_find", "expects 'xs' as ArrayList")
            val p = record.fields["p"] as? FunkFunction
                ?: throw NativeFunctionException("__native_arraylist_find", "expects 'p' as Function")

            val interpreter = (context["__interpreter_instance"] as? InterpreterReference)?.interpreter
                ?: throw NativeFunctionException("__native_arraylist_find", "interpreter not available")

            val found = xs.elements.find { element ->
                val result = interpreter.callFunctionWithRecord(p, FunkRecord(mapOf("x" to element)), context.toMutableMap())
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
            FunkBool(xs.elements.contains(x))
        }

        context["__native_arraylist_index_of"] = NativeFunkFunction("__native_arraylist_index_of") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_index_of", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_index_of", "expects 'xs' as ArrayList")
            val x = record.fields["x"]
                ?: throw NativeFunctionException("__native_arraylist_index_of", "expects 'x' field")
            val index = xs.elements.indexOf(x)
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
            FunkArrayList(xs.elements.reversed())
        }

        context["__native_arraylist_sort"] = NativeFunkFunction("__native_arraylist_sort") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_sort", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_sort", "expects 'xs' as ArrayList")
            // Note: This assumes elements are comparable (Int, Float, String, etc.)
            FunkArrayList(xs.elements.sortedWith(compareBy { it.toString() }))
        }

        context["__native_arraylist_sort_by"] = NativeFunkFunction("__native_arraylist_sort_by") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_arraylist_sort_by", "expects FunkRecord")
            val xs = record.fields["xs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_arraylist_sort_by", "expects 'xs' as ArrayList")
            val cmp = record.fields["cmp"] as? FunkFunction
                ?: throw NativeFunctionException("__native_arraylist_sort_by", "expects 'cmp' as Function")

            val interpreter = (context["__interpreter_instance"] as? InterpreterReference)?.interpreter
                ?: throw NativeFunctionException("__native_arraylist_sort_by", "interpreter not available")

            val sorted = xs.elements.sortedWith { a, b ->
                val result = interpreter.callFunctionWithRecord(cmp, FunkRecord(mapOf("l" to a, "r" to b)), context.toMutableMap())
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
                FunkArrayList(emptyList())
            } else {
                FunkArrayList(xs.elements.subList(safeStart, safeEnd))
            }
        }
    }
}
