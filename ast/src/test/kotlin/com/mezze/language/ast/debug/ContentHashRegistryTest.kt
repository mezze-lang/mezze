package com.mezze.language.ast.debug

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class ContentHashRegistryTest {

    @BeforeEach
    fun setUp() {
        ContentHashRegistry.clear()
    }

    @Test
    fun `register and lookup hash`() {
        val hash = "abc123def456"
        ContentHashRegistry.register(
            hash = hash,
            qualifiedName = "std::int::add",
            modulePath = "std::int",
            kind = ContentHashRegistry.DeclarationKind.FUNCTION
        )

        assertEquals("std::int::add", ContentHashRegistry.nameForHash(hash))
        assertEquals("std::int", ContentHashRegistry.moduleForHash(hash))
        assertEquals(ContentHashRegistry.DeclarationKind.FUNCTION, ContentHashRegistry.kindForHash(hash))
        assertTrue(ContentHashRegistry.isRegistered(hash))
    }

    @Test
    fun `unknown hash returns truncated form`() {
        val hash = "xyz789unknown"
        val name = ContentHashRegistry.nameForHash(hash)
        assertTrue(name.startsWith("<unknown:xyz789un"))
    }

    @Test
    fun `describeHash with full info`() {
        val hash = "hash123"
        ContentHashRegistry.register(
            hash = hash,
            qualifiedName = "my::module::func",
            modulePath = "my::module",
            kind = ContentHashRegistry.DeclarationKind.EFFECT_HANDLER
        )

        val description = ContentHashRegistry.describeHash(hash)
        assertTrue(description.contains("my::module::func"))
        assertTrue(description.contains("effect handler"))
        assertTrue(description.contains("my::module"))
    }

    @Test
    fun `describeHash for unregistered returns truncated`() {
        val description = ContentHashRegistry.describeHash("unregistered123456")
        assertTrue(description.startsWith("<unregistered hash:"))
    }

    @Test
    fun `clear removes all entries`() {
        ContentHashRegistry.register("h1", "name1")
        ContentHashRegistry.register("h2", "name2")
        assertEquals(2, ContentHashRegistry.size())

        ContentHashRegistry.clear()
        assertEquals(0, ContentHashRegistry.size())
        assertFalse(ContentHashRegistry.isRegistered("h1"))
    }

    @Test
    fun `allHashes returns all registered hashes`() {
        ContentHashRegistry.register("h1", "name1")
        ContentHashRegistry.register("h2", "name2")

        val hashes = ContentHashRegistry.allHashes()
        assertEquals(setOf("h1", "h2"), hashes)
    }

    @Test
    fun `allMappings returns all name mappings`() {
        ContentHashRegistry.register("h1", "name1")
        ContentHashRegistry.register("h2", "name2")

        val mappings = ContentHashRegistry.allMappings()
        assertEquals(mapOf("h1" to "name1", "h2" to "name2"), mappings)
    }
}
