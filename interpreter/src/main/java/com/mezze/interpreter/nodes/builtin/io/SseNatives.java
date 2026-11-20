package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.MezzeGuestException;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Server-Sent Events, the sink half: `sse { f }` runs `f` and hands it a `send`
 * that writes through here.
 *
 * <p>This class knows nothing about Helidon on purpose. The server dispatcher
 * {@link #enter}s a {@link Wire} for the connection being handled; with no wire
 * (the mock router, a unit test, the BROWSER build) the sink buffers and `sse`
 * turns the buffer into the response body. That is what lets
 * {@code __native_http_sse_*} be allowed in the browser, where the live path
 * can never arise, while the same handler code runs unchanged.
 */
public final class SseNatives {

    /** What the server offers a live stream: start the response (status, headers) and give the body stream. */
    public interface Wire {
        OutputStream start() throws IOException;
    }

    private static final ThreadLocal<Wire> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> STREAMED = new ThreadLocal<>();

    /** Called by the server dispatcher around the guest handler, on the connection thread. */
    public static void enter(Wire wire) {
        CURRENT.set(wire);
        STREAMED.remove();
    }

    /** Undo {@link #enter}; answers whether the handler streamed its own response, in which case the dispatcher must not write one. */
    public static boolean leave() {
        boolean streamed = STREAMED.get() == Boolean.TRUE;
        CURRENT.remove();
        STREAMED.remove();
        return streamed;
    }

    /** One response's event sink: live (writes to the wire) or buffering (no wire). */
    public static final class SseSink {
        private final OutputStream out;      // null when buffering
        private final StringBuilder buffer;  // null when live
        private boolean closed;

        SseSink(OutputStream out) {
            this.out = out;
            this.buffer = out == null ? new StringBuilder() : null;
        }
    }

    /** Begin: live when a wire was entered (status and headers go out now), buffering otherwise. */
    @TruffleBoundary
    public static Object begin(MezzeRecord args) {
        Wire wire = CURRENT.get();
        if (wire == null) return new SseSink(null);
        try {
            OutputStream out = wire.start();
            STREAMED.set(Boolean.TRUE);
            return new SseSink(out);
        } catch (IOException e) {
            throw new MezzeGuestException("SSE: could not start the event stream (" + e.getMessage() + ")");
        }
    }

    /**
     * Write one already-formatted event. Live, it is flushed at once so the
     * client sees it before the handler returns; a peer that has gone away
     * raises, which ends the handler — nothing it does afterwards could reach
     * anyone.
     */
    @TruffleBoundary
    public static Object send(MezzeRecord args) {
        SseSink sink = sinkOf(args);
        Object text = args.getField("text");
        if (!(text instanceof String s)) {
            throw new NativeTypeMismatchException("__native_http_sse_send expects 'text' to be Str");
        }
        if (sink.closed) throw new MezzeGuestException("SSE: the event stream is already closed");
        if (sink.out == null) {
            sink.buffer.append(s);
            return MezzeRecord.empty();
        }
        try {
            sink.out.write(s.getBytes(StandardCharsets.UTF_8));
            sink.out.flush();
        } catch (IOException e) {
            sink.closed = true;
            throw new MezzeGuestException("SSE: the client disconnected (" + e.getMessage() + ")");
        }
        return MezzeRecord.empty();
    }

    /** End the stream. Live: close the body so the client sees a complete response, answer "". Buffering: answer the collected text. */
    @TruffleBoundary
    public static Object end(MezzeRecord args) {
        SseSink sink = sinkOf(args);
        sink.closed = true;
        if (sink.out == null) return sink.buffer.toString();
        try {
            sink.out.close();
        } catch (IOException e) {
            // The peer went away as we finished; the status and headers are long gone.
        }
        return "";
    }

    private static SseSink sinkOf(MezzeRecord args) {
        Object o = args.getField("sink");
        if (o instanceof SseSink s) return s;
        throw new NativeTypeMismatchException("SSE natives expect the sink from __native_http_sse_begin");
    }

    private SseNatives() {}
}
