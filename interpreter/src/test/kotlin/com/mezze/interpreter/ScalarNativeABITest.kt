package com.mezze.interpreter

import com.mezze.interpreter.nodes.builtin.dsl.NativeBootstrap
import com.mezze.language.truffleir.NativeSignatures
import com.mezze.language.truffleir.SlotKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Minimal sanity tests for the NativeOp DSL → NativeSignatures plumbing.
 * Does NOT touch the IR emission or the Truffle dispatch node; those are
 * covered downstream by integration tests once more ops migrate to the
 * DSL. The goal here is to catch wiring breakage (registry empty, wrong
 * param order, kind mismatch) before it lands.
 */
class ScalarNativeABITest {

    @Test
    fun `float_sqrt is registered with correct signature`() {
        NativeBootstrap.ensureLoaded()

        val sig = NativeSignatures.signatureOf("__native_float_sqrt")
        assertNotNull(sig, "expected __native_float_sqrt to be DSL-registered")
        sig!!
        assertEquals("__native_float_sqrt", sig.name)
        assertEquals(1, sig.params.size)
        assertEquals("n", sig.params[0].name)
        assertEquals(SlotKind.DOUBLE, sig.params[0].kind)
        assertEquals(SlotKind.DOUBLE, sig.returns)
    }

    @Test
    fun `unknown native returns null signature (legacy path fallback)`() {
        NativeBootstrap.ensureLoaded()
        assertEquals(null, NativeSignatures.signatureOf("__native_does_not_exist"))
    }

    @Test
    fun `registry is non-empty after bootstrap`() {
        NativeBootstrap.ensureLoaded()
        assertTrue(NativeSignatures.size() > 0,
            "bootstrap should populate at least one entry; got 0")
    }
}
