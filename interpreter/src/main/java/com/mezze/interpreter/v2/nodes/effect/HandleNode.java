package com.mezze.interpreter.v2.nodes.effect;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.ImplSignature;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.nodes.continuation.ContinuationSuspendedException;
import com.mezze.interpreter.v2.nodes.continuation.ForkedExecutionException;
import com.mezze.interpreter.v2.nodes.continuation.MezzeContinuationSuspended;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeSuspended;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * ThreadLocal reusable map to avoid allocation in hot paths.
 * Each thread gets its own map that is cleared and reused.
 */
final class HandlerMapPool {
    private static final ThreadLocal<Map<String, Object>> POOL = ThreadLocal.withInitial(HashMap::new);

    static Map<String, Object> acquire() {
        Map<String, Object> map = POOL.get();
        map.clear();
        return map;
    }

    // Note: No release needed - the map stays in ThreadLocal and is reused
}

/**
 * Node that looks up a handler by content hash.
 * Tries local frame slot first, then context lookup.
 */
@NodeInfo(shortName = "handler-lookup")
final class HandlerLookupNode extends MezzeNode {

    @CompilationFinal
    private final int localSlot;

    @CompilationFinal
    private final String contentHash;

    @CompilationFinal
    private final String performerName;

    HandlerLookupNode(int localSlot, String contentHash) {
        this(localSlot, contentHash, null);
    }

    HandlerLookupNode(int localSlot, String contentHash, String performerName) {
        this.localSlot = localSlot;
        this.contentHash = contentHash;
        this.performerName = performerName;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Try local slot first
        if (localSlot >= 0) {
            Object handler = frame.getObject(localSlot);
            if (handler != null) {
                return handler;
            }
        }
        // Fall back to context lookup
        return lookupInContext(MezzeRecord.empty());
    }

    /**
     * Look up handler in context and evaluate it with the given params record.
     * For non-parameterized performers, pass MezzeRecord.empty().
     * For parameterized performers (e.g., SeededRandom {seed = 42}), pass the params.
     */
    @TruffleBoundary
    Object lookupInContext(Object paramsRecord) {
        MezzeContext context = MezzeContext.get(this);
        // Try by content hash (impl declaration hash)
        Object handler = context.lookupLocal(contentHash);
        if (handler == null) {
            handler = context.lookupGlobal(contentHash);
        }
        // Try by performer name (WriteLocalWithContextNode registers by variable name)
        if (handler == null && performerName != null) {
            handler = context.lookupLocal(performerName);
            if (handler == null) {
                handler = context.lookupGlobal(performerName);
            }
        }
        // Fall back to Language-level stdlib cache
        if (handler == null) {
            var lang = com.mezze.interpreter.v2.runtime.MezzeLanguage.get(this);
            var ct = lang.getCachedStdlibCallTarget(contentHash);
            if (ct != null) {
                handler = com.mezze.interpreter.v2.values.MezzeFunction.create(ct, contentHash);
            }
            if (handler == null && performerName != null) {
                ct = lang.getCachedStdlibCallTarget(performerName);
                if (ct != null) {
                    handler = com.mezze.interpreter.v2.values.MezzeFunction.create(ct, performerName);
                }
            }
        }
        // If handler is a MezzeFunction (from HashModuleLoaderNode), evaluate it
        // to get the performer record. Performer functions are closure factories
        // that return a record with operation closures.
        if (handler instanceof com.mezze.interpreter.v2.values.MezzeFunction fn) {
            handler = evaluateHandlerFunction(fn, paramsRecord, context);
        }
        if (handler == null) {
            throw new RuntimeException("Handler not found for: " + contentHash +
                (performerName != null ? " (performer: " + performerName + ")" : ""));
        }
        return handler;
    }

