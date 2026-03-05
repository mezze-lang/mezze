package com.mezze.interpreter.v2.nodes.builtin.io;

import com.mezze.interpreter.v2.values.*;
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
        return MezzeList.fromArray(result);
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

    // ==================== JSON ====================

    /**
     * Serialize a Json variant type to a JSON string.
     * Takes: {json: Json, pretty: Bool}
     * Returns: Result String Error (Ok {val = string} or Err {error = {...}})
     *
     * Json variant type:
     * - JNull
     * - JBool {val: Bool}
     * - JInt {val: Int}
     * - JFloat {val: Float}
     * - JString {val: String}
     * - JArray {items: Array Json}
     * - JObject {fields: Array {key: String, value: Json}}
     */
    @TruffleBoundary
    public static MezzeVariant jsonSerialize(MezzeRecord args) {
        Object jsonValue = getField(args, "json");
        Object prettyObj = getField(args, "pretty");
        boolean pretty = prettyObj instanceof Boolean b && b;

        try {
            String result = serializeJsonVariant(jsonValue, pretty, 0);
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
     * Serialize a Json variant to JSON string.
     */
    private static String serializeJsonVariant(Object value, boolean pretty, int indent) {
        if (value instanceof MezzeVariant variant) {
            String constructor = variant.getConstructorName();
            return switch (constructor) {
                case "JNull" -> "null";
                case "JBool" -> {
                    MezzeRecord payload = (MezzeRecord) variant.getPayloadValue();
                    Boolean b = (Boolean) payload.getField("val");
                    yield b.toString();
                }
                case "JInt" -> {
                    MezzeRecord payload = (MezzeRecord) variant.getPayloadValue();
                    Long l = (Long) payload.getField("val");
                    yield l.toString();
                }
                case "JFloat" -> {
                    MezzeRecord payload = (MezzeRecord) variant.getPayloadValue();
                    Double d = (Double) payload.getField("val");
                    yield d.toString();
                }
                case "JString" -> {
                    MezzeRecord payload = (MezzeRecord) variant.getPayloadValue();
                    String s = (String) payload.getField("val");
                    yield "\"" + escapeJsonString(s) + "\"";
                }
                case "JArray" -> {
                    MezzeRecord payload = (MezzeRecord) variant.getPayloadValue();
                    Object items = payload.getField("items");
                    yield serializeJsonArray(items, pretty, indent);
                }
                case "JObject" -> {
                    MezzeRecord payload = (MezzeRecord) variant.getPayloadValue();
                    Object fields = payload.getField("fields");
                    yield serializeJsonObject(fields, pretty, indent);
                }
                default -> throw new RuntimeException("Unknown Json variant: " + constructor);
            };
        }
        throw new RuntimeException("Expected Json variant, got: " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    /**
     * Serialize JArray items to JSON string.
     */
    private static String serializeJsonArray(Object items, boolean pretty, int indent) {
        Object[] elements = getArrayElements(items);
        if (elements.length == 0) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[");
        if (pretty) {
            sb.append("\n");
        }

        for (int i = 0; i < elements.length; i++) {
            if (i > 0) {
                sb.append(",");
                if (pretty) sb.append("\n");
            }
            if (pretty) {
                sb.append("  ".repeat(indent + 1));
            }
            sb.append(serializeJsonVariant(elements[i], pretty, indent + 1));
        }

        if (pretty) {
            sb.append("\n");
            sb.append("  ".repeat(indent));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Serialize JObject fields to JSON string.
     */
    private static String serializeJsonObject(Object fields, boolean pretty, int indent) {
        Object[] entries = getArrayElements(fields);
        if (entries.length == 0) {
            return "{}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        if (pretty) {
            sb.append("\n");
        }

        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                sb.append(",");
                if (pretty) sb.append("\n");
            }
            if (pretty) {
                sb.append("  ".repeat(indent + 1));
            }

            // Each entry is {key: String, value: Json}
            MezzeRecord entry = (MezzeRecord) entries[i];
            String key = (String) entry.getField("key");
            Object value = entry.getField("value");

            sb.append("\"").append(escapeJsonString(key)).append("\"");
            sb.append(":");
            if (pretty) sb.append(" ");
            sb.append(serializeJsonVariant(value, pretty, indent + 1));
        }

        if (pretty) {
            sb.append("\n");
            sb.append("  ".repeat(indent));
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Get array elements from either MezzeList or Object[] (Array in Mezze).
     */
    private static Object[] getArrayElements(Object arr) {
        if (arr instanceof MezzeList list) {
            return list.toArray();
        } else if (arr instanceof Object[] objArr) {
            return objArr;
        }
        throw new RuntimeException("Expected array, got: " + (arr == null ? "null" : arr.getClass().getSimpleName()));
    }

    @TruffleBoundary
    public static Object jsonParse(MezzeRecord args) {
        String s = getString(args, "s");
        try {
            return parseJson(s.trim());
        } catch (Exception e) {
            return createErr("Parse error: " + e.getMessage());
        }
    }

    private static String toJson(Object value) {
        if (value == null || value instanceof MezzeUnit) {
            return "null";
        } else if (value instanceof Boolean b) {
            return b.toString();
        } else if (value instanceof Long l) {
            return l.toString();
        } else if (value instanceof Double d) {
            return d.toString();
        } else if (value instanceof String s) {
            return "\"" + escapeJsonString(s) + "\"";
        } else if (value instanceof MezzeList list) {
            Object[] store = list.toArray();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < store.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(store[i]));
            }
            sb.append("]");
            return sb.toString();
        } else if (value instanceof MezzeRecord rec) {
            // Check if it's a variant
            int variantIdx = rec.getFieldIndex("$variant");
            if (variantIdx >= 0) {
                String variant = (String) rec.getField(variantIdx);
                // Handle Option type
                if ("None".equals(variant)) return "null";
                if ("Some".equals(variant)) {
                    int valIdx = rec.getFieldIndex("val");
                    if (valIdx >= 0) return toJson(rec.getField(valIdx));
                }
            }

            StringBuilder sb = new StringBuilder("{");
            int fieldCount = rec.getFieldCount();
            boolean first = true;
            for (int i = 0; i < fieldCount; i++) {
                String name = rec.getShape().getFieldName(i);
                if (name.startsWith("$")) continue; // Skip internal fields
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escapeJsonString(name)).append("\":");
                sb.append(toJson(rec.getField(i)));
            }
            sb.append("}");
            return sb.toString();
        } else {
            return "\"" + value.toString() + "\"";
        }
    }

    private static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Object parseJson(String s) {
        if (s.isEmpty()) return createErr("Empty input");
        return new JsonParser(s).parse();
    }

    private static class JsonParser {
        private final String s;
        private int pos = 0;

        JsonParser(String s) { this.s = s; }

        Object parse() {
            skipWhitespace();
            Object result = parseValue();
            skipWhitespace();
            return createOk(result);
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= s.length()) throw new RuntimeException("Unexpected end of input");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || Character.isDigit(c)) yield parseNumber();
                    throw new RuntimeException("Unexpected character: " + c);
                }
            };
        }

        // Create Json variant constructors using global tag registry
        MezzeVariant jNull() {
            int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("JNull");
            return MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag);
        }

        MezzeVariant jBool(boolean val) {
            int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("JBool");
            MezzeRecord payload = MezzeRecord.fromFields(new String[]{"val"}, new Object[]{val});
            return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        }

        MezzeVariant jInt(long val) {
            int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("JInt");
            MezzeRecord payload = MezzeRecord.fromFields(new String[]{"val"}, new Object[]{val});
            return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        }

        MezzeVariant jFloat(double val) {
            int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("JFloat");
            MezzeRecord payload = MezzeRecord.fromFields(new String[]{"val"}, new Object[]{val});
            return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        }

        MezzeVariant jString(String val) {
            int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("JString");
            MezzeRecord payload = MezzeRecord.fromFields(new String[]{"val"}, new Object[]{val});
            return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        }

        MezzeVariant jArray(Object[] items) {
            int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("JArray");
            MezzeRecord payload = MezzeRecord.fromFields(new String[]{"items"}, new Object[]{items});
            return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        }

        MezzeVariant jObject(Object[] fields) {
            int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("JObject");
            MezzeRecord payload = MezzeRecord.fromFields(new String[]{"fields"}, new Object[]{fields});
            return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        }

        MezzeVariant parseObject() {
            expect('{');
            skipWhitespace();
            java.util.List<MezzeRecord> fields = new java.util.ArrayList<>();
            if (peek() != '}') {
                do {
                    skipWhitespace();
                    String key = parseRawString();
                    skipWhitespace();
                    expect(':');
                    Object value = parseValue();
                    // Each field is a record {key: String, value: Json}
                    fields.add(MezzeRecord.fromFields(
                        new String[]{"key", "value"}, new Object[]{key, value}));
                    skipWhitespace();
                } while (match(','));
            }
            expect('}');
            return jObject(fields.toArray());
        }

        MezzeVariant parseArray() {
            expect('[');
            skipWhitespace();
            java.util.List<Object> items = new java.util.ArrayList<>();
            if (peek() != ']') {
                do {
                    items.add(parseValue());
                    skipWhitespace();
                } while (match(','));
            }
            expect(']');
            return jArray(items.toArray());
        }

        // Parse string and return as JString variant
        MezzeVariant parseString() {
            return jString(parseRawString());
        }

        // Parse raw string value (not wrapped in JString)
        String parseRawString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length() && s.charAt(pos) != '"') {
                char c = s.charAt(pos++);
                if (c == '\\' && pos < s.length()) {
                    char next = s.charAt(pos++);
                    switch (next) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 <= s.length()) {
                                String hex = s.substring(pos, pos + 4);
                                sb.append((char) Integer.parseInt(hex, 16));
                                pos += 4;
                            }
                        }
                        default -> sb.append(next);
                    }
                } else {
                    sb.append(c);
                }
            }
            expect('"');
            return sb.toString();
        }

        MezzeVariant parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
                return jFloat(Double.parseDouble(s.substring(start, pos)));
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (peek() == '+' || peek() == '-') pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
                return jFloat(Double.parseDouble(s.substring(start, pos)));
            }
            return jInt(Long.parseLong(s.substring(start, pos)));
        }

        MezzeVariant parseBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return jBool(true);
            } else if (s.startsWith("false", pos)) {
                pos += 5;
                return jBool(false);
            }
            throw new RuntimeException("Expected boolean");
        }

        MezzeVariant parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return jNull();
            }
            throw new RuntimeException("Expected null");
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        void expect(char c) {
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw new RuntimeException("Expected '" + c + "'");
            }
            pos++;
        }

        boolean match(char c) {
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
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
        // Handle both MezzeList and Object[] (array literals)
        Object[] ruleStore = null;
        if (rulesObj instanceof MezzeList rules) {
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
        if (methodsObj instanceof MezzeList methods) {
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
        return MezzeVariant.err(message);
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
        // Handle MezzeSeq (Vavr Vector) - convert to MezzeList
        if (value instanceof MezzeSeq seq) {
            return MezzeList.fromArray(seq.toJavaArray());
        }
        // Handle array literals (Object[]) - convert to MezzeList
        if (value instanceof Object[] arr) {
            return MezzeList.fromArray(arr);
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

    private static Map<String, String> parseHeaders(MezzeMap headersMap) {
        Map<String, String> result = new HashMap<>();
        if (headersMap != null) {
            for (var entry : headersMap.toJavaMap().entrySet()) {
                String key = entry.getKey() instanceof String s ? s : entry.getKey().toString();
                String value = entry.getValue() instanceof String s ? s : entry.getValue().toString();
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
