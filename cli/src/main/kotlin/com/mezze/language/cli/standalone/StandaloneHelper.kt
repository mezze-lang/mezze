package com.mezze.language.cli.standalone

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.codebase.CodebaseSerialization
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source

/**
 * Helper for standalone JAR execution.
 *
 * Loads compiled IR from embedded blobs.sqlite and executes via Truffle.
 */
object StandaloneHelper {

    /**
     * Execute from a blobs.sqlite byte array.
     * The SQLite DB contains: blobs table (hash → ir), names table, metadata (entry_hash).
     */
    @JvmStatic
    fun executeFromBlobsSqlite(sqliteBytes: ByteArray) {
        // Write to temp file so SQLite can open it
        val tempFile = java.io.File.createTempFile("mezze-blobs", ".sqlite")
        try {
            tempFile.writeBytes(sqliteBytes)

            // Open the SQLite database
            val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:${tempFile.absolutePath}")
            conn.use { db ->
                // Single scalar metadata: app_name, entry_hash, entry_name.
                fun meta(key: String): String? = db.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT value FROM metadata WHERE key = '$key'").use { rs ->
                        if (rs.next()) rs.getString(1) else null
                    }
                }

                meta("entry_hash") ?: throw RuntimeException("No entry_hash in blobs.sqlite metadata")
                val appName = meta("app_name") ?: throw RuntimeException("No app_name in blobs.sqlite metadata")
                val entryName = meta("entry_name")

                // Load user FunctionIRs from app_functions in stored order.
                val functions = mutableListOf<FunctionIR>()
                db.prepareStatement(
                    "SELECT b.ir FROM app_functions af JOIN blobs b ON b.hash = af.hash WHERE af.app_name = ? ORDER BY af.ord"
                ).use { stmt ->
                    stmt.setString(1, appName)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val irBytes = rs.getBytes("ir") ?: continue
                            try {
                                functions.add(CodebaseSerialization.deserializeFunctionIR(irBytes))
                            } catch (_: Exception) {}
                        }
                    }
                }

                if (functions.isEmpty()) {
                    throw RuntimeException("No executable functions in blobs.sqlite for app '$appName'")
                }

                // Performer records from app_performers, grouped by record_idx, ordered by position.
                val byRecord = mutableMapOf<Int, MutableList<String>>()
                db.prepareStatement(
                    "SELECT record_idx, position, hash FROM app_performers WHERE app_name = ? ORDER BY record_idx, position"
                ).use { stmt ->
                    stmt.setString(1, appName)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            byRecord.getOrPut(rs.getInt(1)) { mutableListOf() }.add(rs.getString(3))
                        }
                    }
                }
                val performers = byRecord.toSortedMap().values.map { it.toTypedArray() }.toMutableList()

                // Execute via Truffle
                executeWithTruffle(functions, performers, entryName)
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Execute from SQLite codebase bytes (legacy format).
     */
    @JvmStatic
    fun executeFromCodebase(codebaseBytes: ByteArray): Any? {
        executeFromBlobsSqlite(codebaseBytes)
        return null
    }

    private fun executeWithTruffle(functions: List<FunctionIR>, performers: List<Array<String>> = emptyList(), entryName: String? = null) {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        System.setProperty("polyglot.log.level", "OFF")

        val context = Context.newBuilder(MezzeLanguage.ID)
            .allowAllAccess(true)
            .allowCreateThread(true)
            // Allow users to enable Truffle instrumentation (cpusampler,
            // coverage, memtracer, insight, engine.Trace*) on the deployed
            // jar via system properties:
            //   java -Dpolyglot.cpusampler=true -jar app.jar
            // Without this, every -Dpolyglot.* option silently no-ops.
            .allowExperimentalOptions(true)
            .build()

        try {
            val projectRoot = java.nio.file.Paths.get(".")
            if (entryName != null) {
                MezzeLanguage.setFunctionsForDirectExecution(functions, performers, projectRoot, entryName)
            } else {
                MezzeLanguage.setFunctionsForDirectExecution(functions, performers, projectRoot)
            }

            val source = Source.newBuilder(
                MezzeLanguage.ID,
                "# Standalone execution",
                "__standalone__.mz"
            ).buildLiteral()

            context.eval(source)
        } finally {
            context.close(true)
        }
    }
}
