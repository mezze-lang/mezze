package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.RowVar
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.ast.ScopedEffectConstants

object EffectHandlerBindings {

    /**
     * [performReturnVar] lets the CALLER own the payload variable, so the type
     * the solve unifies it with survives the solve. Defaults to a fresh one for
     * callers that do not care.
     */
    fun extendWithAbortAndResume(
        context: TypingContext,
        opReturnType: MezzeType,
        performReturnVar: MezzeType? = null,
    ): TypingContext {
        val performReturn = performReturnVar ?: MezzeType.VarType(TypeVar.fresh())
        return context
            .extendMono(ScopedEffectConstants.RESUME, resumeType(opReturnType, performReturn))
            .extendMono(ScopedEffectConstants.ABORT, abortType(performReturn))
    }

    /**
     * `cont` — the runtime-owned continuation handle a performer op body may use:
     *
     *     cont.park  { on: ParkOn, set: WaitSet } -> ParkOutcome <...>
     *     cont.abort { val: performReturn } -> 'never
     *
     * `park` carries an OPEN row on purpose: it is the one call in a handler that
     * suspends, and the lowering propagates a suspension only through calls whose
     * row says they can (C1's `maySuspend`). The three types are the stdlib's,
     * referenced by identity so a performer in ANY module sees them; when the
     * stdlib is absent (bare-typechecker tests), `cont` is simply not bound and
     * stays V0702 as before.
     */
    fun extendWithCont(context: TypingContext, performReturn: MezzeType): TypingContext {
        val parkOn = stdlibType("ParkOn") ?: return context
        val waitSet = stdlibType("WaitSet") ?: return context
        val outcome = stdlibType("ParkOutcome") ?: return context
        val parkType = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("on" to parkOn, "set" to waitSet)),
            outcome,
            effects = MezzeType.EffectRow(effects = emptySet(), rowVar = RowVar.userOpen()),
        )
        val contType = MezzeType.RecordType(mapOf(
            ScopedEffectConstants.CONT_PARK to parkType,
            ScopedEffectConstants.CONT_ABORT to abortType(performReturn),
        ))
        return context.extendMono(ScopedEffectConstants.CONT, contType)
    }

    internal fun stdlibType(name: String): MezzeType? =
        com.mezze.language.typechecker.util.WellKnownTypeApp.resolveHash(name)
            ?.let { com.mezze.language.typechecker.util.WellKnownTypeApp.of(name, emptyList(), it) }

    /**
     * `{ val: opReturn } -> opReturn` — resume is IDENTITY.
     *
     * It RETURNED [performReturn], which is abort's payload variable, and that
     * conflated the two: a handler whose tail is `resume { val = … }` then
     * unified the perform BLOCK's type with the OP's return type. Measured on
     * `integration/all-three`, where the perf branch's explicit
     * `resume { val = X }` form (f8a783733) met this branch's abort-payload
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
     * The same suite pins the identity: `let r = resume { val = 1 }` then
     * `r + 5` expects 6 per operation, so `r` IS the payload.
     */
    private fun resumeType(opReturn: MezzeType, @Suppress("UNUSED_PARAMETER") performReturn: MezzeType): MezzeType =
        MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(ScopedEffectConstants.VAL_FIELD to opReturn)),
            opReturn
        )

    /** `{ val: performReturn } -> 'never` (fresh var unifies with anything) */
    private fun abortType(performReturn: MezzeType): MezzeType =
        MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(ScopedEffectConstants.VAL_FIELD to performReturn)),
            MezzeType.VarType(TypeVar.fresh())
        )
}
