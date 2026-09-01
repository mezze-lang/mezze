package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Native implementations for {@code std::datetime} and
 * {@code std::effects::time}.
 *
 * <p>Design (#72): thin wrappers over {@link java.time}. Every method
 * here is one or two lines calling the JDK's date/time API and
 * packaging the result for Mezze. All the real correctness work —
 * IANA zones, DST rules, leap years, format grammars — lives in
 * {@code java.time}. We do not reimplement any of it.
 *
 * <h2>Wrapper classes</h2>
 *
 * Three tiny final classes wrap the JDK values:
 * <ul>
 *   <li>{@link MezzeInstant} — a moment in wall-clock time
 *       (nanosecond precision since epoch).</li>
 *   <li>{@link MezzeDuration} — a length of time in nanoseconds.
 *       Also used to return monotonic-clock readings.</li>
 *   <li>{@link MezzeZone} — an IANA timezone (e.g. "America/New_York",
 *       "UTC").</li>
 * </ul>
 *
 * <p>Mezze source sees these as opaque {@code newtype} handles:
 * {@code pub newtype Instant = Instant __NativeInstant} etc. Users
 * cannot construct or inspect them directly; they go through the
 * native factories and accessors defined here.
 */
public final class DateTimeNatives {

    private DateTimeNatives() {}

    // ==================== Wrapper classes ====================

    /**
     * Wall-clock instant. Wraps {@link Instant}. Immutable, thread-safe.
     */
    public static final class MezzeInstant {
        public final Instant instant;
        public MezzeInstant(Instant instant) { this.instant = instant; }
        @Override public boolean equals(Object o) {
            return o instanceof MezzeInstant other && this.instant.equals(other.instant);
        }
        @Override public int hashCode() { return instant.hashCode(); }
        @Override public String toString() { return instant.toString(); }
    }

    /**
     * Length of time. Wraps {@link Duration}. Also used for monotonic
     * readings (Duration since arbitrary origin). Immutable.
     */
    public static final class MezzeDuration {
        public final Duration duration;
        public MezzeDuration(Duration duration) { this.duration = duration; }
        @Override public boolean equals(Object o) {
            return o instanceof MezzeDuration other && this.duration.equals(other.duration);
        }
        @Override public int hashCode() { return duration.hashCode(); }
        @Override public String toString() { return duration.toString(); }
    }

    /**
     * IANA timezone. Wraps {@link ZoneId}. Immutable, cheap to share.
     */
    public static final class MezzeZone {
        public final ZoneId zoneId;
        public MezzeZone(ZoneId zoneId) { this.zoneId = zoneId; }
        @Override public boolean equals(Object o) {
            return o instanceof MezzeZone other && this.zoneId.equals(other.zoneId);
        }
        @Override public int hashCode() { return zoneId.hashCode(); }
        @Override public String toString() { return zoneId.getId(); }
    }

    /** Shared UTC instance — the most common zone. Cached for fast access. */
    private static final MezzeZone UTC = new MezzeZone(ZoneId.of("UTC"));

    // ==================== Instant natives ====================

    /** Current wall-clock instant. Backs Time.now. */
    @TruffleBoundary
    public static MezzeInstant instantNow() {
        return new MezzeInstant(Instant.now());
    }

    /** Build an Instant from milliseconds since epoch. */
    @TruffleBoundary
    public static MezzeInstant instantFromMillis(long millis) {
        return new MezzeInstant(Instant.ofEpochMilli(millis));
    }

    /** Build an Instant from seconds since epoch + nanos-of-second. */
    @TruffleBoundary
    public static MezzeInstant instantFromSecondsNanos(long seconds, long nanos) {
        return new MezzeInstant(Instant.ofEpochSecond(seconds, nanos));
    }

    /** Return this Instant as milliseconds since epoch (truncated). */
    @TruffleBoundary
    public static long instantToMillis(MezzeInstant i) {
        return i.instant.toEpochMilli();
    }

    /** Return this Instant as seconds since epoch (truncated). */
    @TruffleBoundary
    public static long instantToSeconds(MezzeInstant i) {
        return i.instant.getEpochSecond();
    }

    /** Return nanos-of-second component (0-999_999_999). */
    @TruffleBoundary
    public static long instantNanoOfSecond(MezzeInstant i) {
        return i.instant.getNano();
    }

    /** Instant + Duration = Instant. */
    @TruffleBoundary
    public static MezzeInstant instantAdd(MezzeInstant i, MezzeDuration d) {
        return new MezzeInstant(i.instant.plus(d.duration));
    }

    /** Instant - Duration = Instant. */
    @TruffleBoundary
    public static MezzeInstant instantSubDuration(MezzeInstant i, MezzeDuration d) {
        return new MezzeInstant(i.instant.minus(d.duration));
    }

    /** Instant - Instant = Duration (may be negative). */
    @TruffleBoundary
    public static MezzeDuration instantDiff(MezzeInstant end, MezzeInstant start) {
        return new MezzeDuration(Duration.between(start.instant, end.instant));
    }

    /** True iff a is strictly before b. */
    @TruffleBoundary
    public static boolean instantBefore(MezzeInstant a, MezzeInstant b) {
        return a.instant.isBefore(b.instant);
    }

    /** True iff a is strictly after b. */
    @TruffleBoundary
    public static boolean instantAfter(MezzeInstant a, MezzeInstant b) {
        return a.instant.isAfter(b.instant);
    }

    /** Signum-style comparison: -1, 0, or 1. */
    @TruffleBoundary
    public static long instantCompare(MezzeInstant a, MezzeInstant b) {
        int c = a.instant.compareTo(b.instant);
        return Long.signum(c);
    }

    /** Structural equality (nanosecond-exact). */
    @TruffleBoundary
    public static boolean instantEquals(MezzeInstant a, MezzeInstant b) {
        return a.instant.equals(b.instant);
    }

    // ==================== Duration natives ====================

    /**
     * Monotonic reading as a Duration since some arbitrary origin.
     * Backs Time.monotonic. Never goes backwards; only meaningful
     * as delta between two readings on the same JVM instance.
     */
    @TruffleBoundary
    public static MezzeDuration durationMonotonic() {
        return new MezzeDuration(Duration.ofNanos(System.nanoTime()));
    }

    /** Duration of exactly N nanoseconds. */
    @TruffleBoundary
    public static MezzeDuration durationFromNanos(long n) {
        return new MezzeDuration(Duration.ofNanos(n));
    }

    /** Duration of exactly N milliseconds. */
    @TruffleBoundary
    public static MezzeDuration durationFromMillis(long n) {
        return new MezzeDuration(Duration.ofMillis(n));
    }

    /** Duration of exactly N seconds. */
    @TruffleBoundary
    public static MezzeDuration durationFromSeconds(long n) {
        return new MezzeDuration(Duration.ofSeconds(n));
    }

    /** Duration of exactly N minutes. */
    @TruffleBoundary
    public static MezzeDuration durationFromMinutes(long n) {
        return new MezzeDuration(Duration.ofMinutes(n));
    }

    /** Duration of exactly N hours. */
    @TruffleBoundary
    public static MezzeDuration durationFromHours(long n) {
        return new MezzeDuration(Duration.ofHours(n));
    }

    /** Duration of exactly N days (fixed 24h). */
    @TruffleBoundary
    public static MezzeDuration durationFromDays(long n) {
        return new MezzeDuration(Duration.ofDays(n));
    }

    /** Total nanoseconds in this Duration. */
    @TruffleBoundary
    public static long durationToNanos(MezzeDuration d) {
        return d.duration.toNanos();
    }

    /** Total whole milliseconds in this Duration. */
    @TruffleBoundary
    public static long durationToMillis(MezzeDuration d) {
        return d.duration.toMillis();
    }

    /** Total whole seconds in this Duration. */
    @TruffleBoundary
    public static long durationToSeconds(MezzeDuration d) {
        return d.duration.toSeconds();
    }

    /** Duration + Duration. */
    @TruffleBoundary
    public static MezzeDuration durationAdd(MezzeDuration a, MezzeDuration b) {
        return new MezzeDuration(a.duration.plus(b.duration));
    }

    /** Duration - Duration (may be negative). */
    @TruffleBoundary
    public static MezzeDuration durationSub(MezzeDuration a, MezzeDuration b) {
        return new MezzeDuration(a.duration.minus(b.duration));
    }

    /** Negate a Duration. */
    @TruffleBoundary
    public static MezzeDuration durationNegate(MezzeDuration d) {
        return new MezzeDuration(d.duration.negated());
    }

    /** Absolute value of a Duration. */
    @TruffleBoundary
    public static MezzeDuration durationAbs(MezzeDuration d) {
        return new MezzeDuration(d.duration.abs());
    }

    /** True iff strictly negative. */
    @TruffleBoundary
    public static boolean durationIsNegative(MezzeDuration d) {
        return d.duration.isNegative();
    }

    /** Signum-style compare: -1, 0, 1. */
    @TruffleBoundary
    public static long durationCompare(MezzeDuration a, MezzeDuration b) {
        return Long.signum(a.duration.compareTo(b.duration));
    }

    /** Structural equality. */
    @TruffleBoundary
    public static boolean durationEquals(MezzeDuration a, MezzeDuration b) {
        return a.duration.equals(b.duration);
    }

    // ==================== Zone natives ====================

    /** The UTC zone. Constant, safe to share. */
    @TruffleBoundary
    public static MezzeZone zoneUtc() {
        return UTC;
    }

    /**
     * The OS's default timezone. Backs {@code Time.system_zone}.
     * Effectful because the OS default depends on env / config.
     */
    @TruffleBoundary
    public static MezzeZone zoneSystem() {
        return new MezzeZone(ZoneId.systemDefault());
    }

    /**
     * Parse an IANA zone name (e.g. "America/New_York") or a fixed
     * offset (e.g. "+05:30"). Returns {@code Result Zone Str} — the
     * error case is the exception message. Callers can wrap in a
     * stdlib-defined TimeError variant if they want richer typing.
     */
    @TruffleBoundary
    public static MezzeVariant zoneOf(String id) {
        try {
            return MezzeVariant.ok(new MezzeZone(ZoneId.of(id)));
        } catch (DateTimeException e) {
            return MezzeVariant.err("Invalid zone id: " + id);
        }
    }

    /** Canonical name of this zone. */
    @TruffleBoundary
    public static String zoneId(MezzeZone z) {
        return z.zoneId.getId();
    }

    /**
     * Offset from UTC at the given instant — DST-aware. Positive east of
     * UTC. Returned as a Duration (may be negative).
     */
    @TruffleBoundary
    public static MezzeDuration zoneOffsetAt(MezzeZone z, MezzeInstant at) {
        int seconds = z.zoneId.getRules().getOffset(at.instant).getTotalSeconds();
        return new MezzeDuration(Duration.ofSeconds(seconds));
    }

    // ==================== Decompose / format / parse ====================

    /**
     * Atomic decomposition of an Instant in a Zone into a Mezze record.
     * One clock read → one consistent snapshot. Fields: year, month,
     * day, hour, minute, second, nanos.
     *
     * <p>Zone is NOT included in the record — the caller passed it in;
     * bundling it back would be redundant. If you want the zone,
     * you already have it.
     */
    @TruffleBoundary
    public static MezzeRecord decompose(MezzeInstant i, MezzeZone z) {
        ZonedDateTime zdt = i.instant.atZone(z.zoneId);
        return MezzeRecord.fromFields(
            DECOMPOSE_FIELDS,
            new Object[]{
                (long) zdt.getYear(),
                (long) zdt.getMonthValue(),
                (long) zdt.getDayOfMonth(),
                (long) zdt.getHour(),
                (long) zdt.getMinute(),
                (long) zdt.getSecond(),
                (long) zdt.getNano(),
            }
        );
    }

    private static final String[] DECOMPOSE_FIELDS = new String[]{
        "year", "month", "day", "hour", "minute", "second", "nanos"
    };

    /**
     * Compose a DateTime record + Zone back to an Instant. Returns
     * {@code Result Instant Str} — rejects impossible values
     * (e.g. Feb 30, hour 25).
     */
    @TruffleBoundary
    public static MezzeVariant compose(long year, long month, long day,
                                        long hour, long minute, long second,
                                        long nanos, MezzeZone z) {
        try {
            ZonedDateTime zdt = ZonedDateTime.of(
                (int) year, (int) month, (int) day,
                (int) hour, (int) minute, (int) second,
                (int) nanos, z.zoneId
            );
            return MezzeVariant.ok(new MezzeInstant(zdt.toInstant()));
        } catch (DateTimeException e) {
            return MezzeVariant.err("Invalid date/time: " + e.getMessage());
        }
    }

    /**
     * Format an Instant in a Zone using a pattern (java.time.format
     * grammar, e.g. "yyyy-MM-dd'T'HH:mm:ssX").
     */
    @TruffleBoundary
    public static String format(MezzeInstant i, String pattern, MezzeZone z) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern).withZone(z.zoneId);
        return fmt.format(i.instant);
    }

    /**
     * Parse a date-string into an Instant using a pattern in a Zone.
     * Returns {@code Result Instant Str}.
     *
     * <p>Handles date-only patterns (e.g. "yyyy-MM-dd") by defaulting
     * to start-of-day at midnight in the given zone. Direct
     * {@code Instant.from(fmt.parse(s))} fails on date-only inputs
     * because a bare {@code LocalDate} has no time-of-day to compute
     * an epoch offset from. The fallback via {@code LocalDate.atStartOfDay(z)}
     * fills in midnight so the roundtrip works.
     */
    @TruffleBoundary
    public static MezzeVariant parse(String s, String pattern, MezzeZone z) {
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern).withZone(z.zoneId);
            java.time.temporal.TemporalAccessor ta = fmt.parse(s);
            try {
                return MezzeVariant.ok(new MezzeInstant(Instant.from(ta)));
            } catch (DateTimeException notEnoughInfo) {
                // Fall back: interpret as a LocalDate at start-of-day in
                // the target zone.
                java.time.LocalDate ld = java.time.LocalDate.from(ta);
                return MezzeVariant.ok(new MezzeInstant(ld.atStartOfDay(z.zoneId).toInstant()));
            }
        } catch (DateTimeParseException e) {
            return MezzeVariant.err("Parse failed: " + e.getMessage());
        } catch (DateTimeException e) {
            return MezzeVariant.err("Invalid date/time: " + e.getMessage());
        }
    }

    // ==================== Direct field accessors (fast path) ====================
    // These skip the DateTime record allocation. Each is one atZone() +
    // one field pull; the ZonedDateTime is a JVM value class that JIT
    // scalarizes in hot loops.

    @TruffleBoundary
    public static long yearOf(MezzeInstant i, MezzeZone z) {
        return i.instant.atZone(z.zoneId).getYear();
    }

    @TruffleBoundary
    public static long monthOf(MezzeInstant i, MezzeZone z) {
        return i.instant.atZone(z.zoneId).getMonthValue();
    }

    @TruffleBoundary
    public static long dayOf(MezzeInstant i, MezzeZone z) {
        return i.instant.atZone(z.zoneId).getDayOfMonth();
    }

    @TruffleBoundary
    public static long hourOf(MezzeInstant i, MezzeZone z) {
        return i.instant.atZone(z.zoneId).getHour();
    }

    @TruffleBoundary
    public static long minuteOf(MezzeInstant i, MezzeZone z) {
        return i.instant.atZone(z.zoneId).getMinute();
    }

    @TruffleBoundary
    public static long secondOf(MezzeInstant i, MezzeZone z) {
        return i.instant.atZone(z.zoneId).getSecond();
    }

    @TruffleBoundary
    public static long nanosOf(MezzeInstant i, MezzeZone z) {
        return i.instant.atZone(z.zoneId).getNano();
    }

    // ==================== Codecs ====================
    // ISO 8601 for Instant + Duration; zone id for Zone. All lossless
    // round-trips.

    @TruffleBoundary
    public static String instantEncode(MezzeInstant i) {
        return DateTimeFormatter.ISO_INSTANT.format(i.instant);
    }

    @TruffleBoundary
    public static MezzeVariant instantDecode(String s) {
        try {
            return MezzeVariant.ok(new MezzeInstant(Instant.parse(s)));
        } catch (DateTimeParseException e) {
            return MezzeVariant.err("Instant decode failed: " + e.getMessage());
        }
    }

    @TruffleBoundary
    public static String durationEncode(MezzeDuration d) {
        return d.duration.toString();
    }

    @TruffleBoundary
    public static MezzeVariant durationDecode(String s) {
        try {
            return MezzeVariant.ok(new MezzeDuration(Duration.parse(s)));
        } catch (DateTimeParseException e) {
            return MezzeVariant.err("Duration decode failed: " + e.getMessage());
        }
    }

    @TruffleBoundary
    public static String zoneEncode(MezzeZone z) {
        return z.zoneId.getId();
    }

    @TruffleBoundary
    public static MezzeVariant zoneDecode(String s) {
        try {
            return MezzeVariant.ok(new MezzeZone(ZoneId.of(s)));
        } catch (DateTimeException e) {
            return MezzeVariant.err("Zone decode failed: " + e.getMessage());
        }
    }

    // ==================== TestClock (advancing mock) ====================
    //
    // Mimics Spring's MutableClock / Kotlin TestClock: mutable state
    // lives in the native handle, API surface is set/advance/now. The
    // Mezze impl body (see MockTime in std::effects::time) stays
    // side-effect-free-looking.

    public static final class MezzeTestClock {
        private final AtomicReference<Instant> state;
        public MezzeTestClock(Instant initial) {
            this.state = new AtomicReference<>(initial);
        }
        public Instant get() { return state.get(); }
        public void set(Instant i) { state.set(i); }
        public Instant advance(Duration d) {
            return state.updateAndGet(cur -> cur.plus(d));
        }
        @Override public String toString() { return "TestClock(" + state.get() + ")"; }
    }

    @TruffleBoundary
    public static MezzeTestClock testClockAt(MezzeInstant i) {
        return new MezzeTestClock(i.instant);
    }

    @TruffleBoundary
    public static MezzeInstant testClockNow(MezzeTestClock c) {
        return new MezzeInstant(c.get());
    }

    @TruffleBoundary
    public static MezzeRecord testClockSet(MezzeTestClock c, MezzeInstant i) {
        c.set(i.instant);
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeRecord testClockAdvance(MezzeTestClock c, MezzeDuration d) {
        c.advance(d.duration);
        return MezzeRecord.empty();
    }

    /**
     * Monotonic reading derived from a TestClock: nanos since epoch of
     * the clock's current state. Advances lock-step with the wall
     * clock so tests can measure elapsed_monotonic_since.
     */
    @TruffleBoundary
    public static MezzeDuration testClockMonotonic(MezzeTestClock c) {
        Instant now = c.get();
        long nanos = now.getEpochSecond() * 1_000_000_000L + now.getNano();
        return new MezzeDuration(Duration.ofNanos(nanos));
    }

    // ==================== Sleep (effectful, backs Time.sleep) ====================

    /**
     * Sleep for the given Duration. Interruptible — restores the
     * interrupt flag AND surfaces the interrupt as a
     * {@link java.util.concurrent.CancellationException} so Mezze
     * callers observe the interrupt instead of silently no-opping
     * (regression fix #244 that the old TimeNatives.sleep already had).
     *
     * <p>Uses {@link Thread#sleep} because {@code LockSupport.parkNanos}
     * doesn't throw on interrupt — it just returns early, which would
     * make SIGINT/SIGTERM invisible to user code.
     */
    @TruffleBoundary
    public static MezzeRecord sleep(MezzeDuration d) {
        long nanos = d.duration.toNanos();
        if (nanos <= 0) return MezzeRecord.empty();
        long millis = nanos / 1_000_000L;
        int subNanos = (int) (nanos % 1_000_000L);
        try {
            Thread.sleep(millis, subNanos);
            return MezzeRecord.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Sleep cancelled");
        }
    }

}
