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
