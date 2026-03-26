package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TypeCheckerV2Test {

    private val parser = MezzeParser()
    private val checker = TypeChecker()

    private fun checkSource(source: String, file: String = "test.mz"): com.mezze.language.typedast.TypedModule {
        val module = parser.parseParsedModule(source, file)
        return checker.checkParsedModule(module)
    }

    @Test
    fun `simple let binding gets hash and type`() {
        val module = checkSource("let x = 42")
        assertEquals(1, module.decls.size)

        val decl = module.decls[0] as TypedLetDecl
        assertNotEquals(Hash.ZERO, decl.hash)
        assertEquals("x", decl.name)

        val lit = decl.value as TypedIntLit
        assertEquals(42L, lit.value)
    }

    @Test
    fun `function gets typed`() {
        val module = checkSource("let double = { x: Int } -> x + x")
        val decl = module.decls[0] as TypedLetDecl
        assertEquals("double", decl.name)
        assertTrue(decl.value is TypedLambda)
    }

    @Test
    fun `public export appears in exports map`() {
        val module = checkSource("pub let add = { l: Int, r: Int } -> l + r")
        assertTrue(module.exports.containsKey("add"))
        assertEquals(ExportKind.FUNCTION, module.exports["add"]?.kind)
    }

    @Test
    fun `private binding not in exports`() {
        val module = checkSource("let private_fn = { x: Int } -> x")
        assertFalse(module.exports.containsKey("private_fn"))
    }

    @Test
    fun `type declaration gets hash`() {
        val module = checkSource("type Color = | Red, Green, Blue |")
        val decl = module.decls[0] as TypedTypeDecl
        assertEquals("Color", decl.name)
        assertNotEquals(Hash.ZERO, decl.hash)
    }

    @Test
    fun `module hash computed from decl hashes`() {
        val module = checkSource("""
            let x = 1
            let y = 2
        """.trimIndent())
        assertNotEquals(Hash.ZERO, module.hash)
    }

    @Test
    fun `ability declaration tracked`() {
        val source = """
ability Eq for 'a where {
    eq: {it: 'a, r: 'a} -> Bool,
    neq: {it: 'a, r: 'a} -> Bool
}
""".trim()
        val module = checkSource(source)
        assertEquals(1, module.abilityDecls.size)
        assertEquals("Eq", module.abilityDecls[0].name)
        assertEquals(2, module.abilityDecls[0].operations.size)
        // Operations have indices
        assertEquals(0, module.abilityDecls[0].operations[0].operationIndex)
        assertEquals(1, module.abilityDecls[0].operations[1].operationIndex)
    }

    @Test
    fun `effect declaration tracked`() {
        val source = """
effect Log where {
    println: { msg: Str } -> {} <Log>
}
""".trim()
        val module = checkSource(source)
        assertEquals(1, module.effectDecls.size)
        assertEquals("Log", module.effectDecls[0].name)
        assertTrue(module.exports.containsKey("Log"))
        assertEquals(ExportKind.EFFECT, module.exports["Log"]?.kind)
    }

    @Test
    fun `identical content produces same hash`() {
        val module1 = checkSource("let x = 42", file = "a.mz")
        val module2 = checkSource("let x = 42", file = "b.mz")

        val hash1 = (module1.decls[0] as TypedLetDecl).hash
        val hash2 = (module2.decls[0] as TypedLetDecl).hash
        assertEquals(hash1, hash2, "Same content should produce same hash regardless of file")
    }

    @Test
    fun `different content produces different hash`() {
        val module1 = checkSource("let x = 42")
        val module2 = checkSource("let x = 43")

        val hash1 = (module1.decls[0] as TypedLetDecl).hash
        val hash2 = (module2.decls[0] as TypedLetDecl).hash
        assertNotEquals(hash1, hash2, "Different content should produce different hash")
    }
}
