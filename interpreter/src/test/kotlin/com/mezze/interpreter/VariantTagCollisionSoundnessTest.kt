package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Soundness probes for [com.mezze.interpreter.values.MezzeVariant.TagRegistry].
 *
 * Variants are dispatched at runtime by an integer tag computed deterministically
 * from the constructor's simple name (`name.hashCode() & 0x7FFFFFFF`). The
 * registry is global: two distinct variant types that both declare a constructor
 * named `Ping` produce variant values that share the same tag at the bit level.
 * Runtime match arms compare only the tag, so the typechecker is the sole guard
 * against a value of one variant type firing a match arm of another.
 *
 * Each probe below tries to construct such a cross-type leak through a different
 * route. The expected outcome for each is **typechecker rejection** — if any
 * probe instead typechecks AND produces the wrong runtime value, the typechecker
 * has a soundness hole that exposes the same bug class as #246 (silent dispatch
 * misroute) at the variant level.
 *
 * These tests pass two ways:
 *   1. Typechecker rejects the program (assertThrows).
 *   2. Program runs and produces the *correct* value (assertEquals on Right answer).
 *
 * A probe FAILS if it runs to completion and returns the *wrong* value — that's
 * the soundness hole we're hunting.
 */
class VariantTagCollisionSoundnessTest {

    /**
     * Returns the runtime value, or a [Rejected] marker carrying the
     * rejection message when the typechecker/parser refused the program.
     * Rejection is the soundness-preserving outcome; the test contract
     * fails only if the program runs AND returns a value indicating
     * cross-type leak.
     */
    data class Rejected(val message: String)

    private fun runOrReject(source: String): Any? = try {
        MezzeTestHelper.run(source)
    } catch (e: Throwable) {
        Rejected(e.message ?: e::class.simpleName ?: "rejected")
    }

    /**
     * Baseline: two variant types each with `Ping`. Used in their own match
     * arms — must always work. Confirms the test harness reaches the runtime.
     *
     * Note: each `match` site fixes the scrutinee's type by row-poly inference
     * from the constructors it lists. `from_t1` sees `Ping | Pong` so its
     * arg type is the `Tag1` row; `from_t2` sees `Ping | Pang` (the `Tag2`
     * row). Both sites work as long as the typechecker keeps them separate.
     */
    @Test
    fun `baseline — same constructor name in two types, each used in its own match`() {
        val source = """
            let from_t1 = {t} -> match t is
                Ping -> "t1-ping"
                Pong -> "t1-pong"

            let from_t2 = {t} -> match t is
                Ping -> "t2-ping"
                Pang -> "t2-pang"

            let a = from_t1 { t = Ping }
            let b = from_t2 { t = Ping }
            a == "t1-ping" && b == "t2-ping"
        """.trimIndent()
        val result = runOrReject(source)
        if (result is Rejected) {
            fail<Unit>("Baseline rejected by typechecker — test harness can't reach the soundness probes: ${result.message}")
        }
        assertEquals(true, result)
    }

    /**
     * Direct attack: bind a `Tag2.Ping` to a variable, then pass it to a
     * function whose parameter is annotated `Tag1`. If the typechecker
     * accepts this, the runtime match in `from_t1` will see a `Ping` tag
     * and fire the `t1-ping` arm — visible misroute.
     */
    @Test
    fun `direct annotated injection — Tag2 value into Tag1 sink`() {
        val source = """
            type Tag1 = | Ping, Pong |
            type Tag2 = | Ping, Pang |

            let sink_t1 = {t: Tag1} -> match t is
                Ping -> "t1-ping"
                Pong -> "t1-pong"

            let v: Tag2 = Ping
            sink_t1 { t = v }
        """.trimIndent()
        val result = runOrReject(source)
        // Either typechecker rejects (null), or — if it incorrectly accepts —
        // we must at least not produce "t1-ping" from a Tag2 value.
        // If "t1-ping" comes back, that's the soundness hole.
        assertNotEquals("t1-ping", result,
            "Soundness hole: Tag2.Ping leaked into Tag1 match site")
    }

