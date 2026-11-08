package com.mezze.interpreter

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.module.ModuleTypeChecker
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * A chained inherent method resolves wrongly when another MODULE has a method
 * of the same name on a different type.
 *
 * Reported shape: `mez check` passes, `mez dev` throws `Field not found: finish`
 * or the compiler's own "no dictionary was supplied for ability operation
 * finish … This is a compiler defect". Bisected by the reporter to needing a
 * CHAIN (the `.finish` receiver comes from a previous method call — a direct
 * `mk {}.finish {}` is fine) plus a COMPETING same-named method.
 *
 * Measured here: those two are NOT sufficient. With both impls in ONE module
 * all three variants pass. The third ingredient is the MODULE BOUNDARY — the
 * competing method has to arrive by import, which is what points at context or
 * dictionary plumbing rather than at method resolution itself.
 *
 * Against [project_dictionary_not_arriving_family]: all four known causes of
 * that message sit on the RECEIVER-LESS path, and this one is receiver-TAKING.
 * The message cites S-3, which is recorded fixed and was about a receiver-less
 * op reading its instance head off a receiver field — so the message is being
 * reused by a different fault.
 *
 * DIAGNOSIS, measured by the last test here: annotating the chain's intermediate
 * value (`let s: Rule = mk {}.step {}`) makes the crash go away. So the fault is
 * that the intermediate LOSES ITS NAME.
 *
 *   `step = { it: 'it } -> { n = it.n }` builds a new literal from a field read.
 *   With `it: 'it` the field's type is a VARIABLE, so `{ n: 'a }` does not match
 *   `Rule = { n: Int }` as an exact ground shape and adoption (spec 11.3) never
 *   fires. The result is anonymous; an anonymous receiver reaches no nominal
 *   inherent impl (11.17); so `.finish` degrades to a FIELD access — which is
 *   why the message says FIELD not found rather than "no such method".
 *
 * Why the clash is needed to see it: with one `finish` candidate in scope a
 * sole-candidate fallback answers correctly by accident. With two it cannot
 * guess, and the degradation surfaces. The same fallback that made
 * AliasImplTargetTest's single-impl case prove nothing.
 *
 * Likely the SAME ROOT CAUSE as the record suite's open gap that a generic
 * record is not adopted: in both, adoption demands an exact GROUND match where
 * it should unify and instantiate.
 */
class ChainedMethodNameClashTest {

    @TempDir lateinit var root: Path

    private fun write(module: String, body: String) {
        val file = root.resolve("src").resolve("$module.mz")
        Files.createDirectories(file.parent)
        Files.writeString(file, body.trimIndent())
    }

    /** Typecheck against [root] and run the lowered program, the way a build does. */
    private fun run(main: String): Any? {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        ModuleTypeChecker.clearCache()
        ModuleTypeChecker.clearTypeCheckingStack()
        write("main", main)
        val parsed = MezzeParser().parseParsedModule(
            main.trimIndent(), root.resolve("src/main.mz").toString(),
        )
        val checked = TypeChecker().typeCheck(parsed, root.resolve("src"), true, null)

        val engine = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .option("engine.Compilation", "false")
            .build()
        val context = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine).allowAllAccess(true).allowExperimentalOptions(true).build()
        try {
            MezzeLanguage.clearLastResult()
            MezzeLanguage.setFunctionsForDirectExecution(
                checked.loweredFunctions, checked.performerRecords, root,
            )
            context.eval(Source.newBuilder(MezzeLanguage.ID, "", "<test>").buildLiteral())
            return MezzeLanguage.getLastExecutionResult()
        } finally {
            try { context.close(true) } catch (_: Exception) {}
            try { engine.close() } catch (_: Exception) {}
        }
    }

    /**
     * The competing `finish` arrives by IMPORT — that module boundary is the
     * third ingredient, per the class doc.
     *
     * `Outcome` is declared here rather than being the stdlib's `Result`: an
     * inherent impl may only be written by the owner of the receiver's outermost
     * constructor, so `impl Result Resp Err` in a user module is refused by
     * V0406 (Rust's answer to `impl Vec<MyType> { }`). What this test needs is a
     * same-named method on a two-argument applied type in ANOTHER module, and a
     * locally declared constructor gives exactly that.
     */
    private fun writeVocab() = write(
        "vocab",
        """
        pub type Resp = { body: Str }
        pub type Err  = { msg: Str }
        pub type Outcome 'a 'e = { v: 'a, e: 'e }

        impl Outcome Resp Err where {
          finish = { it } -> "from-outcome",
        }
        """,
    )

    /** The reported failure. */
    @Test @Timeout(180)
    fun `a chained method is not confused by another module's same-named method`() {
        writeVocab()
        write(
            "client",
            """
            use vocab::{ Resp, Err }

            pub type Rule = { n: Int }

            impl Rule where {
              step   = { it: 'it } -> { n = it.n },
              finish = { it: 'it, x: Str } -> { n = it.n + 1 },
            }

            pub let mk: {} -> Rule = {} -> { n = 0 }
            pub let build: {} -> Rule = {} -> mk {}.step {}.finish { x = "a" }
            """,
        )
        assertEquals(1L, run("""
            use client::{ build }
            build {}.n
        """))
    }

    /** Control — no chain. Reported to pass, so it isolates the chain. */
    @Test @Timeout(180)
    fun `a direct call is fine with the cross-module clash present`() {
        writeVocab()
        write(
            "client",
            """
            use vocab::{ Resp, Err }

            pub type Rule = { n: Int }

            impl Rule where {
              step   = { it: 'it } -> { n = it.n },
              finish = { it: 'it, x: Str } -> { n = it.n + 1 },
            }

            pub let mk: {} -> Rule = {} -> { n = 0 }
            pub let build: {} -> Rule = {} -> mk {}.finish { x = "a" }
            """,
        )
        assertEquals(1L, run("""
            use client::{ build }
            build {}.n
        """))
    }

    /** Control — chain, no competing name anywhere. */
    @Test @Timeout(180)
    fun `a cross-module chain is fine with no competing name`() {
        write("vocab", "pub type Resp = { body: Str }")
        write(
            "client",
            """
            use vocab::{ Resp }

            pub type Rule = { n: Int }

            impl Rule where {
              step   = { it: 'it } -> { n = it.n },
              finish = { it: 'it, x: Str } -> { n = it.n + 1 },
            }

            pub let mk: {} -> Rule = {} -> { n = 0 }
            pub let build: {} -> Rule = {} -> mk {}.step {}.finish { x = "a" }
            """,
        )
        assertEquals(1L, run("""
            use client::{ build }
            build {}.n
        """))
    }

    /**
     * Diagnostic: name the chain's intermediate type and the crash goes away?
     *
     * If so, the fault is that `step`'s result is ANONYMOUS — `{ n = it.n }` with
     * `it: 'it` gives `n` a variable type, so the literal does not match
     * `Rule = { n: Int }` exactly and adoption (11.3) does not fire. An anonymous
     * receiver reaches no nominal inherent impl (11.17), so `.finish` degrades to
     * a field access, which is why the message says FIELD not found.
     */
    @Test @Timeout(180)
    fun `annotating the chain's intermediate value avoids the crash`() {
        writeVocab()
        write(
            "client",
            """
            use vocab::{ Resp, Err }

            pub type Rule = { n: Int }

            impl Rule where {
              step   = { it: 'it } -> { n = it.n },
              finish = { it: 'it, x: Str } -> { n = it.n + 1 },
            }

            pub let mk: {} -> Rule = {} -> { n = 0 }
            pub let build: {} -> Rule = {} -> do
              let s: Rule = mk {}.step {}
              s.finish { x = "a" }
            """,
        )
        assertEquals(1L, run("""
            use client::{ build }
            build {}.n
        """))
    }
}