    /**
     * Evaluate a handler function (closure factory) with the given params to get a performer record.
     * Handles both non-parameterized (params = empty record) and parameterized performers.
     * Also handles double-wrapped closure factories.
     */
    @TruffleBoundary
    static Object evaluateHandlerFunction(MezzeFunction fn, Object paramsRecord, MezzeContext context) {
        try {
            Object[] closureValues = fn.getClosureValues();
            int closureLength = closureValues.length;
            Object[] callArgs;
            if (closureLength == 0) {
                callArgs = new Object[] { paramsRecord };
            } else {
                callArgs = new Object[closureLength + 1];
                System.arraycopy(closureValues, 0, callArgs, 0, closureLength);
                callArgs[closureLength] = paramsRecord;
            }
            Object result = fn.getCallTarget().call(callArgs);
            if (result instanceof com.mezze.interpreter.v2.values.MezzeFunction innerFn) {
                // Double-wrapped closure factory — evaluate again with same params
                Object[] innerClosureValues = innerFn.getClosureValues();
                int innerClosureLength = innerClosureValues.length;
                Object[] innerCallArgs;
                if (innerClosureLength == 0) {
                    innerCallArgs = new Object[] { paramsRecord };
                } else {
                    innerCallArgs = new Object[innerClosureLength + 1];
                    System.arraycopy(innerClosureValues, 0, innerCallArgs, 0, innerClosureLength);
                    innerCallArgs[innerClosureLength] = paramsRecord;
                }
                result = innerFn.getCallTarget().call(innerCallArgs);
            }
            if (result instanceof com.mezze.interpreter.v2.values.MezzeRecord) {
                return result;
            }
            // Not a record — return the original function for lazy evaluation
            return fn;
        } catch (Exception e) {
            // Evaluation failed (missing captures, etc.) — return the raw MezzeFunction.
            // DynamicEffectCallNode will evaluate it lazily when handler operations are called.
            return fn;
        }
    }
}

/**
 * Node that looks up a parameterized handler by content hash and calls it with
 * user-provided parameters. For example, `perform SeededRandom {seed = 42} in body`
 * will evaluate {seed = 42} and pass it to the performer factory function.
 */
@NodeInfo(shortName = "parameterized-handler-lookup")
final class ParameterizedHandlerLookupNode extends MezzeNode {

    @CompilationFinal
    private final int localSlot;

    @CompilationFinal
    private final String contentHash;

    @CompilationFinal
    private final String performerName;

    @Child
    private MezzeNode paramsNode;

    ParameterizedHandlerLookupNode(int localSlot, String contentHash, String performerName, MezzeNode paramsNode) {
        this.localSlot = localSlot;
        this.contentHash = contentHash;
        this.performerName = performerName;
        this.paramsNode = paramsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Evaluate the params (e.g., {seed = 42})
        Object params = paramsNode.execute(frame);

        // Try local slot first — but for parameterized performers, we need to
        // re-evaluate with the new params each time (different seeds produce different performers)
        // So skip the local slot cache.

        // Fall back to context lookup with the params
        return lookupInContextWithParams(params);
    }

