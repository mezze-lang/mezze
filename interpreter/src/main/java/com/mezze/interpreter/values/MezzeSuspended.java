package com.mezze.interpreter.values;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.control.BlockNode;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Suspended computation - a deferred effect call waiting for handlers.
 *
 * This implements the lazy effects model where:
 * - Inside perform: effects execute immediately (hot path)
 * - Outside perform: effects return MezzeSuspended (cold path)
 *
 * The suspended computation can later be executed with perform,
 * which provides the missing handlers.
 *
 * Example:
 *   let result = myEffectfulFunc {}  // Returns MezzeSuspended with unhandled effects
 *   perform Console in result        // Executes with Console handler
 */
public final class MezzeSuspended implements TruffleObject {

    @CompilationFinal
    private final MezzeThunk computation;

    @CompilationFinal(dimensions = 1)
    private final Hash[] unhandledEffects;

    // Cached set view for faster contains checks
    @CompilationFinal
    private Set<Hash> unhandledEffectsSet;

    // Handlers already captured (for partial handling)
    private final Map<Hash, Object> capturedHandlers;

    public MezzeSuspended(MezzeThunk computation, Set<Hash> unhandledEffects) {
        this(computation, unhandledEffects, Collections.emptyMap());
    }

    public MezzeSuspended(MezzeThunk computation, Set<Hash> unhandledEffects, Map<Hash, Object> capturedHandlers) {
        this.computation = computation;
        this.unhandledEffects = unhandledEffects.toArray(new Hash[0]);
        this.capturedHandlers = capturedHandlers;
    }

    public MezzeThunk getComputation() {
        return computation;
    }

    public Set<Hash> getUnhandledEffects() {
        if (unhandledEffectsSet == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            unhandledEffectsSet = new HashSet<>();
            for (Hash effect : unhandledEffects) {
                unhandledEffectsSet.add(effect);
            }
            unhandledEffectsSet = Collections.unmodifiableSet(unhandledEffectsSet);
        }
        return unhandledEffectsSet;
    }

    public Map<Hash, Object> getCapturedHandlers() {
        return capturedHandlers;
    }

    public boolean hasUnhandledEffect(Hash effectHash) {
        for (Hash effect : unhandledEffects) {
            if (effect.equals(effectHash)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if all effects are handled (empty unhandled set).
     */
    public boolean isFullyHandled() {
        return unhandledEffects.length == 0;
    }

    /**
     * Get the effect name from the underlying thunk.
     */
    public String getEffectName() {
        return computation.getEffectName();
    }

    /**
     * Get the operation name from the underlying thunk.
     */
    public String getOperationName() {
        return computation.getOperationName();
    }

    /**
     * Force this suspended computation under the currently-active effect
     * handler chain, if any.
     *
     * <p>Under the new dispatch design (post-rewrite), effect-handler
     * lookup happens via the current fiber, not via a context-wide handler
     * map. So forcing a deferred effect call just re-issues the op on the
     * current fiber: {@code fiber.suspend(new OpCall(opName, args))}.
     * The surrounding {@code HandleNode}'s driver dispatches it like any
     * other op call.
     *
     * <p>If no fiber is current (we're still outside any {@code perform}),
     * returns {@code this} so the suspension persists.
     */
    @TruffleBoundary
    public Object force(IndirectCallNode callNode) {
        com.mezze.interpreter.nodes.continuation.EffectFiber fiber =
            com.mezze.interpreter.nodes.continuation.EffectFiber.current();
        if (fiber == null) {
            return this;
        }
        if (computation instanceof BlockNode.ComposedBlockThunk composedThunk) {
            return forceComposedBlock(composedThunk, fiber, callNode);
        }
        if (computation.isDeferredEffectCall()) {
            // Forward the effect content hash so the dispatcher can
            // disambiguate when two effects in scope declare an op with
            // the same simple name (#246). Dropping it here would silently
            // misroute on force, reintroducing the bug for any op that
            // was deferred outside a perform and forced inside one.
            return fiber.suspend(new com.mezze.interpreter.nodes.continuation.OpCall(
                computation.getOperationName(),
                computation.getArguments(),
                computation.getEffectContentHash()
            ));
        }
        // Regular function thunk — no effect to dispatch, just execute.
        return computation.execute(callNode);
    }

    /**
     * Force a composed block thunk under the current fiber: execute the
     * original effect (which yields to the driver), then run the
     * remaining statements that were closed over the original
     * MaterializedFrame.
     */
    @TruffleBoundary
    private Object forceComposedBlock(
            BlockNode.ComposedBlockThunk composedThunk,
            com.mezze.interpreter.nodes.continuation.EffectFiber fiber,
            IndirectCallNode callNode) {
        MezzeThunk originalThunk = composedThunk.getOriginalThunk();
        MezzeNode[] remainingStatements = composedThunk.getRemainingStatements();
        MaterializedFrame frame = composedThunk.getMaterializedFrame();

        Object effectResult;
        if (originalThunk instanceof BlockNode.ComposedBlockThunk nested) {
            effectResult = forceComposedBlock(nested, fiber, callNode);
        } else if (originalThunk.isDeferredEffectCall()) {
            effectResult = fiber.suspend(new com.mezze.interpreter.nodes.continuation.OpCall(
                originalThunk.getOperationName(),
                originalThunk.getArguments(),
                originalThunk.getEffectContentHash()
            ));
        } else {
            effectResult = originalThunk.execute(callNode);
        }

        // If the effect result is still suspended (no fiber was current at
        // the recursive force), preserve the composition for later.
        if (effectResult instanceof MezzeSuspended ms) {
            return new MezzeSuspended(
                new BlockNode.ComposedBlockThunk(
                    ms.getComputation(),
                    remainingStatements,
                    frame
                ),
                ms.getUnhandledEffects()
            );
        }

        Object result = MezzeUnit.INSTANCE;
        for (MezzeNode stmt : remainingStatements) {
            result = stmt.execute(frame);
            if (result instanceof MezzeSuspended) {
                return result;
            }
        }
        return result;
    }

    /**
     * Check if a value is a suspended computation.
     */
    public static boolean isSuspended(Object value) {
        return value instanceof MezzeSuspended;
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("<suspended <");
        for (int i = 0; i < unhandledEffects.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(unhandledEffects[i].getHex());
        }
        sb.append(">>");
        return sb.toString();
    }
}
