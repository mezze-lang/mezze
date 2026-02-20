package com.mezze.language.typechecker

import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.typechecker.dependencies.DefinitionRef
import com.mezze.language.typechecker.dependencies.DependencyGraph
import com.mezze.language.typechecker.dependencies.DependencyGraphBuilder
import com.mezze.language.typechecker.dependencies.DependencyTracker
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