    @TruffleBoundary
    private Object lookupInContextWithParams(Object params) {
        MezzeContext context = MezzeContext.get(this);
        // For parameterized performers, we need the FUNCTION (closure factory), not a cached record.
        // The function must be re-evaluated with the actual params each time, because
        // different params (e.g., seed=42 vs seed=99) produce different performer records.
        //
        // Strategy: check Language-level stdlib cache FIRST (always has the function),
        // then fall back to context (where it might be cached as a record from non-parameterized path).
        MezzeFunction handlerFn = null;

        // 1. Language-level stdlib cache — always has the original function
        var lang = com.mezze.interpreter.v2.runtime.MezzeLanguage.get(this);
        var ct = lang.getCachedStdlibCallTarget(contentHash);
        if (ct != null) {
            handlerFn = com.mezze.interpreter.v2.values.MezzeFunction.create(ct, contentHash);
        }
        if (handlerFn == null && performerName != null) {
            ct = lang.getCachedStdlibCallTarget(performerName);
            if (ct != null) {
                handlerFn = com.mezze.interpreter.v2.values.MezzeFunction.create(ct, performerName);
            }
        }

        // 2. Context lookup — only use if it's a function (skip cached records)
        if (handlerFn == null) {
            Object candidate = context.lookupLocal(contentHash);
            if (candidate == null) candidate = context.lookupGlobal(contentHash);
            if (candidate instanceof MezzeFunction fn) handlerFn = fn;
        }
        if (handlerFn == null && performerName != null) {
            Object candidate = context.lookupLocal(performerName);
            if (candidate == null) candidate = context.lookupGlobal(performerName);
            if (candidate instanceof MezzeFunction fn) handlerFn = fn;
        }

        if (handlerFn != null) {
            // Evaluate the handler function with the actual params
            Object handler = HandlerLookupNode.evaluateHandlerFunction(handlerFn, params, context);
            if (handler != null) return handler;
        }

        // 3. Fallback: the performer may not actually be parameterized
        // (e.g., `perform TestLogger {} in ...` where {} is just empty params).
        // In that case, no factory function exists — only a pre-constructed MezzeRecord
        // from HashModuleLoaderNode Pass 2. Use it directly.
        Object record = context.lookupLocal(contentHash);
        if (record == null) record = context.lookupGlobal(contentHash);
        if (record == null && performerName != null) {
            record = context.lookupLocal(performerName);
            if (record == null) record = context.lookupGlobal(performerName);
        }
        if (record instanceof MezzeRecord) {
            return record;
        }
        // Also try HandlerLookupNode's full lookup (which includes stdlib call target → evaluate)
        try {
            var lookupNode = new HandlerLookupNode(-1, contentHash, performerName);
            return lookupNode.lookupInContext(params);
        } catch (Exception e) {
            // Fall through to error
        }

        // 4. Search performer records for the correct stdlib hash.
        // The contentHash from the user module's compilation may differ from the stdlib's
        // hash for the same performer. Performer records link performerHash → opHashes.
        if (performerName != null) {
            var stdlibPerformers = lang.getStdlibPerformerRecords();
            var nameMap = lang.getStdlibHashToName();
            for (var info : stdlibPerformers) {
                if (info.length < 2) continue;
                String pHash = info[0];
                String registeredName = nameMap.get(pHash);
                if (performerName.equals(registeredName)) {
                    // Found the stdlib hash for this performer — try to get its factory function
                    ct = lang.getCachedStdlibCallTarget(pHash);
                    if (ct != null) {
                        handlerFn = MezzeFunction.create(ct, pHash);
                        Object handler = HandlerLookupNode.evaluateHandlerFunction(handlerFn, params, context);
                        if (handler != null) return handler;
                    }
                }
            }
            // Also try: scan prelowered functions by name (factory functions have name = performerName)
            var precompiled = com.mezze.language.typechecker.PrecompiledStdlib.INSTANCE;
            var prelowered = precompiled.getPreloweredFunctions();
            for (var entry : prelowered.entrySet()) {
                if (performerName.equals(entry.getValue().getName())) {
                    ct = lang.getCachedStdlibCallTarget(entry.getKey());
                    if (ct != null) {
                        handlerFn = MezzeFunction.create(ct, entry.getKey());
                        Object handler = HandlerLookupNode.evaluateHandlerFunction(handlerFn, params, context);
                        if (handler != null) return handler;
                    }
                }
            }
        }

        // 5. Direct user function IR lookup — compile on demand
        // The factory function may be registered as a user FunctionIR
        // that hasn't been compiled yet
        if (handlerFn == null) {
            var userIR = context.getUserFunctionIR(contentHash);
            if (userIR != null) {
                var compiler = new com.mezze.interpreter.v2.compiler.TruffleCompiler(lang);
                compiler.registerFunctions(new java.util.ArrayList<>(context.getAllUserFunctionIRs()));
                var rootNode = compiler.compile(userIR);
                ct = rootNode.getCallTarget();
                context.cacheUserCallTarget(contentHash, ct);
                handlerFn = MezzeFunction.create(ct, contentHash);
                Object handler = HandlerLookupNode.evaluateHandlerFunction(handlerFn, params, context);
                if (handler != null) return handler;
            }
        }

        throw new RuntimeException("Handler function not found for parameterized performer: " + contentHash +
            (performerName != null ? " (performer: " + performerName + ")" : ""));
    }
}

/**
 * Handle effect node - FULLY JIT-COMPILABLE.
 *
 * The handler is stored in a frame slot that PerformNode reads from.
 * This avoids any runtime lookups or @TruffleBoundary calls.
 *
 * Flow:
 * 1. Evaluate handler expression
 * 2. Store handler in the pre-allocated frame slot AND context (for effect name lookup)
 * 3. Execute body
 * 4. Restore previous handler (if nested)
 *
 * The handler slot is determined statically by the typechecker/converter
 * and passed via the handlerSlot parameter.
 */
@NodeInfo(shortName = "handle")
public final class HandleNode extends MezzeNode {

    @CompilationFinal
    private final int handlerSlot;

    @CompilationFinal(dimensions = 1)
    private final String[] effectNames;

    @CompilationFinal
    private final String handlerName;

    // Content hashes for handler lookup and registration (preferred over string name)
    // First hash is typically the impl hash (for handler lookup)
    // Additional hashes may include effect hash (for effect operation lookup)
    @CompilationFinal(dimensions = 1)
    private final String[] contentHashes;

    @Child
    private MezzeNode handlerNode;

    @Child
    private MezzeNode bodyNode;

    // SCOPED EFFECTS: Slot for storing the scope record in the frame
    // Operations access scope via this slot
    @CompilationFinal
    private final int scopeSlot;

    // SCOPED EFFECTS: Node to execute initialize_scope (may be null)
    @Child
    private MezzeNode initializeScopeNode;

