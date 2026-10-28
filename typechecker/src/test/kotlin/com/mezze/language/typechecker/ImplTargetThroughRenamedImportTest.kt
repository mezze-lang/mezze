package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * An impl whose TARGET arrived under `use … as` is still findable.
 *
 * The claim this pins was carried as an open hole — "an impl's TARGET is a
 * SPELLING, so `use … as` makes it unfindable; the alias resolution dies at the
 * module boundary", with a seven-hop chain measured and a reported
 *
 *     error[V0402]: Thing does not implement 'Describe'.
 *       Available impls: impl Describe for Widget
 *
 * naming the very impl it refused. Re-run on 2026-10-28 it does not reproduce:
 * the three-module arrangement below type-checks, and the method call resolves.
 *
 * It had NO test, which is why the claim outlived the fix. That is the whole
 * reason this file exists — a note claiming an open bug cost a fresh
 * investigation to disprove, twelve times over in one sweep. A test makes the
 * next check a test run.
 *
 * The control matters: with the target under its own name the same arrangement
 * must also pass, so a failure here tells you whether the rename is implicated
 * or the whole cross-module impl path is down.
 */
class ImplTargetThroughRenamedImportTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack()
        parser = MezzeParser()
        tc = TypeChecker()
    }

    private fun writeModule(name: String, body: String) {
        val srcDir = tempDir.resolve("src"); Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("$name.mz"), body.trimIndent())
    }

    private fun checkMain(mainSrc: String): Result<TypeCheckResult> {
        val srcDir = tempDir.resolve("src"); Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("main.mz"), mainSrc.trimIndent())
        return runCatching {
            tc.typeCheck(parser.parseParsedModule(mainSrc.trimIndent(), "main.mz"), tempDir)
        }
    }

    private val caller = """
        use renamed::{ Thing }
        use renamer::{ Describe }
        pub let go : {} -> Str = { } -> do
          let t: Thing = { n = 1 }
          t.describe {}
        pub let main : {} -> {} = { } -> {}
    """.trimIndent()

    /** The reported case: the impl target is the RENAMED spelling. */
    @Test fun `an impl whose target arrived renamed is found from the original name`() {
        writeModule("renamed", "pub type Thing = { n: Int }")
        writeModule("renamer", """
            use renamed::{ Thing as Widget }
            ability Describe for 'a where { describe: { it: 'a } -> Str }
            impl Describe for Widget where { describe = { it } -> "a widget" }
        """)
        val r = checkMain(caller)
        assertTrue(
            r.isSuccess,
            "an impl declared for a renamed import of Thing must be found for Thing; " +
                "got: ${r.exceptionOrNull()?.message}",
        )
    }

    /** CONTROL: the target under its own name, same three-module shape. */
    @Test fun `an impl whose target is its own name is found`() {
        writeModule("renamed", "pub type Thing = { n: Int }")
        writeModule("renamer", """
            use renamed::{ Thing }
            ability Describe for 'a where { describe: { it: 'a } -> Str }
            impl Describe for Thing where { describe = { it } -> "a thing" }
        """)
        val r = checkMain(caller)
        assertTrue(
            r.isSuccess,
            "the control must pass, else the cross-module impl path is down and " +
                "the rename is not what the other case is measuring; got: " +
                "${r.exceptionOrNull()?.message}",
        )
    }
}
