package com.mezze.interpreter.v2.effects;

import java.util.Map;
import java.util.Set;

/**
 * Interface for effect handlers - supports synchronous operations.
 *
 * Effect handlers implement the actual behavior of effect operations.
 * They are registered with effect names and looked up at runtime when
 * effect operations are performed.
 *
 * V2 simplification: Unlike V1, we only support synchronous operations
 * because virtual threads (Project Loom) allow blocking without penalty.
 * This makes the API cleaner and more JIT-friendly.
 *
 * Usage pattern:
 * <pre>
 * EffectHandler logHandler = new SimpleEffectHandler("Log", Map.of(
 *     "log", new EffectOperation() {
 *         public String getName() { return "log"; }
 *         public Object execute(Object args, EffectContext ctx) {
 *             System.out.println(args);
 *             return MezzeUnit.INSTANCE;
 *         }
 *     }
 * ));
 * </pre>
 */
public interface EffectHandler {

    /**
     * Get the name of the effect this handler handles.
     */
    String getEffectName();

    /**
     * Get all operations this handler supports.
     * Key is operation name, value is the operation implementation.
     */
    Map<String, EffectOperation> getOperations();

    /**
     * Handle an effect operation synchronously.
     * Virtual threads make blocking operations efficient, so async is unnecessary.
     *
     * @param operation The operation name to execute
     * @param arguments The arguments to the operation
     * @param context The current effect context
     * @return The result of the operation
     * @throws EffectExecutionException if the operation fails
     */
    Object handleSync(String operation, Object arguments, EffectContext context);

    /**
     * Check if this handler supports an operation.
     *
     * @param operation The operation name
     * @return true if supported
     */
    boolean supportsOperation(String operation);

    /**
     * Get all available operation names.
     */
    Set<String> getAvailableOperations();
}
