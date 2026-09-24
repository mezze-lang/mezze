package com.mezze.language.typechecker

import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An OPEN record demand must not be satisfied by a value that lacks its fields.
 *
 * Symmetric record unification cannot reject this on its own: with `{ x, ... }`
 * meeting `{ y, ... }`, each side's row variable happily absorbs the other's
 * field, and both sides come out as `{ x, y, ... }`. The demand is satisfied by
 * inventing the field rather than by finding it — the launder.
 *
 * `unifyFunctions` closes it at the one site that knows which side is the DEMAND
 * (the callee's parameter) and which is the VALUE (the argument), via
 * `directionalParamFieldCheck`. These tests exist to find whether the same shape
 * is reachable through a path that has no such direction.
 */
class OpenRecordDemandLaunderTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
    }

    /** Null when the program typechecks; otherwise the rejection message. */
    private fun check(src: String): String? {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        return try {
            TypeChecker().typeCheck(parser.parseParsedModule(src.trimIndent(), "launder.mz"), Paths.get("."))
            null
        } catch (e: Throwable) {
            e.message ?: e::class.simpleName ?: "rejected"
        }
    }

    private fun assertRejected(label: String, src: String) {
        val msg = check(src)
        if (msg == null) {
            fail("$label: an open record demand was satisfied by a value that lacks its field")
        }
    }

    // ---- the covered path: a call argument ----

    @Test
    fun `an open argument missing the parameter's field is rejected`() {
        assertRejected(
            "call argument", """
            let needs_y = { r: { y: Int, ... } } -> r.y
            let gives_x = { r } -> needs_y { r = r } && r.x
            let main = {} -> gives_x { r = { x = 1, y = 2 } }
        """
        )
    }

    @Test
    fun `a nested open argument missing an inner field is rejected`() {
        assertRejected(
            "nested call argument", """
            let needs_inner = { r: { inner: { y: Int, ... }, ... } } -> r.inner.y
            let gives_other = { r } -> needs_inner { r = r } + r.inner.x
            let main = {} -> gives_other { r = { inner = { x = 1, y = 2 } } }
        """
        )
    }

    // ---- paths with no caller/callee direction ----

    @Test
    fun `a closed value missing an open annotation's field is rejected`() {
        // No direction needed: one side is closed, so symmetric unification can
        // see the field is absent and cannot invent it.
        assertRejected(
            "annotation vs closed value", """
            let mk = {} -> { a = 1 }
            let x : { b: Int, ... } = mk {}
            let main = {} -> x
        """
        )
    }

    @Test
    fun `an open value returned where an open annotation demands another field is rejected`() {
        // The return position has a direction too (the annotation is the demand),
        // but it does not go through unifyFunctions' parameter check.
        assertRejected(
            "return annotation", """
            let passthrough : { r: { x: Int, ... } } -> { y: Int, ... } = { r } -> r
            let main = {} -> passthrough { r = { x = 1, y = 2 } }
        """
        )
    }

    // ---- the diagnostic must name the field that actually failed ----

    @Test
    fun `a Bool field is not blamed for another field's mismatch`() {
        // `Bool` exists as PrimitiveTypes.BOOL_TYPE and as VariantType({True, False}).
        // They are unequal as DATA and identical as TYPES, so the walk that looks
        // for "the first field where actual != expected" used to stop at `c` and
        // report "Field `c` expects Bool but got Bool" — a sentence nobody can act
        // on — while never mentioning b's unexpected `y`, which is the real
        // problem. The same call with `c: Int` always reported it correctly.
        val msg = check(
            """
            let f = { c: Bool, b } -> if c then b.x else 0
            let main = {} -> f { c = True, b = { x = 2, y = 3 } }
        """
        )
        assertTrue(msg != null, "expected a rejection for b's extra field")
        assertFalse(
            msg!!.contains("expects Bool but got Bool"),
            "the diagnostic blames a Bool field and prints the same type twice: $msg"
        )
        assertTrue(
            msg.contains("y"),
            "the diagnostic never mentions `y`, which is the field that actually failed: $msg"
        )
    }

    @Test
    fun `the same shape with a non-Bool field reports the same thing`() {
        // The control: nothing about Bool should change WHICH field is reported.
        val msg = check(
            """
            let f = { c: Int, b } -> if c > 0 then b.x else 0
            let main = {} -> f { c = 1, b = { x = 2, y = 3 } }
        """
        )
        assertTrue(msg != null && msg.contains("y"), "expected the `y` mismatch: $msg")
    }
}
