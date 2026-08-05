package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.nodes.function.MezzeFunctionRootNode;
import com.mezze.interpreter.nodes.function.ScalarABIMarker;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.language.truffleir.FunctionIR;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Lazy stub root node — minted eagerly at IR-registration time (see
 * {@link MezzeLanguage#getOrMintUserStub}), giving BDSL emit a stable
 * {@link CallTarget} to bake as a {@link com.oracle.truffle.api.dsl.ConstantOperand}
 * for {@code MakeLambda} / {@code CallDirect} sites that reference the
 * function before it's compiled.
 *
 * <p><b>Caller-side ABI</b>: the stub is treated as a BDSL callee at
 * emit time (see {@code BytecodeCompiler.isBdslCallee}), so the caller
 * does NOT prepend closure values or a scalar-ABI marker. The stub
 * receives raw arg values.
 *
 * <p><b>Stub-side ABI</b>: at inflate time the stub inspects the
 * resolved callee's RootNode kind:
 * <ul>
 *   <li>AST callee ({@code MezzeFunctionRootNode}): the stub prepends
 *       the closure values it captured from the MezzeFunction, and
 *       inserts a {@link ScalarABIMarker} when the callee has exactly
 *       one parameter (so {@code normalizeArgs} recognises the scalar
 *       shape and doesn't try to unpack the value as a record).</li>
 *   <li>BDSL callee (bare {@code MezzeBytecodeRootNode} or
 *       {@code BdslTrampolineRootNode}): the stub still prepends the
 *       closure values (BDSL callees also expect
 *       {@code [closure..., args...]}), but no marker — BDSL callees
 *       use flat scalar layout and would misread the marker as a
 *       value.</li>
 * </ul>
 * This mirrors what {@code CallDirectScalarNode} does inline for AST
 * callers, and what {@code CallDirectN.doCached} does inline for BDSL
 * callers.
 */
public final class LazyStubRootNode extends RootNode {
    private final MezzeLanguage lang;
    private final com.mezze.language.typedast.Hash hash;
    private final String name;

    @CompilerDirectives.CompilationFinal
    private volatile CallTarget inflated;

    /**
     * Closure values captured at inflate time. Empty for top-level
     * fns. Prepended to args on every call so the callee sees the
     * ABI layout it expects.
     */
    @CompilerDirectives.CompilationFinal(dimensions = 1)
    private Object[] cachedClosures;

    @Child
    private DirectCallNode forward;

    public LazyStubRootNode(MezzeLanguage lang, com.mezze.language.typedast.Hash hash, String name) {
        super(lang, FrameDescriptor.newBuilder().build());
        this.lang = lang;
        this.hash = hash;
        this.name = name;
    }

    /** Package-visible for the language-level cache to detect that this is a stub. */
    public com.mezze.language.typedast.Hash getHash() {
        return hash;
    }

    /** Returns the inflated target if already resolved, else null. */
    public CallTarget getInflatedTarget() {
        return inflated;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        DirectCallNode fwd = forward;
        if (fwd == null) {
            fwd = inflate();
        }
        // Caller (BDSL CallDirectN) treats stub as an AST callee (because
        // isBdslCallee returns false for LazyStubRootNode). So caller
        // already prepended the marker for scalar-1arg. But it did NOT
        // prepend closures — that's the caller-side node's job (which
        // BDSL doesn't do for CallDirect). Prepend closures here.
        return fwd.call(prependClosures(frame.getArguments()));
    }

    private Object[] prependClosures(Object[] rawArgs) {
        Object[] closures = cachedClosures;
        int closureLen = closures.length;
        if (closureLen == 0) return rawArgs;
        Object[] combined = new Object[closureLen + rawArgs.length];
        System.arraycopy(closures, 0, combined, 0, closureLen);
        System.arraycopy(rawArgs, 0, combined, closureLen, rawArgs.length);
        return combined;
    }

    @CompilerDirectives.TruffleBoundary
    private synchronized DirectCallNode inflate() {
        DirectCallNode fwd = forward;
        if (fwd != null) return fwd;

        MezzeFunction fn = resolveMezzeFunction();
        if (fn == null) {
            throw new IllegalStateException(
                "LazyStubRootNode[" + name + " / " + hash.getHex() + "] "
                + "cannot inflate: no MezzeFunction registered for hash");
        }

        CallTarget ct = fn.getCallTarget();
        Object[] closures = fn.getClosureValues();
        cachedClosures = closures != null ? closures : new Object[0];
        inflated = ct;
        fwd = insert(DirectCallNode.create(ct));
        forward = fwd;
        return fwd;
    }

    @CompilerDirectives.TruffleBoundary
    private MezzeFunction resolveMezzeFunction() {
        CallTarget selfCT = this.getCallTarget();
        // 1. Per-context globals — user let-bound fns land here after
        //    PipelineRunner.compileFromIR walks the module.
        //    #374 fix: SKIP if the global's CallTarget IS this very stub.
        //    That happens when RegisterGlobal published a MezzeFunction
        //    wrapping the stub CT (because MakeLambda under compileEntryFn
        //    uses the stub CT baked as its ConstantOperand — the stub
        //    is what compiledCallTargets held at emit time). Inflating
        //    to that would forward-call ourselves = infinite recursion.
        try {
            MezzeContext ctx = lang.getCurrentContext();
            if (ctx != null) {
                Object global = ctx.lookupGlobal(hash);
                if (global instanceof MezzeFunction f && f.getCallTarget() != selfCT) {
                    return f;
                }
            }
        } catch (Throwable ignored) {}
        // 2. Language-level stdlib CallTarget cache — stdlib fns have
        //    no closures.
        CallTarget ct = lang.getCachedStdlibCallTarget(hash);
        if (ct != null && ct != selfCT) {
            return MezzeFunction.create(ct, hash.getHex());
        }
        // 3. Compile from IR as last resort — bare CT, no closures.
        RootNode real = lang.compileUserIRForStub(hash);
        if (real != null && real.getCallTarget() != selfCT) {
            return MezzeFunction.create(real.getCallTarget(), hash.getHex());
        }
        return null;
    }

    @Override
    public String getName() {
        return name + "$stub";
    }

    @Override
    public String toString() {
        return "LazyStub[" + name + "]";
    }
}
