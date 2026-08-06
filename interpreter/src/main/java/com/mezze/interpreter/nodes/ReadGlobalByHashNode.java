package com.mezze.interpreter.nodes;

import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeSuspend;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Read a global definition from the MezzeContext by content hash.
 *
 * Auto-evaluates zero-param zero-capture "value thunks" (like trig::pi, trig::e).
 * These are top-level values compiled as zero-param FunctionIRs. When read in
 * non-call position (e.g., `pi / 2.0`), the thunk is called to get the actual
 * value instead of returning the MezzeFunction wrapper.
 */
@NodeInfo(shortName = "read-global-by-hash")
public final class ReadGlobalByHashNode extends MezzeNode {

    @CompilationFinal
    private final Hash hash;

    // For forcing MezzeSuspend values
    @com.oracle.truffle.api.nodes.Node.Child
    private IndirectCallNode suspendedCallNode;

    public ReadGlobalByHashNode(Hash hash) {
        this.hash = hash;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return lookupInContext();
    }

    @TruffleBoundary
    private Object lookupInContext() {
        MezzeContext context = MezzeContext.get(this);
        // #391 Step 4 — under MEZZE_EAGER, prefer the SlotTable. Published
        // slots know how to force thunks via SlotEntry.resolveValue.
        if ("1".equals(System.getenv("MEZZE_EAGER"))) {
            var lang = com.mezze.interpreter.runtime.MezzeLanguage.get(this);
            var slot = lang.getSlotTable().get(hash);
            if (slot != null && slot.isPublished()) {
                Object slotValue = slot.resolveValue(hash.getHex());
                if (slotValue instanceof MezzeSuspend suspended) {
                    slotValue = forceSuspended(suspended);
                }
                return slotValue;
            }
        }
        Object value = context.lookupGlobal(hash);
        // Fall back to Language-level lazy compilation (for functions referenced by hash)
        if (value == null) {
            var lang = com.mezze.interpreter.runtime.MezzeLanguage.get(this);
            var ct = lang.getCachedStdlibCallTarget(hash);
            if (ct != null) {
                value = MezzeFunction.create(ct, hash.getHex());
            }
        }
        if (value == null) {
            throw new RuntimeException("Global hash not found: '" + hash.getHex() + "'");
        }
        // Auto-evaluate value thunks: zero-param zero-capture functions that wrap
        // top-level values (like trig::pi = 3.14...). These are compiled as FunctionIRs
        // but should return their computed value when read, not the function itself.
        if (value instanceof MezzeFunction fn) {
            value = autoEvaluateThunk(fn, context);
        }
        // Force MezzeSuspend values when inside an active handler scope.
        // Lazy effects: suspended computations from outside a perform block
        // are evaluated when read inside one. force() returns the suspension
        // unchanged when no fiber is current, so this is safe to call
        // unconditionally.
        if (value instanceof MezzeSuspend suspended) {
            value = forceSuspended(suspended);
        }
        return value;
    }

    @TruffleBoundary
    private Object forceSuspended(MezzeSuspend suspended) {
        if (suspendedCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            suspendedCallNode = insert(IndirectCallNode.create());
        }
        return suspended.force(suspendedCallNode);
    }

    /**
     * If the function is a zero-param zero-capture thunk (value definition),
     * call it to get the actual value. Cache the result in the global context
     * so subsequent lookups are instant.
     *
     * <p>Handles both AST-compiled thunks ({@link MezzeRootNode}) and BDSL-
     * compiled thunks ({@link com.mezze.interpreter.bytecode.MezzeBytecodeRootNode}
     * / {@link com.mezze.interpreter.bytecode.BdslTrampolineRootNode}).
     * BDSL roots don't carry paramCount/closureCount metadata, so we lean
     * on the closure-values check + MezzeFunction's name and trust the
     * FunctionIR pipeline to have shaped these consistently — a BDSL-
     * compiled thunk always has empty closure values by construction.
     */
    @TruffleBoundary
    private Object autoEvaluateThunk(MezzeFunction fn, MezzeContext context) {
        CallTarget ct = fn.getCallTarget();
        if (!(ct instanceof RootCallTarget rct)) return fn;
        if (fn.getClosureValues().length != 0) return fn;
        RootNode root = rct.getRootNode();
        boolean isThunk = false;
        if (root instanceof MezzeRootNode mrn) {
            isThunk = mrn.getClosureCount() == 0 && mrn.getParameterCount() == 0;
        } else if (root instanceof com.mezze.interpreter.bytecode.BdslTrampolineRootNode t) {
            // #374 — must check paramCount, not assume zero. A top-level
            // `let f = {} -> ...` lambda has paramCount == 1 (empty record
            // slot); auto-evaluating it here would blindly invoke the
            // lambda every time a caller looked it up, causing infinite
            // recursion for `perform H in f {}`-shaped programs.
            isThunk = t.getClosureCount() == 0 && t.getParameterCount() == 0;
        } else if (root instanceof com.mezze.interpreter.bytecode.MezzeBytecodeRootNode) {
            // Bare BDSL bytecode roots (no trampoline wrapper) are only
            // produced by BytecodeCompiler when paramCount == 0 AND there
            // are no tail-calls (see needsTrampoline gate in
            // BytecodeCompiler.compile). Safe to treat as a thunk.
            isThunk = true;
        }
        if (isThunk) {
            try {
                Object result = ct.call(com.mezze.interpreter.values.MezzeUnit.INSTANCE);
                // Cache the evaluated value so we don't re-evaluate on every access
                context.defineGlobal(hash, result);
                return result;
            } catch (Exception e) {
                // If evaluation fails, return the function as-is (it might be
                // a function that legitimately takes no params but has side effects)
                return fn;
            }
        }
        return fn;
    }
}
