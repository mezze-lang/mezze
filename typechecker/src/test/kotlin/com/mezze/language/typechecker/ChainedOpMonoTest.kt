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
 * A helper whose arithmetic CHAINS is specialised at a concrete call site, so it
 * takes no dictionaries and its operators become native arithmetic.
 *
 * WHY A CHAIN IS DIFFERENT. Every numeric ability is heterogeneous —
 * `ability NumAdd 'r for 'l having 'out` — so `i + 0` does not pin `i`: each
 * operator threads a fresh `'out` into the next operand. The scheme for
 * `{ i } -> i + 0 + 0 + …` is
 *
 *     typeParams = ['y12, out, 'b13, 'e13, 'h13, 'k13, 'n13, 'q13, 't13]
 *     type       = { i: 'y12 } -> 'out
 *     NumAdd Int 'y12 having 'out = 'b13
 *     NumAdd Int 'b13 having 'out = 'e13      … and so on
 *
 * Nine type params, of which the type mentions TWO. Unifying the surface type
 * against the call site binds those two; the other seven are visible only inside
 * the constraints, and `MonoDriver.fillHavingOutTypeArgs` exists to fill them
 * from each resolved impl's associated `out`.
 *
 * IT FILLED THE WRONG ONE. The target was chosen as "the first unresolved param
 * that is not among this constraint's own args", which for the list above is the
 * function's RETURN — so constraint #1's output went to the return instead of to
 * `'b13`. `'b13` stayed a variable, every later constraint was skipped for having
 * a non-concrete argument, the all-concrete gate failed and nothing was
 * specialised. Accidentally right for ONE constraint, wrong for a chain. The fix
 * reads each constraint's own `associatedTypeBindings` — which name the variable
 * its output IS — and iterates, because one constraint's `out` is the next one's
 * operand.
 *
 * MEASURED, `bench/cases/letcost` kernel 6 at n=2e7, same CLI, min of 3:
 *
 *     before   161 285 ps/iter   370 young GCs
 *     after        647              3
 *
 * 249x, with the control kernel at 220 either way and identical checksums. The
 * unspecialised form carried eight dictionary PARAMETERS and eight
 * `DictMethodCallIR`, each allocating a three-field record; annotated
 * `{ i: Int } -> Int` the same function always compiled to native adds. That
 * made an annotation worth 249x, which is the one thing performance must not
 * depend on.
 *
 * WHAT THESE ASSERT, and why not the obvious thing. `loweredFunctions` still
 * contains the now-dead GENERIC copy — only the CLI drops what nothing reaches —
 * so counting dictionaries across every function reports eight whether or not
 * the fix works.
 *
 * THE DISCRIMINATOR IS `a dictionary-free specialisation exists`, and it is the
 * only one: with the fix reverted, that case fails and the other two still pass.
 * They pass because the unfixed call site hands its dictionaries over
 * POSITIONALLY under the scalar ABI rather than as `__dict_`-named record
 * fields, so a field-name check does not see them. They are kept as regression
 * guards against dictionary plumbing reappearing at a call site, not as evidence
 * for this fix.
 */
class ChainedOpMonoTest {

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

    /** The helper is over the inline budget on purpose: a helper small enough to
     *  inline becomes concrete for a different reason and proves nothing.
     *
     *  The call sites pass LITERALS. An earlier version wrote
     *  `{ n } -> chain { i = n }`, where `n` is itself unpinned — so the site was
     *  not concrete and seeding refused it for the right reason. That test was
     *  wrong, not the compiler: a specialisation needs a concrete argument. */
    private val chained = """
        use std::int
        let chain = { i } -> i + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0
        let main = {} -> chain { i = 1 } + chain { i = 2 }
    """

    /** Dictionary plumbing anywhere in a function's own body — a `__dict_` field
     *  passed at a call site, or a call THROUGH a dictionary slot. */
    private fun dictPlumbing(fn: FunctionIR): Int {
        var n = 0
        count(fn.body) { node ->
            // A call THROUGH a dictionary slot, and a `__dict_` field handed to a
            // callee. There is no single node type meaning "dictionary argument",
            // so the field names are read off the record that carries them.
            if (node is DictMethodCallIR) n++
            if (node is MakeRecordIR) n += node.fields.count { it.name.startsWith("__dict_") }
        }
        return n
    }

    private fun mainOf(src: String): FunctionIR {
        val m = userFns(src).filter { it.name == "main" }
        assertTrue(m.size == 1, "expected one main, found " + m.size)
        return m.first()
    }

    @Test
    fun `the call site passes no dictionaries`() {
        val n = dictPlumbing(mainOf(chained))
        assertTrue(n == 0, "main still plumbs dictionaries: $n")
    }

    @Test
    fun `a dictionary-free specialisation exists`() {
        // The specialised copy takes the argument alone. The generic original is
        // still in the list, with its eight `__dict_add` params, so this asks for
        // the EXISTENCE of a one-param arithmetic copy rather than the absence of
        // the generic one.
        val specialised = userFns(chained).filter { fn ->
            fn.paramNames.size == 1 &&
                fn.paramNames.none { it.startsWith("__dict_") } &&
                fn.name != "main" && fn.name != "__entry__"
        }
        assertTrue(specialised.isNotEmpty(), "no dictionary-free specialisation was produced")
        assertTrue(specialised.any { dictPlumbing(it) == 0 },
            "every candidate specialisation still plumbs dictionaries")
    }

    @Test
    fun `a single-operator helper is specialised too`() {
        // One constraint is the case the old target-picking got right by
        // accident. It must keep working — if this regresses, the chain cases
        // above say nothing about chains specifically.
        val src = """
            use std::int
            let one = { i } -> i + 0
            let main = {} -> one { i = 1 }
        """
        val n = dictPlumbing(mainOf(src))
        assertTrue(n == 0, "the single-operator control plumbs dictionaries: $n")
    }
}
