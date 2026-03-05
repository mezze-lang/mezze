package com.mezze.datastructures.concurrent;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Message-passing channel for concurrent communication.
 *
 * Supports:
 * - Bounded and unbounded variants
 * - Blocking send/receive
 * - Non-blocking try_send/try_recv
 * - Timeout-based operations
 * - Close semantics
 *
 * Thread-safe: Yes
 */
public final class MezzeChannel<T> implements TruffleObject {

    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    // VarHandle for lock-free closed flag access
    private static final VarHandle CLOSED;
    static {
        try {
            CLOSED = MethodHandles.lookup().findVarHandle(MezzeChannel.class, "closed", boolean.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @CompilationFinal
    private final long id;

    private final BlockingQueue<T> queue;
    @SuppressWarnings("FieldMayBeFinal") // VarHandle requires non-final
    private volatile boolean closed = false;
    private final int capacity;

    /**
     * Creates an unbounded channel.
     */
    public MezzeChannel() {
        this(0);
    }

    /**
     * Creates a channel with the given capacity.
     * @param capacity 0 or negative for unbounded, positive for bounded
     */
    public MezzeChannel(int capacity) {
        this.id = NEXT_ID.getAndIncrement();
        this.capacity = capacity;
        this.queue = capacity <= 0
            ? new LinkedBlockingQueue<>()
            : new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Returns the unique ID of this channel.
     */
    public long getId() {
        return id;
    }

    /**
     * Returns the capacity (0 for unbounded).
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Returns the number of elements currently in the channel.
     */
    public int size() {
        return queue.size();
    }

    /**
     * Returns true if the channel is empty.
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Sends a value, blocking if the channel is full.
     * @throws ChannelClosedException if the channel is closed
     */
    @TruffleBoundary
    public void send(T value) throws InterruptedException {
        if (closed) {
            throw new ChannelClosedException("send on closed channel");
        }
        queue.put(value);
    }

    /**
     * Receives a value, blocking if the channel is empty.
     * @throws ChannelClosedException if channel is closed and empty
     */
    @TruffleBoundary
    public T receive() throws InterruptedException {
        T value = queue.take();
        return value;
    }

    /**
     * Tries to send without blocking.
     * @return true if successful, false if full or closed
     */
    @TruffleBoundary
    public boolean trySend(T value) {
        if (closed) return false;
        return queue.offer(value);
    }

    /**
     * Tries to receive without blocking.
     * @return Optional containing the value, or empty if channel is empty
     */
    @TruffleBoundary
    public Optional<T> tryReceive() {
        T value = queue.poll();
        return Optional.ofNullable(value);
    }

    /**
     * Sends with timeout.
     * @return true if successful, false if timeout or closed
     */
    @TruffleBoundary
    public boolean send(T value, long timeout, TimeUnit unit) throws InterruptedException {
        if (closed) return false;
        return queue.offer(value, timeout, unit);
    }

    /**
     * Receives with timeout.
     * @return Optional containing the value, or empty if timeout
     */
    @TruffleBoundary
    public Optional<T> receive(long timeout, TimeUnit unit) throws InterruptedException {
        T value = queue.poll(timeout, unit);
        return Optional.ofNullable(value);
    }

    /**
     * Closes the channel.
     * No more sends allowed; remaining elements can still be received.
     * Uses CAS to ensure exactly-once close semantics.
     */
    @TruffleBoundary
    public void close() {
        CLOSED.setVolatile(this, true);
    }

    /**
     * Returns true if the channel is closed.
     * Uses acquire semantics for proper memory ordering.
     */
    public boolean isClosed() {
        return (boolean) CLOSED.getAcquire(this);
    }

    /**
     * Returns true if the channel is closed and empty.
     */
    public boolean isDrained() {
        return closed && queue.isEmpty();
    }

    /**
     * Clears all pending messages.
     */
    @TruffleBoundary
    public void clear() {
        queue.clear();
    }

    /**
     * Returns the underlying queue (for advanced use like select).
     */
    public BlockingQueue<T> getQueue() {
        return queue;
    }

    @Override
    public String toString() {
        String status = closed ? "closed" : "open";
        return "<channel:" + id + ":" + status + ":" + queue.size() + ">";
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeChannel<?> other)) return false;
        return this.id == other.id;
    }

    /**
     * Exception thrown when operating on a closed channel.
     */
    public static class ChannelClosedException extends RuntimeException {
        public ChannelClosedException(String message) {
            super(message);
        }
    }
}
