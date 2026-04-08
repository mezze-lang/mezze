package com.mezze.interpreter.v2.nodes.function;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
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
 *
 * For closure factory targets (isClosureFactory=true): on first call, if the
 * result is a MezzeFunction (closure), re-resolves to call through that closure.
 */
@NodeInfo(shortName = "monomorphic-call")
public final class MonomorphicCallNode extends MezzeNode {

    private final String functionName;

    @CompilationFinal
    private final String contentHash;

    @CompilationFinal
    private final boolean isClosureFactory;

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

    @CompilationFinal
    private boolean closureResolved = false;

    public MonomorphicCallNode(String functionName, MezzeNode argumentNode) {
        this(functionName, null, argumentNode, false);
    }

    public MonomorphicCallNode(String functionName, String contentHash, MezzeNode argumentNode) {
        this(functionName, contentHash, argumentNode, false);
    }

    public MonomorphicCallNode(String functionName, String contentHash, MezzeNode argumentNode, boolean isClosureFactory) {
        this.functionName = functionName;
        this.contentHash = contentHash;
        this.argumentNode = argumentNode;
        this.isClosureFactory = isClosureFactory;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (callNode != null) {
            Object arg = argumentNode.execute(frame);
            Object result = callWithClosure(arg);
            if (isClosureFactory && !closureResolved && result instanceof MezzeFunction closureResult) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                resolveToClosureTarget(closureResult);
                return callWithClosure(arg);
            }
            return result;
        }

        CompilerDirectives.transferToInterpreterAndInvalidate();
        resolveFunction();

        Object arg = argumentNode.execute(frame);
        Object result = callWithClosure(arg);

        if (isClosureFactory && !closureResolved && result instanceof MezzeFunction closureResult) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            resolveToClosureTarget(closureResult);
            return callWithClosure(arg);
        }
        return result;
    }

    @TruffleBoundary
    private void resolveToClosureTarget(MezzeFunction closure) {
        cachedFunction = closure;
        CallTarget target = closure.getCallTarget();
        callNode = DirectCallNode.create(target);
        cachedClosureValues = closure.getClosureValues();
        cachedClosureLength = cachedClosureValues.length;
        closureResolved = true;
        insert(callNode);
        // Cache resolved closure in globals for other callers
        MezzeContext context = MezzeContext.get(this);
        String lookupKey = contentHash != null ? contentHash : functionName;
        context.defineGlobal(lookupKey, closure);
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
            // Try by name if hash lookup failed
            value = context.lookupGlobal(functionName);
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

        // If this is a closure factory, eagerly evaluate it during resolution
        // instead of during execute(). This avoids the bug where execute()
        // re-invokes the inner closure with the same argument (e.g., MezzeUnit
        // from a {} call), which crashes if the inner function expects a record.
        if (isClosureFactory) {
            try {
                Object factoryResult = cachedFunction.getCallTarget().call(
                    new Object[] { com.mezze.interpreter.v2.values.MezzeRecord.empty() }
                );
                if (factoryResult instanceof MezzeFunction innerClosure) {
                    cachedFunction = innerClosure;
                    closureResolved = true;
                    // Cache for other callers
                    context.defineGlobal(lookupKey, innerClosure);
                }
            } catch (Exception e) {
                // Not a closure factory — use the original function
            }
        }

        CallTarget target = cachedFunction.getCallTarget();
        callNode = DirectCallNode.create(target);
        cachedClosureValues = cachedFunction.getClosureValues();
        cachedClosureLength = cachedClosureValues.length;

        insert(callNode);
    }

    public static MonomorphicCallNode create(String functionName, MezzeNode argumentNode) {
        return new MonomorphicCallNode(functionName, null, argumentNode, false);
    }

    public static MonomorphicCallNode create(String functionName, String contentHash, MezzeNode argumentNode) {
        return new MonomorphicCallNode(functionName, contentHash, argumentNode, false);
    }
}
