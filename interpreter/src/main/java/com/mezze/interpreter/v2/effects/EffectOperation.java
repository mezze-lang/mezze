package com.mezze.interpreter.v2.effects;

/**
 * Interface for individual effect operations.
 *
 * An EffectOperation represents a single operation within an effect handler.
 * For example, the "Log" effect might have operations like "log", "debug", "error".
 *
 * V2 simplification: Only synchronous execution is supported.
 * Virtual threads make blocking operations efficient, eliminating the need
 * for async/await patterns at the effect operation level.
 *
 * JIT Optimization:
 * - Implementations should be final classes where possible
 * - Avoid allocations in execute() for hot paths
 * - Consider caching computed values in @CompilationFinal fields
 */
public interface EffectOperation {

    /**
     * Get the operation name.
     */
    String getName();

    /**
     * Execute the operation synchronously.
     *
     * @param arguments The arguments to the operation (may be MezzeRecord, or any value)
     * @param context The current effect context
     * @return The result of the operation
     * @throws EffectExecutionException if execution fails
     */
    Object execute(Object arguments, EffectContext context);
}
