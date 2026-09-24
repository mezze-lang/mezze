package com.mezze.language.ast.hashing

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A function type's EFFECTS and ABILITY CONSTRAINTS are part of its content hash.
 *
 * They were not. The `FunctionType` arm hashed `paramType` and `returnType` and
 * stopped, so `{x: Int} -> Int` and `{x: Int} -> Int <IO>` had the SAME content
 * hash, and so did `{x: 'a} -> 'a` and `{x: 'a} -> 'a with Show 'a`. Those are
 * distinct types by the language's own rules — effects ride on arrows, and a
 * constraint is part of the signature — so the hasher was equating them.
 *
 * That is the defect #264 fixed for records, where an open `{x, ...}` and a
 * closed `{x}` collided, and the reason is the same one: a content hash keys
 * declaration identity, dispatch, and the precompile cache, so two distinct
 * types sharing one is not a cosmetic problem.
 *
 * Both are hashed in a CANONICAL order rather than as stored, because neither is
 * stored in one — a set, and a list ordered by whatever inference produced.
 * Hashing them as they come would make the hash depend on solve order, which is
 * the opposite of what a content hash is for. The ordering cases below are what
 * hold that down.
 */
class FunctionTypeHashDistinctnessTest {

    private fun hash(t: MezzeType): String {
        val b = MezzeHashBuilder()
        appendType(b, t)
        return b.build().toFullHexString()
    }

    private fun arrow(
        effects: MezzeType.EffectRow? = null,
        constraints: List<MezzeType.FunctionAbilityConstraint> = emptyList(),
    ) = MezzeType.FunctionType(
        paramType = MezzeType.RecordType(mapOf("x" to MezzeType.IntType)),
        returnType = MezzeType.IntType,
        effects = effects,
        abilityConstraints = constraints,
    )

    @Test
    fun `an effectful arrow does not hash as the pure one`() {
        assertNotEquals(
            hash(arrow()),
            hash(arrow(effects = MezzeType.EffectRow(effects = setOf("IO")))),
            "`{x: Int} -> Int` and `{x: Int} -> Int <IO>` are different types",
        )
    }

    @Test
    fun `a pure arrow does not hash as one carrying an EMPTY row`() {
        // The boundary: "no row" and "a row with nothing in it" are different
        // enough to be worth not collapsing, and collapsing them is what a
        // null-skipping encoder would do.
        assertNotEquals(
            hash(arrow()),
            hash(arrow(effects = MezzeType.EffectRow(effects = emptySet()))),
        )
    }

    @Test
    fun `two arrows differing only in WHICH effect do not collide`() {
        assertNotEquals(
            hash(arrow(effects = MezzeType.EffectRow(effects = setOf("IO")))),
            hash(arrow(effects = MezzeType.EffectRow(effects = setOf("State")))),
        )
    }

    @Test
    fun `an OPEN effect row does not hash as a closed one`() {
        // Same marker a record's openness gets, for the same reason: an open row
        // admits effects a closed one does not.
        assertNotEquals(
            hash(arrow(effects = MezzeType.EffectRow(effects = setOf("IO")))),
            hash(arrow(effects = MezzeType.EffectRow(effects = setOf("IO"), rowVar = RowVar.fresh()))),
        )
    }

    @Test
    fun `two arrows differing only in an associated TYPE do not collide`() {
        // `<Exception having 'error = Str>` vs `= Int`. The row's NAME set is
        // identical, so only walking the names cannot tell these apart.
        fun rowWithError(t: MezzeType) = MezzeType.EffectRow(
            effects = setOf("Exception"),
            effectInstances = setOf(MezzeType.EffectInstance("Exception", mapOf("error" to t))),
        )
        assertNotEquals(
            hash(arrow(effects = rowWithError(MezzeType.StringType))),
            hash(arrow(effects = rowWithError(MezzeType.IntType))),
        )
    }

    @Test
    fun `a constrained arrow does not hash as the unconstrained one`() {
        val a = TypeVar.freshWithName("'a")
        val bare = MezzeType.FunctionType(MezzeType.VarType(a), MezzeType.VarType(a))
        val constrained = MezzeType.FunctionType(
            MezzeType.VarType(a), MezzeType.VarType(a),
            abilityConstraints = listOf(
                MezzeType.FunctionAbilityConstraint("Show", typeArgs = listOf(MezzeType.VarType(a)))
            ),
        )
        assertNotEquals(hash(bare), hash(constrained), "`with Show 'a` is part of the signature")
    }

    @Test
    fun `two arrows differing only in WHICH ability is required do not collide`() {
        val a = TypeVar.freshWithName("'a")
        fun withAbility(name: String) = MezzeType.FunctionType(
            MezzeType.VarType(a), MezzeType.VarType(a),
            abilityConstraints = listOf(
                MezzeType.FunctionAbilityConstraint(name, typeArgs = listOf(MezzeType.VarType(a)))
            ),
        )
        assertNotEquals(hash(withAbility("Show")), hash(withAbility("Ord")))
    }

    // ---- canonical order: the same type must hash the same however it was built ----

    @Test
    fun `constraint ORDER does not change the hash`() {
        val a = TypeVar.freshWithName("'a")
        fun withConstraints(names: List<String>) = MezzeType.FunctionType(
            MezzeType.VarType(a), MezzeType.VarType(a),
            abilityConstraints = names.map {
                MezzeType.FunctionAbilityConstraint(it, typeArgs = listOf(MezzeType.VarType(a)))
            },
        )
        assertEquals(
            hash(withConstraints(listOf("Show", "Ord"))),
            hash(withConstraints(listOf("Ord", "Show"))),
            "the constraint list is ordered by whatever inference produced — the hash must not be",
        )
    }

    @Test
    fun `effect ORDER does not change the hash`() {
        fun withEffects(names: List<String>) =
            arrow(effects = MezzeType.EffectRow(effects = LinkedHashSet(names)))
        assertEquals(
            hash(withEffects(listOf("IO", "State"))),
            hash(withEffects(listOf("State", "IO"))),
        )
    }

    @Test
    fun `a constraint is identified by its ability HASH, not its name`() {
        // Two constraints spelled alike but carrying different declaration hashes
        // are different requirements. The name is only the fallback for when there
        // is no hash at all.
        val a = TypeVar.freshWithName("'a")
        fun withHash(h: String) = MezzeType.FunctionType(
            MezzeType.VarType(a), MezzeType.VarType(a),
            abilityConstraints = listOf(
                MezzeType.FunctionAbilityConstraint(
                    "Show", typeArgs = listOf(MezzeType.VarType(a)), abilityHash = Hash(h.repeat(64)),
                )
            ),
        )
        assertNotEquals(hash(withHash("a")), hash(withHash("b")))
    }
}
