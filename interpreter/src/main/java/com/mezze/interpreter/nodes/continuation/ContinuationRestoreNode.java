package com.mezze.interpreter.nodes.continuation;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeUnit;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * __native_continuation_restore - Restore a continuation from serialized bytes.
 *
 * Deserializes a continuation from bytes, looking up the function by name
 * and reconstructing the captured values and state.
 */
@NodeInfo(shortName = "continuation-restore")
public final class ContinuationRestoreNode extends MezzeNode {

    private static final int MAGIC = 0x4D5A434B; // "MZCK"
    private static final int VERSION = 1;

    @Child
    private MezzeNode argumentNode;

    private ContinuationRestoreNode(MezzeNode argumentNode) {
        this.argumentNode = argumentNode;
    }

    public static ContinuationRestoreNode create(MezzeNode argumentNode) {
        return new ContinuationRestoreNode(argumentNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object arg = argumentNode.execute(frame);

        if (!(arg instanceof MezzeRecord record)) {
            throw new RuntimeException("__native_continuation_restore expects record argument");
        }

        Object dataObj = record.getField("data");
        if (!(dataObj instanceof byte[] data)) {
            throw new RuntimeException("__native_continuation_restore expects 'data' field as Bytes");
        }

        // Get the functions registry - currently unused but available for future use
        Object functionsObj = record.getField("functions");

        return restore(data);
    }

    @TruffleBoundary
    private MezzeFunction restore(byte[] data) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream in = new DataInputStream(bais);

            // Read and verify header
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new RuntimeException("Invalid snapshot: bad magic number");
            }

            int version = in.readInt();
            if (version != VERSION) {
                throw new RuntimeException("Unsupported snapshot version: " + version);
            }

            // Read function name
            int nameLen = in.readInt();
            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            String funcName = new String(nameBytes, StandardCharsets.UTF_8);

            // Read state index
            int stateIndex = in.readInt();

            // Read captured values count
            int valueCount = in.readInt();

            // Deserialize captured values
            Object[] capturedValues = new Object[valueCount + 1]; // +1 for state index
            capturedValues[0] = (long) stateIndex;
            for (int i = 0; i < valueCount; i++) {
                capturedValues[i + 1] = deserializeValue(in);
            }

            // Look up the function via synthetic snapshot hash (mirrors ContinuationSnapshotNode.synthesizeSnapshotHash).
            MezzeContext context = MezzeContext.get(this);
            Object funcObj = context.lookupGlobal(ContinuationSnapshotNode.synthesizeSnapshotHash(funcName));

            if (!(funcObj instanceof MezzeFunction originalFunc)) {
                throw new RuntimeException("Cannot restore continuation: function '" + funcName + "' not found");
            }

            // Create new function with restored captured values
            return new MezzeFunction(
                originalFunc.getCallTarget(),
                funcName,
                capturedValues
            );

        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize continuation: " + e.getMessage(), e);
        }
    }

    @TruffleBoundary
    private Object deserializeValue(DataInputStream in) throws IOException {
        byte type = in.readByte();

        return switch (type) {
            case 0 -> MezzeUnit.INSTANCE; // Unit
            case 1 -> in.readLong(); // Int (Long)
            case 2 -> in.readDouble(); // Float (Double)
            case 3 -> in.readBoolean(); // Bool
            case 4 -> { // String
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                yield new String(bytes, StandardCharsets.UTF_8);
            }
            case 5 -> { // Record
                int fieldCount = in.readInt();
                String[] fieldNames = new String[fieldCount];
                Object[] fieldValues = new Object[fieldCount];
                for (int i = 0; i < fieldCount; i++) {
                    int nameLen = in.readInt();
                    byte[] nameBytes = new byte[nameLen];
                    in.readFully(nameBytes);
                    fieldNames[i] = new String(nameBytes, StandardCharsets.UTF_8);
                    fieldValues[i] = deserializeValue(in);
                }
                yield MezzeRecord.fromFields(fieldNames, fieldValues);
            }
            case 6 -> { // Seq
                int len = in.readInt();
                Object[] elements = new Object[len];
                for (int i = 0; i < len; i++) {
                    elements[i] = deserializeValue(in);
                }
                yield MezzeSeq.fromArray(elements);
            }
            case 7 -> { // List
                int len = in.readInt();
                Object[] elements = new Object[len];
                for (int i = 0; i < len; i++) {
                    elements[i] = deserializeValue(in);
                }
                // Build list from end to start using static cons
                MezzeList<Object> list = MezzeList.empty();
                for (int i = len - 1; i >= 0; i--) {
                    list = MezzeList.cons(elements[i], list);
                }
                yield list;
            }
            case 8 -> { // Variant
                int tagLen = in.readInt();
                byte[] tagBytes = new byte[tagLen];
                in.readFully(tagBytes);
                String tagName = new String(tagBytes, StandardCharsets.UTF_8);
                Object value = deserializeValue(in);
                int tagId = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag(tagName);
                yield MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tagId, value);
            }
            case 9 -> { // Bytes
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                yield bytes;
            }
            case 10 -> in.readChar(); // Char
            default -> throw new RuntimeException("Unknown serialized type: " + type);
        };
    }
}
