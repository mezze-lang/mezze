package com.mezze.interpreter.v2.nodes.ability;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * OPTIMIZED resolved ability call node for JIT-friendly codec operations.
 *
 * Performance optimizations:
 * 1. DirectCallNode caching - after first call, dispatch is direct (no lookup)
 * 2. Closure values cached at specialization time - no repeated extraction
 * 3. Pre-allocated argument arrays for common closure sizes (0, 1, 2)
 * 4. @ExplodeLoop for closure copying - JIT unrolls small loops completely
 * 5. NO @TruffleBoundary in hot path - everything inlinable after warmup
 *
 * After JIT compilation, an ability call like `EncodeFormat.encode_int` becomes
 * essentially a direct function call with no dynamic dispatch overhead.
 */
@NodeChild(value = "args", type = MezzeNode.class)
@NodeInfo(shortName = "ability-call")
public abstract class ResolvedAbilityCallNode extends MezzeNode {

    @CompilationFinal
    private final int localSlot;

    @CompilationFinal
    private final String implHash;

    @CompilationFinal
    private final String operationName;

    // Cached closure length for @ExplodeLoop
    @CompilationFinal
    private int cachedClosureLength = -1;

    // Cached operation field index - avoids string lookup after first resolution
    @CompilationFinal
    private int cachedOperationIndex = -1;

    protected ResolvedAbilityCallNode(int localSlot, String implHash, String operationName) {
        this.localSlot = localSlot;
        this.implHash = implHash;
        this.operationName = operationName;
    }

    @Idempotent
    protected final boolean hasLocalSlot() {
        return localSlot >= 0;
    }

    /**
     * FAST PATH: No closure - direct call with single argument.
     * This is the most common case for codec operations.
     * After JIT: becomes a direct method call.
     */
    @SuppressWarnings("truffle-sharing")
    @Specialization(guards = {"hasLocalSlot()", "cachedTarget != null", "closureLength == 0"})
    protected Object callDirectNoClosure(
            VirtualFrame frame,
            Object args,
            @Cached(value = "resolveCallTarget(frame)", allowUncached = true) CallTarget cachedTarget,
            @Cached(value = "resolveClosureLength(frame)") int closureLength,
            @Cached("create(cachedTarget)") @Exclusive DirectCallNode callNode
    ) {
        return callNode.call(args);
    }

    /**
     * FAST PATH: Single closure value - no array allocation needed.
     * Common for lambdas capturing one variable.
     */
    @SuppressWarnings("truffle-sharing")
    @Specialization(guards = {"hasLocalSlot()", "cachedTarget != null", "closureLength == 1"})
    protected Object callDirectOneClosure(
            VirtualFrame frame,
            Object args,
            @Cached(value = "resolveCallTarget(frame)", allowUncached = true) CallTarget cachedTarget,
            @Cached(value = "resolveClosureLength(frame)") int closureLength,
            @Cached(value = "resolveClosureValues(frame)", allowUncached = true, dimensions = 1) Object[] closureValues,
            @Cached("create(cachedTarget)") @Exclusive DirectCallNode callNode
    ) {
        return callNode.call(closureValues[0], args);
    }

    /**
     * FAST PATH: Two closure values - common case.
     */
    @SuppressWarnings("truffle-sharing")
    @Specialization(guards = {"hasLocalSlot()", "cachedTarget != null", "closureLength == 2"})
    protected Object callDirectTwoClosure(
            VirtualFrame frame,
            Object args,
            @Cached(value = "resolveCallTarget(frame)", allowUncached = true) CallTarget cachedTarget,
            @Cached(value = "resolveClosureLength(frame)") int closureLength,
            @Cached(value = "resolveClosureValues(frame)", allowUncached = true, dimensions = 1) Object[] closureValues,
            @Cached("create(cachedTarget)") @Exclusive DirectCallNode callNode
    ) {
        return callNode.call(closureValues[0], closureValues[1], args);
    }

    /**
     * MEDIUM PATH: General closure case with @ExplodeLoop.
     * JIT will unroll the loop for small closure sizes.
     */
    @SuppressWarnings("truffle-sharing")
    @Specialization(guards = {"hasLocalSlot()", "cachedTarget != null", "closureLength >= 3", "closureLength <= 8"})
    @ExplodeLoop
    protected Object callDirectSmallClosure(
            VirtualFrame frame,
            Object args,
            @Cached(value = "resolveCallTarget(frame)", allowUncached = true) CallTarget cachedTarget,
            @Cached(value = "resolveClosureLength(frame)") int closureLength,
            @Cached(value = "resolveClosureValues(frame)", allowUncached = true, dimensions = 1) Object[] closureValues,
            @Cached("create(cachedTarget)") @Exclusive DirectCallNode callNode
    ) {
        Object[] fullArgs = new Object[closureLength + 1];
        for (int i = 0; i < closureLength; i++) {
            fullArgs[i] = closureValues[i];
        }
        fullArgs[closureLength] = args;
        return callNode.call(fullArgs);
    }

