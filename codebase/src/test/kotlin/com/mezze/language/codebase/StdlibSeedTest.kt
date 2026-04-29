package com.mezze.language.codebase

import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File

class StdlibSeedTest {

    private lateinit var target: SQLiteMezzeStore
    private lateinit var seedFile: File

    @BeforeEach
    fun setUp() {
        target = SQLiteMezzeStore.inMemory()

        // Build a tiny seed sqlite that mirrors the real stdlib seed shape:
        // a few stdlib rows + one user row (which must NOT be copied).
        seedFile = File.createTempFile("stdlib-seed-test", ".sqlite")
        seedFile.deleteOnExit()
        val seed = SQLiteMezzeStore.forFile(seedFile)
        try {
            val h = Hash("a".repeat(64))
            seed.transaction {
                seed.putModule("std::int", null, "sh1", ModuleCategory.STDLIB)
                seed.putBlob(h, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
                seed.registerName("std::int", "add", h)
                seed.putDecl("std::int", "add", DeclKind.LET, "shD", h, 1, 1, 1, 10)

                // User row that should NOT be copied across.
                seed.putModule("watchtest", null, "sh2", ModuleCategory.USER)
                seed.setMetadata("stdlib_version", "2")
            }
        } finally {
            seed.close()
        }
    }

    @AfterEach
    fun tearDown() {
        target.close()
        seedFile.delete()
    }

    @Test
    fun `seedIfNeeded copies stdlib rows but not user rows`() {
        // Force the locator to hit our test file via a system property
        // (StdlibSeed.locateSeed checks the legacy path first; we redirect
        // it to our temp file for this test).
        System.setProperty("user.home", seedFile.parentFile.absolutePath)
        val previousUserHome = System.getProperty("user.home")
        try {
            // Mirror the legacy path we hit in production: seed lives at
            // <user.home>/.mezze/cache/stdlib.sqlite.
            val mezzeCache = File(seedFile.parentFile, ".mezze/cache")
            mezzeCache.mkdirs()
            val staged = File(mezzeCache, "stdlib.sqlite")
            seedFile.copyTo(staged, overwrite = true)

            val didSeed = StdlibSeed.seedIfNeeded(target)
            assertTrue(didSeed, "seedIfNeeded should report it ran")

            val modules = target.getAllModules()
            val stdlibModules = modules.filter { it.category == ModuleCategory.STDLIB }
            assertEquals(1, stdlibModules.size)
            assertEquals("std::int", stdlibModules[0].path)
            // The seed's `watchtest` (user) row must not have crossed over.
            assertEquals(0, modules.count { it.category == ModuleCategory.USER })

            // Names + decls + blob followed.
            assertNotNull(target.resolveName("std::int", "add"))
            assertEquals(1, target.getDeclsForModule("std::int").size)
            assertNotNull(target.getBlob(Hash("a".repeat(64))))

            // Idempotent: second call returns false without changes.
            assertFalse(StdlibSeed.seedIfNeeded(target))

            staged.delete()
        } finally {
            System.setProperty("user.home", previousUserHome)
        }
    }
}
