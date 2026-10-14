package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A generic type's ARGUMENTS decide its constructor's payload.
 *
 * The constructor of `newtype Box 'a = ['a]` was typed `{ ['g] } -> Box 'h`: the
 * one written `'a` became two variables, because the payload was converted under
 * one cache key (`a`, tick stripped) and the parameter looked up under another
 * (`'a`). Nothing linked the payload to the argument, so `Box.(["s"])` checked as
 * a `Box Int`, and `Box.(Arbitrary.arbitrary {})` stayed at `['a]`, which nothing
 * determines.
 */
class GenericNewtypePayloadTest {
    @BeforeEach
    fun setUp() { ModuleResolver.clearCache(); TypeChecker.clearCache() }

    private fun check(src: String): TypeChecker.TypeCheckResult =
        TypeChecker().checkParsedModule(MezzeParser().parseParsedModule(src.trimIndent(), "test.mz"), Paths.get("."), currentModulePath = null)

    @Test
    fun `a payload of the wrong type is rejected`() {
        val r = check("""
            newtype Box 'a = ['a]
            let x: {} -> Box Int = {} -> Box.(["s"])
        """)
        assertTrue(r is TypeChecker.TypeCheckResult.Failure, "Box.([\"s\"]) accepted as a Box Int")
    }

    @Test
    fun `the annotation's argument reaches the payload`() {
        val r = check("""
            use std::effects::sample::{ SeededSample }
            use std::abilities::arbitrary::{ Arbitrary }
            newtype Box 'a = ['a]
            let one: {} -> Box Int <*> = {} -> perform SeededSample { seed = 1 } in do
              let b: Box Int = Box.(Arbitrary.arbitrary {})
              b
        """)
        assertTrue(r is TypeChecker.TypeCheckResult.Success,
            (r as? TypeChecker.TypeCheckResult.Failure)?.errors?.joinToString { it.message } ?: "")
    }

    @Test
    fun `two instantiations in one block`() {
        val r = check("""
            use std::effects::sample::{ SeededSample }
            use std::abilities::arbitrary::{ Arbitrary }
            newtype Box 'a = ['a]
            let one: {} -> Int <*> = {} -> perform SeededSample { seed = 1 } in do
              let a: Box Int = Box.(Arbitrary.arbitrary {})
              let b: Box Str = Box.(Arbitrary.arbitrary {})
              1
        """)
        assertTrue(r is TypeChecker.TypeCheckResult.Success,
            (r as? TypeChecker.TypeCheckResult.Failure)?.errors?.joinToString { it.message } ?: "")
    }

    @Test
    fun `the sample shape`() {
        val r = check("""
            use std::effects::sample::{ CoveringSampleWith }
            use std::abilities::arbitrary::{ Arbitrary }
            newtype Box 'a = ['a]
            pub let boxes = { a: Box Int, b: Box Str } -> 1
            let go: {} -> Int <*> = {} -> do
              let row = perform CoveringSampleWith { seed = 1, run = 0, config = { size = 5n, enumerate = 12n } } in do
                let x: Box Int = Box.(Arbitrary.arbitrary {})
                let y: Box Str = Box.(Arbitrary.arbitrary {})
                let args = { a = x, b = y }
                { args = args, result = boxes { a = args.a, b = args.b } }
              row.result
        """)
        assertTrue(r is TypeChecker.TypeCheckResult.Success,
            (r as? TypeChecker.TypeCheckResult.Failure)?.errors?.joinToString { it.message } ?: "")
    }

    /**
     * A recursive variant holding a component with no instance overflowed the
     * stack in derivation (T0214): the fallback restarted the walk and forgot that
     * `Tree` was already on the path.
     */
    @Test
    fun `a recursive variant holding a newtype checks`() {
        val r = check("""
            newtype Age = Int
            type Tree = | Leaf, Node { left: Tree, age: Age, right: Tree } |
            let g: {} -> Tree = {} -> Node { left = Leaf, age = Age.(3), right = Leaf }
        """)
        assertTrue(r is TypeChecker.TypeCheckResult.Success,
            (r as? TypeChecker.TypeCheckResult.Failure)?.errors?.joinToString { it.message } ?: "")
    }

    /** The same constructor builder serves generic VARIANTS. */
    @Test
    fun `a generic variant's payload follows its argument too`() {
        val r = check("""
            type Holder 'a = | Hold { v: 'a }, Empty |
            let x: {} -> Holder Int = {} -> Hold { v = "s" }
        """)
        assertTrue(r is TypeChecker.TypeCheckResult.Failure, "Hold { v = \"s\" } accepted as a Holder Int")
    }

    @Test
    fun `a non-generic newtype is unchanged`() {
        val r = check("""
            newtype Age = Int
            let a: {} -> Age = {} -> Age.(3)
        """)
        assertTrue(r is TypeChecker.TypeCheckResult.Success)
    }
}
