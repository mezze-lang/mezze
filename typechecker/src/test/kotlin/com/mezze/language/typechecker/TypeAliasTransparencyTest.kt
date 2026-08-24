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

    // ── PARAMETRIC ALIASES ─────────────────────────────────────────────

    @Test fun `parametric alias over Seq unifies with the aliased shape`() {
        ok("param.seq", """
            use std::collections::seq
            type Ids 'a = Seq 'a
            pub let take : { xs: Seq Int } -> Seq Int = { xs } -> xs
            pub let call = { xs: Ids Int } -> take { xs = xs }
        """)
    }

    @Test fun `parametric alias with two type params`() {
        ok("param.two", """
            type Pair 'a 'b = { first: 'a, second: 'b }
            pub let mk : {} -> Pair Int Str = {} -> { first = 1, second = "hi" }
        """)
    }

    @Test fun `parametric alias-of-alias resolves transitively`() {
        ok("param.alias-of-alias", """
            use std::collections::seq
            type Ids 'a = Seq 'a
            type Bag 'a = Ids 'a
            pub let take : { xs: Seq Int } -> Seq Int = { xs } -> xs
            pub let call = { xs: Bag Int } -> take { xs = xs }
        """)
    }

    @Test fun `parametric alias inlines through a function type`() {
        ok("param.function-rhs", """
            type Producer 'a = {} -> 'a
            pub let mk : Producer Int = {} -> 42
        """)
    }

    // ── PARAMETRIC NEWTYPES (contrast) ─────────────────────────────────
    //
    // Parametric newtypes stay nominal at every use site — the same way
    // non-parametric ones do. The wrapped type parameter propagates through
    // construction and destructuring, but the nominal wall remains up.

    @Test fun `parametric newtype construct + destruct threads the type param`() {
        ok("newtype.param.roundtrip", """
            use std::collections::seq
            newtype Wrap 'a = Seq 'a
            pub let w : Wrap Int = Wrap.(seq::empty {})
            pub let unwrap : { w: Wrap Int } -> Seq Int = { w } -> match w is Wrap.(xs) -> xs
        """)
    }

    @Test fun `parametric newtype over record wraps a record shape`() {
        ok("newtype.param.record", """
            newtype Box 'a = { val: 'a }
            pub let b : Box Int = Box.({ val = 42 })
        """)
    }

    @Test fun `parametric newtype stays nominal - Wrap Int does NOT unify with Seq Int`() {
        reject("newtype.param.nominal", """
            use std::collections::seq
            newtype Wrap 'a = Seq 'a
            pub let take : { xs: Seq Int } -> Seq Int = { xs } -> xs
            pub let call = { w: Wrap Int } -> take { xs = w }
        """, "")
    }

    // ── ALIAS INTEROP: mixing alias names with the raw type at boundaries ──
    //
    // The whole point of transparent aliases is that call sites can freely
    // mix the alias name and the aliased type. These tests pin the common
    // patterns where a user has a value typed as `MyInt` but the callee is
    // annotated with `Int` (or vice versa), and the value flows through
    // records, sequences, higher-order callbacks, and stdlib functions.

    @Test fun `MyInt value flows into function annotated with bare Int`() {
        ok("interop.value-to-bare", """
            type MyInt = Int
            pub let mk : {} -> MyInt = {} -> 42
            pub let sink : { n: Int } -> Int = { n } -> n
            pub let go = {} -> sink { n = mk {} }
        """)
    }

    @Test fun `bare Int value flows into function annotated with MyInt`() {
        ok("interop.bare-to-value", """
            type MyInt = Int
            pub let take : { n: MyInt } -> MyInt = { n } -> n
            pub let go = {} -> take { n = 5 }
        """)
    }

    @Test fun `alias flows through a record field type`() {
        // Bare inline records: {n: MyInt} must be interchangeable with
        // {n: Int} at every boundary because MyInt inlines to Int.
        // (Record ALIASES `type Wrap = { … }` are a separate nominal/adoption
        // story per [[project_type_alias_adoption]] — not covered here.)
        ok("interop.record-field", """
            type MyInt = Int
            pub let take : { w: { n: Int } } -> Int = { w } -> w.n
            pub let go = { w: { n: MyInt } } -> take { w = w }
        """)
    }

    @Test fun `alias flows through a Seq element boundary`() {
        ok("interop.seq-element", """
            use std::collections::seq
            type MyInt = Int
            pub let mk : {} -> Seq MyInt = {} -> seq::empty {}
            pub let take : { xs: Seq Int } -> Seq Int = { xs } -> xs
            pub let go = {} -> take { xs = mk {} }
        """)
    }

    @Test fun `alias flows into a stdlib map callback typed with bare Int`() {
        ok("interop.hof-callback", """
            use std::collections::seq
            type MyInt = Int
            pub let mk : {} -> Seq MyInt = {} -> seq::empty {}
            pub let go = {} -> seq::map { xs = mk {}, f = { x: Int } -> x + 1 }
        """)
    }

    @Test fun `Option MyInt and Option Int are interchangeable`() {
        ok("interop.option-arg", """
            type MyInt = Int
            pub let take : { o: Option Int } -> Int = { o } -> match o is
                Some { val = n } -> n
                None -> 0
            pub let go = { m: Option MyInt } -> take { o = m }
        """)
    }
}
