package com.mezze.language.typechecker

import java.io.File

/**
 * The repository root, for tests that read files OUT OF THE REPO — ratchets,
 * goldens, anything measuring real source.
 *
 * ## Why not `System.getProperty("mezze.project.root")`
 *
 * The build does set that property for the test task, so reading it looks correct.
 * But it is a MUTABLE GLOBAL, and other tests in the same JVM overwrite it:
 * `TestUtils` points it at a per-test temp directory and `TestMinimalResult` at
 * `/tmp/test_std`. Whether a given test sees the repo root therefore depends on
 * which tests ran before it.
 *
 * That made `EmitFileSizeRatchetTest` pass only by luck — adding an unrelated test
 * class changed discovery order and it started failing. Worse,
 * `TypeCheckerBenchmarkTest` reacted to a wrong root with `?: return`, so it
 * silently measured nothing. A ratchet that quietly stops measuring is worse than
 * no ratchet, because it still reads as a passing guard.
 *
 * Walking up to `settings.gradle.kts` cannot be perturbed by another test. Same
 * technique as `InspectRenderingGoldenTest.goldenFile()`, for the same reason.
 *
 * Note the distinction: a test that wants "the project being type-checked" — a temp
 * fixture — should pass that path explicitly. This is only for "the repo I live in".
 */
object RepoRoot {

    fun find(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        check(File(dir, "settings.gradle.kts").exists()) {
            "could not find the repo root by walking up from ${File(".").absolutePath} — " +
                "expected a settings.gradle.kts in some ancestor"
        }
        return dir
    }

    /** A repo-relative path resolved against [find]. */
    fun file(relative: String): File = File(find(), relative)
}
