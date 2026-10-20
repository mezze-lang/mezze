package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.store.BlobKind
import com.mezze.language.store.DeclKind
import com.mezze.language.store.EdgeKind
import com.mezze.language.store.SignatureIndex
import com.mezze.language.store.Visibility
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * One declaration, assembled from every table that knows something about it.
 *
 * Each field was separately reachable and nothing gathered them, so answering
 * "what IS this?" took six queries and knowing which six. The risk in assembling
 * is quiet omission — a page that renders fine while a section is silently always
 * empty — so each section is asserted from data written for it.
 */
class DocPagesTest {

    private lateinit var store: SQLiteMezzeStore

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
        store.putModule("app", "src/app.mz", "sh")
    }

    @AfterEach fun tearDown() { store.close() }

    private var counter = 0

    private fun decl(
        name: String,
        kind: DeclKind = DeclKind.LET,
        type: MezzeType? = null,
        doc: String? = null,
        metadata: String? = null,
    ): Hash {
        val hash = Hash("%064x".format(++counter))
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putDecl(
            modulePath = "app", declName = name, declKind = kind,
            sourceHash = "sh", hash = hash,
            startLine = 10, startCol = 5, endLine = 12, endCol = 1,
            doc = doc, metadata = metadata,
        )
        if (type != null) store.putSignature(SignatureIndex.flatten(hash, type))
        return hash
    }

    private fun pageOf(hash: Hash) = DocPages.of(store, store.findDeclByHash(hash)!!)

    private fun effectfulFn(vararg effects: String) = MezzeType.FunctionType(
        paramType = MezzeType.RecordType(linkedMapOf("x" to MezzeType.IntType)),
        returnType = MezzeType.IntType,
        effects = if (effects.isEmpty()) null else MezzeType.EffectRow.simple(effects.toSet()),
    )

    // ---- the basics --------------------------------------------------------

    @Test
    fun `a page carries the signature, doc, metadata and location`() {
        val h = decl(
            "add",
            type = MezzeType.FunctionType(
                paramType = MezzeType.RecordType(linkedMapOf("x" to MezzeType.IntType)),
                returnType = MezzeType.IntType,
            ),
            doc = "Adds one.",
            metadata = """{"since": "0.5.0"}""",
        )
        val page = pageOf(h)

        assertEquals("app::add", page.qualifiedName)
        assertEquals("{ x: Int } -> Int", page.signature)
        assertEquals("Adds one.", page.doc)
        assertEquals("""{"since": "0.5.0"}""", page.metadata)
        // The file comes from the MODULE — `decls` does not carry one, so a page
        // that forgot to resolve it would silently have nowhere to point.
        assertEquals("src/app.mz", page.file)
        assertEquals(10, page.decl.startLine)
    }

    @Test
    fun `a declaration with no signature has a null one, not an empty string`() {
        val h = decl("SomeType", kind = DeclKind.TYPE)
        assertNull(pageOf(h).signature, "no signature and a trivial signature are different facts")
    }

    // ---- effects -----------------------------------------------------------

    @Test
    fun `declared and reached effects are both present and distinguished`() {
        val handler = decl("handler", type = effectfulFn("Log"))
        val leaf = decl("leaf", type = effectfulFn("FileSystem"))
        store.addEdge(handler, leaf, EdgeKind.CALLS)

        val page = pageOf(handler)
        assertEquals(setOf("Log"), page.declaredEffects)
        assertEquals(listOf("FileSystem", "Log"), page.reachedEffects.map { it.effect }.sorted())
        assertEquals(listOf("FileSystem"), page.undeclaredEffects.map { it.effect },
            "the finding is the effect the signature does NOT mention")
    }

    @Test
    fun `a pure declaration reports no effects`() {
        val page = pageOf(decl("pure", type = effectfulFn()))
        assertTrue(page.declaredEffects.isEmpty())
        assertTrue(page.reachedEffects.isEmpty())
        assertTrue(page.undeclaredEffects.isEmpty())
    }

    // ---- constraints -------------------------------------------------------

    @Test
    fun `ability constraints on the residual arrow are listed`() {
        val a = MezzeType.VarType(TypeVar.freshWithName("'a"))
        val h = decl("sorted", type = MezzeType.FunctionType(
            paramType = a,
            returnType = a,
            abilityConstraints = listOf(
                MezzeType.FunctionAbilityConstraint("Ord"),
                MezzeType.FunctionAbilityConstraint("Eq"),
            ),
        ))
        assertEquals(listOf("Ord", "Eq"), pageOf(h).constraints, "in written order")
    }

    // ---- the graph ---------------------------------------------------------

    @Test
    fun `direct callers and callees are resolved to declarations`() {
        val target = decl("target")
        val caller = decl("caller")
        val callee = decl("callee")
        store.addEdge(caller, target, EdgeKind.CALLS)
        store.addEdge(target, callee, EdgeKind.CALLS)

        val page = pageOf(target)
        assertEquals(listOf("caller"), page.directCallers.map { it.declName })
        assertEquals(listOf("callee"), page.directCallees.map { it.declName })
    }

    @Test
    fun `an IMPLEMENTS edge is not a call`() {
        val target = decl("target")
        val impl = decl("an_impl")
        store.addEdge(impl, target, EdgeKind.IMPLEMENTS)
        assertTrue(pageOf(target).directCallers.isEmpty())
    }

    @Test
    fun `references carry file and position`() {
        val h = decl("used")
        store.putUsage(h, "src/other.mz", 42, 7, 42, 11, fromHash = Hash("e".repeat(64)), isCall = false)

        val ref = pageOf(h).references.single()
        assertEquals("src/other.mz", ref.filePath)
        assertEquals(42, ref.startLine)
        assertEquals(7, ref.startCol)
    }

    // ---- the kind-specific sections ----------------------------------------

    @Test
    fun `an ABILITY page lists its impls`() {
        val ord = decl("Ord", kind = DeclKind.ABILITY)
        val implHash = Hash("%064x".format(++counter))
        val targetHash = Hash("%064x".format(++counter))
        store.putBlob(implHash, BlobKind.ABILITY_IMPL, Visibility.PUBLIC, null, null)
        store.putAbilityImpl(implHash, ord, listOf(targetHash), "app")
        store.putSignature(SignatureIndex.flatten(targetHash, MezzeType.IntType))

        assertEquals(listOf("Int"), pageOf(ord).impls.map { it.rendered })
    }

    @Test
    fun `a TYPE page lists the abilities implemented FOR it`() {
        val color = decl("Color", kind = DeclKind.TYPE)
        val eq = decl("Eq", kind = DeclKind.ABILITY)

        // The impl targets the type by its own DECLARATION hash, which is how a page
        // for this `Color` avoids matching another module's.
        val asType = MezzeType.TypeApplication(
            "Color", emptyList(),
            identity = com.mezze.language.ast.TypeIdentity.Declared(color, "Color"),
        )
        val implHash = Hash("%064x".format(++counter))
        val targetHash = Hash("%064x".format(++counter))
        store.putBlob(implHash, BlobKind.ABILITY_IMPL, Visibility.PUBLIC, null, null)
        store.putAbilityImpl(implHash, eq, listOf(targetHash), "app")
        store.putSignature(SignatureIndex.flatten(targetHash, asType))

        assertEquals(listOf("Eq"), pageOf(color).implementedAbilities)
    }

    @Test
    fun `a TYPE page does NOT list an ability implemented for another module's same-named type`() {
        // The reason the page builds its type with the declaration's HASH, not just
        // its name. Two modules may each declare `Color`; an impl for one says
        // nothing about the other, and matching on the name alone reports it for both.
        val mine = decl("Color", kind = DeclKind.TYPE)
        store.putModule("other", "src/other.mz", "sh")
        val theirsHash = Hash("%064x".format(++counter))
        store.putBlob(theirsHash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putDecl(
            modulePath = "other", declName = "Color", declKind = DeclKind.TYPE,
            sourceHash = "sh", hash = theirsHash,
            startLine = 1, startCol = 1, endLine = 1, endCol = 1,
        )
        val eq = decl("Eq", kind = DeclKind.ABILITY)

        // The impl is for THEIR Color.
        val theirType = MezzeType.TypeApplication(
            "Color", emptyList(),
            identity = com.mezze.language.ast.TypeIdentity.Declared(theirsHash, "Color"),
        )
        val implHash = Hash("%064x".format(++counter))
        val targetHash = Hash("%064x".format(++counter))
        store.putBlob(implHash, BlobKind.ABILITY_IMPL, Visibility.PUBLIC, null, null)
        store.putAbilityImpl(implHash, eq, listOf(targetHash), "other")
        store.putSignature(SignatureIndex.flatten(targetHash, theirType))

        assertTrue(pageOf(mine).implementedAbilities.isEmpty(),
            "`app::Color` does not implement Eq — `other::Color` does")
        assertEquals(listOf("Eq"), pageOf(theirsHash).implementedAbilities)
    }

    @Test
    fun `an EFFECT page lists its performers`() {
        val log = decl("Log", kind = DeclKind.EFFECT)
        val performer = Hash("%064x".format(++counter))
        store.putBlob(performer, BlobKind.PERFORMER, Visibility.PUBLIC, null, null)
        store.putEffectPerformer(performer, log, "Console", "app")

        assertEquals(listOf("Console"), pageOf(log).performers.map { it.performerName })
    }

    @Test
    fun `the kind-specific sections stay empty for other kinds`() {
        // A `let` has no impls, implements nothing and performs no effect operations.
        // An always-empty section renders identically to a broken one, so this is the
        // assertion that they are conditional rather than dead.
        val page = pageOf(decl("plain_fn", type = effectfulFn()))
        assertTrue(page.impls.isEmpty())
        assertTrue(page.implementedAbilities.isEmpty())
        assertTrue(page.performers.isEmpty())
    }
}
