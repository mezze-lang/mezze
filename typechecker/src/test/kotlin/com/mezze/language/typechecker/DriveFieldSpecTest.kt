package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * `drive` — the third impl-only field of a performer (after `initialize_scope` /
 * `finalize_scope`): the scheduler's SELECTOR, typed `{ runnable: RunQueue } -> Task`
 * against the stdlib's handle types. Not an op of the effect, so it is neither
 * "extra" (V0405) nor required.
 */
class DriveFieldSpecTest : AbilityEffectSpecBase() {

    private val tick = """
        use std::effects::async::{ RunQueue, Task }
        effect Tick where { tick: {} -> Int <Tick>, }
    """

    @Test
    fun `a performer may declare drive`() {
        accepts(
            """
            $tick
            impl Tick for Fair where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              drive = { runnable } -> runnable.first {},
              tick = {} -> 1,
            }
            $MAIN
            """,
            "`drive` is impl-only plumbing like the scope hooks, on ANY effect's performer",
        )
    }

    @Test
    fun `drive may index the run queue`() {
        accepts(
            """
            $tick
            impl Tick for Picky where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              drive = { runnable } -> runnable.at { i = runnable.len {} - 1 },
              tick = {} -> 1,
            }
            $MAIN
            """,
            "`RunQueue` has `first`, `last`, `len`, `at`",
        )
    }

    @Test
    fun `drive must return a Task`() {
        rejects(
            """
            $tick
            impl Tick for Wrong where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              drive = { runnable } -> 5,
              tick = {} -> 1,
            }
            $MAIN
            """,
            "T0201",
            "a selector hands back one of the runnable tasks, not a number",
        )
    }

    @Test
    fun `drive must be a function`() {
        rejects(
            """
            $tick
            impl Tick for Wrong where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              drive = 5,
              tick = {} -> 1,
            }
            $MAIN
            """,
            "T0201",
            "the mirror of the shape",
        )
    }
}
