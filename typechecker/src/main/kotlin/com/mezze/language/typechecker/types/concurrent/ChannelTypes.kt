package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

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
        val chanType = MezzeType.VarType(TypeVar.freshWithName("Chan"))
        val elementType = MezzeType.VarType(TypeVar.freshWithName("a"))

        natives(context) {
            // __native_channel_make: {} -> Chan 'a <Async>
            fn("__native_channel_make") {
                returns(chanType)
                effects("Async")
            }

            // __native_channel_make_buffered: { capacity: Int } -> Chan 'a <Async>
            fn("__native_channel_make_buffered") {
                param("capacity" to int)
                returns(chanType)
                effects("Async")
            }

            // __native_channel_try_send: { ch: Chan 'a, value: 'a } -> Bool <Async>
            fn("__native_channel_try_send") {
                param("ch" to chanType, "value" to elementType)
                returns(bool)
                effects("Async")
            }

            // __native_channel_try_recv: { ch: Chan 'a } -> Option 'a <Async>
            fn("__native_channel_try_recv") {
                param("ch" to chanType)
                returns(WellKnownTypeApp.of("Option", listOf(elementType)))
                effects("Async")
            }

            // __native_channel_close: { ch: Chan 'a } -> {} <Async>
            fn("__native_channel_close") {
                param("ch" to chanType)
                returns(unit)
                effects("Async")
            }

            // __native_channel_is_closed: { ch: Chan 'a } -> Bool <Async>
            fn("__native_channel_is_closed") {
                param("ch" to chanType)
                returns(bool)
                effects("Async")
            }

            // __native_channel_recv_any: { channels: [Chan 'a] } -> { index: Int, value: 'a } <Async>
            fn("__native_channel_recv_any") {
                param("channels" to seq(chanType))
                returns(rec("index" to int, "value" to elementType))
                effects("Async")
            }

            // __native_channel_recv_any_timeout: { channels: [Chan 'a], ms: Int } -> Option { index: Int, value: 'a } <Async>
            fn("__native_channel_recv_any_timeout") {
                param("channels" to seq(chanType), "ms" to int)
                returns(WellKnownTypeApp.of("Option", listOf(
                    rec("index" to int, "value" to elementType)
                )))
                effects("Async")
            }
        }
    }
}
