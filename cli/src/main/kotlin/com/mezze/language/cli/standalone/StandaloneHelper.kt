package com.mezze.language.cli.standalone

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.codebase.CodebaseSerialization
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import java.io.File
import java.io.PrintStream

/**
 * Helper for standalone JAR execution.
 *
 * Loads compiled IR from embedded blobs.sqlite and executes via Truffle.
 */
object StandaloneHelper {

    /**
     * Execute from a blobs.sqlite byte array.
     * The SQLite DB contains: blobs table (hash → ir), names table, metadata (entry_hash).
     *
     * [args] are the CLI args from `java -jar app.jar ...`. Recognized
     * flags: --profile, --coverage, --allocations, --insight=<file>,
     * --inspect, --inspect-brk. Unknown args are ignored — they're
     * available to user code via System args (TODO when Mezze supports
     * argv).
     */
    @JvmStatic
    fun executeFromBlobsSqlite(sqliteBytes: ByteArray, args: List<String> = emptyList()) {
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
                executeWithTruffle(functions, performers, entryName, args)
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

    /**
     * Resolve where profile / coverage / allocation output goes. Defaults
     * to the current directory; override with MEZZE_OUTPUT_DIR env var.
     */
    private fun outputDir(): File {
        val override = System.getenv("MEZZE_OUTPUT_DIR")
        return if (override != null && override.isNotBlank()) File(override) else File(".")
    }

    private fun executeWithTruffle(
        functions: List<FunctionIR>,
        performers: List<Array<String>> = emptyList(),
        entryName: String? = null,
        args: List<String> = emptyList()
    ) {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        System.setProperty("polyglot.log.level", "OFF")

        // Build an explicit Engine so we can attach instruments. Same
        // pattern as DevRunner — keeps the two execution surfaces (dev
        // and standalone) in sync.
        //
        // Truffle JIT is ON by default for standalone apps (truffle-runtime
        // is on the classpath; engine.Compilation defaults to true). Users
        // can disable with MEZZE_JIT=0 if a startup-sensitive workload
        // prefers interpreter-only.
        val jitDisabled = System.getenv("MEZZE_JIT")?.let {
            it == "0" || it.equals("false", ignoreCase = true)
        } ?: false
        val engineBuilder = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .apply { if (jitDisabled) option("engine.Compilation", "false") }

        val profile = args.contains("--profile")
        val coverage = args.contains("--coverage")
        val allocations = args.contains("--allocations")
        val inspect = args.contains("--inspect")
        val inspectBrk = args.contains("--inspect-brk")
        val insightFile = args.firstNotNullOfOrNull { a ->
            if (a.startsWith("--insight=")) a.substringAfter("=") else null
        }

        if (profile) {
            val out = File(outputDir(), "mezze-profile.txt")
            out.parentFile?.mkdirs()
            engineBuilder.option("cpusampler", "true")
                         .option("cpusampler.OutputFile", out.absolutePath)
            System.err.println("[mezze] profile → ${out.absolutePath}")
        }
        if (coverage) {
            val out = File(outputDir(), "mezze-coverage.txt")
            out.parentFile?.mkdirs()
            engineBuilder.option("coverage", "true")
                         .option("coverage.OutputFile", out.absolutePath)
            System.err.println("[mezze] coverage → ${out.absolutePath}")
        }
        if (allocations) {
            // Custom Mezze allocations instrument; output goes to the
            // engine `out` stream on dispose. Redirect that to a file —
            // user `println` writes to System.out via Console effect
            // handler, so it doesn't collide.
            val out = File(outputDir(), "mezze-allocations.txt")
            out.parentFile?.mkdirs()
            engineBuilder.option("mezze-allocations", "true")
                         .out(PrintStream(out.outputStream(), true))
            System.err.println("[mezze] allocations → ${out.absolutePath}")
        }
        if (insightFile != null) {
            val f = File(insightFile)
            if (!f.exists()) {
                System.err.println("Error: --insight=$insightFile: file not found")
                kotlin.system.exitProcess(1)
            }
            engineBuilder.option("insight", f.absolutePath)
        }
        if (inspect || inspectBrk) {
            engineBuilder.option("inspect", "4242")
            if (inspectBrk) engineBuilder.option("inspect.Suspend", "true")
            System.err.println("[mezze] devtools listening on chrome://inspect — port 4242")
        }

        val engine = engineBuilder.build()

        // Close engine on JVM exit so onDispose flushes profile / coverage /
        // allocation output. Without this, those files stay empty.
        Runtime.getRuntime().addShutdownHook(Thread {
            try { engine.close() } catch (_: Throwable) { /* best-effort */ }
        })

        val context = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine)
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
