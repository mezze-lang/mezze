package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.ReadFieldIR
import com.mezze.language.truffleir.StmtIR
import com.mezze.language.truffleir.transformChildren
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A record that never escapes its binding is not built.
 *
 * MEASURED on `bench/cases/recordcost` at n=2e7, A/B on one CLI with the pass's
 * kill switch, a loop whose module-level helper returns a two-field record:
 *
 *     without the pass    5 026 ps/iter    33 young GCs
 *     with it                713            3
 *
 * Seven times, and three young collections against two for the same loop with
 * no record at all — so nothing is allocated per iteration. A JFR profile of
 * the slow form names what goes away: `java.lang.Long` 211 samples,
 * `Object[]` 86, `MezzeRecord` 61, the 1:1:2 a two-field all-object record
 * predicts.
 *
 * WHY THE IR AND NOT THE VALUE. The unscalarized form computes the same answer,
 * slowly, so a value test passes either way. These assert the record is GONE
 * from the lowered function, which is the property that changed.
 *
 * THE REFUSALS ARE THE DELICATE PART, and one of them was a real bug. A
 * module-level `let` lives in the entry block, where the binding is reachable
 * from OTHER functions by hash rather than through the block the use check
 * scans — rewriting one removed a record something else still read, and
 * `BasicLanguageFeaturesTest > test basic records` failed with "null cannot be
 * cast to MezzeRecord". The entry block is therefore never touched, and the
 * last two cases pin that plus the escaping case.
 */
class RecordScalarizationTest {

    private fun lower(src: String): List<FunctionIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions

    /** Field reads left in `p.mz`'s functions, excluding the entry block. */
    private fun fieldReads(src: String): Int {
        var n = 0
        for (fn in lower(src).filter { it.sourceFile == "p.mz" && it.name != "__entry__" }) {
            count(fn.body) { if (it is ReadFieldIR) n++ }
        }
        return n
    }

    /** `let` bindings whose value is a record, outside the entry block. */
    private fun recordLets(src: String): Int {
        var n = 0
        for (fn in lower(src).filter { it.sourceFile == "p.mz" && it.name != "__entry__" }) {
            count(fn.body) { node ->
                if (node is BlockIR) {
                    for (st in node.stmts) if (st is StmtIR.Let && st.value is MakeRecordIR) n++
                }
            }
        }
        return n
    }

    private fun count(node: NodeIR, f: (NodeIR) -> Unit) {
        f(node)
        node.transformChildren { child -> count(child, f); child }
    }

    private val loopWithHelper = """
        let split = { i } -> { lo = i - 1, add = i }
        let go = { i, acc } ->
          if i <= 0 then acc
          else do
            let p = split { i = i }
            go { i = p.lo, acc = acc + p.add }
        let main = { n } -> go { i = n, acc = 0 }
    """

    @Test
    fun `a record bound from an inlined helper leaves no binding and no field read`() {
        // `split` is inlined, so `let p = { lo = …, add = … }` lands in `go`
        // with both uses as field reads — and then disappears.
        assertTrue(recordLets(loopWithHelper) == 0,
            "a record let survived: " + recordLets(loopWithHelper))
        assertTrue(fieldReads(loopWithHelper) == 0,
            "a field read survived: " + fieldReads(loopWithHelper))
    }

    @Test
    fun `a record that is RETURNED keeps its binding`() {
        // The binding escapes as the result, so there is nothing to replace it
        // with and it must stay.
        val src = """
            let mk = { i } -> do
              let p = { lo = i - 1, add = i }
              p
            let main = { n } -> mk { i = n }
        """
        assertTrue(recordLets(src) >= 1, "an escaping record was scalarized away")
    }

    @Test
    fun `a record CAPTURED by a lambda keeps its binding`() {
        // A capture names a frame slot, so after a rewrite that slot holds
        // nothing — the binding has to stay.
        //
        // The closure has to ESCAPE for this to be a capture at all: handing it
        // to a tiny `{ f } -> f {}` gets that helper inlined, the lambda with
        // it, and `p`'s only remaining use really is a field read — which the
        // pass is then right to scalarize. Returning it inside a record keeps
        // the capture.
        val src = """
            let go = { i } -> do
              let p = { lo = i, add = i }
              { get = {} -> p.lo }
            let main = { n } -> go { i = n }
        """
        assertTrue(recordLets(src) >= 1, "a captured record was scalarized away")
    }

    @Test
    fun `a module-level record is never touched`() {
        // THE BUG THIS CAUGHT. A top-level `let` lives in the entry block and is
        // read from other functions by hash, which the block-local use check
        // cannot see. Scalarizing it returned null where a record was expected.
        val src = """
            let origin = { x = 1, y = 2 }
            let read_x = {} -> origin.x
            let main = {} -> read_x {}
        """
        val entry = lower(src).firstOrNull { it.name == "__entry__" }
        assertTrue(entry != null, "no entry block lowered")
        var lets = 0
        count(entry!!.body) { node ->
            if (node is BlockIR) {
                for (st in node.stmts) if (st is StmtIR.Let && st.value is MakeRecordIR) lets++
            }
        }
        assertTrue(lets >= 1, "a module-level record binding was scalarized away")
    }
}
