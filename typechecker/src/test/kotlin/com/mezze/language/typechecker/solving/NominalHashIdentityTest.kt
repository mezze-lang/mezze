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

    // ---- F8: TypeApplication ----
    // NOTE: the hash-first REJECT for TypeApplication was reverted — `typeHash` is
    // not stable across recursive self-references (e.g. `type Json = | … JArray
    // {items:[Json]} |`), so a hash mismatch is not a reliable "distinct decl"
    // signal and over-rejected legal recursive types (JsonCodecInterpreterTest).
    // TypeApplication now unifies by NAME (or equal hash). Cross-module same-name
    // distinct-decl safety for TypeApps is provided structurally by the #148
    // in-scope nominal barrier, not by hash-reject here.

    @Test fun `same-named type applications unify by name even when hashes differ`() {
        val app1 = MezzeType.TypeApplication("Widget", emptyList(), typeHash = hashA)
        val app2 = MezzeType.TypeApplication("Widget", emptyList(), typeHash = hashB)
        val result = unify(app1, app2)
        // Name match wins; hash inequality does NOT reject (recursive-type safety).
        assertIs<UnificationResult.Success>(result)
    }

    @Test fun `differently-named type applications with the SAME hash unify (rename-safe alias)`() {
        // The hash clause ADDS matches: a rename-safe alias (name differs, hash same).
        val app1 = MezzeType.TypeApplication("Widget", emptyList(), typeHash = hashA)
        val app2 = MezzeType.TypeApplication("Gadget", emptyList(), typeHash = hashA)
        val result = unify(app1, app2)
        assertIs<UnificationResult.Success>(result, "equal typeHash on differing names is a rename-safe alias")
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
