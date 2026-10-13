package com.mezze.language.typechecker

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * The two emit monoliths do not grow back.
 *
 * `TypedASTCompiler` was 5,580 lines and `TypedAstBuilder` 3,107 — not because
 * either does two jobs, but because everything they touch had nowhere else to
 * live. Four subjects were moved out (dictionary/evidence and pattern lowering
 * from the compiler; call emission and pattern emission from the builder), each
 * as a pure move: indentation and receiver changed, no body edited.
 *
 * A size bound is a crude instrument and it is deliberately crude. It cannot say
 * whether a file is well organised; it can only say that the thing which made
 * these two hard to work in — new code defaulting to the biggest file because
 * that is where the neighbours are — is being noticed. Adding 200 lines to
 * `TypedASTCompiler` should be a decision, not a default.
 *
 * Raising a bound is allowed and is not a defeat. Do it in the same commit as
 * the growth, with the reason, the way the placeholder and elaboration ratchets
 * are moved.
 */
class EmitFileSizeRatchetTest {

    private fun lines(relative: String): Int {
        val root = System.getProperty("mezze.project.root")
            ?: error("mezze.project.root is not set; the build sets it for the test task")
        // Fail LOUDLY and clearly if the root is set but is not the mezze repo —
        // e.g. a stale copy served from gradle cache / an old daemon (a wrong
        // `/tmp/...` staging dir once produced a cryptic "file not found" here).
        // A sentinel that has always existed at the root turns that into a
        // self-explanatory message instead of a confusing per-file failure.
        assertTrue(
            File(root, "settings.gradle.kts").exists(),
            "mezze.project.root='$root' does not look like the mezze repo " +
                "(no settings.gradle.kts there) — likely a stale cached root from a " +
                "prior run under a different directory; re-run with --no-daemon from a clean checkout.",
        )
        val f = File(root, relative)
        assertTrue(f.exists(), "expected $relative at ${f.absolutePath}")
        return f.readLines().size
    }

    @Test
    fun `the emit monoliths stay split`() {
        for ((path, bound) in BOUNDS) {
            val actual = lines(path)
            assertTrue(
                actual <= bound,
                "$path is $actual lines, over its bound of $bound. Either move the new " +
                    "code to the file whose subject it shares — the siblings listed in " +
                    "this test are what came out already — or raise the bound here, in " +
                    "this commit, with the reason.",
            )
        }
    }

    @Test
    fun `the files that were split out still exist`() {
        // Guards the guard. Deleting a sibling and folding it back would satisfy
        // nothing above — the bounds would simply start failing later, once the
        // re-merged file grew — so the split itself is asserted directly.
        for (path in SPLIT_OUT) {
            assertTrue(lines(path) > 50, "$path should hold a real subject, not a stub")
        }
    }

    private companion object {
        const val C = "typechecker/src/main/kotlin/com/mezze/language/typechecker/compiler/"
        const val E = "typechecker/src/main/kotlin/com/mezze/language/typechecker/emit/"

        /** Measured after the split, with a little slack so an ordinary fix does
         *  not have to touch this file. Lower is better; never raise silently. */
        val BOUNDS = mapOf(
            C + "TypedASTCompiler.kt" to 4300,   // was 5,580
            E + "TypedAstBuilder.kt" to 2350,    // was 3,107
        )

        val SPLIT_OUT = listOf(
            C + "DictEvidence.kt",        // which calls need evidence, and which slot
            C + "PatternCompilation.kt",  // match/pattern lowering to IR
            E + "CallEmit.kt",            // the three call shapes
            E + "PatternEmit.kt",         // match-case binding types
        )
    }
}
