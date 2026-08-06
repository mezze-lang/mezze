package com.mezze.interpreter.runtime;

import com.mezze.language.typedast.Hash;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-Engine {@code Hash → SlotEntry} table for eager whole-program BDSL
 * compilation (#391).
 *
 * <p>Phase 1 (resolve) walks the reachable call graph and calls
 * {@link #allocate} for every reachable hash to create empty slots. Phase 2
 * (emit) looks up each slot, BDSL-emits the function, and publishes the
 * resulting {@link com.oracle.truffle.api.RootCallTarget} into the slot via
 * {@link SlotEntry#publish}. Cross-function references in emitted code read
 * their target's {@code CallTarget} directly from the pre-allocated slot,
 * so forward references and mutual recursion become plain
 * {@code @CompilationFinal} reads rather than trips through a lazy stub.
 *
 * <p>Thread-safety: concurrent {@link #allocate} is safe via
 * {@code ConcurrentHashMap.computeIfAbsent}; publish happens on the compile
 * thread; reads happen from every execute thread.
 *
 * <p>Scaffold added in #391 Step 1 — no callers yet. Slot table sits
 * alongside the existing {@code MezzeContext.globals},
 * {@code MezzeLanguage.userStubCache}, and
 * {@code MezzeLanguage.stdlibCallTargetCache} maps that later steps will
 * collapse into it.
 */
public final class SlotTable {

    private final ConcurrentHashMap<Hash, SlotEntry> slots = new ConcurrentHashMap<>();

    /** Allocate a slot if not present; return the existing slot otherwise. */
    public SlotEntry allocate(Hash hash) {
        return slots.computeIfAbsent(hash, SlotEntry::new);
    }

    /** Look up a slot. Returns null if no slot has been allocated for this hash. */
    public SlotEntry get(Hash hash) {
        return slots.get(hash);
    }

    public boolean contains(Hash hash) {
        return slots.containsKey(hash);
    }

    public int size() {
        return slots.size();
    }

    public Set<Hash> reachableHashes() {
        return slots.keySet();
    }

    /**
     * Drop every slot. Called from {@code MezzeLanguage.disposeContext} so
     * eager slots don't leak across Contexts sharing a Language instance
     * (e.g. the test suite's sharedEngine — see #392).
     */
    public void clear() {
        slots.clear();
    }
}
