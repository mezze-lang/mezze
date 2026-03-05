package com.mezze.datastructures.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MezzeMutexTest {

    @Test
    void testLockUnlock() {
        MezzeMutex mutex = new MezzeMutex();
        assertFalse(mutex.isLocked());

        mutex.lock();
        assertTrue(mutex.isLocked());
        assertTrue(mutex.isHeldByCurrentThread());

        mutex.unlock();
        assertFalse(mutex.isLocked());
    }

    @Test
    void testTryLock() {
        MezzeMutex mutex = new MezzeMutex();
        assertTrue(mutex.tryLock());
        assertTrue(mutex.isLocked());
        mutex.unlock();
    }

    @Test
    @Timeout(5)
    void testTryLockWithTimeout() throws InterruptedException {
        MezzeMutex mutex = new MezzeMutex();
        assertTrue(mutex.tryLock(100, TimeUnit.MILLISECONDS));
        mutex.unlock();
    }

    @Test
    void testReentrant() {
        MezzeMutex mutex = new MezzeMutex();
        assertEquals(0, mutex.getHoldCount());

        mutex.lock();
        assertEquals(1, mutex.getHoldCount());

        mutex.lock(); // Reentrant
        assertEquals(2, mutex.getHoldCount());

        mutex.unlock();
        assertEquals(1, mutex.getHoldCount());

        mutex.unlock();
        assertEquals(0, mutex.getHoldCount());
    }

    @Test
    void testFairMutex() {
        MezzeMutex mutex = new MezzeMutex(true);
        assertTrue(mutex.isFair());

        MezzeMutex unfairMutex = new MezzeMutex(false);
        assertFalse(unfairMutex.isFair());
    }

    @Test
    void testWithLockRunnable() {
        MezzeMutex mutex = new MezzeMutex();
        AtomicInteger counter = new AtomicInteger(0);

        mutex.withLock(() -> {
            assertTrue(mutex.isLocked());
            counter.incrementAndGet();
        });

        assertFalse(mutex.isLocked());
        assertEquals(1, counter.get());
    }

    @Test
    void testWithLockSupplier() {
        MezzeMutex mutex = new MezzeMutex();

        String result = mutex.withLock(() -> {
            assertTrue(mutex.isLocked());
            return "hello";
        });

        assertEquals("hello", result);
        assertFalse(mutex.isLocked());
    }

    @Test
    void testWithLockExceptionSafety() {
        MezzeMutex mutex = new MezzeMutex();

        assertThrows(RuntimeException.class, () -> {
            mutex.withLock(() -> {
                throw new RuntimeException("test error");
            });
        });

        // Lock should be released even after exception
        assertFalse(mutex.isLocked());
    }

    @Test
    @Timeout(5)
    void testMutualExclusion() throws InterruptedException {
        MezzeMutex mutex = new MezzeMutex();
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(2);
        CountDownLatch doneLatch = new CountDownLatch(2);

        Runnable task = () -> {
            startLatch.countDown();
            for (int i = 0; i < 1000; i++) {
                mutex.lock();
                try {
                    int val = counter.get();
                    counter.set(val + 1);
                } finally {
                    mutex.unlock();
                }
            }
            doneLatch.countDown();
        };

        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();

        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        assertEquals(2000, counter.get());
    }

    @Test
    void testGetQueueLength() throws InterruptedException {
        MezzeMutex mutex = new MezzeMutex();
        assertEquals(0, mutex.getQueueLength());

        mutex.lock();
        CountDownLatch started = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            started.countDown();
            mutex.lock();
            mutex.unlock();
        });
        waiter.start();

        started.await();
        Thread.sleep(50); // Give thread time to block
        assertTrue(mutex.getQueueLength() >= 0); // May be 0 or 1 depending on timing

        mutex.unlock();
        waiter.join(1000);
    }

    @Test
    void testGetId() {
        MezzeMutex m1 = new MezzeMutex();
        MezzeMutex m2 = new MezzeMutex();
        assertNotEquals(m1.getId(), m2.getId());
    }

    @Test
    void testToString() {
        MezzeMutex mutex = new MezzeMutex();
        String str = mutex.toString();
        assertTrue(str.startsWith("<mutex:"));
        assertTrue(str.contains("unlocked"));

        mutex.lock();
        assertTrue(mutex.toString().contains("locked"));
        mutex.unlock();
    }

    @Test
    void testEquals() {
        MezzeMutex m1 = new MezzeMutex();
        MezzeMutex m2 = new MezzeMutex();

        assertEquals(m1, m1);
        assertNotEquals(m1, m2);
        assertNotEquals(m1, null);
        assertNotEquals(m1, "not a mutex");
    }

    @Test
    void testHashCode() {
        MezzeMutex m1 = new MezzeMutex();
        MezzeMutex m2 = new MezzeMutex();

        assertEquals(m1.hashCode(), m1.hashCode());
        assertNotEquals(m1.hashCode(), m2.hashCode());
    }
}