    /**
     * Polymorphic identity laundering: pass a `Tag2.Ping` through `id`,
     * then immediately match as `Tag1`. Tests whether row polymorphism
     * widens `Tag2` to include `Tag1`'s constructors.
     */
    @Test
    fun `polymorphic identity laundering`() {
        val source = """
            type Tag1 = | Ping, Pong |
            type Tag2 = | Ping, Pang |

            let id = {x} -> x

            let v: Tag2 = Ping
            let laundered: Tag1 = id { x = v }
            match laundered is
                Ping -> "fired-tag1-ping"
                Pong -> "fired-tag1-pong"
        """.trimIndent()
        val result = runOrReject(source)
        assertNotEquals("fired-tag1-ping", result,
            "Soundness hole: id laundered Tag2 into Tag1")
    }

    /**
     * Record-field exit: stash a Tag2 inside a record, then read it back
     * with a Tag1 annotation. Forces the typechecker to commit to a
     * field type — if it lets the read coerce, we leak.
     */
    @Test
    fun `record-field laundering`() {
        val source = """
            type Tag1 = | Ping, Pong |
            type Tag2 = | Ping, Pang |

            let v: Tag2 = Ping
            let rec = { box = v }
            let back: Tag1 = rec.box
            match back is
                Ping -> "fired-tag1-ping"
                Pong -> "fired-tag1-pong"
        """.trimIndent()
        val result = runOrReject(source)
        assertNotEquals("fired-tag1-ping", result,
            "Soundness hole: record-field read coerced Tag2 to Tag1")
    }

    /**
     * Pattern reuse — match a `Tag1` value but try to bind one arm using
     * a constructor that only exists in `Tag2`. If the parser/typechecker
     * accepts a `Pang` arm for a Tag1 scrutinee, runtime is broken before
     * we even leak a value across.
     */
    @Test
    fun `cross-type constructor in match arm`() {
        val source = """
            type Tag1 = | Ping, Pong |
            type Tag2 = | Ping, Pang |

            let t1: Tag1 = Pong
            match t1 is
                Ping -> "ping"
                Pang -> "pang"
        """.trimIndent()
        val result = runOrReject(source)
        // We don't care which arm fires if the typechecker accepts —
        // we only care that the runtime never returns "pang" from a Tag1 value.
        assertNotEquals("pang", result,
            "Soundness hole: Tag2 constructor in Tag1 match")
    }

    /**
     * Open-variant route: Mezze allows row-polymorphic variants. Try to
     * coerce `Tag2` through an open variant `| Ping | 'rest |` and back
     * into a `Tag1` annotation. Row poly is supposed to be safe — this
     * locks in that supposition.
     */
    @Test
    fun `open-variant row-poly laundering`() {
        val source = """
            type Tag1 = | Ping, Pong |
            type Tag2 = | Ping, Pang |

            let pass_through = {x} -> x

            let v: Tag2 = Ping
            let result: Tag1 = pass_through { x = v }
            match result is
                Ping -> "fired-tag1-ping"
                Pong -> "fired-tag1-pong"
        """.trimIndent()
        val result = runOrReject(source)
        assertNotEquals("fired-tag1-ping", result,
            "Soundness hole: row-poly widened Tag2 to Tag1")
    }

    /**
     * Effect-op result coercion — #231 fixed SuspendType leaks for effect
     * op returns. This probe locks in that an effect op returning Tag2
     * cannot have its return annotated as Tag1.
     */
    @Test
    fun `effect-op return type laundering`() {
        val source = """
            type Tag1 = | Ping, Pong |
            type Tag2 = | Ping, Pang |

            effect Producer where {
              make: {} -> Tag2 <Producer>
            }
            impl Producer for ProduceTag2 where {
              make = {} -> Ping
            }

            perform ProduceTag2 in do
              let v: Tag1 = Producer.make {}
              match v is
                Ping -> "fired-tag1-ping"
                Pong -> "fired-tag1-pong"
        """.trimIndent()
        val result = runOrReject(source)
        assertNotEquals("fired-tag1-ping", result,
            "Soundness hole: effect-op return type laundered Tag2 to Tag1")
    }

