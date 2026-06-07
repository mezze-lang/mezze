package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.language.truffleir.SlotKind

/**
 * Float natives declared in DSL form. Each declaration is the single
 * source of truth for both IR lowering (positional order + kinds) and
 * runtime dispatch (handler body).
 *
 * Migrating an op here makes it route through the scalar-ABI path:
 *  - the typechecker transposes the user record into positional args
 *  - CallNativeIR carries args + argKinds, not a single record
 *  - NativeDispatchScalarNode evaluates args[i].execute(frame) directly,
 *    no MezzeRecord alloc, no field-name string lookup
 *
 * Slow-path entries in NativeDispatch.registerFloat{Basic,}Handlers are
 * kept for now as a safety net for non-MakeRecordIR call shapes (rare).
 * They become dead once we trust the scalar path. The DS-Wire entries in
 * TruffleCompiler.tryCompileDSWiredNative for these same names are also
 * dead-on-arrival here — the scalar branch fires first.
 *
 * Order of params here = positional order passed to the handler =
 * positional order in CallNativeIR.args after IR transposition. The
 * names must match the field names the user (or stdlib wrapper) writes;
 * if they drift, signatureOf().indexOf() returns -1 and we fall back.
 *
 * Semantics match the WINNING slow-path registration (extended ops in
 * registerFloatHandlers shadow basic ones for clamp/sign/to_int/etc).
 */
object FloatOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    // ==================== unary Float -> Float ====================
    private val ABS = NativeOp("__native_float_abs",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatAbs(args.double(0)) }

    private val NEG = NativeOp("__native_float_neg",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatNegate(args.double(0)) }

    private val SQRT = NativeOp("__native_float_sqrt",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatSqrt(args.double(0)) }

    private val FLOOR = NativeOp("__native_float_floor",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatFloor(args.double(0)) }

    private val CEIL = NativeOp("__native_float_ceil",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatCeil(args.double(0)) }

    // Round returns long in PrimitiveNatives; basic-path coerces to double.
    private val ROUND = NativeOp("__native_float_round",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatRound(args.double(0)).toDouble() }

    // Sign — extended-path uses Math.signum directly.
    private val SIGN = NativeOp("__native_float_sign",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> Math.signum(args.double(0)) }

    // ==================== unary Float -> Float (trig/exp/log) ====================
    // Extended-path versions call Math.* directly, not PrimitiveNatives.
    private val SIN = NativeOp("__native_float_sin",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatSin(args.double(0)) }

    private val COS = NativeOp("__native_float_cos",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatCos(args.double(0)) }

    private val TAN = NativeOp("__native_float_tan",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatTan(args.double(0)) }

    private val ASIN = NativeOp("__native_float_asin",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> Math.asin(args.double(0)) }

    private val ACOS = NativeOp("__native_float_acos",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> Math.acos(args.double(0)) }

    private val ATAN = NativeOp("__native_float_atan",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> Math.atan(args.double(0)) }

    private val LN = NativeOp("__native_float_ln",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> Math.log(args.double(0)) }

    private val LOG10 = NativeOp("__native_float_log10",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> Math.log10(args.double(0)) }

    private val EXP = NativeOp("__native_float_exp",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> Math.exp(args.double(0)) }

    // ==================== binary Float, Float -> Float ====================
    private val ADD = NativeOp("__native_float_add",
        listOf(Param("l", SlotKind.DOUBLE), Param("r", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatAdd(args.double(0), args.double(1)) }

    private val SUBTRACT = NativeOp("__native_float_subtract",
        listOf(Param("l", SlotKind.DOUBLE), Param("r", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatSubtract(args.double(0), args.double(1)) }

    private val MULTIPLY = NativeOp("__native_float_multiply",
        listOf(Param("l", SlotKind.DOUBLE), Param("r", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatMultiply(args.double(0), args.double(1)) }

    private val DIVIDE = NativeOp("__native_float_divide",
        listOf(Param("l", SlotKind.DOUBLE), Param("r", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatDivide(args.double(0), args.double(1)) }

    private val MOD = NativeOp("__native_float_mod",
        listOf(Param("l", SlotKind.DOUBLE), Param("r", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> args.double(0) % args.double(1) }

    private val MIN = NativeOp("__native_float_min",
        listOf(Param("l", SlotKind.DOUBLE), Param("r", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatMin(args.double(0), args.double(1)) }

    private val MAX = NativeOp("__native_float_max",
        listOf(Param("l", SlotKind.DOUBLE), Param("r", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatMax(args.double(0), args.double(1)) }

    private val POW = NativeOp("__native_float_pow",
        listOf(Param("base", SlotKind.DOUBLE), Param("exp", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> PrimitiveNatives.floatPow(args.double(0), args.double(1)) }

    // atan2 takes (y, x) — order matters, matches Math.atan2 + stdlib.
    private val ATAN2 = NativeOp("__native_float_atan2",
        listOf(Param("y", SlotKind.DOUBLE), Param("x", SlotKind.DOUBLE)), SlotKind.DOUBLE
    ) { args -> Math.atan2(args.double(0), args.double(1)) }

    // ==================== ternary clamp ====================
    private val CLAMP = NativeOp("__native_float_clamp",
        listOf(Param("n", SlotKind.DOUBLE), Param("min", SlotKind.DOUBLE), Param("max", SlotKind.DOUBLE)),
        SlotKind.DOUBLE
    ) { args ->
        val n = args.double(0); val mn = args.double(1); val mx = args.double(2)
        Math.max(mn, Math.min(mx, n))
    }

    // ==================== Float -> Bool predicates ====================
    private val IS_NAN = NativeOp("__native_float_is_nan",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.BOOLEAN
    ) { args -> java.lang.Double.isNaN(args.double(0)) }

    private val IS_INFINITE = NativeOp("__native_float_is_infinite",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.BOOLEAN
    ) { args -> java.lang.Double.isInfinite(args.double(0)) }

    private val IS_FINITE = NativeOp("__native_float_is_finite",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.BOOLEAN
    ) { args -> java.lang.Double.isFinite(args.double(0)) }

    // ==================== Float, Float -> Bool comparisons ====================
    private val EQ = NativeOp("__native_float_eq",
        listOf(Param("l", SlotKind.DOUBLE), Param("r", SlotKind.DOUBLE)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.floatEq(args.double(0), args.double(1)) }

    private val NEQ = NativeOp("__native_float_neq",
        listOf(Param("l", SlotKind.DOUBLE), Param("r", SlotKind.DOUBLE)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.floatNeq(args.double(0), args.double(1)) }

    private val LT = NativeOp("__native_float_lt",
        listOf(Param("l", SlotKind.DOUBLE), Param("r", SlotKind.DOUBLE)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.floatLt(args.double(0), args.double(1)) }

    private val LE = NativeOp("__native_float_le",
        listOf(Param("l", SlotKind.DOUBLE), Param("r", SlotKind.DOUBLE)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.floatLte(args.double(0), args.double(1)) }

    private val GT = NativeOp("__native_float_gt",
        listOf(Param("l", SlotKind.DOUBLE), Param("r", SlotKind.DOUBLE)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.floatGt(args.double(0), args.double(1)) }

    private val GE = NativeOp("__native_float_ge",
        listOf(Param("l", SlotKind.DOUBLE), Param("r", SlotKind.DOUBLE)), SlotKind.BOOLEAN
    ) { args -> PrimitiveNatives.floatGte(args.double(0), args.double(1)) }

    // ==================== conversions ====================
    private val FROM_INT = NativeOp("__native_float_from_int",
        listOf(Param("n", SlotKind.LONG)), SlotKind.DOUBLE
    ) { args -> args.long(0).toDouble() }

    private val FROM_NAT = NativeOp("__native_float_from_nat",
        listOf(Param("n", SlotKind.LONG)), SlotKind.DOUBLE
    ) { args -> args.long(0).toDouble() }

    // to_int returns Option<Int> — None for NaN/Infinity. Result is an
    // Object (MezzeVariant), so declared return kind is OBJECT.
    private val TO_INT = NativeOp("__native_float_to_int",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.OBJECT
    ) { args ->
        val n = args.double(0)
        if (java.lang.Double.isNaN(n) || java.lang.Double.isInfinite(n)) {
            MezzeVariant.none()
        } else {
            MezzeVariant.some(n.toLong())
        }
    }

    private val TO_STR = NativeOp("__native_float_to_str",
        listOf(Param("n", SlotKind.DOUBLE)), SlotKind.OBJECT
    ) { args -> java.lang.Double.toString(args.double(0)) }

    // __native_float_from_string stays on the legacy record path.
    // Reason: its slow-path implementation calls `floatFromString(...)`,
    // a private helper inside NativeDispatch.java that returns a
    // MezzeVariant. Duplicating that helper here would silently diverge
    // if the slow path changed. The op is cold (parsing user input);
    // record-ABI dispatch cost is irrelevant.
}
