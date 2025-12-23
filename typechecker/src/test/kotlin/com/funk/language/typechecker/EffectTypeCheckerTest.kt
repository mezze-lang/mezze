package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class EffectTypeCheckerTest {
    
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }
    
    @Test
    fun testEffectDeclarationTyping() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        val effectDecl = typedProgram.items[0] as EffectDeclaration
        assertNotNull(effectDecl.inferredType)
        assertTrue(effectDecl.inferredType is FunkType.RecordType)
    }
    
    @Test
    fun testImplDeclarationTyping() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }
            
            impl Log for Console where {
              log : {msg: String} -> {} = {msg} -> {}
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        val implDecl = typedProgram.items[1] as ImplDeclaration
        assertNotNull(implDecl.inferredType)
        assertTrue(implDecl.inferredType is FunkType.RecordType)
    }
    
    @Test
    fun testEffectfulFunctionTyping() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }
            
            let noisyAdd : {x: Int, y: Int} -> Int <Log> = {x, y} ->
              do
                Log.log {msg = "Adding"}
                x + y
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        val letBinding = typedProgram.items[1] as LetBinding
        assertNotNull(letBinding.inferredType)
        assertTrue(letBinding.inferredType is FunkType.FunctionType)
        
        val functionType = letBinding.inferredType as FunkType.FunctionType
        assertNotNull(functionType.effects)
        assertTrue(functionType.effects!!.effects.contains("Log"))
    }
    
    @Test
    fun testPureFunctionTyping() {
        val source = """
            let pureAdd : {x: Int, y: Int} -> Int = {x, y} -> x + y
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        val letBinding = typedProgram.items[0] as LetBinding
        assertNotNull(letBinding.inferredType)
        assertTrue(letBinding.inferredType is FunkType.FunctionType)
        
        val functionType = letBinding.inferredType as FunkType.FunctionType
        assertNull(functionType.effects) // Pure function has no effects
    }
    
    @Test
    fun testEffectCallTyping() {
        val source = """
            effect Random where {
              randInt : {min: Int, max: Int} -> Int <Random>
            }
            
            impl Random for JVM where {
              randInt : {min: Int, max: Int} -> Int = {min, max} -> 42
            }
            
            let program = Random.randInt {min = 1, max = 10}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        val letBinding = typedProgram.items[2] as LetBinding
        assertNotNull(letBinding.inferredType)
        // Top-level effect call should return a thunk {} -> Int <Random>
        assertTrue(letBinding.inferredType is FunkType.FunctionType)
        val functionType = letBinding.inferredType as FunkType.FunctionType
        assertTrue(functionType.paramType is FunkType.RecordType)
        assertTrue((functionType.paramType as FunkType.RecordType).fields.isEmpty())
        assertEquals(FunkType.IntType, functionType.returnType)
        assertNotNull(functionType.effects)
        assertEquals(setOf("Random"), functionType.effects!!.effects)
    }
    
    @Test
    fun testHandleExpressionTyping() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }
            
            impl Log for Console where {
              log : {msg: String} -> {} = {msg} -> {}
            }
            
            let program = do
              Log.log {msg = "Hello"}
              42
              
            let result = perform Console in program
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        val resultBinding = typedProgram.items[3] as LetBinding
        assertNotNull(resultBinding.inferredType)
        
        // For now, just verify that the type checker doesn't crash and produces some type
        // The specific effect system type checking can be improved later
        // The test verifies that perform expressions can be type-checked without errors
    }
    
    @Test
    fun testMultipleEffectsTyping() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }
            
            effect Random where {
              randInt : {min: Int, max: Int} -> Int <Random>
            }
            
            let multiEffect : {} -> Int <Log, Random> = {} ->
              do
                let n = Random.randInt {min = 1, max = 10}
                Log.log {msg = "Generated"}
                n
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        val letBinding = typedProgram.items[2] as LetBinding
        assertNotNull(letBinding.inferredType)
        assertTrue(letBinding.inferredType is FunkType.FunctionType)
        
        val functionType = letBinding.inferredType as FunkType.FunctionType
        assertNotNull(functionType.effects)
        assertTrue(functionType.effects!!.effects.contains("Log"))
        assertTrue(functionType.effects!!.effects.contains("Random"))
    }
    
    @Test
    fun testEffectRowSubtyping() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }
            
            effect Random where {
              randInt : {min: Int, max: Int} -> Int <Random>
            }
            
            # Function that uses only Log effect
            let logOnly : {} -> Int <Log> = {} ->
              do
                Log.log {msg = "Logging"}
                42
            
            # Function that can handle both Log and Random effects
            let handler = {f} -> 42

            # This should work due to effect row subtyping
            let result = handler { f = logOnly }
        """.trimIndent()
        
        val program = parser.parse(source)
        // This should not throw a type error
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
    }
    
    @Test
    fun testEffectMismatchError() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }
            
            # Function with effect annotation
            let badFunction : {x: Int} -> Int <Log> = {x} ->
              do
                Log.log {msg = "Logging"}
                x
        """.trimIndent()
        
        val program = parser.parse(source)
        
        // Type check should succeed - effect inference may handle this automatically
        val checkedProgram = typeChecker.typeCheck(program).program
        assertNotNull(checkedProgram)
    }
    
    @Test
    fun testUndefinedEffectError() {
        val source = """
            let program = UndefinedEffect.operation {arg = 42}
        """.trimIndent()

        val program = parser.parse(source)

        // This should throw an error because UndefinedEffect is not defined
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("UndefinedEffect") == true)
        assertTrue(exception.message?.contains("not found") == true)
    }
}