    /**
     * Anonymous open-variant route — no named `type` declarations, so the
     * "duplicate constructor name" check doesn't fire. Constructors flow
     * through row-polymorphic positions and only get fixed at match sites.
     * If row poly can drop a `Pang` tag and let a `Ping` value through to
     * a closed-row match expecting only `{Ping, Pong}`, we'd leak.
     */
    @Test
    fun `anonymous open-variant cross-row leak`() {
        val source = """
            # Two values of structurally distinct anonymous rows that both have Ping.
            let from_row_a = {t} -> match t is
                Ping -> "row-a-ping"
                Pong -> "row-a-pong"

            # First call narrows the parameter row to {Ping, Pong} via the arms.
            let a = from_row_a { t = Ping }

            # Try to also pass Pang to it — typechecker should reject because
            # from_row_a's row was already closed by inference at its first use.
            let b = from_row_a { t = Pang }
            a == "row-a-ping" && b == "row-a-pong"
        """.trimIndent()
        val result = runOrReject(source)
        // We don't care which path was taken — either rejected, or runtime
        // produced a value that doesn't claim Pang was a Pong.
        // The dangerous outcome is `true` paired with Pang silently being
        // misrouted to row-a-pong via tag collision. (No same-tag here
        // so it's mostly checking row-poly closure.)
        assertNotEquals(true, result,
            "Soundness hole: Pang fired arm in {Ping, Pong} match")
    }

    /**
     * Subset-key ability-dispatch leak (ledger L-36 / L-55 / L-56, fixed
     * 2026-09-10). `typeMatchesTarget`'s VariantType-vs-VariantType arm used to
     * accept a receiver whose constructor set was a proper SUBSET of the impl
     * target's — with no identity guard. So a value of `Small = | A |` could
     * dispatch an `impl` written for a DISTINCT declaration `Big = | A, B, C |`,
     * running `Big`'s method body against a `Small` value.
     *
     * The fix adds the `identityVerdict == DISTINCT -> false` veto (the same one
     * the solver's `unifyVariants` and the sibling TypeApp/Record arms carry) in
     * both the compiler (`TypedASTCompiler.typeMatchesTarget`) and the mono mirror
     * (`MonoTypeOps.typeMatchesTarget`). Two DISTINCT declared variants no longer
     * cross-dispatch; the structural subset path still serves genuine
     * identity-less (cross-module stripped) receivers.
     *
     * Soundness-preserving outcomes: typechecker rejection, OR the value routes to
     * ITS OWN impl (returns "small"). The hole would show as "big" — Big's body
     * fired on a Small value.
     */
    @Test
    fun `subset-variant does not dispatch a distinct superset impl`() {
        val source = """
            ability Describe for 'a where {
              describe: {} -> Str
            }

            type Small = | A |
            type Big = | A, B, C |

            impl Describe for Small where {
              describe = {} -> "small"
            }
            impl Describe for Big where {
              describe = {} -> "big"
            }

            let v: Small = A
            v.describe {}
        """.trimIndent()
        val result = runOrReject(source)
        // v is a Small; it must dispatch Small's impl ("small") or be rejected.
        // "big" means Big's impl fired on a Small value — the subset-key leak.
        assertNotEquals("big", result,
            "Soundness hole: Small value dispatched the distinct Big impl via subset-key match")
        assertTrue(result is Rejected || result == "small",
            "Expected Small to route to its own impl or be rejected; got: $result")
    }

