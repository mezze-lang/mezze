package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A string native has two implementations: the dispatch table's (the interpreter)
 * and this DSL op's (compiled code). They must give the same answer, or a function
 * behaves differently depending on which path happens to run it.
 *
 * Two did not. `__native_string_repeat` called Kotlin's `repeat`, which throws on a
 * negative count, where `str::repeat` documents `n <= 0` as "" — the sampler hit it
 * as "a bug in Mezze" on `str::repeat { s = name, n = times }` with a drawn negative
 * `times`. `__native_string_trim_start`/`_end` used Kotlin's `trimStart`/`trimEnd`,
 * which also strip U+00A0, where the dispatch table's `stripLeading`/`stripTrailing`
 * keep it.
 */
class StringOpsAgreeWithDispatchTest {
    init { StringOps.MARKER }

    private fun call(name: String, vararg args: Any?): Any {
        val h = NativeHandlers.handlerOf(name) ?: error("$name is not registered")
        return h.invoke(PositionalArgs(arrayOf(*args)))
    }

    @Test fun `repeat with a negative or zero count is empty, as documented`() {
        for (n in listOf(-4L, -1L, 0L)) {
            assertEquals(PrimitiveNatives.stringRepeat("ab", n), call("__native_string_repeat", "ab", n), "n = $n")
            assertEquals("", call("__native_string_repeat", "ab", n), "n = $n")
        }
        assertEquals("ababab", call("__native_string_repeat", "ab", 3L))
    }

    @Test fun `trim keeps a no-break space, as the dispatch table does`() {
        // U+00A0 is not whitespace to Java's stripLeading/stripTrailing, so neither end moves.
        val s = "\u00A0 a \u00A0"
        assertEquals(s, call("__native_string_trim_start", s))
        assertEquals(s, call("__native_string_trim_end", s))
    }
}
