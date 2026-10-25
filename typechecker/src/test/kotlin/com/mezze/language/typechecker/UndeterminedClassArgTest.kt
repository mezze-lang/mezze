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
 * REFUSED as of this commit, and the rule is a property of the SOLVED SIGNATURE,
 * asked in the per-binding step: an ability constraint on a type variable that
 * appears in neither the parameters nor the result is one no caller can ever
 * discharge. `f` infers `{} with Into2 'b Int -> Int`.
 *
 * Four refinements, each pinned by a test here or named by the case that forced
 * it, and each measured against the full suite:
 *
 *  1. PARAMETERS, RESULT and EFFECT ROW only — never the constraint list itself,
 *     which contains the very occurrence that makes a variable phantom. An
 *     effect row counts: a caller handling `<Exception having 'error = 'e>`
 *     determines `'e`.
 *  2. The ASSOCIATED-TYPE CLOSURE. `having 'out` is a functional dependency, so
 *     determining a constraint's class arguments determines its associated
 *     types, and those count in turn — closed to a fixpoint. Without it,
 *     `{ x, y, z } -> do let sum = x + y; let product = sum * z; …` is refused
 *     although a caller determines everything (ten such functions).
 *  3. RESOLVED through the binding's own substitution, because the stored tuple
 *     keeps the variable it started from.
 *  4. Only a class argument the operation PRODUCES, which is the doctrine T0215
 *     already states for a receiver-less op. `conv2: {} -> 'b` produces `'b`;
 *     `add: { it: 'l, r: 'r } -> 'out` CONSUMES `'r`. A consumed argument left
 *     open means the value was never produced and Mezze has no bottom type to
 *     say so — `let f = { y } -> x + y` never called, or `sum + will_fail {}`
 *     where `will_fail` throws. Both are legitimate; refusing them was wrong.
 *
 * And one PREREQUISITE, fixed in the same commit: a recursive binding's
 * self-reference is pre-registered as a bare placeholder that nothing relates to
 * the binding's own type, so the self-call's result never pins and the leak
 * lands on the signature. `length` inferred
 * `{ xs: [Int] } with NumAdd 'a Int having 'out = Int -> Int`, junk visible in
 * its own hover. See `EmptySeqsInRecordTest`, where tying the two also caught a
 * program whose match arms disagree.
 *
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

    @Test fun `a class argument nothing determines is refused`() {
        val e = err("""
            $head
            let f = {} -> do
              let s = 5.conv2 {}
              0
            f {}
        """)
        assertNotNull(e, "nothing says what `5.conv2 {}` converts into")
        assertTrue(
            e!!.contains("appears in neither its parameters nor its result"),
            "should name the position, not ask for an annotation; got: $e",
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
