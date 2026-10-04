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

        when (val compiled = EvalClosure.compile(
            currentDir, moduleFile, expression, EXTRA_IMPORTS, buildDecls(target),
        )) {
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
                val batch = runExpression(currentDir, compiled, depth, items, timeoutMs)

                // The whole batch is one invocation, so ONE crashing row kills every
                // other row with it — and a crash is a finding, not a tool
                // malfunction. The boundary values that cause one (`Int`'s minimum
                // and maximum, 10% each) are exactly the inputs worth showing, so
                // losing the table to them would mean the generator's best values
                // make the command useless.
                //
                // Guest errors are UNCATCHABLE by design, so a row cannot be isolated
                // inside Mezze. Recovery is therefore re-running rows one at a time —
                // which produces identical values, because row `i`'s seed is
                // `seed + i` either way (see buildRowExpression). The cost is paid
                // only when something actually crashed.
                val rows: List<RowOutcome> =
                    if (batch?.error == null && batch?.value != null) readRows(batch.value!!)
                    else recoverRowByRow(currentDir, moduleFile, target, seed, count, depth, items, timeoutMs)

                if (rows.isEmpty()) {
                    System.err.println(
                        "${Glyphs.CROSS} `$name` produced no rows, and no row reported a reason — " +
                            "this is a defect in sample, not in `$name`"
                    )
                    kotlin.system.exitProcess(1)
                }

                if (json) println(sampleJson(name, seed, rows)) else printSample(target, seed, rows)

                // A crashed row is a real failure of the function under test, so the
                // exit code says so even though the table printed.
                if (rows.any { it is RowOutcome.Crashed }) kotlin.system.exitProcess(1)
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
     * A zero-argument function gets no `perform SeededSample`: there is nothing to
     * draw, so wrapping it would declare a `<Sample>` the body never performs and be
     * rejected for exactly that.
     */
    /** What one row produced: its values, or the reason it died. */
    internal sealed interface RowOutcome {
        data class Ran(val args: WireValue?, val result: WireValue?) : RowOutcome
        /** [reason] is the runtime's own message — an overflow, a division by zero. */
        data class Crashed(val reason: String) : RowOutcome
    }

    /** Compile-and-run is identical for the batch and for a single recovered row. */
    private fun runExpression(
        currentDir: File,
        compiled: EvalClosure.Result.Compiled,
        depth: Int,
        items: Int,
        timeoutMs: Long,
    ): com.mezze.wire.EvalResult? {
        val runDir = com.mezze.language.store.ProjectLayout.runDir(currentDir)
        val (fnsFile, metaFile) = EvalClosure.writeTo(runDir, compiled)
        val session = WireSession.start(currentDir, timing = false, t0 = System.nanoTime())
            ?: return null
        return try {
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
    }

    /** Unpack `{ rows = [{ args, result }] }` into one outcome per row. */
    private fun readRows(value: WireValue): List<RowOutcome> =
        (value as? WireValue.Record)
            ?.fields?.firstOrNull { it.name == "rows" }?.value
            ?.let { it as? WireValue.Sequence }
            ?.items
            ?.map { row ->
                val fields = (row as? WireValue.Record)?.fields
                RowOutcome.Ran(
                    args = fields?.firstOrNull { it.name == "args" }?.value,
                    result = fields?.firstOrNull { it.name == "result" }?.value,
                )
            }
            ?: emptyList()

    /**
     * Re-run each row alone, so one crash costs one row instead of the table.
     *
     * Reached only after the batch failed. Each row recompiles — the seed is a literal
     * in the expression, so there is nothing to vary without recompiling — which is
     * why this is the recovery path and not the normal one.
     */
    private fun recoverRowByRow(
        currentDir: File,
        moduleFile: File,
        target: SampleTarget.Result.Ready,
        seed: Int,
        count: Int,
        depth: Int,
        items: Int,
        timeoutMs: Long,
    ): List<RowOutcome> = (0 until count).map { i ->
        when (val compiled = EvalClosure.compile(
            currentDir, moduleFile, buildRowExpression(target, seed, i), SampleGen.IMPORTS,
            buildDecls(target),
        )) {
            is EvalClosure.Result.Failed -> RowOutcome.Crashed("did not compile: ${compiled.message.lines().first()}")
            is EvalClosure.Result.Compiled -> {
                val r = runExpression(currentDir, compiled, depth, items, timeoutMs)
                val err = r?.error
                when {
                    r == null -> RowOutcome.Crashed("the runtime did not reply")
                    err != null -> RowOutcome.Crashed(err.detail.message.ifBlank { err.kind })
                    r.value != null -> readRows(r.value!!).firstOrNull()
                        ?: RowOutcome.Crashed("sample could not read the row back")
                    else -> RowOutcome.Crashed("neither a value nor an error")
                }
            }
        }
    }

    /**
     * One row per draw, each in its OWN seeded scope at `seed + i`.
     *
     * Per-row seeds rather than one scope around the batch, and the reason changed
     * this commit. The earlier version used a single scope because a scope PER row
     * reset the stream and every row drew the same values — but that was only true
     * while all rows shared one seed. With `seed + i` the rows differ, and two things
     * follow that a shared scope could not give:
     *
     *  - a row can be re-run ALONE and produce byte-identical values
     *    ([buildRowExpression]), which is what makes a crashing row recoverable
     *    rather than fatal to the whole table.
     *  - `--seed=N` still reproduces the entire table, because row `i` is always
     *    `N + i`.
     *
     * It depends on adjacent seeds giving unrelated streams, which they did NOT until
     * S-5 was fixed in this same commit: `java.util.Random(seed)` scrambles weakly,
     * so `seed` and `seed + 1` returned near-identical first draws. Doing this before
     * that fix would have produced a table of near-duplicate rows.
     *
     * Rows are unrolled by NAME rather than looped because a loop would need a
     * sequence combinator over `<Sample>` in the user's module scope, which it has no
     * reason to import.
     */
    internal fun buildExpression(
        target: SampleTarget.Result.Ready,
        seed: Int,
        count: Int = 1,
    ): String {
        if (target.fields.isEmpty()) return noArgRow(target.name)
        val rows = (0 until count).joinToString("\n") { i -> rowBinding(target, seed + i, i).lets }
        val collected = (0 until count).joinToString(", ") { "${ROW}_$it" }
        return "do\n$rows\n              { rows = [$collected] }"
    }

    /**
     * ONE row, for re-running it in isolation after the batch died.
     *
     * Produces the same values row [index] had in the batch, because the seed is
     * derived identically. That is the whole point: a recovered row is the row that
     * actually ran, not a fresh draw that happens to be adjacent.
     */
    internal fun buildRowExpression(
        target: SampleTarget.Result.Ready,
        seed: Int,
        index: Int,
    ): String {
        if (target.fields.isEmpty()) return noArgRow(target.name)
        return "do\n${rowBinding(target, seed + index, 0).lets}\n              { rows = [${ROW}_0] }"
    }

    /**
     * A zero-argument function has one answer, so `--count` is meaningless for it and
     * there is nothing to draw — hence no `perform SeededSample`, which would
     * declare a `<Sample>` the body never performs and be rejected for exactly that.
     */
    private fun noArgRow(name: String) = "{ rows = [{ args = {}, result = $name {} }] }"

    /** `let __row_<i> = perform SeededSample { … } in do …` — the row's draws
     *  and its call. */
    private fun rowBinding(target: SampleTarget.Result.Ready, rowSeed: Int, i: Int): SampleGen.Plan {
        val args = "${ARGS}_$i"
        val forwarded = target.fieldNames.joinToString(", ") { "$it = $args.$it" }
        // Generated FROM the parameter's types, with one ANNOTATED binding per field.
        // The annotation is what pins the draws — see [SampleGen.bindFields] — and is
        // what makes a container, an Option or a recursive variant work at all.
        val plan = SampleGen.bindFields(target.fields, args) {
            com.mezze.language.ast.prettyPrintType(it)
        }
        // `bindFields` indents its lets to the same depth as this `let`, which was
        // invisible while the row was `with_seed { … }` — the braces delimited the
        // body, so indentation carried no meaning. A `perform … in do` body is
        // delimited by INDENTATION, so at equal depth the do block has no body at all
        // and the parser says "Do block is empty". Push them one level in.
        val lets = plan.lets.lines().joinToString("\n") { "  $it" }
        val text = "              let ${ROW}_$i = perform SeededSample { seed = $rowSeed } in do\n" +
            lets + "\n" +
            "                let $args = ${plan.record}\n" +
            "                { args = $args, result = ${target.name} { $forwarded } }"
        return plan.copy(lets = text)
    }

    /**
     * Top-level declarations the generated expression needs, if any.
     *
     * Only a recursive variant produces one — it needs a helper that calls ITSELF,
     * and a `do`-block `let` is not recursive, so it cannot live in the expression.
     * Identical across rows (it depends on the TYPE, not the seed), hence distinct.
     */
    internal fun buildDecls(target: SampleTarget.Result.Ready): List<String> =
        if (target.fields.isEmpty()) emptyList()
        else rowBinding(target, 0, 0).decls.distinct()

    private fun printSample(target: SampleTarget.Result.Ready, seed: Int, rows: List<RowOutcome>) {
        val headers = target.fieldNames + "→ result"
        val body = rows.map { outcome ->
            when (outcome) {
                is RowOutcome.Ran -> {
                    val args = (outcome.args as? WireValue.Record)?.fields
                    target.fieldNames.map { f ->
                        oneLine(args?.firstOrNull { it.name == f }?.value?.toText() ?: "?")
                    } + oneLine(outcome.result?.toText() ?: "?")
                }
                is RowOutcome.Crashed ->
                    target.fieldNames.map { "—" } + "${Glyphs.CROSS} ${oneLine(brief(outcome.reason))}"
            }
        }

        val widths = headers.indices.map { c ->
            maxOf(headers[c].length, body.maxOf { it[c].length })
        }
        fun line(cells: List<String>) =
            cells.indices.joinToString("  ") { cells[it].padEnd(widths[it]) }.trimEnd()

        println(line(headers))
        println(widths.joinToString("  ") { "-".repeat(it) })
        for (row in body) println(line(row))

        val crashed = rows.count { it is RowOutcome.Crashed }
        println()
        if (crashed > 0) {
            // Named plainly: a crash here is a finding about the function, and the
            // boundary values that tend to cause one are the ones worth keeping.
            println("  $crashed of ${rows.size} row(s) crashed — that is a failure of " +
                "`${target.name}` on those inputs, not of sample")
        }
        println("  seed $seed — rerun with: mez sample ${target.name} --seed=$seed")
    }

    /**
     * The reason without its trailing explanatory clause.
     *
     * A good runtime message explains itself — "(Int is 64-bit signed, so the result
     * has no representable value)" — but repeated on every crashed row it is noise
     * that doubles the table's width. The part that differs per row, and the part
     * worth reading, is the operation and its operands. The full message is still
     * what `--json` carries.
     */
    private fun brief(reason: String): String =
        reason.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()

    /** A cell cannot span lines; a generated `"\n"` would otherwise break the grid. */
    private fun oneLine(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    /**
     * The sample as data: one entry per row, each carrying the TREE for its args and
     * result so a consumer can read a field without parsing Mezze's rendering.
     *
     * A crashed row appears with `"crashed"` and its reason rather than being omitted
     * — a consumer counting rows should see what was asked for, and the reason is the
     * finding.
     */
    private fun sampleJson(name: String, seed: Int, rows: List<RowOutcome>): String {
        fun part(v: WireValue?): String =
            if (v == null) "null"
            else "{\"text\":${WireValueJson.quote(v.toText())},\"value\":${WireValueJson.tree(v)}}"
        return buildString {
            append("{\"ok\":").append(rows.none { it is RowOutcome.Crashed })
            append(",\"function\":").append(WireValueJson.quote(name))
            append(",\"seed\":").append(seed)
            append(",\"rows\":[")
            rows.forEachIndexed { i, outcome ->
                if (i > 0) append(",")
                when (outcome) {
                    is RowOutcome.Ran ->
                        append("{\"args\":").append(part(outcome.args))
                            .append(",\"result\":").append(part(outcome.result)).append("}")
                    is RowOutcome.Crashed ->
                        append("{\"crashed\":").append(WireValueJson.quote(outcome.reason)).append("}")
                }
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
