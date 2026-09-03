package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.solving.Substitution
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UnificationPropertyTest {

    private var nextId = 100
    private fun freshVar(name: String = "u") = TypeVar(name, 0, nextId++)

    @Test
    fun `unifying identical concrete types yields empty substitution`() {
        val concreteTypes = listOf(
            MezzeType.IntType, MezzeType.StringType, MezzeType.BoolType,
            MezzeType.FloatType, MezzeType.CharType,
        )

        for (type in concreteTypes) {
            val result = unify(type, type)
            assertIs<UnificationResult.Success>(result)
            assertEquals(Substitution.empty, result.substitution)
        }
    }

    @Test
    fun `unifying variable with concrete type binds the variable`() {
        val tv = freshVar()
        val concreteTypes = listOf(
            MezzeType.IntType, MezzeType.StringType, MezzeType.BoolType,
        )

        for (concrete in concreteTypes) {
            clearUnificationCache()
            val result = unify(MezzeType.VarType(tv), concrete)
            assertIs<UnificationResult.Success>(result)
            assertEquals(concrete, result.substitution.apply(MezzeType.VarType(tv)))
        }
    }

    @Test
    fun `unification is symmetric for variables`() {
        val tv = freshVar()

        clearUnificationCache()
        val lr = unify(MezzeType.VarType(tv), MezzeType.IntType)
        assertIs<UnificationResult.Success>(lr)

        clearUnificationCache()
        val rl = unify(MezzeType.IntType, MezzeType.VarType(tv))
        assertIs<UnificationResult.Success>(rl)

        assertEquals(
            lr.substitution.apply(MezzeType.VarType(tv)),
            rl.substitution.apply(MezzeType.VarType(tv))
        )
    }

    @Test
    fun `unifying two distinct variables produces a binding`() {
        val a = freshVar("a")
        val b = freshVar("b")

        val result = unify(MezzeType.VarType(a), MezzeType.VarType(b))
        assertIs<UnificationResult.Success>(result)

        val appliedA = result.substitution.apply(MezzeType.VarType(a))
        val appliedB = result.substitution.apply(MezzeType.VarType(b))
        assertEquals(appliedA, appliedB)
    }

    @Test
    fun `unifying ListType distributes to element type`() {
        val tv = freshVar()

        val result = unify(
            MezzeType.ListType(MezzeType.VarType(tv)),
            MezzeType.ListType(MezzeType.IntType)
        )
        assertIs<UnificationResult.Success>(result)
        assertEquals(MezzeType.IntType, result.substitution.apply(MezzeType.VarType(tv)))
    }

    @Test
    fun `unifying RecordType unifies corresponding fields`() {
        val tv = freshVar()

        val result = unify(
            MezzeType.RecordType(linkedMapOf("x" to MezzeType.VarType(tv)), rowVar = null),
            MezzeType.RecordType(linkedMapOf("x" to MezzeType.StringType), rowVar = null)
        )
        assertIs<UnificationResult.Success>(result)
        assertEquals(MezzeType.StringType, result.substitution.apply(MezzeType.VarType(tv)))
    }

    @Test
    fun `unifying incompatible concrete types fails`() {
        val result = unify(MezzeType.IntType, MezzeType.StringType)
        assertIs<UnificationResult.Failure>(result)
    }

    @Test
    fun `unifying incompatible structures fails`() {
        val result = unify(
            MezzeType.ListType(MezzeType.IntType),
            MezzeType.SetType(MezzeType.IntType)
        )
        assertIs<UnificationResult.Failure>(result)
    }

    @Test
    fun `post-unification substitution equates both sides`() {
        val cases = listOf(
            MezzeType.VarType(freshVar()) to MezzeType.IntType,
            MezzeType.ListType(MezzeType.VarType(freshVar())) to MezzeType.ListType(MezzeType.StringType),
            MezzeType.VarType(freshVar()) to MezzeType.VarType(freshVar()),
        )

        for ((left, right) in cases) {
            clearUnificationCache()
            val result = unify(left, right)
            if (result is UnificationResult.Success) {
                val appliedLeft = result.substitution.apply(left)
                val appliedRight = result.substitution.apply(right)
                assertEquals(appliedLeft, appliedRight,
                    "Post-unify: apply(left) != apply(right) for $left ~ $right")
            }
        }
    }

    @Test
    fun `MapType unifies key and value types independently`() {
        val kv = freshVar("k")
        val vv = freshVar("v")

        val result = unify(
            MezzeType.MapType(MezzeType.VarType(kv), MezzeType.VarType(vv)),
            MezzeType.MapType(MezzeType.StringType, MezzeType.IntType)
        )
        assertIs<UnificationResult.Success>(result)
        assertEquals(MezzeType.StringType, result.substitution.apply(MezzeType.VarType(kv)))
        assertEquals(MezzeType.IntType, result.substitution.apply(MezzeType.VarType(vv)))
    }
}
