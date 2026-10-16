package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A variable referenced only inside a performer's parameter record is USED.
 *
 * `generatePerform` typechecked the handler-parameter record and then took two
 * fields off the result — `constraints` and `abilityConstraints` — discarding
 * the rest. `usedVariables` was among the rest, so the #84 unused-variable
 * linter never saw the reference and warned about a binding consumed by the
 * very next line. `referencedBindings` and `undefinedVariables` were lost the
 * same way.
 *
 * Reported from the website gate, which treats a warning as a failure: the
 * `effects` lesson sample binds `rules` and passes it straight to
 * `perform MockHttpClient { rules = rules }`.
 */
class PerformerParamUseTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun warningsFor(code: String): Pair<TypeCheckResult, List<String>> {
        val result = typeChecker.typeCheck(parser.parseParsedModule(code, "test.mz"), Paths.get("."))
        return Pair(result, result.warnings.map { it.message })
    }

    @Test
    fun `a binding used only in a performer parameter is not reported unused`() {
        val source = """
            use std::datetime::{ epoch, test_clock_at, duration_seconds, instant_to_millis }
            use std::effects::time::{Time, MockTime}
            let main = {} -> do
                let clock = test_clock_at { i = epoch {} }
                perform MockTime { clock = clock } in do
                    Time.sleep { d = duration_seconds { n = 5 } }
                    instant_to_millis { i = Time.now {} }
        """.trimIndent()

        val (result, warnings) = warningsFor(source)
        assertNotNull(result)
        assertFalse(
            warnings.any { it.contains("Unused variable") && it.contains("clock") },
            "`clock` is passed to `perform MockTime { clock = clock }`, so it is used. " +
                "Warnings: $warnings",
        )
    }

    /**
     * The linter must still fire, or the test above would pass by having
     * switched the whole warning off.
     */
    @Test
    fun `a binding used nowhere is still reported unused`() {
        val source = """
            use std::datetime::{ epoch, test_clock_at, duration_seconds, instant_to_millis }
            use std::effects::time::{Time, MockTime}
            let main = {} -> do
                let clock = test_clock_at { i = epoch {} }
                let spare = test_clock_at { i = epoch {} }
                perform MockTime { clock = clock } in do
                    Time.sleep { d = duration_seconds { n = 5 } }
                    instant_to_millis { i = Time.now {} }
        """.trimIndent()

        val (result, warnings) = warningsFor(source)
        assertNotNull(result)
        assertTrue(
            warnings.any { it.contains("Unused variable") && it.contains("spare") },
            "`spare` is referenced nowhere and must still warn. Warnings: $warnings",
        )
        assertFalse(
            warnings.any { it.contains("Unused variable") && it.contains("clock") },
            "`clock` is used in the performer parameter. Warnings: $warnings",
        )
    }
}
