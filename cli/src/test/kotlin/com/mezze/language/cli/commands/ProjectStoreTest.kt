package com.mezze.language.cli.commands

import com.mezze.language.codebase.SQLiteMezzeStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * ProjectStore.open must invalidate + re-stamp a per-project cache that a
 * DIFFERENT mez binary wrote, so `check` / `exec` / `dev` / `build` never reuse
 * another compiler's IR after a toolchain switch (#44 — the guard that used to
 * live only in `build`).
 */
class ProjectStoreTest {

    @Test
    fun `open re-stamps mez_version when a different binary wrote the cache`(@TempDir tmp: Path) {
        val dir = tmp.toFile()

        // Seed a store as if some other toolchain had compiled here.
        SQLiteMezzeStore.openProject(dir).use { seed ->
            seed.transaction { seed.setMetadata("mez_version", "0.0.0-other+deadbeef") }
        }
        assertNotEquals("0.0.0-other+deadbeef", ProjectStore.binaryIdentity)

        // Opening via ProjectStore must detect the shift and re-stamp to us.
        ProjectStore.open(dir).use { store ->
            assertEquals(ProjectStore.binaryIdentity, store.getMetadata("mez_version"))
        }
    }

    @Test
    fun `open stamps a fresh project and is idempotent on a matching version`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        ProjectStore.open(dir).use { s1 ->
            assertEquals(ProjectStore.binaryIdentity, s1.getMetadata("mez_version"))
        }
        // Re-open with the same identity: still stamped, no throw.
        ProjectStore.open(dir).use { s2 ->
            assertEquals(ProjectStore.binaryIdentity, s2.getMetadata("mez_version"))
        }
    }
}
