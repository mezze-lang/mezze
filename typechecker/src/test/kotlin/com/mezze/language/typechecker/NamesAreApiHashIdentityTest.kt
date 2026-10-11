package com.mezze.language.typechecker

import com.mezze.language.parsedast.DeclIdentity
import com.mezze.language.parsedast.IdentityScope
import com.mezze.language.parsedast.identity

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.ParsedLetDecl
import com.mezze.language.parsedast.ParsedTypeDecl
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * #319 decision pin: the AUTHORITATIVE definition-identity hash (`DeclIdentity`)
 * hashes names AS-WRITTEN — it is deliberately NOT alpha-normalized.
 *
 * Mezze is call-by-name: arguments are named record fields (`f { x = .., y = .. }`),
 * so a definition's parameter, record-field, and constructor names are part of its
 * INTERFACE, not internal scaffolding. Renaming any of them changes the API and MUST
 * change the identity hash. This is the OPPOSITE of Unison (positional args → all bound
 * names non-semantic → alpha-normalize freely).
 *
 * The separate `TypedASTHasher` (dispatch/mono/wrapper hashes) DOES alpha-normalize
 * bound variables to de-Bruijn positions on purpose — alpha-equivalent expressions
 * SHOULD collide there. These two hashers must not be conflated: "upgrading" the
 * identity hasher to the alpha-normalized one would silently merge distinct public
 * APIs. These tests lock the observable difference so that regression is caught.
 *
 * See DeclIdentity's header.
 */
class NamesAreApiHashIdentityTest {

    private val parser = MezzeParser()

    private fun letHash(source: String): Hash {
        val decl = DeclIdentity.stamp(parser.parseParsedModule(source, "test.mz"), IdentityScope.NONE, "test")
            .decls.filterIsInstance<ParsedLetDecl>().first()
        return decl.identity
    }

    private fun typeHash(source: String): Hash {
        val decl = DeclIdentity.stamp(parser.parseParsedModule(source, "test.mz"), IdentityScope.NONE, "test")
            .decls.filterIsInstance<ParsedTypeDecl>().first()
        return decl.identity
    }

    @Test fun `renaming a record parameter changes the identity hash (params are API)`() {
        // Same shape, same body structure — only the named-argument key differs. Under
        // call-by-name a caller writes `add { x = .. }` vs `add { a = .. }`, so these are
        // genuinely different interfaces.
        val a = letHash("let add = { x, y } -> x")
        val b = letHash("let add = { a, y } -> a")
        assertNotEquals(a, b, "renaming a record param is an API change → different hash")
    }

    @Test fun `renaming a record field changes the identity hash (fields are API)`() {
        val a = typeHash("type Point = { x: Int, y: Int }")
        val b = typeHash("type Point = { col: Int, y: Int }")
        assertNotEquals(a, b, "renaming a record field is an API change → different hash")
    }

    @Test fun `renaming a variant constructor changes the identity hash`() {
        val a = typeHash("type Shape = | Circle { r: Int }, Square { s: Int } |")
        val b = typeHash("type Shape = | Round { r: Int }, Square { s: Int } |")
        assertNotEquals(a, b, "renaming a variant constructor is an API change → different hash")
    }

    @Test fun `renaming a type variable changes the identity hash (no alpha-normalization)`() {
        // An alpha-normalizing hasher would collapse these to one hash. The identity
        // hasher deliberately does not — type-var names are hashed as-written.
        val a = typeHash("type Box 'a = { val: 'a }")
        val b = typeHash("type Box 'b = { val: 'b }")
        assertNotEquals(
            a, b,
            "type-var names are hashed as-written; renaming 'a→'b changes the hash " +
                "(if this ever passes as equal, the identity hasher was wrongly alpha-normalized)",
        )
    }

    @Test fun `identical source hashes identically (determinism sanity)`() {
        val src = "let add = { x, y } -> x"
        assertEquals(letHash(src), letHash(src), "same source must hash identically")
    }
}
