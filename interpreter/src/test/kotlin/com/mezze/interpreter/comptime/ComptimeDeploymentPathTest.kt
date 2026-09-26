package com.mezze.interpreter.comptime

import com.mezze.interpreter.comptime.TruffleComptimeEvaluator
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Paths

/**
 * Comptime on the path every real build uses.
 *
 * `MezzeLanguage.parse` has two ways in. One type-checks source in-process;
 * the other is handed **pre-lowered `FunctionIR`** and never sees a
 * `TypeCheckResult` at all. `mez dev`, `mez exec`, `mez build`,
 * `build --native` and the WASM build all take the second — via `RuntimeServer`,
 * `EmbeddedExec` and `MezzeWeb` — and for `dev`/`exec` the IR has crossed a
 * process boundary as serialized bytes on the way.
 *
 * Every other comptime test uses the first path. This one uses the second, and
 * it exists because a gap here is INVISIBLE: an unreduced call still computes
 * the right answer through the declaration's `eval {}` fallback, so only an
 * evaluation counter can tell that compile-time evaluation quietly stopped
 * happening.
 *
 * What makes it work is that the result shape rides on the call node rather
 * than in a compile-local side table — `FunctionIR` is `@Serializable`, so the
 * shape travels with the program.
 */
class ComptimeDeploymentPathTest {

    private val prelude = """
        use std::comptime::{ Comptime, ComptimeEval }
        use std::effects::noop::{ noop }

        let factorial = { n } -> if n <= 1 then 1 else n * factorial { n = n - 1 }
    """.trimIndent()

    /**
     * Inside a lambda — the shape a real entry point has, and the one that
     * broke. A comptime call in a function body is an ordinary direct call, so
     * everything downstream is entitled to rewrite it: the monomorphizer
     * redirects it to a specialized hash, and the caller-side inliner splices
     * `eval {}` in and deletes the site outright. Both leave a program that
     * still computes the right answer, which is why this needs the evaluation
     * counter to detect.
     */
    private val inLambda = "$prelude\n" + """
        let main = {} -> (perform ComptimeEval in Comptime.comptime { eval = {} -> do
            noop {}
            factorial { n = 5 }
        })
    """.trimIndent()

    /** At the top level, where the binding itself is the comptime block. */
    private val topLevel = "$prelude\n" + """
        let answer = {} -> (perform ComptimeEval in Comptime.comptime { eval = {} -> do
            noop {}
            factorial { n = 5 }
        })

        let main = {} -> answer
    """.trimIndent()

    /** Compile to IR the way the CLI does, then hand that IR to a runtime —
     *  never letting the runtime see the source. */
    private fun runViaPreLoweredIr(source: String): Pair<Any?, Long> {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        val parsed = MezzeParser().parseParsedModule(source, "deploy.mz")
        val checked = TypeChecker().typeCheck(parsed, Paths.get("."), true, null)
        val functions = checked.loweredFunctions
        assertTrue(functions.isNotEmpty(), "type checking produced no IR")

        val before = TruffleComptimeEvaluator.evaluations.get()
        val engine = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .option("engine.Compilation", "false")
            .build()
        val context = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine).allowAllAccess(true).allowExperimentalOptions(true).build()
        try {
            MezzeLanguage.clearLastResult()
            MezzeLanguage.setFunctionsForDirectExecution(
                functions, checked.performerRecords, Paths.get("."),
            )
            context.eval(Source.newBuilder(MezzeLanguage.ID, "", "<deploy>").buildLiteral())
            return MezzeLanguage.getLastExecutionResult() to
                (TruffleComptimeEvaluator.evaluations.get() - before)
        } finally {
            try { context.close(true) } catch (ignored: Exception) {}
            try { engine.close() } catch (ignored: Exception) {}
        }
    }

    @Test
    @Timeout(180)
    fun `comptime folds when the runtime is handed pre-lowered IR`() {
        val (_, evaluated) = runViaPreLoweredIr(inLambda)
        assertTrue(
            evaluated > 0,
            "the thunk was never evaluated — comptime is inert on the path every real build uses",
        )
    }

    @Test
    @Timeout(180)
    fun `a top-level comptime binding folds on the same path`() {
        val (_, evaluated) = runViaPreLoweredIr(topLevel)
        assertTrue(evaluated > 0, "the thunk was never evaluated")
    }

    /** The shape the reifier builds against has to survive lowering, since the
     *  runtime that consumes it never saw the solved types. */
    @Test
    @Timeout(180)
    fun `the result shape survives onto the IR`() {
        for ((what, src) in listOf("in a lambda" to inLambda, "at the top level" to topLevel)) {
            com.mezze.language.typechecker.ModuleResolver.clearCache()
            val parsed = MezzeParser().parseParsedModule(src, "deploy.mz")
            val checked = TypeChecker().typeCheck(parsed, Paths.get("."), true, null)

            val ir = com.mezze.language.comptime.ComptimeIr()
            // At least one: a top-level `let x = {} -> (perform ComptimeEval in Comptime.comptime {…})` puts the same call
            // in both its own thunk and the module entry. The reduction memo keys
            // on the thunk hash, so duplicates are evaluated once regardless.
            val stamped = checked.loweredFunctions.count { ir.hasSite(it.body) }
            assertTrue(stamped >= 1, "no stamped comptime site in the lowered IR, $what")
        }
    }
}
