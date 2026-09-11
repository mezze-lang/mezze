package com.mezze.language.cli

import java.io.File

/**
 * Single source of truth for where the **running toolchain's** files live.
 *
 * The runtime jar / tool jars / native shell are looked up in a `lib/` dir that
 * is **self-located relative to this binary** — so a flat install
 * (`~/.mezze/bin/mez` → `~/.mezze/lib`) and a versioned install
 * (`~/.mezze/toolchains/<v>/bin/mez` → `~/.mezze/toolchains/<v>/lib`) both resolve
 * to *their own* lib without any version knowledge here. Falls back to the flat
 * `~/.mezze/lib` (honoring `MEZZE_HOME`) when self-location doesn't apply (dev/JVM
 * launcher), so existing behavior is unchanged.
 */
object MezzeHome {

    /** `~/.mezze`, or `MEZZE_HOME` if set. */
    val home: File =
        System.getenv("MEZZE_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".mezze")

    /** The `lib/` dir of the toolchain this binary belongs to. */
    val libDir: File by lazy {
        selfLib(runningExecutable()) ?: selfLib(codeSourceFile()) ?: File(home, "lib")
    }

    val runtimeJar: File get() = File(libDir, "mezze-runtime.jar")
    val toolsDir: File get() = File(libDir, "tools")
    val shellNative: File get() = File(libDir, "mez-shell-native")

    /**
     * Absolute path of the running `mez` executable, if discoverable. For an
     * installed native binary this is the binary itself (e.g. `~/.mezze/bin/mez`
     * or `~/.mezze/toolchains/<v>/bin/mez`); the toolchain proxy uses it to tell
     * whether it is running *as the proxy*. Null / a JVM path for dev launches,
     * which is fine — those never dispatch.
     */
    val executable: File? by lazy { runningExecutable() }

    /**
     * Given a `<root>/bin/<exe>` (or a code-source under `<root>/bin` or `<root>/lib`),
     * return `<root>/lib` **iff** it actually holds the runtime jar. Pure — unit-tested.
     */
    fun selfLib(from: File?): File? {
        val binDir = from?.parentFile ?: return null
        // <exe>/../lib  (bin/mez -> ../lib) and <exe>/  (jar sitting next to it) and <exe>/../ (already lib/)
        for (candidate in listOf(File(binDir.parentFile ?: binDir, "lib"), binDir)) {
            if (File(candidate, "mezze-runtime.jar").isFile) return candidate
        }
        return null
    }

    private fun runningExecutable(): File? =
        runCatching { ProcessHandle.current().info().command().orElse(null)?.let { File(it) } }.getOrNull()

    private fun codeSourceFile(): File? =
        runCatching {
            MezzeHome::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { File(it) }
        }.getOrNull()
}
