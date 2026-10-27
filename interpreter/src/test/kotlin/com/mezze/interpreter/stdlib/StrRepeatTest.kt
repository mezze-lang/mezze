package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `str::repeat` at a size worth repeating to (BUG 30).
 *
 * It used to call itself once per copy and allocate an intermediate each time,
 * so it overflowed the stack at a couple of thousand — before the result is big
 * enough to be interesting. It surfaced while a test wanted a large request
 * body and had to build the string in Kotlin instead.
 *
 * Now a native, like every one of its neighbours in `std::str`.
 */
class StrRepeatTest {
    private fun run(body: String): Any = MezzeTestHelper.run(
        """
        use std::str
        use std::int
        ${body.trimIndent()}
        """.trimIndent()
    )

    /** The case that used to die. 100_000 x 10 bytes = 1 MB. */
    @Test fun `repeat builds a megabyte without recursing`() {
        assertEquals(
            1000000L,
            run("""str::size { s = str::repeat { s = "0123456789", n = 100000 } }"""),
        )
    }

    @Test fun `repeat concatenates in order`() {
        assertEquals("ababab", run("""str::repeat { s = "ab", n = 3 }"""))
    }

    @Test fun `one copy is the string itself`() {
        assertEquals("ab", run("""str::repeat { s = "ab", n = 1 }"""))
    }

    /** Zero and negative both give empty — the prior behaviour, kept. */
    @Test fun `zero and negative counts give the empty string`() {
        assertEquals(
            "0:0",
            run(
                """
                let a = str::size { s = str::repeat { s = "x", n = 0 } }
                let b = str::size { s = str::repeat { s = "x", n = 0 - 5 } }
                str::concat { l = str::concat { l = int::to_str { n = a }, r = ":" },
                              r = int::to_str { n = b } }
                """
            ).toString(),
        )
    }

    @Test fun `repeating an empty string is empty`() {
        assertEquals(0L, run("""str::size { s = str::repeat { s = "", n = 1000 } }"""))
    }
}
