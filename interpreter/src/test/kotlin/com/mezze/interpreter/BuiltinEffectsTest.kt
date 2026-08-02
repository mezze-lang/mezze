package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertDoesNotThrow
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.interpreter.nodes.builtin.effects.StdinNatives
import com.mezze.interpreter.nodes.builtin.effects.MockStdinNatives
import java.io.ByteArrayInputStream

/**
 * Tests for builtin effects in V2 interpreter.
 * Migrated from V1 to V2 with unboxed values.
 */
class BuiltinEffectsTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @BeforeEach
    fun resetStdinState() {
        // MockStdin keeps an identity-keyed cursor map. The Mezze runtime
        // may intern a `lines` literal across tests, so reuse of e.g.
        // `MockStdin { lines = ["a", "b"] }` in two tests could share a
        // cursor and produce None on the second test's first read. Clear
        // between tests to make ordering irrelevant.
        MockStdinNatives.clearCursors()
        StdinNatives.resetStreams()
    }

    /** Helper to check if result is unit (either MezzeUnit singleton or empty MezzeRecord) */
    private fun isUnitResult(result: Any): Boolean {
        return MezzeTestHelper.isUnit(result) ||
               (result is MezzeRecord && result.fieldCount == 0)
    }

    @Test
    fun testSeqOfEffectCallsAsTerminalExpression() {
        // Top-level perform — works.
        val source = """
            use std::effects::log::{Console, println}

            perform Console in
                [println { msg = "first" }, println { msg = "second" }]
        """.trimIndent()

        val result = MezzeTestHelper.runWithOutput(source)
        val out = result.output
        assertTrue(out.contains("first"), "expected 'first' in output, got: <$out>")
        assertTrue(out.contains("second"), "expected 'second' in output, got: <$out>")
    }

    @Test
    fun testSeqOfEffectCallsAsTerminalInMainLet() {
        // Wrapped in `let main = ... -> ...` like real programs.
        // This is the exact shape that previously failed for match.
        val source = """
            use std::effects::log::{Console, println}

            let main = {} -> perform Console in
                [println { msg = "first" }, println { msg = "second" }]

            main {}
        """.trimIndent()

        val result = MezzeTestHelper.runWithOutput(source)
        val out = result.output
        assertTrue(out.contains("first"), "expected 'first' in output, got: <$out>")
        assertTrue(out.contains("second"), "expected 'second' in output, got: <$out>")
    }

    @Test
    fun testSeqOfEffectCallsRunsBothArms() {
        // Repro: if the seq-literal type rule conflates SuspendType with
        // value-shape (like match used to), the interpreter may either crash
        // when trying to construct the seq, OR silently drop one of the
        // effect calls. Either way, the captured stdout will be wrong.
        // Expected: both "first" and "second" are printed in order.
        val source = """
            use std::effects::log::{Console, println}

            perform Console in do
                let xs = [println { msg = "first" }, println { msg = "second" }]
                42
        """.trimIndent()

        val result = MezzeTestHelper.runWithOutput(source)
        // Both println side-effects should have fired.
        val out = result.output
        assertTrue(out.contains("first"), "expected 'first' in output, got: <$out>")
        assertTrue(out.contains("second"), "expected 'second' in output, got: <$out>")
        // And the final expression's value is 42.
        assertEquals(42L, result.value)
    }

    // ========== SuspendType-leak audit: other constructs ==========
    //
    // Each test puts an effectful expression in a position where the typechecker
    // might be storing/unifying its type through a fresh TypeVar. If the bug
    // pattern repeats, the side effect won't fire — output will be empty.

    @Test
    fun testRecordLitWithEffectfulFieldAsTerminal() {
        // Record literal with field whose value is effectful.
        val source = """
            use std::effects::log::{Console, println}

            perform Console in do
                let r = { x = println { msg = "rec" } }
                42
        """.trimIndent()
        val result = MezzeTestHelper.runWithOutput(source)
        assertTrue(result.output.contains("rec"),
            "expected 'rec' in output, got: <${result.output}>")
    }

    @Test
    fun testRecordLitAsTerminalUnderPerform() {
        // Record literal as terminal expression — field value is effectful.
        val source = """
            use std::effects::log::{Console, println}

            perform Console in
                { x = println { msg = "rec-terminal" } }
        """.trimIndent()
        val result = MezzeTestHelper.runWithOutput(source)
        assertTrue(result.output.contains("rec-terminal"),
            "expected 'rec-terminal' in output, got: <${result.output}>")
    }

    @Test
    fun testVariantConstructorWithEffectfulPayload() {
        // Variant constructor with effectful payload.
        val source = """
            use std::effects::log::{Console, println}

            type Wrap = | Wrapped { v: {} } |

            perform Console in
                Wrapped { v = println { msg = "variant" } }
        """.trimIndent()
        val result = MezzeTestHelper.runWithOutput(source)
        assertTrue(result.output.contains("variant"),
            "expected 'variant' in output, got: <${result.output}>")
    }

    @Test
    fun testFunctionArgIsEffectfulCallAsTerminal() {
        // Call where the argument value is itself an effectful call.
        // The arg's type is what gets unified with the function's param TypeVar.
        val source = """
            use std::effects::log::{Console, println}

            let identity = { x } -> x

            perform Console in
                identity { x = println { msg = "argcall" } }
        """.trimIndent()
        val result = MezzeTestHelper.runWithOutput(source)
        assertTrue(result.output.contains("argcall"),
            "expected 'argcall' in output, got: <${result.output}>")
    }

    @Test
    fun testIfBranchesWithEffectCallsAsTerminal() {
        // if/else with effectful arms — desugars to match per
        // ExpressionConstraints.kt line 111. Should be covered by match fix
        // already but let's verify directly.
        val source = """
            use std::effects::log::{Console, println}

            perform Console in
                if True then println { msg = "if-true" } else println { msg = "if-false" }
        """.trimIndent()
        val result = MezzeTestHelper.runWithOutput(source)
        assertTrue(result.output.contains("if-true"),
            "expected 'if-true' in output, got: <${result.output}>")
    }

    @Test
    fun testTupleLikeRecordOfEffectCalls() {
        // Multi-field record where each field is effectful.
        val source = """
            use std::effects::log::{Console, println}

            perform Console in
                { a = println { msg = "a-eff" }, b = println { msg = "b-eff" } }
        """.trimIndent()
        val result = MezzeTestHelper.runWithOutput(source)
        assertTrue(result.output.contains("a-eff"),
            "expected 'a-eff' in output, got: <${result.output}>")
        assertTrue(result.output.contains("b-eff"),
            "expected 'b-eff' in output, got: <${result.output}>")
    }

    @Test
    fun testMatchInsideLet() {
        // The match isn't terminal — it's the RHS of a `let`. Let unwraps
        // SuspendType so this should be fine.
        val source = """
            use std::effects::log::{Console, println}

            perform Console in do
                let r = match 1 is
                            1 -> println { msg = "let-match" }
                            _ -> println { msg = "let-other" }
                42
        """.trimIndent()
        val result = MezzeTestHelper.runWithOutput(source)
        assertTrue(result.output.contains("let-match"),
            "expected 'let-match' in output, got: <${result.output}>")
    }

    @Test
    fun testSeqInsideLetWithoutFix() {
        // Without the seq fix this would fail the same way match did when
        // the user uses the seq value somewhere.
        val source = """
            use std::effects::log::{Console, println}
            use std::collections::seq

            perform Console in do
                let xs = [println { msg = "let-seq-a" }, println { msg = "let-seq-b" }]
                seq::size { xs = xs }
        """.trimIndent()
        val result = MezzeTestHelper.runWithOutput(source)
        assertTrue(result.output.contains("let-seq-a"),
            "expected 'let-seq-a' in output, got: <${result.output}>")
        assertEquals(2L, result.value)
    }

    @Test
    fun testNestedMatchInMatchArm() {
        // match arm that contains another match. Inner match's TypeVar bind
        // should not leak through outer match's TypeVar bind.
        val source = """
            use std::effects::log::{Console, println}

            perform Console in
                match 1 is
                    1 -> match 2 is
                            2 -> println { msg = "nested" }
                            _ -> println { msg = "n-other" }
                    _ -> println { msg = "outer-other" }
        """.trimIndent()
        val result = MezzeTestHelper.runWithOutput(source)
        assertTrue(result.output.contains("nested"),
            "expected 'nested' in output, got: <${result.output}>")
    }

    @Test
    fun testBuiltinLogEffect() {
        val source = """
            use std::effects::log::{Log, Console, println}

            # Test the effect using stdlib Log
            perform Console in do
                println {msg = "Hello World!"}
                42
        """.trimIndent()

        // This should execute and print to console
        val result = eval(source)
        // Result should be 42 since we return that after logging
        assertEquals(42L, result)
    }

    @Test
    fun testBuiltinRandomEffect() {
        val source = """
            use std::effects::random::{Random, SeededRandom}

            # Test the effect using stdlib Random with SeededRandom for determinism
            perform SeededRandom { seed = 42 } in do
                let result = Random.random_int {min = 1, max = 10}
                result
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is Long)
        val randomValue = result as Long
        assertTrue(randomValue >= 1 && randomValue <= 10, "Random value $randomValue should be between 1 and 10")
    }

    @Test
    fun testParameterExtractionImprovement() {
        val source = """
            # Test that parameter names are correctly extracted
            effect TestEffect where {
              multiParam : {first: Str, second: Int, third: Float} -> Str <TestEffect>
            }

            impl TestEffect for TestHandler where {
              multiParam : {first: Str, second: Int, third: Float} -> Str =
                {first, second, third} -> first
            }

            # This should work with proper parameter extraction
            let main = {} -> do
                let result = perform TestHandler in TestEffect.multiParam {first = "Hello", second = 42, third = 3.14}
                result

            main {}
        """.trimIndent()

        // This test verifies that our parameter extraction improvement works
        // The impl should create a function with parameters ["first", "second", "third"]
        // instead of the old simplified ["args"]
        val result = eval(source)
        assertTrue(result is String)
        assertEquals("Hello", result)
    }

    /** Run `source` with a fake stdin pre-loaded with `input`. */
    private fun runWithStdin(input: String, source: String): Any {
        StdinNatives.setStreams(ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)))
        try {
            return eval(source)
        } finally {
            StdinNatives.resetStreams()
        }
    }

    @Test
    fun testStdinReadLineReturnsLine() {
        val source = """
            use std::effects::stdin::{Stdin, SystemStdin}

            perform SystemStdin in Stdin.read_line {}
        """.trimIndent()

        val result = runWithStdin("hello\n", source) as MezzeVariant
        assertEquals("Some", result.constructorName)
        assertEquals("hello", result.someValue)
    }

    @Test
    fun testStdinReadLineReturnsNoneOnEof() {
        val source = """
            use std::effects::stdin::{Stdin, SystemStdin}

            perform SystemStdin in Stdin.read_line {}
        """.trimIndent()

        val result = runWithStdin("", source) as MezzeVariant
        assertEquals("None", result.constructorName)
    }

    @Test
    fun testStdinReadLineStripsTrailingNewline() {
        val source = """
            use std::effects::stdin::{Stdin, SystemStdin}

            perform SystemStdin in do
                let a = Stdin.read_line {}
                let b = Stdin.read_line {}
                # Return whatever the second read produced
                b
        """.trimIndent()

        val result = runWithStdin("first\nsecond\n", source) as MezzeVariant
        assertEquals("Some", result.constructorName)
        assertEquals("second", result.someValue)
    }

    @Test
    fun testStdinReadChar() {
        val source = """
            use std::effects::stdin::{Stdin, SystemStdin}

            perform SystemStdin in Stdin.read_char {}
        """.trimIndent()

        val result = runWithStdin("xyz", source) as MezzeVariant
        assertEquals("Some", result.constructorName)
        assertEquals("x", result.someValue)
    }

    @Test
    fun testStdinReadCharReturnsNoneOnEof() {
        val source = """
            use std::effects::stdin::{Stdin, SystemStdin}

            perform SystemStdin in Stdin.read_char {}
        """.trimIndent()

        val result = runWithStdin("", source) as MezzeVariant
        assertEquals("None", result.constructorName)
    }

    @Test
    fun testStdinReadBytes() {
        val source = """
            use std::effects::stdin::{Stdin, SystemStdin}
            use std::collections::bytes

            perform SystemStdin in do
                let b = Stdin.read_bytes { max = 5 }
                bytes::size { b = b }
        """.trimIndent()

        val result = runWithStdin("hello world", source)
        assertEquals(5L, result)
    }

    @Test
    fun testStdinReadBytesReturnsEmptyOnEof() {
        val source = """
            use std::effects::stdin::{Stdin, SystemStdin}
            use std::collections::bytes

            perform SystemStdin in do
                let b = Stdin.read_bytes { max = 8 }
                bytes::size { b = b }
        """.trimIndent()

        val result = runWithStdin("", source)
        assertEquals(0L, result)
    }

    @Test
    fun testMockStdinReadLines() {
        val source = """
            use std::effects::stdin::{Stdin, MockStdin}

            perform MockStdin { lines = ["alice", "bob"] } in do
                let a = Stdin.read_line {}
                let b = Stdin.read_line {}
                let c = Stdin.read_line {}
                # Return the third read (should be None — only 2 lines)
                c
        """.trimIndent()

        val result = eval(source) as MezzeVariant
        assertEquals("None", result.constructorName)
    }

    @Test
    fun testMockStdinReadLineFirst() {
        val source = """
            use std::effects::stdin::{Stdin, MockStdin}

            perform MockStdin { lines = ["alice", "bob"] } in
                Stdin.read_line {}
        """.trimIndent()

        val result = eval(source) as MezzeVariant
        assertEquals("Some", result.constructorName)
        assertEquals("alice", result.someValue)
    }

    @Test
    fun testMockStdinReadLineSecond() {
        val source = """
            use std::effects::stdin::{Stdin, MockStdin}

            perform MockStdin { lines = ["alice", "bob"] } in do
                let a = Stdin.read_line {}
                Stdin.read_line {}
        """.trimIndent()

        val result = eval(source) as MezzeVariant
        assertEquals("Some", result.constructorName)
        assertEquals("bob", result.someValue)
    }

    @Test
    fun testMockStdinReadChar() {
        val source = """
            use std::effects::stdin::{Stdin, MockStdin}

            perform MockStdin { lines = ["xy"] } in
                Stdin.read_char {}
        """.trimIndent()

        val result = eval(source) as MezzeVariant
        assertEquals("Some", result.constructorName)
        assertEquals("x", result.someValue)
    }

    @Test
    fun testMockStdinSharedCursor() {
        // Reading 1 char "a", then read_line should yield the rest of that
        // line up to (and not including) \n — i.e. "b".
        val source = """
            use std::effects::stdin::{Stdin, MockStdin}

            perform MockStdin { lines = ["ab", "cd"] } in do
                let _c = Stdin.read_char {}
                Stdin.read_line {}
        """.trimIndent()

        val result = eval(source) as MezzeVariant
        assertEquals("Some", result.constructorName)
        assertEquals("b", result.someValue)
    }

    @Test
    fun testMockStdinReadBytes() {
        val source = """
            use std::effects::stdin::{Stdin, MockStdin}
            use std::collections::bytes

            perform MockStdin { lines = ["hello"] } in do
                let b = Stdin.read_bytes { max = 3 }
                bytes::size { b = b }
        """.trimIndent()

        val result = eval(source)
        assertEquals(3L, result)
    }

    @Test
    fun testMockStdinIsTtyIsFalse() {
        val source = """
            use std::effects::stdin::{Stdin, MockStdin}

            perform MockStdin { lines = [] } in
                Stdin.is_tty {}
        """.trimIndent()

        val result = eval(source)
        assertEquals(false, result)
    }

    @Test
    fun testNativeFunctionIntegration() {
        // Test that basic arithmetic native operations work
        // These are handled through builtin abilities (NumAdd, NumSub, etc.)
        val source = """
            # Test native operations through arithmetic operators
            let x = 10 + 20
            let y = x * 2
            y
        """.trimIndent()

        // This should execute without throwing an exception
        assertDoesNotThrow {
            val result = eval(source)
            // Native operations should execute successfully
            assertTrue(result is Long)
            assertEquals(60L, result)
        }
    }
}
