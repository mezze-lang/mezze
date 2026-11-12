package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `datetime::format` and `datetime::parse` compile each pattern ONCE.
 *
 * `DateTimeFormatter.ofPattern` parses the pattern string and builds a
 * formatter, and both natives called it on every invocation. MEASURED on
 * `yyyy-MM-dd HH:mm:ss`: 211 ns a call against 75 with one formatter reused, so
 * the pattern parse was two thirds of the cost of formatting a date.
 *
 * WHAT THESE TESTS ARE FOR. A cache is invisible when it is right and silent
 * when it is wrong, so the tests target the two ways this one could be wrong.
 *
 *  - THE ZONE IS NOT IN THE KEY. The cache holds the formatter for a pattern
 *    and the caller applies `withZone` per call, because keying on pattern AND
 *    zone needs a composite key built every call and measured SLOWER (110 ns
 *    against 81) than the `withZone` copy it avoids. If the zoned formatter
 *    were ever cached under the bare pattern, the second zone to use that
 *    pattern would silently get the first one's offset — so the same pattern is
 *    formatted in two zones below, and the answers must differ.
 *  - PATTERNS MUST NOT LEAK INTO EACH OTHER, so several are interleaved and
 *    each must keep its own shape, including past the cache's bound, where it
 *    clears and recompiles.
 */
class DateTimePatternCacheTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val prelude = """
        use std::datetime
        use std::result
        use std::str
        use std::int
    """

    /** A fixed point in time: 2021-01-01T00:00:00Z, as epoch millis. */
    private val fixed = "datetime::instant_from_millis { n = 1609459200000 }"

    /** THE LOAD-BEARING ONE: one pattern, two zones, two answers. */
    @Test
    fun `the same pattern in two zones gives two answers`() {
        val src = """
            $prelude
            let main = {} -> do
              let i = $fixed
              let utc = datetime::utc {}
              let ny = match datetime::zone_of { id = "America/New_York" } is
                Ok { val } -> val
                Err { error } -> utc
              let a = datetime::format { i = i, p = "yyyy-MM-dd HH:mm", z = utc }
              let b = datetime::format { i = i, p = "yyyy-MM-dd HH:mm", z = ny }
              "$(a)|$(b)"
            main {}
        """
        // Midnight UTC is 19:00 the previous day in New York.
        assertEquals("2021-01-01 00:00|2020-12-31 19:00", eval(src))
    }

    /** And the other order, so neither zone can be the one that happens to work. */
    @Test
    fun `the same pattern in two zones gives two answers, reversed`() {
        val src = """
            $prelude
            let main = {} -> do
              let i = $fixed
              let utc = datetime::utc {}
              let ny = match datetime::zone_of { id = "America/New_York" } is
                Ok { val } -> val
                Err { error } -> utc
              let b = datetime::format { i = i, p = "yyyy-MM-dd HH:mm", z = ny }
              let a = datetime::format { i = i, p = "yyyy-MM-dd HH:mm", z = utc }
              "$(a)|$(b)"
            main {}
        """
        assertEquals("2021-01-01 00:00|2020-12-31 19:00", eval(src))
    }

    /** Several patterns interleaved, each keeping its own shape. */
    @Test
    fun `different patterns do not leak into each other`() {
        val src = """
            $prelude
            let main = {} -> do
              let i = $fixed
              let z = datetime::utc {}
              let a = datetime::format { i = i, p = "yyyy", z = z }
              let b = datetime::format { i = i, p = "yyyy-MM-dd", z = z }
              let c = datetime::format { i = i, p = "HH:mm:ss", z = z }
              let d = datetime::format { i = i, p = "yyyy", z = z }
              let e = datetime::format { i = i, p = "yyyy-MM-dd", z = z }
              "$(a)|$(b)|$(c)|$(d)|$(e)"
            main {}
        """
        assertEquals(
            "2021|2021-01-01|00:00:00|2021|2021-01-01",
            eval(src),
        )
    }

    /**
     * PAST THE BOUND. The cache clears rather than growing without limit, so a
     * run with more distinct patterns than it holds must still answer
     * correctly — the first pattern is re-formatted at the end, after the clear
     * has certainly happened.
     */
    @Test
    fun `more patterns than the cache holds still format correctly`() {
        val src = """
            $prelude
            ## A distinct pattern per step: `yyyy` padded with that many quoted
            ## marker characters, which java.time treats as literal text.
            let pad = { n, acc } -> if n <= 0 then acc else pad { n = n - 1, acc = "$(acc)'x'" }
            let loop = { n, i, z, acc } -> if n <= 0 then acc
              else do
                let p = pad { n = n, acc = "yyyy" }
                let s = datetime::format { i = i, p = p, z = z }
                loop { n = n - 1, i = i, z = z, acc = acc + str::size { s = s } }
            let main = {} -> do
              let i = $fixed
              let z = datetime::utc {}
              let p3 = pad { n = 3, acc = "yyyy" }
              let s3 = datetime::format { i = i, p = p3, z = z }
              let total = loop { n = 100, i = i, z = z, acc = 0 }
              let again = datetime::format { i = i, p = "yyyy", z = z }
              "$(p3)|$(s3)|$(int::to_str { n = total })|$(again)"
            main {}
        """
        // The lengths are what java.time's QUOTING gives, which is not what
        // repeating `'x'` looks like it gives: `yyyy'x''x''x'` formats to
        // `2021x'x'x`, because a quoted section runs to the next lone quote and
        // `''` inside one is an escaped quote. So n appends yield a literal of
        // 2n-1 characters and an output of 3 + 2n, and the total for n = 100
        // down to 1 is 300 + 2 * 5050 = 10 400. The first reading was predicted
        // as 5 450 from the wrong model and the run corrected it.
        assertEquals("yyyy'x''x''x'|2021x'x'x|10400|2021", eval(src))
    }

    /** `parse` shares the cache, so it gets the same two checks at once. */
    @Test
    fun `parse honours the zone with a shared pattern`() {
        val src = """
            $prelude
            let main = {} -> do
              let utc = datetime::utc {}
              let ny = match datetime::zone_of { id = "America/New_York" } is
                Ok { val } -> val
                Err { error } -> utc
              let a = match datetime::parse { s = "2021-01-01 00:00", p = "yyyy-MM-dd HH:mm", z = utc } is
                Ok { val } -> datetime::instant_to_millis { i = val }
                Err { error } -> 0 - 1
              let b = match datetime::parse { s = "2021-01-01 00:00", p = "yyyy-MM-dd HH:mm", z = ny } is
                Ok { val } -> datetime::instant_to_millis { i = val }
                Err { error } -> 0 - 1
              b - a
            main {}
        """
        // New York is UTC-5 in January, so the same wall clock is 5 hours later.
        assertEquals(5L * 3600L * 1000L, eval(src))
    }

    /** A round trip through both, to pin that the cached formatter is the one
     *  the pattern describes and not a neighbour. */
    @Test
    fun `format then parse round trips`() {
        val src = """
            $prelude
            let main = {} -> do
              let i = $fixed
              let z = datetime::utc {}
              let s = datetime::format { i = i, p = "yyyy-MM-dd HH:mm:ss", z = z }
              match datetime::parse { s = s, p = "yyyy-MM-dd HH:mm:ss", z = z } is
                Ok { val } -> datetime::instant_to_millis { i = val }
                Err { error } -> 0 - 1
            main {}
        """
        assertEquals(1609459200000L, eval(src))
    }
}
