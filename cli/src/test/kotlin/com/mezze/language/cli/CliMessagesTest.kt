package com.mezze.language.cli

import com.mezze.language.cli.commands.CliMessages
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Unit tests for the shared project-setup error messages used by dev/build/exec.
 * CliMessages is a pure formatter (color=false → plain text), so we assert the
 * wording + actionable hints without stdout or a TTY.
 */
class CliMessagesTest {

    private fun msg(color: Boolean = false) = CliMessages(color = color)
    private val ESC = 27.toChar().toString()  // ANSI escape (byte-safe in source)

    @Test
    fun `missing manifest suggests mez init and mez exec`() {
        val m = msg().noManifest()
        assertTrue(m.contains("Mezze.toml"), "should name the missing file")
        assertTrue(m.contains("mez init"), "should suggest mez init")
        assertTrue(m.contains("mez exec"), "should suggest single-file exec")
    }

    @Test
    fun `bad manifest includes the parser detail`() {
        val m = msg().badManifest("expected '=' at line 3")
        assertTrue(m.contains("Mezze.toml"))
        assertTrue(m.contains("expected '=' at line 3"), "should surface the underlying detail")
    }

    @Test
    fun `bad manifest tolerates a null detail`() {
        val m = msg().badManifest(null)
        assertTrue(m.contains("Mezze.toml"))
        assertFalse(m.contains("null"), "must not print the literal word null")
    }

    @Test
    fun `no app shows a copy-pasteable app stanza`() {
        val m = msg().noApp("myapp")
        assertTrue(m.contains("[[app]]"), "should show the section to add")
        assertTrue(m.contains("name = \"myapp\""), "should prefill the package name")
        assertTrue(m.contains("path = \"src/app.mz\""), "should suggest a default path")
    }

    @Test
    fun `app not found lists the available apps`() {
        val m = msg().appNotFound("web", listOf("cli", "server"))
        assertTrue(m.contains("web"), "should name the missing app")
        assertTrue(m.contains("cli") && m.contains("server"), "should list what IS available")
    }

    @Test
    fun `app file not found names the path and points at the config`() {
        val m = msg().appFileNotFound(File("src/missing.mz"))
        assertTrue(m.contains("src/missing.mz"), "should name the missing file")
        assertTrue(m.contains("path"), "should point at the [[app]] path field")
    }

    @Test
    fun `plain mode emits no ANSI escapes`() {
        val all = listOf(
            msg().noManifest(), msg().badManifest("x"), msg().noApp("a"),
            msg().appNotFound("a", listOf("b")), msg().appFileNotFound(File("f")),
        ).joinToString("\n")
        assertFalse(all.contains(ESC), "color=false must produce zero ANSI escapes")
    }

    @Test
    fun `color mode emits ANSI escapes`() {
        assertTrue(msg(color = true).noManifest().contains(ESC), "color=true should style output")
    }

    // ── build summary ──────────────────────────────────────────────────

    @Test
    fun `humanBytes scales B KB MB GB`() {
        val m = msg()
        assertTrue(m.humanBytes(512).endsWith(" B"), "small → bytes")
        assertTrue(m.humanBytes(3_400).endsWith(" KB"), "kilo → KB")
        assertTrue(m.humanBytes(18L * 1024 * 1024).endsWith(" MB"), "mega → MB")
        assertTrue(m.humanBytes(2L * 1024 * 1024 * 1024).endsWith(" GB"), "giga → GB")
    }

    @Test
    fun `build summary shows artifacts with sizes, timing, and a run block`() {
        val m = msg().buildSummary(
            appName = "devproj",
            durationLabel = "4.1s",
            artifacts = listOf(
                Triple("jar", "./.build/devproj", 3_400_000L),
                Triple("native", "./.build/devproj-native", 18_000_000L),
            ),
        )
        assertTrue(m.contains("devproj"), "names the app")
        assertTrue(m.contains("built in 4.1s"), "shows real timing (not '0s')")
        assertTrue(m.contains("MB"), "reports artifact size")
        assertTrue(m.contains("Run"), "has a Run block")
        assertTrue(m.contains("./.build/devproj-native"), "lists the native run command")
        assertTrue(m.contains("native, no JVM"), "annotates the native artifact")
    }

    @Test
    fun `build summary surfaces warnings when present`() {
        val m = msg().buildSummary(
            appName = "x", durationLabel = "1s",
            artifacts = listOf(Triple("jar", "./x", 100L)),
            warningCount = 3,
        )
        assertTrue(m.contains("3 warnings"), "should report the warning count")
        val none = msg().buildSummary("x", "1s", listOf(Triple("jar", "./x", 100L)), warningCount = 0)
        assertFalse(none.contains("warning"), "no warning line when there are none")
    }

    @Test
    fun `build failed shows the detail`() {
        val m = msg().buildFailed("disk full")
        assertTrue(m.contains("build failed"))
        assertTrue(m.contains("disk full"))
    }

    // ── general error boundary (F1) ─────────────────────────────────────

    @Test
    fun `general error shows message and optional hint`() {
        val m = msg().generalError("something broke", hint = "try turning it off and on")
        assertTrue(m.contains("something broke"), "shows the message")
        assertTrue(m.contains("try turning it off and on"), "shows the hint")
    }

    @Test
    fun `general error offers the verbose trace hint by default`() {
        val m = msg().generalError("boom", hint = null, showTraceHint = true)
        assertTrue(m.contains("--verbose"), "points the user at --verbose for the trace")
    }

    @Test
    fun `general error omits the verbose hint when already verbose`() {
        val m = msg().generalError("boom", hint = null, showTraceHint = false)
        assertFalse(m.contains("--verbose"), "no trace hint when already in verbose mode")
    }

    @Test
    fun `general error never prints a raw stack trace itself`() {
        // The renderer must NOT embed a trace; the caller prints it separately
        // only under --verbose. A trace's tell-tale is "\tat " frame lines.
        val m = msg().generalError("boom", hint = "fix it")
        assertFalse(m.contains("\tat "), "generalError must not contain stack frames")
    }
}
