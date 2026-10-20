package com.mezze.language.codebase

import com.mezze.language.store.DeclKind
import com.mezze.language.store.MezzeStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The identity lookups against what the compiler actually writes.
 *
 * `IdentityLookupContract` proves the queries over hand-built rows. This proves
 * the rows a real type check produces carry the declaration hashes those queries
 * join on. Without them every query by declaration would quietly come back empty.
 */
class IdentityLookupEndToEndTest {

    private lateinit var store: MezzeStore

    private val source = """
        use std::effects::log::{ println, Log }

        ability Total for 'a where { total: { it: 'a } -> Int }
        effect Ping where { ping: {} -> Int <Ping> }

        type Ticket = { n: Int }
        type Order = { m: Int }
        impl Total for Ticket where { total = { it } -> it.n }
        impl Total for Order where { total = { it } -> it.m }

        pub let shout: { msg: Str } -> {} <Log> = { msg } -> println { msg = msg }
        pub let pinged: {} -> Int <Ping> = {} -> Ping.ping {}
        pub let sum: { x: 'a } with Total 'a -> Int = { x } -> x.total {}
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
        indexSource(store, source)
    }

    @AfterEach fun tearDown() { store.close() }

    private fun declHash(kind: DeclKind, name: String) =
        store.getDeclsForModule("app").single { it.declKind == kind && it.declName == name }.hash

    @Test
    fun `a function is found by the effect declaration it performs`() {
        val ping = declHash(DeclKind.EFFECT, "Ping")
        assertEquals(listOf("pinged"), store.findDeclsPerforming(ping).map { it.declName })
    }

    @Test
    fun `a function is found by the ability declaration it requires`() {
        val total = declHash(DeclKind.ABILITY, "Total")
        assertEquals(listOf("sum"), store.findDeclsRequiring(total).map { it.declName })
    }

    @Test
    fun `each impl is found from its own receiver type`() {
        val total = declHash(DeclKind.ABILITY, "Total")
        val impls = store.getAbilityImpls(total)
        assertEquals(2, impls.size)
        for (impl in impls) {
            val receiver = store.getImplTargets(impl.hash).last()
            assertEquals(listOf(impl.hash), store.findImplsForType(receiver).map { it.hash },
                "the reverse lookup from ${impl.hash.hex.take(8)}'s receiver")
        }
    }
}
