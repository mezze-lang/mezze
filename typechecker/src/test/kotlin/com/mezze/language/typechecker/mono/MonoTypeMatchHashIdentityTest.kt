package com.mezze.language.typechecker.mono

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** No declaration registry in these tests: types are built directly, so "no
 *  declared variant by that name" is the TRUE answer, not a stub. */
private val noDeclaredVariants: (String, Set<String>) -> Boolean = { _, _ -> false }

/**
 * A5 regression (hash-driven identity): monomorphization's impl-target matcher
 * [typeMatchesTarget] must key nominal identity on the declaration content HASH,
 * not the bare constructor NAME — the same rule [Unification.unifyTypeApplications]
 * already enforces (see [com.mezze.language.typechecker.solving.NominalHashIdentityTest]).
 *
 * Before the fix, mono matched two `TypeApplication`s with `constructor == constructor`
 * alone, with NO hash veto. Two same-named types from different modules (a version
 * skew, or two `Widget`s) would match at the mono layer where unify would reject them —
 * dispatching an impl written for one declaration onto a value of the other.
 */
class MonoTypeMatchHashIdentityTest {

    private val hashA = Hash("aaaa000000000000000000000000000000000000000000000000000000000000")
    private val hashB = Hash("bbbb000000000000000000000000000000000000000000000000000000000000")

    private fun app(name: String, id: TypeIdentity) =
        MezzeType.TypeApplication(name, emptyList(), identity = id)

    @Test fun `same-named type applications with DIFFERENT hashes do not match`() {
        val concrete = app("Widget", TypeIdentity.Declared(hashA, "Widget"))
        val target = app("Widget", TypeIdentity.Declared(hashB, "Widget"))
        assertFalse(
            typeMatchesTarget(concrete, target, noDeclaredVariants),
            "two modules' `Widget` (different content hash) must NOT match at the mono layer — hash is authoritative",
        )
    }

    @Test fun `same declaration matches itself`() {
        val concrete = app("Widget", TypeIdentity.Declared(hashA, "Widget"))
        val target = app("Widget", TypeIdentity.Declared(hashA, "Widget"))
        assertTrue(typeMatchesTarget(concrete, target, noDeclaredVariants), "same declaration must match")
    }

    @Test fun `differently-named applications with the SAME hash match (rename-safe alias)`() {
        // Identity ADDS a match: a rename-safe alias (name differs, hash same).
        val concrete = app("Widget", TypeIdentity.Declared(hashA, "Widget"))
        val target = app("Gadget", TypeIdentity.Declared(hashA, "Gadget"))
        assertTrue(
            typeMatchesTarget(concrete, target, noDeclaredVariants),
            "equal content hash under differing names is the same declaration — a rename-safe alias must match",
        )
    }

    @Test fun `hashless builtin applications still match by name`() {
        // Pure builtins carry Builtin(name), not a hash — their reserved name IS
        // their identity, so a name match is correct here (no veto fires).
        val concrete = app("List", TypeIdentity.Builtin("List"))
        val target = app("List", TypeIdentity.Builtin("List"))
        assertTrue(typeMatchesTarget(concrete, target, noDeclaredVariants), "hashless builtin TypeApps match by reserved name")
    }
}
