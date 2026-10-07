package com.mezze.language.ast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A function type has ONE trailing row, so when a function RETURNS a function
 * that carries its own row, and also carries one itself, the inner type must be
 * parenthesised.
 *
 * The printer emitted `{} -> {} -> Int <Tick> <Tock>` — two trailing rows with no
 * way to tell which arrow owns which. The parser now refuses that form (P0133),
 * and the printer must never emit what the parser cannot read back: these strings
 * appear in diagnostics, hover and `mez check` output, and users copy them.
 */
class TypePrinterRowParensTest {

    private val unit = MezzeType.RecordType(emptyMap())
    private fun row(vararg names: String) = MezzeType.EffectRow(names.toSet())
    private fun fn(ret: MezzeType, effects: MezzeType.EffectRow? = null) =
        MezzeType.FunctionType(unit, ret, effects)

    @Test
    fun `a row-carrying returned function is parenthesised when the outer has a row`() {
        val t = fn(fn(MezzeType.IntType, row("Tick")), row("Tock"))
        assertEquals("{} -> ({} -> Int <Tick>) <Tock>", prettyPrintType(t))
    }

    /** No outer row: the single row is unambiguously the inner arrow's. No parens. */
    @Test
    fun `no parens when only the returned function has a row`() {
        val t = fn(fn(MezzeType.IntType, row("Tick")))
        assertEquals("{} -> {} -> Int <Tick>", prettyPrintType(t))
    }

    /**
     * The subtle one: no inner row, and parens are STILL required. A trailing row
     * is parsed onto the NEAREST arrow, so `{} -> {} -> Int <Tock>` reads back with
     * the INNER function performing Tock — a different type from the one printed.
     */
    @Test
    fun `parens even when the returned function has no row of its own`() {
        val t = fn(fn(MezzeType.IntType), row("Tock"))
        assertEquals("{} -> ({} -> Int) <Tock>", prettyPrintType(t))
    }
}
