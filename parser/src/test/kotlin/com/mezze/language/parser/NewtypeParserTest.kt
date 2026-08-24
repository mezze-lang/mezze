package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class NewtypeParserTest {

    private val parser = MezzeParser()

    @Test
    fun `test simple newtype with primitive type`() {
        val source = "newtype Email = String"

        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(1, module.decls.size)

        val typeDecl = module.decls[0] as ParsedTypeDecl
        assertEquals("Email", typeDecl.name)
        assertEquals(Visibility.PRIVATE, typeDecl.visibility)
        assertEquals(emptyList<String>(), typeDecl.typeParams)

        val newtypeDef = typeDecl.definition as ParsedNewtypeDef
        assertEquals("Email", newtypeDef.constructor)
        assertTrue(newtypeDef.wrappedType is ParsedNamedType)

        println("✅ Simple newtype with primitive type parsed successfully!")
    }

    @Test
    fun `test public newtype`() {
        val source = "pub newtype UserId = Int"

        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(1, module.decls.size)

        val typeDecl = module.decls[0] as ParsedTypeDecl
        assertEquals("UserId", typeDecl.name)
        assertEquals(Visibility.PUBLIC, typeDecl.visibility)

        val newtypeDef = typeDecl.definition as ParsedNewtypeDef
        assertEquals("UserId", newtypeDef.constructor)

        println("✅ Public newtype parsed successfully!")
    }

    @Test
    fun `test newtype with record type`() {
        val source = "newtype Point = { x: Float, y: Float }"

        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(1, module.decls.size)

        val typeDecl = module.decls[0] as ParsedTypeDecl
        assertEquals("Point", typeDecl.name)

        val newtypeDef = typeDecl.definition as ParsedNewtypeDef
        assertEquals("Point", newtypeDef.constructor)
        assertTrue(newtypeDef.wrappedType is ParsedRecordType)

        val recordType = newtypeDef.wrappedType as ParsedRecordType
        assertEquals(2, recordType.entries.size)

        println("✅ Newtype with record type parsed successfully!")
    }

    @Test
    fun `test newtype with type parameter`() {
        val source = "newtype Option 'a = 'a"

        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(1, module.decls.size)

        val typeDecl = module.decls[0] as ParsedTypeDecl
        assertEquals("Option", typeDecl.name)
        assertEquals(listOf("'a"), typeDecl.typeParams)

        val newtypeDef = typeDecl.definition as ParsedNewtypeDef
        assertEquals("Option", newtypeDef.constructor)

        println("✅ Newtype with type parameter parsed successfully!")
    }

    @Test
    fun `test newtype with multiple type parameters`() {
        val source = "newtype Pair 'a 'b = { first: 'a, second: 'b }"

        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(1, module.decls.size)

        val typeDecl = module.decls[0] as ParsedTypeDecl
        assertEquals("Pair", typeDecl.name)
        assertEquals(listOf("'a", "'b"), typeDecl.typeParams)

        val newtypeDef = typeDecl.definition as ParsedNewtypeDef
        assertEquals("Pair", newtypeDef.constructor)
        assertTrue(newtypeDef.wrappedType is ParsedRecordType)

        println("✅ Newtype with multiple type parameters parsed successfully!")
    }

    @Test
    fun `test newtype with function type`() {
        val source = "newtype Handler = { x: Int } -> String"

        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(1, module.decls.size)

        val typeDecl = module.decls[0] as ParsedTypeDecl
        assertEquals("Handler", typeDecl.name)

        val newtypeDef = typeDecl.definition as ParsedNewtypeDef
        assertEquals("Handler", newtypeDef.constructor)
        assertTrue(newtypeDef.wrappedType is ParsedFunctionType)

        println("✅ Newtype with function type parsed successfully!")
    }

    @Test
    fun `test newtype with multiline formatting`() {
        val source = """
            newtype Email =
                String
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(1, module.decls.size)

        val typeDecl = module.decls[0] as ParsedTypeDecl
        assertEquals("Email", typeDecl.name)

        val newtypeDef = typeDecl.definition as ParsedNewtypeDef
        assertEquals("Email", newtypeDef.constructor)

        println("✅ Newtype with multiline formatting parsed successfully!")
    }

    @Test
    fun `test multiple newtype declarations`() {
        val source = """
            newtype Email = String
            newtype UserId = Int
            newtype Name = String
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(3, module.decls.size)

        val typeDecl1 = module.decls[0] as ParsedTypeDecl
        assertEquals("Email", typeDecl1.name)

        val typeDecl2 = module.decls[1] as ParsedTypeDecl
        assertEquals("UserId", typeDecl2.name)

        val typeDecl3 = module.decls[2] as ParsedTypeDecl
        assertEquals("Name", typeDecl3.name)

        println("✅ Multiple newtype declarations parsed successfully!")
    }

    @Test
    fun `test newtype with Array type`() {
        val source = "newtype Names = [String]"

        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(1, module.decls.size)

        val typeDecl = module.decls[0] as ParsedTypeDecl
        assertEquals("Names", typeDecl.name)

        val newtypeDef = typeDecl.definition as ParsedNewtypeDef
        assertEquals("Names", newtypeDef.constructor)
        assertTrue(newtypeDef.wrappedType is ParsedSeqType)

        println("✅ Newtype with Array type parsed successfully!")
    }

    @Test
    fun `test newtype constructor different from type name`() {
        val source = "newtype EmailAddress = MkEmail String"

        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(1, module.decls.size)

        val typeDecl = module.decls[0] as ParsedTypeDecl
        assertEquals("EmailAddress", typeDecl.name)

        val newtypeDef = typeDecl.definition as ParsedNewtypeDef
        assertEquals("MkEmail", newtypeDef.constructor)

        println("✅ Newtype with different constructor name parsed successfully!")
    }

    @Test
    fun `test newtype with effects in wrapped type`() {
        val source = "newtype Action = { } -> {} <Log>"

        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(1, module.decls.size)

        val typeDecl = module.decls[0] as ParsedTypeDecl
        assertEquals("Action", typeDecl.name)

        val newtypeDef = typeDecl.definition as ParsedNewtypeDef
        assertEquals("Action", newtypeDef.constructor)
        assertTrue(newtypeDef.wrappedType is ParsedFunctionType)

        val funcType = newtypeDef.wrappedType as ParsedFunctionType
        assertNotNull(funcType.effects)

        println("✅ Newtype with effects in wrapped type parsed successfully!")
    }

}
