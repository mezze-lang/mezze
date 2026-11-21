package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.nodes.builtin.io.SocketNatives.Socket;
import com.mezze.interpreter.nodes.continuation.HandlerChainEntry;
import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry;
import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import io.helidon.common.buffers.BufferData;
import io.helidon.http.Headers;
import io.helidon.http.HttpPrologue;
import io.helidon.webclient.websocket.WsClient;
import io.helidon.webserver.websocket.WsRouting;
import io.helidon.websocket.WsCloseCodes;
import io.helidon.websocket.WsListener;
import io.helidon.websocket.WsSession;
import io.helidon.websocket.WsUpgradeException;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * WebSockets, the Helidon half: the client's `connect`, the server's endpoints,
 * and the session wire the Helidon-free ops in {@link SocketNatives} write to.
 *
 * <p>Helidon delivers frames through callbacks on its own threads; those
 * callbacks only hand plain Java to the socket's inbox. A server endpoint's
 * handler runs on a Truffle-owned virtual thread per connection, with the
 * handler chain in force at {@code serve_routes} installed — the same capture
 * {@code Async.spawn} does.
 */
public final class WsNatives {

    /** A live end: the ops write to the Helidon session. */
    private static final class SessionWire implements SocketNatives.Wire {
        private final WsSession session;
        SessionWire(WsSession session) { this.session = session; }
        @Override public void sendText(String text) { session.send(text, true); }
        @Override public void sendBinary(byte[] bytes) { session.send(BufferData.create(bytes), true); }
        @Override public void close() { session.close(WsCloseCodes.NORMAL_CLOSE, "closed"); }
    }

    /** Helidon's callbacks, both sides. */
    static final class Listener implements WsListener {
        final Socket sock;
        Runnable onOpen;   // the server starts the guest handler here
        volatile WsSession session;

        Listener(Socket sock) { this.sock = sock; }

        @Override
        public Optional<Headers> onHttpUpgrade(HttpPrologue prologue, Headers headers) throws WsUpgradeException {
            Map<String, String> m = new HashMap<>();
            headers.forEach(h -> m.put(h.name().toLowerCase(Locale.ROOT), h.get()));
            sock.upgradeHeaders = m;
            return Optional.empty();
        }

        @Override
        public void onOpen(WsSession s) {
            session = s;
            sock.opened(new SessionWire(s));
            if (onOpen != null) onOpen.run();
        }

        @Override
        public void onMessage(WsSession s, String text, boolean last) { sock.deliverText(text, last); }

        @Override
        public void onMessage(WsSession s, BufferData buffer, boolean last) { sock.deliverBinary(buffer.readBytes(), last); }

        @Override
        public void onClose(WsSession s, int status, String reason) { sock.ended(); }

        @Override
        public void onError(WsSession s, Throwable t) { sock.ended(); }
    }

    // ==================== client ====================

