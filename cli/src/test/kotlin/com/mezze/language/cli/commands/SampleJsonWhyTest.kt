package com.mezze.language.cli.commands

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `mez sample --json` carries each row's `why`.
 *
 * The table shows why a pinned row exists (`if n > max_value (= 100) — then`).
 * The JSON did not, so a tool reading the sample as data could see the values but
 * not which branch each one was chosen to reach, which is the branch plan itself.
 * A drawn row's `why` is null.
 */
class SampleJsonWhyTest {

    @Test
    fun `a pinned row carries its reason and a drawn row carries null`() {
        val rows = listOf(
            SampleCommand.RowOutcome.Crashed("boom"),
            SampleCommand.RowOutcome.Crashed("bang"),
        )
        val json = SampleCommand().sampleJson("f", 7, rows, emptyList(), File("app.mz")) { i ->
            if (i == 0) "if n > 10 — then" else null
        }
        assertTrue(json.contains("\"crashed\":\"boom\",\"why\":\"if n > 10 — then\""), json)
        assertTrue(json.contains("\"crashed\":\"bang\",\"why\":null"), json)
    }
}
