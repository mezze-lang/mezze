package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class FunctionAbilityConstraintTypeCheckerTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun testAbilityDeclarationTypeChecking() {
        val source = """
            ability Show for 'a where {
                show : {} -> Str
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertEquals(1, typedProgram.items.size)
        val item = typedProgram.items[0]
        assertTrue(item is AbilityDeclaration)

        val ability = item as AbilityDeclaration
        assertNotNull(ability.inferredType)
        assertTrue(ability.inferredType is MezzeType.RecordType)

        val recordType = ability.inferredType as MezzeType.RecordType
        assertTrue(recordType.fields.containsKey("show"))

        val showType = recordType.fields["show"]
        assertTrue(showType is MezzeType.FunctionType)
    }

    @Test
    fun testAbilityImplementationTypeChecking() {
        val source = """
            ability Show for 'a where {
                show : {} -> Str
            }

            impl Show for Int where {
                show = {} -> "INT"
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(2, typedProgram.items.size)
        
        val abilityDecl = typedProgram.items[0] as AbilityDeclaration
        val implDecl = typedProgram.items[1] as ImplAbilityDeclaration
        
        assertNotNull(abilityDecl.inferredType)
        assertNotNull(implDecl.inferredType)
        
        assertTrue(implDecl.inferredType is MezzeType.RecordType)
        val implType = implDecl.inferredType as MezzeType.RecordType
        assertTrue(implType.fields.containsKey("show"))
    }

    @Test
    fun testLambdaExpressionWithAbilityConstraints() {
        val source = """
            ability Encoder for 'a where {
                encode : {} -> Str
            }

            { val: 'a } -> Str with Encoder 'a = {val} -> "test"
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertEquals(2, typedProgram.items.size)

        val topLevel = typedProgram.items[1] as TopLevelExpression
        assertTrue(topLevel.expression is LambdaExpression)

        val lambda = topLevel.expression as LambdaExpression
        assertNotNull(lambda.inferredType)

        assertTrue(lambda.inferredType is MezzeType.FunctionType)
        val functionType = lambda.inferredType as MezzeType.FunctionType

        // Check that ability constraints are preserved in lambda
        assertEquals(1, functionType.abilityConstraints.size)
        val constraint = functionType.abilityConstraints[0]
        assertEquals("Encoder", constraint.abilityName)
    }

    @Test
    fun testMultipleAbilityConstraints() {
        val source = """
            ability Eq for 'a where {
                equals : {right: 'a} -> Boolean
            }

            ability Show for 'a where {
                show : {} -> Str
            }

            { left: 'a, right: 'a } -> Str with Eq 'a, Show 'a = {left, right} -> "test"
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(3, typedProgram.items.size)
        
        val topLevel = typedProgram.items[2] as TopLevelExpression
        assertTrue(topLevel.expression is LambdaExpression)
        
        val lambda = topLevel.expression as LambdaExpression
        assertNotNull(lambda.inferredType)
        
        assertTrue(lambda.inferredType is MezzeType.FunctionType)
        val functionType = lambda.inferredType as MezzeType.FunctionType
        
        // Check that both ability constraints are preserved
        assertEquals(2, functionType.abilityConstraints.size)
        
        val constraints = functionType.abilityConstraints
        val abilityNames = constraints.map { it.abilityName }.toSet()
        assertTrue(abilityNames.contains("Eq"))
        assertTrue(abilityNames.contains("Show"))
    }

    @Test
    fun testAbilityOperationAccessIsBlocked() {
        val source = """
            ability Show for 'a where {
                show : {} -> Str
            }

            Show.show
        """.trimIndent()

        val program = parser.parse(source)

        // Direct ability access is now blocked
        val error = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program).program
        }

        assertTrue(error.message?.contains("Direct ability access") == true,
            "Error should mention direct ability access: ${error.message}")
        assertTrue(error.message?.contains("Show.show") == true,
            "Error should mention 'Show.show': ${error.message}")
    }

    @Test
    fun testMultiParameterAbilityConstraints() {
        val source = """
            ability KVStore 'b for 'a where {
                store : {value: 'b} -> {}
            }

            { key: 'a, value: 'b } -> {} with KVStore 'b 'a = {key, value} -> {}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(2, typedProgram.items.size)
        
        val topLevel = typedProgram.items[1] as TopLevelExpression
        assertTrue(topLevel.expression is LambdaExpression)
        
        val lambda = topLevel.expression as LambdaExpression
        assertNotNull(lambda.inferredType)
        
        assertTrue(lambda.inferredType is MezzeType.FunctionType)
        val functionType = lambda.inferredType as MezzeType.FunctionType
        
        // Check multi-parameter ability constraint
        assertEquals(1, functionType.abilityConstraints.size)
        val constraint = functionType.abilityConstraints[0]
        assertEquals("KVStore", constraint.abilityName)
        assertEquals(2, constraint.typeArgs.size)
    }

    @Test
    fun testAbilityConstraintWithConcreteTypes() {
        val source = """
            ability Show for 'a where {
                show : {} -> Str
            }

            { val: Int } -> Str with Show Int = {val} -> "test"
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(2, typedProgram.items.size)
        
        val topLevel = typedProgram.items[1] as TopLevelExpression
        assertTrue(topLevel.expression is LambdaExpression)
        
        val lambda = topLevel.expression as LambdaExpression
        
        
        assertNotNull(lambda.inferredType)
        
        assertTrue(lambda.inferredType is MezzeType.FunctionType)
        val functionType = lambda.inferredType as MezzeType.FunctionType
        
        // Check concrete type in ability constraint
        assertEquals(1, functionType.abilityConstraints.size)
        val constraint = functionType.abilityConstraints[0]
        assertEquals("Show", constraint.abilityName)
        assertEquals(1, constraint.typeArgs.size)
        
        val typeArg = constraint.typeArgs[0]
        assertTrue(typeArg is MezzeType.IntType)
    }
}
