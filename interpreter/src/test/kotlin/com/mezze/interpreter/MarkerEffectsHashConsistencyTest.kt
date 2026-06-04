package com.mezze.interpreter

import com.mezze.language.typechecker.WellKnownHashConstants
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Pins the Mut effect hash hardcoded in interpreter
 * {@code MarkerEffects.MUT_EFFECT_HASH_HEX} to the generated
 * {@link WellKnownHashConstants#EFFECT_MUT}.
 *
 * Why we hardcode it in the interpreter at all: the :interpreter
 * runtime module does not depend on :typechecker (the runtime jar
 * stays slim — the wire-protocol split, #220). So we can't import the
 * generated Kotlin constant from production code. The marker-Mut
 * fast path (#257) needs the hex value to recognise the Mut effect
 * at dispatch time.
 *
 * This test runs in :interpreter's test source set, which DOES
 * testImplementation(:typechecker) so the generated constants are
 * reachable. If the stdlib changes Mut's content hash, this test
 * breaks the build and forces the runtime constant to be updated.
 */
class MarkerEffectsHashConsistencyTest {

    @Test
    fun `interpreter MarkerEffects MUT_EFFECT_HASH_HEX matches WellKnownHashConstants EFFECT_MUT`() {
        // Reflective read because MarkerEffects is package-private —
        // it's an internal implementation detail of the runtime, not
        // part of the public surface. The test doesn't open the API.
        val markerEffectsCls = Class.forName(
            "com.mezze.interpreter.nodes.effect.MarkerEffects"
        )
        val hexField = markerEffectsCls.getDeclaredField("MUT_EFFECT_HASH_HEX")
        hexField.isAccessible = true
        val runtimeHex = hexField.get(null) as String

        assertEquals(
            WellKnownHashConstants.EFFECT_MUT.hex,
            runtimeHex,
            "Mut effect hash drifted between WellKnownHashConstants and the " +
                "interpreter MarkerEffects constant. Update " +
                "interpreter/src/main/java/com/mezze/interpreter/nodes/effect/" +
                "MarkerEffects.java to match WellKnownHashConstants.EFFECT_MUT."
        )
    }
}
