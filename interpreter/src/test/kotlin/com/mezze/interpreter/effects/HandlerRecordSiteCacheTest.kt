package com.mezze.interpreter.effects

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `perform Handler {…} in body` resolves the handler record ONCE per site now,
 * and the line between what may be cached and what may not is the whole
 * correctness story.
 *
 * WHAT WAS BEING PAID. The resolution ran on every install and did two
 * `ConcurrentHashMap` probes whose keys are 64-character hex strings — so a hit
 * compares all 64 characters — plus two context lookups. That was 14 ns of a
 * 45 ns install; caching the record per site took `install/iter` to 28 223 ps,
 * **1.61x**, and `abort/iter` to 85 238, 1.25x, converged at two n with the
 * controls flat.
 *
 * WHAT MAY BE CACHED. `MezzeContext.handlersByHash` has exactly two writers:
 * module load puts a `PerformerInfo` under the hash, and `lookupHandler`
 * upgrades that entry in place to the inflated record the first time it is
 * asked. Nothing removes an entry and nothing replaces a record — and the hash
 * is the content hash of the performer's structure, so a re-registration under
 * the same hash cannot mean anything different. That branch also ignores
 * `params`, which is why a cache of it may.
 *
 * WHAT MAY NOT, AND THIS IS WHAT THESE TESTS ARE FOR. The other branch CALLS a
 * guest factory with `params`, and a parameterized performer — `impl Sample for
 * Recording { seed: Int, key: Int, size: Nat }` in std::property is a real one —
 * depends on getting a fresh handler per install. Caching that would hand every
 * install the first install's parameters, which is a wrong ANSWER and not a
 * slow one. `resolveFromContextOnly` returns null whenever that branch could
 * answer, and the guard then declines to cache.
 *
 * NOT COVERED HERE, STATED SO IT IS NOT MISTAKEN FOR COVERED: the cache is also
 * guarded on context identity, because Mezze is `ContextPolicy.SHARED` and this
 * AST is shared between contexts of one engine. That guard is justified
 * structurally, not by a test — two contexts running the same source resolve
 * the same content hash to structurally identical records, so a test cannot
 * tell a leak from correct behaviour by looking at answers.
 */
class HandlerRecordSiteCacheTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val parameterized = """
        effect Pick where { pick: {} -> Int <Pick> }
        impl Pick for Fixed { n: Int } where { pick = {} -> n }
    """.trimIndent()

    /**
     * THE LOAD-BEARING ONE. The same parameterized impl at the same SITE, with
     * a different parameter every install: 1 + 2 + … + 100 = 5 050. A cache
     * that reached this branch would answer with the first install's parameter
     * every time and total 10 000.
     */
    @Test
    fun `a parameterized performer gets its own parameters on every install`() {
        val answer = MezzeTestHelper.run("""
            $parameterized
            let go = { i, acc } ->
              if i <= 0 then acc
              else do
                let x = perform Fixed { n = i } in Pick.pick {}
                go { i = i - 1, acc = acc + x }
            go { i = 100, acc = 0 }
        """.trimIndent())
        assertEquals(5050L, answer)
    }

    /**
     * The same, long enough to be COMPILED, because a per-site cache is a node
     * field and a constant to partial evaluation — a shape can be right
     * interpreted and wrong once the field has been folded into the code.
     *
     * 1 + 2 + … + 2 000 = 2 001 000.
     */
    @Test
    fun `a parameterized performer stays per-install once the loop is compiled`() {
        val answer = MezzeTestHelper.run("""
            $parameterized
            let go = { i, acc } ->
              if i <= 0 then acc
              else do
                let x = perform Fixed { n = i } in Pick.pick {}
                go { i = i - 1, acc = acc + x }
            go { i = 2000, acc = 0 }
        """.trimIndent())
        assertEquals(2001000L, answer)
    }

    /**
     * TWO IMPLS OF ONE EFFECT, each at its own site in one loop. The cache is
     * keyed on the hash the SITE carries, so these must not collide: if they
     * shared an entry, one of the two answers would be the other's.
     *
     * 100 iterations of (10 + 20) = 3 000.
     */
    @Test
    fun `two impls of one effect at two sites do not share a cache entry`() {
        val answer = MezzeTestHelper.run("""
            effect Pick where { pick: {} -> Int <Pick> }
            impl Pick for Ten where { pick = {} -> 10 }
            impl Pick for Twenty where { pick = {} -> 20 }
            let go = { i, acc } ->
              if i <= 0 then acc
              else do
                let a = perform Ten in Pick.pick {}
                let b = perform Twenty in Pick.pick {}
                go { i = i - 1, acc = acc + a + b }
            go { i = 100, acc = 0 }
        """.trimIndent())
        assertEquals(3000L, answer)
    }

    /**
     * A PARAMETERIZED AND A PLAIN IMPL OF THE SAME EFFECT in one loop. The
     * plain one is cacheable and the parameterized one is not, so this is the
     * case where the two branches run at two sites of one program and the
     * decision has to be made per site rather than per effect.
     *
     * 50 iterations of (i from Fixed + 10 from Ten), i = 50 down to 1:
     * 1 275 + 500 = 1 775.
     */
    @Test
    fun `a cacheable and a non-cacheable site coexist in one loop`() {
        val answer = MezzeTestHelper.run("""
            effect Pick where { pick: {} -> Int <Pick> }
            impl Pick for Fixed { n: Int } where { pick = {} -> n }
            impl Pick for Ten where { pick = {} -> 10 }
            let go = { i, acc } ->
              if i <= 0 then acc
              else do
                let a = perform Fixed { n = i } in Pick.pick {}
                let b = perform Ten in Pick.pick {}
                go { i = i - 1, acc = acc + a + b }
            go { i = 50, acc = 0 }
        """.trimIndent())
        assertEquals(1775L, answer)
    }

    /**
     * THE CACHED PATH ITSELF, run far past the point where it has specialized:
     * a plain impl installed 5 000 times. The record it hands back has to stay
     * the right one — a stale or null entry here is an answer, not a slowdown.
     */
    @Test
    fun `a plain handler installed thousands of times keeps answering`() {
        val answer = MezzeTestHelper.run("""
            effect Pick where { pick: {} -> Int <Pick> }
            impl Pick for Ten where { pick = {} -> 10 }
            let one = {} -> perform Ten in Pick.pick {}
            let go = { i, acc } ->
              if i <= 0 then acc
              else go { i = i - 1, acc = acc + one {} }
            go { i = 5000, acc = 0 }
        """.trimIndent())
        assertEquals(50000L, answer)
    }
}
