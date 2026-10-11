package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * S-23: which branch sites an evaluation actually REACHED.
 *
 * `mez sample` pins its leading rows to the target's `match` arms, so those arms
 * are covered by construction — a report derived from the generated arguments
 * would say "all covered" every time. What it cannot reach is what is worth
 * reporting: a GUARD (`Circle { r } if r > 10` pins the constructor, not the
 * guard) and a COMPUTED SCRUTINEE (`match xs.len is …`, which would need the
 * sampler to invert the computation). Those are only knowable by watching the
 * program run.
 *
 * <p>So the CLI rewrites the IR it is about to ship, dropping a {@code hit} at
 * each branch, and this is where the hits land. Normal builds emit none of it:
 * the rewrite happens on IR that only this command produces, so no effect row
 * anywhere changes and nothing is paid for by an ordinary program.
 *
 * <p>Deliberately NOT an effect in the language. If every `if` performed one,
 * every function containing an `if` would carry it in its row and purity would
 * stop meaning anything. This is a compilation mode, not a feature.
 *
 * <p>State is per-process and reset per evaluation rather than per thread: a
 * single sample batch is one invocation that may fan out (`parallel`, `spawn`),
 * and a branch reached on a child thread was still reached. {@link
 * #takeHits()} drains, so two evaluations cannot see each other's sites.
 */
public final class CoverageNatives {

    private CoverageNatives() {}

    /**
     * Sites reached since the last drain.
     *
     * A SET because the question is "was this reached", not "how often" — a count
     * would invite reading the numbers as a profile, which the sampling order
     * makes meaningless. Insertion-ordered so a report can show sites in the order
     * the run first met them, which is the order a reader followed the code in.
     *
     * Synchronised rather than concurrent: writes are one small add per branch of
     * instrumented code only, and a LinkedHashSet keeps the ordering that a
     * concurrent set would not.
     */
    private static final Set<Integer> HITS = new LinkedHashSet<>();

    /** Record that site {@code id} was reached. The instrumented call. */
    @TruffleBoundary
    public static Object observeHit(MezzeRecord args) {
        int index = args.getFieldIndex("site");
        if (index < 0) {
            throw new NativeTypeMismatchException("Missing field 'site'");
        }
        Object val = args.getField(index);
        if (!(val instanceof Long site)) {
            throw new NativeTypeMismatchException(
                "'site' must be an Int, got " + (val == null ? "null" : val.getClass().getSimpleName()));
        }
        synchronized (HITS) {
            HITS.add(site.intValue());
        }
        return MezzeRecord.empty();
    }

    /**
     * Drain the sites reached, for attaching to an eval result.
     *
     * Drains rather than peeks so the next evaluation starts clean — a report that
     * silently included a previous run's sites would overstate coverage, which is
     * the one direction a coverage report must never err in.
     */
    @TruffleBoundary
    public static List<Integer> takeHits() {
        synchronized (HITS) {
            if (HITS.isEmpty()) return List.of();
            List<Integer> out = new ArrayList<>(HITS);
            HITS.clear();
            return out;
        }
    }
}
