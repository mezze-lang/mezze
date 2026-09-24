package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bool has ONE representation, whichever way a program arrives at it.
 *
 * It used to have four at once: a dedicated `MezzeType.BoolType` object, a
 * `| True, False |` variant with BUILTIN identity, the same variant with DECLARED
 * identity (from the synthetic `type Bool` that `ContextBuilder` injects so `True`
 * and `False` resolve), and an identity-less one. Nothing chose between them —
 * unification carried hand-written bridges in both directions, `MonoTypeOps` carried
 * two more so ability dispatch on a Bool constant found its impl, `PerformValidator`
 * two more again, and the error printer carried a `sameTypeDifferentRepresentation`
 * check whose absence made `f = { c: Bool, b } -> …` report "Field `c` expects Bool
 * but got Bool" and stop before the field that actually failed.
 *
 * Measured at the widest of those bridges (`sameBuiltinSeenBothWays`): 1767 Bool
 * pairs across the typechecker suite, now zero.
 *
 * The rule this pins is not "Bool is a variant" — that is visible in the source. It
 * is that every route to Bool lands on the SAME VALUE, so `==` decides it and no
 * bridge has to.
 */
class BoolSingleRepresentationTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
    }

    private fun typeOf(source: String, binding: String): MezzeType? {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        return TypeChecker()
            .typeCheck(parser.parseParsedModule(source.trimIndent(), "bool.mz"), Paths.get("."))
            .getVariableType(binding)
    }

    @Test
    fun `the canonical Bool is a variant with builtin identity`() {
        val bool = PrimitiveTypes.BOOL_TYPE
        assertEquals(mapOf("True" to null, "False" to null), bool.constructors)
        assertEquals(null, bool.rowVar, "Bool is CLOSED — a match on it is exhaustive at True and False")
        assertEquals(
            TypeIdentity.Builtin(PrimitiveTypes.BOOL), bool.identity,
            "BUILTIN, not DECLARED: a declared identity carries a content hash, and names " +
                "are hashed as-written, so a user's own `type Bool` would hash to the same " +
                "thing and silently become this one"
        )
    }

    @Test
    fun `a constructor and an annotation reach the same value`() {
        // The two routes that used to disagree: `True` was solved to a variant,
        // `: Bool` was read from PrimitiveTypes as the primitive object.
        val inferred = typeOf("let b = True\nb", "b")
        val annotated = typeOf("let b: Bool = True\nb", "b")
        assertEquals(PrimitiveTypes.BOOL_TYPE, inferred, "`let b = True` did not infer the canonical Bool")
        assertEquals(PrimitiveTypes.BOOL_TYPE, annotated, "`let b: Bool` did not resolve to the canonical Bool")
        assertEquals(inferred, annotated)
    }

    @Test
    fun `a comparison, a match and a record field all reach it too`() {
        assertEquals(PrimitiveTypes.BOOL_TYPE, typeOf("let b = 1 == 1\nb", "b"), "from a comparison")
        assertEquals(
            PrimitiveTypes.BOOL_TYPE,
            typeOf("let b = match True is\n  True -> False\n  False -> True\nb", "b"),
            "from a match over Bool"
        )
        val rec = typeOf("let r = { flag = True }\nr", "r")
        assertEquals(
            PrimitiveTypes.BOOL_TYPE, (rec as? MezzeType.RecordType)?.fields?.get("flag"),
            "from a record field"
        )
    }

    @Test
    fun `Bool still pattern-matches on both constructors`() {
        // The half of the collapse that is user-visible: Bool is a VARIANT, so
        // `True`/`False` are constructors and a `when` over them is checked for
        // exhaustiveness off the two arms — no declaration lookup involved.
        val t = typeOf(
            """
            let describe = { b: Bool } -> match b is
              True -> 1
              False -> 0
            let r = describe { b = True }
            r
            """,
            "r"
        )
        assertEquals(MezzeType.IntType, t, "a total match over Bool's two constructors should typecheck")
    }

    @Test
    fun `a match missing one of Bool's constructors is rejected`() {
        // The control. Without it the test above passes against a checker that
        // never looks at Bool's arms at all.
        val thrown = runCatching {
            typeOf(
                """
                let describe = { b: Bool } -> match b is
                  True -> 1
                let r = describe { b = True }
                r
                """,
                "r"
            )
        }.exceptionOrNull()
        assertNotNull(thrown, "a match on Bool covering only `True` was accepted as exhaustive")
        assertTrue(
            thrown.message?.contains("False") == true,
            "the rejection should name the uncovered constructor: ${thrown.message}"
        )
    }

    @Test
    fun `expanding Bool's declaration yields the canonical Bool, not a declared one`() {
        // `ContextBuilder` injects a `type Bool = | True, False |` so that `True`
        // resolves as a constructor. Expanding THAT would stamp Bool with the
        // declaration's content hash — a second Bool, which is what unification's
        // name bridge existed to paper over. This is the route that produced 90 of
        // those bridges in a four-class sample before it was closed.
        val expanded = com.mezze.language.typechecker.solving.expandTypeApplicationToVariant(
            MezzeType.TypeApplication(PrimitiveTypes.BOOL, emptyList()),
            com.mezze.language.typechecker.context.DeclarationRegistry.EMPTY,
        )
        assertEquals(PrimitiveTypes.BOOL_TYPE, expanded)
        assertTrue(
            expanded!!.identity is TypeIdentity.Builtin,
            "expanding Bool handed back a ${expanded.identity::class.simpleName} identity"
        )
    }
}
