package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

/**
 * #280/F8 + #278/F0 regression: nominal type identity in unification must key on
 * the declaration content HASH, not the bare constructor/type NAME.
 *
 * Two distinct declarations that happen to share a name (two modules' `Widget`,
 * or a version skew where a decl's hash changed) carry DIFFERENT `typeHash`
 * values. Previously `unifyTypeApplications` matched on `constructor` name FIRST
 * (the hash clause only ever ADDED matches), so same-named distinct types
 * unified — module A's opaque type silently accepted where B's was required.
 * The fix: when BOTH sides carry a hash, the hash DECIDES.
 */
class NominalHashIdentityTest {

    private val hashA = Hash("aaaa000000000000000000000000000000000000000000000000000000000000")
    private val hashB = Hash("bbbb000000000000000000000000000000000000000000000000000000000000")

    // ---- F8: TypeApplication (the LIVE path — user ADTs/newtypes are TypeApplication) ----

    @Test fun `same-named type applications with DIFFERENT hashes do not unify`() {
        val app1 = MezzeType.TypeApplication("Widget", emptyList(), typeHash = hashA)
        val app2 = MezzeType.TypeApplication("Widget", emptyList(), typeHash = hashB)
        val result = unify(app1, app2)
        assertIs<UnificationResult.Failure>(
            result,
            "two distinct declarations named 'Widget' (different typeHash) must NOT unify",
        )
    }

    @Test fun `same-named type applications with the SAME hash unify`() {
        val app1 = MezzeType.TypeApplication("Widget", emptyList(), typeHash = hashA)
        val app2 = MezzeType.TypeApplication("Widget", emptyList(), typeHash = hashA)
        val result = unify(app1, app2)
        assertIs<UnificationResult.Success>(
            result,
            "the same declaration (equal typeHash) must unify with itself",
        )
    }

    @Test fun `same-named type applications with NO hash fall back to name (builtins)`() {
        // Builtins carry no typeHash; name is the only identity — must still unify.
        val app1 = MezzeType.TypeApplication("List", listOf(MezzeType.IntType))
        val app2 = MezzeType.TypeApplication("List", listOf(MezzeType.IntType))
        val result = unify(app1, app2)
        assertIs<UnificationResult.Success>(result, "builtins with no hash unify by name")
    }

    // ---- F0: NewtypeType (defensive — currently near-unreachable, but hardened) ----

    @Test fun `same-named newtypes with DIFFERENT hashes do not unify`() {
        val n1 = MezzeType.NewtypeType("Token", "Token", MezzeType.StringType, typeHash = hashA)
        val n2 = MezzeType.NewtypeType("Token", "Token", MezzeType.StringType, typeHash = hashB)
        val result = unify(n1, n2)
        assertIs<UnificationResult.Failure>(
            result,
            "two modules' opaque `newtype Token = Token Str` (different typeHash) must NOT unify",
        )
    }

    @Test fun `same-named newtypes with the SAME hash unify`() {
        val n1 = MezzeType.NewtypeType("Token", "Token", MezzeType.StringType, typeHash = hashA)
        val n2 = MezzeType.NewtypeType("Token", "Token", MezzeType.StringType, typeHash = hashA)
        val result = unify(n1, n2)
        assertIs<UnificationResult.Success>(result, "the same newtype declaration unifies with itself")
    }

    @Test fun `newtypes with NO hash fall back to name`() {
        val n1 = MezzeType.NewtypeType("Meters", "Meters", MezzeType.IntType)
        val n2 = MezzeType.NewtypeType("Meters", "Meters", MezzeType.IntType)
        val result = unify(n1, n2)
        assertIs<UnificationResult.Success>(result, "hashless newtypes unify by name (legacy behavior)")
    }
}
