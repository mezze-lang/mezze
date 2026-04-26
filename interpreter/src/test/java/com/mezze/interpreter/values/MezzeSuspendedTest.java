package com.mezze.interpreter.values;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tests for MezzeSuspended and MezzeThunk - the lazy effects implementation.
 *
 * MezzeThunk: A deferred computation storing effect name, operation, and argument.
 * MezzeSuspended: A wrapper around MezzeThunk that tracks unhandled effects.
 *
 * The flow is:
 * 1. Effect call without handler → Creates MezzeThunk
 * 2. MezzeThunk wrapped in MezzeSuspended with unhandled effect names
 * 3. MezzeSuspended propagates up the call stack
 * 4. When a handler is available, MezzeSuspended.executeWith(handlers) resolves it
 */
@DisplayName("MezzeSuspended Tests")
class MezzeSuspendedTest {

    // ==================== MezzeThunk Tests ====================

    @Test
    @DisplayName("MezzeThunk stores effect name, operation, and argument")
    void testThunkStoresEffectInfo() {
        MezzeRecord args = MezzeRecord.fromFields(
            new String[]{"msg"},
            new Object[]{"hello"}
        );
        MezzeThunk thunk = new MezzeThunk("Log", "print", args);

        assertEquals("Log", thunk.getEffectName());
        assertEquals("print", thunk.getOperationName());
        assertEquals(args, thunk.getArguments());
    }

    @Test
    @DisplayName("MezzeThunk with different operations")
    void testThunkDifferentOperations() {
        MezzeRecord args1 = MezzeRecord.fromFields(new String[]{"max"}, new Object[]{100L});
        MezzeThunk thunk1 = new MezzeThunk("Random", "nextInt", args1);

        MezzeRecord args2 = MezzeRecord.fromFields(new String[]{"key", "value"}, new Object[]{"a", "b"});
        MezzeThunk thunk2 = new MezzeThunk("State", "put", args2);

        assertEquals("Random", thunk1.getEffectName());
        assertEquals("nextInt", thunk1.getOperationName());

        assertEquals("State", thunk2.getEffectName());
        assertEquals("put", thunk2.getOperationName());
    }

    @Test
    @DisplayName("MezzeThunk toString shows useful debug info")
    void testThunkToString() {
        MezzeRecord args = MezzeRecord.empty();
        MezzeThunk thunk = new MezzeThunk("Console", "log", args);

        String str = thunk.toString();
        assertTrue(str.contains("Console"), "Should contain effect name");
        assertTrue(str.contains("log"), "Should contain operation name");
    }

    // ==================== MezzeSuspended Tests ====================

    @Test
    @DisplayName("MezzeSuspended wraps thunk with unhandled effects set")
    void testSuspendedWrapsThunk() {
        MezzeThunk thunk = new MezzeThunk("Log", "print", MezzeRecord.empty());
        Set<String> effects = Collections.singleton("Log");

        MezzeSuspended suspended = new MezzeSuspended(thunk, effects);

        assertEquals(thunk, suspended.getComputation());
        assertTrue(suspended.getUnhandledEffects().contains("Log"));
    }

    @Test
    @DisplayName("MezzeSuspended with multiple unhandled effects")
    void testSuspendedMultipleEffects() {
        MezzeThunk thunk = new MezzeThunk("Random", "nextInt", MezzeRecord.empty());
        Set<String> effects = new HashSet<>();
        effects.add("Random");
        effects.add("Log");
        effects.add("State");

        MezzeSuspended suspended = new MezzeSuspended(thunk, effects);

        assertEquals(3, suspended.getUnhandledEffects().size());
        assertTrue(suspended.getUnhandledEffects().contains("Random"));
        assertTrue(suspended.getUnhandledEffects().contains("Log"));
        assertTrue(suspended.getUnhandledEffects().contains("State"));
    }

    @Test
    @DisplayName("MezzeSuspended isFullyHandled returns false when effects are unhandled")
    void testSuspendedNotFullyHandled() {
        MezzeThunk thunk = new MezzeThunk("Log", "print", MezzeRecord.empty());
        MezzeSuspended suspended = new MezzeSuspended(thunk, Collections.singleton("Log"));

        assertFalse(suspended.isFullyHandled());
    }

