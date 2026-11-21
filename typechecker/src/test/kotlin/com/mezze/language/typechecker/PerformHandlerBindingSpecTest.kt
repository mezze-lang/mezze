package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * A `perform`'s handler must bind the effect's associated types to what the BODY
 * requires — in the RETURN direction as well as the parameter direction.
 *
 * FIXED. These were BREAK tests asserting the hole; they now assert the refusal.
 *
 * The hole: only the parameter direction was checked, by
 * `PerformValidator.validatePerformExpression`, which works on the PARSED AST —
 * it reads an operation's param-type annotation and rebuilds a type from the
 * literal argument expression. That reaches a value flowing INTO an op and has
 * no route to one flowing out, so `get: {} -> 'val` was never compared.
 * MEASURED before the fix:
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
 * Why the single-impl cases DID get caught: with one impl in scope, inference
 * takes its binding, so the clash surfaces at the body's own annotation as a
 * T0201/T0210 naming `<Store having 'val = …>`. TWO impls leave nothing to infer
 * from, and the perform site was the only check left.
 *
 * THE FIX is in `ExprPerformConstraints`, not in `PerformValidator`: by the time
 * a perform's constraints are generated, the body's requirement is in
 * `effectInstances` (`<Store having 'val = Int>` — what the printer shows) and
 * the performer's binding is in `associatedTypes`. The suspension path twenty
 * lines below already builds a substitution from exactly that pair; it just
 * never asked whether they agree. Asking is one equality constraint, so the
 * solver's unification decides rather than a hand-written comparison.
 *
 * ONLY A CONCRETE BINDING IS CHECKED, and the last test here is why: every
 * performer in `std/effects/` binds to a VARIABLE, which must stay free to take
 * the body's type.
 */
class PerformHandlerBindingSpecTest : VariantSpecBase() {

    @Test fun `a performer whose binding contradicts the body is refused`() {
        rejects(
            """
            effect Store having 'val where { get: {} -> 'val <Store> }
            impl Store for IntStore having 'val = Int where { get = {} -> 1 }
            impl Store for StrStore having 'val = Str where { get = {} -> "A STRING" }
            pub let as_int: {} -> Int <Store> = {} -> Store.get {}
            pub let bad: {} -> Int <*> = {} -> perform StrStore in as_int {}
            $MAIN
            """,
            "T0201",
            "THE HOLE, NOW CLOSED. `as_int`'s row is inferred as " +
                "`<Store having 'val = Int>` and StrStore binds 'val = Str. Before " +
                "the fix this checked clean and then ran, printing " +
                "`bad (declared Int) = A STRING` before dying in the first " +
                "arithmetic. The message names the performer, the binding and both " +
                "types: \"performer 'StrStore' binds `having 'val` to Str, but the " +
                "body it handles requires Int\".",
        )
    }

    @Test fun `the same crossing with several associated types is refused`() {
        rejects(
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
            "T0201",
            "The same crossing with several associated types — the site asks `one` " +
                "for a Str while the impl binds 'a = Int. Each binding is compared " +
                "separately, so an effect with three of them is three constraints, " +
                "not one all-or-nothing match.",
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

    @Test fun `the PARAMETER direction is covered by the same check`() {
        rejects(
            """
            effect Exception having 'error where {
              throw: { error: 'error } -> 'a <Exception>
            }
            type ValidationError = | FieldRequired { field: Str } |
            impl Exception for StringHandler having 'error = Str where {
              throw = { error } -> cont.abort { val = {} }
            }
            pub let validate: {} -> {} <Exception> = {} ->
              Exception.throw { error = FieldRequired { field = "x" } }
            pub let run = {} -> perform StringHandler in validate {}
            $MAIN
            """,
            "T0201",
            "THE PARAMETER DIRECTION. This used to be V0501 from " +
                "`PerformValidator`; the body's `<Exception having 'error = " +
                "ValidationError>` now reaches the same conclusion first. V0501's " +
                "message named the handler, the effect and both types, so the " +
                "replacement had to as well — that is what the " +
                "`HANDLER_BINDING_CONTEXT` branch in ErrorMessageBuilder is for.\n" +
                "It does NOT make `PerformValidator`'s check redundant, which a " +
                "reachability probe over the whole suite settled: that path still " +
                "fires on a VARIANT SUBSET (a handler covering fewer constructors " +
                "than the body throws), because unification JOINS variant rows " +
                "instead of demanding equality. Complementary, not duplicated.",
        )
    }

    @Test fun `a performer bound to a VARIABLE takes whatever the body requires`() {
        accepts(
            """
            use std::result
            effect Ex having 'error where { throw: { error: 'error } -> 'a <Ex> }
            impl Ex for ToResult having 'error = 'e where {
              throw = { error } -> cont.abort { val = Err { error } }
            }
            pub let as_str: { b: Int } -> Int <Ex> = { b } ->
              if b == 0 then Ex.throw { error = "div0" } else b
            pub let run = {} -> perform ToResult in do
              let v = as_str { b = 0 }
              Ok { val = v }
            $MAIN
            """,
            "THE SHAPE THE WHOLE STDLIB IS, and the reason the check skips a " +
                "VARIABLE binding. `std/effects/` has no performer bound to a " +
                "concrete type: `ExceptionToResult`, `ExceptionToOption`, " +
                "`AbortException`, `IgnoreException` and `CrashException` all take " +
                "`having 'error = 'e`; `EarlyReturn` takes `'result = 'r`; the " +
                "three `Condition` performers take `'ctx = 'c`; `WithReader " +
                "{ val: 'a }` takes `'a = 'a`. A variable must stay free to take " +
                "the body's type — that IS the binding's purpose — so constraining " +
                "it would refuse every one of them. `compileStdlib` is the real " +
                "gate on this; the test is here so the reason is written down.",
        )
    }

    @Test fun `the same variable-bound performer serves two different error types`() {
        accepts(
            """
            use std::result
            effect Ex having 'error where { throw: { error: 'error } -> 'a <Ex> }
            type Code = | Bad, Worse |
            impl Ex for ToResult having 'error = 'e where {
              throw = { error } -> cont.abort { val = Err { error } }
            }
            pub let s: { b: Int } -> Int <Ex> = { b } -> Ex.throw { error = "a str" }
            pub let v: { b: Int } -> Int <Ex> = { b } -> Ex.throw { error = Bad }
            pub let ra = {} -> perform ToResult in do
              let x = s { b = 0 }
              Ok { val = x }
            pub let rb = {} -> perform ToResult in do
              let x = v { b = 0 }
              Ok { val = x }
            $MAIN
            """,
            "The other half of why the check must not pin a variable binding: ONE " +
                "performer at TWO error types, in the same module. `performerInfo` " +
                "comes from the typing context, so a variable in it may be shared " +
                "between perform sites — pinning it at the first would wrongly " +
                "constrain the second. A concrete binding cannot be shared that way, " +
                "which is what makes the concrete-only rule safe as well as correct.",
        )
    }
}
