package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A KNOWN associated OUTPUT determines the operands that produced it.
 *
 * The subject is the mandelbrot escape loop written with no annotations, which is
 * how `mezze-benchmark` has it and which measured 2 833 ns/px against Scala's 18.1:
 *
 *     let zr2 = zr * zr                    NumMul 's 's   having out = 'k
 *     let zi2 = zi * zi                    NumMul 'v 'v   having out = 'l
 *     if zr2 + zi2 > 4.0 then i            NumAdd 'l 'k   having out = Float
 *
 * The comparison against `4.0` pins the third output to Float. Exactly one `NumAdd`
 * impl takes two Floats and yields one, so `'l` and `'k` are Float; the multiplies
 * then have a known output and a REPEATED operand variable, where exactly one impl
 * can agree with itself, so `'s` and `'v` are Float too. Nine type parameters
 * become two, and the two that remain (`cr`, `ci`) are real parameters that a
 * caller picks.
 *
 * Before this, all nine were generalised — five of them appearing nowhere in
 * `{cr,ci,zr,zi,i} -> Int`, so no call site could ever instantiate them and no
 * dictionary for their obligations could ever arrive. Every operation in the loop
 * went through evidence-passing anyway.
 *
 * THE OTHER HALF OF THE RULE is what it must NOT do, and those tests live where
 * the shapes do: `HavingOutGeneralizationSoundnessTest` (`{x} -> x + 0` must still
 * accept a Float), `AbilityAssociatedTypeTest` ("kept when nothing pins the args"),
 * `DoExpressionConstraintTest` (a chain of literal-operand constraints stays
 * polymorphic), `ShorthandFieldInferenceTest` and
 * `ListFoldElementKindFusionTest` (a fold's element type belongs to the caller,
 * which is why it folds a Nat list and a record list). This file carries the
 * positive side and one negative control for the guard itself.
 */
class AbilityOutputImprovementTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun setUp() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun typeOf(src: String, name: String): MezzeType? =
        tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            .getVariableType(name)

    private fun typeParamsOf(src: String, name: String): List<String> {
        val program = tc.typeCheck(
            parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
        val decl = program.typedModule!!.decls
            .filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            .firstOrNull { it.name == name }
        assertNotNull(decl, "no decl named `$name`")
        return decl!!.typeParams.map { it.name }
    }

    private val ESCAPE = """
        let limit = 50
        pub let escape = { cr, ci, zr, zi, i } ->
          if i >= limit then i
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
    """.trimIndent()

    @Test fun `an output pinned by a comparison determines the operands that produced it`() {
        val params = typeParamsOf(ESCAPE, "escape")
        // `cr` and `ci` may legitimately stay open: `zr2 - zi2 + cr` can take an Int
        // `cr` through `NumAdd Int for Float`, so the caller really does choose it.
        // Everything else is an intermediate and must be gone.
        assertTrue(params.size <= 2,
            "escape should keep at most its two genuinely-open parameters, got ${params.size}: $params")
    }

    @Test fun `the escape loop still computes the same answer`() {
        val t = typeOf(
            ESCAPE + "\npub let r = escape { cr = 0.5, ci = 0.5, zr = 0.0, zi = 0.0, i = 0 }\n",
            "r")
        assertTrue(t is MezzeType.IntType, "the iteration count is an Int; got $t")
    }

    @Test fun `an intermediate whose operands are parameters is NOT defaulted`() {
        // THE GUARD. Same shape as the loop above — one concrete operand, an open
        // receiver, an open output — but here the chain bottoms out in `a` and `b`,
        // which the caller picks. `inner * 2` must not decide that they are Ints.
        //
        // This is the case that made the first version of the rule unsound: it is
        // structurally identical to `pixel_poly`'s `px * 3.0 / span - 2.0` and
        // differs only in whether the program also calls the function with concrete
        // arguments — which is not a question the typechecker is asked. So the
        // default needs a CONCRETE OUTPUT as its anchor, and a literal sitting in an
        // operand position is not one: `NumMul Int for Float` is a real impl.
        val widen = """
            pub let widen = { a, b } -> do
              let inner = do
                let temp = a + b
                temp * 2
              inner + 10
        """.trimIndent()
        assertTrue(typeParamsOf(widen, "widen").isNotEmpty(),
            "widen must stay polymorphic — `inner * 2`'s literal operand must not " +
                "decide that `a` and `b` are Ints")
        val t = typeOf(widen + "\npub let f = widen { a = 1.5, b = 2.5 }\n", "f")
        assertTrue(t is MezzeType.FloatType,
            "widen applied to Floats must be Float; got $t")
    }
}
