package com.mezze.language.typechecker

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The ability/effect rules, written down. Each claim is a rule the system is
 * supposed to obey; the per-axis files that follow probe each axis in depth.
 *
 * Axes enumerated: DECLARATION (ability vs effect, `having`, `it`, rows),
 * IMPL (ability vs inherent vs performer, receiver, scope hooks, coherence),
 * CALL (receiver-style vs static, scope/cont visibility), and EFFECTS
 * (discharge, undeclared performs, entry obligations).
 */
class AbilityEffectSpecClaimsTest : AbilityEffectSpecBase() {

    @Nested
    inner class Declaration {

        @Test
        fun `declaring an op whose class param appears nowhere is accepted`() {
            // Permissive at the DECLARATION; the refusal comes at the call, below.
            // Whether it should fail eagerly here instead is an open question —
            // pinned as-is rather than asserted as a gap.
            accepts(
                """
                ability Mystery for 'a where {
                  conjure: { n: Int } -> Int
                }
                $MAIN
                """,
                "declaration-time is permissive about an undetermined class param",
            )
        }

        @Test
        @org.junit.jupiter.api.Disabled("BUG 48 — accepted at check time, then a host crash")
        fun `calling an op whose class param appears nowhere is refused`() {
            rejects(
                """
                ability Mystery for 'a where {
                  conjure: { n: Int } -> Int
                }
                pub let main = {} -> Mystery.conjure { n = 1 }
                """,
                "T0215",
                "`'a` is in neither the params nor the result, so the call cannot dispatch. " +
                    "BUG 48: accepted today, and with two competing impls it reaches the " +
                    "runtime as `ShouldNotReachHere: no dictionary was supplied`.",
            )
        }

        @Test
        fun `a receiverless op is dispatched by the type it produces`() {
            // The mirror of the rule above, and the reason it has to be
            // "nowhere AT ALL" rather than "nowhere in the params": an op with
            // no receiver is legal and dispatches on its RESULT type.
            accepts(
                """
                ability Mystery for 'a where {
                  conjure: { n: Int } -> 'a
                }
                $MAIN
                """,
                "`'a` in the result is determinable at the call site",
            )
        }

        @Test
        fun `an op with it as its receiver is accepted`() {
            accepts(
                """
                type Box = { v: Int }
                ability Bulk for 'a where {
                  bulk: { it: 'a } -> Int
                }
                impl Bulk for Box where {
                  bulk = { it } -> it.v,
                }
                $MAIN
                """,
                "the receiver is `it`, declared in the op and in the impl",
            )
        }

        @Test
        fun `two abilities in scope cannot declare the same operation name`() {
            rejects(
                """
                ability Weigh for 'a where { rank: { it: 'a } -> Int }
                ability Price for 'a where { rank: { it: 'a } -> Int }
                $MAIN
                """,
                "V0701",
                "one op name belongs to one ability",
            )
        }
    }

