package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows

/**
 * Tests for recursive let-bound lambdas inside do blocks.
 *
 * Pattern: `let f = {x} -> ... f {x - 1}` inside a do block — the lambda
 * body should be able to reference its own binding name.
 */
class DoBlockRecursiveLetTest {
    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = MezzeParser()
    }

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    @Test
    fun `simple recursive countdown`() {
        val code = """
            let outer = {n: Int} -> do
              let go = {i: Int} -> if i == 0 then 0 else go {i = i - 1}
              go {i = n}
        """.trimIndent()
        parseAndTypeCheck(code)
    }

    @Test
    fun `recursive accumulator`() {
        val code = """
            let outer = {n: Int} -> do
              let go = {acc: Int, i: Int} -> if i == n
                then acc
                else go {acc = acc + i, i = i + 1}
              go {acc = 0, i = 0}
        """.trimIndent()
        parseAndTypeCheck(code)
    }

    @Test
    fun `recursive with type annotation`() {
        val code = """
            let outer = {n: Int} -> do
              let go: {i: Int} -> Int = {i} -> if i == 0 then 0 else go {i = i - 1}
              go {i = n}
        """.trimIndent()
        parseAndTypeCheck(code)
    }

    @Test
    fun `non-recursive lambda still works`() {
        val code = """
            let outer = {n: Int} -> do
              let double = {x: Int} -> x * 2
              double {x = n}
        """.trimIndent()
        parseAndTypeCheck(code)
    }

    @Test
    fun `factorial`() {
        val code = """
            let factorial = {n: Int} -> do
              let go = {i: Int, acc: Int} -> if i == 0
                then acc
                else go {i = i - 1, acc = acc * i}
              go {i = n, acc = 1}
        """.trimIndent()
        parseAndTypeCheck(code)
    }

    /**
     * BUG 35 — a recursive LOCAL lambda calling a function that handles its own
     * effect.
     *
     * `handled` closes over its own `perform`, so its type carries `<*>` —
     * "handled here", a MARKER and not an obligation. Nothing in the loop
     * performs an unhandled effect, and it was refused anyway:
     *
     *     expected { i: Int, acc: 'a } … -> 'b <*>
     *     but got  { i: 'd, acc: 'c }  -> 'e
     *
     * The self-call cannot know the row its own body will need, so it builds a
     * PURE arrow; equating that with the body's real row failed on the row while
     * every type underneath it would have unified. The do-block `let` equated
     * WITH the row; the top-level binding already dropped it, which is why the
     * IDENTICAL loop at top level was accepted — the control below.
     *
     * One rule for both sites now: `selfReferenceType`.
     */
    @Test
    fun `a recursive local lambda may call a function that handles its own effect`() {
        val code = """
            type H = { tag: Int }
            effect E where { op: {} -> Int <E> }
            impl E for H where { op = {} -> 1 }

            let handled = {} -> perform H { tag = 0 } in E.op {}

            let local_loop = { n } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else go { i = i - handled {}, acc = acc + i }
              go { i = n, acc = 0 }
        """.trimIndent()
        parseAndTypeCheck(code)
    }

    /** THE CONTROL: the same loop at top level, which always worked. */
    @Test
    fun `the same loop at top level is accepted`() {
        val code = """
            type H = { tag: Int }
            effect E where { op: {} -> Int <E> }
            impl E for H where { op = {} -> 1 }

            let handled = {} -> perform H { tag = 0 } in E.op {}

            let go_top = { i, acc } ->
              if i <= 0 then acc
              else go_top { i = i - handled {}, acc = acc + i }
        """.trimIndent()
        parseAndTypeCheck(code)
    }

    @Test
    fun `still detects undefined variables`() {
        val code = """
            let outer = {n: Int} -> do
              let result = undefined_var + n
              result
        """.trimIndent()
        assertThrows<TypeCheckError> { parseAndTypeCheck(code) }
    }
}
