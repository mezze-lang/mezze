package com.mezze.interpreter.nodes.continuation;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.utilities.CyclicAssumption;

/**
 * A {@link CyclicAssumption} that gives up.
 *
 * <p>The handler-chain assumptions guard cached effect dispatch: a perform site
 * caches its handler while no handler for that effect is pushed or popped. A
 * plain CyclicAssumption hands out a fresh assumption after every invalidation,
 * and the DSL re-caches against it — so code that installs a handler on every
 * call ({@code perform Console in …} in a recursive function, a handler inside
 * a loop) invalidated its own compiled code on every call, recompiled, and
 * invalidated again. Anything else compiled into the same root went with it: a
 * fused list fold beside such a function ran at interpreter speed, ~10x slower,
 * for as long as the program ran.
 *
 * <p>So churn is counted, and past {@link #CHURN_LIMIT} this hands out
 * {@link Assumption#NEVER_VALID}: the cached specializations can no longer be
 * instantiated, those sites settle on the uncached dispatch, and nothing
 * invalidates again. A churn is an assumption that was handed out AND then
 * invalidated; invalidating one nobody holds is skipped entirely, which also
 * keeps the common push/pop from allocating a new assumption every time.
 */
final class StabilityAssumption {

    /** Hand-out-then-invalidate cycles tolerated before giving up for good. */
    static final int CHURN_LIMIT = 8;

    private final CyclicAssumption cyclic;

    /**
     * The assumption the CHURNED dispatch tier caches against. Churn never
     * invalidates this one — that is the whole point, and why dropping
     * {@code effectStable} from that tier was worth 2.6x. Only {@link #reset}
     * invalidates it.
     *
     * <p>WHY A SECOND ASSUMPTION IS WHAT RECOVERY NEEDS. {@link #reset} already
     * existed and already invalidated {@code cyclic} first so that nothing
     * holding it could keep using it — but once the churned tier stopped
     * carrying ANY assumption, there was nothing for a reset to dislodge. It
     * kept matching, so the Truffle DSL never re-entered its slow path, never
     * re-called {@link #get()}, and never discovered that the effect had become
     * stable again. A working cheaper-but-not-cheapest tier was itself the
     * thing blocking recovery. Invalidating this assumption DROPS that tier,
     * which is what forces the re-specialization that re-asks.
     */
    private final CyclicAssumption recoveryGate;

    private boolean handedOut;
    private int churns;
    private boolean unstable;

    StabilityAssumption(String name) {
        this.cyclic = new CyclicAssumption(name);
        this.recoveryGate = new CyclicAssumption(name + "-tier");
    }

    /** The assumption for the churned tier. See {@link #recoveryGate}. */
    Assumption tierAssumption() {
        return recoveryGate.getAssumption();
    }

    /**
     * True once churn has made {@link #get()} permanently {@code NEVER_VALID}.
     * Read before resetting, so a quiescent effect that never churned is not
     * handed a pointless deoptimization.
     */
    synchronized boolean hasGivenUp() {
        return unstable;
    }

    /** The assumption to cache against; never valid once this has given up. */
    synchronized Assumption get() {
        if (unstable) return Assumption.NEVER_VALID;
        handedOut = true;
        return cyclic.getAssumption();
    }

    /** The state this assumption guards has changed. */
    synchronized void invalidate() {
        if (!handedOut) return;
        handedOut = false;
        cyclic.invalidate();
        if (++churns >= CHURN_LIMIT) unstable = true;
    }

    /** Start over, as if never churned. Invalidates first, so nothing still
     *  holding the current assumption can keep using it. */
    synchronized void reset() {
        cyclic.invalidate();
        // Drop the churned tier, so the DSL re-specializes and re-asks get().
        recoveryGate.invalidate();
        handedOut = false;
        churns = 0;
        unstable = false;
    }
}
