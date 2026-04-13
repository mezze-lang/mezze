package com.mezze.interpreter.v2.nodes.function;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.runtime.MezzeLanguage;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Monomorphic call node — calls a function by content hash.
 * Functions are directly callable (no closure factory pattern).
 */
@NodeInfo(shortName = "monomorphic-call")
public final class MonomorphicCallNode extends MezzeNode {

    private final String functionName;

    @CompilationFinal
    private final String contentHash;

    @Child
    private MezzeNode argumentNode;

    @CompilationFinal
    private MezzeFunction cachedFunction;

    @Child
    private DirectCallNode callNode;

    @CompilationFinal(dimensions = 1)
    private Object[] cachedClosureValues;

    @CompilationFinal
    private int cachedClosureLength;

    public MonomorphicCallNode(String functionName, String contentHash, MezzeNode argumentNode) {
        this.functionName = functionName;
        this.contentHash = contentHash;
        this.argumentNode = argumentNode;
    }

    /** Backward compat — ignores isClosureFactory */
    public MonomorphicCallNode(String functionName, String contentHash, MezzeNode argumentNode, boolean ignored) {
        this(functionName, contentHash, argumentNode);
    }

    public MonomorphicCallNode(String functionName, MezzeNode argumentNode) {
        this(functionName, null, argumentNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (callNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            resolveFunction();
        }

        Object arg = argumentNode.execute(frame);
        return callWithClosure(arg);
    }

    @ExplodeLoop
    private Object callWithClosure(Object arg) {
        if (cachedClosureLength == 0) {
            return callNode.call(new Object[] { arg });
        } else {
            Object[] args = new Object[cachedClosureLength + 1];
            for (int i = 0; i < cachedClosureLength; i++) {
                args[i] = cachedClosureValues[i];
            }
            args[cachedClosureLength] = arg;
            return callNode.call(args);
        }
    }

    @TruffleBoundary
    private void resolveFunction() {
        MezzeContext context = MezzeContext.get(this);

        String lookupKey = contentHash != null ? contentHash : functionName;
        Object value = context.lookupGlobal(lookupKey);

        if (value == null && contentHash != null && !contentHash.equals(functionName)) {
            value = context.lookupGlobal(functionName);
        }

        // Fall back to Language-level stdlib cache — these are compiled once, not per-test.
        // This avoids registering all stdlib CallTargets in every test's context.
        if (value == null && contentHash != null) {
            CallTarget stdlibCT = MezzeLanguage.get(this).getCachedStdlibCallTarget(contentHash);
            if (stdlibCT != null) {
                value = MezzeFunction.create(stdlibCT, contentHash);
            }
        }

        if (value == null) {
            throw new RuntimeException(
                "Function not found" + (contentHash != null ? " for content hash: " + contentHash : "") +
                " (function name: " + functionName + ")"
            );
        }

        if (!(value instanceof MezzeFunction)) {
            throw new RuntimeException("Not a function: " + functionName + " is " + value.getClass().getSimpleName());
        }

        cachedFunction = (MezzeFunction) value;

        CallTarget target = cachedFunction.getCallTarget();
        callNode = DirectCallNode.create(target);
        cachedClosureValues = cachedFunction.getClosureValues();
        cachedClosureLength = cachedClosureValues.length;

        insert(callNode);
    }

    public static MonomorphicCallNode create(String functionName, MezzeNode argumentNode) {
        return new MonomorphicCallNode(functionName, null, argumentNode);
    }

    public static MonomorphicCallNode create(String functionName, String contentHash, MezzeNode argumentNode) {
        return new MonomorphicCallNode(functionName, contentHash, argumentNode);
    }
}
