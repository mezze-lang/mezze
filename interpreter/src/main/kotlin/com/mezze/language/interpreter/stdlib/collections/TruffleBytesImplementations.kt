package com.mezze.language.interpreter.stdlib.collections

import com.mezze.language.interpreter.*

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

    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Construction
        context["__native_bytes_empty"] = NativeMezzeFunction("__native_bytes_empty") { _ ->
            MezzeBytes.empty()
        }

        context["__native_bytes_from_string"] = NativeMezzeFunction("__native_bytes_from_string") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_from_string", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString
                ?: throw NativeFunctionException("__native_bytes_from_string", "expects 's' as String")
            MezzeBytes.fromString(s.value)
        }

        context["__native_bytes_from_array"] = NativeMezzeFunction("__native_bytes_from_array") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_from_array", "expects MezzeRecord")
            val arr = record.fields["arr"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_bytes_from_array", "expects 'arr' as Array")
            val ints = arr.toList().map { (it as MezzeInt).value.toInt() }
            MezzeBytes.fromArray(ints)
        }

        context["__native_bytes_from_hex"] = NativeMezzeFunction("__native_bytes_from_hex") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_from_hex", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString
                ?: throw NativeFunctionException("__native_bytes_from_hex", "expects 's' as String")
            try {
                val b = MezzeBytes.fromHex(s.value)
                mezzeOptionSome(b)
            } catch (e: Exception) {
                mezzeOptionNone()
            }
        }

        context["__native_bytes_from_base64"] = NativeMezzeFunction("__native_bytes_from_base64") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_from_base64", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString
                ?: throw NativeFunctionException("__native_bytes_from_base64", "expects 's' as String")
            try {
                val b = MezzeBytes.fromBase64(s.value)
                mezzeOptionSome(b)
            } catch (e: Exception) {
                mezzeOptionNone()
            }
        }

        context["__native_bytes_repeat"] = NativeMezzeFunction("__native_bytes_repeat") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_repeat", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_repeat", "expects 'b' as Bytes")
            val n = (record.fields["n"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_bytes_repeat", "expects 'n' as Int")
            b.repeat(n)
        }

        // Access
        context["__native_bytes_length"] = NativeMezzeFunction("__native_bytes_length") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_length", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_length", "expects 'b' as Bytes")
            MezzeInt(b.length().toLong())
        }

        context["__native_bytes_get"] = NativeMezzeFunction("__native_bytes_get") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_get", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_get", "expects 'b' as Bytes")
            val i = (record.fields["i"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_bytes_get", "expects 'i' as Int")

            when (val byte = b.get(i)) {
                null -> mezzeOptionNone()
                else -> mezzeOptionSome(MezzeInt(byte.toLong()))
            }
        }

        context["__native_bytes_is_empty"] = NativeMezzeFunction("__native_bytes_is_empty") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_is_empty", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_is_empty", "expects 'b' as Bytes")
            MezzeBool(b.isEmpty())
        }

        // Slicing
        context["__native_bytes_slice"] = NativeMezzeFunction("__native_bytes_slice") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_slice", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_slice", "expects 'b' as Bytes")
            val start = (record.fields["start"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_bytes_slice", "expects 'start' as Int")
            val end = (record.fields["end"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_bytes_slice", "expects 'end' as Int")
            b.slice(start, end)
        }

        context["__native_bytes_take"] = NativeMezzeFunction("__native_bytes_take") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_take", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_take", "expects 'b' as Bytes")
            val n = (record.fields["n"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_bytes_take", "expects 'n' as Int")
            b.take(n)
        }

        context["__native_bytes_drop"] = NativeMezzeFunction("__native_bytes_drop") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_drop", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_drop", "expects 'b' as Bytes")
            val n = (record.fields["n"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_bytes_drop", "expects 'n' as Int")
            b.drop(n)
        }

        // Combination
        context["__native_bytes_concat"] = NativeMezzeFunction("__native_bytes_concat") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_concat", "expects MezzeRecord")
            val b1 = record.fields["b1"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_concat", "expects 'b1' as Bytes")
            val b2 = record.fields["b2"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_concat", "expects 'b2' as Bytes")
            b1.concat(b2)
        }

        context["__native_bytes_concat_all"] = NativeMezzeFunction("__native_bytes_concat_all") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_concat_all", "expects MezzeRecord")
            val bs = record.fields["bs"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_bytes_concat_all", "expects 'bs' as Array")

            val bytesList = bs.toList().map { it as MezzeBytes }
            val totalSize = bytesList.sumOf { it.length() }
            val result = ByteArray(totalSize)
            var offset = 0
            for (b in bytesList) {
                System.arraycopy(b.bytes, 0, result, offset, b.bytes.size)
                offset += b.bytes.size
            }
            MezzeBytes(result)
        }

        // Search
        context["__native_bytes_index_of"] = NativeMezzeFunction("__native_bytes_index_of") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_index_of", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_index_of", "expects 'b' as Bytes")
            val pattern = record.fields["pattern"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_index_of", "expects 'pattern' as Bytes")

            val idx = b.indexOf(pattern)
            if (idx >= 0) {
                mezzeOptionSome(MezzeInt(idx.toLong()))
            } else {
                mezzeOptionNone()
            }
        }

        context["__native_bytes_contains"] = NativeMezzeFunction("__native_bytes_contains") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_contains", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_contains", "expects 'b' as Bytes")
            val pattern = record.fields["pattern"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_contains", "expects 'pattern' as Bytes")
            MezzeBool(b.contains(pattern))
        }

        context["__native_bytes_starts_with"] = NativeMezzeFunction("__native_bytes_starts_with") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_starts_with", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_starts_with", "expects 'b' as Bytes")
            val prefix = record.fields["prefix"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_starts_with", "expects 'prefix' as Bytes")
            MezzeBool(b.startsWith(prefix))
        }

        context["__native_bytes_ends_with"] = NativeMezzeFunction("__native_bytes_ends_with") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_ends_with", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_ends_with", "expects 'b' as Bytes")
            val suffix = record.fields["suffix"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_ends_with", "expects 'suffix' as Bytes")
            MezzeBool(b.endsWith(suffix))
        }

        // Comparison
        context["__native_bytes_equals"] = NativeMezzeFunction("__native_bytes_equals") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_equals", "expects MezzeRecord")
            val b1 = record.fields["b1"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_equals", "expects 'b1' as Bytes")
            val b2 = record.fields["b2"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_equals", "expects 'b2' as Bytes")
            MezzeBool(b1 == b2)
        }

        // Conversion
        context["__native_bytes_to_string"] = NativeMezzeFunction("__native_bytes_to_string") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_to_string", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_to_string", "expects 'b' as Bytes")
            try {
                val s = b.toStringValue()
                mezzeResultOk(MezzeString(s))
            } catch (e: Exception) {
                mezzeResultErr(MezzeString("Invalid UTF-8"))
            }
        }

        context["__native_bytes_to_array"] = NativeMezzeFunction("__native_bytes_to_array") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_to_array", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_to_array", "expects 'b' as Bytes")
            MezzeArrayList(b.toArray().map { MezzeInt(it.toLong()) })
        }

        context["__native_bytes_to_hex"] = NativeMezzeFunction("__native_bytes_to_hex") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_to_hex", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_to_hex", "expects 'b' as Bytes")
            MezzeString(b.toHex())
        }

        context["__native_bytes_to_base64"] = NativeMezzeFunction("__native_bytes_to_base64") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_to_base64", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_to_base64", "expects 'b' as Bytes")
            MezzeString(b.toBase64())
        }

        // Transformation
        context["__native_bytes_reverse"] = NativeMezzeFunction("__native_bytes_reverse") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_reverse", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_reverse", "expects 'b' as Bytes")
            b.reverse()
        }

        context["__native_bytes_map"] = NativeMezzeFunction("__native_bytes_map") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_map", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_map", "expects 'b' as Bytes")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_bytes_map", "expects 'f' as Function")

            val mapped = ByteArray(b.length())
            for (i in 0 until b.length()) {
                val byteVal = b.get(i)!!
                val argRecord = MezzeRecord(mapOf("x" to MezzeInt(byteVal.toLong())))
                val result = when (f) {
                    is MezzeTruffleFunction -> {
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
                        "__native_bytes_map",
                        "f must be a function, got: ${f::class.simpleName}"
                    )
                }
                mapped[i] = ((result as MezzeInt).value.toInt() and 0xFF).toByte()
            }
            MezzeBytes(mapped)
        }

        context["__native_bytes_fold"] = NativeMezzeFunction("__native_bytes_fold") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_bytes_fold", "expects MezzeRecord")
            val b = record.fields["b"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_bytes_fold", "expects 'b' as Bytes")
            val init = record.fields["init"]
                ?: throw NativeFunctionException("__native_bytes_fold", "expects 'init' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_bytes_fold", "expects 'f' as Function")

            var accumulator = init
            for (i in 0 until b.length()) {
                val byteVal = b.get(i)!!
                val argRecord = MezzeRecord(mapOf("acc" to accumulator, "byte" to MezzeInt(byteVal.toLong())))
                accumulator = when (f) {
                    is MezzeTruffleFunction -> {
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
                        "__native_bytes_fold",
                        "f must be a function, got: ${f::class.simpleName}"
                    )
                }
            }
            accumulator
        }
    }
}
