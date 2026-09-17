package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `Eq` is the compiler's for every type but a newtype — primitives included.
 *
 * No `.mz` file writes `impl Eq for Int` any more; the instance is generated, one
 * per primitive, in that primitive's home module. Two different paths consume it
 * and both have to work:
 *
 *  - `Int == Int` never reaches an impl at all. The operator desugars to `.eq`,
 *    and a primitive receiver takes the inline comparison node before ability
 *    resolution runs, so this path measures the OPERATOR, not the instance.
 *  - a generic position measures the INSTANCE: `Pair 'a 'b`'s derived `eq` takes
 *    an `Eq 'b` dict, so comparing a `Pair P Str` needs a real `Eq Str` to pass.
 *    Derivability alone is not one — before this existed, that slot was filled
 *    from elsewhere and a `Str` component was compared with `P`'s `eq`.
 *
 * `Bytes` is the case that separates the two: it is a primitive for typing but
 * not on the inline list, so even `b1 == b2` goes through dispatch and fails
 * outright if the instance is missing.
 */
class PrimitiveEqDerivedTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    fun `every primitive compares with no impl written anywhere`() {
        assertEquals(true, MezzeTestHelper.run("(1) == (1)"))
        assertEquals(false, MezzeTestHelper.run("(1) == (2)"))
        assertEquals(true, MezzeTestHelper.run("\"a\" == \"a\""))
        assertEquals(false, MezzeTestHelper.run("\"a\" == \"b\""))
        assertEquals(true, MezzeTestHelper.run("True == True"))
        assertEquals(false, MezzeTestHelper.run("True == False"))
        assertEquals(true, MezzeTestHelper.run("(1.5) == (1.5)"))
        assertEquals(false, MezzeTestHelper.run("(1.5) == (2.5)"))
        // Char has no literal syntax; it is built through `char::from_str`, so the
        // comparison below is an `Option Char` one — which needs an `Eq Char` dict
        // and therefore exercises the generated instance rather than the operator.
        val ch = "use std::char\nuse std::option\n"
        assertEquals(true, MezzeTestHelper.run("${ch}char::from_str { s = \"a\" } == char::from_str { s = \"a\" }"))
        assertEquals(false, MezzeTestHelper.run("${ch}char::from_str { s = \"a\" } == char::from_str { s = \"b\" }"))
    }

    @Test
    fun `Bytes compares through dispatch, not the inline node`() {
        val head = "use std::collections::bytes\n"
        assertEquals(
            true,
            MezzeTestHelper.run("${head}bytes::from_str { s = \"hi\" } == bytes::from_str { s = \"hi\" }"),
        )
        assertEquals(
            false,
            MezzeTestHelper.run("${head}bytes::from_str { s = \"hi\" } == bytes::from_str { s = \"no\" }"),
        )
    }

    @Test
    fun `a primitive satisfies an Eq obligation in a generic position`() {
        // The dict path: `same` cannot inline anything, so this runs the generated
        // instance itself. One case per primitive that can reach it.
        val same = "let same: { a: 'a, b: 'a } with Eq 'a -> Bool = { a, b } -> a == b\n"
        val head = "use std::abilities::compare::{ Eq }\n$same"
        assertEquals(true, MezzeTestHelper.run("${head}same { a = 1, b = 1 }"))
        assertEquals(false, MezzeTestHelper.run("${head}same { a = \"x\", b = \"y\" }"))
        assertEquals(true, MezzeTestHelper.run("${head}same { a = True, b = True }"))
        assertEquals(true, MezzeTestHelper.run("${head}same { a = 1.5, b = 1.5 }"))
    }

    @Test
    fun `Float equality is the platform's`() {
        // The owner's rule: Float follows GraalVM/JVM semantics, which are IEEE's —
        // NaN is equal to nothing, itself included. Deriving does not get to invent
        // a friendlier answer here.
        val nan = "let nan: { } -> Float = { } -> (0.0) / (0.0)\n"
        assertEquals(false, MezzeTestHelper.run("${nan}nan {} == nan {}"))
        assertEquals(true, MezzeTestHelper.run("${nan}nan {} != nan {}"))
    }

    @Test
    fun `a newtype over a primitive still writes its own`() {
        // The one exclusion, and the reason for it: a newtype is erased to its
        // payload, so a derived comparison would silently be the WRAPPED type's.
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                use std::abilities::compare::{ Eq, Hash }
                newtype Tag = Str
                impl Hash for Tag where {
                  hash = { it } -> match it is Tag.(s) -> 0
                }
                impl Eq for Tag where {
                  eq = { it, r } -> True,
                  neq = { it, r } -> False
                }
                Tag.("a") == Tag.("b")
                """.trimIndent(),
            ),
        )
    }
}
