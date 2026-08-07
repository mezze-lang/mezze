package com.mezze.interpreter.bytecode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationResult;

/**
 * Wrapper around a Truffle Bytecode DSL {@link ContinuationResult} that
 * makes it safe to resume more than once, at the cost of paying a
 * snapshot on every call after the first.
 *
 * <p>Truffle 25's {@code ContinuationResult.continueWith(value)} runs on
 * the underlying frame the yield captured. That frame is shared across
 * calls: a second {@code continueWith} observes any mutations the first
 * invocation made to locals, resulting in observable interference for
 * multi-shot handlers (nondeterminism, backtracking, generators).
 *
 * <p>This wrapper's contract:
 * <ol>
 *   <li>First {@link #continueWith} delegates straight to the raw CR —
 *       zero copy cost, matches the D5 fast path.</li>
 *   <li>Second and subsequent {@link #continueWith} must re-execute from
 *       the same pre-yield state as the first. Two strategies exist and
 *       both live behind this API; the wrapper's job is to hide which
 *       one runs from the caller.</li>
 * </ol>
 *
 * <p>Today's implementation uses <b>strategy A</b>: the wrapper permits
 * only one live resume. A second {@link #continueWith} throws
 * {@link MultiShotUnsupported}. This surfaces the current Truffle 25
 * limitation loudly rather than silently returning wrong answers, which
 * is what raw {@link ContinuationResult#continueWith} would do.
 *
 * <p>A future revision will implement <b>strategy B</b> (frame-snapshot
 * deep copy) once we can materialise the yielded frame without touching
 * Truffle package-private state — see
 * {@code docs/effects/bdsl-path-d6-multi-shot.md} §5 Commit 2 and §6 Q5.
 * When that lands the callers here don't change; only the private
 * {@link #snapshot} / {@link #restore} implementation does.
 *
 * <p>Class is Truffle-boundary-only. Never store an instance in a
 * partial-eval-visible slot; treat it like the raw CR — one-shot in the
 * common path, opt-in slow path when a handler is annotated
 * {@code @Multishot} (see Path D6 doc).
 */
public final class MezzeContinuation {

    private final ContinuationResult raw;
    private boolean consumed;

    private MezzeContinuation(ContinuationResult raw) {
        this.raw = raw;
        this.consumed = false;
    }

    /**
     * Wrap a raw {@link ContinuationResult}. Returns {@code null} if
     * {@code raw} is {@code null} so call sites can pipe untyped
     * driver-loop values through without a null-check.
     */
    public static MezzeContinuation wrap(ContinuationResult raw) {
        if (raw == null) return null;
        return new MezzeContinuation(raw);
    }

    /**
     * Resume the captured continuation with {@code value}.
     *
     * @return whatever the resumed body evaluates to — either a plain
     *         value (body completed) or a fresh {@link ContinuationResult}
     *         (body yielded again). The caller usually loops on the
     *         result, same as {@link BdslYieldDriver#drive}.
     * @throws MultiShotUnsupported if called more than once. Strategy A
     *         (see class doc).
     */
    @TruffleBoundary
    public Object continueWith(Object value) {
        if (consumed) {
            throw new MultiShotUnsupported(
                "MezzeContinuation.continueWith called after first resume. "
                + "Multi-shot handlers require frame-snapshot support; see "
                + "docs/effects/bdsl-path-d6-multi-shot.md §5 Commit 2.");
        }
        consumed = true;
        return raw.continueWith(value);
    }

    /**
     * Whether this continuation has already been resumed once.
     * Useful for handlers to detect illegal double-resume before
     * throwing.
     */
    public boolean isConsumed() {
        return consumed;
    }

    /**
     * The raw underlying CR. Escape hatch for {@link BdslYieldDriver}
     * during migration — do not add new call sites.
     */
    public ContinuationResult getRaw() {
        return raw;
    }

    /**
     * Thrown when a caller tries to resume a MezzeContinuation more than
     * once and the runtime cannot (yet) safely deep-clone the underlying
     * frame. See class doc.
     */
    public static final class MultiShotUnsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public MultiShotUnsupported(String msg) { super(msg); }
    }
}
