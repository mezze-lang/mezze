package com.funk.language.typechecker.types.concurrent

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

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
    fun registerAll(context: MutableMap<String, FunkType>) {
        // Channel type variable used across operations
        val chanType = FunkType.VarType(TypeVar("Chan"))
        val elementType = FunkType.VarType(TypeVar("a"))

        // =========================================================================
        // Channel Creation (pure functions, no effect)
        // =========================================================================

        // __native_channel_make: {} -> __NativeChannel
        context["__native_channel_make"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            chanType  // Returns the raw native channel, wrapped by Chan newtype
        )

        // __native_channel_make_buffered: { capacity: Int } -> __NativeChannel
        context["__native_channel_make_buffered"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "capacity" to FunkType.IntType
            )),
            chanType
        )

        // =========================================================================
        // BlockingChannel Implementation
        // =========================================================================

        // __native_channel_send: { ch: Chan 'a, value: 'a } -> {}
        context["__native_channel_send"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to chanType,
                "value" to elementType
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_channel_recv: { ch: Chan 'a } -> 'a
        context["__native_channel_recv"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to chanType
            )),
            elementType
        )

        // __native_channel_try_send: { ch: Chan 'a, value: 'a } -> Bool
        context["__native_channel_try_send"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to chanType,
                "value" to elementType
            )),
            FunkType.BooleanType
        )

        // __native_channel_try_recv: { ch: Chan 'a } -> Option 'a
        context["__native_channel_try_recv"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to chanType
            )),
            FunkType.TypeApplication("Option", listOf(elementType))
        )

        // __native_channel_close: { ch: Chan 'a } -> {}
        context["__native_channel_close"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to chanType
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_channel_is_closed: { ch: Chan 'a } -> Bool
        context["__native_channel_is_closed"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to chanType
            )),
            FunkType.BooleanType
        )

        // __native_channel_recv_any: { channels: [Chan 'a] } -> { index: Int, value: 'a }
        context["__native_channel_recv_any"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "channels" to FunkType.TypeApplication("List", listOf(chanType))
            )),
            FunkType.RecordType(mapOf(
                "index" to FunkType.IntType,
                "value" to elementType
            ))
        )

        // __native_channel_recv_any_timeout: { channels: [Chan 'a], ms: Int } -> Option { index: Int, value: 'a }
        context["__native_channel_recv_any_timeout"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "channels" to FunkType.TypeApplication("List", listOf(chanType)),
                "ms" to FunkType.IntType
            )),
            FunkType.TypeApplication("Option", listOf(
                FunkType.RecordType(mapOf(
                    "index" to FunkType.IntType,
                    "value" to elementType
                ))
            ))
        )

        // =========================================================================
        // MockChannel Implementation (same signatures, different native functions)
        // =========================================================================

        // __native_mock_channel_send: { ch: Chan 'a, value: 'a } -> {}
        context["__native_mock_channel_send"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to chanType,
                "value" to elementType
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_mock_channel_recv: { ch: Chan 'a } -> 'a
        context["__native_mock_channel_recv"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to chanType
            )),
            elementType
        )

        // __native_mock_channel_try_send: { ch: Chan 'a, value: 'a } -> Bool
        context["__native_mock_channel_try_send"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to chanType,
                "value" to elementType
            )),
            FunkType.BooleanType
        )

        // __native_mock_channel_try_recv: { ch: Chan 'a } -> Option 'a
        context["__native_mock_channel_try_recv"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to chanType
            )),
            FunkType.TypeApplication("Option", listOf(elementType))
        )

        // __native_mock_channel_close: { ch: Chan 'a } -> {}
        context["__native_mock_channel_close"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to chanType
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_mock_channel_is_closed: { ch: Chan 'a } -> Bool
        context["__native_mock_channel_is_closed"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ch" to chanType
            )),
            FunkType.BooleanType
        )

        // __native_mock_channel_recv_any: { channels: [Chan 'a] } -> { index: Int, value: 'a }
        context["__native_mock_channel_recv_any"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "channels" to FunkType.TypeApplication("List", listOf(chanType))
            )),
            FunkType.RecordType(mapOf(
                "index" to FunkType.IntType,
                "value" to elementType
            ))
        )

        // __native_mock_channel_recv_any_timeout: { channels: [Chan 'a], ms: Int } -> Option { index: Int, value: 'a }
        context["__native_mock_channel_recv_any_timeout"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "channels" to FunkType.TypeApplication("List", listOf(chanType)),
                "ms" to FunkType.IntType
            )),
            FunkType.TypeApplication("Option", listOf(
                FunkType.RecordType(mapOf(
                    "index" to FunkType.IntType,
                    "value" to elementType
                ))
            ))
        )
    }
}
