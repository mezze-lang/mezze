package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An ability is identified by its DECLARATION, not by its spelling.
 *
 * Two modules may each declare `ability Show for 'a`. They are different
 * abilities: implementing one says nothing about the other. This used to be
 * ACCEPTED — a value implementing `show_b::Show` discharged a demand for
 * `show_a::Show`, with no diagnostic, and the call then dispatched to an
 * operation whose declaration the caller had never seen (BUG 24).
 *
 * The identity was being dropped and then GUESSED BACK, in two independent
 * places, and closing either one alone changed nothing:
 *
 *  - The DEMAND side. `use_a`'s constraint is stamped correctly at its own
 *    declaration (`Show/56e38553`) and survives import intact — verified. But
 *    generalization converted it to a scheme `AbilityConstraint` that had no hash
 *    field, `instantiate()` produced a `FreshAbilityConstraint` that had none
 *    either, and the call site then re-resolved the NAME in the importing
 *    module's ability table. Where that spelling meant `show_b::Show`, the demand
 *    came back as `a070db73`. All three records now carry the hash.
 *
 *  - The IMPL side. An imported impl's ability was resolved by name in the
 *    IMPORTER's table, which answers null when the importer never imported that
 *    ability. `show_b`'s impl arrived untagged, so the name channel handed it to
 *    a demand for a different `Show`. Identity is now resolved in the impl's OWN
 *    module, where the name is unambiguous, and the name channel may only supply
 *    impls that do not claim a DIFFERENT ability.
 *
 * Both halves are deliberately conservative about what they do NOT know. An impl
 * whose module merely imported the ability stays untagged and remains reachable
 * by name, and a constraint that never had a hash still falls back to the table —
 * because narrowing those was measured to break `Default.default` at Int and at
 * Bool, twice.
 *
 * `DeclarationRegistry` still keys abilities by name, and that remains the reason
 * a bare name cannot distinguish the two. What changed is that nothing on this
 * path asks it to: the identity travels with the constraint and with the impl.
 */
class AbilityIdentityCrossModuleTest {
    private lateinit var parser: MezzeParser
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        tempDir = Files.createTempDirectory("mezze_ability_identity")
        // Two INDEPENDENT abilities that happen to share a name.
        Files.writeString(tempDir.resolve("show_a.mz"), """
            ability Show for 'a where { render: {it: 'a} -> Str }
            pub let use_a = {x} -> Show.render {it = x}
        """.trimIndent())
        Files.writeString(tempDir.resolve("show_b.mz"), """
            ability Show for 'a where { render: {it: 'a} -> Int }
            pub type B = | MkB |
            impl Show for B where { render = {it} -> 1 }
        """.trimIndent())
        Files.writeString(tempDir.resolve("plain.mz"), "pub type C = | MkC |")
    }

    private fun check(src: String) =
        TypeChecker().typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), tempDir, enablePrelude = true)

    @Test
    fun `an impl of a same-named ability from another module does not satisfy the constraint`() {
        val err = try {
            check("""
                use show_a::{use_a}
                use show_b::{B}
                pub let go = {} -> use_a {x = MkB}
            """)
            fail(
                "ACCEPTED: `B` implements show_b::Show, and `use_a` demands show_a::Show — a " +
                    "different declaration that merely shares the name. Nothing in the program " +
                    "implements the ability that was asked for.",
            )
        } catch (e: Throwable) { e }
        assertTrue(
            err.message?.contains("does not implement") == true,
            "expected a missing-impl diagnostic naming the ability, got: ${err.message}",
        )
    }

    @Test
    fun `a type implementing nothing is still rejected`() {
        // The companion to the above: proves the constraint is enforced at all, so
        // the test above cannot pass for the trivial reason that nothing is checked.
        val err = try {
            check("""
                use show_a::{use_a}
                use plain::{C}
                pub let go = {} -> use_a {x = MkC}
            """)
            fail("ACCEPTED: `C` implements no Show at all")
        } catch (e: Throwable) { e }
        assertTrue(
            err.message?.contains("does not implement") == true,
            "expected a missing-impl diagnostic, got: ${err.message}",
        )
    }

    @Test
    fun `the matching impl in the ability's own module is still found`() {
        // And the fix does not over-reject: used from its OWN module's ability, the
        // same impl discharges the constraint.
        Files.writeString(tempDir.resolve("show_b_use.mz"), """
            use show_b::{B}
            pub let use_b = {x: B} -> Show.render {it = x}
        """.trimIndent())
        check("""
            use show_b::{B, Show}
            pub let go = {} -> Show.render {it = MkB}
        """)
    }
}
