package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * S-1: a receiver-less ability operation's declared effect row must reach its call
 * site. Own effect, own ability, nothing from the stdlib and nothing about sampling.
 *
 * Every case hands a thunk to something that requires the thunk to perform
 * `<Tick>`, and the cases differ only in what the thunk calls and what receives it.
 * Both `Tick.tick` and `Maker.make` are declared `<Tick>`.
 *
 * ## Why two consumers, and why the open one is the test that bites
 *
 * `needs_tick` has a CLOSED row and `Runner.go` an OPEN one (`<Tick, ...>`, the
 * shape `Sampler.run` has). Before the fix the ability op's row was dropped in both
 * — but a pure row is ABSORBED into a closed one, so the closed consumer accepted
 * the thunk and looked entirely healthy. Only the open row has a tail with nothing
 * to unify against, and only it failed. A test written against `needs_tick` alone
 * would have passed on the bug.
 */
class AbilityOpEffectRowTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val decls = """
        effect Tick where {
          tick: {} -> Int <Tick>
        }

        impl Tick for Ticker where {
          tick = {} -> 1
        }

        ability Maker for 'a where {
          make: {} -> 'a <Tick>
        }

        impl Maker for Int where {
          make = {} -> Tick.tick {}
        }

        # A plain function, CLOSED row.
        let needs_tick: { call: {} -> 'a <Tick> } -> 'a <Tick> = { call } -> call {}

        # An effect operation, OPEN row, whose performer discharges Tick.
        effect Runner where {
          go: { call: {} -> 'a <Tick, ...> } -> 'a <Runner>
        }

        impl Runner for TickRunner where {
          go = { call } -> perform Ticker in call {}
        }
    """.trimIndent()

    /** Every case here must produce 1 — the one value `Ticker` ever ticks. */
    private fun ticks(body: String) {
        assertEquals(1L, MezzeTestHelper.asLong(MezzeTestHelper.run("$decls\n\n$body")))
    }

    @Test
    fun `closed row, thunk calls an effect operation`() {
        ticks("perform Ticker in needs_tick { call = {} -> Tick.tick {} }")
    }

    @Test
    fun `closed row, thunk calls an ability operation`() {
        ticks(
            """
            perform Ticker in needs_tick { call = {} -> do
              let v: Int = Maker.make {}
              v
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `open row, thunk calls an effect operation`() {
        ticks("perform TickRunner in Runner.go { call = {} -> Tick.tick {} }")
    }

    /** THE case S-1 broke. Fails without the fix; nothing else here does. */
    @Test
    fun `open row, thunk calls an ability operation`() {
        ticks(
            """
            perform TickRunner in Runner.go { call = {} -> do
              let v: Int = Maker.make {}
              v
            }
            """.trimIndent(),
        )
    }

    /**
     * The workaround that used to be needed, kept as a test so it stays correct
     * rather than becoming the documented way to do this. `Tick.tick` is an effect
     * op, so it always propagated; its only job here was to put `Tick` in the row
     * that `Maker.make` had lost.
     */
    @Test
    fun `open row, ability operation alongside an unread effect call`() {
        ticks(
            """
            perform TickRunner in Runner.go { call = {} -> do
              let ignored = Tick.tick {}
              let v: Int = Maker.make {}
              v
            }
            """.trimIndent(),
        )
    }

    /**
     * Dispatch pinning is a SEPARATE and legitimate requirement, asserted here so
     * the two are not confused again: `make` takes no argument, so only the
     * expected type can select an impl. This is not what S-1 was about, and fixing
     * S-1 does not and should not make this compile.
     */
    @Test
    fun `an unpinned receiver-less ability op is rejected, and says why`() {
        val e = runCatching {
            MezzeTestHelper.run("$decls\n\nperform Ticker in needs_tick { call = {} -> Maker.make {} }")
        }.exceptionOrNull()
        val message = e?.message ?: ""
        assertTrue(
            "takes no receiver" in message && "what chooses the implementation" in message,
            "expected a return-type-dispatch refusal, got: $message",
        )
    }
}
