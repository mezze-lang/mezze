package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #202: the hermetic rule (Mut must be the only effect in scope, to prevent data
 * races) must hold for a function's INFERRED effect row, not just for an
 * annotated one. An unannotated fn that performs Mut inside a spawned/joined
 * callback gets an inferred `{Mut, Parallel}`/`{Mut, Async}` row that used to
 * bypass the check (which only ran during annotation conversion) — a soundness
 * hole. The check now also runs on the solved binding type in TypeValidator.
 *
 * Discharging Mut (`perform LocalMut in …`) removes it from the outer row, so a
 * discharged-Mut-then-other-effect program stays accepted.
 */
class HermeticMutInferredSoundnessTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    // ── the hole: Mut laundered through an inferred row must be REJECTED ──

    @Test fun `annotated Mut plus Parallel is rejected`() {
        val e = err("""
            use std::effects::mutation::{ Mut }
            use std::effects::parallel::{ Parallel }
            pub let f : {} -> Int <Mut, Parallel> = {} ->
              Mut.mutate { act = {} -> 1 }
        """)
        assertNotNull(e, "annotated <Mut, Parallel> must be rejected (hermetic)")
        assertTrue(e!!.contains("hermetic"), "error should mention hermetic: $e")
    }

    @Test fun `inferred Mut inside a Parallel-join callback is rejected`() {
        val e = err("""
            use std::effects::mutation::{ Mut }
            use std::effects::parallel::{ Parallel }
            pub let f = {} ->
              Parallel.join {
                l = {} -> Mut.mutate { act = {} -> 1 },
                r = {} -> 2
              }
        """)
        assertNotNull(e, "#202: inferred {Mut, Parallel} must be rejected")
        assertTrue(e!!.contains("hermetic"), "error should mention hermetic: $e")
    }

    // ── still accepted: legitimate Mut uses ──────────────────────────────

    @Test fun `a pure Mut function is accepted`() {
        assertNull(err("""
            use std::effects::mutation::{ Mut }
            pub let f = {} -> Mut.mutate { act = {} -> 1 }
        """), "a Mut-only fn is fine")
    }

    @Test fun `Mut discharged before another effect is accepted`() {
        assertNull(err("""
            use std::effects::mutation::{ Mut, LocalMut }
            use std::effects::log
            pub let f = {} -> do
              let x = perform LocalMut in Mut.mutate { act = {} -> 1 }
              log::print { msg = "done" }
              x
        """), "Mut handled before the Log scope — must stay accepted")
    }
}
