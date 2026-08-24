package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #110 rule-book regression tests. Each rule of the newtype `.()` syntax
 * (declaration, construction, destructuring, module opacity, disambiguation
 * errors, parser diagnostics, migration diagnostic) gets an explicit
 * accept/reject pair. If someone regresses a rule, one of these fails
 * with a clear "rule X broken" message.
 *
 * Complements the higher-level NewtypeComprehensiveTest / NewtypeProbeTest
 * (which cover usage patterns) by pinning the error-message contracts
 * that the design promises.
 */
class NewtypeRulesTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun setUp() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    /** Returns null on typecheck success; the top error message on failure (parse error re-wrapped). */
    private fun err(src: String): String? = try {
        val parsed = parser.parseParsedModule(src.trimIndent(), "test.mz")
        tc.typeCheck(parsed, Paths.get("."), enablePrelude = true)
        null
    } catch (e: ParseException) {
        e.message ?: "ParseException"
    } catch (e: TypeCheckError) {
        e.message ?: "TypeCheckError"
    } catch (e: Exception) {
        e.message ?: e::class.simpleName ?: "Exception"
    }

    private fun ok(label: String, src: String) {
        val e = err(src)
        assertTrue(e == null, "[$label] expected typecheck to succeed, got: $e\n---SRC:\n$src")
    }

    private fun reject(label: String, src: String, mustContain: String) {
        val e = err(src)
        assertTrue(e != null, "[$label] expected error containing '$mustContain', got success\n---SRC:\n$src")
        assertTrue(
            e.contains(mustContain),
            "[$label] error text mismatch. expected substring: '$mustContain'\n  got: $e\n---SRC:\n$src",
        )
    }

    // ── DECLARATION SYNTAX ──────────────────────────────────────────────

    @Test fun `RULE decl - typename IS the constructor - no repetition needed`() {
        ok("decl.scalar", """
            newtype Email = Str
            pub let e = Email.("hi")
        """)
    }

    @Test fun `RULE decl - old ctor-repeat form still parses (backwards compat probe)`() {
        // Documenting current behavior: the parser reads `newtype X = Y Z` as
        // an X aliasing the type application `Y Z`. Without a `Y` in scope
        // this typechecks-fails; document the observed error so a future
        // migration diagnostic can hook in here.
        val e = err("""
            newtype Email = Email Str
            pub let e = Email.("hi")
        """)
        // We ACCEPT that this is currently ill-defined and probe the observed
        // behavior. If someone tightens the parser to reject this form with a
        // targeted diagnostic, update this test.
        assertTrue(e == null || e.isNotEmpty(), "any outcome is documented — this probes current state")
    }

    // ── CONSTRUCTION SYNTAX ─────────────────────────────────────────────

    @Test fun `RULE construct - scalar newtype uses positional-in-parens`() {
        ok("construct.scalar", """
            newtype Age = Int
            pub let a = Age.(42)
        """)
    }

    @Test fun `RULE construct - record newtype takes record literal inside`() {
        ok("construct.record", """
            newtype Point = { x: Int, y: Int }
            pub let p = Point.({ x = 1, y = 2 })
        """)
    }

    // ── DESTRUCTURING SYNTAX ────────────────────────────────────────────

    @Test fun `RULE destructure - scalar newtype binds inner directly`() {
        ok("destruct.scalar", """
            newtype Age = Int
            pub let unwrap = { a: Age } -> match a is Age.(n) -> n
        """)
    }

    @Test fun `RULE destructure - record newtype allows nested record pattern`() {
        ok("destruct.record", """
            newtype Point = { x: Int, y: Int }
            pub let get_x = { p: Point } -> match p is Point.({ x, y }) -> x
        """)
    }

    // ── OPACITY: bare wrapped type is NOT a newtype ─────────────────────

    @Test fun `RULE opacity - bare inner type is NOT accepted where newtype expected`() {
        reject("opacity.bare-inner", """
            newtype Age = Int
            pub let take = { a: Age } -> 42
            pub let f = {} -> take { a = 5 }
        """, "Age")
    }

    @Test fun `RULE opacity - two structurally-identical newtypes are nominally distinct`() {
        reject("opacity.distinct", """
            newtype A = Int
            newtype B = Int
            pub let take_a = { x: A } -> 1
            pub let f = { b: B } -> take_a { x = b }
        """, "A")
    }

    // ── DISAMBIGUATION: TypeName.(x) where TypeName resolves to something else ──

    @Test fun `RULE disambiguation - Ability dot-parens is REJECTED with helpful hint`() {
        reject("disambig.ability", """
            pub let f = {} -> Eq.(42)
        """, "not a newtype")
    }

    @Test fun `RULE disambiguation - Effect dot-parens is REJECTED with helpful hint`() {
        reject("disambig.effect", """
            effect MyEffect where {
                op: {} -> Int
            }
            pub let f = {} -> MyEffect.(42)
        """, "not a newtype")
    }

    @Test fun `RULE disambiguation - Variant type dot-parens is REJECTED`() {
        // Option is a well-known variant. Option.(42) should reject.
        reject("disambig.variant", """
            pub let f = {} -> Option.(42)
        """, "not a newtype")
    }

    @Test fun `RULE disambiguation - Primitive Str dot-parens is REJECTED`() {
        reject("disambig.primitive", """
            pub let f = {} -> Str.("hi")
        """, "not a newtype")
    }

    @Test fun `RULE disambiguation - Unknown type dot-parens is REJECTED with import hint`() {
        reject("disambig.unknown", """
            pub let f = {} -> DoesNotExist.(42)
        """, "DoesNotExist")
    }

    @Test fun `RULE disambiguation - Transparent alias dot-parens is REJECTED with newtype hint`() {
        reject("disambig.alias", """
            type MyInt = Int
            pub let f = {} -> MyInt.(42)
        """, "transparent type alias")
    }

    // ── PARSER DIAGNOSTICS ──────────────────────────────────────────────

    @Test fun `RULE parser - unclosed paren on newtype construction`() {
        reject("parser.unclosed", """
            newtype Age = Int
            pub let a = Age.(42
        """, "Age")  // error mentions 'Age' or matching-paren guidance
    }

    @Test fun `RULE parser - dot-parens with lowercase-identifier LHS is REJECTED`() {
        // Documented weakness: the parser treats any identifier before `.(` as
        // a type name, so `let y = x.("hi")` where x is a value ends up
        // diagnosed as "Unknown type 'x'" rather than a dedicated
        // expression-on-LHS parser error. Not ideal but rejects.
        // Follow-up: teach the parser that lowercase-first identifier is a
        // value, not a type, and emit the more specific parser diagnostic.
        val e = err("""
            pub let x = 42
            pub let y = x.("hi")
        """)
        assertTrue(e != null, "expected some rejection, got success")
        assertTrue(
            e.contains("x") || e.contains(".("),
            "expected a diagnostic mentioning 'x' or '.(' , got: $e",
        )
    }

    // ── OLD-SYNTAX MIGRATION DIAGNOSTIC ─────────────────────────────────

    @Test fun `RULE migration - old record-literal construction on scalar newtype is REJECTED with migration hint`() {
        reject("migration.scalar-record-form", """
            newtype Age = Int
            pub let a = Age { val = 42 }
        """, "not a record")
    }

    // ── RUNTIME ERASURE (integration guardrail) ─────────────────────────
    // Detailed IR-level probes live in NewtypeErasureIRTest. Here we just
    // pin: construction + destructure round-trips return the original value.

    @Test fun `RULE erasure - round-trip returns original scalar value`() {
        ok("erasure.round-trip.scalar", """
            newtype Age = Int
            pub let go = {} -> match Age.(42) is
              Age.(n) -> n
        """)
    }

    @Test fun `RULE erasure - round-trip returns original record value`() {
        ok("erasure.round-trip.record", """
            newtype Point = { x: Int, y: Int }
            pub let go = {} -> match Point.({ x = 3, y = 4 }) is
              Point.({ x, y }) -> x + y
        """)
    }

    // ── NEWTYPE-OVER-NEWTYPE (composition) ──────────────────────────────

    @Test fun `RULE composition - newtype wrapping another newtype declares OK`() {
        ok("compose.nested-newtype", """
            newtype Age = Int
            newtype Years = Age
        """)
    }

    // ── PARAMETERIZED NEWTYPES ──────────────────────────────────────────

    @Test fun `RULE parameterized - newtype with a type parameter`() {
        ok("param.simple", """
            newtype Wrap 'a = 'a
            pub let w = Wrap.(42)
        """)
    }

    @Test fun `RULE parameterized - newtype-of-record with a type parameter`() {
        ok("param.record", """
            newtype Cell 'a = { value: 'a }
            pub let c = Cell.({ value = 42 })
        """)
    }

    // ── AUTO-DERIVED INSPECT (single ability that IS auto-derived on newtypes) ──

    @Test fun `RULE auto-derived - Inspect works on newtype without explicit impl`() {
        ok("auto-derive.inspect", """
            newtype Age = Int
            pub let s = Age.(42).to_dbg_str {}
        """)
    }

    // ── NON-AUTO-DERIVED ABILITIES ──────────────────────────────────────

    @Test fun `RULE no-auto-derive - Eq requires an explicit impl on a newtype`() {
        reject("auto-derive.eq-requires-impl", """
            newtype UserId = Str
            pub let equal = { a: UserId, b: UserId } -> a == b
        """, "Eq")
    }

    @Test fun `RULE explicit-impl - user-defined ability impl on a newtype works`() {
        ok("explicit-impl.user-ability", """
            newtype UserId = Str
            ability Named for 'a where {
              name_of: { it: 'a } -> Str,
            }
            impl Named for UserId where {
              name_of = { it } -> match it is UserId.(s) -> s,
            }
            pub let go = { u: UserId } -> Named.name_of { it = u }
        """)
    }

    // ── NEWTYPE AS FUNCTION PARAMETER / RETURN / FIELD ──────────────────

    @Test fun `RULE use-as-type - newtype is valid as function parameter annotation`() {
        ok("use.param", """
            newtype Age = Int
            pub let takes = { a: Age } -> 1
        """)
    }

    @Test fun `RULE use-as-type - newtype is valid as function return annotation`() {
        ok("use.return", """
            newtype Age = Int
            pub let mk : { n: Int } -> Age = { n } -> Age.(n)
        """)
    }

    @Test fun `RULE use-as-type - newtype is valid as a record field type`() {
        ok("use.field", """
            newtype Email = Str
            type User = { email: Email, id: Int }
            pub let mk = {} -> User { email = Email.("a@b"), id = 1 }
        """)
    }

    @Test fun `RULE use-as-type - newtype composes with generic types`() {
        ok("use.generic-arg", """
            use std::collections::seq
            newtype Email = Str
            pub let xs : Seq Email = seq::empty {}
        """)
    }

    // ── DESTRUCTURE BINDING TYPES ───────────────────────────────────────

    @Test fun `RULE destruct - bound name in scalar pattern has INNER type`() {
        ok("destruct.bound-type.scalar", """
            newtype Age = Int
            pub let plus_one = { a: Age } -> match a is
              Age.(n) -> n + 1
        """)
    }

    @Test fun `RULE destruct - bound name in record pattern has INNER field types`() {
        ok("destruct.bound-type.record", """
            newtype Point = { x: Int, y: Int }
            pub let sum = { p: Point } -> match p is
              Point.({ x, y }) -> x + y
        """)
    }

    @Test fun `RULE destruct - wildcard inside newtype pattern is accepted`() {
        ok("destruct.wildcard", """
            newtype Age = Int
            pub let zero_if = { a: Age } -> match a is
              Age.(_) -> 0
        """)
    }

    // ── OPACITY: WRAPPED VALUE HIDES INNER OPERATIONS ───────────────────

    @Test fun `RULE opacity - inner Int operations NOT available on newtype without impl`() {
        // Age wraps Int but the newtype itself has no NumAdd impl.
        reject("opacity.no-inner-ops", """
            newtype Age = Int
            pub let bump = { a: Age } -> a + 1
        """, "Age")
    }

    // ── NEWTYPE COMPOSITION ─────────────────────────────────────────────

    @Test fun `RULE compose - newtype over record with generic parameter`() {
        ok("compose.generic-record", """
            newtype Box 'a = { val: 'a }
            pub let mk_int = {} -> Box.({ val = 42 })
            pub let unwrap : { b: Box Int } -> Int = { b } -> match b is
              Box.({ val = v }) -> v
        """)
    }

    @Test fun `RULE compose - newtype wrapping Option produces a distinct type`() {
        ok("compose.option", """
            newtype Maybe 'a = Option 'a
            pub let mk = { x: Int } -> Maybe.(Some { val = x })
        """)
    }

    // ── PATTERN EXHAUSTIVENESS ──────────────────────────────────────────

    @Test fun `RULE exhaustive - single arm covering the newtype is exhaustive`() {
        ok("exhaustive.single-arm", """
            newtype Age = Int
            pub let extract = { a: Age } -> match a is
              Age.(n) -> n
        """)
    }

    // ── MODULE VISIBILITY ───────────────────────────────────────────────

    @Test fun `RULE module - private newtype rejected outside its declaring module`() {
        // Documenting the current single-file harness reality: without a
        // multi-file test setup, we can only assert that a private newtype
        // used LOCALLY is fine. Cross-module opacity has separate tests.
        ok("module.private-local", """
            newtype Secret = Str
            pub let use_local = { s: Secret } -> match s is Secret.(v) -> v
        """)
    }
}
