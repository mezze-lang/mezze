package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.diagnostics.FixHint
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A function's ability constraint is discharged at the CALLER, and the caller
 * does NOT need the ability in scope.
 *
 * `absdef` declares `ability Shout` with a blanket `impl Shout for 'a`; `wrap`
 * imports `Shout` to write `yell … with Shout 'a`; a consumer imports only
 * `yell`. Calling `yell { x = 42 }` settles `Shout Int` at the call site, and
 * the impl is reached transitively through `wrap`'s own import of `absdef`
 * (ImportResolver.transitiveAbilityImpls) — so the consumer needs no
 * `use absdef::{ Shout }`. This mirrors how `on_get`'s `with IntoResponse 'r`
 * is satisfied without importing `std::effects::http`: the ability is named in
 * the function's signature, the caller chooses nothing, and coherence guarantees
 * a single impl, so there is nothing to disambiguate.
 *
 * Two things still hold, and are covered below: the ability must be in scope to
 * CALL its method directly (`x.shout {}` — name resolution), and a genuinely
 * UNIMPLEMENTED ability still says "implement it" (no import can help).
 */
class UnimportedAbilityHintTest {

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
        writeModule(
            "absdef",
            """
            ability Shout for 'a where {
              shout: { it: 'a } -> Str,
            }

            impl Shout for 'a where {
              shout: { it: 'a } -> Str = { it } -> "shouted",
            }
            """,
        )
        writeModule(
            "wrap",
            """
            use absdef::{ Shout }

            pub let yell: { x: 'a } with Shout 'a -> Str = { x } -> x.shout {}
            """,
        )
    }

    private fun writeModule(name: String, body: String) {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("$name.mz"), body.trimIndent())
    }

    private class Reported(val message: String, val hints: List<String>, val fixHints: List<FixHint>)

    private fun report(source: String): Reported {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("app.mz"), source.trimIndent())
        return try {
            typeChecker.typeCheck(parser.parseParsedModule(source.trimIndent(), "app.mz"), tempDir)
            Reported("(no error)", emptyList(), emptyList())
        } catch (e: TypeCheckError) {
            Reported(e.message, e.hints, e.fixHints)
        }
    }

    private val callsYellWithoutTheAbility = """
        use wrap::{ yell }

        let go = {} -> yell { x = 42 }

        go {}
    """

    @Test
    fun `a constrained function is called without importing the ability`() {
        // Case 2: the ability is named in `yell`'s signature, not at the call
        // site. The caller chooses nothing and imports nothing; the blanket impl
        // is reached transitively (wrap -> absdef), so `Shout Int` discharges and
        // the program checks clean. (The old behavior demanded `use absdef` here
        // and offered an import hint; coherence makes that unnecessary — the same
        // rule that lets `on_get`'s IntoResponse work without importing http.)
        val r = report(callsYellWithoutTheAbility)
        assertEquals(
            "(no error)", r.message,
            "a caller needs no import to satisfy a constraint a function already named",
        )
    }

    @Test
    fun `importing the ability makes the same program check`() {
        // The counterpart: the advice has to WORK. If this fails, the hint above
        // is telling people to do something that does not help.
        val r = report(
            """
            use wrap::{ yell }
            use absdef::{ Shout }

            let go = {} -> yell { x = 42 }

            go {}
            """,
        )
        assertEquals("(no error)", r.message, "expected the suggested import to be enough")
    }

    @Test
    fun `a pub use carries the instance, not just the name`() {
        // The other half of the story, and the reason the shape above needs
        // three modules: a facade that RE-EXPORTS the ability its API is
        // constrained on must deliver the instance too, or `pub use` is a
        // promise it cannot keep. http.mz is the real case — `with_query` is
        // constrained on `ToQuery` and `pub use`s it — and `request` alone is
        // all this imports.
        val r = report(
            """
            use std::effects::http::{ request }

            let go = {} -> request { url = "https://example.com" }.with_query { params = { q = "x" } }

            go {}
            """,
        )
        assertEquals(
            "(no error)", r.message,
            "a `pub use`d ability must bring its impl to a consumer that did not name it",
        )
    }

    @Test
    fun `an ability nothing implements still says to implement it`() {
        // The old message is right in the case it was written for, and stays:
        // `Quiet` has no impl anywhere, so there is no import to suggest.
        val r = report(
            """
            ability Quiet for 'a where {
              hush: { it: 'a } -> Str,
            }

            let go: { x: 'a } with Quiet 'a -> Str = { x } -> x.hush {}

            go { x = 42 }
            """,
        )
        assertTrue(r.message.contains("Quiet"), "the ability must be named; got: ${r.message}")
        assertTrue(
            r.hints.none { it.contains("add `use") },
            "nothing implements it, so there is no import to suggest; got: ${r.hints}",
        )
    }
}
