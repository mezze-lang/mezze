package com.mezze.language.typechecker.debug

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.Loc
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.constraints.Constraint
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertFailsWith

/**
 * S0.2 (#351) — trip-tests for the NO-UNRESOLVED-DISPATCH-VAR invariant.
 *
 * The invariant's whole job is the generalized-vs-dispatch distinction:
 *  - a receiver that solves to a bare VarType which IS a generalized param of an
 *    emitted decl scheme is legitimate polymorphism → MUST NOT trip;
 *  - a receiver that solves to a bare VarType which is NOT such a param is an
 *    orphan receiver leak (the "defaults to Bytes" mis-dispatch class) → MUST
 *    trip, naming the method + receiver var + Loc.
 *
 * Unit tests drive [InvariantChecker.checkNoUnresolvedDispatchVar] directly with
 * synthetic buckets so the classification logic is pinned independent of the full
 * pipeline; integration tests confirm real polymorphic decls (id/pair/add) don't
 * trip the wired-in invariant.
 */
class NoUnresolvedDispatchVarInvariantTest {

    @BeforeEach
    fun enable() {
        System.setProperty("mezze.invariants", "true")
    }

    @AfterEach
    fun disable() {
        System.clearProperty("mezze.invariants")
    }

    private val loc = Loc("test.mz", 3, 5, 3, 12)

    private fun deferred(receiver: MezzeType, method: String = "abs", receiverLoc: Loc? = null) =
        Constraint.InherentMethodResolve(
            receiverType = receiver,
            methodName = method,
            candidates = emptyList(),
            argsType = MezzeType.VarType(TypeVar("'args", id = -1)),
            resultType = MezzeType.VarType(TypeVar("'res", id = -2)),
            receiverLoc = receiverLoc,
            loc = loc,
        )

    // --- TRIP: unresolved receiver var that is NOT a generalized param ---

    @Test
    fun `receiver solves to an orphan var -- TRIPS`() {
        val v = TypeVar("'a", id = 100)
        val c = deferred(MezzeType.VarType(v))
        // No generalized params at all: this var is an orphan leak.
        val ex = assertFailsWith<InvariantChecker.InvariantViolation> {
            InvariantChecker.checkNoUnresolvedDispatchVar(
                unresolvedInherentMethods = listOf(c),
                substitution = Substitution.empty,
                callReturnTypes = emptyMap(),
                generalizedVarIds = emptySet(),
            )
        }
        assert(ex.message!!.contains("abs")) { "must name the method: ${ex.message}" }
        assert(ex.message!!.contains("id=100")) { "must name the unresolved var: ${ex.message}" }
        assert(ex.message!!.contains(loc.toString()) || ex.message!!.contains("test.mz")) {
            "must name the Loc: ${ex.message}"
        }
    }

    @Test
    fun `receiver solves via substitution to an orphan var -- TRIPS`() {
        val fromVar = TypeVar("'x", id = 7)
        val toVar = TypeVar("'y", id = 8)
        val c = deferred(MezzeType.VarType(fromVar))
        val subst = Substitution(mappings = mapOf(fromVar to MezzeType.VarType(toVar)))
        // 'y (id=8) is not a generalized param — only some unrelated id=999 is.
        assertFailsWith<InvariantChecker.InvariantViolation> {
            InvariantChecker.checkNoUnresolvedDispatchVar(
                unresolvedInherentMethods = listOf(c),
                substitution = subst,
                callReturnTypes = emptyMap(),
                generalizedVarIds = setOf(999),
            )
        }
    }

    // --- PASS: generalized-param receiver, concrete receiver, invariants off ---

    @Test
    fun `receiver var IS a generalized param -- PASSES`() {
        val v = TypeVar("'a", id = 42)
        val c = deferred(MezzeType.VarType(v))
        // id=42 is a legitimately-quantified param of some emitted decl scheme.
        InvariantChecker.checkNoUnresolvedDispatchVar(
            unresolvedInherentMethods = listOf(c),
            substitution = Substitution.empty,
            callReturnTypes = emptyMap(),
            generalizedVarIds = setOf(42),
        ) // no throw
    }

    @Test
    fun `receiver solves to a concrete type -- PASSES`() {
        val v = TypeVar("'a", id = 5)
        val c = deferred(MezzeType.VarType(v))
        val subst = Substitution(mappings = mapOf(v to MezzeType.IntType))
        InvariantChecker.checkNoUnresolvedDispatchVar(
            unresolvedInherentMethods = listOf(c),
            substitution = subst,
            callReturnTypes = emptyMap(),
            generalizedVarIds = emptySet(),
        ) // no throw: receiver is concrete Int
    }

    @Test
    fun `receiver pinned only via callReturnTypes -- PASSES`() {
        // Flat subst leaves the receiver a var, but the per-call-site
        // callReturnTypes pins it concrete (forward-ref / effect-context case).
        val v = TypeVar("'a", id = 9)
        val rloc = Loc("test.mz", 4, 1, 4, 3)
        val c = deferred(MezzeType.VarType(v), receiverLoc = rloc)
        InvariantChecker.checkNoUnresolvedDispatchVar(
            unresolvedInherentMethods = listOf(c),
            substitution = Substitution.empty,
            callReturnTypes = mapOf(rloc to MezzeType.IntType),
            generalizedVarIds = emptySet(),
        ) // no throw: callReturnTypes pins the receiver to Int
    }

    @Test
    fun `disabled when invariants off -- PASSES even on orphan var`() {
        System.setProperty("mezze.invariants", "false")
        val c = deferred(MezzeType.VarType(TypeVar("'a", id = 1)))
        InvariantChecker.checkNoUnresolvedDispatchVar(
            unresolvedInherentMethods = listOf(c),
            substitution = Substitution.empty,
            callReturnTypes = emptyMap(),
            generalizedVarIds = emptySet(),
        ) // no throw: gate off
    }

    // --- INTEGRATION: real polymorphic decls must NOT trip the wired invariant ---

    @Test
    fun `polymorphic id pair add do not trip the invariant`() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        System.setProperty("mezze.typecheck.strict", "true")
        val parser = MezzeParser()
        val typeChecker = TypeChecker()
        val source = """
            let id = {x} -> x
            let pair = {a, b} -> { fst = a, snd = b }
            let add = {a: Int, b: Int} -> a + b
            let r = id { x = 42 }
            r
        """.trimIndent()
        val parsed = parser.parseParsedModule(source, "no_unresolved_poly.mz")
        // Must not throw: every free var here is a legitimately-generalized param.
        typeChecker.typeCheck(parsed, Paths.get("."))
    }
}
