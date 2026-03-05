package com.mezze.interpreter.v2.nodes.variable;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Read a local variable from the frame with fallback to global lookup.
 *
 * This node first tries to read from the frame slot. If the slot is null,
 * it falls back to looking up the variable in the global context.
 * This is needed for module-level functions that are referenced from
 * nested function closures but weren't captured at compile time.
 *
 * PERFORMANCE OPTIMIZATIONS:
 * 1. Uses @CompilationFinal instead of volatile - Graal handles initialization
 *    race-free, and after JIT compilation the memory barrier is eliminated
 * 2. Single state check (cachedGlobal != null) instead of two-field check
 * 3. Cached global value for O(1) subsequent lookups
 */
@NodeInfo(shortName = "read-fallback")
public final class ReadLocalWithFallbackNode extends MezzeNode {

    @CompilationFinal
    private final int slot;

    @CompilationFinal
    private final String variableName;

    // Content hash for content-addressed global lookup (null = use variableName)
    @CompilationFinal
    private final String contentHash;

    // OPTIMIZATION: Use @CompilationFinal instead of volatile
    // After JIT, this becomes a constant reference (no memory barrier)
    // transferToInterpreterAndInvalidate() ensures proper visibility during deopt
    @CompilationFinal
    private Object cachedGlobal;

    // Sentinel for "looked up but not found" case (avoids repeated lookups)
    private static final Object NOT_FOUND_SENTINEL = new Object();

    public ReadLocalWithFallbackNode(int slot, String variableName) {
        this(slot, variableName, null);
    }

    public ReadLocalWithFallbackNode(int slot, String variableName, String contentHash) {
        this.slot = slot;
        this.variableName = variableName;
        this.contentHash = contentHash;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // First try reading from frame slot
        Object value;
        try {
            value = frame.getObject(slot);
        } catch (com.oracle.truffle.api.frame.FrameSlotTypeException e) {
            // Slot might be uninitialized or have wrong type - fall back to global
            value = null;
        }

        if (value != null) {
            return value;
        }

        // Frame slot is null - fall back to global lookup
        return lookupGlobal();
    }

    private Object lookupGlobal() {
        // OPTIMIZATION: Single null check instead of two-field check
        // After JIT, cachedGlobal becomes a constant reference
        Object cached = cachedGlobal;
        if (cached != null) {
            // Fast path: already looked up
            return cached == NOT_FOUND_SENTINEL ? null : cached;
        }

        // Slow path: look up in context (only happens once)
        return lookupGlobalBoundary();
    }

    @TruffleBoundary
    private Object lookupGlobalBoundary() {
        MezzeContext context = MezzeContext.get(this);

        // If we have a content hash, use ONLY content hash lookup (strict mode)
        if (contentHash != null) {
            Object value = context.lookupGlobal(contentHash);
            if (value != null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                cachedGlobal = value;
                return value;
            }
            // Content hash provided but not found - this is an error
            throw new RuntimeException(
                "Content hash lookup failed for '" + variableName + "' with hash '" + contentHash + "'. " +
                "The function was not registered with this content hash."
            );
        }

        // No content hash - first try local lookup (for scoped effects like 'scope')
        // Note: Don't cache local lookups since they're dynamic (scoped)
        Object localValue = context.lookupLocal(variableName);
        if (localValue != null) {
            return localValue;
        }

        // Then try global lookup (for module-level definitions)
        Object value = context.lookupGlobal(variableName);

        // Cache for next time (even null result to avoid repeated lookups)
        // Only cache globals, not locals (which are dynamic)
        if (value != null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cachedGlobal = value;
        }

        return value;
    }

    /**
     * Create a read-with-fallback node.
     */
    public static ReadLocalWithFallbackNode create(int slot, String variableName) {
        return new ReadLocalWithFallbackNode(slot, variableName, null);
    }

    /**
     * Create a read-with-fallback node with content hash for precise lookup.
     */
    public static ReadLocalWithFallbackNode create(int slot, String variableName, String contentHash) {
        return new ReadLocalWithFallbackNode(slot, variableName, contentHash);
    }
}
