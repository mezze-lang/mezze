package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

/**
 * Type definitions for STM (Software Transactional Memory) native functions.
 *
 * Provides types for:
 * - TVar operations: tvar, deref
 * - Core STM operations: read, write, modify, swap, ensure
 * - Control flow: retry, or_else, check
 * - Bulk operations: read_all, write_all
 */
object STMTypes {

    private fun tvarOf(elem: MezzeType) = WellKnownTypeApp.of("TVar", listOf(elem))

    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerTVarOperations(context)
        registerCoreSTMOperations(context)
        registerConfigurableSTMOperations(context)
        registerSequentialSTMOperations(context)
        registerBulkOperations(context)
    }

    private fun registerTVarOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_tvar_new") { val a = tvar("a"); param("init" to a); returns(tvarOf(a)) }
        fn("__native_tvar_deref") { val a = tvar("a"); param("var" to tvarOf(a)); returns(a) }
    }

    private fun registerCoreSTMOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_stm_read") { val a = tvar("a"); param("var" to tvarOf(a)); returns(a) }
        fn("__native_stm_write") { val a = tvar("a"); param("var" to tvarOf(a), "val" to a); returns(unit) }
        fn("__native_stm_modify") {
            val a = tvar("a")
            param("var" to tvarOf(a), "f" to MezzeType.FunctionType(rec("val" to a), a))
            returns(unit)
        }
        fn("__native_stm_swap") { val a = tvar("a"); param("var" to tvarOf(a), "val" to a); returns(a) }
        fn("__native_stm_ensure") { val a = tvar("a"); param("var" to tvarOf(a)); returns(a) }
        fn("__native_stm_retry") { val a = tvar("a"); returns(a) }
        fn("__native_stm_or_else") {
            val a = tvar("a")
            param(
                "primary" to MezzeType.FunctionType(unit, a),
                "fallback" to MezzeType.FunctionType(unit, a)
            )
            returns(a)
        }
        fn("__native_stm_check") { param("cond" to bool); returns(unit) }
        fn("__native_stm_check_and_clear_retry_flag") { returns(bool) }
        fn("__native_stm_park_for_retry") { returns(unit) }
        fn("__native_stm_read_set_changed") { returns(bool) }
    }

    private fun registerConfigurableSTMOperations(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_stm_read_cfg") {
            val a = tvar("a")
            param("var" to tvarOf(a), "max_retries" to int, "spin_count" to int)
            returns(a)
        }
        fn("__native_stm_write_cfg") {
            val a = tvar("a")
            param("var" to tvarOf(a), "val" to a, "max_retries" to int, "spin_count" to int)
            returns(unit)
        }
        fn("__native_stm_modify_cfg") {
            val a = tvar("a")
            param(
                "var" to tvarOf(a),
                "f" to MezzeType.FunctionType(rec("val" to a), a),
                "max_retries" to int,
                "spin_count" to int
            )
            returns(unit)
        }
        fn("__native_stm_swap_cfg") {
            val a = tvar("a")
            param("var" to tvarOf(a), "val" to a, "max_retries" to int, "spin_count" to int)
            returns(a)
        }
        fn("__native_stm_ensure_cfg") {
            val a = tvar("a")
            param("var" to tvarOf(a), "max_retries" to int, "spin_count" to int)
            returns(a)
        }
        fn("__native_stm_retry_cfg") {
            val a = tvar("a")
            param("max_retries" to int, "spin_count" to int)
            returns(a)
        }
        fn("__native_stm_or_else_cfg") {
            val a = tvar("a")
            param(
                "primary" to MezzeType.FunctionType(unit, a),
                "fallback" to MezzeType.FunctionType(unit, a),
                "max_retries" to int,
                "spin_count" to int
            )
            returns(a)
        }
        fn("__native_stm_check_cfg") {
            param("cond" to bool, "max_retries" to int, "spin_count" to int)
            returns(unit)
        }
    }

    private fun registerSequentialSTMOperations(context: MutableMap<String, MezzeType>) {
        // Sequential versions - same signatures as core, different prefix
        context["__native_stm_seq_read"] = context["__native_stm_read"]!!
        context["__native_stm_seq_write"] = context["__native_stm_write"]!!
        context["__native_stm_seq_modify"] = context["__native_stm_modify"]!!
        context["__native_stm_seq_swap"] = context["__native_stm_swap"]!!
        context["__native_stm_seq_ensure"] = context["__native_stm_ensure"]!!
        context["__native_stm_seq_retry"] = context["__native_stm_retry"]!!
        context["__native_stm_seq_or_else"] = context["__native_stm_or_else"]!!
        context["__native_stm_seq_check"] = context["__native_stm_check"]!!
    }

    private fun registerBulkOperations(context: MutableMap<String, MezzeType>) {
        // __native_stm_read_all: {vars: [TVar 'a]} -> ['a] <STM>
        val readA = MezzeType.VarType(TypeVar("a"))
        context["__native_stm_read_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "vars" to MezzeType.SeqType(tvarOf(readA))
            )),
            MezzeType.SeqType(readA),
            MezzeType.EffectRow(effects = setOf("STM"))
        )

        // __native_stm_write_all: {pairs: [{var: TVar 'a, val: 'a}]} -> {} <STM>
        val writeA = MezzeType.VarType(TypeVar("a"))
        context["__native_stm_write_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "pairs" to MezzeType.SeqType(
                    MezzeType.RecordType(mapOf(
                        "var" to tvarOf(writeA),
                        "val" to writeA
                    ))
                )
            )),
            unit,
            MezzeType.EffectRow(effects = setOf("STM"))
        )

        // __native_stm_atomic: {f: {} -> 'a <e>} -> 'a <e>
        val stmRowVar = RowVar.fresh()
        val atomicA = MezzeType.VarType(TypeVar("a"))
        context["__native_stm_atomic"] = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(mapOf(
                "f" to MezzeType.FunctionType(
                    paramType = unit,
                    returnType = atomicA,
                    effects = MezzeType.EffectRow.simple(emptySet(), stmRowVar, performed = false)
                )
            )),
            returnType = atomicA,
            effects = MezzeType.EffectRow.simple(emptySet(), stmRowVar, performed = false)
        )

        // __native_stm_commit: {} -> Bool
        context["__native_stm_commit"] = MezzeType.FunctionType(unit, MezzeType.BoolType)
    }
}
