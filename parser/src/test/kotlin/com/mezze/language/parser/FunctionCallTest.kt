package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FunctionCallTest {

    private val parser = MezzeParser()

    @Test
    fun `test simple function call`() {
        val source = "let result = f {x = 1}"

        assertDoesNotThrow {
            val module = parser.parseParsedModule(source)
            assertNotNull(module)
            assertEquals(1, module.decls.size)

            val letBinding = module.decls[0] as ParsedLetDecl
            assertEquals("result", letBinding.variableName)

            val call = letBinding.value as ParsedFunctionCall
            val func = call.function as ParsedIdentifier
            assertEquals("f", func.localName)

            val args = call.arguments as ParsedRecordLit
            assertEquals(1, args.entries.size)

            println("✅ Simple function call parsed successfully!")
        }
    }

    @Test
    fun `test function call with multiple arguments`() {
        val source = "let result = add {x = 1, y = 2}"

        assertDoesNotThrow {
            val module = parser.parseParsedModule(source)
            assertNotNull(module)

            val letBinding = module.decls[0] as ParsedLetDecl
            val call = letBinding.value as ParsedFunctionCall
            val func = call.function as ParsedIdentifier
            assertEquals("add", func.localName)

            val args = call.arguments as ParsedRecordLit
            assertEquals(2, args.entries.size)

            println("✅ Function call with multiple arguments parsed successfully!")
        }
    }

    @Test
    fun `test member access`() {
        val source = "let value = obj.field"

        assertDoesNotThrow {
            val module = parser.parseParsedModule(source)
            assertNotNull(module)

            val letBinding = module.decls[0] as ParsedLetDecl
            val memberAccess = letBinding.value as ParsedMemberAccess
            val receiver = memberAccess.receiver as ParsedIdentifier
            assertEquals("obj", receiver.localName)
            assertEquals("field", memberAccess.member)

            println("✅ Member access parsed successfully!")
        }
    }

    @Test
    fun `test chained member access`() {
        val source = "let value = obj.x.y.z"

        assertDoesNotThrow {
            val module = parser.parseParsedModule(source)
            assertNotNull(module)

            val letBinding = module.decls[0] as ParsedLetDecl
            val access3 = letBinding.value as ParsedMemberAccess
            assertEquals("z", access3.member)

            val access2 = access3.receiver as ParsedMemberAccess
            assertEquals("y", access2.member)

            val access1 = access2.receiver as ParsedMemberAccess
            assertEquals("x", access1.member)

            val obj = access1.receiver as ParsedIdentifier
            assertEquals("obj", obj.localName)

            println("✅ Chained member access parsed successfully!")
        }
    }

    @Test
    fun `test function call on member access`() {
        val source = "let result = obj.method {x = 1}"

        assertDoesNotThrow {
            val module = parser.parseParsedModule(source)
            assertNotNull(module)

            val letBinding = module.decls[0] as ParsedLetDecl
            val call = letBinding.value as ParsedFunctionCall

            val memberAccess = call.function as ParsedMemberAccess
            assertEquals("method", memberAccess.member)

            val obj = memberAccess.receiver as ParsedIdentifier
            assertEquals("obj", obj.localName)

            println("✅ Function call on member access parsed successfully!")
        }
    }

    @Test
    fun `test nested function calls`() {
        val source = "let result = f {x = g {y = 1}}"

        assertDoesNotThrow {
            val module = parser.parseParsedModule(source)
            assertNotNull(module)

            val letBinding = module.decls[0] as ParsedLetDecl
            val outerCall = letBinding.value as ParsedFunctionCall

            val args = outerCall.arguments as ParsedRecordLit
            val fieldEntry = args.entries[0] as ParsedRecordEntry.Field

            val innerCall = fieldEntry.value as ParsedFunctionCall
            val innerFunc = innerCall.function as ParsedIdentifier
            assertEquals("g", innerFunc.localName)

            println("✅ Nested function calls parsed successfully!")
        }
    }

    @Test
    fun `test nested function calls without parentheses in record literal context`() {
        // This should parse: concat { xss = map { xs = xs, f = f } }
        // Previously required: concat { xss = (map { xs = xs, f = f }) }
        val source = "let result = concat { xss = map { xs = xs, f = f } }"

        assertDoesNotThrow {
            val module = parser.parseParsedModule(source)
            assertNotNull(module)

            val letBinding = module.decls[0] as ParsedLetDecl
            val outerCall = letBinding.value as ParsedFunctionCall
            val outerFunc = outerCall.function as ParsedIdentifier
            assertEquals("concat", outerFunc.localName)

            val args = outerCall.arguments as ParsedRecordLit
            assertEquals(1, args.entries.size)

            val fieldEntry = args.entries[0] as ParsedRecordEntry.Field
            assertEquals("xss", fieldEntry.name)

            val innerCall = fieldEntry.value as ParsedFunctionCall
            val innerFunc = innerCall.function as ParsedIdentifier
            assertEquals("map", innerFunc.localName)

            val innerArgs = innerCall.arguments as ParsedRecordLit
            assertEquals(2, innerArgs.entries.size)

            println("✅ Nested function calls without parentheses parsed successfully!")
        }
    }
}
