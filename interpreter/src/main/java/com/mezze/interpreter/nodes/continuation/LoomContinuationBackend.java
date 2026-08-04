package com.mezze.interpreter.nodes.continuation;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * {@link ContinuationBackend} implementation backed by
 * {@code jdk.internal.vm.Continuation} via {@link LoomSupport}.
 *
 * <p>Owns a {@code ContinuationScope} and a {@code Continuation}, both
 * held as opaque {@code Object} because the reflective bridge cannot
 * name the internal types at compile time.
 *
 * <p>Behaviour is a direct pass-through — no logic added over
 * {@link LoomSupport}. Task #333 tracks deleting this file together
 * with {@link LoomSupport} once BDSL Yield covers 100% of the effect
 * surface (blocked on #384).
 *
 * <p>Not thread-safe. See {@link ContinuationBackend}.
 */
public final class LoomContinuationBackend implements ContinuationBackend {

    private final Object scope;
    private final Object continuation;

    /**
     * Create a fresh Loom continuation for {@code body}. The body does
     * not execute until {@link #run()} is called.
     */
    @TruffleBoundary
    public LoomContinuationBackend(String scopeName, Runnable body) {
        this.scope = LoomSupport.newScope(scopeName);
        this.continuation = LoomSupport.newContinuation(scope, body);
    }

    /**
     * Package-private accessor used by {@link EffectFiber#suspend} to
     * pass the yield's scope to {@link LoomSupport#yield}. Kept while
     * the seam is fresh — Phase 1 will subsume this into
     * {@link #yield()} on the interface once the CPS impl exists and
     * we stop leaking the scope object.
     */
    Object scope() {
        return scope;
    }

    @Override
    @TruffleBoundary
    public void run() {
        LoomSupport.run(continuation);
    }

    @Override
    @TruffleBoundary
    public boolean yield() {
        return LoomSupport.yield(scope);
    }

    @Override
    public boolean isDone() {
        return LoomSupport.isDone(continuation);
    }

    @Override
    public void close() {
        // JVM handles Continuation cleanup on GC. Nothing to do.
    }
}
