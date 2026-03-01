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
 * Monomorphic call node for module-qualified function calls.
 *
 * This is used when calling a function known by name at compile time
 * (e.g., bytes::from_string). The function is looked up ONCE from globals,
 * then cached for subsequent calls.
 *
 * AGGRESSIVE OPTIMIZATIONS:
 * 1. Single global lookup (first execution only)
 * 2. Cached DirectCallNode for subsequent calls
 * 3. No function object allocation per call
 * 4. Closure values are captured once and reused
 * 5. CACHED CLOSURE LENGTH - allows JIT to constant-fold array size
 * 6. INLINE LOOP instead of System.arraycopy - JIT unrolls small loops better
 * 7. @ExplodeLoop for closure copy - fully unrolled after JIT
 *
 * This is faster than ReadLocalWithFallbackNode + InvokeNode because:
 * - Eliminates frame slot lookup on every call
 * - Eliminates InvokeNode's polymorphic dispatch
 * - Function and CallTarget are cached after first execution
 */
@NodeInfo(shortName = "monomorphic-call")
public final class MonomorphicCallNode extends MezzeNode {

    private final String functionName;

    // Content hash for precise lookup (null = use functionName)
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

    // OPTIMIZATION: Cache closure length for JIT constant-folding
    // After JIT, this becomes a compile-time constant enabling loop unrolling
    @CompilationFinal
    private int cachedClosureLength;

    public MonomorphicCallNode(String functionName, MezzeNode argumentNode) {
        this(functionName, null, argumentNode);
    }

    public MonomorphicCallNode(String functionName, String contentHash, MezzeNode argumentNode) {
        this.functionName = functionName;
        this.contentHash = contentHash;
        this.argumentNode = argumentNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Fast path: already resolved
        if (callNode != null) {
            Object arg = argumentNode.execute(frame);
            return callWithClosure(arg);
        }

        // Slow path: first execution - resolve function
        CompilerDirectives.transferToInterpreterAndInvalidate();
        resolveFunction();

        Object arg = argumentNode.execute(frame);
        return callWithClosure(arg);
    }

    /**
     * OPTIMIZATION: Uses inline loop + @ExplodeLoop instead of System.arraycopy.
     * JIT will fully unroll this loop because cachedClosureLength is @CompilationFinal.
     * For small closures (1-4 values), this is faster than System.arraycopy intrinsic.
     */
    @ExplodeLoop
    private Object callWithClosure(Object arg) {
        // cachedClosureLength is @CompilationFinal, so JIT constant-folds this branch
        if (cachedClosureLength == 0) {
            // No closure - direct call (most common case)
            return callNode.call(new Object[] { arg });
        } else {
            // With closure - prepend closure values using inline loop
            // Array size is constant after JIT - enables scalar replacement
            Object[] args = new Object[cachedClosureLength + 1];

            // OPTIMIZATION: Inline loop unrolled by JIT (faster than System.arraycopy for small N)
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

        // Use content hash for lookup if available, otherwise fall back to function name
        String lookupKey = contentHash != null ? contentHash : functionName;
        Object value = context.lookupGlobal(lookupKey);

        if (value == null) {
            if (contentHash != null) {
                throw new RuntimeException(
                    "Function not found for content hash: " + contentHash +
                    " (function name: " + functionName + ")"
                );
            }
            throw new RuntimeException("Function not found: " + functionName);
        }

        if (!(value instanceof MezzeFunction)) {
            throw new RuntimeException("Not a function: " + functionName + " is " + value.getClass().getSimpleName());
        }

        cachedFunction = (MezzeFunction) value;
        CallTarget target = cachedFunction.getCallTarget();
        callNode = DirectCallNode.create(target);
        cachedClosureValues = cachedFunction.getClosureValues();
        cachedClosureLength = cachedClosureValues.length;

        // Adopt the new call node
        insert(callNode);
    }

    /**
     * Create a monomorphic call node for a module-qualified function.
     */
    public static MonomorphicCallNode create(String functionName, MezzeNode argumentNode) {
        return new MonomorphicCallNode(functionName, null, argumentNode);
    }

    /**
     * Create a monomorphic call node with content hash for precise lookup.
     */
    public static MonomorphicCallNode create(String functionName, String contentHash, MezzeNode argumentNode) {
        return new MonomorphicCallNode(functionName, contentHash, argumentNode);
    }
}
