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

    /**
     * Path E (#336) static discharge flag — only meaningful on the outermost
     * HandleNode of a nested `perform A in perform B in body` chain. True
     * iff after this Handle's discharge, no pending effects remain.
     *
     * <p>False for inner Handles of a nested chain (they only handle part
     * of the row) and for any Handle that leaks an effect out to a wider
     * scope. Legacy default false preserves eager execution for anything
     * not stamped by the typechecker.
     */
    @CompilationFinal private final boolean staticallyFullyDischarges;

    // No no-hash constructor: HandleNode must always know which effect it
    // handles so drive() can filter the handler chain by (effect_hash,
    // op_name). Matching by op-name alone silently misroutes when two
    // effects in scope declare an op with the same simple name (#246/#55).
    public HandleNode(MezzeNode handlerNode, MezzeNode bodyNode, Hash effectHash) {
        this(handlerNode, bodyNode, effectHash, false);
    }

    public HandleNode(MezzeNode handlerNode, MezzeNode bodyNode, Hash effectHash,
                      boolean staticallyFullyDischarges) {
        this.handlerNode = handlerNode;
        this.bodyNode = bodyNode;
        this.callNode = IndirectCallNode.create();
        this.effectHash = effectHash;
        this.staticallyFullyDischarges = staticallyFullyDischarges;
    }

    public boolean isStaticallyFullyDischarges() {
        return staticallyFullyDischarges;
    }

    /**
     * Feature flag for #257 — when set, Mut perform sites skip the fiber/
     * Continuation machinery and treat Mut as a marker effect (chain-only
     * dispatch). Off by default while the OpCallNode marker PIC settles;
     * flip via env var to A/B against the regular path.
     *
     * <p>Status (as of branch perf/close-java-gap):
     * <ul>
     *   <li>Mut-heavy hot loops: ~5-34% speedup on Collatz N=100k
     *       (the for_range + while + read_int/write_int/incr shape).
     *       Variance is high — system load dominates the measurement
     *       at this N. Larger workloads should show a cleaner gap.</li>
     *   <li>All MutationRuntimeTest cases pass with the flag on.</li>
     *   <li>Full :interpreter:test suite passes with the flag OFF
     *       (5188/0/0). With the flag ON, runs are clean modulo a
     *       pre-existing flake (CrossEffectCompositionTest LoomAsync
     *       variants are ~20% flaky on both branches).</li>
     * </ul>
     *
     * <p>Mut is a hermetic effect — its state can never leak across the
     * LocalMut handler boundary, so the marker path never affects
     * non-Mut effect interactions. Safe to ship the infrastructure;
     * flipping the default just needs more bench evidence on real
     * workloads.
     */
    private static final boolean MARKER_MUT_ENABLED =
        !"0".equals(System.getenv("MEZZE_MARKER_MUT"));

    /**
     * #341: run every handle through the marker-style Loom-free path.
     * On by default. Escape hatch: MEZZE_HANDLE_LOOM=1 to revert to the
     * fiber path (for regression triage only; the fiber path is on borrowed
     * time — {@code EffectHandlerDriver.run} + {@code EffectFiber.create}
     * exist only until this flag is removed).
     *
     * <p>Safety: handlers that call resume after doing work (multi-shot,
     * or capture-and-defer like Generator.AsStream) will regress from
     * "full resume semantics" to "fall-through only" — see
     * {@link com.mezze.interpreter.bytecode.BdslYieldDispatch}. The BDSL
     * path has this narrowing today and only Generator.AsStream is
     * affected in stdlib (its tests skip under BDSL default-on).
     */
    private static final boolean HANDLE_NO_FIBER =
        !"1".equals(System.getenv("MEZZE_HANDLE_LOOM"));

    @Override
    public Object execute(VirtualFrame frame) {
        Object handlerValue = handlerNode.execute(frame);
        if (!(handlerValue instanceof MezzeRecord handler)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Handler must be a record, got: "
                + (handlerValue == null ? "null" : handlerValue.getClass().getSimpleName()));
        }

        // #257 fast path: Mut is a marker effect — its handler impls never
        // call resume/abort, so we don't need a fiber/Continuation at all.
        // Push the handler on the per-thread chain, run body directly with
        // CURRENT cleared, pop in finally. Inner `Mut.op {...}` calls then
        // fall through OpCallNode → chainDispatch (since current() is null)
        // and route to the handler record's field directly, no suspend/
        // resume round-trip per op.
        if (MARKER_MUT_ENABLED && MarkerEffects.isMutEffect(effectHash)) {
            return runMarker(handler, frame);
        }

        // #341 default path: no Loom fiber. Push handler on chain, clear
        // CURRENT via runDetached, execute body, catch abort in finally.
        // Scope hooks (initialize_scope / installScope / restore / finalize)
        // run around body — many handlers (LoomAsync etc.) rely on a `scope`
        // var installed by initialize_scope. Op-calls inside body dispatch
        // via the per-thread handler chain (see OpCallNode.dispatchOpOnChain).
        //
        // MEZZE_HANDLE_LOOM=1 flips back to the legacy fiber-based path
        // (EffectHandlerDriver.run + a lazy MezzeSuspend branch for the
        // non-fully-discharged case). Kept only for regression triage;
        // slated for deletion with the rest of the fiber machinery (#333).
        if (HANDLE_NO_FIBER && staticallyFullyDischarges) {
            return runNoFiber(handler, frame);
        }
        return runLegacyLoomPath(handler, frame);
    }

    /**
     * Legacy Loom-fiber path. Only reachable when either the escape hatch
     * {@code MEZZE_HANDLE_LOOM=1} is set OR the typechecker stamped this
     * Handle as not-fully-discharging (Path E). Slated for deletion with
     * #333 (delete EffectFiber/LoomSupport) — the {@code MezzeSuspend}
     * capture path here is the only remaining reason this can run under
     * default settings.
     */
    private Object runLegacyLoomPath(MezzeRecord handler, VirtualFrame frame) {
        // Path E (#336) lazy branch: when the typechecker stamped this
        // Handle as NOT fully-discharging, don't run body. Capture
        // (bodyNode, frame, chain-with-this-handler-pushed) as a
        // MezzeSuspend. Force under a wider chain will resume execution.
        if (!staticallyFullyDischarges) {
            com.oracle.truffle.api.frame.MaterializedFrame materialized = frame.materialize();
            com.mezze.interpreter.nodes.continuation.HandlerChainEntry[] chainSnapshot =
                com.mezze.interpreter.nodes.continuation.EffectFiber.snapshotHandlerChainEntries();
            com.mezze.interpreter.nodes.continuation.HandlerChainEntry[] mergedChain =
                new com.mezze.interpreter.nodes.continuation.HandlerChainEntry[chainSnapshot.length + 1];
            mergedChain[0] =
                new com.mezze.interpreter.nodes.continuation.HandlerChainEntry(handler, effectHash);
            System.arraycopy(chainSnapshot, 0, mergedChain, 1, chainSnapshot.length);
            java.util.Set<Hash> pending = new java.util.HashSet<>();
            pending.add(effectHash);
            return com.mezze.interpreter.values.MezzeSuspend.captureContinuation(
                bodyNode, materialized, mergedChain, pending);
        }
        Object result = EffectHandlerDriver.run(
            handler, effectHash, bodyNode, frame.materialize(), callNode, this);
        if (result instanceof com.mezze.interpreter.values.MezzeSuspend s) {
            com.mezze.interpreter.nodes.continuation.EffectFiber.pushHandlerOnChain(
                handler, effectHash);
            try {
                result = s.force(callNode);
            } finally {
                com.mezze.interpreter.nodes.continuation.EffectFiber.popHandlerOffChain();
            }
        }
        return result;
    }

    /**
     * Marker-effect fast path (#257). Body runs on the caller's Java stack;
     * no fiber/Continuation. Inner Mut op-calls dispatch via the per-thread
     * handler chain — see {@link com.mezze.interpreter.nodes.effect.OpCallNode#execute}
     * (when CURRENT is null and chain has entries, it walks the chain
     * innermost-first and calls the matched op handler directly).
     *
     * <p>The {@code runDetached} clears CURRENT around body so any outer
     * fiber (e.g. {@code perform Console in perform LocalMut in ...})
     * doesn't get spuriously suspended by an inner Mut op-call. Same
     * mechanism added in #255 for native loop callbacks.
     *
     * <p>Catches {@link com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException}
     * even though Mut handlers shouldn't throw it — defense-in-depth in
     * case a non-Mut handler shape gets accidentally tagged as marker.
     */
    private Object runMarker(MezzeRecord handler, VirtualFrame frame) {
        int chainDepthBefore =
            com.mezze.interpreter.nodes.continuation.EffectFiber.handlerChainDepth();
        com.mezze.interpreter.nodes.continuation.EffectFiber.pushHandlerOnChain(
            handler, effectHash);
        try {
            return com.mezze.interpreter.nodes.continuation.EffectFiber.runDetached(
                () -> bodyNode.execute(frame));
        } catch (EffectHandlerDriver.AbortPerformException abort) {
            return abort.value;
        } finally {
            com.mezze.interpreter.nodes.continuation.EffectFiber.truncateHandlerChainTo(
                chainDepthBefore);
        }
    }

    /**
     * #341: Loom-free general handle path. Same shape as {@link #runMarker}
     * but with the scope-hook wiring lifted from
     * {@link EffectHandlerDriver#run}. Push handler on chain, run body
     * inline (CURRENT cleared so op dispatch goes through chain-walk),
     * catch abort. Body's op-calls dispatch via
     * {@link com.mezze.interpreter.nodes.effect.OpCallNode#chainDispatch}
     * (fall-through only — same semantic narrowing as BDSL Path D).
     *
     * <p>Only correct for {@code staticallyFullyDischarges = true}. The
     * lazy branch upstream still builds a MezzeSuspend.
     */
    private Object runNoFiber(MezzeRecord handler, VirtualFrame frame) {
        Object scope = EffectHandlerDriver.initializeScopePublic(handler, callNode);
        boolean installedScope = scope != null;
        Object prevScope = installedScope
            ? EffectHandlerDriver.installScopePublic(this, scope)
            : null;
        int chainDepthBefore =
            com.mezze.interpreter.nodes.continuation.EffectFiber.handlerChainDepth();
        com.mezze.interpreter.nodes.continuation.EffectFiber.pushHandlerOnChain(
            handler, effectHash);
        // Push a "handle frame" marker so OpCallNode.dispatchOpOnChain
        // preserves AbortPerformException (instead of converting to
        // TaskUnhandledOpException). Same guard the BDSL Handle op uses —
        // we're a real Handle frame that will catch the abort.
        com.mezze.interpreter.nodes.continuation.EffectFiber.enterBdslHandle();
        try {
            return com.mezze.interpreter.nodes.continuation.EffectFiber.runDetached(
                () -> bodyNode.execute(frame));
        } catch (EffectHandlerDriver.AbortPerformException abort) {
            return abort.value;
        } finally {
            com.mezze.interpreter.nodes.continuation.EffectFiber.exitBdslHandle();
            com.mezze.interpreter.nodes.continuation.EffectFiber.truncateHandlerChainTo(
                chainDepthBefore);
            if (installedScope) {
                EffectHandlerDriver.finalizeScopePublic(handler, scope, callNode);
                EffectHandlerDriver.restoreScopePublic(this, prevScope);
            }
        }
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
