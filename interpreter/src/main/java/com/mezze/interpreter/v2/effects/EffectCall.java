package com.mezze.interpreter.v2.effects;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

import java.util.UUID;

/**
 * Represents an effect call with all necessary information.
 *
 * This is a value object that captures the state of an effect operation call.
 * It's used for tracking, debugging, and async coordination.
 *
 * Immutable and thread-safe by design.
 */
public final class EffectCall {

    /**
     * The effect name being called.
     */
    @CompilationFinal
    private final String effectName;

    /**
     * The operation name within the effect.
     */
    @CompilationFinal
    private final String operation;

    /**
     * The arguments passed to the operation.
     */
    private final Object arguments;

    /**
     * The effect context at the time of the call.
     */
    private final EffectContext context;

    /**
     * Unique identifier for this call.
     */
    @CompilationFinal
    private final String callId;

    /**
     * Timestamp when the call was made.
     */
    @CompilationFinal
    private final long timestamp;

    public EffectCall(
            String effectName,
            String operation,
            Object arguments,
            EffectContext context,
            String callId,
            long timestamp
    ) {
        this.effectName = effectName;
        this.operation = operation;
        this.arguments = arguments;
        this.context = context;
        this.callId = callId;
        this.timestamp = timestamp;
    }

    /**
     * Create a new effect call with auto-generated ID and timestamp.
     */
    public static EffectCall create(
            String effectName,
            String operation,
            Object arguments,
            EffectContext context
    ) {
        return new EffectCall(
                effectName,
                operation,
                arguments,
                context,
                UUID.randomUUID().toString(),
                System.currentTimeMillis()
        );
    }

    // Getters

    public String getEffectName() {
        return effectName;
    }

    public String getOperation() {
        return operation;
    }

    public Object getArguments() {
        return arguments;
    }

    public EffectContext getContext() {
        return context;
    }

    public String getCallId() {
        return callId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Get the age of this call in milliseconds.
     */
    public long getAge() {
        return System.currentTimeMillis() - timestamp;
    }

    /**
     * Get the fully qualified operation name.
     */
    public String getFullOperationName() {
        return effectName + "." + operation;
    }

    @Override
    public String toString() {
        return "EffectCall{" +
                "effect='" + effectName + '\'' +
                ", operation='" + operation + '\'' +
                ", callId='" + callId + '\'' +
                ", age=" + getAge() + "ms" +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EffectCall that = (EffectCall) o;
        return callId.equals(that.callId);
    }

    @Override
    public int hashCode() {
        return callId.hashCode();
    }
}
