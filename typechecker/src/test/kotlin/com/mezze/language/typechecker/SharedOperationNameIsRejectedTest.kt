package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Two abilities in one scope may not declare the same operation name.
 *
 * `ability Weigh { rank }` beside `ability Price { rank }` made `x.rank {}`
 * ambiguous wherever both applied, and made a generic `with Weigh 'a, Price 'a`
 * body depend on which dictionary a method NAME resolved to. That was patched at
 * each dispatch site in turn (BUG 9, the same-op ambiguity check). The rule
 * removes it at the source: an operation name means one ability in a scope, so
 * the second declaration is the error, and dispatch never meets the ambiguity.
 *
 * The prelude is in every scope, so an ability may not reuse `size` (Sized),
 * `add` (NumAdd), `eq` (Eq) and the rest either.
 */
class SharedOperationNameIsRejectedTest {

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun check(src: String): TypeCheckError? = try {
        TypeChecker().typeCheck(MezzeParser().parseParsedModule(src.trimIndent(), "ops.mz"), Paths.get("."), enablePrelude = true)
        null
    } catch (e: TypeCheckError) {
        e
    }

    @Test
    fun `two abilities declaring one operation name are rejected, naming both`() {
        val err = check(
            """
            ability Weigh for 'a where {
              rank: { it: 'a } -> Int
            }
            ability Price for 'a where {
              rank: { it: 'a } -> Int
            }
            """
        ) ?: fail("two abilities both declaring `rank` were accepted")
        assertEquals(ErrorCode.V0701_DUPLICATE_DECLARATION, err.code)
        assertTrue(err.message.contains("Weigh") && err.message.contains("Price") && err.message.contains("rank"), err.message)
    }

    @Test
    fun `an operation name the prelude's abilities already use is rejected`() {
        val err = check(
            """
            ability Container for 'c where {
              size: { it: 'c } -> Int
            }
            """
        ) ?: fail("`size` beside the prelude's Sized was accepted")
        assertEquals(ErrorCode.V0701_DUPLICATE_DECLARATION, err.code)
        assertTrue(err.message.contains("Sized"), err.message)
    }

    @Test
    fun `abilities with distinct operation names are fine`() {
        assertNull(check(
            """
            ability Weigh for 'a where {
              weight: { it: 'a } -> Int
            }
            ability Price for 'a where {
              price: { it: 'a } -> Int
            }
            """
        )?.message)
    }
}
