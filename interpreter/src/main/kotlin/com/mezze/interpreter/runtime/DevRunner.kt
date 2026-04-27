package com.mezze.interpreter.runtime

import com.mezze.language.codebase.CodebaseSerialization
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * JVM entry point for `mez dev` execution.
 *
 * Default: watch mode — compile, store in cache.sqlite, execute, watch, repeat.
 * Same compile/store/execute path as the native binary. JVM stays alive.
 * Truffle Engine reused (JIT stays warm). ~100ms hot reload.
 */
object DevRunner {

    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
    private const val SEPARATOR = "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    private const val ANSI_RED = "\u001b[31m"
    private const val ANSI_GREEN = "\u001b[32m"
    private const val ANSI_GRAY = "\u001b[90m"
    private const val ANSI_RESET = "\u001b[0m"

    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = parseArgs(args)

        val projectRoot = File(parsed["project-root"] ?: ".")
        val cachePath = File(parsed["cache"] ?: File(projectRoot, ".mz/cache.sqlite").absolutePath)
        val entryFile = parsed["entry-file"] ?: "src/app.mz"
        val entryName = parsed["entry-name"] ?: "main"
        val modulePath = parsed["module-path"] ?: "app"
        val noWatch = parsed.containsKey("no-watch")
        val inspect = parsed.containsKey("inspect")
        val inspectBrk = parsed.containsKey("inspect-brk")

        System.setProperty("mezze.project.root", projectRoot.absolutePath)
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        System.setProperty("polyglot.log.level", "OFF")

        // Open project store — stays open for the lifetime of the process
        val store = SQLiteMezzeStore.forProject(projectRoot)

        // Load stdlib into store if not already
        ensureStdlibLoaded(store)

        // Shared Truffle Engine — JIT stays warm across runs
        val engine = Engine.newBuilder()
            .option("engine.WarnInterpreterOnly", "false")
            .build()

        if (noWatch) {
            // Single run from cache
            val functions = loadFunctions(store)
            val performers = loadPerformerRecords(store)
            if (functions.isNotEmpty()) {
                executeWithTruffle(functions, performers, projectRoot.toPath(), entryName, inspect, inspectBrk, engine)
            }
            engine.close()
            store.close()
        } else {
            // Watch mode
            runWatchMode(store, engine, projectRoot, entryFile, entryName, modulePath, inspect, inspectBrk)
        }
    }

