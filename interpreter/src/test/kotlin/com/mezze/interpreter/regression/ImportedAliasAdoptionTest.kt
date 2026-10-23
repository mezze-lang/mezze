package com.mezze.interpreter.regression

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
import org.junit.jupiter.api.parallel.Isolated
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An IMPORTED record alias, a bare record literal, and an ability method on it.
 *
 * `adoptRecordAlias` is what lets `{ n = 2 }.tag {}` reach `impl Tag for Boxed`:
 * the literal adopts `Boxed`'s nominal identity, because the impl registers the
 * nominal target `TypeApplication(Boxed)` and the matcher has no arm pairing
 * that with an ANONYMOUS record. (The impl target cannot be structural instead
 * — `TypeAnnotationConverter.registryConfig` deliberately withholds alias
 * inlining from the emit path, or container impls stop being findable.)
 *
 * Adoption fires only on a UNIQUE match, and its own doc called that guard
 * "defensive … unique in practice". It was not: emit assembles its alias pool
 * from the declaration registry AND the import context, and a `use`d alias is
 * in BOTH, so an imported alias arrived as two entries carrying one content
 * hash. The guard then rejected the only candidate there was, the literal kept
 * `Anonymous`, and the call compiled to a plain field read that failed at
 * RUNTIME with `Field not found: tag`.
 *
 * Only an IMPORTED alias was affected — which is every alias a library exposes.
 * A single-module program never duplicates, so the whole existing
 * [AliasImplTargetTest] matrix passed throughout.
 *
 * The fix dedupes on the content HASH, so two genuinely different declarations
 * that happen to share a field shape stay ambiguous — pinned by
 * [two differently-named declarations of one shape stay ambiguous].
 *
 * A SECOND cause sits behind that one and survived its fix: adoption compares
 * field types exactly, so a literal LESS DETERMINED than the declaration never
 * matches at all (`chunks = None` is `Option 'a`; `Response` declares
 * `Option (Iter Bytes)`). Adoption works outward from the literal and cannot
 * close that case, so the receiver position — the only position with no
 * annotation to read — takes its expectation from the called ability's impl
 * target instead. That half is pinned by [a literal that leaves a field open
 * still dispatches], its determined control, and the ambiguity guard on the
 * name-only matching it rests on.
 */
@Isolated
class ImportedAliasAdoptionTest {

    @TempDir
    lateinit var root: Path

    private fun write(module: String, body: String) {
        val file = root.resolve("src").resolve("$module.mz")
        Files.createDirectories(file.parent)
        Files.writeString(file, body.trimIndent())
    }

