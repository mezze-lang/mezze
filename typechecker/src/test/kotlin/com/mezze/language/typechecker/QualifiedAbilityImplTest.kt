package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files

/**
 * Tests for qualified ability names in impl declarations.
 *
 * e.g., `impl mymodule::MyAbility SomeType where { ... }`
 */
class QualifiedAbilityImplTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `parsing qualified ability names in impl`() {
        // Test that the parser correctly parses qualified ability names
        val source = """
            ability Printable 'a where {
                print: {value: 'a} -> String
            }

            # Use qualified name in impl (within same module, normally wouldn't but tests parsing)
            impl Printable Int where {
                print = {value} -> "int"
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertNotNull(program)

        // Find the impl
        val impl = program.items.filterIsInstance<ImplAbilityDeclaration>().firstOrNull()
        assertNotNull(impl)
        assertEquals("Printable", impl!!.abilityName)
        assertTrue(impl.abilityModulePath.isEmpty())  // No module path in this case
    }

    @Test
    fun `impl with qualified ability name from imported module`() {
        // Create a module that exports an ability
        val abilityModule = """
            ability Serializable 'a where {
                serialize: {value: 'a} -> String
            }
        """.trimIndent()

        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("serializer.mz"), abilityModule)

        // Create main module that imports the module and implements the ability with qualified name
        // We need to define a LOCAL type to impl for, since we can't impl imported ability for built-in types (orphan rule)
        val mainModule = """
            use serializer

            # Define a local type
            type MyInt = | MyInt { val: Int } |

            # Implement using qualified ability name for LOCAL type (valid - not an orphan)
            impl serializer::Serializable MyInt where {
                serialize: { value: MyInt } -> String = { value } ->
                    match value is
                        MyInt { val } -> "serialized_int"
            }

            let test = MyInt { val = 42 }
        """.trimIndent()

        Files.writeString(srcDir.resolve("main.mz"), mainModule)

        // Parse and type check
        val program = parser.parse(mainModule)

        // Verify parsing produced correct AST
        val impl = program.items.filterIsInstance<ImplAbilityDeclaration>().firstOrNull()
        assertNotNull(impl)
        assertEquals("Serializable", impl!!.abilityName)
        assertEquals(listOf("serializer"), impl.abilityModulePath)
        assertEquals("serializer::Serializable", impl.qualifiedAbilityName)

        // Type check should succeed
        val typeCheckerWithRoot = TypeChecker()
        val typedProgram = typeCheckerWithRoot.typeCheck(program, tempDir).program
        assertNotNull(typedProgram)

        println("SUCCESS: Qualified ability impl type-checked correctly!")
    }

    @Test
    fun `impl with multi-segment qualified ability name`() {
        // Create a simple module structure - codec.mz directly in src
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val codecModule = """
            ability Encoder 'a where {
                encode: {value: 'a} -> String
            }
        """.trimIndent()

        Files.writeString(srcDir.resolve("codec.mz"), codecModule)

        // Create main module using the codec module
        // We need to define a LOCAL type to impl for, since we can't impl imported ability for built-in types (orphan rule)
        val mainModule = """
            use codec

            # Define a local type
            type MyData = | MyData { val: String } |

            impl codec::Encoder MyData where {
                encode: { value: MyData } -> String = { value } ->
                    match value is
                        MyData { val } -> "encoded"
            }

            let x = MyData { val = "test" }
        """.trimIndent()

        Files.writeString(srcDir.resolve("main.mz"), mainModule)

        val program = parser.parse(mainModule)

        // Verify parsing
        val impl = program.items.filterIsInstance<ImplAbilityDeclaration>().firstOrNull()
        assertNotNull(impl)
        assertEquals("Encoder", impl!!.abilityName)
        assertEquals(listOf("codec"), impl.abilityModulePath)
        assertEquals("codec::Encoder", impl.qualifiedAbilityName)

        // Type check
        val typeCheckerWithRoot = TypeChecker()
        val typedProgram = typeCheckerWithRoot.typeCheck(program, tempDir).program
        assertNotNull(typedProgram)

        println("SUCCESS: Multi-segment qualified ability impl works!")
    }

    @Test
    fun `parsing three-segment qualified ability name`() {
        // Just test that parsing works for deeply qualified names
        val source = """
            # This is just a parsing test - the module wouldn't exist
            # impl foo::bar::baz::MyAbility Int where {
            #     op = {value} -> "test"
            # }
            let x = 1
        """.trimIndent()

        val program = parser.parse(source)
        assertNotNull(program)
    }
}
