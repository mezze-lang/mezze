package com.mezze.language.typechecker.debug

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.ExportEntry
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypeScheme
import com.mezze.language.typedast.TypedEffectDecl
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedHandlerRef
import com.mezze.language.typedast.TypedIntLit
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedPerform
import com.mezze.language.typedast.TypedPerformOp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * S4.2 (#362) — unit tests for the effect-conservation invariant: the emitted
 * function-type effect row must be ⊇ the effects the body actually performs
 * (not-discharged). Synthetic TypedModules exercise the ⊇ rule directly so the
 * invariant's logic is pinned independent of the full typecheck pipeline.
 */
class EffectConservationInvariantTest {

    @BeforeEach
    fun enable() {
        System.setProperty("mezze.invariants", "true")
    }

    @AfterEach
    fun disable() {
        System.clearProperty("mezze.invariants")
    }

    private val loc = Loc("test.mz", 1, 1, 1, 1)
    private val logHash = Hash("a".repeat(64))
    private val asyncHash = Hash("b".repeat(64))

    private fun effectDecl(hash: Hash, name: String) =
        TypedEffectDecl(hash = hash, name = name, operations = emptyList(), associatedTypes = emptyList(), loc = loc)

    private fun performOp(hash: Hash, op: String): TypedPerformOp =
        TypedPerformOp(effectHash = hash, operationName = op, arg = TypedIntLit(0, MezzeType.IntType, loc), type = MezzeType.IntType, loc = loc)

    /** A single-param lambda whose type carries [row] and whose body is [body]. */
    private fun lambda(row: MezzeType.EffectRow?, body: TypedExpr): TypedLambda {
        val fnType = MezzeType.FunctionType(
            paramType = MezzeType.IntType,
            returnType = MezzeType.IntType,
            effects = row,
        )
        return TypedLambda(params = emptyList(), body = body, captures = emptyList(), type = fnType, loc = loc)
    }

    private fun module(name: String, lam: TypedLambda, effects: List<TypedEffectDecl>): TypedModule {
        val fnType = lam.type
        val decl = TypedLetDecl(
            hash = Hash("f".repeat(64)),
            name = name,
            typeParams = emptyList(),
            value = lam,
            scheme = TypeScheme(typeParams = emptyList(), abilityConstraints = emptyList(), type = fnType),
            isPublic = true,
            loc = loc,
        )
        return TypedModule(
            path = "test",
            hash = Hash.ZERO,
            imports = emptyList(),
            exports = emptyMap<String, ExportEntry>(),
            decls = listOf(decl),
            abilityDecls = emptyList(),
            abilityImpls = emptyList(),
            effectDecls = effects,
            performerDecls = emptyList(),
            dependencies = emptyList(),
        )
    }

    // --- TRIP cases: body performs an effect the concrete emitted row omits ---

    @Test
    fun `performs Log but emitted row is pure -- TRIPS`() {
        val lam = lambda(row = null, body = performOp(logHash, "info"))
        val mod = module("launder", lam, listOf(effectDecl(logHash, "Log")))
        val ex = assertFailsWith<InvariantChecker.InvariantViolation> {
            InvariantChecker.checkEffectConservation(mod)
        }
        assert(ex.message!!.contains("Log")) { "message should name the laundered effect: ${ex.message}" }
    }

    @Test
    fun `performs Log and Async but emitted row promises only Log -- TRIPS`() {
        // Body performs Async; emitted row promises only Log — Async is laundered.
        val lam = lambda(row = MezzeType.EffectRow.simple(setOf("Log")), body = performOp(asyncHash, "await"))
        val mod = module("narrow", lam, listOf(effectDecl(logHash, "Log"), effectDecl(asyncHash, "Async")))
        val ex = assertFailsWith<InvariantChecker.InvariantViolation> {
            InvariantChecker.checkEffectConservation(mod)
        }
        assert(ex.message!!.contains("Async")) { "message should name Async: ${ex.message}" }
    }

    // --- PASS cases: emitted ⊇ performed, incl. open-row and handler discharge ---

    @Test
    fun `emitted row contains the performed effect -- PASSES`() {
        val lam = lambda(row = MezzeType.EffectRow.simple(setOf("Log")), body = performOp(logHash, "info"))
        val mod = module("honest", lam, listOf(effectDecl(logHash, "Log")))
        InvariantChecker.checkEffectConservation(mod) // no throw
    }

    @Test
    fun `open polymorphic effect row is superset of anything -- PASSES`() {
        val openRow = MezzeType.EffectRow(
            effects = emptySet(),
            rowVar = com.mezze.language.ast.RowVar("r"),
        )
        val lam = lambda(row = openRow, body = performOp(logHash, "info"))
        val mod = module("poly", lam, listOf(effectDecl(logHash, "Log")))
        InvariantChecker.checkEffectConservation(mod) // no throw: open row ⊇ anything
    }

    @Test
    fun `handler-discharged effect is not performed-out -- PASSES`() {
        // Body performs Log, but an enclosing perform HANDLER discharges Log.
        // Emitted row is pure — sound, because Log is caught by the ancestor.
        val performsLogUnderHandler = TypedPerform(
            handlers = listOf(
                TypedHandlerRef(
                    performerHash = Hash("c".repeat(64)),
                    effectHash = logHash,
                    performerName = "consoleLog",
                    params = null,
                    loc = loc,
                )
            ),
            body = performOp(logHash, "info"),
            type = MezzeType.IntType,
            loc = loc,
        )
        val lam = lambda(row = null, body = performsLogUnderHandler)
        val mod = module("discharged", lam, listOf(effectDecl(logHash, "Log")))
        InvariantChecker.checkEffectConservation(mod) // no throw: Log discharged by handler
    }

    @Test
    fun `disabled when invariants off`() {
        System.setProperty("mezze.invariants", "false")
        val lam = lambda(row = null, body = performOp(logHash, "info"))
        val mod = module("launder", lam, listOf(effectDecl(logHash, "Log")))
        InvariantChecker.checkEffectConservation(mod) // no throw: gate is off
    }
}
