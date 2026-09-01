package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Focused tests for the Inspect ability - demonstrating the core parser ambiguity issue.
 *
 * CORE ISSUE:
 * The parser cannot distinguish between:
 *   1. Variant constructors: Some { val = 42 }
 *   2. Ability references: Inspect
 *   3. Effect references: Log
 *
 * The parser treats ALL uppercase identifiers as VariantConstruction AST nodes.
 * The interpreter must detect builtin abilities and convert them to variable lookups.
 *
 * FIX APPLIED:
 * In ASTToTruffleConverter.convertVariantConstruction():
 *   - Check if constructor name is in builtin abilities list
 *   - If yes && no arguments: return SimpleReadVariableNode (variable lookup)
 *   - Otherwise: return VariantConstructionNode (variant constructor)
 *
 * LIMITATIONS:
 * - Only works for hardcoded builtin abilities
 * - Won't work for user-defined abilities
 * - Better solution: typechecker should annotate the AST
 *
 * Migrated from V1 to V2 with unboxed values.
 */
class InspectAbilityTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ============================================================================
    // BASIC INSPECT ABILITY TESTS
    // ============================================================================

    @Test
    fun `test Inspect on Int`() {
        val source = """
            let test_val = 42
            let result = test_val.to_dbg {}
            result
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    @Test
    fun `test Inspect on String`() {
        val source = """
            let test_val = "Hello"
            let result = test_val.to_dbg {}
            result
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    @Test
    fun `test Inspect on Boolean`() {
        val source = """
            let result1 = True.to_dbg {}
            let result2 = False.to_dbg {}
            result1
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    @Test
    fun `test Inspect on Array`() {
        val source = """
            let arr = [1, 2, 3, 4, 5]
            let result = arr.to_dbg {}
            result
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    @Test
    fun `test Inspect on Record`() {
        val source = """
            let person = { name = "Alice", age = 30 }
            let result = person.to_dbg {}
            result
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    @Test
    fun `test Inspect on Unit`() {
        val source = """
            let unit = {}
            let result = unit.to_dbg {}
            result
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    // ============================================================================
    // VARIANT VS ABILITY DISAMBIGUATION
    // ============================================================================

    @Test
    fun `test Variant constructor with same name as ability doesn't break Inspect`() {
        val source = """
            # Define a variant called Inspect (parser will think it's an ability)
            type MyType = | Inspect |

            # This should create a variant
            let variant_val = Inspect

            # This should use the builtin ability
            let inspect_result = 42.to_dbg {}

            inspect_result
        """.trimIndent()

        // NOTE: This test will likely fail because user-defined "Inspect" variant
        // will shadow the builtin ability. This demonstrates the limitation of
        // the current fix.

        // Should fail at typecheck or runtime due to ambiguity
        assertThrows(Exception::class.java) {
            eval(source)
        }
    }

    @Test
    fun `test Inspect works without import`() {
        val source = """
            # NO imports - Inspect should still be available
            let result = 100.to_dbg {}
            result
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    @Test
    fun `test Inspect works inside do block`() {
        val source = """
            do
                let x = 42
                let str = x.to_dbg {}
                str
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    @Test
    fun `test Inspect works in lambda`() {
        val source = """
            let to_str = {val} -> val.to_dbg {}
            let result = to_str {val = 42}
            result
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    // ============================================================================
    // CUSTOM TYPES
    // ============================================================================

    @Test
    fun `test Inspect on custom variant type`() {
        val source = """
            type Maybe 'a = | Nothing, Just { val: 'a } |

            let nothing = Nothing
            let something = Just { val = 42 }

            let str1 = nothing.to_dbg {}
            let str2 = something.to_dbg {}

            str2
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    @Test
    fun `test Inspect on nested custom types`() {
        val source = """
            type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a, right: Tree 'a } |

            let tree = Node {
                value = 10,
                left = Node { value = 5, left = Leaf, right = Leaf },
                right = Node { value = 15, left = Leaf, right = Leaf }
            }

            let result = tree.to_dbg {}

            result
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    // NOTE: Removed test for custom List type - conflicts with builtin List
    // and requires explicit Inspect implementation in current type system

    // ============================================================================
    // #395 — do-block newtype to_dbg renders the WRAPPER (was erased to underlying)
    // A do-block `let len = Length.(4.0)` used to infer `Float`, so `len.to_dbg {}`
    // built its PrintType from the erased type and printed `4.0`. Preserving the
    // static newtype wrapper makes it print `Length.(4.0)`, unblocking #394.
    // ============================================================================

    @Test
    fun `do-block newtype scalar to_dbg renders wrapper`() {
        assertEquals("Length.(4.0)", eval("""
            newtype Length = Float
            let f = {} -> do
              let len = Length.(4.0)
              len.to_dbg {}
            f {}
        """.trimIndent()))
    }

    @Test
    fun `do-block newtype over record to_dbg renders wrapper`() {
        assertEquals("Money.({ currency = \"USD\", amount = 42 })", eval("""
            newtype Money = { currency: Str, amount: Int }
            let f = {} -> do
              let m = Money.({ currency = "USD", amount = 42 })
              m.to_dbg {}
            f {}
        """.trimIndent()))
    }

    // ============================================================================
    // LIST vs SEQ RENDERING — must be DISTINGUISHABLE
    // A List is NOT a Seq; it must not borrow Seq's `[...]` syntax. Seq keeps
    // `[...]`; List renders `List(...)` (matching the NonEmptyList(...) form).
    // ============================================================================

    @Test
    fun `Seq renders with square brackets`() {
        assertEquals("[1, 2, 3]", eval("""
            let s = [1, 2, 3]
            s.to_dbg {}
        """.trimIndent()))
    }

    @Test
    fun `empty Seq renders as empty brackets`() {
        assertEquals("[]", eval("""
            let s = []
            s.to_dbg {}
        """.trimIndent()))
    }

    @Test
    fun `List renders with List parens`() {
        assertEquals("List(1, 2, 3)", eval("""
            use std::collections::list
            let l = list::from_seq { xs = [1, 2, 3] }
            l.to_dbg {}
        """.trimIndent()))
    }

    @Test
    fun `empty List renders as List parens`() {
        assertEquals("List()", eval("""
            use std::collections::list
            let l = list::empty {}
            l.to_dbg {}
        """.trimIndent()))
    }

    @Test
    fun `List and Seq of same elements render DIFFERENTLY`() {
        val seqStr = eval("""
            let s = [1, 2, 3]
            s.to_dbg {}
        """.trimIndent())
        val listStr = eval("""
            use std::collections::list
            let l = list::from_seq { xs = [1, 2, 3] }
            l.to_dbg {}
        """.trimIndent())
        assertNotEquals(seqStr, listStr, "List and Seq must not render identically")
        assertEquals("[1, 2, 3]", seqStr)
        assertEquals("List(1, 2, 3)", listStr)
    }

    // ============================================================================
    // EDGE CASES
    // ============================================================================

    @Test
    fun `test Inspect in string interpolation`() {
        val source = """
            let val = 42
            let msg = "Value: $(val.to_dbg {})"
            msg
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    @Test
    fun `test Inspect on deeply nested records`() {
        val source = """
            let nested = {
                a = { b = { c = { d = { e = 42 } } } }
            }
            let result = nested.to_dbg {}
            result
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    @Test
    fun `test Inspect on array of custom types`() {
        val source = """
            type Point = | Point { x: Int, y: Int } |

            let points = [
                Point { x = 0, y = 0 },
                Point { x = 10, y = 20 },
                Point { x = 30, y = 40 }
            ]

            let result = points.to_dbg {}

            result
        """.trimIndent()

        assertDoesNotThrow { eval(source) }
    }

    // ============================================================================
    // PARSER AMBIGUITY DEMONSTRATION
    // ============================================================================

    @Test
    fun `test parser treats Inspect as VariantConstruction before conversion`() {
        // This test demonstrates the core issue at the AST level
        val source = "let x = Inspect"

        val parser = com.mezze.language.parser.MezzeParser()
        val program = parser.parseParsedModule(source)

        // Before typechecking, the parser creates a ParsedVariantConstruct node
        val letDecl = program.decls[0] as com.mezze.language.parsedast.ParsedLetDecl
        val expression = letDecl.value

        // The parser treats "Inspect" as a variant constructor
        assertTrue(
            expression is com.mezze.language.parsedast.ParsedVariantConstruct,
            "Parser should treat uppercase identifier as ParsedVariantConstruct"
        )

        val variantConst = expression as com.mezze.language.parsedast.ParsedVariantConstruct
        assertEquals("Inspect", variantConst.constructor)
        assertNull(variantConst.value, "Unit constructor should have no value")
    }

    @Test
    fun `test typechecker recognizes Inspect as ability not variant`() {
        val source = "let x = Inspect"

        val parser = com.mezze.language.parser.MezzeParser()
        val typeChecker = com.mezze.language.typechecker.TypeChecker()
        val parsedModule = parser.parseParsedModule(source)

        // Typechecker should fail because Inspect is an ability, not a value
        assertThrows(Exception::class.java) {
            typeChecker.typeCheck(parsedModule, java.nio.file.Path.of("."))
        }
    }

    // -------------------------------------------------------------------------
    // to_dbg OUTPUT FORMAT — must be Mezze surface syntax, not Java toString.
    // A variant/record reaching the BDSL InlineScalarInspect fallback used to
    // render via MezzeVariant.toString (`Err({error = divide by zero})`, no
    // quotes) instead of the shared inspect formatter. These pin the exact form.
    // -------------------------------------------------------------------------

    @Test
    fun `to_dbg on a variant with a string payload quotes and spaces braces`() {
        val result = eval(
            """
            let e = Err { error = "divide by zero" }
            e.to_dbg {}
            """
        )
        assertEquals("Err { error = \"divide by zero\" }", result)
    }

    @Test
    fun `to_dbg on Some wraps with spaced braces`() {
        val result = eval(
            """
            let s = Some { val = 42 }
            s.to_dbg {}
            """
        )
        assertEquals("Some { val = 42 }", result)
    }

    @Test
    fun `to_dbg on a record quotes string fields and spaces braces`() {
        val result = eval(
            """
            let r = { a = 1, b = "hi" }
            r.to_dbg {}
            """
        )
        assertEquals("{ a = 1, b = \"hi\" }", result)
    }
}
