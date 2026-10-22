package com.mezze.language.codebase

import com.mezze.language.store.InMemoryMezzeStore
import com.mezze.language.store.MezzeStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Every written expression node is indexed by its `std::syntax` kind and what it
 * spells, so "every `==`", "every pipe", "every use of `double` as written" are
 * one lookup — not a walk over every body.
 */
abstract class OccurrenceIndexContract {

    abstract fun open(): MezzeStore

    private lateinit var store: MezzeStore

    @BeforeEach fun setUp() { store = open() }
    @AfterEach fun tearDown() { store.close() }

    private val source = """
        let double: { x: Int } -> Int = { x } -> x * 2
        let same: { a: Int, b: Int } -> Bool = { a, b } -> a == b
        let piped: {} -> Int = {} -> 5 |> double { x = @ }
        let also: { s: Str } -> Bool = { s } -> s == "x"
    """.trimIndent()

    @Test
    fun `nodes are found by kind and by what they spell`() {
        indexSource(store, source)
        val eqs = store.findOccurrences("BinaryExpr", "==")
        assertEquals(2, eqs.size, "$eqs")
        assertEquals(1, store.findOccurrences("BinaryExpr", "*").size)
        assertEquals(3, store.findOccurrences("BinaryExpr", null).size, "every operator")
        assertEquals(1, store.findOccurrences("PipeExpr", null).size)
        assertEquals(1, store.findOccurrences("PlaceholderExpr", null).size)
        assertEquals(1, store.findOccurrences("NameExpr", "double").size, "written once, in `piped`")

        val owners = eqs.map { it.ownerHash }.toSet()
        val names = store.getDeclsForModule("app").filter { it.hash in owners }.map { it.declName }.toSet()
        assertEquals(setOf("same", "also"), names)
    }

    @Test
    fun `re-indexing a module replaces what it had`() {
        indexSource(store, source)
        indexSource(store, "let only: { a: Int } -> Int = { a } -> a + 1")
        assertTrue(store.findOccurrences("BinaryExpr", "==").isEmpty())
        assertEquals(1, store.findOccurrences("BinaryExpr", "+").size)
    }
}

class SQLiteOccurrenceIndexTest : OccurrenceIndexContract() {
    override fun open(): MezzeStore = SQLiteMezzeStore.inMemory()
}

class InMemoryOccurrenceIndexTest : OccurrenceIndexContract() {
    override fun open(): MezzeStore = InMemoryMezzeStore()
}
