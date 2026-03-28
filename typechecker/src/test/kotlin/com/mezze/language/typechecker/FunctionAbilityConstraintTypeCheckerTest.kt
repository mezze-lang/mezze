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
        typeChecker.typeCheck(program)

        // Check structural properties from parsed AST
        val item = program.items[0]
        assertTrue(item is AbilityDeclaration)

        val ability = item as AbilityDeclaration
        assertEquals("Show", ability.name)
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
        typeChecker.typeCheck(program)

        // Check structural properties from parsed AST
        val abilityDecl = program.items[0] as AbilityDeclaration
        val implDecl = program.items[1] as ImplAbilityDeclaration

        assertEquals("Show", abilityDecl.name)
        assertFalse(implDecl.isInherent)
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
        typeChecker.typeCheck(program)

        // Check structural properties from parsed AST
        val topLevel = program.items[1] as TopLevelExpression
        assertTrue(topLevel.expression is LambdaExpression)

        val lambda = topLevel.expression as LambdaExpression

        // Ability constraints are set by the parser and preserved on the lambda node
        assertEquals(1, lambda.abilityConstraints.size)
        val constraint = lambda.abilityConstraints[0]
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
        typeChecker.typeCheck(program)

        // Check structural properties from parsed AST
        val topLevel = program.items[2] as TopLevelExpression
        assertTrue(topLevel.expression is LambdaExpression)

        val lambda = topLevel.expression as LambdaExpression

        // Ability constraints are set by the parser and preserved on the lambda node
        assertEquals(2, lambda.abilityConstraints.size)

        val abilityNames = lambda.abilityConstraints.map { it.abilityName }.toSet()
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
            typeChecker.typeCheck(program)
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
        typeChecker.typeCheck(program)

        // Check structural properties from parsed AST
        val topLevel = program.items[1] as TopLevelExpression
        assertTrue(topLevel.expression is LambdaExpression)

        val lambda = topLevel.expression as LambdaExpression

        // Ability constraints are set by the parser and preserved on the lambda node
        assertEquals(1, lambda.abilityConstraints.size)
        val constraint = lambda.abilityConstraints[0]
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
        typeChecker.typeCheck(program)

        // Check structural properties from parsed AST
        val topLevel = program.items[1] as TopLevelExpression
        assertTrue(topLevel.expression is LambdaExpression)

        val lambda = topLevel.expression as LambdaExpression

        // Ability constraints are set by the parser and preserved on the lambda node
        assertEquals(1, lambda.abilityConstraints.size)
        val constraint = lambda.abilityConstraints[0]
        assertEquals("Show", constraint.abilityName)
        assertEquals(1, constraint.typeArgs.size)
    }
}
