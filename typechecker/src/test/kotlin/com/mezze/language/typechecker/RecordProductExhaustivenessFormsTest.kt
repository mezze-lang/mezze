package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * The ways to cover a record PRODUCT — `{ m: M, k: Bool }` is 2×2 cases, not 2 —
 * and what each one is worth.
 *
 * Matching on a record of enumerable fields means the case count multiplies, so
 * the question is not just "does the checker know the count" but "can the user
 * write something other than every combination". Each form below answers a
 * different way, and all of them work; this file exists because none of them had
 * a test, which is how the capability came to be filed as missing.
 */
class RecordProductExhaustivenessFormsTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
    }

    private fun check(src: String): String? {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        return try {
            TypeChecker().typeCheck(parser.parseParsedModule(src.trimIndent(), "r.mz"), Paths.get("."))
            null
        } catch (e: Throwable) {
            e.message ?: e::class.simpleName ?: "rejected"
        }
    }

    private fun accepts(label: String, src: String) {
        val msg = check(src)
        assertTrue(msg == null, "$label was rejected: $msg")
    }

    private val m = "type M = | Get, Post |\n"
    private fun prog(arms: String, fields: String = "{ m: M, k: Bool }", call: String = "{ m = Get, k = True }") =
        m + "let f = { r: $fields } -> match r is\n$arms\nlet main = {} -> f { r = $call }"

    @Test
    fun `a bare wildcard covers the rest`() =
        accepts("a bare `_`", prog("  { m = Get, k = True } -> 1\n  _ -> 0"))

    @Test
    fun `a per-field wildcard covers the rest`() =
        accepts("`{ m = _, k = _ }`", prog("  { m = Get, k = True } -> 1\n  { m = _, k = _ } -> 0"))

    @Test
    fun `pinning one field and wildcarding the other splits the product`() =
        accepts(
            "one field pinned per arm",
            prog("  { m = Get, k = _ } -> 1\n  { m = Post, k = _ } -> 0")
        )

    @Test
    fun `a diagonal plus a catch-all is enough`() =
        accepts(
            "two diagonal arms then `_`",
            prog("  { m = Get, k = True } -> 1\n  { m = Post, k = False } -> 2\n  _ -> 0")
        )

    @Test
    fun `a named binding works as a catch-all`() =
        accepts("a binding arm", prog("  { m = Get, k = True } -> 1\n  other -> 0"))

    @Test
    fun `spelling out every combination works without a catch-all`() =
        accepts(
            "the full 2x2",
            prog(
                "  { m = Get, k = True } -> 1\n  { m = Get, k = False } -> 2\n" +
                    "  { m = Post, k = True } -> 3\n  { m = Post, k = False } -> 4"
            )
        )

    @Test
    fun `a three-field product accepts a mixed wildcard and catch-all`() =
        accepts(
            "3 fields",
            prog(
                "  { m = Get, k = True, n = _ } -> 1\n  _ -> 0",
                fields = "{ m: M, k: Bool, n: Bool }",
                call = "{ m = Get, k = True, n = False }",
            )
        )

    @Test
    fun `a nested record product is covered by an outer catch-all`() =
        accepts(
            "nested",
            prog(
                "  { a = { m = Get }, k = True } -> 1\n  _ -> 0",
                fields = "{ a: { m: M }, k: Bool }",
                call = "{ a = { m = Get }, k = True }",
            )
        )

    // ---- and the two directions it must still refuse ----

    @Test
    fun `a missing combination is still reported`() {
        val msg = check(
            prog("  { m = Get, k = True } -> 1\n  { m = Get, k = False } -> 2\n  { m = Post, k = True } -> 3")
        )
        assertTrue(
            msg != null && msg.contains("Post") && msg.contains("False"),
            "the missing `{ m = Post, k = False }` combination was not reported: ${msg ?: "accepted"}"
        )
    }

    @Test
    fun `a catch-all written first makes the rest unreachable`() {
        val msg = check(prog("  _ -> 0\n  { m = Get, k = True } -> 1"))
        assertTrue(
            msg != null && msg.contains("nreachable"),
            "an arm after a leading catch-all should be reported unreachable: ${msg ?: "accepted"}"
        )
    }
}
