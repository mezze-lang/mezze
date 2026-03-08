package com.mezze.interpreter.v2.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Stores metadata about an effect implementation, including scope information
 * for scoped effects.
 *
 * This is used by HandleNode to properly manage the scope lifecycle:
 * 1. Call initializeScopeTarget at the start of perform
 * 2. Store the scope result for operations to access
 * 3. Call finalizeScopeTarget in a finally block when perform completes
 *
 * Design principles:
 * 1. Immutable after creation - safe for concurrent access
 * 2. All CallTargets are pre-resolved for zero-overhead lookup
 * 3. Null checks are compile-time constants after JIT
 */
public final class ImplSignature {

    /**
     * The effect name this impl handles (e.g., "DB", "Pool").
     */
    @CompilationFinal
    private final String effectName;

    /**
     * The performer/handler name (e.g., "PostgresDB", "PgPool").
     */
    @CompilationFinal
    private final String performerName;

    /**
     * Content hash for impl lookup and assumption tracking.
     */
    @CompilationFinal
    private final String contentHash;

    /**
     * Whether this impl has initialize_scope (making it a scoped effect).
     * If true, initializeScopeTarget is non-null.
     */
    @CompilationFinal
    private final boolean hasInitializeScope;

    /**
     * Whether this impl has finalize_scope.
     * If hasInitializeScope is true, this should also be true (enforced by typechecker).
     */
    @CompilationFinal
    private final boolean hasFinalizeScope;

    /**
     * CallTarget for initialize_scope operation.
     * Called at the START of perform to create the scope.
     * Returns a record that becomes available as 'scope' in operations.
     * Null if this is not a scoped effect.
     */
    @CompilationFinal
    private final CallTarget initializeScopeTarget;

    /**
     * CallTarget for finalize_scope operation.
     * Called in a finally block at the END of perform.
     * Has access to 'scope' via closure.
     * Null if this is not a scoped effect.
     */
    @CompilationFinal
    private final CallTarget finalizeScopeTarget;

    /**
     * Field names for the scope record (from initialize_scope return type).
     * Used to access scope fields in operations.
     * Null if not a scoped effect.
     */
    @CompilationFinal(dimensions = 1)
    private final String[] scopeFieldNames;

    /**
     * Create a non-scoped effect implementation signature.
     */
    public ImplSignature(
            String effectName,
            String performerName,
            String contentHash
    ) {
        this(effectName, performerName, contentHash, false, false, null, null, null);
    }

    /**
     * Create a scoped effect implementation signature.
     */
    public ImplSignature(
            String effectName,
            String performerName,
            String contentHash,
            boolean hasInitializeScope,
            boolean hasFinalizeScope,
            CallTarget initializeScopeTarget,
            CallTarget finalizeScopeTarget,
            String[] scopeFieldNames
    ) {
        this.effectName = effectName;
        this.performerName = performerName;
        this.contentHash = contentHash;
        this.hasInitializeScope = hasInitializeScope;
        this.hasFinalizeScope = hasFinalizeScope;
        this.initializeScopeTarget = initializeScopeTarget;
        this.finalizeScopeTarget = finalizeScopeTarget;
        this.scopeFieldNames = scopeFieldNames;
    }

    // Getters

    public String getEffectName() {
        return effectName;
    }

    public String getPerformerName() {
        return performerName;
    }

    public String getContentHash() {
        return contentHash;
    }

    public boolean hasInitializeScope() {
        return hasInitializeScope;
    }

    public boolean hasFinalizeScope() {
        return hasFinalizeScope;
    }

    public boolean isScopedEffect() {
        return hasInitializeScope;
    }

    public CallTarget getInitializeScopeTarget() {
        return initializeScopeTarget;
    }

    public CallTarget getFinalizeScopeTarget() {
        return finalizeScopeTarget;
    }

    public String[] getScopeFieldNames() {
        return scopeFieldNames;
    }

    /**
     * Builder pattern for cleaner construction.
     */
    public static Builder builder(String effectName, String performerName, String contentHash) {
        return new Builder(effectName, performerName, contentHash);
    }

    public static final class Builder {
        private final String effectName;
        private final String performerName;
        private final String contentHash;
        private boolean hasInitializeScope = false;
        private boolean hasFinalizeScope = false;
        private CallTarget initializeScopeTarget = null;
        private CallTarget finalizeScopeTarget = null;
        private String[] scopeFieldNames = null;

        private Builder(String effectName, String performerName, String contentHash) {
            this.effectName = effectName;
            this.performerName = performerName;
            this.contentHash = contentHash;
        }

        public Builder withInitializeScope(CallTarget target, String[] fieldNames) {
            this.hasInitializeScope = true;
            this.initializeScopeTarget = target;
            this.scopeFieldNames = fieldNames;
            return this;
        }

        public Builder withFinalizeScope(CallTarget target) {
            this.hasFinalizeScope = true;
            this.finalizeScopeTarget = target;
            return this;
        }

        public ImplSignature build() {
            return new ImplSignature(
                    effectName,
                    performerName,
                    contentHash,
                    hasInitializeScope,
                    hasFinalizeScope,
                    initializeScopeTarget,
                    finalizeScopeTarget,
                    scopeFieldNames
            );
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ImplSignature{");
        sb.append("effect=").append(effectName);
        sb.append(", performer=").append(performerName);
        if (hasInitializeScope) {
            sb.append(", scoped=true");
            if (scopeFieldNames != null) {
                sb.append(", scopeFields=[");
                for (int i = 0; i < scopeFieldNames.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(scopeFieldNames[i]);
                }
                sb.append("]");
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
