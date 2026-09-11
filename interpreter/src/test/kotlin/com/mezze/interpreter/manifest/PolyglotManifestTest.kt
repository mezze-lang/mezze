package com.mezze.interpreter.manifest

import com.mezze.interpreter.polyglot.PolyglotManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolyglotManifestTest {

    @Test
    fun `parses polyglot python section with path and options`() {
        val toml = """
            [package]
            name = "demo"
            version = "0.1.0"

            [polyglot.python]
            python_path = "./venv/lib/python3.12/site-packages"

            [polyglot.python.options]
            PosixModuleBackend = "java"
            WarnExperimentalFeatures = "false"
        """.trimIndent()

        val sections = PolyglotManifest.parse(toml)
        assertTrue(sections.pythonPresent, "[polyglot.python] should register as present")
        assertEquals("./venv/lib/python3.12/site-packages", sections.pythonPath)
        assertEquals("java", sections.pythonOptions["PosixModuleBackend"])
        assertEquals("false", sections.pythonOptions["WarnExperimentalFeatures"])
    }

    @Test
    fun `parses polyglot jvm classpath array`() {
        val toml = """
            [package]
            name = "demo"
            version = "0.1.0"

            [polyglot.jvm]
            classpath = ["libs/gson-2.10.jar", "libs/commons-lang3.jar"]
        """.trimIndent()

        val sections = PolyglotManifest.parse(toml)
        assertEquals(
            listOf("libs/gson-2.10.jar", "libs/commons-lang3.jar"),
            sections.jvmClasspath,
        )
    }

    @Test
    fun `absent polyglot section yields NONE`(@TempDir tmp: File) {
        val manifest = File(tmp, "Mezze.toml")
        manifest.writeText(
            """
            [package]
            name = "plain"
            version = "0.1.0"
            """.trimIndent()
        )
        val resolved = PolyglotManifest.load(tmp)
        assertEquals(false, resolved.enablePython)
        assertNull(resolved.pythonPath)
        assertTrue(resolved.pythonOptions.isEmpty())
        assertTrue(resolved.jvmClasspath.isEmpty())
    }

    @Test
    fun `python_path resolves relative to manifest directory`(@TempDir tmp: File) {
        val venv = File(tmp, "venv/site-packages").apply { mkdirs() }
        val manifest = File(tmp, "Mezze.toml")
        manifest.writeText(
            """
            [package]
            name = "demo"
            version = "0.1.0"

            [polyglot.python]
            python_path = "venv/site-packages"
            """.trimIndent()
        )
        val resolved = PolyglotManifest.load(tmp)
        assertTrue(resolved.enablePython)
        assertEquals(venv.canonicalPath, resolved.pythonPath)
    }

    @Test
    fun `absolute python_path passes through unchanged`(@TempDir tmp: File) {
        val abs = File(tmp, "some/absolute/site-packages").apply { mkdirs() }
        val manifest = File(tmp, "Mezze.toml")
        manifest.writeText(
            """
            [package]
            name = "demo"
            version = "0.1.0"

            [polyglot.python]
            python_path = "${abs.absolutePath}"
            """.trimIndent()
        )
        val resolved = PolyglotManifest.load(tmp)
        assertEquals(abs.canonicalPath, resolved.pythonPath)
    }

    @Test
    fun `jvm classpath entries resolve relative to manifest`(@TempDir tmp: File) {
        val libs = File(tmp, "libs").apply { mkdirs() }
        val jar = File(libs, "gson.jar").apply { writeBytes(byteArrayOf()) }
        val manifest = File(tmp, "Mezze.toml")
        manifest.writeText(
            """
            [package]
            name = "demo"
            version = "0.1.0"

            [polyglot.jvm]
            classpath = ["libs/gson.jar"]
            """.trimIndent()
        )
        val resolved = PolyglotManifest.load(tmp)
        assertEquals(listOf(jar.canonicalPath), resolved.jvmClasspath)
    }

    @Test
    fun `discover walks up to find manifest`(@TempDir tmp: File) {
        val manifest = File(tmp, "Mezze.toml").apply {
            writeText(
                """
                [package]
                name = "demo"
                version = "0.1.0"

                [polyglot.python]
                """.trimIndent()
            )
        }
        val nested = File(tmp, "src/deep/nested").apply { mkdirs() }
        val found = PolyglotManifest.discover(nested)
        assertNotNull(found)
        assertEquals(manifest.canonicalPath, found!!.canonicalPath)
    }

    @Test
    fun `presence-only python section enables without path`(@TempDir tmp: File) {
        File(tmp, "Mezze.toml").writeText(
            """
            [package]
            name = "demo"
            version = "0.1.0"

            [polyglot.python]
            """.trimIndent()
        )
        val resolved = PolyglotManifest.load(tmp)
        assertTrue(resolved.enablePython)
        assertNull(resolved.pythonPath)
        assertTrue(resolved.pythonOptions.isEmpty())
    }
}
