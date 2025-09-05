package com.funk.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for Funk runtime values.
 * These tests verify our value system works correctly without needing the full Truffle runtime.
 */
class ValueTest {
    
    @Test
    fun testFunkIntValue() {
        val value = FunkInt(42L)
        assertEquals(42L, value.value)
        assertEquals("42", value.getDisplayString())
        assertTrue(value.isNumber())
        assertEquals(42L, value.asLong())
    }
    
    @Test
    fun testFunkStringValue() {
        val value = FunkString("hello")
        assertEquals("hello", value.value)
        assertEquals("\"hello\"", value.getDisplayString())
        assertTrue(value.isString())
        assertEquals("hello", value.asString())
    }
    
    @Test
    fun testFunkBooleanValue() {
        val value = FunkBoolean(true)
        assertTrue(value.value)
        assertEquals("true", value.getDisplayString())
        assertTrue(value.isBoolean())
        assertTrue(value.asBoolean())
    }
    
    @Test
    fun testFunkRecordValue() {
        val fields = mapOf(
            "name" to FunkString("John"),
            "age" to FunkInt(30L)
        )
        val record = FunkRecord(fields)
        
        assertEquals("{ name = \"John\", age = 30 }", record.getDisplayString())
        assertTrue(record.hasMembers())
        
        val members = record.getMembers(false)
        assertEquals(2, members.size)
        assertTrue(members.contains("name"))
        assertTrue(members.contains("age"))
        
        assertTrue(record.isMemberReadable("name"))
        assertEquals(FunkString("John"), record.readMember("name"))
    }
    
    @Test
    fun testFunkVariantValue() {
        val variant1 = FunkVariant("None", null)
        assertEquals("None", variant1.getDisplayString())
        
        val variant2 = FunkVariant("Some", FunkInt(42L))
        assertEquals("Some(42)", variant2.getDisplayString())
        
        assertTrue(variant2.hasMembers())
        assertTrue(variant2.isMemberReadable("constructor"))
        assertTrue(variant2.isMemberReadable("value"))
        
        assertEquals(FunkString("Some"), variant2.readMember("constructor"))
        assertEquals(FunkInt(42L), variant2.readMember("value"))
    }
    
    @Test
    fun testFunkNullValue() {
        assertEquals("null", FunkNull.getDisplayString())
        assertTrue(FunkNull.isNull())
    }
    
    @Test
    fun testRecordUpdate() {
        val original = FunkRecord(mapOf(
            "name" to FunkString("John"),
            "age" to FunkInt(30L)
        ))
        
        val updated = original.withUpdates(mapOf(
            "age" to FunkInt(31L),
            "city" to FunkString("New York")
        ))
        
        // Original should be unchanged
        assertEquals(FunkInt(30L), original.readMember("age"))
        assertThrows(FunkException::class.java) { original.readMember("city") }
        
        // Updated should have new values
        assertEquals(FunkString("John"), updated.readMember("name")) // unchanged
        assertEquals(FunkInt(31L), updated.readMember("age")) // updated
        assertEquals(FunkString("New York"), updated.readMember("city")) // new
    }
}