    // SCOPED EFFECTS: Node to execute finalize_scope (may be null)
    @Child
    private MezzeNode finalizeScopeNode;

    // SCOPED EFFECTS: Direct call node for initialize_scope (for parameterized impls)
    @Child
    private DirectCallNode initializeScopeCallNode;

    // SCOPED EFFECTS: Direct call node for finalize_scope (for parameterized impls)
    @Child
    private DirectCallNode finalizeScopeCallNode;

    // SCOPED EFFECTS: Flags for compile-time optimization
    @CompilationFinal
    private final boolean hasInitializeScope;

    @CompilationFinal
    private final boolean hasFinalizeScope;

    // Cached slot validity - set on first execution, then constant for JIT
    @CompilationFinal
    private boolean slotValidityChecked = false;

    @CompilationFinal
    private boolean slotIsValid = false;

    @CompilationFinal
    private boolean scopeSlotIsValid = false;

    // Dynamic scope detection: when compile-time scope nodes aren't available
    // (v2 pipeline), detect initialize_scope/finalize_scope on handler record at runtime
    @CompilationFinal
    private boolean dynamicScopeChecked = false;

    @CompilationFinal
    private boolean hasDynamicScope = false;

    // For executing suspended computations and dynamic scope functions
    @Child
    private IndirectCallNode indirectCallNode;

    /**
     * Standard constructor for non-scoped effects (backward compatible).
     */
    public HandleNode(
            int handlerSlot,
            String[] effectNames,
            String handlerName,
            MezzeNode handlerNode,
            MezzeNode bodyNode
    ) {
        this(handlerSlot, effectNames, handlerName, new String[0], handlerNode, bodyNode,
             -1, null, null, false, false);
    }

    /**
     * Full constructor with scoped effect support and content hashes.
     */
    public HandleNode(
            int handlerSlot,
            String[] effectNames,
            String handlerName,
            String[] contentHashes,
            MezzeNode handlerNode,
            MezzeNode bodyNode,
            int scopeSlot,
            MezzeNode initializeScopeNode,
            MezzeNode finalizeScopeNode,
            boolean hasInitializeScope,
            boolean hasFinalizeScope
    ) {
        this.handlerSlot = handlerSlot;
        this.effectNames = effectNames != null ? effectNames : new String[0];
        this.handlerName = handlerName;
        this.contentHashes = contentHashes != null ? contentHashes : new String[0];
        this.handlerNode = handlerNode;
        this.bodyNode = bodyNode;
        this.scopeSlot = scopeSlot;
        this.initializeScopeNode = initializeScopeNode;
        this.finalizeScopeNode = finalizeScopeNode;
        this.hasInitializeScope = hasInitializeScope;
        this.hasFinalizeScope = hasFinalizeScope;
    }

    /**
     * Create a HandlerLookupNode that finds a handler by content hash.
     * Tries local frame slot first, then falls back to context lookup.
     * Used by TruffleCompiler for the v2 pipeline.
     */
    public static MezzeNode createHandlerLookup(int localSlot, String contentHash) {
        return new HandlerLookupNode(localSlot, contentHash);
    }

    public static MezzeNode createHandlerLookup(int localSlot, String contentHash, String performerName) {
        return new HandlerLookupNode(localSlot, contentHash, performerName);
    }

    /**
     * Create a ParameterizedHandlerLookupNode that finds a handler by content hash
     * and calls it with the user-provided params record.
     * Used for parameterized performers like `perform SeededRandom {seed = 42} in body`.
     */
    public static MezzeNode createParameterizedHandlerLookup(int localSlot, String contentHash, String performerName, MezzeNode paramsNode) {
        return new ParameterizedHandlerLookupNode(localSlot, contentHash, performerName, paramsNode);
    }

    /**
     * Create a handle node for a statically resolved handler.
     * The handler is looked up by content hash from the frame slot or context.
     */
    public static HandleNode create(int handlerSlot, String contentHash, MezzeNode bodyChild) {
        // Create a handler lookup node
        MezzeNode handlerNode = new HandlerLookupNode(handlerSlot, contentHash);
        // Pass handlerSlot as-is (including -1) - execute() will skip invalid slots
        // Store contentHash for processSuspended fallback lookup
        String[] hashes = (contentHash != null && !contentHash.isEmpty()) ? new String[]{contentHash} : new String[0];
        return new HandleNode(handlerSlot, null, null, hashes, handlerNode, bodyChild,
                -1, null, null, false, false);
    }

