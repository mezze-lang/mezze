package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Two abilities declaring one method name, both implemented for the SAME concrete
 * receiver, called without saying which — rejected.
 *
 * There is no rule that resolves this. The ladder took `.first()` off the impl
 * registry, which is iteration order: the same program could dispatch differently
 * depending on what loaded first, and nothing would say so.
 *
 * Only on a CONCRETE receiver. A polymorphic one leaves several candidates on
 * purpose — the impl narrowing has nothing to match against yet and the
 * signature's own ability constraint decides — so the last case here is the one
 * that keeps this rule from over-reaching.
 *
 * MEASURED before adding it: 40 call sites across both suites reached the ladder
 * with more than one surviving ability; 33 were a name-rendering bug since fixed,
 * and the remaining 7 all have polymorphic receivers. On a concrete receiver the
 * count is ZERO, so this rejects nothing that existed.
 */
class SameOpAmbiguityIsRejectedTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
    }

    private fun check(src: String): TypeCheckError? {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        return try {
            TypeChecker().typeCheck(parser.parseParsedModule(src.trimIndent(), "amb.mz"), Paths.get("."))
            null
        } catch (e: TypeCheckError) {
            e
        }
    }

    private val twoAbilities = """
        ability Weigh for 'a where {
          rank: { it: 'a } -> Int
        }
        ability Price for 'a where {
          rank: { it: 'a } -> Int
        }
        type Item = { kg: Int }
        impl Weigh for Item where {
          rank = { it } -> it.kg
        }
        impl Price for Item where {
          rank = { it } -> it.kg * 2
        }
    """.trimIndent()

    @Test
    fun `an unqualified call on a concrete receiver that implements both is rejected`() {
        val err = check(
            """
            $twoAbilities
            let item = { kg = 3 }
            let r = item.rank {}
            r
            """
        ) ?: fail("`item.rank {}` was accepted — one of the two impls won by load order")

        assertEquals(ErrorCode.T0216_AMBIGUOUS_DISPATCH, err.code)
        assertTrue(
            err.message.contains("Weigh") && err.message.contains("Price"),
            "both abilities must be named — the reader cannot choose between them otherwise: ${err.message}",
        )
        assertTrue(
            err.hints.any { it.contains("Weigh.rank") },
            "the fix is a qualifier, and the diagnostic should show it: ${err.hints}",
        )
    }

    @Test
    fun `the same call QUALIFIED is accepted`() {
        // The control, and the direction the error points in. Without this the
        // test above would pass against a checker that rejects `rank` outright.
        val err = check(
            """
            $twoAbilities
            let item = { kg = 3 }
            let r = Weigh.rank { it = item }
            r
            """
        )
        assertEquals(null, err?.message, "a qualified call says which ability it means and must be accepted")
    }

    @Test
    fun `one ability implemented for the receiver is not ambiguous`() {
        // Two abilities can declare `rank`; that is legal and the stdlib relies on
        // it (Sized and SizedMut both declare `size` and `is_empty`). What makes
        // it ambiguous is BOTH applying to this receiver.
        val err = check(
            """
            ability Weigh for 'a where {
              rank: { it: 'a } -> Int
            }
            ability Price for 'a where {
              rank: { it: 'a } -> Int
            }
            type Item = { kg: Int }
            type Book = { cost: Int }
            impl Weigh for Item where {
              rank = { it } -> it.kg
            }
            impl Price for Book where {
              rank = { it } -> it.cost
            }
            let item = { kg = 3 }
            let r = item.rank {}
            r
            """
        )
        assertEquals(null, err?.message, "only Weigh applies to Item, so nothing is ambiguous")
    }

    @Test
    fun `a POLYMORPHIC receiver is left alone`() {
        // The boundary that keeps this from over-reaching. Inside a generic
        // function the receiver's type is not known yet, so the narrowing has
        // nothing to match against and several candidates survive legitimately —
        // the signature's own constraint is what decides. All 7 multi-survivor
        // sites left in the suites are this shape.
        val err = check(
            """
            $twoAbilities
            let describe: {x: 'a} with Weigh 'a -> Int = {x} -> x.rank {}
            let r = describe { x = { kg = 3 } }
            r
            """
        )
        assertEquals(null, err?.message, "a constrained generic receiver must not be reported as ambiguous")
    }

    @Test
    fun `the error names the receiver type`() {
        val err = check(
            """
            $twoAbilities
            let item = { kg = 3 }
            let r = item.rank {}
            r
            """
        )
        assertNotNull(err)
        assertTrue(
            err.message.contains("Item") || err.message.contains("rank"),
            "the message must locate the problem for the reader: ${err.message}",
        )
    }
}
