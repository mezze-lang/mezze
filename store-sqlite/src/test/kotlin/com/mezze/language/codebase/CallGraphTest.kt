package com.mezze.language.codebase

import com.mezze.language.store.BlobKind
import com.mezze.language.store.DeclKind
import com.mezze.language.store.EdgeKind
import com.mezze.language.store.Visibility
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The call graph, transitively.
 *
 * `getEdgesFrom`/`getEdgesTo` already answered ONE hop, keyed by a hash. Neither
 * question people actually ask is one hop: "what breaks if I change this" reaches
 * the caller's caller, and "what does this need" reaches the whole closure.
 *
 * The hazard is termination. Mutual recursion is ordinary in real code, so a
 * traversal that does not dedup runs forever — `UNION` in the recursive term is
 * load-bearing, and the cyclic cases below are why.
 */
class CallGraphTest {

    private lateinit var store: SQLiteMezzeStore

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
        store.putModule("app", "app.mz", "sh")
    }

    @AfterEach fun tearDown() { store.close() }

    private var counter = 0

    /** A declaration, returning its hash. */
    private fun decl(name: String, visibility: Visibility = Visibility.PRIVATE): Hash {
        val hash = Hash("%064x".format(++counter))
        store.putBlob(hash, BlobKind.FUNCTION, visibility, null, null)
        store.putDecl(
            modulePath = "app", declName = name, declKind = DeclKind.LET,
            sourceHash = "sh", hash = hash,
            startLine = 1, startCol = 1, endLine = 1, endCol = 1,
        )
        return hash
    }

    private fun calls(from: Hash, to: Hash) = store.addEdge(from, to, EdgeKind.CALLS)

    private fun namesOf(hashes: List<Hash>) =
        hashes.mapNotNull { store.findDeclByHash(it)?.declName }.sorted()

    // ---- dependencies ------------------------------------------------------

    @Test
    fun `transitive dependencies reach past the first hop`() {
        val a = decl("a"); val b = decl("b"); val c = decl("c")
        calls(a, b); calls(b, c)

        val reached = store.transitiveEdgesFrom(a)
        assertEquals(listOf("b", "c"), namesOf(reached.map { it.hash }))
        assertEquals(1, reached.single { store.findDeclByHash(it.hash)?.declName == "b" }.depth)
        assertEquals(2, reached.single { store.findDeclByHash(it.hash)?.declName == "c" }.depth,
            "depth is the SHORTEST distance, which is what makes a result orderable")
    }

    @Test
    fun `the blast radius reaches the caller's caller`() {
        // The question to ask before editing `c`: `a` breaks just as surely as `b`.
        val a = decl("a"); val b = decl("b"); val c = decl("c")
        calls(a, b); calls(b, c)

        assertEquals(listOf("a", "b"), namesOf(store.transitiveEdgesTo(c).map { it.hash }))
    }

    @Test
    fun `a leaf has no dependencies and a root has no callers`() {
        val a = decl("a"); val b = decl("b")
        calls(a, b)
        assertTrue(store.transitiveEdgesFrom(b).isEmpty())
        assertTrue(store.transitiveEdgesTo(a).isEmpty())
    }

    // ---- termination -------------------------------------------------------

    @Test
    fun `mutual recursion terminates`() {
        // Without dedup this never returns. `b -> d -> b` is an ordinary pattern.
        val a = decl("a"); val b = decl("b"); val d = decl("d")
        calls(a, b); calls(b, d); calls(d, b)

        assertEquals(listOf("b", "d"), namesOf(store.transitiveEdgesFrom(a).map { it.hash }))
        assertEquals(listOf("a", "d"), namesOf(store.transitiveEdgesTo(b).map { it.hash }))
    }

    /**
     * A TIME LIMIT, because the failure mode here is not a wrong answer — it is a
     * query that never returns.
     *
     * The first implementation used `WITH RECURSIVE … UNION`, which dedups whole
     * rows: carrying a `depth` column made `(a,0)`, `(a,1)`, `(a,2)` … all distinct,
     * so a self-call generated rows forever. Without a deadline this test does not
     * fail, it hangs — and a suite that hangs reports nothing at all.
     */
    @Test
    @org.junit.jupiter.api.Timeout(value = 10, unit = java.util.concurrent.TimeUnit.SECONDS)
    fun `direct self-recursion terminates and does not report itself`() {
        val a = decl("a")
        calls(a, a)
        assertTrue(store.transitiveEdgesFrom(a).isEmpty(),
            "a function is not its own dependency, even when it calls itself")
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 10, unit = java.util.concurrent.TimeUnit.SECONDS)
    fun `a long cycle terminates`() {
        // Ten nodes in a ring. Cheap to state, and it would run forever under the
        // recursive-CTE formulation.
        val ring = (0 until 10).map { decl("n$it") }
        ring.forEachIndexed { i, h -> calls(h, ring[(i + 1) % ring.size]) }

        assertEquals(9, store.transitiveEdgesFrom(ring[0]).size,
            "every other node in the ring, each once, and not the start")
        assertEquals(9, store.transitiveEdgesTo(ring[0]).size)
    }

    @Test
    fun `a node in a cycle does not appear in its own closure`() {
        val a = decl("a"); val b = decl("b")
        calls(a, b); calls(b, a)
        assertEquals(listOf("b"), namesOf(store.transitiveEdgesFrom(a).map { it.hash }))
    }

    // ---- what the traversal follows ---------------------------------------

    @Test
    fun `edge kinds are respected`() {
        val a = decl("a"); val b = decl("b"); val c = decl("c")
        store.addEdge(a, b, EdgeKind.CALLS)
        store.addEdge(a, c, EdgeKind.IMPLEMENTS)

        assertEquals(listOf("b"), namesOf(store.transitiveEdgesFrom(a, setOf(EdgeKind.CALLS)).map { it.hash }))
        assertEquals(listOf("c"), namesOf(store.transitiveEdgesFrom(a, setOf(EdgeKind.IMPLEMENTS)).map { it.hash }))
        assertEquals(listOf("b", "c"),
            namesOf(store.transitiveEdgesFrom(a, setOf(EdgeKind.CALLS, EdgeKind.IMPLEMENTS)).map { it.hash }))
    }

    @Test
    fun `an empty kind set finds nothing rather than everything`() {
        val a = decl("a"); val b = decl("b")
        calls(a, b)
        assertTrue(store.transitiveEdgesFrom(a, emptySet()).isEmpty())
    }

    @Test
    fun `maxDepth bounds the search`() {
        val a = decl("a"); val b = decl("b"); val c = decl("c")
        calls(a, b); calls(b, c)

        assertEquals(listOf("b"), namesOf(store.transitiveEdgesFrom(a, maxDepth = 1).map { it.hash }))
        assertEquals(listOf("b", "c"), namesOf(store.transitiveEdgesFrom(a, maxDepth = 2).map { it.hash }))
        assertEquals(listOf("b", "c"), namesOf(store.transitiveEdgesFrom(a).map { it.hash }),
            "no bound means the whole closure, which is finite")
    }

    // ---- unreferenced declarations ----------------------------------------
    //
    // Based on SOURCE usages, not lowered call edges. The edge graph is built after
    // inlining, so a helper inlined into its callers has no inbound edge while being
    // plainly referenced — basing the report on edges named 18 stdlib declarations
    // as dead, one of which is referenced two lines below its own definition.

    /** A source reference to [target], at [line] of [file], written in some other declaration. */
    private fun referenceTo(target: Hash, file: String = "app.mz", line: Int = 500) =
        store.putUsage(target, file, line, 1, line, 10, fromHash = Hash("f".repeat(64)), isCall = true)

    @Test
    fun `a private declaration nothing references is reported`() {
        decl("orphan")
        val used = decl("used")
        referenceTo(used)

        assertEquals(listOf("orphan"), store.findUnreferencedDecls().map { it.declName })
    }

    @Test
    fun `a PUBLIC declaration is never reported`() {
        // It is reachable from outside this codebase, so "nothing references it here"
        // says nothing about whether it is dead.
        decl("exported", visibility = Visibility.PUBLIC)
        assertTrue(store.findUnreferencedDecls().isEmpty())
    }

    @Test
    fun `a SELF reference does not count as being used`() {
        // A private function that only calls itself is unreachable. The stdlib's
        // `seq::fold_go` is exactly this, and counting its own recursive call would
        // hide it.
        val recursive = decl("only_recurses")
        val row = store.findDeclByHash(recursive)!!
        // Written inside itself.
        store.putUsage(recursive, "app.mz", row.startLine, 5, row.startLine, 9, fromHash = recursive, isCall = true)

        assertEquals(listOf("only_recurses"), store.findUnreferencedDecls().map { it.declName },
            "a reference from inside its own body is not a use")
    }

    @Test
    fun `a reference from ELSEWHERE in the same file counts`() {
        val used = decl("used")
        val row = store.findDeclByHash(used)!!
        referenceTo(used, file = "app.mz", line = row.endLine + 50)

        assertTrue(store.findUnreferencedDecls().none { it.declName == "used" })
    }

    @Test
    fun `an inlined helper is NOT reported dead`() {
        // The regression. No call edge (it was inlined away) but a real source
        // reference, which is what makes it alive.
        val helper = decl("inlined_helper")
        referenceTo(helper, line = 900)
        assertTrue(store.findUnreferencedDecls().none { it.declName == "inlined_helper" },
            "an inlined helper has no call edge and is still referenced")
    }

    @Test
    fun `the report can be scoped to one module`() {
        decl("orphan")
        store.putModule("other", "other.mz", "sh")
        val otherHash = Hash("%064x".format(++counter))
        store.putBlob(otherHash, BlobKind.FUNCTION, Visibility.PRIVATE, null, null)
        store.putDecl(
            modulePath = "other", declName = "other_orphan", declKind = DeclKind.LET,
            sourceHash = "sh", hash = otherHash,
            startLine = 1, startCol = 1, endLine = 1, endCol = 1,
        )

        assertEquals(listOf("orphan"), store.findUnreferencedDecls("app").map { it.declName })
        assertEquals(listOf("other_orphan"), store.findUnreferencedDecls("other").map { it.declName })
    }
}
