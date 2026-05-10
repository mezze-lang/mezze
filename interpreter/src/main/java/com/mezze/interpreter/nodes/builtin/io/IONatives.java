package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import javax.net.ssl.SSLException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Native implementations for I/O primitives.
 *
 * Includes:
 * - Buffer (byte buffer operations)
 * - JSON (serialization/parsing)
 * - HTTP Mock (for testing)
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
    public static MezzeBuffer bufferClear(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        Arrays.fill(buf.getData(), (byte) 0);
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

        // Handle both BytesNatives.MezzeBytes (V2 native) and MezzeBytes (value wrapper)
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
            throw new RuntimeException("Expected MezzeBytes or List for field 'bytes', got: " +
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
        throw new RuntimeException("Expected Buffer for field '" + fieldName + "'");
    }

    // ==================== JSON (using DSL-JSON) ====================

    /**
     * Serialize a Json variant type to a JSON string.
     * Takes: {json: Json, pretty: Bool}
     * Returns: Result String Error (Ok {val = string} or Err {error = {...}})
     *
     * Uses DSL-JSON for high-performance byte-level serialization.
     */
    @TruffleBoundary
    public static MezzeVariant jsonSerialize(MezzeRecord args) {
        Object jsonValue = getField(args, "json");
        Object prettyObj = getField(args, "pretty");
        boolean pretty = prettyObj instanceof Boolean b && b;

        try {
            if (!(jsonValue instanceof MezzeVariant json)) {
                throw new RuntimeException("Expected Json variant");
            }
            String result = DslJsonCodec.serializeToString(json, pretty);
            return MezzeVariant.ok(result);
        } catch (Exception e) {
            MezzeRecord error = MezzeRecord.fromFields(
                new String[]{"path", "message"},
                new Object[]{MezzeList.empty(), "JSON serialize error: " + e.getMessage()}
            );
            return MezzeVariant.err(error);
        }
    }

    /**
     * Serialize a Json variant to bytes.
     * Takes: {json: Json, pretty: Bool}
     * Returns: Result Bytes Error
     *
     * Uses DSL-JSON for high-performance byte-level serialization.
     */
    @TruffleBoundary
    public static MezzeVariant jsonSerializeBytes(MezzeRecord args) {
        Object jsonValue = getField(args, "json");
        Object prettyObj = getField(args, "pretty");
        boolean pretty = prettyObj instanceof Boolean b && b;

        try {
            if (!(jsonValue instanceof MezzeVariant json)) {
                throw new RuntimeException("Expected Json variant");
            }
            byte[] result = DslJsonCodec.serializeToBytes(json, pretty);
            return MezzeVariant.ok(new BytesNatives.MezzeBytes(result));
        } catch (Exception e) {
            MezzeRecord error = MezzeRecord.fromFields(
                new String[]{"path", "message"},
                new Object[]{MezzeList.empty(), "JSON serialize error: " + e.getMessage()}
            );
            return MezzeVariant.err(error);
        }
    }

    /**
     * Parse JSON from string.
     * Takes: {s: String}
     * Returns: Result Json Error
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
            return createErr("JSON parse error: " + e.getMessage());
        }
    }

    /**
     * Parse JSON from bytes.
     * Takes: {bytes: Bytes}
     * Returns: Result Json Error
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
            return createErr("Expected Bytes for field 'bytes'");
        }

        try {
            MezzeVariant result = DslJsonCodec.parseBytes(bytes);
            return createOk(result);
        } catch (Exception e) {
            return createErr("JSON parse error: " + e.getMessage());
        }
    }

    // ==================== HTTP Mock ====================

    private static final Map<String, Object> mockResponses = new ConcurrentHashMap<>();

    @TruffleBoundary
    public static Object httpMockRequest(MezzeRecord args) {
        // Args: { req = { method, url, ... }, rules = [...] }
        Object reqObj = getField(args, "req");
        Object rulesObj = getField(args, "rules");

        if (!(reqObj instanceof MezzeRecord req)) {
            // Fallback - try direct access for legacy API
            String method = getStringOrNull(args, "method");
            String url = getStringOrNull(args, "url");
            if (method != null && url != null) {
                return httpMockRequestByMethodUrl(method, url);
            }
            throw new RuntimeException("Expected 'req' field to be a record");
        }

        // Extract method and url from the request record
        Object methodField = getField(req, "method");
        String url = getString(req, "url");

        // Method can be a variant (Get, Post, etc.) - extract the constructor name
        String method;
        if (methodField instanceof MezzeVariant mv) {
            method = mv.getConstructorName();
        } else if (methodField instanceof String s) {
            method = s;
        } else if (methodField instanceof MezzeRecord methodRec) {
            // It could be a variant-as-record like { $variant = "Get" }
            Object variantName = getField(methodRec, "$variant");
            method = variantName != null ? variantName.toString() : "GET";
        } else {
            method = "GET";
        }

        // Process rules to find matching mock
        // Handle MezzeSeq, MezzeList, and Object[] (array literals)
        Object[] ruleStore = null;
        if (rulesObj instanceof MezzeSeq seq) {
            ruleStore = seq.toArray();
        } else if (rulesObj instanceof MezzeList rules) {
            ruleStore = rules.toArray();
        } else if (rulesObj instanceof Object[] arr) {
            ruleStore = arr;
        }

        if (ruleStore != null) {
            for (Object ruleObj : ruleStore) {
                if (ruleObj instanceof MezzeRecord rule) {
                    Object response = matchMockRule(rule, method, url);
                    if (response != null) {
                        return response;
                    }
                }
            }
        }

        // No matching rule - return a connection failed error
        MezzeRecord errorRecord = MezzeRecord.fromFields(
            new String[]{"url", "reason"},
            new Object[]{url, "No mock rule matched for " + method + " " + url}
        );
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("ConnectionFailed");
        MezzeVariant error = MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, errorRecord);
        return MezzeVariant.err(error);
    }

    private static Object httpMockRequestByMethodUrl(String method, String url) {
        String key = method + ":" + url;
        Object mockResponse = mockResponses.get(key);
        if (mockResponse != null) {
            return createOk(mockResponse);
        }
        return createOk(MezzeRecord.fromFields(
            new String[]{"status", "body", "headers"},
            new Object[]{200L, "{}", MezzeRecord.empty()}
        ));
    }

    /**
     * Match a mock rule against method and URL.
     * Rule format: { methods = [Get, Post, ...], url = Glob { pattern = "..." } | Exact { url = "..." }, response = ... }
     */
    private static Object matchMockRule(MezzeRecord rule, String method, String url) {
        // Check methods
        Object methodsObj = getField(rule, "methods");
        Object[] methodStore = null;
        if (methodsObj instanceof MezzeSeq seq) {
            methodStore = seq.toArray();
        } else if (methodsObj instanceof MezzeList methods) {
            methodStore = methods.toArray();
        } else if (methodsObj instanceof Object[] arr) {
            methodStore = arr;
        }

        if (methodStore != null && methodStore.length > 0) {
            boolean methodMatches = false;
            for (Object m : methodStore) {
                String mName;
                if (m instanceof MezzeVariant mv) {
                    mName = mv.getConstructorName();
                } else if (m instanceof MezzeRecord mr) {
                    Object variantName = getField(mr, "$variant");
                    mName = variantName != null ? variantName.toString() : "";
                } else {
                    mName = m.toString();
                }
                if (mName.equalsIgnoreCase(method)) {
                    methodMatches = true;
                    break;
                }
            }
            if (!methodMatches) {
                return null;
            }
        }
        // Empty or null methods list means match any method

        // Check URL pattern
        Object urlPattern = getField(rule, "url");
        if (urlPattern instanceof MezzeVariant urlVar) {
            String patternType = urlVar.getConstructorName();
            Object payload = urlVar.hasPayload() ? urlVar.getPayloadValue() : null;
            if (payload instanceof MezzeRecord payloadRec) {
                if ("Glob".equals(patternType)) {
                    String pattern = getStringOrNull(payloadRec, "pattern");
                    if (pattern != null && !globMatches(pattern, url)) {
                        return null;
                    }
                } else if ("Exact".equals(patternType)) {
                    String exactUrl = getStringOrNull(payloadRec, "url");
                    if (exactUrl != null && !exactUrl.equals(url)) {
                        return null;
                    }
                }
            }
        } else if (urlPattern instanceof MezzeRecord urlRec) {
            // Handle variant-as-record format
            Object variantName = getField(urlRec, "$variant");
            if ("Glob".equals(variantName)) {
                String pattern = getStringOrNull(urlRec, "pattern");
                if (pattern != null && !globMatches(pattern, url)) {
                    return null;
                }
            } else if ("Exact".equals(variantName)) {
                String exactUrl = getStringOrNull(urlRec, "url");
                if (exactUrl != null && !exactUrl.equals(url)) {
                    return null;
                }
            }
        }

        // Rule matches - return the response
        Object response = getField(rule, "response");
        return response;
    }

    /**
     * Simple glob matching: * matches any chars, ** matches any path segments (including zero)
     * Pattern is matched against the URL path (after stripping scheme and host)
     */
    private static boolean globMatches(String pattern, String url) {
        if (pattern.equals("**") || pattern.equals("*")) {
            return true;
        }

        // Extract path from URL for matching
        String urlPath = extractPath(url);

        // Convert glob to regex
        // Handle /**/  specially - it should match zero or more path segments
        // First, handle the /**/  case by replacing it with a special marker
        String processed = pattern
            .replace("/**/", "\u0000DOUBLE_STAR_SLASH\u0000")
            .replace("/**", "\u0000DOUBLE_STAR_END\u0000")
            .replace("**/", "\u0000DOUBLE_STAR_START\u0000");

        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < processed.length()) {
            if (processed.startsWith("\u0000DOUBLE_STAR_SLASH\u0000", i)) {
                // /**/  - matches "" or "/x" or "/x/y" etc.
                regex.append("(?:/[^/]*)*");
                i += "\u0000DOUBLE_STAR_SLASH\u0000".length();
            } else if (processed.startsWith("\u0000DOUBLE_STAR_END\u0000", i)) {
                // /** at end - matches anything
                regex.append("(?:/.*)?");
                i += "\u0000DOUBLE_STAR_END\u0000".length();
            } else if (processed.startsWith("\u0000DOUBLE_STAR_START\u0000", i)) {
                // **/ at start - matches anything followed by /
                regex.append("(?:.*/)?");
                i += "\u0000DOUBLE_STAR_START\u0000".length();
            } else {
                char c = processed.charAt(i);
                if (c == '*') {
                    // Single * matches any chars except /
                    regex.append("[^/]*");
                } else if (c == '?') {
                    regex.append(".");
                } else if (".[](){}+^$|\\".indexOf(c) >= 0) {
                    // Escape regex special characters
                    regex.append("\\").append(c);
                } else {
                    regex.append(c);
                }
                i++;
            }
        }

        // Match against the path portion of the URL
        return urlPath.matches(regex.toString());
    }

    /**
     * Extract the path portion from a URL.
     * "http://example.com/foo/bar" -> "/foo/bar"
     * "/foo/bar" -> "/foo/bar"
     */
    private static String extractPath(String url) {
        // If it doesn't look like a full URL, return as-is
        if (!url.contains("://")) {
            return url;
        }
        // Find the path after the host
        int schemeEnd = url.indexOf("://");
        int pathStart = url.indexOf('/', schemeEnd + 3);
        if (pathStart == -1) {
            return "/";  // URL like "http://example.com" has root path
        }
        // Return everything from the path onwards (may include query string)
        int queryStart = url.indexOf('?', pathStart);
        if (queryStart == -1) {
            return url.substring(pathStart);
        }
        return url.substring(pathStart, queryStart);
    }

    private static String getStringOrNull(MezzeRecord r, String name) {
        Object value = getField(r, name);
        if (value instanceof String s) return s;
        return null;
    }

    @TruffleBoundary
    public static MezzeRecord httpMockSetResponse(MezzeRecord args) {
        String method = getString(args, "method");
        String url = getString(args, "url");
        Object response = getField(args, "response");
        mockResponses.put(method + ":" + url, response);
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeRecord httpMockClear(MezzeRecord args) {
        mockResponses.clear();
        return MezzeRecord.empty();
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

    private static Object getField(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) return null;
        return r.getField(idx);
    }

    private static long getLong(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        throw new RuntimeException("Expected Long for field '" + fieldName + "'");
    }

    private static String getString(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof String s) return s;
        throw new RuntimeException("Expected String for field '" + fieldName + "'");
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
        throw new RuntimeException("Expected List for field '" + fieldName + "', got: " +
            (value == null ? "null" : value.getClass().getSimpleName()));
    }

    // ==================== HTTP Client ====================

    /**
     * Shared Vert.x instance for HTTP clients.
     * Uses daemon threads so JVM can exit when main program completes.
     */
    private static volatile Vertx vertx;

    private static Vertx getVertx() {
        if (vertx == null) {
            synchronized (IONatives.class) {
                if (vertx == null) {
                    VertxOptions options = new VertxOptions().setUseDaemonThread(true);
                    vertx = Vertx.vertx(options);
                }
            }
        }
        return vertx;
    }

    /**
     * HTTP client wrapper using Vert.x WebClient.
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
            if (!closed) {
                closed = true;
                try {
                    webClient.close();
                } catch (Exception e) {
                    // Ignore close errors
                }
            }
        }
    }

    @TruffleBoundary
    public static Object httpClientCreate(MezzeRecord args) {
        // Create a new HTTP client with sensible defaults
        WebClientOptions options = new WebClientOptions()
            .setConnectTimeout(30000)
            .setIdleTimeout(60)
            .setFollowRedirects(true)
            .setMaxRedirects(10)
            .setTrustAll(false)
            .setVerifyHost(true)
            .setKeepAlive(true)
            .setDecompressionSupported(true);

        WebClient webClient = WebClient.create(getVertx(), options);
        return new HttpClient(webClient);
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
        // Extract arguments
        Object reqObj = getField(args, "req");
        Object configObj = getField(args, "config");
        Object clientObj = getField(args, "client");

        if (!(reqObj instanceof MezzeRecord req)) {
            throw new RuntimeException("Expected 'req' to be a record");
        }

        if (!(clientObj instanceof HttpClient httpClient)) {
            throw new RuntimeException("Expected 'client' to be an HttpClient");
        }

        if (httpClient.isClosed()) {
            // Client was closed - return error
            String url = getString(req, "url");
            return connectionFailed(url, "HTTP client has been closed");
        }

        // Parse the request
        Object methodField = getField(req, "method");
        String urlString = getString(req, "url");
        MezzeMap headersMap = getMap(req, "headers");
        Object body = getOptionalBody(req);

        // Parse config
        MezzeRecord config = (configObj instanceof MezzeRecord c) ? c : MezzeRecord.empty();
        int timeoutMs = getIntOrDefault(config, "timeout_ms", 30000);
        int connectTimeoutMs = getIntOrDefault(config, "connect_timeout_ms", 10000);
        boolean followRedirects = getBoolOrDefault(config, "follow_redirects", true);
        int maxRedirects = getIntOrDefault(config, "max_redirects", 10);
        boolean verifySsl = getBoolOrDefault(config, "verify_ssl", true);
        boolean autoDecompress = getBoolOrDefault(config, "auto_decompress", true);
        boolean keepAlive = getBoolOrDefault(config, "keep_alive", true);

        // Override timeout from request if specified
        Object reqTimeoutObj = getField(req, "timeout_ms");
        if (reqTimeoutObj instanceof Long l) {
            timeoutMs = l.intValue();
        } else if (reqTimeoutObj instanceof MezzeVariant v && "Some".equals(v.getConstructorName())) {
            Object payload = v.getPayloadValue();
            if (payload instanceof MezzeRecord pr) {
                Object val = getField(pr, "val");
                if (val instanceof Long l) {
                    timeoutMs = l.intValue();
                }
            }
        }

        // Parse URL
        ParsedUrl parsed = parseUrl(urlString);
        if (parsed == null) {
            return invalidUrl(urlString, "Invalid URL format");
        }

        HttpMethod method = parseMethod(methodField);
        Map<String, String> headers = parseHeaders(headersMap);

        // Execute the request
        return executeRequest(
            httpClient.getWebClient(),
            method,
            parsed,
            urlString,
            body,
            headers,
            timeoutMs,
            connectTimeoutMs,
            followRedirects,
            maxRedirects,
            verifySsl,
            autoDecompress,
            keepAlive
        );
    }

    /**
     * Execute an HTTP request using the provided WebClient.
     */
    private static Object executeRequest(
        WebClient webClient,
        HttpMethod method,
        ParsedUrl parsed,
        String urlString,
        Object body,
        Map<String, String> headers,
        int timeoutMs,
        int connectTimeoutMs,
        boolean followRedirects,
        int maxRedirects,
        boolean verifySsl,
        boolean autoDecompress,
        boolean keepAlive
    ) {
        CompletableFuture<Object> future = new CompletableFuture<>();

        try {
            // Create request
            var request = parsed.ssl
                ? webClient.request(method, parsed.port, parsed.host, parsed.path).ssl(true)
                : webClient.request(method, parsed.port, parsed.host, parsed.path);

            // Add headers
            headers.forEach(request::putHeader);

            // Set timeout
            request.timeout(timeoutMs);

            // Send request
            var sendFuture = (body != null)
                ? request.sendBuffer(Buffer.buffer(toByteArray(body)))
                : request.send();

            sendFuture
                .onSuccess(response -> {
                    future.complete(createOk(responseToMezze(response)));
                })
                .onFailure(throwable -> {
                    Object error;
                    String msg = throwable.getMessage();
                    if (throwable instanceof TimeoutException ||
                        (msg != null && msg.toLowerCase().contains("timeout"))) {
                        error = timeout(urlString, timeoutMs);
                    } else if (throwable instanceof SSLException ||
                        (msg != null && (msg.toLowerCase().contains("ssl") ||
                                         msg.toLowerCase().contains("tls") ||
                                         msg.toLowerCase().contains("certificate")))) {
                        error = tlsError(urlString, msg != null ? msg : "TLS error");
                    } else if (msg != null && (msg.toLowerCase().contains("connection refused") ||
                                               msg.toLowerCase().contains("no route to host") ||
                                               msg.toLowerCase().contains("network unreachable"))) {
                        error = connectionFailed(urlString, msg);
                    } else {
                        error = connectionFailed(urlString, msg != null ? msg : "Unknown error");
                    }
                    future.complete(error);
                });

            // Wait for result with timeout
            return future.get(timeoutMs + 5000L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return timeout(urlString, timeoutMs);
        } catch (InterruptedException e) {
            return cancelled();
        } catch (Exception e) {
            return connectionFailed(urlString, e.getMessage() != null ? e.getMessage() : "Unknown error");
        }
    }

    /**
     * Convert response to Mezze record.
     */
    private static MezzeRecord responseToMezze(HttpResponse<Buffer> response) {
        // Convert headers to map
        Map<Object, Object> headers = new HashMap<>();
        response.headers().forEach(entry -> {
            headers.put(entry.getKey().toLowerCase(), entry.getValue());
        });

        // Get body bytes
        byte[] bodyBytes = response.body() != null ? response.body().getBytes() : new byte[0];

        return MezzeRecord.fromFields(
            new String[]{"status", "headers", "body"},
            new Object[]{(long) response.statusCode(), MezzeMap.fromJavaMap(headers), MezzeBytes.copyOf(bodyBytes)}
        );
    }

    // ==================== HTTP Helpers ====================

    private static record ParsedUrl(boolean ssl, String host, int port, String path) {}

    private static ParsedUrl parseUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            boolean ssl = "https".equalsIgnoreCase(url.getProtocol());
            int port = url.getPort();
            if (port == -1) {
                port = ssl ? 443 : 80;
            }
            String path = url.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (url.getQuery() != null) {
                path = path + "?" + url.getQuery();
            }
            return new ParsedUrl(ssl, url.getHost(), port, path);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static HttpMethod parseMethod(Object methodField) {
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
            case "Get" -> HttpMethod.GET;
            case "Post" -> HttpMethod.POST;
            case "Put" -> HttpMethod.PUT;
            case "Delete" -> HttpMethod.DELETE;
            case "Patch" -> HttpMethod.PATCH;
            case "Head" -> HttpMethod.HEAD;
            case "Options" -> HttpMethod.OPTIONS;
            default -> HttpMethod.GET;
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
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("ConnectionFailed");
        MezzeVariant errorVariant = MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        return MezzeVariant.err(errorVariant);
    }

    private static MezzeVariant timeout(String url, int ms) {
        MezzeRecord payload = MezzeRecord.fromFields(
            new String[]{"url", "ms"},
            new Object[]{url, (long) ms}
        );
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("Timeout");
        MezzeVariant errorVariant = MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        return MezzeVariant.err(errorVariant);
    }

    private static MezzeVariant invalidUrl(String url, String reason) {
        MezzeRecord payload = MezzeRecord.fromFields(
            new String[]{"url", "reason"},
            new Object[]{url, reason}
        );
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("InvalidUrl");
        MezzeVariant errorVariant = MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        return MezzeVariant.err(errorVariant);
    }

    private static MezzeVariant tlsError(String url, String reason) {
        MezzeRecord payload = MezzeRecord.fromFields(
            new String[]{"url", "reason"},
            new Object[]{url, reason}
        );
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("TlsError");
        MezzeVariant errorVariant = MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        return MezzeVariant.err(errorVariant);
    }

    private static MezzeVariant cancelled() {
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("Cancelled");
        MezzeVariant errorVariant = MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag);
        return MezzeVariant.err(errorVariant);
    }

    /**
     * Shutdown the Vert.x instance (for clean shutdown).
     */
    public static void shutdown() {
        if (vertx != null) {
            try {
                vertx.close();
            } catch (Exception e) {
                // Ignore shutdown errors
            }
        }
    }

}
