package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.IfIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.ReadFieldIR
import com.mezze.language.truffleir.transformChildren
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A parameter only ever FIELD-READ becomes one parameter per field, and the
 * record its callers built stops existing.
 *
 * THE SHAPE is the ordinary functional loop, where the state is a record:
 *
 *     let go = { st } ->
 *       if st.i <= 0 then st.acc
 *       else go { st = { i = st.i - 1, acc = st.acc + st.i } }
 *
 * One record per iteration, allocated by the caller and destructured by the
 * callee, which is the same function. MEASURED on `bench/cases/recordcost`
 * kernel 2 at 2 769 ps/iter with 25 young collections against 233 ps and 2 for
 * the same loop carrying `i` and `acc` positionally.
 *
 * WHAT THESE ASSERT. The parameter list is the direct evidence — two parameters
 * where there was one, named for the fields — and the self call carrying
 * positional arguments rather than a record is what makes the loop eligible for
 * `FusedLongTailLoop`. `MEZZE_TRACE_SELFTAIL` confirms the tier goes
 * `params=1 tier=not-fused` → `params=2 tier=FUSED`, but a tier is not
 * something a lowering test can read, so these assert the shape the tier
 * matches on.
 *
 * THE REFUSALS ARE WHERE THE RISK IS, because changing a function's arity is
 * sound only if every caller changes with it. A bare use of the parameter, a
 * caller whose field set differs, a closure that escapes — each has to leave
 * the function alone, and the last three cases pin that.
 *
 * ONE REFUSAL HAD TO BE RELAXED, carefully: every recursive local lambda
 * captures the slot it is stored in, so that it can call itself through its own
 * value. A capture reads the slot, so the "slot is only ever called" check
 * refused every recursive loop — the entire point of the pass. That capture is
 * dead by construction when the self-calls are direct, and the pass now allows
 * exactly that case: the candidate's OWN closure, whose body never reads the
 * capture index it occupies.
 */
class ParamScalarizationTest {

    private fun lower(src: String): List<FunctionIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions

    private fun userFns(src: String) = lower(src).filter { it.sourceFile == "p.mz" }

    /** The loop body: the lambda whose parameters came from `st`. */
    private fun loopFns(src: String) = userFns(src).filter { fn ->
        fn.paramNames.any { it.startsWith("st") }
    }

    private fun count(node: NodeIR, f: (NodeIR) -> Unit) {
        f(node)
        node.transformChildren { child -> count(child, f); child }
    }

    private val recordState = """
        let main = { n } -> do
          let go = { st } ->
            if st.i <= 0 then st.acc
            else go { st = { i = st.i - 1, acc = st.acc + st.i } }
          go { st = { i = n, acc = 0 } }
    """

    @Test
    fun `a record-only-field-read parameter becomes one parameter per field`() {
        val fns = loopFns(recordState)
        assertTrue(fns.isNotEmpty(), "no loop body found")
        assertTrue(
            fns.all { it.paramNames == listOf("st__i", "st__acc") },
            "parameters were not expanded: " + fns.map { it.paramNames },
        )
    }

    @Test
    fun `and its self call carries the fields positionally`() {
        // What the fused tail-loop tier matches on: the branch is the self call
        // with one argument per parameter, and no record anywhere in the body.
        val fns = loopFns(recordState)
        assertTrue(fns.isNotEmpty(), "no loop body found")
        for (fn in fns) {
            val body = fn.body as? IfIR
            assertTrue(body != null, "the loop body is no longer an if")
            val call = (body!!.elseBr as? CallDirectIR) ?: (body.thenBr as? CallDirectIR)
            assertTrue(call != null, "the branch is not a bare self call")
            assertTrue(call!!.args.size == 2, "the self call carries ${call.args.size} args, not 2")
            var records = 0
            count(fn.body) { if (it is MakeRecordIR) records++ }
            // The remaining record, if any, belongs to an ability op's argument
            // (`acc.add { r = … }`), never to the state.
            var stateReads = 0
            count(fn.body) { if (it is ReadFieldIR && it.fieldName in setOf("i", "acc")) stateReads++ }
            assertTrue(stateReads == 0, "a state field read survived: $stateReads")
        }
    }

    @Test
    fun `a parameter used BARE is left alone`() {
        // The record is a value in its own right — returned here — so there is
        // nothing to expand it into.
        val src = """
            let main = { n } -> do
              let go = { st } ->
                if st.i <= 0 then st
                else go { st = { i = st.i - 1, acc = st.acc + st.i } }
              go { st = { i = n, acc = 0 } }
        """
        val fns = loopFns(src)
        assertTrue(fns.isNotEmpty(), "no loop body found")
        assertTrue(fns.all { it.paramNames == listOf("st") },
            "a bare-used parameter was expanded: " + fns.map { it.paramNames })
    }

    @Test
    fun `a closure that ESCAPES is left alone`() {
        // Handed out as a value, its arity is visible somewhere this pass
        // cannot rewrite, so the parameter has to stay a record.
        val src = """
            let apply = { f, v } -> f { st = v }
            let main = { n } -> do
              let go = { st } -> st.i + st.acc
              apply { f = go, v = { i = n, acc = 1 } }
        """
        val fns = loopFns(src)
        if (fns.isNotEmpty()) {
            assertTrue(fns.all { it.paramNames == listOf("st") },
                "an escaping closure's parameter was expanded: " + fns.map { it.paramNames })
        }
    }
}
