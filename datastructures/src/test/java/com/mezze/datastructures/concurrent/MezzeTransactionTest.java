package com.mezze.datastructures.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MezzeTransactionTest {

    @AfterEach
    void cleanup() {
        // Ensure no lingering transactions
        while (MezzeTransaction.current() != null) {
            MezzeTransaction.end();
        }
    }

    @Test
    void testReadOnlyTransaction() {
        MezzeTVar tvar = new MezzeTVar(42);

        MezzeTransaction txn = new MezzeTransaction();
        Object value = txn.read(tvar);
        assertEquals(42, value);

        assertTrue(txn.commit());
    }

    @Test
    void testWriteTransaction() {
        MezzeTVar tvar = new MezzeTVar(10);

        MezzeTransaction txn = new MezzeTransaction();
        txn.read(tvar);
        txn.write(tvar, 20);
        assertTrue(txn.commit());

        assertEquals(20, tvar.read());
    }

    @Test
    void testMultipleWrites() {
        MezzeTVar tvar1 = new MezzeTVar(1);
        MezzeTVar tvar2 = new MezzeTVar(2);

        MezzeTransaction txn = new MezzeTransaction();
        txn.write(tvar1, 10);
        txn.write(tvar2, 20);
        assertTrue(txn.commit());

        assertEquals(10, tvar1.read());
        assertEquals(20, tvar2.read());
    }

    @Test
    void testReadYourOwnWrites() {
        MezzeTVar tvar = new MezzeTVar(100);

        MezzeTransaction txn = new MezzeTransaction();
        assertEquals(100, txn.read(tvar));
        txn.write(tvar, 200);
        assertEquals(200, txn.read(tvar)); // Should see own write
        assertTrue(txn.commit());

        assertEquals(200, tvar.read());
    }

    @Test
    void testConflictDetection() {
        MezzeTVar tvar = new MezzeTVar(0);

        // First transaction reads
        MezzeTransaction txn1 = new MezzeTransaction();
        txn1.read(tvar);

        // Another transaction modifies
        MezzeTransaction txn2 = new MezzeTransaction();
        txn2.write(tvar, 99);
        assertTrue(txn2.commit());

        // First transaction tries to write - should fail validation
        txn1.write(tvar, 50);
        assertFalse(txn1.commit());

        // Value should be from txn2
        assertEquals(99, tvar.read());
    }

    @Test
    void testValidation() {
        MezzeTVar tvar = new MezzeTVar("initial");

        MezzeTransaction txn = new MezzeTransaction();
        txn.read(tvar);
        assertTrue(txn.validate());

        // External modification
        tvar.forceWrite(999L, "changed");

        assertFalse(txn.validate());
    }

    @Test
    void testEnsure() {
        MezzeTVar tvar = new MezzeTVar(42);

        MezzeTransaction txn = new MezzeTransaction();
        Object value = txn.ensure(tvar);
        assertEquals(42, value);
        assertTrue(txn.hasReads());
    }

    @Test
    void testMightConflict() {
        MezzeTVar tvar1 = new MezzeTVar(1);
        MezzeTVar tvar2 = new MezzeTVar(2);

        MezzeTransaction txn = new MezzeTransaction();
        txn.read(tvar1);

        // tvar1 should be in bloom filter
        assertTrue(txn.mightConflict(tvar1.getId()));
        // tvar2 might or might not (false positives possible)
    }

    @Test
    void testHasWritesAndReads() {
        MezzeTVar tvar = new MezzeTVar(0);

        MezzeTransaction txn = new MezzeTransaction();
        assertFalse(txn.hasReads());
        assertFalse(txn.hasWrites());

        txn.read(tvar);
        assertTrue(txn.hasReads());
        assertFalse(txn.hasWrites());

        txn.write(tvar, 1);
        assertTrue(txn.hasReads());
        assertTrue(txn.hasWrites());
    }

    @Test
    void testStartVersion() {
        long before = MezzeTransaction.getGlobalVersion();
        MezzeTransaction txn = new MezzeTransaction();
        long after = MezzeTransaction.getGlobalVersion();

        assertTrue(txn.getStartVersion() >= before);
        assertTrue(txn.getStartVersion() <= after);
    }

    @Test
    void testStaticBeginEnd() {
        assertNull(MezzeTransaction.current());

        MezzeTransaction.begin();
        assertNotNull(MezzeTransaction.current());

        assertTrue(MezzeTransaction.end());
        assertNull(MezzeTransaction.current());
    }

    @Test
    void testNestedTransactions() {
        MezzeTransaction.begin();
        MezzeTransaction outer = MezzeTransaction.current();

        MezzeTransaction.begin();
        MezzeTransaction inner = MezzeTransaction.current();

        // Same transaction for nested
        assertSame(outer, inner);

        assertTrue(MezzeTransaction.end()); // Inner end
        assertNotNull(MezzeTransaction.current()); // Still in outer

        assertTrue(MezzeTransaction.end()); // Outer end
        assertNull(MezzeTransaction.current());
    }

    @Test
    void testGetOrCreate() {
        // Use begin/end pattern which properly manages nesting depth
        MezzeTransaction.begin();
        MezzeTransaction txn1 = MezzeTransaction.current();
        assertNotNull(txn1);

        // getOrCreate should return the same transaction
        MezzeTransaction txn2 = MezzeTransaction.getOrCreate();
        assertSame(txn1, txn2);

        // Cleanup using end
        MezzeTransaction.end();
        assertNull(MezzeTransaction.current());
    }

    @Test
    void testIncrementGlobalVersion() {
        long v1 = MezzeTransaction.getGlobalVersion();
        long v2 = MezzeTransaction.incrementGlobalVersion();
        long v3 = MezzeTransaction.getGlobalVersion();

        assertEquals(v1 + 1, v2);
        assertEquals(v2, v3);
    }

    @Test
    void testEndWithoutBegin() {
        assertTrue(MezzeTransaction.end()); // Should just return true
    }

    @Test
    @Timeout(5)
    void testConcurrentTransactions() throws InterruptedException {
        MezzeTVar counter = new MezzeTVar(0);
        int numThreads = 4;
        int incrementsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        Runnable task = () -> {
            for (int i = 0; i < incrementsPerThread; i++) {
                boolean success = false;
                while (!success) {
                    MezzeTransaction txn = new MezzeTransaction();
                    int current = (int) txn.read(counter);
                    txn.write(counter, current + 1);
                    success = txn.commit();
                }
                successCount.incrementAndGet();
            }
            latch.countDown();
        };

        for (int i = 0; i < numThreads; i++) {
            new Thread(task).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(numThreads * incrementsPerThread, counter.read());
        assertEquals(numThreads * incrementsPerThread, successCount.get());
    }

    @Test
    void testWriteWithoutExplicitRead() {
        MezzeTVar tvar = new MezzeTVar(10);

        MezzeTransaction txn = new MezzeTransaction();
        // Write without explicit read first - should auto-read
        txn.write(tvar, 20);
        assertTrue(txn.hasReads()); // Auto-read happened
        assertTrue(txn.commit());

        assertEquals(20, tvar.read());
    }

    @Test
    void testMultipleReadsOfSameTVar() {
        MezzeTVar tvar = new MezzeTVar(42);

        MezzeTransaction txn = new MezzeTransaction();
        Object v1 = txn.read(tvar);
        Object v2 = txn.read(tvar);

        assertEquals(v1, v2);
        assertEquals(42, v1);
    }
}
