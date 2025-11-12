package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestrictiveSpreadTest {
    
    private val parser = FunkParser()
    private val typeChecker = TypeChecker()
    
    private fun parseAndTypeCheck(code: String): Program {
        val program = parser.parse(code)
        return typeChecker.typeCheck(program)
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
}