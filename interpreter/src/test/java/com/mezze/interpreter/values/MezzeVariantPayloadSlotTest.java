package com.mezze.interpreter.values;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MezzeVariant} keeps ONE primitive payload slot, not two, and derives
 * {@code payloadLength} instead of storing it. These tests cover the two things
 * that buys and the one thing it risks.
 *
 * <p>WHAT IT BUYS. A variant can only ever be one payload type, so the second
 * eight-byte slot was unusable by construction, and every construction route
 * set {@code payloadLength} to exactly what {@code payloadType} implies. Both
 * removed, the object goes from 64 bytes to 48 — 25% of every variant the
 * language allocates. Measured as young-GC pauses on a loop allocating one
 * single-field variant per iteration: 34 to 27 (`bench/cases/surface` row 17),
 * with the two record rows unchanged as controls. See PERF-LOG.
 *
 * <p>WHAT IT RISKS, and why these tests exist: a {@code Float} payload is now
 * stored as raw bits in the shared slot. {@code doubleToRawLongBits} is the
 * right conversion and {@code doubleToLongBits} is NOT — the latter
 * canonicalizes every NaN to one bit pattern, so a round-trip through it is
 * lossy for a signalling NaN. Negative zero is the other trap: {@code -0.0 ==
 * 0.0} is true, so a test that only compares with {@code ==} would pass even
 * if the sign bit were dropped.
 */
class MezzeVariantPayloadSlotTest {

    private static final int TAG = 7;

    private static MezzeVariant.TagRegistry registry() {
        return new MezzeVariant.TagRegistry(new String[] { "A", "B", "C", "D", "E", "F", "G", "Wrapped" });
    }

    @Test
    void anIntPayloadRoundTripsExactly() {
        for (long v : new long[] { 0L, 1L, -1L, 42L, Long.MIN_VALUE, Long.MAX_VALUE }) {
            MezzeVariant var = MezzeVariant.singleLongPayload(registry(), TAG, v);
            assertEquals(MezzeVariant.PayloadType.LONG, var.getPayloadType());
            assertEquals(v, var.getLongPayload(), "Int payload " + v);
            assertEquals(v, var.getRawPayloadValue());
        }
    }

    @Test
    void aFloatPayloadRoundTripsExactly() {
        for (double v : new double[] { 0.0, 1.0, -1.0, 2.5, Double.MIN_VALUE, Double.MAX_VALUE,
                                       Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
            MezzeVariant var = MezzeVariant.singleDoublePayload(registry(), TAG, v);
            assertEquals(MezzeVariant.PayloadType.DOUBLE, var.getPayloadType());
            assertEquals(v, var.getDoublePayload(), "Float payload " + v);
        }
    }

    /**
     * `-0.0 == 0.0` is true, so this compares the BITS. Dropping the sign bit
     * would be invisible to an equality assertion.
     */
    @Test
    void negativeZeroKeepsItsSignBit() {
        MezzeVariant var = MezzeVariant.singleDoublePayload(registry(), TAG, -0.0);
        assertEquals(Double.doubleToRawLongBits(-0.0),
                     Double.doubleToRawLongBits(var.getDoublePayload()),
                     "-0.0 must not come back as 0.0");
    }

    /**
     * The reason the conversion is `raw`: `doubleToLongBits` collapses every
     * NaN to one canonical pattern, so this payload would not survive it.
     */
    @Test
    void aNonCanonicalNaNSurvivesTheRoundTrip() {
        double signalling = Double.longBitsToDouble(0x7ff0000000000001L);
        assertTrue(Double.isNaN(signalling), "the fixture must actually be a NaN");
        MezzeVariant var = MezzeVariant.singleDoublePayload(registry(), TAG, signalling);
        assertEquals(0x7ff0000000000001L,
                     Double.doubleToRawLongBits(var.getDoublePayload()),
                     "a signalling NaN's bits must survive — doubleToLongBits would canonicalize them");
    }

    @Test
    void aFusedFloatPayloadAlsoRoundTrips() {
        MezzeVariant var = MezzeVariant.singleDoublePayloadFused(registry(), TAG, -2.5, "val");
        assertTrue(var.isFused());
        assertEquals(-2.5, var.getDoublePayload());
    }

    @Test
    void payloadLengthIsDerivedCorrectlyForEveryPayloadType() {
        assertEquals(0, MezzeVariant.noPayload(registry(), TAG).getPayloadLength());
        assertEquals(1, MezzeVariant.singleLongPayload(registry(), TAG, 1L).getPayloadLength());
        assertEquals(1, MezzeVariant.singleDoublePayload(registry(), TAG, 1.0).getPayloadLength());
        assertEquals(1, MezzeVariant.singlePayload(registry(), TAG, "x").getPayloadLength());
        assertEquals(3, new MezzeVariant(registry(), TAG, new Object[] { 1L, 2L, 3L }).getPayloadLength());
    }

    @Test
    void hasPayloadTracksTheDerivedLength() {
        assertFalse(MezzeVariant.noPayload(registry(), TAG).hasPayload());
        assertTrue(MezzeVariant.singleLongPayload(registry(), TAG, 0L).hasPayload());
        assertTrue(new MezzeVariant(registry(), TAG, new Object[] { 1L, 2L }).hasPayload());
    }

    /**
     * Equality compares the derived length across two instances, which is the
     * one place the old code read the field off another object.
     */
    @Test
    void equalityStillComparesArityAndPayload() {
        var r = registry();
        assertEquals(MezzeVariant.singleLongPayload(r, TAG, 5L),
                     MezzeVariant.singleLongPayload(r, TAG, 5L));
        assertNotEquals(MezzeVariant.singleLongPayload(r, TAG, 5L),
                        MezzeVariant.singleLongPayload(r, TAG, 6L));
        assertNotEquals(MezzeVariant.singleLongPayload(r, TAG, 5L),
                        MezzeVariant.noPayload(r, TAG));
        assertNotEquals(new MezzeVariant(r, TAG, new Object[] { 1L, 2L }),
                        new MezzeVariant(r, TAG, new Object[] { 1L, 2L, 3L }));
    }

    @Test
    void aMultiPayloadStillReadsBackByIndex() {
        MezzeVariant var = new MezzeVariant(registry(), TAG, new Object[] { 10L, 20L });
        assertEquals(MezzeVariant.PayloadType.MULTI, var.getPayloadType());
        assertEquals(2, var.getPayloadLength());
        assertEquals(10L, var.getPayloadField(0));
        assertEquals(20L, var.getPayloadField(1));
    }
}
