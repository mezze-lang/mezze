package com.mezze.language.cli.commands

import com.mezze.language.cli.`package`.MezzeToml
import com.mezze.wire.WireProtocol
import com.mezze.wire.WireValue
import com.mezze.wire.toText
import com.akuleshov7.ktoml.Toml
import java.io.File

/**
 * `mez sample <fn>` — generate arguments for a function, run it, show both.
 *
 * It takes a function and nothing else. No generators to supply, no types to name:
 * the arguments come from the `Arbitrary` instances the compiler already derived,
 * because a Mezze function's parameter is an ordinary record and derives like any
 * other. That is the whole reason this command needs no type-directed code of its
 * own — see `std::abilities::arbitrary`.
 *
 * ## The seed is printed because it is the only reproducible thing here
 *
 * Every run draws different values, so a sample that surprised you is worthless if
 * you cannot get it back. The seed is chosen here, printed, and accepted via
 * `--seed`, which makes "run this again" a copy-paste rather than a reconstruction.
 * It is the same property `mez eval` does not need and this command cannot do
 * without.
 *
 * ## Why there is no way to supply handlers
 *
 * A function with an undischarged effect is refused rather than run — see
 * [RunnabilityCheck], which carries the reasoning. Short version: running it
 * unhandled does not fail, it returns a suspended computation that renders as
 * something plausible, which for a command whose job is showing what a function
 * does is worse than any error.
 */
class SampleCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val flags = args.filter { it.startsWith("--") }
        val positional = args.filter { !it.startsWith("--") }
        val json = "--json" in flags

        if (positional.isEmpty()) {
            printUsage()
            kotlin.system.exitProcess(1)
        }
        if (positional.size > 1) {
            System.err.println(
                "${Glyphs.CROSS} sample takes ONE function name, got ${positional.size}: " +
                    positional.joinToString(", ")
            )
            kotlin.system.exitProcess(1)
        }
        val name = positional.single()

        // A seed the user did not give is chosen HERE rather than in the runtime, so
        // that the number printed is provably the number used — a runtime-chosen
        // seed would have to be reported back and could drift from what ran.
        val seed = intFlag(flags, "--seed") ?: freshSeed()
        // One row per draw. More than one is the common case for reading a function:
        // a single sample shows what it returned, several show what it DOES — which
        // arguments matter, where the output turns over, whether an edge case breaks
        // it. The seed covers the whole batch, so `--seed` reproduces every row.
        val count = (intFlag(flags, "--count") ?: 1).coerceIn(1, 100)
        val depth = intFlag(flags, "--depth") ?: WireProtocol.DEFAULT_EVAL_MAX_DEPTH
        val items = intFlag(flags, "--items") ?: WireProtocol.DEFAULT_EVAL_MAX_ITEMS
        val timeoutMs = intFlag(flags, "--timeout")?.toLong()?.times(1_000) ?: 0L

        val moduleFile = resolveAppFile(currentDir) ?: kotlin.system.exitProcess(1)

        val target = when (val t = SampleTarget.resolve(currentDir, moduleFile, name)) {
            is SampleTarget.Result.Refused -> {
                System.err.println(t.message)
                kotlin.system.exitProcess(1)
            }
            is SampleTarget.Result.Ready -> t
        }

        val expression = buildExpression(target, seed, count)

        when (val compiled = EvalClosure.compile(currentDir, moduleFile, expression, EXTRA_IMPORTS)) {
            is EvalClosure.Result.Failed -> {
                // The diagnostic is about an expression THIS command wrote, so it
                // names a type the user never mentioned. Saying where it came from
                // is the difference between a lead and a puzzle.
                System.err.println(
                    "${Glyphs.CROSS} `$name`'s arguments could not be generated.\n"
                )
                System.err.println(compiled.message)
                System.err.println(
                    "\n${Glyphs.TIP} This is the generated call, which sample wrote:\n" +
                        expression.prependIndent("    ")
                )
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

                if (result == null) kotlin.system.exitProcess(1)

                val error = result.error
                if (error != null) {
                    // The function RAN and failed, which is a finding rather than a
                    // tool malfunction — and the seed is what makes it actionable,
                    // so it is printed even on the failure path.
                    System.err.println(
                        "${Glyphs.CROSS} `$name` failed on its generated arguments\n" +
                            "  ${error.detail.message}\n\n" +
                            "${Glyphs.TIP} Reproduce it: mez sample $name --seed=$seed"
                    )
                    kotlin.system.exitProcess(1)
                }

                val value = result.value
                if (value == null) {
                    System.err.println(
                        "${Glyphs.CROSS} the runtime replied with neither a value nor an error"
                    )
                    kotlin.system.exitProcess(1)
                }

                if (json) println(sampleJson(name, seed, value)) else printSample(target, seed, value)
            }
        }
    }

    /**
     * The generated expression: one `arbitrary` per FIELD, then the call.
     *
     * Per field rather than one annotated record, and that is measured rather than
     * stylistic. `let args: { n: Int } = Arbitrary.arbitrary {}` is REJECTED — an
     * anonymous record annotation does not pin a receiver-less ability operation —
     * while the per-field form is pinned by the function's own parameter and needs
     * no rendered type at all. See [SampleTarget.Result.Ready].
     *
     * A zero-argument function gets no `with_seed` wrapper: there is nothing to
     * draw, so wrapping it would declare a `<Sample>` the body never performs and be
     * rejected for exactly that.
     */
    internal fun buildExpression(
        target: SampleTarget.Result.Ready,
        seed: Int,
        count: Int = 1,
    ): String {
        val name = target.name

        // A zero-argument function has one answer, so [count] is meaningless for it
        // and there is nothing to draw — hence no `with_seed`, which would declare a
        // `<Sample>` the body never performs and be rejected for exactly that.
        if (target.fields.isEmpty()) {
            return "{ rows = [{ args = {}, result = $name {} }] }"
        }

        // Rows are built by NAME (`__row_0`, `__row_1`) rather than by a loop, and
        // that is forced rather than chosen: a loop would need a sequence combinator
        // over `<Sample>` in the user's module scope, which it has no reason to
        // import. Unrolling needs nothing beyond what sampling already injects.
        //
        // All rows draw inside ONE `with_seed`, so the seed reproduces the whole
        // batch — and consecutive rows differ, where a seeded scope per row would
        // reset the stream and print the same values N times.
        val rows = (0 until count).joinToString("\n") { i ->
            val args = "${ARGS}_$i"
            val forwarded = target.fieldNames.joinToString(", ") { "$it = $args.$it" }
            // Generated FROM the parameter's types, which the command already knows,
            // with one ANNOTATED binding per field. The annotation is what pins the
            // draws — see [SampleGen.bindFields] — and that is what makes a container
            // or an Option argument work regardless of whether the parameter type
            // itself has a name.
            val (lets, record) = SampleGen.bindFields(target.fields, args) {
                com.mezze.language.ast.prettyPrintType(it)
            }
            lets
                .plus("\n              let $args = $record")
                .plus("\n              let ${ROW}_$i = { args = $args, result = $name { $forwarded } }")
        }
        val collected = (0 until count).joinToString(", ") { "${ROW}_$it" }
        return """
            with_seed { seed = $seed, call = {} -> do
$rows
              { rows = [$collected] }
            }
        """.trimIndent()
    }

    /**
     * One row per draw, as a table.
     *
     * Columns come from the parameter's field names, so the header is the function's
     * own signature and a reader does not have to map positions back to names. Widths
     * are measured over the actual values rather than fixed, because a column of
     * `-9223372036854775808` and a column of `True` want very different space.
     *
     * A value containing a newline would break the grid, so rows are escaped — a
     * generated `Str` can be `"\n"`, which is the whole point of having it in the
     * table.
     */
    private fun printSample(target: SampleTarget.Result.Ready, seed: Int, value: WireValue) {
        val rows = (value as? WireValue.Record)
            ?.fields?.firstOrNull { it.name == "rows" }?.value
            ?.let { it as? WireValue.Sequence }
            ?.items
            ?.mapNotNull { row -> (row as? WireValue.Record)?.fields }

        if (rows.isNullOrEmpty()) {
            // The shape is this command's own doing, so a mismatch is a defect here
            // rather than anything the user can act on.
            println(value.toText())
            System.err.println(
                "${Glyphs.CROSS} sample expected a { rows = [{ args, result }] } record back; " +
                    "this is a defect in sample, not in `${target.name}`"
            )
            return
        }

        val headers = target.fieldNames + "→ result"
        val body = rows.map { fields ->
            val args = (fields.firstOrNull { it.name == "args" }?.value as? WireValue.Record)?.fields
            val cells = target.fieldNames.map { f ->
                oneLine(args?.firstOrNull { it.name == f }?.value?.toText() ?: "?")
            }
            cells + oneLine(fields.firstOrNull { it.name == "result" }?.value?.toText() ?: "?")
        }

        val widths = headers.indices.map { c ->
            maxOf(headers[c].length, body.maxOf { it[c].length })
        }
        fun line(cells: List<String>) =
            cells.indices.joinToString("  ") { cells[it].padEnd(widths[it]) }.trimEnd()

        println(line(headers))
        println(widths.joinToString("  ") { "-".repeat(it) })
        for (row in body) println(line(row))
        println()
        println("  seed $seed — rerun with: mez sample ${target.name} --seed=$seed")
    }

    /** A cell cannot span lines; a generated `"\n"` would otherwise break the grid. */
    private fun oneLine(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    /**
     * The sample as data: one entry per row, each carrying the TREE for its args and
     * result so a consumer can read a field without parsing Mezze's rendering.
     * `text` sits alongside for a consumer that only wants to display it.
     *
     * `seed` is what makes this worth consuming programmatically — it is what lets a
     * caller re-request this exact batch.
     */
    private fun sampleJson(name: String, seed: Int, value: WireValue): String {
        val rows = (value as? WireValue.Record)
            ?.fields?.firstOrNull { it.name == "rows" }?.value
            ?.let { it as? WireValue.Sequence }
            ?.items
            ?.mapNotNull { row -> (row as? WireValue.Record)?.fields }
            ?: emptyList()

        fun part(fields: List<WireValue.Field>, label: String): String {
            val v = fields.firstOrNull { it.name == label }?.value ?: return "null"
            return "{\"text\":${WireValueJson.quote(v.toText())},\"value\":${WireValueJson.tree(v)}}"
        }
        return buildString {
            append("{\"ok\":true,\"function\":").append(WireValueJson.quote(name))
            append(",\"seed\":").append(seed)
            append(",\"rows\":[")
            rows.forEachIndexed { i, fields ->
                if (i > 0) append(",")
                append("{\"args\":").append(part(fields, "args"))
                append(",\"result\":").append(part(fields, "result")).append("}")
            }
            append("]}")
        }
    }

    /**
     * A seed in a range a person can read back and retype.
     *
     * Not the full Int range: the seed's whole purpose is to be copied off a
     * terminal into a `--seed=` flag, and a nineteen-digit number is a transcription
     * error waiting to happen. Six digits is 900k distinct runs, which is ample for
     * "show me another one".
     */
    private fun freshSeed(): Int = (100_000..999_999).random()

    private fun intFlag(flags: List<String>, name: String): Int? =
        flags.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
            ?.toIntOrNull()

    /** The app module `mez dev` would run — the scope a user means by "my code". */
    private fun resolveAppFile(currentDir: File): File? {
        val toml = File(currentDir, "Mezze.toml")
        if (!toml.exists()) {
            System.err.println("${Glyphs.CROSS} no Mezze.toml in ${currentDir.absolutePath}")
            System.err.println("   `mez sample` looks for the function in this project's app module; run `mez init` first.")
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
                    "so there is no module to look in"
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

    private fun printUsage() {
        System.err.println("Usage: mez sample <function> [--count=N] [--seed=N] [--json]")
        System.err.println()
        System.err.println("Generate arguments for a function, run it, and show both.")
        System.err.println()
        System.err.println("  --count=N    how many rows to draw (default 1, max 100)")
        System.err.println("  --seed=N     reproduce an earlier run (the seed is printed every time)")
        System.err.println("  --json       the sample as data")
        System.err.println("  --depth=N    how deep to render a nested value (default ${WireProtocol.DEFAULT_EVAL_MAX_DEPTH})")
        System.err.println("  --items=N    how many elements of a collection to render (default ${WireProtocol.DEFAULT_EVAL_MAX_ITEMS})")
    }

    private companion object {
        /**
         * The binding the generated record uses. Not a name a user could collide
         * with: it is spliced into their module's scope, where a clash would report
         * "already defined" against a line they never wrote.
         */
        const val ARGS = "__mez_sample_args__"

        /** Per-row binding prefix. Same no-collision reasoning as [ARGS]. */
        const val ROW = "__mez_sample_row__"

        /**
         * Sampling's own imports, which the user's module has no reason to carry.
         *
         * `EvalClosure` prepends these, so a module that already imports them is
         * unaffected — a duplicate import is harmless, and diffing them would be
         * work for no benefit.
         */
        val EXTRA_IMPORTS = SampleGen.IMPORTS
    }
}
