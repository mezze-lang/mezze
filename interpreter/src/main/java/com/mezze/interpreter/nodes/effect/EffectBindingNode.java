package com.mezze.interpreter.nodes.effect;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.continuation.EffectFiber;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.language.ast.EffectBindingKind;
import com.mezze.language.ast.ScopedEffectConstants;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Resolves a name contributed by the effect-handler runtime inside an op
 * handler body. The set of names is fixed:
 *
 * <ul>
 *   <li>{@code scope}  — the value {@code initialize_scope} returned for
 *       the surrounding perform. One per perform, on the MezzeContext.</li>
 *   <li>{@code resume} — handle to drive body forward for the current op
 *       invocation. Per-op, on the current fiber's effect-handle stack.</li>
 *   <li>{@code abort}  — handle to terminate the perform with a value.
 *       Per-op, on the current fiber's effect-handle stack.</li>
 * </ul>
 *
 * <p>The two storage locations exist because {@code resume}/{@code abort}
 * must stack across nested op calls (an inner op's handles must not
 * clobber the outer's), while {@code scope} is a single value for the
 * whole perform.
 */
@NodeInfo(shortName = "effect-binding")
public final class EffectBindingNode extends MezzeNode {

    @CompilationFinal
    private final EffectBindingKind kind;

    public EffectBindingNode(String name) {
        // Resolve name → enum at construction time, off the hot path. Bad names
        // are a compiler bug — the typechecker only emits EffectBindingIR for
        // resume/abort/scope. Throw eagerly so a corrupted IR never dispatches.
        EffectBindingKind resolved = EffectBindingKind.Companion.fromName(name);
        if (resolved == null) {
            throw new RuntimeException("Unknown effect-binding name: '" + name + "'");
        }
        this.kind = resolved;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Enum-tagged dispatch — no runtime string equality, no name lookup on
        // the hot path. See project_no_name_based_dispatch.
        switch (kind) {
            case RESUME:
            case ABORT:
                return lookupOnFiber(kind);
            case SCOPE:
                return lookupOnContext(kind.displayName());
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("Unreachable: unknown EffectBindingKind " + kind);
    }

    @TruffleBoundary
    private Object lookupOnFiber(EffectBindingKind which) {
        // resume/abort handles live on the fiber whose op handler is being
        // dispatched right now (driver pins it via enterDispatch). The
        // running fiber, if any, is a different concept and has a separate
        // (or empty) handle stack.
        EffectFiber fiber = EffectFiber.dispatching();
        if (fiber == null) {
            throw new RuntimeException(
                "'" + which.displayName() + "' referenced outside any op handler body. "
                + "These names are only available inside `op = { ... } -> body` "
                + "of an `impl X for Y where`.");
        }
        Object handle = (which == EffectBindingKind.RESUME)
            ? fiber.currentResume()
            : fiber.currentAbort();
        if (handle == null) {
            throw new RuntimeException(
                "'" + which.displayName() + "' is not in scope. This name is only bound "
                + "inside an op handler body during dispatch.");
        }
        return handle;
    }

    @TruffleBoundary
    private Object lookupOnContext(String which) {
        MezzeContext context = MezzeContext.get(this);
        Object value = context.lookupScopeVar(which);
        if (value == null) {
            throw new RuntimeException("Scope variable '" + which + "' not found");
        }
        return value;
    }
}
