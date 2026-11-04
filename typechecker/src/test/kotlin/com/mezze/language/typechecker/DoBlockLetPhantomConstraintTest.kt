package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.collectTypeVarsInOrder
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A function's ability constraint must be ABOUT something the function's type
 * names.
 *
 * `let f = { n } -> do { let g = { xs } -> iter::map { it = xs, f = { x } -> x * 3 }; … }`
 * infers `({n: Int} -> Int)` — fully concrete, no variable in sight — and then
 * carries `with <NumMul Int 'v9 having out='c9>`, an obligation on two variables
 * that occur nowhere in its parameter or return type. No caller can satisfy it,
 * because no caller can even mention it.
 *
 * WHY THIS IS A PERFORMANCE BUG, AND A LARGE ONE. `TypedAstBuilder` derives a
 * decl's `typeParams` from `collectTypeVarsInOrder`, which walks ability
 * constraints' type arguments as well as the parameter and return types. A
 * phantom constraint therefore puts a phantom TYPE PARAMETER on the decl, and
 * `MonoDriver` reads that as "this function is generic". Its seed gate wants
 * every type argument concrete; nothing at a call site can ever bind a variable
 * the signature does not mention, so the gate rejects the function FOREVER and
 * it is never specialized. It keeps its dictionary, its stage lambda lowers
 * `x * 3` to a dictionary field read instead of arithmetic, the chain
 * canonicalizer cannot classify the lambda, and the pipeline does not fuse.
 * `bench/cases/dotprobe` measures that difference at 49 280 ps/elem against
 * 152 — so a phantom constraint is worth two orders of magnitude, and this is
 * how `k_capturing` acquires one.
 *
 * THE ANNOTATION IS THE TELL. `inferred()` and `annotated()` below are the same
 * program; writing `xs: Iter Int` makes the phantom go away. Mezze's rule is
 * that no annotation should ever be needed and performance must never depend on
 * having written one, so a fix that an annotation can substitute for is a fix
 * belonging in inference.
 *
 * THE MECHANISM, which [onOwnParameter] shows in one line: the obligation is
 * recorded TWICE. A do-block `let` re-solves its own statement
 * (`solveDoBlockBindingLocally`), and solving instantiates schemes, so the local
 * solve mints variables the enclosing solve never creates. The copy written in
 * the enclosing solve's variables threads correctly and ends up
 * `NumMul Int Int having out=Int`; the copy written in the local solve's
 * variables is bridged by nothing and stays free for good. See the bridging
 * `Constraint.Equality` in `ExprControlFlowConstraints` — which bridges the
 * binding's TYPE and not its obligations — and
 * `project_one_written_typevar_two_typevars`.
 */
class DoBlockLetPhantomConstraintTest {

