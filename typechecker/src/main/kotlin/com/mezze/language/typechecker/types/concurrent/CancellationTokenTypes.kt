package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

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

    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        val cancellationTokenType = WellKnownTypeApp.of("CancellationToken", emptyList())

        // __native_cancellation_token_new: {} -> CancellationToken <Async>
        fn("__native_cancellation_token_new") {
            returns(cancellationTokenType)
            effects("Async")
        }

        // __native_cancellation_token_cancel: {token: CancellationToken, reason: String} -> {} <Async>
        fn("__native_cancellation_token_cancel") {
            param("token" to cancellationTokenType, "reason" to str)
            returns(unit)
            effects("Async")
        }

        // __native_cancellation_token_is_cancelled: {token: CancellationToken} -> Bool <Async>
        fn("__native_cancellation_token_is_cancelled") {
            param("token" to cancellationTokenType)
            returns(bool)
            effects("Async")
        }

        // __native_cancellation_token_get_reason: {token: CancellationToken} -> Option String <Async>
        fn("__native_cancellation_token_get_reason") {
            param("token" to cancellationTokenType)
            returns(option(str))
            effects("Async")
        }

        // __native_cancellation_token_throw_if_cancelled: {token: CancellationToken} -> {} <Async>
        fn("__native_cancellation_token_throw_if_cancelled") {
            param("token" to cancellationTokenType)
            returns(unit)
            effects("Async")
        }

        // __native_cancellation_token_on_cancellation: {token: CancellationToken, callback: {} -> {}} -> String <Async>
        fn("__native_cancellation_token_on_cancellation") {
            param(
                "token" to cancellationTokenType,
                "callback" to MezzeType.FunctionType(unit, unit)
            )
            returns(str)
            effects("Async")
        }

        // __native_cancellation_token_remove_callback: {token: CancellationToken, id: String} -> {} <Async>
        fn("__native_cancellation_token_remove_callback") {
            param("token" to cancellationTokenType, "id" to str)
            returns(unit)
            effects("Async")
        }

        // __native_cancellation_token_create_child: {token: CancellationToken} -> CancellationToken <Async>
        fn("__native_cancellation_token_create_child") {
            param("token" to cancellationTokenType)
            returns(cancellationTokenType)
            effects("Async")
        }
    }
}
