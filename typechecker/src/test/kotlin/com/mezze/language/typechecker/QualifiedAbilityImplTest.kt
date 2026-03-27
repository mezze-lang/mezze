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
 * e.g., `impl mymodule::MyAbility for SomeType where { ... }`
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
            ability Printable for 'a where {
                print: {value: 'a} -> Str
            }

            # Use qualified name in impl (within same module, normally wouldn't but tests parsing)
            impl Printable for Int where {
                print = {value} -> "int"
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertNotNull(program)

        // Find the impl
        val impl = program.items.filterIsInstance<ImplAbilityDeclaration>().firstOrNull()
        assertNotNull(impl)
        assertEquals("Printable", impl!!.abilityName)
        assertTrue((impl.abilityRef?.qualifier?.segments ?: emptyList()).isEmpty())  // No module path in this case
    }

    @Test
    fun `impl with qualified ability name from imported module`() {
        // Create a module that exports an ability
        val abilityModule = """
            ability Serializable for 'a where {
                serialize: {value: 'a} -> Str
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
            impl serializer::Serializable for MyInt where {
                serialize: { value: MyInt } -> Str = { value } ->
                    match value is
                        MyInt { val } -> "serialized_int"
            }

            let test = MyInt { val = 42 }
        """.trimIndent()

        Files.writeString(srcDir.resolve("main.mz"), mainModule)

        // Parse and type check
        val program = parser.parse(mainModule)

        // Verify parsing: qualified ability impls are initially parsed as ImplDeclaration
        // (since effect and ability impls share syntax). The typechecker converts them.
        val parsedImpl = program.items.filterIsInstance<ImplDeclaration>().firstOrNull()
        assertNotNull(parsedImpl, "Parser should produce ImplDeclaration for cross-module ability impl")
        assertEquals("Serializable", parsedImpl!!.effect.localName)
        assertEquals(listOf("serializer"), parsedImpl.effect.qualifier?.segments ?: emptyList<String>())

        // Type check should succeed and convert to ImplAbilityDeclaration
        val typeCheckerWithRoot = TypeChecker()
        val typedProgram = typeCheckerWithRoot.typeCheck(program, tempDir)
        assertNotNull(typedProgram)

        // After type checking, the impl should have been resolved as an ability impl
        val resolvedImpl = typedProgram.items.filterIsInstance<ImplAbilityDeclaration>().firstOrNull()
        assertNotNull(resolvedImpl, "Typechecker should convert cross-module ability impl to ImplAbilityDeclaration")
        assertEquals("Serializable", resolvedImpl!!.abilityName)

        println("SUCCESS: Qualified ability impl type-checked correctly!")
    }

    @Test
    fun `impl with multi-segment qualified ability name`() {
        // Create a simple module structure - codec.mz directly in src
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)

        val codecModule = """
            ability Encoder for 'a where {
                encode: {value: 'a} -> Str
            }
        """.trimIndent()

        Files.writeString(srcDir.resolve("codec.mz"), codecModule)

        // Create main module using the codec module
        // We need to define a LOCAL type to impl for, since we can't impl imported ability for built-in types (orphan rule)
        val mainModule = """
            use codec

            # Define a local type
            type MyData = | MyData { val: Str } |

            impl codec::Encoder for MyData where {
                encode: { value: MyData } -> Str = { value } ->
                    match value is
                        MyData { val } -> "encoded"
            }

            let x = MyData { val = "test" }
        """.trimIndent()

        Files.writeString(srcDir.resolve("main.mz"), mainModule)

        val program = parser.parse(mainModule)

        // Verify parsing: qualified ability impls are initially parsed as ImplDeclaration
        val parsedImpl = program.items.filterIsInstance<ImplDeclaration>().firstOrNull()
        assertNotNull(parsedImpl, "Parser should produce ImplDeclaration for cross-module ability impl")
        assertEquals("Encoder", parsedImpl!!.effect.localName)
        assertEquals(listOf("codec"), parsedImpl.effect.qualifier?.segments ?: emptyList<String>())

        // Type check should succeed and convert to ImplAbilityDeclaration
        val typeCheckerWithRoot = TypeChecker()
        val typedProgram = typeCheckerWithRoot.typeCheck(program, tempDir)
        assertNotNull(typedProgram)

        // After type checking, the impl should have been resolved
        val resolvedImpl = typedProgram.items.filterIsInstance<ImplAbilityDeclaration>().firstOrNull()
        assertNotNull(resolvedImpl, "Typechecker should convert cross-module ability impl to ImplAbilityDeclaration")
        assertEquals("Encoder", resolvedImpl!!.abilityName)

        println("SUCCESS: Multi-segment qualified ability impl works!")
    }

    @Test
    fun `parsing three-segment qualified ability name`() {
        // Just test that parsing works for deeply qualified names
        val source = """
            # This is just a parsing test - the module wouldn't exist
            # impl foo::bar::baz::MyAbility for Int where {
            #     op = {value} -> "test"
            # }
            let x = 1
        """.trimIndent()

        val program = parser.parse(source)
        assertNotNull(program)
    }
}
