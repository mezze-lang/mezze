package com.mezze.language.typechecker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The same source is the same hashes, and the same stored bytes, in any process.
 *
 * Everything that addresses code by hash — a store shared between checkouts, a
 * node fetching a function another node names — rests on two machines agreeing
 * without talking. Inside one JVM that is easy to believe and impossible to
 * check: the two sides share a locale, a charset, a working directory, a thread
 * pool and every static counter. So this runs [IdentityDump] in two JVMs of
 * their own, as unlike each other as one machine allows, and requires the
 * output to be identical: every stdlib module's identities, and the stored
 * content of every declaration and lowered function of a program.
 */
class HashesAreTheSameEverywhereTest {

    private fun dump(workingDir: File, parsedAs: String, vararg jvmArgs: String): List<String> {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val errors = File.createTempFile("identity-dump", ".err")
        // Where this build's stdlib sources are — the one thing both must share.
        val root = System.getProperty("mezze.project.root") ?: error("mezze.project.root is not set for this test JVM")
        val process = ProcessBuilder(
            listOf(java, "-cp", System.getProperty("java.class.path"), "-Dmezze.project.root=$root") + jvmArgs +
                listOf(IdentityDump::class.java.name, parsedAs)
        ).directory(workingDir).redirectError(errors).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(280, TimeUnit.SECONDS), "the dump JVM did not finish")
        assertEquals(0, process.exitValue(), "the dump JVM failed:\n${errors.readText().take(4000)}")
        return output.lines().filter { it.isNotBlank() }
    }

    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    fun `two unlike JVMs compute the same identities and the same stored content`() {
        val here = File(".").absoluteFile
        val elsewhere = Files.createTempDirectory("mezze-elsewhere").toFile()
        try {
            val plain = dump(here, "app.mz")
            val unlike = dump(
                elsewhere, "/srv/another/checkout/src/app.mz",
                // Turkish casing (`"I".lowercase()` is not "i"), a one-byte default
                // charset, the far side of the date line, and one checking thread
                // instead of a pool — so type variables are numbered in another order.
                "-Duser.language=tr", "-Duser.country=TR",
                "-Dfile.encoding=ISO-8859-1", "-Dsun.jnu.encoding=ISO-8859-1",
                "-Duser.timezone=Pacific/Kiritimati",
                "-Djava.util.concurrent.ForkJoinPool.common.parallelism=1",
                "-XX:+UseSerialGC",
            )

            val ids = plain.count { it.startsWith("id ") }
            val typed = plain.count { it.startsWith("typed ") }
            val lowered = plain.count { it.startsWith("ir ") }
            assertTrue(ids >= 40 && typed >= 8 && lowered >= 20, "too little was compared: $ids modules, $typed typed, $lowered lowered")
            assertTrue(plain.none { it.endsWith(" none") }, "a stdlib module has no identity: ${plain.filter { it.endsWith(" none") }}")

            val differing = plain.zip(unlike).filter { (a, b) -> a != b }
            assertTrue(plain.size == unlike.size && differing.isEmpty()) {
                "the two JVMs disagree on ${differing.size} of ${plain.size} lines (${unlike.size} in the other):\n" +
                    differing.take(8).joinToString("\n") { (a, b) -> "  $a\n  $b" }
            }
        } finally {
            elsewhere.deleteRecursively()
        }
    }
}
