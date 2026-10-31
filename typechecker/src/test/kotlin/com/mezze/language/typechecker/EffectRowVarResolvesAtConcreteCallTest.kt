package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Calling an effect-polymorphic function `{ f: … <...> } -> … <...>` with a
 * CONCRETE callback must resolve the shared open row variable to that callback's
 * actual effect row. A pure callback performs nothing, so `<...>` must become
 * `<>` and the enclosing function must close — not keep a dangling open row that
 * surfaces as `<..., *>` under a `perform`.
 *
 * Bug (pre-fix): `unifyEffectRows` only bound a row variable when there were
 * named extra effects to absorb. When the actual callback's row was CLOSED and
 * the formal's row was the open `ρ`, both "extra" sets were empty, so `ρ` was
 * left UNBOUND — it then generalized onto the caller and leaked as `<...>`.
 */
class EffectRowVarResolvesAtConcreteCallTest {

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

    private fun render(source: String, name: String): String {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("app.mz"), source.trimIndent())
        val result = typeChecker.typeCheck(parser.parseParsedModule(source.trimIndent(), "app.mz"), tempDir)
        return result.getVariableType(name)?.let { prettyPrintType(it) } ?: "<null>"
    }

    private val applyDef = "pub let apply1: { f: { } -> Int <...> } -> Int <...> = { f } -> f { }"

    @Test
    fun `a pure callback resolves the open row so the caller closes`() {
        val t = render(
            """
            use std::effects::log::{ Console, println }

            $applyDef

            pub let via = { } -> perform Console in do
              let x = apply1 { f = { } -> 1 }
              println { msg = "ok" }
            """,
            "via",
        )
        assertFalse(
            t.contains("..."),
            "a pure callback performs nothing, so `<...>` must resolve to `<>` and `via` must close to `<*>`; got: $t",
        )
    }

    @Test
    fun `calling a concrete function in the same spot closes (control)`() {
        // Proves the leaked `...` is specific to the `<...>` function: an
        // ordinary concrete `g : { } -> Int` in the identical position closes.
        val t = render(
            """
            use std::effects::log::{ Console, println }

            pub let g: { } -> Int = { } -> 1

            pub let via = { } -> perform Console in do
              let x = g { }
              println { msg = "ok" }
            """,
            "via",
        )
        assertFalse(t.contains("..."), "control: a concrete call must close; got: $t")
    }

    @Test
    fun `an effectful callback still propagates its effect (no #215 regression)`() {
        // The fix must NOT swallow a real effect: an effectful callback through
        // the same `<...>` channel must still make the effect REQUIRED (named),
        // not dropped.
        val t = render(
            """
            effect Log where { emit: { msg: Str } -> {} <Log> }

            pub let apply1: { f: { } -> {} <...> } -> {} <...> = { f } -> f { }

            pub let via = { } -> apply1 { f = { } -> Log.emit { msg = "x" } }
            """,
            "via",
        )
        assertTrue(
            t.contains("Log"),
            "an effectful callback's Log must propagate (stay required), not be laundered; got: $t",
        )
    }
}
