package com.mezze.interpreter.v2.effects;

import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Global effect registry with bidirectional performer-effect mapping.
 *
 * This provides O(1) lookups in both directions:
 * - Effect -> Performers: Which performers implement this effect?
 * - Performer -> Effect: What effect does this performer implement?
 *
 * The registry is thread-safe using ConcurrentHashMap and CopyOnWriteArrayList.
 *
 * This mirrors V1's EffectHandler pattern for effect resolution:
 * 1. Direct lookup by effect name (e.g., handlers.get("Log"))
 * 2. Indirect lookup by performer name (e.g., handlers.get("Console") when Console implements Log)
 *
 * Integration points:
 * - HandleNode registers handlers and pushes them onto the handler stack
 * - DynamicEffectCallNode looks up handlers using getHandlerFor()
 * - PerformNode uses pre-resolved handlers from frame slots
 *
 * Example usage:
 *   // At effect declaration time:
 *   registry.registerEffect("Log", effectRecord);
 *
 *   // At impl declaration time:
 *   registry.registerPerformer("Console", "Log");
 *
 *   // At effect call time:
 *   String effectName = registry.getEffectForPerformer("Console");  // Returns "Log"
 *   List<String> performers = registry.getPerformersForEffect("Log");  // Returns ["Console"]
 */
public final class EffectRegistry {

    // Global singleton instance (per MezzeContext)
    // Effects are program-wide declarations, so we need one registry per context.
    // However, for simplicity we use a static singleton since Mezze programs
    // are typically single-context.
    private static final EffectRegistry INSTANCE = new EffectRegistry();

    /**
     * Get the global effect registry instance.
     */
    public static EffectRegistry getInstance() {
        return INSTANCE;
    }

    // ========================
    // Effect declarations
    // ========================

    /**
     * Global effect registry - effects are program-wide declarations.
     * Maps effect name -> effect record (containing operation signatures).
     * Thread-safe: ConcurrentHashMap for concurrent access from virtual threads.
     */
    private final ConcurrentHashMap<String, MezzeRecord> globalEffects = new ConcurrentHashMap<>();

    // ========================
    // Bidirectional performer-effect mapping
    // ========================

    /**
     * Forward mapping: performer name -> effect name
     * e.g., "Console" -> "Log", "ToResult" -> "Exception"
     * Thread-safe: ConcurrentHashMap for concurrent access.
     */
    private final ConcurrentHashMap<String, String> performerToEffect = new ConcurrentHashMap<>();

    /**
     * Inverted index: effect name -> list of performer names
     * Enables O(1) lookup in getPerformersForEffect instead of O(N) filter.
     * e.g., "Log" -> ["Console", "FileLogger"]
     * Thread-safe: ConcurrentHashMap with CopyOnWriteArrayList values.
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> effectToPerformers = new ConcurrentHashMap<>();

    // ========================
    // Effect-to-operations mapping
    // ========================

    /**
     * Maps effect name -> set of operation names for validation.
     * e.g., "Log" -> {"log", "debug", "error"}
     * Used to validate that handlers provide all required operations.
     */
    private final ConcurrentHashMap<String, Set<String>> effectOperations = new ConcurrentHashMap<>();

    // ========================
    // Effect registration
    // ========================

