package com.mezze.datastructures.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MezzeAgentTest {

    @Test
    void testCreateAndDeref() {
        MezzeAgent<Integer> agent = new MezzeAgent<>(42);
        assertEquals(42, agent.deref());
    }

    @Test
    @Timeout(5)
    void testSendWithFunction() throws InterruptedException {
        MezzeAgent<Integer> agent = new MezzeAgent<>(10);

        agent.send(x -> x * 2);
        agent.await();

        assertEquals(20, agent.deref());
    }

    @Test
    @Timeout(5)
    void testSendWithValue() throws InterruptedException {
        MezzeAgent<Integer> agent = new MezzeAgent<>(10);

        agent.send(100);
        agent.await();

        assertEquals(100, agent.deref());
    }

    @Test
    @Timeout(5)
    void testMultipleSends() throws InterruptedException {
        MezzeAgent<Integer> agent = new MezzeAgent<>(0);

        for (int i = 1; i <= 10; i++) {
            final int val = i;
            agent.send(x -> x + val);
        }
        agent.await();

        assertEquals(55, agent.deref()); // 1+2+3+...+10
    }

    @Test
    @Timeout(5)
    void testAwaitWithTimeout() throws InterruptedException {
        MezzeAgent<Integer> agent = new MezzeAgent<>(0);
        assertTrue(agent.await(1, TimeUnit.SECONDS));
    }

    @Test
    void testErrorHandling() throws InterruptedException {
        MezzeAgent<Integer> agent = new MezzeAgent<>(10);
        AtomicReference<Throwable> capturedError = new AtomicReference<>();

        agent.setErrorHandler(capturedError::set);

        agent.send(x -> {
            throw new RuntimeException("test error");
        });
        agent.await();

        assertTrue(agent.hasError());
        assertNotNull(agent.getError());
        assertEquals("test error", agent.getError().getMessage());
        assertNotNull(capturedError.get());
    }

    @Test
    void testClearError() throws InterruptedException {
        MezzeAgent<Integer> agent = new MezzeAgent<>(10);
        agent.setErrorHandler(e -> {}); // Ignore errors

        agent.send(x -> {
            throw new RuntimeException("error");
        });
        agent.await();

        assertTrue(agent.hasError());
        agent.clearError();
        assertFalse(agent.hasError());
        assertNull(agent.getError());
    }

    @Test
    @Timeout(5)
    void testSuccessClearsError() throws InterruptedException {
        MezzeAgent<Integer> agent = new MezzeAgent<>(10);
        agent.setErrorHandler(e -> {});

        agent.send(x -> {
            throw new RuntimeException("error");
        });
        agent.await();
        assertTrue(agent.hasError());

        agent.send(x -> x + 1);
        agent.await();
        assertFalse(agent.hasError());
        assertEquals(11, agent.deref());
    }

    @Test
    void testSendWithoutErrorHandlerThrows() throws InterruptedException {
        MezzeAgent<Integer> agent = new MezzeAgent<>(10);

        // Create an error
        agent.setErrorHandler(e -> {});
        agent.send(x -> { throw new RuntimeException("error"); });
        agent.await();

        // Remove handler
        agent.removeErrorHandler();

        // Now send should throw
        assertThrows(IllegalStateException.class, () -> agent.send(x -> x + 1));
    }

    @Test
    @Timeout(5)
    void testRestart() throws InterruptedException {
        MezzeAgent<Integer> agent = new MezzeAgent<>(10);
        agent.setErrorHandler(e -> {});

        agent.send(x -> { throw new RuntimeException("error"); });
        agent.await();

        agent.restart(100);
        agent.await();

        assertEquals(100, agent.deref());
        assertFalse(agent.hasError());
    }

    @Test
    void testShutdown() {
        MezzeAgent<Integer> agent = new MezzeAgent<>(10);
        assertFalse(agent.isShutdown());

        agent.shutdown();
        assertTrue(agent.isShutdown());

        assertThrows(IllegalStateException.class, () -> agent.send(x -> x + 1));
    }

    @Test
    @Timeout(5)
    void testSerialExecution() throws InterruptedException {
        MezzeAgent<StringBuilder> agent = new MezzeAgent<>(new StringBuilder());

        for (char c = 'a'; c <= 'z'; c++) {
            final char ch = c;
            agent.send(sb -> {
                sb.append(ch);
                return sb;
            });
        }
        agent.await();

        assertEquals("abcdefghijklmnopqrstuvwxyz", agent.deref().toString());
    }

    @Test
    void testGetId() {
        MezzeAgent<Integer> a1 = new MezzeAgent<>(1);
        MezzeAgent<Integer> a2 = new MezzeAgent<>(2);
        assertNotEquals(a1.getId(), a2.getId());
    }

    @Test
    void testToString() {
        MezzeAgent<Integer> agent = new MezzeAgent<>(42);
        String str = agent.toString();
        assertTrue(str.startsWith("<agent:"));
        assertTrue(str.contains("active"));
    }

    @Test
    void testToStringShutdown() {
        MezzeAgent<Integer> agent = new MezzeAgent<>(42);
        agent.shutdown();
        assertTrue(agent.toString().contains("shutdown"));
    }

    @Test
    void testToStringError() throws InterruptedException {
        MezzeAgent<Integer> agent = new MezzeAgent<>(42);
        agent.setErrorHandler(e -> {});
        agent.send(x -> { throw new RuntimeException(); });
        agent.await();
        assertTrue(agent.toString().contains("error"));
    }

    @Test
    void testEquals() {
        MezzeAgent<Integer> a1 = new MezzeAgent<>(1);
        MezzeAgent<Integer> a2 = new MezzeAgent<>(1);

        assertEquals(a1, a1);
        assertNotEquals(a1, a2);
        assertNotEquals(a1, null);
        assertNotEquals(a1, "not an agent");
    }

    @Test
    void testHashCode() {
        MezzeAgent<Integer> a1 = new MezzeAgent<>(1);
        MezzeAgent<Integer> a2 = new MezzeAgent<>(1);

        assertEquals(a1.hashCode(), a1.hashCode());
        assertNotEquals(a1.hashCode(), a2.hashCode());
    }

    @Test
    void testRemoveErrorHandler() throws InterruptedException {
        MezzeAgent<Integer> agent = new MezzeAgent<>(10);
        AtomicBoolean handlerCalled = new AtomicBoolean(false);

        agent.setErrorHandler(e -> handlerCalled.set(true));
        agent.send(x -> { throw new RuntimeException(); });
        agent.await();
        assertTrue(handlerCalled.get());

        handlerCalled.set(false);
        agent.clearError();
        agent.removeErrorHandler();

        // Now errors won't call the handler (but will put agent in error state)
        // Need to set a new handler to continue, or agent will throw on send
        agent.setErrorHandler(e -> {}); // Set no-op handler
        agent.send(x -> { throw new RuntimeException(); });
        agent.await();
        assertFalse(handlerCalled.get());
    }

    @Test
    void testNullState() {
        MezzeAgent<String> agent = new MezzeAgent<>(null);
        assertNull(agent.deref());
    }
}
