package com.funk.language.typechecker

import com.funk.language.parser.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BasicTypeCheckerTest {
    
    private val typeChecker = TypeChecker()
    
    @Test
    fun `infers literal types correctly`() {
        // Test literal type inference through complete programs
        val intProgram = Program(listOf(LetBinding("x", null, Literal(42))))
        val floatProgram = Program(listOf(LetBinding("x", null, Literal(3.14f))))
        val stringProgram = Program(listOf(LetBinding("x", null, Literal("hello"))))
        val boolProgram = Program(listOf(LetBinding("x", null, Literal(true))))
        
        val intResult = typeChecker.typeCheck(intProgram)
        val floatResult = typeChecker.typeCheck(floatProgram)
        val stringResult = typeChecker.typeCheck(stringProgram)
        val boolResult = typeChecker.typeCheck(boolProgram)
        
        assertEquals(FunkType.IntType, (intResult.items[0] as TypedLetBinding).inferredType)
        assertEquals(FunkType.FloatType, (floatResult.items[0] as TypedLetBinding).inferredType)
        assertEquals(FunkType.StringType, (stringResult.items[0] as TypedLetBinding).inferredType)
        assertEquals(FunkType.BooleanType, (boolResult.items[0] as TypedLetBinding).inferredType)
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
        val closedRecord = FunkType.RecordType(
            mapOf("x" to FunkType.IntType, "y" to FunkType.StringType)
        )
        
        val openRecord = FunkType.RecordType(
            mapOf("x" to FunkType.IntType),
            RowVar.fresh()
        )
        
        assertTrue(closedRecord.isClosed)
        assertFalse(closedRecord.isOpen)
        
        assertFalse(openRecord.isClosed)
        assertTrue(openRecord.isOpen)
    }
    
    @Test
    fun `variant type supports open and closed variants`() {
        val closedVariant = FunkType.VariantType(
            mapOf("Red" to null, "Green" to null, "Blue" to null)
        )
        
        val openVariant = FunkType.VariantType(
            mapOf("Ok" to FunkType.IntType, "Error" to FunkType.StringType),
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
        
        val functionType = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            FunkType.VariantType(mapOf("Ok" to FunkType.StringType, "Error" to FunkType.StringType)),
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
            type = FunkType.RecordType(
                mapOf("value" to FunkType.VarType(typeVar)),
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
        val type1 = FunkType.RecordType(mapOf("x" to FunkType.IntType))
        val type2 = FunkType.RecordType(mapOf("x" to FunkType.IntType))
        val type3 = FunkType.RecordType(mapOf("y" to FunkType.IntType))
        
        val hash1 = ContentHash.compute(type1)
        val hash2 = ContentHash.compute(type2)
        val hash3 = ContentHash.compute(type3)
        
        // Same structure should have same hash
        assertEquals(hash1.hash, hash2.hash)
        
        // Different structure should have different hash
        assertNotEquals(hash1.hash, hash3.hash)
    }
}

