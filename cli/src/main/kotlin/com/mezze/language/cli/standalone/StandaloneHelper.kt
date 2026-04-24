package com.mezze.language.cli.standalone

/**
 * Helper class for standalone JAR execution.
 * TODO: Rework in #103 — will use MezzeStore + MezzeExecutor for hash-based execution.
 */
object StandaloneHelper {

    @JvmStatic
    fun executeFromCodebase(codebaseBytes: ByteArray): Any? {
        throw UnsupportedOperationException(
            "Standalone JAR execution not yet migrated to MezzeStore. See task #103."
        )
    }
}
