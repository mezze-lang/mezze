package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SAMPLE-5: a scripted operation's values, handed out in CALL order.
 *
 * `mez sample` runs a function whose effects nothing handles by drawing each
 * operation's results up front, like an argument, and generating a performer that
 * hands them back. Handler config is immutable, so the position in the sequence
 * lives here — one cursor per (row key, operation), so rows never share one and
 * neither do two operations of the same row.
 *
 * Past the end the sequence repeats from the start: the row's column shows the
 * whole sequence, so what a later call returned is still on the table.
 */
public final class ScriptNatives {

    private ScriptNatives() {}

    private static final ConcurrentHashMap<String, AtomicLong> CURSORS = new ConcurrentHashMap<>();

    @TruffleBoundary
    public static Object next(MezzeRecord args) {
        MezzeSeq values = seq(args, "values");
        long key = integer(args, "key");
        long op = integer(args, "op");
        int n = values.size();
        if (n == 0) {
            throw new NativeTypeMismatchException("a scripted operation was given no values to hand out");
        }
        long call = CURSORS.computeIfAbsent(key + "/" + op, k -> new AtomicLong()).getAndIncrement();
        return values.get((int) (call % n));
    }

    private static Object field(MezzeRecord r, String name) {
        int i = r.getFieldIndex(name);
        if (i < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        return r.getField(i);
    }

    private static long integer(MezzeRecord r, String name) {
        Object v = field(r, name);
        if (v instanceof Long l) return l;
        throw new NativeTypeMismatchException("'" + name + "' must be an Int, got " + (v == null ? "null" : v.getClass().getSimpleName()));
    }

    private static MezzeSeq seq(MezzeRecord r, String name) {
        Object v = field(r, name);
        if (v instanceof MezzeSeq s) return s;
        throw new NativeTypeMismatchException("'" + name + "' must be a sequence, got " + (v == null ? "null" : v.getClass().getSimpleName()));
    }
}
