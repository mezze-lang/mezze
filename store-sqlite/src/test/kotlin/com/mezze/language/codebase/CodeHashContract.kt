package com.mezze.language.codebase

import com.mezze.language.store.CodeHash
import com.mezze.language.store.InMemoryMezzeStore
import com.mezze.language.store.MezzeStore
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A function's code hash is what it lowered to, by any name, in any module.
 *
 * A declaration's identity answers "which declaration"; these hold the other
 * question — "what code" — to the properties that make it worth having: the same
 * code is one hash wherever and however it is written, a rename reaches no
 * caller, an impl nobody calls changes nothing, and different code is never one
 * hash.
 */
abstract class CodeHashContract {

    abstract fun open(): MezzeStore

    private lateinit var store: MezzeStore

    @BeforeEach fun setUp() { store = open() }
    @AfterEach fun tearDown() { store.close() }

    private fun identity(module: String, name: String): Hash =
        store.resolveName(module, name) ?: error("$module::$name was not indexed")

    private fun code(module: String, name: String): Hash =
        store.getCodeHash(identity(module, name)) ?: error("$module::$name has no code hash recorded")

    @Test
    fun `the same function in two modules under two names is one code`() {
        indexSource(store, "let double = { x: Int } -> x * 2", module = "a", parsedAs = "/home/one/a.mz")
        indexSource(store, "# elsewhere\n\n\nlet twice = { x: Int } -> x * 2", module = "b", parsedAs = "/srv/two/b.mz")

        assertNotEquals(identity("a", "double"), identity("b", "twice"), "two declarations are two identities")
        assertEquals(code("a", "double"), code("b", "twice"))
        assertEquals(
            setOf(identity("a", "double"), identity("b", "twice")),
            store.getHashesForCode(code("a", "double")).toSet(),
        )
        assertNotNull(store.getIRForCode(code("a", "double")))
    }

    @Test
    fun `different code is a different code hash`() {
        indexSource(store, "let double = { x: Int } -> x * 2\nlet triple = { x: Int } -> x * 3", module = "a")
        assertNotEquals(code("a", "double"), code("a", "triple"))
    }

    @Test
    fun `renaming a function changes no caller`() {
        // Recursive, so the caller really calls it rather than having it inlined.
        fun program(helper: String) = """
            let $helper = { n: Int, acc: Int } -> if n <= 0 then acc else $helper { n = n - 1, acc = acc + n }
            let sum_to = { n: Int } -> $helper { n = n, acc = 0 }
        """.trimIndent()
        indexSource(store, program("go"), module = "a")
        indexSource(store, program("accumulate"), module = "b")

        assertNotEquals(identity("a", "sum_to"), identity("b", "sum_to"), "the caller's text changed, so its identity did")
        assertEquals(code("a", "go"), code("b", "accumulate"))
        assertEquals(code("a", "sum_to"), code("b", "sum_to"))
    }

    @Test
    fun `a caller of different code is different code`() {
        fun program(step: String) = """
            let go = { n: Int, acc: Int } -> if n <= 0 then acc else go { n = n - 1, acc = acc + $step }
            let sum_to = { n: Int } -> go { n = n, acc = 0 }
        """.trimIndent()
        indexSource(store, program("n"), module = "a")
        indexSource(store, program("1"), module = "b")
        assertNotEquals(code("a", "go"), code("b", "go"))
        assertNotEquals(code("a", "sum_to"), code("b", "sum_to"), "it calls different code, though its own text is the same")
    }

    /**
     * What a pre-typecheck identity cannot do. `a + b` is hashed as every `NumAdd`
     * impl in scope, since which one answers is not known yet — so an impl for a
     * type this function never touches changes its identity. Its CODE names only
     * the impl it resolved to.
     */
    @Test
    fun `an impl the function does not call changes its identity and not its code`() {
        val total = "let total = { a: Int, b: Int } -> a + b"
        indexSource(store, total, module = "plain")
        indexSource(store, """
            use std::abilities::numeric::{ NumAdd }

            newtype Money = Int
            impl NumAdd Money for Money having 'out = Money where {
              add = { it, r } -> match it is
                Money.(x) -> match r is
                  Money.(y) -> Money.(x + y)
            }

            $total
        """.trimIndent(), module = "with_money")

        assertNotEquals(identity("plain", "total"), identity("with_money", "total"), "another impl of `+` is in scope")
        assertEquals(code("plain", "total"), code("with_money", "total"))
    }

