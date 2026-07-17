package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.language.truffleir.SlotKind

/**
 * Int natives in DSL form.
 *
 * Excluded:
 *  - `__native_int_to_mut` / `__native_int_to_immut` — Mut bridging,
 *    stays on the record path.
 *  - `__native_int_to_frac` — returns a MezzeRecord (Frac), kept on
 *    legacy path with the rest of Frac (which takes record args too).
 *  - `__native_int_from_string` — uses an internal helper in
 *    NativeDispatch (intFromString), kept on legacy to avoid drift.
 */
object IntOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    // ==================== unary Int -> Int ====================
    private val ABS = NativeOp("__native_int_abs",
        listOf(Param("n", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.intAbs(args.long(0)) }

    private val NEG = NativeOp("__native_int_neg",
        listOf(Param("n", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.intNegate(args.long(0)) }

    private val SIGN = NativeOp("__native_int_sign",
        listOf(Param("n", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.intSign(args.long(0)) }

    // ==================== binary Int, Int -> Int ====================
    private val ADD = NativeOp("__native_int_add",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.intAdd(args.long(0), args.long(1)) }

    private val SUBTRACT = NativeOp("__native_int_subtract",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.intSubtract(args.long(0), args.long(1)) }

    private val MULTIPLY = NativeOp("__native_int_multiply",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.intMultiply(args.long(0), args.long(1)) }

    private val DIVIDE = NativeOp("__native_int_divide",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.intDivide(args.long(0), args.long(1)) }

    private val MODULO = NativeOp("__native_int_modulo",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.intModulo(args.long(0), args.long(1)) }

    private val MIN = NativeOp("__native_int_min",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.intMin(args.long(0), args.long(1)) }

    private val MAX = NativeOp("__native_int_max",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> PrimitiveNatives.intMax(args.long(0), args.long(1)) }

    private val POW = NativeOp("__native_int_pow",
        listOf(Param("base", SlotKind.LONG), Param("exp", SlotKind.LONG)), SlotKind.LONG
    ) { args -> NativeHelpers.intPow(args.long(0), args.long(1)) }

    private val GCD = NativeOp("__native_int_gcd",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> NativeHelpers.intGcd(args.long(0), args.long(1)) }

    private val LCM = NativeOp("__native_int_lcm",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.LONG
    ) { args -> NativeHelpers.intLcm(args.long(0), args.long(1)) }

    // ==================== ternary clamp ====================
    private val CLAMP = NativeOp("__native_int_clamp",
        listOf(Param("n", SlotKind.LONG), Param("min", SlotKind.LONG), Param("max", SlotKind.LONG)),
        SlotKind.LONG
    ) { args -> PrimitiveNatives.intClamp(args.long(0), args.long(1), args.long(2)) }

    // ==================== Int -> Bool predicates ====================
    private val IS_EVEN = NativeOp("__native_int_is_even",
        listOf(Param("n", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> args.long(0) % 2L == 0L }

    private val IS_ODD = NativeOp("__native_int_is_odd",
        listOf(Param("n", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> args.long(0) % 2L != 0L }

    // ==================== Int, Int -> Bool comparisons ====================
    private val EQ = NativeOp("__native_int_eq",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.intEq(args.long(0), args.long(1)) }

    private val NEQ = NativeOp("__native_int_neq",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.intNeq(args.long(0), args.long(1)) }

    private val LT = NativeOp("__native_int_lt",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.intLt(args.long(0), args.long(1)) }

    private val LE = NativeOp("__native_int_le",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.intLte(args.long(0), args.long(1)) }

    private val GT = NativeOp("__native_int_gt",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.intGt(args.long(0), args.long(1)) }

    private val GE = NativeOp("__native_int_ge",
        listOf(Param("l", SlotKind.LONG), Param("r", SlotKind.LONG)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.intGte(args.long(0), args.long(1)) }

    // ==================== conversions ====================
    private val FROM_NAT = NativeOp("__native_int_from_nat",
        listOf(Param("n", SlotKind.LONG)), SlotKind.LONG
    ) { args -> args.long(0) }

    private val TO_FLOAT = NativeOp("__native_int_to_float",
        listOf(Param("n", SlotKind.LONG)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.intToFloat(args.long(0)) }

    // to_nat returns Option<Nat> — None when n is negative.
    private val TO_NAT = NativeOp("__native_int_to_nat",
        listOf(Param("n", SlotKind.LONG)), SlotKind.OBJECT
    ) { args ->
        val n = args.long(0)
        if (n >= 0) MezzeVariant.some(n) else MezzeVariant.none()
    }

    private val TO_STR = NativeOp("__native_int_to_str",
        listOf(Param("n", SlotKind.LONG)), SlotKind.OBJECT
    ) { args -> PrimitiveNatives.intToString(args.long(0)) }

    // ==================== typed registrations for BDSL ====================
    // These bypass the untyped NativeHandler dispatch for the hot ops.
    // See TypedNativeHandlers.java for the interface + registry.
    init {
        // (long, long) -> long
        TypedNativeHandlers.registerLLL("__native_int_add", PrimitiveNatives::intAdd)
        TypedNativeHandlers.registerLLL("__native_int_subtract", PrimitiveNatives::intSubtract)
        TypedNativeHandlers.registerLLL("__native_int_multiply", PrimitiveNatives::intMultiply)
        TypedNativeHandlers.registerLLL("__native_int_divide", PrimitiveNatives::intDivide)
        TypedNativeHandlers.registerLLL("__native_int_modulo", PrimitiveNatives::intModulo)
        TypedNativeHandlers.registerLLL("__native_int_min", PrimitiveNatives::intMin)
        TypedNativeHandlers.registerLLL("__native_int_max", PrimitiveNatives::intMax)
        TypedNativeHandlers.registerLLL("__native_int_pow", NativeHelpers::intPow)
        TypedNativeHandlers.registerLLL("__native_int_gcd", NativeHelpers::intGcd)
        TypedNativeHandlers.registerLLL("__native_int_lcm", NativeHelpers::intLcm)

        // (long) -> long
        TypedNativeHandlers.registerLL("__native_int_abs", PrimitiveNatives::intAbs)
        TypedNativeHandlers.registerLL("__native_int_neg", PrimitiveNatives::intNegate)
        TypedNativeHandlers.registerLL("__native_int_sign", PrimitiveNatives::intSign)

        // (long, long) -> boolean
        TypedNativeHandlers.registerLLB("__native_int_eq", PrimitiveNatives::intEq)
        TypedNativeHandlers.registerLLB("__native_int_neq", PrimitiveNatives::intNeq)
        TypedNativeHandlers.registerLLB("__native_int_lt", PrimitiveNatives::intLt)
        TypedNativeHandlers.registerLLB("__native_int_le", PrimitiveNatives::intLte)
        TypedNativeHandlers.registerLLB("__native_int_gt", PrimitiveNatives::intGt)
        TypedNativeHandlers.registerLLB("__native_int_ge", PrimitiveNatives::intGte)
    }
}
