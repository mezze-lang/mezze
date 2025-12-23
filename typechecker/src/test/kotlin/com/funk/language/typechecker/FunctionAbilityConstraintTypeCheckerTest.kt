package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class FunctionAbilityConstraintTypeCheckerTest {

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
    fun testAbilityDeclarationTypeChecking() {
        val source = """
            ability Show 'a where {
                show : {val: 'a} -> String
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(1, typedProgram.items.size)
        val item = typedProgram.items[0]
        assertTrue(item is AbilityDeclaration)
        
        val ability = item as AbilityDeclaration
        assertNotNull(ability.inferredType)
        assertTrue(ability.inferredType is FunkType.RecordType)
        
        val recordType = ability.inferredType as FunkType.RecordType
        assertTrue(recordType.fields.containsKey("show"))
        
        val showType = recordType.fields["show"]
        assertTrue(showType is FunkType.FunctionType)
    }

    @Test
    fun testAbilityImplementationTypeChecking() {
        val source = """
            ability Show 'a where {
                show : {val: 'a} -> String
            }
            
            impl Show Int where {
                show : {val: Int} -> String = {val} -> "INT"
            }
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(2, typedProgram.items.size)
        
        val abilityDecl = typedProgram.items[0] as AbilityDeclaration
        val implDecl = typedProgram.items[1] as ImplAbilityDeclaration
        
        assertNotNull(abilityDecl.inferredType)
        assertNotNull(implDecl.inferredType)
        
        assertTrue(implDecl.inferredType is FunkType.RecordType)
        val implType = implDecl.inferredType as FunkType.RecordType
        assertTrue(implType.fields.containsKey("show"))
    }

    @Test
    fun testLambdaExpressionWithAbilityConstraints() {
        val source = """
            ability Encoder 'a where {
                encode : {val: 'a} -> String
            }
            
            { val: 'a } -> String with Encoder 'a = {val} -> "test"
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(2, typedProgram.items.size)
        
        val topLevel = typedProgram.items[1] as TopLevelExpression
        assertTrue(topLevel.expression is LambdaExpression)
        
        val lambda = topLevel.expression as LambdaExpression
        assertNotNull(lambda.inferredType)
        
        assertTrue(lambda.inferredType is FunkType.FunctionType)
        val functionType = lambda.inferredType as FunkType.FunctionType
        
        // Check that ability constraints are preserved in lambda
        assertEquals(1, functionType.abilityConstraints.size)
        val constraint = functionType.abilityConstraints[0]
        assertEquals("Encoder", constraint.abilityName)
    }

    @Test
    fun testMultipleAbilityConstraints() {
        val source = """
            ability Eq 'a where {
                equals : {left: 'a, right: 'a} -> Boolean
            }
            
            ability Show 'a where {
                show : {val: 'a} -> String
            }
            
            { left: 'a, right: 'a } -> String with Eq 'a, Show 'a = {left, right} -> "test"
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(3, typedProgram.items.size)
        
        val topLevel = typedProgram.items[2] as TopLevelExpression
        assertTrue(topLevel.expression is LambdaExpression)
        
        val lambda = topLevel.expression as LambdaExpression
        assertNotNull(lambda.inferredType)
        
        assertTrue(lambda.inferredType is FunkType.FunctionType)
        val functionType = lambda.inferredType as FunkType.FunctionType
        
        // Check that both ability constraints are preserved
        assertEquals(2, functionType.abilityConstraints.size)
        
        val constraints = functionType.abilityConstraints
        val abilityNames = constraints.map { it.abilityName }.toSet()
        assertTrue(abilityNames.contains("Eq"))
        assertTrue(abilityNames.contains("Show"))
    }

    @Test
    fun testAbilityOperationAccess() {
        val source = """
            ability Show 'a where {
                show : {val: 'a} -> String
            }
            
            Show.show
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(2, typedProgram.items.size)
        
        val topLevel = typedProgram.items[1] as TopLevelExpression
        assertTrue(topLevel.expression is MemberAccess)
        
        val memberAccess = topLevel.expression as MemberAccess
        // Note: The ability operation access may not have inferredType set yet
        // as automatic resolution is still being implemented
        // For now, just verify the structure is correct
        
        // Handle both Identifier and VariantConstruction cases
        val receiverName = when (memberAccess.receiver) {
            is Identifier -> (memberAccess.receiver as Identifier).name
            is VariantConstruction -> (memberAccess.receiver as VariantConstruction).constructor
            else -> fail("Expected receiver to be Identifier or VariantConstruction, got ${memberAccess.receiver::class.simpleName}")
        }
        assertEquals("Show", receiverName)
        assertEquals("show", memberAccess.member)
    }

    @Test
    fun testMultiParameterAbilityConstraints() {
        val source = """
            ability KVStore 'a 'b where {
                store : {key: 'a, value: 'b} -> {}
            }
            
            { key: 'a, value: 'b } -> {} with KVStore 'a 'b = {key, value} -> {}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(2, typedProgram.items.size)
        
        val topLevel = typedProgram.items[1] as TopLevelExpression
        assertTrue(topLevel.expression is LambdaExpression)
        
        val lambda = topLevel.expression as LambdaExpression
        assertNotNull(lambda.inferredType)
        
        assertTrue(lambda.inferredType is FunkType.FunctionType)
        val functionType = lambda.inferredType as FunkType.FunctionType
        
        // Check multi-parameter ability constraint
        assertEquals(1, functionType.abilityConstraints.size)
        val constraint = functionType.abilityConstraints[0]
        assertEquals("KVStore", constraint.abilityName)
        assertEquals(2, constraint.typeArgs.size)
    }

    @Test
    fun testAbilityConstraintWithConcreteTypes() {
        val source = """
            ability Show 'a where {
                show : {val: 'a} -> String
            }
            
            { val: Int } -> String with Show Int = {val} -> "test"
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        assertEquals(2, typedProgram.items.size)
        
        val topLevel = typedProgram.items[1] as TopLevelExpression
        assertTrue(topLevel.expression is LambdaExpression)
        
        val lambda = topLevel.expression as LambdaExpression
        
        
        assertNotNull(lambda.inferredType)
        
        assertTrue(lambda.inferredType is FunkType.FunctionType)
        val functionType = lambda.inferredType as FunkType.FunctionType
        
        // Check concrete type in ability constraint
        assertEquals(1, functionType.abilityConstraints.size)
        val constraint = functionType.abilityConstraints[0]
        assertEquals("Show", constraint.abilityName)
        assertEquals(1, constraint.typeArgs.size)
        
        val typeArg = constraint.typeArgs[0]
        assertTrue(typeArg is FunkType.IntType)
    }
}
