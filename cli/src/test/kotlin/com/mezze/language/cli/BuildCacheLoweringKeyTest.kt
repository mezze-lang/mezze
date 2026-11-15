package com.mezze.language.cli

import com.mezze.language.cli.commands.BuildCache
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `mez build`'s cache must be keyed on the LOWERING CONFIGURATION, not only on
 * the sources.
 *
 * THE BUG, found the hard way. A build with `MEZZE_MONO_STRIP=1` wrote its IR
 * into `.mez`, and every later build — without the flag — got a cache hit and ran
 * the stripped IR. `bench/cases/defunc` kernel 6 answered `sum=<function:>`
 * instead of 400000040000000 long after the flag was gone from the command line,
 * and it cost a detour chasing a regression in `seq::fold` that did not exist.
 * The cache's doc listed what it invalidates on — sources, missing blobs, stdlib
 * drift — and the compiler's own configuration was not on the list.
 *
 * Verified end to end, both directions: with the key check in place, a build with
 * the flag followed by one without RECOMPILES and answers correctly; with the
 * check disabled, the second build reuses the poisoned IR and still throws
 * `FrameSlotTypeException`.
 *
 * What these pin is the key's contract, which is where a future regression would
 * creep in: a flag added to the lowering path and forgotten here reintroduces the
 * bug silently.
 */
class BuildCacheLoweringKeyTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val m = pairs.toMap()
        return { name -> m[name] }
    }

    @Test
    fun `the same configuration gives the same key`() {
        assertEquals(
            BuildCache.loweringConfigKey(env()),
            BuildCache.loweringConfigKey(env()),
            "the key must be stable, or every build misses its own cache",
        )
    }

    @Test
    fun `the default configuration has a key of its own`() {
        // Not "no key": a cache written before this check existed has no recorded
        // key at all, and must be told apart from one written by a default build.
        val key = BuildCache.loweringConfigKey(env())
        assertTrue(key.isNotEmpty() && key.length == 64, "expected a sha-256 hex digest, got '$key'")
    }

    @Test
    fun `every lowering flag changes the key`() {
        // The list is the contract. Loop it rather than naming one flag, so adding
        // a flag to LOWERING_FLAGS extends this test automatically and adding one
        // to the lowering path WITHOUT listing it is the only way to regress.
        val base = BuildCache.loweringConfigKey(env())
        for (flag in BuildCache.loweringFlagNames()) {
            assertNotEquals(
                base,
                BuildCache.loweringConfigKey(env(flag to "1")),
                "setting $flag did not change the cache key, so a build with it set " +
                    "would be reused by a build without it",
            )
        }
    }

    @Test
    fun `a flag's VALUE changes the key, not just its presence`() {
        // `MEZZE_MONO_CLOSURES=0` is the off switch and `=1` is on; keying on
        // presence alone would treat them as the same build.
        assertNotEquals(
            BuildCache.loweringConfigKey(env("MEZZE_MONO_CLOSURES" to "0")),
            BuildCache.loweringConfigKey(env("MEZZE_MONO_CLOSURES" to "1")),
            "two different values of one flag produced the same key",
        )
    }

    @Test
    fun `a trace-only flag does NOT change the key`() {
        // THE CONTROL, and it is what stops this becoming "throw the cache away
        // whenever any MEZZE_ var is set". A diagnostic run prints more and
        // compiles the same IR, so it must keep its cache.
        val base = BuildCache.loweringConfigKey(env())
        for (flag in listOf("MEZZE_DEBUG", "MEZZE_TRACE_FUSION", "MEZZE_TRACE_WRAPPER",
                            "MEZZE_TIMING", "MEZZE_TRACE_SELFTAIL")) {
            assertEquals(
                base,
                BuildCache.loweringConfigKey(env(flag to "1")),
                "$flag changed the cache key, but it only affects diagnostics — a " +
                    "trace run would throw away a valid cache",
            )
        }
    }
}
