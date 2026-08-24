package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.*
import com.mezze.language.typedast.*
import com.mezze.language.typedast.TypedModule as TypedAstModule
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #110 perf guardrail: verify newtype construction (`Newtype.(x)`) and
 * destructuring (`match … is Newtype.(y) -> y`) are ERASED at emit time —
 * they must produce the same FunctionIR as the equivalent code without the
 * wrapper. Runtime is oblivious to newtypes.
 *
 * If someone accidentally adds a wrap/unwrap IR node for newtypes, these
 * tests will fail with a structural mismatch, catching the perf regression
 * BEFORE it hits the JIT.
 */
class NewtypeErasureIRTest {

    private val parser = MezzeParser()

    /** Compile Mezze source to the top-level `__entry__` function's IR body. */
    private fun compileEntryBody(source: String): NodeIR {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()

        val parsed = parser.parseParsedModule(source.trimIndent(), "erasure-test.mz")
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val tc = TypeChecker()
        val result = tc.checkParsedModule(parsed, projectRoot, enablePrelude = true)
        assertTrue(result is TypeChecker.TypeCheckResult.Success, "typecheck failed: $result")
        val success = result as TypeChecker.TypeCheckResult.Success
        val typedModule = success.typedModule!!
        val wrapped = com.mezze.language.typechecker.emit.EntryFunctionBuilder.wrapTopLevel(typedModule)

        val allAbilityImpls = wrapped.abilityImpls
        val compiler = com.mezze.language.typechecker.compiler.TypedASTCompiler(
            abilityImpls = allAbilityImpls,
            stdlibHashes = PrecompiledStdlib.preloweredFunctions.keys,
        )
        val compiled = compiler.compile(wrapped)

        val entryFn = compiled.functions.firstOrNull { it.name == "__entry__" }
            ?: compiled.functions.first()
        return entryFn.body
    }

    /**
     * Structural equality that IGNORES SourceInfo — nodes emitted from
     * different source locations should compare equal if their shape is
     * identical. Uses NodeIR.toString() which renders the type + values,
     * with SourceInfo replaced by a placeholder.
     */
    private fun sameShape(a: NodeIR, b: NodeIR): Boolean {
        return stripSrc(a.toString()) == stripSrc(b.toString())
    }

    /**
     * Replace SourceInfo tuples AND per-decl contentHash values with
     * placeholders so shape comparison ignores things that legitimately
     * differ between two programs that emit equivalent IR (source
     * locations, and the content-hash of the surrounding let-binding
     * which encodes the source text of the declaration).
     */
    private fun stripSrc(s: String): String = s
        .replace(Regex("SourceInfo\\([^)]*\\)"), "SRC")
        .replace(Regex("contentHash=[0-9a-f]+\\.\\.\\.,\\s*"), "")

    @Test
    fun `scalar newtype construction is erased to inner value`() {
        val plain = compileEntryBody("""
            42
        """)
        val newtype = compileEntryBody("""
            newtype Age = Int
            Age.(42)
        """)
        assertTrue(
            sameShape(plain, newtype),
            "Age.(42) IR must equal 42 IR — newtype construction should be erased.\n  plain=$plain\n  newtype=$newtype",
        )
    }

    @Test
    fun `scalar newtype destructure emits no wrap or unwrap IR nodes`() {
        // A newtype destructure is CORRECTLY erased when:
        //  (a) the emitted pattern is a plain Wildcard (no ctor check),
        //  (b) the pattern binds a slot for the inner value directly, and
        //  (c) no NewtypeConstructIR / NewtypeUnwrapIR / MakeVariantIR /
        //      ReadTag / similar wrapping node appears anywhere in the tree.
        val ir = compileEntryBody("""
            newtype Age = Int
            match Age.(42) is
              Age.(x) -> x
        """)
        val rendered = ir.toString()
        // Post-erasure the emitted match should have a Wildcard-with-binding
        // pattern for the sole arm (no ctor check).
        assertTrue(
            rendered.contains("Wildcard(slot="),
            "expected a Wildcard pattern (erased newtype), got: $rendered",
        )
        // No wrap/unwrap or variant-tag nodes should have crept in.
        for (banned in listOf("NewtypeConstruct", "NewtypeUnwrap", "MakeVariantIR", "ReadTagIR")) {
            assertTrue(
                !rendered.contains(banned),
                "IR must not contain '$banned' — newtype should be erased. IR=$rendered",
            )
        }
    }

    @Test
    fun `record newtype construction is erased to record literal`() {
        val plain = compileEntryBody("""
            { x = 1, y = 2 }
        """)
        val newtype = compileEntryBody("""
            newtype Point = { x: Int, y: Int }
            Point.({ x = 1, y = 2 })
        """)
        assertTrue(
            sameShape(plain, newtype),
            "Point.({x=1,y=2}) IR must equal {x=1,y=2} IR — record newtype should be erased.\n  plain=$plain\n  newtype=$newtype",
        )
    }

    @Test
    fun `newtype value returned via lambda is erased`() {
        val plain = compileEntryBody("""
            let f = { n: Int } -> n
            f { n = 42 }
        """)
        val newtype = compileEntryBody("""
            newtype Age = Int
            let f = { n: Int } -> Age.(n)
            f { n = 42 }
        """)
        // The lambda's body IR should be identical — Age.(n) erases to n.
        // The whole __entry__ shape (call + arg) should therefore match.
        assertTrue(
            sameShape(plain, newtype),
            "Wrapping the return in Age.(n) must not affect emitted IR.\n  plain=$plain\n  newtype=$newtype",
        )
    }
}
