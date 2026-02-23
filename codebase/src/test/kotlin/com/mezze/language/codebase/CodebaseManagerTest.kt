package com.mezze.language.codebase

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertIs

class CodebaseManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private var codebase: Codebase? = null

    @AfterEach
    fun cleanup() {
        codebase?.close()
    }

    @Test
    fun `inMemory returns SQLiteCodebase`() {
        codebase = CodebaseManager.inMemory()
        assertIs<SQLiteCodebase>(codebase)
    }

    @Test
    fun `forPath returns in-memory when not in project`() {
        // Create a file outside any project
        val scriptFile = File(tempDir.toFile(), "script.mz")
        scriptFile.writeText("let x = 1")

        codebase = CodebaseManager.forPath(scriptFile.toPath())
        assertNotNull(codebase)
        // Can't easily distinguish in-memory vs file-based externally,
        // but we can verify it works
    }

    @Test
    fun `forPath returns file-based when in project`() {
        // Create a project with Mezze.toml
        val projectDir = File(tempDir.toFile(), "myproject")
        projectDir.mkdirs()
        File(projectDir, "Mezze.toml").writeText("[package]\nname = \"test\"")

        val srcDir = File(projectDir, "src")
        srcDir.mkdirs()
        val appFile = File(srcDir, "app.mz")
        appFile.writeText("let main = {} -> {}")

        codebase = CodebaseManager.forPath(appFile.toPath())
        assertNotNull(codebase)

        // Verify .mez directory was created
        val mezDir = File(projectDir, ".mez")
        assertTrue(mezDir.exists(), ".mez directory should be created")
        assertTrue(File(mezDir, "codebase.db").exists(), "codebase.db should be created")
    }

    @Test
    fun `forProjectRoot creates codebase in project`() {
        val projectDir = File(tempDir.toFile(), "myproject2")
        projectDir.mkdirs()
        File(projectDir, "Mezze.toml").writeText("[package]\nname = \"test\"")

        codebase = CodebaseManager.forProjectRoot(projectDir)
        assertNotNull(codebase)

        assertTrue(CodebaseManager.hasCodebase(projectDir))
    }

    @Test
    fun `findProjectRoot finds Mezze toml`() {
        // Create nested directories
        val projectDir = File(tempDir.toFile(), "project")
        projectDir.mkdirs()
        File(projectDir, "Mezze.toml").writeText("[package]\nname = \"test\"")

        val srcDir = File(projectDir, "src")
        srcDir.mkdirs()
        val nestedDir = File(srcDir, "nested")
        nestedDir.mkdirs()

        // Find from nested directory
        val found = CodebaseManager.findProjectRoot(nestedDir)
        assertNotNull(found)
        assertEquals(projectDir.canonicalPath, found.canonicalPath)
    }

    @Test
    fun `findProjectRoot returns null when not in project`() {
        val randomDir = File(tempDir.toFile(), "random")
        randomDir.mkdirs()

        val found = CodebaseManager.findProjectRoot(randomDir)
        assertNull(found)
    }

    @Test
    fun `isInProject returns correct values`() {
        val projectDir = File(tempDir.toFile(), "project3")
        projectDir.mkdirs()
        File(projectDir, "Mezze.toml").writeText("[package]\nname = \"test\"")

        val srcFile = File(projectDir, "src/app.mz")
        srcFile.parentFile.mkdirs()
        srcFile.writeText("")

        val outsideFile = File(tempDir.toFile(), "outside.mz")
        outsideFile.writeText("")

        assertTrue(CodebaseManager.isInProject(srcFile))
        assertFalse(CodebaseManager.isInProject(outsideFile))
    }

    @Test
    fun `deleteCodebase removes database files`() {
        val projectDir = File(tempDir.toFile(), "project4")
        projectDir.mkdirs()
        File(projectDir, "Mezze.toml").writeText("[package]\nname = \"test\"")

        // Create codebase
        codebase = CodebaseManager.forProjectRoot(projectDir)
        codebase?.close()
        codebase = null

        assertTrue(CodebaseManager.hasCodebase(projectDir))

        // Delete it
        val deleted = CodebaseManager.deleteCodebase(projectDir)
        assertTrue(deleted)
        assertFalse(CodebaseManager.hasCodebase(projectDir))
    }

    @Test
    fun `getCodebasePath returns correct path`() {
        val projectDir = File(tempDir.toFile(), "project5")
        val expectedPath = File(projectDir, ".mez/codebase.db")

        val actualPath = CodebaseManager.getCodebasePath(projectDir)
        assertEquals(expectedPath.path, actualPath.path)
    }
}