    @Test
    fun `mutually recursive functions keep one code under other names`() {
        fun program(even: String, odd: String) = """
            let $even = { n: Int } -> if n == 0 then True else $odd { n = n - 1 }
            let $odd = { n: Int } -> if n == 0 then False else $even { n = n - 1 }
        """.trimIndent()
        indexSource(store, program("is_even", "is_odd"), module = "a")
        indexSource(store, program("ping", "pong"), module = "b")
        assertEquals(code("a", "is_even"), code("b", "ping"))
        assertEquals(code("a", "is_odd"), code("b", "pong"))
        assertNotEquals(code("a", "is_even"), code("a", "is_odd"))
    }
}

class SQLiteCodeHashTest : CodeHashContract() {
    override fun open(): MezzeStore = SQLiteMezzeStore.inMemory()
}

class InMemoryCodeHashTest : CodeHashContract() {
    override fun open(): MezzeStore = InMemoryMezzeStore()
}

/** [CodeHash] on functions built by hand, where the shape of the calls is the whole test. */
class CodeHashGroupTest {
    private val src = SourceInfo(0, 1, 1, 1, 1)
    private fun hash(c: Char) = Hash(c.toString().repeat(128))

    /** A function that does [work] and then calls [callee]. */
    private fun fn(self: Hash, work: Long, callee: Hash) = FunctionIR(
        hash = self, name = "f", qualifiedName = "m::f",
        paramSlots = emptyList(), paramNames = emptyList(),
        localSlots = emptyList(), localNames = emptyList(),
        captureSlots = emptyList(), captureNames = emptyList(),
        body = CallDirectIR(callee, listOf(LongLitIR(work, src)), false, src),
        sourceFile = "m.mz", sourceSection = src,
    )

    /**
     * One cycle f → g → h → k → f. `f` and `h` do the same work and each calls
     * "another member of the cycle" — but f's callee does 2 and h's does 3, so they
     * are different code. Blanking calls inside the group and stopping there would
     * make them one.
     */
    @Test
    fun `two members of one cycle that differ only in what their callee does are different code`() {
        val (f, g, h, k) = listOf('a', 'b', 'c', 'd').map { hash(it) }
        val codes = CodeHash.of(listOf(fn(f, 1, g), fn(g, 2, h), fn(h, 1, k), fn(k, 3, f)))
        assertEquals(4, codes.values.toSet().size, "every member of this cycle is different code: $codes")
    }

    @Test
    fun `a cycle is the same code whatever its members are called and however they are listed`() {
        val first = CodeHash.of(listOf(fn(hash('a'), 1, hash('b')), fn(hash('b'), 2, hash('a'))))
        val second = CodeHash.of(listOf(fn(hash('e'), 2, hash('f')), fn(hash('f'), 1, hash('e'))))
        assertEquals(first.getValue(hash('a')), second.getValue(hash('f')))
        assertEquals(first.getValue(hash('b')), second.getValue(hash('e')))
        assertNotEquals(first.getValue(hash('a')), first.getValue(hash('b')))
    }

    @Test
    fun `members no call can tell apart are one code`() {
        val codes = CodeHash.of(listOf(fn(hash('a'), 1, hash('b')), fn(hash('b'), 1, hash('a'))))
        assertEquals(codes.getValue(hash('a')), codes.getValue(hash('b')))
    }

    @Test
    fun `a callee outside the set is named by its code when that is known`() {
        val callee = hash('c')
        val caller = fn(hash('a'), 1, callee)
        val unknown = CodeHash.of(caller)
        val known = CodeHash.of(caller) { if (it == callee) hash('9') else null }
        val renamed = CodeHash.of(fn(hash('b'), 1, hash('d'))) { if (it == hash('d')) hash('9') else null }
        assertNotEquals(unknown, known)
        assertEquals(known, renamed, "the same code calling the same code, under two pairs of names")
        assertTrue(CodeHash.of(caller) { null } == unknown)
    }
}