    /**
     * SLOW PATH: Large closures (rare).
     */
    @SuppressWarnings("truffle-sharing")
    @Specialization(guards = {"hasLocalSlot()", "cachedTarget != null", "closureLength > 8"})
    protected Object callDirectLargeClosure(
            VirtualFrame frame,
            Object args,
            @Cached(value = "resolveCallTarget(frame)", allowUncached = true) CallTarget cachedTarget,
            @Cached(value = "resolveClosureLength(frame)") int closureLength,
            @Cached(value = "resolveClosureValues(frame)", allowUncached = true, dimensions = 1) Object[] closureValues,
            @Cached("create(cachedTarget)") @Exclusive DirectCallNode callNode
    ) {
        Object[] fullArgs = new Object[closureLength + 1];
        System.arraycopy(closureValues, 0, fullArgs, 0, closureLength);
        fullArgs[closureLength] = args;
        return callNode.call(fullArgs);
    }

    // ================== CONTEXT-BASED LOOKUPS (for cross-module calls) ==================

    /**
     * Context lookup: No closure.
     */
    @SuppressWarnings("truffle-sharing")
    @Specialization(guards = {"!hasLocalSlot()", "cachedTarget != null", "closureLength == 0"})
    protected Object callContextNoClosure(
            VirtualFrame frame,
            Object args,
            @Cached(value = "resolveCallTargetFromContext()", allowUncached = true) CallTarget cachedTarget,
            @Cached(value = "resolveClosureLengthFromContext()") int closureLength,
            @Cached("create(cachedTarget)") @Exclusive DirectCallNode callNode
    ) {
        return callNode.call(args);
    }

    /**
     * Context lookup: Single closure.
     */
    @SuppressWarnings("truffle-sharing")
    @Specialization(guards = {"!hasLocalSlot()", "cachedTarget != null", "closureLength == 1"})
    protected Object callContextOneClosure(
            VirtualFrame frame,
            Object args,
            @Cached(value = "resolveCallTargetFromContext()", allowUncached = true) CallTarget cachedTarget,
            @Cached(value = "resolveClosureLengthFromContext()") int closureLength,
            @Cached(value = "resolveClosureValuesFromContext()", allowUncached = true, dimensions = 1) Object[] closureValues,
            @Cached("create(cachedTarget)") @Exclusive DirectCallNode callNode
    ) {
        return callNode.call(closureValues[0], args);
    }

    /**
     * Context lookup: Two closures.
     */
    @SuppressWarnings("truffle-sharing")
    @Specialization(guards = {"!hasLocalSlot()", "cachedTarget != null", "closureLength == 2"})
    protected Object callContextTwoClosure(
            VirtualFrame frame,
            Object args,
            @Cached(value = "resolveCallTargetFromContext()", allowUncached = true) CallTarget cachedTarget,
            @Cached(value = "resolveClosureLengthFromContext()") int closureLength,
            @Cached(value = "resolveClosureValuesFromContext()", allowUncached = true, dimensions = 1) Object[] closureValues,
            @Cached("create(cachedTarget)") @Exclusive DirectCallNode callNode
    ) {
        return callNode.call(closureValues[0], closureValues[1], args);
    }

    /**
     * Context lookup: Small closure (3-8).
     */
    @SuppressWarnings("truffle-sharing")
    @Specialization(guards = {"!hasLocalSlot()", "cachedTarget != null", "closureLength >= 3", "closureLength <= 8"})
    @ExplodeLoop
    protected Object callContextSmallClosure(
            VirtualFrame frame,
            Object args,
            @Cached(value = "resolveCallTargetFromContext()", allowUncached = true) CallTarget cachedTarget,
            @Cached(value = "resolveClosureLengthFromContext()") int closureLength,
            @Cached(value = "resolveClosureValuesFromContext()", allowUncached = true, dimensions = 1) Object[] closureValues,
            @Cached("create(cachedTarget)") @Exclusive DirectCallNode callNode
    ) {
        Object[] fullArgs = new Object[closureLength + 1];
        for (int i = 0; i < closureLength; i++) {
            fullArgs[i] = closureValues[i];
        }
        fullArgs[closureLength] = args;
        return callNode.call(fullArgs);
    }

    /**
     * Context lookup: Large closure.
     */
    @SuppressWarnings("truffle-sharing")
    @Specialization(guards = {"!hasLocalSlot()", "cachedTarget != null", "closureLength > 8"})
    protected Object callContextLargeClosure(
            VirtualFrame frame,
            Object args,
            @Cached(value = "resolveCallTargetFromContext()", allowUncached = true) CallTarget cachedTarget,
            @Cached(value = "resolveClosureLengthFromContext()") int closureLength,
            @Cached(value = "resolveClosureValuesFromContext()", allowUncached = true, dimensions = 1) Object[] closureValues,
            @Cached("create(cachedTarget)") @Exclusive DirectCallNode callNode
    ) {
        Object[] fullArgs = new Object[closureLength + 1];
        System.arraycopy(closureValues, 0, fullArgs, 0, closureLength);
        fullArgs[closureLength] = args;
        return callNode.call(fullArgs);
    }

    // ================== FALLBACK ==================

