package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ChainMethodDebugTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `simple chain with let`() {
        assertEquals("HELLO", eval("""
            use std::str
            let trimmed = "  hello  ".trim {}
            trimmed.to_upper {}
        """))
    }

    @Test
    fun `direct chain`() {
        assertEquals("HELLO", eval("""
            use std::str
            "  hello  ".trim {}.to_upper {}
        """))
    }

    @Test
    fun `chain with function call between`() {
        assertEquals("HELLO", eval("""
            use std::str
            let trimmed = str::trim {s = "  hello  "}
            trimmed.to_upper {}
        """))
    }

    // ── #160: inherent method (to_str) on a value whose Int type is only
    // known after solving (polymorphic callee). These RUN the program — the
    // emit phase must resolve to the Int impl's hash, not mis-dispatch.

    @Test
    fun `to_str on a polymorphic call result (inline) runs as Int`() {
        assertEquals("11", eval("""
            let help = { a, b } -> a + b
            help { a = 5, b = 6 }.to_str {}
        """))
    }

    @Test
    fun `to_str on a let-bound polymorphic call result runs as Int`() {
        // The exact shape the user hit.
        assertEquals("11", eval("""
            let help = { a, b } -> a + b
            let x = help { a = 5, b = 6 }
            x.to_str {}
        """))
    }

    @Test
    fun `parenthesized int literal to_str runs as Int`() {
        assertEquals("5", eval("(5).to_str {}"))
    }

    @Test
    fun `chained abs then to_str on a let-bound Int runs`() {
        assertEquals("5", eval("""
            let x = 0 - 5
            x.abs {}.to_str {}
        """))
    }

    // ── #160: abs — RECEIVER-DEPENDENT RETURN TYPE — on a value whose type is
    // only known after solving. Unlike to_str (all impls return Str), abs is
    // Int->Int / Float->Float / Frac->Frac, so emit MUST pick both the right
    // impl AND the right return type from the SOLVED receiver.

    @Test
    fun `abs then to_str chain on a polymorphic call result runs as Int`() {
        // help {a=5,b=6} = 11 → abs 11 = 11.
        assertEquals("11", eval("""
            let help = { a, b } -> a + b
            (help { a = 5, b = 6 }).abs {}.to_str {}
        """))
    }

    @Test
    fun `abs then to_str chain on a polymorphic negative sum runs as 5`() {
        // help {a=2,b=3} = 5, negated to -5, abs → 5. Prints "5".
        assertEquals("5", eval("""
            let help = { a, b } -> a + b
            (0 - help { a = 2, b = 3 }).abs {}.to_str {}
        """))
    }

    @Test
    fun `abs then to_str chain on a polymorphic Float call result runs as Float`() {
        // The args pin the receiver to Float, so abs dispatches to Float.abs and
        // the result is a Float ("11.0"), NOT the Int "11" — proves the return
        // type follows the solved receiver.
        assertEquals("11.0", eval("""
            let help = { a, b } -> a + b
            (help { a = 5.0, b = 6.0 }).abs {}.to_str {}
        """))
    }

    // ── #160 final edge: the chain lives INSIDE string interpolation AND the
    // callee is FORWARD-REFERENCED (help declared after its caller). Before the
    // fix, ParsedStringLit.children() dropped interpolation holes, so the
    // dependency walk never saw `help`, help stayed unresolved, and the chain's
    // receiver type never pinned → T0206 at typecheck. These RUN end-to-end.

    @Test
    fun `interpolated abs-to_str chain on forward-ref poly call runs as 11`() {
        assertEquals("11", eval("""
            let g = {} -> "${'$'}(help { a = 5, b = 6 }.abs {}.to_str {})"
            let help = { a, b } -> a + b
            g {}
        """))
    }

    @Test
    fun `interpolated single to_str on forward-ref poly call runs as 11`() {
        assertEquals("11", eval("""
            let g = {} -> "${'$'}(help { a = 5, b = 6 }.to_str {})"
            let help = { a, b } -> a + b
            g {}
        """))
    }
}
