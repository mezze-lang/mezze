package com.mezze.language.codebase

import com.mezze.language.comptime.HostValue
import com.mezze.language.store.InMemoryMezzeStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The syntax tree says what was written; `facts` says what the compiler made of
 * it — across every way the compiler rewrites code. The join is by span, so each
 * category of rewrite is checked here: an operator becomes a method call, a
 * method call moves its receiver, a `True`/`False` match becomes an `if`, pipes
 * are rewritten before type checking, parentheses and a newtype wrapper vanish,
 * a destructuring `let` becomes several, a question runs at build.
 */
class SyntaxFactsTest {

    private val source = """
        use std::codebase::{ Codebase, ComptimeEvalCodebase }
        use std::collections::seq
        use std::abilities::compare::{ Eq, Ord }

        type Money = { cents: Int }
        newtype Meters = Float
        impl Ord for Money where {
          lt = { it, r } -> it.cents < r.cents,
          le = { it, r } -> it.cents <= r.cents,
          gt = { it, r } -> it.cents > r.cents,
          ge = { it, r } -> it.cents >= r.cents,
        }
        ability Total for 'a where { total: { it: 'a } -> Int }
        impl Total for Money where { total = { it } -> it.cents }

        let double: { x: Int } -> Int = { x } -> x * 2
        let probe = { a: Int, b: Int, m: Money, n: Money } -> do
          let bigger = a > b
          let cheaper = m < n
          let summed = m.total {}
          let called = double { x = 1 }
          let piped = 5 |> double { x = @ }
          let grouped = (a + b)
          let length = Meters.(1.5)
          let flag = match bigger is
            True -> 1
            False -> 0
          let { cents } = m
          let asked = perform ComptimeEvalCodebase in seq::size { xs = Codebase.decls { module = "app" } }
          called + piped + grouped + flag + cents + asked
    """.trimIndent()

    private class Node(val tag: String, val line: Int, payload: Map<String, HostValue>) {
        val facts: Map<String, HostValue>? =
            ((payload["facts"] as HostValue.Opt).value as? HostValue.Record)?.fields?.toMap()
        private fun ref(key: String): Map<String, HostValue>? =
            ((facts?.get(key) as? HostValue.Opt)?.value as? HostValue.Record)?.fields?.toMap()
        fun calls(): String? = (ref("calls")?.get("name") as? HostValue.Str)?.value
        fun callsKind(): String? = (ref("calls")?.get("kind") as? HostValue.Variant)?.tag
        fun refersTo(): String? = (ref("refers_to")?.get("name") as? HostValue.Str)?.value
        fun tyTag(): String? = (facts?.get("ty") as? HostValue.Variant)?.tag
        fun atBuild(): Boolean = (facts?.get("at_build") as HostValue.Bool).value
    }

    private lateinit var nodes: List<Node>

    @BeforeEach
    fun index() {
        val store = InMemoryMezzeStore()
        indexSource(store, source)
        val q = CodebaseQueries(store)
        val tree = CodebaseHostAdapter(q).syntax(q.resolve("app", "probe")!!.hash.hex)
        val out = mutableListOf<Node>()
        fun walk(v: HostValue) {
            when (v) {
                is HostValue.Variant -> {
                    val f = v.payload?.fields?.toMap().orEmpty()
                    if ("facts" in f) {
                        val span = (f["span"] as HostValue.Record).fields.toMap()
                        out += Node(v.tag, (span["start_line"] as HostValue.IntV).value.toInt(), f)
                    }
                    v.payload?.let(::walk)
                }
                is HostValue.Record -> v.fields.forEach { (_, x) -> walk(x) }
                is HostValue.Seq -> v.items.forEach(::walk)
                is HostValue.Opt -> v.value?.let(::walk)
                is HostValue.Str, is HostValue.IntV, is HostValue.Bool, is HostValue.FloatV -> Unit
            }
        }
        walk(tree)
        nodes = out
    }

    /** The first node with [tag] on source line [line] of the module (1-based). */
    private fun node(tag: String, line: Int): Node =
        nodes.firstOrNull { it.tag == tag && it.line == line }
            ?: error("no $tag on line $line: ${nodes.filter { it.line == line }.map { it.tag }}")

    @Test
    fun `an operator on Int is a built-in, typed, reaching no impl`() {
        val gt = node("BinaryExpr", 18)
        assertNotNull(gt.facts, "typed at its own span, though the compiler made it a call")
        assertNull(gt.calls())
    }

    @Test
    fun `an operator on a user type reaches that type's impl, as does a method`() {
        val lt = node("BinaryExpr", 19)
        assertEquals("ImplDecl", lt.callsKind(), "`m < n` reaches `Ord for Money`")
        val total = node("CallExpr", 20)
        assertEquals("ImplDecl", total.callsKind(), "`m.total {}` reaches `Total for Money`")
        assertNotEquals(lt.calls(), total.calls())
    }

    @Test
    fun `a call reaches its function, and the name refers to it`() {
        assertEquals("double", node("CallExpr", 21).calls())
        assertEquals("double", node("NameExpr", 21).refersTo())
    }

    @Test
    fun `a pipe and its placeholder are written, not typed - the call inside is`() {
        // Pipes are rewritten before type checking: nothing typed sits at the
        // pipe's span or at `@`'s. What the pipe calls is typed where it is written.
        assertNull(node("PipeExpr", 22).facts)
        assertNull(node("PlaceholderExpr", 22).facts)
        assertEquals("double", node("CallExpr", 22).calls())
    }

    @Test
    fun `parentheses and a newtype wrapper carry nothing - what they wrap does`() {
        assertNull(node("ParensExpr", 23).facts)
        assertNotNull(node("BinaryExpr", 23).facts)
        assertNull(node("NewtypeExpr", 24).facts)
        assertNotNull(node("FloatExpr", 24).facts)
    }

    @Test
    fun `a match on True and False is typed, though the compiler made it an if`() {
        assertEquals("TInt", node("MatchExpr", 25).tyTag())
    }

    @Test
    fun `a destructured value is typed where it is written`() {
        assertEquals("TRecord", node("NameExpr", 28).tyTag())
    }

    @Test
    fun `the question runs at build, and nothing around it does`() {
        val asked = nodes.filter { it.line == 29 && it.facts != null && it.atBuild() }
        assertEquals(listOf("CallExpr"), asked.map { it.tag }, "only `Codebase.decls { … }` runs at build")
        assertTrue(nodes.filter { it.line == 29 && it.tag == "PerformExpr" }.none { it.atBuild() })
    }
}
