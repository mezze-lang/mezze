package com.mezze.interpreter.v2.nodes.builtin.io;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
        Object[] store = bytes.getObjectStore();
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
        Object[] store = bytes.getObjectStore();
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
        return MezzeList.fromObjects(result);
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
            Object[] store = list.getObjectStore();
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
            return list.getObjectStore();
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
            Object[] store = list.getObjectStore();
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
        if (rulesObj instanceof MezzeList rules) {
            Object[] ruleStore = rules.getObjectStore();
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
        if (methodsObj instanceof MezzeList methods) {
            Object[] methodStore = methods.getObjectStore();
            if (methodStore.length > 0) {
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
            // Empty methods list means match any method
        }

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
     * Simple glob matching: * matches any chars, ** matches any path segments
     */
    private static boolean globMatches(String pattern, String url) {
        if (pattern.equals("**") || pattern.equals("*")) {
            return true;
        }
        // Convert glob to regex
        String regex = pattern
            .replace(".", "\\.")
            .replace("**", ".*")
            .replace("*", "[^/]*")
            .replace("?", ".");
        return url.matches(regex);
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
        // Handle array literals (Object[]) - convert to MezzeList
        if (value instanceof Object[] arr) {
            return MezzeList.fromObjects(arr);
        }
        throw new RuntimeException("Expected List for field '" + fieldName + "'");
    }

}
