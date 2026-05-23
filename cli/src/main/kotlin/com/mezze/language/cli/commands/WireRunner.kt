package com.mezze.language.cli.commands

import com.mezze.language.cli.JvmLauncher
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedLetDecl
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter

/**
 * Client for the [com.mezze.interpreter.standalone.RuntimeServer] wire
 * protocol:
 *
 *   - parse + typecheck + lower the user app **here** (native mez)
 *   - serialize the closure + meta to two CBOR files
 *   - spawn JVM with main = RuntimeServer
 *   - send LOAD (and EVICT for hot-reload), stream program output,
 *     wait for DONE/ERR
 *   - send QUIT
 *
 * Once this is the default path, `:parser`, `:typechecker`, `:formatter`,
 * `:store-sqlite`, and `:lsp` can be excluded from `mezze-lang.jar`.
 *
 * Today: feature-gated behind `MEZZE_WIRE=1`. Old DevRunner path stays
 * the default until validated end-to-end.
 */
object WireRunner {

    /** Per-compile output we need to ship to RuntimeServer. */
    data class CompileResult(
        val fnsFile: File,
        val metaFile: File,
        val hashes: Set<Hash>,
    )

    /**
     * Compile + execute once via RuntimeServer. Returns process exit code.
     * Equivalent to the `--no-watch` path of `mez dev` / `mez exec`.
     */
    fun execOnce(
        currentDir: File,
        appFile: File,
        entryName: String,
        modulePath: String,
        appName: String,
    ): Int {
        val timing = System.getenv("MEZZE_TIMING") != null
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000

        if (!appFile.exists()) {
            System.err.println("Error: App file not found: ${appFile.absolutePath}")
            return 1
        }

        initStore(currentDir)
        val compile = compile(currentDir, appFile, entryName, appName, timing, t0) ?: return 4

        val session = ExecuteSession.start(currentDir, timing, t0) ?: return 1
        val exit = try {
            session.runLoad(compile, evict = null, timing = timing, t0 = t0)
        } finally {
            session.close()
        }
        return exit
    }

    /**
     * Watch mode — long-lived RuntimeServer; per-save: recompile, EVICT
     * changed hashes, LOAD new closure. The JVM Engine + Context stay
     * warm so Truffle's per-context caches survive (saves ~30–50 ms per
     * reload).
     *
     * Minimal first cut: re-uses the file-watch / keystroke handling
     * from DevRunner only conceptually — here we keep it simple and just
     * watch the entry-file's directory for `.mz` events. The existing
     * DevRunner watcher is richer (transitive-dep tracking, keyboard
     * controls); once this path is the default we'll port that over.
     */
    fun watch(
        currentDir: File,
        appFile: File,
        entryName: String,
        modulePath: String,
        appName: String,
    ): Int {
        val timing = System.getenv("MEZZE_TIMING") != null
        val t0 = System.nanoTime()

        if (!appFile.exists()) {
            System.err.println("Error: App file not found: ${appFile.absolutePath}")
            return 1
        }
        initStore(currentDir)

        val session = ExecuteSession.start(currentDir, timing, t0) ?: return 1
        var prevHashes: Set<Hash> = emptySet()

        try {
            // First run.
            System.err.println("[mez dev] $appName · ${appFile.name}")
            val first = compile(currentDir, appFile, entryName, appName, timing, t0)
            if (first != null) {
                session.runLoad(first, evict = null, timing = timing, t0 = t0)
                prevHashes = first.hashes
            }
            System.err.println("[mez dev] watching ${appFile.parentFile.absolutePath}")

            // Watch loop — minimal: parent dir only, debounce 50ms.
            val watcher = java.nio.file.FileSystems.getDefault().newWatchService()
            appFile.parentFile.toPath().register(
                watcher,
                java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
            )
            while (true) {
                val key = try { watcher.take() } catch (_: InterruptedException) { return 0 }
                var sawMz = false
                for (ev in key.pollEvents()) {
                    val name = ev.context()?.toString() ?: continue
                    if (name.endsWith(".mz")) sawMz = true
                }
                key.reset()
                if (!sawMz) continue
                // Debounce — macOS fires multiple events per save.
                Thread.sleep(50)
                while (watcher.poll() != null) { /* drain */ }

                val tReload = System.nanoTime()
                val next = compile(currentDir, appFile, entryName, appName, timing, tReload) ?: continue
                val evict = next.hashes - prevHashes
                System.err.println("[mez dev] reload — ${evict.size} new hashes")
                session.runLoad(next, evict = if (evict.isEmpty()) null else evict, timing = timing, t0 = tReload)
                prevHashes = next.hashes
            }
        } finally {
            session.close()
        }
    }

    /** Set up the project store + typechecker singletons. Idempotent per process. */
    private fun initStore(currentDir: File) {
        System.setProperty("mezze.project.root", currentDir.absolutePath)
        val store = SQLiteMezzeStore.openProject(currentDir)
        com.mezze.language.typechecker.PrecompiledStdlib.setStore(store)
        com.mezze.language.typechecker.ModuleResolver.setStore(store)
    }

