package com.mezze.language.cli.commands

import com.mezze.wire.WireValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A sample table reads from the simplest case to larger ones, without repeats.
 *
 * Every row used to be drawn at the same size, so the first row was as likely to be
 * `-6431 × -11083` as anything else, and small sizes repeated themselves
 * (`{ n = 0 }` three times). Now row `i` is drawn at size `min(i, size)`, and rows
 * whose arguments repeat an earlier row are dropped, with extra rows drawn to make
 * up the count.
 */
class SampleRowsGrowTest {
    private val cmd = SampleCommand()
    private fun ran(text: String) = SampleCommand.RowOutcome.Ran(WireValue.Scalar("int", text), null)

    @Test fun `row sizes grow from zero and stop at the configured size`() {
        val config = SampleCommand.Config(size = 5, enumerate = 12)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 5, 5), (0 until 8).map { cmd.rowSize(it, config) })
    }

    @Test fun `a row's size depends on the row alone, so more rows keep the earlier ones`() {
        val config = SampleCommand.Config(size = 5, enumerate = 12)
        assertEquals((0 until 3).map { cmd.rowSize(it, config) }, (0 until 6).map { cmd.rowSize(it, config) }.take(3))
    }

    @Test fun `enough rows are drawn to survive dropping repeats`() {
        assertTrue(cmd.drawnFor(3) > 3)
        assertTrue(cmd.drawnFor(100) <= 100, "never past the maximum")
    }

    @Test fun `repeated arguments are dropped, in order, up to the count`() {
        val rows = listOf(ran("0"), ran("0"), ran("1"), ran("1"), ran("2"), ran("3"))
        assertEquals(listOf(0, 2, 4), cmd.distinctRows(rows, count = 3, pinned = 0))
    }

    @Test fun `a branch row is always kept, even when it repeats`() {
        val rows = listOf(ran("0"), ran("0"), ran("0"), ran("1"))
        assertEquals(listOf(0, 1, 3), cmd.distinctRows(rows, count = 3, pinned = 2))
    }

    @Test fun `a crashed row is always kept`() {
        val rows = listOf(ran("0"), SampleCommand.RowOutcome.Crashed("boom"), SampleCommand.RowOutcome.Crashed("boom"))
        assertEquals(listOf(0, 1, 2), cmd.distinctRows(rows, count = 3, pinned = 0))
    }
}
