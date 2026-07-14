package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.ast.ScopedEffectConstants

object EffectHandlerBindings {

    fun extendWithAbortAndResume(
        context: TypingContext,
        opReturnType: MezzeType
    ): TypingContext {
        val performReturn = MezzeType.VarType(TypeVar.fresh())
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
