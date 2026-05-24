package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Type definitions for channel native operations.
 *
 * `Chan 'a` is a plain newtype (no Channel effect). Park-aware
 * `send`/`recv` live in stdlib as free functions built on
 * `__native_channel_try_*` + `Async.park`. The blocking native
 * primitives (`__native_channel_send`/`recv`) have been removed
 * along with the `Channel` effect and its impls (BlockingChannel,
 * MockChannel) — tracked under #115.
 *
 * Every native carries `<Async>` because creating a channel and
 * every operation on it observe/mutate hidden state — see #170.
 *
 * Internally backed by `LinkedBlockingQueue`.
 */
object ChannelTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        val chanType = MezzeType.VarType(TypeVar("Chan"))
        val elementType = MezzeType.VarType(TypeVar("a"))
        val asyncEffect = MezzeType.EffectRow(effects = setOf("Async"))

        // __native_channel_make: {} -> Chan 'a <Async>
        context["__native_channel_make"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            chanType,
            effects = asyncEffect
        )

        // __native_channel_make_buffered: { capacity: Int } -> Chan 'a <Async>
        context["__native_channel_make_buffered"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "capacity" to MezzeType.IntType
            )),
            chanType,
            effects = asyncEffect
        )

        // __native_channel_try_send: { ch: Chan 'a, value: 'a } -> Bool <Async>
        context["__native_channel_try_send"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType,
                "value" to elementType
            )),
            MezzeType.BoolType,
            effects = asyncEffect
        )

        // __native_channel_try_recv: { ch: Chan 'a } -> Option 'a <Async>
        context["__native_channel_try_recv"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType
            )),
            com.mezze.language.typechecker.util.WellKnownTypeApp.of("Option", listOf(elementType)),
            effects = asyncEffect
        )

        // __native_channel_close: { ch: Chan 'a } -> {} <Async>
        context["__native_channel_close"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType
            )),
            MezzeType.RecordType(emptyMap()),
            effects = asyncEffect
        )

        // __native_channel_is_closed: { ch: Chan 'a } -> Bool <Async>
        context["__native_channel_is_closed"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ch" to chanType
            )),
            MezzeType.BoolType,
            effects = asyncEffect
        )

        // __native_channel_recv_any: { channels: [Chan 'a] } -> { index: Int, value: 'a } <Async>
        context["__native_channel_recv_any"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "channels" to MezzeType.SeqType(chanType)
            )),
            MezzeType.RecordType(mapOf(
                "index" to MezzeType.IntType,
                "value" to elementType
            )),
            effects = asyncEffect
        )

        // __native_channel_recv_any_timeout: { channels: [Chan 'a], ms: Int } -> Option { index: Int, value: 'a } <Async>
        context["__native_channel_recv_any_timeout"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "channels" to MezzeType.SeqType(chanType),
                "ms" to MezzeType.IntType
            )),
            com.mezze.language.typechecker.util.WellKnownTypeApp.of("Option", listOf(
                MezzeType.RecordType(mapOf(
                    "index" to MezzeType.IntType,
                    "value" to elementType
                ))
            )),
            effects = asyncEffect
        )
    }
}
