package com.mezze.language.typechecker

import com.mezze.language.parsedast.DeclIdentity
import com.mezze.language.parsedast.IdentityScope
import com.mezze.language.parsedast.ImplClassifier
import com.mezze.language.parsedast.ParsedImplAbilityDecl
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.parsedast.ParsedPerformerDecl
import com.mezze.language.parsedast.identity
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * #3b — the reclassification `ModuleResolver.fromSnapshot` applies so a stdlib
 * module loaded from the snapshot gets its IMPORTED-ability-impl performers
 * turned into ability impls (otherwise `std::codec::query`'s `EncodeRecord for
 * QueryC` etc. stay performers and `Encode QueryC` can't derive through
 * `with_query`).
 *
 * The full scenario only reproduces against the real snapshot (the test suites
 * force-recompile the stdlib, so they never hit `fromSnapshot`); that end is
 * guarded by the `native-server-smoke` fixture build. This pins the reclassify
 * LOGIC directly, on a STAMPED module (as the snapshot's is): a performer whose
 * ref names an ability becomes an impl, an effect handler does not, and the
 * converted impl keeps the performer's identity (so the module fingerprint,
 * which hashes decl identities, is unchanged).
 */
class QueryCodecDerivationTest {
    private val parser = MezzeParser()

    /** Stamped, as a module loaded from the snapshot is. */
    private fun stamped(src: String): ParsedModule =
        DeclIdentity.stamp(parser.parseParsedModule(src.trimIndent(), "t.mz"), IdentityScope.NONE, "t")

    private fun implCount(m: ParsedModule, abilities: Set<String>, effects: Set<String>): Int =
        ImplClassifier.reclassifyImportedAbilityImpls(m, abilities, effects)
            .decls.count { it is ParsedImplAbilityDecl }

    private val src = "impl Format for QueryC where { encode = { it } -> it }"

    /** Parsed in isolation, `impl Format for QueryC` is a performer, not an impl. */
    @Test fun `an impl of a non-local name is a performer before reclassification`() {
        val m = stamped(src)
        assertEquals(0, m.decls.count { it is ParsedImplAbilityDecl })
        assertEquals(1, m.decls.count { it is ParsedPerformerDecl })
    }

    /** When the ref names an ability (as `Format` does once imports are known), it reclassifies. */
    @Test fun `a performer whose ref names an ability becomes an ability impl`() {
        assertEquals(1, implCount(stamped(src), abilities = setOf("Format"), effects = emptySet()))
    }

    /** An effect name always wins — a handler stays a handler. */
    @Test fun `a performer whose ref names an effect stays a performer`() {
        assertEquals(0, implCount(stamped(src), abilities = setOf("Format"), effects = setOf("Format")))
    }

    /** Unknown ref → unchanged (no false reclassification). */
    @Test fun `a performer whose ref is neither ability nor effect is left alone`() {
        assertEquals(0, implCount(stamped(src), abilities = emptySet(), effects = emptySet()))
    }

    /** Identity is preserved: the impl carries the performer's identity. */
    @Test fun `reclassification preserves the performer's identity`() {
        val m = stamped(src)
        val perf = m.decls.filterIsInstance<ParsedPerformerDecl>().single()
        val out = ImplClassifier.reclassifyImportedAbilityImpls(m, setOf("Format"), emptySet())
        val impl = out.decls.filterIsInstance<ParsedImplAbilityDecl>().single()
        assertEquals(perf.identity, impl.identity, "the converted impl must keep the performer's identity")
    }
}
