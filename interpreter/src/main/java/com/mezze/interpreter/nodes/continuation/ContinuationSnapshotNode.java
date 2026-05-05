package com.mezze.interpreter.nodes.continuation;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeUnit;
import com.mezze.interpreter.values.MezzeVariant;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * __native_continuation_snapshot - Serialize a continuation to bytes.
 *
 * Serializes a state machine continuation's captured values and state index.
 * This enables workflow persistence and resumption.
 *
 * Serialization format:
 * - 4 bytes: magic number (0x4D5A434B = "MZCK")
 * - 4 bytes: version
 * - variable: function name (length-prefixed string)
 * - 4 bytes: state index
 * - 4 bytes: number of captured values
 * - variable: captured values (recursive serialization)
 */
@NodeInfo(shortName = "continuation-snapshot")
public final class ContinuationSnapshotNode extends MezzeNode {

    private static final int MAGIC = 0x4D5A434B; // "MZCK"
    private static final int VERSION = 1;
    private static final java.util.concurrent.atomic.AtomicLong SNAPSHOT_COUNTER = new java.util.concurrent.atomic.AtomicLong();

    @Child
    private MezzeNode argumentNode;

    private ContinuationSnapshotNode(MezzeNode argumentNode) {
        this.argumentNode = argumentNode;
    }

    public static ContinuationSnapshotNode create(MezzeNode argumentNode) {
        return new ContinuationSnapshotNode(argumentNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object arg = argumentNode.execute(frame);

        if (!(arg instanceof MezzeRecord record)) {
            throw new RuntimeException("__native_continuation_snapshot expects record argument");
        }

        Object contObj = record.getField("cont");
        if (!(contObj instanceof MezzeFunction func)) {
            throw new RuntimeException("__native_continuation_snapshot expects 'cont' field as function");
        }

        return serialize(func);
    }

    @TruffleBoundary
    private byte[] serialize(MezzeFunction func) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            // Write header
            out.writeInt(MAGIC);
            out.writeInt(VERSION);

            // Generate a unique snapshot key for this function so restore can find it.
            // Lambda functions are all named "lambda" which is not unique, so we
            // generate a unique key and register the function globally under that key.
            String funcName = func.getName();
            String snapshotKey = funcName + "__snapshot_" + SNAPSHOT_COUNTER.incrementAndGet();

            // Register the function globally so ContinuationRestoreNode can find it.
            // Build a synthetic Hash for the snapshot key — the `#` prefix marks it as
            // synthetic (Hash accepts hex + `#`).
            MezzeContext context = MezzeContext.get(this);
            Hash snapshotHash = synthesizeSnapshotHash(snapshotKey);
            context.defineGlobal(snapshotHash, func);

            byte[] nameBytes = snapshotKey.getBytes(StandardCharsets.UTF_8);
            out.writeInt(nameBytes.length);
            out.write(nameBytes);

            // Get captured values
            Object[] captured = func.getClosureValues();

            // Check if first captured value is state index (for state machines)
            int stateIndex = 0;
            int valuesStart = 0;
            if (captured.length > 0 && captured[0] instanceof Long stateVal) {
                stateIndex = stateVal.intValue();
                valuesStart = 1;
            }
            out.writeInt(stateIndex);

            // Write captured values count
            int valueCount = captured.length - valuesStart;
            out.writeInt(valueCount);

            // Serialize each captured value
            for (int i = valuesStart; i < captured.length; i++) {
                serializeValue(out, captured[i]);
            }

            out.flush();
            return baos.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize continuation: " + e.getMessage(), e);
        }
    }

    @TruffleBoundary
    private void serializeValue(DataOutputStream out, Object value) throws IOException {
        if (value == null || value instanceof MezzeUnit) {
            out.writeByte(0); // Unit type
        } else if (value instanceof Long l) {
            out.writeByte(1); // Int type
            out.writeLong(l);
        } else if (value instanceof Double d) {
            out.writeByte(2); // Float type
            out.writeDouble(d);
        } else if (value instanceof Boolean b) {
            out.writeByte(3); // Bool type
            out.writeBoolean(b);
        } else if (value instanceof String s) {
            out.writeByte(4); // String type
            byte[] strBytes = s.getBytes(StandardCharsets.UTF_8);
            out.writeInt(strBytes.length);
            out.write(strBytes);
        } else if (value instanceof MezzeRecord record) {
            out.writeByte(5); // Record type
            int fieldCount = record.getFieldCount();
            out.writeInt(fieldCount);
            for (int i = 0; i < fieldCount; i++) {
                // Write field name - access via shape
                String fieldName = record.getShape().getFieldName(i);
                byte[] fieldBytes = fieldName.getBytes(StandardCharsets.UTF_8);
                out.writeInt(fieldBytes.length);
                out.write(fieldBytes);
                // Write field value
                serializeValue(out, record.getField(i));
            }
        } else if (value instanceof MezzeSeq<?> seq) {
            out.writeByte(6); // Seq type
            Object[] arr = seq.toArray();
            out.writeInt(arr.length);
            for (Object elem : arr) {
                serializeValue(out, elem);
            }
        } else if (value instanceof MezzeList<?> list) {
            out.writeByte(7); // List type
            List<Object> items = new ArrayList<>();
            MezzeList<?> curr = list;
            while (!curr.isEmpty()) {
                items.add(curr.head());
                curr = curr.tail();
            }
            out.writeInt(items.size());
            for (Object elem : items) {
                serializeValue(out, elem);
            }
        } else if (value instanceof MezzeVariant variant) {
            out.writeByte(8); // Variant type
            int tag = variant.getTag();
            String tagName = MezzeVariant.TagRegistry.getGlobal().getConstructorName(tag);
            byte[] tagBytes = tagName.getBytes(StandardCharsets.UTF_8);
            out.writeInt(tagBytes.length);
            out.write(tagBytes);
            serializeValue(out, variant.getPayloadValue());
        } else if (value instanceof byte[] bytes) {
            out.writeByte(9); // Bytes type
            out.writeInt(bytes.length);
            out.write(bytes);
        } else if (value instanceof Character c) {
            out.writeByte(10); // Char type
            out.writeChar(c);
        } else {
            throw new RuntimeException("Cannot serialize value of type: " + value.getClass().getName());
        }
    }

    /**
     * Build a synthetic Hash for a snapshot key. Hash accepts hex chars + `#`,
     * so we hex-encode the key bytes and prefix with `#` to mark it synthetic.
     */
    static Hash synthesizeSnapshotHash(String snapshotKey) {
        byte[] bytes = snapshotKey.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("#");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return new Hash(sb.toString());
    }
}
