package com.mezze.interpreter.v2.nodes.builtin.concurrency;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.*;
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
            Thread waiter;
            while ((waiter = waiters.poll()) != null) {
                LockSupport.unpark(waiter);
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
    private static final AtomicLong globalVersion = new AtomicLong(0);
    private static final ReentrantLock commitLock = new ReentrantLock();

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
        throw new RuntimeException("Expected TVar for field '" + fieldName + "', got: " +
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
                        throw new com.mezze.interpreter.v2.nodes.effect.RetryingScopeException(
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
                        throw new com.mezze.interpreter.v2.nodes.effect.RetryingScopeException(
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

        public void setValidator(MezzeFunction newValidator) {
            this.validator = newValidator;
        }

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
                throw new RuntimeException("Atom validator rejected value");
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
        Object value = getField(args, "value");
        return new MezzeAtom(value);
    }

    @TruffleBoundary
    public static MezzeAtom atomNewValidated(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object value = getField(args, "value");
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
            throw new RuntimeException("Atom validator rejected initial value");
        }

        return atom;
    }

    @TruffleBoundary
    public static Object atomDeref(MezzeRecord args) {
        MezzeAtom atom = getAtom(args, "atom");
        return atom.deref();
    }

    @TruffleBoundary
    public static Object atomReset(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeAtom atom = getAtom(args, "atom");
        Object value = getField(args, "value");
        atom.reset(value, callNode);
        return value;  // Return the new value
    }

    @TruffleBoundary
    public static Object atomSwap(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // Simple value replacement version (no function callback)
        MezzeAtom atom = getAtom(args, "atom");
        Object val = getField(args, "value");
        return atom.swap(val, callNode);
    }

    private static final String[] CURRENT_FIELD = new String[]{"current"};

    @TruffleBoundary
    public static Object atomSwapFunc(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // swap takes a function {current} -> new value
        MezzeAtom atom = getAtom(args, "atom");
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
            // Validate before CAS - throws if invalid
            atom.validate(newVal, callNode);
        } while (!atom.compareAndSet(oldVal, newVal));

        // Notify watches after successful swap
        atom.notifyWatches(oldVal, newVal, callNode);

        return newVal;
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
        throw new RuntimeException("Expected Atom for field '" + fieldName + "'");
    }

    // ==================== Mutex ====================

    public static class MezzeMutex {
        private final ReentrantLock lock = new ReentrantLock();
        public void lock() { lock.lock(); }
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
        mutex.lock();
        return MezzeRecord.empty();
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
        throw new RuntimeException("Expected Mutex for field '" + fieldName + "'");
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
        try { sem.acquire(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return MezzeRecord.empty();
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
        throw new RuntimeException("Expected Semaphore for field '" + fieldName + "'");
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
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return MezzeRecord.empty();
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
        throw new RuntimeException("Expected Latch for field '" + fieldName + "'");
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
        try { wg.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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
        throw new RuntimeException("Expected WaitGroup for field '" + fieldName + "'");
    }

    // ==================== Channel ====================

    public static class MezzeChannel {
        final BlockingQueue<Object> queue;
        private volatile boolean closed = false;

        public MezzeChannel(int capacity) {
            // capacity <= 0 means unbounded (LinkedBlockingQueue)
            // capacity > 0 means bounded (ArrayBlockingQueue)
            this.queue = capacity <= 0
                ? new java.util.concurrent.LinkedBlockingQueue<>()
                : new ArrayBlockingQueue<>(capacity);
        }

        public void send(Object value) throws InterruptedException {
            if (closed) throw new RuntimeException("send on closed channel");
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
    public static MezzeRecord channelSend(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        Object val = getField(args, "value");
        try { ch.send(val); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static Object channelReceive(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        try { return ch.receive(); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MezzeUnit.INSTANCE;
        }
    }

    @TruffleBoundary
    public static MezzeRecord channelClose(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        ch.close();
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static boolean channelTrySend(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        Object val = getField(args, "value");
        if (ch.isClosed()) return false;
        return ch.queue.offer(val);
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
                    new String[]{"index", "value"},
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
        long deadline = System.currentTimeMillis() + ms;

        // Poll until timeout
        while (System.currentTimeMillis() < deadline) {
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
                        new String[]{"index", "value"},
                        new Object[]{(long) i, val}
                    ));
                }
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return createNone();
    }

    private static MezzeChannel getChannelFromRecord(MezzeRecord rec) {
        for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
            Object inner = rec.getField(i);
            if (inner instanceof MezzeChannel ch) return ch;
        }
        throw new RuntimeException("No channel found in record");
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
        throw new RuntimeException("Expected Channel for field '" + fieldName + "'");
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
        throw new RuntimeException("Expected CancellationToken for field '" + fieldName + "'");
    }

    // ==================== Agent ====================

    /**
     * Agent action - wraps a function and its Truffle environment.
     */
    private record AgentAction(MezzeFunction func, com.oracle.truffle.api.TruffleLanguage.Env env) {}

    /**
     * Clojure-style Agent for async state updates.
     *
     * Uses Truffle-aware threads (via env.newTruffleThreadBuilder) to ensure
     * CallTargets can be properly invoked from spawned threads.
     */
    public static class MezzeAgent {
        private static final String[] CURRENT_FIELD = new String[]{"current"};

        private final AtomicReference<Object> state;
        private final AtomicReference<String> error = new AtomicReference<>(null);
        private final AtomicBoolean processing = new AtomicBoolean(false);
        private final ConcurrentLinkedQueue<AgentAction> actionQueue = new ConcurrentLinkedQueue<>();

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
                throw new RuntimeException("Agent is in error state: " + error.get());
            }

            actionQueue.add(new AgentAction(func, env));
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

                            Object newState = func.getCallTarget().call(callArgs);
                            state.set(newState);
                        } catch (Throwable e) {
                            // Set error state
                            error.set(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
                            // Stop processing on error
                            break;
                        }
                    }
                } finally {
                    processing.set(false);

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
         */
        public boolean await(long timeoutMs) {
            long start = System.currentTimeMillis();
            while (!actionQueue.isEmpty() || processing.get()) {
                if (timeoutMs > 0 && System.currentTimeMillis() - start > timeoutMs) {
                    return false;
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }

        @Override public String toString() { return "<agent>"; }
    }

    @TruffleBoundary
    public static MezzeAgent agentNew(MezzeRecord args) {
        Object value = getField(args, "value");
        return new MezzeAgent(value);
    }

    @TruffleBoundary
    public static Object agentDeref(MezzeRecord args) {
        MezzeAgent agent = getAgent(args, "agent");
        return agent.deref();
    }

    @TruffleBoundary
    public static MezzeRecord agentSend(MezzeRecord args, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        MezzeAgent agent = getAgent(args, "agent");
        Object f = getField(args, "f");
        if (!(f instanceof MezzeFunction func)) {
            throw new RuntimeException("Agent send expects a function, got: " +
                (f != null ? f.getClass().getSimpleName() : "null"));
        }
        com.oracle.truffle.api.TruffleLanguage.Env env =
            com.mezze.interpreter.v2.runtime.MezzeContext.get(node).getEnv();
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
        throw new RuntimeException("Expected Agent for field '" + fieldName + "'");
    }

    // ==================== Additional STM Functions ====================

    @TruffleBoundary
    public static Object stmAtomic(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeFunction f = getFunction(args, "f");
        int maxRetries = 1000;
        for (int i = 0; i < maxRetries; i++) {
            currentTransaction.set(new Transaction());
            try {
                Object result = callNode.call(f.getCallTarget(), f.getClosureValues());
                Transaction txn = currentTransaction.get();
                if (txn != null && txn.commit()) {
                    return result;
                }
            } finally {
                currentTransaction.remove();
            }
            // Retry with backoff
            try { Thread.sleep(Math.min(i, 10)); } catch (InterruptedException e) { break; }
        }
        throw new RuntimeException("STM transaction failed after max retries");
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
        throw new RuntimeException("Expected TVar");
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
        mutex.lock();
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
            throw new RuntimeException("Semaphore acquire interrupted");
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

    // ==================== Additional Channel Functions ====================

    @TruffleBoundary
    public static MezzeRecord mockChannelSend(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        Object val = getField(args, "value");
        try { ch.send(val); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static Object mockChannelReceive(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        try { return ch.receive(); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MezzeUnit.INSTANCE;
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

    // ==================== Sequential Operations ====================

    @TruffleBoundary
    public static MezzeSeq sequentialMap(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return MezzeSeq.fromArray(new Object[0]);
        Object[] results = new Object[store.length];
        for (int i = 0; i < store.length; i++) {
            results[i] = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), store[i]));
        }
        return MezzeSeq.fromArray(results);
    }

    @TruffleBoundary
    public static MezzeSeq sequentialFilter(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return MezzeSeq.fromArray(new Object[0]);
        java.util.List<Object> results = new java.util.ArrayList<>();
        for (Object x : store) {
            Object result = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), x));
            if (result instanceof Boolean b && b) {
                results.add(x);
            }
        }
        return MezzeSeq.fromArray(results.toArray());
    }

    @TruffleBoundary
    public static Object sequentialFold(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // Sequential map-reduce style fold: fold {xs, zero, map_fn, combine}
        MezzeList xs = getList(args, "xs");
        Object zero = getField(args, "zero");
        MezzeFunction mapFn = getFunction(args, "map_fn");
        MezzeFunction combine = getFunction(args, "combine");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return zero;

        Object acc = zero;
        for (Object x : store) {
            // Apply map_fn to element
            Object mapped = callNode.call(mapFn.getCallTarget(), appendToClosures(mapFn.getClosureValues(), x));
            // Combine with accumulator - combine expects {left, right} as a direct argument
            MezzeRecord combineArgs = MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{acc, mapped});
            Object[] combineClosuresWithArg = new Object[combine.getClosureValues().length + 1];
            System.arraycopy(combine.getClosureValues(), 0, combineClosuresWithArg, 0, combine.getClosureValues().length);
            combineClosuresWithArg[combine.getClosureValues().length] = combineArgs;
            acc = callNode.call(combine.getCallTarget(), combineClosuresWithArg);
        }
        return acc;
    }

    @TruffleBoundary
    public static Object sequentialReduce(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return createNone();
        Object acc = store[0];
        for (int i = 1; i < store.length; i++) {
            // reduce expects {left, right} as argument
            MezzeRecord reduceArgs = MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{acc, store[i]});
            Object[] closuresWithArg = new Object[f.getClosureValues().length + 1];
            System.arraycopy(f.getClosureValues(), 0, closuresWithArg, 0, f.getClosureValues().length);
            closuresWithArg[f.getClosureValues().length] = reduceArgs;
            acc = callNode.call(f.getCallTarget(), closuresWithArg);
        }
        return createSome(acc);
    }

    @TruffleBoundary
    public static Object sequentialFind(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return createNone();
        for (Object x : store) {
            Object result = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), x));
            if (result instanceof Boolean b && b) {
                return createSome(x);
            }
        }
        return createNone();
    }

    @TruffleBoundary
    public static boolean sequentialAll(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return true;  // Vacuously true
        for (Object x : store) {
            Object result = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), x));
            if (!(result instanceof Boolean b && b)) return false;
        }
        return true;
    }

    @TruffleBoundary
    public static boolean sequentialAny(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return false;  // No matches possible
        for (Object x : store) {
            Object result = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), x));
            if (result instanceof Boolean b && b) return true;
        }
        return false;
    }

    @TruffleBoundary
    public static MezzeRecord sequentialPartition(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null) {
            return MezzeRecord.fromFields(
                new String[]{"satisfying", "notSatisfying"},
                new Object[]{MezzeSeq.fromArray(new Object[0]), MezzeSeq.fromArray(new Object[0])}
            );
        }
        java.util.List<Object> trueList = new java.util.ArrayList<>();
        java.util.List<Object> falseList = new java.util.ArrayList<>();
        for (Object x : store) {
            Object result = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), x));
            if (result instanceof Boolean b && b) {
                trueList.add(x);
            } else {
                falseList.add(x);
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"matching", "not_matching"},
            new Object[]{MezzeSeq.fromArray(trueList.toArray()), MezzeSeq.fromArray(falseList.toArray())}
        );
    }

    @TruffleBoundary
    public static MezzeRecord sequentialBoth(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeFunction left = getFunction(args, "left");
        MezzeFunction right = getFunction(args, "right");
        Object leftResult = callNode.call(left.getCallTarget(), left.getClosureValues());
        Object rightResult = callNode.call(right.getCallTarget(), right.getClosureValues());
        return MezzeRecord.fromFields(new String[]{"left", "right"}, new Object[]{leftResult, rightResult});
    }

    // ==================== Parallel Operations ====================

    // Threshold below which sequential execution is faster
    private static final int PARALLEL_THRESHOLD = 4;

    // Batch size for chunked parallel operations
    private static final int BATCH_SIZE = 64;

    // Pre-allocated field name arrays for record wrapping
    private static final String[] X_FIELD = new String[]{"x"};

    // Thread-local argument buffers to avoid allocation per iteration
    private static final ThreadLocal<Object[]> CALL_ARGS_BUFFER = ThreadLocal.withInitial(() -> new Object[16]);

    private static Object[] getCallArgsBuffer(int minSize) {
        Object[] buffer = CALL_ARGS_BUFFER.get();
        if (buffer.length < minSize) {
            buffer = new Object[Math.max(minSize, buffer.length * 2)];
            CALL_ARGS_BUFFER.set(buffer);
        }
        return buffer;
    }

    /**
     * Parallel map using batched ForkJoin execution.
     * Uses Truffle thread builder for proper context sharing.
     */
    @TruffleBoundary
    public static MezzeSeq parallelMap(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return MezzeSeq.fromArray(new Object[0]);

        // Sequential for small arrays
        if (store.length < PARALLEL_THRESHOLD) {
            return sequentialMap(store, f, callNode);
        }

        // Parallel batched execution
        Env env = MezzeContext.get(node).getEnv();
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;
        Object[] results = new Object[store.length];

        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, store.length / (numCores * 2));
        batchSize = Math.min(batchSize, BATCH_SIZE);
        int numBatches = (store.length + batchSize - 1) / batchSize;

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, store.length);

            threads[b] = env.newTruffleThreadBuilder(() -> {
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    // Wrap element in record - callbacks expect {x} -> ...
                    callArgs[closureLen] = MezzeRecord.fromFields(X_FIELD, new Object[]{store[i]});
                    results[i] = callNode.call(callTarget, callArgs);
                }
            }).virtual(true).build();
            threads[b].start();
        }

        try {
            for (Thread t : threads) {
                t.join();
            }
        } catch (InterruptedException e) {
            for (Thread t : threads) t.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel map interrupted", e);
        }

        return MezzeSeq.fromArray(results);
    }

    private static MezzeSeq sequentialMap(Object[] store, MezzeFunction f, IndirectCallNode callNode) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        Object[] results = new Object[store.length];
        Object[] callArgs = getCallArgsBuffer(closure.length + 1);
        System.arraycopy(closure, 0, callArgs, 0, closure.length);

        for (int i = 0; i < store.length; i++) {
            // Wrap element in record - callbacks expect {x} -> ...
            callArgs[closure.length] = MezzeRecord.fromFields(X_FIELD, new Object[]{store[i]});
            results[i] = callNode.call(callTarget, callArgs);
        }
        return MezzeSeq.fromArray(results);
    }

    /**
     * Parallel filter using batched ForkJoin execution.
     */
    @TruffleBoundary
    public static MezzeSeq parallelFilter(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return MezzeSeq.fromArray(new Object[0]);

        // Sequential for small arrays
        if (store.length < PARALLEL_THRESHOLD) {
            return sequentialFilter(store, f, callNode);
        }

        // Parallel batched execution
        Env env = MezzeContext.get(node).getEnv();
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;
        boolean[] keep = new boolean[store.length];

        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, store.length / (numCores * 2));
        batchSize = Math.min(batchSize, BATCH_SIZE);
        int numBatches = (store.length + batchSize - 1) / batchSize;

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int batch = 0; batch < numBatches; batch++) {
            final int batchStart = batch * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, store.length);

            threads[batch] = env.newTruffleThreadBuilder(() -> {
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    // Wrap element in record - callbacks expect {x} -> ...
                    callArgs[closureLen] = MezzeRecord.fromFields(X_FIELD, new Object[]{store[i]});
                    Object result = callNode.call(callTarget, callArgs);
                    keep[i] = result instanceof Boolean b && b;
                }
            }).virtual(true).build();
            threads[batch].start();
        }

        try {
            for (Thread t : threads) {
                t.join();
            }
        } catch (InterruptedException e) {
            for (Thread t : threads) t.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel filter interrupted", e);
        }

        // Collect results
        List<Object> results = new ArrayList<>();
        for (int i = 0; i < store.length; i++) {
            if (keep[i]) results.add(store[i]);
        }
        return MezzeSeq.fromArray(results.toArray());
    }

    private static MezzeSeq sequentialFilter(Object[] store, MezzeFunction f, IndirectCallNode callNode) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        Object[] callArgs = getCallArgsBuffer(closure.length + 1);
        System.arraycopy(closure, 0, callArgs, 0, closure.length);

        List<Object> results = new ArrayList<>();
        for (Object x : store) {
            // Wrap element in record - callbacks expect {x} -> ...
            callArgs[closure.length] = MezzeRecord.fromFields(X_FIELD, new Object[]{x});
            Object result = callNode.call(callTarget, callArgs);
            if (result instanceof Boolean b && b) {
                results.add(x);
            }
        }
        return MezzeSeq.fromArray(results.toArray());
    }

    private static final String[] LEFT_RIGHT_FIELDS = new String[]{"left", "right"};

    /**
     * Parallel fold using parallel map phase + sequential reduce.
     * The map phase is parallel, the combine phase is sequential.
     */
    @TruffleBoundary
    public static Object parallelFold(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeList xs = getList(args, "xs");
        Object zero = getField(args, "zero");
        MezzeFunction mapFn = getFunction(args, "map_fn");
        MezzeFunction combine = getFunction(args, "combine");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return zero;

        // Sequential for small arrays
        if (store.length < PARALLEL_THRESHOLD) {
            return sequentialFold(store, zero, mapFn, combine, callNode);
        }

        // Parallel map phase
        Env env = MezzeContext.get(node).getEnv();
        var mapTarget = mapFn.getCallTarget();
        Object[] mapClosure = mapFn.getClosureValues();
        final int mapClosureLen = mapClosure.length;
        Object[] mapped = new Object[store.length];

        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, store.length / (numCores * 2));
        batchSize = Math.min(batchSize, BATCH_SIZE);
        int numBatches = (store.length + batchSize - 1) / batchSize;

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, store.length);

            threads[b] = env.newTruffleThreadBuilder(() -> {
                Object[] callArgs = getCallArgsBuffer(mapClosureLen + 1);
                System.arraycopy(mapClosure, 0, callArgs, 0, mapClosureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    // Wrap element in record - callbacks expect {x} -> ...
                    callArgs[mapClosureLen] = MezzeRecord.fromFields(X_FIELD, new Object[]{store[i]});
                    mapped[i] = callNode.call(mapTarget, callArgs);
                }
            }).virtual(true).build();
            threads[b].start();
        }

        try {
            for (Thread t : threads) {
                t.join();
            }
        } catch (InterruptedException e) {
            for (Thread t : threads) t.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel fold map phase interrupted", e);
        }

        // Sequential reduce phase (combine is typically not parallelizable)
        Object acc = zero;
        for (Object m : mapped) {
            MezzeRecord combineArgs = MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{acc, m});
            Object[] combineClosures = new Object[combine.getClosureValues().length + 1];
            System.arraycopy(combine.getClosureValues(), 0, combineClosures, 0, combine.getClosureValues().length);
            combineClosures[combine.getClosureValues().length] = combineArgs;
            acc = callNode.call(combine.getCallTarget(), combineClosures);
        }
        return acc;
    }

    private static Object sequentialFold(Object[] store, Object zero, MezzeFunction mapFn,
                                          MezzeFunction combine, IndirectCallNode callNode) {
        var mapTarget = mapFn.getCallTarget();
        Object[] mapClosure = mapFn.getClosureValues();
        Object[] callArgs = getCallArgsBuffer(mapClosure.length + 1);
        System.arraycopy(mapClosure, 0, callArgs, 0, mapClosure.length);

        // Map phase
        Object[] mapped = new Object[store.length];
        for (int i = 0; i < store.length; i++) {
            // Wrap element in record - callbacks expect {x} -> ...
            callArgs[mapClosure.length] = MezzeRecord.fromFields(X_FIELD, new Object[]{store[i]});
            mapped[i] = callNode.call(mapTarget, callArgs);
        }

        // Reduce phase
        Object acc = zero;
        for (Object m : mapped) {
            MezzeRecord combineArgs = MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{acc, m});
            Object[] combineClosures = new Object[combine.getClosureValues().length + 1];
            System.arraycopy(combine.getClosureValues(), 0, combineClosures, 0, combine.getClosureValues().length);
            combineClosures[combine.getClosureValues().length] = combineArgs;
            acc = callNode.call(combine.getCallTarget(), combineClosures);
        }
        return acc;
    }

    /**
     * Parallel reduce - tree reduction for associative operations.
     */
    @TruffleBoundary
    public static Object parallelReduce(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return createNone();

        // For reduce, sequential is simpler since it's inherently serial for non-associative ops
        // TODO: Could use parallel tree reduction for known associative ops
        Object acc = store[0];
        for (int i = 1; i < store.length; i++) {
            MezzeRecord reduceArgs = MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{acc, store[i]});
            Object[] closuresWithArg = new Object[f.getClosureValues().length + 1];
            System.arraycopy(f.getClosureValues(), 0, closuresWithArg, 0, f.getClosureValues().length);
            closuresWithArg[f.getClosureValues().length] = reduceArgs;
            acc = callNode.call(f.getCallTarget(), closuresWithArg);
        }
        return createSome(acc);
    }

    /**
     * Parallel all - short-circuit on first false.
     */
    @TruffleBoundary
    public static boolean parallelAll(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return true;

        // Sequential for small arrays
        if (store.length < PARALLEL_THRESHOLD) {
            return sequentialAll(store, f, callNode);
        }

        // Parallel with short-circuit via AtomicBoolean
        Env env = MezzeContext.get(node).getEnv();
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;
        AtomicBoolean allTrue = new AtomicBoolean(true);

        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, store.length / (numCores * 2));
        batchSize = Math.min(batchSize, BATCH_SIZE);
        int numBatches = (store.length + batchSize - 1) / batchSize;

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, store.length);

            threads[b] = env.newTruffleThreadBuilder(() -> {
                if (!allTrue.get()) return; // Early exit if already found false

                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd && allTrue.get(); i++) {
                    // Wrap element in record - callbacks expect {x} -> ...
                    callArgs[closureLen] = MezzeRecord.fromFields(X_FIELD, new Object[]{store[i]});
                    Object result = callNode.call(callTarget, callArgs);
                    if (!(result instanceof Boolean bool && bool)) {
                        allTrue.set(false);
                        return;
                    }
                }
            }).virtual(true).build();
            threads[b].start();
        }

        try {
            for (Thread t : threads) {
                t.join();
            }
        } catch (InterruptedException e) {
            for (Thread t : threads) t.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel all interrupted", e);
        }

        return allTrue.get();
    }

    private static boolean sequentialAll(Object[] store, MezzeFunction f, IndirectCallNode callNode) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        Object[] callArgs = getCallArgsBuffer(closure.length + 1);
        System.arraycopy(closure, 0, callArgs, 0, closure.length);

        for (Object x : store) {
            // Wrap element in record - callbacks expect {x} -> ...
            callArgs[closure.length] = MezzeRecord.fromFields(X_FIELD, new Object[]{x});
            Object result = callNode.call(callTarget, callArgs);
            if (!(result instanceof Boolean b && b)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parallel any - short-circuit on first true.
     */
    @TruffleBoundary
    public static boolean parallelAny(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return false;

        // Sequential for small arrays
        if (store.length < PARALLEL_THRESHOLD) {
            return sequentialAny(store, f, callNode);
        }

        // Parallel with short-circuit via AtomicBoolean
        Env env = MezzeContext.get(node).getEnv();
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;
        AtomicBoolean foundTrue = new AtomicBoolean(false);

        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, store.length / (numCores * 2));
        batchSize = Math.min(batchSize, BATCH_SIZE);
        int numBatches = (store.length + batchSize - 1) / batchSize;

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, store.length);

            threads[b] = env.newTruffleThreadBuilder(() -> {
                if (foundTrue.get()) return; // Early exit if already found true

                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd && !foundTrue.get(); i++) {
                    // Wrap element in record - callbacks expect {x} -> ...
                    callArgs[closureLen] = MezzeRecord.fromFields(X_FIELD, new Object[]{store[i]});
                    Object result = callNode.call(callTarget, callArgs);
                    if (result instanceof Boolean bool && bool) {
                        foundTrue.set(true);
                        return;
                    }
                }
            }).virtual(true).build();
            threads[b].start();
        }

        try {
            for (Thread t : threads) {
                t.join();
            }
        } catch (InterruptedException e) {
            for (Thread t : threads) t.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel any interrupted", e);
        }

        return foundTrue.get();
    }

    private static boolean sequentialAny(Object[] store, MezzeFunction f, IndirectCallNode callNode) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        Object[] callArgs = getCallArgsBuffer(closure.length + 1);
        System.arraycopy(closure, 0, callArgs, 0, closure.length);

        for (Object x : store) {
            // Wrap element in record - callbacks expect {x} -> ...
            callArgs[closure.length] = MezzeRecord.fromFields(X_FIELD, new Object[]{x});
            Object result = callNode.call(callTarget, callArgs);
            if (result instanceof Boolean b && b) {
                return true;
            }
        }
        return false;
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

    @TruffleBoundary
    public static boolean stmCheck(MezzeRecord args) {
        Transaction txn = currentTransaction.get();
        return txn != null && txn.validate();
    }

    @TruffleBoundary
    public static boolean stmSeqCheck(MezzeRecord args) {
        return true; // Always succeeds in sequential mode
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

    @TruffleBoundary
    public static MezzeRecord atomSetValidator(MezzeRecord args) {
        MezzeAtom atom = getAtom(args, "atom");
        MezzeFunction validator = getFunction(args, "validator");
        atom.setValidator(validator);
        return MezzeRecord.empty();
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

    @TruffleBoundary
    public static boolean mockChannelTrySend(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        Object val = getField(args, "value");
        if (ch.isClosed()) return false;
        // MockChannel is non-blocking - always add to queue
        ch.queue.add(val);
        return true;
    }

    @TruffleBoundary
    public static Object mockChannelTryRecv(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        Object val = ch.queue.poll();
        if (val == null) return createNone();
        return createSome(val);
    }

    @TruffleBoundary
    public static Object mockChannelRecvAny(MezzeRecord args) {
        Object[] channels = getArray(args, "channels");
        // Check all channels - throw if all empty (mock doesn't block)
        for (int i = 0; i < channels.length; i++) {
            MezzeChannel ch = extractChannelFromObject(channels[i]);
            if (ch != null) {
                Object val = ch.queue.poll();
                if (val != null) {
                    return MezzeRecord.fromFields(
                        new String[]{"index", "value"},
                        new Object[]{(long) i, val}
                    );
                }
            }
        }
        throw new RuntimeException("MockChannel: All channels are empty (would block)");
    }

    @TruffleBoundary
    public static Object mockChannelRecvAnyTimeout(MezzeRecord args) {
        Object[] channels = getArray(args, "channels");
        long ms = getLongOrDefault(args, "ms", 0);
        // MockChannel doesn't actually timeout - just check if any have data
        for (int i = 0; i < channels.length; i++) {
            MezzeChannel ch = extractChannelFromObject(channels[i]);
            if (ch != null) {
                Object val = ch.queue.poll();
                if (val != null) {
                    return createSome(MezzeRecord.fromFields(
                        new String[]{"index", "value"},
                        new Object[]{(long) i, val}
                    ));
                }
            }
        }
        // Return None (simulates timeout when nothing available)
        return createNone();
    }

    private static MezzeChannel extractChannelFromObject(Object obj) {
        if (obj instanceof MezzeChannel ch) return ch;
        if (obj instanceof MezzeRecord rec) {
            for (int i = 0; i < rec.getShape().getFieldCount(); i++) {
                Object inner = rec.getField(i);
                if (inner instanceof MezzeChannel ch) return ch;
            }
        }
        return null;
    }

    @TruffleBoundary
    public static MezzeRecord mockChannelClose(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        ch.close();
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static boolean mockChannelIsClosed(MezzeRecord args) {
        MezzeChannel ch = getChannel(args, "ch");
        return ch.isClosed();
    }

    // ==================== Additional Agent Operations ====================

    @TruffleBoundary
    public static MezzeRecord agentRestart(MezzeRecord args) {
        MezzeAgent agent = getAgent(args, "agent");
        Object value = getField(args, "value");
        agent.clearError(value);
        return MezzeRecord.empty();
    }

    // ==================== Additional Sequential Operations ====================

    @TruffleBoundary
    public static MezzeRecord sequentialForEach(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return MezzeRecord.empty();
        for (Object x : store) {
            callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), x));
        }
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeSeq sequentialGroupBy(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) {
            return MezzeSeq.fromArray(new Object[0]);
        }
        java.util.Map<Object, java.util.List<Object>> groups = new java.util.LinkedHashMap<>();
        for (Object x : store) {
            Object key = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), x));
            groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(x);
        }
        // Convert to list of records with {key, values} fields
        java.util.List<Object> result = new java.util.ArrayList<>();
        for (var entry : groups.entrySet()) {
            result.add(MezzeRecord.fromFields(
                new String[]{"key", "values"},
                new Object[]{entry.getKey(), MezzeSeq.fromArray(entry.getValue().toArray())}
            ));
        }
        return MezzeSeq.fromArray(result.toArray());
    }

    @TruffleBoundary
    public static MezzeRecord sequentialBoth3(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeFunction a = getFunction(args, "a");
        MezzeFunction b = getFunction(args, "b");
        MezzeFunction c = getFunction(args, "c");
        Object ra = callNode.call(a.getCallTarget(), a.getClosureValues());
        Object rb = callNode.call(b.getCallTarget(), b.getClosureValues());
        Object rc = callNode.call(c.getCallTarget(), c.getClosureValues());
        return MezzeRecord.fromFields(new String[]{"a", "b", "c"}, new Object[]{ra, rb, rc});
    }

    // ==================== Additional Parallel Operations ====================

    @TruffleBoundary
    public static MezzeRecord parallelForEach(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        // Due to Truffle context sharing constraints, we run sequentially on the main thread
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return MezzeRecord.empty();
        for (int i = 0; i < store.length; i++) {
            callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), store[i]));
        }
        return MezzeRecord.empty();
    }

    /**
     * Parallel find - short-circuit on first match.
     */
    @TruffleBoundary
    public static Object parallelFind(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) return createNone();

        // Sequential for small arrays
        if (store.length < PARALLEL_THRESHOLD) {
            return sequentialFind(store, f, callNode);
        }

        // Parallel with short-circuit
        Env env = MezzeContext.get(node).getEnv();
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;
        AtomicReference<Object> found = new AtomicReference<>(null);

        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, store.length / (numCores * 2));
        batchSize = Math.min(batchSize, BATCH_SIZE);
        int numBatches = (store.length + batchSize - 1) / batchSize;

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, store.length);

            threads[b] = env.newTruffleThreadBuilder(() -> {
                if (found.get() != null) return;

                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd && found.get() == null; i++) {
                    // Wrap element in record - callbacks expect {x} -> ...
                    callArgs[closureLen] = MezzeRecord.fromFields(X_FIELD, new Object[]{store[i]});
                    Object result = callNode.call(callTarget, callArgs);
                    if (result instanceof Boolean bool && bool) {
                        found.compareAndSet(null, store[i]);
                        return;
                    }
                }
            }).virtual(true).build();
            threads[b].start();
        }

        try {
            for (Thread t : threads) {
                t.join();
            }
        } catch (InterruptedException e) {
            for (Thread t : threads) t.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel find interrupted", e);
        }

        Object result = found.get();
        return result != null ? createSome(result) : createNone();
    }

    private static Object sequentialFind(Object[] store, MezzeFunction f, IndirectCallNode callNode) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        Object[] callArgs = getCallArgsBuffer(closure.length + 1);
        System.arraycopy(closure, 0, callArgs, 0, closure.length);

        for (Object x : store) {
            // Wrap element in record - callbacks expect {x} -> ...
            callArgs[closure.length] = MezzeRecord.fromFields(X_FIELD, new Object[]{x});
            Object result = callNode.call(callTarget, callArgs);
            if (result instanceof Boolean b && b) {
                return createSome(x);
            }
        }
        return createNone();
    }

    private static final String[] MATCHING_NOT_MATCHING = new String[]{"matching", "not_matching"};

    /**
     * Parallel partition - split elements by predicate.
     */
    @TruffleBoundary
    public static MezzeRecord parallelPartition(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.toArray();
        if (store == null || store.length == 0) {
            return MezzeRecord.fromFields(MATCHING_NOT_MATCHING,
                new Object[]{MezzeSeq.fromArray(new Object[0]), MezzeSeq.fromArray(new Object[0])});
        }

        // Sequential for small arrays
        if (store.length < PARALLEL_THRESHOLD) {
            return sequentialPartition(store, f, callNode);
        }

        // Parallel evaluation of predicate
        Env env = MezzeContext.get(node).getEnv();
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        final int closureLen = closure.length;
        boolean[] matches = new boolean[store.length];

        int numCores = Runtime.getRuntime().availableProcessors();
        int batchSize = Math.max(1, store.length / (numCores * 2));
        batchSize = Math.min(batchSize, BATCH_SIZE);
        int numBatches = (store.length + batchSize - 1) / batchSize;

        Thread[] threads = new Thread[numBatches];
        final int finalBatchSize = batchSize;

        for (int b = 0; b < numBatches; b++) {
            final int batchStart = b * finalBatchSize;
            final int batchEnd = Math.min(batchStart + finalBatchSize, store.length);

            threads[b] = env.newTruffleThreadBuilder(() -> {
                Object[] callArgs = getCallArgsBuffer(closureLen + 1);
                System.arraycopy(closure, 0, callArgs, 0, closureLen);

                for (int i = batchStart; i < batchEnd; i++) {
                    // Wrap element in record - callbacks expect {x} -> ...
                    callArgs[closureLen] = MezzeRecord.fromFields(X_FIELD, new Object[]{store[i]});
                    Object result = callNode.call(callTarget, callArgs);
                    matches[i] = result instanceof Boolean bool && bool;
                }
            }).virtual(true).build();
            threads[b].start();
        }

        try {
            for (Thread t : threads) {
                t.join();
            }
        } catch (InterruptedException e) {
            for (Thread t : threads) t.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel partition interrupted", e);
        }

        // Collect results
        List<Object> matching = new ArrayList<>();
        List<Object> notMatching = new ArrayList<>();
        for (int i = 0; i < store.length; i++) {
            if (matches[i]) {
                matching.add(store[i]);
            } else {
                notMatching.add(store[i]);
            }
        }

        return MezzeRecord.fromFields(MATCHING_NOT_MATCHING,
            new Object[]{MezzeSeq.fromArray(matching.toArray()), MezzeSeq.fromArray(notMatching.toArray())});
    }

    private static MezzeRecord sequentialPartition(Object[] store, MezzeFunction f, IndirectCallNode callNode) {
        var callTarget = f.getCallTarget();
        Object[] closure = f.getClosureValues();
        Object[] callArgs = getCallArgsBuffer(closure.length + 1);
        System.arraycopy(closure, 0, callArgs, 0, closure.length);

        List<Object> matching = new ArrayList<>();
        List<Object> notMatching = new ArrayList<>();
        for (Object x : store) {
            // Wrap element in record - callbacks expect {x} -> ...
            callArgs[closure.length] = MezzeRecord.fromFields(X_FIELD, new Object[]{x});
            Object result = callNode.call(callTarget, callArgs);
            if (result instanceof Boolean b && b) {
                matching.add(x);
            } else {
                notMatching.add(x);
            }
        }
        return MezzeRecord.fromFields(MATCHING_NOT_MATCHING,
            new Object[]{MezzeSeq.fromArray(matching.toArray()), MezzeSeq.fromArray(notMatching.toArray())});
    }

    @TruffleBoundary
    public static MezzeSeq parallelGroupBy(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        // GroupBy is complex to parallelize correctly; fall back to sequential
        return sequentialGroupBy(args, callNode);
    }

    private static final String[] A_B_C_FIELDS = new String[]{"a", "b", "c"};

    /**
     * Parallel both - run two functions in parallel.
     */
    @TruffleBoundary
    public static MezzeRecord parallelBoth(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction left = getFunction(args, "left");
        MezzeFunction right = getFunction(args, "right");

        Env env = MezzeContext.get(node).getEnv();

        CompletableFuture<Object> leftFuture = new CompletableFuture<>();
        CompletableFuture<Object> rightFuture = new CompletableFuture<>();

        Thread leftThread = env.newTruffleThreadBuilder(() -> {
            try {
                leftFuture.complete(callNode.call(left.getCallTarget(), left.getClosureValues()));
            } catch (Throwable e) {
                leftFuture.completeExceptionally(e);
            }
        }).virtual(true).build();

        Thread rightThread = env.newTruffleThreadBuilder(() -> {
            try {
                rightFuture.complete(callNode.call(right.getCallTarget(), right.getClosureValues()));
            } catch (Throwable e) {
                rightFuture.completeExceptionally(e);
            }
        }).virtual(true).build();

        leftThread.start();
        rightThread.start();

        try {
            Object leftResult = leftFuture.get();
            Object rightResult = rightFuture.get();
            return MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{leftResult, rightResult});
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            leftThread.interrupt();
            rightThread.interrupt();
            throw new RuntimeException("Parallel both interrupted", e);
        } catch (ExecutionException e) {
            leftThread.interrupt();
            rightThread.interrupt();
            throw new RuntimeException("Parallel both failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /**
     * Parallel both3 - run three functions in parallel.
     */
    @TruffleBoundary
    public static MezzeRecord parallelBoth3(MezzeRecord args, IndirectCallNode callNode, MezzeNode node) {
        MezzeFunction a = getFunction(args, "a");
        MezzeFunction b = getFunction(args, "b");
        MezzeFunction c = getFunction(args, "c");

        Env env = MezzeContext.get(node).getEnv();

        CompletableFuture<Object> aFuture = new CompletableFuture<>();
        CompletableFuture<Object> bFuture = new CompletableFuture<>();
        CompletableFuture<Object> cFuture = new CompletableFuture<>();

        Thread aThread = env.newTruffleThreadBuilder(() -> {
            try {
                aFuture.complete(callNode.call(a.getCallTarget(), a.getClosureValues()));
            } catch (Throwable e) {
                aFuture.completeExceptionally(e);
            }
        }).virtual(true).build();

        Thread bThread = env.newTruffleThreadBuilder(() -> {
            try {
                bFuture.complete(callNode.call(b.getCallTarget(), b.getClosureValues()));
            } catch (Throwable e) {
                bFuture.completeExceptionally(e);
            }
        }).virtual(true).build();

        Thread cThread = env.newTruffleThreadBuilder(() -> {
            try {
                cFuture.complete(callNode.call(c.getCallTarget(), c.getClosureValues()));
            } catch (Throwable e) {
                cFuture.completeExceptionally(e);
            }
        }).virtual(true).build();

        aThread.start();
        bThread.start();
        cThread.start();

        try {
            return MezzeRecord.fromFields(A_B_C_FIELDS,
                new Object[]{aFuture.get(), bFuture.get(), cFuture.get()});
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            aThread.interrupt();
            bThread.interrupt();
            cThread.interrupt();
            throw new RuntimeException("Parallel both3 interrupted", e);
        } catch (ExecutionException e) {
            aThread.interrupt();
            bThread.interrupt();
            cThread.interrupt();
            throw new RuntimeException("Parallel both3 failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    // === Parallel cfg variants ===
    @TruffleBoundary
    public static MezzeSeq parallelMapCfg(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return parallelMap(args, callNode, node);
    }

    @TruffleBoundary
    public static Object parallelReduceCfg(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return parallelReduce(args, callNode, node);
    }

    @TruffleBoundary
    public static Object parallelFoldCfg(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return parallelFold(args, callNode, node);
    }

    @TruffleBoundary
    public static MezzeSeq parallelFilterCfg(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return parallelFilter(args, callNode, node);
    }

    // ==================== ForkJoin Operations ====================

    @TruffleBoundary
    public static MezzeSeq forkjoinMap(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return parallelMap(args, callNode, node); // Use parallel impl
    }

    @TruffleBoundary
    public static MezzeSeq forkjoinFilter(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return parallelFilter(args, callNode, node);
    }

    @TruffleBoundary
    public static Object forkjoinReduce(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return parallelReduce(args, callNode, node);
    }

    @TruffleBoundary
    public static Object forkjoinFold(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return parallelFold(args, callNode, node);
    }

    @TruffleBoundary
    public static boolean forkjoinAll(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return parallelAll(args, callNode, node);
    }

    @TruffleBoundary
    public static boolean forkjoinAny(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return parallelAny(args, callNode, node);
    }

    @TruffleBoundary
    public static MezzeRecord forkjoinBoth(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return parallelBoth(args, callNode, node);
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
        throw new RuntimeException("Expected Function for field '" + fieldName + "', " + sb);
    }

    private static MezzeList getList(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof MezzeList list) return list;
        // Handle MezzeSeq (Vavr Vector) - convert to MezzeList
        if (value instanceof MezzeSeq seq) {
            return MezzeList.fromArray(seq.toJavaArray());
        }
        // Handle array literals (Object[]) - convert to MezzeList
        if (value instanceof Object[] arr) {
            return MezzeList.fromArray(arr);
        }
        throw new RuntimeException("Expected List for field '" + fieldName + "', got: " +
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
        throw new RuntimeException("Expected Long for field '" + fieldName + "', got: " +
            (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private static String getString(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof String s) return s;
        throw new RuntimeException("Expected String for field '" + fieldName + "', got: " +
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
        throw new RuntimeException("Expected Array for field '" + fieldName + "'");
    }

}
