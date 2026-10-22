package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.TypeVar
import com.mezze.language.store.*
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Storing and querying signatures through SQL.
 *
 * The first version of this index put the signature on `decls` as three TEXT
 * columns — a rendered string plus two JSON arrays — and these tests asserted on
 * that text. They now assert on the tables, because the text was the defect: a
 * rendered signature cannot be read back as a type, so the column could only be
 * displayed or substring-matched, and `LIKE '%Log%'` also finds `Logger`.
 *
 * `SignatureIndexRoundTripTest` (in :store) covers what the rows MEAN; this
 * covers storing and querying them, which is where the SQL can be wrong while
 * the flattening is right.
 */
class SignatureIndexTest {

    private lateinit var store: SQLiteMezzeStore

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
        // Once — putModule is INSERT OR REPLACE and decls.module_path cascades,
        // so calling it per decl deletes everything written before it.
        store.putModule("app", "app.mz", "srchash")
    }

    @AfterEach fun tearDown() { store.close() }

    private var counter = 0

    /** Index a decl named [name] with signature [type], returning its hash. */
    private fun decl(name: String, type: MezzeType? = null): Hash {
        val hash = Hash("%064x".format(++counter))
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putDecl(
            modulePath = "app", declName = name, declKind = DeclKind.LET,
            sourceHash = "srchash", hash = hash,
            startLine = 1, startCol = 1, endLine = 1, endCol = 10,
            doc = null, metadata = null,
        )
        if (type != null) store.putSignature(SignatureIndex.flatten(hash, type))
        return hash
    }

    private fun names(rows: List<DeclRow>) = rows.map { it.declName }.sorted()

    private val unitRecord = MezzeType.RecordType(emptyMap())

    /** `{ msg: Str } -> {} <effects>` */
    private fun effectful(vararg effectNames: String) = MezzeType.FunctionType(
        paramType = MezzeType.RecordType(linkedMapOf("msg" to MezzeType.StringType)),
        returnType = unitRecord,
        effects = MezzeType.EffectRow.simple(effectNames.toSet()),
    )

    // ---- round-trip through SQL ---------------------------------------------

    @Test
    fun `a signature survives the database as a TYPE`() {
        // The property the TEXT column could not have at all.
        val type = effectful("Log")
        val hash = decl("shout", type)

        val rebuilt = SignatureIndex.rebuild(store.getSignature(hash), store::getTypeRows)
        assertTrue(rebuilt is MezzeType.FunctionType, "got $rebuilt")
        rebuilt as MezzeType.FunctionType
        assertEquals(setOf("Log"), rebuilt.effects?.effects)
        assertEquals(
            setOf("msg"),
            (rebuilt.paramType as MezzeType.RecordType).fields.keys,
            "the parameter shape must come back, not just the effect names",
        )
    }

    @Test
    fun `a decl indexed without a type has no signature rows`() {
        // Null rather than empty: a `type`/`ability`/`effect` declaration has no
        // signature, which is a different fact from having a trivial one.
        val hash = decl("plain")
        assertTrue(store.getSignature(hash).isEmpty)
    }

    @Test
    fun `re-indexing a decl replaces its signature rather than merging`() {
        // A type that grew SIMPLER has fewer nodes, and `INSERT OR REPLACE`
        // overwrites only the ids the new tree happens to reuse — so the old tail
        // survives unless the whole signature is cleared first.
        //
        // Asserted on the ROWS, not on the rebuilt type. Rebuilding hides this:
        // the stale nodes hang off positions the new root never reads, so the type
        // comes back correct while the table holds a second signature's worth of
        // rows that later queries still join against. (Verified by removing the
        // DELETE: a type-level assertion here passes, this one fails.)
        val hash = decl("f", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("a" to MezzeType.IntType, "b" to MezzeType.IntType)),
            returnType = MezzeType.IntType,
            effects = MezzeType.EffectRow.simple(setOf("Log")),
        ))
        val replacement = SignatureIndex.flatten(hash, MezzeType.IntType)
        store.putSignature(replacement)

        val stored = store.getSignature(hash)
        assertEquals(replacement.nodes, stored.nodes,
            "the decl is now a constant; no node of the old arrow may survive")
        assertTrue(stored.effects.isEmpty(),
            "the old arrow's <Log> must not still be attached — it would keep the " +
                "decl answering an effect search it no longer performs")
        assertEquals(MezzeType.IntType, SignatureIndex.rebuild(stored, store::getTypeRows))
        assertTrue(store.findDeclsByEffect("Log").isEmpty(),
            "and the effect search must agree")
    }

    // ---- effect search -------------------------------------------------------

    @Test
    fun `finds declarations by effect`() {
        decl("shout", effectful("Log"))
        decl("read", effectful("FileSystem"))
        decl("pure_fn", MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType))

        assertEquals(listOf("shout"), names(store.findDeclsByEffect("Log")))
        assertEquals(listOf("read"), names(store.findDeclsByEffect("FileSystem")))
    }

    @Test
    fun `a declaration with several effects is found by each`() {
        decl("both", effectful("FileSystem", "Log"))

        assertEquals(listOf("both"), names(store.findDeclsByEffect("Log")))
        assertEquals(listOf("both"), names(store.findDeclsByEffect("FileSystem")))
    }

    @Test
    fun `effect matching is exact, not substring`() {
        // Kept from the original tests: this is what the JSON array existed to
        // guarantee and what a join now gives for free. A search that quietly
        // over-matches is worse than one that finds nothing, because the caller
        // cannot tell.
        decl("logger_user", effectful("Logger"))

        assertTrue(store.findDeclsByEffect("Log").isEmpty(),
            "`Log` must not match the effect `Logger`")
        assertEquals(listOf("logger_user"), names(store.findDeclsByEffect("Logger")))
    }

    @Test
    fun `pure declarations are never returned by an effect search`() {
        decl("pure_fn", MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType))
        assertTrue(store.findDeclsByEffect("Log").isEmpty())
    }

    @Test
    fun `an effect nothing performs finds nothing`() {
        decl("shout", effectful("Log"))
        assertTrue(store.findDeclsByEffect("Nonexistent").isEmpty())
    }

    @Test
    fun `the effect search reads the RESIDUAL arrow, not the outermost`() {
        // `{a} -> {b} -> {} <Log>`: applying one argument leaves a pure function,
        // so the effects live on the inner arrow. A query against the root's row
        // would report this decl as pure.
        val curried = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("a" to MezzeType.IntType)),
            returnType = MezzeType.FunctionType(
                paramType = MezzeType.RecordType(linkedMapOf("b" to MezzeType.IntType)),
                returnType = unitRecord,
                effects = MezzeType.EffectRow.simple(setOf("Log")),
            ),
        )
        decl("curried", curried)
        assertEquals(listOf("curried"), names(store.findDeclsByEffect("Log")),
            "a curried effectful function must be findable by its effect")
    }

    // ---- constraint search ---------------------------------------------------

    @Test
    fun `finds declarations by ability constraint`() {
        val a = MezzeType.VarType(TypeVar.freshWithName("'a"))
        decl("sorted", MezzeType.FunctionType(
            paramType = a, returnType = a,
            abilityConstraints = listOf(MezzeType.FunctionAbilityConstraint("Ord")),
        ))
        decl("shown", MezzeType.FunctionType(
            paramType = a, returnType = MezzeType.StringType,
            abilityConstraints = listOf(MezzeType.FunctionAbilityConstraint("Show")),
        ))

        assertEquals(listOf("sorted"), names(store.findDeclsByConstraint("Ord")))
        assertEquals(listOf("shown"), names(store.findDeclsByConstraint("Show")))
        assertTrue(store.findDeclsByConstraint("Eq").isEmpty())
    }

    // ---- "who mentions this type" --------------------------------------------

    @Test
    fun `finds declarations mentioning a type by its HASH`() {
        val colorHash = Hash("c".repeat(64))
        val color = MezzeType.TypeApplication(
            "Color", emptyList(), identity = TypeIdentity.Declared(colorHash, "Color")
        )

        decl("paint", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("c" to color)),
            returnType = unitRecord,
        ))
        // Nested inside a container, which a signature-string search would miss
        // unless it happened to substring-match.
        decl("palette", MezzeType.FunctionType(
            paramType = unitRecord,
            returnType = MezzeType.TypeApplication("Seq", listOf(color)),
        ))
        decl("unrelated", MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType))

        assertEquals(listOf("paint", "palette"), names(store.findDeclsMentioningType(colorHash)))
    }

    @Test
    fun `a same-named type from another module is NOT a match`() {
        // The reason this is keyed on the hash. Two modules may each declare
        // `Color`; they are different types, and a search for one must not return
        // users of the other.
        val mine = Hash("a".repeat(64))
        val theirs = Hash("b".repeat(64))
        decl("uses_mine", MezzeType.TypeApplication(
            "Color", emptyList(), identity = TypeIdentity.Declared(mine, "Color")
        ))

        assertEquals(listOf("uses_mine"), names(store.findDeclsMentioningType(mine)))
        assertTrue(store.findDeclsMentioningType(theirs).isEmpty(),
            "same spelling, different declaration — not a match")
    }

    @Test
    fun `a decl mentioning the same type twice is returned once`() {
        val colorHash = Hash("d".repeat(64))
        val color = MezzeType.TypeApplication(
            "Color", emptyList(), identity = TypeIdentity.Declared(colorHash, "Color")
        )
        decl("recolor", MezzeType.FunctionType(paramType = color, returnType = color))

        assertEquals(listOf("recolor"), names(store.findDeclsMentioningType(colorHash)))
    }

    // ---- the columns that carry structure, not text -------------------------

    @Test
    fun `openness round-trips through SQL`() {
        // An integer column, not a character in a rendered string.
        val hash = decl("open_rec", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("a" to MezzeType.IntType), rowVar = RowVar.userOpen()),
            returnType = MezzeType.IntType,
        ))
        val rebuilt = SignatureIndex.rebuild(store.getSignature(hash), store::getTypeRows) as MezzeType.FunctionType
        assertTrue((rebuilt.paramType as MezzeType.RecordType).rowVar != null,
            "`{ a: Int, ... }` must still be open after a trip through the database")
    }

    @Test
    fun `the root node's null parent is read back as null`() {
        // getInt returns 0 for SQL NULL and 0 is the ROOT's own id, so a reader
        // that does not check wasNull() parents every root to itself — and the
        // tree stops being a tree.
        val hash = decl("f", MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType))
        val roots = store.getSignature(hash).nodes.filter { it.parentId == null }
        assertEquals(1, roots.size, "exactly one node has no parent")
        assertEquals(0, roots.single().nodeId)
    }
}