    /** `__native_ws_connect { url, config }` → `Result Socket SocketError`. Blocks until the session is open or the timeout passes. */
    @TruffleBoundary
    public static Object connect(MezzeRecord args) {
        Object urlObj = args.getField("url");
        if (!(urlObj instanceof String url)) throw new NativeTypeMismatchException("__native_ws_connect expects 'url' to be Str");
        int timeoutMs = 30_000;
        if (args.getField("config") instanceof MezzeRecord config && config.getField("timeout_ms") instanceof Long l) {
            timeoutMs = (int) Math.min(l, Integer.MAX_VALUE);
        }
        Socket s = new Socket();
        try {
            WsClient.create().connect(URI.create(url), new Listener(s));
            if (!s.opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return SocketNatives.connectFailed(url, "no WebSocket handshake within " + timeoutMs + " ms");
            }
            if (s.wire == null || s.closed) return SocketNatives.connectFailed(url, "the server did not accept the upgrade");
            return MezzeVariant.ok(s);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SocketNatives.connectFailed(url, "interrupted while connecting");
        } catch (RuntimeException e) {
            return SocketNatives.connectFailed(url, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ==================== server endpoints ====================

    /**
     * The `WsRouting` builder for a server's `[SocketRoute]`: a fresh listener per
     * connection whose `onOpen` starts the guest handler on a Truffle-owned
     * virtual thread with the chain and scope captured at `serve_routes`.
     */
    static WsRouting.Builder routing(Object socketsField, Env env, HandlerChainEntry[] chain, Object scope) {
        Object[] routes = seqToArray(socketsField);
        WsRouting.Builder b = WsRouting.builder();
        for (Object o : routes) {
            if (!(o instanceof MezzeRecord r)) throw new NativeTypeMismatchException("a socket route must be a record");
            Object pathObj = r.getField("path");
            Object handlerObj = r.getField("handler");
            if (!(pathObj instanceof String path) || !(handlerObj instanceof MezzeFunction handler)) {
                throw new NativeTypeMismatchException("a socket route needs 'path: Str' and 'handler: { m, socket } -> {}'");
            }
            b.endpoint(path, () -> serverListener(env, chain, scope, handler, path));
        }
        return b;   // `WebServer.builder().addRouting` takes the builder
    }

    /** Is there anything to register? (`[]` arrives as an empty seq or array.) */
    static boolean hasRoutes(Object socketsField) {
        return seqToArray(socketsField).length > 0;
    }

    private static Object[] seqToArray(Object field) {
        if (field instanceof MezzeSeq<?> seq) {
            Object[] out = new Object[seq.size()];
            for (int i = 0; i < out.length; i++) out[i] = seq.get(i);
            return out;
        }
        if (field instanceof Object[] arr) return arr;
        return new Object[0];
    }

    private static WsListener serverListener(Env env, HandlerChainEntry[] chain, Object scope, MezzeFunction handler, String path) {
        Socket s = new Socket();
        Listener l = new Listener(s);
        l.onOpen = () -> env.newTruffleThreadBuilder(() -> runHandler(chain, scope, handler, s, l, path))
            .virtual(true)
            .build()
            .start();
        return l;
    }

    /** One connection's guest handler, start to finish; the session is closed when it returns or raises. */
    private static void runHandler(HandlerChainEntry[] chain, Object scope, MezzeFunction handler, Socket s, Listener l, String path) {
        boolean installed = false;
        try {
            if (scope != null) HandlerChainRegistry.restoreScopeVar(scope);
            if (chain.length > 0) {
                HandlerChainRegistry.installHandlerChainEntries(chain);
                installed = true;
            }
            MezzeRecord req = MezzeRecord.fromFields(
                new String[]{"method", "url", "headers", "body", "timeout_ms", "chunks"},
                new Object[]{
                    MezzeVariant.noPayload(SocketNatives.REG, SocketNatives.tag("Get")),
                    pathOf(l, path),
                    MezzeMap.fromJavaMap(new HashMap<Object, Object>(s.upgradeHeaders)),
                    MezzeVariant.none(),
                    MezzeVariant.none(),
                    MezzeVariant.none(),
                });
            MezzeRecord matched = MezzeRecord.fromFields(
                new String[]{"req", "params"},
                new Object[]{req, MezzeMap.fromJavaMap(new HashMap<Object, Object>())});
            MezzeRecord arg = MezzeRecord.fromFields(new String[]{"m", "socket"}, new Object[]{matched, s});
            Object[] closures = handler.getClosureValues();
            Object[] full = new Object[closures.length + 1];
            System.arraycopy(closures, 0, full, 0, closures.length);
            full[closures.length] = arg;
            IndirectCallNode.getUncached().call(handler.getCallTarget(), full);
        } catch (Throwable t) {
            System.err.println("[mezze] websocket handler on " + path + " raised: "
                + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        } finally {
            if (installed) HandlerChainRegistry.clearHandlerChain();
            if (!s.closed && l.session != null) {
                s.closed = true;
                try {
                    l.session.close(WsCloseCodes.NORMAL_CLOSE, "handler finished");
                } catch (RuntimeException ignored) {
                    // nothing left to tell the peer
                }
            }
        }
    }

    /** The handshake's request path (the router's `path_of` shape; the query is not carried). */
    private static String pathOf(Listener l, String fallbackPath) {
        try {
            WsSession session = l.session;
            if (session != null) return session.prologue().uriPath().path();
        } catch (RuntimeException ignored) {
            // the endpoint path is a true answer
        }
        return fallbackPath;
    }

    private WsNatives() {}
}
