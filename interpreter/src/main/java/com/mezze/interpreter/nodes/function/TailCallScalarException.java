package com.mezze.interpreter.nodes.function;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * Tail-call exception carrying a typed payload — eliminates boxing for primitive params.
 *
 * Layout: paramKinds[i] tells the receiver where to read each param's new value:
 *   KIND_LONG    → longArgs[longIdx[i]]
 *   KIND_DOUBLE  → doubleArgs[doubleIdx[i]]
 *   KIND_OBJECT  → objArgs[objIdx[i]]
 *
 * Only used for fully-applied scalar self-recursive tail calls where the call
 * site can resolve callee param kinds at compile time. Mixed-kind callees today
 * still use the legacy {@link TailCallException} with Object[] payload.
 *
 * The {@code longArgs} / {@code doubleArgs} arrays may be null if no params of
 * that kind exist; the receiver does not read past parameterCount entries.
 *
 * Closures are not part of this payload — for self-recursive tail calls the
 * frame's closure slots are already bound and don't change.
 */
public final class TailCallScalarException extends ControlFlowException {

    private static final long serialVersionUID = 1L;

    /**
     * Mutable fields — this exception is meant to be allocated once per
     * call site and reused across throws. Truffle ControlFlowExceptions
     * intentionally do not preserve stack traces, so reuse is safe.
     *
     * Two modes signal which receiver path applies:
     *   - paramKinds == null: "single-bucket" mode. Exactly one of longArgs /
     *     doubleArgs is set; all params bind from that bucket positionally.
     *     Used by all-LONG / all-DOUBLE callees.
     *   - paramKinds != null: "mixed-kind" mode. paramKinds[i] tells the
     *     receiver which bucket to read for param i (LONG/DOUBLE/OBJECT
     *     advances the matching per-kind index).
     */
    public CallTarget callTarget;
    public long[] longArgs;
    public double[] doubleArgs;
    public Object[] objArgs;
    public byte[] paramKinds;

    public TailCallScalarException() {
        // Empty constructor — fields populated by fill*() before each throw.
    }

    /** Single-bucket fill (all-LONG or all-DOUBLE). paramKinds left null. */
    public TailCallScalarException fill(CallTarget target, long[] longArgs, double[] doubleArgs, Object[] objArgs) {
        this.callTarget = target;
        this.longArgs = longArgs;
        this.doubleArgs = doubleArgs;
        this.objArgs = objArgs;
        this.paramKinds = null;
        return this;
    }

    /** Mixed-kind fill — paramKinds tells the receiver which bucket to read per param. */
    public TailCallScalarException fillMixed(CallTarget target, byte[] paramKinds,
                                             long[] longArgs, double[] doubleArgs, Object[] objArgs) {
        this.callTarget = target;
        this.paramKinds = paramKinds;
        this.longArgs = longArgs;
        this.doubleArgs = doubleArgs;
        this.objArgs = objArgs;
        return this;
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }
}
