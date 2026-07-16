package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.Loc
import com.mezze.language.typechecker.constraints.Constraint
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConstraintSimplifierTest {

    private fun loc(l: Int) = Loc("t.mz", l, 1, l, 2)
    private val a = MezzeType.VarType(TypeVar.fresh())
    private val b = MezzeType.VarType(TypeVar.fresh())

    @Test
    fun `drops reflexive equalities`() {
        val cs = listOf(
            Constraint.Equality(MezzeType.IntType, MezzeType.IntType),
            Constraint.Equality(a, a),
            Constraint.Equality(a, MezzeType.IntType),  // not reflexive
        )
        val out = ConstraintSimplifier.simplify(cs)
        assertEquals(1, out.size)
        assertTrue(out.single() is Constraint.Equality)
    }

    @Test
    fun `drops exact duplicates keeping the first`() {
        val first = Constraint.Equality(a, MezzeType.IntType, loc = loc(1))
        val dup = Constraint.Equality(a, MezzeType.IntType, loc = loc(9))
        val out = ConstraintSimplifier.simplify(listOf(first, dup))
        assertEquals(1, out.size)
        // kept the FIRST occurrence (earliest loc) for diagnostics
        assertEquals(loc(1), out.single().loc)
    }

    @Test
    fun `dedup ignores loc and context but keeps distinct requirements`() {
        val cs = listOf(
            Constraint.HasField(a, "x", MezzeType.IntType, context = "c1", loc = loc(1)),
            Constraint.HasField(a, "x", MezzeType.IntType, context = "c2", loc = loc(2)),  // dup (loc/ctx differ)
            Constraint.HasField(a, "x", MezzeType.StringType, loc = loc(3)),               // distinct fieldType
            Constraint.HasField(a, "y", MezzeType.IntType, loc = loc(4)),                  // distinct fieldName
        )
        val out = ConstraintSimplifier.simplify(cs)
        assertEquals(3, out.size)
    }

    @Test
    fun `strictFieldCheck is part of the key`() {
        val cs = listOf(
            Constraint.HasField(a, "x", MezzeType.IntType, strictFieldCheck = false),
            Constraint.HasField(a, "x", MezzeType.IntType, strictFieldCheck = true),  // different meaning
        )
        assertEquals(2, ConstraintSimplifier.simplify(cs).size)
    }

    @Test
    fun `isMatchCaseContext is part of the key`() {
        val cs = listOf(
            Constraint.Equality(a, b, isMatchCaseContext = false),
            Constraint.Equality(a, b, isMatchCaseContext = true),  // different variant-merge behaviour
        )
        assertEquals(2, ConstraintSimplifier.simplify(cs).size)
    }

    @Test
    fun `preserves distinct constraint kinds`() {
        val cs = listOf(
            Constraint.Equality(a, MezzeType.IntType),
            Constraint.HasField(a, "x", MezzeType.IntType),
            Constraint.MustBeOpen(a),
        )
        assertEquals(3, ConstraintSimplifier.simplify(cs).size)
    }

    @Test
    fun `empty and singleton pass through`() {
        assertTrue(ConstraintSimplifier.simplify(emptyList()).isEmpty())
        val one = listOf(Constraint.Equality(a, MezzeType.IntType))
        assertEquals(1, ConstraintSimplifier.simplify(one).size)
    }

    @Test
    fun `preserves relative order of survivors`() {
        val c1 = Constraint.HasField(a, "x", MezzeType.IntType)
        val c2 = Constraint.HasField(a, "y", MezzeType.StringType)
        val c3 = Constraint.HasField(a, "z", MezzeType.BoolType)
        val out = ConstraintSimplifier.simplify(listOf(c1, c2, c1, c3))  // c1 duplicated
        assertEquals(listOf(c1, c2, c3), out)
    }
}
