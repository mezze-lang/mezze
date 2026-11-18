package com.mezze.interpreter.nodes.continuation;

import com.oracle.truffle.api.interop.TruffleObject;

/**
 * C4: a native loop whose callback SUSPENDED. {@link #inner} is what the callback
 * handed back (a {@code ContinuationResult}, or a nested suspension); {@link #resume}
 * re-enters the native's loop with the callback's eventual value and returns the
 * loop's final result — or another {@code NativeSuspension} if a later callback
 * suspends too. The task machine treats it as one more frame in a task's stack.
 *
 * <p>Nothing is allocated unless a callback actually suspends; the fast path of a
 * resumable native is one {@code instanceof} per callback result.
 */
public final class NativeSuspension implements TruffleObject {
    @FunctionalInterface
    public interface Resume { Object with(Object callbackResult); }

    public final Object inner;
    public final Resume resume;

    public NativeSuspension(Object inner, Resume resume) {
        this.inner = inner;
        this.resume = resume;
    }

    /** A/B kill switch for the per-callback-result test (MEZZE_RESUMABLE_NATIVES=0);
     *  a static final the JIT folds, so OFF is exactly the pre-C4 loop. */
    public static final boolean ENABLED = !"0".equals(System.getenv("MEZZE_RESUMABLE_NATIVES"));

    /** Is this value a suspension a native must stop at? */
    public static boolean isSuspension(Object v) {
        return ENABLED && (v instanceof com.oracle.truffle.api.bytecode.ContinuationResult || v instanceof NativeSuspension);
    }

    /** Apply {@code f} to a native's eventual result, through any number of suspensions. */
    public static Object mapResult(Object r, java.util.function.Function<Object, Object> f) {
        if (r instanceof NativeSuspension ns) return new NativeSuspension(ns.inner, v -> mapResult(ns.resume.with(v), f));
        return f.apply(r);
    }

    @Override public String toString() { return "NativeSuspension(" + inner + ")"; }
}
