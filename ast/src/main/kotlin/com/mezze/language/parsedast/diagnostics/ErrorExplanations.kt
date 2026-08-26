package com.mezze.language.parsedast.diagnostics

fun ErrorCode.explain(): String = when (this) {
    ErrorCode.UNCATEGORIZED ->
        "This error has not yet been assigned a specific code. " +
        "It will be categorized in a future release."

    ErrorCode.P0001_UNTERMINATED_STRING ->
        "A string literal was opened with a quote but never closed. " +
        "Check for a missing closing quote, or an unescaped quote inside the string."

    ErrorCode.P0002_UNEXPECTED_CHARACTER ->
        "The parser encountered a character that is not valid in this position. " +
        "Check for typos, unsupported operators, or encoding issues."

    ErrorCode.P0101_EXPECTED_TOKEN ->
        "The parser expected a specific token (like a closing bracket, comma, or keyword) " +
        "but found something else. Check the surrounding syntax."

    ErrorCode.P0102_EMPTY_LAMBDA_PARAMS ->
        "Lambda parameters cannot be empty. Use `{} -> body` for a unit lambda, " +
        "or provide at least one parameter: `{x} -> body`."

    ErrorCode.P0103_MIXED_TYPED_UNTYPED_PARAMS ->
        "All lambda parameters must either have type annotations or none. " +
        "You cannot mix `{x: Int, y}` — either annotate all or annotate none."

    ErrorCode.P0104_SPREAD_NOT_AT_START ->
        "The spread operator `...record` must appear at the start of a record literal, " +
        "before any explicit field assignments."

    ErrorCode.P0105_LOWERCASE_CONSTRUCTOR ->
        "Variant constructors must start with an uppercase letter. " +
        "Use `Ok` instead of `ok`, `Some` instead of `some`."

    ErrorCode.P0106_NOT_CONSTRAINED_LAMBDA ->
        "This expression is not a valid constrained lambda. " +
        "Check that the `with` clause is properly formed."

    ErrorCode.P0107_INVALID_FRACTION_LITERAL ->
        "The fraction literal is malformed. Fractions use the syntax `1/3` " +
        "where both parts are integer literals."

    ErrorCode.P0108_EMPTY_PARENTHESES ->
        "Empty parentheses `()` are not valid in Mezze. " +
        "For a unit value, use `{}`. For grouping, the parentheses must contain an expression."

    ErrorCode.P0109_REFUTABLE_PATTERN_IN_LET ->
        "This pattern may not match all values and cannot be used in a `let` binding. " +
        "Use `match` for patterns that might fail."

    ErrorCode.P0110_EMPTY_INTERPOLATION ->
        "String interpolation `\\()` must contain an expression. " +
        "Use `\"value is \\(expr)\"` with a non-empty expression."

    ErrorCode.P0111_INVALID_VARIANT_SYNTAX ->
        "Variant type syntax is invalid. Use `type T = | A, B { val: Int }, C |` — " +
        "pipe-delimited, comma-separated constructors."

    ErrorCode.P0112_DO_BLOCK_LAST_STATEMENT_IS_LET ->
        "A `do` block cannot end with a `let` binding. " +
        "The last statement must be an expression that produces the block's value."

    ErrorCode.P0113_DO_BLOCK_LAST_STATEMENT_NEEDS_EXPR ->
        "The last statement in a `do` block must be an expression. " +
        "It determines the block's return value."

    ErrorCode.P0114_DO_BLOCK_EMPTY ->
        "A `do` block cannot be empty. It must contain at least one expression."

    ErrorCode.P0115_PIPE_MISSING_PLACEHOLDER ->
        "A pipe expression `|>` requires a `@` placeholder in the right-hand side " +
        "to mark where the piped value goes: `x |> f { arg = @ }`."

    ErrorCode.P0116_PIPE_MULTIPLE_PLACEHOLDERS ->
        "A pipe expression can only have one `@` placeholder. " +
        "Bind the value first if you need it in multiple positions."

    ErrorCode.P0124_PIPE_PLACEHOLDER_AS_RECEIVER ->
        "`@` in a pipe may be an argument value (`x |> f { arg = @ }`) but not a " +
        "method or field receiver. Write `x.method {}` directly, or pipe into a " +
        "function that takes the value."

    ErrorCode.P0125_INTEGER_LITERAL_OUT_OF_RANGE ->
        "An integer literal is larger than a 64-bit integer can hold " +
        "(max 9223372036854775807, min -9223372036854775808)."

    ErrorCode.P0126_DECL_NAME_ON_NEW_LINE ->
        "A declaration's name must be on the same line as its keyword " +
        "(`let name …`, `type Name …`, `pub let …`). A keyword left alone " +
        "on a line is almost always a typo that would silently absorb the " +
        "next line."

    ErrorCode.P0117_RECORD_FIELD_MISSING_TYPE ->
        "Record type fields must have type annotations: `{ name: Str, age: Int }`."

    ErrorCode.P0118_NAMED_ARGS_REQUIRED ->
        "Mezze uses named arguments for all function calls. " +
        "Use `f { x = 1, y = 2 }` instead of `f(1, 2)`."

    ErrorCode.P0119_ABILITY_LEVEL_CONSTRAINT ->
        "Ability-level constraints like `ability A for 'a with B 'a` are not allowed. " +
        "Put constraints on individual methods instead."

    ErrorCode.P0120_WITH_AFTER_ARROW ->
        "The `with` clause for ability constraints must come before the `->` arrow. " +
        "Use `{x} with Show 'a -> body` not `{x} -> body with Show 'a`."

    ErrorCode.P0121_LAMBDA_BODY_BARE_LET ->
        "A lambda body cannot start with a bare `let`. " +
        "Wrap the body in a `do` block: `{x} -> do let y = ...; y`."

    ErrorCode.P0122_MATCH_NEEDS_IS ->
        "Mezze uses `match expr is` followed by indented arms. " +
        "Not `match expr { ... }` or `match expr case ...`."

    ErrorCode.P0123_LAMBDA_INLINE_RETURN_TYPE ->
        "Return type annotations go on the binding, not inline in the lambda. " +
        "Use `let f: {Int} -> Int = {x} -> x` not `{x} -> Int do x`."

    ErrorCode.P0127_HAVING_NEEDS_BINDING ->
        "The `having` keyword in a `with` constraint requires a type variable binding: " +
        "`having 'elem = Int`, not `having Int`. The type variable must match " +
        "the one declared in the ability's `having` clause."

    ErrorCode.T0201_TYPE_MISMATCH ->
        "Two types that should be the same are different. This is the most common type error. " +
        "Check that the argument types match the function's parameter types, " +
        "or that both branches of a conditional have the same type."

    ErrorCode.T0202_RECORD_MISSING_FIELDS ->
        "A record value is missing fields that the expected type requires. " +
        "Add the missing fields to the record literal."

    ErrorCode.T0203_RECORD_EXTRA_FIELDS ->
        "A record value has fields that are not in the expected type. " +
        "Remove the extra fields, or use an open record type with `...row`."

    ErrorCode.T0204_VARIANT_CONSTRUCTOR_ARITY ->
        "A variant constructor was called with the wrong payload. " +
        "Check if the constructor expects data or not."

    ErrorCode.T0205_FIELD_NOT_FOUND ->
        "Attempted to access a field that doesn't exist on this record type. " +
        "Check the field name for typos, or verify the record has this field."

    ErrorCode.T0206_METHOD_NOT_FOUND ->
        "No method with this name was found for the given type. " +
        "Check that the ability providing this method is in scope."

    ErrorCode.T0207_INFINITE_TYPE ->
        "Type inference produced a cyclic type (e.g., 'a = List 'a). " +
        "This usually means a recursive function needs a type annotation."

    ErrorCode.T0208_LITERAL_PATTERN_ON_VARIANT ->
        "A literal value (like a number or string) was used as a pattern " +
        "where a variant constructor was expected."

    ErrorCode.T0209_ABILITY_AS_VALUE ->
        "An ability name was used as a value, but abilities are not values. " +
        "Use the ability's methods directly, or call a function with the appropriate constraint."

    ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH ->
        "The type annotation on a binding doesn't match the inferred type of the body. " +
        "Either fix the annotation or the implementation."

    ErrorCode.T0211_CALLBACK_PARAM_NAMES_MISMATCH ->
        "A callback lambda's parameter names don't match the record field names the caller passes. " +
        "In Mezze, lambda parameter lists ARE record destructuring — writing `{ acc, i } -> body` " +
        "means \"extract fields `acc` and `i` from the argument record\". If the caller passes a " +
        "record with fields `{ acc, x }`, the lambda must destructure those exact field names: " +
        "`{ acc, x } -> body`. Rename the parameters to match, or (when supported) alias with " +
        "`{ acc, x: myLocal } -> ...`."

    ErrorCode.T0301_UNBOUND_TYPE_VAR ->
        "A type variable in an annotation is not bound by any parameter. " +
        "All type variables must appear in the function's parameter types."

    ErrorCode.T0302_CANNOT_APPLY_NON_FUNCTION ->
        "Attempted to call something that is not a function. " +
        "Check that the expression before `{ ... }` is a function."

    ErrorCode.T0303_OPEN_RECORD_FIELD_MISSING ->
        "Accessing a field on an open record that hasn't been constrained to have that field. " +
        "The record's row variable means it might not have this field."

    ErrorCode.T0304_DUPLICATE_RECORD_FIELD ->
        "A record literal lists the same field name more than once, e.g. `{ a = 1, a = 2 }`. " +
        "Each field must appear at most once. Remove the duplicate or rename it."

    ErrorCode.T0305_DUPLICATE_LAMBDA_PARAM ->
        "A lambda lists the same parameter name more than once, e.g. `{ x, x } -> x`. " +
        "Each parameter must have a distinct name."

    ErrorCode.T0306_SHADOWED_BINDING_IN_SCOPE ->
        "A `let` reuses a name that is already bound in the same function scope — " +
        "either the enclosing lambda's parameter, or a name bound earlier in the same " +
        "do-block (e.g. `{x} -> do { let x = 1; ... }`). This silently overwrites the " +
        "earlier binding, which is almost always a mistake. Rename one of them. " +
        "(Shadowing an OUTER/nested-scope name, like a top-level `let` or a name from " +
        "an enclosing function, is still allowed.)"

    ErrorCode.V0401_ABILITY_NOT_DEFINED ->
        "Referenced an ability that doesn't exist. " +
        "Check the ability name and that it's imported."

    ErrorCode.V0402_ABILITY_NOT_SATISFIED ->
        "A required ability constraint is not satisfied. " +
        "An implementation of the ability for the given type must be in scope."

    ErrorCode.V0403_DUPLICATE_ABILITY_DECL ->
        "An ability with this name is already declared. " +
        "Each ability name must be unique within its module."

    ErrorCode.V0404_IMPL_MISSING_METHOD ->
        "An ability implementation is missing a required method. " +
        "All methods declared in the ability must be implemented."

    ErrorCode.V0405_IMPL_EXTRA_METHOD ->
        "An ability implementation defines a method that is not part of the ability declaration."

    ErrorCode.V0406_ORPHAN_INSTANCE ->
        "An ability implementation must be defined in the same module as either " +
        "the ability or the type it's implemented for (orphan rule)."

    ErrorCode.V0407_OVERLAPPING_INSTANCE ->
        "Multiple implementations of the same ability for the same type. " +
        "Only one implementation per ability-type pair is allowed."

    ErrorCode.V0408_NO_IMPLEMENTATION ->
        "No implementation found for a required ability. " +
        "Provide an `impl` block or import one."

    ErrorCode.V0409_ABILITY_USED_AS_VALUE ->
        "An ability name was used where a value is expected. " +
        "Abilities are type-level constructs, not runtime values."

    ErrorCode.V0410_ABILITY_LEVEL_CONSTRAINT_FORBIDDEN ->
        "Ability-level constraints are not supported. " +
        "Put constraints on individual methods instead."

    ErrorCode.V0411_POLYMORPHIC_EFFECT_IN_ABILITY_OP ->
        "Ability operations cannot declare polymorphic effects. " +
        "Effect types in ability methods must be concrete."

    ErrorCode.V0412_PERFORMED_MARKER_IN_ABILITY_OP ->
        "Ability operations cannot use `perform` markers."

    ErrorCode.V0413_MISSING_ASSOCIATED_TYPE_BINDING ->
        "An ability implementation is missing a required associated type binding."

    ErrorCode.V0414_EXTRA_ASSOCIATED_TYPE_BINDING ->
        "An ability implementation provides an associated type binding that is not declared in the ability."

    ErrorCode.V0415_IMPL_OP_MUST_BE_LAMBDA ->
        "Ability implementation operations must be lambdas (function values)."

    ErrorCode.V0416_ABILITY_OP_RECORD_PARAM_REQUIRED ->
        "Ability operations must take a record parameter, not positional arguments."

    ErrorCode.V0417_TYPE_VAR_FORBIDDEN_IN_CONCRETE_IMPL ->
        "A concrete ability implementation cannot use type variables. " +
        "The implementation type must be fully concrete."

    ErrorCode.V0418_INHERENT_IMPL_ON_UNDEFINED_TYPE ->
        "An inherent impl block references a type that is not defined in this module."

    ErrorCode.V0419_IMPL_METHOD_FIELD_CONFLICT ->
        "An impl method name conflicts with a record field of the same name."

    ErrorCode.V0420_ABILITY_METHOD_SHADOWS_BUILTIN_OPERATOR ->
        "An ability impl declares a method that the target type already resolves through a " +
        "more-authoritative provider, so the impl's method could never run. Either the name " +
        "is a built-in operator (add, sub, mul, div, mod, eq, neq, lt, le, gt, ge, and, or) — " +
        "which a call compiles to directly, bypassing the impl — or the type already has an " +
        "inherent method of that name (e.g. neg/not on Int/Bool). Rename the ability method."

    ErrorCode.V0421_IMPL_EFFECT_MISMATCH ->
        "An ability impl operation's effects don't match the ability's declared effect contract. " +
        "If the ability op declares a concrete effect like `<Storage>`, every impl must carry exactly that effect. " +
        "If the ability op is pure (no effect annotation), every impl must also be pure. " +
        "If the ability op uses `<E, ...>`, the impl must include E but may add others. " +
        "Only `<...>` (fully polymorphic) allows the impl to choose freely."

    ErrorCode.V0501_EFFECT_NOT_HANDLED ->
        "A performed effect is not handled. " +
        "Wrap the expression in a `handle` block that provides a handler for this effect."

    ErrorCode.V0502_TOP_LEVEL_PERFORM ->
        "Effects cannot be performed at the top level of a module. " +
        "Perform must occur inside a function whose effect is declared or handled."

    ErrorCode.V0504_HERMETIC_EFFECT_CONFLICT ->
        "A hermetic function (pure) cannot perform effects. " +
        "Remove the effect or mark the function as effectful."

    ErrorCode.V0505_EFFECT_NOT_DEFINED ->
        "Referenced an effect that doesn't exist. " +
        "Check the effect name and that it's imported."

    ErrorCode.V0506_SCOPED_EFFECT_INIT_FINALIZE_REQUIRED ->
        "A scoped effect requires both `init` and `finalize` handlers."

    ErrorCode.V0507_HANDLER_DUPLICATE ->
        "Duplicate handler for the same effect operation. " +
        "Each operation can only be handled once per `handle` block."

    ErrorCode.V0508_HANDLER_ON_PURE_EXPR ->
        "A handle block wraps an expression that doesn't perform any effects. " +
        "The handler is unnecessary."

    ErrorCode.V0509_HANDLER_EFFECT_MISMATCH ->
        "The handler handles an effect that the body doesn't perform."

    ErrorCode.V0510_HANDLER_UNNECESSARY ->
        "This handler is unnecessary because the effect is already handled " +
        "by an enclosing handler."

    ErrorCode.V0601_NON_EXHAUSTIVE_MATCH ->
        "This match expression doesn't cover all possible cases. " +
        "Add missing patterns or a wildcard `_` catch-all arm."

    ErrorCode.V0602_NON_EXHAUSTIVE_RECORD_PATTERN ->
        "A record pattern doesn't bind all fields of the record type. " +
        "Add the missing field patterns or use `...` to ignore remaining fields."

    ErrorCode.V0603_UNREACHABLE_PATTERN ->
        "This match arm can never be reached because a previous arm already covers " +
        "all the same cases. Consider removing it."

    ErrorCode.V0604_CONSTRUCTOR_NO_PAYLOAD ->
        "This variant constructor was used with a payload in a pattern, " +
        "but it was declared without one."

    ErrorCode.V0605_OR_PATTERN_BINDS_DIFFERENT_VARS ->
        "Both sides of an or-pattern (`A | B`) must bind the same set of variables."

    ErrorCode.V0606_NON_LINEAR_PATTERN ->
        "A pattern may not bind the same variable name twice (`A { x = n, y = n }`). " +
        "Each binding must be distinct — the two occurrences could have different types. " +
        "Use `_` for a value you want to ignore."

    ErrorCode.V0701_DUPLICATE_DECLARATION ->
        "A binding with this name already exists in this scope. " +
        "Choose a different name or shadow intentionally with a nested scope."

    ErrorCode.V0702_UNDEFINED_VARIABLE ->
        "Referenced a variable or function that is not defined. " +
        "Check the name for typos, or add a `use` import."

    ErrorCode.V0703_UNKNOWN_FIELD ->
        "Referenced a field name that doesn't exist on the given record type."

    ErrorCode.V0704_UNKNOWN_CONSTRUCTOR ->
        "Referenced a variant constructor that doesn't exist. " +
        "Check the name and that the type is imported."

    ErrorCode.V0705_TYPE_ARITY_MISMATCH ->
        "A type constructor was applied with the wrong number of type arguments."

    ErrorCode.V0707_CONSTRUCTOR_MUST_BE_UPPERCASE ->
        "Variant constructors must start with an uppercase letter."

    ErrorCode.V0708_SPREAD_ONLY_RECORD_FORBIDDEN ->
        "A record literal with only a spread and no explicit fields is not allowed."

    ErrorCode.V0709_CIRCULAR_TYPE_DEPENDENCY ->
        "Types form a circular dependency. " +
        "Break the cycle by introducing an indirection (e.g., via a type parameter)."

    ErrorCode.T0401_UNKNOWN_OPERATOR ->
        "This operator is not recognized. Check that it's a valid Mezze operator."

    ErrorCode.T0402_PIPE_PLACEHOLDER_MISUSE ->
        "The `_` placeholder can only be used in a pipe expression's right-hand side."

    ErrorCode.M0801_MODULE_NOT_FOUND ->
        "The imported module could not be found on the module search path. " +
        "Check the module path and that the file exists."

    ErrorCode.M0802_CIRCULAR_DEPENDENCY ->
        "Modules form a circular import chain. " +
        "Break the cycle by restructuring the modules."

    ErrorCode.M0803_NOT_EXPORTED ->
        "The imported name is not exported by the module. " +
        "Check that the binding is declared `pub` in the source module."

    ErrorCode.M0804_MODULE_HAS_ERRORS ->
        "The imported module has type errors and cannot be used. " +
        "Fix the errors in the imported module first."

    ErrorCode.V0901_STRUCTURAL_VALIDATION ->
        "A structural validation check failed. " +
        "This is a catch-all for miscellaneous validation errors."

    ErrorCode.I0001_UNKNOWN_TYPECHECK_FAILURE ->
        "An unexpected internal error occurred during type checking. " +
        "This is a compiler bug — please report it."

    ErrorCode.I0002_PIPELINE_NO_TYPED_MODULE ->
        "The type checking pipeline did not produce a typed module. " +
        "This is an internal compiler error — please report it."
}

fun explainErrorCode(codeStr: String): String? {
    val code = ErrorCode.entries.find { it.display() == codeStr || it.name == codeStr }
        ?: return null
    return """
        |${code.display()} (${code.name})
        |
        |${code.explain()}
    """.trimMargin()
}
