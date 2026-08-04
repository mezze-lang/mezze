package com.mezze.interpreter.nodes.continuation;

/**
 * Abstraction over the effect-suspend/resume primitive. Isolates the
 * seam between {@link EffectFiber} and the underlying continuation
 * mechanism.
 *
 * <p>Today's sole implementation is {@link LoomContinuationBackend},
 * which wraps the reflective {@link LoomSupport} bridge to
 * {@code jdk.internal.vm.Continuation}. Path C's CPS-based
 * implementation was superseded by Path D (BDSL Yield) and deleted;
 * see task #385.
 *
 * <p>An instance represents a single fiber's continuation state. It is
 * created lazily (fibers can start life without one — see
 * {@code EffectFiber.acquireDispatchOnly}) and, once created, owns
 * the Loom scope handle plus the paused body.
 *
 * <p>Instances are single-shot on the Loom backend (JVM constraint).
 *
 * <p>Not thread-safe. A backend instance is owned by the thread that
 * created it; effect-fiber snapshots-and-reinstalls on spawn use a
 * fresh instance on the child.
 */
public interface ContinuationBackend {

    /**
     * Run/resume the body. Returns when the body either yields (via
     * {@link #yield()}) or completes. Callers check {@link #isDone()}
     * after this returns to distinguish.
     */
    void run();

    /**
     * Called from inside the body to suspend. Returns true on
     * successful yield; the caller (backend impl) is responsible for
     * translating false into the correct exception. On the Loom
     * backend this is
     * {@code jdk.internal.vm.Continuation.yield(scope)}.
     *
     * <p>After the calling side re-enters the body via
     * {@link #run()}, control returns from the {@code yield} point.
     */
    boolean yield();

    /** True when the body has run to completion. */
    boolean isDone();

    /**
     * Signal that this backend instance will not be reused. Backends
     * with reusable state (pooling, snapshot management) can reset
     * here. The Loom impl is a no-op — the JVM handles cleanup when
     * the continuation is garbage-collected.
     */
    void close();
}
