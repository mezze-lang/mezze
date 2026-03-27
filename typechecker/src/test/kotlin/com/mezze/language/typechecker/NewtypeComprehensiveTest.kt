package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Nested

/**
 * Comprehensive tests for newtype behavior.
 *
 * Newtypes are compile-time only wrappers — erased at runtime.
 * They create distinct types that block auto-derivation of Eq/Ord/Codec.
 * Only Inspect is universally available.
 *
 * Syntax:
 *   newtype UserId = UserId Int
 *   newtype Point = Point { x: Float, y: Float }
 *
 * Construction:
 *   UserId 42              → at runtime: 42
 *   Point { x = 1.0, y = 2.0 } → at runtime: { x = 1.0, y = 2.0 }
 *
 * Pattern matching:
 *   match uid is UserId val -> val        → at runtime: let val = uid
 *   match pt is Point { x, y } -> x      → at runtime: pt.x
 */
class NewtypeComprehensiveTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    // ========================================================================
    // TYPE CHECKING — CONSTRUCTION
    // ========================================================================

    @Nested
    inner class Construction {

        @Test
        fun `newtype wrapping Int type-checks`() {
            val source = """
                newtype UserId = UserId Int
                let uid = UserId {value = 42}
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }

        @Test
        fun `newtype wrapping String type-checks`() {
            val source = """
                newtype Email = Email String
                let email = Email {value = "test@example.com"}
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }

        @Test
        fun `newtype wrapping record type-checks`() {
            val source = """
                newtype Point = Point { x: Float, y: Float }
                let p = Point { x = 3.0, y = 4.0 }
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }

        @Test
        fun `multiple newtypes wrapping same type are distinct`() {
            val source = """
                newtype UserId = UserId Int
                newtype Age = Age Int
                let uid = UserId {value = 42}
                let age = Age {value = 25}
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }
    }

    // ========================================================================
    // TYPE CHECKING — PATTERN MATCHING
    // ========================================================================

    @Nested
    inner class PatternMatching {

        @Test
        fun `pattern match unwraps newtype with Int`() {
            val source = """
                newtype UserId = UserId Int
                let uid = UserId {value = 42}
                let val = match uid is
                    UserId {value} -> value
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }

        @Test
        fun `pattern match unwraps newtype with record`() {
            val source = """
                newtype Point = Point { x: Float, y: Float }
                let p = Point { x = 3.0, y = 4.0 }
                let x_val = match p is
                    Point { x, y } -> x
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }

        @Test
        fun `pattern match in function parameter`() {
            val source = """
                newtype Email = Email String
                let unwrap = {e} -> match e is
                    Email {value} -> value
                let result = unwrap {e = Email {value = "test"}}
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }
    }

    // ========================================================================
    // TYPE CHECKING — ABILITY DERIVATION (BLOCKED)
    // ========================================================================

    @Nested
    inner class AbilityDerivation {

        @Test
        fun `newtype does NOT auto-derive Eq`() {
            val source = """
                newtype UserId = UserId Int
                let same = {a: UserId, b: UserId} -> a == b
            """.trimIndent()
            assertThrows<TypeCheckError> {
                typeChecker.typeCheck(parser.parse(source))
            }
        }

        @Test
        fun `newtype does NOT auto-derive Ord`() {
            val source = """
                newtype UserId = UserId Int
                let cmp = {a: UserId, b: UserId} -> a < b
            """.trimIndent()
            assertThrows<TypeCheckError> {
                typeChecker.typeCheck(parser.parse(source))
            }
        }

        @Test
        fun `newtype DOES have Inspect (universal)`() {
            val source = """
                newtype Email = Email String
                let dbg = {e: Email} -> e.to_dbg_str {}
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }

        @Test
        fun `newtype with explicit Eq impl works`() {
            val source = """
                newtype UserId = UserId Int

                ability Eq for 'a where {
                    eq: {it: 'a, r: 'a} -> Bool
                }

                impl Eq for UserId where {
                    eq = {it, r} ->
                        match it is
                            UserId {val = a} -> match r is
                                UserId {val = b} -> a == b
                }

                let same = {a: UserId, b: UserId} -> a.eq {r = b}
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }

        @Test
        fun `regular variant DOES auto-derive Eq`() {
            val source = """
                type Color = | Red, Green, Blue |
                let same = {a: Color, b: Color} -> a == b
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }

        @Test
        fun `single-constructor variant (not newtype) auto-derives Eq`() {
            val source = """
                type Wrapper = | Wrap Int |
                let same = {a: Wrapper, b: Wrapper} -> a == b
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }

        @Test
        fun `record type auto-derives Eq`() {
            val source = """
                type Point = { x: Int, y: Int }
                let same = {a: Point, b: Point} -> a == b
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }
    }

    // ========================================================================
    // TYPE CHECKING — TYPE SAFETY
    // ========================================================================

    @Nested
    inner class TypeSafety {

        @Test
        fun `cannot assign Int to newtype parameter`() {
            val source = """
                newtype UserId = UserId Int
                let process = {uid: UserId} -> uid
                let result = process {uid = 42}
            """.trimIndent()
            assertThrows<TypeCheckError> {
                typeChecker.typeCheck(parser.parse(source))
            }
        }

        @Test
        fun `cannot mix different newtypes`() {
            val source = """
                newtype UserId = UserId Int
                newtype Age = Age Int
                let process = {uid: UserId} -> uid
                let result = process {uid = Age {value = 25}}
            """.trimIndent()
            assertThrows<TypeCheckError> {
                typeChecker.typeCheck(parser.parse(source))
            }
        }

        @Test
        fun `newtype function return type is the newtype not inner type`() {
            val source = """
                newtype UserId = UserId Int
                let mkUser = {n: Int} -> UserId {value = n}
                let uid = mkUser {n = 42}
                let val = match uid is
                    UserId {value} -> value
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }
    }

    // ========================================================================
    // TYPE CHECKING — NESTED NEWTYPES
    // ========================================================================

    @Nested
    inner class NestedNewtypes {

        @Test
        fun `newtype wrapping another newtype`() {
            val source = """
                newtype UserId = UserId Int
                newtype User = User { id: UserId, name: String }
                let uid = UserId {value = 42}
                let user = User { id = uid, name = "Alice" }
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }

        @Test
        fun `nested newtype pattern matching`() {
            val source = """
                newtype UserId = UserId Int
                newtype User = User { id: UserId, name: String }
                let uid = UserId {value = 42}
                let user = User { id = uid, name = "Alice" }
                let getId = {u} -> match u is
                    User { id, name } -> match id is
                        UserId {value} -> value
            """.trimIndent()
            val result = typeChecker.typeCheck(parser.parse(source))
            assertNotNull(result)
        }
    }

    // ========================================================================
    // OPACITY — CONSTRUCTOR PRIVATE TO DEFINING MODULE
    // ========================================================================

    @Nested
    inner class Opacity {

        @org.junit.jupiter.api.io.TempDir
        lateinit var tempDir: java.nio.file.Path

        @Test
        fun `cannot construct imported newtype from another module`() {
            // Define newtype in one module
            java.nio.file.Files.writeString(tempDir.resolve("types.mz"), """
                pub newtype UserId = UserId Int
            """.trimIndent())

            // Try to construct it from another module — should fail
            java.nio.file.Files.writeString(tempDir.resolve("user.mz"), """
                use types::{UserId}
                let uid = UserId {value = 42}
            """.trimIndent())

            val userProgram = parser.parse(
                java.nio.file.Files.readString(tempDir.resolve("user.mz"))
            )
            assertThrows<TypeCheckError> {
                typeChecker.typeCheck(userProgram, tempDir)
            }
        }

        @Test
        fun `can use imported newtype as parameter type`() {
            // Define newtype in one module
            java.nio.file.Files.writeString(tempDir.resolve("types.mz"), """
                pub newtype UserId = UserId Int
                pub let make_user_id = {n: Int} -> UserId {val = n}
            """.trimIndent())

            // Use the type (but not construct/destruct)
            java.nio.file.Files.writeString(tempDir.resolve("user.mz"), """
                use types::{UserId, make_user_id}
                let uid = make_user_id {n = 42}
                let process = {id: UserId} -> id
                let result = process {id = uid}
            """.trimIndent())

            val userProgram = parser.parse(
                java.nio.file.Files.readString(tempDir.resolve("user.mz"))
            )
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result)
        }
    }
}
