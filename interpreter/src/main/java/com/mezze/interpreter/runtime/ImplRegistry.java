package com.mezze.interpreter.runtime;

import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.utilities.CyclicAssumption;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for impl assumptions - enables aggressive optimization.
 *
 * When an impl is resolved statically, we create an Assumption that
 * guards the optimization. If the impl is ever redefined (rare), the
 * assumption is invalidated and compiled code deoptimizes.
 *
 * This enables:
 * 1. StaticAbilityCallNode can inline impl operations completely
 * 2. No runtime dispatch overhead for monomorphic impls
 * 3. Automatic deoptimization when impls change
 *
 * Architecture:
 * - contentHash → CyclicAssumption (can be invalidated multiple times)
 * - getAssumption(hash) returns current valid Assumption
 * - invalidate(hash) invalidates and creates new Assumption
 */
public final class ImplRegistry {

    // Content hash → CyclicAssumption
    private final ConcurrentHashMap<Hash, CyclicAssumption> implAssumptions = new ConcurrentHashMap<>();

    // Singleton instance
    private static final ImplRegistry INSTANCE = new ImplRegistry();

    private ImplRegistry() {}

    public static ImplRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Get or create an Assumption for the given impl content hash.
     * The returned Assumption is valid until invalidate() is called.
     *
     * Use this in @Specialization(assumptions = ...) guards.
     */
    @TruffleBoundary
    public Assumption getAssumption(Hash contentHash) {
        CyclicAssumption cyclic = implAssumptions.computeIfAbsent(
            contentHash,
            k -> new CyclicAssumption("impl:" + k.getHex())
        );
        return cyclic.getAssumption();
    }

    /**
     * Invalidate an impl assumption.
     * Call this when an impl is redefined (hot reload, REPL, etc).
     *
     * This triggers deoptimization of all code that assumed the
     * impl was stable.
     */
    @TruffleBoundary
    public void invalidate(Hash contentHash) {
        CyclicAssumption cyclic = implAssumptions.get(contentHash);
        if (cyclic != null) {
            cyclic.invalidate();
        }
    }

    /**
     * Check if an impl has ever been registered.
     */
    @TruffleBoundary
    public boolean hasImpl(Hash contentHash) {
        return implAssumptions.containsKey(contentHash);
    }

    /**
     * Clear all assumptions (for testing).
     */
    @TruffleBoundary
    public void clear() {
        implAssumptions.clear();
    }

    /**
     * Get statistics.
     */
    public int getRegisteredImplCount() {
        return implAssumptions.size();
    }
}
