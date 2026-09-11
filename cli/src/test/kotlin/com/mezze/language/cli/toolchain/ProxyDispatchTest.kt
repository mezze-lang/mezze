package com.mezze.language.cli.toolchain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * The pure proxy decision: given the running executable, the home layout, cwd
 * pins, and env, decide whether to run in-process, re-exec a toolchain binary,
 * or fail. Exercises the rustup precedence end to end (resolver + filesystem).
 */
class ProxyDispatchTest {

    private fun homeWith(root: File, vararg versions: String): File {
        versions.forEach { v ->
            File(root, "toolchains/$v/bin").mkdirs()
            File(root, "toolchains/$v/bin/mez").apply { writeText("stub"); setExecutable(true) }
        }
        return root
    }

    private fun proxy(home: File) = File(home, "bin/mez")

    @Test
    fun `a non-proxy executable always runs in-process`(@TempDir tmp: Path) {
        val home = homeWith(tmp.toFile(), "0.1.0")
        // self is a toolchain's own binary, not the proxy path
        val self = File(home, "toolchains/0.1.0/bin/mez")
        val action = ProxyDispatch.decide(self, home, tmp.toFile(), null, listOf("check"), ToolchainPaths(home))
        assertInstanceOf(ProxyDispatch.Action.RunInProcess::class.java, action)
    }

    @Test
    fun `a Windows-style mez_exe at the proxy path is recognized as the proxy`(@TempDir tmp: Path) {
        // Windows layout: the only executable is mez.exe (no extension-less mez).
        val home = tmp.toFile()
        File(home, "toolchains/0.2.0/bin").mkdirs()
        File(home, "toolchains/0.2.0/bin/mez.exe").apply { writeText("stub"); setExecutable(true) }
        File(home, "bin").mkdirs()
        val self = File(home, "bin/mez.exe")   // proxy invoked as mez.exe
        val action = ProxyDispatch.decide(self, home, home, null, listOf("check"), ToolchainPaths(home))
        val re = assertInstanceOf(ProxyDispatch.Action.Reexec::class.java, action)
        assertEquals(File(home, "toolchains/0.2.0/bin/mez.exe").canonicalFile, re.target.canonicalFile)
    }

    @Test
    fun `null self (dev or JVM launch) runs in-process`(@TempDir tmp: Path) {
        val home = homeWith(tmp.toFile(), "0.1.0")
        val action = ProxyDispatch.decide(null, home, tmp.toFile(), null, listOf("check"), ToolchainPaths(home))
        assertInstanceOf(ProxyDispatch.Action.RunInProcess::class.java, action)
    }

    @Test
    fun `proxy with no pins re-execs the latest installed`(@TempDir tmp: Path) {
        val home = homeWith(tmp.toFile(), "0.1.0", "0.2.0", "0.1.5")
        val action = ProxyDispatch.decide(proxy(home), home, tmp.toFile(), null, listOf("build"), ToolchainPaths(home))
        val re = assertInstanceOf(ProxyDispatch.Action.Reexec::class.java, action)
        assertEquals(File(home, "toolchains/0.2.0/bin/mez").canonicalFile, re.target.canonicalFile)
        assertEquals(listOf("build"), re.args)
    }

    @Test
    fun `explicit +version wins and is stripped from forwarded args`(@TempDir tmp: Path) {
        val home = homeWith(tmp.toFile(), "0.1.0", "0.2.0")
        val action = ProxyDispatch.decide(
            proxy(home), home, tmp.toFile(),
            env = "0.2.0", // even with env set, +ver wins
            args = listOf("+0.1.0", "exec", "app.mz"),
            paths = ToolchainPaths(home),
        )
        val re = assertInstanceOf(ProxyDispatch.Action.Reexec::class.java, action)
        assertEquals(File(home, "toolchains/0.1.0/bin/mez").canonicalFile, re.target.canonicalFile)
        assertEquals(listOf("exec", "app.mz"), re.args)
    }

