package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Real assertions distilled from the exploratory unification audit (formerly a
 * println-only probe). The focus is the BOTH-OPEN VARIANT ARITY hole
 * (audit #258 / #7): in `unifyVariants` the both-open branch
 * (`var1.rowVar != null && var2.rowVar != null`) only unifies a shared
 * constructor's payloads when BOTH sides carry a payload
 * (`if (dataType1 != null && dataType2 != null)`). When the SAME constructor is
 * nullary on one inferred open variant and payload-bearing on the other, the
 * arity disagreement is silently dropped and the two variants unify — a
 * soundness hole.
 *
 * These are source-level reproducers: anonymous (undeclared) constructors infer
 * to OPEN variants (see OpenVariantUnificationTest `anonymous constructor
 * creates open variant`), and merging two such variants — via if/then/else or a
 * generic function that unifies its two args — drives them through the both-open
 * branch. We assert the SOUND behaviour (rejection) and mark the ones the buggy
 * checker still accepts today with EXPECTED-CORRECT so the orchestrator can
 * @Disabled-tag them until the fix lands.
 *
 * Direct Unification unit tests are intentionally avoided: `unifyVariants` is
 * `internal` and needs a real `DeclarationRegistry`, whose construction we
 * cannot verify from source without guessing. Source programs pin the same path
 * with a stable public API.
 *
 * NOTE (occurs-check-in-suspend): the audit found the suspended-type occurs
 * check has no observable exploit — the cycle guard defuses it before a bad type
 * escapes — so there is no honest source-level reproducer to assert on. We
 * deliberately do not fabricate one here.
 */
class AuditUnifProbeTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try {
            tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) { e.message }

    // ── BOTH-OPEN ARITY HOLE (audit #258 / #7) ──────────────────────────────

    // EXPECTED-CORRECT: fails today under bug #258.
    // `Foo` (nullary) and `Foo { x = 1 }` (payload-bearing) are BOTH anonymous
    // constructors, so each side infers to an OPEN variant. `if/then/else` merges
    // them, driving the two open variants through the both-open branch of
    // `unifyVariants`, where the shared `Foo` has dataType==null on the then-side
    // and dataType!=null on the else-side. A sound checker must reject: a single
    // constructor cannot be simultaneously nullary and payload-bearing.
    @Test fun `both-open merge of nullary vs payload Foo is rejected`() {
        val e = err("""
            let mk = { b: Bool } ->
                if b
                then Foo
                else Foo { x = 1 }
        """)
        assertNotNull(e, "nullary `Foo` unified with payload `Foo { x = 1 }` must be rejected")
        assertTrue(
            e!!.contains("Foo") || e.contains("payload") || e.contains("arity") ||
                e.contains("mismatch") || e.contains("constructor", ignoreCase = true),
            "message should mention the conflicting constructor / arity: $e"
        )
    }

    // EXPECTED-CORRECT: fails today under bug #258.
    // Same hole reached through a generic function that UNIFIES its two args:
    // `pick`'s `a` and `b` share one type variable, so passing a nullary `Bar`
    // and a payload `Bar { n = 1 }` forces the two inferred open variants to unify
    // on the shared `Bar` constructor at conflicting arity. Sound = reject.
    @Test fun `both-open unify via shared type var at conflicting arity is rejected`() {
        val e = err("""
            let pick = { a: 'a, b: 'a } -> a
            let r = pick { a = Bar, b = Bar { n = 1 } }
        """)
        assertNotNull(e, "unifying nullary `Bar` with payload `Bar { n = 1 }` through 'a must be rejected")
        assertTrue(
            e!!.contains("Bar") || e.contains("payload") || e.contains("arity") ||
                e.contains("mismatch") || e.contains("constructor", ignoreCase = true),
            "message should mention the conflicting constructor / arity: $e"
        )
    }

    // EXPECTED-CORRECT: fails today under bug #258.
    // The reverse ordering (payload on the then-branch, nullary on the else) must
    // be rejected too — the both-open branch is symmetric, so a sound checker
    // cannot depend on which side carries the payload.
    @Test fun `both-open merge payload-then-nullary Foo is rejected`() {
        val e = err("""
            let mk = { b: Bool } ->
                if b
                then Foo { x = 1 }
                else Foo
        """)
        assertNotNull(e, "payload `Foo { x = 1 }` unified with nullary `Foo` must be rejected (symmetric)")
    }

    // ── CONTROLS: same arity across two open variants unifies fine ──────────

    // Two independently-inferred open variants whose SHARED constructor is nullary
    // on BOTH sides unify cleanly — this is the legitimate open-variant merge and
    // must keep type-checking. (`Foo`/`Foo` nullary, plus a distinct arm on each
    // branch so both stay genuinely open multi-constructor unions.)
    @Test fun `both-open merge of matching nullary constructors is accepted`() {
        assertNull(err("""
            let mk = { b: Bool } ->
                if b
                then Foo
                else Bar
        """))
    }

    // Shared constructor with the SAME payload shape on both sides unifies fine:
    // `Foo { x = 1 }` on both branches — matching arity AND matching payload type.
    @Test fun `both-open merge of matching payload constructors is accepted`() {
        assertNull(err("""
            let mk = { b: Bool } ->
                if b
                then Foo { x = 1 }
                else Foo { x = 2 }
        """))
    }

    // Control via the generic-unify path: same nullary constructor through the
    // shared type variable is accepted.
    @Test fun `both-open unify via shared type var at matching nullary arity is accepted`() {
        assertNull(err("""
            let pick = { a: 'a, b: 'a } -> a
            let r = pick { a = Bar, b = Bar }
        """))
    }

    // Control: same constructor, same payload type, through the shared type var.
    @Test fun `both-open unify via shared type var at matching payload is accepted`() {
        assertNull(err("""
            let pick = { a: 'a, b: 'a } -> a
            let r = pick { a = Bar { n = 1 }, b = Bar { n = 2 } }
        """))
    }

    // ── SANITY: nullary-vs-payload conflict is caught for a DECLARED type ────

    // When the SAME conflict is expressed against a nominally declared variant,
    // the checker already rejects it (bare `Origin` where a payload is required,
    // and payload where none is declared). This anchors the "correct" behaviour
    // the both-open branch above should mirror: arity for a constructor is fixed.
    @Test fun `declared variant rejects payload on a nullary constructor`() {
        val e = err("""
            type Point = | Point2D { x: Int, y: Int }, Origin |
            let p = Origin { x = 1 }
        """)
        assertNotNull(e, "supplying a payload to the nullary `Origin` constructor must be rejected")
    }

    @Test fun `declared variant rejects bare use of a payload constructor`() {
        val e = err("""
            type Point = | Point2D { x: Int, y: Int }, Origin |
            let p = Point2D
        """)
        assertNotNull(e, "using payload-bearing `Point2D` bare must be rejected")
    }
}
