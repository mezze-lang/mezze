package com.mezze.interpreter.bytecode;

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
 * <p>On first {@link #execute}, the stub inflates: it looks up the
 * MezzeFunction via {@link MezzeContext#lookupGlobal(com.mezze.language.typedast.Hash)}
 * (or {@link MezzeLanguage#getCachedStdlibCallTarget}), captures its
 * closure values, and forwards through a cached {@link DirectCallNode}.
 * Subsequent executes bake in the closure prefix and forward.
 *
 * <p><b>ABI adjustment for CallDirect caller</b>: BDSL's CallDirectN op
 * passes args as {@code [marker?, arg0, ..., argN-1]} (marker present
 * only for scalar-1arg calls; see BytecodeCompiler.needsScalarMarker).
 * The AST callee expects {@code [closure0, ..., closureK-1, marker?,
 * arg0, ..., argN-1]}. The stub prepends its cached closure values so
 * the callee's frame layout matches. Without this, args land in
 * closure slots and closures are missing entirely — the root cause of
 * the earlier CallDirect stub attempts silently returning wrong values
 * (STM modify returning 10 instead of 15, LazyEffects closure returning
 * MezzeFunction instead of MezzeSuspended).
 */
public final class LazyStubRootNode extends RootNode {
    private final MezzeLanguage lang;
    private final com.mezze.language.typedast.Hash hash;
    private final String name;

    @CompilerDirectives.CompilationFinal
    private volatile CallTarget inflated;

    /**
     * Closure values captured at inflate time. Empty for top-level
     * fns. Prepended to args on every call so the AST callee sees the
     * ABI layout it expects.
     */
    @CompilerDirectives.CompilationFinal(dimensions = 1)
    private Object[] cachedClosures;

    /**
     * Cached DirectCallNode on the inflated CallTarget. DirectCallNode
     * (rather than IndirectCallNode) lets Truffle inline the callee
     * and profile arg shapes across the stub boundary — matches
     * CallDirectN.doCached's {@code @Cached("create(target)")}
     * pattern, which effect dispatch relies on.
     */
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
        Object[] rawArgs = frame.getArguments();
        Object[] finalArgs = prependClosures(rawArgs);
        return fwd.call(finalArgs);
    }

    private Object[] prependClosures(Object[] rawArgs) {
        Object[] closures = cachedClosures;
        if (closures == null || closures.length == 0) {
            return rawArgs;
        }
        Object[] combined = new Object[closures.length + rawArgs.length];
        System.arraycopy(closures, 0, combined, 0, closures.length);
        System.arraycopy(rawArgs, 0, combined, closures.length, rawArgs.length);
        return combined;
    }

    @CompilerDirectives.TruffleBoundary
    private synchronized DirectCallNode inflate() {
        DirectCallNode fwd = forward;
        if (fwd != null) return fwd;

        // Resolve the MezzeFunction — the runtime's canonical wrapper
        // that holds both the CallTarget and the captured closure
        // values. Try the per-context globals first (user let-bound
        // fns register here); fall back to language-level stdlib CT
        // cache (wrap as MezzeFunction for uniform closure handling —
        // stdlib fns have no closures, so empty).
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
        // 1. Per-context globals — user let-bound fns are here after
        //    PipelineRunner.compileFromIR walks the module.
        try {
            MezzeContext ctx = lang.getCurrentContext();
            if (ctx != null) {
                Object global = ctx.lookupGlobal(hash);
                if (global instanceof MezzeFunction f) return f;
            }
        } catch (Throwable ignored) {}
        // 2. Language-level stdlib CallTarget cache — stdlib fns bake in
        //    with no closures.
        CallTarget ct = lang.getCachedStdlibCallTarget(hash);
        if (ct != null) {
            return MezzeFunction.create(ct, hash.getHex());
        }
        // 3. Compile from IR as last resort. Bare CallTarget, no closures.
        RootNode real = lang.compileUserIRForStub(hash);
        if (real != null) {
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
