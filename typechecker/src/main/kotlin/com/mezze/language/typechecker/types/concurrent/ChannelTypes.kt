package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Type definitions for channel operations.
 *
 * Chan is the value type (like TVar for STM)
 * Channel is the effect (like STM)
 *
 * Uses Java's LinkedBlockingQueue internally for BlockingChannel implementation.
 */
object ChannelTypes {

    /**
     * Register all channel native function types.
     */
    fun registerAll(context: MutableMap<String, MezzeType>) {
        // Channel type variable used across operations
        val chanType = MezzeType.VarType(TypeVar("Chan"))
        val elementType = MezzeType.VarType(TypeVar("a"))

        // =========================================================================
        // Channel Creation (pure functions, no effect)
        // =========================================================================

        // __native_channel_make: {} -> __NativeChannel
        context["__native_channel_make"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            chanType  // Returns the raw native channel, wrapped by Chan newtype
        )

        // __native_channel_make_buffered: { capacity: Int } -> __NativeChannel
        context["__native_channel_make_buffered"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "capacity" to MezzeType.IntType
            )),
            chanType
        )

        // =========================================================================
        // BlockingChannel Implementation
        // =========================================================================

        // __native_channel_send: { ch: Chan 'a, value: 'a } -> {}
        context["__native_channel_send"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType,
                "value" to elementType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_channel_recv: { ch: Chan 'a } -> 'a
        context["__native_channel_recv"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType
            )),
            elementType
        )

        // __native_channel_try_send: { ch: Chan 'a, value: 'a } -> Bool
        context["__native_channel_try_send"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType,
                "value" to elementType
            )),
            MezzeType.BooleanType
        )

        // __native_channel_try_recv: { ch: Chan 'a } -> Option 'a
        context["__native_channel_try_recv"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType
            )),
            MezzeType.TypeApplication("Option", listOf(elementType))
        )

        // __native_channel_close: { ch: Chan 'a } -> {}
        context["__native_channel_close"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_channel_is_closed: { ch: Chan 'a } -> Bool
        context["__native_channel_is_closed"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType
            )),
            MezzeType.BooleanType
        )

        // __native_channel_recv_any: { channels: [Chan 'a] } -> { index: Int, value: 'a }
        context["__native_channel_recv_any"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "channels" to MezzeType.TypeApplication("List", listOf(chanType))
            )),
            MezzeType.RecordType(mapOf(
                "index" to MezzeType.IntType,
                "value" to elementType
            ))
        )

        // __native_channel_recv_any_timeout: { channels: [Chan 'a], ms: Int } -> Option { index: Int, value: 'a }
        context["__native_channel_recv_any_timeout"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "channels" to MezzeType.TypeApplication("List", listOf(chanType)),
                "ms" to MezzeType.IntType
            )),
            MezzeType.TypeApplication("Option", listOf(
                MezzeType.RecordType(mapOf(
                    "index" to MezzeType.IntType,
                    "value" to elementType
                ))
            ))
        )

        // =========================================================================
        // MockChannel Implementation (same signatures, different native functions)
        // =========================================================================

        // __native_mock_channel_send: { ch: Chan 'a, value: 'a } -> {}
        context["__native_mock_channel_send"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType,
                "value" to elementType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_mock_channel_recv: { ch: Chan 'a } -> 'a
        context["__native_mock_channel_recv"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType
            )),
            elementType
        )

        // __native_mock_channel_try_send: { ch: Chan 'a, value: 'a } -> Bool
        context["__native_mock_channel_try_send"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType,
                "value" to elementType
            )),
            MezzeType.BooleanType
        )

        // __native_mock_channel_try_recv: { ch: Chan 'a } -> Option 'a
        context["__native_mock_channel_try_recv"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType
            )),
            MezzeType.TypeApplication("Option", listOf(elementType))
        )

        // __native_mock_channel_close: { ch: Chan 'a } -> {}
        context["__native_mock_channel_close"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_mock_channel_is_closed: { ch: Chan 'a } -> Bool
        context["__native_mock_channel_is_closed"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType
            )),
            MezzeType.BooleanType
        )

        // __native_mock_channel_recv_any: { channels: [Chan 'a] } -> { index: Int, value: 'a }
        context["__native_mock_channel_recv_any"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "channels" to MezzeType.TypeApplication("List", listOf(chanType))
            )),
            MezzeType.RecordType(mapOf(
                "index" to MezzeType.IntType,
                "value" to elementType
            ))
        )

        // __native_mock_channel_recv_any_timeout: { channels: [Chan 'a], ms: Int } -> Option { index: Int, value: 'a }
        context["__native_mock_channel_recv_any_timeout"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "channels" to MezzeType.TypeApplication("List", listOf(chanType)),
                "ms" to MezzeType.IntType
            )),
            MezzeType.TypeApplication("Option", listOf(
                MezzeType.RecordType(mapOf(
                    "index" to MezzeType.IntType,
                    "value" to elementType
                ))
            ))
        )
    }
}
