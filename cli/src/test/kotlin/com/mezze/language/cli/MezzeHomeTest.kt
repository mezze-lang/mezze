package com.mezze.language.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * `MezzeHome.selfLib` locates a toolchain's `lib/` relative to its binary — the
 * mechanism that lets both `~/.mezze/bin/mez` and `~/.mezze/toolchains/<v>/bin/mez`
 * find *their own* runtime jar with no version knowledge.
 */
class MezzeHomeTest {

    @Test
    fun `finds sibling lib that holds the runtime jar`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        File(root, "bin").mkdirs()
        File(root, "lib").mkdirs()
        File(root, "lib/mezze-runtime.jar").writeText("stub")
        assertEquals(
            File(root, "lib").canonicalFile,
            MezzeHome.selfLib(File(root, "bin/mez"))?.canonicalFile,
        )
    }

    @Test
    fun `finds the jar sitting next to the binary`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        File(root, "bin").mkdirs()
        File(root, "bin/mezze-runtime.jar").writeText("stub")
        assertEquals(
            File(root, "bin").canonicalFile,
            MezzeHome.selfLib(File(root, "bin/mez"))?.canonicalFile,
        )
    }

    @Test
    fun `returns null when no runtime jar is present`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        File(root, "bin").mkdirs()
        File(root, "lib").mkdirs()
        assertNull(MezzeHome.selfLib(File(root, "bin/mez")))
    }

    @Test
    fun `null input is null`() {
        assertNull(MezzeHome.selfLib(null))
    }
}
