package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertFailsWith

/**
 * H2: nested match exhaustiveness was SKIPPED when the destructured payload field
 * had a generic type ('a). `match o is Full { v = Full { v = x } } -> x  Empty -> -1`
 * over `Box 'a = | Full { v: 'a }, Empty |` type-checked, then crashed at runtime with
 * "Non-exhaustive match" when `'a` instantiated to `Box Str` and the value was
 * `Full { v = Empty }`. A refutable constructor sub-pattern on a generic field can't
 * be proven exhaustive without a catch-all.
 */
class GenericPayloadNestedExhaustivenessTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun check(src: String) {
        tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
    }

    @Test
    fun `refutable constructor sub-pattern on a generic field without a catch-all is rejected`() {
        assertFailsWith<TypeCheckError> {
            check(
                """
                type Box 'a = | Full { v: 'a }, Empty |
                let f = { o } -> match o is
                  Full { v = Full { v = x } } -> x
                  Empty -> -1
                let main = f { o = Full { v = Empty } }
                """
            )
        }
    }

    @Test
    fun `a catch-all sub-pattern on the generic field makes it exhaustive`() {
        check(
            """
            type Box 'a = | Full { v: 'a }, Empty |
            let f = { o } -> match o is
              Full { v = _ } -> 1
              Empty -> -1
            let main = f { o = Full { v = Empty } }
            """
        )
    }

    @Test
    fun `a single-constructor variant sub-pattern on a generic field is exhaustive`() {
        // `Pair 'a 'b` has ONE constructor, so matching `Pair { … }` on a generic
        // field covers every value — it must NOT be flagged non-exhaustive.
        check(
            """
            type Pair 'a 'b = | Pair { first: 'a, second: 'b } |
            type Wrapped 'ok 'err = | Wok { val: 'ok }, Werr { error: 'err } |
            let swap_if_ok = { result } -> match result is
              Wok { val = Pair { first, second } } -> Wok { val = Pair { first = second, second = first } }
              Werr { error } -> Werr { error = error }
            let example = Wok { val = Pair { first = 42, second = "hi" } }
            let out = swap_if_ok { result = example }
            """
        )
    }

    @Test
    fun `a variable sub-pattern on the generic field is exhaustive`() {
        // Binding the generic field to a variable is irrefutable — no catch-all needed.
        check(
            """
            type Box 'a = | Full { v: 'a }, Empty |
            let f = { o } -> match o is
              Full { v = whole } -> 1
              Empty -> -1
            let main = f { o = Full { v = Empty } }
            """
        )
    }
}
