package com.mezze.language.parser

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.fail

/**
 * An effect row has NO standalone syntax.
 *
 * A row exists only as the trailing part of a function type —
 * `params -> return <row>` — and the type written before it is simply the return
 * type. There is no "type carrying a row": `Int <Tick>`, `MyInt <Tick>`,
 * `Map Int Int <Http>` and `AliasName <Effect>` are not forms, in any position.
 *
 * This file is the list of positions that therefore do not parse. It exists
 * because the shape is easy to misread the other way round: `{} -> Map Int Int
 * <Http>` looks like a row attached to `Map Int Int`, and reading it that way
 * makes every refusal here look like a parser gap to be "fixed" — which would
 * make `<E>` a postfix type modifier and turn this into a different language.
 *
 * The positive side (the row is the arrow's, and it is enforced) is in
 * `AliasEffectRowProbeTest`.
 */
class EffectRowSyntaxTest {
    private val parser = MezzeParser()

    private val effects = """
        effect Tick where { tick: {} -> Int <Tick> }
    """.trimIndent()

    private fun refused(label: String, source: String) {
        try {
            parser.parseParsedModule("$effects\n${source.trimIndent()}", "test.mz")
            fail("$label: a row has no standalone form, so this must not parse")
        } catch (e: Exception) {
            assertTrue(
                (e.message ?: "").contains("<"),
                "$label: the message should name the token that failed, got: ${e.message}",
            )
        }
    }

    /** The canonical form, so the refusals below are about position, not the row. */
    @Test
    fun `a row after the return type of an arrow parses`() {
        val module = parser.parseParsedModule(
            "$effects\nlet f: {} -> Int <Tick> = {} -> Tick.tick {}",
            "test.mz",
        )
        assertNotNull(module)
    }

    @Test
    fun `a row on a non-function annotation does not parse`() {
        refused("let x: Int <Tick>", "let x: Int <Tick> = 5")
    }

    /**
     * Same refusal through an alias of a non-function type. The alias changes
     * nothing: there was never a row-on-a-type form for it to inherit.
     */
    @Test
    fun `a row on an alias of a non-function type does not parse`() {
        refused(
            "let x: MyInt <Tick>",
            """
            type MyInt = Int
            let x: MyInt <Tick> = 5
            """,
        )
    }

    /** The row is the arrow's TRAILING part; it cannot lead. */
    @Test
    fun `a row before the arrow does not parse`() {
        refused("{} <Tick> -> Int", "let f: {} <Tick> -> Int = {} -> Tick.tick {}")
    }

    @Test
    fun `a row in a type alias declaration does not parse`() {
        refused("type A = Int <Tick>", "type A = Int <Tick>")
    }

    /** No arrow in the field, so nothing owns the row. */
    @Test
    fun `a row in a record field with no arrow does not parse`() {
        refused(
            "{ m: MyInt <Tick> }",
            """
            type MyInt = Int
            type Bad = { m: MyInt <Tick> }
            """,
        )
    }

    /** With an arrow in the field it is the field's function type, and fine. */
    @Test
    fun `a row in a record field WITH an arrow parses`() {
        val module = parser.parseParsedModule(
            "$effects\ntype Good = { m: {} -> Int <Tick> }",
            "test.mz",
        )
        assertNotNull(module)
    }

    /**
     * A bare row is not a type, so it cannot be named. This is why there is no
     * way to abstract over a row today, and the concrete ask behind L-1.
     */
    @Test
    fun `a bare row cannot be aliased`() {
        try {
            parser.parseParsedModule("$effects\ntype R = <Tick>", "test.mz")
            fail("a row is not a type and must not be nameable")
        } catch (e: Exception) {
            assertNotNull(e.message)
        }
    }

    /**
     * A function type has ONE trailing row. This used to PARSE: the inner arrow took
     * `<Tick>`, the outer silently took `<Tock>` — an associativity nobody reading it
     * can see, and the printer emitted it back in exactly this unreadable form.
     */
    @Test
    fun `two trailing rows on one return do not parse`() {
        try {
            parser.parseParsedModule(
                "$effects\neffect Tock where { tock: {} -> Int <Tock> }\n" +
                    "let f: { n: Int } -> {} -> Int <Tick> <Tock> = { n } -> {} -> Tick.tick {}",
                "test.mz",
            )
            fail("a second row has no arrow of its own and must be refused")
        } catch (e: Exception) {
            assertTrue(
                (e.message ?: "").contains("one effect row"),
                "expected the two-rows explanation, got: ${e.message}",
            )
        }
    }

    /** The honest spelling: parenthesise the returned function, and each row has its arrow. */
    @Test
    fun `a parenthesised returned function may carry its own row`() {
        assertNotNull(parser.parseParsedModule(
            "$effects\neffect Tock where { tock: {} -> Int <Tock> }\n" +
                "let f: { n: Int } -> ({} -> Int <Tick>) <Tock> = { n } -> {} -> Tick.tick {}",
            "test.mz",
        ))
    }
}
