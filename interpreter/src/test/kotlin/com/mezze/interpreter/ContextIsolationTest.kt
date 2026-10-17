package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * What one program does must not be visible to the next one.
 *
 * Each `MezzeTestHelper.run` is its own Context, and the interpreter runs many
 * Contexts on one Engine. Several natives kept their state in `static` fields,
 * so it crossed: the in-memory filesystem was shared by every program on the
 * Engine, and so was the HTTP mock table — whose values are guest `Response`
 * records, held for the life of the process.
 *
 * Unbounded growth is the lesser half. The real defect is that two programs
 * which never heard of each other saw each other's writes, and whether a test
 * passed depended on what had run before it.
 *
 * The in-memory filesystem is the one with a guest surface to test through, so
 * it is the one tested here. The rest of the sweep — the HTTP mock table, the
 * draw log, inner polyglot contexts, seeded generators, script cursors — moved
 * the same way; of those, only the mock table and the draw log have no guest
 * entry point at all, and the seeded generators are a consistency move whose
 * failure case nobody has reproduced (see the note in RandomNatives).
 */
class ContextIsolationTest {

    private val fsHead = """
        use std::effects::filesystem::{ FileSystem, MemoryFS, exists, write_text }
        use std::effects::log::{ println, Console }
        use std::bool
    """.trimIndent()

    @Test
    fun `one program's in-memory filesystem is not the next program's`() {
        val wrote = MezzeTestHelper.run(
            """
            $fsHead
            perform MemoryFS in
              match write_text { path = "/leaked.txt", text = "from the first program" } is
                Ok { val } -> "wrote"
                Err { error = _ } -> "write failed"
            """.trimIndent(),
        )
        assertEquals("wrote", wrote, "precondition: the first program must manage to write")

        val seen = MezzeTestHelper.run(
            """
            $fsHead
            perform MemoryFS in
              match exists { path = "/leaked.txt" } is
                Ok { val } -> bool::to_str { b = val }
                Err { error = _ } -> "stat failed"
            """.trimIndent(),
        )
        assertEquals(
            "False", seen,
            "a file written by another program must not exist here — the in-memory " +
                "filesystem was static, so it did",
        )
    }

}
