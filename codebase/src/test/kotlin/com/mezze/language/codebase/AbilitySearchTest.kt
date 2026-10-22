package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.store.*
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * "What implements `Ord`?" and "what does `Deque Int` implement?"
 *
 * Both were unanswerable while every byte needed was already stored.
 * `ability_impls` could say `Ord` has 16 impls and name NONE of them: its target
 * key is a structural type hash that joins to no declaration (`Int` has none), and
 * an impl's own decl name is synthetic (`impl_01a38979371c`). The fix was to index
 * the target types' STRUCTURE under those same hashes.
 *
 * Structure and not names, because the interesting cases are generic: `impl Ord
 * for Deque 'a` has to match a query about `Deque Int`, which is unification, not
 * string comparison.
 */
class AbilitySearchTest {

    private lateinit var store: SQLiteMezzeStore

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
        store.putModule("app", "app.mz", "sh")
        registeredModules.add("app")
    }

    @AfterEach fun tearDown() { store.close() }

    private var counter = 0
    private fun freshHash() = Hash("%064x".format(++counter))

    /**
     * Modules already registered. `putModule` is INSERT OR REPLACE and
     * `decls.module_path` cascades, so re-registering a module DELETES every decl
     * written under it — calling it per ability silently wiped the previous ones,
     * and `abilitiesOf` then found nothing while the single-ability tests passed.
     */
    private val registeredModules = mutableSetOf<String>()

    /** Declare an ability named [name] and return its decl hash. */
    private fun ability(name: String, module: String = "app"): Hash {
        val hash = freshHash()
        if (registeredModules.add(module)) store.putModule(module, "$module.mz", "sh")
        store.putBlob(hash, BlobKind.ABILITY, Visibility.PUBLIC, null, null)
        store.putDecl(
            modulePath = module, declName = name, declKind = DeclKind.ABILITY,
            sourceHash = "sh", hash = hash,
            startLine = 1, startCol = 1, endLine = 1, endCol = 1,
        )
        return hash
    }

    /**
     * `impl <ability> <classParams…> for <receiver>`.
     *
     * Targets are written class-parameters-first, receiver LAST — the order the
     * language uses (`ability FromJson 'a for 'in`).
     */
    private fun impl(
        abilityHash: Hash,
        receiver: MezzeType,
        classParams: List<MezzeType> = emptyList(),
        module: String = "app",
        indexTargets: Boolean = true,
    ): Hash {
        val implHash = freshHash()
        store.putBlob(implHash, BlobKind.ABILITY_IMPL, Visibility.PUBLIC, null, null)
        val targets = classParams + receiver
        val hashes = targets.map { freshHash() }
        store.putAbilityImpl(implHash, abilityHash, hashes, module)
        if (indexTargets) {
            targets.forEachIndexed { i, t -> store.putSignature(SignatureIndex.flatten(hashes[i], t)) }
        }
        return implHash
    }

    private fun seqOf(element: MezzeType) = MezzeType.TypeApplication("Deque", listOf(element))
    private fun varType(name: String) = MezzeType.VarType(TypeVar.freshWithName(name))

    // ---- naming the targets: the thing that was impossible ------------------

    @Test
    fun `impls of an ability are listed with their target types`() {
        val ord = ability("Ord")
        impl(ord, MezzeType.IntType)
        impl(ord, MezzeType.StringType)

        assertEquals(listOf("Int", "Str"), AbilitySearch.implsOf(store, "Ord").map { it.rendered })
    }

    @Test
    fun `a generic impl target is reported as written`() {
        val ord = ability("Ord")
        impl(ord, seqOf(varType("'a")))
        assertEquals(listOf("Deque 'a"), AbilitySearch.implsOf(store, "Ord").map { it.rendered })
    }

    @Test
    fun `an ability with no impls reports none`() {
        ability("Unimplemented")
        assertTrue(AbilitySearch.implsOf(store, "Unimplemented").isEmpty())
    }

    @Test
    fun `an impl whose targets are NOT indexed is still listed`() {
        // It exists. Reporting "an impl I cannot describe" beats dropping it and
        // reporting a count that is quietly too low.
        val ord = ability("Ord")
        impl(ord, MezzeType.IntType, indexTargets = false)

        val impls = AbilitySearch.implsOf(store, "Ord")
        assertEquals(1, impls.size)
        assertEquals("", impls.single().rendered)
    }

    // ---- the reverse direction, by unification ------------------------------

    @Test
    fun `a concrete type matches a GENERIC impl target`() {
        // `Deque Int` implements Ord because of `impl Ord for Deque 'a`. Name
        // comparison cannot see this — the strings differ.
        val ord = ability("Ord")
        impl(ord, seqOf(varType("'a")))

        assertNotNull(AbilitySearch.implements(store, "Ord", seqOf(MezzeType.IntType)),
            "a generic impl covers its instances")
    }

    @Test
    fun `a type with no impl does not match`() {
        val ord = ability("Ord")
        impl(ord, MezzeType.IntType)
        assertNull(AbilitySearch.implements(store, "Ord", MezzeType.StringType))
    }

    @Test
    fun `abilitiesOf finds every ability implemented for a type`() {
        val ord = ability("Ord")
        val eq = ability("Eq")
        ability("Unrelated")
        impl(ord, MezzeType.IntType)
        impl(eq, MezzeType.IntType)

        assertEquals(listOf("Eq", "Ord"), AbilitySearch.abilitiesOf(store, MezzeType.IntType).map { it.first })
    }

    // ---- the receiver is the LAST target ------------------------------------

    @Test
    fun `the receiver is the last target, not the first`() {
        // `ability FromJson 'a for 'in` writes class parameters BEFORE `for`, so
        // `impl FromJson 'a for Json` has targets ['a, Json] and the receiver is
        // Json. Reading the FIRST target tests `'a` — a bare variable that unifies
        // with anything — so every type appeared to implement the ability.
        //
        // Measured against the real stdlib: it claimed `Deque Int` implements 19
        // abilities where the true answer is 16, and that `FromJson` has impls for
        // `'a` rather than for `Str` and `Json`.
        val fromJson = ability("FromJson")
        impl(fromJson, receiver = MezzeType.StringType, classParams = listOf(varType("'a")))

        assertEquals(listOf("Str"), AbilitySearch.implsOf(store, "FromJson").map { it.rendered },
            "the impl is FOR Str; `'a` is a class parameter")
        assertNotNull(AbilitySearch.implements(store, "FromJson", MezzeType.StringType))
        assertNull(AbilitySearch.implements(store, "FromJson", MezzeType.IntType),
            "Int does not implement FromJson — matching the class parameter would say it does")
    }

    @Test
    fun `class parameters are kept, not discarded`() {
        val fromJson = ability("FromJson")
        impl(fromJson, receiver = MezzeType.StringType, classParams = listOf(MezzeType.IntType))

        val found = AbilitySearch.implsOf(store, "FromJson").single()
        assertEquals(listOf("Int"), found.classParams.map { com.mezze.language.ast.SignatureNormalizer.render(it) })
        assertEquals("FromJson Int for Str", found.renderedWithParams("FromJson"))
    }

    @Test
    fun `a BLANKET impl matches every type, which is correct`() {
        // `impl Listable for 'a` genuinely applies to anything. Stated so a reader
        // does not mistake it for the receiver bug above.
        val listable = ability("Listable")
        impl(listable, receiver = varType("'a"))

        assertNotNull(AbilitySearch.implements(store, "Listable", MezzeType.IntType))
        assertNotNull(AbilitySearch.implements(store, "Listable", seqOf(MezzeType.StringType)))
    }

    // ---- several targets survive -------------------------------------------

    @Test
    fun `a multi-target impl keeps all of its targets`() {
        // 80 of the stdlib's 310 impls have two or three targets. They were stored
        // comma-joined in one column, so every reader had to split a string and none
        // could join.
        val numAdd = ability("NumAdd")
        impl(numAdd, receiver = MezzeType.IntType, classParams = listOf(MezzeType.FloatType, MezzeType.StringType))

        val found = AbilitySearch.implsOf(store, "NumAdd").single()
        assertEquals(3, found.targets.size)
        assertEquals("Int", found.rendered)
        assertEquals(listOf("Float", "Str"), found.classParams.map {
            com.mezze.language.ast.SignatureNormalizer.render(it)
        })
    }

    @Test
    fun `re-indexing an impl with fewer targets drops the old ones`() {
        val numAdd = ability("NumAdd")
        val implHash = impl(numAdd, receiver = MezzeType.IntType, classParams = listOf(MezzeType.FloatType))
        assertEquals(2, store.getImplTargets(implHash).size)

        val single = freshHash()
        store.putAbilityImpl(implHash, numAdd, listOf(single), "app")
        store.putSignature(SignatureIndex.flatten(single, MezzeType.IntType))

        assertEquals(1, store.getImplTargets(implHash).size,
            "targets are one value; a re-index must not leave the old tail")
    }

    // ---- identity, not spelling --------------------------------------------

    @Test
    fun `two abilities sharing a name do not merge`() {
        // The reason impls resolve through the DECLARATION. Two modules may each
        // declare `Show`; implementing one says nothing about the other.
        val mine = ability("Show", module = "mine")
        val theirs = ability("Show", module = "theirs")
        impl(mine, MezzeType.IntType, module = "mine")
        impl(theirs, MezzeType.StringType, module = "theirs")

        // Both declarations are named Show, so a name search legitimately finds both
        // sets — but each impl stays attached to the ability it was written for.
        assertEquals(listOf("Int"), AbilitySearch.implsOf(store, "Show")
            .filter { it.module == "mine" }.map { it.rendered })
        assertEquals(listOf("Str"), AbilitySearch.implsOf(store, "Show")
            .filter { it.module == "theirs" }.map { it.rendered })
        assertEquals(listOf(MezzeType.IntType), store.getAbilityImpls(mine)
            .flatMap { row -> store.getImplTargets(row.hash) }
            .map { SignatureIndex.rebuild(store.getTypeRows(it), store::getTypeRows) })
    }

    @Test
    fun `getAbilityImplForType keys on the receiver`() {
        val fromJson = ability("FromJson")
        val implHash = impl(fromJson, receiver = MezzeType.StringType, classParams = listOf(varType("'a")))
        val targets = store.getImplTargets(implHash)

        assertEquals(implHash, store.getAbilityImplForType(fromJson, targets.last()),
            "the receiver is the last target")
        assertNull(store.getAbilityImplForType(fromJson, targets.first()),
            "the class parameter is not what the impl is FOR")
    }
}