    /** Typecheck against [root] and run the lowered program — the way a build does. */
    private fun run(main: String): Any? {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        ModuleTypeChecker.clearCache()
        ModuleTypeChecker.clearTypeCheckingStack()
        write("main", main)
        val parsed = MezzeParser().parseParsedModule(main.trimIndent(), root.resolve("src/main.mz").toString())
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
            MezzeLanguage.setFunctionsForDirectExecution(checked.loweredFunctions, checked.performerRecords, root)
            context.eval(Source.newBuilder(MezzeLanguage.ID, "", "<test>").buildLiteral())
            return MezzeLanguage.getLastExecutionResult()
        } finally {
            try { context.close(true) } catch (_: Exception) {}
            try { engine.close() } catch (_: Exception) {}
        }
    }

    private fun writeBoxed() = write("shapes", """
        pub type Boxed = { n: Int }

        ability Tag for 'a where {
          tag: { it: 'a } -> Int
        }

        impl Tag for Boxed where {
          tag = { it } -> it.n + 100
        }
    """)

    /** THE regression: nothing names `Boxed` at the call site but the impl. */
    @Test
    @Timeout(180)
    fun `bare literal dispatches an ability impl written for an imported alias`() {
        writeBoxed()
        val result = run("""
            use shapes::{ Boxed, Tag }

            { n = 2 }.tag {}
        """)
        assertEquals(102L, result)
    }

    /**
     * The alias name is not even imported — only the ability. Adoption reads the
     * alias POOL, not the call site's spelling, so this must dispatch too; it is
     * also the shape that proves the fix is not about what `use` mentions.
     */
    @Test
    @Timeout(180)
    fun `bare literal dispatches without importing the alias name`() {
        writeBoxed()
        val result = run("""
            use shapes::{ Tag }

            { n = 2 }.tag {}
        """)
        assertEquals(102L, result)
    }

    /** The ability and its impl are LOCAL; only the alias is imported. */
    @Test
    @Timeout(180)
    fun `bare literal dispatches a local impl written for an imported alias`() {
        write("shapes", "pub type Boxed = { n: Int }")
        val result = run("""
            use shapes::{ Boxed }

            ability Tag2 for 'a where {
              tag2: { it: 'a } -> Int
            }

            impl Tag2 for Boxed where {
              tag2 = { it } -> it.n + 100
            }

            { n = 2 }.tag2 {}
        """)
        assertEquals(102L, result)
    }

    /** An annotated receiver never depended on adoption — the control. */
    @Test
    @Timeout(180)
    fun `annotated receiver dispatches, as it always did`() {
        writeBoxed()
        val result = run("""
            use shapes::{ Boxed, Tag }

            let b: Boxed = { n = 2 }
            b.tag {}
        """)
        assertEquals(102L, result)
    }

    /**
     * A literal LESS DETERMINED than the declaration it is meant to be.
     *
     * `c = None` types the field `Option 'a` where the alias declares
     * `Option Int`. Adoption compares field types exactly, so it finds no match
     * however many entries the pool holds — this is not the duplicate above, and
     * survived that fix. The receiver has no annotation to read either, so the
     * expectation comes from the one thing that does determine it: the ability's
     * impl target.
     *
     * Measured rather than assumed: `Some { val = 7 }` on the SAME alias
     * dispatched before this and is kept below as the control, so the factor is
     * determinedness and not Option's two representations.
     */
    @Test
    @Timeout(180)
    fun `a literal that leaves a field open still dispatches`() {
        write("shapes", """
            pub type WithOpt = { a: Int, c: Option Int }

            ability Opty for 'a where {
              opty: { it: 'a } -> Int
            }

            impl Opty for WithOpt where {
              opty = { it } -> it.a + 20
            }
        """)
        val result = run("""
            use shapes::{ WithOpt, Opty }

            { a = 1, c = None }.opty {}
        """)
        assertEquals(21L, result)
    }

    /** The control for the test above: the same field, fully determined. */
    @Test
    @Timeout(180)
    fun `a literal that determines every field dispatches, as it always did`() {
        write("shapes", """
            pub type WithOpt = { a: Int, c: Option Int }

            ability Opty for 'a where {
              opty: { it: 'a } -> Int
            }

            impl Opty for WithOpt where {
              opty = { it } -> it.a + 20
            }
        """)
        val result = run("""
            use shapes::{ WithOpt, Opty }

            { a = 1, c = Some { val = 7 } }.opty {}
        """)
        assertEquals(21L, result)
    }

    /**
     * Two impls of ONE ability whose targets share field NAMES and differ only
     * in a field TYPE — declared legally, since their shapes are not identical.
     * The receiver leaves that very field open, so field names are all there is
     * to go on and they answer twice. Two candidates is not evidence for one, so
     * the expectation is declined rather than guessed.
     *
     * This is the guard on the NAME-ONLY matching the fix relies on; a version
     * that took the first candidate would answer 101 here.
     */
    @Test
    @Timeout(180)
    fun `two impl targets sharing field names are declined`() {
        write("shapes", """
            pub type Counted = { v: Option Int }
            pub type Named = { v: Option Str }

            ability Warm for 'a where {
              warm: { it: 'a } -> Int
            }

            impl Warm for Counted where {
              warm = { it } -> 101
            }

            impl Warm for Named where {
              warm = { it } -> 202
            }
        """)
        val outcome = runCatching {
            run("""
                use shapes::{ Counted, Named, Warm }

                { v = None }.warm {}
            """)
        }
        val error = outcome.exceptionOrNull()
        assertTrue(
            error != null,
            "expected the ambiguous receiver to be declined, got: ${outcome.getOrNull()}",
        )
    }

    /**
     * Two DIFFERENTLY-NAMED declarations of one field shape, imported from two
     * modules. A literal cannot be said to be either, so adoption must still
     * decline — deduping the pool must not widen adoption to genuine rivals.
     *
     * Note what this does NOT distinguish, and cannot: the dedupe key. A name
     * is part of a declaration's content, so two decls that both survive the
     * SHAPE filter share a name and therefore a hash — `type Thing = { n: Int }`
     * written in two modules is ONE content-addressed type, and a same-named
     * pair dispatches rather than conflicting (verified: it answers 102). Hash
     * and name keys are therefore indistinguishable through the compiler; the
     * hash is used because identity IS the hash, and the choice is pinned
     * directly on the rule in the ast module's RecordAliasAdoptionTest.
     */
    @Test
    @Timeout(180)
    fun `two differently-named declarations of one shape stay ambiguous`() {
        write("left", """
            pub type L = { n: Int }

            ability Tagged for 'a where {
              tagged: { it: 'a } -> Int
            }

            impl Tagged for L where {
              tagged = { it } -> it.n + 100
            }
        """)
        write("right", "pub type R = { n: Int }")
        val outcome = runCatching {
            run("""
                use left::{ L, Tagged }
                use right::{ R }

                { n = 2 }.tagged {}
            """)
        }
        // Declines rather than guessing: with no identity adopted the method
        // call stays a field read, and `tagged` is not a field of the literal.
        val error = outcome.exceptionOrNull()
        assertTrue(
            error != null && error.toString().contains("tagged"),
            "expected ambiguity to be declined, got: ${error ?: outcome.getOrNull()}",
        )
    }
}
