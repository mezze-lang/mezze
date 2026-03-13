package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class NewtypeParserTest {

    private val parser = MezzeParser()

    @Test
    fun `test simple newtype with primitive type`() {
        val source = "newtype Email = Email String"

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(1, program.items.size)

        val typeDecl = program.items[0] as TypeDeclaration
        assertEquals("Email", typeDecl.name)
        assertEquals(Visibility.PRIVATE, typeDecl.visibility)
        assertEquals(emptyList<String>(), typeDecl.typeParams)

        val newtypeDef = typeDecl.definition as NewtypeDefinition
        assertEquals("Email", newtypeDef.constructor)
        assertTrue(newtypeDef.wrappedType is IdentifierTypeAnnotation)

        println("✅ Simple newtype with primitive type parsed successfully!")
    }

    @Test
    fun `test public newtype`() {
        val source = "pub newtype UserId = UserId Int"

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(1, program.items.size)

        val typeDecl = program.items[0] as TypeDeclaration
        assertEquals("UserId", typeDecl.name)
        assertEquals(Visibility.PUBLIC, typeDecl.visibility)

        val newtypeDef = typeDecl.definition as NewtypeDefinition
        assertEquals("UserId", newtypeDef.constructor)

        println("✅ Public newtype parsed successfully!")
    }

    @Test
    fun `test newtype with record type`() {
        val source = "newtype Point = Point { x: Float, y: Float }"

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(1, program.items.size)

        val typeDecl = program.items[0] as TypeDeclaration
        assertEquals("Point", typeDecl.name)

        val newtypeDef = typeDecl.definition as NewtypeDefinition
        assertEquals("Point", newtypeDef.constructor)
        assertTrue(newtypeDef.wrappedType is RecordTypeAnnotation)

        val recordType = newtypeDef.wrappedType as RecordTypeAnnotation
        assertEquals(2, recordType.entries.size)

        println("✅ Newtype with record type parsed successfully!")
    }

    @Test
    fun `test newtype with type parameter`() {
        val source = "newtype Option 'a = Option 'a"

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(1, program.items.size)

        val typeDecl = program.items[0] as TypeDeclaration
        assertEquals("Option", typeDecl.name)
        assertEquals(listOf("'a"), typeDecl.typeParams)

        val newtypeDef = typeDecl.definition as NewtypeDefinition
        assertEquals("Option", newtypeDef.constructor)

        println("✅ Newtype with type parameter parsed successfully!")
    }

    @Test
    fun `test newtype with multiple type parameters`() {
        val source = "newtype Pair 'a 'b = Pair { first: 'a, second: 'b }"

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(1, program.items.size)

        val typeDecl = program.items[0] as TypeDeclaration
        assertEquals("Pair", typeDecl.name)
        assertEquals(listOf("'a", "'b"), typeDecl.typeParams)

        val newtypeDef = typeDecl.definition as NewtypeDefinition
        assertEquals("Pair", newtypeDef.constructor)
        assertTrue(newtypeDef.wrappedType is RecordTypeAnnotation)

        println("✅ Newtype with multiple type parameters parsed successfully!")
    }

    @Test
    fun `test newtype with function type`() {
        val source = "newtype Handler = Handler { x: Int } -> String"

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(1, program.items.size)

        val typeDecl = program.items[0] as TypeDeclaration
        assertEquals("Handler", typeDecl.name)

        val newtypeDef = typeDecl.definition as NewtypeDefinition
        assertEquals("Handler", newtypeDef.constructor)
        assertTrue(newtypeDef.wrappedType is FunctionTypeAnnotation)

        println("✅ Newtype with function type parsed successfully!")
    }

    @Test
    fun `test newtype with multiline formatting`() {
        val source = """
            newtype Email =
                Email String
        """.trimIndent()

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(1, program.items.size)

        val typeDecl = program.items[0] as TypeDeclaration
        assertEquals("Email", typeDecl.name)

        val newtypeDef = typeDecl.definition as NewtypeDefinition
        assertEquals("Email", newtypeDef.constructor)

        println("✅ Newtype with multiline formatting parsed successfully!")
    }

    @Test
    fun `test multiple newtype declarations`() {
        val source = """
            newtype Email = Email String
            newtype UserId = UserId Int
            newtype Name = Name String
        """.trimIndent()

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(3, program.items.size)

        val typeDecl1 = program.items[0] as TypeDeclaration
        assertEquals("Email", typeDecl1.name)

        val typeDecl2 = program.items[1] as TypeDeclaration
        assertEquals("UserId", typeDecl2.name)

        val typeDecl3 = program.items[2] as TypeDeclaration
        assertEquals("Name", typeDecl3.name)

        println("✅ Multiple newtype declarations parsed successfully!")
    }

    @Test
    fun `test newtype with Array type`() {
        val source = "newtype Names = Names [String]"

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(1, program.items.size)

        val typeDecl = program.items[0] as TypeDeclaration
        assertEquals("Names", typeDecl.name)

        val newtypeDef = typeDecl.definition as NewtypeDefinition
        assertEquals("Names", newtypeDef.constructor)
        assertTrue(newtypeDef.wrappedType is SeqTypeAnnotation)

        println("✅ Newtype with Array type parsed successfully!")
    }

    @Test
    fun `test newtype constructor different from type name`() {
        val source = "newtype EmailAddress = MkEmail String"

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(1, program.items.size)

        val typeDecl = program.items[0] as TypeDeclaration
        assertEquals("EmailAddress", typeDecl.name)

        val newtypeDef = typeDecl.definition as NewtypeDefinition
        assertEquals("MkEmail", newtypeDef.constructor)

        println("✅ Newtype with different constructor name parsed successfully!")
    }

    @Test
    fun `test newtype with effects in wrapped type`() {
        val source = "newtype Action = Action { } -> {} <Log>"

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(1, program.items.size)

        val typeDecl = program.items[0] as TypeDeclaration
        assertEquals("Action", typeDecl.name)

        val newtypeDef = typeDecl.definition as NewtypeDefinition
        assertEquals("Action", newtypeDef.constructor)
        assertTrue(newtypeDef.wrappedType is FunctionTypeAnnotation)

        val funcType = newtypeDef.wrappedType as FunctionTypeAnnotation
        assertNotNull(funcType.effects)

        println("✅ Newtype with effects in wrapped type parsed successfully!")
    }

    @Test
    fun `test newtype with deriving single ability`() {
        val source = "newtype UserId = UserId Int deriving Eq"

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(1, program.items.size)

        val typeDecl = program.items[0] as TypeDeclaration
        assertEquals("UserId", typeDecl.name)

        val newtypeDef = typeDecl.definition as NewtypeDefinition
        assertEquals("UserId", newtypeDef.constructor)
        assertEquals(listOf("Eq"), newtypeDef.derivedAbilities)

        println("✅ Newtype with deriving single ability parsed successfully!")
    }

    @Test
    fun `test newtype with deriving multiple abilities`() {
        val source = "newtype Point = Point { x: Float, y: Float } deriving Eq, Ord"

        val program = parser.parse(source)
        assertNotNull(program)
        assertEquals(1, program.items.size)

        val typeDecl = program.items[0] as TypeDeclaration
        assertEquals("Point", typeDecl.name)

        val newtypeDef = typeDecl.definition as NewtypeDefinition
        assertEquals("Point", newtypeDef.constructor)
        assertEquals(listOf("Eq", "Ord"), newtypeDef.derivedAbilities)

        println("✅ Newtype with deriving multiple abilities parsed successfully!")
    }

    @Test
    fun `test newtype without deriving has empty derivedAbilities`() {
        val source = "newtype Email = Email String"

        val program = parser.parse(source)
        val typeDecl = program.items[0] as TypeDeclaration
        val newtypeDef = typeDecl.definition as NewtypeDefinition

        assertEquals(emptyList<String>(), newtypeDef.derivedAbilities)

        println("✅ Newtype without deriving has empty derivedAbilities!")
    }

    @Test
    fun `test public newtype with deriving`() {
        val source = "pub newtype UserId = UserId Int deriving Eq, Ord"

        val program = parser.parse(source)
        val typeDecl = program.items[0] as TypeDeclaration

        assertEquals(Visibility.PUBLIC, typeDecl.visibility)

        val newtypeDef = typeDecl.definition as NewtypeDefinition
        assertEquals(listOf("Eq", "Ord"), newtypeDef.derivedAbilities)

        println("✅ Public newtype with deriving parsed successfully!")
    }
}
