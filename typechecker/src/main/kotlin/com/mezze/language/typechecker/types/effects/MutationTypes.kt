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
        // Debug mutation marker
        // =================================================================

        // __native_debug_mutation_marker: {} -> {}
        context["__native_debug_mutation_marker"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.RecordType(emptyMap())
        )
    }
}
