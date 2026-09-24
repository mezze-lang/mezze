package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.Loc
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.constraints.MethodResolveBucket
import com.mezze.language.typechecker.constraints.ResolutionCandidate
import com.mezze.language.typechecker.constraints.ResolutionKind
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * S7.1 (#369) — the no-name-dispatch CAPSTONE guard.
 *
 * The PERMANENT, always-on GUARD (unit) — "a NAME is never identity" at the
 * DISPATCH layer. Constructs inherent-method candidates that share a NAME but
 * differ in HASH and asserts the receiver does NOT dispatch to the other
 * declaration's impl (name equality must not cause dispatch equality); and
 * candidates that differ in NAME but share a HASH and asserts they DO dispatch to
 * the same impl (a rename-safe alias). The unification half of this property is
 * locked by [NominalHashIdentityTest]; this locks the post-solve dispatcher.
 *
 * (The #369 corpus PROOF — which flipped a name-fallback measurement probe on and
 * asserted 0 forbidden firings across the fuzzer corpus — proved 0 fallbacks and
 * was retired with the probe scaffolding in #380. This guard is what remains.)
 */
class NoNameDispatchCapstoneTest {

    private val hashA = Hash("aaaa000000000000000000000000000000000000000000000000000000000000")
    private val hashB = Hash("bbbb000000000000000000000000000000000000000000000000000000000000")
    private val opHashWidgetA = Hash("0011000000000000000000000000000000000000000000000000000000000000")
    private val opHashWidgetB = Hash("0022000000000000000000000000000000000000000000000000000000000000")

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    // ---------------------------------------------------------------
    // Permanent GUARD — name is not identity at the dispatch layer
    // ---------------------------------------------------------------

    /**
     * Two same-named `Widget` declarations from different modules carry different
     * declaration hashes. A `Widget`-hashA receiver must dispatch to the hashA
     * impl ONLY — never cross-dispatch to the hashB impl just because the target
     * type NAME is also "Widget".
     */
    @Test
    fun `same-name different-hash inherent impls do not cross-dispatch`() {
        val receiverA = MezzeType.RecordType(
            mapOf("n" to MezzeType.IntType),
            identity = TypeIdentity.Declared(hashA, "Widget"),
        )
        // Two candidates, SAME displayName "Widget", DIFFERENT target hashes+ops.
        val candA = ResolutionCandidate(
            opHash = opHashWidgetA,
            kind = ResolutionKind.Inherent,
            targetTypeIdentity = TypeIdentity.Declared(hashA, "Widget"),
            displayName = "Widget",
        )
        val candB = ResolutionCandidate(
            opHash = opHashWidgetB,
            kind = ResolutionKind.Inherent,
            targetTypeIdentity = TypeIdentity.Declared(hashB, "Widget"),
            displayName = "Widget",
        )
        val loc = Loc("cap.mz", 1, 1, 1, 8)
        val bucket = MethodResolveBucket(loc, "render", receiverA, listOf(candA, candB))

        val resolutions = DispatchResolver.resolveDispatch(
            buckets = listOf(bucket),
            substitution = Substitution.empty,
            methodCallResolutions = emptyMap(),
            typingContext = TypingContext.empty,
        )

        val resolution = resolutions[loc]
        assertNotNull(resolution, "a concrete Widget-hashA receiver resolves an inherent impl")
        assertEquals(
            opHashWidgetA, resolution.opHash,
            "must dispatch to the hashA impl by HASH — the same-named hashB impl must NOT win by name",
        )
    }

    /**
     * Two DIFFERENTLY-named declarations (`Widget`, `Gadget`) that share a hash are
     * the SAME declaration under a rename-safe alias. A receiver carrying that hash
     * must dispatch to that impl even though its own name differs from the
     * candidate's displayName — identity is the hash, not the string.
     */
    @Test
    fun `different-name same-hash inherent impl dispatches (rename-safe alias)`() {
        // Receiver labeled "Gadget" but carrying hashA.
        val receiver = MezzeType.RecordType(
            mapOf("n" to MezzeType.IntType),
            identity = TypeIdentity.Declared(hashA, "Gadget"),
        )
        // Candidate labeled "Widget" but targeting the SAME hashA.
        val cand = ResolutionCandidate(
            opHash = opHashWidgetA,
            kind = ResolutionKind.Inherent,
            targetTypeIdentity = TypeIdentity.Declared(hashA, "Widget"),
            displayName = "Widget",
        )
        val loc = Loc("cap.mz", 2, 1, 2, 8)
        val bucket = MethodResolveBucket(loc, "render", receiver, listOf(cand))

        val resolutions = DispatchResolver.resolveDispatch(
            buckets = listOf(bucket),
            substitution = Substitution.empty,
            methodCallResolutions = emptyMap(),
            typingContext = TypingContext.empty,
        )

        val resolution = resolutions[loc]
        assertNotNull(
            resolution,
            "a same-hash impl dispatches even though the display names differ (rename-safe alias)",
        )
        assertEquals(opHashWidgetA, resolution.opHash, "dispatch keys on the shared HASH, not the name")
    }

    /**
     * A receiver whose hash matches NEITHER same-named candidate must not dispatch
     * to either by name. (No hash match ⇒ no Resolution — never a name-based guess.)
     */
    @Test
    fun `unmatched-hash receiver does not name-dispatch to a same-named impl`() {
        val hashC = Hash("cccc000000000000000000000000000000000000000000000000000000000000")
        val receiver = MezzeType.RecordType(
            mapOf("n" to MezzeType.IntType),
            identity = TypeIdentity.Declared(hashC, "Widget"),
        )
        val cand = ResolutionCandidate(
            opHash = opHashWidgetA,
            kind = ResolutionKind.Inherent,
            targetTypeIdentity = TypeIdentity.Declared(hashA, "Widget"),
            displayName = "Widget",
        )
        val loc = Loc("cap.mz", 3, 1, 3, 8)
        val bucket = MethodResolveBucket(loc, "render", receiver, listOf(cand))

        val resolutions = DispatchResolver.resolveDispatch(
            buckets = listOf(bucket),
            substitution = Substitution.empty,
            methodCallResolutions = emptyMap(),
            typingContext = TypingContext.empty,
        )

        assertNull(
            resolutions[loc],
            "receiver hashC shares the NAME 'Widget' with the hashA impl but not its hash — must NOT dispatch",
        )
    }

    // ---- the ability the SOURCE named at the call site ----

    /**
     * `Weigh.rank { it = x }` names its ability, and that is the answer — not a
     * hint to be weighed against the other ability that also declares `rank`.
     *
     * This was the last deferral in the S2.2 pass. The qualifier is not visible
     * on the receiver by the time a bucket is recorded: `Ability.op { it = … }`
     * desugars to a method call on the `it` ARGUMENT, so the receiver is `x` and
     * never `Weigh` — a narrowing that looked there fired zero times. The name is
     * resolved to its declaration's HASH at generation, where the scope to
     * resolve it in is still known, and the bucket carries that.
     */
    @Test
    fun `an explicitly qualified call dispatches to the named ability, not the other one`() {
        val receiver = MezzeType.RecordType(
            mapOf("kg" to MezzeType.IntType),
            identity = TypeIdentity.Declared(hashA, "Item"),
        )
        val price = ResolutionCandidate(
            opHash = opHashWidgetA, kind = ResolutionKind.Ability, displayName = "Price",
        )
        val weigh = ResolutionCandidate(
            opHash = opHashWidgetB, kind = ResolutionKind.Ability, displayName = "Weigh",
        )
        val loc = Loc("cap.mz", 4, 1, 4, 8)

        val resolution = DispatchResolver.resolveDispatch(
            buckets = listOf(
                MethodResolveBucket(loc, "rank", receiver, listOf(price, weigh),
                    explicitAbilityHash = opHashWidgetB)
            ),
            substitution = Substitution.empty,
            methodCallResolutions = emptyMap(),
            typingContext = TypingContext.empty,
        )[loc]

        assertNotNull(resolution, "a qualified call is not ambiguous — it must resolve, not defer")
        assertEquals(
            opHashWidgetB, resolution.opHash,
            "must dispatch to the ability the source NAMED, not to the other one declaring `rank`",
        )
    }

    /**
     * The control. Without the qualifier the SAME two candidates are a genuine
     * same-op collision, and this pass defers rather than guessing — so the test
     * above is not passing merely because one candidate happens to win.
     */
    @Test
    fun `the same two candidates UNQUALIFIED defer instead of guessing`() {
        val receiver = MezzeType.RecordType(
            mapOf("kg" to MezzeType.IntType),
            identity = TypeIdentity.Declared(hashA, "Item"),
        )
        val loc = Loc("cap.mz", 5, 1, 5, 8)

        val resolutions = DispatchResolver.resolveDispatch(
            buckets = listOf(
                MethodResolveBucket(
                    loc, "rank", receiver,
                    listOf(
                        ResolutionCandidate(opHashWidgetA, ResolutionKind.Ability, displayName = "Price"),
                        ResolutionCandidate(opHashWidgetB, ResolutionKind.Ability, displayName = "Weigh"),
                    ),
                )
            ),
            substitution = Substitution.empty,
            methodCallResolutions = emptyMap(),
            typingContext = TypingContext.empty,
        )

        assertNull(
            resolutions[loc],
            "two distinct ability ops sharing `rank` with nothing to choose between them must " +
                "DEFER to the ladder, not pick one",
        )
    }
}
