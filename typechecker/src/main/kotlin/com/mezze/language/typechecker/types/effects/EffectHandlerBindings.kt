package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.scope.ScopedEffectConstants

/**
 * Runtime-bound names available inside an effect op handler body.
 *
 * When typechecking `op = { ... } -> body` inside an `impl X for Y where`,
 * the runtime contributes two functions to body's scope:
 *
 *   resume : { val: 'op_ret } -> 'r
 *   abort  : { val: 'r } -> 'never
 *
 * `'op_ret` = the op's declared return type.
 * `'r`      = the perform's overall return type. Fresh at impl-definition
 *             time; unified at the `perform Handler in body` use site.
 *
 * Falling off the op body normally is treated as `resume { val = <last expr> }`,
 * so most handlers never name `resume`. `abort { val = X }` ends the perform
 * with `X` as its result, discarding the body.
 */
object EffectHandlerBindings {

    /**
     * Extend `context` with `abort` and `resume` bindings for an op handler
     * whose declared return type is `opReturnType`.
     *
     * The shared 'r type variable links both bindings: any `abort {val = X}`
     * in this op body must produce the same type that `resume` returns.
     */
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
