package com.mezze.language.cli.commands

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.diagnostics.display

/**
 * Long-form documentation for `mez explain CODE`.
 *
 * Inlined as a Kotlin map for now — small enough, no resource-loading or
 * native-image glob gymnastics. If this grows past ~50 codes we'll move
 * it into a sqlite blob alongside `stdlib.sqlite`.
 */
object ExplainDocs {
    fun lookup(code: String): String? = docs[code.uppercase()]
    fun hasDoc(code: ErrorCode): Boolean = code.display() in docs

    private val docs: Map<String, String> = mapOf(
        "T0201" to """
            # T0201: Type mismatch

            A value's type does not match the type expected by its context — a function
            argument, a record field, a return value, or the right-hand side of a
            typed `let`.

            ## Example

            ```mezze
            let pair: { a: Int, b: Str } = { a = 1, b = 2 }
            //                                            ^
            //                          expected Str, found Int
            ```

            ## Common causes

            - A literal of the wrong shape (`2` where `"2"` is expected).
            - Forgetting to wrap a value in a constructor (`5` vs. `Some 5`).
            - A function argument whose record fields don't match the parameter type
              (extra, missing, or wrong-typed fields).
            - A polymorphic call site whose type variable was already pinned by an
              earlier use.

            ## How to fix

            - Change the value to match the expected type, or
            - Change the annotation/parameter type to match the value, or
            - If both sides should be polymorphic, replace the concrete type with a
              type variable.
        """.trimIndent(),

        "T0202" to """
            # T0202: Record is missing fields

            A closed record literal is missing field(s) required by its declared type.
            Closed records must list every field exactly — no extras, no omissions.

            ## Example

            ```mezze
            type Point = { x: Int, y: Int }
            let p: Point = { x = 1 }
            //             ^^^^^^^^^ missing field `y`
            ```

            ## How to fix

            - Add the missing fields to the literal:
              `let p: Point = { x = 1, y = 2 }`.
            - Or, if you want a record with only some of the fields, use a different
              type (an open row type) or build a separate record.
        """.trimIndent(),

        "T0203" to """
            # T0203: Record has extra fields

            A closed record literal has field(s) that aren't part of its declared type.

            ## Example

            ```mezze
            type Point = { x: Int, y: Int }
            let p: Point = { x = 1, y = 2, z = 3 }
            //                              ^^^^^ extra field `z`
            ```

            ## How to fix

            - Remove the extra fields from the literal, or
            - Add them to the type definition if they belong there.
        """.trimIndent(),

        "T0205" to """
            # T0205: Field not found

            A `.field` access was used on a value whose type doesn't have that field.

            ## Example

            ```mezze
            let p = { x = 1, y = 2 }
            let z = p.z
            //        ^ no field `z` on `{ x: Int, y: Int }`
            ```

            ## How to fix

            - Check the field name for typos. The error usually includes a
              "did you mean ...?" hint when a close match exists.
            - Verify the receiver's actual type — sometimes the value isn't the
              record you think it is (e.g. an option wrapper).
        """.trimIndent(),

        "T0210" to """
            # T0210: Annotation vs body mismatch

            A `let` binding has a type annotation that doesn't match its body's
            inferred type. The annotation is the contract, so the body must produce
            exactly that type.

            ## Example

            ```mezze
            let n: Int = "hello"
            //           ^^^^^^^ annotated as Int but body is Str
            ```

            ## How to fix

            - Change the annotation to match the body, or
            - Change the body to produce the annotated type, or
            - If the annotation is too strict (concrete where a type variable is
              wanted), generalize it.
        """.trimIndent(),

        "T0211" to """
            # T0211: Callback parameter names don't match the signature

            You passed a lambda whose parameter list destructures record fields
            that don't match the field names the caller expects. In Mezze,
            functions take a single record argument, and lambda parameter lists
            ARE record destructuring by field name — so the parameter names in
            the lambda MUST match the field names in the caller's record.

            ## Example

            ```mezze
            # seq::fold's signature says: f: { acc: 'b, x: 'a } -> 'b
            # So fold will invoke your callback with a record whose fields
            # are literally `acc` and `x`.

            seq::fold {
              xs = [1, 2, 3],
              init = 0,
              f = { acc, i } -> acc + i    # ✗ tries to destructure field `i`
            }                              #   but caller passes `{ acc, x }`

            seq::fold {
              xs = [1, 2, 3],
              init = 0,
              f = { acc, x } -> acc + x    # ✓ matches signature
            }
            ```

            ## How to fix

            - Rename your lambda's parameters to match the field names the
              caller uses. Look at the signature of the HOF you're calling
              (`mez check` on your file usually prints the expected shape).
            - This is not arbitrary: because Mezze passes named records, the
              stdlib author's field-name choice ("x" vs "elem" vs "item")
              propagates to every caller. Positional arguments don't exist
              here.
            - Common HOFs and their expected callback records:
              - `seq::map { xs, f: { x } -> ... }`
              - `seq::fold { xs, init, f: { acc, x } -> ... }`
              - `seq::filter { xs, p: { x } -> Bool }`
        """.trimIndent(),

        "V0501" to """
            # V0501: Effect not handled

            An effect operation was performed but no handler is installed for that
            effect at the call site. Mezze tracks effects in the type system — every
            effectful operation must eventually be enclosed by a `perform`/`handle`
            block that resolves it.

            ## Example

            ```mezze
            effect Log { log: Str -> {} }
            let main = do
                Log.log { val = "hi" }
            //  ^^^^^^^ effect `Log` is performed but not handled
            end
            ```

            ## How to fix

            - Wrap the expression in a `perform Logger in do ... end` block, where
              `Logger` is a performer for the `Log` effect.
            - Or, install an inline handler with `handle e is { ... } in expr`.
            - If the effect should propagate up to the caller, annotate the
              enclosing function with the effect in its return type.
        """.trimIndent(),

        "V0601" to """
            # V0601: Non-exhaustive match

            A `match` doesn't cover every possible value of its scrutinee. Mezze
            requires matches to be exhaustive — every variant constructor (or every
            literal class) must have a branch, or there must be a catch-all.

            ## Example

            ```mezze
            type Shape = | Circle | Square | Triangle |
            let area = match s is
                Circle -> 3.14
                Square -> 1.0
            //  missing branch for `Triangle`
            ```

            ## How to fix

            - Add branches for the missing constructors.
            - Or, add a wildcard `_ -> ...` branch as a default.
        """.trimIndent(),

        "V0701" to """
            # V0701: Duplicate declaration

            A name was declared more than once in the same scope. Mezze does not allow
            shadowing of top-level declarations within a module.

            ## Example

            ```mezze
            let count = 1
            let count = 2
            //  ^^^^^ already declared above
            ```

            ## How to fix

            - Rename one of the bindings.
            - Or, if you meant to update a value, introduce a new name (Mezze is
              immutable-by-default).
            - For functions, consider whether you actually want one general function
              rather than two with the same name.
        """.trimIndent(),

        "V0702" to """
            # V0702: Undefined variable

            A name was used but never declared in scope, nor imported from another
            module.

            ## Example

            ```mezze
            let total = sum + 1
            //          ^^^ not in scope
            ```

            ## How to fix

            - Declare the variable with `let` before using it.
            - Import it with `use module::name` if it lives in another module.
            - Check the spelling — Mezze usually suggests a close match if one exists.
        """.trimIndent(),

        "M0801" to """
            # M0801: Module not found

            A `use` declaration named a module that the loader couldn't locate on any
            of its search paths (project sources + stdlib).

            ## Example

            ```mezze
            use my_app::missing
            //          ^^^^^^^ no file `missing.mz` in this package
            ```

            ## How to fix

            - Check the spelling of the module path.
            - Verify the file exists at the expected location (Mezze maps
              `pkg::sub::mod` to `<pkg-root>/sub/mod.mz`).
            - If you're importing from another package, make sure it's listed in
              `Mezze.toml` and synced.
        """.trimIndent(),

        "M0803" to """
            # M0803: Not exported

            You tried to import a name that exists in the target module but isn't
            public. Mezze requires explicit `pub` on every cross-module export.

            ## Example

            ```mezze
            // in src/util.mz
            let secret = 42       // not pub
            pub let visible = 7

            // in src/main.mz
            use my_app::util::{secret}
            //                 ^^^^^^ exists but private
            ```

            ## How to fix

            - Add `pub` in front of the declaration in the source module:
              `pub let secret = 42`.
            - Or, expose a public helper that uses the private value.
        """.trimIndent(),

        "P0001" to """
            # P0001: Unterminated string

            A string literal opened with `"` was never closed before end of line or
            end of file.

            ## Example

            ```mezze
            let greeting = "hello
            //             ^ missing closing quote
            ```

            ## How to fix

            - Add the missing `"` at the end of the string.
            - If the string should span multiple lines, escape the newline or break
              it into multiple concatenated strings.
            - Watch out for stray `"` characters in the middle of the string —
              escape them as `\"`.
        """.trimIndent(),

        "T0204" to """
            # T0204: Variant constructor field mismatch

            A variant constructor was called with the wrong set of fields.
            Variant constructors take a record payload whose fields must match
            the declared shape exactly.

            ## Example

            ```mezze
            type Shape = | Circle { r: Int } | Square { s: Int } |
            let c = Circle { radius = 5 }  // ❌ expected `r`, got `radius`
            ```

            ## How to fix

            - Use the declared field names from the type definition.
            - If you wanted to add fields, edit the type declaration first.
        """.trimIndent(),

        "T0206" to """
            # T0206: Method not found

            A `.method` call resolves through ability/impl tables. If no matching
            method exists for the receiver's type, you get T0206.

            ## Example

            ```mezze
            let n = 5
            let s = n.greet {}
            //        ^^^^^ no method `greet` on `Int`
            ```

            ## How to fix

            - Check the spelling — the error usually suggests close matches.
            - Make sure the relevant `impl` is in scope (imported via `use`).
            - If the method should exist, add an `impl Ability for Type` block.
        """.trimIndent(),

        "T0207" to """
            # T0207: Infinite type

            The type-checker tried to unify a type variable with a type that
            contains itself. Mezze's type system doesn't allow recursive types
            via inference — only via named `type` declarations.

            ## Example

            ```mezze
            let f = { x } -> x.f {}
            //               ^^^ f's type would need to contain itself
            ```

            ## How to fix

            - Use a named recursive type declaration: `type Tree = ...`.
            - Or break the recursion by introducing a wrapper variant.
        """.trimIndent(),

        "V0401" to """
            # V0401: Ability not defined

            An `impl SomeAbility for ...` block referred to an ability name
            that isn't declared or imported.

            ## How to fix

            - Declare the ability with `ability SomeAbility { ... }`.
            - Or import it: `use other_module::SomeAbility`.
            - Check spelling — error message suggests close matches.
        """.trimIndent(),

        "V0402" to """
            # V0402: Ability constraint not satisfied

            A generic function requires an ability the caller hasn't proven for
            the type at the call site.

            ## Example

            ```mezze
            let sort = { xs: [a], a: Ord } -> ...
            let bad = sort { xs = [MyType {}] }  // ❌ MyType has no Ord impl
            ```

            ## How to fix

            - Provide an `impl Ord for MyType` block, or
            - Restrict the call to types that already have the impl, or
            - Relax the generic signature if the ability isn't actually needed.
        """.trimIndent(),

        "V0408" to """
            # V0408: No implementation found

            A method dispatch resolved to no concrete impl. This usually means
            the receiver's type has no `impl SomeAbility for ThatType` in scope.

            ## How to fix

            - Add the missing `impl` block.
            - Import an existing impl: `use other_module::{impl SomeAbility for ThatType}`.
            - Check that the receiver's type matches what the impl expects
              (sometimes a value is inferred as a different type than you expect).
        """.trimIndent(),

        "V0501" to """
            # V0501: Effect not handled

            An effect operation was performed but no handler is installed for
            that effect at the call site. Mezze tracks effects in the type
            system — every effectful operation must eventually be enclosed by
            a `perform`/`handle` block that resolves it.

            ## Example

            ```mezze
            effect Log { log: { msg: Str } -> {} }
            let main = do
                Log.log { msg = "hi" }
            //  ^^^^^^^ effect `Log` is performed but not handled
            end
            ```

            ## How to fix

            - Wrap the expression in `perform Logger in do ... end`, where
              `Logger` is a performer for the `Log` effect.
            - Or, if the effect should propagate to the caller, annotate the
              enclosing function's return type so the effect is visible.
        """.trimIndent(),

        "V0603" to """
            # V0603: Unreachable pattern

            A match case can never run because an earlier case already covers
            every value it would match.

            ## Example

            ```mezze
            match x is
                _ -> "anything"   // catches everything
                Some n -> n       // ❌ unreachable
            end
            ```

            ## How to fix

            - Reorder cases so specific patterns come before catch-alls.
            - Or remove the redundant case.
        """.trimIndent(),

        "V0604" to """
            # V0604: Constructor has no payload

            A variant constructor was called with arguments, but its declaration
            has no payload — it's a nullary tag.

            ## Example

            ```mezze
            type Color = | Red | Green | Blue |
            let c = Red { intensity = 5 }
            //          ^^^^^^^^^^^^^^^^^ Red has no payload fields
            ```

            ## How to fix

            - Use the constructor without args: `let c = Red`.
            - Or, if Red should carry data, declare it with a payload:
              `| Red { intensity: Int } |`.
        """.trimIndent(),

        "V0707" to """
            # V0707: Constructor must start with uppercase

            Variant constructor names must be uppercase to distinguish them
            from variable bindings in patterns.

            ## Example

            ```mezze
            type Maybe = | none | some Int |
            //            ^^^^   ^^^^ must be `None`, `Some`
            ```

            ## How to fix

            - Rename to start with an uppercase letter.
        """.trimIndent(),

        "P0101" to """
            # P0101: Expected token

            The parser expected a particular token (like `=`, `}`, `->`) but
            saw something else.

            ## Example

            ```mezze
            let x: Int 5    // ❌ expected `=` between annotation and value
            ```

            ## How to fix

            - Read the error's "expected" hint and insert the missing token.
            - Common slips: missing `=` in `let`, missing `->` in lambdas,
              missing `}` in records.
        """.trimIndent(),

        "P0109" to """
            # P0109: Refutable pattern in `let`

            `let` bindings require an irrefutable pattern — one that always
            matches. Variant constructors (like `Some x`) might fail to match,
            so they're not allowed in `let`.

            ## Example

            ```mezze
            let Some n = lookup k m   // ❌ refutable
            ```

            ## How to fix

            - Use `match` instead:
              `match lookup k m is | Some n -> ... | None -> ... end`.
            - Or, for partial bindings, use a record pattern: `let {x, y} = p`.
        """.trimIndent(),

        "P0118" to """
            # P0118: Function or constructor needs named arguments

            Mezze functions are called with named arguments wrapped in
            braces: `f { x = 1, y = 2 }`. Positional `()` calls and bare-
            argument calls (`f x`) are intentional syntax errors — every
            argument has a name to keep call sites self-documenting.

            ## Example

            ```mezze
            greet("world")    // ❌ uses `()`
            greet "world"     // ❌ bare argument
            Some 42           // ❌ constructor without `{}`
            ```

            ## How to fix

            - Use the named form: `greet { msg = "world" }`.
            - For one-field constructors: `Some { value = 42 }`.
            - The field names come from the function or constructor's
              record-typed parameter; hover the callee to see them.
        """.trimIndent(),
    )
}
