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
 * An inherent impl method constrained `with C 'r`, where `'r` lives in a
 * PARAMETER (not the return), must not leak a PHANTOM constraint onto a caller
 * when it is imported from another module.
 *
 * `widendef` declares `Widen`, a transparent-less `newtype Box` with
 * `impl Widen for Box`, and a `Builder.close` method:
 *
 *     close: { it, make: { x: Int } -> 'r } with Widen 'r -> { x: Int } -> Int
 *
 * whose body calls `make { … }.widen {}`. When `widendef` is compiled, the op's
 * post-solve type carries TWO Widen constraints: the body's `.widen {}`
 * obligation (tied to `make`'s return var) and the DECLARED `with Widen 'r`,
 * whose `'r` the solve severed from the parameter — an orphan that occurs
 * nowhere in the method's param or return (the recurring
 * one-written-typevar-two-typevars shape). Harvested across the module boundary
 * and re-emitted at the call site, the orphan discharged correctly at a
 * top-level binding (value-restricted, so it is solved eagerly) but, inside a
 * lambda, generalized onto the caller as a phantom `with Widen 'a` — a
 * constraint over a variable the caller's type never mentions, which can neither
 * discharge nor be a type error. This is the `RouteGroup.otherwise` case that
 * put `with IntoResponse 'a` on the showcase's `run_server` / `main`.
 *
 * ContextBuilder.registerImportedInherentImpl drops a harvested constraint whose
 * variables are ALL orphan (absent from the method's param + return), keeping the
 * properly-tied obligation. So the caller is clean AND the constraint is still
 * enforced — a producer whose result has no `Widen` impl is still rejected.
 */
class CrossModuleInherentMethodConstraintTest {

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
            "widendef",
            """
            ability Widen for 'r where {
              widen: { it: 'r } -> Int,
            }

            pub newtype Box = { n: Int }

            impl Widen for Box where {
              widen: { it: Box } -> Int = { it } -> 7,
            }

            pub newtype Builder = { total: Int }

            pub let builder: { } -> Builder = { } -> Builder.({ total = 0 })

            pub let box: { n: Int } -> Box = { n } -> Box.({ n = n })

            impl Builder where {
              close: { it: 'it, make: { x: Int } -> 'r } with Widen 'r -> { x: Int } -> Int =
                { it, make } -> { x } -> do
                  let answered = make { x = x }
                  answered.widen { },
            }
            """,
        )
    }

    private fun writeModule(name: String, body: String) {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("$name.mz"), body.trimIndent())
    }

    private class Checked(val message: String, val typeOf: (String) -> String)

    private fun check(source: String): Checked {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("app.mz"), source.trimIndent())
        return try {
            val result = typeChecker.typeCheck(parser.parseParsedModule(source.trimIndent(), "app.mz"), tempDir)
            Checked("(no error)") { name ->
                result.getVariableType(name)?.let { prettyPrintType(it) } ?: "<null>"
            }
        } catch (e: TypeCheckError) {
            Checked(e.message) { "<error>" }
        }
    }

    @Test
    fun `a lambda-bound caller of an imported constrained method carries no phantom constraint`() {
        val r = check(
            """
            use widendef::{ builder, Builder, box }

            let mk = { } -> builder { }.close { make = { x } -> box { n = x } }
            """,
        )
        val t = r.typeOf("mk")
        assertFalse(
            t.contains("Widen"),
            "mk is `{} -> {x:Int}->Int`; the imported method's `with Widen 'r` is " +
                "discharged inside it, so no `with Widen 'a` may ride on mk's scheme. Got: $t",
        )
    }

    @Test
    fun `a top-level caller is also clean`() {
        val r = check(
            """
            use widendef::{ builder, Builder, box }

            let app = builder { }.close { make = { x } -> box { n = x } }
            """,
        )
        val t = r.typeOf("app")
        assertFalse(t.contains("Widen"), "app must carry no Widen constraint; got: $t")
    }

    @Test
    fun `the constraint is still enforced for a producer with no impl`() {
        // Dropping the orphan must not drop the ENFORCEMENT: a producer whose
        // result has no `Widen` impl is still a type error (the body-tied
        // obligation survives). If this stopped failing, the fix would have
        // silently discarded a real constraint.
        val r = check(
            """
            use widendef::{ builder, Builder }

            let bad = { } -> builder { }.close { make = { x } -> { z = x } }
            """,
        )
        assertTrue(
            r.message.contains("Widen"),
            "a producer with no Widen impl must still be rejected; got: ${r.message}",
        )
    }
}
