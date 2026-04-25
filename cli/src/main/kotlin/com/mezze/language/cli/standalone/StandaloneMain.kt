package com.mezze.language.cli.standalone

/**
 * Main class for standalone JAR execution.
 * Loads embedded blobs.sqlite and executes via StandaloneHelper.
 */
object StandaloneMain {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val stream = StandaloneMain::class.java.getResourceAsStream("/com/mezze/standalone/blobs.sqlite")
                ?: throw RuntimeException("blobs.sqlite not found in JAR")

            val bytes = stream.readBytes()
            stream.close()

            StandaloneHelper.executeFromBlobsSqlite(bytes)
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (args.contains("--debug")) e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }
}
