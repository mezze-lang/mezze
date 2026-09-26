package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.ParseException
import com.mezze.language.store.*
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Searching for a declaration by its TYPE.
 *
 * The cases that matter are the ones where a cheaper implementation silently
 * returns nothing: a query with variables against a concrete signature, a
 * concrete query against a polymorphic signature, and a curried candidate whose
 * arrow count does not match the query's. Each of those is a plausible
 * "optimisation" away from correctness, so each is a test.
 */
class TypeSearchTest {

    private lateinit var store: SQLiteMezzeStore

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
        store.putModule("app", "app.mz", "srchash")
    }

    @AfterEach fun tearDown() { store.close() }

    private var counter = 0

    private fun decl(name: String, type: MezzeType) {
        val hash = Hash("%064x".format(++counter))
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putDecl(
            modulePath = "app", declName = name, declKind = DeclKind.LET,
            sourceHash = "srchash", hash = hash,
            startLine = 1, startCol = 1, endLine = 1, endCol = 10,
        )
        store.putSignature(SignatureIndex.flatten(hash, type))
    }

    private fun found(query: String) = TypeSearch.search(store, query).map { it.decl.declName }.sorted()
    private fun hit(query: String, name: String) =
        TypeSearch.search(store, query).single { it.decl.declName == name }

    // ---- the query is a type, parsed by the real parser ---------------------

    @Test
    fun `a query is parsed as a type`() {
        val parsed = TypeSearch.parseQuery("{ x: Int } -> Str")
        assertTrue(parsed is MezzeType.FunctionType, "got $parsed")
        assertEquals(MezzeType.StringType, (parsed as MezzeType.FunctionType).returnType)
    }

    @Test
    fun `a query that is not a type is rejected, not guessed at`() {
        assertFailsWith<ParseException> { TypeSearch.parseQuery("let x = 1") }
    }

    @Test
    fun `trailing junk after the type is rejected`() {
        // Parsing the prefix would answer a question nobody asked.
        assertFailsWith<ParseException> { TypeSearch.parseQuery("Int -> Int and then some") }
    }

    // ---- matching, both directions -----------------------------------------

    @Test
    fun `an exact signature is found and ranked EXACT`() {
        decl("add", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("x" to MezzeType.IntType, "y" to MezzeType.IntType)),
            returnType = MezzeType.IntType,
        ))
        assertEquals(TypeSearch.Quality.EXACT, hit("{ x: Int, y: Int } -> Int", "add").quality)
    }

    @Test
    fun `field order in the query does not matter`() {
        // Mezze records are named. This is why signature search here avoids the
        // argument-permutation problem that costs Hoogle real effort.
        decl("add", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("x" to MezzeType.IntType, "y" to MezzeType.IntType)),
            returnType = MezzeType.IntType,
        ))
        assertEquals(listOf("add"), found("{ y: Int, x: Int } -> Int"))
    }

    @Test
    fun `a polymorphic query finds a concrete function`() {
        // "something that takes an x and gives back the same type" must find
        // `{x: Int} -> Int`.
        decl("identity_int", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("x" to MezzeType.IntType)),
            returnType = MezzeType.IntType,
        ))
        val h = hit("{ x: 'a } -> 'a", "identity_int")
        assertEquals(TypeSearch.Quality.MORE_SPECIFIC, h.quality,
            "the candidate is narrower than what was asked for")
    }

    @Test
    fun `a concrete query finds a polymorphic function`() {
        // The other direction, and the one a cheap implementation loses: `id`
        // can do the specific job, so asking for the specific job must find it.
        decl("id", run {
            val a = MezzeType.VarType(com.mezze.language.ast.TypeVar.freshWithName("'a"))
            MezzeType.FunctionType(
                paramType = MezzeType.RecordType(linkedMapOf("x" to a)),
                returnType = a,
            )
        })
        val h = hit("{ x: Str } -> Str", "id")
        assertEquals(TypeSearch.Quality.MORE_GENERAL, h.quality,
            "`id` is more general than the query; it still answers it")
    }

    @Test
    fun `a signature that cannot unify is not returned`() {
        decl("to_str", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("x" to MezzeType.IntType)),
            returnType = MezzeType.StringType,
        ))
        assertTrue(found("{ x: Int } -> Int").isEmpty(),
            "`{x: Int} -> Str` does not answer `{x: Int} -> Int`")
    }

    @Test
    fun `the identity shape does NOT match a wrapping function`() {
        // `{x: 'a} -> Deque 'a` cannot be the identity function: unifying `'a` with
        // `Deque 'a` is an infinite type, which the occurs check rejects.
        //
        // This was live and no unit test saw it. The index rebuilt each occurrence
        // of a name as its OWN variable, so the candidate arrived as
        // `{x: 'b} -> Deque 'c` — two unrelated variables, nothing to occurs-check
        // — and a search for the identity shape returned every `singleton` in the
        // stdlib, each labelled "more general than your query". Found by running
        // the command, not by the suite.
        decl("singleton", run {
            val a = MezzeType.VarType(com.mezze.language.ast.TypeVar.freshWithName("'a"))
            MezzeType.FunctionType(
                paramType = MezzeType.RecordType(linkedMapOf("x" to a)),
                returnType = MezzeType.TypeApplication("Deque", listOf(a)),
            )
        })
        decl("id", run {
            val a = MezzeType.VarType(com.mezze.language.ast.TypeVar.freshWithName("'a"))
            MezzeType.FunctionType(
                paramType = MezzeType.RecordType(linkedMapOf("x" to a)),
                returnType = a,
            )
        })

        assertEquals(listOf("id"), found("{ x: 'a } -> 'a"),
            "a function that WRAPS its argument does not answer a query for one that returns it")
    }

    // ---- the unsound pre-filters, stated as tests ---------------------------

    @Test
    fun `a query with a variable return finds a CURRIED candidate`() {
        // Filtering candidates by arrow count is the obvious optimisation and it
        // is wrong: `'r` unifies with the inner arrow.
        decl("curried", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("a" to MezzeType.IntType)),
            returnType = MezzeType.FunctionType(
                paramType = MezzeType.RecordType(linkedMapOf("b" to MezzeType.IntType)),
                returnType = MezzeType.IntType,
            ),
        ))
        assertEquals(listOf("curried"), found("{ a: Int } -> 'r"),
            "one arrow in the query can match two in the candidate")
    }

    @Test
    fun `a query naming a type finds a candidate that names NO types`() {
        // Filtering on shared head symbols is the other obvious optimisation, and
        // it is wrong for the same reason: a candidate variable matches anything.
        decl("id", run {
            val a = MezzeType.VarType(com.mezze.language.ast.TypeVar.freshWithName("'a"))
            MezzeType.FunctionType(paramType = a, returnType = a)
        })
        assertEquals(listOf("id"), found("Str -> Str"),
            "`'a -> 'a` mentions no Str, and still answers a query about Str")
    }

    // ---- effects and constraints are sound requirements ---------------------

    @Test
    fun `a query naming an effect only finds functions performing it`() {
        val unit = MezzeType.RecordType(emptyMap())
        decl("shout", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("msg" to MezzeType.StringType)),
            returnType = unit,
            effects = MezzeType.EffectRow.simple(setOf("Log")),
        ))
        decl("quiet", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("msg" to MezzeType.StringType)),
            returnType = unit,
        ))

        assertEquals(listOf("shout"), found("{ msg: Str } -> {} <Log>"))
    }

    @Test
    fun `a query with no effect clause still finds effectful functions`() {
        // The effect row narrows only when the QUERY names effects. Asking for a
        // shape without mentioning effects is not asking for a pure function —
        // otherwise every search would have to spell out the effects to work.
        val unit = MezzeType.RecordType(emptyMap())
        decl("shout", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("msg" to MezzeType.StringType)),
            returnType = unit,
            effects = MezzeType.EffectRow.simple(setOf("Log")),
        ))
        assertTrue("shout" in found("{ msg: Str } -> {}"),
            "a query silent about effects must not exclude effectful functions")
    }

    @Test
    fun `an effect in the query that nothing performs finds nothing`() {
        decl("shout", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("msg" to MezzeType.StringType)),
            returnType = MezzeType.RecordType(emptyMap()),
            effects = MezzeType.EffectRow.simple(setOf("Log")),
        ))
        assertTrue(found("{ msg: Str } -> {} <FileSystem>").isEmpty())
    }

    @Test
    fun `a query naming TWO requirements needs both, not either`() {
        // The candidate sets are intersected. A union would return anything
        // matching one requirement — which reads as a working search while
        // quietly answering a weaker question than the one asked.
        val a = MezzeType.VarType(com.mezze.language.ast.TypeVar.freshWithName("'a"))
        fun sig(effects: Set<String>, abilities: List<String>) = MezzeType.FunctionType(
            paramType = a,
            returnType = MezzeType.RecordType(emptyMap()),
            effects = if (effects.isEmpty()) null else MezzeType.EffectRow.simple(effects),
            abilityConstraints = abilities.map { MezzeType.FunctionAbilityConstraint(it) },
        )
        decl("both", sig(setOf("Log"), listOf("Ord")))
        decl("only_effect", sig(setOf("Log"), emptyList()))
        decl("only_constraint", sig(emptySet(), listOf("Ord")))

        assertEquals(listOf("both"), found("'a with Ord 'a -> {} <Log>"))
    }

    // ---- ranking ------------------------------------------------------------

    @Test
    fun `an exact match outranks a merely unifiable one`() {
        val a = MezzeType.VarType(com.mezze.language.ast.TypeVar.freshWithName("'a"))
        decl("exact", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("x" to MezzeType.IntType)),
            returnType = MezzeType.IntType,
        ))
        decl("general", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("x" to a)),
            returnType = a,
        ))

        val results = TypeSearch.search(store, "{ x: Int } -> Int")
        assertEquals(listOf("exact", "general"), results.map { it.decl.declName },
            "the signature the user typed comes first")
        assertEquals(TypeSearch.Quality.EXACT, results.first().quality)
    }

    @Test
    fun `variable naming does not affect an exact match`() {
        // `{x: 'zzz} -> 'zzz` and `{x: 'a} -> 'a` are the same signature; a
        // rename is not an instantiation.
        decl("id", run {
            val v = MezzeType.VarType(com.mezze.language.ast.TypeVar.freshWithName("'zzz"))
            MezzeType.FunctionType(
                paramType = MezzeType.RecordType(linkedMapOf("x" to v)),
                returnType = v,
            )
        })
        assertEquals(TypeSearch.Quality.EXACT, hit("{ x: 'a } -> 'a", "id").quality,
            "the name a user picked for a variable is not information")
    }

    @Test
    fun `a hit carries the candidate's rendered signature`() {
        decl("add", MezzeType.FunctionType(
            paramType = MezzeType.RecordType(linkedMapOf("x" to MezzeType.IntType, "y" to MezzeType.IntType)),
            returnType = MezzeType.IntType,
        ))
        assertEquals("{ x: Int, y: Int } -> Int", hit("{ x: Int, y: Int } -> Int", "add").signature,
            "the result must show what it found, rebuilt from the index")
    }

    @Test
    fun `the limit is honoured`() {
        repeat(30) { i ->
            decl("f$i", MezzeType.FunctionType(
                paramType = MezzeType.RecordType(linkedMapOf("x" to MezzeType.IntType)),
                returnType = MezzeType.IntType,
            ))
        }
        assertEquals(5, TypeSearch.search(store, "{ x: Int } -> Int", limit = 5).size)
    }

    @Test
    fun `a decl with no indexed signature is never a hit`() {
        val hash = Hash("%064x".format(999))
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putDecl(
            modulePath = "app", declName = "unindexed", declKind = DeclKind.LET,
            sourceHash = "srchash", hash = hash,
            startLine = 1, startCol = 1, endLine = 1, endCol = 10,
        )
        assertTrue(found("{ x: 'a } -> 'a").isEmpty(),
            "no signature means no match — not a match against an assumed one")
    }
}
