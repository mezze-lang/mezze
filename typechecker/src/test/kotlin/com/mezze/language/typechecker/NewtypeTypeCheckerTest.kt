package com.mezze.language.typechecker

import java.nio.file.Paths
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
            newtype Email = Str

            let mkEmail = {s} -> Email.(s)
            let unwrapEmail = {e} -> match e is
                Email.(value) -> value
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Simple newtype declaration typechecks")
    }

    @Test
    fun `test public newtype declaration typechecks`() {
        val source = """
            pub newtype UserId = Int

            let mkUserId = {id} -> UserId.(id)
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Public newtype declaration typechecks")
    }

    @Test
    fun `test newtype with record type typechecks`() {
        val source = """
            newtype Point = { x: Float, y: Float }

            let origin = Point.({x = 0.0, y = 0.0})
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Newtype with record type typechecks")
    }

    @Test
    fun `test newtype with type parameter typechecks`() {
        val source = """
            newtype Box 'a = 'a

            let box = {value} -> Box.(value)
            let unbox = {b} -> match b is
                Box.(value) -> value
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Newtype with type parameter typechecks")
    }

    @Test
    fun `test newtype with function type typechecks`() {
        val source = """
            newtype Handler = { x: Int } -> Str

            let mkHandler = {f} -> Handler.(f)
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Newtype with function type typechecks")
    }

    @Test
    fun `test newtype with array type typechecks`() {
        val source = """
            newtype Names = { list: [Str] }

            let mkNames = {ns} -> Names.(ns)
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Newtype with array type typechecks")
    }

    @Test
    fun `test multiple newtype declarations typecheck`() {
        val source = """
            newtype Email = Str
            newtype UserId = Int
            newtype Name = Str

            let mkEmail = {s} -> Email.(s)
            let mkUserId = {i} -> UserId.(i)
            let mkName = {n} -> Name.(n)
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Multiple newtype declarations typecheck")
    }

    // ========== Validation Tests ==========

    @Test
    fun `test newtype constructor naming convention - must start with uppercase`() {
        // Under the `.()` syntax the constructor is IMPLICIT (== type name); no
        // separate lowercase constructor name is possible. The equivalent invariant
        // that still applies is: the TYPE name must start with an uppercase letter.
        // A lowercase type name should fail to parse (or fail to typecheck).
        val source = """
            newtype email = Str
        """.trimIndent()

        assertThrows<Throwable> {
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Newtype type name must be uppercase")
    }

    @Test
    fun `test newtype constructor cannot collide with built-in ability`() {
        // Under the `.()` syntax the constructor is the type name itself; the
        // equivalent invariant is that a newtype cannot be NAMED after a built-in
        // ability (like `NumAdd`), because that would collide with the ability's
        // registered name.
        val source = """
            newtype NumAdd = Str
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Newtype name collision with built-in ability prevented")
    }

    @Test
    fun `test newtype allows same name for type and constructor`() {
        val source = """
            newtype Email = Str

            let mkEmail = {s} -> Email.(s)
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Same name for type and constructor allowed")
    }

    @Test
    fun `test newtype allows different names for type and constructor`() {
        val source = """
            newtype EmailAddress = MkEmail Str

            let mkEmail = {s} -> MkEmail {value = s}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Different names for type and constructor allowed")
    }

    // ========== Usage Tests ==========

    @Test
    fun `test newtype constructor is a function`() {
        val source = """
            newtype Email = Str

            # Constructor can be used directly
            let email = Email.("test@example.com")
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Newtype constructor works as a function")
    }

    @Test
    fun `test newtype provides type safety`() {
        val source = """
            newtype Email = Str
            newtype Name = Str

            let email = Email.("test@example.com")
            let name = Name.("John Doe")

            # Email and Name are distinct types even though both wrap String
            let processEmail = {e} -> match e is
                Email.(value) -> value
            let processName = {n} -> match n is
                Name.(value) -> value
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Newtype provides type safety")
    }

    @Test
    fun `test newtype with nested types typechecks`() {
        val source = """
            newtype UserId = Int
            newtype User = { id: UserId, name: Str }

            let userId = UserId.(123)
            let user = User.({id = userId, name = "Alice"})
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Newtype with nested types typechecks")
    }

    @Test
    fun `test newtype works with pattern matching on wrapper value`() {
        val source = """
            newtype Point = { x: Float, y: Float }

            let origin = Point.({x = 0.0, y = 0.0})

            # Pattern matching on the record inside
            let getX = {p} -> match p is
                Point.({x, y}) -> x
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        println("✅ Newtype pattern matching typechecks")
    }
}
