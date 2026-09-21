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

    private fun probedAs(
        kind: GraalvmResolver.Kind,
        source: String,
        jit: Boolean = false,
        native: Boolean = false,
    ) = GraalvmResolver.Resolved(
        kind = kind, home = null, javaExe = null, version = "25.0.3", majorVersion = 25,
        graalLine = "25.0", jit = jit, native = native, degradeReason = null, source = source,
    )

    /**
     * Selection used to be "the first candidate that probes", so a cross-line
     * `JAVA_HOME` beat the matching GraalVM mez had installed itself — a machine
     * that HAD the right one still ran degraded, and the "and no matching GraalVM
     * is present" half of the fallback rule was never checked.
     */
    @Test fun `a matching-line GraalVM wins over an earlier cross-line one`() {
        val chosen = GraalvmResolver.select(
            listOf(
                probedAs(GraalvmResolver.Kind.GRAALVM_OTHER_LINE, "JAVA_HOME"),
                probedAs(GraalvmResolver.Kind.GRAALVM_MATCHING, "~/.mezze/graalvm", jit = true),
            ),
        )
        assertEquals(GraalvmResolver.Kind.GRAALVM_MATCHING, chosen.kind)
    }

    /** Naming a JDK explicitly means running on it, degraded or not. */
    @Test fun `an explicit MEZZE_GRAALVM_HOME wins even when it degrades`() {
        val chosen = GraalvmResolver.select(
            listOf(
                probedAs(GraalvmResolver.Kind.GRAALVM_OTHER_LINE, "MEZZE_GRAALVM_HOME"),
                probedAs(GraalvmResolver.Kind.GRAALVM_MATCHING, "PATH", jit = true),
            ),
        )
        assertEquals("MEZZE_GRAALVM_HOME", chosen.source)
    }

    @Test fun `with nothing matching, the first candidate still wins`() {
        val chosen = GraalvmResolver.select(
            listOf(
                probedAs(GraalvmResolver.Kind.PLAIN_JDK, "JAVA_HOME"),
                probedAs(GraalvmResolver.Kind.GRAALVM_OTHER_LINE, "PATH"),
            ),
        )
        assertEquals("JAVA_HOME", chosen.source)
    }

    @Test fun `no candidates resolves to NONE`() {
        assertEquals(GraalvmResolver.Kind.NONE, GraalvmResolver.select(emptyList()).kind)
    }

    @Test fun `managed dir is keyed by graal line under mezze home`() {
        val dir = GraalvmResolver.managedGraalvmDir("25.0")
        assertEquals("25.0", dir.name)
        assertEquals("graalvm", dir.parentFile.name)
    }

    /**
     * A cross-line GraalVM must not claim JIT. Its libgraal is locked to ITS
     * line, so mez's bundled Truffle cannot initialize against it — the child JVM
     * died with "Could not initialize class com.oracle.truffle.api.Truffle" after
     * the resolver had already warned and handed the caller a JIT.
     *
     * Conditional because it depends on the host: vacuous on a machine whose
     * GraalVM matches (this one), load-bearing on a drifted one, which is exactly
     * where the crash was reported.
     */
    @Test fun `a cross-line GraalVM does not claim JIT`() {
        val r = GraalvmResolver.resolve()
        if (r.kind == GraalvmResolver.Kind.GRAALVM_OTHER_LINE) {
            assertFalse(r.jit, "a cross-line GraalVM must degrade to the interpreter, got jit=true")
            assertNotNull(r.degradeReason)
        }
    }

    @Test fun `resolve on this machine returns a runnable JDK`() {
        // The dev/CI machine always has a JDK 21+ (GraalVM). Sanity-check the
        // happy path end-to-end without asserting a specific version.
        val r = GraalvmResolver.resolve()
        assertTrue(r.canRun, "expected a runnable JDK on the build machine; got ${r.kind}")
        assertNotNull(r.javaExe)
    }
}
