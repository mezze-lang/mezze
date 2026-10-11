package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SHRINK-1: the draws a property run made, for replaying them simplified.
 *
 * Every generated value bottoms out in `Sample.choose`, so a run is exactly its
 * sequence of draws, and 0 is always the simplest draw. Shrinking a failing input
 * is then type-agnostic: replay the draws with some made smaller, and keep any
 * change that still fails. These natives hold, per run key, the draws a performer
 * ANSWERED — when recording, the random ones; when replaying, the given ones as
 * actually used (clamped below each bound, 0 past the end). Reading back what was
 * answered rather than what was asked for is what makes a shrunk sequence
 * canonical: draws a smaller value never consumed simply are not in it.
 */
public final class DrawLogNatives {

    private DrawLogNatives() {}

    private static final ConcurrentHashMap<Long, List<Long>> LOGS = new ConcurrentHashMap<>();

    private static List<Long> log(long key) {
        return LOGS.computeIfAbsent(key, k -> new ArrayList<>());
    }

    /** Start run [key] with an empty log. */
    @TruffleBoundary
    public static Object reset(MezzeRecord args) {
        LOGS.put(integer(args, "key"), new ArrayList<>());
        return MezzeRecord.empty();
    }

    /** Record that run [key] drew [value]; returns it. */
    @TruffleBoundary
    public static Object append(MezzeRecord args) {
        long value = integer(args, "value");
        List<Long> l = log(integer(args, "key"));
        synchronized (l) { l.add(value); }
        return value;
    }

    /**
     * Run [key]'s next draw, replayed from [draws]: the draw at its position,
     * clamped below [bound], or 0 once the draws run out. Recorded as answered.
     */
    @TruffleBoundary
    public static Object replay(MezzeRecord args) {
        long key = integer(args, "key");
        long bound = integer(args, "bound");
        Object d = field(args, "draws");
        if (!(d instanceof MezzeSeq<?> draws)) {
            throw new NativeTypeMismatchException("'draws' must be a sequence");
        }
        List<Long> l = log(key);
        synchronized (l) {
            int at = l.size();
            long answer = 0;
            if (bound > 1 && at < draws.size()) {
                Object v = draws.get(at);
                long wanted = v instanceof Long n ? n : 0;
                answer = Math.max(0, Math.min(wanted, bound - 1));
            }
            l.add(answer);
            return answer;
        }
    }

    /** What run [key] drew, in order. */
    @TruffleBoundary
    public static Object drawn(MezzeRecord args) {
        List<Long> l = log(integer(args, "key"));
        synchronized (l) {
            long[] out = new long[l.size()];
            for (int i = 0; i < out.length; i++) out[i] = l.get(i);
            return MezzeSeq.fromLongs(out);
        }
    }

    /**
     * Sequences simpler than [draws], most promising first: the first half, all
     * but the last, then each draw zeroed, halved and decremented. Every candidate
     * is strictly [simpler], so shrinking cannot cycle.
     */
    @TruffleBoundary
    public static Object candidates(MezzeRecord args) {
        long[] d = longs(field(args, "draws"));
        List<long[]> out = new ArrayList<>();
        if (d.length > 1) out.add(java.util.Arrays.copyOf(d, d.length / 2));
        if (d.length > 0) out.add(java.util.Arrays.copyOf(d, d.length - 1));
        for (int i = 0; i < d.length; i++) {
            for (long v : new long[]{0, d[i] / 2, d[i] - 1}) {
                if (v < 0 || v >= d[i]) continue;
                long[] c = d.clone();
                c[i] = v;
                if (out.stream().noneMatch(o -> java.util.Arrays.equals(o, c))) out.add(c);
            }
        }
        Object[] seqs = new Object[out.size()];
        for (int i = 0; i < seqs.length; i++) seqs[i] = MezzeSeq.fromLongs(out.get(i));
        return MezzeSeq.fromArray(seqs);
    }

    /** Whether [a] is strictly simpler than [b]: shorter, or as long and lexicographically smaller. */
    @TruffleBoundary
    public static Object simpler(MezzeRecord args) {
        long[] a = longs(field(args, "a"));
        long[] b = longs(field(args, "b"));
        if (a.length != b.length) return a.length < b.length;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return a[i] < b[i];
        return false;
    }

    private static long[] longs(Object v) {
        if (!(v instanceof MezzeSeq<?> s)) throw new NativeTypeMismatchException("expected a sequence of Int");
        long[] out = new long[s.size()];
        for (int i = 0; i < out.length; i++) {
            Object e = s.get(i);
            if (!(e instanceof Long n)) throw new NativeTypeMismatchException("expected a sequence of Int");
            out[i] = n;
        }
        return out;
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
}
