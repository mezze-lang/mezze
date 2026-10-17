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
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The HTTP SERVER natives — the socket half of `std::effects::http_server`.
 *
 * Backed by Helidon WebServer 4 (Níma): the same stack the client half already
 * uses, Loom-native, one virtual thread per connection, no Netty.
 *
 * <h2>Why a queue and a worker pool</h2>
 *
 * Helidon owns the threads that accept connections and read requests. Those
 * threads are NOT entered into the Truffle context, so guest code must not run
 * on them — and nor may a guest VALUE be built there. So the two worlds are kept
 * strictly apart:
 *
 * <ul>
 *   <li>A Helidon thread reads the request into {@link Job} — plain Java only:
 *       strings, a {@code Map<String,String>}, a {@code byte[]} — hands it to a
 *       queue, and parks on the job's future until a reply comes back.</li>
 *   <li>A worker thread, created through {@code Env.newTruffleThreadBuilder}
 *       and therefore owned by Truffle, takes the job, builds the Mezze
 *       {@code Request} record, calls the guest handler, reads the
 *       {@code Response} back out into plain Java, and completes the future.</li>
 * </ul>
 *
 * No guest object ever crosses a thread Truffle does not own. That is the whole
 * invariant, and it is why this does not need {@code TruffleContext.enter} on a
 * foreign thread.
 *
 * <h2>Why a pool rather than a thread per request</h2>
 *
 * A worker is a VIRTUAL thread parked on {@code queue.take()}, so an idle one
 * costs almost nothing and the pool can be large. The pool exists to pay the
 * Truffle thread-registration and context-entry cost once per worker instead of
 * once per request. A handler that blocks — one that performs an HTTP request of
 * its own, say — occupies its worker for the duration, which is why the default
 * pool is wide rather than core-count-sized.
 *
 * When the queue is full the request is refused with 503 instead of parking the
 * Helidon thread indefinitely: backpressure that a client can see beats a hang
 * nobody can diagnose.
 */
public final class HttpServerNatives {

    private HttpServerNatives() {
    }

    /** Pool size when the config does not name one. Wide, because workers park. */
    private static final int DEFAULT_WORKERS =
        Math.max(16, 2 * Runtime.getRuntime().availableProcessors());

    /** Queued jobs per worker before new requests are refused with 503. */
    private static final int QUEUE_PER_WORKER = 64;

    /** How long a Helidon thread waits for a worker's reply before giving up. */
    private static final long REPLY_TIMEOUT_MS = 120_000L;

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
        private final BlockingQueue<Job> queue;
        private final List<Thread> workers;
        private final int port;
        private volatile boolean stopped;

        RunningServer(WebServer server, BlockingQueue<Job> queue, List<Thread> workers, int port) {
            this.server = server;
            this.queue = queue;
            this.workers = workers;
            this.port = port;
        }

        public int getPort() {
            return port;
        }

        public boolean isStopped() {
            return stopped;
        }

