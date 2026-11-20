package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import io.helidon.common.tls.Tls;
import io.helidon.http.Method;
import io.helidon.webclient.api.HttpClientRequest;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import io.helidon.webclient.api.WebClientConfig;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Native implementations for I/O primitives.
 *
 * Includes:
 * - Buffer (byte buffer operations)
 * - JSON (serialization/parsing)
 */
public final class IONatives {

    // ==================== Buffer ====================

    public static class MezzeBuffer {
        private byte[] data;
        private int position = 0;

        public MezzeBuffer(int capacity) {
            this.data = new byte[capacity];
        }

        public MezzeBuffer(byte[] data) {
            this.data = data;
        }

        public byte[] getData() { return data; }
        public int getPosition() { return position; }
        public void setPosition(int pos) { this.position = Math.max(0, Math.min(pos, data.length)); }
        public int getCapacity() { return data.length; }

        public void writeByte(int b) {
            ensureCapacity(position + 1);
            data[position++] = (byte) b;
        }

        public int readByte() {
            if (position >= data.length) return -1;
            return data[position++] & 0xFF;
        }

        public void writeBytes(byte[] bytes) {
            ensureCapacity(position + bytes.length);
            System.arraycopy(bytes, 0, data, position, bytes.length);
            position += bytes.length;
        }

        public byte[] readBytes(int n) {
            int toRead = Math.min(n, data.length - position);
            byte[] result = new byte[toRead];
            System.arraycopy(data, position, result, 0, toRead);
            position += toRead;
            return result;
        }

        private void ensureCapacity(int needed) {
            if (needed > data.length) {
                int newSize = Math.max(data.length * 2, needed);
                data = Arrays.copyOf(data, newSize);
            }
        }

        @Override
        public String toString() { return "<buffer:" + data.length + ">"; }
    }

    @TruffleBoundary
    public static MezzeBuffer bufferEmpty(MezzeRecord args) {
        return new MezzeBuffer(64);
    }

    @TruffleBoundary
    public static MezzeBuffer bufferNew(MezzeRecord args) {
        long capacity = getLong(args, "capacity");
        return new MezzeBuffer((int) capacity);
    }

    @TruffleBoundary
    public static MezzeBuffer bufferFromBytes(MezzeRecord args) {
        MezzeList bytes = getList(args, "bytes");
        Object[] store = bytes.toArray();
        byte[] data = new byte[store.length];
        for (int i = 0; i < store.length; i++) {
            if (store[i] instanceof Long l) {
                data[i] = l.byteValue();
            } else if (store[i] instanceof Integer n) {
                data[i] = n.byteValue();
            }
        }
        return new MezzeBuffer(data);
    }

    @TruffleBoundary
    public static MezzeBuffer bufferFromString(MezzeRecord args) {
        String s = getString(args, "s");
        return new MezzeBuffer(s.getBytes(StandardCharsets.UTF_8));
    }

    @TruffleBoundary
    public static BytesNatives.MezzeBytes bufferToBytes(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        // Return MezzeBytes from the buffer's written content (up to position)
        byte[] data = buf.getData();
        int size = buf.getPosition();
        // Return BytesNatives.MezzeBytes for consistency with native bytes ops
        byte[] copy = new byte[size];
        System.arraycopy(data, 0, copy, 0, size);
        return new BytesNatives.MezzeBytes(copy);
    }

    @TruffleBoundary
    public static String bufferToString(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        return new String(buf.getData(), StandardCharsets.UTF_8);
    }

    @TruffleBoundary
    public static long bufferLength(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        return buf.getCapacity();
    }

    @TruffleBoundary
    public static long bufferPosition(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        return buf.getPosition();
    }

    @TruffleBoundary
    public static MezzeBuffer bufferSetPosition(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        long pos = getLong(args, "pos");
        buf.setPosition((int) pos);
        return buf;  // Return the buffer for functional API
    }

    @TruffleBoundary
    public static MezzeBuffer bufferWriteByte(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        long b = getLong(args, "b");  // Field is 'b', not 'byte'
        buf.writeByte((int) b);
        return buf;  // Return the buffer for functional API
    }

    @TruffleBoundary
    public static long bufferReadByte(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        return buf.readByte();
    }

    @TruffleBoundary
    public static MezzeBuffer bufferWriteBytes(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        MezzeList bytes = getList(args, "bytes");
        Object[] store = bytes.toArray();
        byte[] data = new byte[store.length];
        for (int i = 0; i < store.length; i++) {
            if (store[i] instanceof Long l) {
                data[i] = l.byteValue();
            }
        }
        buf.writeBytes(data);
        return buf;  // Return the buffer for functional API
    }

    @TruffleBoundary
    public static MezzeList bufferReadBytes(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        long n = getLong(args, "n");
        byte[] data = buf.readBytes((int) n);
        Object[] result = new Object[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (long) (data[i] & 0xFF);
        }
        return MezzeList.of(result);
    }

