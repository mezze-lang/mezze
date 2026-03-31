package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for multi-line parsing and whitespace flexibility.
 * Migrated from V1 to V2 with unboxed values.
 */
class MultiLineParsingTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test multi-line if expressions - then branch`() {
        val code = """
            if True
               then 42
               else 0
        """
        assertEquals(42L, eval(code))
    }

    @Test
    fun `test multi-line if expressions - else branch`() {
        val code = """
            if False
               then "no"
               else "yes"
        """
        assertEquals("yes", eval(code))
    }

    @Test
    fun `test nested multi-line conditionals - small`() {
        val code = """
            let classify = {x} ->
                if x > 100
                then
                    if x > 1000
                    then "huge"
                    else "large"
                else
                    if x > 10
                    then "medium"
                    else "small"

            classify {x = 5}
        """
        assertEquals("small", eval(code))
    }

    @Test
    fun `test nested multi-line conditionals - medium`() {
        val code = """
            let classify = {x} ->
                if x > 100
                then
                    if x > 1000
                    then "huge"
                    else "large"
                else
                    if x > 10
                    then "medium"
                    else "small"

            classify {x = 50}
        """
        assertEquals("medium", eval(code))
    }

    @Test
    fun `test nested multi-line conditionals - large`() {
        val code = """
            let classify = {x} ->
                if x > 100
                then
                    if x > 1000
                    then "huge"
                    else "large"
                else
                    if x > 10
                    then "medium"
                    else "small"

            classify {x = 500}
        """
        assertEquals("large", eval(code))
    }

    @Test
    fun `test nested multi-line conditionals - huge`() {
        val code = """
            let classify = {x} ->
                if x > 100
                then
                    if x > 1000
                    then "huge"
                    else "large"
                else
                    if x > 10
                    then "medium"
                    else "small"

            classify {x = 5000}
        """
        assertEquals("huge", eval(code))
    }

    @Test
    fun `test multi-line function definitions`() {
        val code = """
            let complexCalc = {a, b, c} ->
                if a > b
                then
                    if b > c
                    then a + b + c
                    else a + b - c
                else
                    if a > c
                    then b + a - c
                    else b + c - a

            complexCalc {a = 5, b = 3, c = 2}
        """
        assertEquals(10L, eval(code)) // 5 + 3 + 2 = 10
    }

    @Test
    fun `test simple pattern matching with Success`() {
        val code = """
            let result = Success {value = 42}

            match result is
                Success {value} -> value
        """
        assertEquals(42L, eval(code))
    }

    @Test
    fun `test simple pattern matching with type`() {
        val code = """
            type MyResult = | MySuccess {value: Int} |
            let result: MyResult = MySuccess {value = 100}

            match result is
                MySuccess {value} -> value * 2
        """
        assertEquals(200L, eval(code))
    }

    @Test
    fun `test multi-line expressions with whitespace flexibility - if`() {
        val code = """
            if   True
               then   42
               else   0
        """
        assertEquals(42L, eval(code))
    }

    @Test
    fun `test multi-line expressions with whitespace flexibility - function`() {
        val code = """
            let calc = {x, y} ->   if   x   >   y
                                  then   x   +   y
                                  else   x   -   y

            calc {x = 10, y = 3}
        """
        assertEquals(13L, eval(code))
    }

    @Test
    fun `test multi-line lambda expressions - positive`() {
        val code = """
            let processor = {data} ->
                if data > 0
                then data * 2
                else 0

            processor {data = 5}
        """
        assertEquals(10L, eval(code))
    }

    @Test
    fun `test multi-line lambda expressions - negative`() {
        val code = """
            let processor = {data} ->
                if data > 0
                then data * 2
                else 0

            processor {data = -3}
        """
        assertEquals(0L, eval(code))
    }
}
