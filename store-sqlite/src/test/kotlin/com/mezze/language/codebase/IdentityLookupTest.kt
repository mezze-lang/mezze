package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.store.*
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Lookups by DECLARATION rather than by spelling, on every store.
 *
 * The name-keyed searches (`findDeclsByEffect`, `findDeclsByConstraint`) answer
 * what a person typed, and two packages that each declare `Db` are one answer to
 * them. A codebase query holds the declaration itself, so it must get exactly
 * that declaration's users. Both stores run the same cases: the SQL store can be
 * wrong where the in-memory one is right, and the reverse.
 */
abstract class IdentityLookupContract {

    abstract fun open(): MezzeStore

    private lateinit var store: MezzeStore

    @BeforeEach
    fun setUp() {
        store = open()
        store.putModule("app", "app.mz", "srchash")
    }

    @AfterEach fun tearDown() { store.close() }

    private var counter = 0
    private fun freshHash() = Hash("%064x".format(++counter))

    private fun decl(name: String, type: MezzeType? = null, kind: DeclKind = DeclKind.LET): Hash {
        val hash = freshHash()
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putDecl(
            modulePath = "app", declName = name, declKind = kind,
            sourceHash = "srchash", hash = hash,
            startLine = 1, startCol = 1, endLine = 1, endCol = 10,
            doc = null, metadata = null,
        )
        if (type != null) store.putSignature(SignatureIndex.flatten(hash, type))
        return hash
    }

    private fun names(rows: List<DeclRow>) = rows.map { it.declName }.sorted()

    /** `{ msg: Str } -> {} <name>`, where the effect is the one declared at [effect]. */
    private fun performing(name: String, effect: Hash) = MezzeType.FunctionType(
        paramType = MezzeType.RecordType(linkedMapOf("msg" to MezzeType.StringType)),
        returnType = MezzeType.RecordType(emptyMap()),
        effects = MezzeType.EffectRow.simple(setOf(name), effectHashes = mapOf(name to effect)),
    )

    @Test
    fun `two effects that share a name are told apart by hash`() {
        val dbA = freshHash()
        val dbB = freshHash()
        decl("uses_a", performing("Db", dbA))
        decl("uses_b", performing("Db", dbB))

        assertEquals(listOf("uses_a", "uses_b"), names(store.findDeclsByEffect("Db")), "the spelling finds both")
        assertEquals(listOf("uses_a"), names(store.findDeclsPerforming(dbA)))
        assertEquals(listOf("uses_b"), names(store.findDeclsPerforming(dbB)))
    }

    @Test
    fun `an effect nothing performs finds nothing by hash`() {
        decl("uses_a", performing("Db", freshHash()))
        assertEquals(emptyList<String>(), names(store.findDeclsPerforming(freshHash())))
    }

    @Test
    fun `two abilities that share a name are told apart by hash`() {
        val ordA = freshHash()
        val ordB = freshHash()
        fun requiring(ability: Hash): MezzeType {
            val a = MezzeType.VarType(TypeVar.freshWithName("'a"))
            return MezzeType.FunctionType(
                paramType = a, returnType = a,
                abilityConstraints = listOf(MezzeType.FunctionAbilityConstraint("Ord", abilityHash = ability)),
            )
        }
        decl("sort_a", requiring(ordA))
        decl("sort_b", requiring(ordB))

        assertEquals(listOf("sort_a", "sort_b"), names(store.findDeclsByConstraint("Ord")))
        assertEquals(listOf("sort_a"), names(store.findDeclsRequiring(ordA)))
        assertEquals(listOf("sort_b"), names(store.findDeclsRequiring(ordB)))
    }

    @Test
    fun `impls for a type are found by their RECEIVER, not a class parameter`() {
        val point = freshHash()
        val json = freshHash()
        val eq = freshHash()
        val show = freshHash()
        val fromJson = freshHash()
        val eqPoint = freshHash()
        val showPoint = freshHash()
        val fromJsonForJson = freshHash()
        // An impl row belongs to its blob (a foreign key in the SQL store).
        for (impl in listOf(eqPoint, showPoint, fromJsonForJson)) {
            store.putBlob(impl, BlobKind.ABILITY_IMPL, Visibility.PUBLIC, null, null)
        }
        store.putAbilityImpl(eqPoint, eq, listOf(point), "app")
        store.putAbilityImpl(showPoint, show, listOf(point), "app")
        // `impl FromJson Point for Json`: Point is a class parameter here, not the receiver.
        store.putAbilityImpl(fromJsonForJson, fromJson, listOf(point, json), "app")

        assertEquals(setOf(eqPoint, showPoint), store.findImplsForType(point).map { it.hash }.toSet())
        assertEquals(listOf(fromJsonForJson), store.findImplsForType(json).map { it.hash })
    }

    @Test
    fun `every stored blob is listed`() {
        val a = decl("a")
        val b = decl("b")
        assertEquals(setOf(a, b), store.getAllBlobHashes().toSet())
    }
}

class SQLiteIdentityLookupTest : IdentityLookupContract() {
    override fun open(): MezzeStore = SQLiteMezzeStore.inMemory()
}

class InMemoryIdentityLookupTest : IdentityLookupContract() {
    override fun open(): MezzeStore = InMemoryMezzeStore()
}
