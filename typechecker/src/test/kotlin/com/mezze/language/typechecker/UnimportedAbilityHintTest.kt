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
 * "Implement it" is the wrong advice when an impl already exists.
 *
 * A blanket `impl X for 'a` covers every type, and it is matched where the
 * constraint is settled — the call site. So a caller of a function constrained
 * on `X`, in a file where `X` is not in scope, used to be told
 * "`Int` does not implement 'X'. To enable this, implement: impl X for Int":
 * true, and it sends the reader to write a second impl of something that is
 * already implemented for everything.
 *
 * What they need is an import, and the compiler knows which one. The message
 * says what is wrong in one plain line; the hints say what to do; the fix hint
 * lets an editor do it.
 *
 * THE SHAPE THAT STILL REACHES THIS, and why the test is three modules: a
 * direct `use` of a module carries that module's own impls, and a `pub use`
 * carries what it re-exports. What carries nothing is a module that plain-`use`s
 * an ability and exposes a function constrained on it — its consumers get the
 * function and not the instance. That is `wrap` below, and it is the shape of
 * `http.mz`'s `from_json_result`.
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
    fun `the message is one plain line and says the ability is not imported`() {
        val r = report(callsYellWithoutTheAbility)
        assertEquals(
            "`Shout` isn't imported here, so `Int` can't use it", r.message,
            "the one-liner says what is wrong, in the reader's terms, and stops",
        )
        assertTrue(
            !r.message.contains("impl Shout for"),
            "do not tell them to write an impl: ${r.message}",
        )
    }

    @Test
    fun `the hints say an impl already exists and give the import line`() {
        val r = report(callsYellWithoutTheAbility)
        assertEquals(
            listOf(
                "`absdef` implements `Shout` for every type, so `Int` needs no impl of its own",
                "add `use absdef::{ Shout }` at the top of this file",
            ),
            r.hints,
        )
    }

    @Test
    fun `an editor gets the import as a one-click fix`() {
        val r = report(callsYellWithoutTheAbility)
        val imports = r.fixHints.filterIsInstance<FixHint.Import>()
        assertEquals(1, imports.size, "expected one Import fix hint; got ${r.fixHints}")
        assertEquals("Shout", imports.single().name)
        assertEquals(listOf("absdef"), imports.single().candidateModules)
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
