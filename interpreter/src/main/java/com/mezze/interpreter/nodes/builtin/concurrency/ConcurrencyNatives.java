package com.mezze.interpreter.nodes.builtin.concurrency;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.builtin.BuiltinFieldNames;
import com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException;
import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry;
import com.mezze.interpreter.nodes.continuation.SpawnSupport;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.*;

/**
 * Native implementations for concurrency primitives.
 *
 * Includes:
 * - TVar (Software Transactional Memory)
 * - Atom (atomic reference)
 * - Mutex/Semaphore/Latch/WaitGroup
 * - Channel
 * - Agent
 * - CancellationToken
 */
public final class ConcurrencyNatives {

    // ==================== TVar (STM) ====================

    /**
     * Transactional Variable for STM.
     */
    public static class MezzeTVar {
        private static final AtomicLong NEXT_ID = new AtomicLong(0);
        private final long id = NEXT_ID.getAndIncrement();
        private final AtomicReference<VersionedValue> versionedValue;
        private final ConcurrentLinkedQueue<Thread> waiters = new ConcurrentLinkedQueue<>();

        public MezzeTVar(Object initialValue) {
            this.versionedValue = new AtomicReference<>(new VersionedValue(0L, initialValue));
        }

        public long getId() { return id; }
        public Object read() { return versionedValue.get().value; }
        public VersionedValue readVersioned() { return versionedValue.get(); }

        public void forceWrite(long version, Object value) {
            versionedValue.set(new VersionedValue(version, value));
            wakeWaiters();
        }

        private void wakeWaiters() {
            // Drain-loop: O(N) with no iterator allocation, no CHS overhead.
            Thread t;
            while ((t = waiters.poll()) != null) {
                LockSupport.unpark(t);
            }
        }

        public void addWaiter(Thread t) { waiters.add(t); }
        public void removeWaiter(Thread t) { waiters.remove(t); }

        @Override
        public String toString() { return "<tvar:" + id + ">"; }

        public record VersionedValue(long version, Object value) {}
    }

    // Thread-local transaction context
    private static final ThreadLocal<Transaction> currentTransaction = new ThreadLocal<>();
    private static final ThreadLocal<Integer> transactionNestingDepth = ThreadLocal.withInitial(() -> 0);
    /**
     * Flag set by stmRetry/stmCheck-false to signal that the surrounding
     * atomically must park and retry after the perform aborts. The stdlib
     * atomically free function checks this flag, parks on the saved read
     * set, then loops.
     */
    private static final ThreadLocal<Boolean> stmRetryPending = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /**
     * Snapshot of the txn's read set at the moment of retry — held so the
     * stdlib park-helper can wait on those specific TVar versions even
     * after the abort has cleared {@code currentTransaction}.
     */
    private static final ThreadLocal<List<Transaction.ReadEntry>> stmRetryReadSet = new ThreadLocal<>();
    /**
     * Nesting depth for stmAtomic's own retry loop. When > 0, a nested
     * stmAtomic call runs f against the existing transaction instead of
     * creating its own — the outer stmAtomic's retry loop governs
     * commit/retry. Separate from transactionNestingDepth which tracks
     * stmBeginTransaction/stmCommitTransaction (the effect-handler scope
     * path).
     */
    private static final ThreadLocal<Integer> atomicNestingDepth = ThreadLocal.withInitial(() -> 0);
    private static final AtomicLong globalVersion = new AtomicLong(0);
    private static final ReentrantLock commitLock = new ReentrantLock();

    /**
     * Clear this thread's STM ThreadLocals. Called from
     * {@code MezzeLanguage.disposeThread} / {@code disposeContext} so
     * residue from one Context can't corrupt the next one on the same
     * JVM thread. #380: an interrupted STM transaction (test failure,
     * uncaught exception, forced Context close) leaves
     * {@code transactionNestingDepth &gt; 0} and a live
     * {@code currentTransaction}; the next Context's stmBeginTransaction
     * then treats itself as a nested no-op, and its commit skips the
     * commit-lock path — writes silently drop. Reset per-thread on
     * dispose keeps the process-wide JVM safe for sequential Mezze
     * runs (mez dev watch, multi-test JVM, any host embedding).
     */
    public static void resetPerThreadStmState() {
        currentTransaction.remove();
        transactionNestingDepth.remove();
        atomicNestingDepth.remove();
        stmRetryPending.remove();
        stmRetryReadSet.remove();
    }

    @TruffleBoundary
    public static MezzeTVar tvarNew(MezzeRecord args) {
        Object init = getField(args, "init");
        return new MezzeTVar(init);
    }

    @TruffleBoundary
    public static Object tvarDeref(MezzeRecord args) {
        MezzeTVar tvar = getTVar(args, "var");
        return tvar.read();
    }

    @TruffleBoundary
    public static Object stmRead(MezzeRecord args) {
        MezzeTVar tvar = getTVar(args, "var");
        Transaction txn = getOrCreateTransaction();
        return txn.read(tvar);
    }

