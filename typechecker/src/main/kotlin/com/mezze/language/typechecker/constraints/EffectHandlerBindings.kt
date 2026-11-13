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

    /** `{ val: opReturn } -> performReturn` */
    private fun resumeType(opReturn: MezzeType, performReturn: MezzeType): MezzeType =
        MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(ScopedEffectConstants.VAL_FIELD to opReturn)),
            performReturn
        )

    /** `{ val: performReturn } -> 'never` (fresh var unifies with anything) */
    private fun abortType(performReturn: MezzeType): MezzeType =
        MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(ScopedEffectConstants.VAL_FIELD to performReturn)),
            MezzeType.VarType(TypeVar.fresh())
        )
}
