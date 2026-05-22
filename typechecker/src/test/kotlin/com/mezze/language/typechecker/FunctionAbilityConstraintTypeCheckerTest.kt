package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parsedast.*
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from parsed AST
        val item = parsedModule.decls[0]
        assertTrue(item is ParsedAbilityDecl)

        val ability = item as ParsedAbilityDecl
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from parsed AST
        val abilityDecl = parsedModule.decls[0] as ParsedAbilityDecl
        val implDecl = parsedModule.decls[1] as ParsedImplAbilityDecl

        assertEquals("Show", abilityDecl.name)
        assertFalse(implDecl.isInherent)
    }

    @Test
    fun testLambdaExpressionWithAbilityConstraints() {
        val source = """
            ability Encoder for 'a where {
                encode : {} -> Str
            }

            { val: 'a } with Encoder 'a -> Str = {val} -> "test"
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from v2 parsed AST
        val topLevel = parsedModule.decls.filterIsInstance<ParsedTopLevelExpr>().first()
        assertTrue(topLevel.expression is ParsedLambda)

        val lambda = topLevel.expression as ParsedLambda

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

            { left: 'a, right: 'a } with Eq 'a, Show 'a -> Str = {left, right} -> "test"
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from v2 parsed AST
        val topLevel = parsedModule.decls.filterIsInstance<ParsedTopLevelExpr>().first()
        assertTrue(topLevel.expression is ParsedLambda)

        val lambda = topLevel.expression as ParsedLambda

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

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Direct ability access is now blocked
        val error = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
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

            { key: 'a, value: 'b } with KVStore 'b 'a -> {} = {key, value} -> {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from v2 parsed AST
        val topLevel = parsedModule.decls.filterIsInstance<ParsedTopLevelExpr>().first()
        assertTrue(topLevel.expression is ParsedLambda)

        val lambda = topLevel.expression as ParsedLambda

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

            { val: Int } with Show Int -> Str = {val} -> "test"
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from v2 parsed AST
        val topLevel = parsedModule.decls.filterIsInstance<ParsedTopLevelExpr>().first()
        assertTrue(topLevel.expression is ParsedLambda)

        val lambda = topLevel.expression as ParsedLambda

        // Ability constraints are set by the parser and preserved on the lambda node
        assertEquals(1, lambda.abilityConstraints.size)
        val constraint = lambda.abilityConstraints[0]
        assertEquals("Show", constraint.abilityName)
        assertEquals(1, constraint.typeArgs.size)
    }
}
