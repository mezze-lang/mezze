package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.interpreter.nodes.continuation.HandlerChainEntry;
import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The HTTP SERVER natives — the socket half of `std::effects::http_server`.
 *
 * Backed by Helidon WebServer 4 (Níma): the same stack the client half already
 * uses, Loom-native, one virtual thread per connection, no Netty.
 *
 * <h2>The handler runs on Helidon's own thread</h2>
 *
 * Helidon owns the threads that accept connections and read requests, and it
 * gives one virtual thread per CONNECTION — measured: 2,033,444 requests across
 * exactly 64 threads under a 64-connection load. So each of those threads serves
 * tens of thousands of requests, which is what makes it worth entering the
 * Truffle context on them: {@code isThreadAccessAllowed} admits every thread,
 * and the per-thread costs (registering the thread, installing the handler
 * chain) are paid once per connection and amortise to nothing.
 *
 * The handler therefore runs inline: enter the context, build the Mezze
 * {@code Request}, call the guest handler, read the {@code Response} back out,
 * leave, write the reply.
 *
 * <h2>What this replaced, and what it was worth</h2>
 *
 * Every request used to be copied into a plain-Java {@code Job}, pushed onto a
 * bounded queue and picked up by one of a pool of Truffle-owned workers, while
 * the Helidon thread parked on a {@code CompletableFuture} waiting for the
 * reply. That bought a simple invariant — no guest value on a thread Truffle
 * does not own — at the cost of two context switches, four allocations and
 * three copies of the request headers per request.
 *
 * It was the single largest cost in the server. The same Java program with and
 * without that handoff measured 545.9k vs 444.7k req/s, and removing it here
 * was worth, at 8 threads and 256 connections:
 *
 * <ul>
 *   <li>throughput 333k -> 391k req/s (+17.5%)</li>
 *   <li>median latency 500us -> 333us (-33%)</li>
 *   <li>43.5k -> 55.6k req/s per core (+28%), on fewer cores</li>
 *   <li>p99 3.8ms -> 5.1ms: the one regression, see below</li>
 * </ul>
 *
 * <h2>Why there is still a concurrency bound</h2>
 *
 * {@code workers} bounds how many handlers run at once. The pool enforced that
 * by having exactly that many threads; inline there is no pool, so the bound is
 * a permit count instead. It is not optional: without it all 256 connections ran
 * handlers simultaneously and p99 went to 9.2ms, while capping it brought the
 * tail back to 5.1ms at no cost to throughput. A lower bound trades throughput
 * for tail — 16 permits measured 395k req/s at p99 4.6ms — so the knob is the
 * same one it always was, and still means what its documentation says.
 *
 * A request waits up to {@link #ADMIT_WAIT_MS} for a permit, because a burst
 * should queue the way it used to. Sustained overload still gets a 503 the
 * client can see rather than a hang nobody can diagnose.
 *
 */
public final class HttpServerNatives {

    private HttpServerNatives() {
    }

    /**
     * How many handlers may run at once when the config does not say.
     *
     * Wide, and deliberately not core-count-sized: a handler that performs a
     * request of its own HOLDS its permit while it waits, so a bound near the
     * core count would starve an IO-bound route.
     *
     * It is also the knob for the one regression that came with running handlers
     * on Helidon's own threads — p99 went 3.8ms to 5.1ms while the mean went 333k
     * to 391k. Measured, same payload, at 8 threads / 256 connections:
     *
     *   16 permits    395k req/s   p99 4.6ms
     *   32 permits    392k req/s   p99 4.8ms
     *   64 permits    391k req/s   p99 5.1ms
     *  128 permits    401k req/s   p99 6.3ms
     *
     * So a narrower bound buys tail latency and a wider one buys throughput. The
     * default stays wide because starving a blocking handler is a worse failure
     * than a 0.3ms tail, and a deployment that cares more about the tail than the
     * mean can say so in its `ServerConfig`.
     */
    private static final int DEFAULT_WORKERS =
        Math.max(16, 2 * Runtime.getRuntime().availableProcessors());

    /** How long a request waits for an in-flight slot before it is refused. */
    private static final long ADMIT_WAIT_MS = 10_000L;

    /** Tag for the `Method` constructors — the same name-hash the guest uses.
     *  Computed lazily per name; the set is small and fixed. */
    private static int methodTag(String constructorName) {
        return com.mezze.language.ast.BuiltinTypes.INSTANCE.computeVariantTag(constructorName, null);
    }

    // ==================== The handle Mezze holds ====================

    /**
     * A running server. Opaque to the guest: the stdlib only ever passes it back
     * to {@code __native_http_server_port} / {@code __native_http_server_stop}.
     */
    public static final class RunningServer implements AutoCloseable {
        private final WebServer server;
        private final int port;
        private volatile boolean stopped;

        RunningServer(WebServer server, int port) {
            this.server = server;
            this.port = port;
        }

        public int getPort() {
            return port;
        }

        public boolean isStopped() {
            return stopped;
        }

        /**
         * Release the socket.
         *
         * Registered with the context so a guest that never calls `stop` — a
         * crash, an interrupted `mez dev`, a test that forgot — does not leave a
         * bound port behind. `stop` and this are the same operation; both are
         * idempotent.
         */
        @Override
        public void close() {
            stopServer(this);
        }
    }

    /** One handler result, on its way back to a Helidon thread. */
    /**
     * A reply is EITHER a complete byte[] or a guest iterator of chunks.
     *
     * The streaming arm exists because `res.send(byte[])` needs the whole body
     * in heap first: serving a 2GB file cost 2GB. With chunks, the handler
     * yields `Bytes` at a time and this writes each one to Helidon's output
     * stream, so the peak is one chunk.
     *
     * The guest iterator is safe to hold here ONLY because the handler now runs
     * on the Helidon connection thread, entered into the Truffle context
     * (4bc94b1b1) — pulling from it happens on the same thread that built it.
     * Under the old queue-and-worker design this field could not have existed:
     * the Helidon thread was not Truffle-owned, and "no guest value crosses a
     * thread Truffle does not own" was the invariant.
     */
    private record Reply(int status, Map<String, String> headers, byte[] body, Object chunks) {
        Reply(int status, Map<String, String> headers, byte[] body) {
            this(status, headers, body, null);
        }

        boolean isStreamed() { return chunks != null; }
    }


    // ==================== Natives ====================

    /**
     * Start a server: bind the socket, start the workers, return the handle.
     *
     * Args: {@code { handler, port, host, workers }}. A port of 0 lets the OS
     * choose, which is what a test wants — the chosen port is readable from the
     * handle afterwards.
     */
    @TruffleBoundary
    public static Object httpServerStart(MezzeRecord args) {
        Object handlerObj = getField(args, "handler");
        if (!(handlerObj instanceof MezzeFunction handler)) {
            throw new NativeTypeMismatchException("Expected 'handler' to be a function");
        }
        int port = (int) getLong(args, "port");
        String host = getString(args, "host");
        int workerCount = (int) getLongOrDefault(args, "workers", 0);
        if (workerCount <= 0) workerCount = DEFAULT_WORKERS;
        boolean streamBodies = getBool(args, "stream_bodies");

        MezzeContext context = MezzeContext.get(null);
        Env env = context.getEnv();

        // The handler is called from a worker, not from here, so the handler
        // chain and scope in force at `serve` have to be carried across — the
        // same capture `Async.spawn` does for a spawned task. Without it a
        // handler that performs an effect would find an empty chain.
        HandlerChainEntry[] chain = HandlerChainRegistry.snapshotHandlerChainEntries();
        Object scope = HandlerChainRegistry.lookupScopeVar();

        TruffleContext truffleCtx = env.getContext();
        Semaphore inFlight = new Semaphore(workerCount);

        WebServer server = WebServer.builder()
            .host(host)
            .port(port)
            .routing(rules -> rules.any((req, res) ->
                dispatch(truffleCtx, handler, chain, scope, inFlight, streamBodies, req, res)))
            .build();

        server.start();
        RunningServer running = new RunningServer(server, server.port());
        // The guest gets a `stop`, and may never call it. The context closing is
        // the backstop: a listening socket must not outlive the program that
        // opened it.
        context.registerForDispose(running);
        return running;
    }

    /** The bound port. Interesting when the caller asked for 0. */
    @TruffleBoundary
    public static long httpServerPort(MezzeRecord args) {
        return server(args).port;
    }

    /** Stop the listener. Idempotent. */
    @TruffleBoundary
    public static MezzeRecord httpServerStop(MezzeRecord args) {
        stopServer(server(args));
        return MezzeRecord.empty();
    }

    /** The one shutdown path, shared by `stop` and by context dispose. */
    private static void stopServer(RunningServer running) {
        if (running.stopped) {
            return;
        }
        running.stopped = true;
        // The handlers run on Helidon's own threads, so stopping the listener is
        // the whole shutdown: there is no pool left to retire.
        try {
            running.server.stop();
        } catch (RuntimeException ignored) {
            // A server that is already down is a stop that already happened.
        }
    }

    private static RunningServer server(MezzeRecord args) {
        Object obj = getField(args, "server");
        if (obj instanceof RunningServer running) return running;
        throw new NativeTypeMismatchException("Expected 'server' to be a running HTTP server");
    }

    /** Set once per connection thread: the chain whose entries are installed
     *  on it. Identity, not a flag, so a second server on the same thread
     *  installs its own chain rather than inheriting the first one's. */
    private static final ThreadLocal<HandlerChainEntry[]> INSTALLED_CHAIN = new ThreadLocal<>();

    /**
     * Run the guest handler on the calling Helidon thread.
     *
     * The thread is not one Truffle created, so the context is entered here.
     * {@code isThreadAccessAllowed} admits every thread, and Helidon gives one
     * virtual thread per CONNECTION — measured: 2,033,444 requests across 64
     * threads — so the per-thread costs (registration, installing the handler
     * chain) are paid once per connection and amortise to nothing, while the
     * queue, the future and two context switches per request all disappear.
     */
    @TruffleBoundary
    private static void dispatch(
        TruffleContext truffleCtx,
        MezzeFunction handler,
        HandlerChainEntry[] chain,
        Object scope,
        Semaphore inFlight,
        boolean streamBodies,
        ServerRequest req,
        ServerResponse res
    ) {
        // Wait for a slot rather than refusing at once: a burst should queue,
        // which is what the job queue used to do. Sustained overload still gets
        // a 503 the client can see instead of a hang nobody can diagnose.
        try {
            if (!inFlight.tryAcquire(ADMIT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                res.status(503).send("The server is at capacity");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            res.status(503).send("The server is shutting down");
            return;
        }

        Object prev = truffleCtx.enter(null);
        Reply reply;
        try {
            if (INSTALLED_CHAIN.get() != chain) {
                if (scope != null) HandlerChainRegistry.restoreScopeVar(scope);
                if (chain.length > 0) HandlerChainRegistry.installHandlerChainEntries(chain);
                INSTALLED_CHAIN.set(chain);
            }
            reply = runHandler(handler, req, streamBodies);
        } finally {
            truffleCtx.leave(null, prev);
            inFlight.release();
        }
        res.status(reply.status());
        for (Map.Entry<String, String> h : reply.headers().entrySet()) {
            res.header(h.getKey(), h.getValue());
        }
        if (reply.isStreamed()) {
            sendChunks(truffleCtx, res, reply.chunks());
        } else {
            res.send(reply.body());
        }
    }

    /** One guest handler call straight off a {@link ServerRequest}. */
    private static Reply runHandler(MezzeFunction handler, ServerRequest req, boolean streamBodies) {
        MezzeBodyReader reader = streamBodies
            ? new MezzeBodyReader(req.content().inputStream())
            : null;
        try {
            Object result = callOneArg(handler, IndirectCallNode.getUncached(),
                                       handlerArg(req, reader));
            if (!(result instanceof MezzeRecord response)) {
                return new Reply(500,
                    Map.of("content-type", "text/plain"),
                    utf8("The handler returned something that is not a Response"));
            }
            return replyOf(response);
        } catch (Throwable e) {
            return new Reply(500,
                Map.of("content-type", "text/plain"),
                utf8("The handler raised: " + message(e)));
        }
    }

    /**
     * The guest `Request`, built straight from Helidon.
     *
     * The queued path copies the headers three times — into a {@code HashMap}
     * on the Helidon thread, into a second one in {@code requestRecord}, then
     * into a {@code MezzeMap}. This builds the guest map once.
     */
    /**
     * What the guest handler is called with: the request, and a reader for its
     * body when the server was asked to stream.
     *
     * The handler here is NOT the one a caller passed to `serve` — it is the
     * wrapper `start_socket` builds, which turns a `Some` reader into the
     * request's `chunks` and hands an ordinary `Request` on. Two fields, always,
     * so there is one calling convention rather than two.
     *
     * A body can only be read once, so streaming and buffering are exclusive:
     * taking the bytes drains the stream, and handing over the stream means the
     * bytes were never taken. That is why `stream_bodies` is a decision made
     * before the socket is bound and not per request.
     */
    private static MezzeRecord handlerArg(ServerRequest req, MezzeBodyReader reader) {
        if (reader == null) {
            return MezzeRecord.fromFields(
                new String[]{"req", "reader"},
                new Object[]{requestRecord(req, true), MezzeVariant.none()});
        }
        // The reader outlives the handler on purpose: Helidon keeps the
        // connection's content readable while the response is written, so a
        // handler may pass its request's `chunks` on as the response's.
        return MezzeRecord.fromFields(
            new String[]{"req", "reader"},
            new Object[]{requestRecord(req, false), MezzeVariant.some(reader)});
    }

    /**
     * Single-shot reader over one request's body, behind the guest's opaque
     * `BodyReader` newtype — the same arrangement as `FileHandle` over a
     * FileChannel.
     */
    public static final class MezzeBodyReader {
        private final java.io.InputStream in;

        MezzeBodyReader(java.io.InputStream in) {
            this.in = in;
        }
    }

    /**
     * The next chunk, or empty Bytes once the body is done.
     *
     * A SHORT read is not the end — a socket hands over what has arrived, so a
     * chunk smaller than `max_bytes` is ordinary and the iterator keeps pulling.
     * Only 0 or -1 ends it.
     *
     * A read failure THROWS. Ending the iterator instead would make a client
     * that vanished mid-upload indistinguishable from one that finished, and a
     * handler would commit a truncated upload believing it whole.
     */
    @TruffleBoundary
    public static Object requestReadChunk(MezzeRecord args) {
        Object r = getField(args, "reader");
        if (!(r instanceof MezzeBodyReader reader)) {
            throw new NativeTypeMismatchException("Expected 'reader' to be a request body reader");
        }
        long maxBytes = getLong(args, "max_bytes");
        if (maxBytes <= 0) {
            throw new NativeTypeMismatchException("'max_bytes' must be positive, got " + maxBytes);
        }
        // The body is readable only while the handler runs — Helidon owns the
        // connection's content and is done with it once dispatch returns. The
        // tempting thing to write is an echo: hand the request's `chunks`
        // straight back as the response's. That walks them AFTER the handler
        // returned, and without this check the reads would answer nothing and
        // the client would get a 200 with an empty body, silently. Collect with
        // `body_bytes` inside the handler and answer those bytes instead.
        // No check that the handler is still running. It is fair to assume one
        // is needed and it is not: Helidon keeps the connection's content
        // readable while the response is written, so a handler MAY hand its
        // request's `chunks` on as the response's and have them walked
        // afterwards. Measured at 20 B, 40 KB and 400 KB — all echoed back
        // byte-for-byte. A guard here was written on the assumption that this
        // could not work, and it only removed the capability.
        int cap = (int) Math.min(maxBytes, 1 << 20);
        byte[] buf = new byte[cap];
        try {
            int n = reader.in.read(buf, 0, cap);
            if (n <= 0) return new BytesNatives.MezzeBytes(new byte[0]);
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return new BytesNatives.MezzeBytes(out);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("The request body could not be read: " + message(e), e);
        }
    }

    private static MezzeRecord requestRecord(ServerRequest req, boolean withBody) {
        Map<Object, Object> headers = new HashMap<>();
        req.headers().forEach(h -> headers.put(h.name().toLowerCase(), h.get()));

        Object body = MezzeVariant.none();
        if (withBody) {
            byte[] raw;
            try {
                raw = req.content().hasEntity() ? req.content().as(byte[].class) : null;
            } catch (RuntimeException e) {
                raw = null;
            }
            if (raw != null && raw.length > 0) {
                body = MezzeVariant.some(new BytesNatives.MezzeBytes(raw));
            }
        }

        // `chunks` is always None HERE. The guest wrapper fills it from the
        // reader, because `iter::unfold`'s step has to be a Mezze function —
        // IterCursor casts it to MezzeFunction — so a native cannot build the
        // iterator this field wants.
        return MezzeRecord.fromFields(
            new String[]{"method", "url", "headers", "body", "timeout_ms", "chunks"},
            new Object[]{
                methodVariant(req.prologue().method().text()),
                requestTarget(req),
                MezzeMap.fromJavaMap(headers),
                body,
                MezzeVariant.none(),
                MezzeVariant.none(),
            }
        );
    }

    /**
     * The request target as the guest sees it: path, plus `?query` when there is
     * one. Not an absolute URL — a server receives a target, not a URL, and the
     * router's `path_of` is written for exactly this shape.
     */
    private static String requestTarget(ServerRequest req) {
        String path = req.prologue().uriPath().path();
        var query = req.prologue().query();
        if (query.isEmpty()) return path;
        String raw = query.rawValue();
        return (raw == null || raw.isEmpty()) ? path : path + "?" + raw;
    }

    /**
     * The `Method` constructor for a wire method name.
     *
     * An unknown method would otherwise mint a variant the guest's `match` has
     * no arm for, so it is refused here where the message can still name the
     * method.
     */
    private static MezzeVariant methodVariant(String wireName) {
        String constructor = switch (wireName.toUpperCase()) {
            case "GET" -> "Get";
            case "POST" -> "Post";
            case "PUT" -> "Put";
            case "DELETE" -> "Delete";
            case "PATCH" -> "Patch";
            case "HEAD" -> "Head";
            case "OPTIONS" -> "Options";
            default -> null;
        };
        if (constructor == null) {
            throw new NativeTypeMismatchException(
                "The HTTP method '" + wireName + "' has no Mezze `Method` constructor");
        }
        return MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), methodTag(constructor));
    }

    /**
     * Pull chunks from the guest iterator and write each to Helidon's output
     * stream, so the peak heap is one chunk rather than the whole body.
     *
     * Re-enters the context: `dispatch` has already left it by the time the
     * response is written, and advancing a guest iterator runs guest code —
     * the pipeline's stages, which may themselves perform.
     *
     * A chunk that is not Bytes is a type error rather than something to
     * coerce: a body is bytes, and silently stringifying whatever arrived
     * would send a plausible-looking wrong response.
     */
    @TruffleBoundary
    private static void sendChunks(TruffleContext truffleCtx, ServerResponse res, Object chunks) {
        Object prev = truffleCtx.enter(null);
        java.io.OutputStream out = null;
        // Non-null once the BODY itself has failed — as opposed to the peer
        // having gone away, which is the IOException below and is not our
        // failure to report.
        String bodyFailure = null;
        try {
            out = res.outputStream();
            com.mezze.interpreter.nodes.builtin.collections.IterCursor cursor =
                com.mezze.interpreter.nodes.builtin.collections.IterCursor.of(
                    (com.mezze.datastructures.iter.MezzeIter) chunks,
                    com.oracle.truffle.api.nodes.IndirectCallNode.getUncached());
            while (true) {
                Object chunk = cursor.next();
                if (chunk == com.mezze.interpreter.nodes.builtin.collections.IterCursor.END) break;
                if (chunk instanceof BytesNatives.MezzeBytes mb) {
                    out.write(mb.getData());
                } else if (chunk instanceof String str) {
                    out.write(utf8(str));
                } else {
                    throw new NativeTypeMismatchException(
                        "A streamed response body must yield Bytes, got "
                            + (chunk == null ? "null" : chunk.getClass().getSimpleName()));
                }
            }
        } catch (java.io.IOException e) {
            // The peer went away mid-body. Nothing can be said on the wire now
            // — the status and headers are long gone — so this is dropped
            // rather than turned into a 500 that cannot be delivered.
            System.err.println("[mezze] streamed response aborted: " + e.getMessage());
        } catch (Throwable t) {
            // The BODY failed while being produced: a guest step threw, which is
            // what `response_file` now does when a read fails instead of
            // answering `Done` (BUG 28). Only the MESSAGE is kept — a guest
            // value must not travel out onto a Helidon thread.
            // A `perform` inside a chunk step arrives here as a SUSPENSION, not
            // as an unwinding exception: the drain enters the context but does
            // not install the handler chain `runHandler` does, so there is no
            // handler for the effect at this point and the cursor is handed a
            // MezzeSuspend where a Step was due. Name it, rather than reporting
            // the ClassCastException that falls out of the cast.
            String raw = t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : ": " + t.getMessage());
            bodyFailure = raw.contains("MezzeSuspend")
                ? "a chunk step performed an effect with no handler at the point the "
                    + "body is written — a streamed body cannot perform one that is "
                    + "handled inside the handler that returned it (" + raw + ")"
                : raw;
        } finally {
            truffleCtx.leave(null, prev);
        }

        if (bodyFailure == null) {
            // Completing the response is a PROMISE that the body is whole: for
            // chunked HTTP/1.1 the terminal zero-length chunk is the only signal
            // the client has, and under Content-Length it is the promised count
            // being met. Only say it when it is true.
            if (out != null) {
                try {
                    out.close();
                } catch (java.io.IOException e) {
                    System.err.println("[mezze] streamed response aborted: " + e.getMessage());
                }
            }
            return;
        }

        // DELIBERATELY NOT CLOSED. A clean close here is what made a 40 KB
        // truncation of a 200 KB file indistinguishable from a 40 KB file with a
        // 200 on it. The status and headers left long ago, so no error response
        // is available — the only honest thing left is to fail the body and let
        // the connection drop, so the client sees a truncated transfer. That is
        // what axum does with an errored stream, and for the same reason.
        //
        // Logged HOST-side, where it costs no guest effect. A log call in the
        // guest step would have put a logging effect in the row of every caller
        // that streams a file.
        System.err.println("[mezze] streamed response body FAILED mid-stream, connection aborted: "
            + bodyFailure);
        throw new IllegalStateException(
            "streamed response body failed mid-stream: " + bodyFailure);
    }

    /** Read a guest `Response` back out into plain Java. */
    private static Reply replyOf(MezzeRecord response) {
        int status = (int) getLongOrDefault(response, "status", 200L);

        Map<String, String> headers = new HashMap<>();
        Object headersField = getField(response, "headers");
        if (headersField instanceof MezzeMap<?, ?> m) {
            for (MezzeMap.Entry<?, ?> entry : m) {
                headers.put(String.valueOf(entry.key()), String.valueOf(entry.value()));
            }
        }

        // `chunks: Option (Iter Bytes)` decides the shape. An ADDITIVE field
        // rather than turning `body` into a variant: that would change what
        // every READER of `resp.body` gets back, across the client, the router
        // and user code, where this only touches the handful of places that
        // CONSTRUCT a Response.
        //
        // isSome() on the precomputed tag, not getConstructorName() — a
        // recovered name is "Unknown(<tag>)" in an AOT image for anything the
        // registry has not seen.
        Object chunksField = getField(response, "chunks");
        if (chunksField instanceof MezzeVariant maybe && maybe.isSome()) {
            Object chunks = maybe.getPayloadFieldByName("val");
            if (chunks != null) {
                return new Reply(status, headers, new byte[0], chunks);
            }
        }

        byte[] body = new byte[0];
        Object bodyField = getField(response, "body");
        if (bodyField instanceof BytesNatives.MezzeBytes mb) {
            body = mb.getData();
        } else if (bodyField instanceof String s) {
            body = utf8(s);
        }
        return new Reply(status, headers, body);
    }

    /**
     * Call a one-argument guest function: `[closure0..closureN, argRecord]` is
     * the calling convention every Mezze function expects.
     */
    private static Object callOneArg(MezzeFunction f, IndirectCallNode callNode, MezzeRecord arg) {
        Object[] closureValues = f.getClosureValues();
        Object[] fullArgs = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, fullArgs, 0, closureValues.length);
        fullArgs[closureValues.length] = arg;
        return callNode.call(f.getCallTarget(), fullArgs);
    }

    // ==================== Small helpers ====================

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private static Object getField(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) return null;
        return r.getField(idx);
    }

    private static long getLong(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        throw new NativeTypeMismatchException("Expected Int for field '" + fieldName + "'");
    }

    private static long getLongOrDefault(MezzeRecord args, String fieldName, long fallback) {
        Object value = getField(args, fieldName);
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        return fallback;
    }

    /**
     * A guest `Bool` arrives as a java.lang.Boolean, which is the only shape
     * accepted here — the same as FilesystemNatives.getBool and IONatives.
     *
     * Deliberately NOT falling back to a variant's constructor NAME: a
     * stdlib-built variant reads back as "Unknown(tag)" under native-image, so
     * such a fallback would quietly answer `false` in AOT and nowhere else, and
     * every interpreter test is JVM-only and blind to it. `stream_bodies` is a
     * required ServerConfig field, so a missing or ill-typed one is a defect
     * worth saying out loud rather than defaulting past.
     */
    private static boolean getBool(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof Boolean b) return b;
        throw new NativeTypeMismatchException(
            "Expected Bool for field '" + fieldName + "'"
                + (value == null ? " but it was absent" : ", got " + value.getClass().getSimpleName()));
    }

    private static String getString(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof String s) return s;
        throw new NativeTypeMismatchException("Expected Str for field '" + fieldName + "'");
    }
}
