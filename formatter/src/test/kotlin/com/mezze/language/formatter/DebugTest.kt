package com.mezze.language.formatter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DebugTest {
    private val formatter = Formatter()

    @Test
    fun `format should preserve operator syntax`() {
        val input = "let f = {x, y} -> x + y"
        val output = formatter.format(input)
        assertEquals("let f = { x, y } -> x + y\n", output)
    }

    @Test
    fun `format variant type`() {
        val input = "type Option 'a = | Some { value: 'a }, None |"
        val output = formatter.format(input)
        assertEquals("type Option 'a = | Some { value: 'a }, None |\n", output)
    }

    @Test
    fun `format extensible variant type`() {
        val input = "type LogLevel = | Error, Warn, Info, ... |"
        val output = formatter.format(input)
        assertEquals("type LogLevel = | Error, Warn, Info, ... |\n", output)
    }

    @Test
    fun `format effect with associated types`() {
        val input = """effect MyEffect having 'a, 'b where {
    op1: { x: 'a } -> 'b,
    op2: { y: 'b } -> 'a
}"""
        val output = formatter.format(input)
        val expected = """effect MyEffect having 'a, 'b where {
  op1: { x: 'a } -> 'b,
  op2: { y: 'b } -> 'a
}
"""
        assertEquals(expected, output)
    }

    @Test
    fun `format ability with associated types`() {
        val input = """ability Encoder 'a having 'output where {
    encode: { val: 'a } -> 'output
}"""
        val output = formatter.format(input)
        val expected = """ability Encoder 'a having 'output where {
  encode: { val: 'a } -> 'output
}
"""
        assertEquals(expected, output)
    }

    @Test
    fun `format ability with dependencies`() {
        val input = """ability Ord 'a with Eq 'a where {
    compare: { left: 'a, right: 'a } -> Int
}"""
        val output = formatter.format(input)
        val expected = """ability Ord 'a with Eq 'a where {
  compare: { left: 'a, right: 'a } -> Int
}
"""
        assertEquals(expected, output)
    }

    @Test
    fun `format ability with multiple type params`() {
        val input = """ability Encode 'repr 'a where {
    encode: { val: 'a, config: CodecConfig } -> (Result 'repr EncodeError)
}"""
        val output = formatter.format(input)
        val expected = """ability Encode 'repr 'a where {
  encode: { val: 'a, config: CodecConfig } -> Result 'repr EncodeError
}
"""
        assertEquals(expected, output)
    }

    @Test
    fun `format effect impl multi-line`() {
        val input = """impl Log for ConsoleLogger where {
    info = {msg} -> {},
    warn = {msg} -> {}
}"""
        val output = formatter.format(input)
        // Trailing comma when multiple operations (like records)
        val expected = """impl Log for ConsoleLogger where {
  info = { msg } -> {},
  warn = { msg } -> {},
}
"""
        assertEquals(expected, output)
    }

    @Test
    fun `format effect impl with associated type binding`() {
        val input = """impl State for Counter having 'state = Int where {
    get = {} -> 0,
    put = {value} -> {}
}"""
        val output = formatter.format(input)
        // Trailing comma when multiple operations (like records)
        val expected = """impl State for Counter having 'state = Int where {
  get = {} -> 0,
  put = { value } -> {},
}
"""
        assertEquals(expected, output)
    }

    @Test
    fun `format effect impl single-line`() {
        // impl bodies always go multi-line, but single operation = no trailing comma
        val input = """impl Random for SeededRandom { seed: Int } where {
    random = {} -> 42
}"""
        val output = formatter.format(input)
        val expected = """impl Random for SeededRandom { seed: Int } where {
  random = {} -> 42
}
"""
        assertEquals(expected, output)
    }

    @Test
    fun `format ability impl single-line`() {
        // impl bodies always go multi-line, but single operation = no trailing comma
        val input = """impl Eq Int where {
    eq = {left, right} -> left == right
}"""
        val output = formatter.format(input)
        val expected = """impl Eq Int where {
  eq = { left, right } -> left == right
}
"""
        assertEquals(expected, output)
    }

    @Test
    fun `format record literal with spaces`() {
        val input = "let person = {name = \"Alice\", age = 30}"
        val output = formatter.format(input)
        assertEquals("let person = { name = \"Alice\", age = 30 }\n", output)
    }

    @Test
    fun `format function call with spaces`() {
        val input = "let result = add {a = 1, b = 2}"
        val output = formatter.format(input)
        assertEquals("let result = add { a = 1, b = 2 }\n", output)
    }

    @Test
    fun `format empty record and lambda`() {
        // Empty records/lambdas should NOT have spaces: {}
        val input = "let f = {} -> 42"
        val output = formatter.format(input)
        assertEquals("let f = {} -> 42\n", output)
    }

    @Test
    fun `format record pattern with spaces`() {
        val input = "let {x, y} = point"
        val output = formatter.format(input)
        assertEquals("let { x, y } = point\n", output)
    }

}
