package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * B1 — having-out generalization soundness.
 *
 * `pub let f = { x } -> x + 0` used to generalize with the RESULT frozen to `Int`
 * (the concrete `0` right-operand drives `NumAdd Int … having 'out = Int`) while
 * the receiver `x` stayed polymorphic. `f { x = 5.0 }` then typed as `Int` even
 * though `5.0 + 0` selects `NumAdd Int for Float having 'out = Float` and RUNS as
 * Float — a static-type / runtime-value mismatch (the interpreter returns a
 * `Double`, verified directly).
 *
 * ROOT CAUSE: `AbilityChecker.solveAssociatedTypeResolution` fell back to a
 * receiver-only impl match (`findMatchingAbilityImpl`) whenever the full class-arg
 * tuple wasn't concrete. For `x + 0` the receiver `'l` is still open, so the
 * fallback matched an impl against the co-operand `'r = Int` and froze `'out := Int`
 * into f's generalized scheme, where no call site could revise it.
 *
 * FIX: resolve `having 'out` only from a fully-concrete class-arg tuple
 * (`findMatchingAbilityImplByArgs`). When the tuple isn't concrete, DEFER keyed on
 * the still-open class-arg vars so the obligation survives generalization and
 * re-resolves at each call site once the receiver is pinned.
 */
class HavingOutGeneralizationSoundnessTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun typeOf(src: String, name: String): MezzeType? =
        tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            .getVariableType(name)

    private fun caught(src: String): TypeCheckError? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e }

    @Test fun `f x plus 0 applied to a Float is Float not Int`() {
        val src = """
            pub let f = { x } -> x + 0
            let r = f { x = 5.0 }
            r
        """
        assertNull(caught(src), "f { x = 5.0 } must type-check")
        val t = typeOf(src, "r")
        assertFalse(t is MezzeType.IntType, "f { x = 5.0 } must NOT be Int (runs as Float); got $t")
        assertTrue(t is MezzeType.FloatType, "f { x = 5.0 } should resolve to Float; got $t")
    }

    @Test fun `f x plus 0 applied to an Int is Int`() {
        val src = """
            pub let f = { x } -> x + 0
            let r = f { x = 5 }
            r
        """
        assertNull(caught(src), "f { x = 5 } must type-check")
        assertTrue(typeOf(src, "r") is MezzeType.IntType, "f { x = 5 } should resolve to Int")
    }

    @Test fun `direct homogeneous arithmetic still resolves concretely`() {
        assertTrue(typeOf("let r = 5 + 0\nr", "r") is MezzeType.IntType, "5 + 0 is Int")
        assertTrue(typeOf("let r = 5.0 + 0.0\nr", "r") is MezzeType.FloatType, "5.0 + 0.0 is Float")
    }
}
