package com.mezze.interpreter.standalone

import org.graalvm.polyglot.PolyglotException

/**
 * What a user reads when a run fails.
 *
 * Mezze has two kinds of runtime failure and they must not look alike:
 *
 *  - a GUEST exception — the program failed: divide by zero, an Int overflow,
 *    an unhandled raise. [PolyglotException.getMessage] is already written for
 *    the user, and it is passed through untouched.
 *  - a HOST exception — a Java throwable escaped the interpreter. In a
 *    well-typed program that cannot happen, so it means the compiler is broken.
 *    `ArithmeticErrorTest` states the same rule from the other side: "a bare
 *    ArithmeticException is a host error and reaches the user as an internal
 *    failure", which is why `%` by zero was moved onto the guest channel.
 *
 * The second kind used to be printed raw, and raw is a Java `toString()`:
 *
 *     java.lang.RuntimeException: Cannot multiply String and Long
 *
 * A class name from a language the user does not write, two JVM type names for
 * values they would call `Str` and `Int`, and nothing saying whose fault it is.
 * (That line is real — it is what `planned_features/known-bugs.md` BUG 35
 * produces.) Host failures now render as [CODE] with the report hint, and the
 * Java class is dropped from the default output; the trace behind `--verbose`
 * still carries it, which is where a reporter looks anyway.
 *
 * The CLI prints [ExitDetail.message] verbatim ([WireRunner]), so whatever this
 * returns IS the user-visible text.
 */
internal object RuntimeFailureMessage {

    /** Runtime invariant violated — see `ErrorCode.I0004_RUNTIME_INVARIANT_VIOLATED`. */
    const val CODE = "I0004"

    /**
     * A fully-qualified Java class name followed by `": "`, as `Throwable.toString()`
     * writes it. Two or more dotted lowercase package segments then a capitalised
     * type, so a guest message that merely contains a colon is left alone.
     */
    private val HOST_CLASS_PREFIX =
        Regex("""^(?:[a-z][A-Za-z0-9_$]*\.){2,}[A-Z][A-Za-z0-9_$]*: """)

    /** The user-visible text for a failed run. */
    fun render(e: PolyglotException): String {
        val raw = e.message ?: "the run failed with no message"
        return if (isInternal(e)) internal(strip(raw)) else raw
    }

    /**
     * The same for a failure caught outside the polyglot boundary. Everything
     * reaching this is a host throwable by construction, so it is internal —
     * this replaces `"${e.javaClass.simpleName}: ${e.message}"`, which put a
     * Java class name in front of the user for the same reason.
     */
    fun render(e: Throwable): String =
        internal(strip(e.message ?: "the run failed with no message"))

    /**
     * A host throwable escaped, or Truffle itself called it internal.
     *
     * Asking [PolyglotException.isHostException] is the house rule, pinned from
     * the other direction by `ArithmeticErrorTest`: a Mezze error must satisfy
     * `isGuestException` and `!isInternalError`, so anything failing that test
     * is by definition not a Mezze error. The accessors can throw on a cancelled
     * or torn-down context, and a failure to classify must not replace the error
     * with its own — so treat an unanswerable question as "not internal" and let
     * the original message through.
     */
    private fun isInternal(e: PolyglotException): Boolean = try {
        e.isHostException || e.isInternalError
    } catch (_: Exception) {
        false
    }

    private fun strip(message: String): String = message.replaceFirst(HOST_CLASS_PREFIX, "")

    private fun internal(detail: String): String = buildString {
        append("error[").append(CODE).append("]: ").append(detail).append('\n')
        append("  this is a bug in Mezze, not a mistake in your program — the ")
        append("typechecker should have\n")
        append("  refused it. Please report it with the program that triggered it.\n")
        append("  `mez --explain ").append(CODE).append("` says more; `--verbose` prints the trace.")
    }
}
