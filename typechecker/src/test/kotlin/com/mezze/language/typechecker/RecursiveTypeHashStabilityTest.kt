package com.mezze.language.typechecker

import com.mezze.language.parsedast.DeclIdentity
import com.mezze.language.parsedast.IdentityScope
import com.mezze.language.parsedast.identity

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.ParsedTypeDecl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * #318 regression: a recursive (self- or mutually-referential) type declaration must
 * produce a FINITE, DETERMINISTIC content hash.
 *
 * Why it can't run away: the identity hash (`DeclIdentity`) is computed
 * from the PARSED AST, where a self-reference — `Tree` inside
 * `type Tree 'a = | Node { left: Tree 'a } |` — is a `ParsedNamedType("Tree")` LEAF (a
 * name token), never an expanded structure. The parsed tree is finite, so the walk
 * terminates trivially; the recursion is broken at the reference boundary, not by a
 * depth cap or a visited-set. (The solved-`MezzeType` hasher, `MezzeTypeHasher`, is safe
 * for the same reason: a recursive nominal type is a `Declared(hash)` reference that
 * appends its hash tag instead of expanding.)
 *
 * These tests pin that guarantee so a future change to the hashers (e.g. one that tries
 * to expand a nominal type inline) can't silently reintroduce non-termination or drift.
 * See [[project_typehash_not_stable_recursive]] — hash is stable to SELECT/store on, and
 * this is the direct evidence. Historic fear of instability across self-reference is dead.
 */
class RecursiveTypeHashStabilityTest {

    private val parser = MezzeParser()

    private fun hashTypeDecls(source: String): Map<String, com.mezze.language.typedast.Hash> {
        val module = DeclIdentity.stamp(parser.parseParsedModule(source, "test.mz"), IdentityScope.NONE, "test")
        return module.decls.filterIsInstance<ParsedTypeDecl>()
            .associate { it.name to it.identity }
    }

    @Test
    fun `self-referential variant type hashes without diverging`() {
        val source = "type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |"
        val hashes = assertDoesNotThrow("recursive type must hash in finite time") {
            hashTypeDecls(source)
        }
        // Sanity: it produced a hash for the recursive decl at all.
        assert(hashes["Tree"] != null) { "expected a content hash for recursive type Tree" }
    }

    @Test
    fun `self-reference through a collection element hashes without diverging`() {
        // The classic JSON case: the recursive occurrence is nested inside a Seq/List.
        val source = "type Json = | JNull, JNum { n: Float }, JArr { items: [Json] } |"
        val hashes = assertDoesNotThrow("self-reference through [Json] must hash in finite time") {
            hashTypeDecls(source)
        }
        assert(hashes["Json"] != null) { "expected a content hash for recursive type Json" }
    }

    @Test
    fun `mutually recursive types hash without diverging`() {
        val source = """
            type Expr = | Lit { v: Int }, Neg { e: Stmt } |
            type Stmt = | Return { e: Expr }, Block { body: [Stmt] } |
        """.trimIndent()
        val hashes = assertDoesNotThrow("mutual recursion must hash in finite time") {
            hashTypeDecls(source)
        }
        assert(hashes["Expr"] != null && hashes["Stmt"] != null) {
            "expected content hashes for both mutually-recursive types"
        }
    }

    @Test
    fun `recursive type hash is deterministic across independent parses`() {
        val source = "type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |"
        val first = hashTypeDecls(source).getValue("Tree")
        val second = hashTypeDecls(source).getValue("Tree")
        assertEquals(first, second, "the same recursive source must hash identically every time")
    }

    @Test
    fun `changing a recursive type's body changes its hash`() {
        // Guards against the hasher becoming insensitive to the recursive payload — the
        // recursion break must not swallow real structural differences.
        val a = hashTypeDecls("type L = | Nil, Cons { head: Int, tail: L } |").getValue("L")
        val b = hashTypeDecls("type L = | Nil, Cons { head: Str, tail: L } |").getValue("L")
        assertNotEquals(a, b, "differing recursive-payload field types must produce different hashes")
    }
}
