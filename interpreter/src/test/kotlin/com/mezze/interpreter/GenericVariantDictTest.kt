package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A generic variant's payload is compared with the PAYLOAD's instance, whichever of
 * the three spellings of the container type reached the compiler.
 *
 * The derived `Eq` for `Option 'a` compares payloads with `==`, which needs an
 * `Eq 'a` dictionary; the compiler fills it by resolving the element type out of
 * the container. `containerElementType` knew two representations — the dedicated
 * nodes (`SeqType`, `MapType`, …) and `TypeApplication("Option", [Int])` — and not
 * the third. An INFERRED `Option Int` is a name-carried `VariantType` carrying
 * `typeArgs`, so it yielded no element at all, the `Eq 'a` slot resolved against
 * `[Option Int]` — Option's OWN `eq` — and comparing two `Some`s ran Option's
 * variant match on the payload `42`:
 *
 *     Non-exhaustive match: no variant tag matched
 *       — typechecker exhaustiveness guarantee violated
 *
 * Well-typed, and stuck. Annotating the binding produced a `TypeApplication` and
 * worked, which is exactly the tell: the same type in two spellings gave two
 * answers.
 *
 * `Result Int 'a` needed the second half. Its LAST type argument is the error type,
 * still unconstrained at `Ok { val = 1 }`, and every caller discards a type var —
 * so answering with the trailing `'a` answered nothing. The last CONCRETE argument
 * is the payload actually being compared.
 */
class GenericVariantDictTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    fun `an unannotated Option binding compares by the payload's instance`() {
        assertEquals(true, MezzeTestHelper.run("let a = Some { val = 42 }\nlet b = Some { val = 42 }\na == b"))
        assertEquals(false, MezzeTestHelper.run("let a = Some { val = 42 }\nlet b = Some { val = 99 }\na == b"))
    }

    @Test
    fun `an unannotated Result binding does too`() {
        // The last type argument here is the UNCONSTRAINED error type.
        assertEquals(true, MezzeTestHelper.run("let a = Ok { val = 1 }\nlet b = Ok { val = 1 }\na == b"))
        assertEquals(false, MezzeTestHelper.run("let a = Ok { val = 1 }\nlet b = Ok { val = 2 }\na == b"))
    }

    @Test
    fun `the three spellings of one type all agree`() {
        // Annotated (TypeApplication), inferred (VariantType), and mixed. All were
        // the same type; only the annotated one used to work.
        val ann = "use std::option\nlet a: Option Int = Some { val = 7 }\nlet b: Option Int = Some { val = 7 }\na == b"
        val inf = "let a = Some { val = 7 }\nlet b = Some { val = 7 }\na == b"
        val mix = "use std::option\nlet a: Option Int = Some { val = 7 }\nlet b = Some { val = 7 }\na == b"
        assertEquals(true, MezzeTestHelper.run(ann))
        assertEquals(true, MezzeTestHelper.run(inf))
        assertEquals(true, MezzeTestHelper.run(mix))
        assertEquals(true, MezzeTestHelper.run("let a = Some { val = 7 }\na == Some { val = 7 }"))
    }

    @Test
    fun `the payload's OWN instance is used, not a structural compare`() {
        // The point of threading the element dict at all: `P` compares mod 10, so a
        // structural comparison of the payloads would answer false here.
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                use std::abilities::compare::{ Eq, Hash }
                newtype P = Int
                impl Hash for P where {
                  hash = { it } -> match it is P.(a) -> a % 10
                }
                impl Eq for P where {
                  eq = { it, r } -> match it is
                    P.(a) -> match r is
                      P.(b) -> a % 10 == b % 10,
                  neq = { it, r } -> match it is
                    P.(a) -> match r is
                      P.(b) -> a % 10 != b % 10
                }
                let a = Some { val = P.(1) }
                let b = Some { val = P.(11) }
                a == b
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a payload-less constructor and a user generic variant still work`() {
        assertEquals(true, MezzeTestHelper.run("let a = None\nlet b = None\na == b"))
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                type W 'a = | A { v: 'a }, B |
                let a = A { v = 1 }
                let b = A { v = 1 }
                a == b
                """.trimIndent(),
            ),
        )
    }
}
