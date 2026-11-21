package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.RowVar
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.ast.ScopedEffectConstants

object EffectHandlerBindings {

    /**
     * `cont` — the ONE runtime-bound name a performer op body gets for its
     * continuation (the bare `resume`/`abort` names are gone: one mechanism,
     * one spelling):
     *
     *     cont.resume { val: opReturn } -> opReturn
     *     cont.abort  { val: performReturn } -> 'never
     *     cont.park   { on: ParkOn, set: WaitSet } -> ParkOutcome <...>
     *
     * [performReturnVar] lets the CALLER own abort's payload variable, so the
     * type the solve unifies it with survives the solve.
     *
     * `park` carries an OPEN row on purpose: it is the one call in a handler that
     * suspends, and the lowering propagates a suspension only through calls whose
     * row says they can (C1's `maySuspend`). Its three types are the stdlib's,
     * referenced by identity so a performer in ANY module sees them; when the
     * stdlib is absent (bare-typechecker tests) `cont` has no `park` field.
     */
    fun extendWithCont(
        context: TypingContext,
        opReturnType: MezzeType,
        performReturnVar: MezzeType? = null,
    ): TypingContext {
        val performReturn = performReturnVar ?: MezzeType.VarType(TypeVar.fresh())
        val fields = linkedMapOf(
            ScopedEffectConstants.CONT_RESUME to resumeType(opReturnType),
            ScopedEffectConstants.CONT_ABORT to abortType(performReturn),
        )
        parkType()?.let { fields[ScopedEffectConstants.CONT_PARK] = it }
        return context.extendMono(ScopedEffectConstants.CONT, MezzeType.RecordType(fields))
    }

    private fun parkType(): MezzeType? {
        val parkOn = stdlibType("ParkOn") ?: return null
        val waitSet = stdlibType("WaitSet") ?: return null
        val outcome = stdlibType("ParkOutcome") ?: return null
        return MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("on" to parkOn, "set" to waitSet)),
            outcome,
            effects = MezzeType.EffectRow(effects = emptySet(), rowVar = RowVar.userOpen()),
        )
    }

    internal fun stdlibType(name: String): MezzeType? =
        com.mezze.language.typechecker.util.WellKnownTypeApp.resolveHash(name)
            ?.let { com.mezze.language.typechecker.util.WellKnownTypeApp.of(name, emptyList(), it) }

    /**
     * `cont.resume`: `{ val: opReturn } -> opReturn` — resume is IDENTITY.
     *
     * It RETURNED [performReturn], which is abort's payload variable, and that
     * conflated the two: a handler whose tail is `cont.resume { val = … }` then
     * unified the perform BLOCK's type with the OP's return type. Measured on
     * `integration/all-three`, where the perf branch's explicit
     * `cont.resume { val = X }` form (f8a783733) met this branch's abort-payload
     * check and five `ResumeFallThroughSemanticsTest` cases failed with
     *
     *     Performer 'Fixed' aborts with Str, but this `perform` block is used as Int
     *
     * for `effect Ask where { ask: {} -> Str }` handled inside a block yielding
     * Int. Nothing aborts in that program.
     *
     * Resume hands its payload back AT THE THROW SITE and the handler body
     * continues, so the call evaluates to the payload — the op's return type —
     * and says nothing about what the block yields. `abort` is the one that
     * decides the block, and it keeps [performReturn].
     *
     * The same suite pins the identity: `let r = cont.resume { val = 1 }` then
     * `r + 5` expects 6 per operation, so `r` IS the payload.
     */
    private fun resumeType(opReturn: MezzeType): MezzeType =
        MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(ScopedEffectConstants.VAL_FIELD to opReturn)),
            opReturn
        )

    /** `cont.abort`: `{ val: performReturn } -> 'never` (fresh var unifies with anything) */
    private fun abortType(performReturn: MezzeType): MezzeType =
        MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(ScopedEffectConstants.VAL_FIELD to performReturn)),
            MezzeType.VarType(TypeVar.fresh())
        )
}
