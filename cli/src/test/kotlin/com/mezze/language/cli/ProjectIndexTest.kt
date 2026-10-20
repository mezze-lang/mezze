package com.mezze.language.cli

import com.mezze.language.cli.commands.ProjectIndex
import com.mezze.language.store.InMemoryMezzeStore
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * The whole project, not just what the entry imports, is what a codebase
 * question sees (introspection §9, T5).
 */
class ProjectIndexTest {

    @TempDir lateinit var dir: File

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        File(dir, "Mezze.toml").writeText(
            """
            [package]
            name = "my-app"
            version = "0.1.0"

            [[app]]
            name = "my-app"
            path = "src/app.mz"
            entry = "main"
            """.trimIndent()
        )
        File(dir, "src/tools").mkdirs()
        File(dir, "src/app.mz").writeText("let main = {} -> 1\n")
        File(dir, "src/tools/lonely.mz").writeText("pub let lonely: { n: Int } -> Int = { n } -> n + 1\n")
    }

    @Test
    fun `a module nothing imports is indexed, under its path`() {
        val store = InMemoryMezzeStore()
        ProjectIndex.ensure(store, dir)
        assertEquals(listOf("lonely"), store.getDeclsForModule("tools::lonely").map { it.declName })
    }

    @Test
    fun `the entry file is the package's module, as a build names it`() {
        val store = InMemoryMezzeStore()
        ProjectIndex.ensure(store, dir)
        assertEquals(listOf("main"), store.getDeclsForModule("my_app").map { it.declName })
        assertTrue(store.getDeclsForModule("app").isEmpty(), "the entry indexed twice, under its file name too")
    }

    @Test
    fun `a module that does not type-check is named, not silently left out`() {
        File(dir, "src/broken.mz").writeText("pub let broken: Int = \"not an int\"\n")
        val e = assertThrows<IllegalStateException> { ProjectIndex.ensure(InMemoryMezzeStore(), dir) }
        assertTrue(e.message.orEmpty().contains("src/broken.mz"), "got: ${e.message}")
    }
}
