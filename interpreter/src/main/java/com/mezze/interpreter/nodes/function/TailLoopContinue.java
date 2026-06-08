package com.mezze.interpreter.nodes.function;

/**
 * Sentinel value returned by {@link TailContinueScalarNode} to signal that
 * the body should re-enter the loop with new param slot values (already
 * written to the frame).
 *
 * The new param values are NOT carried by this sentinel — they live in the
 * frame slots, written by {@code TailContinueScalarNode} before it returns.
 * The receiver in {@code MezzeFunctionRootNode.BodyRepeatingNode} just sees
 * this sentinel and continues the loop; no payload decoding, no exception
 * unwinding, no virtual call across an exception boundary.
 *
 * Unlike {@link TailCallScalarException}, this sentinel only works for
 * self-tail-calls and only inside functions whose body has been compiled in
 * "tail-loop mode" (see TruffleCompiler eligibility logic). Cross-function
 * tail calls or non-eligible bodies still use the throw-based path.
 */
public final class TailLoopContinue {

    /** The single instance returned by every tail-continue. */
    public static final TailLoopContinue INSTANCE = new TailLoopContinue();

    private TailLoopContinue() {}
}