    @TruffleBoundary
    public static MezzeRecord stmWrite(MezzeRecord args) {
        MezzeTVar tvar = getTVar(args, "var");
        Object value = getField(args, "val");
        Transaction txn = getOrCreateTransaction();
        txn.write(tvar, value);
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static Object stmEnsure(MezzeRecord args) {
        MezzeTVar tvar = getTVar(args, "var");
        Transaction txn = getOrCreateTransaction();
        return txn.ensure(tvar);
    }

    @TruffleBoundary
    public static Object stmSeqRead(MezzeRecord args) {
        MezzeTVar tvar = getTVar(args, "var");
        return tvar.read();
    }

    @TruffleBoundary
    public static MezzeRecord stmSeqWrite(MezzeRecord args) {
        MezzeTVar tvar = getTVar(args, "var");
        Object value = getField(args, "val");
        long newVersion = globalVersion.incrementAndGet();
        tvar.forceWrite(newVersion, value);
        return MezzeRecord.empty();
    }

    /**
     * Non-transactional counterpart of {@link #stmEnsure}. Outside a
     * transaction, `ensure` (which marks a TVar for validation in the read
     * set) is meaningless — return the current value like a plain read.
     */
    @TruffleBoundary
    public static Object stmSeqEnsure(MezzeRecord args) {
        MezzeTVar tvar = getTVar(args, "var");
        return tvar.read();
    }

    private static MezzeTVar getTVar(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeTVar tvar) {
            return tvar;
        }
        // Handle newtype wrapper
        if (value instanceof MezzeRecord rec) {
            for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
                Object inner = rec.getField(i);
                if (inner instanceof MezzeTVar tvar) {
                    return tvar;
                }
            }
        }
        throw new NativeTypeMismatchException("Expected TVar for field '" + fieldName + "', got: " +
            (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private static Transaction getOrCreateTransaction() {
        Transaction txn = currentTransaction.get();
        if (txn == null) {
            txn = new Transaction();
            currentTransaction.set(txn);
        }
        return txn;
    }

    /**
     * Begin a new STM transaction for scoped effect support.
     * Used by initialize_scope in OptimisticSTM.
     * Supports nested scopes - if a transaction already exists, just increment nesting depth.
     * Returns a scope record (empty for now).
     */
    @TruffleBoundary
    public static MezzeRecord stmBeginTransaction(MezzeRecord args) {
        int depth = transactionNestingDepth.get();
        if (depth == 0) {
            // Create new transaction only at outermost level
            Transaction txn = new Transaction();
            currentTransaction.set(txn);
        }
        // Increment nesting depth
        transactionNestingDepth.set(depth + 1);
        return MezzeRecord.empty();
    }

    /**
     * Commit the current STM transaction for scoped effect support.
     * Used by finalize_scope in OptimisticSTM.
     * Only actually commits when nesting depth reaches 0.
     *
     * On conflict, throws RetryingScopeException to trigger automatic retry
     * of the entire perform block.
     */
    @TruffleBoundary
    public static MezzeRecord stmCommitTransaction(MezzeRecord args) {
        int depth = transactionNestingDepth.get();
        if (depth <= 0) {
            // No transaction to commit - shouldn't happen with proper pairing
            return MezzeRecord.empty();
        }

        // Decrement nesting depth
        transactionNestingDepth.set(depth - 1);

        if (depth == 1) {
            // Outermost level - actually commit
            Transaction txn = currentTransaction.get();
            if (txn != null) {
                try {
                    if (!txn.commit()) {
                        // Conflict detected - throw RetryingScopeException to retry the perform block
                        throw new com.mezze.interpreter.nodes.effect.RetryingScopeException(
                            100, // max retries
                            "STM transaction conflict - concurrent modification detected"
                        );
                    }
                } finally {
                    currentTransaction.remove();
                    // Reset nesting depth on commit (success or failure)
                    transactionNestingDepth.remove();
                }
            }
        }
        // Inner levels just decrement and return
        return MezzeRecord.empty();
    }

    /**
     * Commit with configurable max retries.
     * Used by OptimisticSTMWith to pass the max_retries parameter.
     */
    @TruffleBoundary
    public static MezzeRecord stmCommitTransactionCfg(MezzeRecord args) {
        int maxRetries = (int) getLong(args, "max_retries");
        if (maxRetries <= 0) maxRetries = 100; // Default

        int depth = transactionNestingDepth.get();
        if (depth <= 0) {
            return MezzeRecord.empty();
        }

        transactionNestingDepth.set(depth - 1);

        if (depth == 1) {
            Transaction txn = currentTransaction.get();
            if (txn != null) {
                try {
                    if (!txn.commit()) {
                        throw new com.mezze.interpreter.nodes.effect.RetryingScopeException(
                            maxRetries,
                            "STM transaction conflict - concurrent modification detected"
                        );
                    }
                } finally {
                    currentTransaction.remove();
                    transactionNestingDepth.remove();
                }
            }
        }
        return MezzeRecord.empty();
    }

    /**
     * STM Transaction with MVCC and Bloom Filter optimization.
     *
     * Performance optimizations:
     * 1. Bloom filter for fast "definitely no conflict" checks
     * 2. Version-based conflict detection with early exit
     * 3. Lock-free read path with CAS for commits
     */
    private static class Transaction {
        // Read/write tracking
        private final Map<Long, ReadEntry> readSet = new HashMap<>();
        private final Map<Long, WriteEntry> writeSet = new HashMap<>();
        private final Set<Long> ensureSet = new HashSet<>();

        // Bloom filter for fast conflict detection (64-bit, 4 hash functions)
        // Trades ~1% false positive rate for O(1) "definitely no conflict" check
        private long readBloom = 0L;

        // Snapshot version at transaction start for consistent reads
        private final long startVersion = globalVersion.get();

        record ReadEntry(MezzeTVar tvar, long version, Object value) {}
        record WriteEntry(MezzeTVar tvar, Object value) {}

        // Bloom filter hash functions using FNV-1a inspired mixing
        private static long bloomHash(long id) {
            long h = id;
            // 4 different bits set based on id, spread across 64 bits
            return (1L << (h & 63))
                 | (1L << ((h >>> 6) & 63))
                 | (1L << ((h >>> 12) & 63))
                 | (1L << ((h >>> 18) & 63));
        }

        Object read(MezzeTVar tvar) {
            long id = tvar.getId();
            // Fast path: check write set first (most recent value)
            WriteEntry we = writeSet.get(id);
            if (we != null) return we.value;

            // Check if already read
            ReadEntry re = readSet.get(id);
            if (re != null) return re.value;

            // Read from TVar and add to read set
            MezzeTVar.VersionedValue vv = tvar.readVersioned();
            readSet.put(id, new ReadEntry(tvar, vv.version(), vv.value()));
            readBloom |= bloomHash(id);
            return vv.value();
        }

        void write(MezzeTVar tvar, Object value) {
            long id = tvar.getId();
            // Ensure we have a read snapshot for validation
            if (!readSet.containsKey(id) && !writeSet.containsKey(id)) {
                read(tvar);
            }
            writeSet.put(id, new WriteEntry(tvar, value));
        }

        Object ensure(MezzeTVar tvar) {
            ensureSet.add(tvar.getId());
            return read(tvar);
        }

        /**
         * Check if this transaction MIGHT conflict with given TVar IDs.
         * Uses bloom filter for fast "definitely no conflict" answer.
         * @return false if definitely no conflict, true if maybe conflict
         */
        boolean mightConflict(long tvarId) {
            return (readBloom & bloomHash(tvarId)) != 0;
        }

        boolean validate() {
            for (ReadEntry entry : readSet.values()) {
                MezzeTVar.VersionedValue current = entry.tvar.readVersioned();
                if (current.version() != entry.version) return false;
            }
            return true;
        }

        boolean commit() {
            // Read-only transactions just need validation
            if (writeSet.isEmpty()) return validate();

            // Use commit lock for writes
            commitLock.lock();
            try {
                // Validate all reads are still at same version
                if (!validate()) return false;

                // Atomically write all changes with new version
                long newVersion = globalVersion.incrementAndGet();
                for (WriteEntry entry : writeSet.values()) {
                    entry.tvar.forceWrite(newVersion, entry.value);
                }
                return true;
            } finally {
                commitLock.unlock();
            }
        }
    }

    // ==================== Atom ====================

    /**
     * Atomic reference - simpler than TVar, no transactions.
     * Supports watches and optional validation.
     */
    public static class MezzeAtom {
        private static final AtomicLong NEXT_ID = new AtomicLong(0);
        private final long id = NEXT_ID.getAndIncrement();
        private final AtomicReference<Object> value;
        private volatile MezzeFunction validator;  // null if no validation, mutable
        private final java.util.concurrent.ConcurrentHashMap<String, WatchCallback> watches;

        // Stores watch callback info
        private record WatchCallback(MezzeFunction callback, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {}

        public MezzeAtom(Object initialValue) {
            this(initialValue, null);
        }

        public MezzeAtom(Object initialValue, MezzeFunction validator) {
            this.value = new AtomicReference<>(initialValue);
            this.validator = validator;
            this.watches = new java.util.concurrent.ConcurrentHashMap<>();
        }

        public boolean hasValidator() { return validator != null; }

        public Object deref() { return value.get(); }

        public void reset(Object newValue, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
            validate(newValue, callNode);
            Object oldValue = value.getAndSet(newValue);
            notifyWatches(oldValue, newValue, callNode);
        }

        public Object swap(Object newValue, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
            validate(newValue, callNode);
            Object oldValue = value.getAndSet(newValue);
            notifyWatches(oldValue, newValue, callNode);
            return oldValue;
        }

        /**
         * Run the validator (if any) against a proposed value WITHOUT throwing.
         * Returns true if the value is acceptable (or there is no validator).
         * Used by the ValidatedAtom Result-returning ops.
         */
        public boolean validatePasses(Object newValue, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
            MezzeFunction v = validator;  // volatile read
            if (v == null) return true;
            MezzeRecord argRecord = MezzeRecord.fromFields(new String[]{"val"}, new Object[]{newValue});
            Object[] closuresWithArg = new Object[v.getClosureValues().length + 1];
            System.arraycopy(v.getClosureValues(), 0, closuresWithArg, 0, v.getClosureValues().length);
            closuresWithArg[v.getClosureValues().length] = argRecord;
            Object result = callNode.call(v.getCallTarget(), closuresWithArg);
            return !(result instanceof Boolean b && !b);
        }

        // Legacy method for simple cases without watches
        public void reset(Object newValue) { value.set(newValue); }
        public Object swap(Object newValue) { return value.getAndSet(newValue); }

        public boolean compareAndSet(Object expected, Object newValue) {
            return value.compareAndSet(expected, newValue);
        }

        public void validate(Object newValue, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
            MezzeFunction v = validator;  // volatile read
            if (v == null) return;

            MezzeRecord argRecord = MezzeRecord.fromFields(new String[]{"val"}, new Object[]{newValue});
            Object[] closuresWithArg = new Object[v.getClosureValues().length + 1];
            System.arraycopy(v.getClosureValues(), 0, closuresWithArg, 0, v.getClosureValues().length);
            closuresWithArg[v.getClosureValues().length] = argRecord;

            Object result = callNode.call(v.getCallTarget(), closuresWithArg);
            if (result instanceof Boolean b && !b) {
                // User-reachable: a validator returns False for a swap/reset.
                // Guest-visible fatal — the Atom API (reset/swap) returns a
                // bare value, not a Result (see report for the Result-returning
                // recommendation).
                throw new MezzeGuestException("Atom: validator rejected the new value");
            }
        }

        public void addWatch(String key, MezzeFunction callback, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
            watches.put(key, new WatchCallback(callback, callNode));
        }

        public void removeWatch(String key) {
            watches.remove(key);
        }

        public void notifyWatches(Object oldValue, Object newValue, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
            for (var entry : watches.entrySet()) {
                String watchKey = entry.getKey();
                WatchCallback wc = entry.getValue();
                // Watch callback signature: {key: String, old: 'a, new: 'a} -> Unit
                MezzeRecord argRecord = MezzeRecord.fromFields(
                    new String[]{"key", "old", "new"},
                    new Object[]{watchKey, oldValue, newValue}
                );
                Object[] closuresWithArg = new Object[wc.callback.getClosureValues().length + 1];
                System.arraycopy(wc.callback.getClosureValues(), 0, closuresWithArg, 0, wc.callback.getClosureValues().length);
                closuresWithArg[wc.callback.getClosureValues().length] = argRecord;
                callNode.call(wc.callback.getCallTarget(), closuresWithArg);
            }
        }

        @Override
        public String toString() { return "<atom:" + id + ">"; }
    }

    @TruffleBoundary
    public static MezzeAtom atomNew(MezzeRecord args) {
        Object value = getField(args, "val");
        return new MezzeAtom(value);
    }

    @TruffleBoundary
    public static Object atomNewValidated(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object value = getField(args, "val");
        MezzeFunction validator = getFunction(args, "validator");

        // Create atom with validator and validate initial value
        MezzeAtom atom = new MezzeAtom(value, validator);

        // Validate initial value
        MezzeRecord validatorArg = MezzeRecord.fromFields(new String[]{"val"}, new Object[]{value});
        Object[] closuresWithArg = new Object[validator.getClosureValues().length + 1];
        System.arraycopy(validator.getClosureValues(), 0, closuresWithArg, 0, validator.getClosureValues().length);
        closuresWithArg[validator.getClosureValues().length] = validatorArg;

        Object result = callNode.call(validator.getCallTarget(), closuresWithArg);
        if (result instanceof Boolean b && !b) {
            return validatorRejectedErr(value);  // initial value rejected; return Err, no crash
        }

        return MezzeVariant.ok(atom);
    }

    @TruffleBoundary
    public static Object atomDeref(MezzeRecord args) {
        MezzeAtom atom = getAtom(args, "atom");
        return atom.deref();
    }

    @TruffleBoundary
    public static Object atomReset(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeAtom atom = getAtom(args, "atom");
        assertNoValidator(atom, "reset");
        Object value = getField(args, "val");
        atom.reset(value, callNode);
        return value;  // Return the new value
    }

    /**
     * Belt-and-suspenders: a plain {@code Atom} must never carry a validator.
     * The type system prevents a validated ref from reaching a plain op, so
     * this only fires on an internal invariant violation.
     */
    private static void assertNoValidator(MezzeAtom atom, String op) {
        if (atom.hasValidator()) {
            throw new NativeTypeMismatchException(
                "Atom." + op + ": plain (unvalidated) op received a validator-carrying atom");
        }
    }

    /** Build {@code Err { error = ValidatorRejected { val = <rejected> } }}. */
    private static MezzeVariant validatorRejectedErr(Object rejectedValue) {
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("ValidatorRejected");
        MezzeRecord payload = MezzeRecord.fromFields(new String[]{"val"}, new Object[]{rejectedValue});
        MezzeVariant rejected = MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        return MezzeVariant.err(rejected);
    }

    @TruffleBoundary
    public static Object atomSwap(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // Simple value replacement version (no function callback)
        MezzeAtom atom = getAtom(args, "atom");
        Object val = getField(args, "val");
        return atom.swap(val, callNode);
    }

    private static final String[] CURRENT_FIELD = BuiltinFieldNames.CURRENT;

    @TruffleBoundary
    public static Object atomSwapFunc(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // Plain swap: total, returns the new value directly. Must not be validated.
        MezzeAtom atom = getAtom(args, "atom");
        assertNoValidator(atom, "swap");
        MezzeFunction f = getFunction(args, "f");
        Object oldVal;
        Object newVal;
        do {
            oldVal = atom.deref();
            // The function takes {current} argument
            MezzeRecord argRecord = MezzeRecord.fromFields(CURRENT_FIELD, new Object[]{oldVal});
            Object[] closuresWithArg = new Object[f.getClosureValues().length + 1];
            System.arraycopy(f.getClosureValues(), 0, closuresWithArg, 0, f.getClosureValues().length);
            closuresWithArg[f.getClosureValues().length] = argRecord;
            newVal = callNode.call(f.getCallTarget(), closuresWithArg);
        } while (!atom.compareAndSet(oldVal, newVal));

        // Notify watches after successful swap
        atom.notifyWatches(oldVal, newVal, callNode);

        return newVal;
    }

    // ==================== Validated Atom ops (return Result) ====================

    @TruffleBoundary
    public static Object atomResetValidated(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeAtom atom = getAtom(args, "atom");
        Object value = getField(args, "val");
        if (!atom.validatePasses(value, callNode)) {
            return validatorRejectedErr(value);  // atom unchanged
        }
        Object oldValue = atom.deref();
        atom.value.set(value);
        atom.notifyWatches(oldValue, value, callNode);
        return MezzeVariant.ok(value);
    }

    @TruffleBoundary
    public static Object atomSwapValidated(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeAtom atom = getAtom(args, "atom");
        MezzeFunction f = getFunction(args, "f");
        Object oldVal;
        Object newVal;
        do {
            oldVal = atom.deref();
            MezzeRecord argRecord = MezzeRecord.fromFields(CURRENT_FIELD, new Object[]{oldVal});
            Object[] closuresWithArg = new Object[f.getClosureValues().length + 1];
            System.arraycopy(f.getClosureValues(), 0, closuresWithArg, 0, f.getClosureValues().length);
            closuresWithArg[f.getClosureValues().length] = argRecord;
            newVal = callNode.call(f.getCallTarget(), closuresWithArg);
            if (!atom.validatePasses(newVal, callNode)) {
                return validatorRejectedErr(newVal);  // atom unchanged
            }
        } while (!atom.compareAndSet(oldVal, newVal));
        atom.notifyWatches(oldVal, newVal, callNode);
        return MezzeVariant.ok(newVal);
    }

    @TruffleBoundary
    public static Object atomSwapWithValidated(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeAtom atom = getAtom(args, "atom");
        MezzeFunction f = getFunction(args, "f");
        Object arg = getField(args, "arg");
        Object oldVal;
        Object newVal;
        do {
            oldVal = atom.deref();
            MezzeRecord funcArg = MezzeRecord.fromFields(CURRENT_ARG_FIELDS, new Object[]{oldVal, arg});
            Object[] closures = f.getClosureValues();
            Object[] callArgs = new Object[closures.length + 1];
            System.arraycopy(closures, 0, callArgs, 0, closures.length);
            callArgs[closures.length] = funcArg;
            newVal = callNode.call(f.getCallTarget(), callArgs);
            if (!atom.validatePasses(newVal, callNode)) {
                return validatorRejectedErr(newVal);  // atom unchanged
            }
        } while (!atom.compareAndSet(oldVal, newVal));
        atom.notifyWatches(oldVal, newVal, callNode);
        return MezzeVariant.ok(newVal);
    }

    @TruffleBoundary
    public static Object atomGetAndSwapValidated(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeAtom atom = getAtom(args, "atom");
        MezzeFunction f = getFunction(args, "f");
        Object oldVal;
        Object newVal;
        do {
            oldVal = atom.deref();
            MezzeRecord argRecord = MezzeRecord.fromFields(CURRENT_FIELD, new Object[]{oldVal});
            Object[] closuresWithArg = new Object[f.getClosureValues().length + 1];
            System.arraycopy(f.getClosureValues(), 0, closuresWithArg, 0, f.getClosureValues().length);
            closuresWithArg[f.getClosureValues().length] = argRecord;
            newVal = callNode.call(f.getCallTarget(), closuresWithArg);
            if (!atom.validatePasses(newVal, callNode)) {
                return validatorRejectedErr(newVal);  // atom unchanged
            }
        } while (!atom.compareAndSet(oldVal, newVal));
        atom.notifyWatches(oldVal, newVal, callNode);
        return MezzeVariant.ok(MezzeRecord.fromFields(OLD_NEW_FIELDS, new Object[]{oldVal, newVal}));
    }

    @TruffleBoundary
    public static boolean atomCompareAndSet(MezzeRecord args) {
        MezzeAtom atom = getAtom(args, "atom");
        Object expected = getField(args, "expected");
        Object newVal = getField(args, "new");
        return atom.compareAndSet(expected, newVal);
    }

    private static MezzeAtom getAtom(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeAtom atom) return atom;
        if (value instanceof MezzeRecord rec) {
            for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
                Object inner = rec.getField(i);
                if (inner instanceof MezzeAtom atom) return atom;
            }
        }
        throw new NativeTypeMismatchException("Expected Atom for field '" + fieldName + "'");
    }

    // ==================== Mutex ====================

    public static class MezzeMutex {
        private final ReentrantLock lock = new ReentrantLock();
        public void lock() { lock.lock(); }
        public void lockInterruptibly() throws InterruptedException { lock.lockInterruptibly(); }
        public void unlock() { lock.unlock(); }
        public boolean tryLock() { return lock.tryLock(); }
        @Override public String toString() { return "<mutex>"; }
    }

    @TruffleBoundary
    public static MezzeMutex mutexNew(MezzeRecord args) {
        return new MezzeMutex();
    }

    @TruffleBoundary
    public static MezzeRecord mutexLock(MezzeRecord args) {
        MezzeMutex mutex = getMutex(args, "mutex");
        // Interruptibly so Async.cancel surfaces AsyncCancelled to a
        // task blocked on mutex.lock.
        try {
            mutex.lockInterruptibly();
            return MezzeRecord.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Mutex lock cancelled");
        }
    }

    @TruffleBoundary
    public static MezzeRecord mutexUnlock(MezzeRecord args) {
        MezzeMutex mutex = getMutex(args, "mutex");
        mutex.unlock();
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static boolean mutexTryLock(MezzeRecord args) {
        MezzeMutex mutex = getMutex(args, "mutex");
        return mutex.tryLock();
    }

    private static MezzeMutex getMutex(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeMutex m) return m;
        if (value instanceof MezzeRecord rec) {
            for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
                Object inner = rec.getField(i);
                if (inner instanceof MezzeMutex m) return m;
            }
        }
        throw new NativeTypeMismatchException("Expected Mutex for field '" + fieldName + "'");
    }

