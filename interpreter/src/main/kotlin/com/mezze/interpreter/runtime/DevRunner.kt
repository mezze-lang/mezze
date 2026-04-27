package com.mezze.interpreter.runtime

import com.mezze.language.codebase.CodebaseSerialization
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * JVM entry point for `mez dev` execution.
 *
 * Default: watch mode — compile, execute, watch for .mz changes, repeat.
 * JVM stays alive. Truffle Engine reused (JIT stays warm).
 * ~97ms hot reload on file change.
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
        val noWatch = parsed.containsKey("no-watch")
        val inspect = parsed.containsKey("inspect")
        val inspectBrk = parsed.containsKey("inspect-brk")

        if (noWatch) {
            runSingleExecution(cachePath, projectRoot, entryName, inspect, inspectBrk)
        } else {
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

            val engine = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build()
            executeWithTruffle(functions, performers, projectRoot.toPath(), entryName, inspect, inspectBrk, engine)
            engine.close()
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
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        System.setProperty("polyglot.log.level", "OFF")

        // Shared Truffle Engine — JIT stays warm across runs
        val engine = Engine.newBuilder()
            .option("engine.WarnInterpreterOnly", "false")
            .build()

        // First run
        printSeparator()
        val ok = compileAndExecute(sourceFile, projectRoot, entryName, inspect, inspectBrk, engine)
        printWatching()

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
            val key = watcher.take()
            val changedFiles = mutableSetOf<String>()

            for (event in key.pollEvents()) {
                val filename = event.context()?.toString() ?: continue
                if (filename.endsWith(".mz")) {
                    changedFiles.add(filename)
                }
            }
            key.reset()

            if (changedFiles.isNotEmpty()) {
                // Debounce — editors save multiple times
                Thread.sleep(50)
                watcher.poll()?.let { it.pollEvents(); it.reset() }

                printSeparator()
                val timestamp = LocalTime.now().format(TIME_FMT)
                System.err.println("${ANSI_GRAY}[$timestamp]${ANSI_RESET} ${changedFiles.joinToString(", ")} changed")
                System.err.println()

                compileAndExecute(sourceFile, projectRoot, entryName, inspect, inspectBrk, engine)
                printWatching()
            }
        }
    }

    /**
     * Compile and execute. Returns true on success, false on error.
     * Errors are printed with full formatting — never crashes the watcher.
     */
    private fun compileAndExecute(
        sourceFile: File, projectRoot: File, entryName: String,
        inspect: Boolean, inspectBrk: Boolean, engine: Engine
    ): Boolean {
        val startTime = System.nanoTime()
        val source = sourceFile.readText()

        // Parse
        val parser = com.mezze.language.parser.MezzeParser()
        val ast = try {
            parser.parseParsedModule(source, sourceFile.name)
        } catch (e: com.mezze.language.parser.ParseException) {
            printError("Parse Error", e.message ?: "Unknown parse error")
            return false
        }

        // Typecheck + lower
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
        val typeChecker = com.mezze.language.typechecker.TypeChecker()
        val result = try {
            typeChecker.typeCheck(ast, projectRoot.toPath())
        } catch (e: com.mezze.language.typechecker.TypeCheckError) {
            val formatted = try {
                com.mezze.language.typechecker.printing.ErrorFormatter.format(e, source)
            } catch (_: Exception) { e.message ?: "Unknown type error" }
            printError("", formatted)
            return false
        }

        val functions = result.loweredFunctions
        if (functions.isEmpty()) {
            printError("Error", "No functions produced by compiler")
            return false
        }

        // Execute
        try {
            executeWithTruffle(functions, result.performerRecords, projectRoot.toPath(), entryName, inspect, inspectBrk, engine)
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            printError("Runtime Error", e.message ?: "Unknown error")
            return false
        } catch (e: Exception) {
            printError("Error", e.message ?: "Unknown error")
            return false
        }

        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        System.err.println()
        System.err.println("${ANSI_GREEN}[done in ${elapsed}ms]${ANSI_RESET}")
        return true
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

    private fun printSeparator() {
        System.err.println(ANSI_GRAY + SEPARATOR + ANSI_RESET)
    }

    private fun printError(label: String, message: String) {
        System.err.println()
        System.err.println("${ANSI_RED}$label${ANSI_RESET}")
        System.err.println(message)
    }

    private fun printWatching() {
        System.err.println()
        System.err.println("${ANSI_GRAY}Watching for changes... (Ctrl+C to stop)${ANSI_RESET}")
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
