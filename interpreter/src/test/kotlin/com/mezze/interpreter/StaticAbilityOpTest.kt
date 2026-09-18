package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An ability operation that declares NO RECEIVER is dispatched by the type it
 * PRODUCES.
 *
 * `ability Default for 'a where { default: {} -> 'a }` is in the stdlib and had
 * no callable form. `Default.default {}` crashed at runtime with "Expected record
 * for field 'default', got: MezzeVariant", and the bare `default {}` was rejected
 * with a message recommending the form that crashed. A user ability with a static
 * operation was rejected outright: "Direct ability access 'Zero.zero' is not
 * allowed. Use 'value.zero { args }' or 'Zero.zero { it = value, ... }'" — advice
 * that cannot be followed, because a static operation has no value to put in `it`.
 *
 * The cause was that `Ability.op { it = v, … }` is DESUGARED to `v.op { … }`, and
 * with no `it` field there was nothing to desugar through: the call fell to the
 * ordinary path, which builds the receiver by evaluating `memberAccess.receiver`.
 * An ability name is not a value — and `Default` is uppercase, so it built as a
 * variant constructor, an open variant that unified with whatever the ability's
 * type variable wanted, and the program reached the runtime doing a field read on
 * a variant.
 *
 * The receiver-taking forms only ever worked by going through that desugaring;
 * this suite keeps one of them here so the two paths are read together.
 */
class StaticAbilityOpTest {

    private val zero = """
        ability Zero for 'a where {
          zero: {} -> 'a
        }
        impl Zero for Int where {
          zero = {} -> 0
        }
    """.trimIndent()

    @Test
    fun `the stdlib Default resolves through the annotated type`() {
        assertEquals(
            false,
            MezzeTestHelper.run(
                """
                use std::abilities::default::{ Default }
                let b: Bool = Default.default {}
                b
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the same call at a different type picks that type's impl`() {
        assertEquals(
            0L,
            MezzeTestHelper.run(
                """
                use std::abilities::default::{ Default }
                let n: Int = Default.default {}
                n
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a user ability's static operation is callable`() {
        assertEquals(0L, MezzeTestHelper.run("$zero\nlet n: Int = Zero.zero {}\nn"))
    }

    @Test
    fun `its result is an ordinary value`() {
        assertEquals(1L, MezzeTestHelper.run("$zero\nlet n: Int = Zero.zero {}\nn + 1"))
    }

    @Test
    fun `a static operation may take arguments of its own`() {
        assertEquals(
            "42",
            MezzeTestHelper.run(
                """
                ability Make for 'a where {
                  of: { n: Int } -> 'a
                }
                impl Make for Str where {
                  of = { n } -> n.to_str {}
                }
                let s: Str = Make.of { n = 42 }
                s
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `no impl for the produced type is an error that names both`() {
        val e = runCatching {
            MezzeTestHelper.run("$zero\nlet s: Str = Zero.zero {}\ns")
        }.exceptionOrNull()
        val message = e?.message ?: ""
        assertTrue(message.contains("Str"), "should name the produced type; got: $message")
        assertTrue(message.contains("Zero"), "should name the ability; got: $message")
    }

    @Test
    fun `a produced type nothing determines is a diagnostic, not silence`() {
        // The boundary of this feature, stated rather than discovered: the impl is
        // chosen from the type the call produces, so that type has to be known AT
        // THE CALL. `let f = Zero.zero {}` followed by `f + 10` does not qualify —
        // the usage pins `f`, but the call's own type variable is still open when
        // the implementation must be picked.
        //
        // What matters is that this SAYS SO. The message used to be swallowed by a
        // blanket `catch (e: Exception)` in TypedModuleEmitter, and the program
        // died at the runtime with "TruffleIR not produced by typechecker" — a
        // sentence no one can act on.
        val e = runCatching {
            MezzeTestHelper.run("$zero\nlet f = Zero.zero {}\nf + 10")
        }.exceptionOrNull()
        val message = e?.message ?: ""
        assertTrue(
            message.contains("nothing here determines that type"),
            "should say the produced type is undetermined; got: $message",
        )
        assertTrue(
            message.contains("with <Ability>"),
            "should also offer declaring the constraint; got: $message",
        )
    }

    @Test
    fun `it forwards through a declared constraint`() {
        // A wrapper that declares `with Zero 'a` and returns what the operation
        // produces. A receiver-taking operation has always been able to do this —
        // the dictionary is reached through the receiver's TYPE. With no receiver
        // there is no such value, so the reference names the (ability, operation,
        // variable) it wants directly and reads the same dictionary slot.
        assertEquals(
            0L,
            MezzeTestHelper.run("$zero\nlet make: {} with Zero 'a -> 'a = {} -> Zero.zero {}\nlet n: Int = make {}\nn"),
        )
    }

    @Test
    fun `the same wrapper resolves at each type it is used at`() {
        val src = "$zero\nimpl Zero for Str where {\n  zero = {} -> \"z\"\n}\n" +
            "let make: {} with Zero 'a -> 'a = {} -> Zero.zero {}\n" +
            "let s: Str = make {}\ns"
        assertEquals("z", MezzeTestHelper.run(src))
    }

    @Test
    fun `the stdlib Default forwards through a wrapper too`() {
        assertEquals(
            false,
            MezzeTestHelper.run(
                """
                use std::abilities::default::{ Default }
                let mk: {} with Default 'a -> 'a = {} -> Default.default {}
                let b: Bool = mk {}
                b
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the receiver-taking form still goes through the desugaring`() {
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                use std::abilities::compare::{ Eq }
                Eq.eq { it = 1, r = 1 }
                """.trimIndent(),
            ),
        )
    }
}
