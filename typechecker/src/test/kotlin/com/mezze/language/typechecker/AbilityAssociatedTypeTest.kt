package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * End-to-end coverage for ability ASSOCIATED TYPES (`having`), verifying the
 * whole path works after the `<-` do-bind removal made associated-type
 * propagation uniform (no more `Tryable` name-exclusion in
 * ExprFunctionConstraints — every ability method now contributes its `having`
 * binding to the enclosing function's signature the same way).
 *
 * The behaviour mirrors effect associated types:
 *   - a GENERIC function renders `having 'out = <var>` (the binding is a
 *     variable until the ability is discharged),
 *   - a CONCRETE receiver resolves and CHECKS the concrete associated type,
 *   - `having` in an annotation is OPTIONAL (inferred when omitted, rejected
 *     when wrong).
 *
 * [AbilityHavingParityTest] is the focused parity spec; this is the broader
 * matrix (multi-param abilities, multiple `having`, chained calls, the
 * surviving `.try {}` ability).
 */
class AbilityAssociatedTypeTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach fun setup() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); typeChecker = TypeChecker()
    }

    private fun typeOf(name: String, body: String): String? {
        val parsed = parser.parseParsedModule(body, "test.mz")
        val r = typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
        return r.getVariableType(name)?.let { prettyPrintType(it) }
    }

    private fun err(body: String): String? {
        val parsed = parser.parseParsedModule(body, "test.mz")
        return try { typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }
    }

    // ─── generic: `having` renders as a variable, uniformly ──────────────

    @Test
    fun `generic ability call renders having as a variable in the with-clause`() {
        val t = typeOf("mk", """
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            pub let mk = { x } -> x.produce {}
        """.trimIndent())
        assertNotNull(t, "mk should typecheck")
        assertTrue(
            Regex("""with Producer .*having 'out = '\w+""").containsMatchIn(t!!),
            "generic fn should render `having 'out = <var>`, got: $t"
        )
    }

    @Test
    fun `two generic ability calls each surface their own having binding`() {
        // Two different single-associated-type abilities used on the same
        // generic param: both `having`s must appear (uniform propagation — no
        // ability is special-cased out anymore).
        val t = typeOf("both", """
            ability First for 'a having 'x where { first: { it: 'a } -> 'x }
            ability Second for 'a having 'y where { second: { it: 'a } -> 'y }
            impl First for Int having 'x = Str where { first = { it } -> "a" }
            impl Second for Int having 'y = Bool where { second = { it } -> True }
            pub let both = { v } -> {
              a = v.first {},
              b = v.second {}
            }
        """.trimIndent())
        assertNotNull(t, "both should typecheck")
        assertTrue(Regex("""having 'x = '\w+""").containsMatchIn(t!!),
            "expected `having 'x = <var>`; got: $t")
        assertTrue(Regex("""having 'y = '\w+""").containsMatchIn(t),
            "expected `having 'y = <var>`; got: $t")
    }

    // ─── concrete receiver: resolve + check the concrete associated type ──

    @Test
    fun `concrete receiver resolves the associated type (no with-clause)`() {
        // `(5).produce{}` discharges Producer on Int → 'out = Str; the result
        // is a concrete `Str` and there is no leftover `with`-clause.
        val t = typeOf("go", """
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            pub let go = {} -> (5).produce {}
        """.trimIndent())
        assertNotNull(t, "go should typecheck")
        assertTrue(t!!.contains("Str"), "concrete resolution should yield Str, got: $t")
        assertFalse(t.contains("with Producer"),
            "a discharged ability leaves no with-clause, got: $t")
    }

    @Test
    fun `wrong concrete having is rejected`() {
        val e = err("""
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            pub let bad: {} -> Int = {} -> (5).produce {}
        """.trimIndent())
        assertNotNull(e, "produce resolves 'out=Str on Int; annotating Int must be rejected")
    }

    @Test
    fun `right concrete having is accepted`() {
        val e = err("""
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            pub let ok: {} -> Str = {} -> (5).produce {}
        """.trimIndent())
        assertNull(e, "annotating the correctly-resolved Str must typecheck, got: $e")
    }

    // ─── annotation rules: having optional ───────────────────────────────

    @Test
    fun `wrong having value in a with-clause on a generic fn is rejected`() {
        // R2: the impl resolves 'out = Str; annotating `having 'out = Int` on a
        // GENERIC function must be rejected (not just on a concrete receiver).
        val e = err("""
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            pub let mk: { x: 'a } with Producer 'a having 'out = Int -> Int = { x } -> x.produce {}
        """.trimIndent())
        assertNotNull(e, "wrong `having 'out = Int` (impl says Str) must be rejected")
        assertTrue(e!!.contains("'out"), "error should name the associated type, got: $e")
    }

    @Test
    fun `having for a nonexistent associated type is rejected`() {
        // R5: `Producer` declares only `'out`; `having 'nope` must be rejected.
        val e = err("""
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            pub let mk: { x: 'a } with Producer 'a having 'nope = Str -> Str = { x } -> x.produce {}
        """.trimIndent())
        assertNotNull(e, "`having 'nope` (undeclared) must be rejected")
        assertTrue(e!!.contains("'nope") || e.contains("no associated type"),
            "error should call out the unknown associated type, got: $e")
    }

    @Test
    fun `a generic ability annotation carries exactly one constraint (no doubling)`() {
        // Cosmetic regression guard: the printed signature must not contain a
        // malformed self-repeat like `Producer 'a 'a`.
        val t = typeOf("mk", """
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            pub let mk: { x: 'a } with Producer 'a having 'out = Str -> Str = { x } -> x.produce {}
        """.trimIndent())
        assertNotNull(t, "mk should typecheck")
        assertFalse(Regex("""Producer '\w+ '\w+""").containsMatchIn(t!!),
            "must not print a doubled `Producer 'a 'a` constraint, got: $t")
    }

    @Test
    fun `omitting having in a with-clause annotation typechecks`() {
        assertNull(
            err("""
                ability Producer for 'a having 'out where {
                  produce: { it: 'a } -> 'out
                }
                impl Producer for Int having 'out = Str where {
                  produce = { it } -> "x"
                }
                pub let mk: { x: 'a } with Producer 'a -> 'out = { x } ->
                  x.produce {}
            """.trimIndent()),
            "with Producer 'a (no having) should typecheck; 'out is inferred"
        )
    }

    // ─── multi-parameter abilities (Mul 'b for 'a having 'out) ───────────

    @Test
    fun `multi-param ability resolves associated type on a concrete receiver`() {
        // The heterogeneous-operator shape: `Scale 'by for 'a having 'out`.
        // `(10).scale {by = 3}` discharges on (Int, Int) → 'out = Int.
        val e = err("""
            ability Scale 'by for 'a having 'out where {
              scale: { it: 'a, by: 'by } -> 'out
            }
            impl Scale Int for Int having 'out = Int where {
              scale = { it, by } -> it
            }
            pub let tripled: {} -> Int = {} -> (10).scale { by = 3 }
        """.trimIndent())
        assertNull(e, "multi-param ability on a concrete receiver must resolve, got: $e")
    }

    @Test
    fun `multi-param ability wrong having is rejected`() {
        val e = err("""
            ability Scale 'by for 'a having 'out where {
              scale: { it: 'a, by: 'by } -> 'out
            }
            impl Scale Int for Int having 'out = Int where {
              scale = { it, by } -> it
            }
            pub let bad: {} -> Str = {} -> (10).scale { by = 3 }
        """.trimIndent())
        assertNotNull(e, "scale resolves 'out=Int; annotating Str must be rejected")
    }

    @Test
    fun `multi-param ability constraint is kept when nothing pins the args`() {
        // When BOTH the receiver and the extra arg come from params (nothing
        // concrete), the constraint is preserved on the signature and the
        // associated type shows as a variable — the fully-generic shape.
        val t = typeOf("sc2", """
            ability Scale 'by for 'a having 'out where {
              scale: { it: 'a, by: 'by } -> 'out
            }
            impl Scale Int for Int having 'out = Int where {
              scale = { it, by } -> it
            }
            pub let sc2 = { x, y } -> x.scale { by = y }
        """.trimIndent())
        assertNotNull(t, "sc2 should typecheck")
        assertTrue(t!!.contains("Scale"), "expected a Scale constraint in the with-clause, got: $t")
        assertTrue(Regex("""having 'out = '\w+""").containsMatchIn(t),
            "expected `having 'out = <var>` on the generic signature, got: $t")
    }

    /**
     * When a NON-receiver arg is pinned to a concrete type (`by = 3` ⇒ 'by =
     * Int) and exactly ONE impl matches those pinned args, bidirectional
     * inference resolves the receiver too (`x : Int`) and the associated type
     * to its concrete binding (`'out = Int`). This is sound under strict
     * coherence — `impl Scale Int for Int` is the only inhabitant, so `Int` is
     * the only valid receiver — and is the same mechanism codec dispatch relies
     * on (`Decode Json 'a` infers `'a` from the single format impl).
     */
    @Test
    fun `multi-param ability with a pinned arg resolves the receiver and associated type`() {
        val t = typeOf("sc", """
            ability Scale 'by for 'a having 'out where {
              scale: { it: 'a, by: 'by } -> 'out
            }
            impl Scale Int for Int having 'out = Int where {
              scale = { it, by } -> it
            }
            pub let sc = { x } -> x.scale { by = 3 }
        """.trimIndent())
        assertNotNull(t, "sc should typecheck")
        // 'out resolves to Int (the impl's `having 'out = Int`), so the return is Int.
        assertTrue(t!!.contains("Int"),
            "pinned-arg single-impl should resolve 'out=Int in the return, got: $t")
    }

    // ─── the surviving `.try {}` associated-type ability ─────────────────

    @Test
    fun `explicit try dispatches and its inner associated type resolves`() {
        // `Tryable for 'm having 'inner`. `.try {}` on a concrete Result
        // unwraps to the inner value inside a perform ExceptionToResult.
        assertNull(
            err("""
                use std::effects::exception::{ ExceptionToResult }
                pub let unwrap = { a: Result Int Str } -> perform ExceptionToResult in do
                  let x = a.try {}
                  Ok { val = x }
            """.trimIndent()),
            "explicit .try {} on Result must typecheck (Tryable having 'inner resolves)"
        )
    }

    // ─── chained calls: each link resolves its own associated type ───────

    @Test
    fun `chained ability calls resolve associated types left-to-right`() {
        // `(5).produce{}` → Str, then `.consume{}` on Str → Int.
        val t = typeOf("chain", """
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            ability Consumer for 'a having 'res where {
              consume: { it: 'a } -> 'res
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            impl Consumer for Str having 'res = Int where {
              consume = { it } -> 0
            }
            pub let chain = {} -> (5).produce {}.consume {}
        """.trimIndent())
        assertNotNull(t, "chain should typecheck")
        assertTrue(t!!.contains("Int"),
            "chained resolution Int→Str→Int should end at Int, got: $t")
    }
}
