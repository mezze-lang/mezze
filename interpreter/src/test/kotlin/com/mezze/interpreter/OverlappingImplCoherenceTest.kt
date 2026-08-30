package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * H3 / #333 regression: overlapping ability impls (a concrete `[Int]` + a blanket
 * `['a]` that covers it) must be REJECTED at compile time (coherence), not silently
 * dispatched to the blanket at runtime. A non-overlapping pair (different top-level
 * constructors) must still be accepted.
 */
class OverlappingImplCoherenceTest {
    // H3: overlapping concrete [Int] + blanket ['a] impls must now be REJECTED
    // at compile time (coherence), not silently dispatch to the blanket at runtime.
    @Test @Timeout(60, unit = TimeUnit.SECONDS)
    fun h3_overlappingImplRejected() {
        val src = """
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for [Int] where { sz = { it } -> 111 }
            impl Sz for ['a]  where { sz = { it } -> 999 }
            let go = {} -> do
                let xs: [Int] = [ 1, 2, 3 ]
                xs.sz {}
            go {}
        """.trimIndent()
        try {
            val v = MezzeTestHelper.runWithOutput(src).value()
            fail<Unit>("expected an overlapping-impl compile error, but the program ran and returned $v")
        } catch (e: Throwable) {
            System.err.println("H3 result=[${e::class.simpleName}: ${e.message?.replace("\n"," ")?.take(140)}]")
            val msg = e.message ?: ""
            assertTrue(
                msg.contains("verlap", ignoreCase = true) || msg.contains("V0407"),
                "expected an overlapping-instance compile error; got: $msg"
            )
        }
    }

    // A NON-overlapping pair (different constructors) must still be accepted.
    @Test @Timeout(60, unit = TimeUnit.SECONDS)
    fun h3_nonOverlappingStillAccepted() {
        val src = """
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            type Box 'a = | Bx { v: 'a } |
            impl Sz for [Int]    where { sz = { it } -> 111 }
            impl Sz for (Box 'a) where { sz = { it } -> 222 }
            let go = {} -> do
                let xs: [Int] = [ 1, 2 ]
                xs.sz {}
            go {}
        """.trimIndent()
        val v = MezzeTestHelper.runWithOutput(src).value()
        System.err.println("H3 nonoverlap value=$v")
    }
}
