package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

/**
 * Type definitions for safe local mutation operations.
 *
 * The Mutation effect provides compile-time safe in-place mutation.
 * It is "hermetic" - cannot coexist with other effects to prevent data races.
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 */
object MutationTypes {

    // Abbreviations for common opaque types
    private fun mutSeq(elem: MezzeType) = WellKnownTypeApp.of("MutSeq", listOf(elem))
    private fun mutMap(k: MezzeType, v: MezzeType) = WellKnownTypeApp.of("MutMap", listOf(k, v))
    private fun mutSet(elem: MezzeType) = WellKnownTypeApp.of("MutSet", listOf(elem))
    private fun mutDeque(elem: MezzeType) = WellKnownTypeApp.of("MutDeque", listOf(elem))
    private fun deque(elem: MezzeType) = WellKnownTypeApp.of("Deque", listOf(elem))
    private val mutBytes = WellKnownTypeApp.of("MutBytes", emptyList())
    private val mutInt = WellKnownTypeApp.of("MutInt", emptyList())
    private val mutFloat = WellKnownTypeApp.of("MutFloat", emptyList())
    private val mutBool = WellKnownTypeApp.of("MutBool", emptyList())
    private val mutString = WellKnownTypeApp.of("MutString", emptyList())
    private val mutChar = WellKnownTypeApp.of("MutChar", emptyList())

    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerSequenceConversions(context)
        registerMapConversions(context)
        registerSetConversions(context)
        registerMutMapOperations(context)
        registerMutSetOperations(context)
        registerBytesConversions(context)
        registerMutBytesOperations(context)
        registerPrimitiveConversions(context)
        registerMutSeqOperations(context)
        registerMutIntOperations(context)
        registerMutFloatOperations(context)
        registerMutDequeOperations(context)
        registerMutBoolOperations(context)
        registerMutStringOperations(context)
        registerMutCharOperations(context)
        registerLoopNatives(context)
        registerDebugMutationNatives(context)
    }

    private fun registerSequenceConversions(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_seq_to_mut") { val a = tvar("a"); param("seq" to seq(a)); returns(mutSeq(a)) }
        fn("__native_seq_to_immut") { val a = tvar("a"); param("seq" to mutSeq(a)); returns(seq(a)) }
        fn("__native_mut_seq_with_capacity") { val a = tvar("a"); param("capacity" to int); returns(mutSeq(a)) }
    }

    private fun registerMapConversions(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_map_to_mut") { val k = tvar("k"); val v = tvar("v"); param("map" to map(k, v)); returns(mutMap(k, v)) }
        fn("__native_map_to_immut") { val k = tvar("k"); val v = tvar("v"); param("map" to mutMap(k, v)); returns(map(k, v)) }
        fn("__native_mut_map_with_capacity") { val k = tvar("k"); val v = tvar("v"); param("capacity" to int); returns(mutMap(k, v)) }
    }

    private fun registerSetConversions(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_set_to_mut") {
            val a = tvar("a")
            param("set" to WellKnownTypeApp.of("Set", listOf(a)))
            returns(mutSet(a))
        }
        fn("__native_set_to_immut") {
            val a = tvar("a")
            param("set" to mutSet(a))
            returns(WellKnownTypeApp.of("Set", listOf(a)))
        }
        fn("__native_mut_set_with_capacity") {
            val a = tvar("a")
            param("capacity" to int)
            returns(mutSet(a))
        }
    }

    private fun registerMutMapOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_mut_map_get") { val k = tvar("k"); val v = tvar("v"); param("map" to mutMap(k, v), "key" to k); returns(option(v)) }
        fn("__native_mut_map_put") { val k = tvar("k"); val v = tvar("v"); param("map" to mutMap(k, v), "key" to k, "value" to v); returns(unit) }
        fn("__native_mut_map_remove") { val k = tvar("k"); val v = tvar("v"); param("map" to mutMap(k, v), "key" to k); returns(unit) }
        fn("__native_mut_map_contains") { val k = tvar("k"); val v = tvar("v"); param("map" to mutMap(k, v), "key" to k); returns(bool) }
        fn("__native_mut_map_size") { val k = tvar("k"); val v = tvar("v"); param("map" to mutMap(k, v)); returns(int) }
        fn("__native_mut_map_clear") { val k = tvar("k"); val v = tvar("v"); param("map" to mutMap(k, v)); returns(unit) }
        fn("__native_mut_map_keys") { val k = tvar("k"); val v = tvar("v"); param("map" to mutMap(k, v)); returns(seq(k)) }
        fn("__native_mut_map_values") { val k = tvar("k"); val v = tvar("v"); param("map" to mutMap(k, v)); returns(seq(v)) }
        fn("__native_mut_map_entries") { val k = tvar("k"); val v = tvar("v"); param("map" to mutMap(k, v)); returns(seq(rec("k" to k, "v" to v))) }
    }

    private fun registerMutSetOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_mut_set_add") { val a = tvar("a"); param("set" to mutSet(a), "elem" to a); returns(unit) }
        fn("__native_mut_set_remove") { val a = tvar("a"); param("set" to mutSet(a), "elem" to a); returns(unit) }
        fn("__native_mut_set_contains") { val a = tvar("a"); param("set" to mutSet(a), "elem" to a); returns(bool) }
        fn("__native_mut_set_size") { val a = tvar("a"); param("set" to mutSet(a)); returns(int) }
        fn("__native_mut_set_clear") { val a = tvar("a"); param("set" to mutSet(a)); returns(unit) }
        fn("__native_mut_set_elements") { val a = tvar("a"); param("set" to mutSet(a)); returns(seq(a)) }
        fn("__native_mut_set_toggle") { val a = tvar("a"); param("set" to mutSet(a), "elem" to a); returns(bool) }
    }

    private fun registerBytesConversions(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_bytes_to_mut") { param("bytes" to bytes); returns(mutBytes) }
        fn("__native_bytes_to_immut") { param("bytes" to mutBytes); returns(bytes) }
    }

    private fun registerMutBytesOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_mut_bytes_get") { param("bytes" to mutBytes, "i" to int); returns(option(int)) }
        fn("__native_mut_bytes_set") { param("bytes" to mutBytes, "i" to int, "v" to int); returns(bool) }
        fn("__native_mut_bytes_len") { param("bytes" to mutBytes); returns(int) }
        fn("__native_mut_bytes_fill") { param("bytes" to mutBytes, "v" to int); returns(unit) }
        fn("__native_mut_bytes_fill_range") { param("bytes" to mutBytes, "start" to int, "end" to int, "v" to int); returns(unit) }
        fn("__native_mut_bytes_slice") { param("bytes" to mutBytes, "start" to int, "end" to int); returns(bytes) }
        fn("__native_mut_bytes_resize") { param("bytes" to mutBytes, "size" to int); returns(unit) }
        fn("__native_mut_bytes_swap") { param("bytes" to mutBytes, "i" to int, "j" to int); returns(bool) }
        fn("__native_mut_bytes_reverse") { param("bytes" to mutBytes); returns(unit) }
        fn("__native_mut_bytes_clear") { param("bytes" to mutBytes); returns(unit) }
        fn("__native_mut_bytes_copy") {
            param("dest" to mutBytes, "src" to mutBytes, "src_offset" to int, "dest_offset" to int, "length" to int)
            returns(unit)
        }
    }

    private fun registerPrimitiveConversions(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_int_to_mut") { param("n" to int); returns(mutInt) }
        fn("__native_int_to_immut") { param("n" to mutInt); returns(int) }
        fn("__native_float_to_mut") { param("n" to float); returns(mutFloat) }
        fn("__native_float_to_immut") { param("n" to mutFloat); returns(float) }
        fn("__native_bool_to_mut") { param("b" to bool); returns(mutBool) }
        fn("__native_bool_to_immut") { param("b" to mutBool); returns(bool) }
    }

    private fun registerMutSeqOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        // Sort (concrete Int type)
        fn("__native_mut_seq_sort") {
            param("seq" to mutSeq(int))
            returns(unit)
        }
        // Sort by comparator
        fn("__native_mut_seq_sort_by") {
            val a = tvar("a")
            param(
                "seq" to mutSeq(a),
                "cmp" to MezzeType.FunctionType(rec("a" to a, "b" to a), int)
            )
            returns(unit)
        }
        fn("__native_mut_seq_reverse") { val a = tvar("a"); param("seq" to mutSeq(a)); returns(unit) }
        fn("__native_mut_seq_fill") { val a = tvar("a"); param("seq" to mutSeq(a), "v" to a); returns(unit) }
        fn("__native_mut_seq_fill_range") { val a = tvar("a"); param("seq" to mutSeq(a), "start" to int, "end" to int, "v" to a); returns(unit) }
        fn("__native_mut_seq_push") { val a = tvar("a"); param("seq" to mutSeq(a), "v" to a); returns(unit) }
        fn("__native_mut_seq_pop") { val a = tvar("a"); param("seq" to mutSeq(a)); returns(option(a)) }
        fn("__native_mut_seq_get") { val a = tvar("a"); param("seq" to mutSeq(a), "i" to int); returns(option(a)) }
        fn("__native_mut_seq_set") { val a = tvar("a"); param("seq" to mutSeq(a), "i" to int, "v" to a); returns(bool) }
        fn("__native_mut_seq_len") { val a = tvar("a"); param("seq" to mutSeq(a)); returns(int) }
        fn("__native_mut_seq_clear") { val a = tvar("a"); param("seq" to mutSeq(a)); returns(unit) }
        fn("__native_mut_seq_insert") { val a = tvar("a"); param("seq" to mutSeq(a), "i" to int, "v" to a); returns(bool) }
        fn("__native_mut_seq_remove") { val a = tvar("a"); param("seq" to mutSeq(a), "i" to int); returns(option(a)) }
        fn("__native_mut_seq_swap") { val a = tvar("a"); param("seq" to mutSeq(a), "i" to int, "j" to int); returns(bool) }
    }

    private fun registerMutIntOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_mut_int_read") { param("n" to mutInt); returns(int) }
        fn("__native_mut_int_write") { param("n" to mutInt, "v" to int); returns(unit) }
        fn("__native_mut_int_incr") { param("n" to mutInt, "delta" to int); returns(unit) }
        fn("__native_mut_int_decr") { param("n" to mutInt, "delta" to int); returns(unit) }
        fn("__native_mut_int_mul") { param("n" to mutInt, "factor" to int); returns(unit) }
        fn("__native_mut_int_div") { param("n" to mutInt, "divisor" to int); returns(unit) }
        fn("__native_mut_int_mod") { param("n" to mutInt, "divisor" to int); returns(unit) }
        fn("__native_mut_int_cas") { param("n" to mutInt, "expected" to int, "new_val" to int); returns(bool) }
    }

    private fun registerMutFloatOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_mut_float_read") { param("n" to mutFloat); returns(float) }
        fn("__native_mut_float_write") { param("n" to mutFloat, "v" to float); returns(unit) }
        fn("__native_mut_float_add") { param("n" to mutFloat, "delta" to float); returns(unit) }
        fn("__native_mut_float_sub") { param("n" to mutFloat, "delta" to float); returns(unit) }
        fn("__native_mut_float_mul") { param("n" to mutFloat, "factor" to float); returns(unit) }
        fn("__native_mut_float_div") { param("n" to mutFloat, "divisor" to float); returns(unit) }
    }

    private fun registerMutDequeOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_deque_to_mut") { val a = tvar("a"); param("deque" to deque(a)); returns(mutDeque(a)) }
        fn("__native_deque_to_immut") { val a = tvar("a"); param("deque" to mutDeque(a)); returns(deque(a)) }
        fn("__native_mut_deque_with_capacity") { val a = tvar("a"); param("capacity" to int); returns(mutDeque(a)) }
        fn("__native_mut_deque_push_front") { val a = tvar("a"); param("deque" to mutDeque(a), "elem" to a); returns(unit) }
        fn("__native_mut_deque_push_back") { val a = tvar("a"); param("deque" to mutDeque(a), "elem" to a); returns(unit) }
        fn("__native_mut_deque_pop_front") { val a = tvar("a"); param("deque" to mutDeque(a)); returns(option(a)) }
        fn("__native_mut_deque_pop_back") { val a = tvar("a"); param("deque" to mutDeque(a)); returns(option(a)) }
        fn("__native_mut_deque_peek_front") { val a = tvar("a"); param("deque" to mutDeque(a)); returns(option(a)) }
        fn("__native_mut_deque_peek_back") { val a = tvar("a"); param("deque" to mutDeque(a)); returns(option(a)) }
        fn("__native_mut_deque_size") { val a = tvar("a"); param("deque" to mutDeque(a)); returns(int) }
        fn("__native_mut_deque_is_empty") { val a = tvar("a"); param("deque" to mutDeque(a)); returns(bool) }
        fn("__native_mut_deque_clear") { val a = tvar("a"); param("deque" to mutDeque(a)); returns(unit) }
        fn("__native_mut_deque_reverse") { val a = tvar("a"); param("deque" to mutDeque(a)); returns(unit) }
        fn("__native_mut_deque_to_seq") { val a = tvar("a"); param("deque" to mutDeque(a)); returns(seq(a)) }
    }

    private fun registerMutBoolOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_mut_bool_read") { param("b" to mutBool); returns(bool) }
        fn("__native_mut_bool_write") { param("b" to mutBool, "v" to bool); returns(unit) }
        fn("__native_mut_bool_toggle") { param("b" to mutBool); returns(unit) }
        fn("__native_mut_bool_set_true") { param("b" to mutBool); returns(unit) }
        fn("__native_mut_bool_set_false") { param("b" to mutBool); returns(unit) }
    }

    private fun registerMutStringOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_string_to_mut") { param("s" to str); returns(mutString) }
        fn("__native_string_with_capacity") { param("capacity" to int); returns(mutString) }
        fn("__native_string_to_immut") { param("s" to mutString); returns(str) }
        fn("__native_mut_string_read") { param("s" to mutString); returns(str) }
        fn("__native_mut_string_write") { param("s" to mutString, "v" to str); returns(unit) }
        fn("__native_mut_string_len") { param("s" to mutString); returns(int) }
        fn("__native_mut_string_is_empty") { param("s" to mutString); returns(bool) }
        fn("__native_mut_string_append") { param("s" to mutString, "suffix" to str); returns(unit) }
        fn("__native_mut_string_prepend") { param("s" to mutString, "prefix" to str); returns(unit) }
        fn("__native_mut_string_clear") { param("s" to mutString); returns(unit) }
        fn("__native_mut_string_reverse") { param("s" to mutString); returns(unit) }
        fn("__native_mut_string_insert") { param("s" to mutString, "i" to int, "text" to str); returns(unit) }
        fn("__native_mut_string_delete") { param("s" to mutString, "start" to int, "end" to int); returns(unit) }
        fn("__native_mut_string_char_at") { param("s" to mutString, "i" to int); returns(option(char)) }
        fn("__native_mut_string_set_char_at") { param("s" to mutString, "i" to int, "c" to char); returns(unit) }
    }

    private fun registerMutCharOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_char_to_mut") { param("c" to char); returns(mutChar) }
        fn("__native_char_to_immut") { param("c" to mutChar); returns(char) }
        fn("__native_mut_char_read") { param("c" to mutChar); returns(char) }
        fn("__native_mut_char_write") { param("c" to mutChar, "v" to char); returns(unit) }
        fn("__native_mut_char_to_upper") { param("c" to mutChar); returns(unit) }
        fn("__native_mut_char_to_lower") { param("c" to mutChar); returns(unit) }
        fn("__native_mut_char_is_digit") { param("c" to mutChar); returns(bool) }
        fn("__native_mut_char_is_letter") { param("c" to mutChar); returns(bool) }
        fn("__native_mut_char_is_whitespace") { param("c" to mutChar); returns(bool) }
    }

    private fun registerLoopNatives(context: MutableMap<String, MezzeType>) {
        // __native_mutation_loop: {cond: {} -> Bool <Mutation>, body: {} -> {} <Mutation>} -> {}
        val mutationEffect = MezzeType.EffectRow(effects = setOf("Mut"))
        val condType = MezzeType.FunctionType(unit, MezzeType.BoolType, mutationEffect)
        val bodyType = MezzeType.FunctionType(unit, unit, mutationEffect)
        context["__native_mutation_loop"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("cond" to condType, "body" to bodyType)),
            unit
        )
    }

    private fun registerDebugMutationNatives(context: MutableMap<String, MezzeType>) {
        // __native_debug_mutation_mutate: {act: {} -> 'a} -> 'a
        val resultType = MezzeType.VarType(TypeVar("a"))
        val actType = MezzeType.FunctionType(unit, resultType)
        context["__native_debug_mutation_mutate"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("act" to actType)),
            resultType
        )

        // __native_debug_mutation_loop: {cond: {} -> Bool <Mutation>, body: {} -> {} <Mutation>} -> {}
        val mutationEffect = MezzeType.EffectRow(effects = setOf("Mut"))
        val condType = MezzeType.FunctionType(unit, MezzeType.BoolType, mutationEffect)
        val bodyType = MezzeType.FunctionType(unit, unit, mutationEffect)
        context["__native_debug_mutation_loop"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("cond" to condType, "body" to bodyType)),
            unit
        )
    }
}
