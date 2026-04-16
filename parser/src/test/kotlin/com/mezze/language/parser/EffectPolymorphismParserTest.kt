package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EffectPolymorphismParserTest {

    private val parser = MezzeParser()

    @Test
    fun `test parse purely polymorphic effect row`() {
        val code = """
            let f: { x: Int } -> Int <...> = { x } -> x + 1
        """.trimIndent()

        val program = parser.parseParsedModule(code)
        val binding = program.items[0] as LetBinding
        val typeAnnotation = binding.typeAnnotation as FunctionTypeAnnotation

        assertNotNull(typeAnnotation.effects, "Effect annotation should not be null")
        assertTrue(typeAnnotation.effects!!.isPolymorphic, "Effect row should be polymorphic")
        assertEquals(emptyList(), typeAnnotation.effects!!.effects, "Polymorphic effect row should have no concrete effects")
    }

    @Test
    fun `test parse effect extension with one effect`() {
        val code = """
            let f: { x: Int } -> Int <Log, ...> = { x } -> x + 1
        """.trimIndent()

        val program = parser.parseParsedModule(code)
        val binding = program.items[0] as LetBinding
        val typeAnnotation = binding.typeAnnotation as FunctionTypeAnnotation

        assertNotNull(typeAnnotation.effects, "Effect annotation should not be null")
        assertTrue(typeAnnotation.effects!!.isPolymorphic, "Effect row should be polymorphic")
        assertEquals(listOf("Log"), typeAnnotation.effects!!.effects, "Effect row should have Log")
    }

    @Test
    fun `test parse effect extension with multiple effects`() {
        val code = """
            let f: { x: Int } -> Int <Log, Console, ...> = { x } -> x + 1
        """.trimIndent()

        val program = parser.parseParsedModule(code)
        val binding = program.items[0] as LetBinding
        val typeAnnotation = binding.typeAnnotation as FunctionTypeAnnotation

        assertNotNull(typeAnnotation.effects, "Effect annotation should not be null")
        assertTrue(typeAnnotation.effects!!.isPolymorphic, "Effect row should be polymorphic")
        assertEquals(listOf("Log", "Console"), typeAnnotation.effects!!.effects, "Effect row should have Log and Console")
    }

    @Test
    fun `test parse closed effect row is not polymorphic`() {
        val code = """
            let f: { x: Int } -> Int <Log, Console> = { x } -> x + 1
        """.trimIndent()

        val program = parser.parseParsedModule(code)
        val binding = program.items[0] as LetBinding
        val typeAnnotation = binding.typeAnnotation as FunctionTypeAnnotation

        assertNotNull(typeAnnotation.effects, "Effect annotation should not be null")
        assertTrue(!typeAnnotation.effects!!.isPolymorphic, "Closed effect row should NOT be polymorphic")
        assertEquals(listOf("Log", "Console"), typeAnnotation.effects!!.effects, "Effect row should have Log and Console")
    }

    @Test
    fun `test parse empty effect row is not polymorphic`() {
        val code = """
            let f: { x: Int } -> Int <> = { x } -> x + 1
        """.trimIndent()

        val program = parser.parseParsedModule(code)
        val binding = program.items[0] as LetBinding
        val typeAnnotation = binding.typeAnnotation as FunctionTypeAnnotation

        // Empty effect row <> means no effects, so the annotation should be null
        // (Because the parser only creates EffectRowAnnotation when effects.isNotEmpty() || isPolymorphic)
        // For <>, both are false, so it's null
        assertEquals(null, typeAnnotation.effects, "Empty effect row <> should result in null annotation")
    }

    @Test
    fun `test parse function with no effects has null effect annotation`() {
        val code = """
            let f: { x: Int } -> Int = { x } -> x + 1
        """.trimIndent()

        val program = parser.parseParsedModule(code)
        val binding = program.items[0] as LetBinding
        val typeAnnotation = binding.typeAnnotation as FunctionTypeAnnotation

        assertEquals(null, typeAnnotation.effects, "Function with no effects should have null effect annotation")
    }

    @Test
    fun `test parse nested polymorphic effects in record fields`() {
        val code = """
            let config: { handler: { x: Int } -> Int <...> } = { handler = { x } -> x }
        """.trimIndent()

        val program = parser.parseParsedModule(code)
        val binding = program.items[0] as LetBinding
        val typeAnnotation = binding.typeAnnotation as RecordTypeAnnotation

        val handlerType = typeAnnotation.entries.find { it.name == "handler" }!!.type as FunctionTypeAnnotation
        assertNotNull(handlerType.effects)
        assertTrue(handlerType.effects!!.isPolymorphic)
    }

    @Test
    fun `test parse polymorphic effects in function parameter and return`() {
        val code = """
            let compose: { f: { x: Int } -> Int <...>, g: { x: Int } -> Int <...> } -> { x: Int } -> Int <...> =
                { f, g } -> { x } -> f { x = g { x = x } }
        """.trimIndent()

        val program = parser.parseParsedModule(code)
        val binding = program.items[0] as LetBinding
        val typeAnnotation = binding.typeAnnotation as FunctionTypeAnnotation

        // Check parameter type (record with f and g)
        val paramType = typeAnnotation.paramType as RecordTypeAnnotation
        val fType = paramType.entries.find { it.name == "f" }!!.type as FunctionTypeAnnotation
        val gType = paramType.entries.find { it.name == "g" }!!.type as FunctionTypeAnnotation

        assertNotNull(fType.effects)
        assertTrue(fType.effects!!.isPolymorphic)
        assertNotNull(gType.effects)
        assertTrue(gType.effects!!.isPolymorphic)

        // Check return type
        val returnType = typeAnnotation.returnType as FunctionTypeAnnotation
        assertNotNull(returnType.effects)
        assertTrue(returnType.effects!!.isPolymorphic)
    }

    @Test
    fun `test parse effect extension preserves effect order`() {
        val code = """
            let f: { x: Int } -> Int <Log, DB, FileSystem, ...> = { x } -> x
        """.trimIndent()

        val program = parser.parseParsedModule(code)
        val binding = program.items[0] as LetBinding
        val typeAnnotation = binding.typeAnnotation as FunctionTypeAnnotation

        assertNotNull(typeAnnotation.effects)
        assertTrue(typeAnnotation.effects!!.isPolymorphic)
        assertEquals(listOf("Log", "DB", "FileSystem"), typeAnnotation.effects!!.effects)
    }

    @Test
    fun `test parse purely polymorphic in complex nested structure`() {
        val code = """
            let complex: {
                handlers: {
                    onSuccess: { result: Int } -> {} <...>,
                    onError: { error: String } -> {} <...>
                }
            } -> {} = { handlers } -> {}
        """.trimIndent()

        val program = parser.parseParsedModule(code)
        val binding = program.items[0] as LetBinding
        val typeAnnotation = binding.typeAnnotation as FunctionTypeAnnotation

        val paramType = typeAnnotation.paramType as RecordTypeAnnotation
        val handlersType = paramType.entries.find { it.name == "handlers" }!!.type as RecordTypeAnnotation

        val onSuccessType = handlersType.entries.find { it.name == "onSuccess" }!!.type as FunctionTypeAnnotation
        val onErrorType = handlersType.entries.find { it.name == "onError" }!!.type as FunctionTypeAnnotation

        assertNotNull(onSuccessType.effects)
        assertTrue(onSuccessType.effects!!.isPolymorphic)
        assertNotNull(onErrorType.effects)
        assertTrue(onErrorType.effects!!.isPolymorphic)
    }

    @Test
    fun `test parse mixed closed and polymorphic effects`() {
        val code = """
            let mixed: {
                pure_fn: { x: Int } -> Int,
                effectful_fn: { x: Int } -> Int <Log>,
                poly_fn: { x: Int } -> Int <...>
            } -> {} = { pure_fn, effectful_fn, poly_fn } -> {}
        """.trimIndent()

        val program = parser.parseParsedModule(code)
        val binding = program.items[0] as LetBinding
        val typeAnnotation = binding.typeAnnotation as FunctionTypeAnnotation
        val paramType = typeAnnotation.paramType as RecordTypeAnnotation

        val pureFnType = paramType.entries.find { it.name == "pure_fn" }!!.type as FunctionTypeAnnotation
        val effectfulFnType = paramType.entries.find { it.name == "effectful_fn" }!!.type as FunctionTypeAnnotation
        val polyFnType = paramType.entries.find { it.name == "poly_fn" }!!.type as FunctionTypeAnnotation

        // pure_fn has no effects
        assertEquals(null, pureFnType.effects)

        // effectful_fn has closed effect row
        assertNotNull(effectfulFnType.effects)
        assertTrue(!effectfulFnType.effects!!.isPolymorphic)
        assertEquals(listOf("Log"), effectfulFnType.effects!!.effects)

        // poly_fn has polymorphic effects
        assertNotNull(polyFnType.effects)
        assertTrue(polyFnType.effects!!.isPolymorphic)
    }

    @Test
    fun `test parse single effect with ellipsis`() {
        val code = """
            let f: { x: Int } -> Int <Log, ...> = { x } -> x
        """.trimIndent()

        val program = parser.parseParsedModule(code)
        val binding = program.items[0] as LetBinding
        val typeAnnotation = binding.typeAnnotation as FunctionTypeAnnotation

        assertNotNull(typeAnnotation.effects)
        assertTrue(typeAnnotation.effects!!.isPolymorphic)
        assertEquals(1, typeAnnotation.effects!!.effects.size)
        assertEquals("Log", typeAnnotation.effects!!.effects[0])
    }

    @Test
    fun `test parse effect names with underscores and capitals`() {
        val code = """
            let f: { x: Int } -> Int <FileSystem, HTTP_Client, DatabasePool, ...> = { x } -> x
        """.trimIndent()

        val program = parser.parseParsedModule(code)
        val binding = program.items[0] as LetBinding
        val typeAnnotation = binding.typeAnnotation as FunctionTypeAnnotation

        assertNotNull(typeAnnotation.effects)
        assertTrue(typeAnnotation.effects!!.isPolymorphic)
        assertEquals(listOf("FileSystem", "HTTP_Client", "DatabasePool"), typeAnnotation.effects!!.effects)
    }
}
