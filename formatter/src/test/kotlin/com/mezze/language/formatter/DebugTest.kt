package com.mezze.language.formatter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DebugTest {
    private val formatter = Formatter()

    @Test
    fun `format should preserve operator syntax`() {
        val input = "let f = {x, y} -> x + y"
        val output = formatter.format(input)
        assertEquals("let f = {x, y} -> x + y\n", output)
    }

    @Test
    fun `format variant type`() {
        val input = "type Option 'a = | Some { value: 'a }, None |"
        val output = formatter.format(input)
        assertEquals("type Option 'a = Some {value: 'a} | None\n", output)
    }

    @Test
    fun `format effect with associated types`() {
        val input = """effect MyEffect having 'a, 'b where {
    op1: { x: 'a } -> 'b,
    op2: { y: 'b } -> 'a
}"""
        val output = formatter.format(input)
        val expected = """effect MyEffect having 'a, 'b where {
	op1: {x: 'a} -> 'b,
	op2: {y: 'b} -> 'a
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
	encode: {val: 'a} -> 'output
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
	compare: {left: 'a, right: 'a} -> Int
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
	encode: {val: 'a, config: CodecConfig} -> Result 'repr EncodeError
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
        // Multi-line with trailing comma (like records)
        val expected = """impl Log ConsoleLogger where {
	info = {msg} -> {},
	warn = {msg} -> {},
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
        // Multi-line with trailing comma (like records)
        val expected = """impl State Counter having 'state = Int where {
	get = {} -> 0,
	put = {value} -> {},
}
"""
        assertEquals(expected, output)
    }

    @Test
    fun `format effect impl single-line`() {
        // Single short operation fits on one line (like record)
        val input = """impl Random for SeededRandom { seed: Int } where {
    random = {} -> 42
}"""
        val output = formatter.format(input)
        val expected = """impl Random SeededRandom {seed: Int} where {random = {} -> 42}
"""
        assertEquals(expected, output)
    }

    @Test
    fun `format ability impl single-line`() {
        // Single short operation fits on one line (like record)
        val input = """impl Eq Int where {
    eq = {left, right} -> left == right
}"""
        val output = formatter.format(input)
        val expected = """impl Eq Int where {eq = {left, right} -> left == right}
"""
        assertEquals(expected, output)
    }

}
