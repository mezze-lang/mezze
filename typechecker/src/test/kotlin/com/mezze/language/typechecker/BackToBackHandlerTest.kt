package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Installing the same effect's handler twice back to back.
 *
 * `perform A, A in e` — one list, two handlers for one effect — has always been a
 * hard error. `perform A in perform A in e` is the same mistake spelled the other
 * way: the inner handler takes every operation `e` performs, so the outer can
 * never fire. It was accepted in silence.
 *
 * The line this draws is structural, and it has to be: SHADOWING is a feature.
 * `Reader`'s `local` IS a nested `WithReader`, and `perform SystemTime in perform
 * LoomAsync in …` is ordinary composition. What separates those from the mistake
 * is whether anything sits BETWEEN the two installs — so the check asks only
 * "is the body *exactly* the inner `perform`?" and never consults an inferred
 * effect row.
 *
 * That restraint was learned the expensive way. Dropping the `!isNestedPerform`
 * guard on the existing row-based checks — which looks redundant, since
 * `shouldValidate` already carries the nested case — makes them fire on legitimate
 * nesting and breaks twelve tests, `Reader`'s own shadowing among them. The row
 * for a nested body is not reliable enough to carry an error. This check does not
 * use it.
 */
class BackToBackHandlerTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private val base = """
        effect Log where {
          info: { msg: Str } -> {} <Log>
        }

        effect Metric where {
          count: { name: Str } -> {} <Metric>
        }

        impl Log for ConsoleLog where {
          info = { msg } -> {}
        }

        impl Log for OtherLog where {
          info = { msg } -> {}
        }

        impl Metric for StatsD where {
          count = { name } -> {}
        }

        let logOnly = {} -> Log.info { msg = "hi" }

        let both = {} -> do
          Log.info { msg = "hi" }
          Metric.count { name = "hits" }
    """.trimIndent()

    private fun errorFor(tail: String): String? =
        runCatching {
            typeChecker.typeCheck(parser.parseParsedModule("$base\n\n$tail", "test.mz"), Paths.get("."))
        }.exceptionOrNull()?.message

    @Test
    fun `the same handler back to back is refused`() {
        val e = errorFor("let main = {} -> perform ConsoleLog in perform ConsoleLog in logOnly {}")
        assertTrue(
            e?.contains("can never fire") == true && e.contains("ConsoleLog"),
            "expected the back-to-back rejection, got: $e",
        )
    }

    @Test
    fun `two different handlers for one effect, back to back, are refused`() {
        val e = errorFor("let main = {} -> perform OtherLog in perform ConsoleLog in logOnly {}")
        assertTrue(
            e?.contains("can never fire") == true && e.contains("OtherLog"),
            "expected the back-to-back rejection naming the outer handler, got: $e",
        )
    }

    @Test
    fun `the message names both handlers and how to keep both`() {
        val e = errorFor("let main = {} -> perform OtherLog in perform ConsoleLog in logOnly {}")
        assertTrue(e?.contains("OtherLog") == true, "should name the outer handler; got: $e")
        assertTrue(e?.contains("ConsoleLog") == true, "should name the inner handler; got: $e")
    }

    // ── What must keep working ────────────────────────────────────────────

    @Test
    fun `different effects nested back to back are ordinary composition`() {
        val e = errorFor("let main = {} -> perform StatsD in perform ConsoleLog in both {}")
        assertTrue(e == null, "nesting two DIFFERENT effects must stay legal, got: $e")
    }

    @Test
    fun `the same effect nested with code between the two installs is shadowing`() {
        // This is `Reader`'s `local`, and the outer handler is live for whatever
        // runs outside the inner `perform`. The body here is a do-block, not the
        // inner `perform` itself, which is exactly the distinction.
        val e = errorFor(
            """
            let main = {} -> perform OtherLog in do
              Log.info { msg = "outer" }
              perform ConsoleLog in logOnly {}
            """.trimIndent(),
        )
        assertTrue(e == null, "shadowing with code between the installs must stay legal, got: $e")
    }

    @Test
    fun `one handler for one effect is untouched`() {
        val e = errorFor("let main = {} -> perform ConsoleLog in logOnly {}")
        assertTrue(e == null, "the ordinary single-handler case must stay legal, got: $e")
    }
}
