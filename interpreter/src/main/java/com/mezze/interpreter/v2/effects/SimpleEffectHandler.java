package com.mezze.interpreter.v2.effects;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Simple implementation of an effect handler.
 *
 * This is the standard way to create effect handlers in V2.
 * It maps operation names to EffectOperation implementations.
 *
 * JIT Optimization:
 * - Uses @CompilationFinal for immutable fields
 * - Operations map is unmodifiable after construction
 * - Operation lookup is O(1) via HashMap
 *
 * Usage:
 * <pre>
 * SimpleEffectHandler handler = new SimpleEffectHandler("Log", Map.of(
 *     "log", (args, ctx) -> { System.out.println(args); return MezzeUnit.INSTANCE; },
 *     "debug", (args, ctx) -> { System.err.println("[DEBUG] " + args); return MezzeUnit.INSTANCE; }
 * ));
 * </pre>
 */
public final class SimpleEffectHandler implements EffectHandler {

    @CompilationFinal
    private final String effectName;

    private final Map<String, EffectOperation> operations;

    public SimpleEffectHandler(String effectName, Map<String, EffectOperation> operations) {
        this.effectName = effectName;
        this.operations = Collections.unmodifiableMap(new HashMap<>(operations));
    }

    @Override
    public String getEffectName() {
        return effectName;
    }

    @Override
    public Map<String, EffectOperation> getOperations() {
        return operations;
    }

    @Override
    @TruffleBoundary
    public Object handleSync(String operation, Object arguments, EffectContext context) {
        EffectOperation effectOperation = operations.get(operation);
        if (effectOperation == null) {
            throw EffectExecutionException.operationNotFound(operation, effectName, context);
        }
        return effectOperation.execute(arguments, context);
    }

    @Override
    public boolean supportsOperation(String operation) {
        return operations.containsKey(operation);
    }

    @Override
    public Set<String> getAvailableOperations() {
        return operations.keySet();
    }

    @Override
    public String toString() {
        return "SimpleEffectHandler{" +
                "effect='" + effectName + '\'' +
                ", operations=" + operations.keySet() +
                '}';
    }

    // ========================
    // Builder for convenience
    // ========================

    /**
     * Create a builder for constructing a SimpleEffectHandler.
     */
    public static Builder builder(String effectName) {
        return new Builder(effectName);
    }

    /**
     * Builder for SimpleEffectHandler.
     */
    public static final class Builder {
        private final String effectName;
        private final Map<String, EffectOperation> operations = new HashMap<>();

        Builder(String effectName) {
            this.effectName = effectName;
        }

        /**
         * Add an operation with an EffectOperation implementation.
         */
        public Builder operation(String name, EffectOperation operation) {
            operations.put(name, operation);
            return this;
        }

        /**
         * Add an operation with a lambda.
         */
        public Builder operation(String name, java.util.function.BiFunction<Object, EffectContext, Object> impl) {
            operations.put(name, new LambdaEffectOperation(name, impl));
            return this;
        }

        public SimpleEffectHandler build() {
            return new SimpleEffectHandler(effectName, operations);
        }
    }

    /**
     * EffectOperation implementation using a lambda.
     */
    private static final class LambdaEffectOperation implements EffectOperation {
        private final String name;
        private final java.util.function.BiFunction<Object, EffectContext, Object> impl;

        LambdaEffectOperation(String name, java.util.function.BiFunction<Object, EffectContext, Object> impl) {
            this.name = name;
            this.impl = impl;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object execute(Object arguments, EffectContext context) {
            return impl.apply(arguments, context);
        }
    }
}
