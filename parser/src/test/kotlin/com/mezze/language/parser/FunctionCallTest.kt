package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FunctionCallTest {

    private val parser = MezzeParser()

    @Test
    fun `test simple function call`() {
        val source = "let result = f {x = 1}"

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val letBinding = program.items[0] as LetBinding
            assertEquals("result", letBinding.variableName)

            val call = letBinding.expression as FunctionCall
            val func = call.function as Identifier
            assertEquals("f", func.localName)

            val args = call.arguments as RecordLiteral
            assertEquals(1, args.entries.size)

            println("✅ Simple function call parsed successfully!")
        }
    }

    @Test
    fun `test function call with multiple arguments`() {
        val source = "let result = add {x = 1, y = 2}"

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)

            val letBinding = program.items[0] as LetBinding
            val call = letBinding.expression as FunctionCall
            val func = call.function as Identifier
            assertEquals("add", func.localName)

            val args = call.arguments as RecordLiteral
            assertEquals(2, args.entries.size)

            println("✅ Function call with multiple arguments parsed successfully!")
        }
    }

    @Test
    fun `test member access`() {
        val source = "let value = obj.field"

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)

            val letBinding = program.items[0] as LetBinding
            val memberAccess = letBinding.expression as MemberAccess
            val receiver = memberAccess.receiver as Identifier
            assertEquals("obj", receiver.localName)
            assertEquals("field", memberAccess.member)

            println("✅ Member access parsed successfully!")
        }
    }

    @Test
    fun `test chained member access`() {
        val source = "let value = obj.x.y.z"

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)

            val letBinding = program.items[0] as LetBinding
            val access3 = letBinding.expression as MemberAccess
            assertEquals("z", access3.member)

            val access2 = access3.receiver as MemberAccess
            assertEquals("y", access2.member)

            val access1 = access2.receiver as MemberAccess
            assertEquals("x", access1.member)

            val obj = access1.receiver as Identifier
            assertEquals("obj", obj.localName)

            println("✅ Chained member access parsed successfully!")
        }
    }

    @Test
    fun `test function call on member access`() {
        val source = "let result = obj.method {x = 1}"

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)

            val letBinding = program.items[0] as LetBinding
            val call = letBinding.expression as FunctionCall

            val memberAccess = call.function as MemberAccess
            assertEquals("method", memberAccess.member)

            val obj = memberAccess.receiver as Identifier
            assertEquals("obj", obj.localName)

            println("✅ Function call on member access parsed successfully!")
        }
    }

    @Test
    fun `test nested function calls`() {
        val source = "let result = f {x = g {y = 1}}"

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)

            val letBinding = program.items[0] as LetBinding
            val outerCall = letBinding.expression as FunctionCall

            val args = outerCall.arguments as RecordLiteral
            val fieldEntry = args.entries[0] as RecordFieldEntry

            val innerCall = fieldEntry.value as FunctionCall
            val innerFunc = innerCall.function as Identifier
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
            val program = parser.parseParsedModule(source)
            assertNotNull(program)

            val letBinding = program.items[0] as LetBinding
            val outerCall = letBinding.expression as FunctionCall
            val outerFunc = outerCall.function as Identifier
            assertEquals("concat", outerFunc.localName)

            val args = outerCall.arguments as RecordLiteral
            assertEquals(1, args.entries.size)

            val fieldEntry = args.entries[0] as RecordFieldEntry
            assertEquals("xss", fieldEntry.name)

            val innerCall = fieldEntry.value as FunctionCall
            val innerFunc = innerCall.function as Identifier
            assertEquals("map", innerFunc.localName)

            val innerArgs = innerCall.arguments as RecordLiteral
            assertEquals(2, innerArgs.entries.size)

            println("✅ Nested function calls without parentheses parsed successfully!")
        }
    }
}
