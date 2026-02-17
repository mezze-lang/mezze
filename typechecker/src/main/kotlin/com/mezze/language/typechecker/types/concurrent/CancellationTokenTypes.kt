package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType

/**
 * Type definitions for CancellationToken operations.
 *
 * CancellationToken provides cooperative cancellation for async operations.
 * It supports checking cancellation status, registering callbacks, and
 * creating child tokens that form a cancellation hierarchy.
 */
object CancellationTokenTypes {

    /**
     * Register all cancellation token native function types.
     */
    fun registerAll(context: MutableMap<String, MezzeType>) {
        val cancellationTokenType = MezzeType.TypeApplication("CancellationToken", emptyList())
        val unitType = MezzeType.RecordType(emptyMap())

        // __native_cancellation_token_new: {} -> CancellationToken
        context["__native_cancellation_token_new"] = MezzeType.FunctionType(
            unitType,
            cancellationTokenType
        )

        // __native_cancellation_token_cancel: {token: CancellationToken, reason: String} -> {}
        context["__native_cancellation_token_cancel"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType,
                "reason" to MezzeType.StringType
            )),
            unitType
        )

        // __native_cancellation_token_is_cancelled: {token: CancellationToken} -> Bool
        context["__native_cancellation_token_is_cancelled"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType
            )),
            MezzeType.BooleanType
        )

        // __native_cancellation_token_get_reason: {token: CancellationToken} -> Option String
        context["__native_cancellation_token_get_reason"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType
            )),
            MezzeType.TypeApplication("Option", listOf(MezzeType.StringType))
        )

        // __native_cancellation_token_throw_if_cancelled: {token: CancellationToken} -> {}
        context["__native_cancellation_token_throw_if_cancelled"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType
            )),
            unitType
        )

        // __native_cancellation_token_on_cancellation: {token: CancellationToken, callback: {} -> {}} -> String
        context["__native_cancellation_token_on_cancellation"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType,
                "callback" to MezzeType.FunctionType(unitType, unitType)
            )),
            MezzeType.StringType
        )

        // __native_cancellation_token_remove_callback: {token: CancellationToken, id: String} -> {}
        context["__native_cancellation_token_remove_callback"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType,
                "id" to MezzeType.StringType
            )),
            unitType
        )

        // __native_cancellation_token_create_child: {token: CancellationToken} -> CancellationToken
        context["__native_cancellation_token_create_child"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "token" to cancellationTokenType
            )),
            cancellationTokenType
        )
    }
}
