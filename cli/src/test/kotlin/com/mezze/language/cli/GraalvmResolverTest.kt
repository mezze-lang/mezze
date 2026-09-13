package com.mezze.language.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parts of GraalVM resolution that are pure and don't shell out: the
 * CI-strictness `requirementFailure` decision and the managed-dir layout. Full
 * precedence + `java -version` probing is exercised by the e2e scripts against a
 * throwaway MEZZE_HOME (see scripts/graalvm-e2e.sh), which is where a real JDK
 * exists to probe.
 */
class GraalvmResolverTest {

    private fun resolved(
        kind: GraalvmResolver.Kind,
        jit: Boolean,
        native: Boolean,
        version: String? = "25.0.3",
    ) = GraalvmResolver.Resolved(
        kind = kind, home = null, javaExe = null, version = version, majorVersion = 25,
        graalLine = "25.0", jit = jit, native = native, degradeReason = null, source = "test",
    )

    @Test fun `require-native fails when native-image absent`() {
        val r = resolved(GraalvmResolver.Kind.PLAIN_JDK, jit = false, native = false)
        val msg = GraalvmResolver.requirementFailure(r, requireJit = false, requireNative = true)
        assertNotNull(msg)
        assertTrue(msg!!.contains("native"), "message should mention native")
    }

    @Test fun `require-jit fails when JIT absent`() {
        val r = resolved(GraalvmResolver.Kind.PLAIN_JDK, jit = false, native = false)
        val msg = GraalvmResolver.requirementFailure(r, requireJit = true, requireNative = false)
        assertNotNull(msg)
        assertTrue(msg!!.contains("JIT") || msg.contains("jit"), "message should mention JIT")
    }

    @Test fun `no requirement failure when capabilities present`() {
        val r = resolved(GraalvmResolver.Kind.GRAALVM_MATCHING, jit = true, native = true)
        assertNull(GraalvmResolver.requirementFailure(r, requireJit = true, requireNative = true))
    }

    @Test fun `no requirement failure when nothing required`() {
        val r = resolved(GraalvmResolver.Kind.PLAIN_JDK, jit = false, native = false)
        assertNull(GraalvmResolver.requirementFailure(r, requireJit = false, requireNative = false))
    }

    @Test fun `canRun is false for TOO_OLD and NONE, true otherwise`() {
        assertFalse(resolved(GraalvmResolver.Kind.TOO_OLD, false, false).canRun)
        assertFalse(resolved(GraalvmResolver.Kind.NONE, false, false, version = null).canRun)
        assertTrue(resolved(GraalvmResolver.Kind.PLAIN_JDK, false, false).canRun)
        assertTrue(resolved(GraalvmResolver.Kind.GRAALVM_MATCHING, true, true).canRun)
        assertTrue(resolved(GraalvmResolver.Kind.GRAALVM_OTHER_LINE, true, true).canRun)
    }

    @Test fun `managed dir is keyed by graal line under mezze home`() {
        val dir = GraalvmResolver.managedGraalvmDir("25.0")
        assertEquals("25.0", dir.name)
        assertEquals("graalvm", dir.parentFile.name)
    }

    @Test fun `resolve on this machine returns a runnable JDK`() {
        // The dev/CI machine always has a JDK 21+ (GraalVM). Sanity-check the
        // happy path end-to-end without asserting a specific version.
        val r = GraalvmResolver.resolve()
        assertTrue(r.canRun, "expected a runnable JDK on the build machine; got ${r.kind}")
        assertNotNull(r.javaExe)
    }
}
