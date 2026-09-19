package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.occursCheck
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class SubstitutionPropertyTest {

    private var nextId = 0
    private fun freshVar(name: String = "t") = TypeVar(name, 0, nextId++)

    private fun randomLeafType(seed: Int): MezzeType = when (seed % 5) {
        0 -> MezzeType.IntType
        1 -> MezzeType.StringType
        2 -> MezzeType.BoolType
        3 -> MezzeType.FloatType
        else -> MezzeType.IntType
    }

    private fun randomType(depth: Int, seed: Int): MezzeType {
        if (depth <= 0) return randomLeafType(seed)
        return when (seed % 6) {
            0 -> randomLeafType(seed / 6)
            1 -> MezzeType.TypeApplication("List", listOf(randomType(depth - 1, seed / 6)))
            2 -> MezzeType.RecordType(
                fields = linkedMapOf("x" to randomType(depth - 1, seed / 6)),
                rowVar = null
            )
            3 -> MezzeType.TypeApplication("Set", listOf(randomType(depth - 1, seed / 6)))
            4 -> MezzeType.TypeApplication(
                "Map",
                listOf(
                    randomType(depth - 1, seed / 6),
                    randomType(depth - 1, (seed / 6) + 1),
                ),
            )
            else -> MezzeType.VarType(freshVar())
        }
    }

    @Test
    fun `empty substitution is identity`() {
        val empty = Substitution.empty
        for (seed in 0..20) {
            val type = randomType(2, seed)
            assertSame(type, empty.apply(type))
        }
    }

    @RepeatedTest(20)
    fun `single mapping replaces exactly the target variable`() {
        val tv = freshVar()
        val target = randomLeafType(nextId)
        val sub = Substitution.single(tv, target)

        assertEquals(target, sub.apply(MezzeType.VarType(tv)))

        val other = freshVar("other")
        val otherVar = MezzeType.VarType(other)
        assertEquals(otherVar, sub.apply(otherVar))
    }

    @RepeatedTest(20)
    fun `apply preserves concrete types`() {
        val tv = freshVar()
        val sub = Substitution.single(tv, MezzeType.IntType)

        assertSame(MezzeType.IntType, sub.apply(MezzeType.IntType))
        assertSame(MezzeType.StringType, sub.apply(MezzeType.StringType))
        assertSame(MezzeType.BoolType, sub.apply(MezzeType.BoolType))
    }

    @RepeatedTest(10)
    fun `apply distributes over ListType`() {
        val tv = freshVar()
        val sub = Substitution.single(tv, MezzeType.IntType)

        val listOfVar = MezzeType.TypeApplication("List", listOf(MezzeType.VarType(tv)))
        val result = sub.apply(listOfVar)
        assertEquals(MezzeType.TypeApplication("List", listOf(MezzeType.IntType)), result)
    }

    @RepeatedTest(10)
    fun `apply distributes over RecordType fields`() {
        val tv = freshVar()
        val sub = Substitution.single(tv, MezzeType.StringType)

        val record = MezzeType.RecordType(
            fields = linkedMapOf("a" to MezzeType.VarType(tv), "b" to MezzeType.IntType),
            rowVar = null
        )
        val result = sub.apply(record)
        val expected = MezzeType.RecordType(
            fields = linkedMapOf("a" to MezzeType.StringType, "b" to MezzeType.IntType),
            rowVar = null
        )
        assertEquals(expected, result)
    }

    @Test
    fun `compose right identity`() {
        val tv = freshVar()
        val sub = Substitution.single(tv, MezzeType.IntType)
        val composed = sub.compose(Substitution.empty)

        for (seed in 0..10) {
            val type = randomType(2, seed)
            assertEquals(sub.apply(type), composed.apply(type))
        }
    }

    @Test
    fun `compose left identity`() {
        val tv = freshVar()
        val sub = Substitution.single(tv, MezzeType.IntType)
        val composed = Substitution.empty.compose(sub)

        for (seed in 0..10) {
            val type = randomType(2, seed)
            assertEquals(sub.apply(type), composed.apply(type))
        }
    }

    @Test
    fun `transitive chains resolve through apply`() {
        val a = freshVar("a")
        val b = freshVar("b")
        val sub = Substitution(mapOf(
            a to MezzeType.VarType(b),
            b to MezzeType.IntType,
        ))

        assertEquals(MezzeType.IntType, sub.apply(MezzeType.VarType(a)))
        assertEquals(MezzeType.IntType, sub.apply(MezzeType.VarType(b)))
    }

    @Test
    fun `cyclic mapping does not infinite loop`() {
        val a = freshVar("a")
        val b = freshVar("b")
        val sub = Substitution(mapOf(
            a to MezzeType.VarType(b),
            b to MezzeType.VarType(a),
        ))

        val result = sub.apply(MezzeType.VarType(a))
        // Should terminate — visitedVars prevents infinite recursion
        assert(result is MezzeType.VarType)
    }

    @Test
    fun `no mapping produces occurs-check violation`() {
        for (seed in 0..50) {
            val tv = freshVar()
            val target = randomType(2, seed)
            val sub = Substitution.single(tv, target)

            for ((k, v) in sub.mappings) {
                assertFalse(occursCheck(k, v), "Occurs check failed for $k -> $v")
            }
        }
    }

    @Test
    fun `no mapping is a self-mapping`() {
        for (seed in 0..50) {
            val tv = freshVar()
            val target = randomType(2, seed)
            if (target is MezzeType.VarType && target.variable == tv) continue
            val sub = Substitution.single(tv, target)

            for ((k, v) in sub.mappings) {
                assertFalse(
                    v is MezzeType.VarType && v.variable == k,
                    "Self-mapping found: $k -> $v"
                )
            }
        }
    }
}
