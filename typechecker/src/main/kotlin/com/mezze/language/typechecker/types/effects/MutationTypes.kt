package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar

/**
 * Type definitions for safe local mutation operations.
 *
 * The Mutation effect provides compile-time safe in-place mutation.
 * It is "hermetic" - cannot coexist with other effects to prevent data races.
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 */
object MutationTypes {

    /**
     * Register all mutation native function types.
     */
    fun registerAll(context: MutableMap<String, MezzeType>) {
        // =================================================================
        // Sequence conversions
        // =================================================================

        // __native_seq_to_mut: {seq: ['a]} -> MutSeq 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val seqType = MezzeType.SeqType(elementType)
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            context["__native_seq_to_mut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to seqType)),
                mutSeqType
            )
        }

        // __native_seq_to_immut: {seq: MutSeq 'a} -> ['a]
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val seqType = MezzeType.SeqType(elementType)
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            context["__native_seq_to_immut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType)),
                seqType
            )
        }

        // __native_mut_seq_with_capacity: {capacity: Int} -> MutSeq 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            context["__native_mut_seq_with_capacity"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("capacity" to MezzeType.IntType)),
                mutSeqType
            )
        }

        // =================================================================
        // Map conversions
        // =================================================================

        // __native_map_to_mut: {map: {'k: 'v}} -> MutMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val mapType = MezzeType.MapType(keyType, valueType)
            val mutMapType = MezzeType.TypeApplication("MutMap", listOf(keyType, valueType))
            context["__native_map_to_mut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("map" to mapType)),
                mutMapType
            )
        }

        // __native_map_to_immut: {map: MutMap 'k 'v} -> Map 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val mapType = MezzeType.MapType(keyType, valueType)
            val mutMapType = MezzeType.TypeApplication("MutMap", listOf(keyType, valueType))
            context["__native_map_to_immut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("map" to mutMapType)),
                mapType
            )
        }

        // __native_mut_map_with_capacity: {capacity: Int} -> MutMap 'k 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val mutMapType = MezzeType.TypeApplication("MutMap", listOf(keyType, valueType))
            context["__native_mut_map_with_capacity"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("capacity" to MezzeType.IntType)),
                mutMapType
            )
        }

        // =================================================================
        // Set conversions
        // =================================================================

        // __native_set_to_mut: {set: Set 'a} -> MutSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val setType = MezzeType.TypeApplication("Set", listOf(elementType))
            val mutSetType = MezzeType.TypeApplication("MutSet", listOf(elementType))
            context["__native_set_to_mut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("set" to setType)),
                mutSetType
            )
        }

        // __native_set_to_immut: {set: MutSet 'a} -> Set 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val setType = MezzeType.TypeApplication("Set", listOf(elementType))
            val mutSetType = MezzeType.TypeApplication("MutSet", listOf(elementType))
            context["__native_set_to_immut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("set" to mutSetType)),
                setType
            )
        }

        // __native_mut_set_with_capacity: {capacity: Int} -> MutSet 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSetType = MezzeType.TypeApplication("MutSet", listOf(elementType))
            context["__native_mut_set_with_capacity"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("capacity" to MezzeType.IntType)),
                mutSetType
            )
        }

        // =================================================================
        // MutMap operations
        // =================================================================

        // __native_mut_map_get: {map: MutMap 'k 'v, key: 'k} -> Option 'v
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val mutMapType = MezzeType.TypeApplication("MutMap", listOf(keyType, valueType))
            val optionType = MezzeType.TypeApplication("Option", listOf(valueType))
            context["__native_mut_map_get"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("map" to mutMapType, "key" to keyType)),
                optionType
            )
        }

        // __native_mut_map_put: {map: MutMap 'k 'v, key: 'k, value: 'v} -> {}
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val mutMapType = MezzeType.TypeApplication("MutMap", listOf(keyType, valueType))
            context["__native_mut_map_put"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("map" to mutMapType, "key" to keyType, "value" to valueType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_map_remove: {map: MutMap 'k 'v, key: 'k} -> {}
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val mutMapType = MezzeType.TypeApplication("MutMap", listOf(keyType, valueType))
            context["__native_mut_map_remove"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("map" to mutMapType, "key" to keyType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_map_contains: {map: MutMap 'k 'v, key: 'k} -> Bool
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val mutMapType = MezzeType.TypeApplication("MutMap", listOf(keyType, valueType))
            context["__native_mut_map_contains"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("map" to mutMapType, "key" to keyType)),
                MezzeType.BoolType
            )
        }

        // __native_mut_map_size: {map: MutMap 'k 'v} -> Int
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val mutMapType = MezzeType.TypeApplication("MutMap", listOf(keyType, valueType))
            context["__native_mut_map_size"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("map" to mutMapType)),
                MezzeType.IntType
            )
        }

        // __native_mut_map_clear: {map: MutMap 'k 'v} -> {}
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val mutMapType = MezzeType.TypeApplication("MutMap", listOf(keyType, valueType))
            context["__native_mut_map_clear"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("map" to mutMapType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_map_keys: {map: MutMap 'k 'v} -> ['k]
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val mutMapType = MezzeType.TypeApplication("MutMap", listOf(keyType, valueType))
            context["__native_mut_map_keys"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("map" to mutMapType)),
                MezzeType.SeqType(keyType)
            )
        }

        // __native_mut_map_values: {map: MutMap 'k 'v} -> ['v]
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val mutMapType = MezzeType.TypeApplication("MutMap", listOf(keyType, valueType))
            context["__native_mut_map_values"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("map" to mutMapType)),
                MezzeType.SeqType(valueType)
            )
        }

        // __native_mut_map_entries: {map: MutMap 'k 'v} -> [{key: 'k, value: 'v}]
        run {
            val keyType = MezzeType.VarType(TypeVar("k"))
            val valueType = MezzeType.VarType(TypeVar("v"))
            val mutMapType = MezzeType.TypeApplication("MutMap", listOf(keyType, valueType))
            val entryType = MezzeType.RecordType(mapOf("key" to keyType, "value" to valueType))
            context["__native_mut_map_entries"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("map" to mutMapType)),
                MezzeType.SeqType(entryType)
            )
        }

        // =================================================================
        // MutSet operations
        // =================================================================

        // __native_mut_set_add: {set: MutSet 'a, elem: 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSetType = MezzeType.TypeApplication("MutSet", listOf(elementType))
            context["__native_mut_set_add"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("set" to mutSetType, "elem" to elementType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_set_remove: {set: MutSet 'a, elem: 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSetType = MezzeType.TypeApplication("MutSet", listOf(elementType))
            context["__native_mut_set_remove"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("set" to mutSetType, "elem" to elementType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_set_contains: {set: MutSet 'a, elem: 'a} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSetType = MezzeType.TypeApplication("MutSet", listOf(elementType))
            context["__native_mut_set_contains"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("set" to mutSetType, "elem" to elementType)),
                MezzeType.BoolType
            )
        }

        // __native_mut_set_size: {set: MutSet 'a} -> Int
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSetType = MezzeType.TypeApplication("MutSet", listOf(elementType))
            context["__native_mut_set_size"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("set" to mutSetType)),
                MezzeType.IntType
            )
        }

        // __native_mut_set_clear: {set: MutSet 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSetType = MezzeType.TypeApplication("MutSet", listOf(elementType))
            context["__native_mut_set_clear"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("set" to mutSetType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_set_elements: {set: MutSet 'a} -> ['a]
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSetType = MezzeType.TypeApplication("MutSet", listOf(elementType))
            context["__native_mut_set_elements"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("set" to mutSetType)),
                MezzeType.SeqType(elementType)
            )
        }

        // __native_mut_set_toggle: {set: MutSet 'a, elem: 'a} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSetType = MezzeType.TypeApplication("MutSet", listOf(elementType))
            context["__native_mut_set_toggle"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("set" to mutSetType, "elem" to elementType)),
                MezzeType.BoolType
            )
        }

        // =================================================================
        // Bytes conversions
        // =================================================================

        // __native_bytes_to_mut: {bytes: Bytes} -> MutBytes
        run {
            val mutBytesType = MezzeType.TypeApplication("MutBytes", emptyList())
            context["__native_bytes_to_mut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("bytes" to MezzeType.BytesType)),
                mutBytesType
            )
        }

        // __native_bytes_to_immut: {bytes: MutBytes} -> Bytes
        run {
            val mutBytesType = MezzeType.TypeApplication("MutBytes", emptyList())
            context["__native_bytes_to_immut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("bytes" to mutBytesType)),
                MezzeType.BytesType
            )
        }

        // =================================================================
        // MutBytes operations
        // =================================================================

        // __native_mut_bytes_get: {bytes: MutBytes, i: Int} -> Option Int
        run {
            val mutBytesType = MezzeType.TypeApplication("MutBytes", emptyList())
            val optionType = MezzeType.TypeApplication("Option", listOf(MezzeType.IntType))
            context["__native_mut_bytes_get"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("bytes" to mutBytesType, "i" to MezzeType.IntType)),
                optionType
            )
        }

        // __native_mut_bytes_set: {bytes: MutBytes, i: Int, v: Int} -> Bool
        run {
            val mutBytesType = MezzeType.TypeApplication("MutBytes", emptyList())
            context["__native_mut_bytes_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("bytes" to mutBytesType, "i" to MezzeType.IntType, "v" to MezzeType.IntType)),
                MezzeType.BoolType
            )
        }

        // __native_mut_bytes_len: {bytes: MutBytes} -> Int
        run {
            val mutBytesType = MezzeType.TypeApplication("MutBytes", emptyList())
            context["__native_mut_bytes_len"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("bytes" to mutBytesType)),
                MezzeType.IntType
            )
        }

        // __native_mut_bytes_fill: {bytes: MutBytes, v: Int} -> {}
        run {
            val mutBytesType = MezzeType.TypeApplication("MutBytes", emptyList())
            context["__native_mut_bytes_fill"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("bytes" to mutBytesType, "v" to MezzeType.IntType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_bytes_fill_range: {bytes: MutBytes, start: Int, end: Int, v: Int} -> {}
        run {
            val mutBytesType = MezzeType.TypeApplication("MutBytes", emptyList())
            context["__native_mut_bytes_fill_range"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "bytes" to mutBytesType,
                    "start" to MezzeType.IntType,
                    "end" to MezzeType.IntType,
                    "v" to MezzeType.IntType
                )),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_bytes_slice: {bytes: MutBytes, start: Int, end: Int} -> Bytes
        run {
            val mutBytesType = MezzeType.TypeApplication("MutBytes", emptyList())
            context["__native_mut_bytes_slice"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "bytes" to mutBytesType,
                    "start" to MezzeType.IntType,
                    "end" to MezzeType.IntType
                )),
                MezzeType.BytesType
            )
        }

        // __native_mut_bytes_resize: {bytes: MutBytes, size: Int} -> {}
        run {
            val mutBytesType = MezzeType.TypeApplication("MutBytes", emptyList())
            context["__native_mut_bytes_resize"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("bytes" to mutBytesType, "size" to MezzeType.IntType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_bytes_swap: {bytes: MutBytes, i: Int, j: Int} -> Bool
        run {
            val mutBytesType = MezzeType.TypeApplication("MutBytes", emptyList())
            context["__native_mut_bytes_swap"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("bytes" to mutBytesType, "i" to MezzeType.IntType, "j" to MezzeType.IntType)),
                MezzeType.BoolType
            )
        }

        // __native_mut_bytes_reverse: {bytes: MutBytes} -> {}
        run {
            val mutBytesType = MezzeType.TypeApplication("MutBytes", emptyList())
            context["__native_mut_bytes_reverse"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("bytes" to mutBytesType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_bytes_clear: {bytes: MutBytes} -> {}
        run {
            val mutBytesType = MezzeType.TypeApplication("MutBytes", emptyList())
            context["__native_mut_bytes_clear"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("bytes" to mutBytesType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_bytes_copy: {dest: MutBytes, src: MutBytes, src_offset: Int, dest_offset: Int, length: Int} -> {}
        run {
            val mutBytesType = MezzeType.TypeApplication("MutBytes", emptyList())
            context["__native_mut_bytes_copy"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "dest" to mutBytesType,
                    "src" to mutBytesType,
                    "src_offset" to MezzeType.IntType,
                    "dest_offset" to MezzeType.IntType,
                    "length" to MezzeType.IntType
                )),
                MezzeType.RecordType(emptyMap())
            )
        }

        // =================================================================
        // Primitive conversions
        // =================================================================

        // __native_int_to_mut: {n: Int} -> MutInt
        run {
            val mutIntType = MezzeType.TypeApplication("MutInt", emptyList())
            context["__native_int_to_mut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
                mutIntType
            )
        }

        // __native_int_to_immut: {n: MutInt} -> Int
        run {
            val mutIntType = MezzeType.TypeApplication("MutInt", emptyList())
            context["__native_int_to_immut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutIntType)),
                MezzeType.IntType
            )
        }

        // __native_float_to_mut: {n: Float} -> MutFloat
        run {
            val mutFloatType = MezzeType.TypeApplication("MutFloat", emptyList())
            context["__native_float_to_mut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to MezzeType.FloatType)),
                mutFloatType
            )
        }

        // __native_float_to_immut: {n: MutFloat} -> Float
        run {
            val mutFloatType = MezzeType.TypeApplication("MutFloat", emptyList())
            context["__native_float_to_immut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutFloatType)),
                MezzeType.FloatType
            )
        }

        // __native_bool_to_mut: {b: Bool} -> MutBool
        run {
            val mutBoolType = MezzeType.TypeApplication("MutBool", emptyList())
            context["__native_bool_to_mut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("b" to MezzeType.BoolType)),
                mutBoolType
            )
        }

        // __native_bool_to_immut: {b: MutBool} -> Bool
        run {
            val mutBoolType = MezzeType.TypeApplication("MutBool", emptyList())
            context["__native_bool_to_immut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("b" to mutBoolType)),
                MezzeType.BoolType
            )
        }

        // =================================================================
        // MutSeq operations
        // =================================================================

        // __native_mut_seq_sort: {seq: MutSeq Int} -> {}
        run {
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(MezzeType.IntType))
            context["__native_mut_seq_sort"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_seq_sort_by: {seq: MutSeq 'a, cmp: {a: 'a, b: 'a} -> Int} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            val cmpType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("a" to elementType, "b" to elementType)),
                MezzeType.IntType
            )
            context["__native_mut_seq_sort_by"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType, "cmp" to cmpType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_seq_reverse: {seq: MutSeq 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            context["__native_mut_seq_reverse"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_seq_fill: {seq: MutSeq 'a, v: 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            context["__native_mut_seq_fill"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType, "v" to elementType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_seq_fill_range: {seq: MutSeq 'a, start: Int, end: Int, v: 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            context["__native_mut_seq_fill_range"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "seq" to mutSeqType,
                    "start" to MezzeType.IntType,
                    "end" to MezzeType.IntType,
                    "v" to elementType
                )),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_seq_push: {seq: MutSeq 'a, v: 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            context["__native_mut_seq_push"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType, "v" to elementType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_seq_pop: {seq: MutSeq 'a} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            val optionType = MezzeType.TypeApplication("Option", listOf(elementType))
            context["__native_mut_seq_pop"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType)),
                optionType
            )
        }

        // __native_mut_seq_get: {seq: MutSeq 'a, i: Int} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            val optionType = MezzeType.TypeApplication("Option", listOf(elementType))
            context["__native_mut_seq_get"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType, "i" to MezzeType.IntType)),
                optionType
            )
        }

        // __native_mut_seq_set: {seq: MutSeq 'a, i: Int, v: 'a} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            context["__native_mut_seq_set"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType, "i" to MezzeType.IntType, "v" to elementType)),
                MezzeType.BoolType
            )
        }

        // __native_mut_seq_len: {seq: MutSeq 'a} -> Int
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            context["__native_mut_seq_len"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType)),
                MezzeType.IntType
            )
        }

        // __native_mut_seq_clear: {seq: MutSeq 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            context["__native_mut_seq_clear"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_seq_insert: {seq: MutSeq 'a, i: Int, v: 'a} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            context["__native_mut_seq_insert"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType, "i" to MezzeType.IntType, "v" to elementType)),
                MezzeType.BoolType
            )
        }

        // __native_mut_seq_remove: {seq: MutSeq 'a, i: Int} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            val optionType = MezzeType.TypeApplication("Option", listOf(elementType))
            context["__native_mut_seq_remove"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType, "i" to MezzeType.IntType)),
                optionType
            )
        }

        // __native_mut_seq_swap: {seq: MutSeq 'a, i: Int, j: Int} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutSeqType = MezzeType.TypeApplication("MutSeq", listOf(elementType))
            context["__native_mut_seq_swap"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("seq" to mutSeqType, "i" to MezzeType.IntType, "j" to MezzeType.IntType)),
                MezzeType.BoolType
            )
        }

        // =================================================================
        // MutInt operations
        // =================================================================

        // __native_mut_int_read: {n: MutInt} -> Int
        run {
            val mutIntType = MezzeType.TypeApplication("MutInt", emptyList())
            context["__native_mut_int_read"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutIntType)),
                MezzeType.IntType
            )
        }

        // __native_mut_int_write: {n: MutInt, v: Int} -> {}
        run {
            val mutIntType = MezzeType.TypeApplication("MutInt", emptyList())
            context["__native_mut_int_write"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutIntType, "v" to MezzeType.IntType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_int_incr: {n: MutInt, delta: Int} -> {}
        run {
            val mutIntType = MezzeType.TypeApplication("MutInt", emptyList())
            context["__native_mut_int_incr"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutIntType, "delta" to MezzeType.IntType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_int_decr: {n: MutInt, delta: Int} -> {}
        run {
            val mutIntType = MezzeType.TypeApplication("MutInt", emptyList())
            context["__native_mut_int_decr"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutIntType, "delta" to MezzeType.IntType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_int_mul: {n: MutInt, factor: Int} -> {}
        run {
            val mutIntType = MezzeType.TypeApplication("MutInt", emptyList())
            context["__native_mut_int_mul"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutIntType, "factor" to MezzeType.IntType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_int_div: {n: MutInt, divisor: Int} -> {}
        run {
            val mutIntType = MezzeType.TypeApplication("MutInt", emptyList())
            context["__native_mut_int_div"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutIntType, "divisor" to MezzeType.IntType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_int_mod: {n: MutInt, divisor: Int} -> {}
        run {
            val mutIntType = MezzeType.TypeApplication("MutInt", emptyList())
            context["__native_mut_int_mod"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutIntType, "divisor" to MezzeType.IntType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_int_cas: {n: MutInt, expected: Int, new_val: Int} -> Bool
        run {
            val mutIntType = MezzeType.TypeApplication("MutInt", emptyList())
            context["__native_mut_int_cas"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "n" to mutIntType,
                    "expected" to MezzeType.IntType,
                    "new_val" to MezzeType.IntType
                )),
                MezzeType.BoolType
            )
        }

        // =================================================================
        // MutFloat operations
        // =================================================================

        // __native_mut_float_read: {n: MutFloat} -> Float
        run {
            val mutFloatType = MezzeType.TypeApplication("MutFloat", emptyList())
            context["__native_mut_float_read"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutFloatType)),
                MezzeType.FloatType
            )
        }

        // __native_mut_float_write: {n: MutFloat, v: Float} -> {}
        run {
            val mutFloatType = MezzeType.TypeApplication("MutFloat", emptyList())
            context["__native_mut_float_write"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutFloatType, "v" to MezzeType.FloatType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_float_add: {n: MutFloat, delta: Float} -> {}
        run {
            val mutFloatType = MezzeType.TypeApplication("MutFloat", emptyList())
            context["__native_mut_float_add"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutFloatType, "delta" to MezzeType.FloatType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_float_sub: {n: MutFloat, delta: Float} -> {}
        run {
            val mutFloatType = MezzeType.TypeApplication("MutFloat", emptyList())
            context["__native_mut_float_sub"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutFloatType, "delta" to MezzeType.FloatType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_float_mul: {n: MutFloat, factor: Float} -> {}
        run {
            val mutFloatType = MezzeType.TypeApplication("MutFloat", emptyList())
            context["__native_mut_float_mul"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutFloatType, "factor" to MezzeType.FloatType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_float_div: {n: MutFloat, divisor: Float} -> {}
        run {
            val mutFloatType = MezzeType.TypeApplication("MutFloat", emptyList())
            context["__native_mut_float_div"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("n" to mutFloatType, "divisor" to MezzeType.FloatType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // =================================================================
        // Looping constructs
        // =================================================================

        // __native_for_range: {start: Int, end: Int, body: {i: Int} -> {} <...>} -> {} <...>
        // Effect-polymorphic: propagates effects from body
        run {
            val effectRowVar = RowVar.fresh()
            val effectRow = MezzeType.EffectRow.simple(emptySet(), effectRowVar, performed = false)
            val bodyType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("i" to MezzeType.IntType)),
                MezzeType.RecordType(emptyMap()),
                effects = effectRow
            )
            context["__native_for_range"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "start" to MezzeType.IntType,
                    "end" to MezzeType.IntType,
                    "body" to bodyType
                )),
                MezzeType.RecordType(emptyMap()),
                effects = effectRow
            )
        }

        // __native_while: {cond: {} -> Bool <...>, body: {} -> {} <...>} -> {} <...>
        // Effect-polymorphic: propagates effects from cond and body
        run {
            val effectRowVar = RowVar.fresh()
            val effectRow = MezzeType.EffectRow.simple(emptySet(), effectRowVar, performed = false)
            val condType = MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                MezzeType.BoolType,
                effects = effectRow
            )
            val bodyType = MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                MezzeType.RecordType(emptyMap()),
                effects = effectRow
            )
            context["__native_while"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "cond" to condType,
                    "body" to bodyType
                )),
                MezzeType.RecordType(emptyMap()),
                effects = effectRow
            )
        }

        // =================================================================
        // MutDeque operations
        // =================================================================

        // __native_deque_to_mut: {deque: Deque 'a} -> MutDeque 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elementType))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            context["__native_deque_to_mut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("deque" to dequeType)),
                mutDequeType
            )
        }

        // __native_deque_to_immut: {deque: MutDeque 'a} -> Deque 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val dequeType = MezzeType.TypeApplication("Deque", listOf(elementType))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            context["__native_deque_to_immut"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("deque" to mutDequeType)),
                dequeType
            )
        }

        // __native_mut_deque_with_capacity: {capacity: Int} -> MutDeque 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            context["__native_mut_deque_with_capacity"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("capacity" to MezzeType.IntType)),
                mutDequeType
            )
        }

        // __native_mut_deque_push_front: {deque: MutDeque 'a, elem: 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            context["__native_mut_deque_push_front"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("deque" to mutDequeType, "elem" to elementType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_deque_push_back: {deque: MutDeque 'a, elem: 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            context["__native_mut_deque_push_back"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("deque" to mutDequeType, "elem" to elementType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_deque_pop_front: {deque: MutDeque 'a} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            val optionType = MezzeType.TypeApplication("Option", listOf(elementType))
            context["__native_mut_deque_pop_front"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("deque" to mutDequeType)),
                optionType
            )
        }

        // __native_mut_deque_pop_back: {deque: MutDeque 'a} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            val optionType = MezzeType.TypeApplication("Option", listOf(elementType))
            context["__native_mut_deque_pop_back"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("deque" to mutDequeType)),
                optionType
            )
        }

        // __native_mut_deque_peek_front: {deque: MutDeque 'a} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            val optionType = MezzeType.TypeApplication("Option", listOf(elementType))
            context["__native_mut_deque_peek_front"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("deque" to mutDequeType)),
                optionType
            )
        }

        // __native_mut_deque_peek_back: {deque: MutDeque 'a} -> Option 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            val optionType = MezzeType.TypeApplication("Option", listOf(elementType))
            context["__native_mut_deque_peek_back"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("deque" to mutDequeType)),
                optionType
            )
        }

        // __native_mut_deque_size: {deque: MutDeque 'a} -> Int
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            context["__native_mut_deque_size"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("deque" to mutDequeType)),
                MezzeType.IntType
            )
        }

        // __native_mut_deque_is_empty: {deque: MutDeque 'a} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            context["__native_mut_deque_is_empty"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("deque" to mutDequeType)),
                MezzeType.BoolType
            )
        }

        // __native_mut_deque_clear: {deque: MutDeque 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            context["__native_mut_deque_clear"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("deque" to mutDequeType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_deque_reverse: {deque: MutDeque 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            context["__native_mut_deque_reverse"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("deque" to mutDequeType)),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mut_deque_to_seq: {deque: MutDeque 'a} -> ['a]
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val mutDequeType = MezzeType.TypeApplication("MutDeque", listOf(elementType))
            context["__native_mut_deque_to_seq"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("deque" to mutDequeType)),
                MezzeType.SeqType(elementType)
            )
        }

        // =================================================================
        // Debug mutation marker
        // =================================================================

        // __native_debug_mutation_marker: {} -> {}
        context["__native_debug_mutation_marker"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.RecordType(emptyMap())
        )
    }
}
