package com.mezze.interpreter;

import com.mezze.interpreter.nodes.builtin.io.DateTimeNatives;
import com.mezze.interpreter.nodes.builtin.io.DateTimeNatives.MezzeInstant;
import com.mezze.interpreter.nodes.builtin.io.DateTimeNatives.MezzeZone;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The direct field accessors must answer exactly what {@code atZone} answers.
 *
 * <p>They used to BE {@code atZone}: `i.instant.atZone(z.zoneId).getYear()` and
 * so on, under the note that "the ZonedDateTime is a JVM value class that JIT
 * scalarizes in hot loops". It does not — measured 1.82x on `year` and 2.39x on
 * `hour` in America/New_York, and 3.47x and 9.88x in UTC, against the same
 * fields computed from the zone OFFSET alone. So the date fields now build one
 * {@code LocalDate} instead of four objects, the time fields build nothing at
 * all, and `nanos` does not look at the zone.
 *
 * <p>THE RISK IS THE OFFSET. Everything here rests on the local second count
 * being {@code epochSecond + offsetAt(instant)}, and on a zone offset being a
 * whole number of seconds. The first is what {@code atZone} itself does, so the
 * two agree by construction; the second is what makes the time fields pure
 * arithmetic and `nanos` zone-free. Neither is obvious, and the place they
 * would break is a DST TRANSITION, where the offset changes under the same
 * wall clock — so the comparison below runs across both sides of one, second by
 * second through the jump, and over a spread of zones including half-hour and
 * three-quarter-hour offsets.
 */
public class DateTimeFieldAccessorTest {

    /** Every field, against the ZonedDateTime the accessors no longer build. */
    private static void agree(Instant t, ZoneId zone) {
        MezzeInstant i = new MezzeInstant(t);
        MezzeZone z = new MezzeZone(zone);
        ZonedDateTime expected = t.atZone(zone);
        String at = t + " in " + zone;

        assertEquals(expected.getYear(), DateTimeNatives.yearOf(i, z), "year at " + at);
        assertEquals(expected.getMonthValue(), DateTimeNatives.monthOf(i, z), "month at " + at);
        assertEquals(expected.getDayOfMonth(), DateTimeNatives.dayOf(i, z), "day at " + at);
        assertEquals(expected.getHour(), DateTimeNatives.hourOf(i, z), "hour at " + at);
        assertEquals(expected.getMinute(), DateTimeNatives.minuteOf(i, z), "minute at " + at);
        assertEquals(expected.getSecond(), DateTimeNatives.secondOf(i, z), "second at " + at);
        assertEquals(expected.getNano(), DateTimeNatives.nanosOf(i, z), "nanos at " + at);
    }

    /** Zones chosen for their offsets: whole, half, three-quarter, and negative. */
    private static final String[] ZONES = {
        "UTC",
        "America/New_York",   // -5 / -4, DST
        "Europe/London",      //  0 / +1, DST
        "Asia/Kolkata",       // +5:30, no DST
        "Australia/Eucla",    // +8:45, no DST
        "Pacific/Chatham",    // +12:45 / +13:45, DST on a 45-minute offset
        "America/St_Johns",   // -3:30 / -2:30, DST on a 30-minute offset
    };

    @Test
    public void everyFieldAgreesAcrossZonesAndTheYear() {
        Instant start = Instant.parse("2021-01-01T00:00:17.123456789Z");
        for (String zone : ZONES) {
            ZoneId z = ZoneId.of(zone);
            // Every ~37 hours through two years: 474 instants a zone, which
            // crosses every month boundary and both DST jumps.
            for (int k = 0; k < 474; k++) {
                agree(start.plusSeconds(k * 133_237L), z);
            }
        }
    }

    /**
     * THE SPRING FORWARD, second by second. New York jumps from 01:59:59 EST to
     * 03:00:00 EDT on 2021-03-14 at 07:00 UTC, so the offset changes mid-stride
     * and the hour field must jump with it.
     */
    @Test
    public void everyFieldAgreesThroughSpringForward() {
        ZoneId ny = ZoneId.of("America/New_York");
        Instant justBefore = Instant.parse("2021-03-14T06:59:55Z");
        for (int k = 0; k < 20; k++) {
            agree(justBefore.plusSeconds(k), ny);
        }
    }

    /**
     * THE FALL BACK, where the same wall clock happens twice: New York returns
     * from EDT to EST on 2021-11-07 at 06:00 UTC. An accessor that resolved the
     * offset from the LOCAL time rather than from the instant would have to pick
     * one of the two and would be wrong for the other.
     */
    @Test
    public void everyFieldAgreesThroughFallBack() {
        ZoneId ny = ZoneId.of("America/New_York");
        Instant justBefore = Instant.parse("2021-11-07T05:59:55Z");
        for (int k = 0; k < 20; k++) {
            agree(justBefore.plusSeconds(k), ny);
        }
    }

    /** Before 1970, where the epoch second is negative and floorDiv matters. */
    @Test
    public void everyFieldAgreesBeforeTheEpoch() {
        Instant old = Instant.parse("1954-07-22T03:04:05.000000006Z");
        for (String zone : ZONES) {
            ZoneId z = ZoneId.of(zone);
            for (int k = 0; k < 200; k++) {
                agree(old.plusSeconds(k * 98_731L), z);
            }
        }
    }

    /**
     * MIDNIGHT EXACTLY, in both directions. An off-by-one in the floorDiv or
     * floorMod shows up as the wrong day at 00:00:00 and at 23:59:59.
     */
    @Test
    public void everyFieldAgreesAtTheDayBoundary() {
        for (String zone : ZONES) {
            ZoneId z = ZoneId.of(zone);
            ZonedDateTime midnight = Instant.parse("2021-06-15T00:00:00Z")
                .atZone(z).toLocalDate().atStartOfDay(z);
            Instant at = midnight.toInstant();
            agree(at.minusNanos(1), z);
            agree(at, z);
            agree(at.plusNanos(1), z);
            agree(at.plusSeconds(86_399L), z);
            agree(at.plusSeconds(86_400L), z);
        }
    }
}