    @Test
    fun `MEZ_VERSION env beats project pin and default`(@TempDir tmp: Path) {
        val home = homeWith(tmp.toFile(), "0.1.0", "0.2.0")
        ToolchainPaths(home).setDefault("0.1.0")
        val cwd = File(tmp.toFile(), "proj").apply { mkdirs() }
        File(cwd, "Mezze.toml").writeText("[toolchain]\nchannel = \"0.1.0\"\n")
        val action = ProxyDispatch.decide(proxy(home), home, cwd, env = "0.2.0", args = listOf("check"), paths = ToolchainPaths(home))
        val re = assertInstanceOf(ProxyDispatch.Action.Reexec::class.java, action)
        assertEquals(File(home, "toolchains/0.2.0/bin/mez").canonicalFile, re.target.canonicalFile)
    }

    @Test
    fun `project toolchain channel pins the version - track resolves to newest on it`(@TempDir tmp: Path) {
        val home = homeWith(tmp.toFile(), "0.1.0", "0.1.7", "0.2.0")
        val cwd = File(tmp.toFile(), "proj").apply { mkdirs() }
        File(cwd, "Mezze.toml").writeText("name = \"x\"\n[toolchain]\nchannel = \"0.1\"\n")
        val action = ProxyDispatch.decide(proxy(home), home, cwd, null, listOf("check"), ToolchainPaths(home))
        val re = assertInstanceOf(ProxyDispatch.Action.Reexec::class.java, action)
        assertEquals(File(home, "toolchains/0.1.7/bin/mez").canonicalFile, re.target.canonicalFile)
    }

    @Test
    fun `nearest Mezze_toml without a channel falls through to global default`(@TempDir tmp: Path) {
        val home = homeWith(tmp.toFile(), "0.1.0", "0.2.0")
        ToolchainPaths(home).setDefault("0.1.0")
        val cwd = File(tmp.toFile(), "proj").apply { mkdirs() }
        File(cwd, "Mezze.toml").writeText("name = \"x\"\n") // no [toolchain]
        val action = ProxyDispatch.decide(proxy(home), home, cwd, null, listOf("check"), ToolchainPaths(home))
        val re = assertInstanceOf(ProxyDispatch.Action.Reexec::class.java, action)
        assertEquals(File(home, "toolchains/0.1.0/bin/mez").canonicalFile, re.target.canonicalFile)
    }

    @Test
    fun `requesting an uninstalled version fails with a helpful message`(@TempDir tmp: Path) {
        val home = homeWith(tmp.toFile(), "0.1.0")
        val action = ProxyDispatch.decide(proxy(home), home, tmp.toFile(), env = "9.9.9", args = listOf("check"), paths = ToolchainPaths(home))
        val fail = assertInstanceOf(ProxyDispatch.Action.Fail::class.java, action)
        assertEquals(1, fail.code)
        assertTrue(fail.message.contains("9.9.9"))
        assertTrue(fail.message.contains("not installed"))
    }

    @Test
    fun `toolchain management runs in the proxy, never dispatched`(@TempDir tmp: Path) {
        // Even with a broken default pointing at an uninstalled version, `mez
        // toolchain …` must be handled in-process so it can be fixed.
        val home = homeWith(tmp.toFile(), "0.1.0")
        ToolchainPaths(home).setDefault("9.9.9")
        val action = ProxyDispatch.decide(proxy(home), home, tmp.toFile(), null, listOf("toolchain", "default", "0.1.0"), ToolchainPaths(home))
        assertInstanceOf(ProxyDispatch.Action.RunInProcess::class.java, action)
    }

    @Test
    fun `no toolchains installed at all fails`(@TempDir tmp: Path) {
        val home = tmp.toFile() // no toolchains/ dir
        File(home, "bin").mkdirs()
        val action = ProxyDispatch.decide(proxy(home), home, tmp.toFile(), null, listOf("check"), ToolchainPaths(home))
        val fail = assertInstanceOf(ProxyDispatch.Action.Fail::class.java, action)
        assertEquals(1, fail.code)
        assertTrue(fail.message.contains("no toolchains installed"))
    }

    @Test
    fun `resolved-but-missing binary fails rather than silently running the proxy`(@TempDir tmp: Path) {
        val home = tmp.toFile()
        File(home, "bin").mkdirs()
        File(home, "toolchains/0.1.0").mkdirs() // dir exists, but no bin/mez inside
        val action = ProxyDispatch.decide(proxy(home), home, tmp.toFile(), null, listOf("check"), ToolchainPaths(home))
        val fail = assertInstanceOf(ProxyDispatch.Action.Fail::class.java, action)
        assertEquals(1, fail.code)
        assertTrue(fail.message.contains("binary is missing"))
    }
}