    /**
     * Register a global effect declaration.
     *
     * @param name The effect name (e.g., "Log", "Random", "Exception")
     * @param effectRecord The effect record containing operation signatures
     */
    @TruffleBoundary
    public void registerEffect(String name, MezzeRecord effectRecord) {
        globalEffects.put(name, effectRecord);

        // Extract operation names from the effect record
        Set<String> operations = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < effectRecord.getFieldCount(); i++) {
            String fieldName = effectRecord.getShape().getFieldName(i);
            Object fieldValue = effectRecord.getField(i);
            // Operations are functions
            if (fieldValue instanceof MezzeFunction) {
                operations.add(fieldName);
            }
        }
        effectOperations.put(name, operations);
    }

    /**
     * Check if an effect is declared globally.
     */
    @TruffleBoundary
    public boolean isEffectDeclared(String name) {
        return globalEffects.containsKey(name);
    }

    /**
     * Get the effect record for a declared effect.
     *
     * @param name The effect name
     * @return The effect record, or null if not declared
     */
    @TruffleBoundary
    public MezzeRecord getEffect(String name) {
        return globalEffects.get(name);
    }

    /**
     * Get all declared effects.
     *
     * @return Unmodifiable map of effect name -> effect record
     */
    @TruffleBoundary
    public Map<String, MezzeRecord> getGlobalEffects() {
        return Collections.unmodifiableMap(globalEffects);
    }

    /**
     * Get the operations declared by an effect.
     *
     * @param effectName The effect name
     * @return Set of operation names, or empty set if effect not declared
     */
    @TruffleBoundary
    public Set<String> getEffectOperations(String effectName) {
        Set<String> ops = effectOperations.get(effectName);
        return ops != null ? Collections.unmodifiableSet(ops) : Collections.emptySet();
    }

    // ========================
    // Performer registration
    // ========================

    /**
     * Register that a performer implements an effect.
     * Updates both forward and inverted indexes for O(1) bidirectional lookup.
     *
     * e.g., registerPerformer("Console", "Log") means Console implements Log
     *
     * @param performerName The performer/impl name (e.g., "Console", "ToResult")
     * @param effectName The effect it implements (e.g., "Log", "Exception")
     */
    @TruffleBoundary
    public void registerPerformer(String performerName, String effectName) {
        // Update forward mapping
        performerToEffect.put(performerName, effectName);

        // Update inverted index
        effectToPerformers.computeIfAbsent(effectName, k -> new CopyOnWriteArrayList<>())
            .addIfAbsent(performerName);
    }

    /**
     * Get the effect that a performer implements.
     * O(1) lookup via forward mapping.
     *
     * @param performerName The performer name
     * @return The effect name, or null if performer not registered
     */
    @TruffleBoundary
    public String getEffectForPerformer(String performerName) {
        return performerToEffect.get(performerName);
    }

    /**
     * Get all performers that implement a given effect.
     * O(1) lookup via inverted index.
     *
     * e.g., getPerformersForEffect("Log") returns ["Console", "FileLogger"]
     *
     * @param effectName The effect name
     * @return List of performer names (never null, may be empty)
     */
    @TruffleBoundary
    public List<String> getPerformersForEffect(String effectName) {
        CopyOnWriteArrayList<String> performers = effectToPerformers.get(effectName);
        return performers != null ? Collections.unmodifiableList(performers) : Collections.emptyList();
    }

    /**
     * Check if a performer handles a specific effect.
     *
     * @param performerName The performer name
     * @param effectName The effect name to check
     * @return true if the performer handles the effect
     */
    @TruffleBoundary
    public boolean performerHandlesEffect(String performerName, String effectName) {
        String effect = performerToEffect.get(performerName);
        return effect != null && effect.equals(effectName);
    }

    // ========================
    // Handler resolution
    // ========================

    /**
     * Smart handler resolution - finds a handler for an effect using multiple strategies.
     *
     * Resolution order:
     * 1. Direct lookup by effect name (e.g., handlers.get("Log"))
     * 2. Lookup by performer names that implement the effect
     *    (e.g., handlers.get("Console") when Console implements Log)
     *
     * This enables flexible handler registration where you can provide
     * either the effect name or the performer name.
     *
     * @param effectName The effect to find a handler for
     * @param handlers Map of available handlers (name -> handler record)
     * @return The handler record, or null if no handler found
     */
    @TruffleBoundary
    public Object resolveHandler(String effectName, Map<String, Object> handlers) {
        if (handlers == null) {
            return null;
        }

        // Strategy 1: Direct lookup by effect name
        Object handler = handlers.get(effectName);
        if (handler != null) {
            return handler;
        }

        // Strategy 2: Find a performer that implements this effect
        List<String> performers = getPerformersForEffect(effectName);
        for (String performerName : performers) {
            handler = handlers.get(performerName);
            if (handler != null) {
                return handler;
            }
        }

        return null;
    }

    /**
     * Check if handlers cover an effect (either directly or via performer).
     *
     * @param effectName The effect to check
     * @param handlers Map of available handlers
     * @return true if the effect is handled
     */
    @TruffleBoundary
    public boolean isEffectHandled(String effectName, Map<String, Object> handlers) {
        return resolveHandler(effectName, handlers) != null;
    }

    /**
     * Validate that a handler record provides all required operations for an effect.
     *
     * @param effectName The effect name
     * @param handler The handler record to validate
     * @return null if valid, or error message describing missing operations
     */
    @TruffleBoundary
    public String validateHandler(String effectName, MezzeRecord handler) {
        Set<String> requiredOps = effectOperations.get(effectName);
        if (requiredOps == null) {
            // Effect not registered - can't validate
            return null;
        }

        StringBuilder missing = null;
        for (String opName : requiredOps) {
            Object op = handler.getField(opName);
            if (op == null || !(op instanceof MezzeFunction)) {
                if (missing == null) {
                    missing = new StringBuilder("Missing operations: ");
                } else {
                    missing.append(", ");
                }
                missing.append(opName);
            }
        }

        return missing != null ? missing.toString() : null;
    }

    // ========================
    // State management
    // ========================

    /**
     * Clear all state. Used for testing.
     */
    @TruffleBoundary
    public void clear() {
        globalEffects.clear();
        performerToEffect.clear();
        effectToPerformers.clear();
        effectOperations.clear();
    }

    /**
     * Get the number of registered effects. Used for debugging/testing.
     */
    @TruffleBoundary
    public int getEffectCount() {
        return globalEffects.size();
    }

    /**
     * Get the number of registered performers. Used for debugging/testing.
     */
    @TruffleBoundary
    public int getPerformerCount() {
        return performerToEffect.size();
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("EffectRegistry {\n");
        sb.append("  effects: ").append(globalEffects.keySet()).append("\n");
        sb.append("  performers: {\n");
        for (Map.Entry<String, String> entry : performerToEffect.entrySet()) {
            sb.append("    ").append(entry.getKey()).append(" -> ").append(entry.getValue()).append("\n");
        }
        sb.append("  }\n");
        sb.append("}");
        return sb.toString();
    }

    // Private constructor to enforce singleton pattern
    private EffectRegistry() {}
}
