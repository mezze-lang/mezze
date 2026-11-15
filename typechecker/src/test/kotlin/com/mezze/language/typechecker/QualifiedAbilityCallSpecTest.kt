package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * `Ability.op { … }` — the QUALIFIED call, which is the only form a receiverless
 * operation has, and what its `having` bindings mean there.
 *
 * Two bugs, both found by the ability/constraint dive and both about this one
 * path.
 *
 * BUG 43: the path equated the argument record with the operation's parameter
 * type — which DOES bind the receiver, so a wrong one has always given V0402 —
 * and then never emitted an `AssociatedTypeResolution`. The impl was selected
 * and never asked what its `having` bindings ARE, so a wrong associated-typed
 * argument passed and the result escaped as the ability's own variable.
 *
 * BUG 42: an operation may carry its own type parameter when a per-op `with`
 * clause introduces it (T0301 permits exactly that, and says so). Every impl of
 * such an operation was V0417, so the ability could be declared and never
 * implemented. The runtime half — each instantiation reaching its own
 * dictionary — is in `GenericAbilityOpRuntimeTest`, because the typechecker
 * accepted both call sites before and after.
 */
class QualifiedAbilityCallSpecTest : VariantSpecBase() {

    private val receives = """
        type Light = | Red, Green |
        type LightMsg = | Flip |
        ability Receives for 'a having 'msg, 'reply where {
          receive: { target: 'a, msg: 'msg } -> 'reply,
        }
        impl Receives for Light having 'msg = LightMsg, 'reply = Light where {
          receive: { target: Light, msg: LightMsg } -> Light = { target, msg } -> Green,
        }
    """.trimIndent()

    @Test fun `a qualified call APPLIES the impl's having binding`() {
        val r = check(
            """
            $receives
            pub let p = Receives.receive { target = Red, msg = Flip }
            $MAIN
            """
        )
        if (r.isFailure) throw AssertionError("expected this to check: ${r.exceptionOrNull()?.message}")
        val t = r.getOrNull()?.getVariableType("p")
        assertTrue(
            t is MezzeType.TypeApplication && t.constructor == "Light",
            "the impl says `having 'reply = Light`, so that is what the call produces — " +
                "got `$t`. It read `'a` before: the impl was selected and then never " +
                "asked what its associated types are.",
        )
    }

    @Test fun `a qualified call CHECKS the impl's having binding`() {
        rejects(
            """
            $receives
            pub let p: Light = Receives.receive { target = Red, msg = 5 }
            $MAIN
            """,
            "T0215",
            "`'msg = LightMsg` for this impl, and `5` is an Int. This was ACCEPTED. " +
                "The code is T0215 rather than T0201 because the mismatch breaks the " +
                "solve before the receiver binds, so the undetermined-type check " +
                "reports first — the REFUSAL is the point here, and the message is " +
                "recorded as worth improving.",
        )
    }

    @Test fun `an operation may carry its own type parameter`() {
        accepts(
            """
            type Src = { n: Int }
            ability Show for 'a where { show: { it: 'a } -> Int }
            impl Show for Src where { show: { it: Src } -> Int = { it } -> 7 }
            ability Wrap for 'w where { wrap: { it: 'w, x: 'a } with Show 'a -> Int }
            impl Wrap for Src where {
              wrap: { it: Src, x: 'a } with Show 'a -> Int = { it, x } -> x.show {} + 1,
            }
            let s: Src = { n = 1 }
            pub let r: Int = s.wrap { x = s }
            $MAIN
            """,
            "BUG 42. `'a` belongs to the OPERATION — Rust's `fn wrap<A: Show>` — and the " +
                "per-op `with` clause is what introduces it, which is one of the three " +
                "spellings T0301 names. Every impl of it used to be V0417, so the ability " +
                "was uninhabitable.",
        )
    }

    @Test fun `an impl may NOT invent a type parameter the ability never offers`() {
        rejects(
            """
            type Src = { n: Int }
            ability Wrap for 'w where { wrap: { it: 'w, x: Int } -> Int }
            impl Wrap for Src where { wrap: { it: Src, x: 'z } -> Int = { it, x } -> 1 }
            $MAIN
            """,
            "V0417",
            "THE GUARD that keeps BUG 42's widening honest: the allowed variables are " +
                "read off the ABILITY's declaration, not the impl's, so a name the " +
                "contract never mentions is still refused — which is what stops a typo " +
                "in an impl from silently becoming a new quantified parameter.",
        )
    }

    @Test fun `an operation's own parameter must still be introduced somewhere`() {
        rejects(
            """
            type Src = { n: Int }
            ability Wrap for 'w where { wrap: { it: 'w, x: 'a } -> Int }
            impl Wrap for Src where { wrap: { it: Src, x: 'a } -> Int = { it, x } -> 1 }
            $MAIN
            """,
            "T0301",
            "The other half of the same guard, and it is DELIBERATE: a variable bound by " +
                "nothing is a \"magic tyvar\" generalized per call, which is the pattern " +
                "that bit codec's `Encode … Result 'out`. A per-op `with` constraint is " +
                "how an operation introduces one — and without a constraint the parameter " +
                "is opaque anyway, so nothing is lost.",
        )
    }
}
