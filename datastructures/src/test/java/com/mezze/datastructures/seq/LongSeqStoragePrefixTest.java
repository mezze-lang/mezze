package com.mezze.datastructures.seq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LongSeqStorage#ofPrefix} — take an array but use only its first
 * {@code length} slots.
 *
 * WHY IT EXISTS. A materialising pipeline sink sizes its buffer to the SOURCE
 * and fills only the elements that survive the stages, so it used to
 * {@code Arrays.copyOf} down to the real count and then hand that to
 * {@code MezzeSeq.fromLongs}, which {@code clone()}s defensively — the result
 * array was copied TWICE before anyone could read it. `length` has always been
 * allowed to sit below `data.length`; that is what the spare-capacity append
 * path uses.
 *
 * WHAT CAN GO WRONG, and so what is tested:
 *
 *  - THE PADDING MUST NOT BE VISIBLE. The slots past `length` hold whatever the
 *    buffer held, usually zeros, and a seq that reports them is wrong in the
 *    quietest possible way: `sum` is unchanged by trailing zeros, so only a
 *    SIZE or an element check catches it. `product` would catch it, which is
 *    why one test multiplies.
 *  - THE OWNER TOKEN MUST BE SOUND. `ofPrefix` installs a FRESH token so the
 *    spare capacity becomes O(1) append room. That is exactly the
 *    copy-on-write path, so an append must leave the ORIGINAL seq untouched —
 *    the invariant is that a write lands at index `length`, outside every
 *    sharer's `[0, length)` window.
 *  - `getArray()` TRIMS. It is the accessor every native reaches for, and it
 *    must hand back an array of exactly `size()` elements, or a caller that
 *    bounds its loop by `data.length` reads the padding.
 */
class LongSeqStoragePrefixTest {

    @Test
    void aPrefixReportsItsLengthAndNotTheArrays() {
        long[] buf = new long[10];
        buf[0] = 7; buf[1] = 8; buf[2] = 9;   // buf[3..9] are padding
        var s = LongSeqStorage.ofPrefix(buf, 3);
        assertEquals(3, s.size());
        assertEquals(7L, s.get(0));
        assertEquals(8L, s.get(1));
        assertEquals(9L, s.get(2));
    }

    /**
     * The padding is ZERO, so a sum cannot see it. A product can: 7*8*9 = 504,
     * and one stray zero makes it 0.
     */
    @Test
    void thePaddingIsNotMultipliedIn() {
        long[] buf = new long[10];
        buf[0] = 7; buf[1] = 8; buf[2] = 9;
        var s = LongSeqStorage.ofPrefix(buf, 3);
        long product = 1;
        for (int i = 0; i < s.size(); i++) product *= (Long) s.get(i);
        assertEquals(504L, product);
    }

    @Test
    void getArrayTrimsToTheLogicalSize() {
        long[] buf = new long[10];
        buf[0] = 1; buf[1] = 2;
        var s = LongSeqStorage.ofPrefix(buf, 2);
        long[] got = s.getArray();
        assertEquals(2, got.length, "getArray must not expose the padding");
        assertArrayEquals(new long[] {1, 2}, got);
    }

    /**
     * THE SOUNDNESS CASE. The fresh owner token means the first append writes
     * into the spare capacity IN PLACE. The original must not see it.
     */
    @Test
    void anAppendIntoTheSpareCapacityLeavesTheOriginalAlone() {
        long[] buf = new long[6];
        buf[0] = 1; buf[1] = 2; buf[2] = 3;
        var base = LongSeqStorage.ofPrefix(buf, 3);
        var grown = base.appendLong(99);

        assertEquals(4, grown.size());
        assertEquals(99L, grown.get(3));
        assertEquals(3, base.size(), "the original grew");
        assertArrayEquals(new long[] {1, 2, 3}, base.getArray());
    }

    /** A second append off the SAME base must not see the first one's write. */
    @Test
    void twoAppendsOffOneBaseDoNotSeeEachOther() {
        long[] buf = new long[6];
        buf[0] = 1; buf[1] = 2;
        var base = LongSeqStorage.ofPrefix(buf, 2);
        var a = base.appendLong(10);
        var b = base.appendLong(20);

        assertEquals(10L, a.get(2), "the first append was overwritten");
        assertEquals(20L, b.get(2));
        assertEquals(2, base.size());
    }

    @Test
    void theWholeArrayIsAllowedAndSoIsNothing() {
        long[] buf = {4, 5, 6};
        assertEquals(3, LongSeqStorage.ofPrefix(buf, 3).size());
        assertEquals(0, LongSeqStorage.ofPrefix(buf, 0).size());
    }

    @Test
    void aLengthOutsideTheArrayIsRefused() {
        long[] buf = {1, 2, 3};
        assertThrows(IllegalArgumentException.class, () -> LongSeqStorage.ofPrefix(buf, 4));
        assertThrows(IllegalArgumentException.class, () -> LongSeqStorage.ofPrefix(buf, -1));
    }
}
