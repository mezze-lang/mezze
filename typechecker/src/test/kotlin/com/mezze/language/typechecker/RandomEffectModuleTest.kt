package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for std/effects/random module functions.
 * Verifies that importing and using random effect functions works correctly.
 */
class RandomEffectModuleTest {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        // Set project root for module resolution
        TestUtils.setupProjectRoot()
    }

    private fun parseAndTypeCheck(source: String): com.mezze.language.ast.Program {
        val program = parser.parse(source)
        return typeChecker.typeCheck(program).program
    }

    @Test
    fun `test SeededRandom float function`() {
        val source = """
            use std::effects::random::{Random, SeededRandom}
            let main = {} ->
                perform SeededRandom { seed = 42 } in do
                    let r = Random.random_float {}
                    r
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SeededRandom int function`() {
        val source = """
            use std::effects::random::{Random, SeededRandom}
            let main = {} ->
                perform SeededRandom { seed = 42 } in do
                    let r = Random.random_int { min = 0, max = 100 }
                    r
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SeededRandom nat function`() {
        val source = """
            use std::effects::random::{Random, SeededRandom}

            let natWrapper = { n: Nat } -> n

            let main = {} ->
                perform SeededRandom { seed = 42 } in do
                    let r = Random.random_nat { max = natWrapper { n = 100n } }
                    r
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SeededRandom update_seed function`() {
        val source = """
            use std::effects::random::{Random, SeededRandom}
            let main = {} ->
                perform SeededRandom { seed = 42 } in Random.seed { seed = 100 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test multiple random generations with same seed`() {
        val source = """
            use std::effects::random::{Random, SeededRandom}
            let main = {} ->
                perform SeededRandom { seed = 42 } in do
                    let r1 = Random.random_int { min = 0, max = 100 }
                    let r2 = Random.random_int { min = 0, max = 100 }
                    r1
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test random distribution functions`() {
        val source = """
            use std::effects::random::{Random, SeededRandom, normal}
            let main = {} ->
                perform SeededRandom { seed = 42 } in do
                    let result = normal { mean = 0.0, std_dev = 1.0 }
                    result
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test exponential distribution`() {
        val source = """
            use std::effects::random::{Random, SeededRandom, exponential}
            let main = {} ->
                perform SeededRandom { seed = 42 } in do
                    let result = exponential { rate = 1.0 }
                    result
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test uniform float distribution`() {
        val source = """
            use std::effects::random::{Random, SeededRandom, uniform}
            let main = {} ->
                perform SeededRandom { seed = 42 } in do
                    let result = uniform { min = 0.0, max = 1.0 }
                    result
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test deterministic random sequence`() {
        val source = """
            use std::effects::random::{Random, SeededRandom}
            let main = {} ->
                perform SeededRandom { seed = 42 } in do
                    let r1 = Random.random_int { min = 0, max = 100 }
                    let r2 = Random.random_int { min = 0, max = 100 }
                    let r3 = Random.random_int { min = 0, max = 100 }
                    r3
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
