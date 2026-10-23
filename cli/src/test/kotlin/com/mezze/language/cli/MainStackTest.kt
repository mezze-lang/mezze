package com.mezze.language.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Checking recurses once per level of an expression, and `a + b + c + …` is one
 * level per term: on the JVM's 1 MB main stack a module summing a few hundred
 * terms overflowed. `mez` runs its commands on a stack of its own.
 */
class MainStackTest {

    @Test
    fun `a long sum checks on a 1 MB main stack`(@TempDir project: Path) {
        project.resolve("Mezze.toml").writeText(
            """
            [package]
            name = "stk"
            version = "0.1.0"

            [[app]]
            name = "stk"
            path = "src/app.mz"
            entry = "main"
            """.trimIndent(),
        )
        project.resolve("src").createDirectories()
        project.resolve("src/app.mz").writeText(
            "let total = {} -> " + List(2000) { "1" }.joinToString(" + ") + "\n\nlet main = {} -> {}\n",
        )

        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java, "-Xss1m", "-cp", System.getProperty("java.class.path"),
            "com.mezze.language.cli.MainKt", "check",
        )
            .directory(project.toFile())
            .redirectErrorStream(true)
            .also { it.environment()["MEZZE_HOME"] = project.resolve(".home").toString() }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(5, TimeUnit.MINUTES), "mez check did not finish")
        assertEquals(0, process.exitValue(), output)
        assertTrue("Check passed" in output, output)
    }
}
