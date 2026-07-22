package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for std/effects/time module against the redesigned 3-op Time
 * effect (#72/#75): now, monotonic, sleep. Values are opaque Instant/
 * Duration from std::datetime; calendar/format live in std::datetime.
 */
class TimeEffectModuleTest {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        TestUtils.setupProjectRoot()
    }

    private fun parseAndTypeCheck(source: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    @Test
    fun `Time now returns Instant under SystemTime`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let i = Time.now {}
                    i
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `Time monotonic returns Duration under SystemTime`() {
        val source = """
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let d = Time.monotonic {}
                    d
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `Time sleep takes a Duration argument`() {
        val source = """
            use std::datetime::{ duration_millis }
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in
                    Time.sleep { d = duration_millis { n = 100 } }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `FrozenTime with fixed Instant`() {
        val source = """
            use std::datetime::{ instant_from_millis }
            use std::effects::time::{Time, FrozenTime}
            let main = {} ->
                perform FrozenTime { fixed = instant_from_millis { n = 1609459200000 } } in do
                    let i = Time.now {}
                    i
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `MockTime with advancing TestClock`() {
        val source = """
            use std::datetime::{ epoch, test_clock_at, duration_seconds, instant_to_millis }
            use std::effects::time::{Time, MockTime}
            let main = {} -> do
                let clock = test_clock_at { i = epoch {} }
                perform MockTime { clock = clock } in do
                    Time.sleep { d = duration_seconds { n = 5 } }
                    instant_to_millis { i = Time.now {} }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `Instant codec roundtrip typechecks`() {
        val source = """
            use std::datetime::{ epoch, instant_encode, instant_decode }
            let main = {} -> instant_decode { s = instant_encode { i = epoch {} } }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `Duration codec roundtrip typechecks`() {
        val source = """
            use std::datetime::{ duration_seconds, duration_encode, duration_decode }
            let main = {} -> duration_decode { s = duration_encode { d = duration_seconds { n = 1 } } }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `Zone codec roundtrip typechecks`() {
        val source = """
            use std::datetime::{ utc, zone_encode, zone_decode }
            let main = {} -> zone_decode { s = zone_encode { z = utc {} } }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `current_year sugar under SystemTime`() {
        val source = """
            use std::datetime::{ utc }
            use std::effects::time::{Time, SystemTime, current_year}
            let main = {} ->
                perform SystemTime in
                    current_year { z = utc {} }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `current_month sugar under SystemTime`() {
        val source = """
            use std::datetime::{ utc }
            use std::effects::time::{Time, SystemTime, current_month}
            let main = {} ->
                perform SystemTime in
                    current_month { z = utc {} }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `current_day sugar under SystemTime`() {
        val source = """
            use std::datetime::{ utc }
            use std::effects::time::{Time, SystemTime, current_day}
            let main = {} ->
                perform SystemTime in
                    current_day { z = utc {} }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `current_hour sugar under SystemTime`() {
        val source = """
            use std::datetime::{ utc }
            use std::effects::time::{Time, SystemTime, current_hour}
            let main = {} ->
                perform SystemTime in
                    current_hour { z = utc {} }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `current_minute sugar under SystemTime`() {
        val source = """
            use std::datetime::{ utc }
            use std::effects::time::{Time, SystemTime, current_minute}
            let main = {} ->
                perform SystemTime in
                    current_minute { z = utc {} }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `current_second sugar under SystemTime`() {
        val source = """
            use std::datetime::{ utc }
            use std::effects::time::{Time, SystemTime, current_second}
            let main = {} ->
                perform SystemTime in
                    current_second { z = utc {} }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `format Instant with pattern under SystemTime`() {
        val source = """
            use std::datetime::{ utc, format }
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let i = Time.now {}
                    format { i = i, p = "yyyy-MM-dd", z = utc {} }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `parse date string with pattern returns Result Instant`() {
        val source = """
            use std::datetime::{ utc, parse }
            let main = {} ->
                parse { s = "2021-01-01", p = "yyyy-MM-dd", z = utc {} }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `FrozenTime with fixed Instant plus current_year sugar`() {
        val source = """
            use std::datetime::{ instant_from_millis, utc }
            use std::effects::time::{Time, FrozenTime, current_year}
            let main = {} ->
                perform FrozenTime { fixed = instant_from_millis { n = 1609459200000 } } in
                    current_year { z = utc {} }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `combining now with format`() {
        val source = """
            use std::datetime::{ utc, format }
            use std::effects::time::{Time, SystemTime}
            let main = {} ->
                perform SystemTime in do
                    let now = Time.now {}
                    format { i = now, p = "yyyy-MM-dd HH:mm:ss", z = utc {} }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }
}
