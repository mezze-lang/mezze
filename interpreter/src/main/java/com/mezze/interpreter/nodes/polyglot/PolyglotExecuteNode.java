package com.mezze.interpreter.nodes.polyglot;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Execute a polyglot callable (function, lambda, etc.) - FULLY JIT-COMPILABLE.
 *
 * Uses @CachedLibrary for polymorphic inline caching:
 * - Monomorphic (1 type): Fully inlined, O(1)
 * - Polymorphic (2-3 types): Inline cache with type guards
 * - Megamorphic (4+ types): Falls back to uncached library
 *
 * Example Mezze code:
 *   let sorted_fn = Python.eval { code = "sorted" }
 *   let result = sorted_fn { args = [3, 1, 2] }
 */
@NodeChild("callableNode")
@NodeInfo(shortName = "polyglot-execute")
public abstract class PolyglotExecuteNode extends MezzeNode {

    @Children
    protected final MezzeNode[] argumentNodes;

    @CompilationFinal
    protected final int argumentCount;

    protected PolyglotExecuteNode(MezzeNode[] argumentNodes) {
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
    }

    /**
     * Evaluate arguments - extracted for @ExplodeLoop.
     */
    @ExplodeLoop
    protected Object[] evaluateArguments(VirtualFrame frame) {
        Object[] arguments = new Object[argumentCount];
        for (int i = 0; i < argumentCount; i++) {
            arguments[i] = argumentNodes[i].execute(frame);
        }
        return arguments;
    }

    /**
     * Cached path: inline cache for up to 3 callable types.
     */
    @Specialization(guards = "interop.isExecutable(callable)", limit = "3")
    protected Object doCached(VirtualFrame frame, Object callable,
            @CachedLibrary("callable") InteropLibrary interop) {
        Object[] arguments = evaluateArguments(frame);
        try {
            return interop.execute(callable, arguments);
        } catch (UnsupportedMessageException | ArityException | UnsupportedTypeException e) {
            throw handleError(callable, e);
        }
    }

    /**
     * Megamorphic fallback: uses uncached library.
     */
    @Specialization(replaces = "doCached")
    @ReportPolymorphism.Megamorphic
    protected Object doUncached(VirtualFrame frame, Object callable) {
        Object[] arguments = evaluateArguments(frame);
        InteropLibrary interop = InteropLibrary.getUncached(callable);
        try {
            return interop.execute(callable, arguments);
        } catch (UnsupportedMessageException | ArityException | UnsupportedTypeException e) {
            throw handleError(callable, e);
        }
    }

    @TruffleBoundary
    private RuntimeException handleError(Object callable, Exception e) {
        if (e instanceof ArityException ae) {
            return new RuntimeException(
                "Wrong number of arguments: expected " +
                ae.getExpectedMinArity() + ", got " + ae.getActualArity(), e
            );
        } else if (e instanceof UnsupportedTypeException) {
            return new RuntimeException("Unsupported argument types", e);
        }
        return new RuntimeException("Object is not callable: " + callable, e);
    }

    public static PolyglotExecuteNode create(MezzeNode callableNode, MezzeNode[] argumentNodes) {
        return PolyglotExecuteNodeGen.create(argumentNodes, callableNode);
    }
}
