package com.mezze.interpreter.standalone.wire

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Child-side buffer that receives STDIN_DATA frames from the parent and
 * exposes them as an [InputStream] for `StdinNatives` to read.
 *
 * Operation:
 *   - The wire dispatch loop pushes bytes here via [push].
 *   - [pushEof] marks end-of-stream.
 *   - Mezze code that reads via `Stdin.read_line` etc. eventually calls
 *     [read] (through [asInputStream]). [read] blocks if no bytes are
 *     available and EOF has not been signalled, returning -1 on EOF
 *     after all buffered bytes are drained.
 *
 * Thread-safety: all state is guarded by a single lock. Readers and the
 * dispatch loop coordinate through a Condition (`hasData`).
 *
 * Resetability: [reset] drops the buffer and clears EOF. Called by
 * RuntimeServer between runs so a long-lived `mez dev` session does not
 * carry stdin state across LOADs.
 */
class WireStdinBuffer {
    private val lock = ReentrantLock()
    private val hasData: Condition = lock.newCondition()

    // ArrayDeque<ByteArray> with a head-offset that walks within the head
    // chunk. Avoids copying on every read; only allocates when chunks
    // arrive from the wire.
    private val chunks: ArrayDeque<ByteArray> = ArrayDeque()
    private var headOffset: Int = 0
    private var eof: Boolean = false

    /** Push bytes received from the wire. No-op on empty array. */
    fun push(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        lock.withLock {
            chunks.addLast(bytes)
            hasData.signalAll()
        }
    }

    /** Mark end of stream. Subsequent reads return EOF once drained. */
    fun pushEof() {
        lock.withLock {
            eof = true
            hasData.signalAll()
        }
    }

    /** Drop all state and clear EOF. Called between runs. */
    fun reset() {
        lock.withLock {
            chunks.clear()
            headOffset = 0
            eof = false
            // No need to signal — readers were just told there's nothing
            // for them. A new request will arrive when the next read fires.
        }
    }

    /**
     * Read up to `len` bytes starting at `off` into `dst`. Blocks until
     * at least one byte is available or EOF is signalled. Returns
     * -1 on EOF, 0 if `len <= 0`, otherwise the number of bytes read.
     *
     * Behaviour mirrors `InputStream.read(byte[], int, int)`.
     */
    @Throws(IOException::class)
    fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        lock.withLock {
            while (chunks.isEmpty()) {
                if (eof) return -1
                try {
                    hasData.await()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("interrupted while waiting for stdin", e)
                }
            }
            return drainLocked(dst, off, len)
        }
    }

    /** Caller must hold [lock]. */
    private fun drainLocked(dst: ByteArray, off: Int, len: Int): Int {
        var dstPos = off
        var remaining = len
        while (remaining > 0 && chunks.isNotEmpty()) {
            val head = chunks.first()
            val available = head.size - headOffset
            val take = minOf(available, remaining)
            System.arraycopy(head, headOffset, dst, dstPos, take)
            headOffset += take
            dstPos += take
            remaining -= take
            if (headOffset >= head.size) {
                chunks.removeFirst()
                headOffset = 0
            }
        }
        return dstPos - off
    }

    /**
     * View this buffer as an InputStream. The returned stream's `read`
     * methods delegate to [read]. `available()` reports the count of
     * currently-buffered bytes (a lower bound; more may arrive).
     */
    fun asInputStream(): InputStream = object : InputStream() {
        private val singleByte = ByteArray(1)

        override fun read(): Int {
            val n = this@WireStdinBuffer.read(singleByte, 0, 1)
            return if (n <= 0) -1 else singleByte[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            this@WireStdinBuffer.read(b, off, len)

        override fun available(): Int = lock.withLock {
            var n = 0
            val iter = chunks.iterator()
            if (iter.hasNext()) n += iter.next().size - headOffset
            while (iter.hasNext()) n += iter.next().size
            n
        }
    }
}
