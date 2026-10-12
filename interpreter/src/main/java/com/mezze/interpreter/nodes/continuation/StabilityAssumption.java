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
    private boolean handedOut;
    private int churns;
    private boolean unstable;

    StabilityAssumption(String name) {
        this.cyclic = new CyclicAssumption(name);
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
        handedOut = false;
        churns = 0;
        unstable = false;
    }
}
