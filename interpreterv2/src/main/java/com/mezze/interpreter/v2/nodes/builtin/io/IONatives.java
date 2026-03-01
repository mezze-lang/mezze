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
    public static MezzeList bufferToBytes(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        byte[] data = buf.getData();
        Object[] result = new Object[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (long) (data[i] & 0xFF);
        }
        return MezzeList.fromObjects(result);
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
    public static MezzeRecord bufferSetPosition(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        long pos = getLong(args, "pos");
        buf.setPosition((int) pos);
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeRecord bufferWriteByte(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        long b = getLong(args, "byte");
        buf.writeByte((int) b);
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static long bufferReadByte(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        return buf.readByte();
    }

    @TruffleBoundary
    public static MezzeRecord bufferWriteBytes(MezzeRecord args) {
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
        return MezzeRecord.empty();
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
    public static MezzeRecord bufferClear(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        Arrays.fill(buf.getData(), (byte) 0);
        buf.setPosition(0);
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeRecord bufferWriteString(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        String s = getString(args, "s");
        buf.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeRecord bufferWrite(MezzeRecord args) {
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
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static boolean bufferIsEmpty(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        return buf.getCapacity() == 0;
    }

    @TruffleBoundary
    public static long bufferSize(MezzeRecord args) {
        MezzeBuffer buf = getBuffer(args, "buf");
        return buf.getCapacity();
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

    @TruffleBoundary
    public static String jsonSerialize(MezzeRecord args) {
        Object value = getField(args, "value");
        return toJson(value);
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

        MezzeRecord parseObject() {
            expect('{');
            skipWhitespace();
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.List<Object> values = new java.util.ArrayList<>();
            if (peek() != '}') {
                do {
                    skipWhitespace();
                    String key = (String) parseString();
                    skipWhitespace();
                    expect(':');
                    Object value = parseValue();
                    names.add(key);
                    values.add(value);
                    skipWhitespace();
                } while (match(','));
            }
            expect('}');
            return MezzeRecord.fromFields(names.toArray(new String[0]), values.toArray());
        }

        MezzeList parseArray() {
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
            return MezzeList.fromObjects(items.toArray());
        }

        Object parseString() {
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

        Object parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
                return Double.parseDouble(s.substring(start, pos));
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (peek() == '+' || peek() == '-') pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
                return Double.parseDouble(s.substring(start, pos));
            }
            return Long.parseLong(s.substring(start, pos));
        }

        Object parseBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return true;
            } else if (s.startsWith("false", pos)) {
                pos += 5;
                return false;
            }
            throw new RuntimeException("Expected boolean");
        }

        Object parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return MezzeUnit.INSTANCE;
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
        String method = getString(args, "method");
        String url = getString(args, "url");

        // Check if there's a mock response
        String key = method + ":" + url;
        Object mockResponse = mockResponses.get(key);
        if (mockResponse != null) {
            return createOk(mockResponse);
        }

        // Return a default mock response
        return createOk(MezzeRecord.fromFields(
            new String[]{"status", "body", "headers"},
            new Object[]{200L, "{}", MezzeRecord.empty()}
        ));
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

    private static Object createOk(Object value) {
        return MezzeRecord.fromFields(new String[]{"$variant", "val"}, new Object[]{"Ok", value});
    }

    private static Object createErr(String message) {
        return MezzeRecord.fromFields(new String[]{"$variant", "err"}, new Object[]{"Err", message});
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
        throw new RuntimeException("Expected List for field '" + fieldName + "'");
    }

}