        /**
         * Release the socket and the workers.
         *
         * Registered with the context so a guest that never calls `stop` — a
         * crash, an interrupted `mez dev`, a test that forgot — does not leave a
         * bound port and a pool of parked workers behind. `stop` and this are the
         * same operation; both are idempotent.
         */
        @Override
        public void close() {
            stopServer(this);
        }
    }

    /** One in-flight request. Plain Java by construction — see the class note. */
    private static final class Job {
        final String method;
        final String target;
        final Map<String, String> headers;
        final byte[] body;
        final CompletableFuture<Reply> reply = new CompletableFuture<>();

        Job(String method, String target, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.target = target;
            this.headers = headers;
            this.body = body;
        }
    }

    /** One handler result, on its way back to a Helidon thread. */
    private record Reply(int status, Map<String, String> headers, byte[] body) {
    }

    /** The poison pill: a worker that takes this exits its loop. */
    private static final Job STOP = new Job("", "", Map.of(), new byte[0]);

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

        MezzeContext context = MezzeContext.get(null);
        Env env = context.getEnv();

        // The handler is called from a worker, not from here, so the handler
        // chain and scope in force at `serve` have to be carried across — the
        // same capture `Async.spawn` does for a spawned task. Without it a
        // handler that performs an effect would find an empty chain.
        HandlerChainEntry[] chain = HandlerChainRegistry.snapshotHandlerChainEntries();
        Object scope = HandlerChainRegistry.lookupScopeVar();

        BlockingQueue<Job> queue = new ArrayBlockingQueue<>(workerCount * QUEUE_PER_WORKER);

        List<Thread> workers = new ArrayList<>(workerCount);
        for (int i = 0; i < workerCount; i++) {
            Thread worker = env.newTruffleThreadBuilder(() -> workerLoop(queue, handler, chain, scope))
                .virtual(true)
                .build();
            workers.add(worker);
        }

        WebServer server = WebServer.builder()
            .host(host)
            .port(port)
            .routing(rules -> rules.any((req, res) -> dispatch(queue, req, res)))
            .build();

        for (Thread worker : workers) {
            worker.start();
        }
        server.start();
        RunningServer running = new RunningServer(server, queue, workers, server.port());
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

    /** Stop the listener, then retire the workers. Idempotent. */
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
        // Listener first: no new job can be queued once it is down, so the
        // poison pills below cannot be overtaken by real work.
        try {
            running.server.stop();
        } catch (RuntimeException ignored) {
            // A server that is already down is a stop that already happened.
        }
        for (int i = 0; i < running.workers.size(); i++) {
            if (!running.queue.offer(STOP)) {
                // Queue full of work nobody will now read: interrupting is the
                // only way left to retire the worker.
                break;
            }
        }
        for (Thread worker : running.workers) {
            worker.interrupt();
        }
    }

    private static RunningServer server(MezzeRecord args) {
        Object obj = getField(args, "server");
        if (obj instanceof RunningServer running) return running;
        throw new NativeTypeMismatchException("Expected 'server' to be a running HTTP server");
    }

    // ==================== Helidon side (no guest values here) ====================

    /**
     * Read one request into plain Java, queue it, wait for the reply.
     *
     * Runs on a Helidon thread. Everything it touches is Java; the Mezze
     * handler is somebody else's problem, by design.
     */
    private static void dispatch(BlockingQueue<Job> queue, ServerRequest req, ServerResponse res) {
        String method = req.prologue().method().text();
        String target = requestTarget(req);

        Map<String, String> headers = new HashMap<>();
        req.headers().forEach(h -> headers.put(h.name().toLowerCase(), h.get()));

        byte[] body;
        try {
            body = req.content().hasEntity() ? req.content().as(byte[].class) : new byte[0];
            if (body == null) body = new byte[0];
        } catch (RuntimeException e) {
            res.status(400).send("Could not read the request body: " + message(e));
            return;
        }

        Job job = new Job(method, target, headers, body);
        if (!queue.offer(job)) {
            // Every worker is busy and the backlog is full. Say so, rather than
            // parking this thread on a queue that is not draining.
            res.status(503).send("The server is at capacity");
            return;
        }

        Reply reply;
        try {
            reply = job.reply.get(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            res.status(503).send("The server is shutting down");
            return;
        } catch (Exception e) {
            res.status(500).send("The handler did not reply: " + message(e));
            return;
        }

        res.status(reply.status());
        for (Map.Entry<String, String> h : reply.headers().entrySet()) {
            res.header(h.getKey(), h.getValue());
        }
        res.send(reply.body());
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

    // ==================== Worker side (guest values live here) ====================

    /**
     * Take jobs and run the guest handler on each, until stopped.
     *
     * Runs on a Truffle-owned virtual thread, so calling guest code and
     * allocating guest values are both legal here.
     */
    private static void workerLoop(
        BlockingQueue<Job> queue,
        MezzeFunction handler,
        HandlerChainEntry[] chain,
        Object scope
    ) {
        boolean installed = false;
        try {
            if (scope != null) HandlerChainRegistry.restoreScopeVar(scope);
            if (chain.length > 0) {
                HandlerChainRegistry.installHandlerChainEntries(chain);
                installed = true;
            }
            IndirectCallNode callNode = IndirectCallNode.getUncached();
            while (true) {
                Job job = queue.take();
                if (job == STOP) return;
                job.reply.complete(runHandler(handler, callNode, job));
            }
        } catch (InterruptedException e) {
            // Shutdown. Nothing to report: `stop` interrupts on purpose.
        } finally {
            if (installed) HandlerChainRegistry.clearHandlerChain();
        }
    }

    /**
     * One guest handler call, in and out.
     *
     * A handler that raises gets a 500 carrying the message. A server that dies
     * because one route threw is worse than a server that says what went wrong:
     * the next request still gets served, and the message is in the response
     * rather than only in a log the caller cannot see.
     */
    private static Reply runHandler(MezzeFunction handler, IndirectCallNode callNode, Job job) {
        try {
            Object result = callOneArg(handler, callNode, requestRecord(job));
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

    /** Build the guest `Request` for one job. */
    private static MezzeRecord requestRecord(Job job) {
        Map<Object, Object> headers = new HashMap<>(job.headers);
        Object body = job.body.length == 0
            ? MezzeVariant.none()
            : MezzeVariant.some(new BytesNatives.MezzeBytes(job.body));
        return MezzeRecord.fromFields(
            new String[]{"method", "url", "headers", "body", "timeout_ms"},
            new Object[]{
                methodVariant(job.method),
                job.target,
                MezzeMap.fromJavaMap(headers),
                body,
                MezzeVariant.none(),
            }
        );
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

    private static String getString(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof String s) return s;
        throw new NativeTypeMismatchException("Expected Str for field '" + fieldName + "'");
    }
}