    /**
     * Match-inference variant merge launder (ledger L-01, fixed 2026-09-10).
     * ConstraintSolver.mergeVariantConstructors unioned two variants'
     * constructor maps in a match/both-open context with NO identity guard, so a
     * value of one declared variant could have a DISTINCT declared variant's
     * constructors merged into its row var — laundering a foreign constructor
     * into a match site. Fix: gate the merge on identityVerdict != DISTINCT
     * (mirrors unifyVariants). Soundness-preserving outcome: rejection, or the
     * value routes only within its own type. The hole would let a Tag2-only
     * constructor fire on a Tag1 value.
     */
    @Test
    fun `match inference does not merge distinct declared variants`() {
        val source = """
            type Tag1 = | Ping, Pong |
            type Tag2 = | Zip, Zap |

            let sink = { t: Tag1 } -> match t is
                Ping -> "t1-ping"
                Pong -> "t1-pong"

            let v: Tag2 = Zip
            sink { t = v }
        """.trimIndent()
        val result = runOrReject(source)
        // A Tag2 value must never satisfy a Tag1 sink. Rejection is correct; the
        // dangerous outcome is any Tag1 arm string coming back from a Tag2 value.
        assertTrue(result is Rejected || (result != "t1-ping" && result != "t1-pong"),
            "Soundness hole: distinct-variant merge laundered Tag2 into Tag1 match")
    }

    /**
     * Stdlib-vs-user-type clash. The stdlib defines `Some` (Option). If a
     * user declared `type Foo = | Some, Bar |`, would the typechecker
     * accept it, and if so does a `Some` value of `Foo` match an
     * `Option`-typed scrutinee's `Some` arm?
     */
    @Test
    fun `stdlib Some constructor reuse blocked`() {
        val source = """
            type Foo = | Some, Bar |
            let v: Foo = Some
            match v is
                Some -> "foo-some"
                Bar -> "foo-bar"
        """.trimIndent()
        val result = runOrReject(source)
        // We aren't testing the leak here — just verifying the language
        // either rejects reusing `Some` outside std::option, or accepts it
        // and routes correctly within its own type. The dangerous outcome
        // would be silently producing a value that escaped its declared
        // type (e.g. a runtime crash claiming Option saw a Bar).
        // For now we just assert the typechecker has SOMETHING to say.
        assertTrue(result is Rejected || result == "foo-some",
            "Unexpected outcome for stdlib constructor reuse: $result")
    }

    /**
     * The collision the three documented guards do NOT cover.
     *
     * [com.mezze.language.ast.BuiltinTypes.computeVariantTag] is
     * `ctorName.hashCode() and 0x7FFFFFFF`, and the protections named in its
     * KDoc — duplicate-constructor rejection, stdlib shadowing, row closure —
     * all guard constructors that share a NAME. Two DIFFERENT names that share
     * a hash are guarded by nothing, and Java string hashes collide trivially:
     * `"Aa".hashCode() == "BB".hashCode() == 2112`.
     *
     * Both constructors live in ONE type here, so no cross-type rule applies
     * and the typechecker has no reason to object. If the runtime dispatches on
     * the tag alone, `BB` fires the `Aa` arm.
     */
    @Test
    fun `two constructors of ONE type whose names hash-collide must not share an arm`() {
        // Same-hash pairs, verified: "Aa"/"BB" and "Ea"/"FB" both hash to the
        // same value as each other.
        val source = """
            type Collide = | Aa, BB |

            let describe = {c: Collide} -> match c is
                Aa -> "first"
                BB -> "second"

            describe { c = BB }
        """.trimIndent()
        val result = runOrReject(source)
        if (result is Rejected) return   // rejection is a sound outcome
        assertEquals("second", result,
            "`BB` fired the `Aa` arm: the runtime tag is a bare String.hashCode() and " +
                "\"Aa\" and \"BB\" collide. Both constructors are in ONE type, so none of " +
                "the cross-type guards apply.")
    }

    /** The same collision reached through construction rather than a match arm. */
    @Test
    fun `hash-colliding constructors are distinguishable by equality`() {
        val source = """
            type Collide = | Ea, FB |

            let x: Collide = Ea
            let y: Collide = FB
            x == y
        """.trimIndent()
        val result = runOrReject(source)
        if (result is Rejected) return
        assertEquals(false, result,
            "`Ea` and `FB` compared EQUAL: their runtime tags collide (both 2236).")
    }
}
