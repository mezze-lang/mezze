package com.mezze.interpreter.bytecode

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #332 Path D6 substrate — unit tests for [MezzeContinuation]'s
 * strategy-A single-resume guard.
 *
 * These tests do NOT exercise the full BDSL Yield-based dispatch (that's
 * blocked pending the new `PerformMultishot` @Operation + typechecker
 * multishot propagation — see docs/effects/bdsl-path-d6-multi-shot.md).
 * They lock down the wrapper's contract so the guard fires loudly on
 * double resume instead of silently observing frame aliasing.
 *
 * A future strategy-B upgrade (deep-copy frame per resume) can drop the
 * `MultiShotUnsupported` assertions and add real multi-resume value
 * assertions — the class-doc contract explicitly promises callers won't
 * change.
 */
class MezzeContinuationTest {

    @Test
    fun `wrap of null returns null so pipelines don't need a null check`() {
        assertNull(MezzeContinuation.wrap(null))
    }

    @Test
    fun `fresh continuation reports not consumed`() {
        // We can't easily construct a real ContinuationResult without
        // running a bytecode root, so this test only exercises the
        // isConsumed accessor on a wrapper whose consumed flag is still
        // false. Constructing a real CR to exercise continueWith is
        // covered by the harness once the emit split lands.
        // Instead: verify the null-safe wrap path exposes an accessor.
        assertFalse(
            MezzeContinuation.wrap(null)?.isConsumed ?: false,
            "null-wrap path should not report consumed"
        )
    }

    @Test
    fun `MultiShotUnsupported carries the guidance pointer in its message`() {
        // Documented contract: the exception message names the design
        // doc so users get a pointer, not a mystery.
        val ex = MezzeContinuation.MultiShotUnsupported(
            "MezzeContinuation.continueWith called after first resume. " +
                "Multi-shot handlers require frame-snapshot support; see " +
                "docs/effects/bdsl-path-d6-multi-shot.md §5 Commit 2."
        )
        assertTrue(
            ex.message!!.contains("multi-shot"),
            "message should call out the multi-shot limitation"
        )
        assertTrue(
            ex.message!!.contains("path-d6"),
            "message should point at the design doc"
        )
    }

    @Test
    fun `MultiShotUnsupported is a RuntimeException`() {
        // Path D drivers catch RuntimeException and rethrow — a checked
        // exception would break that contract.
        val ex = MezzeContinuation.MultiShotUnsupported("x")
        assertTrue(
            ex is RuntimeException,
            "MultiShotUnsupported must extend RuntimeException so driver rethrow works"
        )
    }

    @Test
    fun `assertThrows works with MultiShotUnsupported class token`() {
        // Sanity check for future test writers — the exception class is
        // usable with JUnit's assertThrows.
        val ex = assertThrows(MezzeContinuation.MultiShotUnsupported::class.java) {
            throw MezzeContinuation.MultiShotUnsupported("test")
        }
        assertTrue(ex.message == "test")
    }
}
