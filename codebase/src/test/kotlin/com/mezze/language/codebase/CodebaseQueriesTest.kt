package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.store.DeclKind
import com.mezze.language.store.Definition
import com.mezze.language.store.InMemoryMezzeStore
import com.mezze.language.store.MezzeStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The codebase questions, over real type-checked code, on every store.
 *
 * The same contract runs against SQLite and the in-memory store: a query layer
 * built on the abstraction must not care which one answers.
 */
abstract class CodebaseQueriesContract {

    abstract fun open(): MezzeStore

    @TempDir lateinit var dir: File

    private lateinit var store: MezzeStore
    private lateinit var q: CodebaseQueries

    private val source = """
        ## The module under test.
        use std::effects::log::{ println, Log }

        ## Something that can be totalled.
        ability Total for 'a where { total: { it: 'a } -> Int }
        effect Ping where { ping: {} -> Int <Ping> }

        type Ticket = { n: Int, label: Str }
        type Order = { m: Int }
        impl Total for Ticket where { total = { it } -> it.n }
        impl Total for Order where { total = { it } -> it.m }

        ## Says something.
        pub let shout: { msg: Str } -> {} <Log> = { msg } -> println { msg = msg }
        pub let pinged: {} -> Int <Ping> = {} -> Ping.ping {}
        pub let sum: { x: 'a } with Total 'a -> Int = { x } -> x.total {}
        let twice: { msg: Str } -> {} <Log> = { msg } -> do
          shout { msg = msg }
          shout { msg = msg }
        let keep: {} -> { msg: Str } -> {} <Log> = {} -> shout
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        val file = File(dir, "app.mz").apply { writeText(source) }
        store = open()
        indexSource(store, source, filePath = file.absolutePath)
        q = CodebaseQueries(store)
    }

    @AfterEach fun tearDown() { store.close() }

    private fun ref(name: String) = q.resolve("app", name) ?: error("no declaration named $name")

    @Test
    fun `a name is resolved once, to a declaration`() {
        val shout = ref("shout")
        assertEquals("shout", shout.name)
        assertEquals("app", shout.module)
        assertEquals(DeclKind.LET, shout.kind)
        assertNull(q.resolve("app", "no_such_thing"))
    }

    @Test
    fun `a declaration's docs, visibility and span`() {
        val info = q.get(ref("shout").hash)!!
        assertEquals("Says something.", info.doc?.trim())
        assertTrue(info.isPublic)
        assertEquals(false, q.get(ref("twice").hash)!!.isPublic)
        assertEquals(com.mezze.language.store.StoreTier.PROJECT, info.origin)
    }

    // ── search ───────────────────────────────────────────────────────────

    private fun names(found: List<CodebaseQueries.DeclRef>) = found.map { it.name }

    @Test
    fun `search by kind, module and name prefix`() {
        val found = q.search(CodebaseQueries.Query(kind = DeclKind.LET, modulePrefix = "app", namePrefix = "s"))
        assertEquals(listOf("shout", "sum"), names(found))
    }

    @Test
    fun `search by effect and by ability, by declaration`() {
        assertEquals(listOf("pinged"), names(q.search(CodebaseQueries.Query(performs = ref("Ping").hash))))
        assertEquals(listOf("sum"), names(q.search(CodebaseQueries.Query(requires = ref("Total").hash))))
    }

    @Test
    fun `search for pure declarations leaves out every effectful one`() {
        val pure = names(q.search(CodebaseQueries.Query(kind = DeclKind.LET, pureOnly = true)))
        assertTrue("sum" in pure, "got $pure")
        assertTrue(pure.none { it in setOf("shout", "pinged", "twice") }, "got $pure")
    }

    @Test
    fun `search by text reads docs as well as names`() {
        assertEquals(listOf("shout"), names(q.search(CodebaseQueries.Query(text = "says something"))))
    }

    @Test
    fun `search for public, documented and unused declarations`() {
        assertEquals(listOf("pinged", "shout", "sum"), names(q.search(CodebaseQueries.Query(kind = DeclKind.LET, publicOnly = true))))
        assertEquals(listOf("Total", "shout"), names(q.search(CodebaseQueries.Query(documentedOnly = true))))
        val unused = names(q.search(CodebaseQueries.Query(kind = DeclKind.LET, unusedOnly = true)))
        assertTrue("shout" !in unused && "twice" in unused, "got $unused")
    }

    @Test
    fun `search by shape is type-directed`() {
        assertEquals(listOf("pinged"), names(q.search(CodebaseQueries.Query(shape = "{} -> Int <Ping>"))))
    }

    @Test
    fun `search orders by name and takes a limit`() {
        val found = q.search(CodebaseQueries.Query(kind = DeclKind.LET, order = CodebaseQueries.Order.BY_NAME, limit = 2))
        assertEquals(listOf("keep", "pinged"), names(found))
    }

    // ── abilities of a type ──────────────────────────────────────────────

    @Test
    fun `the abilities written for a type`() {
        val ticket = q.implsOf(ref("Total").hash).first().targets.last()
        val abilities = q.abilitiesOf(ticket)
        assertTrue(abilities.any { it.ability?.name == "Total" && it.via == CodebaseQueries.Via.WRITTEN }, "got $abilities")
    }

    @Test
    fun `a module's own documentation is its leading block`() {
        assertEquals("The module under test.", q.moduleDoc("app"))
    }

    @Test
    fun `the source of a declaration is its slice of the file`() {
        val text = q.source(ref("pinged").hash)
        assertNotNull(text)
        assertEquals("let pinged: {} -> Int <Ping> = {} -> Ping.ping {}", text)
    }

    @Test
    fun `a function's signature is its type`() {
        val sig = q.signature(ref("pinged").hash) as MezzeType.FunctionType
        assertEquals(MezzeType.IntType, sig.returnType)
    }

    @Test
    fun `a type's definition lists its fields`() {
        val def = q.definition(ref("Ticket").hash) as Definition.Record
        assertEquals(listOf("n", "label"), def.fields.map { it.name })
    }

    @Test
    fun `impls are found from the ability and from the type`() {
        val total = ref("Total")
        val impls = q.implsOf(total.hash)
        assertEquals(2, impls.size)
        assertTrue(impls.all { it.ability == total })
        for (impl in impls) {
            assertEquals(listOf(impl.hash), q.implsFor(impl.targets.last()).map { it.hash })
        }
    }

    @Test
    fun `effects are reported with the declaration they name`() {
        val effects = q.effectsOf(ref("pinged").hash)
        assertEquals(listOf("Ping"), effects.map { it.name })
        assertEquals(ref("Ping"), effects.single().decl)
        assertEquals(emptyList<CodebaseQueries.EffectRef>(), q.effectsOf(ref("sum").hash))
    }

    @Test
    fun `who performs an effect and who requires an ability`() {
        assertEquals(listOf("pinged"), q.performing(ref("Ping").hash).map { it.name })
        assertEquals(listOf("sum"), q.requiring(ref("Total").hash).map { it.name })
    }

    @Test
    fun `callers and callees follow the SOURCE, even where lowering inlined the call`() {
        // `shout` is small enough to be inlined into `twice`, so the lowered edge
        // graph has no `twice -> shout` call at all. The source does.
        assertEquals(listOf("shout"), q.callees(ref("twice").hash).map { it.name })
        assertEquals(listOf("twice"), q.callers(ref("shout").hash).map { it.name })
    }

    @Test
    fun `a value mention is a reference but not a call`() {
        assertEquals(listOf("keep"), q.references(ref("shout").hash).map { it.name }.filter { it == "keep" })
        assertTrue(q.callers(ref("shout").hash).none { it.name == "keep" })
    }

    @Test
    fun `usages are listed in file order`() {
        val spans = q.usages(ref("shout").hash)
        assertEquals(3, spans.size)
        assertEquals(spans.sortedBy { it.startLine }, spans)
    }

    @Test
    fun `answers come back in one fixed order`() {
        val names = q.declsIn("app").map { it.name }
        assertEquals(names.sorted(), names)
    }
}

class SQLiteCodebaseQueriesTest : CodebaseQueriesContract() {
    override fun open(): MezzeStore = com.mezze.language.codebase.SQLiteMezzeStore.inMemory()
}

class InMemoryCodebaseQueriesTest : CodebaseQueriesContract() {
    override fun open(): MezzeStore = InMemoryMezzeStore()
}
