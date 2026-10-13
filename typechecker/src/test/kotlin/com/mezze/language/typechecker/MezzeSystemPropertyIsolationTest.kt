package com.mezze.language.typechecker

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The property guard puts back exactly what the build set.
 *
 * Written against [MezzeSystemPropertyIsolation.snapshot]/[restore] directly
 * rather than by having one test pollute and another check: the suite randomises
 * class and method order, so a two-test version would assert nothing on the runs
 * where the checker happens to go first.
 */
class MezzeSystemPropertyIsolationTest {

    private val probe = "mezze.__isolation_probe"
    private val introduced = "mezze.__isolation_introduced"

    @Test
    fun `a CHANGED property is put back`() {
        System.setProperty(probe, "from-the-build")
        val guard = MezzeSystemPropertyIsolation()
        guard.snapshot()

        System.setProperty(probe, "clobbered-by-a-test")
        assertEquals("clobbered-by-a-test", System.getProperty(probe), "precondition")

        guard.restore()
        assertEquals(
            "from-the-build", System.getProperty(probe),
            "the value the build set must survive a test that overwrote it — this is the " +
                "`mezze.project.root` = /tmp/test_std case",
        )
        System.clearProperty(probe)
    }

    @Test
    fun `a property the test INTRODUCED is cleared, not left set`() {
        System.clearProperty(introduced)
        val guard = MezzeSystemPropertyIsolation()
        guard.snapshot()

        System.setProperty(introduced, "invented-by-a-test")
        guard.restore()

        assertNull(
            System.getProperty(introduced),
            "restoring only the snapshot would leave a NEW key behind; absence has to be " +
                "restored as absence",
        )
    }

    @Test
    fun `a property outside the mezze namespace is left alone`() {
        // The guard must not reach into JVM or JUnit properties — it owns one
        // namespace, and a broader sweep could fight the platform.
        val foreign = "not.mezze.__isolation_probe"
        System.clearProperty(foreign)
        val guard = MezzeSystemPropertyIsolation()
        guard.snapshot()

        System.setProperty(foreign, "set-by-a-test")
        guard.restore()

        assertEquals("set-by-a-test", System.getProperty(foreign), "non-mezze keys are not ours to restore")
        System.clearProperty(foreign)
    }

    @Test
    fun `the build's project root is the real repo, not a leftover`() {
        // The end-to-end assertion: whatever ran before this test in this JVM,
        // `mezze.project.root` is the repo the build pointed at. Under the leak
        // this read `/tmp/test_std`.
        val root = System.getProperty("mezze.project.root")
        assertEquals(
            true, root != null && java.io.File(root, "settings.gradle.kts").exists(),
            "mezze.project.root='$root' is not the mezze repo — a previous test in this JVM " +
                "changed it and the guard did not put it back",
        )
    }
}
