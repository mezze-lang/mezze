package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertNotNull

/**
 * #91: an ability-impl operation body may access a field on an un-annotated
 * param whose type comes from the ability declaration in ANOTHER module,
 * WITHOUT the impl module importing that type by name. The type stays closed /
 * nominal (Mezze is closed-by-default) — it's just resolved by content-hash
 * from the ability declaration, so a by-name import isn't required.
 */
class AbilityOpImplicitTypeTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() { parser = MezzeParser(); typeChecker = TypeChecker() }

    private fun lib(tempDir: Path) {
        tempDir.resolve("fmtlib.mz").writeText("""
            pub type Config = { name: Str }

            ability Fmt for 'fmt where {
              transform: { input: Str, config: Config } -> Str
            }
        """.trimIndent())
    }

    @Test
    fun `impl field-access on implicit ability-op param without importing its type`(@TempDir tempDir: Path) {
        lib(tempDir)
        val src = """
            use fmtlib::{ Fmt }
            type MyFmt = {}
            impl Fmt for MyFmt where {
              transform = { input, config } -> config.name,
            }
        """.trimIndent()
        val result = typeChecker.typeCheck(parser.parseParsedModule(src, "test.mz"), tempDir)
        assertNotNull(result.typedModule, "impl should typecheck without importing Config")
    }

    @Test
    fun `still works when the type IS imported (baseline unchanged)`(@TempDir tempDir: Path) {
        lib(tempDir)
        val src = """
            use fmtlib::{ Fmt, Config }
            type MyFmt = {}
            impl Fmt for MyFmt where {
              transform = { input, config } -> config.name,
            }
        """.trimIndent()
        val result = typeChecker.typeCheck(parser.parseParsedModule(src, "test.mz"), tempDir)
        assertNotNull(result.typedModule)
    }

    @Test
    fun `impl not touching the param needs no import (regression)`(@TempDir tempDir: Path) {
        lib(tempDir)
        val src = """
            use fmtlib::{ Fmt }
            type MyFmt = {}
            impl Fmt for MyFmt where {
              transform = { input, config } -> input,
            }
        """.trimIndent()
        val result = typeChecker.typeCheck(parser.parseParsedModule(src, "test.mz"), tempDir)
        assertNotNull(result.typedModule)
    }
}
