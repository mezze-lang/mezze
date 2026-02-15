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
        // Channel creation (pure, no effect)
        registerMake(context)
        registerMakeBuffered(context)

        // BlockingChannel implementation
        registerSend(context)
        registerRecv(context)
        registerTrySend(context)
        registerTryRecv(context)
        registerClose(context)
        registerIsClosed(context)
        registerRecvAny(context)
        registerRecvAnyTimeout(context)

        // MockChannel implementation
        registerMockChannelOps(context)
    }

    // =========================================================================
    // Channel Creation (pure functions)
    // =========================================================================

    /**
     * __native_channel_make: {} -> __NativeChannel
     * Creates an unbounded channel
     */
    private fun registerMake(context: MutableMap<String, FunkValue>) {
        context["__native_channel_make"] = NativeFunkFunction("__native_channel_make") { _ ->
            FunkChannel(LinkedBlockingQueue())
        }
    }

    /**
     * __native_channel_make_buffered: { capacity: Int } -> __NativeChannel
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

    // =========================================================================
    // BlockingChannel Implementation
    // =========================================================================

    /**
     * __native_channel_send: { ch: Chan 'a, value: 'a } -> {}
     * Sends a value to the channel, blocking if the channel is full
     */
    private fun registerSend(context: MutableMap<String, FunkValue>) {
        context["__native_channel_send"] = NativeFunkFunction("__native_channel_send") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_send expects FunkRecord")

            val channel = extractChannel(record.fields["ch"])
                ?: throw FunkException("__native_channel_send expects 'ch' field of type Chan")

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
     * __native_channel_recv: { ch: Chan 'a } -> 'a
     * Receives a value from the channel, blocking if the channel is empty
     */
    private fun registerRecv(context: MutableMap<String, FunkValue>) {
        context["__native_channel_recv"] = NativeFunkFunction("__native_channel_recv") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_recv expects FunkRecord")

            val channel = extractChannel(record.fields["ch"])
                ?: throw FunkException("__native_channel_recv expects 'ch' field of type Chan")

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
     * __native_channel_try_send: { ch: Chan 'a, value: 'a } -> Bool
     * Tries to send a value without blocking, returns true if successful
     */
    private fun registerTrySend(context: MutableMap<String, FunkValue>) {
        context["__native_channel_try_send"] = NativeFunkFunction("__native_channel_try_send") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_try_send expects FunkRecord")

            val channel = extractChannel(record.fields["ch"])
                ?: throw FunkException("__native_channel_try_send expects 'ch' field of type Chan")

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
     * __native_channel_try_recv: { ch: Chan 'a } -> Option 'a
     * Tries to receive a value without blocking, returns Some if available
     */
    private fun registerTryRecv(context: MutableMap<String, FunkValue>) {
        context["__native_channel_try_recv"] = NativeFunkFunction("__native_channel_try_recv") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_try_recv expects FunkRecord")

            val channel = extractChannel(record.fields["ch"])
                ?: throw FunkException("__native_channel_try_recv expects 'ch' field of type Chan")

            val value = channel.queue.poll()
            if (value != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to value)))
            } else {
                FunkVariant("None", null)
            }
        }
    }

    /**
     * __native_channel_close: { ch: Chan 'a } -> {}
     * Closes the channel, preventing further sends
     */
    private fun registerClose(context: MutableMap<String, FunkValue>) {
        context["__native_channel_close"] = NativeFunkFunction("__native_channel_close") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_close expects FunkRecord")

            val channel = extractChannel(record.fields["ch"])
                ?: throw FunkException("__native_channel_close expects 'ch' field of type Chan")

            channel.close()
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_channel_is_closed: { ch: Chan 'a } -> Bool
     * Check if a channel is closed
     */
    private fun registerIsClosed(context: MutableMap<String, FunkValue>) {
        context["__native_channel_is_closed"] = NativeFunkFunction("__native_channel_is_closed") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_is_closed expects FunkRecord")

            val channel = extractChannel(record.fields["ch"])
                ?: throw FunkException("__native_channel_is_closed expects 'ch' field of type Chan")

            FunkBool(channel.isClosed())
        }
    }

    /**
     * __native_channel_recv_any: { channels: [Chan 'a] } -> { index: Int, value: 'a }
     * Receives from the first channel that has data available.
     * Blocks until at least one channel has data.
     */
    private fun registerRecvAny(context: MutableMap<String, FunkValue>) {
        context["__native_channel_recv_any"] = NativeFunkFunction("__native_channel_recv_any") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_recv_any expects FunkRecord")

            val channelsList = record.fields["channels"] as? FunkArrayList
                ?: throw FunkException("__native_channel_recv_any expects 'channels' field of type [Chan]")

            val channels = channelsList.elements.map { extractChannel(it)!! }

            if (channels.isEmpty()) {
                throw FunkException("recv_any requires at least one channel")
            }

            // Poll all channels in round-robin until one has data
            while (true) {
                for ((index, channel) in channels.withIndex()) {
                    val value = channel.queue.poll()
                    if (value != null) {
                        return@NativeFunkFunction FunkRecord(mapOf(
                            "index" to FunkInt(index.toLong()),
                            "value" to value
                        ))
                    }
                }

                // Check if all channels are closed and empty
                val allClosedAndEmpty = channels.all { it.isClosed() && it.queue.isEmpty() }
                if (allClosedAndEmpty) {
                    throw FunkException("All channels are closed and empty")
                }

                // Small sleep to avoid busy-waiting
                Thread.sleep(1)
            }
            @Suppress("UNREACHABLE_CODE")
            FunkNull
        }
    }

    /**
     * __native_channel_recv_any_timeout: { channels: [Chan 'a], ms: Int } -> Option { index: Int, value: 'a }
     * Receives from the first channel that has data available, with timeout.
     * Returns None if timeout expires before any channel has data.
     */
    private fun registerRecvAnyTimeout(context: MutableMap<String, FunkValue>) {
        context["__native_channel_recv_any_timeout"] = NativeFunkFunction("__native_channel_recv_any_timeout") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_channel_recv_any_timeout expects FunkRecord")

            val channelsList = record.fields["channels"] as? FunkArrayList
                ?: throw FunkException("__native_channel_recv_any_timeout expects 'channels' field of type [Chan]")

            val timeoutMs = (record.fields["ms"] as? FunkInt)?.value?.toLong()
                ?: throw FunkException("__native_channel_recv_any_timeout expects 'ms' field of type Int")

            val channels = channelsList.elements.map { extractChannel(it)!! }

            if (channels.isEmpty()) {
                throw FunkException("recv_any_timeout requires at least one channel")
            }

            val deadline = System.currentTimeMillis() + timeoutMs

            // Poll all channels in round-robin until one has data or timeout
            while (System.currentTimeMillis() < deadline) {
                for ((index, channel) in channels.withIndex()) {
                    val value = channel.queue.poll()
                    if (value != null) {
                        return@NativeFunkFunction FunkVariant("Some", FunkRecord(mapOf(
                            "val" to FunkRecord(mapOf(
                                "index" to FunkInt(index.toLong()),
                                "value" to value
                            ))
                        )))
                    }
                }

                // Check if all channels are closed and empty
                val allClosedAndEmpty = channels.all { it.isClosed() && it.queue.isEmpty() }
                if (allClosedAndEmpty) {
                    return@NativeFunkFunction FunkVariant("None", null)
                }

                // Small sleep to avoid busy-waiting
                Thread.sleep(1)
            }

            // Timeout expired
            FunkVariant("None", null)
        }
    }

    // =========================================================================
    // MockChannel Implementation (for testing)
    // =========================================================================

    /**
     * Register all mock channel operations.
     * MockChannel is deterministic and non-blocking (throws if would block).
     */
    private fun registerMockChannelOps(context: MutableMap<String, FunkValue>) {
        // Mock send - never blocks, just adds to queue
        context["__native_mock_channel_send"] = NativeFunkFunction("__native_mock_channel_send") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_mock_channel_send expects FunkRecord")

            val channel = extractChannel(record.fields["ch"])
                ?: throw FunkException("__native_mock_channel_send expects 'ch' field of type Chan")

            val value = record.fields["value"]
                ?: throw FunkException("__native_mock_channel_send expects 'value' field")

            if (channel.isClosed()) {
                throw FunkException("MockChannel: Cannot send to closed channel")
            }

            channel.queue.add(value)
            FunkRecord(emptyMap())
        }

        // Mock recv - throws if empty (no blocking)
        context["__native_mock_channel_recv"] = NativeFunkFunction("__native_mock_channel_recv") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_mock_channel_recv expects FunkRecord")

            val channel = extractChannel(record.fields["ch"])
                ?: throw FunkException("__native_mock_channel_recv expects 'ch' field of type Chan")

            val value = channel.queue.poll()
                ?: throw FunkException("MockChannel: Channel is empty (would block)")

            value
        }

        // Mock try_send - same as regular (non-blocking anyway)
        context["__native_mock_channel_try_send"] = NativeFunkFunction("__native_mock_channel_try_send") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_mock_channel_try_send expects FunkRecord")

            val channel = extractChannel(record.fields["ch"])
                ?: throw FunkException("__native_mock_channel_try_send expects 'ch' field of type Chan")

            val value = record.fields["value"]
                ?: throw FunkException("__native_mock_channel_try_send expects 'value' field")

            if (channel.isClosed()) {
                FunkBool(false)
            } else {
                channel.queue.add(value)
                FunkBool(true)
            }
        }

        // Mock try_recv - same as regular
        context["__native_mock_channel_try_recv"] = NativeFunkFunction("__native_mock_channel_try_recv") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_mock_channel_try_recv expects FunkRecord")

            val channel = extractChannel(record.fields["ch"])
                ?: throw FunkException("__native_mock_channel_try_recv expects 'ch' field of type Chan")

            val value = channel.queue.poll()
            if (value != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to value)))
            } else {
                FunkVariant("None", null)
            }
        }

        // Mock close
        context["__native_mock_channel_close"] = NativeFunkFunction("__native_mock_channel_close") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_mock_channel_close expects FunkRecord")

            val channel = extractChannel(record.fields["ch"])
                ?: throw FunkException("__native_mock_channel_close expects 'ch' field of type Chan")

            channel.close()
            FunkRecord(emptyMap())
        }

        // Mock is_closed
        context["__native_mock_channel_is_closed"] = NativeFunkFunction("__native_mock_channel_is_closed") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_mock_channel_is_closed expects FunkRecord")

            val channel = extractChannel(record.fields["ch"])
                ?: throw FunkException("__native_mock_channel_is_closed expects 'ch' field of type Chan")

            FunkBool(channel.isClosed())
        }

        // Mock recv_any - throws if all empty
        context["__native_mock_channel_recv_any"] = NativeFunkFunction("__native_mock_channel_recv_any") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_mock_channel_recv_any expects FunkRecord")

            val channelsList = record.fields["channels"] as? FunkArrayList
                ?: throw FunkException("__native_mock_channel_recv_any expects 'channels' field of type [Chan]")

            val channels = channelsList.elements.map { extractChannel(it)!! }

            for ((index, channel) in channels.withIndex()) {
                val value = channel.queue.poll()
                if (value != null) {
                    return@NativeFunkFunction FunkRecord(mapOf(
                        "index" to FunkInt(index.toLong()),
                        "value" to value
                    ))
                }
            }

            throw FunkException("MockChannel: All channels are empty (would block)")
        }

        // Mock recv_any_timeout - same as recv_any for mock (no actual timeout)
        context["__native_mock_channel_recv_any_timeout"] = NativeFunkFunction("__native_mock_channel_recv_any_timeout") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_mock_channel_recv_any_timeout expects FunkRecord")

            val channelsList = record.fields["channels"] as? FunkArrayList
                ?: throw FunkException("__native_mock_channel_recv_any_timeout expects 'channels' field of type [Chan]")

            val channels = channelsList.elements.map { extractChannel(it)!! }

            for ((index, channel) in channels.withIndex()) {
                val value = channel.queue.poll()
                if (value != null) {
                    return@NativeFunkFunction FunkVariant("Some", FunkRecord(mapOf(
                        "val" to FunkRecord(mapOf(
                            "index" to FunkInt(index.toLong()),
                            "value" to value
                        ))
                    )))
                }
            }

            // Return None (simulates timeout when nothing available)
            FunkVariant("None", null)
        }
    }

    // =========================================================================
    // Helper Functions
    // =========================================================================

    /**
     * Extract a FunkChannel from a value, handling both direct FunkChannel
     * and FunkVariant wrapper (from newtype Chan).
     */
    private fun extractChannel(value: FunkValue?): FunkChannel? {
        return when (value) {
            is FunkChannel -> value
            is FunkVariant -> {
                // Chan newtype wraps __NativeChannel as FunkVariant
                when (val inner = value.value) {
                    is FunkChannel -> inner
                    is FunkRecord -> inner.fields.values.firstOrNull() as? FunkChannel
                    else -> null
                }
            }
            else -> null
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
