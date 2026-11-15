package com.mezze.language.typechecker.constraints

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
