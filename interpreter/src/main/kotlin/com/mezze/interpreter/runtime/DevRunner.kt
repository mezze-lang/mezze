package com.mezze.interpreter.runtime

import com.mezze.language.codebase.CodebaseSerialization
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/**
 * JVM entry point for `mez dev` execution.
 *
 * Two modes:
 * 1. Single-run: load cached FunctionIRs from cache.sqlite, execute, exit
 * 2. Watch mode: compile from source, execute, watch for changes, repeat
 *
 * Watch mode keeps the JVM alive — no cold start on each save.
 */
object DevRunner {

    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = parseArgs(args)

        val projectRoot = File(parsed["project-root"] ?: ".")
        val cachePath = File(parsed["cache"] ?: File(projectRoot, ".mz/cache.sqlite").absolutePath)
        val entryFile = parsed["entry-file"] ?: "src/app.mz"
        val entryName = parsed["entry-name"] ?: "main"
        val noWatch = parsed.containsKey("no-watch")
        val inspect = parsed.containsKey("inspect")
        val inspectBrk = parsed.containsKey("inspect-brk")

        if (noWatch) {
            runSingleExecution(cachePath, projectRoot, entryName, inspect, inspectBrk)
        } else {
            // Default: watch mode — compile, execute, watch for changes, repeat
            runWatchMode(projectRoot, entryFile, entryName, inspect, inspectBrk)
        }
    }

    private fun runSingleExecution(
        cachePath: File, projectRoot: File, entryName: String,
        inspect: Boolean, inspectBrk: Boolean
    ) {
        if (!cachePath.exists()) {
            System.err.println("Error: Cache not found at ${cachePath.absolutePath}")
            System.exit(1)
        }

        val store = SQLiteMezzeStore.readOnly(cachePath)
        try {
            val functions = loadFunctions(store)
            val performers = loadPerformerRecords(store)

            if (functions.isEmpty()) {
                System.err.println("Error: No functions found in cache.")
                System.exit(1)
            }

            executeWithTruffle(functions, performers, projectRoot.toPath(), entryName, inspect, inspectBrk)
        } finally {
            store.close()
        }
    }

    private fun runWatchMode(
        projectRoot: File, entryFile: String, entryName: String,
        inspect: Boolean, inspectBrk: Boolean
    ) {
        val sourceFile = File(projectRoot, entryFile)
        if (!sourceFile.exists()) {
            System.err.println("Error: Source file not found: ${sourceFile.absolutePath}")
            System.exit(1)
        }

        System.setProperty("mezze.project.root", projectRoot.absolutePath)

        // First run
        val startTime = System.nanoTime()
        compileAndExecute(sourceFile, projectRoot, entryName, inspect, inspectBrk)
        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        System.err.println("[${elapsed}ms] done")
        System.err.println()
        System.err.println("Watching for changes... (Ctrl+C to stop)")

        // Watch loop
        val watcher = FileSystems.getDefault().newWatchService()
        val srcDir = sourceFile.parentFile.toPath()

        // Register all directories under src/ recursively
        Files.walkFileTree(srcDir, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)
                return FileVisitResult.CONTINUE
            }
        })

        while (true) {
            val key = watcher.take() // blocks until a change
            var changed = false

            for (event in key.pollEvents()) {
                val filename = event.context()?.toString() ?: continue
                if (filename.endsWith(".mz")) {
                    changed = true
                }
            }
            key.reset()

            if (changed) {
                // Small debounce — editors save multiple times
                Thread.sleep(50)
                // Drain any additional events
                watcher.poll()?.let { it.pollEvents(); it.reset() }

                System.err.println()
                val rerunStart = System.nanoTime()
                try {
                    compileAndExecute(sourceFile, projectRoot, entryName, inspect, inspectBrk)
                } catch (e: Exception) {
                    System.err.println("Error: ${e.message}")
                }
                val rerunElapsed = (System.nanoTime() - rerunStart) / 1_000_000
                System.err.println("[${rerunElapsed}ms] done")
                System.err.println()
                System.err.println("Watching for changes... (Ctrl+C to stop)")
            }
        }
    }

    private fun compileAndExecute(
        sourceFile: File, projectRoot: File, entryName: String,
        inspect: Boolean, inspectBrk: Boolean
    ) {
        // Parse
        val source = sourceFile.readText()
        val parser = com.mezze.language.parser.MezzeParser()
        val ast = parser.parseParsedModule(source, sourceFile.name)

        // Typecheck + lower
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
        val typeChecker = com.mezze.language.typechecker.TypeChecker()
        val result = typeChecker.typeCheck(ast, projectRoot.toPath())

        val functions = result.loweredFunctions
        if (functions.isEmpty()) {
            System.err.println("Error: No functions produced")
            return
        }

        val performers = result.performerRecords

        // Execute
        executeWithTruffle(functions, performers, projectRoot.toPath(), entryName, inspect, inspectBrk)
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

    private fun executeWithTruffle(
        functions: List<FunctionIR>,
        performers: List<Array<String>>,
        projectRoot: Path,
        entryName: String,
        inspect: Boolean,
        inspectBrk: Boolean
    ) {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        System.setProperty("polyglot.log.level", "OFF")

        val builder = Context.newBuilder(MezzeLanguage.ID)
            .allowAllAccess(true)
            .allowCreateThread(true)

        if (inspect || inspectBrk) {
            builder.option("inspect", "true")
            if (inspectBrk) builder.option("inspect.Suspend", "true")
            System.err.println("Debugger listening on ws://127.0.0.1:9229")
        }

        val context = builder.build()
        try {
            MezzeLanguage.setFunctionsForDirectExecution(functions, performers, projectRoot, entryName)

            val source = Source.newBuilder(
                MezzeLanguage.ID,
                "# DevRunner execution",
                "__dev_runner__.mz"
            ).buildLiteral()

            context.eval(source)
        } finally {
            context.close(true)
        }
    }

    private fun parseArgs(args: Array<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (arg in args) {
            when {
                arg.startsWith("--") && "=" in arg -> {
                    val (key, value) = arg.removePrefix("--").split("=", limit = 2)
                    result[key] = value
                }
                arg.startsWith("--") -> {
                    result[arg.removePrefix("--")] = ""
                }
            }
        }
        return result
    }
}
