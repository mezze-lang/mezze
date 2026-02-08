package com.funk.language.typechecker.types.concurrent

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

/**
 * Type definitions for channel operations.
 *
 * Channels provide typed communication between virtual threads.
 * Uses Java's LinkedBlockingQueue internally.
 */
object ChannelTypes {

    /**
     * Register all channel native function types.
     */
    fun registerAll(context: MutableMap<String, FunkType>) {
        // Channel type variable used across operations
        val channelType = FunkType.VarType(TypeVar("Channel"))
        val elementType = FunkType.VarType(TypeVar("a"))

        // __native_channel_make: {} -> Channel 'a
        context["__native_channel_make"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            channelType
        )

        // __native_channel_make_buffered: { capacity: Int } -> Channel 'a
        context["__native_channel_make_buffered"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "capacity" to FunkType.IntType
            )),
            channelType
        )

        // __native_channel_send: { ch: Channel 'a, value: 'a } -> {}
        context["__native_channel_send"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to channelType,
                "value" to elementType
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_channel_recv: { ch: Channel 'a } -> 'a
        context["__native_channel_recv"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to channelType
            )),
            elementType
        )

        // __native_channel_try_send: { ch: Channel 'a, value: 'a } -> Bool
        context["__native_channel_try_send"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to channelType,
                "value" to elementType
            )),
            FunkType.BooleanType
        )

        // __native_channel_try_recv: { ch: Channel 'a } -> Option 'a
        context["__native_channel_try_recv"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to channelType
            )),
            FunkType.TypeApplication("Option", listOf(elementType))
        )

        // __native_channel_close: { ch: Channel 'a } -> {}
        context["__native_channel_close"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to channelType
            )),
            FunkType.RecordType(emptyMap())
        )
    }
}