    private fun runWatchMode(
        store: SQLiteMezzeStore, engine: Engine,
        projectRoot: File, entryFile: String, entryName: String, modulePath: String,
        inspect: Boolean, inspectBrk: Boolean
    ) {
        val sourceFile = File(projectRoot, entryFile)
        if (!sourceFile.exists()) {
            System.err.println("Error: Source file not found: ${sourceFile.absolutePath}")
            System.exit(1)
        }

        // First run — always compile
        printSeparator()
        compileStoreExecute(store, engine, sourceFile, projectRoot, entryName, modulePath, inspect, inspectBrk)
        printWatching()

        // Watch loop
        val watcher = FileSystems.getDefault().newWatchService()
        val srcDir = sourceFile.parentFile.toPath()

        Files.walkFileTree(srcDir, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)
                return FileVisitResult.CONTINUE
            }
        })

        while (true) {
            val key = watcher.take()
            val changedFiles = mutableSetOf<String>()

            for (event in key.pollEvents()) {
                val filename = event.context()?.toString() ?: continue
                if (filename.endsWith(".mz")) changedFiles.add(filename)
            }
            key.reset()

            if (changedFiles.isNotEmpty()) {
                Thread.sleep(50) // debounce
                watcher.poll()?.let { it.pollEvents(); it.reset() }

                printSeparator()
                val timestamp = LocalTime.now().format(TIME_FMT)
                System.err.println("${ANSI_GRAY}[$timestamp]${ANSI_RESET} ${changedFiles.joinToString(", ")} changed")
                System.err.println()

                compileStoreExecute(store, engine, sourceFile, projectRoot, entryName, modulePath, inspect, inspectBrk)
                printWatching()
            }
        }
    }

    /**
     * The unified compile/store/execute path.
     * Same logic as DevCommand in the native binary:
     * 1. Check staleness (SHA-256)
     * 2. If stale: parse → typecheck → lower → store in cache.sqlite
     * 3. If fresh: load FunctionIRs from cache.sqlite
     * 4. Execute via Truffle
     */
    private fun compileStoreExecute(
        store: SQLiteMezzeStore, engine: Engine,
        sourceFile: File, projectRoot: File, entryName: String, modulePath: String,
        inspect: Boolean, inspectBrk: Boolean
    ) {
        val startTime = System.nanoTime()
        val source = sourceFile.readText()
        val sourceHash = sha256(source)
        val filePath = sourceFile.absolutePath

        val stale = store.isFileStale(filePath, sourceHash)
        if (System.getenv("MEZZE_DEBUG") != null) {
            System.err.println("[DevRunner] file=$filePath stale=$stale hash=${sourceHash.take(16)} source_preview=${source.lines().last { it.contains("msg") || it.isNotBlank() }.trim().take(40)}")
        }

        if (stale) {
            // Compile the changed file
            val parser = com.mezze.language.parser.MezzeParser()
            val ast = try {
                parser.parseParsedModule(source, sourceFile.name)
            } catch (e: com.mezze.language.parser.ParseException) {
                printError("Parse Error", e.message ?: "Unknown parse error")
                return
            }

            // Clear caches so recompilation sees fresh state
            com.mezze.language.typechecker.ModuleResolver.clearCache()
            com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack()
            com.mezze.interpreter.compiler.PipelineRunner.clearCache()

            val typeChecker = com.mezze.language.typechecker.TypeChecker()
            val result = try {
                typeChecker.typeCheck(ast, projectRoot.toPath())
            } catch (e: com.mezze.language.typechecker.TypeCheckError) {
                val formatted = try {
                    com.mezze.language.typechecker.printing.ErrorFormatter.format(e, source)
                } catch (_: Exception) { e.message ?: "Unknown type error" }
                printError("", formatted)
                return
            }

            if (result.loweredFunctions.isEmpty()) {
                printError("Error", "No functions produced by compiler")
                return
            }

            // Write to cache.sqlite for next fresh run
            try {
                StorePopulator.populate(store, result, modulePath, filePath, sourceHash)
                store.setMetadata("entry_name", entryName)
                store.setMetadata("module_path", modulePath)
                store.setMetadata("user_function_hashes",
                    result.loweredFunctions.joinToString(";") { it.hash.hex })
                if (result.performerRecords.isNotEmpty()) {
                    store.setMetadata("performer_records",
                        result.performerRecords.joinToString(";") { it.joinToString(",") })
                }
            } catch (_: Exception) {}

            // Use freshly compiled functions directly (not from store)
            return executeAndReport(result.loweredFunctions, result.performerRecords,
                projectRoot, entryName, inspect, inspectBrk, engine, startTime)
        }

        // Fresh path — load from cache.sqlite
        val functions = loadFunctions(store)
        val performers = loadPerformerRecords(store)

        if (functions.isEmpty()) {
            printError("Error", "No functions in cache. Delete .mz/ and retry.")
            return
        }

        executeAndReport(functions, performers, projectRoot, entryName, inspect, inspectBrk, engine, startTime)

        // Execute
        try {
            executeWithTruffle(functions, performers, projectRoot.toPath(), entryName, inspect, inspectBrk, engine)
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            printError("Runtime Error", e.message ?: "Unknown error")
            return
        } catch (e: Exception) {
            printError("Error", e.message ?: "Unknown error")
            return
        }

        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        System.err.println()
        System.err.println("${ANSI_GREEN}[done in ${elapsed}ms]${ANSI_RESET}")
    }

    private fun ensureStdlibLoaded(store: SQLiteMezzeStore) {
        if (store.getMetadata("stdlib_loaded") != null) return
        try {
            val precompiled = com.mezze.language.typechecker.PrecompiledStdlib
            store.transaction {
                for ((hash, fnIR) in precompiled.preloweredFunctions) {
                    val irBytes = CodebaseSerialization.serializeFunctionIR(fnIR)
                    store.putBlob(Hash(hash),
                        com.mezze.language.codebase.BlobKind.FUNCTION,
                        com.mezze.language.codebase.Visibility.PUBLIC, null, irBytes)
                }
                store.setMetadata("stdlib_loaded", "true")
            }
        } catch (_: Exception) {}
    }

    private fun executeAndReport(
        functions: List<FunctionIR>, performers: List<Array<String>>,
        projectRoot: File, entryName: String, inspect: Boolean, inspectBrk: Boolean,
        engine: Engine, startTime: Long
    ) {
        try {
            executeWithTruffle(functions, performers, projectRoot.toPath(), entryName, inspect, inspectBrk, engine)
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            printError("Runtime Error", e.message ?: "Unknown error")
            return
        } catch (e: Exception) {
            printError("Error", e.message ?: "Unknown error")
            return
        }
        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        System.err.println()
        System.err.println("${ANSI_GREEN}[done in ${elapsed}ms]${ANSI_RESET}")
    }

    private fun executeWithTruffle(
        functions: List<FunctionIR>,
        performers: List<Array<String>>,
        projectRoot: java.nio.file.Path,
        entryName: String,
        inspect: Boolean,
        inspectBrk: Boolean,
        engine: Engine
    ) {
        val builder = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine)
            .allowAllAccess(true)
            .allowCreateThread(true)

        if (inspect || inspectBrk) {
            builder.option("inspect", "true")
            if (inspectBrk) builder.option("inspect.Suspend", "true")
        }

        val context = builder.build()
        try {
            MezzeLanguage.setFunctionsForDirectExecution(functions, performers, projectRoot, entryName)
            val source = Source.newBuilder(MezzeLanguage.ID, "# DevRunner", "__dev_runner__.mz").buildLiteral()
            context.eval(source)
        } finally {
            context.close(true)
        }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun loadFunctions(store: SQLiteMezzeStore): List<FunctionIR> {
        val hashList = store.getMetadata("user_function_hashes") ?: return emptyList()
        return hashList.split(";").mapNotNull { hex ->
            if (hex.isEmpty()) return@mapNotNull null
            val irBytes = store.getIR(Hash(hex)) ?: return@mapNotNull null
            CodebaseSerialization.deserializeFunctionIR(irBytes)
        }
    }

    private fun loadPerformerRecords(store: SQLiteMezzeStore): List<Array<String>> {
        val raw = store.getMetadata("performer_records") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(";").map { it.split(",").toTypedArray() }
    }

    private fun printSeparator() { System.err.println(ANSI_GRAY + SEPARATOR + ANSI_RESET) }
    private fun printError(label: String, msg: String) { System.err.println(); if (label.isNotEmpty()) System.err.println("${ANSI_RED}$label${ANSI_RESET}"); System.err.println(msg) }
    private fun printWatching() { System.err.println(); System.err.println("${ANSI_GRAY}Watching for changes... (Ctrl+C to stop)${ANSI_RESET}") }

    private fun parseArgs(args: Array<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (arg in args) {
            when {
                arg.startsWith("--") && "=" in arg -> {
                    val (key, value) = arg.removePrefix("--").split("=", limit = 2)
                    result[key] = value
                }
                arg.startsWith("--") -> result[arg.removePrefix("--")] = ""
            }
        }
        return result
    }
}
