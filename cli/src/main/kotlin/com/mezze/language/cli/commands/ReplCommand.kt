package com.mezze.language.cli.commands

import com.mezze.language.cli.`package`.MezzeToml
import com.mezze.wire.EvalError
import com.mezze.wire.WireListener
import com.mezze.wire.WireProtocol
import com.mezze.wire.WireValue
import com.mezze.wire.toText
import com.akuleshov7.ktoml.Toml
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * `mez repl` — an interactive session where definitions accumulate.
 *
 * ## Why this is mostly a client
 *
 * The runtime cannot compile — that is the two-layer split [EvalClosure] documents
 * — so a Mezze REPL is not a runtime that remembers your `let`s. It is a CLIENT
 * that remembers them: every definition you type is kept HERE, and each new line is
 * compiled with all the earlier ones appended to the module's source (exactly the
 * `extraDecls` path `mez eval` already uses for a single expression). The runtime
 * only executes the resulting self-contained closure.
 *
 * ## Why there is a session id even though scope is client-side
 *
 * Accumulated DEFINITIONS are re-shipped each line, so the runtime need not keep
 * them. What the runtime DOES keep, per [com.mezze.wire.Eval.session], is RUNTIME
 * state a line leaves behind — an effect store, a resource — so a later line sees
 * it. The id also isolates this REPL's world from the sampler's (which runs with a
 * null session) and from the program `mez dev` is running. One id is minted per
 * REPL process and dropped (CLOSE_SESSION) on the way out.
 *
 * ## Warm or cold
 *
 * If `mez dev` is running it publishes `.mez/session.json`; the REPL attaches to
 * that warm runtime (the project's own devenv toolchain, no JDK of its own needed).
 * Otherwise it cold-spawns a one-shot runtime for the session (which needs a JDK).
 */
class ReplCommand {

    /** Accumulated imports, in order, de-duplicated — a `use` belongs at the top. */
    private val imports = LinkedHashSet<String>()

    /**
     * Accumulated definitions, keyed by the name they bind so a redefinition
     * SHADOWS the earlier one rather than colliding with it (`let x = 1` then
     * `let x = 2` is a REPL staple, and two top-level `x`es would be "already
     * defined"). A decl with no simple name (an `impl`) is keyed by its own text,
     * so re-entering the identical impl is a no-op and a different one is added.
     */
    private val defs = LinkedHashMap<String, String>()

    private val sessionId = "repl-${(100_000..999_999).random()}"
    private var requestId = 1L

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val flags = args.filter { it.startsWith("--") }
        if ("--help" in flags || "-h" in args) { printUsage(); return }

        val depth = intFlag(flags, "--depth") ?: WireProtocol.DEFAULT_EVAL_MAX_DEPTH
        val items = intFlag(flags, "--items") ?: WireProtocol.DEFAULT_EVAL_MAX_ITEMS
        val timeoutMs = intFlag(flags, "--timeout")?.toLong()?.times(1_000) ?: 0L

        val moduleFile = resolveAppFile(currentDir) ?: kotlin.system.exitProcess(1)
        val conn = connect(currentDir) ?: kotlin.system.exitProcess(1)

        // Drop the runtime's session world and close the link however we leave —
        // Ctrl-C, Ctrl-D, or `:quit` — so a long-lived `mez dev` does not accrue a
        // dead context per REPL that ever ran against it.
        val shutdown = Thread { try { conn.endSession(sessionId); conn.close() } catch (_: Exception) {} }
        Runtime.getRuntime().addShutdownHook(shutdown)

        banner(conn, moduleFile)
        val reader = BufferedReader(InputStreamReader(System.`in`))
        try {
            while (true) {
                val input = readLogicalInput(reader) ?: break   // EOF (Ctrl-D)
                val trimmed = input.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith(":")) {
                    if (handleMeta(trimmed, currentDir, moduleFile, conn, depth, items, timeoutMs)) break
                    continue
                }
                handleLine(input, currentDir, moduleFile, conn, depth, items, timeoutMs)
            }
        } finally {
            Runtime.getRuntime().removeShutdownHook(shutdown)
            try { conn.endSession(sessionId) } catch (_: Exception) {}
            conn.close()
        }
        println("${Glyphs.CHECK} bye")
    }

    // ── one line of input ────────────────────────────────────────────────

    private fun handleLine(
        input: String,
        currentDir: File,
        moduleFile: File,
        conn: Conn,
        depth: Int,
        items: Int,
        timeoutMs: Long,
    ) {
        when (val cls = ReplClassifier.classify(input)) {
            is ReplClassifier.Input.SyntaxError -> System.err.println(cls.message)

            is ReplClassifier.Input.Import -> {
                // Validate in context before keeping it: a `use` of something that
                // does not exist should fail HERE, not poison every later line.
                val added = cls.lines.filterNot { it in imports }
                imports.addAll(added)
                val probe = EvalClosure.compile(currentDir, moduleFile, "0", imports, defsText())
                if (probe is EvalClosure.Result.Failed) {
                    imports.removeAll(added.toSet())
                    System.err.println(probe.message)
                } else {
                    cls.lines.forEach { println("  ${Glyphs.CHECK} $it") }
                }
            }

            is ReplClassifier.Input.Definition -> acceptDefinition(cls, currentDir, moduleFile, conn, depth, items, timeoutMs)

            is ReplClassifier.Input.Expression ->
                evalAndPrint(input, currentDir, moduleFile, conn, depth, items, timeoutMs, showType = true)
        }
    }

    /**
     * Keep a definition only if the module still compiles with it in scope, and
     * for a single `let` also show its value — one compile does both, so a good
     * definition is not type-checked twice.
     */
    private fun acceptDefinition(
        def: ReplClassifier.Input.Definition,
        currentDir: File,
        moduleFile: File,
        conn: Conn,
        depth: Int,
        items: Int,
        timeoutMs: Long,
    ) {
        // Snapshot so a rejected definition leaves the old scope exactly intact.
        val previous = def.keys.associateWith { defs[it] }
        val addedImports = def.importLines.filterNot { it in imports }
        imports.addAll(addedImports)
        def.keys.forEachIndexed { i, k -> defs[k] = def.texts[i] }

        val valueName = def.singleLetName
        val expr = valueName ?: "0"   // a value def shows its value; any other, a probe
        when (val compiled = EvalClosure.compile(currentDir, moduleFile, expr, imports, defsText())) {
            is EvalClosure.Result.Failed -> {
                // Roll back to exactly what was there before.
                def.keys.forEach { k -> val p = previous[k]; if (p == null) defs.remove(k) else defs[k] = p }
                imports.removeAll(addedImports.toSet())
                System.err.println(compiled.message)
            }
            is EvalClosure.Result.Compiled -> {
                if (valueName != null) {
                    val r = runCompiled(conn, currentDir, compiled, depth, items, timeoutMs)
                    val v = r?.value
                    if (r?.error != null) { printError(r.error!!) }
                    else if (v != null) println("  $valueName : ${compiled.resultType} = ${v.toText()}")
                    else println("  ${Glyphs.CHECK} $valueName")
                } else {
                    def.names.forEach { println("  ${Glyphs.CHECK} $it") }
                }
            }
        }
    }

    private fun evalAndPrint(
        expression: String,
        currentDir: File,
        moduleFile: File,
        conn: Conn,
        depth: Int,
        items: Int,
        timeoutMs: Long,
        showType: Boolean,
    ) {
        when (val compiled = EvalClosure.compile(currentDir, moduleFile, expression, imports, defsText())) {
            is EvalClosure.Result.Failed -> System.err.println(compiled.message)
            is EvalClosure.Result.Compiled -> {
                val r = runCompiled(conn, currentDir, compiled, depth, items, timeoutMs) ?: return
                val error = r.error
                if (error != null) { printError(error); return }
                val value = r.value ?: run {
                    System.err.println("${Glyphs.CROSS} the runtime replied with neither a value nor an error")
                    return
                }
                print(value.toText())
                if (showType) print("  : ${compiled.resultType}")
                println()
                if (hasElided(value)) {
                    println("  ${Glyphs.TIP} some branches were elided at depth $depth — raise it with :depth or --depth=N next time")
                }
            }
        }
    }

    private fun runCompiled(
        conn: Conn,
        currentDir: File,
        compiled: EvalClosure.Result.Compiled,
        depth: Int,
        items: Int,
        timeoutMs: Long,
    ): com.mezze.wire.EvalResult? {
        val runDir = com.mezze.language.store.ProjectLayout.runDir(currentDir)
        val (fnsFile, metaFile) = EvalClosure.writeTo(runDir, compiled)
        return conn.eval(fnsFile, metaFile, requestId++, depth, items, timeoutMs, sessionId)
    }

    /** The accumulated definitions, oldest first — the order they were entered. */
    private fun defsText(): List<String> = defs.values.toList()

    // ── meta-commands ─────────────────────────────────────────────────────

    /** Returns true when the REPL should exit. */
    private fun handleMeta(
        line: String,
        currentDir: File,
        moduleFile: File,
        conn: Conn,
        depth: Int,
        items: Int,
        timeoutMs: Long,
    ): Boolean {
        val cmd = line.substringBefore(' ').lowercase()
        val rest = line.substringAfter(' ', "").trim()
        when (cmd) {
            ":quit", ":q", ":exit" -> return true
            ":help", ":h", ":?" -> printReplHelp()
            ":reset" -> { imports.clear(); defs.clear(); println("  ${Glyphs.BROOM} scope cleared") }
            ":scope" -> printScope()
            ":type", ":t" ->
                if (rest.isEmpty()) System.err.println("  usage: :type <expression>")
                else when (val c = EvalClosure.compile(currentDir, moduleFile, rest, imports, defsText())) {
                    is EvalClosure.Result.Failed -> System.err.println(c.message)
                    is EvalClosure.Result.Compiled -> println("  $rest : ${c.resultType}")
                }
            else -> System.err.println("  unknown command `$cmd` — `:help` lists them")
        }
        return false
    }

    private fun printScope() {
        if (imports.isEmpty() && defs.isEmpty()) { println("  (nothing defined yet)"); return }
        if (imports.isNotEmpty()) { println("  imports:"); imports.forEach { println("    $it") } }
        if (defs.isNotEmpty()) { println("  definitions:"); defs.keys.forEach { println("    $it") } }
    }

    // ── connection ────────────────────────────────────────────────────────

    private sealed interface Conn : AutoCloseable {
        fun eval(fns: File, meta: File, reqId: Long, depth: Int, items: Int, timeoutMs: Long, session: String): com.mezze.wire.EvalResult?
        /** Drop the runtime's world for [session]. Meaningful only on a shared runtime. */
        fun endSession(session: String) {}
    }

    private class Warm(private val rt: RuntimeSession) : Conn {
        override fun eval(fns: File, meta: File, reqId: Long, depth: Int, items: Int, timeoutMs: Long, session: String) =
            rt.evalOnce(fns, meta, reqId, depth, items, timeoutMs, session = session)
        override fun endSession(session: String) = rt.closeSession(session)
        override fun close() = rt.close()
    }

    private class Cold(private val ws: WireSession) : Conn {
        override fun eval(fns: File, meta: File, reqId: Long, depth: Int, items: Int, timeoutMs: Long, session: String) =
            ws.evalOnce(fns, meta, reqId, depth, items, timeoutMs, session = session)
        // Closing the whole session kills this cold runtime's JVM, so its contexts
        // go with it — no CLOSE_SESSION needed.
        override fun close() = ws.close()
    }

    private var warm = false

    private fun connect(currentDir: File): Conn? {
        val sessionFile = File(currentDir, ".mez/session.json")
        WireListener.readSessionFile(sessionFile)?.let { session ->
            RuntimeSession.connect(session, client = "mez-repl")?.let { warm = true; return Warm(it) }
            // A stale session file (dev gone) is not fatal — fall through to cold.
            System.err.println("${Glyphs.WARN} a `.mez/session.json` is present but unreachable — starting a private runtime instead")
        }
        val ws = WireSession.start(currentDir, timing = false, t0 = System.nanoTime()) ?: return null
        return Cold(ws)
    }

    private fun banner(conn: Conn, moduleFile: File) {
        val where = if (warm) "attached to the running mez dev session" else "running a private runtime (no mez dev session found)"
        println("mez repl — $where")
        println("scope: ${moduleFile.name}. Type an expression, `let`/`type`/… to define, `:help` for commands, Ctrl-D to exit.")
    }

    // ── input reading with delimiter-aware continuation ────────────────────

    /**
     * One logical input: lines joined until the bracket delimiters balance, so a
     * record or list written across lines arrives whole. Returns null at EOF.
     *
     * Bracket-balance only — `do` blocks are indentation-delimited and are entered
     * on one line for now. A balanced line that still will not parse is reported as
     * the syntax error it is, rather than hanging waiting for more.
     */
    private fun readLogicalInput(reader: BufferedReader): String? {
        val sb = StringBuilder()
        print(if (sb.isEmpty()) PROMPT else CONT)
        System.out.flush()
        while (true) {
            val line = reader.readLine() ?: return sb.takeIf { it.isNotEmpty() }?.toString()
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
            if (delimiterBalance(sb) <= 0) return sb.toString()
            print(CONT); System.out.flush()
        }
    }

    /** Net unclosed `([{`, ignoring delimiters inside strings and `#` comments. */
    private fun delimiterBalance(s: CharSequence): Int {
        var depth = 0; var inStr = false; var escaped = false; var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                inStr -> when { escaped -> escaped = false; c == '\\' -> escaped = true; c == '"' -> inStr = false }
                c == '"' -> inStr = true
                c == '#' -> { while (i < s.length && s[i] != '\n') i++; continue }
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
            }
            i++
        }
        return depth
    }

    // ── rendering ───────────────────────────────────────────────────────

    private fun printError(error: EvalError) {
        val headline = when (error.kind) {
            EvalError.Kind.TIMEOUT -> "the expression did not finish in time"
            EvalError.Kind.CANCELLED -> "the evaluation was cancelled"
            EvalError.Kind.FAILED -> "the expression failed while running"
            EvalError.Kind.UNRENDERABLE -> "the value could not be rendered"
            else -> "the evaluation did not produce a value (${error.kind})"
        }
        System.err.println("${Glyphs.CROSS} $headline")
        error.detail.message.takeIf { it.isNotBlank() }?.let { System.err.println("  $it") }
        for (frame in error.detail.stack) {
            val where = listOfNotNull(frame.file, frame.line?.toString()).joinToString(":")
            System.err.println("    at ${frame.function}${if (where.isNotEmpty()) " ($where)" else ""}")
        }
    }

    private fun hasElided(value: WireValue): Boolean = when (value) {
        is WireValue.Elided -> true
        is WireValue.Newtype -> hasElided(value.inner)
        is WireValue.Record -> value.fields.any { hasElided(it.value) }
        is WireValue.Variant -> value.payload?.let { hasElided(it) } ?: false
        is WireValue.Sequence -> value.omitted > 0 || value.items.any { hasElided(it) }
        is WireValue.Mapping -> value.omitted > 0 || value.entries.any { hasElided(it.key) || hasElided(it.value) }
        is WireValue.Scalar, is WireValue.Fn -> false
    }

    // ── project resolution ────────────────────────────────────────────────

    private fun resolveAppFile(currentDir: File): File? {
        val toml = File(currentDir, "Mezze.toml")
        if (!toml.exists()) {
            System.err.println("${Glyphs.CROSS} no Mezze.toml in ${currentDir.absolutePath}")
            System.err.println("   `mez repl` evaluates in this project's app module; run `mez init` first.")
            return null
        }
        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), toml.readText())
        } catch (e: Exception) {
            System.err.println("${Glyphs.CROSS} Mezze.toml could not be read: ${e.message}")
            return null
        }
        val app = manifest.app?.firstOrNull() ?: run {
            System.err.println("${Glyphs.CROSS} ${manifest.`package`.name} declares no [[app]], so there is no module to evaluate against")
            return null
        }
        val file = File(currentDir, app.path)
        if (!file.exists()) {
            System.err.println("${Glyphs.CROSS} app file not found: ${file.absolutePath}")
            return null
        }
        return file
    }

    private fun intFlag(flags: List<String>, name: String): Int? =
        flags.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.toIntOrNull()

    private fun printReplHelp() {
        println("""
            |  commands:
            |    :help              this list
            |    :type <expr>       the expression's type, without evaluating it
            |    :scope             the imports and definitions in scope
            |    :reset             forget every definition
            |    :quit              leave (same as Ctrl-D)
            |
            |  Anything else is Mezze: an expression is evaluated, a `use` / `let` /
            |  `type` / `effect` / `ability` / `impl` is kept in scope. Redefining a
            |  name shadows the earlier one.
        """.trimMargin())
    }

    private fun printUsage() {
        println("USAGE: mez repl [--depth=N] [--items=N] [--timeout=SECONDS]")
        println()
        println("An interactive session in your app module's scope where definitions accumulate.")
        println("Attaches to a running `mez dev` when there is one; otherwise starts its own runtime.")
    }

    private companion object {
        const val PROMPT = "mez> "
        const val CONT = "   | "
    }
}
