package com.funk.language.typechecker.types.effects

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

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

    fun registerAll(context: MutableMap<String, FunkType>) {
        registerTVarOperations(context)
        registerCoreSTMOperations(context)
        registerConfigurableSTMOperations(context)
        registerSequentialSTMOperations(context)
        registerBulkOperations(context)
    }

    private fun registerTVarOperations(context: MutableMap<String, FunkType>) {
        // __native_tvar_new: {init: 'a} -> TVar 'a
        context["__native_tvar_new"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "init" to FunkType.VarType(TypeVar("a"))
            )),
            FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a"))))
        )

        // __native_tvar_deref: {var: TVar 'a} -> 'a
        context["__native_tvar_deref"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "var" to FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a"))))
            )),
            FunkType.VarType(TypeVar("a"))
        )
    }

    private fun registerCoreSTMOperations(context: MutableMap<String, FunkType>) {
        // __native_stm_read: {var: TVar 'a} -> 'a
        context["__native_stm_read"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "var" to FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a"))))
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_stm_write: {var: TVar 'a, val: 'a} -> {}
        context["__native_stm_write"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "var" to FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a")))),
                "val" to FunkType.VarType(TypeVar("a"))
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_stm_modify: {var: TVar 'a, f: {val: 'a} -> 'a} -> {}
        context["__native_stm_modify"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "var" to FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a")))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("val" to FunkType.VarType(TypeVar("a")))),
                    FunkType.VarType(TypeVar("a"))
                )
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_stm_swap: {var: TVar 'a, val: 'a} -> 'a
        context["__native_stm_swap"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "var" to FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a")))),
                "val" to FunkType.VarType(TypeVar("a"))
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_stm_ensure: {var: TVar 'a} -> 'a
        context["__native_stm_ensure"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "var" to FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a"))))
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_stm_retry: {} -> 'a
        context["__native_stm_retry"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_stm_or_else: {primary: {} -> 'a, fallback: {} -> 'a} -> 'a
        context["__native_stm_or_else"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "primary" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                ),
                "fallback" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                )
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_stm_check: {cond: Bool} -> {}
        context["__native_stm_check"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "cond" to FunkType.BooleanType
            )),
            FunkType.RecordType(emptyMap())
        )
    }

    private fun registerConfigurableSTMOperations(context: MutableMap<String, FunkType>) {
        // Configurable versions with max_retries and spin_count parameters

        // __native_stm_read_cfg: {var: TVar 'a, max_retries: Int, spin_count: Int} -> 'a
        context["__native_stm_read_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "var" to FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a")))),
                "max_retries" to FunkType.IntType,
                "spin_count" to FunkType.IntType
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_stm_write_cfg: {var: TVar 'a, val: 'a, max_retries: Int, spin_count: Int} -> {}
        context["__native_stm_write_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "var" to FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a")))),
                "val" to FunkType.VarType(TypeVar("a")),
                "max_retries" to FunkType.IntType,
                "spin_count" to FunkType.IntType
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_stm_modify_cfg
        context["__native_stm_modify_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "var" to FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a")))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("val" to FunkType.VarType(TypeVar("a")))),
                    FunkType.VarType(TypeVar("a"))
                ),
                "max_retries" to FunkType.IntType,
                "spin_count" to FunkType.IntType
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_stm_swap_cfg
        context["__native_stm_swap_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "var" to FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a")))),
                "val" to FunkType.VarType(TypeVar("a")),
                "max_retries" to FunkType.IntType,
                "spin_count" to FunkType.IntType
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_stm_ensure_cfg
        context["__native_stm_ensure_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "var" to FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a")))),
                "max_retries" to FunkType.IntType,
                "spin_count" to FunkType.IntType
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_stm_retry_cfg: {max_retries: Int, spin_count: Int} -> 'a
        context["__native_stm_retry_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "max_retries" to FunkType.IntType,
                "spin_count" to FunkType.IntType
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_stm_or_else_cfg
        context["__native_stm_or_else_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "primary" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                ),
                "fallback" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                ),
                "max_retries" to FunkType.IntType,
                "spin_count" to FunkType.IntType
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_stm_check_cfg
        context["__native_stm_check_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "cond" to FunkType.BooleanType,
                "max_retries" to FunkType.IntType,
                "spin_count" to FunkType.IntType
            )),
            FunkType.RecordType(emptyMap())
        )
    }

    private fun registerSequentialSTMOperations(context: MutableMap<String, FunkType>) {
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

    private fun registerBulkOperations(context: MutableMap<String, FunkType>) {
        // __native_stm_read_all: {vars: [TVar 'a]} -> ['a]
        context["__native_stm_read_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "vars" to FunkType.ArrayType(
                    FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a"))))
                )
            )),
            FunkType.ArrayType(FunkType.VarType(TypeVar("a")))
        )

        // __native_stm_write_all: {pairs: [{var: TVar 'a, val: 'a}]} -> {}
        context["__native_stm_write_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "pairs" to FunkType.ArrayType(
                    FunkType.RecordType(mapOf(
                        "var" to FunkType.TypeApplication("TVar", listOf(FunkType.VarType(TypeVar("a")))),
                        "val" to FunkType.VarType(TypeVar("a"))
                    ))
                )
            )),
            FunkType.RecordType(emptyMap())
        )
    }
}
