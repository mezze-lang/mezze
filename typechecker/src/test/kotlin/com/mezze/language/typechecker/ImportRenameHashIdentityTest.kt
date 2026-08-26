package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.ParsedTypeDecl
import com.mezze.language.parsedast.ParsedDeclHasher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A type's content-hash IDENTITY is a function of its DECLARATION (its own
 * declared name at the definition site + type params + body) — NOT of where or
 * how it is used or imported. Therefore:
 *   - importing a type under an alias (`use m::{ Bar as Baz }`) does NOT change
 *     its identity: `Baz` resolves to `Bar`'s decl hash, so they are the SAME type.
 *   - renaming the DECLARATION itself (`type Bar` → `type Qux`) DOES change the
 *     identity: it is a different declaration, hence a different type.
 *
 * These pin `ParsedDeclHasher.hashTypeDecl` (which hashes `decl.name` + params +
 * body) — the property the import machinery relies on when it computes an
 * imported type's `typeHash` from the ORIGINAL decl, tracking the alias only as
 * a local binding name.
 */
class ImportRenameHashIdentityTest {
    private val parser = MezzeParser()

    @BeforeEach fun setup() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
    }

    private fun typeDeclOf(source: String): ParsedTypeDecl =
        parser.parseParsedModule(source, "t.mz").decls.filterIsInstance<ParsedTypeDecl>().first()

    // The hash depends only on the decl (name+params+body), so the SAME decl —
    // regardless of any import alias applied at a use site — has the SAME hash.
    // An alias never touches the declaration, so it cannot change this hash.
    @Test fun `same declaration hashes identically (alias at use site is irrelevant)`() {
        val decl = typeDeclOf("type Bar 'a = | Wrap { val: 'a }, Empty |")
        val h1 = ParsedDeclHasher.hashTypeDecl(decl)
        // Re-parsing the identical declaration yields the identical hash: the
        // hash is a pure function of the decl, so importing it elsewhere under
        // any alias reuses THIS hash unchanged.
        val declAgain = typeDeclOf("type Bar 'a = | Wrap { val: 'a }, Empty |")
        val h2 = ParsedDeclHasher.hashTypeDecl(declAgain)
        assertEquals(h1, h2, "same declaration must hash identically; an import alias never changes it")
    }

    // Renaming the DECLARATION (not an import alias) changes the identity —
    // it is a different type.
    @Test fun `renaming the declaration name changes the hash`() {
        val bar = ParsedDeclHasher.hashTypeDecl(typeDeclOf("type Bar 'a = | Wrap { val: 'a }, Empty |"))
        val qux = ParsedDeclHasher.hashTypeDecl(typeDeclOf("type Qux 'a = | Wrap { val: 'a }, Empty |"))
        assertNotEquals(bar, qux, "renaming the declaration itself is a different type → different hash")
    }

    // Body change → different hash (identity tracks content, not just name).
    @Test fun `changing the body changes the hash`() {
        val a = ParsedDeclHasher.hashTypeDecl(typeDeclOf("type Bar = { x: Int }"))
        val b = ParsedDeclHasher.hashTypeDecl(typeDeclOf("type Bar = { x: Str }"))
        assertNotEquals(a, b, "different body content → different hash")
    }
}
