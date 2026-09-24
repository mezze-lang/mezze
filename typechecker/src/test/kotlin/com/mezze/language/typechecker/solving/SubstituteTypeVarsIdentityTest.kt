package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Two type substitutions, and the difference between them is the point.
 *
 * [substituteTypeParamsByName] replaces by NAME. That is right for its callers —
 * their keys are DECLARED PARAMETER NAMES read off an ability declaration and
 * applied to a freshly converted annotation, where one written name is one
 * variable and the name is the only handle there is.
 *
 * It is wrong anywhere the caller already HAS the variables, because a name is
 * not one. `TypeVar.fresh()` mints `'a`, `'b`, … per level, so two distinct
 * variables spelled alike are a naming convention away rather than a language
 * impossibility — and the name-keyed version rewrites BOTH.
 *
 * Not hypothetical: a probe over both suites caught it twice at
 * `TypedAstBuilder.buildFunctionCall`, on `out` (the `having 'out` associated
 * type), with two distinct variables sharing the name while `out` was in the
 * substitution. That call site is keyed on identity now.
 */
class SubstituteTypeVarsIdentityTest {

    /** Two DISTINCT variables, deliberately spelled the same. */
    private fun twoVarsNamedOut(): Pair<TypeVar, TypeVar> =
        TypeVar.freshWithName("out") to TypeVar.freshWithName("out")

    @Test
    fun `identity-keyed substitution rewrites only the variable it was given`() {
        val (first, second) = twoVarsNamedOut()
        assertNotEquals(first.id, second.id, "the two variables must be distinct to test anything")

        val t = MezzeType.FunctionType(MezzeType.VarType(first), MezzeType.VarType(second))
        val result = substituteTypeVars(t, mapOf(first to MezzeType.IntType)) as MezzeType.FunctionType

        assertEquals(MezzeType.IntType, result.paramType, "the variable in the map must be replaced")
        assertEquals(
            MezzeType.VarType(second), result.returnType,
            "the OTHER variable shares only a spelling and must be left alone",
        )
    }

    @Test
    fun `the name-keyed substitution rewrites both — which is why the other one exists`() {
        // Pinning the hazard, not endorsing it. If this ever stops aliasing, the
        // two functions have converged and the identity-keyed one is redundant;
        // that is a result worth noticing rather than a failure to paper over.
        val (first, second) = twoVarsNamedOut()
        val t = MezzeType.FunctionType(MezzeType.VarType(first), MezzeType.VarType(second))

        val result = substituteTypeParamsByName(t, mapOf("out" to MezzeType.IntType)) as MezzeType.FunctionType

        assertEquals(MezzeType.IntType, result.paramType)
        assertEquals(
            MezzeType.IntType, result.returnType,
            "by NAME, both variables spelled `out` are rewritten — this is the aliasing the " +
                "identity-keyed version avoids",
        )
    }

    @Test
    fun `identity-keyed substitution descends into every position`() {
        // The walk must not be shallower than the name-keyed one it replaces: a
        // variable nested in a record field, a type application argument and a
        // variant payload all have to move.
        val v = TypeVar.freshWithName("a")
        val nested = MezzeType.RecordType(
            mapOf(
                "f" to MezzeType.VarType(v),
                "g" to MezzeType.TypeApplication("Seq", listOf(MezzeType.VarType(v))),
                "h" to MezzeType.VariantType(mapOf("C" to MezzeType.RecordType(mapOf("val" to MezzeType.VarType(v))))),
            )
        )

        val result = substituteTypeVars(nested, mapOf(v to MezzeType.StringType)) as MezzeType.RecordType

        assertEquals(MezzeType.StringType, result.fields["f"], "a record field")
        assertEquals(
            listOf(MezzeType.StringType),
            (result.fields["g"] as MezzeType.TypeApplication).args,
            "a type-application argument",
        )
        val payload = (result.fields["h"] as MezzeType.VariantType).constructors["C"] as MezzeType.RecordType
        assertEquals(MezzeType.StringType, payload.fields["val"], "a variant constructor's payload")
    }

    @Test
    fun `an empty substitution returns the type untouched`() {
        val v = TypeVar.freshWithName("a")
        val t = MezzeType.FunctionType(MezzeType.VarType(v), MezzeType.IntType)
        assertEquals(t, substituteTypeVars(t, emptyMap()))
    }
}
