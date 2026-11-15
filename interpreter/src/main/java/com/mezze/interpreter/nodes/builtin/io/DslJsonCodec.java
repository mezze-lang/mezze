package com.mezze.interpreter.nodes.builtin.io;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonReader;
import com.dslplatform.json.JsonWriter;
import com.dslplatform.json.NumberConverter;
import com.dslplatform.json.BoolConverter;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * High-performance JSON codec using DSL-JSON library.
 *
 * DSL-JSON operates at byte level with minimal allocations, making it
 * ideal for Truffle JIT optimization and GraalVM native-image.
 *
 * This codec converts between:
 * - byte[]/String <-> Mezze Json variant type (JNull, JBool, JInt, JFloat, JString, JArray, JObject)
 */
public final class DslJsonCodec {

    // Thread-local DslJson instance for reuse (DSL-JSON recommendation)
    private static final ThreadLocal<DslJson<Object>> DSL_JSON = ThreadLocal.withInitial(() -> {
        DslJson.Settings<Object> settings = new DslJson.Settings<>()
            .allowArrayFormat(true)
            .includeServiceLoader();
        return new DslJson<>(settings);
    });

    // Thread-local JsonWriter for serialization (reused for performance)
    private static final ThreadLocal<JsonWriter> WRITER = ThreadLocal.withInitial(() ->
        DSL_JSON.get().newWriter()
    );

    // Pre-allocated field names for variant constructors (avoid repeated allocations)
    // Single field NAMES, for the fused variant constructors. These were
    // `String[]` arrays feeding `MezzeRecord.fromFields`; nothing builds those
    // records any more.
    private static final String VAL_NAME = "val";
    private static final String ITEMS_NAME = "items";
    private static final String FIELDS_NAME = "fields";
    private static final String[] KEY_VALUE_FIELDS = {"key", "value"};

    // Cached tag IDs for Json variants (computed once per tag registry)
    private static volatile int tagJNull = -1;
    private static volatile int tagJBool = -1;
    private static volatile int tagJInt = -1;
    private static volatile int tagJFloat = -1;
    private static volatile int tagJString = -1;
    private static volatile int tagJArray = -1;
    private static volatile int tagJObject = -1;

    private DslJsonCodec() {} // Utility class

    // =========================================================================
    // PARSING (byte[] / String -> Json variant)
    // =========================================================================

    /**
     * Parse JSON from byte array to Mezze Json variant.
     * This is the primary parsing method - works directly on bytes for maximum performance.
     */
    @TruffleBoundary
    public static MezzeVariant parseBytes(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Empty input");
        }

