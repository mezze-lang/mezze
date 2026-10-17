package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `.to_json {}` on a seq whose element type came from an ability-constrained
 * lambda.
 *
 * Found while benchmarking the JSON codec. `seq::map { … }.to_json {}` compiles
 * and then fails at RUN TIME with
 *
 *     Expected record for field 'encode', got: MezzeSeq
 *
 * which is the `Encode` dictionary slot receiving the receiver instead of a
 * dictionary. The narrowing below is the useful part, because almost every
 * neighbouring shape is fine:
 *
 *  - `seq::range { … }.to_json {}`                     works — a call receiver is fine
 *  - `[1, 2, 3].to_json {}`                            works
 *  - `seq::filter { … p = { x } -> x > 1 }.to_json {}` works — a HOF is fine, filter
 *                                                      preserves the element type
 *  - `seq::map { … f = { x } -> x }.to_json {}`        works — identity lambda
 *  - a `let` for the map result, then `.to_json {}`    works
 *  - `seq::map { … f = { x } -> x * 2 }.to_json {}`    FAILS
 *
 * So it is neither "call receiver" nor "higher-order function". The trigger is an
 * element type produced by a lambda whose body uses an ABILITY operation (`*`),
 * leaving the element a type variable with a pending constraint at the point the
 * `Encode` dictionary is chosen. A `let` resolves it first, which is why binding
 * the same expression works. It also survives an intervening call —
 * `seq::reverse { xs = seq::map { … } }.to_json {}` fails the same way — so the
 * unresolved element type travels with the value.
 *
 * This is the "dictionary not arriving" shape, and the runtime crash on an erased
 * payload is the same symptom BUG 13 had in a different position. Filed as BUG 25
 * in planned_features/known-bugs.md.
 */
class JsonSeqElementDictTest {

    private val head = """
        use std::codec::json::{ ToJson, to_bytes }
        use std::collections::seq
        use std::collections::bytes
    """.trimIndent()

    private fun jsonOf(expr: String): Any = MezzeTestHelper.run(
        """
        $head
        let go: {} -> Str = {} -> match bytes::to_str { b = to_bytes { json = $expr } } is
          Ok { val } -> val
          Err { error = _ } -> "undecodable"
        go {}
        """.trimIndent(),
    )

    // ── The shapes that work, so a fix is not free to break them ──

    @Test
    fun `a seq literal encodes`() = assertEquals("[1,2,3]", jsonOf("[1, 2, 3].to_json {}"))

    @Test
    fun `a range call encodes`() =
        assertEquals("[1,2,3]", jsonOf("seq::range { start = 1, end = 4 }.to_json {}"))

    @Test
    fun `a filter call encodes — the element type is preserved, not inferred`() =
        assertEquals("[2,3]", jsonOf("seq::filter { xs = [1, 2, 3], p = { x } -> x > 1 }.to_json {}"))

    @Test
    fun `a map with an identity lambda encodes`() =
        assertEquals("[1,2,3]", jsonOf("seq::map { xs = [1, 2, 3], f = { x } -> x }.to_json {}"))

    @Test
    fun `a record literal encodes`() = assertEquals("""{"a":1}""", jsonOf("{ a = 1 }.to_json {}"))

    @Test
    fun `binding the mapped seq first encodes`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let go: {} -> Str = {} -> do
              let rows = seq::map { xs = seq::range { start = 1, end = 4 }, f = { x } -> x * 2 }
              match bytes::to_str { b = to_bytes { json = rows.to_json {} } } is
                Ok { val } -> val
                Err { error = _ } -> "undecodable"
            go {}
            """.trimIndent(),
        )
        assertEquals("[2,4,6]", r)
    }

    // ── The bug ──

    @Test
    @Disabled("BUG 25: the Encode dictionary slot gets the receiver when the element type came from an ability-constrained lambda")
    fun `a mapped seq encodes without being bound first`() =
        assertEquals("[2,4,6]",
            jsonOf("seq::map { xs = seq::range { start = 1, end = 4 }, f = { x } -> x * 2 }.to_json {}"))

    @Test
    @Disabled("BUG 25: same cause, reached through an intervening call")
    fun `a mapped seq encodes after passing through another call`() =
        assertEquals("[2,4,6]",
            jsonOf("seq::reverse { xs = seq::map { xs = [3, 2, 1], f = { x } -> x * 2 } }.to_json {}"))
}
