package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A `.field` on something that is not a record must say WHAT THE RECEIVER IS.
 *
 * Every one of these used to read "Cannot access field 'x' on non-record type:
 * T" — an answer phrased as a negative. It was worst for a builtin primitive
 * like `Frac`, whose parts genuinely exist (`MezzeRecord{numerator,
 * denominator}` at runtime) but are reached by other means, so "non-record"
 * read as if the field were merely misspelled:
 *
 *     let q: Frac = (5).into {}
 *     q.numerator
 *     -> error[T0205]: Cannot access field 'numerator' on non-record type: Frac
 *
 * The newtype branch of `solveHasField` was already the model — it names the
 * shape, says why the field is hidden, and gives the way in — and the other
 * branches now follow it.
 *
 * ASSERTED AS A PROPERTY, not as snapshots, matching
 * [ErrorMessageQualityGuardTest]: each message must NAME the receiver and must
 * not fall back to the old uninformative phrase. Pinning whole sentences would
 * make every future rewording a test edit, which is the opposite of the point.
 */
class FieldAccessOnNonRecordMessageTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun messageFor(src: String): String {
        val parsed = parser.parseParsedModule(src.trimIndent(), "test.mz")
        return try {
            when (val r = typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)) {
                is TypeChecker.TypeCheckResult.Failure -> r.errors.joinToString("\n") { it.message }
                else -> fail<String>("expected a field-access error, got: $r")
            }
        } catch (e: TypeCheckError) {
            e.message
        }
    }

    /** The receiver's own name, and no retreat to the negative phrasing. */
    private fun assertNamesReceiver(src: String, receiver: String, alsoMentions: String, why: String) {
        val msg = messageFor(src)
        assertTrue(
            msg.contains(receiver),
            "$why — the message must name the receiver `$receiver`, got:\n$msg",
        )
        assertFalse(
            msg.contains("on non-record type"),
            "$why — \"on non-record type\" says what the receiver is NOT. Got:\n$msg",
        )
        assertTrue(
            msg.contains(alsoMentions),
            "$why — it must also say what to do instead (expected to mention " +
                "\"$alsoMentions\"), got:\n$msg",
        )
    }

    @Test
    fun `a primitive is named as a primitive`() {
        assertNamesReceiver(
            """
            use std::int
            pub let f: {} -> Int = {} -> do
              let x: Int = 1
              x.numerator
            """,
            receiver = "Int",
            alsoMentions = "primitive",
            why = "`Int` has no fields at all, and a reader needs to know that is " +
                "the KIND of thing it is rather than hunt for the right field name",
        )
    }

    @Test
    fun `Frac is named as a primitive, not merely as a non-record`() {
        assertNamesReceiver(
            """
            use std::int
            use std::frac
            use std::abilities::convert::{ Into }
            pub let f: {} -> Int = {} -> do
              let q: Frac = (5).into {}
              q.numerator
            """,
            receiver = "Frac",
            alsoMentions = "primitive",
            why = "THE CASE THAT PROMPTED THIS. `Frac` is a builtin primitive " +
                "(`MezzeType.FracType`, `PrimitiveTypes.FRAC`) with no declaration " +
                "in the stdlib; `MezzeRecord{numerator, denominator}` is only its " +
                "runtime layout. It is opaque like a newtype but by a different " +
                "mechanism — there is no `Frac.(v)` unwrap — so the message must " +
                "not suggest the field is just missing",
        )
    }

    @Test
    fun `a function says to call it first`() {
        assertNamesReceiver(
            """
            use std::int
            pub let f: {} -> Int = {} -> do
              let g: { n: Int } -> Int = { n } -> n
              g.n
            """,
            receiver = "function",
            alsoMentions = "Call it first",
            why = "Reading a field off an UNCALLED function is a missing call, and " +
                "that is the most likely thing the author meant",
        )
    }

    @Test
    fun `a variant points at the match`() {
        assertNamesReceiver(
            """
            use std::int
            type Color = | Red, Green |
            pub let f: {} -> Int = {} -> do
              let c: Color = Red
              c.shade
            """,
            receiver = "variant",
            alsoMentions = "match",
            why = "A variant's fields live on a CONSTRUCTOR'S PAYLOAD, so the step " +
                "being skipped is the match, not a different field name",
        )
    }

    @Test
    fun `a newtype still gives its own message`() {
        val msg = messageFor(
            """
            use std::int
            newtype Meters = Int
            pub let f: {} -> Int = {} -> do
              let m: Meters = Meters.(5)
              m.value
            """,
        )
        assertTrue(
            msg.contains("opaque newtype 'Meters'") && msg.contains("match x is Meters.(v)"),
            "THE CONTROL: the newtype branch was already the model the others now " +
                "follow, so it must be untouched — it names the wrapper AND the " +
                "exact unwrap pattern. Got:\n$msg",
        )
    }
}
