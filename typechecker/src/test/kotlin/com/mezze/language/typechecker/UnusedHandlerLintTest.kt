package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.diagnostics.WarningCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BUG 3 — a handler in a `perform` list that the body never exercises.
 *
 * The pre-existing V0510 error only fires when the body performs NOTHING, so a
 * dead handler standing next to a live one was accepted in silence:
 *
 *     perform Console, GraalPy, GraalJs in do   # nothing performs a Js op
 *
 * A reader takes that list at face value and assumes JS is in play. W0401 now
 * asks the same question once per handler.
 *
 * It is a WARNING, not an error, on purpose: the body's effect row is inferred,
 * and where other effects are already flowing the row is a weaker basis than the
 * all-pure case V0510 rejects outright. A warning that is occasionally imprecise
 * costs a line of noise; a spurious hard error costs a build.
 */
class UnusedHandlerLintTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun warningsFor(code: String) =
        typeChecker.typeCheck(parser.parseParsedModule(code, "test.mz"), Paths.get(".")).warnings

    private fun deadHandlersIn(code: String): List<String> =
        warningsFor(code)
            .filter { it.code == WarningCode.W0401_UNUSED_HANDLER }
            .map { it.message }

    private val twoEffects = """
        effect Log where {
          info: { msg: Str } -> {} <Log>
        }

        effect Metric where {
          count: { name: Str } -> {} <Metric>
        }

        impl Log for ConsoleLog where {
          info = { msg } -> {}
        }

        impl Metric for StatsD where {
          count = { name } -> {}
        }

        let logOnly = {} -> Log.info { msg = "hi" }
    """.trimIndent()

    @Test
    fun `a dead handler beside a live one warns`() {
        val messages = deadHandlersIn(
            """
            $twoEffects

            let main = {} -> perform ConsoleLog, StatsD in logOnly {}
            """.trimIndent()
        )
        assertTrue(
            messages.any { it.contains("StatsD") && it.contains("Metric") },
            "the unexercised Metric handler should warn, got: $messages",
        )
        assertFalse(
            messages.any { it.contains("ConsoleLog") },
            "the handler that DOES fire must not warn, got: $messages",
        )
    }

    @Test
    fun `every handler exercised means no warning`() {
        val messages = deadHandlersIn(
            """
            $twoEffects

            let both = {} -> do
              Log.info { msg = "hi" }
              Metric.count { name = "hits" }

            let main = {} -> perform ConsoleLog, StatsD in both {}
            """.trimIndent()
        )
        assertTrue(messages.isEmpty(), "no handler is dead here, got: $messages")
    }

    @Test
    fun `a handler whose effect only the OTHER handler performs is not dead`() {
        // StatsD's own impl performs Log, so ConsoleLog is reached transitively
        // even though the body never performs Log directly. performerAddedEffects
        // carries that; the check must consult it rather than the body's row alone.
        val messages = deadHandlersIn(
            """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            effect Metric where {
              count: { name: Str } -> {} <Metric>
            }

            impl Log for ConsoleLog where {
              info = { msg } -> {}
            }

            impl Metric for StatsD where {
              count = { name } -> Log.info { msg = name }
            }

            let metricOnly = {} -> Metric.count { name = "hits" }

            let main = {} -> perform ConsoleLog, StatsD in metricOnly {}
            """.trimIndent()
        )
        assertFalse(
            messages.any { it.contains("ConsoleLog") },
            "a transitively-reached handler must not be called dead, got: $messages",
        )
    }

    @Test
    fun `the warning names the handler and how to fix it`() {
        val messages = deadHandlersIn(
            """
            $twoEffects

            let main = {} -> perform ConsoleLog, StatsD in logOnly {}
            """.trimIndent()
        )
        val warning = messages.single { it.contains("StatsD") }
        assertTrue(warning.contains("never fires"), "should say what goes wrong: $warning")
        assertTrue(warning.contains("Remove 'StatsD'"), "should say what to do: $warning")
    }
}
