package com.mezze.language.typechecker

import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.typechecker.dependencies.DefinitionRef
import com.mezze.language.typechecker.dependencies.DependencyGraph
import com.mezze.language.typechecker.dependencies.DependencyGraphBuilder
import com.mezze.language.typechecker.dependencies.DependencyTracker
import com.mezze.language.typechecker.dependencies.NameRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for dependency tracking infrastructure.
 */
class DependencyTrackingTest {

    // Helper to create distinct hashes from simple strings
    private fun hashOf(name: String) = MezzeHash.fromString(name)

    @Test
    fun `DependencyGraphBuilder creates correct graph`() {
        val builder = DependencyGraphBuilder()

        val hashA = hashOf("definitionA")
        val hashB = hashOf("definitionB")
        val hashC = hashOf("definitionC")

        // A depends on B, A depends on C
        builder.addDependency(hashA, hashB)
        builder.addDependency(hashA, hashC)

        val graph = builder.build()

        // Check forward dependencies
        assertEquals(setOf(hashB, hashC), graph.getDependencies(hashA))
        assertEquals(emptySet(), graph.getDependencies(hashB))

        // Check reverse dependencies (dependents)
        assertEquals(setOf(hashA), graph.getDependents(hashB))
        assertEquals(setOf(hashA), graph.getDependents(hashC))
    }

    @Test
    fun `DependencyTracker resolves name-based dependencies to hashes`() {
        val tracker = DependencyTracker()

        // Simulate tracking during constraint generation
        tracker.startTracking("foo")
        tracker.recordReference("bar", null)  // foo references bar (same module)
        tracker.recordReference("baz", "other::module")  // foo references other::module::baz
        tracker.finishTracking()

        tracker.startTracking("bar")
        tracker.recordReference("helper", null)  // bar references helper
        tracker.finishTracking()

        // Get unresolved dependencies
        val unresolved = tracker.getUnresolvedDependencies()

        assertEquals(
            setOf(
                DefinitionRef.ByName(null, "bar"),
                DefinitionRef.ByName("other::module", "baz")
            ),
            unresolved.forBinding("foo")
        )

        assertEquals(
            setOf(DefinitionRef.ByName(null, "helper")),
            unresolved.forBinding("bar")
        )

        // Resolve dependencies to hashes
        val hashFoo = hashOf("foo")
        val hashBar = hashOf("bar")
        val hashBaz = hashOf("baz")
        val hashHelper = hashOf("helper")

        val nameToHash: (String, String?) -> MezzeHash? = { name, modulePath ->
            when {
                name == "bar" && modulePath == null -> hashBar
                name == "baz" && modulePath == "other::module" -> hashBaz
                name == "helper" && modulePath == null -> hashHelper
                else -> null
            }
        }

        val bindingToHash: (String) -> MezzeHash? = { bindingName ->
            when (bindingName) {
                "foo" -> hashFoo
                "bar" -> hashBar
                else -> null
            }
        }

        val graph = tracker.resolve(nameToHash, bindingToHash)

        // foo depends on bar and baz
        assertEquals(setOf(hashBar, hashBaz), graph.getDependencies(hashFoo))

        // bar depends on helper
        assertEquals(setOf(hashHelper), graph.getDependencies(hashBar))

        // bar has foo as dependent
        assertEquals(setOf(hashFoo), graph.getDependents(hashBar))
    }

    @Test
    fun `DependencyGraph supports transitive dependency lookup`() {
        val builder = DependencyGraphBuilder()

        val hashA = hashOf("A")
        val hashB = hashOf("B")
        val hashC = hashOf("C")
        val hashD = hashOf("D")

        // A -> B -> C -> D
        builder.addDependency(hashA, hashB)
        builder.addDependency(hashB, hashC)
        builder.addDependency(hashC, hashD)

        val graph = builder.build()

        // A transitively depends on B, C, D
        val transitiveDeps = graph.getTransitiveDependencies(hashA)
        assertEquals(setOf(hashB, hashC, hashD), transitiveDeps)

        // D has transitive dependents A, B, C
        val transitiveDependents = graph.getTransitiveDependents(hashD)
        assertEquals(setOf(hashA, hashB, hashC), transitiveDependents)
    }

    @Test
    fun `DependencyGraph merge combines two graphs`() {
        val builder1 = DependencyGraphBuilder()
        val builder2 = DependencyGraphBuilder()

        val hashA = hashOf("mergeA")
        val hashB = hashOf("mergeB")
        val hashC = hashOf("mergeC")

        builder1.addDependency(hashA, hashB)
        builder2.addDependency(hashA, hashC)

        val graph1 = builder1.build()
        val graph2 = builder2.build()

        val merged = graph1.merge(graph2)

        // A should depend on both B and C after merge
        assertEquals(setOf(hashB, hashC), merged.getDependencies(hashA))
    }

    @Test
    fun `DefinitionRef ByName qualifiedName format`() {
        val localRef = DefinitionRef.ByName(null, "foo")
        val qualifiedRef = DefinitionRef.ByName("std::list", "map")

        assertEquals("foo", localRef.qualifiedName)
        assertEquals("std::list::map", qualifiedRef.qualifiedName)
    }

