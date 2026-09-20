package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * S7.3 (#371) — FINAL ADVERSARIAL SOUNDNESS SWEEP (the re-layering capstone).
 *
 * Each test here is an ADVERSARIAL program written to try to make the type
 * checker either (i) ACCEPT a program that would crash / give a wrong answer at
 * runtime (a false-negative = soundness hole), or (ii) REJECT a program that is
 * actually fine (a false-positive = over-strict). The lenses cover the areas the
 * re-layering touched hardest: dispatch/identity, effects/handlers, exhaustiveness,
 * records/spread, row-poly, associated types, numerics/class-params, coherence.
 *
 * The oracle is the DESIGNED behaviour, not the checker under test:
 *  - `accepts(src)` asserts the program type-checks (no TypeCheckError).
 *  - `rejects(src)` asserts the program is rejected, optionally matching a substring.
 *
 * Run under `-Dmezze.invariants=true` (set in setup, matching the gate) so every
 * boundary invariant is armed while these programs are checked.
 *
 * ENTRY-POINT RUNNABILITY is a CLI rule (cli `EntryPointCheck`), not a typechecker
 * rule: `main` is not special to the typechecker, and the entry name comes from
 * Mezze.toml. So a program whose entry leaves a concrete effect undischarged
 * type-checks fine HERE (it is well-typed) — the CLI rejects it before running.
 * Entry-rejection coverage lives in cli `EntryPointCheckTest`.
 */
class SoundnessSweepTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker
    private var priorInvariants: String? = null

    @BeforeEach fun s() {
        priorInvariants = System.getProperty("mezze.invariants")
        System.setProperty("mezze.invariants", "true")
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    @AfterEach fun t() {
        if (priorInvariants == null) System.clearProperty("mezze.invariants")
        else System.setProperty("mezze.invariants", priorInvariants!!)
    }

    /** null ⇒ accepted; else the rejection message. */
    private fun check(src: String): String? =
        try {
            tc.typeCheck(parser.parseParsedModule(src, "sweep.mz"), Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) {
            e.message
        }

    private fun accepts(src: String) {
        val msg = check(src)
        assertNull(msg, "Expected program to type-check but it was REJECTED:\n$src\n-> $msg")
    }

    private fun rejects(src: String, contains: String? = null) {
        val msg = check(src)
        assertNotNull(msg, "Expected program to be REJECTED but it type-checked:\n$src")
        if (contains != null) {
            assertTrue(msg!!.contains(contains, ignoreCase = true),
                "Rejection message did not mention '$contains':\n$src\n-> $msg")
        }
    }

    // ---- Lens: PATTERNS / EXHAUSTIVENESS -----------------------------------

    @Test fun `nonexhaustive closed variant match is rejected`() = rejects(
        """
        type Color = | Red, Green, Blue |
        let name = {c: Color} -> match c is
          Red -> "red"
          Green -> "green"
        """.trimIndent()
    )

    @Test fun `exhaustive closed variant match is accepted`() = accepts(
        """
        type Color = | Red, Green, Blue |
        let name = {c: Color} -> match c is
          Red -> "red"
          Green -> "green"
          Blue -> "blue"
        """.trimIndent()
    )

    @Test fun `nested variant string-interp sub-pattern binds - not dropped (hash-221 class)`() = accepts(
        """
        type Msg = | Greet {text: Str}, Bye {text: Str} |
        let handle = {m: Msg} -> match m is
          Greet {text = "hello ${'$'}(name)"} -> name
          Greet {text = t} -> t
          Bye {text = t} -> t
        """.trimIndent()
    )

    @Test fun `nested seq plus string-interp sub-pattern accepted (hash-221 class)`() = accepts(
        """
        let handle = {items: [Str]} -> match items is
          ["hello ${'$'}(name)", ...rest] -> name
          [first, ...rest] -> first
          [] -> "empty"
        """.trimIndent()
    )

    @Test fun `newtype scrutinee is opaque - cannot match underlying literal`() = rejects(
        """
        newtype Wrapped = Int
        let describe = {w: Wrapped} -> match w is
          0 -> "zero"
          n -> "nonzero"
        """.trimIndent()
    )

    // ---- Lens: ABILITIES / IMPLS / COHERENCE -------------------------------

    @Test fun `duplicate impl for same ability-type is rejected`() = rejects(
        """
        ability Greet for 'a where {
          greet: {it: 'a} -> Str
        }
        type Person = { name: Str }
        impl Greet for Person where { greet = {it} -> "Hello" }
        impl Greet for Person where { greet = {it} -> "Hi" }
        """.trimIndent(),
        contains = "already implemented"
    )

    @Test fun `ability method on a type with no impl is rejected - never default-dispatches`() = rejects(
        """
        ability Describe for 'a where {
          describe: {it: 'a} -> Str
        }
        impl Describe for Int where { describe = {it} -> "int" }
        type Foo = { x: Int }
        let go = {f: Foo} -> f.describe {}
        """.trimIndent(),
        contains = "Describe"
    )

    @Test fun `polymorphic ability call infers the constraint - dispatch per instantiation`() = accepts(
        """
        ability Describe for 'a where {
          describe: {it: 'a} -> Str
        }
        impl Describe for Int where { describe = {it} -> "int" }
        impl Describe for Str where { describe = {it} -> "str" }
        let show = {x} -> x.describe {}
        let a = show {x = 42}
        let b = show {x = "hi"}
        """.trimIndent()
    )

    @Test fun `anon-record ability dispatch resolves to declared record impl (hash-333)`() = accepts(
        """
        type Vec = { x: Int, y: Int }
        ability Norm for 'a where { norm: {it: 'a} -> Int }
        impl Norm for Vec where { norm = {it} -> it.x * it.x + it.y * it.y }
        let n = { x = 3, y = 4 }.norm {}
        """.trimIndent()
    )

    // ---- Lens: EFFECTS / HANDLERS ------------------------------------------

    @Test fun `effectful callback into a pure-declared slot is rejected (hash-215 slash 331 launder class)`() = rejects(
        """
        effect Boom where { boom: {} -> {} <Boom> }
        let apply = {f: {a: Int} -> Int, x: Int} -> f {a = x}
        let cb = {a: Int} -> do
            Boom.boom {}
            a + 1
        let run = {} -> apply {f = cb, x = 5}
        """.trimIndent()
    )

    @Test fun `effect performed in a nested callback propagates into the outer row (no launder)`() = accepts(
        // Boom is NOT discharged; the correct behaviour is that `bad`'s inferred
        // row carries <Boom> — the effect is conserved, not laundered away.
        """
        effect Boom where { boom: {} -> {} <Boom> }
        let bad = {} -> do
            let xs = [1, 2, 3]
            xs.map {f = {x} -> do
                Boom.boom {}
                x + 1
            }
        """.trimIndent()
    )

    @Test fun `handler for the wrong effect leaves the performed effect in the row (sound)`() = accepts(
        // perform AlphaH but body performs Beta: Beta must REMAIN in the row
        // (run : {} -> Int <Beta, ...>), never silently discharged by AlphaH.
        """
        effect Alpha where { a_op: {} -> Int <Alpha> }
        effect Beta where { b_op: {} -> Int <Beta> }
        impl Alpha for AlphaH where { a_op = {} -> 1 }
        let run = {} -> perform AlphaH in do
            Beta.b_op {}
        """.trimIndent()
    )

    @Test fun `effect fully discharged by its handler yields a clean row`() = accepts(
        """
        effect Oracle where { ask: {} -> Int <Oracle> }
        impl Oracle for FixedOracle where { ask = {} -> 42 }
        let run = {} -> perform FixedOracle in do
            let n = Oracle.ask {}
            n + 1
        """.trimIndent()
    )

    // ---- Lens: NUMERICS / CLASS-PARAMS -------------------------------------

    @Test fun `a function cannot be used as a numeric operand (classparam_fn hole closed)`() = rejects(
        """
        let f = {x} -> x + 1
        let bad = f + 1
        """.trimIndent(),
        contains = "function"
    )

    // ---- Lens: RECORDS / SPREAD / ALIASES ----------------------------------

    @Test fun `spread cannot ADD a field to a closed record type`() = rejects(
        """
        type Point = { x: Int, y: Int }
        let p: Point = { x = 1, y = 2 }
        let q: Point = { ...p, z = 3 }
        """.trimIndent(),
        contains = "z"
    )

    @Test fun `open-record required field cannot be bypassed`() = rejects(
        """
        type HasName = { name: Str, ... }
        let greet = {u: HasName} -> u.name
        let go = greet {u = { age = 30 }}
        """.trimIndent()
    )

    @Test fun `access to an undeclared field is rejected (hash-277)`() = rejects(
        """
        type Point = { x: Int, y: Int }
        let p: Point = { x = 1, y = 2 }
        let z = p.z
        """.trimIndent(),
        contains = "z"
    )

    // ---- Lens: ROW-POLY -----------------------------------------------------

    @Test fun `field not declared on an open-row parameter cannot be read off the result`() = rejects(
        """
        let getx = {r: {x: Int, ...}} -> r
        let out = getx {r = {x = 1}}
        let y = out.y
        """.trimIndent(),
        contains = "y"
    )

    @Test fun `open-row parameter accepts a wider record and reads its declared field`() = accepts(
        """
        let describe = {r: {name: Str, ...}} -> r.name
        let s = describe {r = {name = "a", age = 30}}
        """.trimIndent()
    )

    // ---- Lens: ASSOCIATED TYPES --------------------------------------------

    @Test fun `associated error type mismatch across two perform-ops is rejected`() = rejects(
        """
        use std::effects::exception::{Exception, ExceptionToResult, throw}
        let compute = {} -> perform ExceptionToResult in do
            let a = Exception.throw {error = 42}
            let b = Exception.throw {error = "str"}
            a
        """.trimIndent()
    )

    // ---- KNOWN HOLE (documented, disabled) ---------------------------------

    /**
     * S7.3 FINDING #1 — SOUNDNESS HOLE (documented for a follow-up ticket; NOT
     * fixed here). An unhandled USER effect that reaches the program ENTRY point
     * type-checks but crashes at runtime.
     *
     * Program:
     *   effect Oracle where { ask: {} -> Int <Oracle> }
     *   let main = {} -> do
     *       let n = Oracle.ask {}      # Oracle NEVER handled
     *       n + 1
     *
     * EXPECTED: rejected — the entry point's effect row still contains a concrete,
     * un-dischargeable effect (Oracle). Effect rule: top-level code must handle all
     * effects.
     *
     * ACTUAL (via `mez check` + `mez exec`, strictTopLevel=true): the program
     * type-checks — `main : {} -> {} <Oracle, *>` — because a concrete effect
     * unifies into the entry's OPEN expected row `<*>` and is never required to be
     * discharged. At runtime the un-handled `perform`-op returns a `MezzeSuspend`
     * continuation value where an `Int` is expected:
     *   java.lang.RuntimeException: Expected Long for field 'n', got: MezzeSuspend=<suspended ...>
     *
     * ROOT CAUSE: TypeValidator.validateParsedTopLevelEffects only inspects a
     * top-level `let` whose inferred type is DIRECTLY a SuspendType (a value
     * binding); when the entry is a lambda (`let main = {} -> ...`, a FunctionType
     * carrying a residual effect ROW), the residual concrete effect on the return
     * row is never validated against "the entry must discharge all effects".
     *
     * This test is NOT disabled — it is RE-SCOPED, and the sentence that stood here
     * claiming it was `@Disabled` was stale. Entry runnability is a CLI rule
     * (EntryPointCheck), so the typechecker unit asserts what the typechecker
     * decides: this program is well-typed. EntryPointCheckTest covers the rejection.
     */
    // Entry runnability is a CLI rule now (EntryPointCheck): the TYPECHECKER
    // accepts this — `main : {} -> Int <Oracle, *>` is well-typed. The CLI
    // rejects the undischarged Oracle before running (see EntryPointCheckTest).
    @Test fun `entry with an unhandled user effect type-checks (CLI rejects it)`() = accepts(
        """
        effect Oracle where { ask: {} -> Int <Oracle> }
        let main = {} -> do
            let n = Oracle.ask {}
            n + 1
        """.trimIndent()
    )

    // #377: an effectful entry that HANDLES its effect (perform Performer in ...)
    // leaves NO concrete undischarged effect on the residual row → ACCEPTED.
    @Test fun `entry that handles its user effect is accepted (hash-377)`() = accepts(
        """
        effect Oracle where { ask: {} -> Int <Oracle> }
        impl Oracle for MyOracle where {
          ask : {} -> Int = {} -> resume {val = 42}
        }
        let main = {} -> perform MyOracle in do
            let n = Oracle.ask {}
            n + 1
        """.trimIndent()
    )

    // Wrong-handler variant — main handles Beta but performs Oracle, so `main`'s
    // residual still carries the undischarged Oracle. The TYPECHECKER accepts it
    // (well-typed); the CLI EntryPointCheck rejects it (see EntryPointCheckTest).
    @Test fun `entry with wrong handler leaves undischarged user effect (CLI rejects it)`() = accepts(
        """
        effect Oracle where { ask: {} -> Int <Oracle> }
        effect Beta where { poke: {} -> Int <Beta> }
        impl Beta for MyBeta where {
          poke : {} -> Int = {} -> resume {val = 7}
        }
        let main = {} -> perform MyBeta in do
            let n = Oracle.ask {}
            n + 1
        """.trimIndent()
    )

    // #377 GUARD (no over-rejection): a non-entry library fn may mention a user
    // effect in its signature — its caller discharges it. Only `main` is gated.
    @Test fun `non-entry fn mentioning a user effect is accepted (hash-377)`() = accepts(
        """
        effect Oracle where { ask: {} -> Int <Oracle> }
        let ask_twice = {} -> do
            let a = Oracle.ask {}
            let b = Oracle.ask {}
            a + b
        """.trimIndent()
    )
}
