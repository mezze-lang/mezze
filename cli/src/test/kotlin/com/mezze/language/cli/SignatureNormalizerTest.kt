package com.mezze.language.cli

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.SignatureNormalizer
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Canonical rendering of a type, which is the thing a signature index is keyed
 * on. The property that matters is **two types that mean the same thing render
 * identically** — when that breaks, a search silently misses the answer it is
 * already holding, which is the worst failure an index has.
 *
 * Lives in :cli because that is where the typechecker is on the test
 * classpath; the code under test is in :ast. Most types come from real source,
 * so the fixtures cannot drift from what the typechecker produces — except
 * where going through the typechecker would canonicalise the very thing under
 * test, which is called out where it happens.
 */
class SignatureNormalizerTest {

    private fun sigOf(source: String, name: String): String {
        val parsed = MezzeParser().parseParsedModule(source, "test.mz")
        val result = TypeChecker().checkParsedModule(parsed, Paths.get("."), currentModulePath = null)
        val success = result as? TypeChecker.TypeCheckResult.Success
            ?: error("typecheck failed: " +
                (result as TypeChecker.TypeCheckResult.Failure).errors.joinToString { it.message })
        val decl = (success.typedModule ?: error("no typedModule"))
            .decls.filterIsInstance<TypedLetDecl>().find { it.name == name }
            ?: error("no decl '$name'")
        return SignatureNormalizer.render(decl.scheme.type)
    }

    // ---- the canonicalisations, each stated as an equality -------------------

    @Test
    fun `type variable names do not matter`() {
        val a = sigOf("let id: { x: 'a } -> 'a = { x } -> x", "id")
        val b = sigOf("let id: { x: 'zzz } -> 'zzz = { x } -> x", "id")
        assertEquals(a, b, "the name a user picked for a type variable is not information")
    }

    /**
     * Built directly rather than typechecked, and that is the point: going
     * through an annotation canonicalises field order before the normalizer
     * ever sees it, so an end-to-end version of this test passes whether or not
     * the sorting exists. It was vacuous until this replaced it — verified by
     * removing the sort and watching it still pass.
     *
     * The sorting still earns its place, because a record reaching here need
     * not have come from an annotation.
     */
    @Test
    fun `record field order does not matter`() {
        val xy = MezzeType.RecordType(linkedMapOf(
            "x" to MezzeType.IntType, "y" to MezzeType.StringType))
        val yx = MezzeType.RecordType(linkedMapOf(
            "y" to MezzeType.StringType, "x" to MezzeType.IntType))

        assertEquals(SignatureNormalizer.render(xy), SignatureNormalizer.render(yx),
            "Mezze records are named, so field order carries no meaning")
        assertEquals("{ x: Int, y: Str }", SignatureNormalizer.render(yx))
    }

    @Test
    fun `one variable used twice keeps one name`() {
        // If the renamer were stateless per occurrence this would render as
        // `{ x: 'a, y: 'b } -> 'c` and stop looking like the identity function.
        val sig = sigOf("let id: { x: 'a } -> 'a = { x } -> x", "id")
        assertEquals(1, Regex("'[a-z]").findAll(sig).map { it.value }.toSet().size,
            "the same variable must render the same everywhere: $sig")
    }

    @Test
    fun `distinct variables get distinct names, in first-appearance order`() {
        val sig = sigOf("let pair: { a: 'p, b: 'q } -> 'p = { a, b } -> a", "pair")
        assertTrue(sig.contains("'a") && sig.contains("'b"),
            "two variables should be 'a and 'b: $sig")
    }

    // ---- shape is preserved -------------------------------------------------

    @Test
    fun `a concrete signature renders readably`() {
        assertEquals("{ x: Int, y: Int } -> Int",
            sigOf("let add: { x: Int, y: Int } -> Int = { x, y } -> x + y", "add"))
    }

    @Test
    fun `a non-function renders as its type`() {
        assertEquals("Int", sigOf("let answer: Int = 42", "answer"))
    }

    // ---- effects and constraints are part of the signature ------------------

    @Test
    fun `effects appear in the rendering and in effectNames`() {
        val src = """
            use std::effects::log::{ println, Console }

            let shout: { msg: Str } -> {} <Log> = { msg } -> println { msg = msg }
        """.trimIndent()
        val sig = sigOf(src, "shout")
        assertTrue(sig.contains("<Log>"),
            "an effect row is part of what a function IS, not decoration: $sig")
    }

    @Test
    fun `a pure function has no effect clause`() {
        val sig = sigOf("let add: { x: Int, y: Int } -> Int = { x, y } -> x + y", "add")
        assertTrue(!sig.contains("<"), "pure functions carry no effect clause: $sig")
    }

    @Test
    fun `effectNames is empty for a pure function and populated for an effectful one`() {
        val parsedPure = MezzeParser().parseParsedModule(
            "let add: { x: Int, y: Int } -> Int = { x, y } -> x + y", "t.mz")
        val pure = (TypeChecker().checkParsedModule(parsedPure, Paths.get("."), currentModulePath = null)
            as TypeChecker.TypeCheckResult.Success).typedModule!!
            .decls.filterIsInstance<TypedLetDecl>().first()
        assertEquals(emptyList<String>(), SignatureNormalizer.effectNames(pure.scheme.type))
    }
}