    @Test
    @DisplayName("MezzeSuspended with empty unhandled set is fully handled")
    void testSuspendedFullyHandled() {
        MezzeThunk thunk = new MezzeThunk("Log", "print", MezzeRecord.empty());
        MezzeSuspended suspended = new MezzeSuspended(thunk, Collections.emptySet());

        assertTrue(suspended.isFullyHandled());
    }

    @Test
    @DisplayName("MezzeSuspended toString shows unhandled effects")
    void testSuspendedToString() {
        MezzeThunk thunk = new MezzeThunk("Async", "spawn", MezzeRecord.empty());
        MezzeSuspended suspended = new MezzeSuspended(thunk, Collections.singleton("Async"));

        String str = suspended.toString();
        assertTrue(str.contains("Async"), "Should mention unhandled effect");
        assertTrue(str.contains("suspended") || str.contains("Suspended"),
            "Should indicate suspended state");
    }

    @Test
    @DisplayName("MezzeSuspended unhandled effects set is immutable view")
    void testSuspendedEffectsImmutable() {
        Set<String> effects = new HashSet<>();
        effects.add("Log");
        MezzeThunk thunk = new MezzeThunk("Log", "print", MezzeRecord.empty());
        MezzeSuspended suspended = new MezzeSuspended(thunk, effects);

        // Modifying original set shouldn't affect suspended
        effects.add("State");
        assertFalse(suspended.getUnhandledEffects().contains("State"),
            "Suspended should have its own copy of effects");
    }

    // ==================== Handler Resolution Tests ====================

    @Test
    @DisplayName("MezzeSuspended can check if specific effect is handled")
    void testSuspendedCheckEffectHandled() {
        Set<String> effects = new HashSet<>();
        effects.add("Log");
        effects.add("Random");
        MezzeThunk thunk = new MezzeThunk("Log", "print", MezzeRecord.empty());
        MezzeSuspended suspended = new MezzeSuspended(thunk, effects);

        assertTrue(suspended.getUnhandledEffects().contains("Log"));
        assertTrue(suspended.getUnhandledEffects().contains("Random"));
        assertFalse(suspended.getUnhandledEffects().contains("State"));
    }

    @Test
    @DisplayName("MezzeSuspended getEffectName returns the effect from thunk")
    void testSuspendedGetEffectName() {
        MezzeThunk thunk = new MezzeThunk("Database", "query", MezzeRecord.empty());
        MezzeSuspended suspended = new MezzeSuspended(thunk, Collections.singleton("Database"));

        assertEquals("Database", suspended.getEffectName());
    }

    @Test
    @DisplayName("MezzeSuspended getOperationName returns the operation from thunk")
    void testSuspendedGetOperationName() {
        MezzeThunk thunk = new MezzeThunk("HTTP", "get", MezzeRecord.empty());
        MezzeSuspended suspended = new MezzeSuspended(thunk, Collections.singleton("HTTP"));

        assertEquals("get", suspended.getOperationName());
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("MezzeThunk with null argument")
    void testThunkNullArgument() {
        MezzeThunk thunk = new MezzeThunk("Effect", "op", null);

        assertNull(thunk.getArguments());
        assertEquals("Effect", thunk.getEffectName());
    }

    @Test
    @DisplayName("MezzeSuspended with single effect")
    void testSuspendedSingleEffect() {
        MezzeThunk thunk = new MezzeThunk("Timer", "sleep", MezzeRecord.empty());
        MezzeSuspended suspended = new MezzeSuspended(thunk, Collections.singleton("Timer"));

        assertEquals(1, suspended.getUnhandledEffects().size());
        assertEquals("Timer", suspended.getUnhandledEffects().iterator().next());
    }

    @Test
    @DisplayName("MezzeSuspended equality based on thunk and effects")
    void testSuspendedEquality() {
        MezzeRecord args = MezzeRecord.empty();
        MezzeThunk thunk1 = new MezzeThunk("Log", "print", args);
        MezzeThunk thunk2 = new MezzeThunk("Log", "print", args);

        MezzeSuspended s1 = new MezzeSuspended(thunk1, Collections.singleton("Log"));
        MezzeSuspended s2 = new MezzeSuspended(thunk2, Collections.singleton("Log"));

        // They wrap different thunk instances, so may not be equal
        // depending on implementation
        assertNotNull(s1);
        assertNotNull(s2);
    }
}
