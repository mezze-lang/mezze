package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType

/**
 * Type definitions for CancellationToken operations.
 *
 * Every native carries `<Async>` (post-#170).
 *
 * CancellationToken provides cooperative cancellation for async operations.
 * It supports checking cancellation status, registering callbacks, and
 * creating child tokens that form a cancellation hierarchy.
 */
object CancellationTokenTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        val cancellationTokenType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("CancellationToken", emptyList())
        val unitType = MezzeType.RecordType(emptyMap())
        val asyncEffect = MezzeType.EffectRow(effects = setOf("Async"))

        // __native_cancellation_token_new: {} -> CancellationToken <Async>
        context["__native_cancellation_token_new"] = MezzeType.FunctionType(
            unitType,
            cancellationTokenType,
            effects = asyncEffect
        )

        // __native_cancellation_token_cancel: {token: CancellationToken, reason: String} -> {} <Async>
        context["__native_cancellation_token_cancel"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType,
                "reason" to MezzeType.StringType
            )),
            unitType,
            effects = asyncEffect
        )

        // __native_cancellation_token_is_cancelled: {token: CancellationToken} -> Bool <Async>
        context["__native_cancellation_token_is_cancelled"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType
            )),
            MezzeType.BoolType,
            effects = asyncEffect
        )

        // __native_cancellation_token_get_reason: {token: CancellationToken} -> Option String <Async>
        context["__native_cancellation_token_get_reason"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType
            )),
            com.mezze.language.typechecker.util.WellKnownTypeApp.of("Option", listOf(MezzeType.StringType)),
            effects = asyncEffect
        )

        // __native_cancellation_token_throw_if_cancelled: {token: CancellationToken} -> {} <Async>
        context["__native_cancellation_token_throw_if_cancelled"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType
            )),
            unitType,
            effects = asyncEffect
        )

        // __native_cancellation_token_on_cancellation: {token: CancellationToken, callback: {} -> {}} -> String <Async>
        context["__native_cancellation_token_on_cancellation"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType,
                "callback" to MezzeType.FunctionType(unitType, unitType)
            )),
            MezzeType.StringType,
            effects = asyncEffect
        )

        // __native_cancellation_token_remove_callback: {token: CancellationToken, id: String} -> {} <Async>
        context["__native_cancellation_token_remove_callback"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType,
                "id" to MezzeType.StringType
            )),
            unitType,
            effects = asyncEffect
        )

        // __native_cancellation_token_create_child: {token: CancellationToken} -> CancellationToken <Async>
        context["__native_cancellation_token_create_child"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType
            )),
            cancellationTokenType,
            effects = asyncEffect
        )
    }
}
