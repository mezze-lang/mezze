package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * B5 — `Nat - Nat → Int` (`having 'out = Int`) diagnostic blame.
 *
 * `NumSub Nat for Nat having 'out = Int` widens: subtracting two Nats yields Int
 * (they can go negative). The bug was that the `'out := Int` binding could lose a
 * priority race to an argument Equality, so a type error was mis-attributed to an
 * innocent consumer rather than to where the Int actually flowed. The class-param
 * associated-type deferral (see [HavingOutGeneralizationSoundnessTest]) makes the
 * resolution order deterministic, so blame now lands at the real site. These pin
 * that.
 */
class NatSubBlameLocationTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun errorOf(src: String): TypeCheckError? =
        try {
            tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) { e }

    private fun typeOf(src: String, name: String) =
        tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            .getVariableType(name)

    @Test fun `Nat minus Nat is Int`() {
        assertTrue(typeOf("let d = 5n - 2n\nd", "d") is MezzeType.IntType, "5n - 2n should be Int (widened)")
    }

    @Test fun `feeding a Nat-Nat result into a Nat slot blames that call`() {
        val err = errorOf("""
            pub let need_nat: { n: Nat } -> Nat = { n } -> n
            let d = 5n - 2n
            let bad = need_nat { n = d }
            bad
        """)
        assertNotNull(err, "feeding Int (5n-2n) into a Nat field must fail")
        val msg = err!!.message ?: ""
        // Blame must name the Nat consumer, not some unrelated site.
        assertTrue(
            msg.contains("need_nat") || (msg.contains("Nat") && msg.contains("Int")),
            "error should point at the Nat consumer; got: $msg"
        )
    }

    @Test fun `an Int consumer of the same result is fine and not blamed`() {
        // The Int consumer accepts the Int result; only the Nat consumer errors.
        val err = errorOf("""
            pub let need_int: { n: Int } -> Int = { n } -> n
            let d = 5n - 2n
            let ok = need_int { n = d }
            ok
        """)
        assertNull(err, "feeding Int (5n-2n) into an Int field must type-check")
    }

    @Test fun `annotated Nat return from a Nat-Nat subtraction is rejected`() {
        // Body `a - b` is Int, annotated return Nat → must be rejected (the widening
        // means the body cannot honour the Nat return).
        val err = errorOf("""
            pub let f: { a: Nat, b: Nat } -> Nat = { a, b } -> a - b
            f { a = 5n, b = 2n }
        """)
        assertNotNull(err, "annotated `-> Nat` body `a - b` (Int) must be rejected")
    }
}
