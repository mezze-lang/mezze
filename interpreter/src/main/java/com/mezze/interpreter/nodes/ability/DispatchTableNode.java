package com.mezze.interpreter.nodes.ability;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.concurrent.ConcurrentHashMap;

/**
 * DISPATCH TABLE for ability operations.
 *
 * This provides O(1) direct dispatch for ability calls by:
 * 1. Pre-computing a dispatch table at compile time
 * 2. Using integer operation IDs instead of string names
 * 3. Direct CallTarget access without any runtime lookup
 *
 * The dispatch table is guarded by an Assumption that is invalidated
 * when new impls are added, triggering deoptimization and rebuild.
 *
 * This is the fastest possible dispatch mechanism - even faster than
 * inline caching because there's NO runtime check at all.
 */
@NodeInfo(shortName = "dispatch-table")
public final class DispatchTableNode extends MezzeNode {

    /**
     * Global registry of dispatch tables, keyed by ability content hash.
     * Thread-safe and lock-free for reads after initialization.
     */
    private static final ConcurrentHashMap<Hash, DispatchTable> DISPATCH_TABLES = new ConcurrentHashMap<>();

    @CompilationFinal
    private final Hash abilityHash;

    @CompilationFinal
    private final int operationId;

    @Children
    private final MezzeNode[] argumentNodes;

    @CompilationFinal
    private final int argumentCount;

    @Child
    private DirectCallNode callNode;

    @CompilationFinal
    private Assumption tableStableAssumption;

    public DispatchTableNode(Hash abilityHash, int operationId, MezzeNode[] argumentNodes) {
        this.abilityHash = abilityHash;
        this.operationId = operationId;
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
        // Call node and assumption are initialized lazily on first execute
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Check if we need to (re)initialize
        if (callNode == null || !tableStableAssumption.isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            initializeCallNode();
        }

        // Build arguments array
        Object[] arguments = new Object[argumentCount];
        for (int i = 0; i < argumentCount; i++) {
            arguments[i] = argumentNodes[i].execute(frame);
        }

        // Direct call - NO runtime lookup!
        return callNode.call(arguments);
    }

    private void initializeCallNode() {
        DispatchTable table = getOrCreateDispatchTable(abilityHash);
        CallTarget target = table.getCallTarget(operationId);

        if (target == null) {
            throw new RuntimeException("No impl registered for operation " + operationId +
                                       " of ability " + abilityHash.getHex());
        }

        this.callNode = insert(DirectCallNode.create(target));
        this.tableStableAssumption = table.getStableAssumption();
    }

    /**
     * Factory method.
     */
    public static DispatchTableNode create(Hash abilityHash, int operationId, MezzeNode[] arguments) {
        return new DispatchTableNode(abilityHash, operationId, arguments);
    }

    /**
     * Get or create a dispatch table for an ability.
     */
    @TruffleBoundary
    public static DispatchTable getOrCreateDispatchTable(Hash abilityHash) {
        return DISPATCH_TABLES.computeIfAbsent(abilityHash, DispatchTable::new);
    }

    /**
     * Register an impl's CallTarget in the dispatch table.
     * Called during impl loading.
     */
    @TruffleBoundary
    public static void registerImpl(Hash abilityHash, int operationId, CallTarget callTarget) {
        DispatchTable table = getOrCreateDispatchTable(abilityHash);
        table.register(operationId, callTarget);
    }

    /**
     * The dispatch table itself.
     * Uses an array for O(1) lookup by operation ID.
     */
    public static final class DispatchTable {

        private final Hash abilityHash;

        // Note: These are modified only during registration (rare) and checked via Assumption
        // The Assumption invalidation triggers deoptimization, so volatile is not needed
        @CompilationFinal(dimensions = 1)
        private CallTarget[] callTargets;

        @CompilationFinal
        private int capacity;

        @CompilationFinal
        private Assumption stableAssumption;

        public DispatchTable(Hash abilityHash) {
            this.abilityHash = abilityHash;
            this.callTargets = new CallTarget[8];  // Initial capacity
            this.capacity = 8;
            this.stableAssumption = createAssumption();
        }

        private Assumption createAssumption() {
            return Truffle.getRuntime().createAssumption("dispatch-table-stable:" + abilityHash.getHex());
        }

        /**
         * Register a CallTarget for an operation ID.
         * Invalidates the stable assumption to trigger deoptimization.
         */
        public synchronized void register(int operationId, CallTarget target) {
            // Invalidate old assumption
            stableAssumption.invalidate("new impl registered");

            // Grow array if needed
            if (operationId >= capacity) {
                int newCapacity = Math.max(capacity * 2, operationId + 1);
                CallTarget[] newTargets = new CallTarget[newCapacity];
                System.arraycopy(callTargets, 0, newTargets, 0, capacity);
                callTargets = newTargets;
                capacity = newCapacity;
            }

            // Store the target
            callTargets[operationId] = target;

            // Create new assumption for stability
            stableAssumption = createAssumption();
        }

        /**
         * Get CallTarget for an operation ID.
         * O(1) array access - NO LOOKUP!
         */
        public CallTarget getCallTarget(int operationId) {
            if (operationId < 0 || operationId >= capacity) {
                return null;
            }
            return callTargets[operationId];
        }

        /**
         * Get the stability assumption.
         * Used by DispatchTableNode to detect when reinitialization is needed.
         */
        public Assumption getStableAssumption() {
            return stableAssumption;
        }

        /**
         * Get all registered operation IDs (for debugging).
         */
        @TruffleBoundary
        public int[] getRegisteredOperationIds() {
            int count = 0;
            for (int i = 0; i < capacity; i++) {
                if (callTargets[i] != null) count++;
            }

            int[] ids = new int[count];
            int idx = 0;
            for (int i = 0; i < capacity; i++) {
                if (callTargets[i] != null) {
                    ids[idx++] = i;
                }
            }
            return ids;
        }
    }
}

