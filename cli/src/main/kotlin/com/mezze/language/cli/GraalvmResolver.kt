package com.mezze.language.cli

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resolve which JDK / GraalVM mez will run on, and classify what it can do
 * (interpreter only, JIT, native-image), so every command can degrade with a
 * clear warning instead of crashing when the host GraalVM doesn't match.
 *
 * mez is NOT a package manager: the user's own GraalVM — from Nix, brew,
 * sdkman, a manual tarball, wherever — always wins. We only *detect* it. A
 * GraalVM mez fetched into ~/.mezze/graalvm/<line> (opt-in, never automatic) is
 * the LAST resort, so a hand-installed one is never shadowed.
 *
 * Precedence (first usable wins):
 *   1. MEZZE_GRAALVM_HOME   explicit override (Nix / manual point here)
 *   2. GRAALVM_HOME         environment already set one
 *   3. MEZZE_JAVA_HOME      mez-specific JDK override (legacy; kept working)
 *   4. JAVA_HOME
 *   5. `java` on PATH
 *   6. ~/.mezze/graalvm/<line>   what mez fetched, if present
 *
 * The distribution ladder this classification drives:
 *   - any JDK 21+            → run in the interpreter (always works, no JIT)
 *   - GraalVM, matching line → JIT + native-image
 *   - GraalVM, other line    → runs, but JIT/native may misbehave → warn
 *   - < 21 / none            → cannot run; a clear error, not a trace
 */
object GraalvmResolver {

    /** What a resolved JDK can do. */
    enum class Kind {
        /** GraalVM whose line matches BuildInfo.GRAAL_LINE — JIT + native-image. */
        GRAALVM_MATCHING,
        /** GraalVM, but a different line — runs; JIT/native may degrade. */
        GRAALVM_OTHER_LINE,
        /** A plain JDK 21+ — interpreter only, no Truffle JIT. */
        PLAIN_JDK,
        /** Found a JDK but < 21 — cannot run the runtime (needs Loom). */
        TOO_OLD,
        /** No usable JDK found at all. */
        NONE,
    }

    /**
     * The outcome of resolution. [home] is the JDK home (…/bin/java lives under
     * it); [javaExe] the executable to launch. [jit]/[native] say what's
     * available. [degradeReason] is a one-line, user-facing explanation set
     * whenever we're not at full capability (null iff GRAALVM_MATCHING).
     */
    data class Resolved(
        val kind: Kind,
        val home: File?,
        val javaExe: File?,
        val version: String?,
        val majorVersion: Int?,
        val graalLine: String?,
        val jit: Boolean,
        val native: Boolean,
        val degradeReason: String?,
        val source: String,  // which precedence step matched (for `mez doctor`)
    ) {
        val canRun: Boolean get() = kind != Kind.NONE && kind != Kind.TOO_OLD
    }

    /** A single candidate JDK home plus where it came from, before probing. */
    private data class Candidate(val home: File, val source: String)

    /** The mez-managed provisioning slot for the wanted line (last-resort). */
    fun managedGraalvmDir(line: String = BuildInfo.GRAAL_LINE): File =
        File(File(MezzeHome.home, "graalvm"), line)

    /** Resolve the JDK/GraalVM per the precedence chain and classify it. */
    fun resolve(): Resolved {
        for (candidate in candidates()) {
            val probed = probe(candidate) ?: continue
            return probed
        }
        return Resolved(
            kind = Kind.NONE, home = null, javaExe = null, version = null,
            majorVersion = null, graalLine = null, jit = false, native = false,
            degradeReason = "no JDK found — mez needs a JDK 21+ (GraalVM for JIT/native)",
            source = "none",
        )
    }

    /** Candidate homes in precedence order; only ones that look like a JDK. */
    private fun candidates(): List<Candidate> {
        val out = mutableListOf<Candidate>()
        fun addHome(home: String?, source: String) {
            if (home.isNullOrBlank()) return
            val dir = File(home)
            if (File(dir, "bin/java").canExecute()) out += Candidate(dir, source)
        }
        addHome(System.getenv("MEZZE_GRAALVM_HOME"), "MEZZE_GRAALVM_HOME")
        addHome(System.getenv("GRAALVM_HOME"), "GRAALVM_HOME")
        addHome(System.getenv("MEZZE_JAVA_HOME"), "MEZZE_JAVA_HOME")
        addHome(System.getenv("JAVA_HOME"), "JAVA_HOME")
        findOnPath("java")?.let { java ->
            // …/bin/java → home is two parents up.
            java.parentFile?.parentFile?.let { out += Candidate(it, "PATH") }
        }
        val managed = managedGraalvmDir()
        if (File(managed, "bin/java").canExecute()) out += Candidate(managed, "~/.mezze/graalvm")
        return out
    }

