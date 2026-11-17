package com.mezze.interpreter.nodes.continuation;

import com.oracle.truffle.api.interop.TruffleObject;

/**
 * What {@code cont.park { on, set }} evaluates to INSIDE the op body before the
 * task is suspended: a request the lowering treats as a suspension. The call
 * site of {@code park} is flagged {@code maySuspend} (its type carries an open
 * row), so the propagation check yields this request out of the op body's
 * root; {@code PerformD}'s check yields the resulting continuation out of the
 * task's root; and so on up to the perform boundary, whose driver decides when
 * to resume and with what {@code ParkOutcome}. The op body then continues
 * exactly where {@code park} was called, with the outcome as its value.
 *
 * <p>Userland never sees this object: it exists between the park call and the
 * boundary driver only.
 */
public final class ParkRequest implements TruffleObject {
    public final Object on;
    public final Object set;

    public ParkRequest(Object on, Object set) {
        this.on = on;
        this.set = set;
    }

    @Override
    public String toString() {
        return "ParkRequest(on=" + on + ", set=" + set + ")";
    }
}