    /**
     * Create a handle node with effect names for context registration.
     */
    public static HandleNode create(int handlerSlot, String contentHash, String[] effectNames, String handlerName, MezzeNode bodyChild) {
        MezzeNode handlerNode = new HandlerLookupNode(handlerSlot, contentHash);
        // Pass handlerSlot as-is (including -1) - execute() will skip invalid slots
        // Store contentHash for processSuspended fallback lookup
        String[] hashes = (contentHash != null && !contentHash.isEmpty()) ? new String[]{contentHash} : new String[0];
        return new HandleNode(handlerSlot, effectNames, handlerName, hashes, handlerNode, bodyChild,
                -1, null, null, false, false);
    }

    /**
     * Create a handle node with multiple content hashes for registration.
     * Use this for cases where handler needs to be found by multiple hashes
     * (e.g., both impl hash and effect hash).
     */
    public static HandleNode create(int handlerSlot, String[] contentHashes, String[] effectNames, String handlerName, MezzeNode bodyChild) {
        String primaryHash = (contentHashes != null && contentHashes.length > 0) ? contentHashes[0] : "";
        MezzeNode handlerNode = new HandlerLookupNode(handlerSlot, primaryHash);
        return new HandleNode(handlerSlot, effectNames, handlerName, contentHashes, handlerNode, bodyChild,
                -1, null, null, false, false);
    }

    /**
     * Create a handle node for scoped effects with a single content hash.
     *
     * @param handlerSlot Frame slot for storing the handler
     * @param contentHash Content hash for handler lookup
     * @param effectNames Effect names this handler handles
     * @param handlerName The handler/performer name
     * @param bodyChild The body to execute
     * @param scopeSlot Frame slot for storing the scope record
     * @param initializeScopeNode Node to execute initialize_scope
     * @param finalizeScopeNode Node to execute finalize_scope
     */
    public static HandleNode createScoped(
            int handlerSlot,
            String contentHash,
            String[] effectNames,
            String handlerName,
            MezzeNode bodyChild,
            int scopeSlot,
            MezzeNode initializeScopeNode,
            MezzeNode finalizeScopeNode
    ) {
        String[] hashes = (contentHash != null && !contentHash.isEmpty()) ? new String[]{contentHash} : new String[0];
        return createScoped(handlerSlot, hashes, effectNames, handlerName, bodyChild, scopeSlot, initializeScopeNode, finalizeScopeNode);
    }

    /**
     * Create a handle node for scoped effects with multiple content hashes.
     * Use this when the handler needs to be found by both impl hash and effect hash.
     *
     * @param handlerSlot Frame slot for storing the handler
     * @param contentHashes Content hashes for handler lookup and registration
     * @param effectNames Effect names this handler handles
     * @param handlerName The handler/performer name
     * @param bodyChild The body to execute
     * @param scopeSlot Frame slot for storing the scope record
     * @param initializeScopeNode Node to execute initialize_scope
     * @param finalizeScopeNode Node to execute finalize_scope
     */
    public static HandleNode createScoped(
            int handlerSlot,
            String[] contentHashes,
            String[] effectNames,
            String handlerName,
            MezzeNode bodyChild,
            int scopeSlot,
            MezzeNode initializeScopeNode,
            MezzeNode finalizeScopeNode
    ) {
        String primaryHash = (contentHashes != null && contentHashes.length > 0) ? contentHashes[0] : "";
        MezzeNode handlerNode = new HandlerLookupNode(handlerSlot, primaryHash);
        return new HandleNode(
                handlerSlot,
                effectNames,
                handlerName,
                contentHashes,  // Pass all content hashes for registration
                handlerNode,
                bodyChild,
                scopeSlot,
                initializeScopeNode,
                finalizeScopeNode,
                initializeScopeNode != null,
                finalizeScopeNode != null
        );
    }

    /**
     * Check if this handle node uses scoped effects.
     */
    public boolean isScopedEffect() {
        return hasInitializeScope;
    }

    /**
     * Get the scope slot (for operations to access).
     */
    public int getScopeSlot() {
        return scopeSlot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Evaluate the handler (may be parameterized impl call)
        Object handler = handlerNode.execute(frame);

        // Handler resolved

        // Cache slot validity on first execution - becomes constant for JIT
        if (!slotValidityChecked) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            int frameSlotCount = frame.getFrameDescriptor().getNumberOfSlots();
            slotIsValid = handlerSlot >= 0 && handlerSlot < frameSlotCount;
            scopeSlotIsValid = scopeSlot >= 0 && scopeSlot < frameSlotCount;
            slotValidityChecked = true;
        }

        // Save previous handler value (for nested handlers)
        Object previousHandler = null;
        boolean hadPrevious = false;

