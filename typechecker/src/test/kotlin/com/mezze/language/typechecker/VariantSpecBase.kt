package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.diagnostics.display
import com.mezze.language.ast.prettyPrintTypeWithAliases
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Shared harness for the variant specification suite.
 *
 * The suite encodes the variant rules as they are SPECIFIED, not as the
 * typechecker currently behaves — so a red test is a gap to close rather than a
 * test to fix. Making the suite green is what makes variant checking sound.
 *
 * Two things about how the assertions are written, both learned the hard way:
 *
 * 1. A rule that only asserts "this is refused" is weak evidence. Rigid
 *    equality refuses BOTH directions, so a direction rule looks healthy under
 *    an implementation that has no direction at all. Every inclusion rule here
 *    therefore ships with a mirror case that must be ACCEPTED — see the
 *    `widening` group in VariantSpecInclusionTest. Do not add a `rejects` for a
 *    directional rule without its `accepts` twin.
 *
 * 2. Assertions name an error CODE, never a message fragment. The prose moves;
 *    V0601/V0704/T0201 do not.
 *
 * Each test gets a fresh TypeChecker: MezzeTestHelper's is static and a warmed
 * route can carry a broken program to green, which cost a long hunt once
 * already.
 */
abstract class VariantSpecBase {

    @TempDir lateinit var tempDir: Path
    protected lateinit var parser: MezzeParser
    protected lateinit var tc: TypeChecker

    @BeforeEach
    fun setupSpecHarness() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack()
        parser = MezzeParser()
        tc = TypeChecker()
    }

    /** Type-check a single module, returning success or the thrown error. */
    protected fun check(src: String): Result<TypeCheckResult> = runCatching {
        tc.typeCheck(
            parser.parseParsedModule(src.trimIndent(), "test.mz"),
            Paths.get("."),
            enablePrelude = true,
        )
    }

    /** The rule says this program is well typed. */
    protected fun accepts(src: String, why: String) {
        val r = check(src)
        if (r.isFailure) {
            fail("$why\n\nexpected: accepted\n     got: ${r.exceptionOrNull()?.message}\n\n$src")
        }
        assertNotNull(r.getOrNull(), why)
    }

    /**
     * The rule says this program is refused. [code] names the diagnostic when
     * the rule fixes one; pass null only where the rule genuinely does not care
     * which check fires.
     */
    protected fun rejects(src: String, code: String?, why: String) {
        val r = check(src)
        if (r.isSuccess) {
            fail("$why\n\nexpected: refused${code?.let { " ($it)" } ?: ""}\n     got: accepted\n\n$src")
        }
        assertCode(r, code, why)
    }

    /**
     * The diagnostic a rule fixes, read from [TypeCheckError.code] rather than
     * from the message: the prose moves, the code does not, and the `T0201`
     * header is added by the CLI so it never appears in `message` at all.
     */
    private fun assertCode(r: Result<TypeCheckResult>, code: String?, why: String) {
        if (code == null) return
        val e = r.exceptionOrNull()
        val actual = (e as? TypeCheckError)?.code?.display()
        assertTrue(
            actual == code,
            "$why\n\nrefused, but with the wrong diagnostic.\n" +
                "expected: $code\n     got: ${actual ?: "(not a TypeCheckError)"}\n" +
                "message: ${e?.message}",
        )
    }

    /** The inferred type of a top-level `let`, for the rules that assert a type. */
    protected fun letType(src: String, name: String): MezzeType? {
        val r = check(src).getOrNull() ?: return null
        val typed = r.typedModule ?: return null
        for (decl in typed.decls) {
            if (decl is TypedLetDecl && decl.name == name) return decl.scheme.type
        }
        return null
    }

    /** The printed form of a top-level `let`'s type. */
    protected fun printedType(src: String, name: String): String {
        val t = letType(src, name)
            ?: fail(
                "no type for `$name` — the program did not typecheck, so the type " +
                    "assertion below is measuring nothing:\n\n" +
                    "${check(src).exceptionOrNull()?.message}\n\n$src",
            )
        return prettyPrintTypeWithAliases(t, emptyMap())
    }

    // ---------------------------------------------------------------- modules

    protected fun writeModule(name: String, body: String) {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("$name.mz"), body.trimIndent())
    }

    /** Type-check `main.mz` against whatever `writeModule` has put beside it. */
    protected fun checkMain(mainSrc: String): Result<TypeCheckResult> {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("main.mz"), mainSrc.trimIndent())
        return runCatching {
            tc.typeCheck(
                parser.parseParsedModule(mainSrc.trimIndent(), "main.mz"),
                tempDir,
                enablePrelude = true,
            )
        }
    }

    protected fun acceptsMain(mainSrc: String, why: String) {
        val r = checkMain(mainSrc)
        if (r.isFailure) {
            fail("$why\n\nexpected: accepted\n     got: ${r.exceptionOrNull()?.message}")
        }
    }

    protected fun rejectsMain(mainSrc: String, code: String?, why: String) {
        val r = checkMain(mainSrc)
        if (r.isSuccess) {
            fail("$why\n\nexpected: refused${code?.let { " ($it)" } ?: ""}\n     got: accepted")
        }
        assertCode(r, code, why)
    }

    protected companion object {
        const val MAIN = "pub let main = {} -> {}"
    }
}