    private fun typedDecls(src: String): List<TypedLetDecl> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).typedModule!!.decls.filterIsInstance<TypedLetDecl>()

    /**
     * Variables a constraint is about that the signature never names.
     *
     * Deliberately NOT `collectTypeVarsInOrder(type)`: that walk includes the
     * constraints, which is the whole defect. This asks the narrower question —
     * which variables do the PARAMETER and RETURN positions name — and measures
     * the constraints against it.
     */
    private fun phantomVars(decl: TypedLetDecl): List<String> {
        val fn = decl.scheme.type as? MezzeType.FunctionType ?: return emptyList()
        val named = (collectTypeVarsInOrder(fn.paramType) + collectTypeVarsInOrder(fn.returnType))
            .map { it.name }.toHashSet()
        return fn.abilityConstraints
            .flatMap { c -> c.typeArgs + c.associatedTypeBindings.values }
            .flatMap { collectTypeVarsInOrder(it) }
            .map { it.name }
            .filter { it !in named }
            .distinct()
    }

    private fun assertNoPhantom(name: String, src: String) {
        val decl = typedDecls(src).first { it.name == name }
        val phantoms = phantomVars(decl)
        assertTrue(
            phantoms.isEmpty(),
            "`$name` carries an ability constraint about ${phantoms.size} variable(s) its " +
                "type never names: $phantoms. Its type is ${decl.scheme.type}. A caller " +
                "cannot satisfy a constraint it cannot mention, and MonoDriver reads the " +
                "phantom as a type parameter and refuses to specialize the function.",
        )
    }

    private val PRELUDE = """
        use std::collections::iter
        use std::int
    """.trimIndent()

    /**
     * THE DEFECT. A named pipeline step, its element type inferred.
     *
     * `scaled` is applied exactly once, to `iter::range { start = 0, end = n }`,
     * so its element type is `Int` and nothing about this program is
     * polymorphic.
     */
    @Test
    fun `an inferred local pipeline step leaves no constraint the signature cannot name`() {
        assertNoPhantom(
            "inferred",
            """
            $PRELUDE
            pub let inferred = { n } -> do
              let scaled = { xs } -> iter::map { it = xs, f = { x } -> x * 3 }
              iter::fold {
                it = scaled { xs = iter::range { start = 0, end = n } },
                init = 0, f = { acc, x } -> acc + x,
              }
            """,
        )
    }

    /**
     * CONTROL, and the tell: the same program with the element type written
     * down. This one passes today. If it ever fails, the fix broke the path
     * that already worked rather than the one that did not.
     */
    @Test
    fun `an annotated local pipeline step leaves no phantom either`() {
        assertNoPhantom(
            "annotated",
            """
            $PRELUDE
            pub let annotated = { n } -> do
              let scaled = { xs: Iter Int } -> iter::map { it = xs, f = { x } -> x * 3 }
              iter::fold {
                it = scaled { xs = iter::range { start = 0, end = n } },
                init = 0, f = { acc, x } -> acc + x,
              }
            """,
        )
    }

    /**
     * CONTROL: a local step with no arithmetic raises no ability obligation at
     * all, so it cannot leak one. Pins that the defect is about the OBLIGATION
     * and not about do-block `let` bindings in general — without this, a fix
     * that disabled local lets entirely would look like a pass.
     */
    @Test
    fun `a local step without an ability obligation is clean`() {
        assertNoPhantom(
            "no_obligation",
            """
            $PRELUDE
            pub let no_obligation = { n } -> do
              let kept = { xs } -> iter::filter { it = xs, p = { x } -> x > 2 }
              iter::fold {
                it = kept { xs = iter::range { start = 0, end = n } },
                init = 0, f = { acc, x } -> acc + x,
              }
            """,
        )
    }

    /**
     * THE MECHANISM, isolated. `double`'s obligation appears twice on `on_own`:
     * once as `NumMul Int Int having out=Int` — correctly threaded, the
     * enclosing solve's copy — and once on a variable nothing bridges.
     *
     * This asserts the SHAPE rather than only the absence: every constraint
     * `on_own` carries must be about a variable it names, AND there must be no
     * two constraints of the same ability differing only in whether their
     * variables got solved. The second half is what distinguishes "the phantom
     * was removed" from "the phantom was merged with the real one", which is
     * the outcome worth having.
     */
    @Test
    fun `a local lambda's obligation is recorded once, not twice`() {
        val decl = typedDecls(
            """
            $PRELUDE
            pub let on_own = { n } -> do
              let double = { a } -> a * 2
              double { a = n }
            """,
        ).first { it.name == "on_own" }
        val fn = decl.scheme.type as MezzeType.FunctionType

        assertTrue(
            phantomVars(decl).isEmpty(),
            "`on_own` carries constraints about ${phantomVars(decl)}, which its type " +
                "(${decl.scheme.type}) never names.",
        )
        assertEquals(
            fn.abilityConstraints.map { it.abilityName }.distinct().size,
            fn.abilityConstraints.size,
            "`on_own` carries the same ability twice — ${fn.abilityConstraints}. The " +
                "do-block's local solve mints its own variables, so an obligation " +
                "recorded in both solves' variables appears as two constraints that " +
                "mean one demand.",
        )
    }
}
