package com.mezze.language.typechecker.debug

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.solving.Substitution
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class InvariantCheckerTest {

    @BeforeEach
    fun enable() {
        System.setProperty("mezze.invariants", "true")
    }

    @AfterEach
    fun disable() {
        System.clearProperty("mezze.invariants")
    }

    @Test
    fun `valid substitution passes`() {
        val tv = TypeVar("a", 0, 1)
        val sub = Substitution.single(tv, MezzeType.IntType)
        InvariantChecker.checkSubstitution(sub, "test")
    }

    @Test
    fun `self-mapping detected`() {
        val tv = TypeVar("a", 0, 1)
        val sub = Substitution(mapOf(tv to MezzeType.VarType(tv)))
        assertFailsWith<InvariantChecker.InvariantViolation> {
            InvariantChecker.checkSubstitution(sub, "test")
        }
    }

    @Test
    fun `occurs-check violation detected`() {
        val tv = TypeVar("a", 0, 1)
        val sub = Substitution(mapOf(tv to MezzeType.TypeApplication("List", listOf(MezzeType.VarType(tv)))))
        assertFailsWith<InvariantChecker.InvariantViolation> {
            InvariantChecker.checkSubstitution(sub, "test")
        }
    }

    @Test
    fun `idempotent substitution passes`() {
        val tv = TypeVar("a", 0, 1)
        val sub = Substitution.single(tv, MezzeType.IntType)
        InvariantChecker.checkSubstitutionIdempotence(sub, "test")
    }

    @Test
    fun `non-idempotent substitution detected`() {
        val tv1 = TypeVar("a", 0, 1)
        val tv2 = TypeVar("b", 0, 2)
        val sub = Substitution(mapOf(
            tv1 to MezzeType.VarType(tv2),
            tv2 to MezzeType.IntType,
        ))
        assertFailsWith<InvariantChecker.InvariantViolation> {
            InvariantChecker.checkSubstitutionIdempotence(sub, "test")
        }
    }

    @Test
    fun `checks disabled when property is false`() {
        System.setProperty("mezze.invariants", "false")
        val tv = TypeVar("a", 0, 1)
        val badSub = Substitution(mapOf(tv to MezzeType.VarType(tv)))
        InvariantChecker.checkSubstitution(badSub, "test")
    }
}
