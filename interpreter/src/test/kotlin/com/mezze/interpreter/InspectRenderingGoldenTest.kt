package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Task D.1's precursor — what `to_dbg` actually prints today, pinned.
 *
 * Inspect has no generated impl. It exists as an implicit "everything derives
 * it" in the typechecker and a deep-printer in the runtime, and the two are only
 * consistent because the printer never refuses: a record holding a function
 * renders as `{ name = "double", run = <function:lambda> }`, which no
 * field-by-field derivation would produce.
 *
 * D.1 replaces that with a generated impl, and 617 assertion sites across 110
 * test files depend on the exact strings. Changing the renderer without knowing
 * what it currently emits would be a 617-site guess. This is the characterisation
 * instead: one file recording the rendering of every shape, so D.1 becomes
 * "reproduce this" — mechanical and verifiable — and any accidental change to
 * user-visible output shows up here first, as a diff, rather than scattered
 * across unrelated suites.
 *
 * The golden file is DATA, not a specification. Some of what it records is
 * probably not what anyone would design; the point is that it is what programs
 * see today.
 */
class InspectRenderingGoldenTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val prelude = """
        use std::collections::seq
        use std::collections::list::{ from_seq as list_of }
        use std::collections::set::{ from_seq as set_of }
        use std::collections::deque::{ from_seq as deque_of }
    """.trimIndent()

    /**
     * label → (declarations, the `let v: T = …` binding whose rendering is
     * recorded). A let with an annotation rather than an inline ascription
     * because Mezze has no ascription syntax — and the annotation is the point
     * for several cases: an `Option Int` renders differently from whatever a
     * bare `Some { val = 1 }` infers to.
     */
    private val cases: List<Triple<String, String, String>> = listOf(
        Triple("int", "", "let v: Int = 42"),
        Triple("int-negative", "", "let v: Int = 0 - 7"),
        Triple("float", "", "let v: Float = 3.5"),
        Triple("str", "", "let v: Str = \"hi\""),
        Triple("str-empty", "", "let v: Str = \"\""),
        Triple("str-with-escape", "", "let v: Str = \"a\\nb\""),
        Triple("bool-true", "", "let v: Bool = True"),
        Triple("bool-false", "", "let v: Bool = False"),
        Triple("seq-empty", "", "let v: [Int] = []"),
        Triple("seq-ints", "", "let v: [Int] = [1, 2, 3]"),
        Triple("seq-strs", "", "let v: [Str] = [\"a\", \"b\"]"),
        Triple("seq-nested", "", "let v: [[Int]] = [[1], [2, 3]]"),
        Triple("list", "", "let v: List Int = list_of { xs = [1, 2] }"),
        Triple("set", "", "let v: Set Int = set_of { xs = [1, 2] }"),
        Triple("deque", "", "let v: Deque Int = deque_of { xs = [1, 2] }"),
        Triple("option-some", "", "let v: Option Int = Some { val = 1 }"),
        Triple("option-none", "", "let v: Option Int = None"),
        Triple("result-ok", "", "let v: Result Int Str = Ok { val = 1 }"),
        Triple("result-err", "", "let v: Result Int Str = Err { error = \"bad\" }"),
        Triple("record", "", "let v: { x: Int, y: Str } = { x = 1, y = \"a\" }"),
        Triple("record-nested", "", "let v: { inner: { x: Int }, n: Int } = { inner = { x = 1 }, n = 2 }"),
        Triple("record-of-seq", "", "let v: { xs: [Int] } = { xs = [1, 2] }"),
        Triple("seq-of-record", "", "let v: [{ x: Int }] = [{ x = 1 }, { x = 2 }]"),
        Triple("option-of-record", "", "let v: Option { x: Int } = Some { val = { x = 1 } }"),
        Triple("seq-of-option", "", "let v: [Option Int] = [Some { val = 1 }, None]"),
        Triple(
            "variant-with-payload",
            "type V = | A { n: Int }, B { s: Str } |",
            "let v: V = A { n = 1 }",
        ),
        Triple(
            "variant-no-payload",
            "type E = | Red, Green |",
            "let v: E = Red",
        ),
        Triple(
            "variant-in-seq",
            "type E = | Red, Green |",
            "let v: [E] = [Red, Green]",
        ),
        Triple(
            "newtype",
            "newtype Money = Int",
            "let v: Money = Money.(5)",
        ),
        Triple(
            "record-of-newtype",
            "newtype Id = Int\ntype R = { id: Id }",
            "let v: R = { id = Id.(7) }",
        ),
        Triple(
            "record-with-function-field",
            "type H = { name: Str, run: { x: Int } -> Int }",
            "let v: H = { name = \"d\", run = { x } -> x }",
        ),
        Triple(
            "recursive-variant",
            "type T = | Node { l: T, r: T }, Leaf { n: Int } |",
            "let v: T = Node { l = Leaf { n = 1 }, r = Leaf { n = 2 } }",
        ),
    )

    private fun render(decls: String, binding: String): String {
        val source = buildString {
            appendLine(prelude)
            if (decls.isNotBlank()) appendLine(decls)
            appendLine(binding)
            appendLine("v.to_dbg {}")
        }
        return runCatching { MezzeTestHelper.run(source) }
            .fold({ it.toString() }, { e ->
                "<<error: ${e.message?.lineSequence()?.firstOrNull()?.trim()?.take(120)}>>"
            })
    }

    private fun currentRendering(): String = buildString {
        // One line per case, so a change shows up as a readable diff. A rendered
        // value may itself contain a newline (`"a\nb"` prints across two lines),
        // so escape it here rather than letting one case silently consume the
        // next case's line.
        for ((label, decls, binding) in cases) {
            val rendered = render(decls, binding).replace("\\", "\\\\").replace("\n", "\\n")
            appendLine("$label\t$rendered")
        }
    }

    /**
     * Anchored on a file that exists in the repo rather than on the working
     * directory: Gradle runs this module's tests with cwd = interpreter/, but the
     * same test invoked from the repo root would otherwise write a second golden
     * file one directory deep.
     */
    private fun goldenFile(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return File(dir, "interpreter/src/test/resources/inspect-rendering-golden.txt")
    }

    @Test
    fun `to_dbg renders every shape exactly as recorded`() {
        val golden = goldenFile()
        val actual = currentRendering()
        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            fail("wrote a new golden file at ${golden.path} — review it, then re-run")
        }
        val expected = golden.readText()
        if (expected != actual) {
            val expectedLines = expected.lines()
            val actualLines = actual.lines()
            val diffs = expectedLines.zip(actualLines)
                .filter { (e, a) -> e != a }
                .joinToString("\n") { (e, a) -> "  was: $e\n  now: $a" }
            fail(
                "`to_dbg` output changed — this is user-visible, and 617 assertions " +
                    "across the suite depend on it:\n$diffs\n\n" +
                    "If the change is intended (task D.1 replacing the runtime printer " +
                    "with a generated impl), update ${golden.path} in the same commit.",
            )
        }
        assertEquals(expected, actual)
    }
}
