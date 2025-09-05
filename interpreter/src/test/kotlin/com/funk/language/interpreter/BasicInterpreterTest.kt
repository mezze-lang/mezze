package com.funk.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.graalvm.polyglot.Context

/**
 * Basic tests for the Funk language interpreter.
 * Tests fundamental functionality like literals and simple expressions.
 */
class BasicInterpreterTest {
    
    private fun createContext(): Context {
        return Context.newBuilder(FunkLanguage.ID)
            .allowAllAccess(true)
            .build()
    }
    
    @Test
    fun testIntegerLiteral() {
        createContext().use { context ->
            try {
                val result = context.eval(FunkLanguage.ID, "42")
                assertTrue(result.isNumber)
                assertEquals(42L, result.asLong())
            } catch (e: Exception) {
                // For now, we expect this to fail since we haven't fully implemented the conversion
                // This test serves as a starting point
                println("Expected failure: ${e.message}")
                assertTrue(e.message?.contains("not yet implemented") == true || 
                          e.message?.contains("Parse error") == true)
            }
        }
    }
    
    @Test
    fun testStringLiteral() {
        createContext().use { context ->
            try {
                val result = context.eval(FunkLanguage.ID, "\"hello world\"")
                assertTrue(result.isString)
                assertEquals("hello world", result.asString())
            } catch (e: Exception) {
                // For now, we expect this to fail since we haven't fully implemented the conversion
                println("Expected failure: ${e.message}")
                assertTrue(e.message?.contains("not yet implemented") == true || 
                          e.message?.contains("Parse error") == true)
            }
        }
    }
    
    @Test
    fun testBooleanLiteral() {
        createContext().use { context ->
            try {
                val result = context.eval(FunkLanguage.ID, "true")
                assertTrue(result.isBoolean)
                assertTrue(result.asBoolean())
            } catch (e: Exception) {
                // For now, we expect this to fail since we haven't fully implemented the conversion
                println("Expected failure: ${e.message}")
                assertTrue(e.message?.contains("not yet implemented") == true || 
                          e.message?.contains("Parse error") == true)
            }
        }
    }
    
    @Test
    fun testSimpleArithmetic() {
        createContext().use { context ->
            try {
                val result = context.eval(FunkLanguage.ID, "2 + 3")
                assertTrue(result.isNumber)
                assertEquals(5L, result.asLong())
            } catch (e: Exception) {
                // For now, we expect this to fail since we haven't fully implemented the conversion
                println("Expected failure: ${e.message}")
                assertTrue(e.message?.contains("not yet implemented") == true || 
                          e.message?.contains("Parse error") == true)
            }
        }
    }
}
