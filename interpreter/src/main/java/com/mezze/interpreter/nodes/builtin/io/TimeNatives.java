package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.CancellationException;

/**
 * Native Time operations - ULTRA PERFORMANT.
 *
 * Time in V2 uses Java's time API.
 * Timestamps are represented as milliseconds since epoch.
 */
public final class TimeNatives {

    private TimeNatives() {}

    // ==================== Current Time ====================

    @TruffleBoundary
    public static long now() {
        return System.currentTimeMillis();
    }

    @TruffleBoundary
    public static long nowNanos() {
        return System.nanoTime();
    }

    // ==================== Current Date/Time Components ====================

    @TruffleBoundary
    public static long currentYear() {
        return LocalDateTime.now().getYear();
    }

    @TruffleBoundary
    public static long currentMonth() {
        return LocalDateTime.now().getMonthValue();
    }

    @TruffleBoundary
    public static long currentDay() {
        return LocalDateTime.now().getDayOfMonth();
    }

    @TruffleBoundary
    public static long currentHour() {
        return LocalDateTime.now().getHour();
    }

    @TruffleBoundary
    public static long currentMinute() {
        return LocalDateTime.now().getMinute();
    }

    @TruffleBoundary
    public static long currentSecond() {
        return LocalDateTime.now().getSecond();
    }

    // ==================== Timestamp Components ====================

    @TruffleBoundary
    public static long yearFromTimestamp(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).getYear();
    }

    @TruffleBoundary
    public static long monthFromTimestamp(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).getMonthValue();
    }

    @TruffleBoundary
    public static long dayFromTimestamp(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).getDayOfMonth();
    }

    @TruffleBoundary
    public static long hourFromTimestamp(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).getHour();
    }

    @TruffleBoundary
    public static long minuteFromTimestamp(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).getMinute();
    }

    @TruffleBoundary
    public static long secondFromTimestamp(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).getSecond();
    }

    // ==================== Formatting ====================

    @TruffleBoundary
    public static String format(long timestamp, String pattern) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
            return dt.format(formatter);
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== Parsing ====================

    @TruffleBoundary
    public static long parse(String dateStr, String pattern) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            LocalDateTime dt = LocalDateTime.parse(dateStr, formatter);
            return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new RuntimeException("Failed to parse date string: " + e.getMessage(), e);
        }
    }

    // ==================== Sleep ====================

    @TruffleBoundary
    public static MezzeUnit sleep(long millis) {
        // #251: deadline-based re-block so that inside an uninterruptible
        // region we continue sleeping for the remaining time when a
        // cancel arrives.
        final long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        while (true) {
            long remainingMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, deadlineNanos - System.nanoTime()));
            if (remainingMs == 0L) return MezzeUnit.INSTANCE;
            try {
                Thread.sleep(remainingMs);
                return MezzeUnit.INSTANCE;
            } catch (InterruptedException e) {
                if (com.mezze.interpreter.nodes.builtin.effects.AsyncNatives.deferInterruptIfInRegion()) {
                    continue;
                }
                Thread.currentThread().interrupt();
                throw new CancellationException("Sleep cancelled");
            }
        }
    }

    @TruffleBoundary
    public static MezzeUnit sleepNanos(long nanos) {
        final long deadlineNanos = System.nanoTime() + nanos;
        while (true) {
            long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
            if (remainingNanos == 0L) return MezzeUnit.INSTANCE;
            try {
                long millis = remainingNanos / 1_000_000;
                int subNanos = (int) (remainingNanos % 1_000_000);
                Thread.sleep(millis, subNanos);
                return MezzeUnit.INSTANCE;
            } catch (InterruptedException e) {
                if (com.mezze.interpreter.nodes.builtin.effects.AsyncNatives.deferInterruptIfInRegion()) {
                    continue;
                }
                Thread.currentThread().interrupt();
                throw new CancellationException("Sleep cancelled");
            }
        }
    }
}
