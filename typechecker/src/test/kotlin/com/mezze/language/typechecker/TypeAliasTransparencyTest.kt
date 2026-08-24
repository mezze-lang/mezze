package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Pins the transparency rule for `type X = ...`.
 *
 * - `type MyInt = Int` — MyInt and Int are interchangeable at every use site.
 * - Ability impls of the aliased type apply automatically (no need to
 *   `impl Eq for MyInt`).
 * - Contrast: `newtype UserId = Str` stays nominally distinct.
 */
class TypeAliasTransparencyTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun setUp() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? = try {
        val p = parser.parseParsedModule(src.trimIndent(), "alias.mz")
        tc.typeCheck(p, Paths.get("."), enablePrelude = true)
        null
    } catch (e: ParseException) { "PARSE: ${e.message}" }
      catch (e: TypeCheckError) { "TC: ${e.message}" }
      catch (e: Exception) { "${e::class.simpleName}: ${e.message}" }

    private fun ok(label: String, src: String) {
        val e = err(src)
        assertTrue(e == null, "[$label] expected typecheck to succeed, got: $e\n---\n$src")
    }

    private fun reject(label: String, src: String, hint: String) {
        val e = err(src)
        assertTrue(e != null, "[$label] expected rejection")
        assertTrue(e.contains(hint), "[$label] error mismatch. want: '$hint'\n got: $e")
    }

    // ── PARSING ────────────────────────────────────────────────────────

    @Test fun `type Foo = Int parses`() {
        ok("parse.primitive", "type Foo = Int")
    }

    @Test fun `type Ids = Seq Int parses`() {
        ok("parse.generic", "use std::collections::seq\ntype Ids = Seq Int")
    }

    // ── TRANSPARENCY: MyInt is Int at use sites ────────────────────────

    @Test fun `alias transparently accepts the aliased value`() {
        ok("transparent.accept", """
            type MyInt = Int
            pub let take = { n: MyInt } -> n
            pub let call = {} -> take { n = 42 }
        """)
    }

    @Test fun `aliased value flows into aliased parameter`() {
        ok("transparent.both-ways", """
            type MyInt = Int
            pub let mk : {} -> MyInt = {} -> 42
            pub let sink : { n: Int } -> Int = { n } -> n
            pub let go = {} -> sink { n = mk {} }
        """)
    }

    @Test fun `alias inherits Eq impl from aliased primitive`() {
        ok("transparent.eq-inherited", """
            type MyInt = Int
            pub let same = { a: MyInt, b: MyInt } -> a == b
        """)
    }

    @Test fun `alias inherits arithmetic from aliased primitive`() {
        ok("transparent.arith-inherited", """
            type MyInt = Int
            pub let inc = { n: MyInt } -> n + 1
        """)
    }

    // ── CONTRAST: newtype stays nominal ────────────────────────────────

    @Test fun `newtype does NOT unify with wrapped type`() {
        reject("newtype.nominal-distinct", """
            newtype UserId = Int
            pub let take = { n: Int } -> n
            pub let call = { u: UserId } -> take { n = u }
        """, "")
        // Any rejection is fine — the point is it MUST NOT typecheck.
    }

    @Test fun `newtype does NOT inherit Eq from wrapped Str`() {
        reject("newtype.eq-not-inherited", """
            newtype UserId = Str
            pub let same = { a: UserId, b: UserId } -> a == b
        """, "Eq")
    }

    // ── COMPOSITION ────────────────────────────────────────────────────

    @Test fun `alias-of-alias resolves transitively`() {
        ok("compose.chain", """
            type MyInt = Int
            type MyInt2 = MyInt
            pub let take : { n: Int } -> Int = { n } -> n
            pub let call = { x: MyInt2 } -> take { n = x }
        """)
    }
}
