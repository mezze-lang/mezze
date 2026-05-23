package com.mezze.interpreter.standalone

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.store.StoreSerialization
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Paths

/**
 * Long-lived JVM-side runtime that the native `mez` binary spawns for
 * `mez dev` / `mez exec`. Native mez does all compile work (parse +
 * typecheck + lower) and ships pre-built FunctionIR closures to this
 * process. The JVM only executes.
 *
 * This lets `mezze-lang.jar` ship without `:parser`, `:typechecker`,
 * `:formatter`, `:lsp`, or `:store-sqlite` — every compile-side
 * concern lives in the native binary now.
 *
 * Wire protocol (one command per line on stdin; status replies on
 * stdout prefixed with `MEZ>`, user program output is inherited):
 *
 *   EVICT <hex>,<hex>,...    optional, before LOAD; evicts CallTarget
 *                            cache entries for hashes whose bodies
 *                            changed (mirrors what DevRunner used to
 *                            do via MezzeLanguage.setHashesToInvalidate).
 *
 *   LOAD <fns.cbor> <meta.cbor> [project-root]
 *                            load closure + meta from the given paths,
 *                            register with MezzeLanguage, eval entry.
 *                            Replies `MEZ> READY` then program output,
 *                            then `MEZ> DONE` on success or
 *                            `MEZ> ERR <one-line>` on failure.
 *
 *   QUIT                     close context + exit 0.
 *
 * Paths are absolute on the native-mez side. CBOR shape matches
 * [StoreSerialization.deserializeFunctionIRList] and
 * [StoreSerialization.deserializeEmbeddedAppMeta] — the same format
 * `mez build` already packs for the standalone-jar path.
 */
object RuntimeServer {

    private const val OK_READY = "MEZ> READY"
    private const val OK_DONE = "MEZ> DONE"
    private const val ERR_PREFIX = "MEZ> ERR "

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        System.setProperty("polyglot.log.level", "OFF")

        // JIT is off by default for `mez dev` — hot-reload edits invalidate
        // compiled code, and per-save warmup hurts the dev loop. Users can
        // flip MEZZE_JIT=1 for long sessions. Matches what DevRunner used.
        val jitEnabled = System.getenv("MEZZE_JIT")
            ?.let { it == "1" || it.equals("true", ignoreCase = true) }
            ?: false

        val engine = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .option("engine.Compilation", jitEnabled.toString())
            .build()

        val context = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine)
            .allowAllAccess(true)
            .allowCreateThread(true)
            .allowExperimentalOptions(true)
            .build()

        // Truffle installs its own engine-close shutdown hook; don't double-up.
        val runCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val reader = BufferedReader(InputStreamReader(System.`in`))

        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val trimmed = line.trim()
                when {
                    trimmed == "QUIT" -> return
                    trimmed.startsWith("EVICT ") -> handleEvict(trimmed.removePrefix("EVICT ").trim())
                    trimmed.startsWith("LOAD ") -> handleLoad(trimmed.removePrefix("LOAD ").trim(), context, runCounter)
                    else -> reply(ERR_PREFIX + "unknown command: $trimmed")
                }
            }
        } finally {
            try { context.close(true) } catch (_: Exception) {}
        }
    }

    private fun handleEvict(payload: String) {
        if (payload.isEmpty()) return
        val hashes = payload.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { hex ->
                try { Hash(hex) } catch (_: Exception) { null }
            }
        if (hashes.isNotEmpty()) MezzeLanguage.setHashesToInvalidate(hashes)
    }

    private fun handleLoad(payload: String, context: Context, runCounter: java.util.concurrent.atomic.AtomicInteger) {
        // `<fns.cbor> <meta.cbor> [project-root]` — paths may not contain
        // spaces (we own both sides; native mez writes to .mz/run/). The
        // optional `project-root` arg is for callers that don't set
        // `mezze.project.root` via -D before spawn.
        val parts = payload.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) {
            reply(ERR_PREFIX + "LOAD needs <fns.cbor> <meta.cbor> [project-root]")
            return
        }
        val fnsFile = File(parts[0])
        val metaFile = File(parts[1])
        val projectRoot = when {
            parts.size >= 3 -> Paths.get(parts[2])
            else -> System.getProperty("mezze.project.root")?.let { Paths.get(it) } ?: Paths.get(".")
        }

        if (!fnsFile.exists()) { reply(ERR_PREFIX + "fns cbor not found: ${fnsFile.absolutePath}"); return }
        if (!metaFile.exists()) { reply(ERR_PREFIX + "meta cbor not found: ${metaFile.absolutePath}"); return }

        val functions: List<FunctionIR>
        val meta: StoreSerialization.DeserializedEmbeddedAppMeta
        try {
            functions = StoreSerialization.deserializeFunctionIRList(fnsFile.readBytes())
            meta = StoreSerialization.deserializeEmbeddedAppMeta(metaFile.readBytes())
        } catch (e: Exception) {
            reply(ERR_PREFIX + "decode failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (functions.isEmpty()) { reply(ERR_PREFIX + "empty function closure"); return }

        try {
            val entryName = meta.entryName
            if (entryName != null) {
                MezzeLanguage.setFunctionsForDirectExecution(functions, meta.performerRecords, projectRoot, entryName)
            } else {
                MezzeLanguage.setFunctionsForDirectExecution(functions, meta.performerRecords, projectRoot)
            }
            val runId = runCounter.incrementAndGet()
            val source = Source.newBuilder(
                MezzeLanguage.ID,
                "# RuntimeServer $runId",
                "__runtime_server_${runId}__.mz"
            ).buildLiteral()
            reply(OK_READY)
            context.eval(source)
            reply(OK_DONE)
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            reply(ERR_PREFIX + (e.message?.lineSequence()?.firstOrNull() ?: "polyglot error"))
        } catch (e: Exception) {
            reply(ERR_PREFIX + "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }

    private fun reply(line: String) {
        // Status goes on stdout; user program output already goes through
        // the Console handler to stdout too. The MEZ> prefix lets native
        // mez distinguish protocol replies from program output without a
        // separate fd. (Stderr is reserved for boot diagnostics.)
        println(line)
        System.out.flush()
    }
}