        // Fast path: slot validity is now a compile-time constant
        if (slotIsValid && frame.isObject(handlerSlot)) {
            previousHandler = frame.getObject(handlerSlot);
            hadPrevious = true;
        }

        // Only set frame slot if valid
        if (slotIsValid) {
            frame.setObject(handlerSlot, handler);
        }

        // Also register in context so DynamicEffectCallNode can find it
        // AND push onto handler stack for lazy effects
        registerHandlerInContext(handler);
        pushHandlersToStack(handler);

        // SCOPED EFFECTS: Execute initialize_scope if present
        Object scope = null;
        boolean usedDynamicScope = false;
        if (hasInitializeScope && initializeScopeNode != null) {
            scope = initializeScopeNode.execute(frame);

            // Store scope in frame slot so operations can access it
            if (scopeSlotIsValid) {
                frame.setObject(scopeSlot, scope);
            }

            // Also register scope in context under the name "scope"
            registerScopeInContext(scope);
        } else if (!hasInitializeScope && handler instanceof MezzeRecord handlerRecord) {
            // Dynamic scope detection for v2 pipeline: check handler record for
            // initialize_scope/finalize_scope fields at runtime
            if (!dynamicScopeChecked) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                // Check both naming conventions: user-facing and internal double-underscore
                hasDynamicScope = handlerRecord.getField("initialize_scope") instanceof MezzeFunction
                    || handlerRecord.getField("__initialize_scope__") instanceof MezzeFunction;
                dynamicScopeChecked = true;
            }
            if (hasDynamicScope) {
                scope = callDynamicInitializeScope(handlerRecord);
                if (scope != null) {
                    registerScopeInContext(scope);
                    usedDynamicScope = true;
                }
            }
        }

        Throwable bodyException = null;
        Object result = null;
        Object forkedResults = null;

        Object suspendedValue = null;  // For ContinuationSuspendedException

        Object finalizeSuspendedValue = null;  // For ContinuationSuspendedException from finalize_scope

        try {
            // Execute the body with the handler (and scope if scoped effect) installed
            result = bodyNode.execute(frame);
        } catch (ContinuationSuspendedException e) {
            // Continuation was suspended via suspend_with - extract the suspended value
            // This is how continuation-based effects (Exception, Return, etc.) escape
            // to the perform boundary without being wrapped by intermediate expressions
            suspendedValue = e.getSuspendedValue();
        } catch (ForkedExecutionException e) {
            // Fork completed - capture results (MezzeSeq), run finalize_scope, then return values
            forkedResults = e.values;
        } catch (Throwable t) {
            bodyException = t;
        } finally {
            // SCOPED EFFECTS: Execute finalize_scope in finally block
            // This ensures cleanup even if body throws
            Throwable finalizeException = null;
            if (hasFinalizeScope && finalizeScopeNode != null) {
                try {
                    Object finalizeResult = finalizeScopeNode.execute(frame);
                    // Check if finalize_scope returned a suspended continuation
                    // This happens when finalize_scope uses a state machine with YieldState
                    if (finalizeResult instanceof MezzeContinuationSuspended suspended) {
                        finalizeSuspendedValue = suspended.getSuspendedValue();
                    }
                } catch (ContinuationSuspendedException e) {
                    // finalize_scope can call suspend_with to transform/return the collected values
                    // This is how Generator returns collected values: finalize_scope calls
                    // suspend_with {value = collected} and we capture that value here
                    finalizeSuspendedValue = e.getSuspendedValue();
                } catch (Throwable t) {
                    finalizeException = t;
                }
            } else if (usedDynamicScope && handler instanceof MezzeRecord handlerRecord) {
                // Dynamic finalize_scope for v2 pipeline
                try {
                    Object finalizeResult = callDynamicFinalizeScope(handlerRecord);
                    if (finalizeResult instanceof MezzeContinuationSuspended suspended) {
                        finalizeSuspendedValue = suspended.getSuspendedValue();
                    }
                } catch (ContinuationSuspendedException e) {
                    finalizeSuspendedValue = e.getSuspendedValue();
                } catch (Throwable t) {
                    finalizeException = t;
                }
            }

            // Clear scope from frame
            if (scopeSlotIsValid) {
                frame.setObject(scopeSlot, null);
            }

            // Pop handler from stack (for lazy effects)
            popHandlersFromStack();

            // Restore previous handler (or clear if none) - only if slot is valid
            if (slotIsValid) {
                if (hadPrevious) {
                    frame.setObject(handlerSlot, previousHandler);
                } else {
                    // Clear the slot to indicate no handler
                    frame.setObject(handlerSlot, null);
                }
            }

            // Handle exceptions: body exception takes priority, but log finalize exception
            // This matches try-with-resources semantics in Java
            if (bodyException != null) {
                if (finalizeException != null && finalizeException != bodyException) {
                    // Add finalize exception as suppressed
                    bodyException.addSuppressed(finalizeException);
                }
                // Re-throw body exception
                if (bodyException instanceof RuntimeException) {
                    throw (RuntimeException) bodyException;
                } else if (bodyException instanceof Error) {
                    throw (Error) bodyException;
                } else {
                    throw new RuntimeException(bodyException);
                }
            } else if (finalizeException != null) {
                // Only finalize failed - throw it
                if (finalizeException instanceof RuntimeException) {
                    throw (RuntimeException) finalizeException;
                } else if (finalizeException instanceof Error) {
                    throw (Error) finalizeException;
                } else {
                    throw new RuntimeException(finalizeException);
                }
            }
        }

        // If fork completed successfully, return the forked results
        if (forkedResults != null) {
            return forkedResults;
        }

        // If finalize_scope suspended (for Generator returning collected values),
        // that takes priority - return the suspended value from finalize_scope
        if (finalizeSuspendedValue != null) {
            return finalizeSuspendedValue;
        }

        // If body continuation was suspended, return the suspended value directly
        // This is how continuation-based effects (Exception, Return, etc.) work:
        // the handler calls suspend_with {value = ...} and we return that value
        if (suspendedValue != null) {
            return suspendedValue;
        }

        return processResult(result, handler);
    }

    /**
     * Process the result:
     * - For MezzeSuspended (lazy effects), try to execute with our handlers
     * - For MezzeContinuationSuspended, extract the suspended value (this is how
     *   continuation-based effects like Exception.throw and Return.return work)
     *
     * NOTE: We do NOT wrap results in Ok - the user code is responsible for
     * wrapping success values explicitly. This keeps effects as pure userland
     * constructs without hardcoded effect names in the interpreter.
     *
     * @param resolvedHandler the handler object that was active during body execution
     *                        (passed directly since handler stack is already popped by finally block)
     */
    @TruffleBoundary
    private Object processResult(Object result, Object resolvedHandler) {
        // Handle lazy effects: if result is MezzeSuspended, try to execute with our handlers
        if (result instanceof MezzeSuspended suspended) {
            return processSuspended(suspended, resolvedHandler);
        }

        // Handle suspended continuations - extract the suspended value.
        // This is how continuation-based effects (Exception, Return, etc.) work:
        // the handler calls suspend_with {value = ...} and we extract that value.
        if (result instanceof MezzeContinuationSuspended suspended) {
            return suspended.getSuspendedValue();
        }

        return result;
    }

    /**
     * Process a MezzeSuspended result by executing it with our handlers.
     * Uses the resolved handler object directly since the handler stack has already
     * been popped by the finally block.
     */
    @TruffleBoundary
    private Object processSuspended(MezzeSuspended suspended, Object resolvedHandler) {
        MezzeContext context;
        try {
            context = MezzeContext.get(this);
        } catch (AssertionError e) {
            // No context - return suspended as-is
            return suspended;
        }

        // Build handler map using the resolved handler directly
        // (can't look up from context — handler stack already popped)
        Map<String, Object> handlers = HandlerMapPool.acquire();

        for (String hash : contentHashes) {
            if (hash != null && !hash.isEmpty()) {
                if (resolvedHandler != null) {
                    handlers.put(hash, resolvedHandler);
                } else {
                    // Fallback: try context lookup (may work for nested handlers)
                    Object h = context.getHandlerFor(hash);
                    if (h != null) {
                        handlers.put(hash, h);
                    }
                }
            }
        }

        // FALLBACK: Also register the handler under the suspended value's unhandled
        // effect hashes. This handles hash mismatches where the suspended computation
        // was created with a different hash than what this HandleNode registered under.
        if (resolvedHandler != null) {
            for (String unhandled : suspended.getUnhandledEffects()) {
                if (unhandled != null && !handlers.containsKey(unhandled)) {
                    // Only add if the handler record actually has the right operation
                    if (resolvedHandler instanceof MezzeRecord rec) {
                        String opName = suspended.getOperationName();
                        if (opName != null && rec.getField(opName) instanceof MezzeFunction) {
                            handlers.put(unhandled, resolvedHandler);
                        }
                    }
                }
            }
        }

        // Execute the suspended computation with our handlers
        if (indirectCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            indirectCallNode = insert(IndirectCallNode.create());
        }

        Object executedResult = suspended.executeWith(handlers, context, indirectCallNode);

        // Return the executed result (may still be suspended or a final value)
        return executedResult;
    }

    @TruffleBoundary
    private void registerHandlerInContext(Object handler) {
        if (handler == null) return;

        MezzeContext context;
        try {
            context = MezzeContext.get(this);
        } catch (AssertionError e) {
            // No polyglot context available (e.g., in isolated unit tests)
            // Skip context registration - frame slot registration is sufficient
            return;
        }

        // Register under all content hashes (O(1) lookup) - no effect names
        for (String hash : contentHashes) {
            if (hash != null && !hash.isEmpty()) {
                context.defineLocal(hash, handler);
            }
        }
    }

    /**
     * Push handlers onto the context's handler stack for lazy effects.
     * Registers under all content hashes.
     */
    @TruffleBoundary
    private void pushHandlersToStack(Object handler) {
        if (handler == null) return;

        MezzeContext context;
        try {
            context = MezzeContext.get(this);
        } catch (AssertionError e) {
            return;
        }

        // Build handler map - reuse pooled map to avoid allocation
        Map<String, Object> handlers = HandlerMapPool.acquire();

        for (String hash : contentHashes) {
            if (hash != null && !hash.isEmpty()) {
                handlers.put(hash, handler);
            }
        }

        if (!handlers.isEmpty()) {
            // Note: pushHandlers may copy the map if it needs to retain it
            context.pushHandlers(handlers);
        }
    }

    /**
     * Pop handlers from the context's handler stack.
     */
    @TruffleBoundary
    private void popHandlersFromStack() {
        MezzeContext context;
        try {
            context = MezzeContext.get(this);
        } catch (AssertionError e) {
            return;
        }

        context.popHandlers();
    }

    /**
     * Dynamically call initialize_scope on a handler record.
     * Used by v2 pipeline where scope nodes aren't available at compile time.
     */
    @TruffleBoundary
    private Object callDynamicInitializeScope(MezzeRecord handlerRecord) {
        Object initFn = handlerRecord.getField("initialize_scope");
        if (initFn == null) {
            initFn = handlerRecord.getField("__initialize_scope__");
        }
        if (!(initFn instanceof MezzeFunction func)) {
            return null;
        }
        if (indirectCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            indirectCallNode = insert(IndirectCallNode.create());
        }
        // Call initialize_scope with {} (empty record) as argument
        Object[] closureValues = func.getClosureValues();
        int closureLength = closureValues.length;
        // The empty record argument for {} -> ...
        Object emptyRecord = MezzeRecord.empty();
        Object[] fullArgs;
        if (closureLength == 0) {
            fullArgs = new Object[]{emptyRecord};
        } else {
            fullArgs = new Object[closureLength + 1];
            System.arraycopy(closureValues, 0, fullArgs, 0, closureLength);
            fullArgs[closureLength] = emptyRecord;
        }
        return indirectCallNode.call(func.getCallTarget(), fullArgs);
    }

    /**
     * Dynamically call finalize_scope on a handler record.
     * Used by v2 pipeline where scope nodes aren't available at compile time.
     */
    @TruffleBoundary
    private Object callDynamicFinalizeScope(MezzeRecord handlerRecord) {
        Object finalizeFn = handlerRecord.getField("finalize_scope");
        if (finalizeFn == null) {
            finalizeFn = handlerRecord.getField("__finalize_scope__");
        }
        if (!(finalizeFn instanceof MezzeFunction func)) {
            return null;
        }
        if (indirectCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            indirectCallNode = insert(IndirectCallNode.create());
        }
        Object[] closureValues = func.getClosureValues();
        int closureLength = closureValues.length;
        Object emptyRecord = MezzeRecord.empty();
        Object[] fullArgs;
        if (closureLength == 0) {
            fullArgs = new Object[]{emptyRecord};
        } else {
            fullArgs = new Object[closureLength + 1];
            System.arraycopy(closureValues, 0, fullArgs, 0, closureLength);
            fullArgs[closureLength] = emptyRecord;
        }
        return indirectCallNode.call(func.getCallTarget(), fullArgs);
    }

    /**
     * Register scope in context so operations can access it.
     * The scope is registered under the name "scope".
     */
    @TruffleBoundary
    private void registerScopeInContext(Object scope) {
        if (scope == null) return;

        MezzeContext context;
        try {
            context = MezzeContext.get(this);
        } catch (AssertionError e) {
            // No polyglot context available (e.g., in isolated unit tests)
            return;
        }

        // Register scope under the reserved name "scope"
        context.defineLocal("scope", scope);
    }
}
