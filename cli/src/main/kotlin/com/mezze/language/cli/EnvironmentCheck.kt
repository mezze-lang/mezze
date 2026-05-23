package com.mezze.language.cli

import com.mezze.language.cli.commands.NativeImageRunner
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for "is the host system set up to run / build
 * Mezze code". Used by:
 *
 *   - `mez init`               info-only preflight after project creation
 *   - `mez dev` / `mez exec`   exit with install message if Java missing
 *   - `mez build --native`     exit with install message if Java or
 *                              native-image missing
 *
 * What "set up" means in practice:
 *
 *   - A Java executable, 21+ (Mezze runtime needs Loom virtual threads).
 *   - Ideally GraalVM (Truffle JIT activates → 3-10× faster hot loops).
 *     Non-GraalVM Java still runs everything, just slower.
 *   - `native-image` on PATH (only for `mez build --native`).
 */
object EnvironmentCheck {

    sealed class JavaStatus {
        object NotFound : JavaStatus()
        data class TooOld(val home: File, val version: String) : JavaStatus()
        data class Probed(val home: File, val version: String, val majorVersion: Int, val isGraal: Boolean) : JavaStatus()
        data class ProbeFailed(val home: File, val reason: String) : JavaStatus()
    }

    sealed class NativeImageStatus {
        object NotFound : NativeImageStatus()
        data class Found(val path: File) : NativeImageStatus()
    }

    data class Report(val java: JavaStatus, val nativeImage: NativeImageStatus)

    /** Probe both Java and native-image. Total cost ≈ one `java -version`. */
    fun probe(): Report = Report(probeJava(), probeNativeImage())