    /**
     * Fallback: indirect call when caching fails (megamorphic).
     */
    @Specialization(replaces = {
        "callDirectNoClosure", "callDirectOneClosure", "callDirectTwoClosure",
        "callDirectSmallClosure", "callDirectLargeClosure",
        "callContextNoClosure", "callContextOneClosure", "callContextTwoClosure",
        "callContextSmallClosure", "callContextLargeClosure"
    })
    protected Object callIndirect(
            VirtualFrame frame,
            Object args,
            @Cached @Exclusive IndirectCallNode callNode
    ) {
        CallTarget target = hasLocalSlot()
            ? resolveCallTarget(frame)
            : resolveCallTargetFromContext();

        if (target == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Operation " + operationName + " not found for impl: " + implHash);
        }

        Object[] closureValues = hasLocalSlot()
            ? resolveClosureValues(frame)
            : resolveClosureValuesFromContext();

        int len = closureValues.length;
        if (len == 0) {
            return callNode.call(target, args);
        }

        Object[] fullArgs = new Object[len + 1];
        System.arraycopy(closureValues, 0, fullArgs, 0, len);
        fullArgs[len] = args;
        return callNode.call(target, fullArgs);
    }

    // ================== RESOLUTION METHODS (called during specialization only) ==================

    protected CallTarget resolveCallTarget(VirtualFrame frame) {
        Object impl = frame.getObject(localSlot);
        return extractOperationTarget(impl);
    }

    protected Object[] resolveClosureValues(VirtualFrame frame) {
        Object impl = frame.getObject(localSlot);
        return extractClosureValues(impl);
    }

    protected int resolveClosureLength(VirtualFrame frame) {
        Object impl = frame.getObject(localSlot);
        return extractClosureLength(impl);
    }

    @TruffleBoundary
    protected CallTarget resolveCallTargetFromContext() {
        MezzeContext context = MezzeContext.get(this);
        Object impl = context.lookupGlobal(implHash);
        return extractOperationTarget(impl);
    }

    @TruffleBoundary
    protected Object[] resolveClosureValuesFromContext() {
        MezzeContext context = MezzeContext.get(this);
        Object impl = context.lookupGlobal(implHash);
        return extractClosureValues(impl);
    }

    @TruffleBoundary
    protected int resolveClosureLengthFromContext() {
        MezzeContext context = MezzeContext.get(this);
        Object impl = context.lookupGlobal(implHash);
        return extractClosureLength(impl);
    }

    // ================== EXTRACTION HELPERS ==================

    private static final Object[] EMPTY_CLOSURE = new Object[0];

    @TruffleBoundary
    private CallTarget extractOperationTarget(Object impl) {
        if (!(impl instanceof MezzeRecord record)) {
            return null;
        }
        // Use cached index if available, otherwise compute and cache it
        int opIndex = cachedOperationIndex;
        if (opIndex < 0) {
            opIndex = record.getFieldIndex(operationName);
            if (opIndex >= 0) {
                cachedOperationIndex = opIndex;
            }
        }
        // Use index-based access when available (faster than string lookup)
        Object operationFunc = opIndex >= 0 ? record.getField(opIndex) : record.getField(operationName);
        if (!(operationFunc instanceof MezzeFunction func)) {
            return null;
        }
        return func.getCallTarget();
    }

    @TruffleBoundary
    private Object[] extractClosureValues(Object impl) {
        if (!(impl instanceof MezzeRecord record)) {
            return EMPTY_CLOSURE;
        }
        // Use cached index if available
        int opIndex = cachedOperationIndex;
        if (opIndex < 0) {
            opIndex = record.getFieldIndex(operationName);
            if (opIndex >= 0) {
                cachedOperationIndex = opIndex;
            }
        }
        Object operationFunc = opIndex >= 0 ? record.getField(opIndex) : record.getField(operationName);
        if (!(operationFunc instanceof MezzeFunction func)) {
            return EMPTY_CLOSURE;
        }
        return func.getClosureValues();
    }

    @TruffleBoundary
    private int extractClosureLength(Object impl) {
        if (!(impl instanceof MezzeRecord record)) {
            return 0;
        }
        // Use cached index if available
        int opIndex = cachedOperationIndex;
        if (opIndex < 0) {
            opIndex = record.getFieldIndex(operationName);
            if (opIndex >= 0) {
                cachedOperationIndex = opIndex;
            }
        }
        Object operationFunc = opIndex >= 0 ? record.getField(opIndex) : record.getField(operationName);
        if (!(operationFunc instanceof MezzeFunction func)) {
            return 0;
        }
        return func.getClosureValues().length;
    }

    // ================== FACTORY METHODS ==================

    public static ResolvedAbilityCallNode create(
            MezzeNode argsNode,
            int localSlot,
            String implHash,
            String operationName
    ) {
        return ResolvedAbilityCallNodeGen.create(localSlot, implHash, operationName, argsNode);
    }

    public static ResolvedAbilityCallNode createContextLookup(
            MezzeNode argsNode,
            String implHash,
            String operationName
    ) {
        return ResolvedAbilityCallNodeGen.create(-1, implHash, operationName, argsNode);
    }
}
