package com.funk.language.interpreter.stdlib.concurrent

import com.funk.language.interpreter.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native implementations for channel operations.
 *
 * Channels provide typed communication between virtual threads.
 * Uses Java's LinkedBlockingQueue for thread-safe blocking operations.
 */
object ChannelImplementations {

    /**
     * Register all channel-related native functions.
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerMake(context)
        registerMakeBuffered(context)
        registerSend(context)
        registerRecv(context)
        registerTrySend(context)
        registerTryRecv(context)
        registerClose(context)
    }

    /**
     * __native_channel_make: {} -> Channel 'a
     * Creates an unbounded channel
     */
    private fun registerMake(context: MutableMap<String, FunkValue>) {
        context["__native_channel_make"] = NativeFunkFunction("__native_channel_make") { _ ->
            FunkChannel(LinkedBlockingQueue())
        }
    }

    /**
     * __native_channel_make_buffered: { capacity: Int } -> Channel 'a
     * Creates a bounded channel with specified capacity
     */
    private fun registerMakeBuffered(context: MutableMap<String, FunkValue>) {
        context["__native_channel_make_buffered"] = NativeFunkFunction("__native_channel_make_buffered") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_make_buffered expects FunkRecord")

            val capacity = (record.fields["capacity"] as? FunkInt)?.value?.toInt()
                ?: throw FunkException("__native_channel_make_buffered expects 'capacity' field of type Int")

            if (capacity <= 0) {
                throw FunkException("Channel capacity must be positive, got: $capacity")
            }

            FunkChannel(LinkedBlockingQueue(capacity))
        }
    }

    /**
     * __native_channel_send: { ch: Channel 'a, value: 'a } -> {}
     * Sends a value to the channel, blocking if the channel is full
     */
    private fun registerSend(context: MutableMap<String, FunkValue>) {
        context["__native_channel_send"] = NativeFunkFunction("__native_channel_send") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_send expects FunkRecord")

            val channel = record.fields["ch"] as? FunkChannel
                ?: throw FunkException("__native_channel_send expects 'ch' field of type Channel")

            val value = record.fields["value"]
                ?: throw FunkException("__native_channel_send expects 'value' field")

            if (channel.isClosed()) {
                throw FunkException("Cannot send to closed channel")
            }

            channel.queue.put(value)
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_channel_recv: { ch: Channel 'a } -> 'a
     * Receives a value from the channel, blocking if the channel is empty
     */
    private fun registerRecv(context: MutableMap<String, FunkValue>) {
        context["__native_channel_recv"] = NativeFunkFunction("__native_channel_recv") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_recv expects FunkRecord")

            val channel = record.fields["ch"] as? FunkChannel
                ?: throw FunkException("__native_channel_recv expects 'ch' field of type Channel")

            // Use poll with timeout to allow checking for closed channel
            while (true) {
                val value = channel.queue.poll(100, TimeUnit.MILLISECONDS)
                if (value != null) {
                    return@NativeFunkFunction value
                }
                if (channel.isClosed() && channel.queue.isEmpty()) {
                    throw FunkException("Channel is closed and empty")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            FunkNull // Never reached
        }
    }

    /**
     * __native_channel_try_send: { ch: Channel 'a, value: 'a } -> Bool
     * Tries to send a value without blocking, returns true if successful
     */
    private fun registerTrySend(context: MutableMap<String, FunkValue>) {
        context["__native_channel_try_send"] = NativeFunkFunction("__native_channel_try_send") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_try_send expects FunkRecord")

            val channel = record.fields["ch"] as? FunkChannel
                ?: throw FunkException("__native_channel_try_send expects 'ch' field of type Channel")

            val value = record.fields["value"]
                ?: throw FunkException("__native_channel_try_send expects 'value' field")

            if (channel.isClosed()) {
                FunkBool(false)
            } else {
                FunkBool(channel.queue.offer(value))
            }
        }
    }

    /**
     * __native_channel_try_recv: { ch: Channel 'a } -> Option 'a
     * Tries to receive a value without blocking, returns Some if available
     */
    private fun registerTryRecv(context: MutableMap<String, FunkValue>) {
        context["__native_channel_try_recv"] = NativeFunkFunction("__native_channel_try_recv") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_try_recv expects FunkRecord")

            val channel = record.fields["ch"] as? FunkChannel
                ?: throw FunkException("__native_channel_try_recv expects 'ch' field of type Channel")

            val value = channel.queue.poll()
            if (value != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to value)))
            } else {
                FunkVariant("None", null)
            }
        }
    }

    /**
     * __native_channel_close: { ch: Channel 'a } -> {}
     * Closes the channel, preventing further sends
     */
    private fun registerClose(context: MutableMap<String, FunkValue>) {
        context["__native_channel_close"] = NativeFunkFunction("__native_channel_close") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_close expects FunkRecord")

            val channel = record.fields["ch"] as? FunkChannel
                ?: throw FunkException("__native_channel_close expects 'ch' field of type Channel")

            channel.close()
            FunkRecord(emptyMap())
        }
    }
}

/**
 * Channel value - wraps a LinkedBlockingQueue for typed channel communication
 */
data class FunkChannel(
    val queue: LinkedBlockingQueue<FunkValue>,
    private val closed: AtomicBoolean = AtomicBoolean(false)
) : FunkValue() {
    override fun getDisplayString(): String = "<channel:${if (isClosed()) "closed" else "open"}:${queue.size}>"

    fun isClosed(): Boolean = closed.get()

    fun close() {
        closed.set(true)
    }
}
