package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.DictMethodCallIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.transformChildren
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A type param a CALL SITE can never supply is mono's to determine, not mono's
 * reason to decline.
 *
 * The caller of an un-annotated numeric function inherits its unresolved
 * obligations, and the type params carrying them appear nowhere in the caller's
 * own signature. For the mandelbrot grid walk:
 *
 *     grid  { side, span } -> Int
 *       params ['span, 'u, 'v, 'w, 'x]
 *       NumDiv ['span, Float] out='u        px * 3.0 / span, inside pixel
 *       NumSub [Float, 'u]    out='w
 *       NumSub [Float, 'v]    out='w        ← 'v only ever an OPERAND
 *       NumAdd ['x, Float]    out={}        ← inherited, its out already discharged
 *
 * The call site pins `span`, and the forward filler then resolves `'u` and `'w`
 * off the impls' `having out`. `'v` and `'x` it could not: the first needs the
 * chain read BACKWARDS — which operand makes this impl produce Float — and the
 * second arrives with an EMPTY binding map, because the callee it came from had
 * already discharged its own output, so the forward filler skipped it before
 * looking at all.
 *
 * Two params out of five, and the whole site was refused. `grid` stayed generic,
 * which left the `pixel` call inside it polymorphic, which left the `escape` call
 * inside THAT polymorphic — and the innermost one is the loop. On
 * `bench/cases/mandelannot` filling these two took the kernel from 704 ns/px to a
 * 58.0 ns/px slope, level with the same loop written with its types declared
 * (64.2), identical checksums.
 *
 * THIS IS A MONO-ONLY RULE, deliberately. The typechecker does the same backward
 * read but refuses to DEFAULT on a concrete co-operand, because at that point the
 * callers have not arrived and `{a, b} -> do { let t = a + b; t * 2 }` is
 * indistinguishable from the chain above — see `AbilityOutputImprovementTest`'s
 * "an intermediate whose operands are parameters is NOT defaulted". Here every
 * caller HAS arrived: this runs against one specialization request, for a param no
 * site could ever name.
 *
 * It asserts the LOWERED shape rather than the mono tables because
 * `MonoDriver.run` is driven from the post-`wrapTopLevel` module with the record
 * and decl shapes the emitter builds; a test that calls it directly with the
 * unwrapped module seeds nothing at all and would pass for the wrong reason.
 */
class MonoInheritedResidueTest {

    private val GRID = """
        use std::float
        use std::int

        let escape = { cr, ci, zr, zi, i } ->
          if i >= 50 then i
          else do
            let zr2 = zr * zr
            let zi2 = zi * zi
            if zr2 + zi2 > 4.0 then i
            else escape {
              cr = cr,
              ci = ci,
              zr = zr2 - zi2 + cr,
              zi = 2.0 * zr * zi + ci,
              i = i + 1,
            }

        let pixel = { p, side, span } -> do
          let px = float::from_int { n = p % side }
          let py = float::from_int { n = p / side }
          escape {
            cr = px * 3.0 / span - 2.0,
            ci = py * 3.0 / span - 1.5,
            zr = 0.0,
            zi = 0.0,
            i = 0,
          }

        let grid = { side, span } -> pixel { p = 7, side = side, span = span }

        let main = {} -> grid { side = 400, span = 400.0 }
    """

    private fun userFns(src: String): List<FunctionIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions.filter { it.sourceFile == "p.mz" }

    private fun walk(node: NodeIR, f: (NodeIR) -> Unit) {
        f(node)
        node.transformChildren { child -> walk(child, f); child }
    }

    /** Dictionary plumbing in a function's own body — a call THROUGH a dictionary
     *  slot, or a `__dict_` field handed to a callee. */
    private fun dictPlumbing(fn: FunctionIR): Int {
        var n = 0
        walk(fn.body) { node ->
            if (node is DictMethodCallIR) n++
            if (node is MakeRecordIR) n += node.fields.count { it.name.startsWith("__dict_") }
        }
        return n
    }

    /**
     * ONE OF THESE CARRIES THE FIX and the other is a guard; with
     * `fillOperandsFromEvidence` reverted, "the cascade reaches every function in
     * the chain" fails and this one still passes. It passes because an unfixed call
     * site hands its dictionaries over POSITIONALLY under the scalar ABI rather
     * than as `__dict_`-named record fields, so a field-name check does not see
     * them — the same blind spot `ChainedOpMonoTest` records. Kept as a regression
     * guard against named dictionary plumbing reappearing at a call site, not as
     * evidence for this fix.
     */
    @Test
    fun `the grid call site passes no dictionaries`() {
        val main = userFns(GRID).single { it.name == "main" }
        val n = dictPlumbing(main)
        assertTrue(n == 0,
            "main still plumbs $n dictionaries into `grid`. Its parameters are both " +
                "pinned, so what refused the site is the obligation residue `grid` " +
                "inherited from `pixel` — params that appear nowhere in " +
                "`{ side, span } -> Int` and that no caller could ever name.")
    }

    @Test
    fun `the cascade reaches every function in the chain`() {
        // The CASCADE is the point: specializing `grid` makes the `pixel` call
        // inside it concrete, which makes the `escape` call inside that one
        // concrete. `escape` is the loop.
        //
        // It asks for the EXISTENCE of a dictionary-free copy at each arity rather
        // than the ABSENCE of a dictionary anywhere: the generic originals stay in
        // the lowered list beside their specializations, `pixel` among them with
        // its four pieces of plumbing, and that is correct — another module could
        // still call it at a type this program never uses.
        val fns = userFns(GRID)
        for ((arity, name) in listOf(2 to "grid", 3 to "pixel", 5 to "escape")) {
            val clean = fns.filter { fn ->
                fn.paramNames.size == arity &&
                    fn.paramNames.none { it.startsWith("__dict_") } &&
                    dictPlumbing(fn) == 0
            }
            assertTrue(clean.isNotEmpty(),
                "no dictionary-free $arity-parameter function, so `$name` never got a " +
                    "specialization the chain could call. At that arity: " +
                    fns.filter { it.paramNames.size == arity }
                        .map { it.name to (it.paramNames to dictPlumbing(it)) })
        }
    }
}
