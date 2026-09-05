package com.mezze.interpreter.regression

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Bug: anonymous impl target not found through type alias.
 *
 * With an anonymous impl target `impl Summary for { user: Str }` and a
 * receiver annotated as the alias `p : Post`, dispatch fails: the anon impl
 * registers the structural record type while the alias-annotated receiver
 * carries the alias identity.
 *
 * Root cause: TypedASTCompiler.typeMatchesTarget compares anonymous record
 * against alias-tagged record as unequal.
 *
 * Fix: normalize alias to its shape so structural and named record types
 * compare equal in both directions.
 */
class AliasImplTargetTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `anonymous impl target, alias receiver — the failing combo`() {
        val src = """
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            type Post = { user: Str }
            impl Summary for { user: Str } where { summarize = { it } -> "@${'$'}(it.user)" }
            let p : Post = { user = "dev" }
            p.summarize {}
        """.trimIndent()
        assertEquals("@dev", eval(src))
    }

    @Test
    fun `anonymous impl target, bare record literal — already works`() {
        val src = """
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            impl Summary for { user: Str } where { summarize = { it } -> "@${'$'}(it.user)" }
            let p = { user = "dev" }
            p.summarize {}
        """.trimIndent()
        assertEquals("@dev", eval(src))
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
        // Ensures the fix isn't a single-candidate fallback: two anonymous
        // impls exist, but only one matches the alias-tagged receiver.
        val src = """
            ability Summary for 'a where { summarize: { it: 'a } -> Str }
            type Post = { user: Str }
            impl Summary for { user: Str } where { summarize = { it } -> "@${'$'}(it.user)" }
            impl Summary for { title: Str } where { summarize = { it } -> "#${'$'}(it.title)" }
            let p : Post = { user = "dev" }
            p.summarize {}
        """.trimIndent()
        assertEquals("@dev", eval(src))
    }
}