    /**
     * Run the full compile path (parse + typecheck + lower + closure +
     * pack). Writes CBOR files under `.mz/run/` (overwriting per run).
     *
     * Errors are formatted + printed; returns null on failure.
     */
    private fun compile(
        currentDir: File,
        appFile: File,
        entryName: String,
        appName: String,
        timing: Boolean,
        t0: Long,
    ): CompileResult? {
        fun ms() = (System.nanoTime() - t0) / 1_000_000

        val source = appFile.readText()
        val parser = MezzeParser()
        val parseResult = parser.parseWithRecovery(source, file = appFile.name)
        if (parseResult.errors.isNotEmpty()) {
            System.err.println(ParseErrorFormatter.formatAll(parseResult.errors))
            return null
        }
        val ast = parseResult.module
        if (timing) System.err.println("[${ms()}ms] parsed")

        val typeChecker = TypeChecker()
        val rawResult = try {
            typeChecker.checkParsedModule(ast, currentDir.toPath(), enablePrelude = true)
        } catch (e: com.mezze.language.typechecker.TypeCheckError) {
            System.err.println(com.mezze.language.typechecker.printing.ErrorFormatter.format(e, source))
            return null
        }
        if (rawResult is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Failure) {
            System.err.println(TypeCheckRunner.formatErrors(rawResult, source))
            return null
        }
        val result = TypeCheckRunner.wrapSuccess(
            rawResult as com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success
        )
        if (timing) System.err.println("[${ms()}ms] typechecked + lowered (${result.loweredFunctions.size} fns)")

        val entry = result.typedModule?.decls
            ?.filterIsInstance<TypedLetDecl>()
            ?.find { it.name == entryName }
        if (entry == null) {
            System.err.println("Error: App must define a '$entryName' function with type `{} -> {} <*>`")
            return null
        }
        EntryPointCheck.errorMessage(EntryPointCheck.check(entry), entryName)?.let { msg ->
            System.err.println("Error: $msg")
            return null
        }

        val closure: List<FunctionIR> = NativeBuildPath.computeReachableClosure(result.loweredFunctions)
        val (fnBytes, metaBytes) = NativeBuildPath.packEmbeddedAppCbor(
            closure = closure,
            appName = appName,
            entryName = entryName,
            performerRecords = result.performerRecords,
        )
        val runDir = File(currentDir, ".mz/run").apply { mkdirs() }
        val fnsFile = File(runDir, "fns.cbor").apply { writeBytes(fnBytes) }
        val metaFile = File(runDir, "meta.cbor").apply { writeBytes(metaBytes) }
        if (timing) System.err.println("[${ms()}ms] packed (${fnBytes.size} + ${metaBytes.size} bytes)")
        return CompileResult(fnsFile, metaFile, closure.map { it.hash }.toSet())
    }

    /**
     * Long-lived handle to a RuntimeServer JVM subprocess. Multiple
     * LOADs can be sent across its lifetime; `close()` sends QUIT.
     */
    private class ExecuteSession private constructor(
        private val process: Process,
        private val toJvm: PrintWriter,
        private val fromJvm: BufferedReader,
        private val shutdownHook: Thread,
    ) {
        /**
         * Send LOAD (optionally preceded by EVICT) and stream output
         * until DONE/ERR. Returns 0 on success, 1 on error.
         */
        fun runLoad(compile: CompileResult, evict: Set<Hash>?, timing: Boolean, t0: Long): Int {
            fun ms() = (System.nanoTime() - t0) / 1_000_000

            if (evict != null && evict.isNotEmpty()) {
                toJvm.println("EVICT " + evict.joinToString(",") { it.hex })
            }
            toJvm.println("LOAD ${compile.fnsFile.absolutePath} ${compile.metaFile.absolutePath}")
            toJvm.flush()
            if (timing) System.err.println("[${ms()}ms] sent LOAD")

            var sawReady = false
            while (true) {
                val line = fromJvm.readLine() ?: run {
                    System.err.println("Error: RuntimeServer exited unexpectedly")
                    return 1
                }
                when {
                    line == "MEZ> READY" -> { sawReady = true; if (timing) System.err.println("[${ms()}ms] READY") }
                    line == "MEZ> DONE" -> {
                        if (timing) System.err.println("[${ms()}ms] DONE")
                        return if (sawReady) 0 else 1
                    }
                    line.startsWith("MEZ> ERR ") -> {
                        System.err.println(line.removePrefix("MEZ> ERR "))
                        return 1
                    }
                    else -> println(line)
                }
            }
            @Suppress("UNREACHABLE_CODE") return 0
        }

        fun close() {
            try { toJvm.println("QUIT"); toJvm.flush(); toJvm.close() } catch (_: Exception) {}
            try { process.waitFor() } catch (_: Exception) {}
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook) } catch (_: Exception) {}
        }

        companion object {
            fun start(currentDir: File, timing: Boolean, t0: Long): ExecuteSession? {
                fun ms() = (System.nanoTime() - t0) / 1_000_000

                val java = JvmLauncher.findJava() ?: run {
                    System.err.println("Error: Java 21+ required.")
                    return null
                }
                val classpath = JvmLauncher.buildClasspath()
                val command = mutableListOf(java.absolutePath)
                command.addAll(jvmArgs())
                command.add("-Dmezze.project.root=${currentDir.absolutePath}")
                command.add("-cp"); command.add(classpath)
                command.add("com.mezze.interpreter.standalone.RuntimeServer")

                val process = ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                val hook = Thread { if (process.isAlive) process.destroy() }
                Runtime.getRuntime().addShutdownHook(hook)

                val toJvm = PrintWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8), false)
                val fromJvm = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                if (timing) System.err.println("[${ms()}ms] RuntimeServer spawned")
                return ExecuteSession(process, toJvm, fromJvm, hook)
            }
        }
    }

    private fun jvmArgs(): List<String> = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
        "-Dpolyglot.engine.WarnInterpreterOnly=false",
    )
}
