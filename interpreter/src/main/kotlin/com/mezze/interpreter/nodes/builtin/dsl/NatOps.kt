package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.interpreter.nodes.builtin.primitives.NatNatives
import com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
import com.mezze.language.truffleir.SlotKind

/**
 * Nat natives in DSL form.
 *
 * Excluded:
 *  - `__native_nat_to_frac` — returns a MezzeRecord (Frac); kept on
 *    legacy with the rest of Frac.
 *  - `__native_nat_from_string` / `__native_nat_from_int` /
 *    `__native_nat_from_float` — return Option<Nat> via NatNatives
 *    helpers. Cold paths (parsing/conversion), kept on legacy to
 *    avoid duplicating the helper logic.
 */
object NatOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    // ==================== binary Nat, Nat -> Nat ====================
    private val ADD = NativeOp("__native_nat_add",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.natAdd(args.long(0), args.long(1)) }

    private val MULTIPLY = NativeOp("__native_nat_multiply",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.natMultiply(args.long(0), args.long(1)) }

    private val DIVIDE = NativeOp("__native_nat_divide",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.natDivide(args.long(0), args.long(1)) }

    private val MODULO = NativeOp("__native_nat_modulo",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.natModulo(args.long(0), args.long(1)) }

    private val MIN = NativeOp("__native_nat_min",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> NatNatives.min(args.long(0), args.long(1)) }

    private val MAX = NativeOp("__native_nat_max",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> NatNatives.max(args.long(0), args.long(1)) }

    private val POW = NativeOp("__native_nat_pow",
        listOf(Param("base", SlotKind.LONG), Param("exp", SlotKind.LONG)), SlotKind.LONG
    ) { args -> NatNatives.pow(args.long(0), args.long(1)) }

    // subtract_to_int: saturating subtraction returning Int (can go
    // negative). Result is LONG (Mezze's Int kind).
    private val SUBTRACT_TO_INT = NativeOp("__native_nat_subtract_to_int",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> NatNatives.subtractToInt(args.long(0), args.long(1)) }

    // ==================== ternary clamp ====================
    private val CLAMP = NativeOp("__native_nat_clamp",
        listOf(Param("n", SlotKind.LONG), Param("min", SlotKind.LONG), Param("max", SlotKind.LONG)),
        SlotKind.LONG
    ) { args -> NatNatives.clamp(args.long(0), args.long(1), args.long(2)) }

    // ==================== Nat, Nat -> Bool comparisons ====================
    private val EQ = NativeOp("__native_nat_eq",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.natEq(args.long(0), args.long(1)) }

    private val NEQ = NativeOp("__native_nat_neq",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.natNeq(args.long(0), args.long(1)) }

    private val LT = NativeOp("__native_nat_lt",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.natLt(args.long(0), args.long(1)) }

    private val LE = NativeOp("__native_nat_le",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.natLte(args.long(0), args.long(1)) }

    private val GT = NativeOp("__native_nat_gt",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.natGt(args.long(0), args.long(1)) }

    private val GE = NativeOp("__native_nat_ge",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.natGte(args.long(0), args.long(1)) }

    // ==================== conversions ====================
    private val TO_INT = NativeOp("__native_nat_to_int",
        listOf(Param("n", SlotKind.LONG)), SlotKind.LONG
    ) { args -> NatNatives.toInt(args.long(0)) }

    private val TO_STR = NativeOp("__native_nat_to_str",
        listOf(Param("n", SlotKind.LONG)), SlotKind.OBJECT
    ) { args -> NatNatives.toString(args.long(0)) }
}
