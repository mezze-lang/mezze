package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestrictiveSpreadTest {
    
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }
    
    private fun parseAndTypeCheck(code: String): Program {
        val program = parser.parse(code)
        return typeChecker.typeCheck(program).program
    }
    
    @Test
    fun `should allow field override with compatible types`() {
        val code = """
            let person = {name = "Alice", age = 30}
            let updated = {...person, age = 31}
        """.trimIndent()
        
        // This should type check successfully
        val result = parseAndTypeCheck(code)
        assertTrue(result.items.isNotEmpty())
    }
    
    @Test
    fun `should reject field addition attempts`() {
        val code = """
            let person = {name = "Alice", age = 30}
            let invalid = {...person, city = "NYC"}
        """.trimIndent()
        
        val exception = assertThrows<SpreadSyntaxError.FieldAdditionNotAllowed> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message.contains("Cannot add new field 'city' to record type"))
        assertTrue(exception.message.contains("Available fields: name, age"))
    }
    
    @Test
    fun `should reject spread of non-record types`() {
        val code = """
            let number = 42
            let invalid = {...number, field = "value"}
        """.trimIndent()
        
        val exception = assertThrows<SpreadSyntaxError.NonRecordSpread> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message.contains("Cannot spread non-record value"))
    }
    
    @Test
    fun `should allow multiple field overrides`() {
        val code = """
            let person = {name = "Alice", age = 30, city = "Boston"}
            let updated = {...person, age = 31, city = "NYC"}
        """.trimIndent()
        
        // This should type check successfully
        val result = parseAndTypeCheck(code)
        assertTrue(result.items.isNotEmpty())
    }
    
    @Test
    fun `should reject any new field addition`() {
        val code = """
            let person = {name = "Alice"}
            let invalid = {...person, age = 30}
        """.trimIndent()
        
        val exception = assertThrows<SpreadSyntaxError.FieldAdditionNotAllowed> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message.contains("Cannot add new field 'age' to record type"))
        assertTrue(exception.message.contains("Available fields: name"))
    }
    
    @Test
    fun `should allow spread without field overrides`() {
        val code = """
            let person = {name = "Alice", age = 30}
            let copy = {...person}
        """.trimIndent()
        
        // This should type check successfully
        val result = parseAndTypeCheck(code)
        assertTrue(result.items.isNotEmpty())
    }
    
    @Test
    fun `should work with nested record types`() {
        val code = """
            let address = {street = "Main St", city = "Boston"}
            let person = {name = "Alice", address = address}
            let updated = {...person, name = "Bob"}
        """.trimIndent()

        // This should type check successfully
        val result = parseAndTypeCheck(code)
        assertTrue(result.items.isNotEmpty())
    }

    // =========================================================================
    // TYPE ALIAS SPREAD TESTS
    // =========================================================================

    @Test
    fun `should spread type alias record`() {
        val code = """
            type Config = { timeout: Int, retries: Int }
            let default_config: Config = { timeout = 30, retries = 3 }
            let custom = { ...default_config, timeout = 60 }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `should spread type alias and result matches type alias`() {
        val code = """
            type Config = { timeout: Int, retries: Int }
            let default_config: Config = { timeout = 30, retries = 3 }
            let custom: Config = { ...default_config, timeout = 60 }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `should spread type alias with multiple overrides`() {
        val code = """
            type Config = { timeout: Int, retries: Int, verbose: Bool }
            let default_config: Config = { timeout = 30, retries = 3, verbose = False }
            let custom: Config = { ...default_config, timeout = 60, verbose = True }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `should reject field addition to type alias`() {
        val code = """
            type Config = { timeout: Int, retries: Int }
            let default_config: Config = { timeout = 30, retries = 3 }
            let invalid = { ...default_config, extra = "not allowed" }
        """.trimIndent()

        val exception = assertThrows<SpreadSyntaxError.FieldAdditionNotAllowed> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message.contains("Cannot add new field 'extra'"))
    }

    @Test
    fun `should spread type alias without overrides`() {
        val code = """
            type Config = { timeout: Int, retries: Int }
            let default_config: Config = { timeout = 30, retries = 3 }
            let copy: Config = { ...default_config }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `should spread type alias with nested types`() {
        val code = """
            type Inner = { value: Int }
            type Outer = { name: String, inner: Inner }
            let default_outer: Outer = { name = "test", inner = { value = 42 } }
            let updated: Outer = { ...default_outer, name = "updated" }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `should infer correct type from spread of type alias`() {
        val code = """
            type Config = { timeout: Int, retries: Int }
            let default_config: Config = { timeout = 30, retries = 3 }
            let custom = { ...default_config, timeout = 60 }
            let x = custom.retries
        """.trimIndent()

        // Should type check - custom should have retries field from spread
        val result = parseAndTypeCheck(code)
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `should spread imported type alias`() {
        // This tests that type aliases from imports work with spread
        // The HttpConfig type declaration should be auto-imported when importing default_http_config
        val code = """
            use std::effects::http::{default_http_config}
            let custom = { ...default_http_config, timeout_ms = 5000 }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `should spread imported type alias with annotation`() {
        val code = """
            use std::effects::http::{default_http_config, HttpConfig}
            let custom: HttpConfig = { ...default_http_config, timeout_ms = 5000 }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertTrue(result.items.isNotEmpty())
    }
}
