package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.collectTypeVarsInOrder
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.mono.LocalLambdaSpecializer
import com.mezze.language.typedast.TypedBlock
import com.mezze.language.typedast.TypedDecl
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedStatement
import com.mezze.language.typedast.forEachChild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * [LocalLambdaSpecializer]: a polymorphic LOCAL lambda is monomorphised at the
 * type its uses ask for.
 *
 * A do-block `let` bound to a lambda GENERALIZES (#76), and generalization is
 * suppressed only for a NON-GROUND capture (#15). So the ordinary way of naming
 * a step in a pipeline
 *
 *     let scaled = { xs } -> iter::map { it = xs, f = { x } -> x * factor }
 *
 * produces a local POLYMORPHIC function:
 *
 *     LET  scaled  ({xs: Iter['h]} -> Iter['o])   with <NumMul Int 'h having out='o>
 *     CALL scaled  ({xs: Iter[Int]} -> Iter[Int]) with <NumMul Int Int having out=Int>
 *
 * The call site was always concrete and always named the impl that discharges
 * the obligation; only the BODY kept the variables, which makes `x * factor` a
 * dictionary field read instead of arithmetic. `Monomorphizer` cannot help: it
 * specializes DECLS by content hash and emits new ones, while a local binding is
 * not a decl and the function around it is already concrete, so no
 * specialization of it is ever produced.
 *
 * WHAT THIS TEST IS, AND IS NOT. It asserts the TRANSFORMATION — that the
 * binding's type loses its variables and its discharged obligation, and that a
 * binding whose uses disagree is left alone. It does NOT assert that the
 * downstream chain fuses, and that is not an omission:
 *
 *   - a typechecker lowering test is BLIND here, because that path type-checks
 *     and lowers the stdlib FROM SOURCE while `mez build` serves
 *     `PrecompiledStdlib.preloweredFunctions`; the pipeline fuses either way on
 *     the from-source path, so such a test passes with the pass disabled and is
 *     no evidence at all (measured: it did);
 *   - `BytecodeCompiler.FUSED_EMITS`, which [ListFoldElementKindFusionTest] uses
 *     to assert a chosen path, records nothing for an `iter` chain (measured:
 *     empty for the inline spelling too, which does fuse).
 *
 * So the perf claim rests on a measurement with the pass as its own switch,
 * recorded in PERF-LOG: `bench/cases/dotprobe` k3 at 64 672 ps/elem with the
 * pass off against 206 with it on, control k1 steady at 171, identical
 * checksums — 314x. `NamedPipelineStepFusionTest` guards the semantics of the
 * specialized code by running it.
 */
class LocalLambdaSpecializerTest {

    private fun checked(src: String) =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        )

    /** Every impl in scope: the module's own plus every loaded module's. */
    private fun specialize(src: String): List<TypedDecl> {
        val out = checked(src)
        val module = out.typedModule!!
        val impls = module.abilityImpls + out.loadedModules.values.flatMap { it.abilityImpls }
        return LocalLambdaSpecializer.specializeDecls(
            module.decls, impls, emptyMap(),
        ) { expr, _ -> expr }
    }

    /** The `let` named [name] anywhere inside [decl]'s value. */
    private fun localLet(decl: TypedLetDecl, name: String): TypedStatement.Let {
        var found: TypedStatement.Let? = null
        fun walk(e: TypedExpr) {
            if (e is TypedBlock) {
                for (s in e.statements) if (s is TypedStatement.Let && s.name == name) found = s
            }
            e.forEachChild { walk(it) }
        }
        walk(decl.value)
        return found ?: error("no local `let $name` in ${decl.name}")
    }

    private fun letIn(decls: List<TypedDecl>, declName: String, letName: String) =
        localLet(decls.filterIsInstance<TypedLetDecl>().first { it.name == declName }, letName)

    private val PRELUDE = """
        use std::collections::iter
        use std::collections::seq
        use std::int
    """.trimIndent()

    private val CAPTURING = """
        $PRELUDE
        pub let run = { n } -> do
          let factor = if n > 0 then 3 else 1
          let scaled = { xs } -> iter::map { it = xs, f = { x } -> x * factor }
          iter::fold {
            it = scaled { xs = iter::range { start = 0, end = n } },
            init = 0, f = { acc, x } -> acc + x,
          }
    """

    /** THE DEFECT: before the pass, `scaled` is polymorphic and constrained. */
    @Test
    fun `the unspecialized binding is polymorphic and carries its obligation`() {
        val before = letIn(checked(CAPTURING).typedModule!!.decls, "run", "scaled")
        val fn = before.type as MezzeType.FunctionType
        assertTrue(
            collectTypeVarsInOrder(fn, sortFields = false).isNotEmpty(),
            "`scaled` was expected to be polymorphic before the pass, but its type is " +
                "${before.type} — if this fails, generalization changed and the pass may " +
                "have nothing left to do.",
        )
        assertTrue(
            fn.abilityConstraints.isNotEmpty(),
            "`scaled` was expected to carry the NumMul obligation before the pass; " +
                "its type is ${before.type}",
        )
    }

    /**
     * THE FIX: the binding's type is pinned at the type its one use asks for,
     * and the discharged obligation is gone from the signature.
     *
     * The obligation must come OFF the type, not merely be resolvable: left on,
     * lowering threads a dictionary the rewritten body no longer reads.
     */
    @Test
    fun `a single-use local lambda is pinned and loses its obligation`() {
        val after = letIn(specialize(CAPTURING), "run", "scaled")
        val fn = after.type as MezzeType.FunctionType
        assertEquals(
            emptyList<Any>(), collectTypeVarsInOrder(fn, sortFields = false),
            "`scaled` still names type variables after specialization: ${after.type}",
        )
        assertEquals(
            emptyList<Any>(), fn.abilityConstraints,
            "`scaled` kept its ability constraint after specialization (${after.type}). " +
                "Left on the signature, lowering threads a dictionary the body no longer " +
                "reads.",
        )
        assertTrue(after.value is TypedLambda, "the binding stopped being a lambda")
    }

    /**
     * The capture is NOT the trigger — a local step generalizes either way — so
     * the non-capturing spelling must be specialized identically. If these ever
     * diverge, the pass has become sensitive to something it should not read.
     */
    @Test
    fun `a non-capturing local lambda is specialized the same way`() {
        val src = """
            $PRELUDE
            pub let run = { n } -> do
              let scaled = { xs } -> iter::map { it = xs, f = { x } -> x * 3 }
              iter::fold {
                it = scaled { xs = iter::range { start = 0, end = n } },
                init = 0, f = { acc, x } -> acc + x,
              }
        """
        val fn = letIn(specialize(src), "run", "scaled").type as MezzeType.FunctionType
        assertEquals(emptyList<Any>(), collectTypeVarsInOrder(fn, sortFields = false), "$fn")
        assertEquals(emptyList<Any>(), fn.abilityConstraints, "$fn")
    }

    /**
     * THE CONTROL THAT MAKES THE PASS SAFE TO RUN BY DEFAULT: a local lambda
     * used at TWO types keeps its polymorphism.
     *
     * The pass may only remove polymorphism nothing was using. Pinning this one
     * to its first use would make the second run the first's impl — the same
     * unsoundness the enclosing-signature constraint leak used to cause, which
     * is why that removal is scoped the way it is.
     */
    @Test
    fun `a local lambda used at two types is left polymorphic`() {
        val src = """
            $PRELUDE
            pub let run = { n } -> do
              let pair = { x } -> seq::size { xs = [x, x] }
              let a = pair { x = 1 }
              let b = pair { x = "s" }
              a + b + n
        """
        val before = letIn(checked(src).typedModule!!.decls, "run", "pair")
        val after = letIn(specialize(src), "run", "pair")
        // The variables, not the object: `rewrite` rebuilds every node via
        // `mapChildren` whether or not it changes anything, so the two types are
        // never the same instance even when the pass left the binding alone.
        val beforeVars = collectTypeVarsInOrder(before.type, sortFields = false).map { it.name }
        val afterVars = collectTypeVarsInOrder(after.type, sortFields = false).map { it.name }
        assertEquals(
            beforeVars, afterVars,
            "a local lambda used at two different types must keep its polymorphism; " +
                "the pass took ${before.type} to ${after.type}",
        )
        assertTrue(
            afterVars.isNotEmpty(),
            "`pair` is used at Int and at Str, so it must stay polymorphic; the pass " +
                "pinned it to ${after.type}. Pinned to one use, the other would run that " +
                "use's impl.",
        )
    }
}
