package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Tests for effect polymorphism with <...> and <Effect, ...> syntax
 */
class EffectPolymorphismTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    // getBinding is only used for .inferredType access — use getVariableType instead

    @Test
    fun `test parser handles purely polymorphic effect row`() {
        val code = """
            let f: { x: Int } -> Int <...> = { x } -> x + 1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val binding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().first()
        val typeAnnotation = binding.typeAnnotation as ParsedFunctionType

        assertNotNull(typeAnnotation.effects, "Effect annotation should not be null")
        assertTrue(typeAnnotation.effects!!.isPolymorphic, "Effect row should be polymorphic")
        assertEquals(emptyList(), typeAnnotation.effects!!.effects, "Polymorphic effect row should have no concrete effects")
    }

    @Test
    fun `test parser handles effect extension with one effect`() {
        val code = """
            let f: { x: Int } -> Int <Log, ...> = { x } -> x + 1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val binding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().first()
        val typeAnnotation = binding.typeAnnotation as ParsedFunctionType

        assertNotNull(typeAnnotation.effects, "Effect annotation should not be null")
        assertTrue(typeAnnotation.effects!!.isPolymorphic, "Effect row should be polymorphic")
        assertEquals(listOf("Log"), typeAnnotation.effects!!.effects, "Effect row should have Log")
    }

    @Test
    fun `test parser handles effect extension with multiple effects`() {
        val code = """
            let f: { x: Int } -> Int <Log, Console, ...> = { x } -> x + 1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val binding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().first()
        val typeAnnotation = binding.typeAnnotation as ParsedFunctionType

        assertNotNull(typeAnnotation.effects, "Effect annotation should not be null")
        assertTrue(typeAnnotation.effects!!.isPolymorphic, "Effect row should be polymorphic")
        assertEquals(listOf("Log", "Console"), typeAnnotation.effects!!.effects, "Effect row should have Log and Console")
    }

    @Test
    fun `test closed effect row is not polymorphic`() {
        val code = """
            let f: { x: Int } -> Int <Log> = { x } -> x + 1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val binding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().first()
        val typeAnnotation = binding.typeAnnotation as ParsedFunctionType

        assertNotNull(typeAnnotation.effects, "Effect annotation should not be null")
        assertTrue(!typeAnnotation.effects!!.isPolymorphic, "Closed effect row should NOT be polymorphic")
        assertEquals(listOf("Log"), typeAnnotation.effects!!.effects, "Effect row should have Log")
    }

    @Test
    fun `test empty effect row results in null annotation`() {
        val code = """
            let f: { x: Int } -> Int <> = { x } -> x + 1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val binding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().first()
        val typeAnnotation = binding.typeAnnotation as ParsedFunctionType

        // Empty effect row <> means no effects, so the annotation should be null
        assertEquals(null, typeAnnotation.effects, "Empty effect row <> should result in null annotation")
    }

    @Test
    fun `test function with no effect annotation has null effects`() {
        val code = """
            let f: { x: Int } -> Int = { x } -> x + 1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val binding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().first()
        val typeAnnotation = binding.typeAnnotation as ParsedFunctionType

        assertEquals(null, typeAnnotation.effects, "Function with no effects should have null effect annotation")
    }

    @Test
    fun `test type checker accepts polymorphic effect annotation`() {
        // For now, just test that polymorphic annotations don't cause parse errors
        // Full type inference for polymorphic effects requires call-site instantiation
        val code = """
            let f: { x: Int } -> Int <...> = { x } -> x + 1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val binding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().first()
        val typeAnnotation = binding.typeAnnotation as ParsedFunctionType

        assertNotNull(typeAnnotation.effects)
        assertTrue(typeAnnotation.effects!!.isPolymorphic)
    }

    @Test
    fun `test type checker accepts effect extension annotation`() {
        // Test that parser handles effect extension syntax
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            let f: { x: Int } -> Int <Log, ...> = { x } -> x + 1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val binding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().first()
        val typeAnnotation = binding.typeAnnotation as ParsedFunctionType

        assertNotNull(typeAnnotation.effects)
        assertTrue(typeAnnotation.effects!!.isPolymorphic, "Effect row should be polymorphic")
        assertEquals(listOf("Log"), typeAnnotation.effects!!.effects, "Effect row should have Log effect")
    }

    @Test
    fun `test type checker accepts closed effect rows`() {
        // Test that parser handles closed effect row syntax
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            let f: { x: Int } -> Int <Log> = { x } -> x + 1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val binding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().first()
        val typeAnnotation = binding.typeAnnotation as ParsedFunctionType

        assertNotNull(typeAnnotation.effects)
        assertTrue(!typeAnnotation.effects!!.isPolymorphic, "Effect row should NOT be polymorphic")
        assertEquals(listOf("Log"), typeAnnotation.effects!!.effects, "Effect row should have Log effect")
    }

    @Test
    fun `test multiple polymorphic effect parameters parse correctly`() {
        // Test that parser handles multiple polymorphic effect parameters
        val code = """
            let both : { f: {} -> Int <...>, g: {} -> Str <...> } -> Int =
                { f, g } -> 42
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val binding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().first()
        val typeAnnotation = binding.typeAnnotation as ParsedFunctionType

        // Check that parameter types have polymorphic effects
        val paramType = typeAnnotation.paramType as ParsedRecordType
        val fType = paramType.entries.find { it.name == "f" }!!.type as ParsedFunctionType
        val gType = paramType.entries.find { it.name == "g" }!!.type as ParsedFunctionType

        assertNotNull(fType.effects, "f should have effect annotation")
        assertTrue(fType.effects!!.isPolymorphic, "f effect row should be polymorphic")

        assertNotNull(gType.effects, "g should have effect annotation")
        assertTrue(gType.effects!!.isPolymorphic, "g effect row should be polymorphic")
    }

    @Test
    fun `test effect extension with multiple concrete effects parses correctly`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            effect Console where {
                print: {msg: Str} -> {} <Console>
            }

            let f: { x: Int } -> Int <Log, Console, ...> = { x } -> x + 1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val binding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().first()
        val typeAnnotation = binding.typeAnnotation as ParsedFunctionType

        assertNotNull(typeAnnotation.effects, "Function should have effect annotation")
        assertTrue(typeAnnotation.effects!!.isPolymorphic, "Effect row should be polymorphic")
        assertEquals(listOf("Log", "Console"), typeAnnotation.effects!!.effects, "Effect row should have Log and Console")
    }

    @Test
    fun `test nested function types with polymorphic effects parse correctly`() {
        val code = """
            let higher_order : { f: { x: Int } -> Int <...> } -> Int =
                { f } -> 42
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val binding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().first()
        val typeAnnotation = binding.typeAnnotation as ParsedFunctionType

        // Check that the parameter type (a function) has polymorphic effects
        val paramType = typeAnnotation.paramType as ParsedRecordType
        val fType = paramType.entries.find { it.name == "f" }!!.type as ParsedFunctionType

        assertNotNull(fType.effects)
        assertTrue(fType.effects!!.isPolymorphic)
    }

    @Test
    fun `test effect polymorphism doesn't break existing pure functions`() {
        val code = """
            let add = { x: Int, y: Int } -> x + y
            let result = add { x = 1, y = 2 }
        """.trimIndent()

        val program = typeCheck(code)
        val addType = program.getVariableType("add") as MezzeType.FunctionType
        val resultType = program.getVariableType("result")

        // Pure functions should have no effects
        assertNull(addType.effects, "Pure function should have no effects")

        // Result should be Int
        assertTrue(resultType is MezzeType.IntType)
    }

    @Test
    fun `test effect polymorphism doesn't break existing effectful functions`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger {} where {
                info = {msg} -> {}
            }

            let log_and_add = { x: Int, y: Int } -> do
                perform ConsoleLogger in Log.info { msg = "Adding" }
                x + y

            # Wrap in function to avoid top-level <*> restriction
            let getResult = {} -> log_and_add { x = 1, y = 2 }
        """.trimIndent()

        val program = typeCheck(code)
        val getResultType = program.getVariableType("getResult")

        // getResult should be a function returning Int <*>
        assertTrue(getResultType is MezzeType.FunctionType)
        val funcType = getResultType as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
    }

    @Test
    fun `test validation allows polymorphic effects in function signature`() {
        // This should pass: polymorphic effects are allowed in function signatures
        val code = """
            let f: { x: Int } -> Int <...> = { x } -> x + 1
        """.trimIndent()

        // Should not throw an exception
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(typedProgram)
    }

    @Test
    fun `test validation allows effect extension in function signature - pure function`() {
        // Effect widening is allowed: a pure function can be assigned to a type that declares effects
        // This is useful for higher-order functions that might receive effectful callbacks
        // The pure implementation is a valid subtype of an effectful type
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            let f: { x: Int } -> Int <Log, ...> = { x } -> x + 1
        """.trimIndent()

        // This should pass - effect widening is allowed (pure is a subtype of effectful)
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(typedProgram)
    }

    @Test
    fun `test validation allows polymorphic effects in nested function types`() {
        // This should pass: nested function types can have polymorphic effects
        val code = """
            let higher_order : { f: { x: Int } -> Int <...> } -> Int =
                { f } -> f { x = 42 }
        """.trimIndent()

        // Should not throw an exception
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(typedProgram)
    }

    @Test
    fun `test validation allows polymorphic effects in record fields with function types`() {
        // This should pass: function types in record fields can have polymorphic effects
        val code = """
            let record: { callback: { x: Int } -> Int <...> } =
                { callback = { x } -> x + 1 }
        """.trimIndent()

        // Should not throw an exception
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(typedProgram)
    }

    @Test
    fun `test polymorphic effects work with higher-order functions`() {
        // Test that polymorphic effects enable writing higher-order functions
        // that accept callbacks with any effects
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger {} where {
                info = {msg} -> {}
            }

            let twice: { f: {} -> Int <...> } -> Int =
                { f } -> do
                    let x = f {}
                    let y = f {}
                    x + y

            let result = twice { f = {} -> 42 }
        """.trimIndent()

        // Should not throw an exception
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(typedProgram)

        val resultType = typedProgram.getVariableType("result")
        assertTrue(resultType is MezzeType.IntType)
    }
}
