package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A method call on a receiver that was EXTRACTED from a record — by field
 * access (`r.n.m {}`), a destructuring `let`, a record-pattern match, or a
 * variant-payload pattern — must carry the same "receiver has this method"
 * requirement onto the extracted field as it does for a directly-bound
 * parameter (`{ y } -> y.m {}` infers `{ y: { m: {} -> 'a } }`).
 *
 * The bug this guards (2026-09-06): the requirement was DROPPED specifically for
 * a KNOWN/resolvable method name (`to_str`, which has real per-type stdlib
 * functions + InspectIR auto-derivation) when the receiver was extracted —
 * `r.n.to_str {}` inferred `{ r: { n: 'a } } -> Str`, leaving `n` unconstrained.
 * A value with no `to_str` (a function) was then accepted and CRASHED at runtime
 * (NativeTypeMismatchException). An UNKNOWN method name (`say_name`) kept the
 * refinement correctly in the same positions, which is the reference behavior.
 *
 * Key discovery captured by these tests: the bug is NOT about extraction in
 * general (say_name extracts fine) and NOT about the specific name `to_str`
 * conceptually — it is the CONCRETE/InspectIR resolution path (taken for a
 * method name that has real candidates) failing to constrain an extracted
 * receiver. So the matrix below is method-name × receiver-position.
 */
class MethodOnExtractedReceiverTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun typeOf(name: String, src: String): String? {
        val r = tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
        return r.getVariableType(name)?.let { prettyPrintType(it) }
    }
    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    // ── reference behavior: a top-level param is constrained (both names) ──────

    @Test fun `to_str on a top-level param constrains it`() {
        val t = typeOf("x", "let x = { y } -> y.to_str {}")
        assertNotNull(t)
        assertTrue(t!!.contains("to_str"),
            "top-level receiver must require to_str; got: $t")
    }

    @Test fun `unknown method on a top-level param constrains it`() {
        val t = typeOf("x", "let x = { y } -> y.say_name {}")
        assertNotNull(t)
        assertTrue(t!!.contains("say_name"),
            "top-level receiver must require say_name; got: $t")
    }

    // ── the core matrix: extracted receiver must be constrained for to_str too ─

    @Test fun `to_str on a field-accessed receiver constrains the field`() {
        val t = typeOf("x", "let x = { r } -> r.n.to_str {}")
        assertNotNull(t)
        // `n` must require to_str — NOT be a bare var. The reference is say_name,
        // which produces `{ r: { n: { say_name: {} -> 'a } } }`.
        assertTrue(t!!.contains("to_str"),
            "extracted field receiver must require to_str, not be unconstrained; got: $t")
    }

    @Test fun `to_str on a let-destructured receiver constrains the field`() {
        val t = typeOf("x", """
            let x = { r } -> do
              let { n } = r
              n.to_str {}
        """)
        assertNotNull(t)
        assertTrue(t!!.contains("to_str"),
            "let-destructured receiver must require to_str; got: $t")
    }

    @Test fun `to_str on a record-pattern-matched receiver constrains the field`() {
        val t = typeOf("x", """
            let x = { r } -> match r is
              { n } -> n.to_str {}
        """)
        assertNotNull(t)
        assertTrue(t!!.contains("to_str"),
            "record-pattern receiver must require to_str; got: $t")
    }

    @Test fun `to_str on a variant-payload receiver constrains the payload field`() {
        val t = typeOf("show", """
            let show = { qty } -> match qty is
              Grams { n } -> n.to_str {}
              other -> "u"
        """)
        assertNotNull(t)
        assertTrue(t!!.contains("to_str"),
            "variant-payload receiver must require to_str; got: $t")
    }

    // ── soundness: a receiver with no to_str is REJECTED in every position ─────

    @Test fun `a function passed as a field-accessed to_str receiver is rejected`() {
        val e = err("""
            let x = { r } -> r.n.to_str {}
            let bad = x { r = { n = ({} -> {}) } }
        """)
        assertNotNull(e, "a function has no to_str — must be a compile error, not a runtime crash")
    }

    @Test fun `a function passed to a let-destructured to_str receiver is rejected`() {
        val e = err("""
            let x = { r } -> do
              let { n } = r
              n.to_str {}
            let bad = x { r = { n = ({} -> {}) } }
        """)
        assertNotNull(e, "function receiver via let-destructure must be rejected")
    }

    @Test fun `a function passed to a record-pattern to_str receiver is rejected`() {
        val e = err("""
            let x = { r } -> match r is
              { n } -> n.to_str {}
            let bad = x { r = { n = ({} -> {}) } }
        """)
        assertNotNull(e, "function receiver via record pattern must be rejected")
    }

    @Test fun `a function passed to a variant-payload to_str receiver is rejected`() {
        val e = err("""
            let show = { qty } -> match qty is
              Grams { n } -> n.to_str {}
              other -> "u"
            let bad = show { qty = Grams { n = ({} -> {}) } }
        """)
        assertNotNull(e, "function receiver via variant payload must be rejected")
    }

    // ── an Int is (correctly) REJECTED, same as a top-level receiver ──────────
    //
    // `.to_str {}` on an unconstrained receiver requires a STRUCTURAL `to_str`
    // method (`{ to_str: {} -> _ }`). An Int does NOT have a `to_str` field — it
    // has `int::to_str` as a free function — so it is rejected, EXACTLY as the
    // top-level `{ x } -> x.to_str {}` then `x = 5` case is rejected. To
    // stringify an Int, call `int::to_str { n = n }` explicitly. These assert the
    // extracted position stays consistent with the top-level one (both reject),
    // rather than the extracted one silently accepting anything (the old bug).

    @Test fun `an Int is rejected for a field-accessed to_str receiver, like top-level`() {
        // Reference: the top-level `{ x } -> x.to_str {}` also rejects x = 5.
        assertNotNull(err("let x = { y } -> y.to_str {}\nlet r = x { y = 5 }"),
            "sanity: top-level to_str rejects an Int")
        assertNotNull(err("""
            let x = { r } -> r.n.to_str {}
            let ok = x { r = { n = 400 } }
        """), "an Int field has no structural to_str — rejected, same as top-level")
    }

    @Test fun `an Int payload is rejected for a variant-pattern to_str receiver`() {
        assertNotNull(err("""
            let show = { qty } -> match qty is
              Grams { n } -> n.to_str {}
              other -> "u"
            let ok = show { qty = Grams { n = 400 } }
        """), "an Int variant payload has no structural to_str — rejected")
    }

    // ── the explicit int::to_str form is how you stringify a concrete Int ─────

    @Test fun `int to_str free function on a payload field is accepted`() {
        assertNull(err("""
            use std::int
            let show = { qty } -> match qty is
              Grams { n } -> int::to_str { n = n }
              other -> "u"
            let ok = show { qty = Grams { n = 400 } }
        """), "int::to_str { n = n } is the explicit, correct way to stringify an Int payload")
    }

    // ── the unknown-name reference cases keep working in extracted positions ──

    @Test fun `unknown method on a field-accessed receiver constrains the field`() {
        val t = typeOf("x", "let x = { r } -> r.n.say_name {}")
        assertNotNull(t)
        assertTrue(t!!.contains("say_name"),
            "reference: unknown method already refines the extracted field; got: $t")
    }
}
