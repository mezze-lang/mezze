package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * WebSockets, the half that knows nothing about Helidon: the `WebSocket`
 * effect's ops (send / receive / close), the socket itself, and the in-memory
 * `socket_pair`.
 *
 * <p>One shape on both sides and in memory. A live end has a {@link Wire} the
 * Helidon half ({@link WsNatives}) installs over its session; an in-memory end
 * has a {@link Socket#peer} instead, and what one end sends lands in the
 * other's inbox as the very guest value. Frames arrive through
 * {@link Socket#deliverText}/{@link Socket#deliverBinary} on Helidon's threads
 * as plain Java; the guest value is minted by {@code receive}, on the Truffle
 * thread that drains the inbox.
 *
 * <p>Helidon-free on purpose: the browser build drops any class that links
 * against Helidon, and `socket_pair` has to work there (it is how a playground
 * sample drives a socket handler).
 */
public final class SocketNatives {

    /** Inbox sentinel: the peer closed (or the connection failed). Re-queued so every later receive sees it too. */
    static final Object CLOSED = new Object();

    static final MezzeVariant.TagRegistry REG = MezzeVariant.TagRegistry.getGlobal();
    static int tag(String ctor) { return REG.getOrCreateTag(ctor); }

    /** What a live end can do. Implemented by the Helidon half over a session. */
    public interface Wire {
        void sendText(String text);
        void sendBinary(byte[] bytes);
        void close();
    }

    /** One end of a WebSocket. */
    public static final class Socket {
        volatile Wire wire;              // null for an in-memory end, and until a client connect opens
        volatile Socket peer;            // the other in-memory end, or null
        final LinkedBlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
        final CountDownLatch opened = new CountDownLatch(1);
        volatile boolean closed;
        volatile Map<String, String> upgradeHeaders = Map.of();

        /** A text fragment off the wire; the message is queued once `last`. */
        public void deliverText(String fragment, boolean last) {
            text.append(fragment);
            if (last) {
                inbox.offer(new Incoming(text.toString(), null));
                text.setLength(0);
            }
        }

        /** A binary fragment off the wire; the message is queued once `last`. */
        public void deliverBinary(byte[] fragment, boolean last) {
            binary.write(fragment, 0, fragment.length);
            if (last) {
                inbox.offer(new Incoming(null, binary.toByteArray()));
                binary.reset();
            }
        }

        /** The connection is open on this wire. */
        public void opened(Wire w) {
            wire = w;
            opened.countDown();
        }

        /** The peer closed, or the connection failed. */
        public void ended() {
            closed = true;
            inbox.offer(CLOSED);
            opened.countDown();
        }
    }

    /** A complete message off the wire, as plain Java; turned into a guest `Message` by `receive`. */
    record Incoming(String text, byte[] bytes) {}

    /** `__native_ws_send { socket, msg }` → `Result {} SocketError`. */
    @TruffleBoundary
    public static Object send(MezzeRecord args) {
        Socket s = socketOf(args);
        Object msg = args.getField("msg");
        if (!(msg instanceof MezzeVariant v)) throw new NativeTypeMismatchException("__native_ws_send expects 'msg' to be a Message");
        if (s.closed) return closedErr();
        Socket peer = s.peer;
        if (peer != null) {
            peer.inbox.offer(v);
            return MezzeVariant.ok(MezzeRecord.empty());
        }
        Wire wire = s.wire;
        if (wire == null) return closedErr();
        try {
            if (v.getTag() == tag("Text")) {
                wire.sendText(String.valueOf(v.getPayloadFieldByName("text")));
            } else if (v.getTag() == tag("Binary")) {
                Object bytes = v.getPayloadFieldByName("bytes");
                if (!(bytes instanceof BytesNatives.MezzeBytes mb)) throw new NativeTypeMismatchException("Binary message without Bytes");
                wire.sendBinary(mb.getData());
            } else {
                throw new NativeTypeMismatchException("A Message is Text or Binary; got tag " + v.getTag());
            }
            return MezzeVariant.ok(MezzeRecord.empty());
        } catch (RuntimeException e) {
            s.closed = true;
            return closedErr();
        }
    }

    /** `__native_ws_receive { socket }` → `Option Message`; blocks; `None` once the peer has closed. */
    @TruffleBoundary
    public static Object receive(MezzeRecord args) {
        Socket s = socketOf(args);
        Object m;
        try {
            m = s.inbox.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MezzeVariant.none();
        }
        if (m == CLOSED) {
            s.inbox.offer(CLOSED);
            return MezzeVariant.none();
        }
        if (m instanceof Incoming in) {
            return MezzeVariant.some(in.text != null ? textMessage(in.text) : binaryMessage(in.bytes));
        }
        return MezzeVariant.some(m);   // an in-memory peer's Message, passed through
    }

    /** `__native_ws_close { socket }`. Closing one in-memory end ends the other's receives too. */
    @TruffleBoundary
    public static Object close(MezzeRecord args) {
        Socket s = socketOf(args);
        if (!s.closed) {
            s.closed = true;
            Socket peer = s.peer;
            if (peer != null) {
                peer.closed = true;
                peer.inbox.offer(CLOSED);
            } else if (s.wire != null) {
                try {
                    s.wire.close();
                } catch (RuntimeException ignored) {
                    // the peer may already be gone; the local end is closed either way
                }
            }
            s.inbox.offer(CLOSED);
        }
        return MezzeRecord.empty();
    }

    /** `__native_mock_ws_pair {}` → `{ a: Socket, b: Socket }`, two connected in-memory ends. */
    @TruffleBoundary
    public static Object pair(MezzeRecord args) {
        Socket a = new Socket();
        Socket b = new Socket();
        a.peer = b;
        b.peer = a;
        return MezzeRecord.fromFields(new String[]{"a", "b"}, new Object[]{a, b});
    }

    static Socket socketOf(MezzeRecord args) {
        Object o = args.getField("socket");
        if (o instanceof Socket s) return s;
        throw new NativeTypeMismatchException("WebSocket natives expect a Socket from connect, a server route, or socket_pair");
    }

    static MezzeVariant textMessage(String text) {
        return MezzeVariant.singlePayload(REG, tag("Text"),
            MezzeRecord.fromFields(new String[]{"text"}, new Object[]{text}));
    }

    static MezzeVariant binaryMessage(byte[] bytes) {
        return MezzeVariant.singlePayload(REG, tag("Binary"),
            MezzeRecord.fromFields(new String[]{"bytes"}, new Object[]{new BytesNatives.MezzeBytes(bytes)}));
    }

    static MezzeVariant closedErr() {
        return MezzeVariant.err(MezzeVariant.noPayload(REG, tag("SocketClosed")));
    }

    static MezzeVariant connectFailed(String url, String reason) {
        MezzeRecord payload = MezzeRecord.fromFields(new String[]{"url", "reason"}, new Object[]{url, reason});
        return MezzeVariant.err(MezzeVariant.singlePayload(REG, tag("SocketConnectFailed"), payload));
    }

    private SocketNatives() {}
}
