package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * A non-exhaustive `match` is reported wherever it is written.
 *
 * `MatchValidator` FINDS the matches to check by walking the expression tree, so
 * a position the walk does not reach is a position where exhaustiveness is not
 * checked at all — silently, since nothing reports a match it never saw.
 *
 * That walk was hand-written and ended in `else -> {}`, which skipped
 * `ParsedBinaryOp`, `ParsedNewtypeConstruct`, `ParsedEffectCall`, a match's own
 * `ifLetBindings` and a `perform`'s handler params. `1 + (match … )` was the
 * easiest of those to write by accident.
 */
class MatchFoundInEveryPositionTest {

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
            TypeChecker().typeCheck(parser.parseParsedModule(src.trimIndent(), "m.mz"), Paths.get("."))
            null
        } catch (e: Throwable) {
            e.message ?: e::class.simpleName ?: "rejected"
        }
    }

    /** A record-product match over `{ M, Bool }` missing exactly one combination. */
    private fun nonExhaustive(wrap: (String) -> String) = """
        type M = | Get, Post |
        let f = { r: { m: M, k: Bool } } -> ${wrap("""match r is
          { m = Get, k = True } -> 1
          { m = Get, k = False } -> 2
          { m = Post, k = True } -> 3""")}
        let main = {} -> f { r = { m = Get, k = True } }
    """

    private fun assertReported(position: String, src: String) {
        val msg = check(src)
        assertTrue(
            msg != null && msg.contains("Post") && msg.contains("False"),
            "a non-exhaustive match $position was not reported — the walk that finds " +
                "matches never reached it. Got: ${msg ?: "accepted"}"
        )
    }

    @Test
    fun `at the top of a function body`() =
        assertReported("at the top of a body", nonExhaustive { it })

    @Test
    fun `inside a binary operation`() =
        assertReported("inside a binary operation", nonExhaustive { "1 + ($it)" })

    @Test
    fun `inside a comparison`() =
        assertReported("inside a comparison", nonExhaustive { "if (($it) == 1) then 0 else 1" })

    @Test
    fun `an exhaustive match in the same position is accepted`() {
        // The control: the position must not reject everything put in it.
        val msg = check(
            """
            type M = | Get, Post |
            let f = { r: { m: M, k: Bool } } -> 1 + (match r is
              { m = Get, k = True } -> 1
              { m = Get, k = False } -> 2
              { m = Post, k = True } -> 3
              { m = Post, k = False } -> 4)
            let main = {} -> f { r = { m = Get, k = True } }
        """
        )
        assertTrue(msg == null, "an EXHAUSTIVE match inside a binary operation was rejected: $msg")
    }
}