    @TruffleBoundary
    public static MezzeBuffer bufferSlice(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        long start = getLong(args, "start");
        long end = getLong(args, "end");
        byte[] data = buf.getData();
        int s = Math.max(0, (int) start);
        int e = Math.min(data.length, (int) end);
        byte[] sliced = Arrays.copyOfRange(data, s, e);
        return new MezzeBuffer(sliced);
    }

    @TruffleBoundary
    public static MezzeBuffer bufferConcat(MezzeRecord args) {
        MezzeBuffer a = getBuffer(args, "a");
        MezzeBuffer b = getBuffer(args, "b");
        byte[] dataA = a.getData();
        byte[] dataB = b.getData();
        byte[] result = new byte[dataA.length + dataB.length];
        System.arraycopy(dataA, 0, result, 0, dataA.length);
        System.arraycopy(dataB, 0, result, dataA.length, dataB.length);
        return new MezzeBuffer(result);
    }

    @TruffleBoundary
    public static MezzeBuffer bufferCopy(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        return new MezzeBuffer(buf.getData().clone());
    }

    @TruffleBoundary
    /**
     * Reset the buffer to empty. O(1), and it used to be O(capacity).
     *
     * <p>This called {@code Arrays.fill(buf.getData(), (byte) 0)} — zeroing the
     * ENTIRE backing array — before setting the position to zero. MEASURED on
     * `bench/cases/buffersurface` with a 2048-byte capacity: 237 643 ps, where
     * `write_byte` is ~12 000 and `size` 6 900. Clearing cost twenty times a
     * write, and it scaled with the CAPACITY rather than with anything written:
     * a megabyte buffer would have spent ~110 microseconds being "reset".
     *
     * <p>THE FILL WAS UNOBSERVABLE. `std::collections::buffer` exposes nine
     * operations, and none of them can see a byte at or beyond the position:
     * `size` and `is_empty` read the position, `to_bytes` copies only the
     * prefix below it, and `write`/`write_byte`/`write_str` append at it. So
     * zeroing the tail achieved exactly what {@code setPosition(0)} already
     * achieved.
     *
     * <p>WHAT THE FILL WOULD HAVE MATTERED FOR, and why it still does not. The
     * read side of this class — {@code bufferReadByte}, {@code bufferReadBytes},
     * {@code bufferToString} — is bounded by {@code data.length} rather than by
     * the position, so those CAN see the tail, and after this change they see
     * stale bytes where they used to see zeros. None of them is reachable:
     * `__native_buffer_to_str`, `_read_byte`, `_read_bytes`, `_position` and
     * `_length` have no `pub let` in the stdlib. They also disagree with the
     * write side about what `position` means — a write cursor for a builder, a
     * read cursor for a reader — which is why {@code bufferFromBytes} produces
     * a buffer whose `to_bytes` is empty. That wants resolving before any of
     * them is exposed, and it is not resolved here.
     */
    public static MezzeBuffer bufferClear(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        buf.setPosition(0);
        return buf;  // Return the buffer for functional API
    }

    @TruffleBoundary
    public static MezzeBuffer bufferWriteString(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        String s = getString(args, "s");
        buf.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        return buf;  // Return the buffer for functional API
    }

    @TruffleBoundary
    public static MezzeBuffer bufferWrite(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        Object bytesValue = getField(args, "bytes");

        // Handle both BytesNatives.MezzeBytes (the native) and MezzeBytes (value wrapper)
        if (bytesValue instanceof BytesNatives.MezzeBytes mb) {
            buf.writeBytes(mb.getData());
        } else if (bytesValue instanceof MezzeBytes mb) {
            // Copy the relevant portion of MezzeBytes to a byte array
            byte[] data = new byte[mb.length()];
            System.arraycopy(mb.getData(), mb.getOffset(), data, 0, mb.length());
            buf.writeBytes(data);
        } else if (bytesValue instanceof MezzeList list) {
            Object[] store = list.toArray();
            byte[] data = new byte[store.length];
            for (int i = 0; i < store.length; i++) {
                if (store[i] instanceof Long l) {
                    data[i] = l.byteValue();
                }
            }
            buf.writeBytes(data);
        } else {
            throw new NativeTypeMismatchException("Expected MezzeBytes or List for field 'bytes', got: " +
                (bytesValue == null ? "null" : bytesValue.getClass().getSimpleName()));
        }

        return buf;  // Return the buffer for functional API
    }

    @TruffleBoundary
    public static boolean bufferIsEmpty(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        return buf.getPosition() == 0;  // Empty = no bytes written
    }

    @TruffleBoundary
    public static long bufferSize(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        return buf.getPosition();  // Size = bytes written (position)
    }

