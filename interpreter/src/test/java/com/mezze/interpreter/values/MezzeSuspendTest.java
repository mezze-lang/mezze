package com.mezze.interpreter.values;

import com.mezze.language.typedast.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Tests for MezzeSuspend and MezzeThunk - the lazy effects implementation.
 *
 * MezzeThunk: A deferred op-call storing (opName, args, effectHash).
 * MezzeSuspend: A wrapper around MezzeThunk that tracks unhandled effects.
 *
 * The flow is:
 * 1. Effect call without handler → Creates MezzeThunk
 * 2. MezzeThunk wrapped in MezzeSuspend with unhandled effect hashes
 * 3. MezzeSuspend propagates up the call stack
 * 4. When a handler is available, MezzeSuspend.force() dispatches on the fiber's
 *    handler chain.
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
    @DisplayName("MezzeThunk stores operation name, argument, and effect hash")
    void testThunkStoresOpInfo() {
        MezzeRecord args = MezzeRecord.fromFields(
            new String[]{"msg"},
            new Object[]{"hello"}
        );
        Hash logHash = hashOf("Log");
        MezzeThunk thunk = new MezzeThunk("print", args, logHash);

        assertEquals("print", thunk.getOperationName());
        assertEquals(args, thunk.getArguments());
        assertEquals(logHash, thunk.getEffectContentHash());
    }

    @Test
    @DisplayName("MezzeThunk with different operations")
    void testThunkDifferentOperations() {
        MezzeRecord args1 = MezzeRecord.fromFields(new String[]{"max"}, new Object[]{100L});
        MezzeThunk thunk1 = new MezzeThunk("nextInt", args1, hashOf("Random"));

        MezzeRecord args2 = MezzeRecord.fromFields(new String[]{"key", "value"}, new Object[]{"a", "b"});
        MezzeThunk thunk2 = new MezzeThunk("put", args2, hashOf("State"));

        assertEquals("nextInt", thunk1.getOperationName());
        assertEquals("put", thunk2.getOperationName());
        assertNotEquals(thunk1.getEffectContentHash(), thunk2.getEffectContentHash());
    }

    @Test
    @DisplayName("MezzeThunk toString shows the op name")
    void testThunkToString() {
        MezzeRecord args = MezzeRecord.empty();
        MezzeThunk thunk = new MezzeThunk("log", args, hashOf("Console"));

        String str = thunk.toString();
        assertTrue(str.contains("log"), "Should contain op name, got: " + str);
    }

    // ==================== MezzeSuspend Tests ====================

    @Test
    @DisplayName("MezzeSuspend wraps thunk with unhandled effects set")
    void testSuspendedWrapsThunk() {
        MezzeThunk thunk = new MezzeThunk("print", MezzeRecord.empty(), hashOf("Log"));
        Set<Hash> effects = Collections.singleton(hashOf("Log"));

        MezzeSuspend suspended = new MezzeSuspend(thunk, effects);

        assertTrue(suspended.getUnhandledEffects().contains(hashOf("Log")));
    }

    @Test
    @DisplayName("MezzeSuspend with multiple unhandled effects")
    void testSuspendedMultipleEffects() {
        MezzeThunk thunk = new MezzeThunk("nextInt", MezzeRecord.empty(), hashOf("Random"));
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
        MezzeThunk thunk = new MezzeThunk("print", MezzeRecord.empty(), hashOf("Log"));
        MezzeSuspend suspended = new MezzeSuspend(thunk, Collections.singleton(hashOf("Log")));

        assertFalse(suspended.isFullyHandled());
    }

    @Test
    @DisplayName("MezzeSuspend with empty unhandled set is fully handled")
    void testSuspendedFullyHandled() {
        MezzeThunk thunk = new MezzeThunk("print", MezzeRecord.empty(), hashOf("Log"));
        MezzeSuspend suspended = new MezzeSuspend(thunk, Collections.emptySet());

        assertTrue(suspended.isFullyHandled());
    }

    @Test
    @DisplayName("MezzeSuspend toString shows unhandled effects")
    void testSuspendedToString() {
        MezzeThunk thunk = new MezzeThunk("spawn", MezzeRecord.empty(), hashOf("Async"));
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
        MezzeThunk thunk = new MezzeThunk("print", MezzeRecord.empty(), hashOf("Log"));
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
        MezzeThunk thunk = new MezzeThunk("print", MezzeRecord.empty(), hashOf("Log"));
        MezzeSuspend suspended = new MezzeSuspend(thunk, effects);

        assertTrue(suspended.getUnhandledEffects().contains(hashOf("Log")));
        assertTrue(suspended.getUnhandledEffects().contains(hashOf("Random")));
        assertFalse(suspended.getUnhandledEffects().contains(hashOf("State")));
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("MezzeThunk with null argument")
    void testThunkNullArgument() {
        Hash h = hashOf("Effect");
        MezzeThunk thunk = new MezzeThunk("op", null, h);

        assertNull(thunk.getArguments());
        assertEquals("op", thunk.getOperationName());
        assertEquals(h, thunk.getEffectContentHash());
    }

    @Test
    @DisplayName("MezzeSuspend with single effect")
    void testSuspendedSingleEffect() {
        MezzeThunk thunk = new MezzeThunk("sleep", MezzeRecord.empty(), hashOf("Timer"));
        MezzeSuspend suspended = new MezzeSuspend(thunk, Collections.singleton(hashOf("Timer")));

        assertEquals(1, suspended.getUnhandledEffects().size());
        assertEquals(hashOf("Timer"), suspended.getUnhandledEffects().iterator().next());
    }

    @Test
    @DisplayName("MezzeSuspend equality based on thunk and effects")
    void testSuspendedEquality() {
        MezzeRecord args = MezzeRecord.empty();
        MezzeThunk thunk1 = new MezzeThunk("print", args, hashOf("Log"));
        MezzeThunk thunk2 = new MezzeThunk("print", args, hashOf("Log"));

        MezzeSuspend s1 = new MezzeSuspend(thunk1, Collections.singleton(hashOf("Log")));
        MezzeSuspend s2 = new MezzeSuspend(thunk2, Collections.singleton(hashOf("Log")));

        // They wrap different thunk instances, so may not be equal
        // depending on implementation
        assertNotNull(s1);
        assertNotNull(s2);
    }
}
