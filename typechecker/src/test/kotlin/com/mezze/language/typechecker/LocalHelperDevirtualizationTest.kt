package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.truffleir.CallIndirectIR
import com.mezze.language.truffleir.CallIndirectScalarIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.ReadCaptureIR
import com.mezze.language.truffleir.ReadFieldIR
import com.mezze.language.truffleir.ReadLocalIR
import com.mezze.language.truffleir.transformChildren
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A `let`-bound lambda that captures nothing is the same function at every call
 * through its name, so the call does not have to read its slot and dispatch on
 * the value — it can be a direct call by hash.
 *
 * WHY THIS IS WORTH A PASS. Devirtualising is worth little on its own; what it
 * unlocks is everything downstream that only looks at direct calls. A record a
 * helper builds and the caller only reads fields of is erased only once the two
 * sit in ONE function, and that needs the call direct (so `tryInlineDirectCall`
 * can splice the body) before the scalarizer can see anything. Written as a
 * top-level helper that loop measured 713 ps/iter; written as a local one,
 * which is how anybody would actually write it, 5 306 ps.
 *
 * THE CAPTURED CASE IS THE ONE THAT MATTERS. A helper is almost never called
 * from the frame that bound it:
 *
 *     let split = { i } -> { nxt = i - 1, cur = i }
 *     let go = { i, acc } -> … split { i = i } …
 *
 * `go` CAPTURES `split`, so inside `go` the name is a capture read, not a local
 * slot. A pass that only knew the current frame's slots would refuse every
 * realistic shape and measure as nothing.
 *
 * WHAT THE ASSERTIONS COUNT, and why not the obvious thing. `ReadFieldIR` is
 * NOT a record read here: an ability op on a receiver whose type is still a
 * variable lowers to one too, so `i - 1` inside a generic helper IS a
 * `ReadFieldIR(i, "sub")` with a call through it. Counting those as record
 * reads mixes the two completely — and `acc + p.add` makes the field name
 * `add` ambiguous with the op of the same name, which is why the records here
 * are spelled `nxt`/`cur`. The same goes for calls: the arithmetic in a generic
 * body is a `CallIndirectIR`, so what says a helper call was devirtualised is
 * specifically the absence of a call whose callee is a VALUE — a local or a
 * capture — not the absence of indirect calls.
 */
class LocalHelperDevirtualizationTest {

    private fun lower(src: String): List<FunctionIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions

    /** The loop body, which is where every call under test sits. Named by its
     *  params: `main`'s own tail call to it is a closure call too (`go` is
     *  recursive and capturing, so it is NOT devirtualised), and counting that
     *  would make every assertion here read the same whatever happened. */
    private fun loopFn(src: String): FunctionIR {
        val fns = lower(src).filter { it.sourceFile == "p.mz" && it.paramNames == listOf("i", "acc") }
        assertTrue(fns.size == 1, "expected one loop body, found " + fns.size)
        return fns.first()
    }

    private fun count(node: NodeIR, f: (NodeIR) -> Unit) {
        f(node)
        node.transformChildren { child -> count(child, f); child }
    }

    private fun tally(src: String, pred: (NodeIR) -> Boolean): Int {
        var n = 0
        count(loopFn(src).body) { if (pred(it)) n++ }
        return n
    }

    /** Calls that dispatch on a function VALUE — the shape devirtualisation
     *  removes. An arithmetic op in a generic body is also a CallIndirect, but
     *  through a field read, so it is not one of these. */
    private fun closureCalls(src: String) = tally(src) { n ->
        val callee = when (n) {
            is CallIndirectScalarIR -> n.function
            is CallIndirectIR -> n.function
            else -> null
        }
        callee is ReadLocalIR || callee is ReadCaptureIR
    }

    /** Reads of the helper's record, by the names only it uses. */
    private fun recordReads(src: String) =
        tally(src) { it is ReadFieldIR && it.fieldName in setOf("nxt", "cur") }

    private fun recordsBuilt(src: String) = tally(src) { n ->
        n is MakeRecordIR && n.fields.any { it is com.mezze.language.truffleir.FieldIR && it.name == "nxt" }
    }

    private val localHelper = """
        let main = { n } -> do
          let split = { i } -> { nxt = i - 1, cur = i }
          let go = { i, acc } ->
            if i <= 0 then acc
            else do
              let p = split { i = i }
              go { i = p.nxt, acc = acc + p.cur }
          go { i = n, acc = 0 }
    """

    @Test
    fun `a record from a LOCAL helper called inside a closure is erased end to end`() {
        // The whole chain in one case: the call to `split` goes direct, its body
        // is spliced into `go`, and the record — now built and read in the same
        // function — becomes two locals. Unfixed, the record is built inside
        // `split` and RETURNED, so both reads survive and nothing downstream
        // can reach it.
        assertTrue(closureCalls(localHelper) == 0,
            "a call through a function value survived: " + closureCalls(localHelper))
        assertTrue(recordsBuilt(localHelper) == 0,
            "the record is still built: " + recordsBuilt(localHelper))
        assertTrue(recordReads(localHelper) == 0,
            "a record field read survived: " + recordReads(localHelper))
    }

    @Test
    fun `a call to a captured helper too big to inline still goes direct`() {
        // Devirtualisation on its own, with inlining out of the picture: the
        // helper is over the inline budget, so the call stays a call — but a
        // DIRECT one. This is the part that pays where nothing can be spliced,
        // and it is what any later pass needs in order to know the callee.
        val src = """
            let main = { n } -> do
              let heavy = { i } ->
                i * 3 + i * 5 + i * 7 + i * 11 + i * 13 + i * 17 + i * 19 + i * 23
              let go = { i, acc } ->
                if i <= 0 then acc
                else go { i = i - 1, acc = acc + heavy { i = i } }
              go { i = n, acc = 0 }
        """
        assertTrue(closureCalls(src) == 0, "the helper call still goes through its value")
        assertTrue(tally(src) { it is CallDirectIR } >= 1, "no direct call to the helper")
    }

    @Test
    fun `a helper that CAPTURES is left alone`() {
        // The refusal. `bias` makes `scale` a closure, and its captures live in
        // the VALUE — a direct call by hash would pass args into a frame that
        // was never given them, so this call has to keep going through the value.
        val src = """
            let main = { n } -> do
              let bias = n + 1
              let scale = { i } -> i * bias
              let go = { i, acc } ->
                if i <= 0 then acc
                else go { i = i - 1, acc = acc + scale { i = i } }
              go { i = n, acc = 0 }
        """
        assertTrue(closureCalls(src) >= 1, "a capturing helper was devirtualised")
    }

    @Test
    fun `a top-level helper was already direct`() {
        // The control for the first case: the same loop with the helper at
        // module level, which went through `tryInlineDirectCall` before any of
        // this. If this regresses, the first case's result says nothing.
        val src = """
            let split = { i } -> { nxt = i - 1, cur = i }
            let go = { i, acc } ->
              if i <= 0 then acc
              else do
                let p = split { i = i }
                go { i = p.nxt, acc = acc + p.cur }
            let main = { n } -> go { i = n, acc = 0 }
        """
        assertTrue(recordsBuilt(src) == 0, "the record is still built")
        assertTrue(recordReads(src) == 0, "a record field read survived")
    }
}
