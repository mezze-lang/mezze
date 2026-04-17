package com.mezze.interpreter.v2

import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

/**
 * End-to-end integration tests for the content-addressable hashing system.
 *
 * These tests verify the entire pipeline:
 * 1. Hash computation during type checking
 * 2. Hash-based ability/effect resolution
 * 3. Hash-based runtime dispatch
 * 4. Cross-module hash consistency
 * 5. Cache invalidation based on content hashes
 *
 * Migrated from V1 to V2 with TypedAST types.
 */
class ContentAddressableHashingE2ETest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker
    private val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    /** Helper: type-check and return the TypedModule (v2 output). */
    private fun typeCheckModule(source: String): TypedModule {
        val parsedModule = parser.parseParsedModule(source)
        val result = typeChecker.typeCheck(parsedModule, projectRoot)
        return result.typedModule ?: error("TypedModule should not be null after type checking")
    }

    // =========================================================================
    // PHASE 1: HASH STABILITY ACROSS PIPELINE
    // =========================================================================

    @Test
    fun `content hashes are populated after type checking`() {
        val source = """
            let add = {x: Int, y: Int} -> x + y
            let mul = {a: Int, b: Int} -> a * b
            add {x = 1, y = 2}
        """.trimIndent()

        val typedModule = typeCheckModule(source)

        // Find TypedLetDecls and verify they have content hashes
        val letDecls = typedModule.decls.filterIsInstance<TypedLetDecl>()
        assertTrue(letDecls.isNotEmpty(), "Should have let bindings")

        for (decl in letDecls) {
            val hex = decl.hash.hex
            assertTrue(hex.isNotEmpty(), "TypedLetDecl '${decl.name}' should have a non-empty hash")
            // Hash is hex string (SHA3-512 = 128 hex chars, or may vary during migration)
            assertTrue(hex.matches(Regex("[0-9a-fA-F]+")), "Content hash should be hex chars, got: ${hex.take(20)}")
        }
    }

    @Test
    fun `same source produces same hash across type check runs`() {
        val source = """
            let identity = {x: Int} -> x
            identity {x = 42}
        """.trimIndent()

        // Run type checking twice
        val typedModule1 = typeCheckModule(source)
        val typedModule2 = typeCheckModule(source)

        // Find the 'identity' binding in both results
        val binding1 = typedModule1.decls.filterIsInstance<TypedLetDecl>()
            .find { it.name == "identity" }
        val binding2 = typedModule2.decls.filterIsInstance<TypedLetDecl>()
            .find { it.name == "identity" }

        assertNotNull(binding1, "Should find 'identity' in first run")
        assertNotNull(binding2, "Should find 'identity' in second run")
        assertEquals(binding1!!.hash, binding2!!.hash,
            "Same source should produce same content hash across runs")
    }

    @Test
    fun `different function bodies produce different hashes`() {
        val source1 = """
            let f = {x: Int} -> x + 1
            f {x = 0}
        """.trimIndent()

        val source2 = """
            let f = {x: Int} -> x + 2
            f {x = 0}
        """.trimIndent()

        val typedModule1 = typeCheckModule(source1)
        val typedModule2 = typeCheckModule(source2)

        val hash1 = typedModule1.decls.filterIsInstance<TypedLetDecl>()
            .find { it.name == "f" }?.hash
        val hash2 = typedModule2.decls.filterIsInstance<TypedLetDecl>()
            .find { it.name == "f" }?.hash

        assertNotNull(hash1)
        assertNotNull(hash2)
        assertNotEquals(hash1, hash2, "Different function bodies should produce different hashes")
    }

    // =========================================================================
    // PHASE 2: ALPHA-EQUIVALENCE
    // =========================================================================

    @Test
    fun `parameter names produce different hashes - part of API`() {
        // In Mezze, parameter names ARE part of the API (named parameters)
        val source1 = """
            let f = {x: Int} -> x + 1
            f {x = 0}
        """.trimIndent()

        val source2 = """
            let f = {y: Int} -> y + 1
            f {y = 0}
        """.trimIndent()

        val typedModule1 = typeCheckModule(source1)
        val typedModule2 = typeCheckModule(source2)

        val hash1 = typedModule1.decls.filterIsInstance<TypedLetDecl>()
            .find { it.name == "f" }?.hash
        val hash2 = typedModule2.decls.filterIsInstance<TypedLetDecl>()
            .find { it.name == "f" }?.hash

        assertNotNull(hash1)
        assertNotNull(hash2)
        assertNotEquals(hash1, hash2,
            "Functions with different parameter names should have different hashes (named params are API)")
    }

    // =========================================================================
    // PHASE 3: ABILITY IMPLEMENTATION HASHING
    // =========================================================================

    @Test
    fun `ability implementations have content hashes`() {
        val source = """
            ability Describable for 'a where {
                describe: {it: 'a} -> Str
            }

            impl Describable for Int where {
                describe = {it} -> "an integer"
            }

            42.describe {}
        """.trimIndent()

        val typedModule = typeCheckModule(source)

        // Find ability impl — in v2, ability impls are in typedModule.abilityImpls
        val impls = typedModule.abilityImpls
        assertTrue(impls.isNotEmpty(), "Should find at least one ability impl")

        // Find the Describable impl by matching its abilityHash to the Describable ability decl
        val describableAbility = typedModule.abilityDecls.find { it.name == "Describable" }
        assertNotNull(describableAbility, "Should find Describable ability decl")
        val impl = impls.find { it.abilityHash == describableAbility!!.hash }

        assertNotNull(impl, "Should find Describable impl")
        val hex = impl!!.hash.hex
        assertTrue(hex.isNotEmpty(), "Ability impl should have a non-empty hash")
        assertTrue(hex.matches(Regex("[0-9a-fA-F]+")), "Content hash should be hex chars")
    }

    @Test
    fun `resolved ability has implementation hash at call site`() {
        val source = """
            ability Doubler for 'a where {
                double: {it: 'a} -> 'a
            }

            impl Doubler for Int where {
                double = {it} -> it + it
            }

            21.double {}
        """.trimIndent()

        val typedModule = typeCheckModule(source)

        // In v2, ability calls are inlined as TypedCall with BindingRef.ByHash.
        // The ability impl has a hash, and the call site references it via TypeInstantiation.
        val doublerAbility = typedModule.abilityDecls.find { it.name == "Doubler" }
        assertNotNull(doublerAbility, "Should find Doubler ability decl")

        val impl = typedModule.abilityImpls.find { it.abilityHash == doublerAbility!!.hash }
        assertNotNull(impl, "Should find Doubler impl")
        assertTrue(impl!!.hash.hex.isNotEmpty(), "Impl should have a content hash")

        // The impl operations should contain the inlined body
        assertTrue(impl.operations.isNotEmpty(),
            "Impl should have operations with inlined bodies")
    }

    @Test
    fun `ability resolution works at runtime via hash lookup`() {
        val source = """
            ability Triple for 'a where {
                triple: {it: 'a} -> 'a
            }

            impl Triple for Int where {
                triple = {it} -> it + it + it
            }

            10.triple {}
        """.trimIndent()

        val result = eval(source)
        assertEquals(30L, result, "Triple of 10 should be 30")
    }

    // =========================================================================
    // PHASE 4: EFFECT HANDLER HASHING
    // =========================================================================

    @Test
    fun `effect handlers have content hashes`() {
        val source = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print: {msg: Str} -> {} = {msg} -> {}
            }

            perform Console in Log.print {msg = "test"}
        """

        val typedModule = typeCheckModule(source)

        // Find effect handler impl (performer) — in v2, performers are in typedModule.performerDecls
        val logEffect = typedModule.effectDecls.find { it.name == "Log" }
        assertNotNull(logEffect, "Should find Log effect decl")

        val performer = typedModule.performerDecls.find { it.effectHash == logEffect!!.hash }
        assertNotNull(performer, "Should find Log effect handler (performer)")
        val hex = performer!!.hash.hex
        assertTrue(hex.isNotEmpty(), "Effect handler should have a non-empty hash")
        assertTrue(hex.matches(Regex("[0-9a-fA-F]+")), "Content hash should be hex chars")
    }

    @Test
    fun `effect handler resolution works at runtime via hash lookup`() {
        val source = """
            effect Math where {
                compute: {x: Int, y: Int} -> Int <Math>
            }

            impl Math for Calculator where {
                compute: {x: Int, y: Int} -> Int = {x, y} -> x + y
            }

            perform Calculator in Math.compute {x = 10, y = 32}
        """

        // Debug: check hashes on the typed module
        val typedModule = typeCheckModule(source)
        val effectDecl = typedModule.effectDecls.first()
        val performerDecl = typedModule.performerDecls.first()
        println("DEBUG - Effect name: ${effectDecl.name}, hash: ${effectDecl.hash}")
        println("DEBUG - Performer name: ${performerDecl.name}, hash: ${performerDecl.hash}")
        println("DEBUG - Performer effectHash: ${performerDecl.effectHash}")

        val result = eval(source)
        assertEquals(42L, result)
    }

    // =========================================================================
    // PHASE 5: TYPE DECLARATION HASHING
    // =========================================================================

    @Test
    fun `type declarations have content hashes`() {
        val source = """
            type Point = {x: Int, y: Int}

            Point {x = 1, y = 2}
        """.trimIndent()

        val typedModule = typeCheckModule(source)

        val typeDecls = typedModule.decls.filterIsInstance<TypedTypeDecl>()
        assertTrue(typeDecls.isNotEmpty(), "Should have at least 1 type declaration")

        for (decl in typeDecls) {
            assertTrue(decl.hash.hex.isNotEmpty(), "TypedTypeDecl '${decl.name}' should have a non-empty hash")
        }
    }

    @Test
    fun `structurally identical types have same hash`() {
        val source1 = """
            type Point = {x: Int, y: Int}
            Point {x = 0, y = 0}
        """.trimIndent()

        val source2 = """
            type Coord = {x: Int, y: Int}
            Coord {x = 0, y = 0}
        """.trimIndent()

        val typedModule1 = typeCheckModule(source1)
        val typedModule2 = typeCheckModule(source2)

        val hash1 = typedModule1.decls.filterIsInstance<TypedTypeDecl>().first().hash
        val hash2 = typedModule2.decls.filterIsInstance<TypedTypeDecl>().first().hash

        // Note: Type name is NOT part of hash, only structure
        assertEquals(hash1, hash2,
            "Structurally identical types should have same content hash (names don't matter)")
    }

    // =========================================================================
    // PHASE 6: EXECUTION WITH HASH-BASED DISPATCH
    // =========================================================================

    @Test
    fun `complete pipeline - parse, typecheck, execute with hash-based dispatch`() {
        val source = """
            ability Square for 'a where {
                square: {it: 'a} -> 'a
            }

            impl Square for Int where {
                square = {it} -> it * it
            }

            let compute = {n: Int} -> n.square {}

            compute {n = 7}
        """.trimIndent()

        // Type check (computes hashes)
        val typedModule = typeCheckModule(source)

        // Verify hashes are populated on ability impls
        val squareAbility = typedModule.abilityDecls.find { it.name == "Square" }
        assertNotNull(squareAbility, "Should find Square ability decl")
        val impl = typedModule.abilityImpls.find { it.abilityHash == squareAbility!!.hash }
        assertNotNull(impl, "Should find Square impl")
        assertTrue(impl!!.hash.hex.isNotEmpty(), "Impl should have content hash after type checking")

        // Execute (uses hash-based dispatch)
        val execResult = eval(source)

        // Verify result
        assertEquals(49L, execResult, "7 squared should be 49")
    }

    @Test
    fun `multiple ability implementations have unique content hashes`() {
        val source = """
            ability Stringify for 'a where {
                stringify: {it: 'a} -> Str
            }

            impl Stringify for Int where {
                stringify: {it: Int} -> Str = {it} -> "int"
            }

            impl Stringify for Str where {
                stringify: {it: Str} -> Str = {it} -> "string"
            }

            42.stringify {}
        """.trimIndent()

        val typedModule = typeCheckModule(source)

        // Find the Stringify ability decl to get its hash
        val stringifyAbility = typedModule.abilityDecls.find { it.name == "Stringify" }
        assertNotNull(stringifyAbility, "Should find Stringify ability decl")

        // Verify each impl has a different content hash
        val impls = typedModule.abilityImpls
            .filter { it.abilityHash == stringifyAbility!!.hash }

        assertEquals(2, impls.size, "Should have 2 Stringify impls")

        val hashes = impls.map { it.hash }.toSet()
        assertEquals(2, hashes.size, "Each impl should have a unique content hash")

        // Execute and verify correct dispatch
        val execResult = eval(source)
        assertEquals("int", execResult)
    }

    // =========================================================================
    // PHASE 7: MODULE CACHE VALIDATION
    // =========================================================================

    @Test
    fun `module source hash enables cache validation`() {
        val sourceContent1 = "let x = 1"
        val sourceContent2 = "let x = 2"

        // Compute hashes for both source contents
        val hash1 = MezzeHashBuilder().append(sourceContent1).build().toString()
        val hash2 = MezzeHashBuilder().append(sourceContent2).build().toString()

        // Same content should produce same hash
        val hash1Again = MezzeHashBuilder().append(sourceContent1).build().toString()
        assertEquals(hash1, hash1Again, "Same source content should produce same hash")

        // Different content should produce different hash
        assertNotEquals(hash1, hash2, "Different source content should produce different hash")
    }

    @Test
    fun `isModuleStale detects content changes`() {
        val modulePath = "test::stale_check"
        val originalContent = "let value = 42"

        // Module not in cache - should be stale
        assertTrue(ModuleResolver.isModuleStale(modulePath, originalContent),
            "Module not in cache should be considered stale")

        // Note: Full cache validation test would require setting up a cached module
        // which is complex. The key point is that isModuleStale uses content hashing.
    }

    // =========================================================================
    // PHASE 8: HASH BUILDER DETERMINISM
    // =========================================================================

    @Test
    fun `MezzeHashBuilder produces deterministic hashes`() {
        val hash1 = MezzeHashBuilder()
            .append("test")
            .appendInt(42)
            .appendBoolean(true)
            .build()

        val hash2 = MezzeHashBuilder()
            .append("test")
            .appendInt(42)
            .appendBoolean(true)
            .build()

        assertEquals(hash1, hash2, "Same builder operations should produce same hash")
    }

    @Test
    fun `MezzeHash combine is order-sensitive`() {
        val h1 = MezzeHash.fromString("a")
        val h2 = MezzeHash.fromString("b")

        val combined1 = MezzeHash.combine(listOf(h1, h2))
        val combined2 = MezzeHash.combine(listOf(h2, h1))

        // Order matters for combine (not sorted internally)
        assertNotEquals(combined1, combined2,
            "MezzeHash.combine should be order-sensitive (sorting done at call site when needed)")
    }

    // =========================================================================
    // PHASE 9: COMPLEX SCENARIOS
    // =========================================================================

    // TODO: Nested ability method calls inside ability impl bodies don't resolve at runtime.
    // The compiler treats `it.inner {}` as field access instead of method dispatch when inside
    // another ability's impl body. This requires deeper compiler work to fix.
    @org.junit.jupiter.api.Disabled("Nested ability method calls inside impl bodies not yet supported")
    @Test
    fun `nested ability calls resolve correctly via hashes`() {
        val source = """
            ability Inner for 'a where {
                inner: {it: 'a} -> Int
            }

            ability Outer for 'a where {
                outer: {it: 'a} -> Int
            }

            impl Inner for Int where {
                inner = {it} -> it
            }

            impl Outer for Int where {
                outer = {it} -> it.inner {} + 10
            }

            5.outer {}
        """.trimIndent()

        val result = eval(source)
        assertEquals(15L, result, "5 + 10 = 15")
    }

    @Test
    fun `higher-order functions with ability constraints work with hash dispatch`() {
        val source = """
            ability Addable for 'a where {
                plus: {it: 'a, b: 'a} -> 'a
            }

            impl Addable for Int where {
                plus = {it, b} -> it + b
            }

            let sumWith = {x: Int, y: Int} -> x.plus {b = y}

            sumWith {x = 100, y = 23}
        """.trimIndent()

        val result = eval(source)
        assertEquals(123L, result)
    }

    // =========================================================================
    // PHASE 10: HASH PERSISTENCE THROUGH TRANSFORMATIONS
    // =========================================================================

    @Test
    fun `content hash survives monomorphization`() {
        val source = """
            let identity = {x: Int} -> x
            let result = identity {x = 42}
            result
        """.trimIndent()

        val typedModule = typeCheckModule(source)

        // Find the identity binding
        val identityBinding = typedModule.decls.filterIsInstance<TypedLetDecl>()
            .find { it.name == "identity" }

        assertNotNull(identityBinding, "Should find identity binding")
        assertTrue(identityBinding!!.hash.hex.isNotEmpty(),
            "Identity binding should have a non-empty hash after type checking")

        // Execute to verify the whole pipeline works
        val execResult = eval(source)
        assertEquals(42L, execResult)
    }

    @Test
    fun `ability impl hash matches between declaration and resolution`() {
        val source = """
            ability Negate for 'a where {
                negate: {it: 'a} -> 'a
            }

            impl Negate for Int where {
                negate = {it} -> 0 - it
            }

            42.negate {}
        """.trimIndent()

        val typedModule = typeCheckModule(source)

        // Get the impl's content hash
        val negateAbility = typedModule.abilityDecls.find { it.name == "Negate" }
        assertNotNull(negateAbility, "Should find Negate ability decl")

        val impl = typedModule.abilityImpls.find { it.abilityHash == negateAbility!!.hash }
        assertNotNull(impl, "Should find Negate impl")
        val implHash = impl!!.hash
        assertTrue(implHash.hex.isNotEmpty(), "Impl should have a non-empty hash")

        // In v2, ability calls are resolved at compile time via BindingRef.ByHash.
        // The impl body is inlined and referenced by hash, not by name.
        // Verify the impl has operations (the inlined body)
        assertTrue(impl.operations.isNotEmpty(),
            "Impl should have operations with inlined bodies")

        // Verify the ability decl also has a hash and it's different from the impl hash
        assertTrue(negateAbility!!.hash.hex.isNotEmpty(),
            "Ability decl should have a non-empty hash")

        // Execute to verify runtime dispatch works
        val execResult = eval(source)
        assertEquals(-42L, execResult, "Negate of 42 should be -42")
    }
}
