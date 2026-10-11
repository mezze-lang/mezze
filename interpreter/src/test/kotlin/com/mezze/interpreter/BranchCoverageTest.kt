package com.mezze.interpreter

import com.mezze.interpreter.nodes.builtin.effects.CoverageNatives
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.BranchCoverage
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.module.ModuleTypeChecker
import com.mezze.language.typedast.TypedLetDecl
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Paths

/**
 * Instrumented IR, run: the sites recorded are the branches that actually ran.
 *
 * End to end on purpose — the instrumenter rewrites IR, the runtime records hits,
 * and only running the two together shows a site is reported exactly when its
 * branch is taken: not when its guard is merely tried, and never for a branch the
 * run did not reach.
 */
class BranchCoverageTest {

    private val prelude = """
        type Shape = | Circle { r: Int }, Square { side: Int } |

        let classify = { n: Int } -> if n > 10 then "big" else "small"

        let describe = { s: Shape } -> match s is
          Circle { r } if r > 10 -> "large circle"
          Circle { r } -> "circle"
          Square { side } -> "square"

        let nested = { n: Int } -> ({ m: Int } -> if m > 0 then 1 else 2) { m = n }
    """.trimIndent()

    /** Run [call] with [target] instrumented; the sites reached, as (kind, arm). */
    private fun reached(target: String, call: String): Pair<List<BranchCoverage.Site>, Set<Pair<BranchCoverage.Site.Kind, Int>>> {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        ModuleTypeChecker.clearCache()
        val source = "$prelude\n\n$call"
        val checked = TypeChecker().typeCheck(MezzeParser().parseParsedModule(source, "coverage.mz"), Paths.get("."), true, null)
        val targetHash = checked.typedModule!!.decls.filterIsInstance<TypedLetDecl>().single { it.name == target }.hash
        val instrumented = BranchCoverage.instrument(checked.loweredFunctions, targetHash)

        val engine = Engine.newBuilder().allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false").option("engine.Compilation", "false").build()
        val context = Context.newBuilder(MezzeLanguage.ID).engine(engine).allowAllAccess(true).allowExperimentalOptions(true).build()
        try {
            CoverageNatives.takeHits()
            MezzeLanguage.setFunctionsForDirectExecution(instrumented.functions, checked.performerRecords, Paths.get("."))
            context.eval(Source.newBuilder(MezzeLanguage.ID, "", "<coverage>").buildLiteral())
            val hits = CoverageNatives.takeHits().toSet()
            return instrumented.sites to instrumented.sites.filter { it.id in hits }.map { it.kind to it.arm }.toSet()
        } finally {
            try { context.close(true) } catch (_: Exception) {}
            try { engine.close() } catch (_: Exception) {}
        }
    }

    @Test
    @Timeout(180)
    fun `an if records only the branch that ran`() {
        val (sites, hit) = reached("classify", "classify { n = 5 }")
        assertEquals(2, sites.size, "an if has two sites")
        assertEquals(setOf(BranchCoverage.Site.Kind.ELSE to 1), hit)
    }

    @Test
    @Timeout(180)
    fun `an arm whose guard failed is not reached`() {
        val (sites, hit) = reached("describe", "describe { s = Circle { r = 3 } }")
        assertEquals(3, sites.size, "one site per arm")
        // The guarded arm was TRIED and its guard failed — that is not reaching it.
        assertEquals(setOf(BranchCoverage.Site.Kind.ARM to 1), hit)
    }

    @Test
    @Timeout(180)
    fun `branches inside a lambda written in the target belong to the target`() {
        val (sites, hit) = reached("nested", "nested { n = 4 }")
        assertEquals(2, sites.size)
        assertEquals(setOf(BranchCoverage.Site.Kind.THEN to 0), hit)
    }
}
