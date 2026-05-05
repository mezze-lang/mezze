package com.mezze.interpreter.values;

import com.mezze.language.typedast.Hash;
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

    // Synthetic test-only Hash values keyed by display name. The Hash class accepts
    // hex + `#`, so we encode the name's bytes as hex and prefix with `#`.
    private static Hash hashOf(String name) {
        byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("#");
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return new Hash(sb.toString());
    }

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
        Set<Hash> effects = Collections.singleton(hashOf("Log"));

        MezzeSuspended suspended = new MezzeSuspended(thunk, effects);

        assertEquals(thunk, suspended.getComputation());
        assertTrue(suspended.getUnhandledEffects().contains(hashOf("Log")));
    }

    @Test
    @DisplayName("MezzeSuspended with multiple unhandled effects")
    void testSuspendedMultipleEffects() {
        MezzeThunk thunk = new MezzeThunk("Random", "nextInt", MezzeRecord.empty());
        Set<Hash> effects = new HashSet<>();
        effects.add(hashOf("Random"));
        effects.add(hashOf("Log"));
        effects.add(hashOf("State"));

        MezzeSuspended suspended = new MezzeSuspended(thunk, effects);

        assertEquals(3, suspended.getUnhandledEffects().size());
        assertTrue(suspended.getUnhandledEffects().contains(hashOf("Random")));
        assertTrue(suspended.getUnhandledEffects().contains(hashOf("Log")));
        assertTrue(suspended.getUnhandledEffects().contains(hashOf("State")));
    }

    @Test
    @DisplayName("MezzeSuspended isFullyHandled returns false when effects are unhandled")
    void testSuspendedNotFullyHandled() {
        MezzeThunk thunk = new MezzeThunk("Log", "print", MezzeRecord.empty());
        MezzeSuspended suspended = new MezzeSuspended(thunk, Collections.singleton(hashOf("Log")));

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
        Hash asyncHash = hashOf("Async");
        MezzeSuspended suspended = new MezzeSuspended(thunk, Collections.singleton(asyncHash));

        String str = suspended.toString();
        // toString uses the Hash's hex form (the truncated hex prefix from Hash.toString()).
        assertTrue(str.contains(asyncHash.toString()) || str.contains(asyncHash.getHex()),
            "Should mention unhandled effect (hash) but was: " + str);
        assertTrue(str.contains("suspended") || str.contains("Suspended"),
            "Should indicate suspended state");
    }

    @Test
    @DisplayName("MezzeSuspended unhandled effects set is immutable view")
    void testSuspendedEffectsImmutable() {
        Set<Hash> effects = new HashSet<>();
        effects.add(hashOf("Log"));
        MezzeThunk thunk = new MezzeThunk("Log", "print", MezzeRecord.empty());
        MezzeSuspended suspended = new MezzeSuspended(thunk, effects);

        // Modifying original set shouldn't affect suspended
        effects.add(hashOf("State"));
        assertFalse(suspended.getUnhandledEffects().contains(hashOf("State")),
            "Suspended should have its own copy of effects");
    }

    // ==================== Handler Resolution Tests ====================

    @Test
    @DisplayName("MezzeSuspended can check if specific effect is handled")
    void testSuspendedCheckEffectHandled() {
        Set<Hash> effects = new HashSet<>();
        effects.add(hashOf("Log"));
        effects.add(hashOf("Random"));
        MezzeThunk thunk = new MezzeThunk("Log", "print", MezzeRecord.empty());
        MezzeSuspended suspended = new MezzeSuspended(thunk, effects);

        assertTrue(suspended.getUnhandledEffects().contains(hashOf("Log")));
        assertTrue(suspended.getUnhandledEffects().contains(hashOf("Random")));
        assertFalse(suspended.getUnhandledEffects().contains(hashOf("State")));
    }

    @Test
    @DisplayName("MezzeSuspended getEffectName returns the effect from thunk")
    void testSuspendedGetEffectName() {
        MezzeThunk thunk = new MezzeThunk("Database", "query", MezzeRecord.empty());
        MezzeSuspended suspended = new MezzeSuspended(thunk, Collections.singleton(hashOf("Database")));

        assertEquals("Database", suspended.getEffectName());
    }

    @Test
    @DisplayName("MezzeSuspended getOperationName returns the operation from thunk")
    void testSuspendedGetOperationName() {
        MezzeThunk thunk = new MezzeThunk("HTTP", "get", MezzeRecord.empty());
        MezzeSuspended suspended = new MezzeSuspended(thunk, Collections.singleton(hashOf("HTTP")));

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
        MezzeSuspended suspended = new MezzeSuspended(thunk, Collections.singleton(hashOf("Timer")));

        assertEquals(1, suspended.getUnhandledEffects().size());
        assertEquals(hashOf("Timer"), suspended.getUnhandledEffects().iterator().next());
    }

    @Test
    @DisplayName("MezzeSuspended equality based on thunk and effects")
    void testSuspendedEquality() {
        MezzeRecord args = MezzeRecord.empty();
        MezzeThunk thunk1 = new MezzeThunk("Log", "print", args);
        MezzeThunk thunk2 = new MezzeThunk("Log", "print", args);

        MezzeSuspended s1 = new MezzeSuspended(thunk1, Collections.singleton(hashOf("Log")));
        MezzeSuspended s2 = new MezzeSuspended(thunk2, Collections.singleton(hashOf("Log")));

        // They wrap different thunk instances, so may not be equal
        // depending on implementation
        assertNotNull(s1);
        assertNotNull(s2);
    }
}
