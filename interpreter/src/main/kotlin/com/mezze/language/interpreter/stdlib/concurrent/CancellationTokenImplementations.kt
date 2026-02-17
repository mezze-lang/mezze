package com.mezze.language.interpreter.stdlib.concurrent

import com.mezze.language.interpreter.*
import com.mezze.language.interpreter.effects.CancellationToken
import com.mezze.language.interpreter.effects.EffectCancellationException
import java.util.UUID

/**
 * Native implementations for CancellationToken.
 *
 * CancellationToken provides cooperative cancellation for async operations.
 * It allows:
 * - Checking if cancellation has been requested
 * - Registering callbacks to run on cancellation
 * - Creating child tokens that are cancelled when parent is cancelled
 * - Throwing exceptions at cancellation checkpoints
 *
 * Usage pattern:
 *   let token = cancellation_token {}
 *   # Pass token to async operations
 *   # In async code: check is_cancelled, or use throw_if_cancelled
 *   # When done: cancel {token = token}
 */
object CancellationTokenImplementations {

    /**
     * Register all cancellation token native functions.
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        registerNew(context)
        registerCancel(context)
        registerIsCancelled(context)
        registerGetReason(context)
        registerThrowIfCancelled(context)
        registerOnCancellation(context)
        registerRemoveCallback(context)
        registerCreateChild(context)
    }

    /**
     * __native_cancellation_token_new: {} -> CancellationToken
     * Creates a new cancellation token.
     */
    private fun registerNew(context: MutableMap<String, MezzeValue>) {
        context["__native_cancellation_token_new"] = NativeMezzeFunction("__native_cancellation_token_new") { _ ->
            MezzeCancellationToken(CancellationToken())
        }
    }

