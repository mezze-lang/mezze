package com.mezze.language.cli.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A check with nothing changed since the last that passed prints what that one
 * printed — and any change to what it read is checked, never replayed.
 */
class CheckReplayTest {

    @TempDir
    lateinit var project: Path

    private fun write(path: String, text: String) {
        project.resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent() + "\n")
    }

    private fun mez(vararg args: String): Pair<Int, String> {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(listOf(java, "-cp", System.getProperty("java.class.path"), "com.mezze.language.cli.MainKt") + args)
            .directory(project.toFile())
            .redirectErrorStream(true)
            .also { it.environment()["MEZZE_HOME"] = project.resolve(".home").toString() }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "mez did not finish" }
        return process.exitValue() to output
    }

    /** The output without its last line, which carries the time taken. */
    private fun body(output: String) = output.trimEnd().lines().dropLast(1).joinToString("\n")

    @Test
    fun `an unchanged project prints the same, and a change is checked`() {
        write("Mezze.toml", """
            [package]
            name = "replay"
            version = "0.1.0"

            [[app]]
            name = "replay"
            path = "src/app.mz"
            entry = "main"
        """)
        write("src/lib.mz", """
            pub let double = { n: Int } -> n * 2
        """)
        write("src/app.mz", """
            use lib::{ double }

            let four = double { n = 2 }

            let main = {} -> {}
        """)

        val (first, out1) = mez("check")
        assertEquals(0, first, out1)
        val (second, out2) = mez("check")
        assertEquals(0, second, out2)
        assertEquals(body(out1), body(out2), "the replay prints what the check printed")
        assertTrue("four" in out2, out2)

        // An imported module now fails to check: a replay would pass it.
        write("src/lib.mz", """
            pub let double = { n: Int } -> n * "two"
        """)
        val (third, out3) = mez("check")
        assertNotEquals(0, third, "a changed module is checked, not replayed: $out3")
    }
}
