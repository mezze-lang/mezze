package com.mezze.language.typechecker

import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.ParsedNamedType
import com.mezze.language.parsedast.ParsedSeqType
import com.mezze.language.parsedast.ParsedTypeApp
import com.mezze.language.typechecker.util.AbilityImplFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The name an `impl` registers its target under — the key every by-produced-type
 * dispatch looks up.
 *
 * Asserted directly because the consumer that would catch a mistake is blocked
 * elsewhere: a receiver-less operation on a sequence cannot reach its dictionary
 * yet (S-3), so breaking this name changes no end-to-end behaviour today and would
 * sit undetected until S-3 lands and immediately failed. `typeNameOf` is a pure
 * function of an annotation, so there is no reason to test it at a distance.
 *
 * The rule: a GENERIC impl registers under the bare constructor, because it applies
 * at every instantiation. Only a concrete argument is mangled in.
 */
class ImplTargetNameTest {

    private val l = Loc("<test>", 1, 0, 1, 0)
    private fun name(n: String) = ParsedNamedType(n, l)

    @Test
    fun `a generic sequence impl is keyed by the bare constructor`() {
        // `impl Eq for ['a]` — registered as `Seq_a` before the fix, keyed by its own
        // type PARAMETER's spelling, which no lookup for a `[Int]` instance could
        // ever match. Every `impl X for ['a]` in the stdlib carried it.
        assertEquals("Seq", AbilityImplFactory.typeNameOf(ParsedSeqType(name("'a"), l)))
    }

    @Test
    fun `a concrete sequence impl keeps its element in the key`() {
        // `impl Eq for [Int]` must stay distinguishable from the generic one, or a
        // specific instance and a blanket one would collide.
        assertEquals("Seq_Int", AbilityImplFactory.typeNameOf(ParsedSeqType(name("Int"), l)))
    }

    @Test
    fun `a nested concrete sequence mangles through`() {
        assertEquals(
            "Seq_Seq_Int",
            AbilityImplFactory.typeNameOf(ParsedSeqType(ParsedSeqType(name("Int"), l), l)),
        )
    }

    /** The arm the sequence arm was out of step with — same rule, already correct. */
    @Test
    fun `a generic type application is keyed by the bare constructor`() {
        assertEquals(
            "Option",
            AbilityImplFactory.typeNameOf(ParsedTypeApp("Option", listOf(name("'a")), l)),
        )
    }

    @Test
    fun `a concrete type application keeps its arguments in the key`() {
        assertEquals(
            "Result_Int_Str",
            AbilityImplFactory.typeNameOf(ParsedTypeApp("Result", listOf(name("Int"), name("Str")), l)),
        )
    }
}