    // ========================================================================
    // NameRegistry Tests
    // ========================================================================

    @Test
    fun `NameRegistry basic registration and lookup`() {
        val registry = NameRegistry()

        val hashFoo = hashOf("foo_content")
        val hashBar = hashOf("bar_content")

        registry.register(null, "foo", hashFoo)
        registry.register("std::list", "map", hashBar)

        // Forward lookup
        assertEquals(hashFoo, registry.getHash(null, "foo"))
        assertEquals(hashBar, registry.getHash("std::list", "map"))
        assertNull(registry.getHash(null, "unknown"))

        // Qualified name lookup using structured API
        assertEquals(hashFoo, registry.getHash(null, "foo"))
        assertEquals(hashBar, registry.getHash("std::list", "map"))
    }

    @Test
    fun `NameRegistry reverse lookup returns all aliases`() {
        val registry = NameRegistry()

        val hash = hashOf("shared_content")

        // Register multiple names for the same hash (aliases)
        registry.register(null, "myFunc", hash)
        registry.register("module", "alias1", hash)
        registry.register("other::module", "alias2", hash)

        val names = registry.getNames(hash)

        assertEquals(3, names.size)
        assertTrue(names.any { it.name == "myFunc" && it.modulePath == null })
        assertTrue(names.any { it.name == "alias1" && it.modulePath == "module" })
        assertTrue(names.any { it.name == "alias2" && it.modulePath == "other::module" })
    }

    @Test
    fun `NameRegistry updating a name removes old mapping`() {
        val registry = NameRegistry()

        val hash1 = hashOf("version1")
        val hash2 = hashOf("version2")

        // Register initial mapping
        registry.register(null, "myFunc", hash1)
        assertEquals(hash1, registry.getHash(null, "myFunc"))
        assertEquals(setOf(NameRegistry.QualifiedName(null, "myFunc")), registry.getNames(hash1))

        // Update to new hash (simulates editing a function)
        registry.register(null, "myFunc", hash2)
        assertEquals(hash2, registry.getHash(null, "myFunc"))

        // Old hash should no longer have this name
        assertTrue(registry.getNames(hash1).isEmpty())

        // New hash should have the name
        assertEquals(setOf(NameRegistry.QualifiedName(null, "myFunc")), registry.getNames(hash2))
    }

    @Test
    fun `NameRegistry remove operations`() {
        val registry = NameRegistry()

        val hash = hashOf("content")
        registry.register(null, "foo", hash)
        registry.register("mod", "bar", hash)

        // Remove one name
        val removedHash = registry.removeName(null, "foo")
        assertEquals(hash, removedHash)
        assertFalse(registry.hasName(null, "foo"))
        assertTrue(registry.hasName("mod", "bar"))

        // Hash still exists with remaining name
        assertTrue(registry.hasHash(hash))
        assertEquals(1, registry.getNames(hash).size)

        // Remove all names for hash
        val removedNames = registry.removeHash(hash)
        assertEquals(1, removedNames.size)
        assertFalse(registry.hasHash(hash))
    }

    @Test
    fun `NameRegistry snapshot and restore`() {
        val registry = NameRegistry()

        val hash1 = hashOf("content1")
        val hash2 = hashOf("content2")
        registry.register(null, "foo", hash1)
        registry.register("mod", "bar", hash2)

        // Create snapshot
        val snapshot = registry.snapshot()

        // Clear and verify empty
        registry.clear()
        assertTrue(registry.isEmpty())

        // Restore from snapshot
        registry.restore(snapshot)
        assertEquals(hash1, registry.getHash(null, "foo"))
        assertEquals(hash2, registry.getHash("mod", "bar"))
    }

    @Test
    fun `NameRegistry merge combines registries`() {
        val registry1 = NameRegistry()
        val registry2 = NameRegistry()

        val hash1 = hashOf("content1")
        val hash2 = hashOf("content2")
        val hash3 = hashOf("content3")

        registry1.register(null, "foo", hash1)
        registry1.register(null, "bar", hash2)

        registry2.register(null, "bar", hash3)  // Conflict: different hash
        registry2.register(null, "baz", hash3)

        // Merge - registry2 wins on conflicts
        registry1.merge(registry2)

        assertEquals(hash1, registry1.getHash(null, "foo"))
        assertEquals(hash3, registry1.getHash(null, "bar"))  // registry2 wins
        assertEquals(hash3, registry1.getHash(null, "baz"))
        assertEquals(3, registry1.size)
    }

    @Test
    fun `NameRegistry QualifiedName formatting`() {
        val local = NameRegistry.QualifiedName(null, "myFunc")
        val qualified = NameRegistry.QualifiedName("std::list", "map")

        assertEquals("myFunc", local.fullName)
        assertEquals("std::list::map", qualified.fullName)
        assertEquals("myFunc", local.toString())
        assertEquals("std::list::map", qualified.toString())
    }
}
