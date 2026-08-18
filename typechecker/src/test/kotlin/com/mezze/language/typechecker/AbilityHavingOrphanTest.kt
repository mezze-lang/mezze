package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

/**
 * Cross-module behaviour for abilities that carry an ASSOCIATED TYPE (`having`).
 * [AbilityOrphanRulesTest] covers plain abilities; this adds the `having` cases:
 *   - an `impl … having 'out = T` in the ability's OR the type's module is legal,
 *   - resolving the associated type at a call site in a THIRD module works,
 *   - an ORPHAN `impl … having …` (owning neither ability nor type) is rejected.
 *
 * Multi-module is simulated the same way as AbilityOrphanRulesTest: write each
 * `.mz` into a temp dir and type-check with that dir as the resolution root, so
 * `use other_mod::{ … }` resolves `other_mod.mz`.
 */
class AbilityHavingOrphanTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker
    private lateinit var dir: Path

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
        dir = Files.createTempDirectory("mezze_having_orphan")
    }

    private fun mod(name: String, src: String) {
        Files.writeString(dir.resolve("$name.mz"), src.trimIndent())
    }
    /** Type-check a written module with `dir` as the module root. */
    private fun checkErr(name: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(Files.readString(dir.resolve("$name.mz")), "$name.mz"), dir); null }
        catch (e: TypeCheckError) { e.message }

    // ── legal: impl-with-having in the TYPE's module (imports the ability) ──

    @Test
    fun `having impl in the type's module is legal and resolves cross-module`() {
        mod("prod_ab", "ability Producer for 'a having 'out where { produce: { it: 'a } -> 'out }")
        // Widget's module owns the type, imports the ability, writes the impl —
        // legal under orphan rules (impl is with a target type).
        mod("widget", """
            use prod_ab::{ Producer }
            pub type Widget = { id: Int }
            impl Producer for Widget having 'out = Str where { produce = { it } -> "w" }
            pub let mk = { n: Int } -> { id = n }
        """)
        // Third module: use both, call produce, rely on the cross-module having.
        mod("user", """
            use prod_ab::{ Producer }
            use widget::{ Widget, mk }
            pub let f = {} -> (mk { n = 5 }).produce {}
        """)
        assertNull(checkErr("widget"), "the having impl in the type's module must typecheck")
        assertNull(checkErr("user"), "produce must resolve its associated type across modules")
    }

    // ── legal: impl-with-having in the ABILITY's module ─────────────────────

    @Test
    fun `having impl in the ability's module is legal`() {
        // Ability module owns the ability and writes an impl for a built-in —
        // legal (impl is with the ability).
        mod("show_ab", """
            ability Producer for 'a having 'out where { produce: { it: 'a } -> 'out }
            impl Producer for Int having 'out = Str where { produce = { it } -> "n" }
        """)
        mod("user2", """
            use show_ab::{ Producer }
            pub let f = {} -> (5).produce {}
        """)
        assertNull(checkErr("show_ab"), "impl in the ability's module must typecheck")
        assertNull(checkErr("user2"), "the imported Int impl must resolve")
    }

    // ── illegal: ORPHAN impl-with-having ────────────────────────────────────

    @Test
    fun `orphan having impl in a third module is rejected`() {
        mod("prod_ab2", "ability Producer for 'a having 'out where { produce: { it: 'a } -> 'out }")
        mod("gtype", "pub type Gadget = { n: Int }")
        // Owns NEITHER Producer NOR Gadget → orphan.
        mod("orphan", """
            use prod_ab2::{ Producer }
            use gtype::{ Gadget }
            impl Producer for Gadget having 'out = Int where { produce = { it } -> 0 }
        """)
        val e = assertThrows<TypeCheckError> {
            tc.typeCheck(parser.parseParsedModule(Files.readString(dir.resolve("orphan.mz")), "orphan.mz"), dir)
        }
        assertTrue(
            e.message?.contains("Orphan", ignoreCase = true) == true ||
            e.message?.contains("must be in the same module") == true,
            "orphan having-impl should be rejected with an orphan message, got: ${e.message}"
        )
    }
}
