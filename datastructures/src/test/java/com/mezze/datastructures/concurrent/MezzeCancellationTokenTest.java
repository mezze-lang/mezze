package com.mezze.datastructures.concurrent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MezzeCancellationTokenTest {

    @Test
    void testCreateToken() {
        MezzeCancellationToken token = new MezzeCancellationToken();
        assertFalse(token.isCancelled());
        assertNull(token.getReason());
        assertNull(token.getParent());
    }

    @Test
    void testCancel() {
        MezzeCancellationToken token = new MezzeCancellationToken();
        assertFalse(token.isCancelled());

        token.cancel();
        assertTrue(token.isCancelled());
        assertNull(token.getReason());
    }

    @Test
    void testCancelWithReason() {
        MezzeCancellationToken token = new MezzeCancellationToken();
        token.cancel("timeout");

        assertTrue(token.isCancelled());
        assertEquals("timeout", token.getReason());
    }

    @Test
    void testCancelOnlyOnce() {
        MezzeCancellationToken token = new MezzeCancellationToken();
        token.cancel("first reason");
        token.cancel("second reason"); // Should be ignored

        assertEquals("first reason", token.getReason());
    }

    @Test
    void testOnCancelledCallback() {
        MezzeCancellationToken token = new MezzeCancellationToken();
        AtomicBoolean callbackFired = new AtomicBoolean(false);
        AtomicReference<MezzeCancellationToken> receivedToken = new AtomicReference<>();

        token.onCancelled("test", t -> {
            callbackFired.set(true);
            receivedToken.set(t);
        });

        assertFalse(callbackFired.get());

        token.cancel();

        assertTrue(callbackFired.get());
        assertSame(token, receivedToken.get());
    }

    @Test
    void testOnCancelledImmediateIfAlreadyCancelled() {
        MezzeCancellationToken token = new MezzeCancellationToken();
        token.cancel();

        AtomicBoolean callbackFired = new AtomicBoolean(false);
        token.onCancelled("test", t -> callbackFired.set(true));

        assertTrue(callbackFired.get());
    }

    @Test
    void testRemoveCallback() {
        MezzeCancellationToken token = new MezzeCancellationToken();
        AtomicBoolean callbackFired = new AtomicBoolean(false);

        token.onCancelled("test", t -> callbackFired.set(true));
        token.removeCallback("test");
        token.cancel();

        assertFalse(callbackFired.get());
    }

    @Test
    void testHasCallback() {
        MezzeCancellationToken token = new MezzeCancellationToken();
        assertFalse(token.hasCallback("test"));

        token.onCancelled("test", t -> {});
        assertTrue(token.hasCallback("test"));

        token.removeCallback("test");
        assertFalse(token.hasCallback("test"));
    }

    @Test
    void testCreateChild() {
        MezzeCancellationToken parent = new MezzeCancellationToken();
        MezzeCancellationToken child = parent.createChild();

        assertEquals(parent, child.getParent());
        assertEquals(1, parent.getChildCount());
        assertEquals(0, child.getChildCount());
    }

    @Test
    void testCancelPropagesToChildren() {
        MezzeCancellationToken parent = new MezzeCancellationToken();
        MezzeCancellationToken child1 = parent.createChild();
        MezzeCancellationToken child2 = parent.createChild();

        assertFalse(parent.isCancelled());
        assertFalse(child1.isCancelled());
        assertFalse(child2.isCancelled());

        parent.cancel("parent cancelled");

        assertTrue(parent.isCancelled());
        assertTrue(child1.isCancelled());
        assertTrue(child2.isCancelled());
        assertEquals("parent cancelled", child1.getReason());
        assertEquals("parent cancelled", child2.getReason());
    }

    @Test
    void testChildCreatedAfterParentCancelled() {
        MezzeCancellationToken parent = new MezzeCancellationToken();
        parent.cancel("already cancelled");

        MezzeCancellationToken child = parent.createChild();
        assertTrue(child.isCancelled());
        assertEquals("already cancelled", child.getReason());
    }

    @Test
    void testChildCancelDoesNotAffectParent() {
        MezzeCancellationToken parent = new MezzeCancellationToken();
        MezzeCancellationToken child = parent.createChild();

        child.cancel("child only");

        assertTrue(child.isCancelled());
        assertFalse(parent.isCancelled());
    }

    @Test
    void testNestedChildren() {
        MezzeCancellationToken grandparent = new MezzeCancellationToken();
        MezzeCancellationToken parent = grandparent.createChild();
        MezzeCancellationToken child = parent.createChild();

        grandparent.cancel("cascade");

        assertTrue(grandparent.isCancelled());
        assertTrue(parent.isCancelled());
        assertTrue(child.isCancelled());
    }

    @Test
    void testThrowIfCancelled() {
        MezzeCancellationToken token = new MezzeCancellationToken();
        assertDoesNotThrow(() -> token.throwIfCancelled());

        token.cancel("operation cancelled");

        MezzeCancellationToken.CancelledException ex =
            assertThrows(MezzeCancellationToken.CancelledException.class,
                () -> token.throwIfCancelled());
        assertEquals("operation cancelled", ex.getMessage());
    }

    @Test
    void testThrowIfCancelledNoReason() {
        MezzeCancellationToken token = new MezzeCancellationToken();
        token.cancel();

        MezzeCancellationToken.CancelledException ex =
            assertThrows(MezzeCancellationToken.CancelledException.class,
                () -> token.throwIfCancelled());
        assertEquals("Operation cancelled", ex.getMessage());
    }

    @Test
    void testGetId() {
        MezzeCancellationToken t1 = new MezzeCancellationToken();
        MezzeCancellationToken t2 = new MezzeCancellationToken();
        assertNotEquals(t1.getId(), t2.getId());
    }

    @Test
    void testToString() {
        MezzeCancellationToken token = new MezzeCancellationToken();
        String str = token.toString();
        assertTrue(str.startsWith("<cancellation-token:"));
        assertTrue(str.contains("active"));

        token.cancel();
        assertTrue(token.toString().contains("cancelled"));
    }

    @Test
    void testEquals() {
        MezzeCancellationToken t1 = new MezzeCancellationToken();
        MezzeCancellationToken t2 = new MezzeCancellationToken();

        assertEquals(t1, t1);
        assertNotEquals(t1, t2);
        assertNotEquals(t1, null);
        assertNotEquals(t1, "not a token");
    }

    @Test
    void testHashCode() {
        MezzeCancellationToken t1 = new MezzeCancellationToken();
        MezzeCancellationToken t2 = new MezzeCancellationToken();

        assertEquals(t1.hashCode(), t1.hashCode());
        assertNotEquals(t1.hashCode(), t2.hashCode());
    }

    @Test
    void testMultipleCallbacks() {
        MezzeCancellationToken token = new MezzeCancellationToken();
        AtomicBoolean callback1 = new AtomicBoolean(false);
        AtomicBoolean callback2 = new AtomicBoolean(false);

        token.onCancelled("first", t -> callback1.set(true));
        token.onCancelled("second", t -> callback2.set(true));

        token.cancel();

        assertTrue(callback1.get());
        assertTrue(callback2.get());
    }

    @Test
    void testCallbackExceptionDoesNotStopOthers() {
        MezzeCancellationToken token = new MezzeCancellationToken();
        AtomicBoolean callback2Fired = new AtomicBoolean(false);

        token.onCancelled("throws", t -> {
            throw new RuntimeException("callback error");
        });
        token.onCancelled("succeeds", t -> callback2Fired.set(true));

        token.cancel(); // Should not throw

        assertTrue(callback2Fired.get());
    }
}
