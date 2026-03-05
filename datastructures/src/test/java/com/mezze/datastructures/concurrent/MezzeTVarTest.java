package com.mezze.datastructures.concurrent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MezzeTVarTest {

    @Test
    void testCreateAndRead() {
        MezzeTVar tvar = new MezzeTVar(42);
        assertEquals(42, tvar.read());
    }

    @Test
    void testReadVersioned() {
        MezzeTVar tvar = new MezzeTVar("hello");
        MezzeTVar.VersionedValue vv = tvar.readVersioned();

        assertEquals(0L, vv.version());
        assertEquals("hello", vv.value());
    }

    @Test
    void testForceWrite() {
        MezzeTVar tvar = new MezzeTVar(10);
        tvar.forceWrite(5L, 100);

        MezzeTVar.VersionedValue vv = tvar.readVersioned();
        assertEquals(5L, vv.version());
        assertEquals(100, vv.value());
    }

    @Test
    void testCompareAndSetSuccess() {
        MezzeTVar tvar = new MezzeTVar(1);
        MezzeTVar.VersionedValue current = tvar.readVersioned();

        boolean success = tvar.compareAndSet(current, new MezzeTVar.VersionedValue(1L, 2));
        assertTrue(success);
        assertEquals(2, tvar.read());
    }

    @Test
    void testCompareAndSetFailure() {
        MezzeTVar tvar = new MezzeTVar(1);
        MezzeTVar.VersionedValue stale = tvar.readVersioned();

        tvar.forceWrite(2L, 100);

        boolean success = tvar.compareAndSet(stale, new MezzeTVar.VersionedValue(3L, 200));
        assertFalse(success);
        assertEquals(100, tvar.read());
    }

    @Test
    void testWaiters() throws InterruptedException {
        MezzeTVar tvar = new MezzeTVar(0);
        AtomicBoolean woken = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            tvar.addWaiter(Thread.currentThread());
            started.countDown();
            java.util.concurrent.locks.LockSupport.park();
            woken.set(true);
            done.countDown();
        });
        waiter.start();

        started.await();
        Thread.sleep(50); // Ensure waiter is parked

        assertFalse(woken.get());

        tvar.forceWrite(1L, 10); // Should wake waiter

        assertTrue(done.await(1, TimeUnit.SECONDS));
        assertTrue(woken.get());
    }

    @Test
    void testRemoveWaiter() {
        MezzeTVar tvar = new MezzeTVar(0);
        Thread t = Thread.currentThread();

        tvar.addWaiter(t);
        tvar.removeWaiter(t);

        // Verify no exception on force write
        tvar.forceWrite(1L, 10);
    }

    @Test
    void testNullValue() {
        MezzeTVar tvar = new MezzeTVar(null);
        assertNull(tvar.read());

        tvar.forceWrite(1L, "not null");
        assertEquals("not null", tvar.read());

        tvar.forceWrite(2L, null);
        assertNull(tvar.read());
    }

    @Test
    void testGetId() {
        MezzeTVar t1 = new MezzeTVar(1);
        MezzeTVar t2 = new MezzeTVar(2);
        assertNotEquals(t1.getId(), t2.getId());
    }

    @Test
    void testToString() {
        MezzeTVar tvar = new MezzeTVar(42);
        String str = tvar.toString();
        assertTrue(str.startsWith("<tvar:"));
        assertTrue(str.endsWith(">"));
    }

    @Test
    void testEquals() {
        MezzeTVar t1 = new MezzeTVar(1);
        MezzeTVar t2 = new MezzeTVar(1);

        assertEquals(t1, t1);
        assertNotEquals(t1, t2);
        assertNotEquals(t1, null);
        assertNotEquals(t1, "not a tvar");
    }

    @Test
    void testHashCode() {
        MezzeTVar t1 = new MezzeTVar(1);
        MezzeTVar t2 = new MezzeTVar(1);

        assertEquals(t1.hashCode(), t1.hashCode());
        assertNotEquals(t1.hashCode(), t2.hashCode());
    }

    @Test
    void testVersionedValueRecord() {
        MezzeTVar.VersionedValue vv1 = new MezzeTVar.VersionedValue(1L, "hello");
        MezzeTVar.VersionedValue vv2 = new MezzeTVar.VersionedValue(1L, "hello");
        MezzeTVar.VersionedValue vv3 = new MezzeTVar.VersionedValue(2L, "hello");

        assertEquals(vv1, vv2);
        assertNotEquals(vv1, vv3);
        assertEquals(vv1.hashCode(), vv2.hashCode());
    }
}
