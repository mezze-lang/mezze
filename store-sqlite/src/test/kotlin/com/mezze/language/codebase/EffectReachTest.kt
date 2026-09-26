package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.store.BlobKind
import com.mezze.language.store.DeclKind
import com.mezze.language.store.EdgeKind
import com.mezze.language.store.SignatureIndex
import com.mezze.language.store.Visibility
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What a function ACTUALLY performs, versus what its own signature declares.
 *
 * The declared row is already indexed, so "does this log?" was answerable. The
 * question that was not: a request handler whose signature says `<Log>` may reach
 * `FileSystem` three calls down, and no single row says so. That matters twice —
 * deciding which handler an entry point needs, and noticing in review that
 * something which used to be pure now reaches the network.
 *
 * Every effect is attributed to the NEAREST declaration that declares it, because
 * "your handler reaches FileSystem" is only actionable with "…through
 * `read_config`, two calls away".
 */
class EffectReachTest {

    private lateinit var store: SQLiteMezzeStore

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
        store.putModule("app", "app.mz", "sh")
    }

    @AfterEach fun tearDown() { store.close() }

    private var counter = 0

    /** A declaration whose residual arrow performs [effects]. */
    private fun decl(name: String, vararg effects: String): Hash {
        val hash = Hash("%064x".format(++counter))
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putDecl(
            modulePath = "app", declName = name, declKind = DeclKind.LET,
            sourceHash = "sh", hash = hash,
            startLine = 1, startCol = 1, endLine = 1, endCol = 1,
        )
        val type = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.RecordType(emptyMap()),
            effects = if (effects.isEmpty()) null
            else MezzeType.EffectRow.simple(effects.toSet()),
        )
        store.putSignature(SignatureIndex.flatten(hash, type))
        return hash
    }

    private fun calls(from: Hash, to: Hash) = store.addEdge(from, to, EdgeKind.CALLS)

    private fun effectsOf(h: Hash) = EffectReach.of(store, h).map { it.effect }.sorted()

    // ---- the declared row, which was already answerable ---------------------

    @Test
    fun `an effect on its own signature is reported at depth zero`() {
        val shout = decl("shout", "Log")
        val found = EffectReach.of(store, shout).single()
        assertEquals("Log", found.effect)
        assertEquals(0, found.depth)
        assertEquals("shout", found.source?.declName)
    }

    @Test
    fun `a pure function with no callees reaches nothing`() {
        assertTrue(EffectReach.of(store, decl("pure")).isEmpty())
    }

    // ---- the transitive part, which was not ---------------------------------

    @Test
    fun `an effect two calls away is reached`() {
        // `handler` declares nothing. The effect is real and its signature is silent.
        val handler = decl("handler")
        val middle = decl("middle")
        val leaf = decl("leaf", "FileSystem")
        calls(handler, middle); calls(middle, leaf)

        assertEquals(listOf("FileSystem"), effectsOf(handler))
        val found = EffectReach.of(store, handler).single()
        assertEquals(2, found.depth)
        assertEquals("leaf", found.source?.declName, "attributed to the declaration responsible")
    }

    @Test
    fun `effects from several callees are unioned`() {
        val handler = decl("handler", "Log")
        val a = decl("a", "FileSystem")
        val b = decl("b", "Network")
        calls(handler, a); calls(handler, b)

        assertEquals(listOf("FileSystem", "Log", "Network"), effectsOf(handler))
    }

    @Test
    fun `the NEAREST declaration is credited for an effect`() {
        // Both `near` and `far` perform Log. The useful explanation is the closer one.
        val start = decl("start")
        val near = decl("near", "Log")
        val far = decl("far", "Log")
        calls(start, near); calls(near, far)

        val log = EffectReach.of(store, start).single { it.effect == "Log" }
        assertEquals(1, log.depth)
        assertEquals("near", log.source?.declName)
    }

    @Test
    fun `a cycle in the call graph does not loop`() {
        val a = decl("a"); val b = decl("b", "Log")
        calls(a, b); calls(b, a)
        assertEquals(listOf("Log"), effectsOf(a))
    }

    @Test
    fun `REFERENCES edges count, not only CALLS`() {
        // A function that merely references another can still hand it somewhere it
        // gets run. Excluding references would UNDER-report, and under-reporting
        // effects makes a program look safer than it is.
        val holder = decl("holder")
        val effectful = decl("effectful", "Network")
        store.addEdge(holder, effectful, EdgeKind.REFERENCES)

        assertEquals(listOf("Network"), effectsOf(holder))
    }

    @Test
    fun `maxDepth bounds the reach`() {
        val a = decl("a"); val b = decl("b"); val c = decl("c", "Log")
        calls(a, b); calls(b, c)

        assertTrue(EffectReach.of(store, a, maxDepth = 1).isEmpty(),
            "Log is two hops away")
        assertEquals(listOf("Log"), EffectReach.of(store, a, maxDepth = 2).map { it.effect })
    }

    // ---- what the signature does NOT say -----------------------------------

    @Test
    fun `undeclared reports only what the signature omits`() {
        val handler = decl("handler", "Log")
        val leaf = decl("leaf", "FileSystem")
        calls(handler, leaf)

        assertEquals(listOf("FileSystem"), EffectReach.undeclared(store, handler).map { it.effect },
            "`Log` is visible in the signature already; `FileSystem` is the finding")
    }

    @Test
    fun `undeclared is empty when the signature already says everything`() {
        val handler = decl("handler", "Log")
        val leaf = decl("leaf", "Log")
        calls(handler, leaf)
        assertTrue(EffectReach.undeclared(store, handler).isEmpty())
    }

    @Test
    fun `a callee with no indexed signature is skipped, not guessed`() {
        // A decl with no signature contributes no effects. Inventing "unknown, assume
        // pure" and inventing "unknown, assume everything" are both wrong; reporting
        // what is known is right.
        val handler = decl("handler")
        val unindexed = Hash("%064x".format(++counter))
        store.putBlob(unindexed, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        calls(handler, unindexed)

        assertTrue(EffectReach.of(store, handler).isEmpty())
    }
}