    // ==================== Semaphore ====================

    public static class MezzeSemaphore {
        private final Semaphore semaphore;
        public MezzeSemaphore(int permits) { this.semaphore = new Semaphore(permits); }
        public void acquire() throws InterruptedException { semaphore.acquire(); }
        public void release() { semaphore.release(); }
        public boolean tryAcquire() { return semaphore.tryAcquire(); }
        public int availablePermits() { return semaphore.availablePermits(); }
        @Override public String toString() { return "<semaphore:" + semaphore.availablePermits() + ">"; }
    }

    @TruffleBoundary
    public static MezzeSemaphore semaphoreNew(MezzeRecord args) {
        long permits = getLong(args, "permits");
        return new MezzeSemaphore((int) permits);
    }

    @TruffleBoundary
    public static MezzeRecord semaphoreAcquire(MezzeRecord args) {
        MezzeSemaphore sem = getSemaphore(args, "sem");
        try {
            sem.acquire();
            return MezzeRecord.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Semaphore acquire cancelled");
        }
    }

    @TruffleBoundary
    public static MezzeRecord semaphoreRelease(MezzeRecord args) {
        MezzeSemaphore sem = getSemaphore(args, "sem");
        sem.release();
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static boolean semaphoreTryAcquire(MezzeRecord args) {
        MezzeSemaphore sem = getSemaphore(args, "sem");
        return sem.tryAcquire();
    }

    @TruffleBoundary
    public static long semaphoreAvailable(MezzeRecord args) {
        MezzeSemaphore sem = getSemaphore(args, "sem");
        return sem.availablePermits();
    }

    private static MezzeSemaphore getSemaphore(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeSemaphore s) return s;
        if (value instanceof MezzeRecord rec) {
            for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
                Object inner = rec.getField(i);
                if (inner instanceof MezzeSemaphore s) return s;
            }
        }
        throw new NativeTypeMismatchException("Expected Semaphore for field '" + fieldName + "'");
    }

    // ==================== Latch ====================

    public static class MezzeLatch {
        private final CountDownLatch latch;
        public MezzeLatch(int count) { this.latch = new CountDownLatch(count); }
        public void countDown() { latch.countDown(); }
        public void await() throws InterruptedException { latch.await(); }
        public long getCount() { return latch.getCount(); }
        @Override public String toString() { return "<latch:" + latch.getCount() + ">"; }
    }

    @TruffleBoundary
    public static MezzeLatch latchNew(MezzeRecord args) {
        long count = getLong(args, "count");
        return new MezzeLatch((int) count);
    }

    @TruffleBoundary
    public static MezzeRecord latchCountDown(MezzeRecord args) {
        MezzeLatch latch = getLatch(args, "latch");
        latch.countDown();
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeRecord latchAwait(MezzeRecord args) {
        MezzeLatch latch = getLatch(args, "latch");
        try {
            latch.await();
            return MezzeRecord.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Latch await cancelled");
        }
    }

    @TruffleBoundary
    public static long latchGetCount(MezzeRecord args) {
        MezzeLatch latch = getLatch(args, "latch");
        return latch.getCount();
    }

    private static MezzeLatch getLatch(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeLatch l) return l;
        if (value instanceof MezzeRecord rec) {
            for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
                Object inner = rec.getField(i);
                if (inner instanceof MezzeLatch l) return l;
            }
        }
        throw new NativeTypeMismatchException("Expected Latch for field '" + fieldName + "'");
    }

    // ==================== WaitGroup ====================

    public static class MezzeWaitGroup {
        private final AtomicInteger count = new AtomicInteger(0);
        private final CountDownLatch done = new CountDownLatch(1);

        public void add(int delta) {
            int newCount = count.addAndGet(delta);
            if (newCount == 0) done.countDown();
        }
        public void done() { add(-1); }
        public void await() throws InterruptedException {
            if (count.get() > 0) done.await();
        }
        public int getCount() { return count.get(); }
        @Override public String toString() { return "<waitgroup:" + count.get() + ">"; }
    }

    @TruffleBoundary
    public static MezzeWaitGroup waitgroupNew(MezzeRecord args) {
        return new MezzeWaitGroup();
    }

    @TruffleBoundary
    public static MezzeRecord waitgroupAdd(MezzeRecord args) {
        MezzeWaitGroup wg = getWaitGroup(args, "wg");
        long delta = getLong(args, "delta");
        wg.add((int) delta);
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeRecord waitgroupDone(MezzeRecord args) {
        MezzeWaitGroup wg = getWaitGroup(args, "wg");
        wg.done();
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeRecord waitgroupWait(MezzeRecord args) {
        MezzeWaitGroup wg = getWaitGroup(args, "wg");
        try {
            wg.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("WaitGroup wait cancelled");
        }
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static long waitgroupCount(MezzeRecord args) {
        MezzeWaitGroup wg = getWaitGroup(args, "wg");
        return wg.getCount();
    }

    private static MezzeWaitGroup getWaitGroup(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeWaitGroup wg) return wg;
        if (value instanceof MezzeRecord rec) {
            for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
                Object inner = rec.getField(i);
                if (inner instanceof MezzeWaitGroup wg) return wg;
            }
        }
        throw new NativeTypeMismatchException("Expected WaitGroup for field '" + fieldName + "'");
    }

    // ==================== Channel ====================

    public static class MezzeChannel {
        final BlockingQueue<Object> queue;
        private volatile boolean closed = false;
        // Waiters registered by channelRecvAnyTimeout — unparked on offer.
        final ConcurrentLinkedQueue<Thread> selectWaiters = new ConcurrentLinkedQueue<>();

        public MezzeChannel(int capacity) {
            // capacity <= 0 means unbounded (LinkedBlockingQueue)
            // capacity > 0 means bounded (ArrayBlockingQueue)
            this.queue = capacity <= 0
                ? new java.util.concurrent.LinkedBlockingQueue<>()
                : new ArrayBlockingQueue<>(capacity);
        }

        public void send(Object value) throws InterruptedException {
            // NOTE: this blocking send()/receive() pair is dead — the live
            // channel path is park-aware (channel.mz → __native_channel_try_send
            // / __native_channel_try_recv, which return False/None on a closed
            // channel rather than throwing). Kept as a labeled guest error in
            // case a future native re-wires the blocking path.
            if (closed) throw new MezzeGuestException("Channel: send on a closed channel");
            queue.put(value);
        }

        public Object receive() throws InterruptedException {
            Object value = queue.take();
            return value;
        }

        public void close() { closed = true; }
        public boolean isClosed() { return closed; }
        @Override public String toString() { return "<channel>"; }
    }

    @TruffleBoundary
    public static MezzeChannel channelMake(MezzeRecord args) {
        long capacity = getLongOrDefault(args, "capacity", 0);
        return new MezzeChannel((int) capacity);
    }

    @TruffleBoundary
    public static MezzeRecord channelClose(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        ch.close();
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static boolean channelIsClosed(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        return ch.isClosed();
    }

    @TruffleBoundary
    public static boolean channelTrySend(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        Object val = getField(args, "val");
        if (ch.isClosed()) return false;
        boolean sent = ch.queue.offer(val);
        if (sent) {
            // Wake any threads parked in channelRecvAnyTimeout select.
            Thread w;
            while ((w = ch.selectWaiters.poll()) != null) {
                LockSupport.unpark(w);
            }
        }
        return sent;
    }

    @TruffleBoundary
    public static Object channelTryRecv(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        Object val = ch.queue.poll();
        if (val == null) return createNone();
        return createSome(val);
    }

    @TruffleBoundary
    public static Object channelRecvAny(MezzeRecord args) {
        Object[] channels = getArray(args, "channels");
        // Non-blocking check of all channels
        for (int i = 0; i < channels.length; i++) {
            MezzeChannel ch;
            if (channels[i] instanceof MezzeChannel c) {
                ch = c;
            } else if (channels[i] instanceof MezzeRecord rec) {
                ch = getChannelFromRecord(rec);
            } else {
                continue;
            }
            Object val = ch.queue.poll();
            if (val != null) {
                return createSome(MezzeRecord.fromFields(
                    new String[]{"index", "val"},
                    new Object[]{(long) i, val}
                ));
            }
        }
        return createNone();
    }

    @TruffleBoundary
    public static Object channelRecvAnyTimeout(MezzeRecord args) {
        Object[] channels = getArray(args, "channels");
        long ms = getLong(args, "ms");
        long deadlineNanos = System.nanoTime() + ms * 1_000_000L;

        // Resolve MezzeChannel handles once up front.
        MezzeChannel[] resolved = new MezzeChannel[channels.length];
        for (int i = 0; i < channels.length; i++) {
            if (channels[i] instanceof MezzeChannel c) {
                resolved[i] = c;
            } else if (channels[i] instanceof MezzeRecord rec) {
                resolved[i] = getChannelFromRecord(rec);
            }
            // null entries (unresolvable) are skipped in the poll loop
        }

        Thread self = Thread.currentThread();

        // Register this thread as a select waiter on every channel so
        // channelTrySend unparks us immediately — no sleep-polling.
        for (MezzeChannel ch : resolved) {
            if (ch != null) ch.selectWaiters.add(self);
        }

        try {
            while (true) {
                // Scan all channels for a ready value.
                for (int i = 0; i < resolved.length; i++) {
                    MezzeChannel ch = resolved[i];
                    if (ch == null) continue;
                    Object val = ch.queue.poll();
                    if (val != null) {
                        return createSome(MezzeRecord.fromFields(
                            new String[]{"index", "val"},
                            new Object[]{(long) i, val}
                        ));
                    }
                }

                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) break;

                // Park until a sender unparks us or the deadline expires.
                LockSupport.parkNanos(remainingNanos);

                if (Thread.interrupted()) {
                    throw new CancellationException("Channel select cancelled");
                }
            }
            return createNone();
        } finally {
            // Deregister from all channels.
            for (MezzeChannel ch : resolved) {
                if (ch != null) ch.selectWaiters.remove(self);
            }
        }
    }

    private static MezzeChannel getChannelFromRecord(MezzeRecord rec) {
        for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
            Object inner = rec.getField(i);
            if (inner instanceof MezzeChannel ch) return ch;
        }
        throw new NativeTypeMismatchException("[internal] expected a record containing a channel, found none");
    }

    private static MezzeChannel getChannel(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeChannel ch) return ch;
        if (value instanceof MezzeRecord rec) {
            for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
                Object inner = rec.getField(i);
                if (inner instanceof MezzeChannel ch) return ch;
            }
        }
        throw new NativeTypeMismatchException("Expected Channel for field '" + fieldName + "'");
    }

    // ==================== CancellationToken ====================

    public static class MezzeCancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile String reason = null;
        private final MezzeCancellationToken parent;

        public MezzeCancellationToken() {
            this.parent = null;
        }

        public MezzeCancellationToken(MezzeCancellationToken parent) {
            this.parent = parent;
        }

        public void cancel(String reason) {
            this.reason = reason;
            cancelled.set(true);
        }

        public void cancel() {
            cancel("no reason");
        }

        public boolean isCancelled() {
            // Check self first, then parent chain
            if (cancelled.get()) return true;
            if (parent != null) return parent.isCancelled();
            return false;
        }

        public String getReason() {
            // If we have our own cancellation, return that reason
            if (cancelled.get()) return reason != null ? reason : "no reason";
            // Otherwise check parent
            if (parent != null && parent.isCancelled()) return parent.getReason();
            return null;
        }

        @Override public String toString() { return "<cancellation-token:" + (isCancelled() ? "cancelled" : "active") + ">"; }
    }

    @TruffleBoundary
    public static MezzeCancellationToken cancellationTokenNew(MezzeRecord args) {
        return new MezzeCancellationToken();
    }

    @TruffleBoundary
    public static MezzeRecord cancellationTokenCancel(MezzeRecord args) {
        MezzeCancellationToken token = getCancellationToken(args, "token");
        Object reasonObj = args.getField("reason");
        String reason = reasonObj instanceof String s ? s : "no reason";
        token.cancel(reason);
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static boolean cancellationTokenIsCancelled(MezzeRecord args) {
        MezzeCancellationToken token = getCancellationToken(args, "token");
        return token.isCancelled();
    }

    private static MezzeCancellationToken getCancellationToken(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeCancellationToken t) return t;
        if (value instanceof MezzeRecord rec) {
            for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
                Object inner = rec.getField(i);
                if (inner instanceof MezzeCancellationToken t) return t;
            }
        }
        throw new NativeTypeMismatchException("Expected CancellationToken for field '" + fieldName + "'");
    }

    // ==================== Agent ====================

    /**
     * Agent action - wraps a function, its Truffle environment, and the
     * effect-handler chain captured at send time. The chain is installed
     * on the worker thread when this action runs so effect-ops inside
     * the function dispatch through the same handlers that were in scope
     * at the send site.
     */
    private record AgentAction(MezzeFunction func, com.oracle.truffle.api.TruffleLanguage.Env env, com.mezze.interpreter.nodes.continuation.HandlerChainEntry[] chain, Object scope) {}

    /**
     * Clojure-style Agent for async state updates.
     *
     * Uses Truffle-aware threads (via env.newTruffleThreadBuilder) to ensure
     * CallTargets can be properly invoked from spawned threads.
     */
    public static class MezzeAgent {
        private static final String[] CURRENT_FIELD = BuiltinFieldNames.CURRENT;

        private final AtomicReference<Object> state;
        private final AtomicReference<String> error = new AtomicReference<>(null);
        private final AtomicBoolean processing = new AtomicBoolean(false);
        private final ConcurrentLinkedQueue<AgentAction> actionQueue = new ConcurrentLinkedQueue<>();
        // Threads parked in await() — unparked when processing finishes or errors.
        private final ConcurrentLinkedQueue<Thread> awaitWaiters = new ConcurrentLinkedQueue<>();

        public MezzeAgent(Object initialState) {
            this.state = new AtomicReference<>(initialState);
        }

        public Object deref() { return state.get(); }

        public String getError() { return error.get(); }

        public void clearError(Object newValue) {
            error.set(null);
            state.set(newValue);
            // Resume processing if there are pending actions
            processQueue();
        }

        /**
         * Queue an action function to be applied to the agent's state.
         * The function receives {current: currentState} and returns the new state.
         */
        public void send(MezzeFunction func, com.oracle.truffle.api.TruffleLanguage.Env env) {
            // Check if agent is in error state
            if (error.get() != null) {
                // User-reachable: sending to an agent that failed a prior
                // action. The Agent API exposes has_error/agent_error for
                // callers to check first; sending to an errored agent is a
                // documented guest-visible fatal (send returns bare {} <Async>).
                throw new MezzeGuestException("Agent: cannot send to an agent in error state: " + error.get());
            }

            actionQueue.add(new AgentAction(func, env, HandlerChainRegistry.snapshotHandlerChainEntries(), HandlerChainRegistry.lookupScopeVar()));
            processQueue();
        }

        /**
         * Process the action queue.
         * Uses CAS to ensure only one thread processes at a time.
         */
        private void processQueue() {
            // Try to become the processor
            if (!processing.compareAndSet(false, true)) {
                return; // Another thread is already processing
            }

            // Get the first action to process (we need its env for the thread)
            AgentAction firstAction = actionQueue.peek();
            if (firstAction == null) {
                processing.set(false);
                return;
            }

            // Create a Truffle thread using the environment from the action
            Thread thread = firstAction.env.newTruffleThreadBuilder(() -> {
                try {
                    while (true) {
                        AgentAction action = actionQueue.poll();
                        if (action == null) break;

                        try {
                            Object currentState = state.get();
                            MezzeRecord argRecord = MezzeRecord.fromFields(
                                CURRENT_FIELD,
                                new Object[]{currentState}
                            );

                            MezzeFunction func = action.func;
                            Object[] closureValues = func.getClosureValues();
                            Object[] callArgs = new Object[closureValues.length + 1];
                            System.arraycopy(closureValues, 0, callArgs, 0, closureValues.length);
                            callArgs[closureValues.length] = argRecord;

                            Object[] newStateHolder = new Object[1];
                            HandlerChainRegistry.runWithHandlerChain(action.chain, action.scope, () ->
                                newStateHolder[0] = func.getCallTarget().call(callArgs));
                            state.set(newStateHolder[0]);
                        } catch (Throwable e) {
                            // Set error state. Unhandled effect-ops are surfaced
                            // as TaskUnhandledOpException; their op name lands
                            // in the agent's error field — a future enhancement
                            // could re-issue on a fiber, but agents are
                            // fire-and-forget so we just record and stop.
                            error.set(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
                            // Stop processing on error
                            break;
                        }
                    }
                } finally {
                    processing.set(false);

                    // Wake all threads parked in await().
                    Thread w;
                    while ((w = awaitWaiters.poll()) != null) {
                        LockSupport.unpark(w);
                    }

                    // Check if more actions were added while we were finishing
                    if (!actionQueue.isEmpty() && error.get() == null) {
                        processQueue();
                    }
                }
            }).virtual(true).build();  // Use virtual threads for efficiency

            thread.start();
        }

        /**
         * Wait for all pending actions to complete.
         * Returns true if completed within timeout, false otherwise.
         * timeout_ms of 0 means wait indefinitely.
         *
         * Uses LockSupport.park instead of sleep-polling — the processing
         * thread unparks us when processing finishes or errors.
         */
        public boolean await(long timeoutMs) {
            long deadlineNanos = timeoutMs > 0
                ? System.nanoTime() + timeoutMs * 1_000_000L
                : Long.MAX_VALUE;

            Thread self = Thread.currentThread();
            awaitWaiters.add(self);
            try {
                while (!actionQueue.isEmpty() || processing.get()) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) return false;

                    if (deadlineNanos == Long.MAX_VALUE) {
                        LockSupport.park();
                    } else {
                        LockSupport.parkNanos(remainingNanos);
                    }

                    if (Thread.interrupted()) return false;
                }
                return true;
            } finally {
                awaitWaiters.remove(self);
            }
        }

        @Override public String toString() { return "<agent>"; }
    }

    @TruffleBoundary
    public static MezzeAgent agentNew(MezzeRecord args) {
        Object value = getField(args, "val");
        return new MezzeAgent(value);
    }

    @TruffleBoundary
    public static Object agentDeref(MezzeRecord args) {
        MezzeAgent agent = getAgent(args, "agent");
        return agent.deref();
    }

    @TruffleBoundary
    public static MezzeRecord agentSend(MezzeRecord args, com.mezze.interpreter.nodes.MezzeNode node) {
        MezzeAgent agent = getAgent(args, "agent");
        Object f = getField(args, "f");
        if (!(f instanceof MezzeFunction func)) {
            throw new NativeTypeMismatchException("Agent send expects a function, got: " +
                (f != null ? f.getClass().getSimpleName() : "null"));
        }
        com.oracle.truffle.api.TruffleLanguage.Env env =
            com.mezze.interpreter.runtime.MezzeContext.get(node).getEnv();
        agent.send(func, env);
        return MezzeRecord.empty();
    }

    private static MezzeAgent getAgent(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeAgent a) return a;
        if (value instanceof MezzeRecord rec) {
            for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
                Object inner = rec.getField(i);
                if (inner instanceof MezzeAgent a) return a;
            }
        }
        throw new NativeTypeMismatchException("Expected Agent for field '" + fieldName + "'");
    }

    // ==================== Additional STM Functions ====================

    @TruffleBoundary
    public static Object stmAtomic(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeFunction f = getFunction(args, "f");

        // Nested stmAtomic: run f against the existing transaction and let
        // the outer stmAtomic's retry loop govern commit. Creating an
        // independent Transaction here would (a) commit the inner's writes
        // prematurely, (b) leave the outer's ThreadLocal clobbered to null
        // when the inner's finally runs currentTransaction.remove().
        //
        // We check atomicNestingDepth (not currentTransaction) because the
        // perform OptimisticSTM scope also sets currentTransaction via
        // stmBeginTransaction — stmAtomic must still manage its own
        // transaction and retry loop in that case.
        int atomicDepth = atomicNestingDepth.get();
        if (atomicDepth > 0) {
            return callNode.call(f.getCallTarget(), f.getClosureValues());
        }

        int maxRetries = 1000;
        for (int i = 0; i < maxRetries; i++) {
            Transaction txn = new Transaction();
            currentTransaction.set(txn);
            boolean blocked = false;
            try {
                Object result;
                atomicNestingDepth.set(atomicDepth + 1);
                try {
                    result = callNode.call(f.getCallTarget(), f.getClosureValues());
                } catch (StmRetryException retry) {
                    // User-initiated retry from STM.retry / STM.check: park on
                    // read set until something changes, then re-run the
                    // transaction body with a fresh Transaction.
                    //
                    // NOTE: In the effect-handler dispatch architecture, the
                    // StmRetryException thrown by __native_stm_retry inside the
                    // OptimisticSTM handler does NOT actually unwind back here
                    // — the op-call is dispatched on a fiber, and the exception
                    // unwinds the fiber driver, not stmAtomic's call frame.
                    // The catch is kept for the SeqSTM path and any future
                    // direct-call paths; the real retry mechanism for op-call
                    // STM.retry requires stdlib-level loop (see task #176).
                    blocked = true;
                    blockUntilReadSetChanged(txn);
                    continue;
                } finally {
                    atomicNestingDepth.set(atomicDepth);
                }
                Transaction current = currentTransaction.get();
                if (current != null && current.commit()) {
                    return result;
                }
                // Commit conflict: fall through to retry without sleep.
            } finally {
                currentTransaction.remove();
            }
            // Commit-conflict retry path: exponential-ish backoff.
            if (!blocked) {
                try {
                    Thread.sleep(Math.min(i, 10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("STM transaction cancelled during retry backoff");
                }
            }
        }
        throw new MezzeGuestException("STM: transaction failed after max retries (persistent contention)");
    }

    /**
     * Block the current thread until any TVar in the transaction's read set
     * advances past its read version. Registers as a waiter on each read
     * TVar; existing {@code forceWrite()} wakes all waiters via
     * {@code LockSupport.unpark()}.
     *
     * Pre-checks for the change before parking — if a write landed between
     * the user's read and our park, we don't park at all.
     */
    private static void blockUntilReadSetChanged(Transaction txn) {
        if (txn.readSet.isEmpty()) {
            throw new MezzeGuestException("STM.retry: called with an empty read set — retry would deadlock (read at least one TVar before retrying)");
        }

        Thread self = Thread.currentThread();
        // Register as waiter on every read TVar.
        for (Transaction.ReadEntry entry : txn.readSet.values()) {
            entry.tvar().addWaiter(self);
        }

        try {
            while (true) {
                // Check if any tvar version has advanced past its read entry.
                for (Transaction.ReadEntry entry : txn.readSet.values()) {
                    if (entry.tvar().readVersioned().version() != entry.version()) {
                        return;
                    }
                }
                LockSupport.park(txn);
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("STM.retry cancelled");
                }
            }
        } finally {
            for (Transaction.ReadEntry entry : txn.readSet.values()) {
                entry.tvar().removeWaiter(self);
            }
        }
    }

    @TruffleBoundary
    public static MezzeRecord stmModify(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeTVar tvar = getTVar(args, "var");
        MezzeFunction f = getFunction(args, "f");
        Transaction txn = getOrCreateTransaction();
        Object current = txn.read(tvar);
        // STM modify expects {val: 'a} -> 'a, so wrap current in {val = current}
        MezzeRecord argRecord = MezzeRecord.fromFields(VAL_FIELD, new Object[]{current});
        Object[] closuresWithArg = new Object[f.getClosureValues().length + 1];
        System.arraycopy(f.getClosureValues(), 0, closuresWithArg, 0, f.getClosureValues().length);
        closuresWithArg[f.getClosureValues().length] = argRecord;
        Object newVal = callNode.call(f.getCallTarget(), closuresWithArg);
        txn.write(tvar, newVal);
        return MezzeRecord.empty();  // Return {} as per signature
    }

    @TruffleBoundary
    public static MezzeRecord stmSeqModify(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeTVar tvar = getTVar(args, "var");
        MezzeFunction f = getFunction(args, "f");
        Object current = tvar.read();
        // STM modify expects {val: 'a} -> 'a, so wrap current in {val = current}
        MezzeRecord argRecord = MezzeRecord.fromFields(VAL_FIELD, new Object[]{current});
        Object[] closuresWithArg = new Object[f.getClosureValues().length + 1];
        System.arraycopy(f.getClosureValues(), 0, closuresWithArg, 0, f.getClosureValues().length);
        closuresWithArg[f.getClosureValues().length] = argRecord;
        Object newVal = callNode.call(f.getCallTarget(), closuresWithArg);
        long newVersion = globalVersion.incrementAndGet();
        tvar.forceWrite(newVersion, newVal);
        return MezzeRecord.empty();  // Return {} as per signature
    }

    @TruffleBoundary
    public static Object stmSeqSwap(MezzeRecord args) {
        MezzeTVar tvar = getTVar(args, "var");
        Object newVal = getField(args, "val");
        Object old = tvar.read();
        long newVersion = globalVersion.incrementAndGet();
        tvar.forceWrite(newVersion, newVal);
        return old;
    }

    @TruffleBoundary
    public static MezzeSeq stmReadAll(MezzeRecord args) {
        MezzeList vars = getList(args, "vars");
        Object[] store = vars.toArray();
        if (store == null || store.length == 0) return MezzeSeq.fromArray(new Object[0]);
        Object[] results = new Object[store.length];

        // Check if we're in a transaction (OptimisticSTM) or not (SequentialSTM)
        Transaction txn = currentTransaction.get();

        for (int i = 0; i < store.length; i++) {
            MezzeTVar tvar = unwrapTVar(store[i]);
            if (txn != null) {
                // Transactional read - use transaction's read set
                results[i] = txn.read(tvar);
            } else {
                // Non-transactional read - read directly from TVar
                results[i] = tvar.read();
            }
        }
        return MezzeSeq.fromArray(results);
    }

    @TruffleBoundary
    public static MezzeRecord stmWriteAll(MezzeRecord args) {
        // Get pairs list - each pair is a record {var: TVar, val: Value}
        MezzeList pairs = getList(args, "pairs");
        Object[] pairStore = pairs.toArray();
        if (pairStore == null) return MezzeRecord.empty();

        // Check if we have an active transaction (OptimisticSTM) or not (SequentialSTM)
        Transaction txn = currentTransaction.get();

        if (txn != null) {
            // Transactional write - buffer in transaction
            for (Object pair : pairStore) {
                if (pair instanceof MezzeRecord pairRec) {
                    Object varField = getField(pairRec, "var");
                    Object valField = getField(pairRec, "val");
                    MezzeTVar tvar = unwrapTVar(varField);
                    txn.write(tvar, valField);
                }
            }
        } else {
            // Sequential/non-transactional write - write directly
            long newVersion = globalVersion.incrementAndGet();
            for (Object pair : pairStore) {
                if (pair instanceof MezzeRecord pairRec) {
                    Object varField = getField(pairRec, "var");
                    Object valField = getField(pairRec, "val");
                    MezzeTVar tvar = unwrapTVar(varField);
                    tvar.forceWrite(newVersion, valField);
                }
            }
        }
        return MezzeRecord.empty();
    }

    private static MezzeTVar unwrapTVar(Object value) {
        if (value instanceof MezzeTVar tvar) return tvar;
        if (value instanceof MezzeRecord rec) {
            for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
                Object inner = rec.getField(i);
                if (inner instanceof MezzeTVar tvar) return tvar;
            }
        }
        throw new NativeTypeMismatchException("Expected TVar");
    }

    // ==================== Additional Atom Functions ====================

    @TruffleBoundary
    public static boolean atomCas(MezzeRecord args) {
        MezzeAtom atom = getAtom(args, "atom");
        Object expected = getField(args, "expected");
        Object newVal = getField(args, "new");
        return atom.compareAndSet(expected, newVal);
    }

    @TruffleBoundary
    public static MezzeRecord atomAddWatch(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeAtom atom = getAtom(args, "atom");
        String key = getString(args, "key");
        MezzeFunction callback = getFunction(args, "f");  // Field is "f", not "callback"
        atom.addWatch(key, callback, callNode);
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeRecord atomRemoveWatch(MezzeRecord args) {
        MezzeAtom atom = getAtom(args, "atom");
        String key = getString(args, "key");
        atom.removeWatch(key);
        return MezzeRecord.empty();
    }

    // ==================== Additional Mutex Functions ====================

    @TruffleBoundary
    public static Object mutexWithLock(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeMutex mutex = getMutex(args, "mutex");
        MezzeFunction f = getFunction(args, "f");
        // Interruptibly so Async.cancel actually reaches tasks blocked
        // on with_lock.
        try {
            mutex.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Mutex with_lock cancelled");
        }
        try {
            return callNode.call(f.getCallTarget(), f.getClosureValues());
        } finally {
            mutex.unlock();
        }
    }

    @TruffleBoundary
    public static boolean mutexIsHeldByCurrentThread(MezzeRecord args) {
        MezzeMutex mutex = getMutex(args, "mutex");
        return mutex.lock.isHeldByCurrentThread();
    }

    // ==================== Additional Semaphore Functions ====================

    @TruffleBoundary
    public static Object semaphoreWithPermit(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeSemaphore sem = getSemaphore(args, "sem");
        MezzeFunction f = getFunction(args, "f");
        try {
            sem.acquire();
            try {
                return callNode.call(f.getCallTarget(), f.getClosureValues());
            } finally {
                sem.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Semaphore acquire cancelled");
        }
    }

    @TruffleBoundary
    public static MezzeRecord semaphoreAcquireN(MezzeRecord args) {
        MezzeSemaphore sem = getSemaphore(args, "sem");
        long permits = getLong(args, "permits");
        try {
            for (int i = 0; i < permits; i++) sem.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Semaphore acquireN cancelled");
        }
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeRecord semaphoreReleaseN(MezzeRecord args) {
        MezzeSemaphore sem = getSemaphore(args, "sem");
        long permits = getLong(args, "permits");
        for (int i = 0; i < permits; i++) sem.release();
        return MezzeRecord.empty();
    }

    // ==================== Additional Latch Functions ====================

    @TruffleBoundary
    public static boolean latchAwaitTimeout(MezzeRecord args) {
        MezzeLatch latch = getLatch(args, "latch");
        long ms = getLong(args, "ms");
        try {
            return latch.latch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== Additional WaitGroup Functions ====================

    @TruffleBoundary
    public static long waitgroupGetCount(MezzeRecord args) {
        MezzeWaitGroup wg = getWaitGroup(args, "wg");
        return wg.getCount();
    }

    @TruffleBoundary
    public static boolean waitgroupWaitTimeout(MezzeRecord args) {
        MezzeWaitGroup wg = getWaitGroup(args, "wg");
        long ms = getLong(args, "ms");
        // If count is already 0, return immediately without waiting
        if (wg.getCount() == 0) {
            return true;
        }
        try {
            return wg.done.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== Additional CancellationToken Functions ====================

    @TruffleBoundary
    public static MezzeCancellationToken cancellationTokenCreateChild(MezzeRecord args) {
        MezzeCancellationToken parent = getCancellationToken(args, "token");
        return new MezzeCancellationToken(parent);
    }

    @TruffleBoundary
    public static Object cancellationTokenGetReason(MezzeRecord args) {
        MezzeCancellationToken token = getCancellationToken(args, "token");
        String reason = token.getReason();
        if (reason != null) {
            return createSome(reason);
        }
        return createNone();
    }

    // ==================== Additional Agent Functions ====================

    @TruffleBoundary
    public static Object agentAwait(MezzeRecord args) {
        MezzeAgent agent = getAgent(args, "agent");
        long timeoutMs = 0L;
        Object timeoutObj = args.getField("timeout_ms");
        if (timeoutObj instanceof Long l) {
            timeoutMs = l;
        } else if (timeoutObj instanceof Integer i) {
            timeoutMs = i.longValue();
        }
        boolean completed = agent.await(timeoutMs);
        return completed;
    }

    @TruffleBoundary
    public static Object agentError(MezzeRecord args) {
        MezzeAgent agent = getAgent(args, "agent");
        String error = agent.getError();
        if (error != null) {
            return createSome(error);
        }
        return createNone();
    }

    // ==================== SortedMap Operations ====================

    @TruffleBoundary
    public static Object sortedMapMinKey(MezzeRecord args) {
        Object map = getField(args, "map");
        // Return None - needs actual sorted map implementation
        return createNone();
    }

    @TruffleBoundary
    public static Object sortedMapMaxKey(MezzeRecord args) {
        Object map = getField(args, "map");
        return createNone();
    }

    // ==================== Additional STM Operations ====================

    @TruffleBoundary
    public static Object stmSwap(MezzeRecord args) {
        MezzeTVar tvar = getTVar(args, "var");
        Object newVal = getField(args, "val");
        Transaction txn = getOrCreateTransaction();
        Object old = txn.read(tvar);
        txn.write(tvar, newVal);
        return old;
    }

    /**
     * Control-flow exception thrown by {@link #stmRetry} and {@link #stmCheck}
     * (when cond is false) to abort the current STM transaction and block
     * until at least one TVar in the read set has changed.
     *
     * Caught by {@link #stmAtomic} which then parks via the TVar waiter
     * mechanism — same approach as the channel/mutex park-aware ops.
     *
     * Extends ControlFlowException — Truffle's standard "do not pollute the
     * profile with this exception" marker. Singleton-reusable since CFE
     * doesn't preserve stack traces.
     */
    public static final class StmRetryException
        extends com.oracle.truffle.api.nodes.ControlFlowException {
        public static final StmRetryException INSTANCE = new StmRetryException();
        private StmRetryException() { super(); }
    }

    /**
     * Control-flow sentinel thrown by {@link #stmSeqRetry} and caught BY TYPE
     * in {@link #stmSeqOrElse} to route to the fallback branch. Stays a
     * {@code RuntimeException} (not an AbstractTruffleException) so no other
     * catch behaviour changes — this exception is only ever caught, by type,
     * inside stmSeqOrElse. Cheap no-stack construction: it is pure control flow.
     */
    public static final class StmRetrySignal extends RuntimeException {
        public static final StmRetrySignal INSTANCE = new StmRetrySignal();
        private StmRetrySignal() {
            super("SequentialSTM.retry: no scheduler available to block on",
                  null, false, false);
        }
    }

    /** {@code STM.check { cond: Bool }} — retry if cond is false. */
    @TruffleBoundary
    public static MezzeRecord stmCheck(MezzeRecord args) {
        Object cond = getField(args, "cond");
        if (cond instanceof Boolean b && b) return MezzeRecord.empty();
        // Same path as stmRetry — snapshot read set + flag + abort.
        Transaction txn = currentTransaction.get();
        snapshotReadSetForRetry(txn);
        stmRetryPending.set(Boolean.TRUE);
        throw new com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException(MezzeRecord.empty());
    }

    @TruffleBoundary
    public static MezzeRecord stmSeqCheck(MezzeRecord args) {
        Object cond = getField(args, "cond");
        if (cond instanceof Boolean b && b) return MezzeRecord.empty();
        // Sequential STM has no scheduler to park on — fail loudly.
        // Intentionally fatal: SequentialSTM has no scheduler to block on, so
        // a failed check cannot park-and-retry. Guest-visible fatal.
        throw new MezzeGuestException("SequentialSTM.check: condition was False and there is no scheduler to block on");
    }

    /**
     * {@code STM.retry {}} — signal the stdlib atomically loop that this
     * transaction needs to park-and-retry, then abort the surrounding
     * perform. The stdlib reads {@link #stmCheckAndClearRetryFlag} after
     * the perform returns; on True it calls {@link #stmParkForRetry} which
     * blocks on the saved read set.
     *
     * Sets {@code stmRetryPending = true} and snapshots the current txn's
     * read set; then throws {@link com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException}
     * with a dummy value (the stdlib ignores the result when the flag is
     * set).
     */
    @TruffleBoundary
    public static Object stmRetry(MezzeRecord args) {
        Transaction txn = currentTransaction.get();
        snapshotReadSetForRetry(txn);
        stmRetryPending.set(Boolean.TRUE);
        throw new com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException(MezzeRecord.empty());
    }

    private static void snapshotReadSetForRetry(Transaction txn) {
        if (txn == null) {
            // Defensive — STM.retry called outside a transaction. Park
            // would deadlock, so we still set the flag but with an empty
            // snapshot. stmParkForRetry surfaces a clear error.
            stmRetryReadSet.set(java.util.Collections.emptyList());
            return;
        }
        stmRetryReadSet.set(new java.util.ArrayList<>(txn.readSet.values()));
    }

    /** Read & clear the retry flag — called by stdlib atomically. */
    @TruffleBoundary
    public static boolean stmCheckAndClearRetryFlag(MezzeRecord args) {
        boolean v = stmRetryPending.get();
        if (v) stmRetryPending.set(Boolean.FALSE);
        return v;
    }

    /**
     * Park the current thread until any TVar in the snapshotted read set
     * advances past its read version. Uses LockSupport.park — works for
     * LoomAsync (each task on its own virtual thread, parked thread
     * doesn't block others). Under CoopAsync use stmReadSetChanged as an
     * Async.park predicate instead.
     */
    @TruffleBoundary
    public static MezzeRecord stmParkForRetry(MezzeRecord args) {
        List<Transaction.ReadEntry> readSet = stmRetryReadSet.get();
        if (readSet == null || readSet.isEmpty()) {
            stmRetryReadSet.remove();
            throw new MezzeGuestException("STM.retry: called with an empty read set — retry would deadlock (read at least one TVar before retrying)");
        }
        Thread self = Thread.currentThread();
        for (Transaction.ReadEntry entry : readSet) {
            entry.tvar().addWaiter(self);
        }
        try {
            while (true) {
                for (Transaction.ReadEntry entry : readSet) {
                    if (entry.tvar().readVersioned().version() != entry.version()) {
                        return MezzeRecord.empty();
                    }
                }
                LockSupport.park(readSet);
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("STM.retry cancelled");
                }
            }
        } finally {
            for (Transaction.ReadEntry entry : readSet) {
                entry.tvar().removeWaiter(self);
            }
            stmRetryReadSet.remove();
        }
    }

    /**
     * Predicate for Async.park — returns True when any TVar in the
     * snapshotted read set has been updated since the transaction's
     * read. Used by stdlib atomically under CoopAsync (where blocking
     * the carrier thread would deadlock).
     *
     * Clears the snapshot once it observes a change so the next retry
     * iteration starts fresh.
     */
    @TruffleBoundary
    public static boolean stmReadSetChanged(MezzeRecord args) {
        List<Transaction.ReadEntry> readSet = stmRetryReadSet.get();
        if (readSet == null || readSet.isEmpty()) return true;
        for (Transaction.ReadEntry entry : readSet) {
            if (entry.tvar().readVersioned().version() != entry.version()) {
                stmRetryReadSet.remove();
                return true;
            }
        }
        return false;
    }

    /** SequentialSTM.retry — no scheduler to park on, always fails. */
    @TruffleBoundary
    public static Object stmSeqRetry(MezzeRecord args) {
        // NOT a fatal error: this is a control-flow SENTINEL. stmSeqOrElse
        // (below) catches StmRetrySignal BY TYPE and routes to its fallback
        // branch. It stays a RuntimeException (not an AbstractTruffleException)
        // so no other catch behaviour changes.
        throw StmRetrySignal.INSTANCE;
    }

    /**
     * {@code STM.or_else { primary, fallback }} — try primary; if it retries,
     * roll back the txn's reads/writes added by primary and try fallback.
     * If fallback also retries, the whole or_else block retries (caller-level
     * StmRetryException propagates).
     */
    @TruffleBoundary
    public static Object stmOrElse(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeFunction primary = getFunction(args, "primary");
        MezzeFunction fallback = getFunction(args, "fallback");
        Transaction txn = getOrCreateTransaction();

        // Snapshot txn state so we can roll back primary's effects on retry.
        var savedReadSet = new HashMap<>(txn.readSet);
        var savedWriteSet = new HashMap<>(txn.writeSet);
        var savedEnsureSet = new HashSet<>(txn.ensureSet);
        long savedBloom = txn.readBloom;

        try {
            return callNode.call(primary.getCallTarget(), primary.getClosureValues());
        } catch (StmRetryException ignored) {
            // Roll back primary's mutations, then try fallback.
            txn.readSet.clear();
            txn.readSet.putAll(savedReadSet);
            txn.writeSet.clear();
            txn.writeSet.putAll(savedWriteSet);
            txn.ensureSet.clear();
            txn.ensureSet.addAll(savedEnsureSet);
            txn.readBloom = savedBloom;
            return callNode.call(fallback.getCallTarget(), fallback.getClosureValues());
        }
    }

    /** SequentialSTM.or_else — try primary; if it throws StmRetryException or the
     *  StmRetrySignal sentinel, try fallback. */
    @TruffleBoundary
    public static Object stmSeqOrElse(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeFunction primary = getFunction(args, "primary");
        MezzeFunction fallback = getFunction(args, "fallback");
        try {
            return callNode.call(primary.getCallTarget(), primary.getClosureValues());
        } catch (StmRetryException | StmRetrySignal ignored) {
            return callNode.call(fallback.getCallTarget(), fallback.getClosureValues());
        }
    }

    @TruffleBoundary
    public static MezzeRecord stmModifyCfg(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return stmModify(args, callNode);
    }

    // ==================== Additional Atom Operations ====================

    private static final String[] CURRENT_ARG_FIELDS = new String[]{"current", "arg"};

    @TruffleBoundary
    public static Object atomSwapWith(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeAtom atom = getAtom(args, "atom");
        assertNoValidator(atom, "swap_with");
        MezzeFunction f = getFunction(args, "f");
        Object arg = getField(args, "arg");
        Object oldVal;
        Object newVal;
        do {
            oldVal = atom.deref();
            // Function expects {current: 'a, arg: 'b} as a single record argument
            // Don't use appendToClosures since that would wrap it in another record
            MezzeRecord funcArg = MezzeRecord.fromFields(CURRENT_ARG_FIELDS, new Object[]{oldVal, arg});
            Object[] closures = f.getClosureValues();
            Object[] callArgs = new Object[closures.length + 1];
            System.arraycopy(closures, 0, callArgs, 0, closures.length);
            callArgs[closures.length] = funcArg;
            newVal = callNode.call(f.getCallTarget(), callArgs);
        } while (!atom.compareAndSet(oldVal, newVal));
        return newVal;
    }

    private static final String[] OLD_NEW_FIELDS = new String[]{"old", "new"};

    @TruffleBoundary
    public static MezzeRecord atomGetAndSwap(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // get_and_swap takes a function {current} -> new and returns {old, new}
        MezzeAtom atom = getAtom(args, "atom");
        assertNoValidator(atom, "get_and_swap");
        MezzeFunction f = getFunction(args, "f");
        Object oldVal;
        Object newVal;
        do {
            oldVal = atom.deref();
            // The function takes {current} argument
            MezzeRecord argRecord = MezzeRecord.fromFields(CURRENT_FIELD, new Object[]{oldVal});
            Object[] closuresWithArg = new Object[f.getClosureValues().length + 1];
            System.arraycopy(f.getClosureValues(), 0, closuresWithArg, 0, f.getClosureValues().length);
            closuresWithArg[f.getClosureValues().length] = argRecord;
            newVal = callNode.call(f.getCallTarget(), closuresWithArg);
        } while (!atom.compareAndSet(oldVal, newVal));
        return MezzeRecord.fromFields(OLD_NEW_FIELDS, new Object[]{oldVal, newVal});
    }

    // ==================== Additional Semaphore Operations ====================

    @TruffleBoundary
    public static boolean semaphoreTryAcquireN(MezzeRecord args) {
        MezzeSemaphore sem = getSemaphore(args, "sem");
        long permits = getLong(args, "permits");
        for (int i = 0; i < permits; i++) {
            if (!sem.tryAcquire()) {
                // Release what we acquired
                for (int j = 0; j < i; j++) sem.release();
                return false;
            }
        }
        return true;
    }

    // ==================== Additional Channel Operations ====================

    @TruffleBoundary
    public static MezzeChannel channelMakeBuffered(MezzeRecord args) {
        long capacity = getLongOrDefault(args, "capacity", 16);
        return new MezzeChannel((int) capacity);
    }

    // ==================== Additional Agent Operations ====================

    @TruffleBoundary
    public static MezzeRecord agentRestart(MezzeRecord args) {
        MezzeAgent agent = getAgent(args, "agent");
        Object value = getField(args, "val");
        agent.clearError(value);
        return MezzeRecord.empty();
    }

    // ==================== Helpers ====================

    private static Object createNone() {
        return MezzeVariant.none();
    }

    private static Object createSome(Object value) {
        return MezzeVariant.some(value);
    }

    // Pre-allocated field name arrays to avoid allocation in hot paths
    // X_FIELD is defined above in the Parallel Operations section
    private static final String[] X_FIELD = new String[]{"x"};
    private static final String[] VAL_FIELD = new String[]{"val"};
    private static final String[] ACC_X_FIELDS = new String[]{"acc", "x"};

    /**
     * Wrap arguments in a record and append to closures.
     * Callbacks expect record arguments like {x} or {acc, x}.
     */
    private static Object[] appendToClosures(Object[] closures, Object... args) {
        Object[] result = new Object[closures.length + 1];
        System.arraycopy(closures, 0, result, 0, closures.length);
        // Wrap args in a record based on count
        if (args.length == 1) {
            result[closures.length] = MezzeRecord.fromFields(X_FIELD, new Object[]{args[0]});
        } else if (args.length == 2) {
            result[closures.length] = MezzeRecord.fromFields(ACC_X_FIELDS, new Object[]{args[0], args[1]});
        } else {
            // Fallback for other cases - create record with numbered fields
            String[] fields = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                fields[i] = "arg" + i;
            }
            result[closures.length] = MezzeRecord.fromFields(fields, args);
        }
        return result;
    }

    private static MezzeFunction getFunction(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeFunction f) return f;
        // Also check if the whole record IS a function (common pattern for single-field {f} -> f)
        if (args.getShape().getFieldCount() == 1) {
            Object firstField = args.getField(0);
            if (firstField instanceof MezzeFunction f) return f;
        }
        // Debug: dump available fields
        StringBuilder sb = new StringBuilder("Available fields: [");
        for (int i = 0; i < args.getShape().getFieldCount(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(args.getShape().getFieldName(i));
            sb.append("=");
            Object fv = args.getField(i);
            sb.append(fv == null ? "null" : fv.getClass().getSimpleName());
        }
        sb.append("]");
        throw new NativeTypeMismatchException("Expected Function for field '" + fieldName + "', " + sb);
    }

    private static MezzeList getList(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeList list) return list;
        // Handle MezzeSeq (persistent vector) - convert to MezzeList
        if (value instanceof MezzeSeq seq) {
            return MezzeList.of(seq.toArray());
        }
        // Handle array literals (Object[]) - convert to MezzeList
        if (value instanceof Object[] arr) {
            return MezzeList.of(arr);
        }
        throw new NativeTypeMismatchException("Expected List for field '" + fieldName + "', got: " +
            (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private static Object getField(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) return null;
        return r.getField(idx);
    }

    private static long getLong(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        throw new NativeTypeMismatchException("Expected Long for field '" + fieldName + "', got: " +
            (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private static String getString(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof String s) return s;
        throw new NativeTypeMismatchException("Expected String for field '" + fieldName + "', got: " +
            (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private static long getLongOrDefault(MezzeRecord args, String fieldName, long defaultValue) {
        Object value = getField(args, fieldName);
        if (value == null) return defaultValue;
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        return defaultValue;
    }

    private static Object[] getArray(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof Object[] arr) return arr;
        if (value instanceof MezzeSeq seq) return seq.toArray();
        if (value instanceof MezzeList list) {
            Object[] result = new Object[list.size()];
            for (int i = 0; i < list.size(); i++) {
                result[i] = list.get(i);
            }
            return result;
        }
        throw new NativeTypeMismatchException("Expected Array for field '" + fieldName + "'");
    }

}
