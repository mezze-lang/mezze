package com.mezze.language.cli

import com.mezze.language.cli.commands.DevConsole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the `mez dev` watch-mode chrome. DevConsole is a pure
 * formatter (color=false -> plain text), so we assert exact layout without a
 * subprocess or a TTY.
 *
 * Design invariants under test (locked with the user):
 *  - The rule-with-status SEPARATOR precedes each run's output.
 *  - A reload separator folds in the PREVIOUS run's exit status.
 *  - Program logs are never boxed/wrapped by a rule -- the separator only ever
 *    appears BEFORE a run, so continuous server logs stay uninterrupted.
 */
class DevConsoleTest {

    private val ESC = ""

    private fun console(color: Boolean = false) =
        DevConsole(appName = "devproj", color = color, width = 44)

    @Test
    fun `banner names the app and reports compile time + watched dir`() {
        val lines = console().banner(watchedDir = "src", compileSeconds = 0.33)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("mez dev") && lines[0].contains("devproj"),
            "banner line 1 should name the tool + app, got: ${lines[0]}")
        assertTrue(lines[1].contains("compiled in") && lines[1].contains("watching src"),
            "banner line 2 should report compile time + watched dir, got: ${lines[1]}")
    }

    @Test
    fun `sub-second compile time renders as milliseconds`() {
        val lines = console().banner("src", 0.08)
        assertTrue(lines[1].contains("80ms"), "0.08s should render as 80ms, got: ${lines[1]}")
    }

    @Test
    fun `multi-second compile time renders as seconds`() {
        val lines = console().banner("src", 2.5)
        assertTrue(lines[1].contains("2.50s"), "2.5s should render as 2.50s, got: ${lines[1]}")
    }

    @Test
    fun `first-run separator has a rule and the running label but no prior status`() {
        val sep = console().separator("running · ^C to stop")
        assertTrue(sep.contains("─"), "separator should contain a horizontal rule")
        assertTrue(sep.contains("running · ^C to stop"), "should carry the run label")
        assertFalse(sep.contains("prev exited"), "the first run has no prior status")
    }

    @Test
    fun `reload separator folds in the previous run's exit status`() {
        val c = console()
        val sep = c.separator(
            label = "reloaded app.mz · 90ms",
            priorStatus = c.priorStatus(DevConsole.RunResult.Exited(0)),
        )
        assertTrue(sep.contains("prev exited 0"), "should fold in the previous exit, got: $sep")
        assertTrue(sep.contains("reloaded app.mz · 90ms"), "should carry the reload label, got: $sep")
        // The prior status comes BEFORE the current-run label on the one line.
        assertTrue(sep.indexOf("prev exited 0") < sep.indexOf("reloaded app.mz"),
            "prior status should precede the new-run label, got: $sep")
    }

    @Test
    fun `a crashed previous run is reported in the next separator`() {
        val c = console()
        assertTrue(c.priorStatus(DevConsole.RunResult.Crashed).contains("prev crashed"))
    }

    @Test
    fun `plain mode emits no ANSI escapes`() {
        val c = console(color = false)
        val all = (c.banner("src", 0.1) + c.separator("running", c.priorStatus(DevConsole.RunResult.Exited(0))))
            .joinToString("\n")
        assertFalse(all.contains(ESC), "color=false must produce zero ANSI escapes, got: $all")
    }

    @Test
    fun `color mode emits ANSI escapes`() {
        val c = console(color = true)
        val sep = c.separator("running · ^C to stop")
        assertTrue(sep.contains(ESC), "color=true should emit ANSI escapes")
    }

    @Test
    fun `separator width stays bounded even with a long label`() {
        // A very long label shouldn't produce a negative-length rule / crash.
        val sep = console().separator("reloaded a-very-long-filename-that-exceeds-the-rule-width.mz · 1.23s")
        assertTrue(sep.contains("─"), "should still render at least a minimal rule")
        assertTrue(sep.contains("reloaded"), "label preserved")
    }
}
