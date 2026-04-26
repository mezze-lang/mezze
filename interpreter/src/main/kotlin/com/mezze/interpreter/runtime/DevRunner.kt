package com.mezze.interpreter.runtime

import com.mezze.language.codebase.CodebaseSerialization
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * JVM entry point for `mez dev` execution.
 *
 * The native `mez` binary compiles source to FunctionIR and stores it in
 * .mz/cache.sqlite. This class loads the cached IR and executes it via
 * Truffle with full JIT compilation and polyglot support.
 *
 * Usage:
 *   java -cp mezze-lang.jar com.mezze.interpreter.runtime.DevRunner \
 *     --project-root=/path/to/project \
 *     --entry-file=src/app.mz \
 *     --cache=.mz/cache.sqlite
 */
object DevRunner {

    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = parseArgs(args)

        val projectRoot = File(parsed["project-root"] ?: ".")
        val cachePath = File(parsed["cache"] ?: File(projectRoot, ".mz/cache.sqlite").absolutePath)
        val entryName = parsed["entry-name"] ?: "main"
        val inspect = parsed.containsKey("inspect")
        val inspectBrk = parsed.containsKey("inspect-brk")

        if (!cachePath.exists()) {
            System.err.println("Error: Cache not found at ${cachePath.absolutePath}")
            System.err.println("Run 'mez dev' to compile first.")
            System.exit(1)
        }

        val store = SQLiteMezzeStore.readOnly(cachePath)
        try {
            val functions = loadFunctions(store)
            val performers = loadPerformerRecords(store)

            if (functions.isEmpty()) {
                System.err.println("Error: No functions found in cache. Re-run 'mez dev' to recompile.")
                System.exit(1)
            }

            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println("[DevRunner] Loaded ${functions.size} functions, ${performers.size} performers from cache")
                for (fn in functions) {
                    System.err.println("[DevRunner]   fn: ${fn.hash.hex.take(16)}... name=${fn.name ?: "<anon>"}")
                }
                for ((i, pr) in performers.withIndex()) {
                    System.err.println("[DevRunner]   performer[$i]: ${pr.joinToString(", ") { it.take(16) + "..." }}")
                }
            }

            executeWithTruffle(functions, performers, projectRoot.toPath(), entryName, inspect, inspectBrk)
        } finally {
            store.close()
        }
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
            System.err.println("Open chrome://inspect in Chrome to connect")
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
