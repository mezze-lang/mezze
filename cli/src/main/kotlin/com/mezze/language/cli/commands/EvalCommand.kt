package com.mezze.language.cli.commands

import com.mezze.language.cli.`package`.MezzeToml
import com.mezze.wire.EvalError
import com.mezze.wire.WireProtocol
import com.mezze.wire.WireValue
import com.mezze.wire.toText
import com.akuleshov7.ktoml.Toml
import java.io.File

/**
 * `mez eval '<expr>'` — evaluate one expression in a project's scope and print its
 * value.
 *
 * Distinct from `mez exec`, which runs a program and gives you its console output
 * and an exit code. This asks for a VALUE: the expression is compiled against the
 * app module, run, and the result comes back as a structured tree that is printed
 * here — or handed over as JSON for a caller that wants the structure.
 *
 * ## What "in scope" means
 *
 * The app module's scope, exactly: the expression is compiled by appending a
 * binding to that module's source, so every import and every top-level
 * declaration is visible. See [EvalClosure].
 *
 * ## It evaluates SAVED code, and says so
 *
 * The expression is compiled against the module as it is ON DISK. From a terminal
 * that is the only meaning available, so nothing is hidden — but an editor-driven
 * eval would be compiling code the user may not be looking at, and there is no
 * wire field that can detect it (the IR closure is self-contained and every
 * runtime cache is content-hash keyed, so nothing stale is reachable). The honesty
 * has to live wherever the store-versus-buffer distinction is visible, which is
 * not here.
 *
 * ## Definitions do not accumulate
 *
 * Each invocation compiles the module plus one expression and discards it. There
 * is no session in which `let x = …` persists, which keeps every question about
 * hashing a REPL binding out of scope until something needs it.
 */
class EvalCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val flags = args.filter { it.startsWith("--") }
        val positional = args.filter { !it.startsWith("--") }
        val json = "--json" in flags
        val showType = "--type" in flags

        if (positional.isEmpty()) {
            printUsage()
            kotlin.system.exitProcess(1)
        }
        val expression = positional.joinToString(" ")

        val depth = intFlag(flags, "--depth") ?: WireProtocol.DEFAULT_EVAL_MAX_DEPTH
        val items = intFlag(flags, "--items") ?: WireProtocol.DEFAULT_EVAL_MAX_ITEMS
        val timeoutMs = intFlag(flags, "--timeout")?.toLong()?.times(1_000) ?: 0L

        val moduleFile = resolveAppFile(currentDir) ?: kotlin.system.exitProcess(1)

        when (val compiled = EvalClosure.compile(currentDir, moduleFile, expression)) {
            is EvalClosure.Result.Failed -> {
                // A type error in an expression is an ordinary compiler
                // diagnostic, reported here — it never reaches the runtime,
                // because this side is what compiles.
                System.err.println(compiled.message)
                kotlin.system.exitProcess(1)
            }

            is EvalClosure.Result.Compiled -> {
                val runDir = com.mezze.language.store.ProjectLayout.runDir(currentDir)
                val (fnsFile, metaFile) = EvalClosure.writeTo(runDir, compiled)

                val session = WireSession.start(currentDir, timing = false, t0 = System.nanoTime())
                    ?: kotlin.system.exitProcess(1)
                val result = try {
                    session.evalOnce(
                        fnsFile = fnsFile,
                        metaFile = metaFile,
                        requestId = 1,
                        maxDepth = depth,
                        maxItems = items,
                        timeoutMs = timeoutMs,
                    )
                } finally {
                    session.close()
                }

                if (result == null) {
                    // The runtime died; it already said why on stderr. Saying
                    // nothing more beats inventing a second explanation.
                    kotlin.system.exitProcess(1)
                }

                val error = result.error
                if (error != null) {
                    if (json) println(errorJson(error)) else printError(error)
                    kotlin.system.exitProcess(1)
                }

                val value = result.value
                if (value == null) {
                    System.err.println(
                        "${Glyphs.CROSS} the runtime replied with neither a value nor an error"
                    )
                    kotlin.system.exitProcess(1)
                }

                if (json) {
                    println(valueJson(value, compiled.resultType, result.elapsedMicros))
                } else {
                    println(value.toText())
                    if (showType) println("  : ${compiled.resultType}")
                    if (hasElided(value)) {
                        // Silence here would make a truncated value read as a
                        // complete one.
                        println(
                            "  ${Glyphs.TIP} some branches were elided at depth $depth — " +
                                "raise it with --depth=N"
                        )
                    }
                }
            }
        }
    }

    private fun intFlag(flags: List<String>, name: String): Int? =
        flags.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
            ?.toIntOrNull()

    /** The app module `mez dev` would run — the scope a user means by "my code". */
    private fun resolveAppFile(currentDir: File): File? {
        val toml = File(currentDir, "Mezze.toml")
        if (!toml.exists()) {
            System.err.println("${Glyphs.CROSS} no Mezze.toml in ${currentDir.absolutePath}")
            System.err.println("   `mez eval` compiles against this project's app module; run `mez init` first.")
            return null
        }
        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), toml.readText())
        } catch (e: Exception) {
            System.err.println("${Glyphs.CROSS} Mezze.toml could not be read: ${e.message}")
            return null
        }
        val app = manifest.app?.firstOrNull() ?: run {
            System.err.println(
                "${Glyphs.CROSS} ${manifest.`package`.name} declares no [[app]], " +
                    "so there is no module to evaluate against"
            )
            return null
        }
        val file = File(currentDir, app.path)
        if (!file.exists()) {
            System.err.println("${Glyphs.CROSS} app file not found: ${file.absolutePath}")
            return null
        }
        return file
    }

    private fun printError(error: EvalError) {
        val headline = when (error.kind) {
            EvalError.Kind.TIMEOUT -> "the expression did not finish in time"
            EvalError.Kind.CANCELLED -> "the evaluation was cancelled"
            EvalError.Kind.FAILED -> "the expression failed while running"
            EvalError.Kind.UNRENDERABLE -> "the value could not be rendered"
            EvalError.Kind.NOT_FOUND, EvalError.Kind.DECODE_FAILED, EvalError.Kind.NO_ENTRY ->
                "the runtime could not load the compiled expression"
            else -> "the evaluation did not produce a value (${error.kind})"
        }
        System.err.println("${Glyphs.CROSS} $headline")
        error.detail.message.takeIf { it.isNotBlank() }?.let { System.err.println("  $it") }
        for (frame in error.detail.stack) {
            val where = listOfNotNull(frame.file, frame.line?.toString()).joinToString(":")
            System.err.println("    at ${frame.function}${if (where.isNotEmpty()) " ($where)" else ""}")
        }
    }

    /** True when any branch of the tree was cut, so the printer can say so. */
    private fun hasElided(value: WireValue): Boolean = when (value) {
        is WireValue.Elided -> true
        is WireValue.Newtype -> hasElided(value.inner)
        is WireValue.Record -> value.fields.any { hasElided(it.value) }
        is WireValue.Variant -> value.payload?.let { hasElided(it) } ?: false
        is WireValue.Sequence -> value.omitted > 0 || value.items.any { hasElided(it) }
        is WireValue.Mapping ->
            value.omitted > 0 || value.entries.any { hasElided(it.key) || hasElided(it.value) }
        is WireValue.Scalar, is WireValue.Fn -> false
    }

    // ---- JSON ---------------------------------------------------------------
    //
    // The tree, not its rendering: a caller asking for JSON wants the structure,
    // and handing it a string it would have to re-parse defeats the point. `text`
    // is included alongside so a consumer that only wants to display something
    // does not have to fold the tree itself.

    private fun valueJson(value: WireValue, type: String, elapsedMicros: Long): String = buildString {
        append("{\"ok\":true,\"type\":").append(quote(type))
        append(",\"text\":").append(quote(value.toText()))
        append(",\"elapsed_micros\":").append(elapsedMicros)
        append(",\"value\":").append(treeJson(value))
        append("}")
    }

    private fun errorJson(error: EvalError): String = buildString {
        append("{\"ok\":false,\"kind\":").append(quote(error.kind))
        append(",\"message\":").append(quote(error.detail.message))
        append(",\"stack\":[")
        error.detail.stack.forEachIndexed { i, f ->
            if (i > 0) append(",")
            append("{\"function\":").append(quote(f.function))
            f.file?.let { append(",\"file\":").append(quote(it)) }
            f.line?.let { append(",\"line\":").append(it) }
            append("}")
        }
        append("]}")
    }

    private fun treeJson(value: WireValue): String = when (value) {
        is WireValue.Scalar ->
            "{\"node\":\"scalar\",\"kind\":${quote(value.kind)},\"text\":${quote(value.text)}}"

        is WireValue.Newtype ->
            "{\"node\":\"newtype\",\"name\":${quote(value.name)},\"inner\":${treeJson(value.inner)}}"

        is WireValue.Record -> buildString {
            append("{\"node\":\"record\",\"fields\":[")
            value.fields.forEachIndexed { i, f ->
                if (i > 0) append(",")
                append("{\"name\":").append(quote(f.name))
                append(",\"value\":").append(treeJson(f.value)).append("}")
            }
            append("]}")
        }

        is WireValue.Variant -> buildString {
            append("{\"node\":\"variant\",\"constructor\":").append(quote(value.constructor))
            value.payload?.let { append(",\"payload\":").append(treeJson(it)) }
            append("}")
        }

        is WireValue.Sequence -> buildString {
            append("{\"node\":\"seq\",\"kind\":").append(quote(value.kind))
            append(",\"omitted\":").append(value.omitted)
            append(",\"items\":[")
            value.items.forEachIndexed { i, item ->
                if (i > 0) append(",")
                append(treeJson(item))
            }
            append("]}")
        }

        is WireValue.Mapping -> buildString {
            append("{\"node\":\"map\",\"kind\":").append(quote(value.kind))
            append(",\"omitted\":").append(value.omitted)
            append(",\"entries\":[")
            value.entries.forEachIndexed { i, e ->
                if (i > 0) append(",")
                append("{\"key\":").append(treeJson(e.key))
                append(",\"value\":").append(treeJson(e.value)).append("}")
            }
            append("]}")
        }

        is WireValue.Fn ->
            "{\"node\":\"fn\"" + (value.name?.let { ",\"name\":${quote(it)}" } ?: "") + "}"

        // `path` is what an EXPAND names, so a JSON consumer can ask for this
        // branch specifically rather than re-running at a larger depth.
        is WireValue.Elided ->
            "{\"node\":\"elided\",\"path\":${quote(value.path)},\"hint\":${quote(value.hint)}}"
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private fun printUsage() {
        println("USAGE: mez eval '<expression>' [--type] [--json] [--depth=N] [--items=N] [--timeout=SECONDS]")
        println()
        println("Evaluate one expression in your app module's scope and print its VALUE.")
        println("Every import and top-level declaration in that module is in scope.")
        println()
        println("EXAMPLES:")
        println("  mez eval '1 + 2'")
        println("  mez eval 'greet { name = \"world\" }'   call your own function")
        println("  mez eval '[1, 2, 3]' --type            show the type too")
        println("  mez eval 'big_value' --depth=2         stop at depth 2")
        println("  mez eval 'config' --json               the value as a tree")
        println()
        println("Evaluates the code as SAVED on disk. Definitions do not accumulate between runs.")
    }
}
