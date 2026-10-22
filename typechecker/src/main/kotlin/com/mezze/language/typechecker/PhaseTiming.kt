package com.mezze.language.typechecker

/**
 * `MEZZE_TIMING=1`: how long each phase of checking a module takes, on stderr.
 * The tool for keeping the checker fast — measure the phase, not the process.
 */
object PhaseTiming {
    @JvmStatic
    val on: Boolean = System.getenv("MEZZE_TIMING") != null

    inline fun <T> phase(module: String?, name: String, body: () -> T): T {
        if (!on) return body()
        val start = System.nanoTime()
        try {
            return body()
        } finally {
            System.err.println("[timing] ${module ?: "<entry>"} $name ${(System.nanoTime() - start) / 100_000 / 10.0}ms")
        }
    }
}
