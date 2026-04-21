package com.mezze.interpreter.v2.nodes.function;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeUnit;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.list.MezzeList;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * Dynamic ability method dispatch node.
 *
 * Resolves the ability method at runtime by matching the receiver's actual
 * type against baked-in candidates from compile time.
 * The arg already has the receiver injected as "it" by the TypedAstBuilder.
 */
public final class DynamicAbilityCallNode extends MezzeNode {

    private final String methodName;
    private final String[] candidateHashes;
    private final String[] candidateTypeTags;
    @Child private MezzeNode receiverNode;
    @Child private MezzeNode argNode;

    private DynamicAbilityCallNode(String methodName, String[] hashes, String[] typeTags,
                                    MezzeNode receiverNode, MezzeNode argNode) {
        this.methodName = methodName;
        this.candidateHashes = hashes;
        this.candidateTypeTags = typeTags;
        this.receiverNode = receiverNode;
        this.argNode = argNode;
    }

    public static DynamicAbilityCallNode create(String methodName, String[] hashes, String[] typeTags,
                                                 MezzeNode receiverNode, MezzeNode argNode) {
        return new DynamicAbilityCallNode(methodName, hashes, typeTags, receiverNode, argNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object receiver = receiverNode.execute(frame);
        Object arg = argNode.execute(frame);

        // Native codec fast path: decode_repr/encode_repr for record types from/to JSON
        if ("decode_repr".equals(methodName) && receiver instanceof MezzeVariant) {
            Object nativeResult = tryNativeDecode(receiver, arg);
            if (nativeResult != null) return nativeResult;
        }
        if ("encode_repr".equals(methodName) && receiver instanceof MezzeRecord rec) {
            Object nativeResult = tryNativeEncode(rec, arg);
            if (nativeResult != null) return nativeResult;
        }

        String opHash = matchCandidate(receiver);

        if (opHash == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException(
                "No ability method '" + methodName + "' found for " +
                getTypeName(receiver) + " (candidates: " + java.util.Arrays.toString(candidateTypeTags) + ")");
        }

        return callByHash(opHash, arg);
    }

    @CompilerDirectives.TruffleBoundary
    private String matchCandidate(Object receiver) {
        if (receiver == null) {
            // Null receiver — use single candidate if available, else fail
            if (candidateHashes.length == 1) return candidateHashes[0];
            return null;
        }
        String typeName = getTypeName(receiver);
        for (int i = 0; i < candidateTypeTags.length; i++) {
            if (candidateTypeTags[i].equals(typeName)) return candidateHashes[i];
        }
        // For variant receivers, also check if any candidate type tag matches
        // a parent/family type. E.g., Just/Nothing should match "Maybe",
        // since impl Encoder for (Maybe Int) should handle Just {val = 42}.
        if (receiver instanceof MezzeVariant) {
            for (int i = 0; i < candidateTypeTags.length; i++) {
                String tag = candidateTypeTags[i];
                // If the candidate tag looks like a type constructor (starts with uppercase,
                // not matching a specific tag), try it as a match for any variant.
                // This handles parameterized types like Maybe, Either, etc.
                if (!tag.equals("Any") && !tag.equals(typeName) && Character.isUpperCase(tag.charAt(0))) {
                    return candidateHashes[i];
                }
            }
        }
        for (int i = 0; i < candidateTypeTags.length; i++) {
            if (candidateTypeTags[i].equals("Any")) return candidateHashes[i];
        }
        if (candidateHashes.length == 1) return candidateHashes[0];
        // For Record receivers with named type tags (e.g., JsonFmt = {}), the runtime
        // can't distinguish named record types. If all candidates share the same hash,
        // use any one of them.
        if (receiver instanceof MezzeRecord && candidateHashes.length > 0) {
            String firstHash = candidateHashes[0];
            boolean allSame = true;
            for (int i = 1; i < candidateHashes.length; i++) {
                if (!candidateHashes[i].equals(firstHash)) { allSame = false; break; }
            }
            if (allSame) return firstHash;
            // If tags are named record types (uppercase, not "Record"), try the first match
            // since records lose their type name at runtime.
            if ("Record".equals(typeName)) return candidateHashes[0];
        }
        return null;
    }

    @CompilerDirectives.TruffleBoundary
    private String getTypeName(Object value) {
        if (value instanceof Long) return "Int";
        if (value instanceof Double) return "Float";
        if (value instanceof Boolean) return "Bool";
        if (value instanceof String) return "Str";
        if (value instanceof MezzeSeq) return "Seq";
        if (value instanceof MezzeList) return "List";
        if (value instanceof MezzeUnit) return "Unit";
        if (value instanceof MezzeVariant v) {
            String tag = v.getConstructorName();
            if ("Some".equals(tag) || "None".equals(tag)) return "Option";
            if ("Ok".equals(tag) || "Err".equals(tag)) return "Result";
            if ("True".equals(tag) || "False".equals(tag)) return "Bool";
            if ("Cons".equals(tag) || "Nil".equals(tag)) return "List";
            return tag;
        }
        if (value instanceof MezzeRecord) return "Record";
        String className = value.getClass().getSimpleName();
        if (className.contains("Set")) return "Set";
        if (className.contains("Map")) return "Map";
        if (className.contains("Deque")) return "Deque";
        return className;
    }

    /**
     * Try native record decode: Json variant (JObject) -> Mezze record.
     * Returns null if native decode is not applicable (non-JObject, unsupported field types).
     */
    @CompilerDirectives.TruffleBoundary
    private Object tryNativeDecode(Object receiver, Object arg) {
        if (!(receiver instanceof MezzeVariant jv)) return null;
        if (!"JObject".equals(jv.getConstructorName())) return null;

        // JObject payload is a record with "fields" field
        Object payloadValue = jv.getPayloadValue();
        if (!(payloadValue instanceof MezzeRecord payloadRec)) return null;
        Object fieldsObj = payloadRec.getField("fields");
        if (!(fieldsObj instanceof MezzeSeq fieldsSeq)) return null;

        // Extract field names and decode values from the JObject
        int numFields = fieldsSeq.size();
        String[] fieldNames = new String[numFields];
        Object[] fieldValues = new Object[numFields];

        for (int i = 0; i < numFields; i++) {
            Object entry = fieldsSeq.get(i);
            if (!(entry instanceof MezzeRecord entryRec)) return null;
            Object key = entryRec.getField("key");
            if (!(key instanceof String keyStr)) return null;
            fieldNames[i] = keyStr;

            Object jsonValue = entryRec.getField("value");
            Object decoded = decodeJsonPrimitive(jsonValue);
            if (decoded == null) return null; // Unsupported type - fall back to generated impl
            fieldValues[i] = decoded;
        }

        MezzeRecord result = MezzeRecord.fromFields(fieldNames, fieldValues);
        return MezzeVariant.ok(result);
    }

    /**
     * Decode a single Json primitive to a Mezze value.
     * Returns null for unsupported/complex types (arrays, nested objects, etc.)
     */
    @CompilerDirectives.TruffleBoundary
    private static Object decodeJsonPrimitive(Object json) {
        if (!(json instanceof MezzeVariant jv)) return null;
        String tag = jv.getConstructorName();
        Object payloadValue = jv.getPayloadValue();
        if (payloadValue instanceof MezzeRecord rec) {
            return switch (tag) {
                case "JString", "JInt", "JFloat", "JBool" -> rec.getField("val");
                default -> null;
            };
        }
        if ("JNull".equals(tag)) {
            int noneTag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("None");
            return MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), noneTag);
        }
        return null;
    }

    /**
     * Try native record encode: Mezze record -> Json JObject variant.
     * Returns null if native encode is not applicable.
     */
    @CompilerDirectives.TruffleBoundary
    private Object tryNativeEncode(MezzeRecord receiver, Object arg) {
        // Only apply to simple records with primitive fields
        int numFields = receiver.getFieldCount();

        Object[] entries = new Object[numFields];
        for (int i = 0; i < numFields; i++) {
            String key = receiver.getShape().getFieldName(i);
            Object value = receiver.getField(i);
            Object jsonValue = encodeToJsonPrimitive(value);
            if (jsonValue == null) return null; // Unsupported type - fall back
            entries[i] = MezzeRecord.fromFields(new String[]{"key", "value"}, new Object[]{key, jsonValue});
        }

        MezzeSeq fieldsSeq = MezzeSeq.fromArray(entries);
        MezzeRecord payload = MezzeRecord.fromFields(new String[]{"fields"}, new Object[]{fieldsSeq});
        int jObjectTag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("JObject");
        MezzeVariant jObject = MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), jObjectTag, payload);
        return MezzeVariant.ok(jObject);
    }

    /**
     * Encode a Mezze primitive value to a Json variant.
     * Returns null for unsupported types.
     */
    @CompilerDirectives.TruffleBoundary
    private static Object encodeToJsonPrimitive(Object value) {
        var registry = MezzeVariant.TagRegistry.getGlobal();
        if (value instanceof String s) {
            int tag = registry.getOrCreateTag("JString");
            return MezzeVariant.singlePayload(registry, tag,
                MezzeRecord.fromFields(new String[]{"val"}, new Object[]{s}));
        }
        if (value instanceof Long l) {
            int tag = registry.getOrCreateTag("JInt");
            return MezzeVariant.singlePayload(registry, tag,
                MezzeRecord.fromFields(new String[]{"val"}, new Object[]{l}));
        }
        if (value instanceof Double d) {
            int tag = registry.getOrCreateTag("JFloat");
            return MezzeVariant.singlePayload(registry, tag,
                MezzeRecord.fromFields(new String[]{"val"}, new Object[]{d}));
        }
        if (value instanceof Boolean b) {
            int tag = registry.getOrCreateTag("JBool");
            return MezzeVariant.singlePayload(registry, tag,
                MezzeRecord.fromFields(new String[]{"val"}, new Object[]{b}));
        }
        return null;
    }

    @CompilerDirectives.TruffleBoundary
    private Object callByHash(String hash, Object arg) {
        MezzeContext context = MezzeContext.get(this);
        Object function = context.lookupGlobal(hash);

        // Fall back to Language-level cache (same pattern as MonomorphicCallNode).
        // User-defined ability impl ops are registered via language.registerFunctionIR
        // but not eagerly placed in the per-context global map.
        if (function == null) {
            var lang = com.mezze.interpreter.v2.runtime.MezzeLanguage.get(this);
            com.oracle.truffle.api.CallTarget ct = lang.getCachedStdlibCallTarget(hash);
            if (ct != null) {
                function = MezzeFunction.create(ct, hash);
                // Cache in context for subsequent calls
                context.defineGlobal(hash, function);
            }
        }

        if (function instanceof MezzeFunction fn) {
            return fn.getCallTarget().call(arg);
        }
        if (function == null) {
            throw new RuntimeException(
                "Function not found for dynamic dispatch: " + hash + " (method: " + methodName + ")");
        }
        throw new RuntimeException(
            "Ability method '" + methodName + "' resolved to non-function: " + function.getClass().getSimpleName());
    }
}
