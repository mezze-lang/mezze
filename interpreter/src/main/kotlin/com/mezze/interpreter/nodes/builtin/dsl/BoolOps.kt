package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
import com.mezze.language.truffleir.SlotKind

/**
 * Bool natives in DSL form. See FloatOps for the migration pattern.
 *
 * Excluded:
 *  - `__native_bool_to_mut` / `__native_bool_to_immut` — Mut bridging,
 *    stays on the record path until the Mut surface migrates.
 */
object BoolOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    private val AND = NativeOp("__native_bool_and",
        listOf(Param("l", SlotKind.BOOLEAN), Param("r", SlotKind.BOOLEAN)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.boolAnd(args.bool(0), args.bool(1)) }

    private val OR = NativeOp("__native_bool_or",
        listOf(Param("l", SlotKind.BOOLEAN), Param("r", SlotKind.BOOLEAN)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.boolOr(args.bool(0), args.bool(1)) }

    private val XOR = NativeOp("__native_bool_xor",
        listOf(Param("l", SlotKind.BOOLEAN), Param("r", SlotKind.BOOLEAN)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.boolXor(args.bool(0), args.bool(1)) }

    private val NOT = NativeOp("__native_bool_not",
        listOf(Param("b", SlotKind.BOOLEAN)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.boolNot(args.bool(0)) }

    private val EQ = NativeOp("__native_bool_eq",
        listOf(Param("l", SlotKind.BOOLEAN), Param("r", SlotKind.BOOLEAN)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.boolEq(args.bool(0), args.bool(1)) }

    private val NEQ = NativeOp("__native_bool_neq",
        listOf(Param("l", SlotKind.BOOLEAN), Param("r", SlotKind.BOOLEAN)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.boolNeq(args.bool(0), args.bool(1)) }

    private val TO_STR = NativeOp("__native_bool_to_str",
        listOf(Param("b", SlotKind.BOOLEAN)), SlotKind.OBJECT
    ) { args -> java.lang.Boolean.toString(args.bool(0)) }
}
