package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Gleam's rule: a module path must resolve to exactly ONE source. More than one
 * (a local file AND a flat stdlib module, a local file AND a dependency, two
 * dependency versions) is an ambiguity ERROR (M0805), not a silent first-match.
 *
 * Here a local `src/int.mz` collides with the flat stdlib `int` — `use int`
 * resolves to both, so resolution must reject it rather than quietly picking one.
 */
class ModuleAmbiguityTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun write(dir: String, name: String, body: String) {
        val d = tempDir.resolve(dir)
        Files.createDirectories(d)
        Files.writeString(d.resolve("$name.mz"), body.trimIndent())
    }

    @Test
    fun `a module present in two local roots is an ambiguity error`() {
        // `foo` exists in BOTH src/ and lib/ — `use foo` resolves to two sources,
        // which must be rejected rather than silently taking src/ first.
        write("src", "foo", "pub let two = { } -> 2")
        write("lib", "foo", "pub let two = { } -> 2")
        val source = """
            use foo::{ two }

            let main = { } -> do
              let x = two { }
              {}
        """.trimIndent()
        write("src", "app", source)
        try {
            typeChecker.typeCheck(parser.parseParsedModule(source, "app.mz"), tempDir)
            fail("expected an ambiguity error for a local `int` that collides with the flat stdlib `int`")
        } catch (e: Throwable) {
            val msg = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue(
                msg.contains("ambiguous", ignoreCase = true) && msg.contains("foo"),
                "expected an ambiguity error naming the colliding sources; got: $msg",
            )
        }
    }
}
