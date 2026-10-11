package com.mezze.interpreter

import com.mezze.language.typechecker.WellKnownHashConstants
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Pins the Mut identity the runtime hardcodes
 * ({@code RuntimeAnchors.MUT_EFFECT_HASH_HEX}, read by MarkerEffects and the IR
 * eraser) to the one the compiler computes ({@link WellKnownHashConstants#EFFECT_MUT}).
 *
 * Why it is hardcoded at all: the runtime does not depend on :typechecker (the
 * runtime jar stays slim — the wire-protocol split, #220), and the marker-Mut
 * fast path (#257) needs the identity at dispatch time.
 *
 * This test runs in :interpreter's test source set, which DOES
 * testImplementation(:typechecker). If the Mut effect's identity changes — its
 * declaration, or anything its names resolve to — this names the new value.
 */
class MarkerEffectsHashConsistencyTest {

    @Test
    fun `the runtime's Mut identity is the one the compiler computes`() {
        assertEquals(
            WellKnownHashConstants.EFFECT_MUT.hex,
            com.mezze.language.truffleir.RuntimeAnchors.MUT_EFFECT_HASH_HEX,
            "Mut identity drifted between the compiler and the runtime. Update " +
                "ast/src/main/kotlin/com/mezze/language/truffleir/RuntimeAnchors.kt " +
                "to match WellKnownHashConstants.EFFECT_MUT."
        )
    }

    @Test
    fun `MarkerEffects reads that identity`() {
        // Reflective read because MarkerEffects is package-private.
        val hexField = Class.forName("com.mezze.interpreter.nodes.effect.MarkerEffects")
            .getDeclaredField("MUT_EFFECT_HASH_HEX")
        hexField.isAccessible = true
        assertEquals(com.mezze.language.truffleir.RuntimeAnchors.MUT_EFFECT_HASH_HEX, hexField.get(null) as String)
    }
}
