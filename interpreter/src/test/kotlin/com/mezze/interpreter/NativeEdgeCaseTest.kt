package com.mezze.interpreter

import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A well-typed Mezze expression either produces a value or fails as a MEZZE
 * error. It must never surface a host throwable.
 *
 * Natives are written in Java, and a JDK method reached with an edge-case
 * argument throws a JVM exception — IndexOutOfBounds, ArithmeticException,
 * NumberFormatException. Truffle classifies those as HOST errors, so they
 * reach the user as an internal failure with a JVM stack trace instead of
 * something they can read or catch, and the debugger's exception-breakpoint
 * filter does not fire for them. Two real instances motivated this gate:
 * `10 % 0` threw ArithmeticException, and `str::replace` with `new = "$5"`
 * threw IndexOutOfBoundsException out of java.util.regex.
 *
 * Every case below is an edge the argument TYPES permit, so the typechecker
 * cannot rule it out — an out-of-range index is still an Int. Whether a case
 * answers (a clamp, a `None`) or raises a guest error is the operation's own
 * business; this test only pins that the failure mode is never the host's.
 */
class NativeEdgeCaseTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /**
     * Run it and report how it failed, if it did. A value is fine; a guest
     * error is fine. Anything else is the bug.
     */
    private fun assertNeverHostFailure(label: String, source: String) {
        try {
            MezzeTestHelper.run(source.trimIndent())
        } catch (e: PolyglotException) {
            assertTrue(
                e.isGuestException && !e.isInternalError,
                "$label: failed as a HOST error, which reaches the user as an " +
                    "internal failure with a JVM stack trace — ${e.message}",
            )
        } catch (e: StackOverflowError) {
            throw AssertionError("$label: overflowed the host stack — ${e.javaClass.name}")
        } catch (e: RuntimeException) {
            // A bare JVM exception escaping the engine is the same defect
            // wearing different clothes.
            throw AssertionError("$label: threw a host ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun str(body: String) = "use std::str\n$body"
    private fun seq(body: String) = "use std::collections::seq\n$body"

    @Test
    fun `integer edges never fail as host errors`() {
        assertNeverHostFailure("divide by zero", "10 / 0")
        assertNeverHostFailure("modulo by zero", "10 % 0")
        assertNeverHostFailure("negative modulo", "-10 % 3")
        assertNeverHostFailure(
            "pow with a negative exponent",
            "use std::int\nint::pow { base = 2, exp = -3 }",
        )
        assertNeverHostFailure("gcd of zeroes", "use std::int\nint::gcd { l = 0, r = 0 }")
        assertNeverHostFailure("lcm of zeroes", "use std::int\nint::lcm { l = 0, r = 0 }")
        assertNeverHostFailure("abs of the most negative Int", "use std::int\nint::abs { n = -9223372036854775807 }")
    }

    @Test
    fun `float edges never fail as host errors`() {
        assertNeverHostFailure("float divide by zero", "1.0 / 0.0")
        assertNeverHostFailure("zero over zero", "0.0 / 0.0")
        assertNeverHostFailure("sqrt of a negative", "use std::float\nlet n = -1.0\nn.sqrt {}")
        assertNeverHostFailure("round of an infinity", "use std::float\nlet n = 1.0 / 0.0\nn.round {}")
    }

    @Test
    fun `string index edges never fail as host errors`() {
        assertNeverHostFailure("substring past the end", str("""str::substring { s = "abc", start = 0, end = 99 }"""))
        assertNeverHostFailure("substring from a negative", str("""str::substring { s = "abc", start = -5, end = 2 }"""))
        assertNeverHostFailure("substring reversed", str("""str::substring { s = "abc", start = 2, end = 0 }"""))
        assertNeverHostFailure("char_at off the end", str("""str::char_at { s = "", i = 0 }"""))
        assertNeverHostFailure("char_at negative", str("""str::char_at { s = "abc", i = -1 }"""))
    }

    /**
     * The replacement is TEXT. Every one of these threw out of java.util.regex
     * before `replace` stopped going through `String.replaceFirst`.
     */
    @Test
    fun `replace with regex metacharacters never fails as a host error`() {
        val d = "${'$'}"
        for (new in listOf("${d}5", "c$d", "${d}0", "\\\\", "${d}{x}")) {
            assertNeverHostFailure(
                "replace with `$new`",
                str("""str::replace { s = "cost: X", old = "X", new = "$new" }"""),
            )
            assertNeverHostFailure(
                "replace_all with `$new`",
                str("""str::replace_all { s = "cost: X", old = "X", new = "$new" }"""),
            )
        }
        assertNeverHostFailure("replace an empty needle", str("""str::replace { s = "ab", old = "", new = "-" }"""))
        assertNeverHostFailure("replace a missing needle", str("""str::replace { s = "ab", old = "zz", new = "-" }"""))
    }

    @Test
    fun `other string edges never fail as host errors`() {
        assertNeverHostFailure("repeat a negative count", str("""str::repeat { s = "a", n = -1 }"""))
        assertNeverHostFailure("split on an empty delimiter", str("""str::split { s = "abc", delimiter = "" }"""))
        assertNeverHostFailure("split an empty string", str("""str::split { s = "", delimiter = "," }"""))
        assertNeverHostFailure("index_of a missing needle", str("""str::index_of { s = "abc", sub = "zz" }"""))
    }

    @Test
    fun `sequence index edges never fail as host errors`() {
        assertNeverHostFailure("get past the end", seq("""seq::get { xs = [1, 2, 3], i = 99 }"""))
        assertNeverHostFailure("get a negative index", seq("""seq::get { xs = [1, 2, 3], i = -1 }"""))
        assertNeverHostFailure("tail of a singleton", seq("""seq::tail { xs = [1] }"""))
        assertNeverHostFailure("first of a tail of a singleton", seq("""seq::first { xs = seq::tail { xs = [1] } }"""))
        assertNeverHostFailure("last of a tail of a singleton", seq("""seq::last { xs = seq::tail { xs = [1] } }"""))
    }
}
