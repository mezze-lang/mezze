package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.MezzeType.*
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.TypeVar
import com.mezze.language.typedast.Hash
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.solving.identityVerdict
import com.mezze.language.typechecker.solving.IdentityVerdict
import com.mezze.language.typechecker.solving.unify
import com.mezze.language.typechecker.solving.UnificationResult
import com.mezze.language.typechecker.mono.typeMatchesTarget as monoTypeMatchesTarget
import com.mezze.language.typechecker.mono.unify as monoUnify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Move-4 (#24) — the DIFFERENTIAL GATE for the structural matchers.
 *
 * The audit's core finding is that ≥5 separate deciders answer "are these two
 * types the same / does this type match this target?" and they historically
 * DISAGREED — some keyed on type identity (content hash), some on rendered names
 * or bare structure. The L-xx fixes routed the load-bearing ones through
 * [identityVerdict] so a DISTINCT declaration (same display name, different content
 * hash — the newtype-two-faces / cross-module-collision hazard) is rejected
 * everywhere.
 *
 * This gate LOCKS THAT IN: it drives the callable matchers over a matrix of
 * type-pairs — deliberately including the adversarial same-name / distinct-hash
 * cases — and asserts the single load-bearing invariant every matcher must honor:
 *
 *     if identityVerdict(a, b) == DISTINCT  then  NO matcher may report a match.
 *
 * A future refactor (Move-5) that collapses these onto one `Unification.unify`
 * must keep this green; a regression that reintroduces a name-only decider breaks
 * it here with the exact offending pair. (`TypedASTCompiler.typeMatchesTarget` is
 * `private` and not directly callable from a test; the callable trio —
 * MonoTypeOps.typeMatchesTarget, MonoTypeOps.unify, Unification.unify — covers the
 * identity-veto invariant that governs it too.)
 */
class StructuralMatcherDifferentialGateTest {

    // Distinct 64-hex-char hashes, one per seed letter. Each known seed maps to a
    // UNIQUE hex digit so different seeds are guaranteed DISTINCT (and every char is
    // valid hex, 0-9a-f). Fixed table — no hashCode collisions.
    private val seedToHex = mapOf(
        "a" to '1', "b" to '2', "c" to '3', "d" to '4',
        "e" to '5', "f" to '6', "g" to '7', "h" to '8',
    )
    private fun hash(seed: String): Hash {
        val d = seedToHex[seed] ?: error("unknown hash seed '$seed' — add it to seedToHex")
        return Hash(d.toString().repeat(64))
    }

    private fun declaredVariant(name: String, hashSeed: String, ctors: List<String>): VariantType =
        VariantType(
            constructors = ctors.associateWith { null },
            identity = TypeIdentity.Declared(hash(hashSeed), name)
        )

    private fun declaredNewtype(name: String, hashSeed: String, wrapped: MezzeType): NewtypeType =
        NewtypeType(
            name = name,
            constructor = name,
            wrappedType = wrapped,
            identity = TypeIdentity.Declared(hash(hashSeed), name)
        )

    private fun declaredRecord(name: String, hashSeed: String, fields: Map<String, MezzeType>): RecordType =
        RecordType(fields = fields, identity = TypeIdentity.Declared(hash(hashSeed), name))

    // The three callable matchers, each reduced to a Boolean "does A match B?".
    private fun monoTMT(a: MezzeType, b: MezzeType): Boolean = monoTypeMatchesTarget(a, b)
    private fun monoUn(a: MezzeType, b: MezzeType): Boolean = monoUnify(a, b, mutableMapOf())
    private fun solverUn(a: MezzeType, b: MezzeType): Boolean =
        unify(a, b, DeclarationRegistry.EMPTY) is UnificationResult.Success

    /**
     * The load-bearing invariant: for any pair the identity core calls DISTINCT,
     * every matcher must refuse. Runs both directions (matchers are directional).
     */
    private fun assertDistinctRejectedEverywhere(a: MezzeType, b: MezzeType, why: String) {
        // Precondition: the identity core must actually call these DISTINCT.
        val idA = (a as? VariantType)?.identity ?: (a as? NewtypeType)?.identity ?: (a as? RecordType)?.identity
        val idB = (b as? VariantType)?.identity ?: (b as? NewtypeType)?.identity ?: (b as? RecordType)?.identity
        if (idA != null && idB != null) {
            assertEquals(IdentityVerdict.DISTINCT, identityVerdict(idA, idB),
                "test setup: $why should be DISTINCT by identityVerdict")
        }
        for ((x, y, dir) in listOf(Triple(a, b, "A→B"), Triple(b, a, "B→A"))) {
            assertFalse(monoTMT(x, y), "MonoTypeOps.typeMatchesTarget matched DISTINCT $why ($dir) — $x vs $y")
            assertFalse(monoUn(x, y), "MonoTypeOps.unify matched DISTINCT $why ($dir) — $x vs $y")
            assertFalse(solverUn(x, y), "Unification.unify succeeded on DISTINCT $why ($dir) — $x vs $y")
        }
    }

    @Test
    fun `distinct same-named variants are rejected by every matcher`() {
        // The classic cross-module hazard: two `Color`s with the SAME ctor set but
        // DIFFERENT content hashes are DISTINCT declarations and must never match.
        val colorA = declaredVariant("Color", "a", listOf("Red", "Green", "Blue"))
        val colorB = declaredVariant("Color", "b", listOf("Red", "Green", "Blue"))
        assertDistinctRejectedEverywhere(colorA, colorB, "two distinct `Color` variants")
    }

    @Test
    fun `distinct same-named newtypes over the same wrapped type are rejected everywhere`() {
        // newtype-two-faces: `Id` in module A vs `Id` in module B, both wrap Int.
        val idA = declaredNewtype("Id", "c", IntType)
        val idB = declaredNewtype("Id", "d", IntType)
        assertDistinctRejectedEverywhere(idA, idB, "two distinct `Id` newtypes over Int")
    }

    @Test
    fun `distinct same-named records with identical fields are rejected everywhere`() {
        val pA = declaredRecord("Point", "e", mapOf("x" to IntType, "y" to IntType))
        val pB = declaredRecord("Point", "f", mapOf("x" to IntType, "y" to IntType))
        assertDistinctRejectedEverywhere(pA, pB, "two distinct `Point` records")
    }

    @Test
    fun `a sub-variant does not match a distinct superset variant`() {
        // The L-36/L-55/L-56 subset-key hole: `| A |` (module X) must NOT dispatch a
        // target `| A, B, C |` (module Y) when their declarations are DISTINCT.
        val small = declaredVariant("Small", "g", listOf("A"))
        val big = declaredVariant("Big", "h", listOf("A", "B", "C"))
        // These have different names AND hashes → DISTINCT; subset must not match.
        for ((x, y, dir) in listOf(Triple(small, big, "small→big"), Triple(big, small, "big→small"))) {
            assertFalse(monoTMT(x, y), "MonoTypeOps.typeMatchesTarget subset-matched distinct variants ($dir)")
        }
    }

    @Test
    fun `distinct same-named type-applications are rejected by every matcher`() {
        // Two `Widget 'a` from different modules (same constructor name, different
        // content hash) are DISTINCT declarations — mono unify's TypeApp arm must
        // not seed one for the other.
        val wA = TypeApplication(constructor = "Widget", args = listOf(IntType),
            identity = TypeIdentity.Declared(hash("a"), "Widget"))
        val wB = TypeApplication(constructor = "Widget", args = listOf(IntType),
            identity = TypeIdentity.Declared(hash("b"), "Widget"))
        assertEquals(IdentityVerdict.DISTINCT, identityVerdict(wA.identity, wB.identity),
            "test setup: two distinct-hash Widget TypeApps should be DISTINCT")
        for ((x, y, dir) in listOf(Triple(wA, wB, "A→B"), Triple(wB, wA, "B→A"))) {
            assertFalse(monoTMT(x, y), "MonoTypeOps.typeMatchesTarget matched DISTINCT Widget TypeApps ($dir)")
            assertFalse(monoUn(x, y), "MonoTypeOps.unify matched DISTINCT Widget TypeApps ($dir)")
            assertFalse(solverUn(x, y), "Unification.unify matched DISTINCT Widget TypeApps ($dir)")
        }
    }

    @Test
    fun `SAME-identity pairs still match — the gate does not over-reject`() {
        // Sanity: identical declarations (same hash) MUST still match, or the veto
        // would break legitimate dispatch. This guards against a future over-fix.
        val colorA = declaredVariant("Color", "a", listOf("Red", "Green", "Blue"))
        val colorAgain = declaredVariant("Color", "a", listOf("Red", "Green", "Blue"))
        assertEquals(IdentityVerdict.SAME, identityVerdict(colorA.identity, colorAgain.identity),
            "same hash must be SAME")
        assertTrue(monoTMT(colorA, colorAgain), "MonoTypeOps.typeMatchesTarget rejected identical variants")
        assertTrue(solverUn(colorA, colorAgain), "Unification.unify rejected identical variants")

        val idA = declaredNewtype("Id", "c", IntType)
        val idAgain = declaredNewtype("Id", "c", IntType)
        assertTrue(solverUn(idA, idAgain), "Unification.unify rejected identical newtypes")
    }

    @Test
    fun `primitives match themselves and not each other across every matcher`() {
        assertTrue(solverUn(IntType, IntType))
        assertTrue(monoTMT(IntType, IntType))
        assertFalse(solverUn(IntType, StringType), "Unification.unify matched Int vs Str")
        assertFalse(monoTMT(IntType, StringType), "MonoTypeOps matched Int vs Str")
        assertFalse(monoUn(IntType, StringType), "MonoTypeOps.unify matched Int vs Str")
    }
}
