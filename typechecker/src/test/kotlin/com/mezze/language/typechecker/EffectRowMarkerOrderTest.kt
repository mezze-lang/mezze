package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * An effect row reads in ONE order: names, then `...`, then `*`.
 *
 * `<..., *>` used to be a PARSE ERROR while being exactly what the compiler
 * PRINTS for a fully-handled body that still carries a row variable — so the
 * type `mez check` showed could not be written back (BUG 34). That is the bug
 * this closes: a type you are shown has to be a type you can write.
 *
 * The refusal was never a semantic rule. Leading `...` and leading `*` were each
 * special-cased in the parser as the SOLE content of a row, and the general loop
 * that knows how to combine markers requires a NAME first — so `<Tick, ...>`
 * worked and `<..., *>` had no path at all. The loop also `break`s after the
 * first marker, so `<Tick, ..., *>` was refused too. Both are fixed here.
 *
 * The ORDER is fixed on purpose, so a row has one spelling, and it is the order
 * the printer emits: `<*, ...>` is the same row backwards and gets a diagnostic
 * that says which way round to write it rather than a parse error pointing at
 * the wrong place. Before this, the wrong order surfaced as
 * `Expected '=' but got '<'` — the parser backtracking and failing somewhere
 * else entirely.
 *
 * This is the PARSER half of BUG 34. Inference still keeps a row variable that
 * carries no effects and that no caller can instantiate, which is WHY a
 * `<..., *>` is printed where `<*>` would do; that half is separate.
 */
class EffectRowMarkerOrderTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        tc = TypeChecker()
    }

    private val head = """
        effect Tick where { tick: {} -> Int <Tick> }
        impl Tick for Ticker where { tick = {} -> 7 }
    """.trimIndent()

    /** null ⇒ accepted outright; else the rejection message. */
    private fun err(src: String): String? =
        try {
            tc.typeCheck(parser.parseParsedModule("$head\n${src.trimIndent()}", "a.mz"), Paths.get("."), enablePrelude = true)
            null
        } catch (e: Throwable) {
            e.message ?: "?"
        }

    /**
     * Whether the row SPELLING parses, which is what most cases here are about.
     * Kept separate from whether the program type-checks: `<Tick, *>` parses
     * fine and is then an over-declaration if the body handles Tick, and
     * conflating the two had this file asserting a parse claim against a type
     * error.
     */
    private fun parses(src: String): Boolean {
        val message = err(src) ?: return true
        return !message.contains("ParseException") && !message.contains("Parse error")
    }

    /** THE case: the spelling the printer emits now parses. */
    @Test fun `an open tail followed by the handled marker parses`() {
        assertNull(err("let go: {} -> Int <..., *> = {} -> perform Ticker in Tick.tick {}"))
    }

    /** With a named effect in front of both. */
    @Test fun `a name then a tail then the marker parses`() {
        assertNull(err("let go: {} -> Int <Tick, ..., *> = {} -> perform Ticker in Tick.tick {}"))
    }

    /** The wrong order is refused BY NAME, pointing at the `*`. */
    @Test fun `the marker before the tail is refused with the order`() {
        val message = err("let go: {} -> Int <*, ...> = {} -> perform Ticker in Tick.tick {}")
        assertNotNull(message)
        assert(message!!.contains("comes last") && message.contains("<..., *>")) {
            "the error must say which order to write; got: $message"
        }
    }

    /** The same, with a name in front. */
    @Test fun `a name then the marker then the tail is refused`() {
        val message = err("let go: {} -> Int <Tick, *, ...> = {} -> perform Ticker in Tick.tick {}")
        assertNotNull(message)
        assert(message!!.contains("comes last")) {
            "the error must say the marker comes last; got: $message"
        }
    }

    // ── the spellings that already worked must keep working ──────────────────

    @Test fun `the marker alone still parses`() {
        assertNull(err("let go: {} -> Int <*> = {} -> perform Ticker in Tick.tick {}"))
    }

    @Test fun `the tail alone still parses`() {
        assertNull(err("let go: { f: {} -> Int <...> } -> Int <...> = { f } -> f {}"))
    }

    @Test fun `a name and a tail still parses`() {
        assertNull(err("let go: { f: {} -> Int <Tick, ...> } -> Int <Tick> = { f } -> f {}"))
    }

    /**
     * `<Tick, *>` PARSES. Whether it type-checks depends on the body — here the
     * `perform` handles Tick, so naming it in the row is an over-declaration and
     * the checker says so. That is a separate question from the spelling, which
     * is all this case claims.
     */
    @Test fun `a name and the marker still parses`() {
        assert(parses("let go: {} -> Int <Tick, *> = {} -> perform Ticker in Tick.tick {}")) {
            "`<Tick, *>` must still parse"
        }
    }

    @Test fun `an empty row still parses`() {
        assertNull(err("let go: {} -> Int <> = {} -> 1"))
    }
}
