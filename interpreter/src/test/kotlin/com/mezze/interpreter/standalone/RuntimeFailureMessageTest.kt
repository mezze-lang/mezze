package com.mezze.interpreter.standalone

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * No Java class name may reach a user.
 *
 * The failure that prompted this printed, verbatim:
 *
 *     java.lang.RuntimeException: Cannot multiply String and Long
 *
 * A class name from a language the user does not write, and JVM type names for
 * values they would call `Str` and `Int`. [RuntimeFailureMessage] is the one
 * place that decides the text, because [com.mezze.language.cli.commands.WireRunner]
 * prints `ExitDetail.message` verbatim.
 *
 * The two directions are both pinned here. A check that only proved host
 * failures get relabelled would pass under an implementation that relabels
 * EVERYTHING — including `Division by zero`, which is the program's mistake and
 * must stay exactly as it is. `ArithmeticErrorTest` guards the same boundary
 * from the guest side.
 */
class RuntimeFailureMessageTest {

    @Test
    fun `a host throwable is relabelled as an internal error`() {
        val out = RuntimeFailureMessage.render(RuntimeException("cannot multiply a Str and an Int"))
        assertTrue(
            out.startsWith("error[I0004]: cannot multiply a Str and an Int"),
            "a host throwable is a compiler bug and must say so: $out",
        )
        assertTrue(out.contains("not a mistake in your program"), out)
        assertTrue(out.contains("--explain I0004"), "must point at the long-form doc: $out")
    }

    @Test
    fun `the java class prefix is stripped`() {
        val out = RuntimeFailureMessage.render(
            RuntimeException("java.lang.IllegalStateException: something internal"),
        )
        assertFalse(out.contains("java.lang"), "a Java class name reached the user: $out")
        assertTrue(out.contains("something internal"), out)
    }

    @Test
    fun `a mezze class prefix is stripped too`() {
        val out = RuntimeFailureMessage.render(
            RuntimeException(
                "com.mezze.interpreter.runtime.MezzeInternalError: cannot negate a record",
            ),
        )
        assertFalse(out.contains("com.mezze"), "an internal class name reached the user: $out")
        assertTrue(out.contains("cannot negate a record"), out)
    }

    @Test
    fun `a guest message that merely contains a colon is left alone`() {
        // THE FALSE-POSITIVE GUARD. The prefix pattern needs two or more dotted
        // lowercase segments then a capitalised type; a user's own text must not
        // match it, or the strip would eat the start of a real message.
        val untouched = listOf(
            "Division by zero",
            "key not found: 'name'",
            "src/app.mz: parse failed",
            "expected Int: found Str",
            "http://example.com: unreachable",
        )
        for (message in untouched) {
            val out = RuntimeFailureMessage.render(RuntimeException(message))
            assertTrue(
                out.contains(message),
                "the message was mangled by the class-name strip: `$message` -> `$out`",
            )
        }
    }

    @Test
    fun `an absent message still produces text`() {
        val out = RuntimeFailureMessage.render(RuntimeException())
        assertTrue(out.contains("I0004"), out)
        assertFalse(out.contains("null"), "a missing message must not render as `null`: $out")
    }

    @Test
    fun `the rendered form is exactly what a user reads`() {
        // Pinned whole, not by fragments: this string IS the user interface, and
        // a reflow that drops the report hint would otherwise pass unnoticed.
        assertEquals(
            "error[I0004]: cannot multiply a Str and an Int\n" +
                "  this is a bug in Mezze, not a mistake in your program — the typechecker should have\n" +
                "  refused it. Please report it with the program that triggered it.\n" +
                "  `mez --explain I0004` says more; `--verbose` prints the trace.",
            RuntimeFailureMessage.render(RuntimeException("cannot multiply a Str and an Int")),
        )
    }
}
