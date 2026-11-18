package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.interpreter.nodes.continuation.NativeSuspension;
import com.mezze.interpreter.values.MezzeGuestException;

/**
 * The natives whose callback DECIDES ORDER OR IDENTITY — comparators, key functions,
 * hash/eq closures — are not resumable (C4 stops at Java's sort). A callback there
 * that parks is reported here, by name and at once, instead of surfacing as a dropped
 * continuation downstream. One type test per callback result.
 */
public final class OrderCallbacks {
    private OrderCallbacks() {}

    public static Object checkNotParked(Object r, String what) {
        if (NativeSuspension.isSuspension(r)) {
            throw new MezzeGuestException(
                what + ": the callback parked (an `await`, `yield_now` or `park` inside it), and a callback "
                + "that decides order or identity cannot park — sort_by, sort_by_ord, min_by, max_by, dedup, "
                + "unique, group_by with evidence, last_index_of, equals and is_sorted_by run their callback inside "
                + "a Java loop. Compute the keys first (`map`, which can park), then sort or compare.");
        }
        return r;
    }
}
