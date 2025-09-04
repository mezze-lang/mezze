package com.funk.language.typechecker

import com.funk.language.parser.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BasicTypeCheckerTest {
    
    private val typeChecker = TypeChecker()
    
    @Test
    fun `infers literal types correctly`() {
        val intLiteral = Literal(42)
        val floatLiteral = Literal(3.14f)
        val stringLiteral = Literal("hello")
        val boolLiteral = Literal(true)
        
        val context = TypeContext()
        
        assertEquals(Type.IntType, typeChecker.inferLiteral(intLiteral))
        assertEquals(Type.FloatType, typeChecker.inferLiteral(floatLiteral))
        assertEquals(Type.StringType, typeChecker.inferLiteral(stringLiteral))
        assertEquals(Type.BooleanType, typeChecker.inferLiteral(boolLiteral))
    }
    
    @Test
    fun `creates fresh type variables`() {
        val var1 = TypeVar.fresh()
        val var2 = TypeVar.fresh()
        
        assertNotEquals(var1.id, var2.id)
        assertTrue(var1.name.startsWith("t"))
        assertTrue(var2.name.startsWith("t"))
    }
    
    @Test
    fun `creates fresh row variables`() {
        val row1 = RowVar.fresh()
        val row2 = RowVar.fresh()
        
        assertNotEquals(row1.id, row2.id)
        assertTrue(row1.name.startsWith("r"))
        assertTrue(row2.name.startsWith("r"))
    }
    
    @Test
    fun `record type supports open and closed variants`() {
        val closedRecord = Type.RecordType(
            mapOf("x" to Type.IntType, "y" to Type.StringType)
        )
        
        val openRecord = Type.RecordType(
            mapOf("x" to Type.IntType),
            RowVar.fresh()
        )
        
        assertTrue(closedRecord.isClosed)
        assertFalse(closedRecord.isOpen)
        
        assertFalse(openRecord.isClosed)
        assertTrue(openRecord.isOpen)
    }
    
    @Test
    fun `variant type supports open and closed variants`() {
        val closedVariant = Type.VariantType(
            mapOf("Red" to null, "Green" to null, "Blue" to null)
        )
        
        val openVariant = Type.VariantType(
            mapOf("Ok" to Type.IntType, "Error" to Type.StringType),
            RowVar.fresh()
        )
        
        assertTrue(closedVariant.isClosed)
        assertFalse(closedVariant.isOpen)
        
        assertFalse(openVariant.isClosed)
        assertTrue(openVariant.isOpen)
    }
    
    @Test
    fun `function type includes effects`() {
        val readFileEffect = Effect("ReadFile")
        val writeFileEffect = Effect("WriteFile")
        
        val functionType = Type.FunctionType(
            Type.RecordType(mapOf("path" to Type.StringType)),
            Type.VariantType(mapOf("Ok" to Type.StringType, "Error" to Type.StringType)),
            setOf(readFileEffect, writeFileEffect)
        )
        
        assertEquals(2, functionType.effects.size)
        assertTrue(functionType.effects.contains(readFileEffect))
        assertTrue(functionType.effects.contains(writeFileEffect))
    }
    
    @Test
    fun `type scheme supports polymorphism`() {
        val typeVar = TypeVar.fresh("a")
        val rowVar = RowVar.fresh("r")
        
        val scheme = TypeScheme(
            typeVars = setOf(typeVar),
            rowVars = setOf(rowVar),
            constraints = emptySet(),
            type = Type.RecordType(
                mapOf("value" to Type.VarType(typeVar)),
                rowVar
            )
        )
        
        assertEquals(1, scheme.typeVars.size)
        assertEquals(1, scheme.rowVars.size)
        assertTrue(scheme.typeVars.contains(typeVar))
        assertTrue(scheme.rowVars.contains(rowVar))
    }
    
    @Test
    fun `content hash computation`() {
        val type1 = Type.RecordType(mapOf("x" to Type.IntType))
        val type2 = Type.RecordType(mapOf("x" to Type.IntType))
        val type3 = Type.RecordType(mapOf("y" to Type.IntType))
        
        val hash1 = ContentHash.compute(type1)
        val hash2 = ContentHash.compute(type2)
        val hash3 = ContentHash.compute(type3)
        
        // Same structure should have same hash
        assertEquals(hash1.hash, hash2.hash)
        
        // Different structure should have different hash
        assertNotEquals(hash1.hash, hash3.hash)
    }
}

