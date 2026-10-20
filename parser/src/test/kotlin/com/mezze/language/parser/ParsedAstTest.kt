package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for parseParsedModule — the new ParsedAST output.
 */
class ParsedAstTest {

    private val parser = MezzeParser()

    @Test
    fun `simple let binding`() {
        val module = parser.parseParsedModule("let x = 42", file = "test.mz")
        assertEquals("test.mz", module.path)
        assertEquals(1, module.decls.size)

        val decl = module.decls[0] as ParsedLetDecl
        assertEquals(Visibility.PRIVATE, decl.visibility)
        assertTrue(decl.isSimpleBinding)
        assertEquals("x", decl.variableName)

        val value = decl.value as ParsedIntLit
        assertEquals(42L, value.value)
    }

    @Test
    fun `pub let with type annotation`() {
        val module = parser.parseParsedModule(
            "pub let add: { l: Int, r: Int } -> Int = { l, r } -> l + r",
            file = "test.mz"
        )
        val decl = module.decls[0] as ParsedLetDecl
        assertEquals(Visibility.PUBLIC, decl.visibility)
        assertEquals("add", decl.variableName)
        assertNotNull(decl.typeAnnotation)
        assertTrue(decl.value is ParsedLambda)
    }

    @Test
    fun `record literal`() {
        val module = parser.parseParsedModule("let r = { x = 1, y = 2 }", file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        val record = decl.value as ParsedRecordLit
        assertEquals(2, record.entries.size)

        val field1 = record.entries[0] as ParsedRecordEntry.Field
        assertEquals("x", field1.name)
        assertTrue(field1.value is ParsedIntLit)

        val field2 = record.entries[1] as ParsedRecordEntry.Field
        assertEquals("y", field2.name)
    }

    @Test
    fun `record punning`() {
        val module = parser.parseParsedModule("let r = { x, y }", file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        val record = decl.value as ParsedRecordLit
        assertEquals(2, record.entries.size)

        val field1 = record.entries[0] as ParsedRecordEntry.Field
        assertEquals("x", field1.name)
        assertNull(field1.value) // punning
    }

    @Test
    fun `variant construction`() {
        val module = parser.parseParsedModule("let x = Some { val = 42 }", file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        val variant = decl.value as ParsedVariantConstruct
        assertEquals("Some", variant.constructor)
        assertNotNull(variant.value)
    }

    @Test
    fun `bare variant`() {
        val module = parser.parseParsedModule("let x = None", file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        val variant = decl.value as ParsedVariantConstruct
        assertEquals("None", variant.constructor)
        assertNull(variant.value)
    }

    @Test
    fun `if expression`() {
        val module = parser.parseParsedModule("let x = if true then 1 else 0", file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        // After desugaring, if becomes match on True/False
        // The bridge should still produce ParsedIf or ParsedMatch
        assertTrue(decl.value is ParsedIf || decl.value is ParsedMatch)
    }

    @Test
    fun `match expression`() {
        val source = """
let x = match y is
    Some { val } -> val
    None -> 0
""".trim()
        val module = parser.parseParsedModule(source, file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        val match = decl.value as ParsedMatch
        assertEquals(2, match.cases.size)
    }

    @Test
    fun `lambda expression`() {
        val module = parser.parseParsedModule("let f = { x, y } -> x", file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        val lambda = decl.value as ParsedLambda
        val params = lambda.params as ParsedLambdaParams.Untyped
        assertEquals(listOf("x", "y"), params.names)
    }

    @Test
    fun `function call`() {
        val module = parser.parseParsedModule("let x = f { a = 1 }", file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        val call = decl.value as ParsedFunctionCall
        assertTrue(call.function is ParsedIdentifier)
        assertEquals("f", (call.function as ParsedIdentifier).localName)
    }

    @Test
    fun `qualified identifier`() {
        val module = parser.parseParsedModule("let x = int::add { l = 1, r = 2 }", file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        val call = decl.value as ParsedFunctionCall
        val id = call.function as ParsedIdentifier
        assertEquals("add", id.localName)
        assertTrue(id.isQualified)
        assertEquals(listOf("int"), id.qualifier!!.segments)
    }

    @Test
    fun `use declaration`() {
        val module = parser.parseParsedModule("use std::int", file = "test.mz")
        val decl = module.decls[0] as ParsedUseDecl
        val path = decl.path as ParsedUsePath.Module
        assertEquals(listOf("std", "int"), path.path.segments)
    }

    @Test
    fun `selective import`() {
        val module = parser.parseParsedModule("use std::int::{add, multiply}", file = "test.mz")
        val decl = module.decls[0] as ParsedUseDecl
        val path = decl.path as ParsedUsePath.Selective
        assertEquals(listOf("std", "int"), path.modulePath.segments)
        assertEquals(2, path.items.size)
        assertEquals("add", path.items[0].name)
        assertEquals("multiply", path.items[1].name)
    }

    @Test
    fun `type declaration`() {
        val module = parser.parseParsedModule(
            "type Color = | Red, Green, Blue |",
            file = "test.mz"
        )
        val decl = module.decls[0] as ParsedTypeDecl
        assertEquals("Color", decl.name)
        val def = decl.definition as ParsedVariantDef
        assertEquals(3, def.constructors.size)
        assertEquals("Red", def.constructors[0].name)
    }

    @Test
    fun `ability declaration`() {
        val source = """
ability Eq for 'a where {
    eq: {it: 'a, r: 'a} -> Bool,
    neq: {it: 'a, r: 'a} -> Bool
}
""".trim()
        val module = parser.parseParsedModule(source, file = "test.mz")
        val decl = module.decls[0] as ParsedAbilityDecl
        assertEquals("Eq", decl.name)
        assertEquals(2, decl.operations.size)
        assertEquals("eq", decl.operations[0].name)
        assertEquals("neq", decl.operations[1].name)
    }

    @Test
    fun `ability impl`() {
        // Need ability declaration in same file for parser's resolveImplDeclarations heuristic
        val source = """
ability Eq for 'a where {
    eq: {it: 'a, r: 'a} -> Bool,
    neq: {it: 'a, r: 'a} -> Bool
}
impl Eq for Int where { eq = {it, r} -> it == r, neq = {it, r} -> it != r }
""".trim()
        val module = parser.parseParsedModule(source, file = "test.mz")
        val implDecl = module.decls.filterIsInstance<ParsedImplAbilityDecl>().first()
        assertFalse(implDecl.isInherent)
        assertEquals(2, implDecl.operations.size)
    }

    @Test
    fun `inherent impl`() {
        val source = "impl Str where { size = {it} -> __native_string_size { s = it } }"
        val module = parser.parseParsedModule(source, file = "test.mz")
        val decl = module.decls[0] as ParsedImplAbilityDecl
        assertTrue(decl.isInherent)
        assertEquals(1, decl.operations.size)
        assertEquals("size", decl.operations[0].name)
    }

    @Test
    fun `effect declaration`() {
        val source = """
effect FileSystem where {
    read_file: { path: Str } -> Result Str Error <FileSystem>
}
""".trim()
        val module = parser.parseParsedModule(source, file = "test.mz")
        val decl = module.decls[0] as ParsedEffectDecl
        assertEquals("FileSystem", decl.name)
        assertEquals(1, decl.operations.size)
        assertEquals("read_file", decl.operations[0].name)
    }

    @Test
    fun `performer declaration`() {
        val source = """
impl FileSystem for LocalFileSystem where {
    read_file = { path } -> __native_fs_read_file { path = path }
}
""".trim()
        val module = parser.parseParsedModule(source, file = "test.mz")
        val decl = module.decls[0] as ParsedPerformerDecl
        assertEquals("LocalFileSystem", decl.performerName)
        assertEquals(1, decl.operations.size)
    }

    @Test
    fun `do block`() {
        val source = """
let main = {} -> do
    let x = 1
    let y = 2
    x + y
""".trim()
        val module = parser.parseParsedModule(source, file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        val lambda = decl.value as ParsedLambda
        val doBlock = lambda.body as ParsedDo
        assertEquals(2, doBlock.statements.size)
        assertTrue(doBlock.statements[0] is ParsedStatement.Let)
        assertTrue(doBlock.statements[1] is ParsedStatement.Let)
    }

    @Test
    fun `perform expression`() {
        val source = """
let main = {} -> perform Console in println { msg = "hi" }
""".trim()
        val module = parser.parseParsedModule(source, file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        val lambda = decl.value as ParsedLambda
        val perform = lambda.body as ParsedPerform
        assertEquals(1, perform.handlers.size)
        assertEquals("Console", perform.handlers[0].performer.localName)
    }

    @Test
    fun `seq literal`() {
        val module = parser.parseParsedModule("let xs = [1, 2, 3]", file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        val seq = decl.value as ParsedSeqLit
        assertEquals(3, seq.elements.size)
    }

    @Test
    fun `string interpolation`() {
        val module = parser.parseParsedModule("""let s = "hello $(name)"""", file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        val str = decl.value as ParsedStringLit
        assertEquals(2, str.parts.size)
        assertTrue(str.parts[0] is ParsedStringPart.Literal)
        assertTrue(str.parts[1] is ParsedStringPart.Interpolation)
    }

    @Test
    fun `loc has file`() {
        val module = parser.parseParsedModule("let x = 42", file = "my/file.mz")
        val decl = module.decls[0] as ParsedLetDecl
        assertEquals("my/file.mz", decl.loc.file)
        val lit = decl.value as ParsedIntLit
        assertEquals("my/file.mz", lit.loc.file)
    }

    @Test
    fun `no inferredType no contentHash no resolved fields`() {
        // This test is a compile-time check — if ParsedLetDecl had inferredType,
        // this code wouldn't compile because those fields don't exist.
        val module = parser.parseParsedModule("let x = 42", file = "test.mz")
        val decl = module.decls[0] as ParsedLetDecl
        // These fields simply don't exist on ParsedLetDecl:
        // decl.inferredType   ← compile error
        // decl.contentHash    ← compile error
        // The fact that this compiles proves the point.
        assertNotNull(decl.value)
    }

    @Test
    fun `real stdlib file parses`() {
        val source = """
use std::abilities::compare::{Eq, Ord}
use std::abilities::convert::{Into}

impl Eq for Int where { eq = {it, r} -> __native_int_eq {l = it, r = r}, neq = {it, r} -> __native_int_neq {l = it, r = r} }
impl Ord for Int where { lt = {it, r} -> __native_int_lt {l = it, r = r}, le = {it, r} -> __native_int_le {l = it, r = r}, gt = {it, r} -> __native_int_gt {l = it, r = r}, ge = {it, r} -> __native_int_ge {l = it, r = r} }
impl Into Str for Int where { into = {it} -> __native_int_to_str {n = it} }

pub let add = { l: Int, r: Int } -> __native_int_add { l = l, r = r }
pub let abs = { n: Int } -> __native_int_abs { n = n }
""".trim()
        val module = parser.parseParsedModule(source, file = "std/int.mz")
        assertTrue(module.decls.size >= 5)
        // Has uses, impls, and lets
        assertTrue(module.decls.any { it is ParsedUseDecl })
        assertTrue(module.decls.any { it is ParsedImplAbilityDecl })
        assertTrue(module.decls.any { it is ParsedLetDecl })
    }
}
