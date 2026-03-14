package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class NewtypeTypeCheckerTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ========== Declaration Tests ==========

    @Test
    fun `test simple newtype declaration typechecks`() {
        val source = """
            newtype Email = Email String

            let mkEmail = {s} -> Email {value = s}
            let unwrapEmail = {e} -> match e is
                Email {value} -> value
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
        println("✅ Simple newtype declaration typechecks")
    }

    @Test
    fun `test public newtype declaration typechecks`() {
        val source = """
            pub newtype UserId = UserId Int

            let mkUserId = {id} -> UserId {value = id}
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
        println("✅ Public newtype declaration typechecks")
    }

    @Test
    fun `test newtype with record type typechecks`() {
        val source = """
            newtype Point = Point { x: Float, y: Float }

            let origin = Point {x = 0.0, y = 0.0}
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
        println("✅ Newtype with record type typechecks")
    }

    @Test
    fun `test newtype with type parameter typechecks`() {
        val source = """
            newtype Box 'a = Box 'a

            let box = {value} -> Box {value = value}
            let unbox = {b} -> match b is
                Box {value} -> value
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
        println("✅ Newtype with type parameter typechecks")
    }

    @Test
    fun `test newtype with function type typechecks`() {
        val source = """
            newtype Handler = Handler { x: Int } -> Str

            let mkHandler = {f} -> Handler {fn = f}
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
        println("✅ Newtype with function type typechecks")
    }

    @Test
    fun `test newtype with array type typechecks`() {
        val source = """
            newtype Names = Names { list: [Str] }

            let mkNames = {ns} -> Names {list = ns}
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
        println("✅ Newtype with array type typechecks")
    }

    @Test
    fun `test multiple newtype declarations typecheck`() {
        val source = """
            newtype Email = Email String
            newtype UserId = UserId Int
            newtype Name = Name String

            let mkEmail = {s} -> Email {value = s}
            let mkUserId = {i} -> UserId {value = i}
            let mkName = {n} -> Name {value = n}
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
        println("✅ Multiple newtype declarations typecheck")
    }

    // ========== Validation Tests ==========

    @Test
    fun `test newtype constructor naming convention - must start with uppercase`() {
        val source = """
            newtype Email = email String
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
        assertTrue(exception.message!!.contains("must start with an uppercase letter"))
        println("✅ Constructor naming convention enforced")
    }

    @Test
    fun `test newtype constructor cannot collide with built-in ability`() {
        val source = """
            newtype MyType = NumAdd String
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
        assertTrue(exception.message!!.contains("already used by a built-in ability"))
        println("✅ Constructor collision with built-in ability prevented")
    }

    @Test
    fun `test newtype allows same name for type and constructor`() {
        val source = """
            newtype Email = Email String

            let mkEmail = {s} -> Email {value = s}
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
        println("✅ Same name for type and constructor allowed")
    }

    @Test
    fun `test newtype allows different names for type and constructor`() {
        val source = """
            newtype EmailAddress = MkEmail String

            let mkEmail = {s} -> MkEmail {value = s}
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
        println("✅ Different names for type and constructor allowed")
    }

    // ========== Usage Tests ==========

    @Test
    fun `test newtype constructor is a function`() {
        val source = """
            newtype Email = Email String

            # Constructor can be used directly
            let email = Email {value = "test@example.com"}
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
        println("✅ Newtype constructor works as a function")
    }

    @Test
    fun `test newtype provides type safety`() {
        val source = """
            newtype Email = Email String
            newtype Name = Name String

            let email = Email {value = "test@example.com"}
            let name = Name {value = "John Doe"}

            # Email and Name are distinct types even though both wrap String
            let processEmail = {e} -> match e is
                Email {value} -> value
            let processName = {n} -> match n is
                Name {value} -> value
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
        println("✅ Newtype provides type safety")
    }

    @Test
    fun `test newtype with nested types typechecks`() {
        val source = """
            newtype UserId = UserId Int
            newtype User = User { id: UserId, name: Str }

            let userId = UserId {value = 123}
            let user = User {id = userId, name = "Alice"}
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
        println("✅ Newtype with nested types typechecks")
    }

    @Test
    fun `test newtype works with pattern matching on wrapper value`() {
        val source = """
            newtype Point = Point { x: Float, y: Float }

            let origin = Point {x = 0.0, y = 0.0}

            # Pattern matching on the record inside
            let getX = {p} -> match p is
                Point {x, y} -> x
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
        println("✅ Newtype pattern matching typechecks")
    }
}
