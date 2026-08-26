package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * End-to-end: a module declares a TYPE, a FUNCTION, and a VARIABLE; another
 * module imports each WITH A RENAME (`use lib::{ Foo as Bar }`) and uses it.
 * Everything must type-check across the boundary — the rename is a local binding
 * name only; identity (and its content hash) comes from the original declaration,
 * so a renamed import resolves to exactly the same thing.
 */
class ImportRenameCrossModuleTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun writeModule(name: String, body: String) {
        val srcDir = tempDir.resolve("src"); Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("$name.mz"), body.trimIndent())
    }

    private fun checkMain(mainSrc: String): Result<TypeCheckResult> {
        val srcDir = tempDir.resolve("src"); Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("main.mz"), mainSrc.trimIndent())
        return runCatching {
            typeChecker.typeCheck(parser.parseParsedModule(mainSrc.trimIndent(), "main.mz"), tempDir)
        }
    }

    private val lib = """
        pub type Widget = { size: Int }
        pub let make_widget : { n: Int } -> Widget = { n } -> { size = n }
        pub let default_size : Int = 42
    """.trimIndent()

    // A renamed FUNCTION import calls through to the original definition.
    @Test fun `renamed function import type-checks`() {
        writeModule("lib", lib)
        val r = checkMain("""
            use lib::{ make_widget as build }
            pub let go : { n: Int } -> Int = { n } -> (build { n = n }).size
            pub let main : {} -> {} = { } -> {}
        """)
        assertTrue(r.isSuccess, "renamed function import should type-check; got: ${r.exceptionOrNull()?.message}")
    }

    // A renamed VARIABLE import resolves to the original binding's type.
    @Test fun `renamed variable import type-checks`() {
        writeModule("lib", lib)
        val r = checkMain("""
            use lib::{ default_size as dflt }
            pub let plus_one : {} -> Int = { } -> dflt + 1
            pub let main : {} -> {} = { } -> {}
        """)
        assertTrue(r.isSuccess, "renamed variable import should type-check; got: ${r.exceptionOrNull()?.message}")
    }

    // A renamed TYPE import: the aliased type is the SAME type as the original,
    // so a value built via the (renamed) library function satisfies an annotation
    // written with the alias — proving identity survives the rename.
    @Test fun `renamed type import is the same type as the original`() {
        writeModule("lib", lib)
        val r = checkMain("""
            use lib::{ Widget as Gadget, make_widget as build }
            ## `build` returns lib's Widget; annotating with the alias Gadget must
            ## unify — Gadget and Widget are one type (same decl hash).
            pub let mk : { n: Int } -> Gadget = { n } -> build { n = n }
            pub let main : {} -> {} = { } -> {}
        """)
        assertTrue(r.isSuccess, "renamed type alias must unify with the original type; got: ${r.exceptionOrNull()?.message}")
    }

    // NEGATIVE: the alias is a real binding — a genuine type MISMATCH still errors
    // through the alias (the rename doesn't launder type-checking).
    @Test fun `renamed type still enforces its structure`() {
        writeModule("lib", lib)
        val r = checkMain("""
            use lib::{ Widget as Gadget }
            ## A Str is not a Gadget/Widget — must be rejected.
            pub let bad : {} -> Gadget = { } -> "not a widget"
            pub let main : {} -> {} = { } -> {}
        """)
        assertTrue(r.isFailure, "a value of the wrong type must still be rejected through the alias")
    }
}