    private fun probeJava(): JavaStatus {
        val java = JvmLauncher.findJava() ?: return JavaStatus.NotFound
        val home = java.parentFile?.parentFile ?: java
        // `java -version` prints to stderr (history: Sun's original choice).
        val proc = try {
            ProcessBuilder(java.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return JavaStatus.ProbeFailed(home, e.message ?: e.javaClass.simpleName)
        }
        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(5, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return JavaStatus.ProbeFailed(home, "`java -version` timed out")
        }
        // Parse: lines like `openjdk version "24.0.1" 2025-04-15` or
        // `java version "21.0.2" 2024-01-16`. Major version is the part
        // before the first dot (or the whole string for `"21"`).
        val versionMatch = Regex("""version "([^"]+)"""").find(output)
            ?: return JavaStatus.ProbeFailed(home, "couldn't parse `java -version` output")
        val versionStr = versionMatch.groupValues[1]
        val major = parseMajor(versionStr) ?: return JavaStatus.ProbeFailed(home, "couldn't parse major version from '$versionStr'")
        if (major < 21) return JavaStatus.TooOld(home, versionStr)
        val isGraal = output.contains("GraalVM", ignoreCase = true)
        return JavaStatus.Probed(home, versionStr, major, isGraal)
    }

    private fun parseMajor(version: String): Int? {
        // "24.0.1" → 24, "21" → 21, "1.8.0_352" → 8 (legacy form).
        val first = version.substringBefore('.').toIntOrNull() ?: return null
        if (first == 1) return version.substringAfter('.').substringBefore('.').toIntOrNull()
        return first
    }

    private fun probeNativeImage(): NativeImageStatus {
        val ni = NativeImageRunner.locate() ?: return NativeImageStatus.NotFound
        return NativeImageStatus.Found(ni)
    }

    /**
     * Short, two-or-three-line block suitable for the tail of `mez init`.
     * Returns null if both Java and native-image are good — nothing to say.
     */
    fun shortReport(report: Report = probe()): String? {
        val javaLine = when (val j = report.java) {
            is JavaStatus.Probed -> when {
                j.isGraal -> null  // ideal, stay quiet
                else -> "  ℹ️  Java ${j.version} found (non-GraalVM). Code will run; install GraalVM for JIT speedup."
            }
            is JavaStatus.TooOld -> "  ⚠️  Java ${j.version} is too old. Mezze needs JDK 21+ (Loom virtual threads)."
            is JavaStatus.ProbeFailed -> "  ⚠️  Java found at ${j.home} but couldn't probe (${j.reason})."
            JavaStatus.NotFound -> "  ⚠️  Java not found. `mez dev` / `mez build` will fail until you install GraalVM 21+."
        }
        val niLine = when (report.nativeImage) {
            is NativeImageStatus.Found -> null  // ideal, stay quiet
            NativeImageStatus.NotFound -> "  ℹ️  native-image not installed — `mez build --native` will fail. Bundled with GraalVM."
        }
        val installLine = if (javaLine != null && report.java !is JavaStatus.Probed) {
            "     → brew install --cask graalvm-jdk   |   sdk install java 24-graal   |   https://www.graalvm.org/downloads/"
        } else null
        val lines = listOfNotNull(javaLine, installLine, niLine)
        if (lines.isEmpty()) return null
        return buildString {
            appendLine()
            appendLine("🔧 Environment check:")
            lines.forEach { appendLine(it) }
        }.trimEnd()
    }

    /**
     * Long-form report — shown by `mez dev` / `mez build --native` when
     * the user's toolchain is missing the bits they need to run.
     */
    fun longReport(report: Report = probe()): String = buildString {
        appendLine("Mezze environment check")
        appendLine("=======================")
        appendLine()
        when (val j = report.java) {
            is JavaStatus.Probed -> {
                val flavor = if (j.isGraal) "Oracle GraalVM / GraalVM CE" else "non-GraalVM JDK"
                appendLine("✅ Java ${j.version} ($flavor)")
                appendLine("   Home:  ${j.home}")
                if (!j.isGraal) {
                    appendLine()
                    appendLine("   ℹ️  This JDK works for `mez dev` and `mez build`, but Truffle's")
                    appendLine("      partial-evaluation JIT only activates on GraalVM. Hot loops")
                    appendLine("      run 3-10× faster on GraalVM. Install:")
                    appendLine("        brew install --cask graalvm-jdk        # macOS")
                    appendLine("        sdk install java 24-graal              # sdkman")
                    appendLine("        https://www.graalvm.org/downloads/")
                }
            }
            is JavaStatus.TooOld -> {
                appendLine("❌ Java ${j.version} is too old (need 21+ for Loom virtual threads)")
                appendLine("   Home:  ${j.home}")
                appendLine()
                appendLine("   Install a newer JDK:")
                appendLine("     brew install --cask graalvm-jdk        # macOS, recommended")
                appendLine("     sdk install java 24-graal              # sdkman")
                appendLine("     https://www.graalvm.org/downloads/")
            }
            is JavaStatus.ProbeFailed -> {
                appendLine("⚠️  Java found at ${j.home} but couldn't probe version")
                appendLine("   Reason: ${j.reason}")
                appendLine("   Try running `${j.home}/bin/java -version` manually.")
            }
            JavaStatus.NotFound -> {
                appendLine("❌ Java not found")
                appendLine()
                appendLine("   Mezze needs JDK 21+ to run code (`mez dev`, `mez build`).")
                appendLine("   GraalVM is recommended for Truffle JIT speedup.")
                appendLine()
                appendLine("   Install:")
                appendLine("     brew install --cask graalvm-jdk        # macOS")
                appendLine("     sdk install java 24-graal              # sdkman")
                appendLine("     https://www.graalvm.org/downloads/")
                appendLine()
                appendLine("   After install, either put `<jdk>/bin` on PATH or")
                appendLine("   set JAVA_HOME / MEZZE_JAVA_HOME.")
            }
        }
        appendLine()
        when (val n = report.nativeImage) {
            is NativeImageStatus.Found -> {
                appendLine("✅ native-image (for `mez build --native`)")
                appendLine("   Path:  ${n.path}")
            }
            NativeImageStatus.NotFound -> {
                appendLine("⚠️  native-image not found")
                appendLine()
                appendLine("   Only needed for `mez build --native` (AOT-compiled binaries).")
                appendLine("   `mez build` (JVM jar) works without it.")
                appendLine()
                appendLine("   Bundled with GraalVM since 22.x — install GraalVM and you're set.")
                appendLine("   Verify after install:  native-image --version")
            }
        }
    }.trimEnd()

    /** Did the environment check find a blocker for running code? */
    fun isCriticalFailure(report: Report): Boolean = when (report.java) {
        is JavaStatus.Probed -> false
        is JavaStatus.TooOld -> true
        is JavaStatus.ProbeFailed -> false  // soft failure — code might still run
        JavaStatus.NotFound -> true
    }
}
