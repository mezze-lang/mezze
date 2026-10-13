package com.mezze.language.langservice.sample

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedPerformOp
import com.mezze.language.typedast.forEachChild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The branch analysis is SHARED now — the language server runs it for several
 * documents at once — so one run's per-inventory state must never leak into
 * another's.
 *
 * It lived in the CLI, where an inventory ran to completion before the next, and
 * kept its scripted-operation labels in plain fields of a singleton. Two runs at
 * once then named each other's paths: a row targeting `if n > A` explained
 * itself as `if n > B`.
 */
class SampleBranchesConcurrencyTest {

    @Test
    fun `concurrent inventories keep their own labels`() {
        val parsed = MezzeParser().parseParsedModule(
            """
            effect Config where { threshold: {} -> Int <Config> }
            pub let f = { n: Int } -> n > Config.threshold {}
            """.trimIndent(),
            "test.mz",
        )
        val success = TypeChecker().checkParsedModule(parsed, Paths.get("."), currentModulePath = null)
            as TypeChecker.TypeCheckResult.Success
        val decls = success.typedModule!!.decls.filterIsInstance<TypedLetDecl>()
        val f = decls.single { it.name == "f" }
        val fields = ((f.scheme.type as MezzeType.FunctionType).paramType as MezzeType.RecordType).fields

        var op: TypedPerformOp? = null
        fun find(e: TypedExpr) { if (e is TypedPerformOp) op = e; e.forEachChild { find(it) } }
        find(f.value)
        val key = SampleBranches.contextKey(op!!.effectHash, op!!.operationName)

        fun whys(label: String): Set<String> = SampleBranches.inventory(
            f, fields, decls, context = mapOf(key to (label to MezzeType.IntType)),
        ).map { it.why }.toSet()

        // The label must actually reach the explanation, or this test proves nothing.
        assertEquals(true, whys("Solo").any { "Solo" in it }, "label never appears: ${whys("Solo")}")

        val threads = 8
        val rounds = 200
        val pool = Executors.newFixedThreadPool(threads)
        val barrier = CyclicBarrier(threads)
        val leaks = java.util.concurrent.ConcurrentLinkedQueue<String>()
        repeat(threads) { t ->
            val mine = "Label$t"
            pool.submit {
                barrier.await()
                repeat(rounds) {
                    for (why in whys(mine)) {
                        val foreign = Regex("Label\\d+").findAll(why).map { it.value }.filter { it != mine }.toList()
                        if (foreign.isNotEmpty()) leaks.add("thread $t: $why")
                    }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
        assertEquals(emptyList<String>(), leaks.take(5), "a run named another run's operation")
    }
}
