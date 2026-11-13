package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * A `perform`'s handler must bind the effect's associated types to what the BODY
 * requires — in the RETURN direction as well as the parameter direction.
 *
 * The parameter direction IS checked. `PerformValidator.validatePerformExpression`
 * compares each handler's `associatedTypeBindings` against
 * `collectThrownErrorTypes(expr.body, …)` and raises V0501 "Handler type
 * mismatch", which is why `perform StringHandler in validate {}` is refused when
 * `validate` throws a ValidationError.
 *
 * The RETURN direction is not checked at all, because `collectThrownErrorTypes`
 * only sees values flowing INTO an op. An associated type that flows OUT of one
 * — `get: {} -> 'val` — is never compared. MEASURED end to end:
 *
 *     impl Store for IntStore having 'val = Int where { get = {} -> 1 }
 *     impl Store for StrStore having 'val = Str where { get = {} -> "A STRING" }
 *
 *     pub as_int : {} -> Int <Store having 'val = Int>    <- the body DOES pin it
 *     pub bad    : {} -> Int <*>                          <- performed with StrStore
 *
 *     Check passed
 *     bad (declared Int) = A STRING
 *     java.lang.RuntimeException: Cannot multiply String and Long
 *
 * The information needed is already computed and printed: the body's row carries
 * `having 'val = Int`. The perform site simply never compares it against the
 * performer's `having 'val = Str`.
 *
 * Why the single-impl cases DID get caught: with one impl in scope, inference
 * takes its binding, so the clash surfaces at the body's own annotation as a
 * T0201/T0210 naming `<Store having 'val = …>`. TWO impls leave nothing to infer
 * from, and the perform site was the only check left.
 *
 * THE BREAK TESTS ASSERT THE BROKEN BEHAVIOUR. A failure here means someone
 * fixed it — flip them to `rejects` with V0501 then. The fix belongs in
 * `PerformValidator`: derive the body's required bindings from its inferred ROW
 * (not only from throw sites) and compare them against each handler's bindings,
 * reusing `areAssociatedTypesCompatible` and the existing `HandlerTypeMismatch`.
 */
class PerformHandlerBindingSpecTest : VariantSpecBase() {

    @Test fun `BREAK - a performer whose binding contradicts the body is accepted`() {
        accepts(
            """
            effect Store having 'val where { get: {} -> 'val <Store> }
            impl Store for IntStore having 'val = Int where { get = {} -> 1 }
            impl Store for StrStore having 'val = Str where { get = {} -> "A STRING" }
            pub let as_int: {} -> Int <Store> = {} -> Store.get {}
            pub let bad: {} -> Int <*> = {} -> perform StrStore in as_int {}
            $MAIN
            """,
            "BREAK. `as_int`'s row is inferred as `<Store having 'val = Int>`, and " +
                "StrStore binds 'val = Str. Measured: checks clean, then " +
                "`bad (declared Int) = A STRING` and `Cannot multiply String and " +
                "Long` at the first arithmetic. Should be V0501.",
        )
    }

    @Test fun `BREAK - the same crossing with several associated types`() {
        accepts(
            """
            effect Triple having 'a, 'b, 'c where {
              one: {} -> 'a <Triple>,
              two: {} -> 'b <Triple>,
              three: {} -> 'c <Triple>
            }
            impl Triple for H having 'a = Int, 'b = Str, 'c = Bool where {
              one = {} -> 1,
              two = {} -> "x",
              three = {} -> True
            }
            pub let use_it: {} -> Str <Triple> = {} -> Triple.one {}
            pub let run = {} -> perform H in use_it {}
            $MAIN
            """,
            "The same gap with several associated types: the site asks `one` for a " +
                "Str while the impl binds 'a = Int. Nothing compares them.",
        )
    }

    @Test fun `the matching performer is accepted`() {
        accepts(
            """
            effect Store having 'val where { get: {} -> 'val <Store> }
            impl Store for IntStore having 'val = Int where { get = {} -> 1 }
            impl Store for StrStore having 'val = Str where { get = {} -> "A STRING" }
            pub let as_int: {} -> Int <Store> = {} -> Store.get {}
            pub let good: {} -> Int <*> = {} -> perform IntStore in as_int {}
            $MAIN
            """,
            "THE MIRROR CASE. Any fix must leave this alone — IntStore binds " +
                "'val = Int, which is exactly what the body requires.",
        )
    }

    @Test fun `two bodies at two bindings, each with its own performer`() {
        accepts(
            """
            effect Store having 'val where { get: {} -> 'val <Store> }
            impl Store for IntStore having 'val = Int where { get = {} -> 1 }
            impl Store for StrStore having 'val = Str where { get = {} -> "A STRING" }
            pub let as_int: {} -> Int <Store> = {} -> Store.get {}
            pub let as_str: {} -> Str <Store> = {} -> Store.get {}
            pub let a = {} -> perform IntStore in as_int {}
            pub let b = {} -> perform StrStore in as_str {}
            $MAIN
            """,
            "One effect, two bindings, two performers, each used correctly. This is " +
                "the whole point of an associated type, and the reason the fix " +
                "cannot be 'one binding per effect'.",
        )
    }

    @Test fun `the PARAMETER direction is already checked`() {
        rejects(
            """
            effect Exception having 'error where {
              throw: { error: 'error } -> 'a <Exception>
            }
            type ValidationError = | FieldRequired { field: Str } |
            impl Exception for StringHandler having 'error = Str where {
              throw = { error } -> abort { val = {} }
            }
            pub let validate: {} -> {} <Exception> = {} ->
              Exception.throw { error = FieldRequired { field = "x" } }
            pub let run = {} -> perform StringHandler in validate {}
            $MAIN
            """,
            "V0501",
            "The direction that WORKS, kept beside the one that does not. " +
                "`collectThrownErrorTypes` sees the thrown ValidationError and " +
                "compares it against StringHandler's 'error = Str. Only values " +
                "flowing INTO an op are covered, which is exactly why the return " +
                "direction is not.",
        )
    }
}
