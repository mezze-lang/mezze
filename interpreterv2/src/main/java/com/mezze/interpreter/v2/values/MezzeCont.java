package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.interop.TruffleObject;

/**
 * Opaque delimiter handle for delimited continuations - CANNOT BE FORGED.
 *
 * This is the V2 Truffle-optimized version.
 * User code cannot create these directly - only __native_continuation_delimit can.
 *
 * Optimizations:
 * - Final field for JIT constant folding
 * - Primitive identity comparison
 * - No heap allocation after creation
 */
public final class MezzeCont implements TruffleObject {

    @CompilationFinal
    private final long id;

    // Static counter for generating unique IDs (faster than UUID)
    private static long nextId = 0;

    private MezzeCont(long id) {
        this.id = id;
    }

    /**
     * Create a new delimiter - ONLY called by native continuation functions.
     */
    public static synchronized MezzeCont create() {
        return new MezzeCont(nextId++);
    }

    public long getId() {
        return id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeCont other)) return false;
        return this.id == other.id;
    }

    @Override
    public String toString() {
        return "MezzeCont@" + id;
    }
}
