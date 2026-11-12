package com.mezze.interpreter

import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * `std::effects::condition` — a handler that RESUMES, so the signalling
 * computation carries on.
 *
 * This is the one thing `abort`/`resume` give that neither exceptions nor a
 * plain `Result` can: after a throw the intervening frames are gone, and a
 * `Result` needs an explicit branch at every call site. Here one body signals
 * twice and still produces a complete value.
 *
 * Every test IMPORTS the module, deliberately. A stdlib module is compiled
 * lazily, so `compileStdlib` reported the same "1794 functions, 13 performers"
 * after `condition.mz` was added as before — a green build is NOT evidence a
 * new module is sound, and only an import exercises it.
 *
 * The three performers also demonstrate LATE-BOUND DISCHARGE: one signalling
 * body, and the CALLER decides whether a condition means "carry on", "Err" or
 * "None". Exceptions make the throw site choose; a `Result` return type bakes
 * the choice in permanently.
 */
class ConditionEffectTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    /** Signals at TWO different fallback types, so `'a` is per-site. */
    private val parse = """
        use std::effects::condition::{ Condition, signal, UseFallback, FirstErr, FirstNone }

        pub let parse: { name_ok: Bool, age_ok: Bool } -> { name: Str, age: Int } <Condition> =
          { name_ok, age_ok } -> do
            let name = if name_ok then "alice" else signal { ctx = "bad name", fallback = "?" }
            let age = if age_ok then 30 else signal { ctx = "bad age", fallback = 0 }
            { name = name, age = age }
    """.trimIndent()

    /**
     * Both halves at column 0. Interpolating the already-trimIndent'ed fragment
     * into another indented literal left `parse`'s body at one column and the
     * caller's statements at another, which the do-block alignment rule rejects
     * ("must start at the same column"). Concatenating after a separate
     * trimIndent keeps them aligned.
     */
    private fun withParse(tail: String) = parse + "\n" + tail.trimIndent()

    // ───────────────────────────────── resuming: the body carries on

    @Test
    fun `UseFallback carries on past every signal`() {
        val result = eval(withParse("""
            let run = {} -> perform UseFallback in parse { name_ok = False, age_ok = False }
            run {}
            """))
        val rec = result as MezzeRecord
        // THE POINT: the SECOND fallback is present, so control came back after
        // the first signal. With an exception the body would have unwound at
        // `name` and `age` would never have been reached.
        assertEquals("?", rec.getField("name"), "first signal recovered")
        assertEquals(0L, rec.getField("age"), "second signal ALSO recovered — the body continued")
    }

    @Test
    fun `UseFallback leaves a clean run untouched`() {
        val result = eval(withParse("""
            let run = {} -> perform UseFallback in parse { name_ok = True, age_ok = True }
            run {}
            """))
        val rec = result as MezzeRecord
        assertEquals("alice", rec.getField("name"))
        assertEquals(30L, rec.getField("age"))
    }

    @Test
    fun `a partial failure recovers only the field that signalled`() {
        val result = eval(withParse("""
            let run = {} -> perform UseFallback in parse { name_ok = True, age_ok = False }
            run {}
            """))
        val rec = result as MezzeRecord
        assertEquals("alice", rec.getField("name"), "did not signal, so not recovered")
        assertEquals(0L, rec.getField("age"))
    }

    // ───────────────────────────────── late-bound discharge: same body, caller decides

    @Test
    fun `FirstErr turns the same body into an Err carrying the context`() {
        val result = eval(withParse("""
            let run = {} -> perform FirstErr in do
              let p = parse { name_ok = False, age_ok = False }
              Ok { val = p }
            run {}
            """))
        val v = result as MezzeVariant
        assertEquals("Err", v.constructorName, "gave up instead of carrying on")
        val payload = v.payloadValue as MezzeRecord
        assertEquals(
            "bad name", payload.getField("error"),
            "the FIRST signal's context — it never reached `age`",
        )
    }

    @Test
    fun `FirstNone turns the same body into None`() {
        val result = eval(withParse("""
            let run = {} -> perform FirstNone in do
              let p = parse { name_ok = False, age_ok = False }
              Some { val = p }
            run {}
            """))
        assertEquals("None", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `FirstErr yields Ok when nothing signals`() {
        val result = eval(withParse("""
            let run = {} -> perform FirstErr in do
              let p = parse { name_ok = True, age_ok = True }
              Ok { val = p }
            run {}
            """))
        val v = result as MezzeVariant
        assertEquals("Ok", v.constructorName)
        val inner = (v.payloadValue as MezzeRecord).getField("val") as MezzeRecord
        assertEquals("alice", inner.getField("name"))
    }

    // ───────────────────────────────── every signal is reached, not just the first

    @Test
    fun `a user handler is reached once per signal`() {
        // `1 + 2 = 3` is the assertion that matters: the sum can only be 3 if
        // BOTH signals were reached and each resumed with its own site's
        // fallback. A throw arrives once, so this number is not obtainable from
        // an exception — it is what "collect every problem" rests on.
        val result = eval(
            """
            use std::effects::condition::{ Condition, signal }

            impl Condition for Lenient having 'ctx = Str where {
              signal = { ctx, fallback } -> resume { val = fallback }
            }

            pub let total: {} -> Int <Condition> = {} -> do
              let a = signal { ctx = "a", fallback = 1 }
              let b = signal { ctx = "b", fallback = 2 }
              a + b

            let run = {} -> perform Lenient in total {}
            run {}
            """.trimIndent()
        )
        assertEquals(3L, result, "both signals resumed with their own fallback: 1 + 2")
    }

    @Test
    fun `an aborting handler stops at the first signal`() {
        // The mirror of the test above, and the proof that 3 was not an
        // accident of arithmetic: with an aborting handler the second signal is
        // never reached, so the sum never happens at all.
        val result = eval(
            """
            use std::effects::condition::{ Condition, signal }

            impl Condition for Strict having 'ctx = Str where {
              signal = { ctx, fallback } -> abort { val = 0 - 1 }
            }

            pub let total: {} -> Int <Condition> = {} -> do
              let a = signal { ctx = "a", fallback = 1 }
              let b = signal { ctx = "b", fallback = 2 }
              a + b

            let run = {} -> perform Strict in total {}
            run {}
            """.trimIndent()
        )
        assertEquals(-1L, result, "aborted at the first signal — `a + b` never ran")
    }
}
