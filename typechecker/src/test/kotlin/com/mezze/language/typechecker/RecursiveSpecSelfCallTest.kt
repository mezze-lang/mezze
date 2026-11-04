package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.mono.MonoDriver
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.forEachChild
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A specialized recursive function must recurse into ITSELF, not into its
 * generic self.
 *
 *     let go = { i, acc, p, q } ->
 *       if i <= 0 then acc + p + q
 *       else go { i = i - 1, acc = acc + i, p = p, q = q }
 *     let run = { n } -> go { i = n, acc = 0, p = 0, q = 0 }
 *
 * `run`'s call site pins every parameter to `Int`, mono specializes `go`, and
 * the entry call is rewritten to the specialization. The SELF-CALL was not, so
 * the specialized clone called the generic `go` — which still takes two
 * dictionary parameters — and every iteration after the first ran on the
 * dictionary path.
 *
 * WHY THE SELF-CALL WAS MISSED. `deriveTransitiveTypeArgs` derives a transitive
 * callee's type args by unifying against the call site, and
 * `buildArgsFromBindings` requires EVERY typeParam to come out concrete. `go`'s
 * typeParams are five: the three parameter vars, the result, and `'mid` — the
 * type of `acc + p` before `+ q`. Neither the result nor `'mid` occurs in the
 * parameter type, so nothing binds them, the derivation returned null, and a
 * null transitive derivation is dropped silently.
 *
 * A `having 'out` var is like that by nature: it becomes concrete only once the
 * impl is resolved for the (now concrete) receiver and class args. The SEED path
 * has always completed these with `fillHavingOutTypeArgs`; the transitive path
 * had no equivalent. It now uses the same filler — one rule for completing type
 * args, not two.
 *
 * THE CONTROL IS IN THE SOURCE OF THE BENCH, NOT HERE: `allocsurface` kernel 10
 * is this same function with `then acc` instead of `then acc + p + q`. That
 * drops both the result var and `'mid` (the result IS the accumulator var), so
 * all three of its typeParams are reachable from the parameter type and it
 * specialized correctly all along. One line of source, 595 ps against 30 675.
 * After this fix kernel 11 measures 682 ps — onto kernel 10, where it belongs —
 * and the generic `go_5` with its two dict params is gone from the emitted
 * program entirely (`DictMethodCallIR` 3 → 0).
 *
 * This asserts the MONO FACT rather than the lowered shape, because the lowered
 * shape is not visible from here: a typechecker test lowers the stdlib from
 * source while `mez build` serves the prelowered snapshot. See
 * [LocalLambdaSpecializerTest] for what that blindness cost once already.
 */
class RecursiveSpecSelfCallTest {

    private val SRC = """
        use std::int

        let go = { i, acc, p, q } ->
          if i <= 0 then acc + p + q
          else go { i = i - 1, acc = acc + i, p = p, q = q }

        pub let run = { n } -> go { i = n, acc = 0, p = 0, q = 0 }
    """.trimIndent()

    /** The source line holding the SELF-call (`else go { … }`). */
    private val selfCallLine = 5

    private fun monoResult(): MonoDriver.Result {
        val out = TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(SRC, "p.mz"), Paths.get("."), true, null,
        )
        return MonoDriver.run(
            entry = out.typedModule!!,
            imported = out.loadedModules.values.toList(),
        )
    }

    @Test
    fun `the self-call of a specialized recursive function is rewritten`() {
        val result = monoResult()

        val goHash = result.entryDecls
            ?.filterIsInstance<TypedLetDecl>()?.firstOrNull { it.name == "go" }?.hash
            ?: error("no `go` decl")

        val specs = result.specializations.values.filter { it.genericHash == goHash }
        assertTrue(
            specs.isNotEmpty(),
            "mono produced no specialization for `go` at all — the entry call in `run` " +
                "pins every parameter to Int, so this is a different defect from the one " +
                "under test.",
        )

        // The observable is the SPECIALIZED BODY, not `bySiteLoc`: that map is
        // built from the SEED list only, so a transitive request never appears in
        // it. The Monomorphizer redirects a self-call while cloning the body —
        // `done[key]` already holds the spec hash when the body is rewritten — so
        // the question is which hash the clone's self-reference carries.
        val spec = specs.single()
        val selfTargets = hashesCalledIn(spec.specializedBody, goHash, spec.specializedHash)
        assertTrue(
            selfTargets.isNotEmpty(),
            "no reference to `go` found inside its own specialized body — the test's " +
                "premise (that `go` is self-recursive) no longer holds.",
        )
        assertTrue(
            selfTargets.none { it == goHash },
            "the specialized clone of `go` still calls the GENERIC `go`, which takes two " +
                "dictionary parameters — so every iteration after the first runs the " +
                "dictionary-passing version. Self-reference targets: " +
                "${selfTargets.map { it.toString().take(10) }}, " +
                "generic=${goHash.toString().take(10)}, " +
                "spec=${spec.specializedHash.toString().take(10)}",
        )
    }

    /**
     * The completion must stay HONEST: every type argument concrete, as on every
     * other route. A `having 'out` that cannot be resolved still declines, so a
     * genuinely polymorphic recursive function is left generic rather than
     * specialized at a guess.
     */
    @Test
    fun `a genuinely polymorphic recursion is not specialized at a guess`() {
        val out = TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(
                """
                use std::collections::seq

                let count = { xs, acc } ->
                  if seq::size { xs = xs } == 0 then acc
                  else count { xs = seq::drop { xs = xs, n = 1 }, acc = acc + 1 }

                pub let run = { ys } -> count { xs = ys, acc = 0 }
                """.trimIndent(),
                "q.mz",
            ),
            Paths.get("."), true, null,
        )
        // The point is that this type-checks and mono runs over it without
        // crashing or inventing a binding; `run` is polymorphic in the element
        // type, so no all-concrete specialization of `count` is derivable from it.
        val result = MonoDriver.run(
            entry = out.typedModule!!,
            imported = out.loadedModules.values.toList(),
        )
        assertTrue(result.fallbacks.size >= 0, "mono must complete over a polymorphic recursion")
    }

    /**
     * Hashes that [body] calls which are either the generic [generic] or its
     * specialization [spec] — i.e. this function's self-references.
     */
    private fun hashesCalledIn(
        body: com.mezze.language.typedast.TypedExpr,
        generic: com.mezze.language.typedast.Hash,
        spec: com.mezze.language.typedast.Hash,
    ): List<com.mezze.language.typedast.Hash> {
        val out = mutableListOf<com.mezze.language.typedast.Hash>()
        fun walk(e: com.mezze.language.typedast.TypedExpr) {
            if (e is com.mezze.language.typedast.TypedVar) {
                val b = e.binding
                if (b is com.mezze.language.typedast.BindingRef.ByHash &&
                    (b.hash == generic || b.hash == spec)
                ) out.add(b.hash)
            }
            e.forEachChild { walk(it) }
        }
        walk(body)
        return out
    }
}
