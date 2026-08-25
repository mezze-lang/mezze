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
    // Type identity is HASH-authoritative: when both sides carry a content hash and
    // the hashes DIFFER, they are distinct declarations and MUST NOT unify — even
    // when their names match. (The former non-veto behaviour was withheld out of a
    // fear that typeHash is unstable across recursive self-reference; a suite-wide
    // Stage-0 probe found ZERO same-name/both-hash/unequal pairs across the recursive
    // codec types, so the veto is safe. See hash-driven-typechecker Stage 0/1.)

    @Test fun `same-named type applications with DIFFERENT hashes do not unify`() {
        val app1 = MezzeType.TypeApplication("Widget", emptyList(), typeHash = hashA)
        val app2 = MezzeType.TypeApplication("Widget", emptyList(), typeHash = hashB)
        val result = unify(app1, app2)
        assertIs<UnificationResult.Failure>(
            result,
            "two modules' `Widget` (different typeHash) must NOT unify — hash is authoritative",
        )
    }

    @Test fun `same-named type applications with the SAME hash unify`() {
        val app1 = MezzeType.TypeApplication("Widget", emptyList(), typeHash = hashA)
        val app2 = MezzeType.TypeApplication("Widget", emptyList(), typeHash = hashA)
        assertIs<UnificationResult.Success>(unify(app1, app2), "same declaration unifies with itself")
    }

    @Test fun `hashless type applications unify by name (builtins and pre-hash)`() {
        val app1 = MezzeType.TypeApplication("List", emptyList())
        val app2 = MezzeType.TypeApplication("List", emptyList())
        assertIs<UnificationResult.Success>(unify(app1, app2), "hashless TypeApps unify by name")
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
            "two modules' opaque `newtype Token = Str` (different typeHash) must NOT unify",
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

    // ---- Variants: hash-veto (distinct declarations don't unify) ----

    @Test fun `same-named variants with DIFFERENT hashes do not unify`() {
        val v1 = MezzeType.VariantType(mapOf("A" to null, "B" to null), typeName = "E", typeHash = hashA)
        val v2 = MezzeType.VariantType(mapOf("A" to null, "B" to null), typeName = "E", typeHash = hashB)
        assertIs<UnificationResult.Failure>(
            unify(v1, v2),
            "two modules' `E` variant (different typeHash, identical arms) must NOT unify",
        )
    }

    @Test fun `same-named variants with the SAME hash unify`() {
        val v1 = MezzeType.VariantType(mapOf("A" to null, "B" to null), typeName = "E", typeHash = hashA)
        val v2 = MezzeType.VariantType(mapOf("A" to null, "B" to null), typeName = "E", typeHash = hashA)
        assertIs<UnificationResult.Success>(unify(v1, v2), "same variant declaration unifies with itself")
    }

    // ---- Records: nominal hash-veto; anonymous records stay structural ----

    @Test fun `record types with DIFFERENT hashes do not unify even with identical fields`() {
        val r1 = MezzeType.RecordType(mapOf("x" to MezzeType.IntType), typeHash = hashA)
        val r2 = MezzeType.RecordType(mapOf("x" to MezzeType.IntType), typeHash = hashB)
        assertIs<UnificationResult.Failure>(
            unify(r1, r2),
            "two distinct nominal records (different typeHash) must NOT unify structurally",
        )
    }

    @Test fun `record types with the SAME hash unify`() {
        val r1 = MezzeType.RecordType(mapOf("x" to MezzeType.IntType), typeHash = hashA)
        val r2 = MezzeType.RecordType(mapOf("x" to MezzeType.IntType), typeHash = hashA)
        assertIs<UnificationResult.Success>(unify(r1, r2), "same record declaration unifies with itself")
    }

    @Test fun `anonymous records (no hash) still unify structurally`() {
        val r1 = MezzeType.RecordType(mapOf("x" to MezzeType.IntType))
        val r2 = MezzeType.RecordType(mapOf("x" to MezzeType.IntType))
        assertIs<UnificationResult.Success>(unify(r1, r2), "hashless records unify by structure")
    }
}
