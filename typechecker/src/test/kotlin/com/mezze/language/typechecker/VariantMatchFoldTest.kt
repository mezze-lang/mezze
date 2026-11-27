package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.MakeVariantIR
import com.mezze.language.truffleir.MatchIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.transformChildren
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A variant constructed as the scrutinee of the match that destructures it is
 * not built.
 *
 *     match Some { val = i } is
 *       Some { val } -> … val …
 *       None -> …
 *
 * MEASURED on `bench/cases/coresurface`, A/B on one CLI at n=2e7, min of 5:
 * the Option form 1 751 → 222 ps/iter (7.9x) and a three-constructor user
 * variant 1 753 → 224 (7.8x), both landing on a 220/225 control, with young
 * collections 16 → 2. Per iteration the unfolded form allocates a payload
 * record, a box for the Int in it, and a tag wrapper, none of which outlives
 * the expression — and both tags are known at compile time on both sides.
 *
 * MATCH IS FIRST-MATCH-WINS, so the refusals are the whole risk and three of
 * the cases below are about them. Folding to the case that carries the tag is
 * correct only if nothing earlier could have matched: an arm whose pattern this
 * pass cannot rule out, a guard that might fail and fall through, or an if-let
 * binding each leave the choice unmade.
 */
class VariantMatchFoldTest {

    private fun lower(src: String): List<FunctionIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions

    private fun userFns(src: String) = lower(src).filter { it.sourceFile == "p.mz" }

    private fun count(node: NodeIR, f: (NodeIR) -> Unit) {
        f(node)
        node.transformChildren { child -> count(child, f); child }
    }

    /** Variants still constructed, anywhere in the module's own functions. */
    private fun built(src: String): Int {
        var n = 0
        for (fn in userFns(src)) count(fn.body) { if (it is MakeVariantIR) n++ }
        return n
    }

    /** Matches still performed. */
    private fun matches(src: String): Int {
        var n = 0
        for (fn in userFns(src)) count(fn.body) { if (it is MatchIR) n++ }
        return n
    }

    @Test
    fun `a variant built as its own match's scrutinee is not built`() {
        val src = """
            let main = { n } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else match Some { val = i } is
                  Some { val } -> go { i = i - 1, acc = acc + val }
                  None -> acc
              go { i = n, acc = 0 }
        """
        assertTrue(built(src) == 0, "a variant was still constructed: " + built(src))
        assertTrue(matches(src) == 0, "a match survived: " + matches(src))
    }

    @Test
    fun `a three-constructor user variant folds to the arm carrying the tag`() {
        val src = """
            type Tag = | A { n: Int }, B { n: Int }, C { n: Int } |
            let main = { n } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else match A { n = i } is
                  A { n } -> go { i = i - 1, acc = acc + n }
                  B { ... } -> acc
                  C { ... } -> acc
              go { i = n, acc = 0 }
        """
        assertTrue(built(src) == 0, "a variant was still constructed: " + built(src))
        assertTrue(matches(src) == 0, "a match survived: " + matches(src))
    }

    @Test
    fun `a GUARD on the matching arm refuses`() {
        // A guard that fails falls through to a later arm, so the arm carrying
        // the tag is not yet the arm that runs.
        val src = """
            let main = { n } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else match Some { val = i } is
                  Some { val } if val > 5 -> go { i = i - 1, acc = acc + val }
                  Some { val } -> go { i = i - 1, acc = acc + val }
                  None -> acc
              go { i = n, acc = 0 }
        """
        assertTrue(built(src) >= 1, "a guarded match was folded away")
    }

    @Test
    fun `a WILDCARD arm before the matching one refuses`() {
        // The wildcard matches first, and may bind the whole scrutinee — which
        // is the one thing the fold removes.
        val src = """
            let main = { n } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else match Some { val = i } is
                  _ -> go { i = i - 1, acc = acc + i }
              go { i = n, acc = 0 }
        """
        assertTrue(built(src) >= 1, "a wildcard-first match was folded away")
    }

    @Test
    fun `a variant that is NOT a scrutinee is left alone`() {
        // Returned as a value, so it has to exist.
        val src = """
            let wrap = { i } -> Some { val = i }
            let main = { n } -> wrap { i = n }
        """
        assertTrue(built(src) >= 1, "an escaping variant was folded away")
    }

    @Test
    fun `a match on something other than a construction is untouched`() {
        // The control: nothing is known about the scrutinee's tag here, so the
        // match must stay. If this ever folds, the cases above prove nothing.
        val src = """
            let main = { o } -> match o is
              Some { val } -> val
              None -> 0
        """
        assertTrue(matches(src) >= 1, "a match on an opaque scrutinee was folded")
    }
}
