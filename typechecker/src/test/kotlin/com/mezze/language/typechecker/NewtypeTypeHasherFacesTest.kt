package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.ast.hashing.appendType
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals

/**
 * Characterization of how [MezzeTypeHasher.appendType] encodes the TWO faces a
 * newtype wears in the solved type system:
 *
 *   - [MezzeType.NewtypeType]     — the face a newtype VALUE carries
 *                                   (`UserId.(5)` infers `NewtypeType("UserId", …)`).
 *   - [MezzeType.TypeApplication] — the face a newtype ANNOTATION carries
 *                                   (a param `: UserId` converts to
 *                                   `TypeApplication("UserId", [], Declared(h))`).
 *
 * These two faces UNIFY as the same type (same `Declared` identity ⇒ same newtype).
 * This test answers, on paper, the question "do they also HASH the same?" — and the
 * answer is **NO**: the hasher takes a different arm for each
 * (`TYPE_CONSTRUCTOR,0x10 + name + constructor + identity + wrappedType` for
 * NewtypeType; `TYPE_CONSTRUCTOR + identity + args` for TypeApplication), so the two
 * faces of one declaration produce DIFFERENT content hashes.
 *
 * Why that is tolerable today, and why this test exists as the tripwire:
 *
 *   The content hash keys function identity (mono spec hashes, op hashes, the runtime
 *   function registry). It is only sound to hash unify-equal types differently as long
 *   as no single registry is ever asked for BOTH faces of one type — i.e. the solver
 *   commits to one representation at every point where a hash is taken. It does today,
 *   so the discrepancy never surfaces. If a future change ever lets a caller compute a
 *   callee's hash from one face while the callee was registered under the other, that
 *   re-opens the "no MezzeFunction registered for hash …" class of failure. These
 *   assertions pin the current encoding so that drift is a deliberate, reviewed change
 *   rather than a silent one — and if we later decide unify-equal MUST imply hash-equal,
 *   the `assertNotEquals` below is the line that flips to `assertEquals`.
 *
 * See [[project_type_identity_named_case]] and [[typehash_recursive]].
 */
class NewtypeTypeHasherFacesTest {

    private fun hashOf(type: MezzeType): String {
        val b = MezzeHashBuilder()
        appendType(b, type)
        return b.build().toFullHexString()
    }

    /** A stable, module-salted declaration identity shared by both faces. */
    private val userIdIdentity =
        TypeIdentity.Declared(Hash("a".repeat(64)), "UserId")

    private fun newtypeFace(identity: TypeIdentity = userIdIdentity) =
        MezzeType.NewtypeType(
            name = "UserId",
            constructor = "UserId",
            wrappedType = MezzeType.IntType,
            identity = identity
        )

    private fun applicationFace(identity: TypeIdentity = userIdIdentity) =
        MezzeType.TypeApplication(
            constructor = "UserId",
            args = emptyList(),
            identity = identity
        )

    @Test
    fun `the two faces of one newtype declaration hash DIFFERENTLY`() {
        // THE on-paper answer: unify-equal, hash-distinct. If this ever fails because
        // the two hashes converged, that is a real change to type identity — update the
        // doc above and confirm it is intended (unify-equal ⇒ hash-equal) before landing.
        assertNotEquals(
            hashOf(newtypeFace()),
            hashOf(applicationFace()),
            "NewtypeType and TypeApplication faces of the same declaration currently " +
                "take different hasher arms and must hash distinctly (see class doc)"
        )
    }

    @Test
    fun `each face is internally deterministic`() {
        assertEquals(hashOf(newtypeFace()), hashOf(newtypeFace()),
            "NewtypeType must hash identically to itself")
        assertEquals(hashOf(applicationFace()), hashOf(applicationFace()),
            "TypeApplication must hash identically to itself")
    }

    @Test
    fun `newtype face folds declaration identity — different declarations hash distinctly`() {
        // #278/F0 guard: two same-named newtypes from different declarations must NOT
        // collide in the structural hash (identity is folded in, not just the name).
        val other = TypeIdentity.Declared(Hash("b".repeat(64)), "UserId")
        assertNotEquals(
            hashOf(newtypeFace()),
            hashOf(newtypeFace(identity = other)),
            "same-named newtypes with distinct Declared identities must hash distinctly"
        )
    }

    @Test
    fun `application face folds declaration identity — different declarations hash distinctly`() {
        val other = TypeIdentity.Declared(Hash("b".repeat(64)), "UserId")
        assertNotEquals(
            hashOf(applicationFace()),
            hashOf(applicationFace(identity = other)),
            "same-named TypeApplications with distinct Declared identities must hash distinctly"
        )
    }
}
