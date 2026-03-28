package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.typedast.TypedAbilityDecl
import com.mezze.language.typedast.TypedAbilityImpl
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class AbilityTypeCheckerTest {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
    }
    
    @Test
    fun testSimpleAbilityDeclaration() {
        val source = """
            ability Encoder for 'a where {
              encode : {val: 'a} -> Str
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val ability = typedProgram.typedModule?.abilityDecls?.find { it.name == "Encoder" }
        assertNotNull(ability)
        assertTrue(ability!!.operations.any { it.name == "encode" })
        val encodeOp = ability.operations.first { it.name == "encode" }
        assertTrue(encodeOp.returnType is MezzeType.StringType)
    }

    @Test
    fun testImplAbilityDeclaration() {
        val source = """
            use std::int

            ability Encoder for 'a where {
              encode : {val: 'a} -> Str
            }

            impl Encoder for Int where {
              encode : {val: Int} -> Str = {val} -> int::to_str {n = val}
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val ability = typedProgram.typedModule?.abilityDecls?.find { it.name == "Encoder" }
        assertNotNull(ability)

        val impl = typedProgram.typedModule?.abilityImpls?.firstOrNull()
        assertNotNull(impl)
        assertTrue(impl!!.operations.containsKey("encode"))
    }

    @Test
    fun testMultipleAbilityOperations() {
        val source = """
            ability Show for 'a where {
              show : {val: 'a} -> Str,
              debug : {val: 'a} -> Str
            }

            impl Show for Str where {
              show : {val: Str} -> Str = {val} -> val,
              debug : {val: Str} -> Str = {val} -> "\"$(val)\""
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val ability = typedProgram.typedModule?.abilityDecls?.find { it.name == "Show" }
        assertNotNull(ability)
        assertEquals(2, ability!!.operations.size)
        assertTrue(ability.operations.any { it.name == "show" })
        assertTrue(ability.operations.any { it.name == "debug" })

        val impl = typedProgram.typedModule?.abilityImpls?.firstOrNull()
        assertNotNull(impl)
        assertEquals(2, impl!!.operations.size)
        assertTrue(impl.operations.containsKey("show"))
        assertTrue(impl.operations.containsKey("debug"))
    }

    @Test
    fun testAbilityWithComplexTypes() {
        val source = """
            ability Serializer for 'a where {
              serialize : {val: 'a} -> Str,
              deserialize : {str: Str} -> 'a
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val ability = typedProgram.typedModule?.abilityDecls?.find { it.name == "Serializer" }
        assertNotNull(ability)

        // Check serialize operation
        val serializeOp = ability!!.operations.first { it.name == "serialize" }
        assertTrue(serializeOp.returnType is MezzeType.StringType)

        // Check deserialize operation returns a type variable
        val deserializeOp = ability.operations.first { it.name == "deserialize" }
        assertTrue(deserializeOp.returnType is MezzeType.VarType)
    }
    
    @Test
    fun testAbilityDoesNotThrowErrors() {
        val source = """
            ability Encoder for 'a where {
              encode : {val: 'a} -> Str
            }
            
            impl Encoder for Int where {
              encode : {val: Int} -> Str = {val} -> "number"
            }
            
            impl Encoder for Str where {
              encode : {val: Str} -> Str = {val} -> val
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        
        // Should not throw any exceptions
        assertDoesNotThrow {
            typeChecker.typeCheck(program)
        }
    }
}
