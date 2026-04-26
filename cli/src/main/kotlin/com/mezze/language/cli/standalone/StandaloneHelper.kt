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
                // Read entry hash from metadata
                val entryHash = db.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT value FROM metadata WHERE key = 'entry_hash'").use { rs ->
                        if (rs.next()) rs.getString(1) else null
                    }
                } ?: throw RuntimeException("No entry_hash in blobs.sqlite metadata")

                // Load ONLY user FunctionIRs — identified by the stored hash list
                // Stdlib is handled by PrecompiledStdlib at Language init time
                val userHashList = db.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT value FROM metadata WHERE key = 'user_function_hashes'").use { rs ->
                        if (rs.next()) rs.getString(1).split(";").toSet() else emptySet()
                    }
                }

                val functions = mutableListOf<FunctionIR>()
                for (hash in userHashList) {
                    if (hash.isEmpty()) continue
                    val stmt = db.prepareStatement("SELECT ir FROM blobs WHERE hash = ?")
                    stmt.setString(1, hash)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            val irBytes = rs.getBytes("ir")
                            if (irBytes != null) {
                                try {
                                    functions.add(CodebaseSerialization.deserializeFunctionIR(irBytes))
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

                if (functions.isEmpty()) {
                    throw RuntimeException("No executable functions in blobs.sqlite")
                }

                // Load performer records from metadata
                val performers = mutableListOf<Array<String>>()
                db.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT value FROM metadata WHERE key = 'performer_records'").use { rs ->
                        if (rs.next()) {
                            val json = rs.getString(1)
                            if (json.isNotEmpty()) {
                                for (record in json.split(";")) {
                                    performers.add(record.split(",").toTypedArray())
                                }
                            }
                        }
                    }
                }

                // Read entry function name from metadata
                val entryName = db.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT value FROM metadata WHERE key = 'entry_name'").use { rs ->
                        if (rs.next()) rs.getString(1) else null
                    }
                }

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
