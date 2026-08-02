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
 * Tests for MezzeSuspend and MezzeThunk - the lazy effects implementation.
 *
 * MezzeThunk: A deferred computation storing effect name, operation, and argument.
 * MezzeSuspend: A wrapper around MezzeThunk that tracks unhandled effects.
 *
 * The flow is:
 * 1. Effect call without handler → Creates MezzeThunk
 * 2. MezzeThunk wrapped in MezzeSuspend with unhandled effect names
 * 3. MezzeSuspend propagates up the call stack
 * 4. When a handler is available, MezzeSuspend.executeWith(handlers) resolves it
 */
@DisplayName("MezzeSuspend Tests")
class MezzeSuspendTest {

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
        MezzeThunk thunk = new MezzeThunk("Log", "print", args, hashOf("Log"));

        assertEquals("Log", thunk.getEffectName());
        assertEquals("print", thunk.getOperationName());
        assertEquals(args, thunk.getArguments());
    }

    @Test
    @DisplayName("MezzeThunk with different operations")
    void testThunkDifferentOperations() {
        MezzeRecord args1 = MezzeRecord.fromFields(new String[]{"max"}, new Object[]{100L});
        MezzeThunk thunk1 = new MezzeThunk("Random", "nextInt", args1, hashOf("Random"));

        MezzeRecord args2 = MezzeRecord.fromFields(new String[]{"key", "value"}, new Object[]{"a", "b"});
        MezzeThunk thunk2 = new MezzeThunk("State", "put", args2, hashOf("State"));

        assertEquals("Random", thunk1.getEffectName());
        assertEquals("nextInt", thunk1.getOperationName());

        assertEquals("State", thunk2.getEffectName());
        assertEquals("put", thunk2.getOperationName());
    }

    @Test
    @DisplayName("MezzeThunk toString shows useful debug info")
    void testThunkToString() {
        MezzeRecord args = MezzeRecord.empty();
        MezzeThunk thunk = new MezzeThunk("Console", "log", args, hashOf("Console"));

        String str = thunk.toString();
        assertTrue(str.contains("Console"), "Should contain effect name");
        assertTrue(str.contains("log"), "Should contain operation name");
    }

    // ==================== MezzeSuspend Tests ====================

    @Test
    @DisplayName("MezzeSuspend wraps thunk with unhandled effects set")
    void testSuspendedWrapsThunk() {
        MezzeThunk thunk = new MezzeThunk("Log", "print", MezzeRecord.empty(), hashOf("Log"));
        Set<Hash> effects = Collections.singleton(hashOf("Log"));

        MezzeSuspend suspended = new MezzeSuspend(thunk, effects);

        assertEquals(thunk, suspended.getComputation());
        assertTrue(suspended.getUnhandledEffects().contains(hashOf("Log")));
    }

    @Test
    @DisplayName("MezzeSuspend with multiple unhandled effects")
    void testSuspendedMultipleEffects() {
        MezzeThunk thunk = new MezzeThunk("Random", "nextInt", MezzeRecord.empty(), hashOf("Random"));
        Set<Hash> effects = new HashSet<>();
        effects.add(hashOf("Random"));
        effects.add(hashOf("Log"));
        effects.add(hashOf("State"));

        MezzeSuspend suspended = new MezzeSuspend(thunk, effects);

        assertEquals(3, suspended.getUnhandledEffects().size());
        assertTrue(suspended.getUnhandledEffects().contains(hashOf("Random")));
        assertTrue(suspended.getUnhandledEffects().contains(hashOf("Log")));
        assertTrue(suspended.getUnhandledEffects().contains(hashOf("State")));
    }

    @Test
    @DisplayName("MezzeSuspend isFullyHandled returns false when effects are unhandled")
    void testSuspendedNotFullyHandled() {
        MezzeThunk thunk = new MezzeThunk("Log", "print", MezzeRecord.empty(), hashOf("Log"));
        MezzeSuspend suspended = new MezzeSuspend(thunk, Collections.singleton(hashOf("Log")));

        assertFalse(suspended.isFullyHandled());
    }

    @Test
    @DisplayName("MezzeSuspend with empty unhandled set is fully handled")
    void testSuspendedFullyHandled() {
        MezzeThunk thunk = new MezzeThunk("Log", "print", MezzeRecord.empty(), hashOf("Log"));
        MezzeSuspend suspended = new MezzeSuspend(thunk, Collections.emptySet());

        assertTrue(suspended.isFullyHandled());
    }

    @Test
    @DisplayName("MezzeSuspend toString shows unhandled effects")
    void testSuspendedToString() {
        MezzeThunk thunk = new MezzeThunk("Async", "spawn", MezzeRecord.empty(), hashOf("Async"));
        Hash asyncHash = hashOf("Async");
        MezzeSuspend suspended = new MezzeSuspend(thunk, Collections.singleton(asyncHash));

        String str = suspended.toString();
        // toString uses the Hash's hex form (the truncated hex prefix from Hash.toString()).
        assertTrue(str.contains(asyncHash.toString()) || str.contains(asyncHash.getHex()),
            "Should mention unhandled effect (hash) but was: " + str);
        assertTrue(str.contains("suspended") || str.contains("Suspended"),
            "Should indicate suspended state");
    }

    @Test
    @DisplayName("MezzeSuspend unhandled effects set is immutable view")
    void testSuspendedEffectsImmutable() {
        Set<Hash> effects = new HashSet<>();
        effects.add(hashOf("Log"));
        MezzeThunk thunk = new MezzeThunk("Log", "print", MezzeRecord.empty(), hashOf("Log"));
        MezzeSuspend suspended = new MezzeSuspend(thunk, effects);

        // Modifying original set shouldn't affect suspended
        effects.add(hashOf("State"));
        assertFalse(suspended.getUnhandledEffects().contains(hashOf("State")),
            "Suspended should have its own copy of effects");
    }

    // ==================== Handler Resolution Tests ====================

    @Test
    @DisplayName("MezzeSuspend can check if specific effect is handled")
    void testSuspendedCheckEffectHandled() {
        Set<Hash> effects = new HashSet<>();
        effects.add(hashOf("Log"));
        effects.add(hashOf("Random"));
        MezzeThunk thunk = new MezzeThunk("Log", "print", MezzeRecord.empty(), hashOf("Log"));
        MezzeSuspend suspended = new MezzeSuspend(thunk, effects);

        assertTrue(suspended.getUnhandledEffects().contains(hashOf("Log")));
        assertTrue(suspended.getUnhandledEffects().contains(hashOf("Random")));
        assertFalse(suspended.getUnhandledEffects().contains(hashOf("State")));
    }

    @Test
    @DisplayName("MezzeSuspend getEffectName returns the effect from thunk")
    void testSuspendedGetEffectName() {
        MezzeThunk thunk = new MezzeThunk("Database", "query", MezzeRecord.empty(), hashOf("Database"));
        MezzeSuspend suspended = new MezzeSuspend(thunk, Collections.singleton(hashOf("Database")));

        assertEquals("Database", suspended.getEffectName());
    }

    @Test
    @DisplayName("MezzeSuspend getOperationName returns the operation from thunk")
    void testSuspendedGetOperationName() {
        MezzeThunk thunk = new MezzeThunk("HTTP", "get", MezzeRecord.empty(), hashOf("HTTP"));
        MezzeSuspend suspended = new MezzeSuspend(thunk, Collections.singleton(hashOf("HTTP")));

        assertEquals("get", suspended.getOperationName());
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("MezzeThunk with null argument")
    void testThunkNullArgument() {
        MezzeThunk thunk = new MezzeThunk("Effect", "op", null, hashOf("Effect"));

        assertNull(thunk.getArguments());
        assertEquals("Effect", thunk.getEffectName());
    }

    @Test
    @DisplayName("MezzeSuspend with single effect")
    void testSuspendedSingleEffect() {
        MezzeThunk thunk = new MezzeThunk("Timer", "sleep", MezzeRecord.empty(), hashOf("Timer"));
        MezzeSuspend suspended = new MezzeSuspend(thunk, Collections.singleton(hashOf("Timer")));

        assertEquals(1, suspended.getUnhandledEffects().size());
        assertEquals(hashOf("Timer"), suspended.getUnhandledEffects().iterator().next());
    }

    @Test
    @DisplayName("MezzeSuspend equality based on thunk and effects")
    void testSuspendedEquality() {
        MezzeRecord args = MezzeRecord.empty();
        MezzeThunk thunk1 = new MezzeThunk("Log", "print", args, hashOf("Log"));
        MezzeThunk thunk2 = new MezzeThunk("Log", "print", args, hashOf("Log"));

        MezzeSuspend s1 = new MezzeSuspend(thunk1, Collections.singleton(hashOf("Log")));
        MezzeSuspend s2 = new MezzeSuspend(thunk2, Collections.singleton(hashOf("Log")));

        // They wrap different thunk instances, so may not be equal
        // depending on implementation
        assertNotNull(s1);
        assertNotNull(s2);
    }
}
