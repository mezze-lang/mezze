package com.mezze.interpreter.regression

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dispatch to an impl through a type alias, and the rule that an impl target
 * must be NAMED.
 *
 * This class began as a bug about an ANONYMOUS impl target not being found
 * through an alias: `impl Summary for { user: Str }` registered the structural
 * record while a receiver annotated `p : Post` carried the alias identity, and
 * `typeMatchesTarget` compared the two as unequal. The fix normalized the alias
 * to its shape so both directions compared equal.
 *
 * An anonymous target is now refused outright -- an impl has to be attributable
 * to a declared type -- so the two cases that depended on one assert the refusal
 * instead of a result. What they were really guarding, dispatch reaching an impl
 * through an alias, is covered by the named-target cases, which are unchanged and
 * still pass; the normalization they exercise is what makes a bare record literal
 * reach a nominal target at all.
 */
class AliasImplTargetTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    /** The message a refused impl target produces, for the two cases below. */
    private fun refusal(source: String): String {
        val thrown = runCatching { eval(source) }.exceptionOrNull()
            ?: error("expected this impl target to be refused, but the program ran")
        return generateSequence(thrown) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    }

    @Test
    fun `an anonymous impl target is refused, alias receiver`() {
        val src = """
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            type Post = { user: Str }
            impl Summary for { user: Str } where { summarize = { it } -> "@${'$'}(it.user)" }
            let p : Post = { user = "dev" }
            p.summarize {}
        """.trimIndent()
        val msg = refusal(src)
        assertTrue(
            msg.contains("must target a named type"),
            "An impl has to be attributable to a declared type, so an inline record shape is " +
                "not a target. This test formerly expected `@dev`: the alias receiver DID reach " +
                "the anonymous impl, which was the bug fix this class was written for. The " +
                "dispatch it proved is now covered by the named-target cases.\n\ngot: " + msg,
        )
    }

    @Test
    fun `an anonymous impl target is refused, bare record literal`() {
        val src = """
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            impl Summary for { user: Str } where { summarize = { it } -> "@${'$'}(it.user)" }
            let p = { user = "dev" }
            p.summarize {}
        """.trimIndent()
        val msg = refusal(src)
        assertTrue(
            msg.contains("must target a named type"),
            "Refused at the IMPL, so it does not matter that the receiver here is a bare " +
                "literal rather than an alias -- there is no target to dispatch to either " +
                "way.\n\ngot: " + msg,
        )
    }

    @Test
    fun `named impl target, alias receiver — already works`() {
        val src = """
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            type Post = { user: Str }
            impl Summary for Post where { summarize = { it } -> "@${'$'}(it.user)" }
            let p : Post = { user = "dev" }
            p.summarize {}
        """.trimIndent()
        assertEquals("@dev", eval(src))
    }

    @Test
    fun `named impl target, bare record literal — already works`() {
        val src = """
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            type Post = { user: Str }
            impl Summary for Post where { summarize = { it } -> "@${'$'}(it.user)" }
            let p = { user = "dev" }
            p.summarize {}
        """.trimIndent()
        assertEquals("@dev", eval(src))
    }

    @Test
    fun `alias case resolves with TWO impls in scope`() {
        // Ensures the fix isn't a single-candidate fallback: two impls are in
        // scope, but only one matches the alias-tagged receiver. Both targets
        // are NAMED now -- with one impl, `tryResolveAbilityOp`'s sole-candidate
        // path answers correctly by accident and the test proves nothing.
        val src = """
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            type Post = { user: Str }
            type Note = { title: Str }
            impl Summary for Post where { summarize = { it } -> "@${'$'}(it.user)" }
            impl Summary for Note where { summarize = { it } -> "#${'$'}(it.title)" }
            let p : Post = { user = "dev" }
            p.summarize {}
        """.trimIndent()
        assertEquals("@dev", eval(src))
    }

    /**
     * The ARGUMENT position, with a field nothing determines.
     *
     * `chunks = None` is `Option 'a`, so adoption — which compares field types
     * exactly — cannot label the literal, and it reaches `take` as an ANONYMOUS
     * record. The nominal `impl Into for Resp` target does not match it, and
     * resolution fell through to a FIELD's impl: `Into Str`, off `url: Str`.
     * It answered "str" with no error anywhere.
     *
     * Two impls are load-bearing. With only `Into Resp`, the sole-candidate
     * fallback in `tryResolveAbilityOp` answers correctly by accident and the
     * test proves nothing.
     *
     * The minimal form of the HTTP case: a handler returning a bare
     * Response-shaped literal. See HttpIntoResponseTest.
     */
    @Test
    fun `undetermined literal in the argument position finds its alias impl`() {
        val src = """
            ability Into for 'x where { conv: { it: 'x } -> Str }
            type Resp = { status: Int, url: Str, chunks: Option Str }
            impl Into for Resp where { conv = { it } -> "resp" }
            impl Into for Str where { conv = { it } -> "str" }
            let take: { v: 'v } with Into 'v -> Str = { v } -> v.conv {}
            take { v = { status = 203, url = "", chunks = None } }
        """.trimIndent()
        assertEquals("resp", eval(src))
    }

    /** The sibling position, fixed earlier: the same literal as a RECEIVER. */
    @Test
    fun `undetermined literal as a receiver finds its alias impl`() {
        val src = """
            ability Into for 'x where { conv: { it: 'x } -> Str }
            type Resp = { status: Int, url: Str, chunks: Option Str }
            impl Into for Resp where { conv = { it } -> "resp" }
            impl Into for Str where { conv = { it } -> "str" }
            { status = 203, url = "", chunks = None }.conv {}
        """.trimIndent()
        assertEquals("resp", eval(src))
    }
}
