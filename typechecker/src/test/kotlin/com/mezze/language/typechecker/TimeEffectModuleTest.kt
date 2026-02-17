package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for std/effects/time module functions.
 * Verifies that importing and using time effect functions works correctly.
 */
class TimeEffectModuleTest {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        // Set project root for module resolution
        System.setProperty("mezze.project.root", "/Users/shriyans/Projects/mezze")
    }

    private fun parseAndTypeCheck(source: String): com.mezze.language.ast.Program {
        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        return result.program
    }

    @Test
    fun `test SystemTime now function`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let time = Time.now {}
                    time
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemTime now_nanos function`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let time = Time.now_nanos {}
                    time
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemTime sleep function`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in Time.sleep { millis = 100 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemTime sleep_nanos function`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in Time.sleep_nanos { nanos = 1000000 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemTime current_year function`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let year = Time.current_year {}
                    year
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemTime current_month function`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let month = Time.current_month {}
                    month
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemTime current_day function`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let day = Time.current_day {}
                    day
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemTime current_hour function`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let hour = Time.current_hour {}
                    hour
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemTime current_minute function`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let minute = Time.current_minute {}
                    minute
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemTime current_second function`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let second = Time.current_second {}
                    second
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemTime format_timestamp function`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let formatted = Time.format_timestamp {
                    timestamp = 1609459200000,
                    format = "yyyy-MM-dd"
                }
                    formatted
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemTime parse_timestamp function`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let timestamp = Time.parse_timestamp {
                    date_string = "2021-01-01",
                    format = "yyyy-MM-dd"
                }
                    timestamp
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test MockTime with fixed timestamp`() {
        val source = """
            use std::effects::time::{Time, MockTime}
            let main = {} ->
                perform MockTime { fixed_time = 1609459200000 } in do
                    let year = Time.current_year {}
                    year
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test combining time operations`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let now = Time.now {}
                    let formatted = Time.format_timestamp {
                        timestamp = now,
                        format = "yyyy-MM-dd HH:mm:ss"
                    }
                    formatted
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
