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
        val count = (intFlag(flags, "--count") ?: 1).coerceIn(1, MAX_COUNT)
        val depth = intFlag(flags, "--depth") ?: WireProtocol.DEFAULT_EVAL_MAX_DEPTH
        val items = intFlag(flags, "--items") ?: WireProtocol.DEFAULT_EVAL_MAX_ITEMS
        // GENERATION knobs, carried into each row's `SampleConfig`. Not to be
        // confused with `--depth`/`--items` above, which only limit how a value is
        // PRINTED.
        val config = Config(
            size = (intFlag(flags, "--size") ?: DEFAULT_SIZE).coerceAtLeast(0),
            enumerate = (intFlag(flags, "--enumerate") ?: DEFAULT_ENUMERATE).coerceAtLeast(0),
        )
        val timeoutMs = intFlag(flags, "--timeout")?.toLong()?.times(1_000) ?: 0L

        val moduleFile = resolveAppFile(currentDir) ?: kotlin.system.exitProcess(1)

        val target = when (val t = SampleTarget.resolve(currentDir, moduleFile, name)) {
            is SampleTarget.Result.Refused -> {
                System.err.println(t.message)
                kotlin.system.exitProcess(1)
            }
            is SampleTarget.Result.Ready -> withEvaluatedConstants(currentDir, moduleFile, t)
        }

        val expression = buildExpression(target, seed, count, config)

        when (val compiled = EvalClosure.compile(
            currentDir, moduleFile, expression, imports(target), buildDecls(target), coverageOf = name,
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
                // Which of `name`'s branches ran, over every row (S-23).
                val reached = HashSet<Int>()
                val rows: List<RowOutcome> =
                    if (batch?.error == null && batch?.value != null) {
                        reached.addAll(batch.branchHits)
                        readRows(batch.value!!)
                    } else recoverRowByRow(currentDir, moduleFile, target, seed, count, depth, items, timeoutMs, config, reached)

                if (rows.isEmpty()) {
                    System.err.println(
                        "${Glyphs.CROSS} `$name` produced no rows, and no row reported a reason — " +
                            "this is a defect in sample, not in `$name`"
                    )
                    kotlin.system.exitProcess(1)
                }

                val unreached = compiled.sites.filter { it.id !in reached }
                if (json) println(sampleJson(name, seed, rows, unreached, moduleFile))
                else {
                    printSample(target, seed, rows)
                    SampleCoverageReport.lines(name, moduleFile.readLines(), compiled.sites, unreached).forEach { println(it) }
                }

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
    /**
     * Evaluate the constants the analysis could not fold — `let limit = compute
     * { x = 21 }` — with the interpreter, then re-run the analysis with their values.
     *
     * One extra round-trip for all of them, as a single record `{ c0 = limit, … }`.
     * A top-level value cannot perform effects (V0502), so this touches nothing and
     * gives the value the program itself will see. Bounded by a short timeout; on
     * any failure — the constant crashes, loops, or is not an `Int` — the target is
     * returned unchanged and those branches simply keep their ordinary draws.
     */
    private fun withEvaluatedConstants(
        currentDir: File,
        moduleFile: File,
        target: SampleTarget.Result.Ready,
    ): SampleTarget.Result.Ready {
        if (target.unresolved.isEmpty()) return target
        val expression = target.unresolved.indices.joinToString(", ", prefix = "{ ", postfix = " }") { i ->
            "c$i = ${target.unresolved[i].name}"
        }
        val compiled = EvalClosure.compile(currentDir, moduleFile, expression)
            as? EvalClosure.Result.Compiled ?: return target
        val result = runExpression(currentDir, compiled, depth = 4, items = 64, timeoutMs = CONSTANT_TIMEOUT_MS)
        val fields = (result?.value as? WireValue.Record)?.fields ?: return target
        val evaluated = target.unresolved.mapIndexedNotNull { i, decl ->
            val v = fields.firstOrNull { it.name == "c$i" }?.value as? WireValue.Scalar ?: return@mapIndexedNotNull null
            if (v.kind != WireValue.Scalar.Kind.INT) return@mapIndexedNotNull null
            v.text.toLongOrNull()?.let { decl.hash to it }
        }.toMap()
        if (evaluated.isEmpty()) return target
        return target.copy(branches = target.reanalyze(evaluated))
    }

    /** Generation settings for every row: `--size` and `--enumerate`. */
    internal data class Config(val size: Int = DEFAULT_SIZE, val enumerate: Int = DEFAULT_ENUMERATE)

    /** What one row produced: its values, or the reason it died. */
    internal sealed interface RowOutcome {
        data class Ran(val args: WireValue?, val result: WireValue?, val ctx: WireValue? = null) : RowOutcome
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
                    ctx = fields?.firstOrNull { it.name == "ctx" }?.value,
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
        config: Config = Config(),
        reached: MutableSet<Int> = HashSet(),
    ): List<RowOutcome> = (0 until count).map { i ->
        when (val compiled = EvalClosure.compile(
            currentDir, moduleFile, buildRowExpression(target, seed, i, config), imports(target),
            buildDecls(target), coverageOf = target.name,
        )) {
            is EvalClosure.Result.Failed -> RowOutcome.Crashed("did not compile: ${compiled.message.lines().first()}")
            is EvalClosure.Result.Compiled -> {
                val r = runExpression(currentDir, compiled, depth, items, timeoutMs)
                // A crashed row still reached the branches that led to the crash.
                r?.let { reached.addAll(it.branchHits) }
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
        config: Config = Config(),
    ): String {
        if (target.fields.isEmpty() && target.scripts.isEmpty()) return noArgRow(target.name)
        val rows = (0 until count).joinToString("\n") { i -> rowBinding(target, seed + i, i, i, config).lets }
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
        config: Config = Config(),
    ): String {
        if (target.fields.isEmpty() && target.scripts.isEmpty()) return noArgRow(target.name)
        return "do\n${rowBinding(target, seed + index, 0, index, config).lets}\n              { rows = [${ROW}_0] }"
    }

    /**
     * A zero-argument function has one answer, so `--count` is meaningless for it and
     * there is nothing to draw — hence no `perform SeededSample`, which would
     * declare a `<Sample>` the body never performs and be rejected for exactly that.
     */
    private fun noArgRow(name: String) = "{ rows = [{ args = {}, result = $name {} }] }"

    /** `let __row_<i> = perform CoveringSample { … } in do …` — the row's draws
     *  and its call.
     *
     *  S-21: `CoveringSample` rather than `SeededSample`. Both are seeded and
     *  reproducible; this one additionally ENUMERATES the leading choices from the
     *  row index, so a set of rows covers the shapes a type can take instead of
     *  stumbling on them. It matters because a derived variant picks its
     *  constructor from the first few draws and weights an EMPTY constructor at one
     *  in eight — so under blind draws an `if`/`match` arm reachable only through
     *  one appears in about an eighth of rows, and a run that never reached it
     *  looks exactly like a run that reached it and found nothing wrong. */
    private fun rowBinding(
        target: SampleTarget.Result.Ready,
        rowSeed: Int,
        /** Names this row's bindings. Always 0 on the one-row recovery path. */
        slot: Int,
        /**
         * WHICH row this is, which is not the same as [slot].
         *
         * Recovery re-runs each row as its own one-row expression, so the binding
         * name resets to 0 while the row is still row N — and the arm to pin is
         * chosen by the ROW, not by the name. Passing one number for both pinned
         * every recovered row to the first arm: `area` crashed on its `Rect` arm,
         * fell into recovery, and printed `Empty` five times.
         */
        row: Int,
        config: Config = Config(),
    ): SampleGen.Plan {
        val args = "${ARGS}_$slot"
        val forwarded = target.fieldNames.joinToString(", ") { "$it = $args.$it" }
        // Generated FROM the parameter's types, with one ANNOTATED binding per field.
        // The annotation is what pins the draws — see [SampleGen.bindFields] — and is
        // what makes a container, an Option or a recursive variant work at all.
        // S-21: row `i` is pinned to the `i`-th constructor the function matches on,
        // so the leading rows walk the arms instead of waiting for a draw to land on
        // them. Payloads inside are still drawn, so the SHAPE is pinned and the
        // VALUES vary. Rows past the arm count pin nothing and draw as before —
        // beyond covering the shapes, what is wanted is variety.
        val plan = SampleGen.bindFields(
            target.fields,
            args,
            pin = { field -> target.branches.getOrNull(row)?.pins?.get(field) },
        ) {
            com.mezze.language.ast.prettyPrintType(it)
        }
        // `bindFields` indents its lets to the same depth as this `let`, which was
        // invisible while the row was `with_seed { … }` — the braces delimited the
        // body, so indentation carried no meaning. A `perform … in do` body is
        // delimited by INDENTATION, so at equal depth the do block has no body at all
        // and the parser says "Do block is empty". Push them one level in.
        // S-21 step 7: each scripted operation's result is drawn HERE, like an
        // argument, so it can be shown — the performer only hands it back.
        val valuedOps = target.scripts.flatMap { sc -> sc.ops.filter { it.valued }.map { sc to it } }
        val ctxNames = valuedOps.indices.map { k -> "${CTX}_${slot}_$k" }
        val ctxLets = valuedOps.mapIndexed { k, (_, op) ->
            val sc = valuedOps[k].first
            // Pinned when the analysis found a branch on this result; drawn otherwise.
            val value = target.branches.getOrNull(row)?.pins?.get(sc.key(op))?.source ?: SampleGen.of(op.returnType)
            "              let ${ctxNames[k]}: ${com.mezze.language.ast.prettyPrintType(op.returnType)} = $value"
        }
        val call = target.scripts.joinToString("") { sc ->
            val mine = valuedOps.withIndex().filter { it.value.first === sc }
            val init = if (mine.isEmpty()) "" else
                mine.joinToString(", ", prefix = " { ", postfix = " }") { (k, pair) -> "${sc.field(pair.second)} = ${ctxNames[k]}" }
            "perform ${sc.performer}$init in "
        } + "${target.name} { $forwarded }"
        // Only operations the function can REACH come back for display; the rest
        // still get a value (the performer must be total) but are not its inputs.
        val shownIdx = valuedOps.indices.filter { valuedOps[it].second.shown }
        val ctxRecord = if (shownIdx.isEmpty()) "" else
            ", ctx = " + shownIdx.withIndex().joinToString(", ", prefix = "{ ", postfix = " }") { (c, k) -> "c$c = ${ctxNames[k]}" }
        // Draws only if some argument or some operation result is actually DRAWN. A row
        // whose every input is pinned to a literal performs no `Sample`, and wrapping
        // it in `perform CoveringSample` is a handler that can never fire (V0510).
        val draws = plan.draws || valuedOps.any { (sc, op) ->
            target.branches.getOrNull(row)?.pins?.get(sc.key(op))?.draws ?: true
        }
        val lets = (plan.lets.lines().filter { it.isNotBlank() } + ctxLets).joinToString("\n") { "  $it" }
        // A row pinned entirely to EMPTY constructors draws nothing, and a handler
        // over a body that performs no `Sample` is one that can never fire — V0510
        // rejects it, exactly as it already does for a zero-argument function. So
        // the `perform` is emitted only when the row actually draws.
        val head = if (draws) {
            "perform CoveringSampleWith { seed = $rowSeed, run = $row, " +
                "config = { size = ${config.size}n, enumerate = ${config.enumerate}n } } in do"
        } else "do"
        val text = "              let ${ROW}_$slot = $head\n" +
            (if (lets.isBlank()) "" else lets + "\n") +
            "                let $args = ${plan.record.ifBlank { "{}" }.let { if (it == "{  }") "{}" else it }}\n" +
            "                { args = $args$ctxRecord, result = $call }"
        return plan.copy(lets = text, draws = draws)
    }

    /**
     * Top-level declarations the generated expression needs, if any.
     *
     * Only a recursive variant produces one — it needs a helper that calls ITSELF,
     * and a `do`-block `let` is not recursive, so it cannot live in the expression.
     * Identical across rows (it depends on the TYPE, not the seed), hence distinct.
     */
    internal fun buildDecls(target: SampleTarget.Result.Ready): List<String> {
        val helpers = if (target.fields.isEmpty() && target.scripts.isEmpty()) emptyList()
            else rowBinding(target, 0, 0, 0).decls.distinct()
        val scripted = target.scripts.map { SampleScripts.implDecl(it) { t -> com.mezze.language.ast.prettyPrintType(t) } }
        return helpers + scripted
    }

    /** Sample's own imports, plus a `use` for every scripted effect declared elsewhere. */
    internal fun imports(target: SampleTarget.Result.Ready): Set<String> =
        EXTRA_IMPORTS + target.scripts.mapNotNull { it.importLine }

    private fun printSample(target: SampleTarget.Result.Ready, seed: Int, rows: List<RowOutcome>) {
        // A pinned row says WHICH branch it was built to reach. Without it, a row
        // with `n = 101` reads as a coincidence; with it, as the `then` side of
        // `n > max_value (= 100)`. Shown only when some row is pinned.
        val showWhy = target.branches.isNotEmpty()
        // A scripted operation's result is an INPUT the row drew, so it gets a
        // column beside the arguments — `⟨Config.ask⟩` — rather than staying
        // hidden in the performer. That visibility is what keeps a scripted run
        // from being "a run you set up". See [SampleScripts].
        val ctxLabels = target.scripts.flatMap { sc -> sc.ops.filter { it.shown }.map { sc.label(it) } }
        val headers = target.fieldNames + ctxLabels + "→ result" + (if (showWhy) listOf("why") else emptyList())
        val body = rows.mapIndexed { i, outcome ->
            val why = if (showWhy) listOf(target.branches.getOrNull(i)?.why ?: "") else emptyList()
            when (outcome) {
                is RowOutcome.Ran -> {
                    val args = (outcome.args as? WireValue.Record)?.fields
                    val ctx = (outcome.ctx as? WireValue.Record)?.fields
                    target.fieldNames.map { f ->
                        oneLine(args?.firstOrNull { it.name == f }?.value?.toText() ?: "?")
                    } + ctxLabels.indices.map { k ->
                        oneLine(ctx?.firstOrNull { it.name == "c$k" }?.value?.toText() ?: "?")
                    } + oneLine(outcome.result?.toText() ?: "?") + why
                }
                is RowOutcome.Crashed ->
                    target.fieldNames.map { "—" } + ctxLabels.map { "—" } +
                        "${Glyphs.CROSS} ${oneLine(brief(outcome.reason))}" + why
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
        // Branch rows the analysis found but `--count` did not leave room for. Said
        // out loud: a table that silently omits them reads as "every branch shown".
        val pinned = target.branches.size
        if (pinned > rows.size) {
            val cap = if (pinned > MAX_COUNT) " (more than the maximum of $MAX_COUNT)" else ""
            println("  ${Glyphs.WARN} $pinned branch rows found, ${rows.size} shown$cap — " +
                "rerun with --count=${minOf(pinned, MAX_COUNT)} to see them")
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
    private fun sampleJson(
        name: String,
        seed: Int,
        rows: List<RowOutcome>,
        unreached: List<com.mezze.language.truffleir.BranchCoverage.Site>,
        moduleFile: File,
    ): String {
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
            append("],\"unreached\":[")
            unreached.forEachIndexed { i, site ->
                if (i > 0) append(",")
                append("{\"file\":").append(WireValueJson.quote(moduleFile.name))
                    .append(",\"line\":").append(site.src.startLine)
                    .append(",\"column\":").append(site.src.startCol)
                    .append(",\"branch\":").append(WireValueJson.quote(SampleCoverageReport.branchName(site))).append("}")
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
        System.err.println("  --size=N     generation budget: collection length and recursion depth (default $DEFAULT_SIZE)")
        System.err.println("  --enumerate=N  leading choices steered across rows before drawing randomly (default $DEFAULT_ENUMERATE)")
        System.err.println("")
        System.err.println("  Printing only — these do not change what is generated:")
        System.err.println("  --depth=N    how deep to render a nested value (default ${WireProtocol.DEFAULT_EVAL_MAX_DEPTH})")
        System.err.println("  --items=N    how many elements of a collection to render (default ${WireProtocol.DEFAULT_EVAL_MAX_ITEMS})")
    }

    private companion object {
        /** A constant that takes longer than this to evaluate is left unresolved. */
        const val CONSTANT_TIMEOUT_MS = 5_000L

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
        /** Prefix for a row's drawn operation results — see [SampleScripts]. */
        const val CTX = "__mez_sample_ctx"
        /** `SampleConfig` defaults — the same values as `std::effects::sample::default_config`. */
        /** Most rows one run draws. */
        const val MAX_COUNT = 100
        const val DEFAULT_SIZE = 5
        const val DEFAULT_ENUMERATE = 12
        val EXTRA_IMPORTS = SampleGen.IMPORTS
    }
}
