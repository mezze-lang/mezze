package com.mezze.interpreter.nodes.continuation;

/**
 * Abstraction over the effect-suspend/resume primitive. Introduced as
 * Phase 0 of Path C (see docs/perf/bdsl-effects-path-c-2026-07.md) —
 * isolates the seam between {@link EffectFiber} and the underlying
 * continuation mechanism so future phases can swap the implementation
 * without touching call sites.
 *
 * <p>Today's sole implementation is
 * {@link LoomContinuationBackend}, which wraps the reflective
 * {@link LoomSupport} bridge to {@code jdk.internal.vm.Continuation}.
 * Path C Phase 1 will add {@link com.mezze.interpreter.nodes.continuation.CpsContinuationBackend}
 * (name subject to change); Phase 3 deletes the Loom impl.
 *
 * <p>An instance represents a single fiber's continuation state. It is
 * created lazily (fibers can start life without one — see
 * {@code EffectFiber.acquireDispatchOnly}) and, once created, owns:
 * <ul>
 *   <li>The "scope" — an opaque handle keyed to the backend
 *       (a {@code ContinuationScope} for the Loom impl)</li>
 *   <li>The paused body — the {@link Runnable} handed at creation
 *       that will run to completion or the next {@link #yield()}</li>
 * </ul>
 *
 * <p>Instances are single-shot on the Loom backend (JVM constraint) and
 * multi-shot on the CPS backend (Phase 1+). Callers today assume
 * single-shot semantics; Phase 4 will surface the multi-shot capability.
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
     *
     * <p>The Loom backend enters
     * {@code jdk.internal.vm.Continuation.run}. The CPS backend
     * (Phase 1+) will invoke the current continuation closure.
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
