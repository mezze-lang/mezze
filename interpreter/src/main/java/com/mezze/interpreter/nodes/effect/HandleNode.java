package com.mezze.interpreter.nodes.effect;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Node that looks up a handler by content hash.
 *
 * <p>Tries the Language-level stdlib cache, then context handlers, then
 * context globals. Returns the resolved handler record (a
 * {@link MezzeRecord} whose fields are the op handler functions).
 *
 * <p>Kept independent of {@link HandleNode} so the compiler can compose
 * a handler-resolution sub-tree separately from the perform site.
 */
@NodeInfo(shortName = "handler-lookup")
final class HandlerLookupNode extends MezzeNode {

    @CompilationFinal private final Hash contentHash;
    @CompilationFinal private final String performerName;

    HandlerLookupNode(Hash contentHash) {
        this(contentHash, null);
    }

    HandlerLookupNode(Hash contentHash, String performerName) {
        this.contentHash = contentHash;
        this.performerName = performerName;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return lookupInContext(MezzeRecord.empty());
    }

    /**
     * Resolve the handler record for this performer's content hash. Single
     * source of truth: {@code MezzeContext.handlersByHash}, populated by
     * {@code HashModuleLoaderNode.constructPerformerRecord}. No fallbacks
     * — if the entry isn't a {@code MezzeRecord}, the perform site is
     * invalid (module mis-load, hash mismatch, parameterized impl resolved
     * the wrong way) and we surface that loudly rather than silently
     * dispatch into a partial or stale value.
     *
     * <p>Parameterized performers go through {@link ParameterizedHandlerLookupNode},
     * which takes a different path (factory invocation with user params).
     */
    @TruffleBoundary
    Object lookupInContext(Object paramsRecord) {
        MezzeContext context = MezzeContext.get(this);
        Object handler = context.lookupHandler(contentHash);
        if (!(handler instanceof MezzeRecord)) {
            throw new RuntimeException("Handler not found for: " + contentHash.getHex()
                + (performerName != null ? " (performer: " + performerName + ")" : ""));
        }
        return handler;
    }
}

/**
 * Node that looks up a parameterized handler by content hash and calls
 * it with the user-provided params record (e.g. {@code SeededRandom {seed = 42}}).
 *
 * <p>Different params produce different performer records, so this never
 * caches — it always re-evaluates the factory.
 */
@NodeInfo(shortName = "parameterized-handler-lookup")
final class ParameterizedHandlerLookupNode extends MezzeNode {

    @CompilationFinal private final Hash contentHash;
    @CompilationFinal private final String performerName;

    @Child private MezzeNode paramsNode;

    ParameterizedHandlerLookupNode(Hash contentHash, String performerName, MezzeNode paramsNode) {
        this.contentHash = contentHash;
        this.performerName = performerName;
        this.paramsNode = paramsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object params = paramsNode.execute(frame);
        return lookupInContextWithParams(params);
    }

