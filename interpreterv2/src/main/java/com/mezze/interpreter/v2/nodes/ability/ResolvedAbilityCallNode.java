package com.mezze.interpreter.v2.nodes.ability;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * OPTIMIZED resolved ability call node.
 *
 * Uses DirectCallNode caching for JIT-compilable dispatch:
 * - First call: resolves impl from frame slot or context, caches CallTarget
 * - Subsequent calls: direct dispatch without lookup
 *
 * NO @TruffleBoundary in hot path!
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

    @CompilationFinal
    private final int operationIndex;

    protected ResolvedAbilityCallNode(int localSlot, String implHash, String operationName) {
        this.localSlot = localSlot;
        this.implHash = implHash;
        this.operationName = operationName;
        this.operationIndex = -1; // Will be resolved lazily
    }

    // Guard accessor methods - @Idempotent for fast-path guard elision
    protected final int getLocalSlot() {
        return localSlot;
    }

    @Idempotent
    protected final boolean hasLocalSlot() {
        return localSlot >= 0;
    }

    /**
     * Fast path: monomorphic call with cached CallTarget.
     * Guards ensure same impl record and operation function.
     */
    @Specialization(guards = {"hasLocalSlot()", "cachedTarget != null"})
    protected Object callDirectFromSlot(
            VirtualFrame frame,
            Object args,
            @Cached(value = "resolveCallTarget(frame)", allowUncached = true, neverDefault = true) CallTarget cachedTarget,
            @Cached("create(cachedTarget)") DirectCallNode callNode
    ) {
        return callNode.call(args);
    }

    /**
     * Fast path for context-based lookup with cached CallTarget.
     */
    @Specialization(guards = {"!hasLocalSlot()", "cachedTarget != null"})
    protected Object callDirectFromContext(
            VirtualFrame frame,
            Object args,
            @Cached(value = "resolveCallTargetFromContext()", allowUncached = true, neverDefault = true) CallTarget cachedTarget,
            @Cached("create(cachedTarget)") DirectCallNode callNode
    ) {
        return callNode.call(args);
    }

    /**
     * Fallback: indirect call when caching fails.
     */
    @Specialization(replaces = {"callDirectFromSlot", "callDirectFromContext"})
    protected Object callIndirect(
            VirtualFrame frame,
            Object args,
            @Cached @Exclusive IndirectCallNode callNode
    ) {
        CallTarget target = hasLocalSlot()
            ? resolveCallTarget(frame)
            : resolveCallTargetFromContext();

        if (target == null) {
            throw new RuntimeException("Operation " + operationName + " not found for impl: " + implHash);
        }
        return callNode.call(target, args);
    }

    /**
     * Resolve CallTarget from frame slot.
     * Called once during specialization, then cached.
     */
    protected CallTarget resolveCallTarget(VirtualFrame frame) {
        Object impl = frame.getObject(localSlot);
        return extractOperationTarget(impl);
    }

    /**
     * Resolve CallTarget from context.
     * Called once during specialization, then cached.
     */
    @TruffleBoundary
    protected CallTarget resolveCallTargetFromContext() {
        MezzeContext context = MezzeContext.get(this);
        Object impl = context.lookupGlobal(implHash);
        return extractOperationTarget(impl);
    }

    /**
     * Extract operation CallTarget from impl record.
     */
    @TruffleBoundary
    private CallTarget extractOperationTarget(Object impl) {
        if (!(impl instanceof MezzeRecord record)) {
            return null;
        }

        Object operationFunc = record.getField(operationName);
        if (!(operationFunc instanceof MezzeFunction func)) {
            return null;
        }

        return func.getCallTarget();
    }

    /**
     * Factory method with localSlot (for frame-local impl).
     */
    public static ResolvedAbilityCallNode create(
            MezzeNode argsNode,
            int localSlot,
            String implHash,
            String operationName
    ) {
        return ResolvedAbilityCallNodeGen.create(localSlot, implHash, operationName, argsNode);
    }

    /**
     * Factory method without localSlot (for context lookup).
     */
    public static ResolvedAbilityCallNode createContextLookup(
            MezzeNode argsNode,
            String implHash,
            String operationName
    ) {
        return ResolvedAbilityCallNodeGen.create(-1, implHash, operationName, argsNode);
    }
}