    @Nested
    inner class Impl {

        @Test
        fun `a performer name implements exactly one effect`() {
            rejects(
                """
                effect Ticker where { tick: {} -> Int <Ticker>, }
                effect Jobs where { go: { n: Int } -> Int <Jobs>, }
                impl Ticker for Both where {
                  initialize_scope = {} -> {},
                  finalize_scope = {} -> {},
                  tick = {} -> 1,
                }
                impl Jobs for Both where {
                  go = { n } -> n,
                }
                $MAIN
                """,
                "V0701",
                "a performer is the handler for ONE effect; two impls collide on the name",
            )
        }

        @Test
        fun `an inherent impl body naming it without declaring it is refused`() {
            // BUG 47. The mirror case is below: declared, it must be accepted.
            rejects(
                """
                type Box = { v: Int }
                impl Box where {
                  get = {} -> it.v,
                }
                $MAIN
                """,
                "V0702",
                "`it` is the receiver and is an ordinary parameter — it must be declared",
            )
        }

        @Test
        fun `an inherent impl body declaring it is accepted`() {
            accepts(
                """
                type Box = { v: Int }
                impl Box where {
                  get = { it } -> it.v,
                }
                $MAIN
                """,
                "the mirror of the rule above; without it the refusal is weak evidence",
            )
        }

        @Test
        fun `an ability impl body naming it without declaring it is refused`() {
            rejects(
                """
                type Box = { v: Int }
                ability Bulk for 'a where { bulk: { it: 'a } -> Int }
                impl Bulk for Box where {
                  bulk = {} -> it.v,
                }
                $MAIN
                """,
                "V0702",
                "the ability declares `it`, but the impl's lambda must still bind it",
            )
        }

        @Test
        fun `an effect performer body cannot name it at all`() {
            // Effects are not on types: a performer has no receiver. It reaches
            // its own data through bare performer params and `scope`.
            rejects(
                """
                effect Tick where { tick: { n: Int } -> Int <Tick>, }
                impl Tick for Counter { base: Int } where {
                  initialize_scope = {} -> {},
                  finalize_scope = {} -> {},
                  tick = { n } -> n + it.base,
                }
                $MAIN
                """,
                "V0702",
                "there is no receiver in an effect performer, so `it` is undefined",
            )
        }

        @Test
        fun `an undefined name anywhere in an inherent impl body is refused`() {
            rejects(
                """
                type Box = { v: Int }
                impl Box where {
                  get = { it } -> nonexistent_thing,
                }
                $MAIN
                """,
                "V0702",
                "BUG 47's other half — the gap swallowed every undefined name",
            )
        }

        @Test
        fun `a sibling reference from an inherent impl body resolves`() {
            accepts(
                """
                type Box = { v: Int }
                let bump: { n: Int } -> Int = { n } -> n + 1
                impl Box where {
                  get = { it } -> bump { n = it.v },
                }
                $MAIN
                """,
                "the guard for the above: a module-level name must not read as a typo",
            )
        }
    }

    @Nested
    inner class Call {

        @Test
        fun `scope outside a performer op body is refused`() {
            rejects(
                """
                pub let main = {} -> scope.anything
                """,
                "V0702",
                "`scope` is runtime-bound in a performer op body and nowhere else",
            )
        }

        @Test
        fun `a performer may perform an effect its op declaration never mentions`() {
            accepts(
                """
                effect Jobs where { go: { n: Int } -> Int <Jobs>, }
                effect Ticker where { tick_ms: {} -> Int <Ticker>, }
                impl Ticker for FixedTicker { at: Int } where {
                  initialize_scope = {} -> {},
                  finalize_scope = {} -> {},
                  tick_ms = {} -> at,
                }
                impl Jobs for TimeHungry where {
                  initialize_scope = {} -> {},
                  finalize_scope = {} -> {},
                  go = { n } -> n + Ticker.tick_ms {},
                }
                pub let main = {} -> perform FixedTicker { at = 500 }, TimeHungry {} in
                  Jobs.go { n = 7 }
                """,
                "the obligation is PER-PERFORMER and flows to the caller, not to the op's row",
            )
        }

        @Test
        fun `an intermediate annotation need not carry a performer's added effect`() {
            accepts(
                """
                effect Jobs where { go: { n: Int } -> Int <Jobs>, }
                effect Ticker where { tick_ms: {} -> Int <Ticker>, }
                impl Ticker for FixedTicker { at: Int } where {
                  initialize_scope = {} -> {},
                  finalize_scope = {} -> {},
                  tick_ms = {} -> at,
                }
                impl Jobs for TimeHungry where {
                  initialize_scope = {} -> {},
                  finalize_scope = {} -> {},
                  go = { n } -> n + Ticker.tick_ms {},
                }
                let middle: { n: Int } -> Int <Jobs> = { n } -> Jobs.go { n = n }
                pub let main = {} -> perform FixedTicker { at = 500 }, TimeHungry {} in
                  middle { n = 7 }
                """,
                "`<Jobs>` alone still checks — the added effect is invisible mid-chain",
            )
        }
    }
}
