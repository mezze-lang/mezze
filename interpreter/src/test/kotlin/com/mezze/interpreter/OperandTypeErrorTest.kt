package com.mezze.interpreter

import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.interpreter.nodes.inline.FractionHelper
import com.mezze.interpreter.nodes.inline.OperandTypeError
import com.mezze.interpreter.runtime.MezzeInternalError
import com.mezze.interpreter.values.MezzeNull
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An operator's `@Fallback` names its operands in MEZZE's types.
 *
 * These messages used to read `Cannot multiply String and Long` — the JVM's
 * names for values a user writes as `Str` and `Int`. The naming is the whole
 * point of the class, so it is asserted value by value rather than by "it threw
 * something".
 */
class OperandTypeErrorTest {

    @Test
    fun `primitives are named as mezze types`() {
        assertEquals("an Int", OperandTypeError.describe(1L))
        assertEquals("a Float", OperandTypeError.describe(1.5))
        assertEquals("a Str", OperandTypeError.describe("x"))
        assertEquals("a Bool", OperandTypeError.describe(true))
    }

    @Test
    fun `null and the null value agree`() {
        assertEquals("null", OperandTypeError.describe(null))
        assertEquals("null", OperandTypeError.describe(MezzeNull.INSTANCE))
    }

    @Test
    fun `a Frac is told apart from a plain record`() {
        // A Frac IS a record at runtime, so the general record case would
        // swallow it without the FractionHelper question.
        val frac = FractionHelper.makeFraction(1, 2)
        assertEquals("a Frac", OperandTypeError.describe(frac))
        assertEquals(
            "a record",
            OperandTypeError.describe(MezzeRecord.fromFields(arrayOf("x"), arrayOf<Any>(1L))),
        )
    }

    @Test
    fun `a seq is named`() {
        assertEquals("a seq", OperandTypeError.describe(MezzeSeq.fromArray(arrayOf<Any>(1L, 2L))))
    }

    @Test
    fun `an unnamed internal value is marked internal, not dressed as a type`() {
        // Deliberate: the class is KEPT, because this message is what a bug
        // report carries — but it is labelled internal so it cannot be read as
        // a Mezze type the user should recognise.
        val described = OperandTypeError.describe(StringBuilder())
        assertTrue(described.startsWith("an internal value ("), described)
        assertTrue(described.contains("StringBuilder"), described)
    }

    @Test
    fun `a binary failure reads as a sentence and keeps its operator`() {
        val e = OperandTypeError.binary("multiply", "A STRING", "and", 2L)
        assertEquals("cannot multiply a Str and an Int", e.message)

        // The joiner exists so a comparison still says WHICH comparison.
        assertEquals(
            "cannot compare a Str >= an Int",
            OperandTypeError.binary("compare", "x", ">=", 1L).message,
        )
        assertEquals(
            "cannot modulo an Int by a Str",
            OperandTypeError.binary("modulo", 1L, "by", "x").message,
        )
    }

    @Test
    fun `a unary failure reads as a sentence`() {
        assertEquals("cannot negate a Str", OperandTypeError.unary("negate", "x").message)
        assertEquals("cannot apply `!` to an Int", OperandTypeError.unary("apply `!` to", 1L).message)
    }

    @Test
    fun `the failure is a labelled internal error, and no message names a JVM type`() {
        val e: MezzeInternalError = OperandTypeError.binary("add", "x", "and", 1L)
        for (bad in listOf("String", "Long", "Double", "Boolean", "java.lang")) {
            assertFalse(
                e.message!!.contains(bad),
                "a JVM type name survived into the user-visible message: ${e.message}",
            )
        }
    }
}
