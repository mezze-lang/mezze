package com.mezze.language.truffleir

import kotlinx.serialization.Serializable

/**
 * PrintType — the compile-time-resolved static shape a value is rendered
 * against by the type-driven Inspect printer (`to_dbg`/`to_str`), #394.
 *
 * The runtime value alone cannot decide every rendering: `Nat` and `Int` are
 * both `Long`; `Frac` is an ordinary two-field record; a `Newtype` is erased
 * to its underlying value; a single-character `Str` is indistinguishable from
 * a `Char`. The printer therefore walks (value, PrintType) in lockstep — the
 * PrintType is computed from the receiver's static type at lowering and baked
 * as a constant into the IR (see PrintTypeBuilder.build).
 *
 * TOTAL BY CONSTRUCTION: there is deliberately no Opaque / fallback case.
 * Every constructor names a real Mezze value shape, `Function` included, so a
 * `MezzeType` with no PrintType case is a builder bug — not something the
 * printer silently laundres. The builder's `when` over the sealed MezzeType is
 * exhaustive (no `else`), so the compiler enforces coverage.
 */
@Serializable
sealed interface PrintType {

    /** Scalar leaf kinds that render distinctly. */
    @Serializable
    enum class ScalarKind { Int, Float, Str, Bool, Char, Nat, Bytes }

    @Serializable
    data class Scalar(val kind: ScalarKind) : PrintType

    /** Frac: a `{numerator, denominator}` record printed `n//d`. */
    @Serializable
    object Frac : PrintType

    /** Newtype: erased at runtime; printed `Name.(inner)`. */
    @Serializable
    data class Newtype(val name: String, val inner: PrintType) : PrintType

    /** Record: printed `{ f = .. }` / `{}` in declared field order. */
    @Serializable
    data class Record(val fields: List<Field>) : PrintType

    @Serializable
    data class Field(val name: String, val type: PrintType)

    /**
     * Variant: a tagged union. Each constructor maps to its payload PrintType,
     * or null for a payload-less constructor (`None`, `True`). The printer
     * selects the arm by the runtime constructor name and renders the payload
     * against its PrintType.
     */
    @Serializable
    data class Variant(val arms: List<Arm>) : PrintType

    @Serializable
    data class Arm(val constructor: String, val payload: PrintType?)

    @Serializable
    data class MzList(val inner: PrintType) : PrintType

    @Serializable
    data class MzSeq(val inner: PrintType) : PrintType

    @Serializable
    data class MzSet(val inner: PrintType) : PrintType

    @Serializable
    data class MzSortedSet(val inner: PrintType) : PrintType

    @Serializable
    data class MzDeque(val inner: PrintType) : PrintType

    @Serializable
    data class MzNonEmptyList(val inner: PrintType) : PrintType

    @Serializable
    data class MzMap(val key: PrintType, val value: PrintType) : PrintType

    @Serializable
    data class MzSortedMap(val key: PrintType, val value: PrintType) : PrintType

    /** Function: printed `<function:name>` / `<function>`. */
    @Serializable
    object Function : PrintType

    /**
     * A value whose static type is not resolved at this position — an
     * unconstrained ADT type parameter that will never actually manifest a
     * printed value along the reachable arm (e.g. `Ok.val` when the value is
     * always `Err`). This is NOT a fallback for concrete types: the builder
     * emits it ONLY for a genuine VarType, and the printer renders it with the
     * runtime value-only path (which is correct for every runtime-
     * distinguishable type). Nat/Frac/Newtype/Char can only reach here behind
     * an unconstrained type variable — i.e. never as an actual runtime value.
     */
    @Serializable
    object Dynamic : PrintType
}
