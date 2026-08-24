package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * The full ability × associated-type × annotation × arity × receiver matrix.
 *
 * Axes:
 *   - ARITY:      single-param (`A for 'a`)      | multi-param (`A 'b for 'a`)
 *   - ASSOC:      no `having`                    | with `having 'out`
 *   - RECEIVER:   concrete (`(5).m{}`)           | generic (`{x} -> x.m{}`)
 *   - ANNOTATION: none (inferred)                | with `with A 'a [having 'out = T]`
 *   - VALIDITY:   accepted                        | rejected (wrong having / no impl / wrong arg)
 *
 * Every named test is one cell (or a rejection guard for one cell). If a
 * combination is missing here, it is genuinely untested.
 */
class AbilityCombinationMatrixTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun typeOf(name: String, b: String): String? {
        val r = tc.typeCheck(parser.parseParsedModule(b, "t.mz"), Paths.get("."), enablePrelude = true)
        return r.getVariableType(name)?.let { prettyPrintType(it) }
    }
    private fun err(b: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(b, "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    // Ability fixtures reused across cells.
    private val basic = """
        ability Greet for 'a where { greet: { it: 'a } -> Str }
        impl Greet for Int where { greet = { it } -> "hi" }
    """.trimIndent()
    private val assoc = """
        ability Producer for 'a having 'out where { produce: { it: 'a } -> 'out }
        impl Producer for Int having 'out = Str where { produce = { it } -> "x" }
    """.trimIndent()
    private val multi = """
        ability Scale 'by for 'a where { scale: { it: 'a, by: 'by } -> 'a }
        impl Scale Int for Int where { scale = { it, by } -> it }
    """.trimIndent()
    // NOTE: op is `times`, not `mul` — a `mul` method would collide with the
    // prelude's `NumMul.mul: {it, r}` and resolve to that instead.
    private val multiAssoc = """
        ability Times 'by for 'a having 'out where { times: { it: 'a, by: 'by } -> 'out }
        impl Times Int for Int having 'out = Int where { times = { it, by } -> it }
    """.trimIndent()

    // ── 1. BASIC ability (single-param, no associated type) ─────────────

    @Test fun `basic - concrete receiver, no annotation`() {
        assertEquals("{} -> Str", typeOf("f", "$basic\npub let f = {} -> (5).greet {}"))
    }
    @Test fun `basic - generic receiver, no annotation - keeps constraint`() {
        val t = typeOf("f", "$basic\npub let f = { x } -> x.greet {}")
        assertTrue(t!!.contains("Greet"), "generic should keep the Greet constraint, got: $t")
    }
    @Test fun `basic - generic receiver, with annotation`() {
        assertNull(err("$basic\npub let f: { x: 'a } with Greet 'a -> Str = { x } -> x.greet {}"))
    }
    @Test fun `basic - concrete receiver with no impl is rejected`() {
        assertNotNull(err("$basic\npub let f = {} -> (\"s\").greet {}"))
    }

    // ── 2. ability + ASSOCIATED TYPE (having) ───────────────────────────

    @Test fun `assoc - concrete receiver resolves out to Str`() {
        assertTrue(typeOf("f", "$assoc\npub let f = {} -> (5).produce {}")!!.contains("Str"))
    }
    @Test fun `assoc - generic receiver renders having as a var`() {
        val t = typeOf("f", "$assoc\npub let f = { x } -> x.produce {}")
        assertTrue(Regex("""having 'out = '\w+""").containsMatchIn(t!!), "got: $t")
    }
    @Test fun `assoc - generic with annotation, having omitted`() {
        assertNull(err("$assoc\npub let f: { x: 'a } with Producer 'a -> 'out = { x } -> x.produce {}"))
    }
    @Test fun `assoc - generic with annotation, correct having`() {
        assertNull(err("$assoc\npub let f: { x: 'a } with Producer 'a having 'out = Str -> Str = { x } -> x.produce {}"))
    }
    @Test fun `assoc - generic with annotation, WRONG having rejected`() {
        assertNotNull(err("$assoc\npub let f: { x: 'a } with Producer 'a having 'out = Int -> Int = { x } -> x.produce {}"))
    }
    @Test fun `assoc - concrete receiver, WRONG having annotation rejected`() {
        assertNotNull(err("$assoc\npub let f: {} -> Int = {} -> (5).produce {}"))
    }
    @Test fun `assoc - having for a nonexistent associated type is rejected`() {
        assertNotNull(err("$assoc\npub let f: { x: 'a } with Producer 'a having 'nope = Str -> Str = { x } -> x.produce {}"))
    }

    // ── 3. MULTI-PARAM ability (no associated type) ─────────────────────

    @Test fun `multi - concrete receiver`() {
        assertNull(err("$multi\npub let f: {} -> Int = {} -> (10).scale { by = 3 }"))
    }
    @Test fun `multi - generic receiver, pinned arg + single impl resolves (bidirectional)`() {
        // by=3 pins 'by=Int; with the sole `Scale Int for Int` impl, bidirectional
        // inference resolves the receiver too. Sound (single inhabitant) and is the
        // same mechanism codec dispatch relies on. NOT a dropped constraint.
        assertEquals("{ x: Int } -> Int", typeOf("f", "$multi\npub let f = { x } -> x.scale { by = 3 }"))
    }
    @Test fun `multi - generic receiver AND generic arg keeps the constraint`() {
        val t = typeOf("f", "$multi\npub let f = { x, y } -> x.scale { by = y }")
        assertTrue(t!!.contains("Scale"), "nothing pinned → constraint kept, got: $t")
    }
    @Test fun `multi - with annotation`() {
        assertNull(err("$multi\npub let f: { x: 'a } with Scale Int 'a -> 'a = { x } -> x.scale { by = 3 }"))
    }
    @Test fun `multi - emits exactly one constraint (no doubling)`() {
        val t = typeOf("f", "$multi\npub let f = { x } -> x.scale { by = 3 }")
        assertFalse(Regex("""Scale '\w+ '\w+ '\w+""").containsMatchIn(t!!), "got: $t")
    }

    // ── 4. MULTI-PARAM + ASSOCIATED TYPE (the full combination) ─────────

    @Test fun `multi+assoc - concrete receiver resolves out`() {
        assertNull(err("$multiAssoc\npub let f: {} -> Int = {} -> (10).times { by = 3 }"))
    }
    @Test fun `multi+assoc - concrete receiver, WRONG having rejected`() {
        assertNotNull(err("$multiAssoc\npub let f: {} -> Str = {} -> (10).times { by = 3 }"))
    }
    @Test fun `multi+assoc - generic receiver keeps constraint + having`() {
        // Both receiver and arg generic (nothing pinned) → constraint kept, having is a var.
        val t = typeOf("f", "$multiAssoc\npub let f = { x, y } -> x.times { by = y }")
        assertTrue(t!!.contains("Times") && Regex("""having 'out = '\w+""").containsMatchIn(t), "got: $t")
    }
    @Test fun `multi+assoc - generic with annotation, correct having`() {
        assertNull(err("$multiAssoc\npub let f: { x: 'a } with Times Int 'a having 'out = Int -> 'out = { x } -> x.times { by = 3 }"))
    }
    @Test fun `multi+assoc - generic with annotation, WRONG having rejected`() {
        assertNotNull(err("$multiAssoc\npub let f: { x: 'a } with Times Int 'a having 'out = Str -> 'out = { x } -> x.times { by = 3 }"))
    }

    // ── 5. NEWTYPE / custom receiver (the Money scenario) ───────────────

    @Test fun `newtype - single-param ability on a bare newtype literal`() {
        assertNull(err("""
            newtype Money = Int
            ability Show1 for 'a where { show1: { it: 'a } -> Int }
            impl Show1 for Money where { show1 = { it } -> 0 }
            pub let f = {} -> (Money.(5)).show1 {}
        """.trimIndent()))
    }
    @Test fun `newtype - multi-param having ability on a bare newtype literal`() {
        assertNull(err("""
            newtype Money = Int
            ability ScaleMoney 'b for 'a having 'out where { times: { it: 'a, by: 'b } -> 'out }
            impl ScaleMoney Int for Money having 'out = Money where {
              times = { it, by } -> match it is Money.(val) -> Money.(val)
            }
            pub let f = {} -> (Money.(5)).times { by = 3 }
        """.trimIndent()))
    }
    @Test fun `newtype - via annotated parameter`() {
        assertNull(err("""
            newtype Money = Int
            ability ScaleMoney 'b for 'a having 'out where { times: { it: 'a, by: 'b } -> 'out }
            impl ScaleMoney Int for Money having 'out = Money where {
              times = { it, by } -> match it is Money.(val) -> Money.(val)
            }
            pub let f = { m: Money } -> m.times { by = 3 }
        """.trimIndent()))
    }

    // ── 6. MULTIPLE associated types on one ability ─────────────────────

    @Test fun `multiple associated types - both declared, one used`() {
        val t = typeOf("f", """
            ability KV for 'c having 'k, 'v where { first_key: { it: 'c } -> 'k }
            impl KV for [Int] having 'k = Int, 'v = Str where { first_key = { it } -> 0 }
            pub let f = { xs } -> xs.first_key {}
        """.trimIndent())
        assertTrue(Regex("""having 'k = '\w+""").containsMatchIn(t!!), "got: $t")
    }
}
