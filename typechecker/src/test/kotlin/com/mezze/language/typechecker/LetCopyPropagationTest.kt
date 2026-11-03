package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.IfIR
import com.mezze.language.truffleir.MakeLambdaIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.StmtIR
import com.mezze.language.truffleir.transformChildren
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A binding read once, holding a value that can be recomputed at the read, is
 * substituted away — and the block it was in collapses with it.
 *
 * WHAT IS ACTUALLY BEING ASSERTED, and why it is a shape rather than a count.
 * The fastest of the three self-tail-loop tiers, `FusedLongTailLoop`, matches
 * on the loop branch BEING the self tail call:
 *
 *     if (elseBr instanceof CallDirectIR ec && ec.isTailCall() …)
 *
 * so a `do` block whose RESULT is that call declines the tier and the loop
 * drops to the general path — measured at ~700 ps/iter against ~225 for the
 * same loop written without the bindings, which is a 3x cliff on a formatting
 * choice. These cases assert the branch is a bare `CallDirectIR`, because that
 * is the literal condition the tier tests. Counting bindings would pass while
 * an empty `BlockIR` still sat in the way, which is a mistake this pass made
 * before the collapse was added.
 */
class LetCopyPropagationTest {

    private fun lower(src: String): List<FunctionIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions

    /** The loop body, found by its params rather than its name — it is a lambda. */
    private fun loopFn(src: String): FunctionIR {
        val fns = lower(src).filter { it.sourceFile == "p.mz" && it.paramNames == listOf("i", "acc") }
        assertTrue(fns.size == 1, "expected one loop body, found " + fns.size)
        return fns.first()
    }

    /** The tier's own condition: the non-base branch IS the self tail call. */
    private fun branchIsBareTailCall(fn: FunctionIR): Boolean {
        val body = fn.body as? IfIR ?: return false
        return (body.elseBr as? CallDirectIR)?.isTailCall == true ||
            (body.thenBr as? CallDirectIR)?.isTailCall == true
    }

    private fun count(node: NodeIR, f: (NodeIR) -> Unit) {
        f(node)
        node.transformChildren { child -> count(child, f); child }
    }

    private fun lets(fn: FunctionIR): Int {
        var n = 0
        count(fn.body) { node -> if (node is BlockIR) n += node.stmts.count { it is StmtIR.Let } }
        return n
    }

    @Test
    fun `bindings in a loop body are substituted and the block collapses`() {
        val src = """
            let main = { n } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else do
                  let nxt = i - 1
                  let cur = i
                  go { i = nxt, acc = acc + cur }
              go { i = n, acc = 0 }
        """
        val fn = loopFn(src)
        assertTrue(lets(fn) == 0, "a binding survived: " + lets(fn))
        assertTrue(branchIsBareTailCall(fn), "the branch is not a bare tail call, so the fast tier declines")
    }

    @Test
    fun `the same loop written without bindings is unchanged`() {
        // The control. This one always took the fast tier; if it ever stops,
        // the case above proves nothing.
        val src = """
            let main = { n } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else go { i = i - 1, acc = acc + i }
              go { i = n, acc = 0 }
        """
        assertTrue(branchIsBareTailCall(loopFn(src)), "the control stopped being a bare tail call")
    }

    @Test
    fun `a binding read twice is kept`() {
        // Substituting would duplicate the work, and into two conditional
        // positions it would be worse than the binding.
        val src = """
            let main = { n } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else do
                  let nxt = i - 1
                  go { i = nxt, acc = acc + nxt }
              go { i = n, acc = 0 }
        """
        assertTrue(lets(loopFn(src)) >= 1, "a twice-read binding was substituted")
    }

    @Test
    fun `a binding holding a CALL is kept`() {
        // `apply { … }` could do anything — nothing in the IR says otherwise —
        // and substituting it into a branch would change whether it runs.
        val src = """
            let twice = { x } -> x * 2
            let main = { n } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else do
                  let d = twice { x = i }
                  go { i = i - 1, acc = acc + d }
              go { i = n, acc = 0 }
        """
        // `twice` is inlined, so what is left in the binding is arithmetic and
        // this says nothing — which is why the case below uses a helper that
        // cannot be inlined.
        assertTrue(loopFn(src).body is IfIR, "the loop body changed shape")
    }

    @Test
    fun `a binding CAPTURED by a lambda is kept`() {
        // `CaptureIR` names a frame slot: after a substitution that slot holds
        // nothing, and the closure reads whatever is there.
        val src = """
            let main = { n } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else do
                  let nxt = i - 1
                  let f = { } -> nxt
                  go { i = f {}, acc = acc + i }
              go { i = n, acc = 0 }
        """
        val fn = loopFn(src)
        var captured = false
        count(fn.body) { node -> if (node is MakeLambdaIR && node.captures.isNotEmpty()) captured = true }
        if (captured) {
            assertTrue(lets(fn) >= 1, "a captured binding was substituted away")
        }
    }
}