    /** Probe one candidate with `java -version`; null if it can't be probed. */
    private fun probe(candidate: Candidate): Resolved? {
        val java = File(candidate.home, "bin/java")
        if (!java.canExecute()) return null
        val output = runVersion(java) ?: return null
        val versionStr = Regex("""version "([^"]+)"""").find(output)?.groupValues?.get(1)
            ?: return null
        val major = parseMajor(versionStr) ?: return null
        val isGraal = output.contains("GraalVM", ignoreCase = true)
        val line = if (isGraal) versionStr.split(".").take(2).joinToString(".") else null

        if (major < 21) {
            return Resolved(
                kind = Kind.TOO_OLD, home = candidate.home, javaExe = java, version = versionStr,
                majorVersion = major, graalLine = line, jit = false, native = false,
                degradeReason = "JDK $versionStr is too old — mez needs 21+ (Loom virtual threads)",
                source = candidate.source,
            )
        }

        return when {
            isGraal && line == BuildInfo.GRAAL_LINE -> Resolved(
                kind = Kind.GRAALVM_MATCHING, home = candidate.home, javaExe = java,
                version = versionStr, majorVersion = major, graalLine = line,
                jit = true, native = hasNativeImage(candidate.home),
                degradeReason = null, source = candidate.source,
            )
            isGraal -> Resolved(
                kind = Kind.GRAALVM_OTHER_LINE, home = candidate.home, javaExe = java,
                version = versionStr, majorVersion = major, graalLine = line,
                jit = true, native = hasNativeImage(candidate.home),
                degradeReason = "GraalVM $versionStr is line $line, but mez bundles line " +
                    "${BuildInfo.GRAAL_LINE} — JIT/native may be unstable across lines",
                source = candidate.source,
            )
            else -> Resolved(
                kind = Kind.PLAIN_JDK, home = candidate.home, javaExe = java,
                version = versionStr, majorVersion = major, graalLine = null,
                jit = false, native = false,
                degradeReason = "JDK $versionStr is not GraalVM — running in the interpreter " +
                    "(no JIT); install GraalVM ${BuildInfo.GRAAL_LINE} for full speed",
                source = candidate.source,
            )
        }
    }

    /** Does this GraalVM home ship native-image? (needed for `mez build --native`) */
    private fun hasNativeImage(home: File): Boolean =
        File(home, "bin/native-image").canExecute() ||
            File(home, "bin/native-image.cmd").canExecute()

    private fun runVersion(java: File): String? = try {
        val proc = ProcessBuilder(java.absolutePath, "-version")
            .redirectErrorStream(true)  // `java -version` prints to stderr
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(5, TimeUnit.SECONDS)) { proc.destroyForcibly(); null } else out
    } catch (_: Exception) { null }

    private fun parseMajor(version: String): Int? {
        val first = version.substringBefore('.').toIntOrNull() ?: return null
        if (first == 1) return version.substringAfter('.').substringBefore('.').toIntOrNull()
        return first
    }

    private fun findOnPath(exe: String): File? {
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(File.pathSeparator)) {
            val f = File(dir, exe)
            if (f.canExecute()) return f
        }
        return null
    }

    /**
     * Emit a one-line degrade warning to stderr if this resolution isn't at
     * full capability. Graceful (a ⚠ line + a pointer to `mez doctor`), never a
     * crash. Called once per run/build entry. Silent when GRAALVM_MATCHING.
     */
    fun warnIfDegraded(resolved: Resolved) {
        val reason = resolved.degradeReason ?: return
        val msg = com.mezze.language.cli.commands.CliMessages()
        System.err.println(msg.warning(reason, "see `mez doctor` for setup options"))
    }

    /**
     * Enforce CI strictness flags: `--require-jit` fails unless JIT is
     * available; `--require-native` fails unless native-image is. Returns a
     * MezzeCliError-ready message, or null when the requirement is met (or not
     * requested). Turns a would-be warning into a hard failure for CI.
     */
    fun requirementFailure(resolved: Resolved, requireJit: Boolean, requireNative: Boolean): String? {
        if (requireNative && !resolved.native) {
            return "--require-native: no GraalVM ${BuildInfo.GRAAL_LINE} with native-image found " +
                "(have: ${resolved.version ?: "no JDK"})"
        }
        if (requireJit && !resolved.jit) {
            return "--require-jit: no GraalVM found for JIT (have: ${resolved.version ?: "no JDK"})"
        }
        return null
    }
}
