package com.mezze.language.typechecker.context

import com.mezze.language.ast.AbilityConstraint
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An ability constraint is paired with its fresh variable by IDENTITY.
 *
 * `TypeScheme.instantiate` used to pair them by SPELLING: it built a
 * `Map<String, TypeVar>` from the quantified variables and looked the
 * constraint's `typeParam` up in it. Two quantified variables sharing a base
 * name meant one silently overwrote the other, and the constraint linked to
 * whichever wrote last — the wrong variable, so the wrong impl at the call site,
 * with no diagnostic anywhere.
 *
 * That is not exotic: `TypeVar.fresh()` mints `'a`, `'b`, … per level, so two
 * distinct variables spelled the same are a naming convention away rather than a
 * language impossibility.
 */
class AbilityConstraintIdentityTest {

    /** Two DISTINCT variables, deliberately spelled the same. */
    private fun twoVarsNamedA(): Pair<TypeVar, TypeVar> =
        TypeVar.freshWithName("'a") to TypeVar.freshWithName("'a")

    @Test
    fun `a constraint carrying its variable links to THAT variable, not the last one spelled alike`() {
        val (first, second) = twoVarsNamedA()
        assertNotEquals(first.id, second.id, "the two variables must be distinct to test anything")

        val scheme = TypeScheme(
            quantifiedVars = listOf(first, second),
            constraints = listOf(
                AbilityConstraint(
                    abilityName = "Show",
                    typeParam = "'a",
                    typeVar = first, // the FIRST — a name lookup would find the last
                )
            ),
            type = MezzeType.FunctionType(MezzeType.VarType(first), MezzeType.VarType(second)),
        )

        val result = scheme.instantiate()
        val freshForFirst = (result.substitution[first] as MezzeType.VarType).variable
        val freshForSecond = (result.substitution[second] as MezzeType.VarType).variable
        assertNotEquals(freshForFirst.id, freshForSecond.id)

        val linked = result.freshConstraints.single().typeVar
        assertEquals(
            freshForFirst.id, linked.id,
            "the constraint carries `first`, so it must link to first's fresh variable — " +
                "linking to second's is the name lookup picking whichever was spelled last"
        )
    }

    @Test
    fun `the same scheme with the constraint on the SECOND variable links there`() {
        // The mirror, so the test above cannot pass by always choosing index 0.
        val (first, second) = twoVarsNamedA()
        val scheme = TypeScheme(
            quantifiedVars = listOf(first, second),
            constraints = listOf(
                AbilityConstraint(abilityName = "Show", typeParam = "'a", typeVar = second)
            ),
            type = MezzeType.FunctionType(MezzeType.VarType(first), MezzeType.VarType(second)),
        )
        val result = scheme.instantiate()
        val freshForSecond = (result.substitution[second] as MezzeType.VarType).variable
        assertEquals(freshForSecond.id, result.freshConstraints.single().typeVar.id)
    }

    @Test
    fun `an ordinary constraint on a uniquely named variable still links`() {
        // The common case must keep working — identity resolution is not a
        // special path for the ambiguous case, it is the path.
        val a = TypeVar.freshWithName("'a")
        val b = TypeVar.freshWithName("'b")
        val scheme = TypeScheme(
            quantifiedVars = listOf(a, b),
            constraints = listOf(
                AbilityConstraint(abilityName = "Show", typeParam = "'b", typeVar = b)
            ),
            type = MezzeType.FunctionType(MezzeType.VarType(a), MezzeType.VarType(b)),
        )
        val result = scheme.instantiate()
        val freshForB = (result.substitution[b] as MezzeType.VarType).variable
        assertEquals(freshForB.id, result.freshConstraints.single().typeVar.id)
    }

    @Test
    fun `a constraint WITHOUT its variable, on an ambiguous spelling, fails loudly`() {
        // The fallback path can still be mislinked, so it refuses instead. This is
        // the guard that makes "carries its variable" the only safe way in.
        val (first, second) = twoVarsNamedA()
        val scheme = TypeScheme(
            quantifiedVars = listOf(first, second),
            constraints = listOf(
                AbilityConstraint(abilityName = "Show", typeParam = "'a") // no typeVar
            ),
            type = MezzeType.FunctionType(MezzeType.VarType(first), MezzeType.VarType(second)),
        )
        val thrown = runCatching { scheme.instantiate() }.exceptionOrNull()
        assertTrue(
            thrown is IllegalStateException,
            "an ambiguous spelling with no carried variable must refuse, not guess; got $thrown"
        )
        assertTrue(
            thrown!!.message!!.contains("typeVar"),
            "the error should name the field whose absence caused it: ${thrown.message}"
        )
    }
}
