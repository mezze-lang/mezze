package com.mezze.language.typechecker.solving

import com.mezze.language.parsedast.DeclIdentity
import com.mezze.language.parsedast.IdentityScope
import com.mezze.language.parsedast.identity

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
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
        val app1 = MezzeType.TypeApplication("Widget", emptyList(), identity = TypeIdentity.Declared(hashA, "Widget"))
        val app2 = MezzeType.TypeApplication("Widget", emptyList(), identity = TypeIdentity.Declared(hashB, "Widget"))
        val result = unify(app1, app2)
        assertIs<UnificationResult.Failure>(
            result,
            "two modules' `Widget` (different typeHash) must NOT unify — hash is authoritative",
        )
    }

    @Test fun `same-named type applications with the SAME hash unify`() {
        val app1 = MezzeType.TypeApplication("Widget", emptyList(), identity = TypeIdentity.Declared(hashA, "Widget"))
        val app2 = MezzeType.TypeApplication("Widget", emptyList(), identity = TypeIdentity.Declared(hashA, "Widget"))
        assertIs<UnificationResult.Success>(unify(app1, app2), "same declaration unifies with itself")
    }

    @Test fun `hashless type applications unify by name (builtins and pre-hash)`() {
        val app1 = MezzeType.TypeApplication("List", emptyList(), identity = TypeIdentity.Builtin("List"))
        val app2 = MezzeType.TypeApplication("List", emptyList(), identity = TypeIdentity.Builtin("List"))
        assertIs<UnificationResult.Success>(unify(app1, app2), "hashless TypeApps unify by name")
    }

    @Test fun `differently-named type applications with the SAME hash unify (rename-safe alias)`() {
        // The hash clause ADDS matches: a rename-safe alias (name differs, hash same).
        val app1 = MezzeType.TypeApplication("Widget", emptyList(), identity = TypeIdentity.Declared(hashA, "Widget"))
        val app2 = MezzeType.TypeApplication("Gadget", emptyList(), identity = TypeIdentity.Declared(hashA, "Gadget"))
        val result = unify(app1, app2)
        assertIs<UnificationResult.Success>(result, "equal typeHash on differing names is a rename-safe alias")
    }

    // ---- F0/#400: NewtypeType — the pipeline NOW stamps Declared (was Anonymous) ----
    // These unit cases lock the unify() primitive; the PIPELINE cases below prove the
    // real parse→check path actually PRODUCES the Declared, module-salted identities the
    // unit cases assume — closing the false-confidence gap #397 flagged (the unit tests
    // hand-built Declared identities the pipeline never produced, so a newtype could
    // silently collide cross-module while these stayed green).

    @Test fun `same-named newtypes with DIFFERENT hashes do not unify`() {
        val n1 = MezzeType.NewtypeType("Token", "Token", MezzeType.StringType, identity = TypeIdentity.Declared(hashA, "Token"))
        val n2 = MezzeType.NewtypeType("Token", "Token", MezzeType.StringType, identity = TypeIdentity.Declared(hashB, "Token"))
        val result = unify(n1, n2)
        assertIs<UnificationResult.Failure>(
            result,
            "two modules' opaque `newtype Token = Str` (different typeHash) must NOT unify",
        )
    }

    @Test fun `same-named newtypes with the SAME hash unify`() {
        val n1 = MezzeType.NewtypeType("Token", "Token", MezzeType.StringType, identity = TypeIdentity.Declared(hashA, "Token"))
        val n2 = MezzeType.NewtypeType("Token", "Token", MezzeType.StringType, identity = TypeIdentity.Declared(hashA, "Token"))
        val result = unify(n1, n2)
        assertIs<UnificationResult.Success>(result, "the same newtype declaration unifies with itself")
    }

    @Test fun `newtypes with the SAME hash unify`() {
        val n1 = MezzeType.NewtypeType("Meters", "Meters", MezzeType.IntType, identity = TypeIdentity.Declared(hashA, "Meters"))
        val n2 = MezzeType.NewtypeType("Meters", "Meters", MezzeType.IntType, identity = TypeIdentity.Declared(hashA, "Meters"))
        val result = unify(n1, n2)
        assertIs<UnificationResult.Success>(result, "same newtype declaration unifies with itself")
    }

    // ---- #400: PIPELINE-produced newtype identities (not hand-built) ----
    // Parse REAL newtype decls and read the identity the pipeline actually stamps.
    // This is what #397 was missing: the unit unify() tests above hand-build Declared
    // identities, but the pipeline used to build newtypes with Anonymous — so those
    // tests gave FALSE confidence. These assert on the genuine pipeline output.

    private fun newtypeDecl(src: String, file: String): com.mezze.language.parsedast.ParsedTypeDecl {
        val parsed = DeclIdentity.stamp(
            com.mezze.language.parser.MezzeParser().parseParsedModule(src.trimIndent(), file),
            IdentityScope.NONE, file.removeSuffix(".mz"),
        )
        return parsed.decls.filterIsInstance<com.mezze.language.parsedast.ParsedTypeDecl>()
            .first { it.definition is com.mezze.language.parsedast.ParsedNewtypeDef }
    }

    @Test fun `pipeline — same-named newtypes in different modules get DIFFERENT content hashes`() {
        // Two files each `newtype Id = Str`. Module-salted decl hash ⇒ distinct.
        val a = newtypeDecl("pub newtype Id = Str", "mod_a.mz")
        val b = newtypeDecl("pub newtype Id = Str", "mod_b.mz")
        val ha = a.identity
        val hb = b.identity
        kotlin.test.assertNotEquals(
            ha, hb,
            "mod_a::Id and mod_b::Id are distinct nominal types — their decl-site-salted " +
                "content hashes must differ (else they collide cross-module, the #400 soundness hole)",
        )
    }

    @Test fun `pipeline — the same newtype decl hashes identically (stable within a module)`() {
        // Same module, same decl ⇒ same hash. The salt is the declaring module, NOT line/col,
        // so a newtype's identity is stable across edits elsewhere in its module.
        val a1 = newtypeDecl("pub newtype Id = Str\npub let x : Int = 1", "mod_a.mz")
        val a2 = newtypeDecl("pub newtype Id = Str", "mod_a.mz")
        kotlin.test.assertEquals(
            a1.identity,
            a2.identity,
            "the same newtype decl in the same module must hash identically regardless of siblings",
        )
    }

    @Test fun `pipeline — same-module newtypes over the same underlying get DIFFERENT hashes`() {
        // `newtype Length = Float` and `newtype Age = Float` — same underlying, distinct
        // names ⇒ distinct decls ⇒ distinct hashes (name is folded into the hash).
        val len = newtypeDecl("newtype Length = Float", "m.mz")
        val age = newtypeDecl("newtype Age = Float", "m.mz")
        kotlin.test.assertNotEquals(
            len.identity,
            age.identity,
            "Length and Age both wrap Float but are distinct newtypes — hashes must differ",
        )
    }

    // ---- Variants: hash-veto (distinct declarations don't unify) ----

    @Test fun `same-named variants with DIFFERENT hashes do not unify`() {
        val v1 = MezzeType.VariantType(mapOf("A" to null, "B" to null), identity = TypeIdentity.Declared(hashA, "E"))
        val v2 = MezzeType.VariantType(mapOf("A" to null, "B" to null), identity = TypeIdentity.Declared(hashB, "E"))
        assertIs<UnificationResult.Failure>(
            unify(v1, v2),
            "two modules' `E` variant (different typeHash, identical arms) must NOT unify",
        )
    }

    @Test fun `same-named variants with the SAME hash unify`() {
        val v1 = MezzeType.VariantType(mapOf("A" to null, "B" to null), identity = TypeIdentity.Declared(hashA, "E"))
        val v2 = MezzeType.VariantType(mapOf("A" to null, "B" to null), identity = TypeIdentity.Declared(hashA, "E"))
        assertIs<UnificationResult.Success>(unify(v1, v2), "same variant declaration unifies with itself")
    }

    // ---- Records: nominal hash-veto; anonymous records stay structural ----

    @Test fun `record types with DIFFERENT hashes do not unify even with identical fields`() {
        val r1 = MezzeType.RecordType(mapOf("x" to MezzeType.IntType), identity = TypeIdentity.Declared(hashA, "R"))
        val r2 = MezzeType.RecordType(mapOf("x" to MezzeType.IntType), identity = TypeIdentity.Declared(hashB, "R"))
        assertIs<UnificationResult.Failure>(
            unify(r1, r2),
            "two distinct nominal records (different typeHash) must NOT unify structurally",
        )
    }

    @Test fun `record types with the SAME hash unify`() {
        val r1 = MezzeType.RecordType(mapOf("x" to MezzeType.IntType), identity = TypeIdentity.Declared(hashA, "R"))
        val r2 = MezzeType.RecordType(mapOf("x" to MezzeType.IntType), identity = TypeIdentity.Declared(hashA, "R"))
        assertIs<UnificationResult.Success>(unify(r1, r2), "same record declaration unifies with itself")
    }

    @Test fun `anonymous records (no hash) still unify structurally`() {
        val r1 = MezzeType.RecordType(mapOf("x" to MezzeType.IntType))
        val r2 = MezzeType.RecordType(mapOf("x" to MezzeType.IntType))
        assertIs<UnificationResult.Success>(unify(r1, r2), "hashless records unify by structure")
    }

    // ---- S3.2: isOpaqueWall — the single predicate that decides wall-ness ----
    // Wall-ness is keyed on NODE-KIND + ascriptionWall ONLY, never on identity shape.
    // A `Declared` record is TRANSPARENT; only newtype nodes and ascriptionWall
    // variants are opaque. These lock the two behaviours the consolidation preserves:
    // transparent-alias fall-through, and opaque-wall rejection on identity mismatch.

    @Test fun `isOpaqueWall true only for newtype nodes and ascriptionWall variants`() {
        val newtype = MezzeType.NewtypeType("Token", "Token", MezzeType.StringType, identity = TypeIdentity.Declared(hashA, "Token"))
        val walledVariant = MezzeType.VariantType(mapOf("A" to null), ascriptionWall = true)
        assert(isOpaqueWall(newtype)) { "a newtype node is an opaque wall" }
        assert(isOpaqueWall(walledVariant)) { "an ascriptionWall variant is an opaque wall" }
    }

    @Test fun `isOpaqueWall false for records and non-walled variants regardless of identity shape`() {
        // A DECLARED record is still transparent — wall-ness must NOT key on `identity is Declared`.
        val declaredRecord = MezzeType.RecordType(mapOf("n" to MezzeType.IntType), identity = TypeIdentity.Declared(hashA, "Wrapper"))
        val anonRecord = MezzeType.RecordType(mapOf("n" to MezzeType.IntType))
        // A DECLARED but non-ascription variant (an alias-resolved shape) is transparent too.
        val declaredVariant = MezzeType.VariantType(mapOf("A" to null, "B" to null), identity = TypeIdentity.Declared(hashA, "E"))
        val anonVariant = MezzeType.VariantType(mapOf("A" to null))
        assert(!isOpaqueWall(declaredRecord)) { "a Declared record is TRANSPARENT, not an opaque wall" }
        assert(!isOpaqueWall(anonRecord)) { "an anonymous record is transparent" }
        assert(!isOpaqueWall(declaredVariant)) { "a non-ascriptionWall variant is transparent" }
        assert(!isOpaqueWall(anonVariant)) { "an anonymous non-walled variant is transparent" }
    }

    @Test fun `transparent alias — Declared record unifies structurally with a bare Anonymous record literal`() {
        // `type Wrapper = {n:Int}` slot (Declared) meets a bare `{n=5}` literal (Anonymous):
        // mixed Declared<->Anonymous is UNDETERMINED, and because a record is transparent
        // (isOpaqueWall == false) it MUST fall through to structural unify and succeed.
        val slot = MezzeType.RecordType(mapOf("n" to MezzeType.IntType), identity = TypeIdentity.Declared(hashA, "Wrapper"))
        val literal = MezzeType.RecordType(mapOf("n" to MezzeType.IntType))
        assertIs<UnificationResult.Success>(
            unify(slot, literal),
            "a transparent Declared record alias accepts a structurally-equal bare literal",
        )
    }

    @Test fun `opaque wall — an ascriptionWall variant rejects an identity mismatch`() {
        // Two ascription-walled variants with distinct Declared hashes are distinct
        // declarations; the opaque wall rejects even though arms are identical.
        val w1 = MezzeType.VariantType(mapOf("A" to null, "B" to null), identity = TypeIdentity.Declared(hashA, "E"), ascriptionWall = true)
        val w2 = MezzeType.VariantType(mapOf("A" to null, "B" to null), identity = TypeIdentity.Declared(hashB, "E"), ascriptionWall = true)
        assertIs<UnificationResult.Failure>(
            unify(w1, w2),
            "an opaque ascriptionWall variant rejects a distinct-declaration identity mismatch",
        )
    }
}
