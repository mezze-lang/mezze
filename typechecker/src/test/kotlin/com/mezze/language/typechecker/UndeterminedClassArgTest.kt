package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * An ability class argument that the RECEIVER does not determine.
 *
 *     ability Into2 'b for 'a where { conv2: {} -> 'b }
 *     impl Into2 Str  for Int where { conv2 = { it } -> "x" }
 *     impl Into2 Bool for Int where { conv2 = { it } -> True }
 *
 *     let f = {} -> do
 *       let s = 5.conv2 {}        # converted into WHAT?
 *       0
 *
 * The receiver is `5`, so `'a` is `Int`; `'b` — the type converted INTO, which
 * is what picks the impl — is named by nothing. T0215 already refuses exactly
 * this for a receiver-LESS operation ("the type it produces is what chooses the
 * implementation"), and the same question one class-argument position over is
 * never asked. The first test is that gap; the rest are the shapes that DO
 * determine it and must keep compiling.
 *
 * This is the non-codec face of [UndeterminedCodecTargetTest] — read that for
 * the codec instance, which has a further obstacle of its own.
 *
 * Asking the question needs three changes. All three are written and were run;
 * none is landed, because of the fourth thing below.
 *
 *  1. RECORDING. A receiver-taking ability op carries only its RECEIVER to emit
 *     (`MethodCallInfo.typeArgs`, the monomorphizer's thread), so no dictionary
 *     demand is recorded for it at all. Carrying the whole class-arg tuple
 *     beside it, populated from `abilityTypeVars` in `ExprMethodConstraints`,
 *     puts the demand in front of the judge.
 *  2. THE RECEIVER'S POSITION. `requirementReachesHiddenVar` reads
 *     `typeArgs.first()` as the receiver. That is wrong whenever `for` names a
 *     later parameter, and the tuple may not hold the receiver at all — a `with`
 *     clause on an operation omits it, so `with Decode QueryC 'a` carries only
 *     the non-receiver slots, and reading slot 2 there finds nothing and gives
 *     up before asking anything.
 *  3. NOT ASKING THE RECEIVER QUESTION of a method call, which holds a receiver
 *     value by construction. It answers wrongly: `dispatchesWithoutAValue` reads
 *     "no operation declares `it`" as "can dispatch with no value", and an
 *     ability whose receiver is implicit in `for` declares none. The last test
 *     is refused without this.
 *
 * AND WITH ALL THREE IN PLACE THE FIRST TEST STILL PASSES, for a reason that has
 * nothing to do with the judge: the obligation LEAKS ONTO `f`'s own signature
 * (`{} with Into2 'b Int -> Int`), so `'b` becomes one of the enclosing
 * function's variables and the judge then correctly declines — a caller is meant
 * to supply it. Measured: the demand reaches the judge with the variable listed
 * as VISIBLE. The refusal would have to come at `f {}` instead. That leak is a
 * known shape, pinned by `generalizeDoBlockBinding`'s third guard, and it is the
 * prerequisite here.
 */
class UndeterminedClassArgTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    /**
     * TWO impls are load-bearing. With a single candidate the solver GUESSES it,
     * so `'b` comes out determined and no demand is recorded at all — measured,
     * by watching nothing reach the judge. A one-impl program cannot exercise
     * this question, which is worth knowing before writing a test for it.
     */
    private val head = """
        ability Into2 'b for 'a where {
          conv2: {} -> 'b
        }
        impl Into2 Str for Int where {
          conv2 = { it } -> "x"
        }
        impl Into2 Bool for Int where {
          conv2 = { it } -> True
        }
    """.trimIndent()

    /** THE GAP. When this starts failing the question is being asked — invert it. */
    @Test fun `a class argument nothing determines is accepted today`() {
        assertNull(
            err("""
                $head
                let f = {} -> do
                  let s = 5.conv2 {}
                  0
                f {}
            """),
            "when this is refused, read the class doc and invert this test",
        )
    }

    @Test fun `an annotated binding determines it`() {
        assertNull(
            err("""
                $head
                let f = {} -> do
                  let s: Str = 5.conv2 {}
                  0
                f {}
            """),
        )
    }

    @Test fun `a consumer at a known type determines it`() {
        assertNull(
            err("""
                $head
                let want = { s: Str } -> s
                let f = {} -> want { s = 5.conv2 {} }
                f {}
            """),
        )
    }

    @Test fun `an enclosing function that declares the constraint carries it`() {
        assertNull(
            err("""
                $head
                let f: { n: Int } with Into2 'b Int -> 'b = { n } -> n.conv2 {}
                let g = {} -> do
                  let s: Str = f { n = 5 }
                  0
                g {}
            """),
        )
    }

    /**
     * The receiver's own position is a different question with a different
     * answer, and it must not be asked of a method call. An ability whose
     * receiver is implicit in `for` declares no `it`, which
     * `dispatchesWithoutAValue` reads as "dispatches without a value"; asking
     * that here refuses this program with "'Inner' is needed at `'a`".
     */
    @Test fun `an implicit receiver on a method call is not asked about`() {
        assertNull(
            err("""
                ability Inner for 'a where { inner: {} -> Int }
                impl Inner for Int where { inner = { it } -> it }
                ability Outer for 'a where { outer: {} with Inner 'a -> Int }
                impl Outer for Int where { outer = { it } -> it.inner {} + 10 }
                let test = {} -> 5.outer {}
                test {}
            """),
        )
    }
}