        DslJson<Object> dsl = DSL_JSON.get();
        JsonReader<Object> reader = dsl.newReader(bytes);
        reader.getNextToken(); // Initialize reader
        return parseValue(reader);
    }

    /**
     * Parse JSON from String to Mezze Json variant.
     * Converts to UTF-8 bytes first (DSL-JSON works on bytes).
     */
    @TruffleBoundary
    public static MezzeVariant parseString(String s) throws IOException {
        if (s == null || s.isEmpty()) {
            throw new IOException("Empty input");
        }
        return parseBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parse a single JSON value from the reader.
     */
    private static MezzeVariant parseValue(JsonReader<?> reader) throws IOException {
        switch (reader.last()) {
            case 'n':
                if (!reader.wasNull()) {
                    throw new IOException("Expected null");
                }
                return jNull();

            case 't':
                if (!reader.wasTrue()) {
                    throw new IOException("Expected true");
                }
                return jBool(true);

            case 'f':
                if (!reader.wasFalse()) {
                    throw new IOException("Expected false");
                }
                return jBool(false);

            case '"':
                String str = reader.readString();
                return jString(str);

            case '{':
                return parseObject(reader);

            case '[':
                return parseArray(reader);

            case '-':
            case '0': case '1': case '2': case '3': case '4':
            case '5': case '6': case '7': case '8': case '9':
                return parseNumber(reader);

            default:
                throw new IOException("Unexpected character: " + (char) reader.last());
        }
    }

    /**
     * Parse JSON object to JObject variant.
     */
    private static MezzeVariant parseObject(JsonReader<?> reader) throws IOException {
        if (reader.last() != '{') {
            throw new IOException("Expected '{'");
        }

        List<MezzeRecord> fields = new ArrayList<>();
        byte next = reader.getNextToken();

        if (next == '}') {
            return jObject(fields.toArray());
        }

        // Parse first field
        if (next != '"') {
            throw new IOException("Expected string key");
        }

        while (true) {
            String key = reader.readString();

            if (reader.getNextToken() != ':') {
                throw new IOException("Expected ':'");
            }
            reader.getNextToken(); // Move to value

            MezzeVariant value = parseValue(reader);
            fields.add(MezzeRecord.fromFields(KEY_VALUE_FIELDS, new Object[]{key, value}));

            next = reader.getNextToken();
            if (next == '}') {
                break;
            }
            if (next != ',') {
                throw new IOException("Expected ',' or '}'");
            }
            next = reader.getNextToken(); // Move to next key
            if (next != '"') {
                throw new IOException("Expected string key");
            }
        }

        return jObject(fields.toArray());
    }

    /**
     * Parse JSON array to JArray variant.
     */
    private static MezzeVariant parseArray(JsonReader<?> reader) throws IOException {
        if (reader.last() != '[') {
            throw new IOException("Expected '['");
        }

        List<Object> items = new ArrayList<>();
        byte next = reader.getNextToken();

        if (next == ']') {
            return jArray(items.toArray());
        }

        // Parse first element
        items.add(parseValue(reader));

        while (true) {
            next = reader.getNextToken();
            if (next == ']') {
                break;
            }
            if (next != ',') {
                throw new IOException("Expected ',' or ']'");
            }
            reader.getNextToken(); // Move to next value
            items.add(parseValue(reader));
        }

        return jArray(items.toArray());
    }

    /**
     * Parse JSON number to JInt or JFloat.
     * Uses NumberConverter.deserializeNumber which returns a Number and leaves
     * the reader in a consistent state (last() is NOT the delimiter).
     */
    private static MezzeVariant parseNumber(JsonReader<?> reader) throws IOException {
        Number num = NumberConverter.deserializeNumber(reader);

        if (num instanceof Long l) {
            return jInt(l);
        } else if (num instanceof Double d) {
            // Check if it's actually a whole number
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < Long.MAX_VALUE) {
                return jInt((long) d.doubleValue());
            }
            return jFloat(d);
        } else {
            // BigDecimal or other - convert appropriately
            double d = num.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < Long.MAX_VALUE) {
                return jInt(num.longValue());
            }
            return jFloat(d);
        }
    }

    // =========================================================================
    // SERIALIZATION (Json variant -> byte[] / String)
    // =========================================================================

    /**
     * Serialize Mezze Json variant to byte array.
     * This is the primary serialization method - outputs bytes directly.
     */
    @TruffleBoundary
    public static byte[] serializeToBytes(MezzeVariant json, boolean pretty) throws IOException {
        JsonWriter writer = WRITER.get();
        writer.reset();

        if (pretty) {
            serializeValuePretty(writer, json, 0);
        } else {
            serializeValue(writer, json);
        }

        return writer.toByteArray();
    }

    /**
     * Serialize Mezze Json variant to String.
     */
    @TruffleBoundary
    public static String serializeToString(MezzeVariant json, boolean pretty) throws IOException {
        byte[] bytes = serializeToBytes(json, pretty);
        return new String(bytes, StandardCharsets.UTF_8);
    }


    // ──────────────────────── constructor tags ────────────────────────
    //
    // THE TAG IS THE IDENTITY; THE NAME IS FOR DISPLAY, which `MezzeVariant`
    // says in as many words and then shows how — `SOME_TAG`, `NIL_TAG` and the
    // rest are computed once from the name and compared as ints, "instead of
    // reverse-resolving the tag to a String and comparing spellings".
    //
    // This codec was doing exactly that reverse resolution, once per JSON node:
    // `getConstructorName()` then a switch on the String. And the resolution is
    // not a lookup — for a dynamic registry, which the global one is,
    // `TagRegistry.getConstructorName(tag)` SCANS `nameToTagMap.entrySet()`
    // until it finds the tag. That map holds every constructor name in the
    // program, so a serialize walked all of them per node and its cost grew
    // with code that has nothing to do with json.
    //
    // A tag is a pure function of the name, the same in every registry, so
    // these seven are computed once here and the switch is on an int.
    public static final int TAG_JNULL = tagOf("JNull");
    public static final int TAG_JBOOL = tagOf("JBool");
    public static final int TAG_JINT = tagOf("JInt");
    public static final int TAG_JFLOAT = tagOf("JFloat");
    public static final int TAG_JSTRING = tagOf("JString");
    public static final int TAG_JARRAY = tagOf("JArray");
    public static final int TAG_JOBJECT = tagOf("JObject");

    static int tagOf(String constructor) {
        return com.mezze.language.ast.BuiltinTypes.INSTANCE
            .computeVariantTag(constructor, null);
    }

    /**
     * Serialize a Json variant value (compact mode).
     * Uses DSL-JSON's NumberConverter and BoolConverter for optimal serialization.
     */
    private static void serializeValue(JsonWriter writer, MezzeVariant json) throws IOException {
        int tag = json.getTag();

        if (tag == TAG_JNULL) {
            writer.writeNull();
        } else if (tag == TAG_JBOOL) {
            BoolConverter.serialize((Boolean) json.getPayloadFieldByName("val"), writer);
        } else if (tag == TAG_JINT) {
            NumberConverter.serialize((Long) json.getPayloadFieldByName("val"), writer);
        } else if (tag == TAG_JFLOAT) {
            NumberConverter.serialize((Double) json.getPayloadFieldByName("val"), writer);
        } else if (tag == TAG_JSTRING) {
            writer.writeString((String) json.getPayloadFieldByName("val"));
        } else if (tag == TAG_JARRAY) {
            serializeArray(writer, json.getPayloadFieldByName("items"));
        } else if (tag == TAG_JOBJECT) {
            serializeObject(writer, json.getPayloadFieldByName("fields"));
        } else {
            // The name is resolved only HERE, where the cost does not matter and
            // the reader needs it.
            throw new IOException("Unknown Json variant: " + json.getConstructorName());
        }
    }

    /**
     * Serialize JArray items.
     */
    private static void serializeArray(JsonWriter writer, Object items) throws IOException {
        Object[] elements = getArrayElements(items);
        writer.writeByte((byte) '[');

        for (int i = 0; i < elements.length; i++) {
            if (i > 0) {
                writer.writeByte((byte) ',');
            }
            serializeValue(writer, (MezzeVariant) elements[i]);
        }

        writer.writeByte((byte) ']');
    }

    /**
     * Serialize JObject fields.
     */
    private static void serializeObject(JsonWriter writer, Object fields) throws IOException {
        Object[] entries = getArrayElements(fields);
        writer.writeByte((byte) '{');

        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                writer.writeByte((byte) ',');
            }

            MezzeRecord entry = (MezzeRecord) entries[i];
            String key = (String) entry.getField("key");
            MezzeVariant value = (MezzeVariant) entry.getField("value");

            writer.writeString(key);
            writer.writeByte((byte) ':');
            serializeValue(writer, value);
        }

        writer.writeByte((byte) '}');
    }

    // =========================================================================
    // PRETTY PRINTING
    // =========================================================================

    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INDENT = "  ".getBytes(StandardCharsets.UTF_8);

    /**
     * Serialize with pretty printing.
     */
    private static void serializeValuePretty(JsonWriter writer, MezzeVariant json, int indent) throws IOException {
        int tag = json.getTag();

        if (tag == TAG_JNULL) {
            writer.writeNull();
        } else if (tag == TAG_JBOOL) {
            BoolConverter.serialize((Boolean) json.getPayloadFieldByName("val"), writer);
        } else if (tag == TAG_JINT) {
            NumberConverter.serialize((Long) json.getPayloadFieldByName("val"), writer);
        } else if (tag == TAG_JFLOAT) {
            NumberConverter.serialize((Double) json.getPayloadFieldByName("val"), writer);
        } else if (tag == TAG_JSTRING) {
            writer.writeString((String) json.getPayloadFieldByName("val"));
        } else if (tag == TAG_JARRAY) {
            serializeArrayPretty(writer, json.getPayloadFieldByName("items"), indent);
        } else if (tag == TAG_JOBJECT) {
            serializeObjectPretty(writer, json.getPayloadFieldByName("fields"), indent);
        } else {
            throw new IOException("Unknown Json variant: " + json.getConstructorName());
        }
    }

    private static void serializeArrayPretty(JsonWriter writer, Object items, int indent) throws IOException {
        Object[] elements = getArrayElements(items);

        if (elements.length == 0) {
            writer.writeAscii("[]");
            return;
        }

        writer.writeByte((byte) '[');
        writer.writeAscii(NEWLINE);

        for (int i = 0; i < elements.length; i++) {
            if (i > 0) {
                writer.writeByte((byte) ',');
                writer.writeAscii(NEWLINE);
            }
            writeIndent(writer, indent + 1);
            serializeValuePretty(writer, (MezzeVariant) elements[i], indent + 1);
        }

        writer.writeAscii(NEWLINE);
        writeIndent(writer, indent);
        writer.writeByte((byte) ']');
    }

    private static void serializeObjectPretty(JsonWriter writer, Object fields, int indent) throws IOException {
        Object[] entries = getArrayElements(fields);

        if (entries.length == 0) {
            writer.writeAscii("{}");
            return;
        }

        writer.writeByte((byte) '{');
        writer.writeAscii(NEWLINE);

        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                writer.writeByte((byte) ',');
                writer.writeAscii(NEWLINE);
            }

            MezzeRecord entry = (MezzeRecord) entries[i];
            String key = (String) entry.getField("key");
            MezzeVariant value = (MezzeVariant) entry.getField("value");

            writeIndent(writer, indent + 1);
            writer.writeString(key);
            writer.writeAscii(": ");
            serializeValuePretty(writer, value, indent + 1);
        }

        writer.writeAscii(NEWLINE);
        writeIndent(writer, indent);
        writer.writeByte((byte) '}');
    }

    private static void writeIndent(JsonWriter writer, int level) {
        for (int i = 0; i < level; i++) {
            writer.writeAscii(INDENT);
        }
    }

    // =========================================================================
    // VARIANT CONSTRUCTORS (optimized with cached tags)
    // =========================================================================

    private static MezzeVariant.TagRegistry getTagRegistry() {
        return MezzeVariant.TagRegistry.getGlobal();
    }

    private static int getTagJNull() {
        int tag = tagJNull;
        if (tag == -1) {
            tag = tagJNull = getTagRegistry().getOrCreateTag("JNull");
        }
        return tag;
    }

    private static int getTagJBool() {
        int tag = tagJBool;
        if (tag == -1) {
            tag = tagJBool = getTagRegistry().getOrCreateTag("JBool");
        }
        return tag;
    }

    private static int getTagJInt() {
        int tag = tagJInt;
        if (tag == -1) {
            tag = tagJInt = getTagRegistry().getOrCreateTag("JInt");
        }
        return tag;
    }

    private static int getTagJFloat() {
        int tag = tagJFloat;
        if (tag == -1) {
            tag = tagJFloat = getTagRegistry().getOrCreateTag("JFloat");
        }
        return tag;
    }

    private static int getTagJString() {
        int tag = tagJString;
        if (tag == -1) {
            tag = tagJString = getTagRegistry().getOrCreateTag("JString");
        }
        return tag;
    }

    private static int getTagJArray() {
        int tag = tagJArray;
        if (tag == -1) {
            tag = tagJArray = getTagRegistry().getOrCreateTag("JArray");
        }
        return tag;
    }

    private static int getTagJObject() {
        int tag = tagJObject;
        if (tag == -1) {
            tag = tagJObject = getTagRegistry().getOrCreateTag("JObject");
        }
        return tag;
    }

    private static MezzeVariant jNull() {
        return MezzeVariant.noPayload(getTagRegistry(), getTagJNull());
    }

    // ─────────────────── node construction, FUSED ───────────────────
    //
    // Each of these used to build a MezzeRecord and an `Object[]` to hold one
    // field, then wrap it:
    //
    //     MezzeRecord payload = MezzeRecord.fromFields(VAL_FIELD, new Object[]{val});
    //     MezzeVariant.singlePayload(getTagRegistry(), tag, payload);
    //
    // `MezzeVariant`'s fused constructors allocate none of that, and their own
    // comment says why they exist: "the compiler skips MakeRecord for 1-field
    // variant payloads and stores the raw value directly ... these factories
    // record the original field name so getPayloadValue() can lazily
    // materialize the MezzeRecord wrapper for cold paths". The parser was the
    // one builder of single-field variants not using them.
    //
    // MEASURED AGAINST DSL-JSON PARSING THE SAME DOCUMENT into its own Java
    // Maps and Lists — a reference that allocates too, not a bare scan:
    //
    //     DSL-JSON to java Maps/Lists        5 582 975 ps   11 143 ps/node
    //     Mezze parseBytes to MezzeVariant  11 877 094      23 706
    //
    // so Mezze's construction was 53% of its own parse and the ceiling is
    // 2.13x. Reading the code had suggested single digit percent; the estimate
    // was wrong because `MezzeRecord.fromFields` is not merely an `Object[]` —
    // there is shape handling behind it. Measuring beat estimating by 2x.
    //
    // The typed forms carry the primitive UNBOXED, so `jInt` and `jFloat` also
    // stop boxing a Long and a Double per node.
    //
    // Readers need no change: `getPayloadFieldByName` returns the payload
    // directly for a fused variant, which is why the serializer already reads
    // `ok()` and `some()` — both built fused — without drilling into a record.

    private static MezzeVariant jBool(boolean val) {
        return MezzeVariant.singlePayloadFused(getTagRegistry(), getTagJBool(),
            val, VAL_NAME);
    }

    private static MezzeVariant jInt(long val) {
        return MezzeVariant.singleLongPayloadFused(getTagRegistry(), getTagJInt(),
            val, VAL_NAME);
    }

    private static MezzeVariant jFloat(double val) {
        return MezzeVariant.singleDoublePayloadFused(getTagRegistry(), getTagJFloat(),
            val, VAL_NAME);
    }

    private static MezzeVariant jString(String val) {
        return MezzeVariant.singlePayloadFused(getTagRegistry(), getTagJString(),
            val, VAL_NAME);
    }

    private static MezzeVariant jArray(Object[] items) {
        return MezzeVariant.singlePayloadFused(getTagRegistry(), getTagJArray(),
            items, ITEMS_NAME);
    }

    private static MezzeVariant jObject(Object[] fields) {
        return MezzeVariant.singlePayloadFused(getTagRegistry(), getTagJObject(),
            fields, FIELDS_NAME);
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    /**
     * Get array elements from MezzeSeq, MezzeList, or Object[].
     */
    private static Object[] getArrayElements(Object arr) {
        if (arr instanceof MezzeSeq seq) {
            return seq.toArray();
        } else if (arr instanceof MezzeList list) {
            return list.toArray();
        } else if (arr instanceof Object[] objArr) {
            return objArr;
        }
        throw new IllegalArgumentException("Expected array, got: " +
            (arr == null ? "null" : arr.getClass().getSimpleName()));
    }
}
