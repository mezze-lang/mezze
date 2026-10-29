package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An ability needed at a type variable that the function's own type never
 * mentions is REJECTED at check time.
 *
 * A dictionary reaches a function from its caller, by instantiating the
 * function's type. `let xs: ['a] = Arbitrary.arbitrary {}` inside a function
 * typed `{} -> Int <Sample>` puts `'a` where no caller can name it, so nothing
 * can ever supply `Arbitrary 'a`. It checked clean and then:
 *  - the scalar shape died at RUN time with "no dictionary was supplied …
 *    This is a compiler defect";
 *  - the `['a]` shape ran fine for as long as every draw was empty, and died
 *    only once a draw was non-empty and the element generator was needed.
 *
 * Found while making `mez sample` handle generic targets (ec5b581ad).
 */
class AmbiguousTypeVarDictTest {
    private val head = """
        use std::effects::sample::{ SeededSample }
        use std::abilities::arbitrary::{ Arbitrary }
    """.trimIndent()

    private fun run(body: String): Any? = MezzeTestHelper.run("$head\n\n${body.trimIndent()}")

    private fun assertRejectedAtCheck(label: String, body: String) {
        val message = runCatching { run(body) }.exceptionOrNull()?.message
            ?: "<returned a value instead of failing>"
        assertTrue(
            "nothing here determines that type" in message,
            "$label: expected the check-time ambiguity error, got: $message",
        )
        assertTrue(
            "no dictionary was supplied" !in message,
            "$label: reached the runtime instead of being rejected: $message",
        )
    }

    @Test
    fun `a bare type variable nothing determines is rejected`() = assertRejectedAtCheck(
        "scalar",
        """
            let draw: {} -> Int <Sample> = {} -> do
              let x: 'a = Arbitrary.arbitrary {}
              7
            perform SeededSample { seed = 7 } in draw {}
        """,
    )

    /** One level down: the generic `[_]` impl's OWN requirement on `'a`. */
    @Test
    fun `a type variable inside a container is rejected too`() = assertRejectedAtCheck(
        "list",
        """
            let draw: {} -> Int <Sample> = {} -> do
              let xs: ['a] = Arbitrary.arbitrary {}
              xs.size {}
            perform SeededSample { seed = 7 } in draw {}
        """,
    )

    /** Through a constrained function: the CALL instantiates its `with Arbitrary 'a` at the hidden variable. */
    @Test
    fun `a constrained callee instantiated at a hidden variable is rejected`() = assertRejectedAtCheck(
        "via function",
        """
            let draw_any: {} with Arbitrary 'a -> 'a <Sample> = {} -> Arbitrary.arbitrary {}
            let draw: {} -> Int <Sample> = {} -> do
              let x: 'a = draw_any {}
              7
            perform SeededSample { seed = 7 } in draw {}
        """,
    )

    /**
     * A DERIVED impl: `Box` has no written `Arbitrary`, so the derivation supplies
     * one that generates every field — including the hidden `'a`.
     */
    @Test
    fun `a derived impl that asks a hidden field is rejected`() = assertRejectedAtCheck(
        "derived",
        """
            type Box 'a = { v: 'a }
            let draw: {} -> Int <Sample> = {} -> do
              let b: Box 'a = Arbitrary.arbitrary {}
              7
            perform SeededSample { seed = 7 } in draw {}
        """,
    )

    /**
     * A helper generalized INSIDE a body owns its variable: each use instantiates
     * it, so the enclosing function's type need not mention it.
     */
    @Test
    fun `a local generalized helper keeps its own variable`() = assertEquals(
        7L,
        run("""
            let draw: {} -> Int <Sample> = {} -> do
              let mk: {} with Arbitrary 'a -> 'a <Sample> = {} -> Arbitrary.arbitrary {}
              let n: Int = mk {}
              7
            perform SeededSample { seed = 7 } in draw {}
        """),
    )

    /**
     * A hidden variable whose impl asks NOTHING of it is fine: `[_]`'s `size`
     * never looks at an element. Rejecting this would be the over-reach.
     */
    @Test
    fun `an impl that needs nothing from the hidden variable is untouched`() = assertEquals(
        0L,
        run("""
            let count: {} -> Int = {} -> do
              let xs: ['a] = []
              xs.size {}
            count {}
        """),
    )

    /**
     * A top-level VALUE is evaluated once and called by nobody, so no variable in
     * it can ever be given a dictionary — not even one its own type mentions.
     */
    @Test
    fun `a top-level value at an undetermined type is rejected`() {
        val message = runCatching {
            MezzeTestHelper.run(
                """
                use std::abilities::default::{ Default }
                let z: 'a = Default.default {}
                let main = {} -> 7
                main {}
                """.trimIndent(),
            )
        }.exceptionOrNull()?.message ?: "<returned a value instead of failing>"
        assertTrue("nothing here determines that type" in message, "expected the ambiguity error, got: $message")
    }

    @Test
    fun `a top-level value at a concrete type is untouched`() = assertEquals(
        0L,
        MezzeTestHelper.run(
            """
            use std::abilities::default::{ Default }
            let z: Int = Default.default {}
            z
            """.trimIndent(),
        ),
    )

    /** The rule must not touch a variable the CALLER decides. */
    @Test
    fun `a variable in the function's own type stays the caller's to choose`() = assertEquals(
        7L,
        run("""
            let draw_any: {} with Arbitrary 'a -> 'a <Sample> = {} -> Arbitrary.arbitrary {}
            let draw: {} -> Int <Sample> = {} -> do
              let x: Int = draw_any {}
              7
            perform SeededSample { seed = 7 } in draw {}
        """),
    )

    @Test
    fun `a concrete type is untouched`() = assertEquals(
        7L,
        run("""
            let draw: {} -> Int <Sample> = {} -> do
              let xs: [Int] = Arbitrary.arbitrary {}
              7
            perform SeededSample { seed = 7 } in draw {}
        """),
    )
}
