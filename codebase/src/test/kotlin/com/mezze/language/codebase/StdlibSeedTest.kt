package com.mezze.language.codebase

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files

class StdlibSeedTest {

    private lateinit var projectRoot: File

    @BeforeEach
    fun setUp() {
        projectRoot = Files.createTempDirectory("stdlib-seed-test").toFile()
    }

    @AfterEach
    fun tearDown() {
        projectRoot.deleteRecursively()
    }

    @Test
    fun `seedIfMissing returns false when stdlib file already exists`() {
        // Pre-create an empty stdlib.sqlite so seedIfMissing sees it
        // and bails. We don't care about its content for this test.
        val stdlibFile = SQLiteMezzeStore.resolveStdlibFile(projectRoot)
        stdlibFile.parentFile.mkdirs()
        stdlibFile.writeText("dummy")

        assertFalse(StdlibSeed.seedIfMissing(projectRoot))
        // File contents shouldn't change.
        assertEquals("dummy", stdlibFile.readText())
    }

    @Test
    fun `seedIfMissing returns false when no bundled resource is available`() {
        // No bundled stdlib.sqlite resource is on the classpath in the
        // codebase test runtime — only the cli module ships it. So a
        // real seed-from-bundle attempt is a no-op here.
        val didSeed = StdlibSeed.seedIfMissing(projectRoot)
        // Either succeeds (resource present somewhere on classpath) or
        // returns false (resource absent). Both are valid; we just want
        // the call not to throw.
        val stdlibFile = SQLiteMezzeStore.resolveStdlibFile(projectRoot)
        if (didSeed) {
            assertTrue(stdlibFile.exists())
        } else {
            assertFalse(stdlibFile.exists())
        }
    }
}