    private static MezzeBuffer getBuffer(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeBuffer b) return b;
        if (value instanceof MezzeRecord rec) {
            for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
                Object inner = rec.getField(i);
                if (inner instanceof MezzeBuffer b) return b;
            }
        }
        throw new NativeTypeMismatchException("Expected Buffer for field '" + fieldName + "'");
    }

    // ==================== JSON (using DSL-JSON) ====================

    /**
     * Serialize a Json variant type to a JSON string.
     * Takes: {json: Json, pretty: Bool}
     * Returns: the string. NOT a Result — see below.
     *
     * Uses DSL-JSON for high-performance byte-level serialization.
     *
     * <h2>Why this has no error channel</h2>
     *
     * A well-formed `Json` tree is ALWAYS serializable. The writer is an
     * in-memory buffer, so there is no IO to fail, and the only throw in
     * {@link DslJsonCodec#serializeValue} is its `default` arm — reached only by
     * a value that is not a `Json` variant at all, which is a contract
     * violation, not a datum the caller could handle.
     *
     * It used to return `Result`, and the catch swallowed even the
     * NativeTypeMismatchException it had just thrown itself. The stdlib then had
     * nowhere to put the error — `to_str`'s signature returns `Str` — so it
     * answered `""`, and `to_bytes` answered empty bytes. A handler encoding a
     * bad value therefore replied 200 with a ZERO-BYTE body and said nothing,
     * which is exactly the silent wrong answer a Result is supposed to prevent.
     * Being unable to fail is what lets the signature say so.
     */
    @TruffleBoundary
    public static String jsonSerialize(MezzeRecord args) {
        Object jsonValue = getField(args, "json");
        Object prettyObj = getField(args, "pretty");
        boolean pretty = prettyObj instanceof Boolean b && b;

        if (!(jsonValue instanceof MezzeVariant json)) {
            throw new NativeTypeMismatchException(
                "Expected 'json' to be a Json variant, got: " +
                (jsonValue == null ? "null" : jsonValue.getClass().getSimpleName()));
        }
        try {
            return DslJsonCodec.serializeToString(json, pretty);
        } catch (java.io.IOException e) {
            // Only DslJsonCodec's `default` arm reaches here: the tree holds a
            // constructor that is not part of `Json`. Name it rather than hide it.
            throw new NativeTypeMismatchException("Cannot serialize as JSON: " + e.getMessage());
        }
    }

    /**
     * Serialize a Json variant to bytes.
     * Takes: {json: Json, pretty: Bool}
     * Returns: the bytes. No error channel — see {@link #jsonSerialize}.
     *
     * Uses DSL-JSON for high-performance byte-level serialization.
     */
    @TruffleBoundary
    public static BytesNatives.MezzeBytes jsonSerializeBytes(MezzeRecord args) {
        Object jsonValue = getField(args, "json");
        Object prettyObj = getField(args, "pretty");
        boolean pretty = prettyObj instanceof Boolean b && b;

        if (!(jsonValue instanceof MezzeVariant json)) {
            throw new NativeTypeMismatchException(
                "Expected 'json' to be a Json variant, got: " +
                (jsonValue == null ? "null" : jsonValue.getClass().getSimpleName()));
        }
        try {
            return new BytesNatives.MezzeBytes(DslJsonCodec.serializeToBytes(json, pretty));
        } catch (java.io.IOException e) {
            throw new NativeTypeMismatchException("Cannot serialize as JSON: " + e.getMessage());
        }
    }

    /**
     * Parse JSON from string.
     * Takes: {s: String}
     * Returns: Result Json ParseError
     *
     * ParseError shape (Phase 1 codec redesign, task #86):
     *   { path: [Str], reason: Str, input_ref: Option Str }
     *
     * `path` is empty for top-level parse failures; higher-layer decoders
     * add a path when they recurse into a JSON tree. `reason` carries the
     * human message. `input_ref` is a truncated echo of the offending input
     * (first 80 bytes) for diagnostic use; `None` when the input is not
     * available or too binary to render.
     *
     * Uses DSL-JSON for high-performance byte-level parsing.
     */
    @TruffleBoundary
    public static Object jsonParse(MezzeRecord args) {
        String s = getString(args, "s");
        try {
            MezzeVariant result = DslJsonCodec.parseString(s.trim());
            return createOk(result);
        } catch (Exception e) {
            return createParseErr("JSON parse error: " + e.getMessage(), s);
        }
    }

    /**
     * Parse JSON from bytes.
     * Takes: {bytes: Bytes}
     * Returns: Result Json ParseError
     *
     * See `jsonParse` for the ParseError shape.
     *
     * Uses DSL-JSON for high-performance byte-level parsing (no UTF-8 conversion needed).
     */
    @TruffleBoundary
    public static Object jsonParseBytes(MezzeRecord args) {
        Object bytesValue = getField(args, "bytes");
        byte[] bytes;

        if (bytesValue instanceof BytesNatives.MezzeBytes mb) {
            bytes = mb.getData();
        } else if (bytesValue instanceof MezzeBytes mb) {
            bytes = new byte[mb.length()];
            System.arraycopy(mb.getData(), mb.getOffset(), bytes, 0, mb.length());
        } else if (bytesValue instanceof byte[] b) {
            bytes = b;
        } else {
            return createParseErr("Expected Bytes for field 'bytes'", null);
        }

        try {
            MezzeVariant result = DslJsonCodec.parseBytes(bytes);
            return createOk(result);
        } catch (Exception e) {
            String inputRef = bytes.length == 0
                ? null
                : new String(bytes, 0, Math.min(bytes.length, 80), StandardCharsets.UTF_8);
            return createParseErr("JSON parse error: " + e.getMessage(), inputRef);
        }
    }

    // ==================== Helpers ====================

    private static MezzeVariant createOk(Object value) {
        return MezzeVariant.ok(value);
    }

    private static MezzeVariant createErr(String message) {
        // Create a DecodeError record: {path = [], message = "...", expected = "JSON", found = None}
        MezzeRecord error = MezzeRecord.fromFields(
            new String[]{"path", "message", "expected", "found"},
            new Object[]{
                MezzeList.empty(),
                message,
                "JSON",
                MezzeVariant.none()
            }
        );
        return MezzeVariant.err(error);
    }

    /**
     * Build the Phase-1 ParseError record used by the format-neutral
     * JSON parse natives. Shape: {path: [Str], reason: Str, input_ref: Option Str}.
     *
     * `input_ref` carries a short echo of the source bytes/string (used by
     * error renderers to point at the offending input); pass `null` when
     * no meaningful echo is available.
     */
    private static MezzeVariant createParseErr(String reason, String inputRef) {
        MezzeRecord error = MezzeRecord.fromFields(
            new String[]{"path", "reason", "input_ref"},
            new Object[]{
                MezzeList.empty(),
                reason,
                inputRef == null ? MezzeVariant.none() : MezzeVariant.some(inputRef)
            }
        );
        return MezzeVariant.err(error);
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
        throw new NativeTypeMismatchException("Expected Long for field '" + fieldName + "'");
    }

    private static String getString(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof String s) return s;
        throw new NativeTypeMismatchException("Expected String for field '" + fieldName + "'");
    }

    private static MezzeList getList(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeList list) return list;
        // Handle MezzeSeq (persistent vector) - convert to MezzeList
        if (value instanceof MezzeSeq seq) {
            return MezzeList.of(seq.toArray());
        }
        // Handle array literals (Object[]) - convert to MezzeList
        if (value instanceof Object[] arr) {
            return MezzeList.of(arr);
        }
        throw new NativeTypeMismatchException("Expected List for field '" + fieldName + "', got: " +
            (value == null ? "null" : value.getClass().getSimpleName()));
    }

    // ==================== HTTP Client ====================
    //
    // Backed by Helidon Níma WebClient — Loom-native blocking I/O on JDK
    // NIO + SSLEngine. `request.submit(...)` parks the calling virtual
    // thread until the response is back, so the same call works under
    // every Async impl: LoomAsync parks a VT, OSThreadAsync blocks a
    // platform thread, CoopAsync blocks the carrier (see #114-#125 for
    // the CoopAsync-blocking native pattern we use for file I/O too).

    /**
     * HTTP client wrapper. Holds a configured Helidon WebClient. One
     * WebClient is shared across many requests; Helidon's internal
     * connection pool handles reuse.
     */
    public static class HttpClient {
        private final WebClient webClient;
        private volatile boolean closed = false;

        public HttpClient(WebClient webClient) {
            this.webClient = webClient;
        }

        public WebClient getWebClient() {
            return webClient;
        }

        public boolean isClosed() {
            return closed;
        }

        public void close() {
            // Helidon WebClient has no explicit close — its connection
            // pool is reclaimed when the client is GC'd. We just mark
            // closed so subsequent requests return an error.
            closed = true;
        }
    }

    /**
     * Build a client from an `HttpConfig`.
     *
     * Six of `HttpConfig`'s seven fields are client-level in Helidon and used to
     * be ignored here: the builder hardcoded a 10s connect timeout, redirects on,
     * ten of them, and nothing else. A config that is accepted and then dropped
     * is worse than one that is not offered, so all seven are honoured now —
     * `timeout_ms` is the read timeout, applied per request where a request may
     * still override it.
     *
     * `config` is optional. Absent, the defaults match `default_http_config`.
     */
    @TruffleBoundary
    public static Object httpClientCreate(MezzeRecord args) {
        // The WebClient logs through the same `io.helidon` JUL namespace as the
        // server, so the client honors the same level knob — silent by default.
        Object lvlObj = getField(args, "helidon_log_level");
        String level = (lvlObj instanceof String s) ? s : "off";
        HttpServerNatives.configureHelidonLogging(HttpServerNatives.resolveHelidonLevel(level));

        Object configObj = getField(args, "config");
        MezzeRecord config = (configObj instanceof MezzeRecord c) ? c : MezzeRecord.empty();

        var builder = WebClient.builder()
            .connectTimeout(Duration.ofMillis(getIntOrDefault(config, "connect_timeout_ms", 10000)))
            .followRedirects(getBoolOrDefault(config, "follow_redirects", true))
            .maxRedirects(getIntOrDefault(config, "max_redirects", 10))
            .keepAlive(getBoolOrDefault(config, "keep_alive", true));

        if (!getBoolOrDefault(config, "verify_ssl", true)) {
            // Deliberately insecure, and only on request: a self-signed
            // development endpoint is the case this exists for.
            builder.tls(io.helidon.common.tls.Tls.builder().trustAll(true).build());
        }
        if (!getBoolOrDefault(config, "auto_decompress", true)) {
            // Discovered content encoders are what decompress a response body;
            // without them the body arrives exactly as it was sent.
            builder.contentEncoding(io.helidon.http.encoding.ContentEncodingContext.builder()
                .contentEncodingsDiscoverServices(false)
                .build());
        }
        return new HttpClient(builder.build());
    }

    @TruffleBoundary
    public static MezzeRecord httpClientClose(MezzeRecord args) {
        Object clientObj = getField(args, "client");
        if (clientObj instanceof HttpClient client) {
            client.close();
        }
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static Object httpRequest(MezzeRecord args) {
        Object reqObj = getField(args, "req");
        Object configObj = getField(args, "config");
        Object clientObj = getField(args, "client");

        if (!(reqObj instanceof MezzeRecord req)) {
            throw new NativeTypeMismatchException("Expected 'req' to be a record");
        }
        if (!(clientObj instanceof HttpClient httpClient)) {
            throw new NativeTypeMismatchException("Expected 'client' to be an HttpClient");
        }

        String urlString = getString(req, "url");

        if (httpClient.isClosed()) {
            return connectionFailed(urlString, "HTTP client has been closed");
        }

        Object methodField = getField(req, "method");
        MezzeMap headersMap = getMap(req, "headers");
        Object body = getOptionalBody(req);

        MezzeRecord config = (configObj instanceof MezzeRecord c) ? c : MezzeRecord.empty();
        int timeoutMs = getIntOrDefault(config, "timeout_ms", 30000);

        // Per-request timeout override.
        Object reqTimeoutObj = getField(req, "timeout_ms");
        if (reqTimeoutObj instanceof Long l) {
            timeoutMs = l.intValue();
        } else if (reqTimeoutObj instanceof MezzeVariant v && v.isSome()) {
            Object val = v.getPayloadFieldByName("val");
            if (val instanceof Long l) {
                timeoutMs = l.intValue();
            }
        }

        URI uri;
        try {
            uri = URI.create(urlString);
        } catch (IllegalArgumentException e) {
            return invalidUrl(urlString, e.getMessage() != null ? e.getMessage() : "Invalid URL syntax");
        }
        if (uri.getHost() == null) {
            return invalidUrl(urlString, "URL has no host");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            return invalidUrl(urlString, "URL scheme must be http or https");
        }

        Method method = parseMethod(methodField);
        Map<String, String> headers = parseHeaders(headersMap);
        byte[] bodyBytes = (body != null) ? toByteArray(body) : null;

        return executeRequest(httpClient.getWebClient(), method, uri, urlString,
                              bodyBytes, headers, timeoutMs);
    }

    /**
     * Execute an HTTP request via Helidon WebClient. Blocks the calling
     * thread (or VT) until the response or an error is observed.
     */
    private static Object executeRequest(
        WebClient client,
        Method method,
        URI uri,
        String urlString,
        byte[] body,
        Map<String, String> headers,
        int timeoutMs
    ) {
        try {
            HttpClientResponse response = submitOpen(client, method, uri, body, headers, timeoutMs);

            try {
                return createOk(responseToMezze(response, urlString));
            } finally {
                response.close();
            }
        } catch (RuntimeException e) {
            // Helidon raises unchecked exceptions for IO failures and
            // wraps lower-level checked exceptions in the cause chain.
            // Classify by walking the chain and then by message
            // keywords — same pattern as the prior Vert.x path.
            return classifyFailure(urlString, timeoutMs, e);
        } catch (Exception e) {
            return classifyFailure(urlString, timeoutMs, e);
        }
    }

    private static Object classifyFailure(String urlString, int timeoutMs, Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SSLException) {
                return tlsError(urlString, cause.getMessage() != null ? cause.getMessage() : "TLS error");
            }
            if (cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof TimeoutException
                    || cause instanceof java.net.SocketTimeoutException) {
                return timeout(urlString, timeoutMs);
            }
            if (cause instanceof UnknownHostException) {
                return connectionFailed(urlString,
                    "Unknown host: " + (cause.getMessage() != null ? cause.getMessage() : ""));
            }
            if (cause instanceof ConnectException) {
                return connectionFailed(urlString,
                    cause.getMessage() != null ? cause.getMessage() : "Connection refused");
            }
            if (cause instanceof java.nio.channels.ClosedChannelException) {
                return connectionFailed(urlString, "Connection closed");
            }
            cause = cause.getCause();
        }
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        // Locale.ROOT: this classifies an ASCII exception message, so the
        // host locale must not decide which branch a failure takes.
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return timeout(urlString, timeoutMs);
        }
        if (lower.contains("ssl") || lower.contains("tls") || lower.contains("certificate")) {
            return tlsError(urlString, msg);
        }
        if (lower.contains("unknown host") || lower.contains("nodename nor servname")) {
            return connectionFailed(urlString, msg);
        }
        return connectionFailed(urlString, msg);
    }

    /**
     * Build and send one request, handing back the response still OPEN.
     *
     * The caller closes it. `executeRequest` does so in a finally after
     * reading the whole body; the streaming path hands it to guest code and
     * closes when that scope ends.
     */
    private static HttpClientResponse submitOpen(
        WebClient client,
        Method method,
        URI uri,
        byte[] body,
        Map<String, String> headers,
        int timeoutMs
    ) {
        HttpClientRequest request = client.method(method).uri(uri);

        // Apply per-request timeout — Helidon exposes this on the
        // request builder via readTimeout (waiting for the server to
        // respond / write). Connect-timeout is configured client-wide.
        request.readTimeout(Duration.ofMillis(timeoutMs));

        // Headers — Helidon's request.header(name, value) is the
        // simple form. Multi-value headers can be added by calling
        // it repeatedly; the Map<String,String> here is single-valued.
        for (Map.Entry<String, String> e : headers.entrySet()) {
            request.header(io.helidon.http.HeaderNames.create(e.getKey()), e.getValue());
        }

        // submit() / outputStream() pattern: for small bodies, just
        // submit the bytes. For empty body, submit empty.
        return (body != null) ? request.submit(body) : request.request();
    }

    // ==================== Streamed download ====================

    /**
     * An OPEN response whose body is still on the wire, behind the guest's
     * opaque `Download` newtype.
     *
     * It owns the connection, and the client too when it created one. Unlike
     * the server's inbound reader — where Helidon keeps the request content
     * readable on its own account — nothing here closes itself, so the guest
     * scope that opened it has to close it. That is why the public shape is
     * `with_download`, not a bare `open`.
     */
    public static final class MezzeDownload {
        private final HttpClientResponse response;
        private final java.io.InputStream in;
        private final HttpClient ownedClient;
        private final String url;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        /**
         * Set when a read failed part-way. `close` reports it, which is how a
         * TRUNCATED download stops looking like a complete one: the iterator
         * ends either way, so the scope is the only place left to say so.
         */
        private volatile String failure = null;

        MezzeDownload(HttpClientResponse response, java.io.InputStream in,
                      HttpClient ownedClient, String url) {
            this.response = response;
            this.in = in;
            this.ownedClient = ownedClient;
            this.url = url;
        }
    }

    /**
     * Send, and hand back the response with its body unread.
     *
     * `status` and `headers` have arrived by this point — they are what a
     * caller needs before deciding to read the body at all, which is half the
     * reason to stream rather than buffer.
     */
    @TruffleBoundary
    public static Object httpStreamOpen(MezzeRecord args) {
        Object reqObj = getField(args, "req");
        if (!(reqObj instanceof MezzeRecord req)) {
            throw new NativeTypeMismatchException("Expected 'req' to be a Request record");
        }
        Object configObj = getField(args, "config");
        MezzeRecord config = (configObj instanceof MezzeRecord c) ? c : MezzeRecord.empty();

        String urlString = getString(req, "url");
        URI uri;
        try {
            uri = URI.create(urlString);
        } catch (IllegalArgumentException e) {
            return invalidUrl(urlString, "Malformed URL");
        }
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            return invalidUrl(urlString, "URL scheme must be http or https");
        }

        // A client passed in is the caller's to close; one made here is ours,
        // and the handle closes it alongside the connection.
        Object clientObj = getField(args, "client");
        HttpClient passed = (clientObj instanceof HttpClient hc) ? hc : null;
        HttpClient owned = (passed == null)
            ? (HttpClient) httpClientCreate(MezzeRecord.fromFields(
                new String[]{"config"}, new Object[]{config}))
            : null;
        HttpClient client = (passed != null) ? passed : owned;

        // Same precedence as the buffered path: the config's timeout, overridden
        // by the request's own when it carries one.
        int timeoutMs = getIntOrDefault(config, "timeout_ms", 30000);
        Object reqTimeoutObj = getField(req, "timeout_ms");
        if (reqTimeoutObj instanceof Long l) {
            timeoutMs = l.intValue();
        } else if (reqTimeoutObj instanceof MezzeVariant v && v.isSome()) {
            Object val = v.getPayloadFieldByName("val");
            if (val instanceof Long l) {
                timeoutMs = l.intValue();
            }
        }

        Object body = getOptionalBody(req);
        try {
            HttpClientResponse response = submitOpen(
                client.getWebClient(),
                parseMethod(getField(req, "method")),
                uri,
                (body != null) ? toByteArray(body) : null,
                parseHeaders(getMap(req, "headers")),
                timeoutMs);

            Map<Object, Object> headersOut = new HashMap<>();
            // Locale.ROOT — see HttpServerNatives: an ASCII header name must
            // fold with ASCII rules or a Turkish host loses `if-match`.
            response.headers().forEach(h -> headersOut.put(h.name().toLowerCase(java.util.Locale.ROOT), h.get()));

            MezzeDownload download = new MezzeDownload(
                response, response.inputStream(), owned, urlString);
            return createOk(MezzeRecord.fromFields(
                new String[]{"status", "headers", "reader"},
                new Object[]{
                    (long) response.status().code(),
                    MezzeMap.fromJavaMap(headersOut),
                    download,
                }));
        } catch (RuntimeException e) {
            if (owned != null) httpClientClose(MezzeRecord.fromFields(
                new String[]{"client"}, new Object[]{owned}));
            return classifyFailure(urlString, timeoutMs, e);
        } catch (Exception e) {
            if (owned != null) httpClientClose(MezzeRecord.fromFields(
                new String[]{"client"}, new Object[]{owned}));
            return classifyFailure(urlString, timeoutMs, e);
        }
    }

    /**
     * The next chunk of a download, or empty Bytes at the end.
     *
     * A SHORT read is not the end — the socket hands over what has arrived, so
     * a chunk smaller than `max_bytes` is ordinary. Only 0 or -1 ends it.
     *
     * Reading a CLOSED download is an error that names itself, not an empty
     * answer. `lines` settled this shape for files: a read on a closed handle
     * reports FsClosedHandle rather than silently answering nothing.
     */
    @TruffleBoundary
    public static Object httpStreamRead(MezzeRecord args) {
        MezzeDownload d = downloadOf(args);
        long maxBytes = getIntOrDefault(args, "max_bytes", 0);
        if (maxBytes <= 0) {
            throw new NativeTypeMismatchException("'max_bytes' must be positive, got " + maxBytes);
        }
        if (d.closed.get()) {
            return connectionFailed(d.url,
                "this download was already closed — a response body cannot be read "
                    + "outside the `with_download` scope that opened it");
        }
        int cap = (int) Math.min(maxBytes, 1 << 20);
        byte[] buf = new byte[cap];
        try {
            int n = d.in.read(buf, 0, cap);
            if (n <= 0) return createOk(new BytesNatives.MezzeBytes(new byte[0]));
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return createOk(new BytesNatives.MezzeBytes(out));
        } catch (java.io.IOException e) {
            String why = "the response body could not be read: "
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            d.failure = why;
            return connectionFailed(d.url, why);
        } catch (RuntimeException e) {
            // A TRUNCATED transfer, and the reason this arm exists: the peer
            // closing mid-body leaves a chunked response without its terminal
            // zero-length chunk, and Helidon's decoder says so by raising —
            // `DataReader$InsufficientDataAvailableException`, which is a
            // RuntimeException and so sailed straight past the IOException arm
            // above and out of the guest program as a raw Java exception.
            //
            // It must come back as a named failure instead, through the same
            // `d.failure` channel `close` already reports from: a download that
            // died at 40 KB of 200 is EXACTLY what the caller needs told, and
            // the alternative is the shape BUG 28 was about — a short body that
            // looks whole.
            String why = "the response body ended early — the connection closed "
                + "before the body was complete (" + e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : "") + ")";
            d.failure = why;
            return connectionFailed(d.url, why);
        }
    }

    /** Close the connection, and the client when this handle made one. */
    @TruffleBoundary
    public static Object httpStreamClose(MezzeRecord args) {
        MezzeDownload d = downloadOf(args);
        if (d.closed.compareAndSet(false, true)) {
            try {
                d.response.close();
            } catch (RuntimeException ignored) {
                // The peer may already be gone; the handle is spent either way.
            }
            if (d.ownedClient != null) {
                httpClientClose(MezzeRecord.fromFields(
                    new String[]{"client"}, new Object[]{d.ownedClient}));
            }
        }
        // A read that failed part-way is reported HERE, because the iterator
        // cannot report it: a stream's step says Yield or Done, and "the
        // connection died at 40 KB of 200" has to come out as Done. Left at
        // that, a truncated download would be indistinguishable from a
        // complete one — the same trap `response_file` still has in the other
        // direction, where a read error ends the file early and silently.
        String why = d.failure;
        if (why != null) return connectionFailed(d.url, why);
        return createOk(MezzeRecord.empty());
    }

    private static MezzeDownload downloadOf(MezzeRecord args) {
        Object r = getField(args, "reader");
        if (!(r instanceof MezzeDownload d)) {
            throw new NativeTypeMismatchException("Expected 'reader' to be an open download");
        }
        return d;
    }

    /**
     * Convert a Helidon response to a Mezze {status, headers, body, url}
     * record. Header names are lowercased to match the prior contract
     * (case-insensitive lookup via response.get_header is the stdlib
     * pattern). The request URL is threaded in so the stdlib's
     * `ensure_ok` can build a `HttpRequestFailed { url, ... }` on non-2xx.
     */
    private static MezzeRecord responseToMezze(HttpClientResponse response, String url) {
        Map<Object, Object> headersOut = new HashMap<>();
        response.headers().forEach(h ->
            // Locale.ROOT — ASCII header name, ASCII folding.
            headersOut.put(h.name().toLowerCase(java.util.Locale.ROOT), h.get())
        );

        // Helidon: response.as(byte[].class) reads the entire body.
        byte[] bodyBytes;
        try {
            bodyBytes = response.as(byte[].class);
            if (bodyBytes == null) bodyBytes = new byte[0];
        } catch (Exception e) {
            bodyBytes = new byte[0];
        }

        return MezzeRecord.fromFields(
            new String[]{"status", "headers", "body", "url", "chunks"},
            new Object[]{
                (long) response.status().code(),
                MezzeMap.fromJavaMap(headersOut),
                // Response body MUST be the canonical Bytes runtime type
                // (BytesNatives.MezzeBytes) — the one every bytes native
                // produces/consumes. Using the values.MezzeBytes wrapper made
                // `bytes::to_str { b = resp.body }` throw
                // "Expected MezzeBytes for field 'b'".
                new BytesNatives.MezzeBytes(bodyBytes),
                url,
                // A client response is never streamed — the native already has
                // the whole body in hand by the time it builds this.
                MezzeVariant.none()
            }
        );
    }

    // ==================== HTTP Helpers ====================

    private static Method parseMethod(Object methodField) {
        String methodName;
        if (methodField instanceof MezzeVariant mv) {
            methodName = mv.getConstructorName();
        } else if (methodField instanceof String s) {
            methodName = s;
        } else if (methodField instanceof MezzeRecord mr) {
            Object variantName = getField(mr, "$variant");
            methodName = variantName != null ? variantName.toString() : "Get";
        } else {
            methodName = "Get";
        }

        return switch (methodName) {
            case "Get"     -> Method.GET;
            case "Post"    -> Method.POST;
            case "Put"     -> Method.PUT;
            case "Delete"  -> Method.DELETE;
            case "Patch"   -> Method.PATCH;
            case "Head"    -> Method.HEAD;
            case "Options" -> Method.OPTIONS;
            default        -> Method.GET;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseHeaders(MezzeMap headersMap) {
        Map<String, String> result = new HashMap<>();
        if (headersMap != null) {
            for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) headersMap) {
                String key = entry.key() instanceof String s ? s : entry.key().toString();
                String value = entry.value() instanceof String s ? s : entry.value().toString();
                result.put(key, value);
            }
        }
        return result;
    }

    private static Object getOptionalBody(MezzeRecord req) {
        Object bodyOption = getField(req, "body");
        if (bodyOption == null) return null;
        if (bodyOption instanceof MezzeVariant v) {
            if ("Some".equals(v.getConstructorName())) {
                Object payload = v.getPayloadValue();
                if (payload instanceof MezzeRecord pr) {
                    return getField(pr, "val");
                }
                return payload;
            }
            return null;
        }
        return bodyOption;
    }

    private static byte[] toByteArray(Object body) {
        if (body instanceof MezzeBytes mb) {
            byte[] result = new byte[mb.length()];
            System.arraycopy(mb.getData(), mb.getOffset(), result, 0, mb.length());
            return result;
        } else if (body instanceof BytesNatives.MezzeBytes mb) {
            return mb.getData();
        } else if (body instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        } else if (body instanceof byte[] b) {
            return b;
        }
        return new byte[0];
    }

    private static MezzeMap getMap(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeMap m) return m;
        return MezzeMap.empty();
    }

    private static int getIntOrDefault(MezzeRecord args, String fieldName, int defaultValue) {
        Object value = getField(args, fieldName);
        if (value instanceof Long l) return l.intValue();
        if (value instanceof Integer i) return i;
        return defaultValue;
    }

    private static boolean getBoolOrDefault(MezzeRecord args, String fieldName, boolean defaultValue) {
        Object value = getField(args, fieldName);
        if (value instanceof Boolean b) return b;
        return defaultValue;
    }

    // ==================== HTTP Error Helpers ====================

    private static MezzeVariant connectionFailed(String url, String reason) {
        MezzeRecord payload = MezzeRecord.fromFields(
            new String[]{"url", "reason"},
            new Object[]{url, reason}
        );
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("HttpConnectionFailed");
        MezzeVariant errorVariant = MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        return MezzeVariant.err(errorVariant);
    }

    private static MezzeVariant timeout(String url, int ms) {
        MezzeRecord payload = MezzeRecord.fromFields(
            new String[]{"url", "ms"},
            new Object[]{url, (long) ms}
        );
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("HttpTimeout");
        MezzeVariant errorVariant = MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        return MezzeVariant.err(errorVariant);
    }

    private static MezzeVariant invalidUrl(String url, String reason) {
        MezzeRecord payload = MezzeRecord.fromFields(
            new String[]{"url", "reason"},
            new Object[]{url, reason}
        );
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("HttpInvalidUrl");
        MezzeVariant errorVariant = MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        return MezzeVariant.err(errorVariant);
    }

    private static MezzeVariant tlsError(String url, String reason) {
        MezzeRecord payload = MezzeRecord.fromFields(
            new String[]{"url", "reason"},
            new Object[]{url, reason}
        );
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("HttpTlsError");
        MezzeVariant errorVariant = MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        return MezzeVariant.err(errorVariant);
    }

}
