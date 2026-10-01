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

        val expression = buildExpression(target, seed)

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

                if (json) println(sampleJson(name, seed, value)) else printSample(name, seed, value)
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
    internal fun buildExpression(target: SampleTarget.Result.Ready, seed: Int): String {
        val name = target.name
        if (target.fieldNames.isEmpty()) {
            return "{ args = {}, result = $name {} }"
        }
        val generated = target.fieldNames.joinToString(", ") { "$it = Arbitrary.arbitrary {}" }
        val forwarded = target.fieldNames.joinToString(", ") { "$it = $ARGS.$it" }
        return """
            with_seed { seed = $seed, call = {} -> do
              let $ARGS = { $generated }
              { args = $ARGS, result = $name { $forwarded } }
            }
        """.trimIndent()
    }

    private fun printSample(name: String, seed: Int, value: WireValue) {
        val fields = (value as? WireValue.Record)?.fields
        val argsValue = fields?.firstOrNull { it.name == "args" }?.value
        val resultValue = fields?.firstOrNull { it.name == "result" }?.value

        if (argsValue == null || resultValue == null) {
            // The shape is this command's own doing, so a mismatch is a defect here
            // rather than anything the user can act on — print the tree and say so.
            println(value.toText())
            System.err.println(
                "${Glyphs.CROSS} sample expected an { args, result } record back; " +
                    "this is a defect in sample, not in `$name`"
            )
            return
        }

        println("$name ${argsValue.toText()}")
        println("  → ${resultValue.toText()}")
        println()
        println("  seed ${seed} — rerun with: mez sample $name --seed=$seed")
    }

    /**
     * The sample as data: the TREE for both args and result, so a consumer can read
     * a field without parsing Mezze's rendering. `text` sits alongside each for a
     * consumer that only wants to display it.
     *
     * `seed` is the field that makes this worth consuming programmatically — it is
     * what lets a caller re-request the exact same sample.
     */
    private fun sampleJson(name: String, seed: Int, value: WireValue): String {
        val fields = (value as? WireValue.Record)?.fields
        fun part(label: String): String {
            val v = fields?.firstOrNull { it.name == label }?.value ?: return "null"
            return "{\"text\":${WireValueJson.quote(v.toText())},\"value\":${WireValueJson.tree(v)}}"
        }
        return buildString {
            append("{\"ok\":true,\"function\":").append(WireValueJson.quote(name))
            append(",\"seed\":").append(seed)
            append(",\"args\":").append(part("args"))
            append(",\"result\":").append(part("result"))
            append("}")
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
        System.err.println("Usage: mez sample <function> [--seed=N] [--json]")
        System.err.println()
        System.err.println("Generate arguments for a function, run it, and show both.")
        System.err.println()
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

        /**
         * Sampling's own imports, which the user's module has no reason to carry.
         *
         * `EvalClosure` prepends these, so a module that already imports them is
         * unaffected — a duplicate import is harmless, and diffing them would be
         * work for no benefit.
         */
        val EXTRA_IMPORTS = setOf(
            "use std::effects::sample::{ with_seed }",
            "use std::abilities::arbitrary::{ Arbitrary }",
        )
    }
}
