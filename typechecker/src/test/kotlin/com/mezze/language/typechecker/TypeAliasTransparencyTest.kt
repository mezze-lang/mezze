package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Pins the transparency rule for `type X = ...`, and what a `type` may name.
 *
 * ONE SHAPE, ONE NAME — so inference has exactly one answer:
 *
 *     type Person = { name: Str, age: Int }
 *     let x = { name = "Ada", age = 32 }      # infers Person
 *
 * works only because the shape has exactly one name. A second name makes
 * adoption a choice, and a choice is what inference cannot make for the author.
 * So a `type` names a shape that has NONE, and never renames something already
 * named (V0713).
 *
 * This file USED to pin `type MyInt = Int` as intended behaviour, in most of the
 * tests below. That was the evidence I cited when I first proposed refusing
 * primitive aliases and then backed off — "a test file pins this, so someone
 * decided it deliberately". The decision has since been made the other way, and
 * on the stronger argument: a primitive alias is erased before inference even
 * finishes, so it never reaches a signature, a diagnostic or a hover, and it
 * cannot carry an impl. It is a comment the compiler spell-checks. `newtype`
 * does all three and costs one line.
 *
 * The transparency property is REAL and still worth pinning — it just needs a
 * vehicle that survives the rule. Three do, and each carries a different half
 * of what the primitive alias used to demonstrate:
 *
 *   - a RECORD alias (`type Row = { id: Int }`) — interchangeable with its
 *     shape in both directions, and derives `Eq` like any record
 *   - a SEQ alias over the author's own type (`type Rows = [Row]`) — inherits
 *     the underlying type's INHERENT methods, which is the claim V0706's
 *     message makes
 *   - a FUNCTION alias (`type Producer 'a = {} -> 'a`) — stays callable, which
 *     is the abbreviation value that justified keeping it
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
      // The CODE is included so a refusal can be asserted on the thing that
      // does not move. The prose does move — this file's own rewrite is the
      // proof — and `reject` matches a substring, so both still work.
      catch (e: TypeCheckError) { "TC ${e.code?.name ?: "(uncoded)"}: ${e.message}" }
      catch (e: Exception) { "${e::class.simpleName}: ${e.message}" }

    private fun ok(label: String, src: String) {
        val e = err(src)
        assertTrue(e == null, "[$label] expected typecheck to succeed, got: $e\n---\n$src")
    }

    private fun reject(label: String, src: String, hint: String) {
        val e = err(src)
        assertTrue(e != null, "[$label] expected rejection\n---\n$src")
        assertTrue(e.contains(hint), "[$label] error mismatch. want: '$hint'\n got: $e")
    }

    // ── WHAT A `type` MAY NAME ─────────────────────────────────────────

    @Test fun `a record declaration parses and is accepted`() {
        ok("parse.record", "type Person = { name: Str, age: Int }")
    }

    @Test fun `a variant declaration parses and is accepted`() {
        ok("parse.variant", "type Colour = | R, G, B |")
    }

    @Test fun `a function alias parses and is accepted`() {
        ok("parse.function", "type Handler = { n: Int } -> Int")
    }

    @Test fun `a seq alias over the author's own type is accepted`() {
        ok("parse.seq-of-mine", "type Row = { id: Int }\ntype Rows = [Row]")
    }

    // ── TRANSPARENCY: a record alias IS its shape ──────────────────────

    @Test fun `alias transparently accepts the aliased value`() {
        ok("transparent.accept", """
            type Row = { id: Int }
            pub let take = { r: Row } -> r.id
            pub let call = {} -> take { r = { id = 42 } }
        """)
    }

    @Test fun `aliased value flows into a bare-shape parameter`() {
        ok("transparent.both-ways", """
            type Row = { id: Int }
            pub let mk : {} -> Row = {} -> { id = 42 }
            pub let sink : { r: { id: Int } } -> Int = { r } -> r.id
            pub let go = {} -> sink { r = mk {} }
        """)
    }

    @Test fun `alias inherits Eq from the shape it names`() {
        ok("transparent.eq-inherited", """
            type Row = { id: Int }
            pub let same = { a: Row, b: Row } -> a == b
        """)
    }

    /**
     * The INHERENT methods, which is the claim V0706's message makes — "a
     * transparent alias already inherits the impls of the type it aliases". A
     * seq alias is the vehicle: `first` lives on `Seq`, not on `Rows`, and a
     * `Rows` receiver reaches it.
     */
    @Test fun `seq alias inherits the seq inherent methods`() {
        ok("transparent.seq-method-inherited", """
            use std::int
            use std::collections::seq
            type Row = { id: Int }
            type Rows = [Row]
            pub let top = { xs: Rows } -> xs.first {}
        """)
    }

    /** A function alias stays callable — the abbreviation it buys is usable. */
    @Test fun `function alias stays callable`() {
        ok("transparent.fn-callable", """
            use std::int
            type Handler = { n: Int } -> Int
            pub let run = { h: Handler } -> h { n = 1 }
            pub let go = {} -> run { h = { n } -> n + 1 }
        """)
    }

    @Test fun `function alias is interchangeable with the raw signature`() {
        ok("transparent.fn-both-ways", """
            use std::int
            type Handler = { n: Int } -> Int
            pub let takesAlias = { h: Handler } -> h { n = 1 }
            pub let takesRaw = { f: { n: Int } -> Int } -> f { n = 1 }
            pub let a = { f: { n: Int } -> Int } -> takesAlias { h = f }
            pub let b = { h: Handler } -> takesRaw { f = h }
        """)
    }

    // ── CONTRAST: newtype stays nominal ────────────────────────────────

    /**
     * The other side of the line: a newtype inherits NOTHING, so the method is
     * absent and `impl M where` is the way to give it one — which is what both
     * V0706 and V0713 point the author at.
     */
    @Test fun `newtype does NOT inherit inherent methods from the wrapped type`() {
        reject("newtype.method-not-inherited", """
            use std::int
            newtype M = Int
            pub let show = { m: M } -> m.to_str {}
        """, "to_str")
    }

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

    // ── PARAMETRIC ALIASES ─────────────────────────────────────────────

    @Test fun `parametric record alias with two type params`() {
        ok("param.two", """
            type Pair 'a 'b = { first: 'a, second: 'b }
            pub let mk : {} -> Pair Int Str = {} -> { first = 1, second = "hi" }
        """)
    }

    @Test fun `parametric function alias inlines through the signature`() {
        ok("param.function-rhs", """
            type Producer 'a = {} -> 'a
            pub let mk : Producer Int = {} -> 42
        """)
    }

    @Test fun `parametric record alias of a record alias is accepted`() {
        // Both are record DECLARATIONS (ParsedRecordDef), not aliases of a
        // name, so neither renames anything — the nesting is just composition.
        ok("param.record-in-record", """
            type Row = { id: Int }
            type Holder = { r: Row }
            pub let take : { h: { r: { id: Int } } } -> Int = { h } -> h.r.id
            pub let go = { h: Holder } -> take { h = h }
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

    // ── ALIAS INTEROP: mixing the alias name with the raw shape ────────
    //
    // The whole point of a transparent alias is that call sites may freely mix
    // the alias name and the thing it names. These pin the common boundaries —
    // a record field, a seq element, a higher-order callback, a type argument —
    // with a RECORD alias as the vehicle, since that is the kind whose name
    // both survives and stays interchangeable.

    @Test fun `alias flows through a record field type`() {
        ok("interop.record-field", """
            type Row = { id: Int }
            pub let take : { w: { r: { id: Int } } } -> Int = { w } -> w.r.id
            pub let go = { w: { r: Row } } -> take { w = w }
        """)
    }

    @Test fun `bare inline record flows into a record-alias parameter`() {
        ok("interop.bare-into-alias", """
            type Wrap = { n: Int }
            pub let take : { w: Wrap } -> Int = { w } -> w.n
            pub let go = {} -> take { w = { n = 42 } }
        """)
    }

    @Test fun `alias flows through a Seq element boundary`() {
        ok("interop.seq-element", """
            use std::collections::seq
            type Row = { id: Int }
            pub let mk : {} -> Seq Row = {} -> seq::empty {}
            pub let take : { xs: Seq { id: Int } } -> Seq { id: Int } = { xs } -> xs
            pub let go = {} -> take { xs = mk {} }
        """)
    }

    @Test fun `alias flows into a stdlib map callback typed with the raw shape`() {
        ok("interop.hof-callback", """
            use std::int
            use std::collections::seq
            type Row = { id: Int }
            pub let mk : {} -> Seq Row = {} -> seq::empty {}
            pub let go = {} -> seq::map { xs = mk {}, f = { x: { id: Int } } -> x.id }
        """)
    }

    @Test fun `Option Row and Option of the raw shape are interchangeable`() {
        ok("interop.option-arg", """
            type Row = { id: Int }
            pub let take : { o: Option { id: Int } } -> Int = { o } -> match o is
                Some { val = r } -> r.id
                None -> 0
            pub let go = { m: Option Row } -> take { o = m }
        """)
    }

    // ── REFUSED: a second name for something already named (V0713) ─────
    //
    // Each of these was an `ok(...)` in this file before the rule changed, and
    // the comment on each says which property it used to pin and where that
    // property went.

    /** Was `parse.primitive`. A primitive has a name; `newtype` for a new one. */
    @Test fun `a primitive alias is refused`() {
        reject("refused.primitive", "type Foo = Int", "V0713")
    }

    /**
     * Was `transparent.arith-inherited` — an alias of `Int` could be added to.
     * That property has no vehicle left, and does not need one: the author who
     * wanted `MyInt` to behave as `Int` can write `Int`, and the author who
     * wanted it DISTINCT wants a newtype, which refuses arithmetic on purpose.
     */
    @Test fun `a primitive alias used arithmetically is refused at the declaration`() {
        reject("refused.arith", """
            use std::int
            type MyInt = Int
            pub let inc = { n: MyInt } -> n + 1
        """, "V0713")
    }

    /** Was `compose.chain`. A second name for a name. */
    @Test fun `an alias of an alias is refused`() {
        reject("refused.chain", """
            type Row = { id: Int }
            type Row2 = Row
        """, "V0713")
    }

    /** Was `parse.generic` and `param.seq`. `Seq Int` is already named. */
    @Test fun `a seq alias built only from builtins is refused`() {
        reject("refused.seq-builtin", "use std::collections::seq\ntype Ids = Seq Int", "V0713")
        reject("refused.seq-bracket", "type Ids = [Int]", "V0713")
        reject("refused.seq-nested", "type Matrix = [[Float]]", "V0713")
    }

    /** Was `param.alias-of-alias`. A parametric rename is still a rename. */
    @Test fun `a parametric seq alias is refused`() {
        reject("refused.param-seq", "use std::collections::seq\ntype Ids 'a = Seq 'a", "V0713")
    }

    /**
     * An APPLIED name is the one refusal that is about ambiguity rather than
     * pointlessness: `withAliasIdentity` stamps a variant with the alias's
     * identity, so `Res` would be a SECOND identity over one shape — exactly
     * what the `Person` rule rules out.
     */
    @Test fun `an alias of an applied type is refused`() {
        reject("refused.applied", """
            use std::option
            type Opt = Option Int
        """, "V0713")
    }

    /** A newtype is already a distinct type; renaming it gains nothing. */
    @Test fun `an alias of a newtype is refused`() {
        reject("refused.newtype", """
            use std::int
            newtype M = Int
            type M2 = M
        """, "V0713")
    }

    /**
     * The exemption, and why it is not a hole: `__Native*` is a reserved prefix
     * for opaque runtime handles an author cannot spell, so the alias is that
     * type's FIRST usable name. `std/polyglot/core.mz` relies on it.
     */
    @Test fun `an alias of an unspellable native handle is accepted`() {
        ok("native.first-name", "type Foreign = __NativeForeign")
    }
}
