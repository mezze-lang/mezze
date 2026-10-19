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
        use std::effects::filesystem::{ FileSystem, MemoryFileSystem, exists, write_text }
        use std::effects::log::{ println, Console }
        use std::bool
    """.trimIndent()

    @Test
    fun `one program's in-memory filesystem is not the next program's`() {
        val wrote = MezzeTestHelper.run(
            """
            $fsHead
            perform MemoryFileSystem in
              match write_text { path = "/leaked.txt", text = "from the first program" } is
                Ok { val } -> "wrote"
                Err { error = _ } -> "write failed"
            """.trimIndent(),
        )
        assertEquals("wrote", wrote, "precondition: the first program must manage to write")

        val seen = MezzeTestHelper.run(
            """
            $fsHead
            perform MemoryFileSystem in
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

    /**
     * Isolation was only half the job: the state also has to be RELEASED.
     *
     * Per-context state fixed what the next program could SEE, not how long
     * the last program's bytes lived. `closeRegisteredResources` closed the
     * AutoCloseable state objects — which releases what they hold, not the
     * objects themselves — and most state objects are not AutoCloseable at
     * all, because they hold nothing but memory. So every entry stayed
     * reachable from the MezzeContext's own field until the MezzeContext was
     * collected: an in-memory filesystem, a mock response table of guest
     * records, a draw log, a cursor map.
     *
     * For `MezzeTestHelper.run` that is invisible, which is why it went
     * unnoticed — the Context is dropped on the next line and the heap goes
     * with it. It is visible to an embedder that KEEPS its Context objects,
     * which is what this test is: it holds the MezzeContext across the close,
     * exactly as a pool or a cache would.
     *
     * Reflection on `nativeState` is deliberate. Clearing a map has no
     * behavioural signature — a fresh context builds fresh state whether or
     * not the old map was emptied — so retention is the only thing to assert,
     * and the field is the only place it is observable. Without the clear in
     * `closeRegisteredResources` this fails on the second assert.
     */
    @Test
    fun `a disposed context releases its native state, not just closes it`() {
        val context = org.graalvm.polyglot.Context.newBuilder("mezze")
            .allowExperimentalOptions(true)
            .allowAllAccess(true)
            .build()

        val nativeState: java.util.Map<*, *>
        try {
            context.eval(
                "mezze",
                """
                $fsHead
                perform MemoryFileSystem in
                  match write_text { path = "/retained.txt", text = "bytes that must not outlive me" } is
                    Ok { val } -> "wrote"
                    Err { error = _ } -> "write failed"
                """.trimIndent(),
            )

            context.enter()
            try {
                val field = com.mezze.interpreter.runtime.MezzeContext::class.java
                    .getDeclaredField("nativeState")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                nativeState = field.get(
                    com.mezze.interpreter.runtime.MezzeContext.get(null),
                ) as java.util.Map<*, *>
            } finally {
                context.leave()
            }

            assertEquals(
                false, nativeState.isEmpty(),
                "precondition: writing to the in-memory filesystem must have created native state",
            )
        } finally {
            context.close()
        }

        assertEquals(
            true, nativeState.isEmpty(),
            "native state survived the close of its own context — an embedder holding " +
                "Context objects would retain every program's in-memory filesystem",
        )
    }
}
