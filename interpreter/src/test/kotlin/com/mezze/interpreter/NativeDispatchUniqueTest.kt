package com.mezze.interpreter

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * No native may be registered twice.
 *
 * `HANDLERS` is a `HashMap`, so a repeated `put` SILENTLY overwrites: the
 * earlier body becomes dead code and which one survives is decided by source
 * order rather than by anyone's intent. The map cannot be asked about this at
 * runtime — it just holds one entry — so the invariant is checked against the
 * SOURCE, the way `EmitFileSizeRatchetTest` checks its bounds.
 *
 * FOUND BY SWEEP, not by a report: 19 natives were registered twice, 15 with
 * different bodies. Nothing was broken, because in all three cases where the
 * bodies differed materially the correct one happened to be registered LAST:
 *
 *  - `__native_float_to_int` — dead `(long) n` against the live Option that
 *    answers `None` for NaN and infinity. The declared signature is
 *    `-> Option Int`, so the dead body would have been wrong for it: source
 *    order was load-bearing on a native's RETURN TYPE.
 *  - `__native_deque_to_seq` and `__native_set_to_seq` — dead `toArray`
 *    returning a bare `Object[]` against the live `toSeq`, which builds
 *    SPECIALIZED storage. These are a redundant wrap and NOT a hazard: the
 *    first reading of them claimed the dead body would crash `seq::sort` on an
 *    object-tier seq of Ints, and that is wrong, because whatever consumes the
 *    seq calls `coerceSeq`, which wraps an `Object[]` with
 *    `MezzeSeq.fromArray` — and that scans and specializes. Checked rather
 *    than assumed, after asserting the opposite.
 *
 * The other sixteen were equivalent bodies — `PrimitiveNatives.floatXxx`
 * wrappers against the same `Math.xxx` calls — and so cost only dead code.
 */
class NativeDispatchUniqueTest {

    private val dispatchSource = File(
        "src/main/java/com/mezze/interpreter/nodes/builtin/NativeDispatch.java",
    )

    @Test
    fun `every native is registered exactly once`() {
        assert(dispatchSource.isFile) { "not found: ${dispatchSource.absolutePath}" }

        val registration = Regex("""HANDLERS\.put\("([^"]+)"""")
        val byName = dispatchSource.readLines()
            .withIndex()
            .mapNotNull { (i, line) ->
                registration.find(line)?.groupValues?.get(1)?.let { it to i + 1 }
            }
            .groupBy({ it.first }, { it.second })

        val duplicates = byName.filterValues { it.size > 1 }

        assertEquals(
            emptyMap(),
            duplicates,
            "a native registered more than once: the LAST `put` wins and the" +
                " others are dead code, so which body runs depends on source" +
                " order. Delete the dead registration, keeping the one that is" +
                " currently live — see this class's comment for the three that" +
                " would have changed behaviour.",
        )
    }
}
