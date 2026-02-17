package com.funk.language.typechecker.types.concurrent

import com.funk.language.ast.FunkType

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
    fun registerAll(context: MutableMap<String, FunkType>) {
        val cancellationTokenType = FunkType.TypeApplication("CancellationToken", emptyList())
        val unitType = FunkType.RecordType(emptyMap())

        // __native_cancellation_token_new: {} -> CancellationToken
        context["__native_cancellation_token_new"] = FunkType.FunctionType(
            unitType,
            cancellationTokenType
        )

        // __native_cancellation_token_cancel: {token: CancellationToken, reason: String} -> {}
        context["__native_cancellation_token_cancel"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "token" to cancellationTokenType,
                "reason" to FunkType.StringType
            )),
            unitType
        )

        // __native_cancellation_token_is_cancelled: {token: CancellationToken} -> Bool
        context["__native_cancellation_token_is_cancelled"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "token" to cancellationTokenType
            )),
            FunkType.BooleanType
        )

        // __native_cancellation_token_get_reason: {token: CancellationToken} -> Option String
        context["__native_cancellation_token_get_reason"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "token" to cancellationTokenType
            )),
            FunkType.TypeApplication("Option", listOf(FunkType.StringType))
        )

        // __native_cancellation_token_throw_if_cancelled: {token: CancellationToken} -> {}
        context["__native_cancellation_token_throw_if_cancelled"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "token" to cancellationTokenType
            )),
            unitType
        )

        // __native_cancellation_token_on_cancellation: {token: CancellationToken, callback: {} -> {}} -> String
        context["__native_cancellation_token_on_cancellation"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "token" to cancellationTokenType,
                "callback" to FunkType.FunctionType(unitType, unitType)
            )),
            FunkType.StringType
        )

        // __native_cancellation_token_remove_callback: {token: CancellationToken, id: String} -> {}
        context["__native_cancellation_token_remove_callback"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "token" to cancellationTokenType,
                "id" to FunkType.StringType
            )),
            unitType
        )

        // __native_cancellation_token_create_child: {token: CancellationToken} -> CancellationToken
        context["__native_cancellation_token_create_child"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "token" to cancellationTokenType
            )),
            cancellationTokenType
        )
    }
}
