package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests that impl operations preserve type annotations during parsing.
 *
 * Note: when the ability is in the same file, impl is parsed as ParsedImplAbilityDecl.
 * When the ability is imported (not in the same file), impl stays as ParsedPerformerDecl
 * and is resolved later by the typechecker.
 */
class ImplTypeAnnotationTest {

    private val parser = MezzeParser()

    @Test
    fun `impl operation without type annotation - same file ability`() {
        val source = """
ability Eq for 'a where {
  eq: {it: 'a, r: 'a} -> Bool
}

impl Eq for Int where {
  eq = {it, r} -> __native_int_eq {l = it, r = r}
}""".trimIndent()
        val module = parser.parseParsedModule(source, "test.mz")
        val impl = module.decls.filterIsInstance<ParsedImplAbilityDecl>().first()
        val op = impl.operations.first()
        assertEquals("eq", op.name)
        assertNull(op.paramType, "Should have no param type when not annotated")
        assertNull(op.returnType, "Should have no return type when not annotated")
    }

    @Test
    fun `impl operation with type annotation - same file ability`() {
        val source = """
ability Eq for 'a where {
  eq: {it: 'a, r: 'a} -> Bool
}

impl Eq for Int where {
  eq: {it: Int, r: Int} -> Bool = {it, r} -> __native_int_eq {l = it, r = r}
}""".trimIndent()
        val module = parser.parseParsedModule(source, "test.mz")
        val impl = module.decls.filterIsInstance<ParsedImplAbilityDecl>().first()
        val op = impl.operations.first()
        assertEquals("eq", op.name)
        assertNotNull(op.paramType, "Should have param type when annotated")
        assertNotNull(op.returnType, "Should have return type when annotated")
    }

    @Test
    fun `impl with multiple annotated operations - same file ability`() {
        val source = """
ability Eq for 'a where {
  eq: {it: 'a, r: 'a} -> Bool,
  neq: {it: 'a, r: 'a} -> Bool
}

impl Eq for Int where {
  eq: {it: Int, r: Int} -> Bool = {it, r} -> __native_int_eq {l = it, r = r},
  neq: {it: Int, r: Int} -> Bool = {it, r} -> __native_int_neq {l = it, r = r}
}""".trimIndent()
        val module = parser.parseParsedModule(source, "test.mz")
        val impl = module.decls.filterIsInstance<ParsedImplAbilityDecl>().first()
        assertEquals(2, impl.operations.size)
        for (op in impl.operations) {
            assertNotNull(op.paramType, "${op.name} should have param type")
            assertNotNull(op.returnType, "${op.name} should have return type")
        }
    }

    @Test
    fun `inherent impl with type annotation`() {
        val source = """impl Point where {
  get_x: {it: Point} -> Int = {it} -> it.x
}"""
        val module = parser.parseParsedModule(source, "test.mz")
        val impl = module.decls.first() as ParsedImplAbilityDecl
        val op = impl.operations.first()
        assertEquals("get_x", op.name)
        assertNotNull(op.paramType, "Should have param type")
        assertNotNull(op.returnType, "Should have return type")
    }

    @Test
    fun `performer impl preserves type annotations on operations`() {
        // Without ability in same file, this stays as ParsedPerformerDecl
        val source = """impl Log for Console where {
  log: {msg: Str} -> {} = {msg} -> __native_println {msg = msg}
}"""
        val module = parser.parseParsedModule(source, "test.mz")
        val perf = module.decls.first() as ParsedPerformerDecl
        val op = perf.operations.first()
        assertEquals("log", op.name)
        assertNotNull(op.paramType, "Performer op should preserve param type")
        assertNotNull(op.returnType, "Performer op should preserve return type")
    }
}
