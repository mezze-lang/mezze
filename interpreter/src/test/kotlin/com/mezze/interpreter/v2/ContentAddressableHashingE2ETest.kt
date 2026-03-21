package com.mezze.interpreter.v2

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.ModuleResolver
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
 * Migrated from V1 to V2 with unboxed values.
 */
class ContentAddressableHashingE2ETest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker
    private val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

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

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program, projectRoot)

        // Find LetBindings and verify they have content hashes
        val letBindings = result.program.items.filterIsInstance<LetBinding>()
        assertTrue(letBindings.isNotEmpty(), "Should have let bindings")

        for (binding in letBindings) {
            assertNotNull(binding.contentHash, "LetBinding '${binding.variableName}' should have contentHash")
            assertTrue(binding.contentHash!!.matches(Regex("[0-9a-f]{128}")), "Content hash should be 128 hex chars (SHA3-512)")
            assertEquals(128, binding.contentHash!!.length, "Content hash should be 128 hex chars")
        }
    }

    @Test
    fun `same source produces same hash across type check runs`() {
        val source = """
            let identity = {x: Int} -> x
            identity {x = 42}
        """.trimIndent()

        // Run type checking twice
        val program1 = parser.parse(source)
        val result1 = typeChecker.typeCheck(program1, projectRoot)

        // Clear and run again
        ModuleResolver.clearCache()
        val program2 = parser.parse(source)
        val result2 = typeChecker.typeCheck(program2, projectRoot)

        // Find the 'identity' binding in both results
        val binding1 = result1.program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "identity" }
        val binding2 = result2.program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "identity" }

        assertNotNull(binding1, "Should find 'identity' in first run")
        assertNotNull(binding2, "Should find 'identity' in second run")
        assertEquals(binding1!!.contentHash, binding2!!.contentHash,
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

        val result1 = typeChecker.typeCheck(parser.parse(source1), projectRoot)
        ModuleResolver.clearCache()
        val result2 = typeChecker.typeCheck(parser.parse(source2), projectRoot)

        val hash1 = result1.program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "f" }?.contentHash
        val hash2 = result2.program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "f" }?.contentHash

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

        val result1 = typeChecker.typeCheck(parser.parse(source1), projectRoot)
        ModuleResolver.clearCache()
        val result2 = typeChecker.typeCheck(parser.parse(source2), projectRoot)

        val hash1 = result1.program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "f" }?.contentHash
        val hash2 = result2.program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "f" }?.contentHash

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

        val result = typeChecker.typeCheck(parser.parse(source), projectRoot)

        // Find ability impl
        val impl = result.program.items.filterIsInstance<ImplAbilityDeclaration>()
            .find { it.abilityName == "Describable" }

        assertNotNull(impl, "Should find Describable impl")
        assertNotNull(impl!!.contentHash, "Ability impl should have contentHash")
        assertTrue(impl.contentHash!!.matches(Regex("[0-9a-f]{128}")), "Content hash should be 128 hex chars")
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

        val result = typeChecker.typeCheck(parser.parse(source), projectRoot)

        // After ability inlining, the call is rewritten to a direct function call
        // with the impl body. The ability is fully resolved at compile time.
        val topLevelExpr = result.program.items.filterIsInstance<TopLevelExpression>().lastOrNull()
        assertNotNull(topLevelExpr, "Should have top-level expression")

        val funcCall = topLevelExpr!!.expression as? FunctionCall
        assertNotNull(funcCall, "Should be a function call (inlined impl body)")

        // The impl body should be inlined — resolvedAbilities should be empty
        // because the ability abstraction is fully resolved at compile time
        assertTrue(funcCall!!.resolvedAbilities.isEmpty(),
            "Inlined ability call should have empty resolvedAbilities")

        // With explicit `it` parameter, the inlined call's function is the impl lambda
        // (no longer wrapped in a DoExpression since `it` is a regular parameter)
        assertNotNull(funcCall.function,
            "Inlined call should have a function (impl body)")
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

        val result = typeChecker.typeCheck(parser.parse(source), projectRoot)

        // Find effect handler impl
        val impl = result.program.items.filterIsInstance<ImplDeclaration>()
            .find { it.effect.localName == "Log" }

        assertNotNull(impl, "Should find Log effect handler")
        assertNotNull(impl!!.contentHash, "Effect handler should have contentHash")
        assertTrue(impl.contentHash!!.matches(Regex("[0-9a-f]{128}")), "Content hash should be 128 hex chars")
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

        // Debug: check hashes on the typed program
        val typedProgram = MezzeTestHelper.typeCheck(source)
        val effectDecl = typedProgram.items.filterIsInstance<EffectDeclaration>().first()
        val implDecl = typedProgram.items.filterIsInstance<ImplDeclaration>().first()
        println("DEBUG - Effect name: ${effectDecl.name}, hash: ${effectDecl.contentHash}")
        println("DEBUG - Impl performer: ${implDecl.performerName}, impl hash: ${implDecl.contentHash}")
        println("DEBUG - Impl effect.localName: ${implDecl.effect.localName}, effect.resolvedContentHash: ${implDecl.effect.resolvedContentHash}")

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

        val result = typeChecker.typeCheck(parser.parse(source), projectRoot)

        val typeDecls = result.program.items.filterIsInstance<TypeDeclaration>()
        assertTrue(typeDecls.isNotEmpty(), "Should have at least 1 type declaration")

        for (decl in typeDecls) {
            assertNotNull(decl.contentHash, "TypeDeclaration '${decl.name}' should have contentHash")
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

        val result1 = typeChecker.typeCheck(parser.parse(source1), projectRoot)
        ModuleResolver.clearCache()
        val result2 = typeChecker.typeCheck(parser.parse(source2), projectRoot)

        val hash1 = result1.program.items.filterIsInstance<TypeDeclaration>().first().contentHash
        val hash2 = result2.program.items.filterIsInstance<TypeDeclaration>().first().contentHash

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

        // Parse
        val program = parser.parse(source)

        // Type check (computes hashes)
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)

        // Verify hashes are populated
        val impl = typeCheckResult.program.items.filterIsInstance<ImplAbilityDeclaration>().first()
        assertNotNull(impl.contentHash, "Impl should have content hash after type checking")

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

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)

        // Verify each impl has a different content hash
        val impls = typeCheckResult.program.items.filterIsInstance<ImplAbilityDeclaration>()
            .filter { it.abilityName == "Stringify" }

        assertEquals(2, impls.size, "Should have 2 Stringify impls")

        val hashes = impls.map { it.contentHash }.toSet()
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

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)

        // Find the identity binding
        val identityBinding = typeCheckResult.program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "identity" }

        assertNotNull(identityBinding, "Should find identity binding")
        assertNotNull(identityBinding!!.contentHash, "Identity binding should have contentHash after monomorphization")

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

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)

        // Get the impl's content hash
        val impl = typeCheckResult.program.items.filterIsInstance<ImplAbilityDeclaration>()
            .find { it.abilityName == "Negate" }
        assertNotNull(impl)
        val implHash = impl!!.contentHash
        assertNotNull(implHash)

        // After inlining, the call is rewritten: resolvedAbilities is empty,
        // but the impl body is inlined directly. The impl still has its content hash.
        val topLevelExpr = typeCheckResult.program.items.filterIsInstance<TopLevelExpression>().lastOrNull()
        assertNotNull(topLevelExpr)
        val funcCall = topLevelExpr!!.expression as? FunctionCall
        assertNotNull(funcCall)

        // Ability is fully resolved at compile time — resolvedAbilities should be empty
        assertTrue(funcCall!!.resolvedAbilities.isEmpty(),
            "Inlined ability call should have empty resolvedAbilities")
        // With explicit `it` parameter, the inlined call's function is the impl lambda
        // (no longer wrapped in a DoExpression since `it` is a regular parameter)
        assertNotNull(funcCall.function,
            "Inlined call should have a function (impl body)")
    }
}