    /**
     * __native_cancellation_token_cancel: {token: CancellationToken, reason: String} -> {}
     * Cancels the token with the given reason.
     */
    private fun registerCancel(context: MutableMap<String, MezzeValue>) {
        context["__native_cancellation_token_cancel"] = NativeMezzeFunction("__native_cancellation_token_cancel") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_cancellation_token_cancel expects MezzeRecord")

            val token = record.fields["token"] as? MezzeCancellationToken
                ?: throw MezzeException("__native_cancellation_token_cancel expects 'token' field of type CancellationToken")

            val reason = (record.fields["reason"] as? MezzeString)?.value
                ?: "Operation cancelled"

            token.token.cancel(reason)
            MezzeUnit
        }
    }

    /**
     * __native_cancellation_token_is_cancelled: {token: CancellationToken} -> Bool
     * Checks if the token has been cancelled.
     */
    private fun registerIsCancelled(context: MutableMap<String, MezzeValue>) {
        context["__native_cancellation_token_is_cancelled"] = NativeMezzeFunction("__native_cancellation_token_is_cancelled") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_cancellation_token_is_cancelled expects MezzeRecord")

            val token = record.fields["token"] as? MezzeCancellationToken
                ?: throw MezzeException("__native_cancellation_token_is_cancelled expects 'token' field of type CancellationToken")

            MezzeBool(token.token.isCancelled())
        }
    }

    /**
     * __native_cancellation_token_get_reason: {token: CancellationToken} -> Option String
     * Gets the cancellation reason if cancelled, or None.
     */
    private fun registerGetReason(context: MutableMap<String, MezzeValue>) {
        context["__native_cancellation_token_get_reason"] = NativeMezzeFunction("__native_cancellation_token_get_reason") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_cancellation_token_get_reason expects MezzeRecord")

            val token = record.fields["token"] as? MezzeCancellationToken
                ?: throw MezzeException("__native_cancellation_token_get_reason expects 'token' field of type CancellationToken")

            val reason = token.token.getCancellationReason()
            if (reason != null) {
                MezzeVariant("Some", MezzeRecord(mapOf("val" to MezzeString(reason))))
            } else {
                MezzeVariant("None", MezzeRecord(emptyMap()))
            }
        }
    }

    /**
     * __native_cancellation_token_throw_if_cancelled: {token: CancellationToken} -> {}
     * Throws an exception if the token has been cancelled.
     * Use this at cancellation checkpoints.
     */
    private fun registerThrowIfCancelled(context: MutableMap<String, MezzeValue>) {
        context["__native_cancellation_token_throw_if_cancelled"] = NativeMezzeFunction("__native_cancellation_token_throw_if_cancelled") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_cancellation_token_throw_if_cancelled expects MezzeRecord")

            val token = record.fields["token"] as? MezzeCancellationToken
                ?: throw MezzeException("__native_cancellation_token_throw_if_cancelled expects 'token' field of type CancellationToken")

            token.token.throwIfCancelled()
            MezzeUnit
        }
    }

    /**
     * __native_cancellation_token_on_cancellation: {token: CancellationToken, callback: {} -> {}} -> String
     * Registers a callback to be called when the token is cancelled.
     * Returns a unique ID that can be used to remove the callback.
     * If the token is already cancelled, the callback is invoked immediately.
     */
    private fun registerOnCancellation(context: MutableMap<String, MezzeValue>) {
        context["__native_cancellation_token_on_cancellation"] = NativeMezzeFunction("__native_cancellation_token_on_cancellation") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_cancellation_token_on_cancellation expects MezzeRecord")

            val token = record.fields["token"] as? MezzeCancellationToken
                ?: throw MezzeException("__native_cancellation_token_on_cancellation expects 'token' field of type CancellationToken")

            val callback = record.fields["callback"]
                ?: throw MezzeException("__native_cancellation_token_on_cancellation expects 'callback' field")

            val callbackId = UUID.randomUUID().toString()

            token.token.onCancellation(callbackId) {
                // Execute the Mezze callback
                when (callback) {
                    is MezzeTruffleFunction -> {
                        val emptyArgs = MezzeRecord(emptyMap())
                        callback.callTarget.call(
                            emptyArgs,
                            callback.closureVariables,
                            callback.lexicalScope,
                            callback.lexicalScopeSlotMap
                        )
                    }
                    is NativeMezzeFunction -> {
                        callback.call(listOf(MezzeRecord(emptyMap())))
                    }
                    else -> throw MezzeException("callback must be a function")
                }
            }

            MezzeString(callbackId)
        }
    }

    /**
     * __native_cancellation_token_remove_callback: {token: CancellationToken, id: String} -> {}
     * Removes a previously registered cancellation callback.
     */
    private fun registerRemoveCallback(context: MutableMap<String, MezzeValue>) {
        context["__native_cancellation_token_remove_callback"] = NativeMezzeFunction("__native_cancellation_token_remove_callback") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_cancellation_token_remove_callback expects MezzeRecord")

            val token = record.fields["token"] as? MezzeCancellationToken
                ?: throw MezzeException("__native_cancellation_token_remove_callback expects 'token' field of type CancellationToken")

            val id = (record.fields["id"] as? MezzeString)?.value
                ?: throw MezzeException("__native_cancellation_token_remove_callback expects 'id' field of type String")

            token.token.removeCancellationCallback(id)
            MezzeUnit
        }
    }

    /**
     * __native_cancellation_token_create_child: {token: CancellationToken} -> CancellationToken
     * Creates a child token that is cancelled when the parent is cancelled.
     * The child can also be cancelled independently.
     */
    private fun registerCreateChild(context: MutableMap<String, MezzeValue>) {
        context["__native_cancellation_token_create_child"] = NativeMezzeFunction("__native_cancellation_token_create_child") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_cancellation_token_create_child expects MezzeRecord")

            val parentToken = record.fields["token"] as? MezzeCancellationToken
                ?: throw MezzeException("__native_cancellation_token_create_child expects 'token' field of type CancellationToken")

            val childToken = parentToken.token.createChild()
            MezzeCancellationToken(childToken)
        }
    }
}

/**
 * MezzeCancellationToken - wraps a CancellationToken for Mezze.
 */
data class MezzeCancellationToken(
    val token: CancellationToken
) : MezzeValue() {
    override fun getDisplayString(): String = "<cancellation-token:${if (token.isCancelled()) "cancelled" else "active"}>"
}
