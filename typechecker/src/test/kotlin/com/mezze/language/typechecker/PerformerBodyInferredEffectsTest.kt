package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Pins `performerAddedEffects` enrichment from INFERRED impl-operation bodies
 * (task #44 / #274). The map is SEEDED statically in ContextBuilder from each
 * op's declared effect row (ContextBuilder.kt ~197), but the load-bearing
 * enrichment happens downstream: ImplValidator merges each op body's *inferred*
 * effect row into the same live `performerAddedEffects` map
 * (ImplValidator.kt ~611), and ExprPerformConstraints reads that map to decide
 * whether a `perform` transitively needs an effect (ExprPerformConstraints.kt
 * ~89). These tests lock in that body-inferred path so a future refactor of the
 * decl-processing order can't silently regress it.
 *
 * NOTE (scoping, see project_tc_audit_2026-08): the body-inference itself already
 * works. The genuine residual is ORDER-INDEPENDENCE — performer decls are not
 * reordered ahead of their consumers by dependencyOrderedDecls, so a `perform`
 * declared before the performer relies on source order. That restructure is
 * folded into the effect-row soundness effort, not this test.
 */
class PerformerBodyInferredEffectsTest {

    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        tc = TypeChecker()
    }

    /** Returns the first error message, or null if the program type-checks. */
    private fun err(src: String): String? =
        try {
            tc.typeCheck(parser.parseParsedModule(src, "t.mz"), Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) {
            e.message
        }

    @Test
    fun `performer op body performing a second effect is tracked as an added effect`() {
        // The `Logger` performer's `log` op body performs the `Bare` effect via
        // its own performer `BarePerformer`. That `Bare` effect is NOT in the
        // Logger effect's declared row — it must be discovered from the op BODY
        // and recorded as an added effect of the `Logger` performer, so a
        // `perform Logger` block that reaches `log` is understood to also touch
        // Bare (and the whole program only fully discharges once Bare is handled).
        val e = err(
            """
            effect Bare  where { bare : { msg: Str } -> {} <Bare> }
            effect Logs  where { logg : { msg: Str } -> {} <Logs> }

            impl Bare for BarePerformer where {
                bare = { msg } -> {}
            }
            impl Logs for Logger where {
                logg = { msg } -> perform BarePerformer in Bare.bare { msg = msg }
            }

            pub let run = {} -> perform Logger in Logs.logg { msg = "hi" }
            """.trimIndent()
        )
        assertNull(e, "body-inferred Bare effect on the Logger performer should let the program check; got: $e")
    }

    @Test
    fun `pure-bodied performer keeps a genuinely pure perform pure`() {
        // Control: a performer whose op body performs nothing must NOT gain a
        // spurious added effect. `perform PureP in nop {}` stays clean.
        val e = err(
            """
            effect Nop where { nop : {} -> {} <Nop> }

            impl Nop for PureP where {
                nop = {} -> {}
            }

            pub let run = {} -> perform PureP in Nop.nop {}
            """.trimIndent()
        )
        assertNull(e, "pure performer body should keep the perform pure; got: $e")
    }
}
