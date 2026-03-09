package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

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

    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerTVarOperations(context)
        registerCoreSTMOperations(context)
        registerConfigurableSTMOperations(context)
        registerSequentialSTMOperations(context)
        registerBulkOperations(context)
    }

    private fun registerTVarOperations(context: MutableMap<String, MezzeType>) {
        // __native_tvar_new: {init: 'a} -> TVar 'a
        context["__native_tvar_new"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "init" to MezzeType.VarType(TypeVar("a"))
            )),
            MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a"))))
        )

        // __native_tvar_deref: {var: TVar 'a} -> 'a
        context["__native_tvar_deref"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "var" to MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a"))))
            )),
            MezzeType.VarType(TypeVar("a"))
        )
    }

    private fun registerCoreSTMOperations(context: MutableMap<String, MezzeType>) {
        // __native_stm_read: {var: TVar 'a} -> 'a
        context["__native_stm_read"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "var" to MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a"))))
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_stm_write: {var: TVar 'a, val: 'a} -> {}
        context["__native_stm_write"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "var" to MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a")))),
                "val" to MezzeType.VarType(TypeVar("a"))
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_stm_modify: {var: TVar 'a, f: {val: 'a} -> 'a} -> {}
        context["__native_stm_modify"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "var" to MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a")))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("a"))
                )
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_stm_swap: {var: TVar 'a, val: 'a} -> 'a
        context["__native_stm_swap"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "var" to MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a")))),
                "val" to MezzeType.VarType(TypeVar("a"))
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_stm_ensure: {var: TVar 'a} -> 'a
        context["__native_stm_ensure"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "var" to MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a"))))
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_stm_retry: {} -> 'a
        context["__native_stm_retry"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_stm_or_else: {primary: {} -> 'a, fallback: {} -> 'a} -> 'a
        context["__native_stm_or_else"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "primary" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "fallback" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                )
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_stm_check: {cond: Bool} -> {}
        context["__native_stm_check"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "cond" to MezzeType.BoolType
            )),
            MezzeType.RecordType(emptyMap())
        )
    }

    private fun registerConfigurableSTMOperations(context: MutableMap<String, MezzeType>) {
        // Configurable versions with max_retries and spin_count parameters

        // __native_stm_read_cfg: {var: TVar 'a, max_retries: Int, spin_count: Int} -> 'a
        context["__native_stm_read_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "var" to MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a")))),
                "max_retries" to MezzeType.IntType,
                "spin_count" to MezzeType.IntType
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_stm_write_cfg: {var: TVar 'a, val: 'a, max_retries: Int, spin_count: Int} -> {}
        context["__native_stm_write_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "var" to MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a")))),
                "val" to MezzeType.VarType(TypeVar("a")),
                "max_retries" to MezzeType.IntType,
                "spin_count" to MezzeType.IntType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_stm_modify_cfg
        context["__native_stm_modify_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "var" to MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a")))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("val" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "max_retries" to MezzeType.IntType,
                "spin_count" to MezzeType.IntType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_stm_swap_cfg
        context["__native_stm_swap_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "var" to MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a")))),
                "val" to MezzeType.VarType(TypeVar("a")),
                "max_retries" to MezzeType.IntType,
                "spin_count" to MezzeType.IntType
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_stm_ensure_cfg
        context["__native_stm_ensure_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "var" to MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a")))),
                "max_retries" to MezzeType.IntType,
                "spin_count" to MezzeType.IntType
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_stm_retry_cfg: {max_retries: Int, spin_count: Int} -> 'a
        context["__native_stm_retry_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "max_retries" to MezzeType.IntType,
                "spin_count" to MezzeType.IntType
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_stm_or_else_cfg
        context["__native_stm_or_else_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "primary" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "fallback" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "max_retries" to MezzeType.IntType,
                "spin_count" to MezzeType.IntType
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_stm_check_cfg
        context["__native_stm_check_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "cond" to MezzeType.BoolType,
                "max_retries" to MezzeType.IntType,
                "spin_count" to MezzeType.IntType
            )),
            MezzeType.RecordType(emptyMap())
        )
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
        // __native_stm_read_all: {vars: [TVar 'a]} -> ['a]
        context["__native_stm_read_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "vars" to MezzeType.SeqType(
                    MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a"))))
                )
            )),
            MezzeType.SeqType(MezzeType.VarType(TypeVar("a")))
        )

        // __native_stm_write_all: {pairs: [{var: TVar 'a, val: 'a}]} -> {}
        context["__native_stm_write_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "pairs" to MezzeType.SeqType(
                    MezzeType.RecordType(mapOf(
                        "var" to MezzeType.TypeApplication("TVar", listOf(MezzeType.VarType(TypeVar("a")))),
                        "val" to MezzeType.VarType(TypeVar("a"))
                    ))
                )
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_stm_atomic: {f: {} -> 'a <STM>} -> 'a <STM>
        // Executes a function atomically with automatic retry on conflict
        context["__native_stm_atomic"] = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(mapOf(
                "f" to MezzeType.FunctionType(
                    paramType = MezzeType.RecordType(emptyMap()),
                    returnType = MezzeType.VarType(TypeVar("a")),
                    effects = MezzeType.EffectRow(setOf("STM"))
                )
            )),
            returnType = MezzeType.VarType(TypeVar("a")),
            effects = MezzeType.EffectRow(setOf("STM"))
        )

        // __native_stm_commit: {} -> Bool
        // Explicitly commit the current transaction
        context["__native_stm_commit"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.BoolType
        )
    }
}
