package com.funk.language.interpreter.stdlib.collections

import com.funk.language.interpreter.*

/**
 * Truffle-optimized implementations for Bytes operations.
 *
 * Bytes is a primitive type for binary data, providing compact storage
 * and efficient operations for working with raw byte sequences.
 *
 * Performance characteristics:
 * - length: O(1)
 * - get: O(1)
 * - slice: O(k) where k = slice size
 * - concat: O(n + m)
 * - indexOf: O(n * m) naive search
 */
object TruffleBytesImplementations {

    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Construction
        context["__native_bytes_empty"] = NativeFunkFunction("__native_bytes_empty") { _ ->
            FunkBytes.empty()
        }

        context["__native_bytes_from_string"] = NativeFunkFunction("__native_bytes_from_string") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_from_string", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString
                ?: throw NativeFunctionException("__native_bytes_from_string", "expects 's' as String")
            FunkBytes.fromString(s.value)
        }

        context["__native_bytes_from_array"] = NativeFunkFunction("__native_bytes_from_array") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_from_array", "expects FunkRecord")
            val arr = record.fields["arr"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_bytes_from_array", "expects 'arr' as Array")
            val ints = arr.toList().map { (it as FunkInt).value.toInt() }
            FunkBytes.fromArray(ints)
        }

        context["__native_bytes_from_hex"] = NativeFunkFunction("__native_bytes_from_hex") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_from_hex", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString
                ?: throw NativeFunctionException("__native_bytes_from_hex", "expects 's' as String")
            try {
                val b = FunkBytes.fromHex(s.value)
                FunkVariant("Some", FunkRecord(mapOf("val" to b)))
            } catch (e: Exception) {
                FunkVariant("None", null)
            }
        }

        context["__native_bytes_from_base64"] = NativeFunkFunction("__native_bytes_from_base64") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_from_base64", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString
                ?: throw NativeFunctionException("__native_bytes_from_base64", "expects 's' as String")
            try {
                val b = FunkBytes.fromBase64(s.value)
                FunkVariant("Some", FunkRecord(mapOf("val" to b)))
            } catch (e: Exception) {
                FunkVariant("None", null)
            }
        }

        context["__native_bytes_repeat"] = NativeFunkFunction("__native_bytes_repeat") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_repeat", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_repeat", "expects 'b' as Bytes")
            val n = (record.fields["n"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_bytes_repeat", "expects 'n' as Int")
            b.repeat(n)
        }

        // Access
        context["__native_bytes_length"] = NativeFunkFunction("__native_bytes_length") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_length", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_length", "expects 'b' as Bytes")
            FunkInt(b.length().toLong())
        }

        context["__native_bytes_get"] = NativeFunkFunction("__native_bytes_get") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_get", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_get", "expects 'b' as Bytes")
            val i = (record.fields["i"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_bytes_get", "expects 'i' as Int")

            when (val byte = b.get(i)) {
                null -> FunkVariant("None", null)
                else -> FunkVariant("Some", FunkRecord(mapOf("val" to FunkInt(byte.toLong()))))
            }
        }

        context["__native_bytes_is_empty"] = NativeFunkFunction("__native_bytes_is_empty") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_is_empty", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_is_empty", "expects 'b' as Bytes")
            FunkBool(b.isEmpty())
        }

        // Slicing
        context["__native_bytes_slice"] = NativeFunkFunction("__native_bytes_slice") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_slice", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_slice", "expects 'b' as Bytes")
            val start = (record.fields["start"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_bytes_slice", "expects 'start' as Int")
            val end = (record.fields["end"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_bytes_slice", "expects 'end' as Int")
            b.slice(start, end)
        }

        context["__native_bytes_take"] = NativeFunkFunction("__native_bytes_take") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_take", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_take", "expects 'b' as Bytes")
            val n = (record.fields["n"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_bytes_take", "expects 'n' as Int")
            b.take(n)
        }

        context["__native_bytes_drop"] = NativeFunkFunction("__native_bytes_drop") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_drop", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_drop", "expects 'b' as Bytes")
            val n = (record.fields["n"] as? FunkInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_bytes_drop", "expects 'n' as Int")
            b.drop(n)
        }

        // Combination
        context["__native_bytes_concat"] = NativeFunkFunction("__native_bytes_concat") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_concat", "expects FunkRecord")
            val b1 = record.fields["b1"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_concat", "expects 'b1' as Bytes")
            val b2 = record.fields["b2"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_concat", "expects 'b2' as Bytes")
            b1.concat(b2)
        }

        context["__native_bytes_concat_all"] = NativeFunkFunction("__native_bytes_concat_all") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_concat_all", "expects FunkRecord")
            val bs = record.fields["bs"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_bytes_concat_all", "expects 'bs' as Array")

            val bytesList = bs.toList().map { it as FunkBytes }
            val totalSize = bytesList.sumOf { it.length() }
            val result = ByteArray(totalSize)
            var offset = 0
            for (b in bytesList) {
                System.arraycopy(b.bytes, 0, result, offset, b.bytes.size)
                offset += b.bytes.size
            }
            FunkBytes(result)
        }

        // Search
        context["__native_bytes_index_of"] = NativeFunkFunction("__native_bytes_index_of") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_index_of", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_index_of", "expects 'b' as Bytes")
            val pattern = record.fields["pattern"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_index_of", "expects 'pattern' as Bytes")

            val idx = b.indexOf(pattern)
            if (idx >= 0) {
                FunkVariant("Some", FunkRecord(mapOf("val" to FunkInt(idx.toLong()))))
            } else {
                FunkVariant("None", null)
            }
        }

        context["__native_bytes_contains"] = NativeFunkFunction("__native_bytes_contains") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_contains", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_contains", "expects 'b' as Bytes")
            val pattern = record.fields["pattern"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_contains", "expects 'pattern' as Bytes")
            FunkBool(b.contains(pattern))
        }

        context["__native_bytes_starts_with"] = NativeFunkFunction("__native_bytes_starts_with") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_starts_with", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_starts_with", "expects 'b' as Bytes")
            val prefix = record.fields["prefix"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_starts_with", "expects 'prefix' as Bytes")
            FunkBool(b.startsWith(prefix))
        }

        context["__native_bytes_ends_with"] = NativeFunkFunction("__native_bytes_ends_with") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_ends_with", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_ends_with", "expects 'b' as Bytes")
            val suffix = record.fields["suffix"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_ends_with", "expects 'suffix' as Bytes")
            FunkBool(b.endsWith(suffix))
        }

        // Comparison
        context["__native_bytes_equals"] = NativeFunkFunction("__native_bytes_equals") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_equals", "expects FunkRecord")
            val b1 = record.fields["b1"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_equals", "expects 'b1' as Bytes")
            val b2 = record.fields["b2"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_equals", "expects 'b2' as Bytes")
            FunkBool(b1 == b2)
        }

        // Conversion
        context["__native_bytes_to_string"] = NativeFunkFunction("__native_bytes_to_string") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_to_string", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_to_string", "expects 'b' as Bytes")
            try {
                val s = b.toStringValue()
                FunkVariant("Ok", FunkRecord(mapOf("val" to FunkString(s))))
            } catch (e: Exception) {
                FunkVariant("Err", FunkRecord(mapOf("error" to FunkString("Invalid UTF-8"))))
            }
        }

        context["__native_bytes_to_array"] = NativeFunkFunction("__native_bytes_to_array") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_to_array", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_to_array", "expects 'b' as Bytes")
            FunkArrayList(b.toArray().map { FunkInt(it.toLong()) })
        }

        context["__native_bytes_to_hex"] = NativeFunkFunction("__native_bytes_to_hex") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_to_hex", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_to_hex", "expects 'b' as Bytes")
            FunkString(b.toHex())
        }

        context["__native_bytes_to_base64"] = NativeFunkFunction("__native_bytes_to_base64") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_to_base64", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_to_base64", "expects 'b' as Bytes")
            FunkString(b.toBase64())
        }

        // Transformation
        context["__native_bytes_reverse"] = NativeFunkFunction("__native_bytes_reverse") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_reverse", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_reverse", "expects 'b' as Bytes")
            b.reverse()
        }

        context["__native_bytes_map"] = NativeFunkFunction("__native_bytes_map") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_map", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_map", "expects 'b' as Bytes")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_bytes_map", "expects 'f' as Function")

            val mapped = ByteArray(b.length())
            for (i in 0 until b.length()) {
                val byteVal = b.get(i)!!
                val argRecord = FunkRecord(mapOf("x" to FunkInt(byteVal.toLong())))
                val result = when (f) {
                    is FunkTruffleFunction -> {
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
                        "__native_bytes_map",
                        "f must be a function, got: ${f::class.simpleName}"
                    )
                }
                mapped[i] = ((result as FunkInt).value.toInt() and 0xFF).toByte()
            }
            FunkBytes(mapped)
        }

        context["__native_bytes_fold"] = NativeFunkFunction("__native_bytes_fold") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_bytes_fold", "expects FunkRecord")
            val b = record.fields["b"] as? FunkBytes
                ?: throw NativeFunctionException("__native_bytes_fold", "expects 'b' as Bytes")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_bytes_fold", "expects 'init' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_bytes_fold", "expects 'f' as Function")

            var accumulator = init
            for (i in 0 until b.length()) {
                val byteVal = b.get(i)!!
                val argRecord = FunkRecord(mapOf("acc" to accumulator, "byte" to FunkInt(byteVal.toLong())))
                accumulator = when (f) {
                    is FunkTruffleFunction -> {
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
                        "__native_bytes_fold",
                        "f must be a function, got: ${f::class.simpleName}"
                    )
                }
            }
            accumulator
        }
    }
}
