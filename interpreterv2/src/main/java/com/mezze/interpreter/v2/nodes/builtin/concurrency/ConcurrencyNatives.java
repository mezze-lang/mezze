package com.mezze.interpreter.v2.nodes.builtin.concurrency;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

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
     * STM Transaction with MVCC.
     */
    private static class Transaction {
        private final Map<Long, ReadEntry> readSet = new HashMap<>();
        private final Map<Long, WriteEntry> writeSet = new HashMap<>();
        private final Set<Long> ensureSet = new HashSet<>();

        record ReadEntry(MezzeTVar tvar, long version, Object value) {}
        record WriteEntry(MezzeTVar tvar, Object value) {}

        Object read(MezzeTVar tvar) {
            long id = tvar.getId();
            WriteEntry we = writeSet.get(id);
            if (we != null) return we.value;
            ReadEntry re = readSet.get(id);
            if (re != null) return re.value;

            MezzeTVar.VersionedValue vv = tvar.readVersioned();
            readSet.put(id, new ReadEntry(tvar, vv.version(), vv.value()));
            return vv.value();
        }

        void write(MezzeTVar tvar, Object value) {
            long id = tvar.getId();
            if (!readSet.containsKey(id) && !writeSet.containsKey(id)) {
                read(tvar);
            }
            writeSet.put(id, new WriteEntry(tvar, value));
        }

        Object ensure(MezzeTVar tvar) {
            ensureSet.add(tvar.getId());
            return read(tvar);
        }

        boolean validate() {
            for (ReadEntry entry : readSet.values()) {
                MezzeTVar.VersionedValue current = entry.tvar.readVersioned();
                if (current.version() != entry.version) return false;
            }
            return true;
        }

        boolean commit() {
            if (writeSet.isEmpty()) return validate();

            commitLock.lock();
            try {
                if (!validate()) return false;
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
        public void cancel() { cancelled.set(true); }
        public boolean isCancelled() { return cancelled.get(); }
        @Override public String toString() { return "<cancellation-token:" + (cancelled.get() ? "cancelled" : "active") + ">"; }
    }

    @TruffleBoundary
    public static MezzeCancellationToken cancellationTokenNew(MezzeRecord args) {
        return new MezzeCancellationToken();
    }

    @TruffleBoundary
    public static MezzeRecord cancellationTokenCancel(MezzeRecord args) {
        MezzeCancellationToken token = getCancellationToken(args, "token");
        token.cancel();
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

    public static class MezzeAgent {
        private final AtomicReference<Object> state;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        public MezzeAgent(Object initialState) {
            this.state = new AtomicReference<>(initialState);
        }

        public Object deref() { return state.get(); }

        public void send(Object action) {
            // Action should be a function, but we just set state directly for now
            state.set(action);
        }

        @Override public String toString() { return "<agent>"; }
    }

    @TruffleBoundary
    public static MezzeAgent agentNew(MezzeRecord args) {
        Object init = getField(args, "init");
        return new MezzeAgent(init);
    }

    @TruffleBoundary
    public static Object agentDeref(MezzeRecord args) {
        MezzeAgent agent = getAgent(args, "agent");
        return agent.deref();
    }

    @TruffleBoundary
    public static MezzeRecord agentSend(MezzeRecord args) {
        MezzeAgent agent = getAgent(args, "agent");
        Object action = getField(args, "action");
        agent.send(action);
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
    public static Object stmModify(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeTVar tvar = getTVar(args, "var");
        MezzeFunction f = getFunction(args, "f");
        Transaction txn = getOrCreateTransaction();
        Object current = txn.read(tvar);
        Object newVal = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), current));
        txn.write(tvar, newVal);
        return newVal;
    }

    @TruffleBoundary
    public static Object stmSeqModify(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeTVar tvar = getTVar(args, "var");
        MezzeFunction f = getFunction(args, "f");
        Object current = tvar.read();
        Object newVal = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), current));
        long newVersion = globalVersion.incrementAndGet();
        tvar.forceWrite(newVersion, newVal);
        return newVal;
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
    public static MezzeList stmReadAll(MezzeRecord args) {
        MezzeList vars = getList(args, "vars");
        Object[] store = vars.getObjectStore();
        if (store == null || store.length == 0) return MezzeList.fromObjects(new Object[0]);
        Object[] results = new Object[store.length];
        for (int i = 0; i < store.length; i++) {
            MezzeTVar tvar = unwrapTVar(store[i]);
            Transaction txn = getOrCreateTransaction();
            results[i] = txn.read(tvar);
        }
        return MezzeList.fromObjects(results);
    }

    @TruffleBoundary
    public static MezzeRecord stmWriteAll(MezzeRecord args) {
        MezzeList vars = getList(args, "vars");
        MezzeList vals = getList(args, "vals");
        Object[] varStore = vars.getObjectStore();
        Object[] valStore = vals.getObjectStore();
        if (varStore == null || valStore == null) return MezzeRecord.empty();
        Transaction txn = getOrCreateTransaction();
        for (int i = 0; i < varStore.length && i < valStore.length; i++) {
            MezzeTVar tvar = unwrapTVar(varStore[i]);
            txn.write(tvar, valStore[i]);
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
        long n = getLong(args, "n");
        try {
            for (int i = 0; i < n; i++) sem.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeRecord semaphoreReleaseN(MezzeRecord args) {
        MezzeSemaphore sem = getSemaphore(args, "sem");
        long n = getLong(args, "n");
        for (int i = 0; i < n; i++) sem.release();
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
        // For now, child is independent (parent cancellation doesn't propagate)
        return new MezzeCancellationToken();
    }

    @TruffleBoundary
    public static Object cancellationTokenGetReason(MezzeRecord args) {
        // Return None for now - no reason tracking
        return createNone();
    }

    // ==================== Additional Agent Functions ====================

    @TruffleBoundary
    public static Object agentAwait(MezzeRecord args) {
        MezzeAgent agent = getAgent(args, "agent");
        // Just return current state - no actual awaiting needed for simple impl
        return agent.deref();
    }

    @TruffleBoundary
    public static Object agentError(MezzeRecord args) {
        // Return None - no error tracking
        return createNone();
    }

    // ==================== Sequential Operations ====================

    @TruffleBoundary
    public static MezzeList sequentialMap(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.getObjectStore();
        if (store == null || store.length == 0) return MezzeList.fromObjects(new Object[0]);
        Object[] results = new Object[store.length];
        for (int i = 0; i < store.length; i++) {
            results[i] = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), store[i]));
        }
        return MezzeList.fromObjects(results);
    }

    @TruffleBoundary
    public static MezzeList sequentialFilter(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.getObjectStore();
        if (store == null || store.length == 0) return MezzeList.fromObjects(new Object[0]);
        java.util.List<Object> results = new java.util.ArrayList<>();
        for (Object x : store) {
            Object result = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), x));
            if (result instanceof Boolean b && b) {
                results.add(x);
            }
        }
        return MezzeList.fromObjects(results.toArray());
    }

    @TruffleBoundary
    public static Object sequentialFold(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // Sequential map-reduce style fold: fold {xs, zero, map_fn, combine}
        MezzeList xs = getList(args, "xs");
        Object zero = getField(args, "zero");
        MezzeFunction mapFn = getFunction(args, "map_fn");
        MezzeFunction combine = getFunction(args, "combine");
        Object[] store = xs.getObjectStore();
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
        Object[] store = xs.getObjectStore();
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
        Object[] store = xs.getObjectStore();
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
        Object[] store = xs.getObjectStore();
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
        Object[] store = xs.getObjectStore();
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
        Object[] store = xs.getObjectStore();
        if (store == null) {
            return MezzeRecord.fromFields(
                new String[]{"satisfying", "notSatisfying"},
                new Object[]{MezzeList.fromObjects(new Object[0]), MezzeList.fromObjects(new Object[0])}
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
            new Object[]{MezzeList.fromObjects(trueList.toArray()), MezzeList.fromObjects(falseList.toArray())}
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

    @TruffleBoundary
    public static MezzeList parallelMap(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        // Due to Truffle context sharing constraints, we run sequentially on the main thread
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.getObjectStore();
        if (store == null || store.length == 0) return MezzeList.fromObjects(new Object[0]);

        Object[] results = new Object[store.length];
        for (int i = 0; i < store.length; i++) {
            results[i] = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), store[i]));
        }
        return MezzeList.fromObjects(results);
    }

    @TruffleBoundary
    public static MezzeList parallelFilter(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        // Due to Truffle context sharing constraints, we run sequentially on the main thread
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.getObjectStore();
        if (store == null || store.length == 0) return MezzeList.fromObjects(new Object[0]);

        java.util.List<Object> results = new java.util.ArrayList<>();
        for (int i = 0; i < store.length; i++) {
            Object result = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), store[i]));
            if (result instanceof Boolean b && b) {
                results.add(store[i]);
            }
        }
        return MezzeList.fromObjects(results.toArray());
    }

    private static final String[] LEFT_RIGHT_FIELDS = new String[]{"left", "right"};

    @TruffleBoundary
    public static Object parallelFold(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        // Parallel map-reduce style fold: fold {xs, zero, map_fn, combine}
        // Due to Truffle context sharing constraints, we run sequentially on the main thread
        MezzeList xs = getList(args, "xs");
        Object zero = getField(args, "zero");
        MezzeFunction mapFn = getFunction(args, "map_fn");
        MezzeFunction combine = getFunction(args, "combine");
        Object[] store = xs.getObjectStore();
        if (store == null || store.length == 0) return zero;

        // Map phase: apply map_fn to each element
        Object[] mapped = new Object[store.length];
        for (int i = 0; i < store.length; i++) {
            mapped[i] = callNode.call(mapFn.getCallTarget(), appendToClosures(mapFn.getClosureValues(), store[i]));
        }

        // Reduce phase: combine all results
        Object acc = zero;
        for (Object m : mapped) {
            // combine expects {left, right} as a direct argument
            MezzeRecord combineArgs = MezzeRecord.fromFields(LEFT_RIGHT_FIELDS, new Object[]{acc, m});
            Object[] combineClosuresWithArg = new Object[combine.getClosureValues().length + 1];
            System.arraycopy(combine.getClosureValues(), 0, combineClosuresWithArg, 0, combine.getClosureValues().length);
            combineClosuresWithArg[combine.getClosureValues().length] = combineArgs;
            acc = callNode.call(combine.getCallTarget(), combineClosuresWithArg);
        }
        return acc;
    }

    @TruffleBoundary
    public static Object parallelReduce(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        // Due to Truffle context sharing constraints, we run sequentially on the main thread
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.getObjectStore();
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
    public static boolean parallelAll(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        // Due to Truffle context sharing constraints, we run sequentially on the main thread
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.getObjectStore();
        if (store == null || store.length == 0) return true;  // Vacuously true

        for (int i = 0; i < store.length; i++) {
            Object result = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), store[i]));
            if (!(result instanceof Boolean b && b)) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static boolean parallelAny(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        // Due to Truffle context sharing constraints, we run sequentially on the main thread
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.getObjectStore();
        if (store == null || store.length == 0) return false;  // No matches possible

        for (int i = 0; i < store.length; i++) {
            Object result = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), store[i]));
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
    public static Object stmModifyCfg(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return stmModify(args, callNode);
    }

    // ==================== Additional Atom Operations ====================

    @TruffleBoundary
    public static Object atomSwapWith(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeAtom atom = getAtom(args, "atom");
        MezzeFunction f = getFunction(args, "f");
        Object oldVal;
        Object newVal;
        do {
            oldVal = atom.deref();
            newVal = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), oldVal));
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
        long n = getLong(args, "n");
        for (int i = 0; i < n; i++) {
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
        // No-op for simple agent implementation
        return MezzeRecord.empty();
    }

    // ==================== Additional Sequential Operations ====================

    @TruffleBoundary
    public static MezzeRecord sequentialForEach(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.getObjectStore();
        if (store == null || store.length == 0) return MezzeRecord.empty();
        for (Object x : store) {
            callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), x));
        }
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeList sequentialGroupBy(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.getObjectStore();
        if (store == null || store.length == 0) {
            return MezzeList.fromObjects(new Object[0]);
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
                new Object[]{entry.getKey(), MezzeList.fromObjects(entry.getValue().toArray())}
            ));
        }
        return MezzeList.fromObjects(result.toArray());
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
        Object[] store = xs.getObjectStore();
        if (store == null || store.length == 0) return MezzeRecord.empty();
        for (int i = 0; i < store.length; i++) {
            callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), store[i]));
        }
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static Object parallelFind(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.getObjectStore();
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
    public static MezzeRecord parallelPartition(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        // Due to Truffle context sharing constraints, we run sequentially on the main thread
        MezzeList xs = getList(args, "xs");
        MezzeFunction f = getFunction(args, "f");
        Object[] store = xs.getObjectStore();
        if (store == null || store.length == 0) {
            return MezzeRecord.fromFields(
                new String[]{"matching", "not_matching"},
                new Object[]{MezzeList.fromObjects(new Object[0]), MezzeList.fromObjects(new Object[0])}
            );
        }
        java.util.List<Object> matching = new java.util.ArrayList<>();
        java.util.List<Object> notMatching = new java.util.ArrayList<>();
        for (int i = 0; i < store.length; i++) {
            Object result = callNode.call(f.getCallTarget(), appendToClosures(f.getClosureValues(), store[i]));
            if (result instanceof Boolean b && b) {
                matching.add(store[i]);
            } else {
                notMatching.add(store[i]);
            }
        }
        return MezzeRecord.fromFields(
            new String[]{"matching", "not_matching"},
            new Object[]{MezzeList.fromObjects(matching.toArray()), MezzeList.fromObjects(notMatching.toArray())}
        );
    }

    @TruffleBoundary
    public static MezzeList parallelGroupBy(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return sequentialGroupBy(args, callNode); // Fall back to sequential for simplicity
    }

    @TruffleBoundary
    public static MezzeRecord parallelBoth(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        // Due to Truffle context sharing constraints, we run sequentially on the main thread
        MezzeFunction left = getFunction(args, "left");
        MezzeFunction right = getFunction(args, "right");
        Object leftResult = callNode.call(left.getCallTarget(), left.getClosureValues());
        Object rightResult = callNode.call(right.getCallTarget(), right.getClosureValues());
        return MezzeRecord.fromFields(new String[]{"left", "right"}, new Object[]{leftResult, rightResult});
    }

    @TruffleBoundary
    public static MezzeRecord parallelBoth3(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        // Due to Truffle context sharing constraints, we run sequentially on the main thread
        MezzeFunction a = getFunction(args, "a");
        MezzeFunction b = getFunction(args, "b");
        MezzeFunction c = getFunction(args, "c");
        Object ra = callNode.call(a.getCallTarget(), a.getClosureValues());
        Object rb = callNode.call(b.getCallTarget(), b.getClosureValues());
        Object rc = callNode.call(c.getCallTarget(), c.getClosureValues());
        return MezzeRecord.fromFields(new String[]{"a", "b", "c"}, new Object[]{ra, rb, rc});
    }

    // === Parallel cfg variants ===
    @TruffleBoundary
    public static MezzeList parallelMapCfg(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
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
    public static MezzeList parallelFilterCfg(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return parallelFilter(args, callNode, node);
    }

    // ==================== ForkJoin Operations ====================

    @TruffleBoundary
    public static MezzeList forkjoinMap(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
        return parallelMap(args, callNode, node); // Use parallel impl
    }

    @TruffleBoundary
    public static MezzeList forkjoinFilter(MezzeRecord args, com.oracle.truffle.api.nodes.IndirectCallNode callNode, com.mezze.interpreter.v2.nodes.MezzeNode node) {
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
    private static final String[] X_FIELD = new String[]{"x"};
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
        // Handle array literals (Object[]) - convert to MezzeList
        if (value instanceof Object[] arr) {
            return MezzeList.fromObjects(arr);
        }
        throw new RuntimeException("Expected List for field '" + fieldName + "'");
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
