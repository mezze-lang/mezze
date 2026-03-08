package com.mezze.interpreter.v2;

import com.mezze.interpreter.v2.nodes.builtin.effects.AdaptiveAsyncHandler;
import com.mezze.interpreter.v2.nodes.builtin.effects.AdaptiveAsyncHandler.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AdaptiveAsyncHandler strategy selection.
 * Tests the public API of strategies and handlers.
 */
class AdaptiveAsyncHandlerUnitTest {

    @Test
    @DisplayName("LOOM_ONLY strategy always returns true")
    void testLoomOnlyStrategy() {
        AsyncStrategy strategy = AdaptiveAsyncHandler.LOOM_ONLY;
        AdaptiveStats stats = new AdaptiveStats();

        assertTrue(strategy.shouldUseLoom(stats, 0));
        assertTrue(strategy.shouldUseLoom(stats, 1));
        assertTrue(strategy.shouldUseLoom(stats, 100));
        assertTrue(strategy.shouldUseLoom(stats, 10000));

        assertEquals("LOOM_ONLY", strategy.getName());
    }

    @Test
    @DisplayName("COOP_ONLY strategy always returns false")
    void testCoopOnlyStrategy() {
        AsyncStrategy strategy = AdaptiveAsyncHandler.COOP_ONLY;
        AdaptiveStats stats = new AdaptiveStats();

        assertFalse(strategy.shouldUseLoom(stats, 0));
        assertFalse(strategy.shouldUseLoom(stats, 1));
        assertFalse(strategy.shouldUseLoom(stats, 100));
        assertFalse(strategy.shouldUseLoom(stats, 10000));

        assertEquals("COOP_ONLY", strategy.getName());
    }

    @Test
    @DisplayName("AUTO strategy prefers Loom by default")
    void testAutoStrategy() {
        AsyncStrategy strategy = AdaptiveAsyncHandler.AUTO;
        AdaptiveStats stats = new AdaptiveStats();

        // AUTO prefers Loom for most cases
        assertTrue(strategy.shouldUseLoom(stats, 0));
        assertTrue(strategy.shouldUseLoom(stats, 100));

        // Very small tasks use Coop
        assertFalse(strategy.shouldUseLoom(stats, 5));

        assertEquals("AUTO", strategy.getName());
    }

    @Test
    @DisplayName("ThresholdStrategy respects threshold")
    void testThresholdStrategy() {
        AsyncStrategy strategy = new ThresholdStrategy(50);
        AdaptiveStats stats = new AdaptiveStats();

        assertFalse(strategy.shouldUseLoom(stats, 10));
        assertFalse(strategy.shouldUseLoom(stats, 49));
        assertTrue(strategy.shouldUseLoom(stats, 50));
        assertTrue(strategy.shouldUseLoom(stats, 100));

        assertEquals("THRESHOLD(50)", strategy.getName());
    }

    @Test
    @DisplayName("AdaptiveStrategy learns from execution patterns")
    void testAdaptiveStrategy() {
        AdaptiveStrategy strategy = new AdaptiveStrategy();
        AdaptiveStats stats = new AdaptiveStats();

        // Initial state - prefers Loom
        assertTrue(strategy.shouldUseLoom(stats, 0));

        // Record some operations via the public callback
        strategy.onOperationComplete(true, 1_000_000); // 1ms Loom
        strategy.onOperationComplete(false, 100_000);  // 0.1ms Coop

        // Strategy should still work
        assertTrue(strategy.shouldUseLoom(stats, 1000));

        assertEquals("ADAPTIVE", strategy.getName());
        assertNotNull(strategy.getTimingInfo());
    }

    @Test
    @DisplayName("AdaptiveStats initial values are zero")
    void testAdaptiveStatsInitial() {
        AdaptiveStats stats = new AdaptiveStats();

        assertEquals(0, stats.getTotalOperations());
        assertEquals(0, stats.getLoomOperations());
        assertEquals(0, stats.getCoopOperations());
        assertEquals(0, stats.getAvgLoomNanos());
        assertEquals(0, stats.getAvgCoopNanos());
        assertEquals(0.0, stats.getLoomPercentage(), 0.01);

        // toString should not throw
        assertNotNull(stats.toString());
    }

    @Test
    @DisplayName("Handler uses default AUTO strategy when none specified")
    void testDefaultStrategy() {
        AdaptiveAsyncHandler handler = new AdaptiveAsyncHandler();
        assertEquals(AdaptiveAsyncHandler.AUTO, handler.getStrategy());
    }

    @Test
    @DisplayName("Handler uses specified strategy")
    void testCustomStrategy() {
        AdaptiveAsyncHandler handler = new AdaptiveAsyncHandler(AdaptiveAsyncHandler.COOP_ONLY);
        assertEquals(AdaptiveAsyncHandler.COOP_ONLY, handler.getStrategy());
    }

    @Test
    @DisplayName("Handler provides stats and tracker")
    void testHandlerAccessors() {
        AdaptiveAsyncHandler handler = new AdaptiveAsyncHandler();

        assertNotNull(handler.getStats());
        assertNotNull(handler.getTracker());
        assertNotNull(handler.getStrategy());
    }

    @Test
    @DisplayName("Handler with LOOM_ONLY strategy")
    void testHandlerWithLoomOnlyStrategy() {
        AdaptiveAsyncHandler handler = new AdaptiveAsyncHandler(AdaptiveAsyncHandler.LOOM_ONLY);
        assertEquals(AdaptiveAsyncHandler.LOOM_ONLY, handler.getStrategy());
        assertEquals("LOOM_ONLY", handler.getStrategy().getName());
    }

    @Test
    @DisplayName("Handler with AdaptiveStrategy")
    void testHandlerWithAdaptiveStrategy() {
        AdaptiveAsyncHandler handler = new AdaptiveAsyncHandler(new AdaptiveStrategy());
        assertEquals("ADAPTIVE", handler.getStrategy().getName());
    }

    @Test
    @DisplayName("Handler with ThresholdStrategy")
    void testHandlerWithThresholdStrategy() {
        AdaptiveAsyncHandler handler = new AdaptiveAsyncHandler(new ThresholdStrategy(100));
        assertEquals("THRESHOLD(100)", handler.getStrategy().getName());
    }

    @Test
    @DisplayName("Stats are shared instance per handler")
    void testStatsSharedPerHandler() {
        AdaptiveAsyncHandler handler = new AdaptiveAsyncHandler();
        AdaptiveStats stats1 = handler.getStats();
        AdaptiveStats stats2 = handler.getStats();
        assertSame(stats1, stats2);
    }

    @Test
    @DisplayName("Different handlers have different stats")
    void testDifferentHandlersDifferentStats() {
        AdaptiveAsyncHandler handler1 = new AdaptiveAsyncHandler();
        AdaptiveAsyncHandler handler2 = new AdaptiveAsyncHandler();

        assertNotSame(handler1.getStats(), handler2.getStats());
    }
}
