package com.mezze.datastructures.concurrent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MezzeAtomTest {

    @Test
    void testCreateAndDeref() {
        MezzeAtom<Integer> atom = new MezzeAtom<>(42);
        assertEquals(42, atom.deref());
    }

    @Test
    void testReset() {
        MezzeAtom<Integer> atom = new MezzeAtom<>(10);
        atom.reset(20);
        assertEquals(20, atom.deref());
    }

    @Test
    void testSwap() {
        MezzeAtom<Integer> atom = new MezzeAtom<>(5);
        int result = atom.swap(x -> x * 2);
        assertEquals(10, result);
        assertEquals(10, atom.deref());
    }

    @Test
    void testCompareAndSet() {
        MezzeAtom<Integer> atom = new MezzeAtom<>(100);
        assertTrue(atom.compareAndSet(100, 200));
        assertEquals(200, atom.deref());
        assertFalse(atom.compareAndSet(100, 300));
        assertEquals(200, atom.deref());
    }

    @Test
    void testValidator() {
        MezzeAtom<Integer> atom = new MezzeAtom<>(10);
        atom.setValidator(x -> x > 0);

        atom.reset(5);
        assertEquals(5, atom.deref());

        assertThrows(IllegalArgumentException.class, () -> atom.reset(-1));
        assertEquals(5, atom.deref());
    }

    @Test
    void testRemoveValidator() {
        MezzeAtom<Integer> atom = new MezzeAtom<>(10);
        atom.setValidator(x -> x > 0);
        atom.removeValidator();

        atom.reset(-5);
        assertEquals(-5, atom.deref());
    }

    @Test
    void testWatch() {
        MezzeAtom<Integer> atom = new MezzeAtom<>(1);
        AtomicReference<MezzeAtom.WatchEvent<Integer>> capturedEvent = new AtomicReference<>();

        atom.addWatch("test", event -> capturedEvent.set(event));
        atom.reset(2);

        assertNotNull(capturedEvent.get());
        assertEquals(1, capturedEvent.get().oldValue());
        assertEquals(2, capturedEvent.get().newValue());
        assertEquals("test", capturedEvent.get().key());
    }

    @Test
    void testRemoveWatch() {
        MezzeAtom<Integer> atom = new MezzeAtom<>(1);
        AtomicInteger callCount = new AtomicInteger(0);

        atom.addWatch("counter", event -> callCount.incrementAndGet());
        atom.reset(2);
        assertEquals(1, callCount.get());

        atom.removeWatch("counter");
        atom.reset(3);
        assertEquals(1, callCount.get());
    }

    @Test
    void testHasWatch() {
        MezzeAtom<Integer> atom = new MezzeAtom<>(1);
        assertFalse(atom.hasWatch("test"));
        atom.addWatch("test", event -> {});
        assertTrue(atom.hasWatch("test"));
    }

    @Test
    void testGetId() {
        MezzeAtom<Integer> atom1 = new MezzeAtom<>(1);
        MezzeAtom<Integer> atom2 = new MezzeAtom<>(2);
        assertNotEquals(atom1.getId(), atom2.getId());
    }

    @Test
    void testToString() {
        MezzeAtom<Integer> atom = new MezzeAtom<>(42);
        String str = atom.toString();
        assertTrue(str.startsWith("<atom:"));
        assertTrue(str.endsWith(">"));
    }

    @Test
    void testEquals() {
        MezzeAtom<Integer> atom1 = new MezzeAtom<>(1);
        MezzeAtom<Integer> atom2 = new MezzeAtom<>(1);

        assertEquals(atom1, atom1);
        assertNotEquals(atom1, atom2);
        assertNotEquals(atom1, null);
        assertNotEquals(atom1, "not an atom");
    }

    @Test
    void testHashCode() {
        MezzeAtom<Integer> atom1 = new MezzeAtom<>(1);
        MezzeAtom<Integer> atom2 = new MezzeAtom<>(1);

        assertEquals(atom1.hashCode(), atom1.hashCode());
        // Different atoms should have different hash codes (based on id)
        assertNotEquals(atom1.hashCode(), atom2.hashCode());
    }

    @Test
    void testSwapRetry() {
        MezzeAtom<Integer> atom = new MezzeAtom<>(0);
        AtomicInteger attempts = new AtomicInteger(0);

        // Swap should succeed even if it needs to retry
        atom.swap(x -> {
            attempts.incrementAndGet();
            return x + 1;
        });

        assertEquals(1, atom.deref());
        assertTrue(attempts.get() >= 1);
    }

    @Test
    void testNullValue() {
        MezzeAtom<String> atom = new MezzeAtom<>(null);
        assertNull(atom.deref());

        atom.reset("hello");
        assertEquals("hello", atom.deref());

        atom.reset(null);
        assertNull(atom.deref());
    }

    @Test
    void testValidatorOnSwap() {
        MezzeAtom<Integer> atom = new MezzeAtom<>(10);
        atom.setValidator(x -> x >= 0);

        assertThrows(IllegalArgumentException.class, () -> atom.swap(x -> -1));
        assertEquals(10, atom.deref());
    }
}
