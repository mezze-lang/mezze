package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.language.truffleir.FunctionIR;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Lazy stub root node — minted eagerly at IR-registration time (see
 * {@link MezzeLanguage#getOrMintUserStub}), giving BDSL emit a stable
 * {@link CallTarget} to bake as a {@link com.oracle.truffle.api.dsl.ConstantOperand}
 * for {@code MakeLambda} / {@code CallDirect} sites that reference the
 * function before it's compiled.
 *
 * <p>On first {@link #execute}, the stub inflates the real implementation
 * via {@link MezzeLanguage#compileUserIRForStub} (AST-path, bypassing
 * BDSL to avoid re-entering the singleton {@code bdslCompiler} mid-emit),
 * caches the resulting {@link CallTarget}, and forwards the current call
 * through an {@link IndirectCallNode}. Subsequent executes go straight
 * to the cached target.
 *
 * <p>Why this works where the previous {@code MakeLambdaByHash}-style
 * attempts failed (see historical comment in {@code BytecodeCompiler.java}
 * around the MakeLambdaIR reject branch):
 * <ul>
 *   <li>Stable identity: the stub is one object per hash, minted at
 *       IR-registration time. Effect dispatch and downstream caching
 *       that key on {@code CallTarget} identity see the same object
 *       whether the callee has been inflated yet or not.</li>
 *   <li>No runtime {@code getCurrentContext}: the stub holds a direct
 *       reference to {@link MezzeLanguage} + the {@link FunctionIR}
 *       hash, so cross-thread execution (Loom-spawned) doesn't need a
 *       polyglot context to resolve.</li>
 *   <li>Recursive let: because stubs are minted at registration time —
 *       before any body compiles — a recursive-let's inner function
 *       already has a stub baked in when the outer function's
 *       {@code MakeLambda} emits.</li>
 * </ul>
 */
public final class LazyStubRootNode extends RootNode {
    private final MezzeLanguage lang;
    private final com.mezze.language.typedast.Hash hash;
    private final String name;

    @CompilerDirectives.CompilationFinal
    private volatile CallTarget inflated;

    /**
     * Once inflated, replace the forwarding node with a cached
     * DirectCallNode on the concrete target. DirectCallNode lets Truffle
     * inline the callee and profile arg shapes across the boundary —
     * matching the semantics of BDSL's CallDirectN.doCached ({@code
     * @Cached("create(target)")}). Using IndirectCallNode instead
     * breaks per-CallTarget argument profiling that STM and lazy-effect
     * force paths depend on.
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
        return fwd.call(frame.getArguments());
    }

    @CompilerDirectives.TruffleBoundary
    private synchronized DirectCallNode inflate() {
        DirectCallNode fwd = forward;
        if (fwd != null) return fwd;
        // Prefer the pipeline-produced CallTarget if the runtime already
        // has one for this hash — that's the canonical version everything
        // else in the process uses. Only fall back to AST-only compile
        // if no CT is available yet (which shouldn't happen at inflate
        // time in practice because the pipeline compiles user fns before
        // returning control to the caller).
        CallTarget ct = lang.getCachedStdlibCallTarget(hash);
        if (ct == null) {
            RootNode real = lang.compileUserIRForStub(hash);
            if (real == null) {
                throw new IllegalStateException(
                    "LazyStubRootNode[" + name + " / " + hash.getHex() + "] "
                    + "cannot inflate: FunctionIR not registered on any context");
            }
            ct = real.getCallTarget();
        }
        inflated = ct;
        fwd = insert(DirectCallNode.create(ct));
        forward = fwd;
        return fwd;
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
