package com.mezze.language.parser

import com.mezze.language.parsedast.ParsedDeclHasher
import com.mezze.language.parsedast.ParsedLetDecl
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Content-hash collision regression tests for [ParsedDeclHasher].
 *
 * The content hash is identity for by-hash registries and cross-module import
 * merge (DeclarationRegistry.byHash, extendMono/extendPoly(contentHash)), so two
 * decls that differ in a semantically-significant way MUST get different hashes.
 * These pin the fields the hasher previously dropped:
 *   - ParsedFunctionType.effects        (effect row on a fn-typed param/return)
 *   - ParsedFunctionType.abilityConstraints  (`with Eq 'a` etc.)
 *   - ParsedQualifiedType.args          (type args of a qualified type)
 */
class ParsedDeclHasherCollisionTest {
    private val parser = MezzeParser()

    private fun letHash(src: String): com.mezze.language.typedast.Hash {
        val decl = parser.parseParsedModule(src.trimIndent())
            .decls.filterIsInstance<ParsedLetDecl>().first()
        return ParsedDeclHasher.hashLetDecl(decl)
    }

    @Test
    fun `effect row on a function-typed param changes the content hash`() {
        val withEffect = letHash(
            "pub let apply: { f: { p: Str } -> Str <FileSystem> } -> Str = { f } -> f { p = \"x\" }"
        )
        val pure = letHash(
            "pub let apply: { f: { p: Str } -> Str } -> Str = { f } -> f { p = \"x\" }"
        )
        assertNotEquals(withEffect, pure, "effect row must be part of the content hash")
    }

    @Test
    fun `ability constraint on a function type changes the content hash`() {
        val constrained = letHash(
            "pub let use_it: { a: 'a } with Eq 'a -> 'a = { a } -> a"
        )
        val unconstrained = letHash(
            "pub let use_it: { a: 'a } -> 'a = { a } -> a"
        )
        assertNotEquals(constrained, unconstrained, "fn-type ability constraint must be part of the content hash")
    }

    @Test
    fun `qualified type args change the content hash`() {
        val a = letHash(
            "pub let take: { x: codec::Entry Str Json } -> Int = { x } -> 0"
        )
        val b = letHash(
            "pub let take: { x: codec::Entry Bool Xml } -> Int = { x } -> 0"
        )
        assertNotEquals(a, b, "qualified-type args must be part of the content hash")
    }
}