    /**
     * Resolve the handler for {@code perform Performer params in body}.
     *
     * <p>Two-step single path:
     * <ol>
     *   <li>If a factory {@code CallTarget} exists for this hash (via
     *       {@link com.mezze.interpreter.runtime.MezzeLanguage#getCachedStdlibCallTarget(Hash)},
     *       which handles both stdlib precompiled and per-context user IR),
     *       invoke it with {@code params} and return the resulting record.</li>
     *   <li>Otherwise, fall back to the pre-constructed performer record in
     *       {@code handlersByHash} — used when the performer has no real
     *       parameters and the typechecker still chose this node (e.g.
     *       {@code perform LoomAsync {} in body}).</li>
     * </ol>
     */
    @TruffleBoundary
    private Object lookupInContextWithParams(Object params) {
        var lang = MezzeLanguage.get(this);
        var ct = lang.getCachedStdlibCallTarget(contentHash);
        if (ct != null) {
            MezzeFunction handlerFn = MezzeFunction.create(ct, contentHash.getHex());
            Object[] closure = handlerFn.getClosureValues();
            Object[] callArgs = new Object[closure.length + 1];
            System.arraycopy(closure, 0, callArgs, 0, closure.length);
            callArgs[closure.length] = params;
            Object result = handlerFn.getCallTarget().call(callArgs);
            if (result instanceof MezzeFunction innerFn) {
                // Double-wrapped factory — invoke inner with same params.
                Object[] innerClosure = innerFn.getClosureValues();
                Object[] innerCallArgs = new Object[innerClosure.length + 1];
                System.arraycopy(innerClosure, 0, innerCallArgs, 0, innerClosure.length);
                innerCallArgs[innerClosure.length] = params;
                result = innerFn.getCallTarget().call(innerCallArgs);
            }
            if (result instanceof MezzeRecord) {
                return result;
            }
        }
        // Non-parameterized performer routed through this node — use the
        // pre-constructed record from HashModuleLoaderNode (same source
        // HandlerLookupNode reads from).
        MezzeContext context = MezzeContext.get(this);
        Object record = context.lookupHandler(contentHash);
        if (record instanceof MezzeRecord) {
            return record;
        }
        throw new RuntimeException("Parameterized performer factory not found for: "
            + contentHash.getHex()
            + (performerName != null ? " (performer: " + performerName + ")" : ""));
    }
}

/**
 * Lowers {@code perform Handler in body}.
 *
 * <p>Evaluates the handler expression (which produces a {@link MezzeRecord}
 * whose fields are op handler functions, plus optional
 * {@code initialize_scope} / {@code finalize_scope}), then drives body
 * inside a Loom-backed fiber via {@link EffectHandlerDriver#run}.
 *
 * <p>Effect-operation call sites inside body are emitted as
 * {@link OpCallNode}, which calls
 * {@code EffectFiber.current().suspend(new OpCall(opName, args))}. The
 * driver catches the suspend, looks up the op on the handler record, and
 * dispatches with {@code {args, resume, abort}}.
 */
@NodeInfo(shortName = "handle")
public final class HandleNode extends MezzeNode {

    @Child private MezzeNode handlerNode;
    @Child private MezzeNode bodyNode;
    @Child private IndirectCallNode callNode;
    @CompilationFinal private final Hash effectHash;

    public HandleNode(MezzeNode handlerNode, MezzeNode bodyNode) {
        this(handlerNode, bodyNode, null);
    }

    public HandleNode(MezzeNode handlerNode, MezzeNode bodyNode, Hash effectHash) {
        this.handlerNode = handlerNode;
        this.bodyNode = bodyNode;
        this.callNode = IndirectCallNode.create();
        this.effectHash = effectHash;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object handlerValue = handlerNode.execute(frame);
        if (!(handlerValue instanceof MezzeRecord handler)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Handler must be a record, got: "
                + (handlerValue == null ? "null" : handlerValue.getClass().getSimpleName()));
        }
        // Body executes inside a Loom continuation, which may yield back to
        // the driver across the frame boundary. Materialize so body's frame
        // survives the suspend/resume cycles.
        return EffectHandlerDriver.run(
            handler, effectHash, bodyNode, frame.materialize(), callNode, this);
    }

    // ------------------------------------------------------------------
    // Factories used by TruffleCompiler — kept stable so callers don't change.
    // ------------------------------------------------------------------

    public static MezzeNode createHandlerLookup(int unusedSlot, Hash contentHash) {
        return new HandlerLookupNode(contentHash);
    }

    public static MezzeNode createHandlerLookup(int unusedSlot, Hash contentHash, String performerName) {
        return new HandlerLookupNode(contentHash, performerName);
    }

    public static MezzeNode createParameterizedHandlerLookup(
            int unusedSlot, Hash contentHash, String performerName, MezzeNode paramsNode) {
        return new ParameterizedHandlerLookupNode(contentHash, performerName, paramsNode);
    }
}
