package com.mezze.interpreter.bench

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Sentinel test class that runs LAST (lexicographically "ZZZ" prefix
 * places it after all other bench classes when JUnit sorts) and writes
 * the accumulated [BenchRecorder] results to a markdown file.
 *
 * Output path: `planned_features/runtime/perf-baseline.md` from the repo
 * root. The test working dir is `:interpreter/`, so we resolve `../planned_features/...`.
 *
 * Override the output path via system property `mezze.bench.out`.
 *
 * Class name matches `*Bench*` so it's picked up by the bench task.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZZZBenchSummaryWriter {

    @Test
    @Tag("bench-summary")
    fun `writeBenchSummary placeholder`() {
        // No-op. Real work happens in @AfterAll once all sibling bench
        // classes have run and registered their results with BenchRecorder.
    }

    @AfterAll
    fun writeSummary() {
        val outPath = System.getProperty("mezze.bench.out")
            ?: "../planned_features/runtime/perf-baseline.md"
        val out = File(outPath).absoluteFile
        val gitSha = runCatching {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText().trim()
        }.getOrNull()
        BenchRecorder.writeMarkdown(out, gitSha)
    }
}
