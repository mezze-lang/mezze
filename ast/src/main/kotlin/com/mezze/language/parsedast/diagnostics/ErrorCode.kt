package com.mezze.language.parsedast.diagnostics

/**
 * Stable identifier for every distinct error produced by the Mezze compiler.
 *
 * Categories:
 * - `P0xxx` parser (lex + syntax)
 * - `T0xxx` typechecker (constraints, inference, mismatches)
 * - `V0xxx` validator (abilities, effects, patterns, bindings)
 * - `M0xxx` module loader (resolution, imports)
 * - `I0xxx` internal compiler errors (never-should-fire)
 *
 * Codes are STATIC — once assigned, never reused or renumbered. Old entries
 * stay even if the error is removed (deprecated, not deleted). New entries
 * are appended.
 *
 * The bridge value [UNCATEGORIZED] exists ONLY during the Phase B migration.
 * Once every site has a real code, this value is deleted from the enum.
 * Linting / CI should fail builds that introduce new `UNCATEGORIZED` uses
 * after migration is complete.
 */
enum class ErrorCode {
    /**
     * Bridge value for sites that haven't been migrated to a real code yet.
     * Reading a diagnostic with this code means: the site exists, the error
     * fires, but we haven't yet assigned a specific code. Tracked in the
     * Phase B migration.
     */
    UNCATEGORIZED,

    // ─── Parser: lexical (P00xx) ─────────────────────────────────────────
    P0001_UNTERMINATED_STRING,
    P0002_UNEXPECTED_CHARACTER,

    // ─── Parser: syntax (P01xx) ──────────────────────────────────────────
    P0101_EXPECTED_TOKEN,
    P0102_EMPTY_LAMBDA_PARAMS,
    P0103_MIXED_TYPED_UNTYPED_PARAMS,
    P0104_SPREAD_NOT_AT_START,
    P0105_LOWERCASE_CONSTRUCTOR,
    P0106_NOT_CONSTRAINED_LAMBDA,
    P0107_INVALID_FRACTION_LITERAL,
    P0108_EMPTY_PARENTHESES,
    P0109_REFUTABLE_PATTERN_IN_LET,
    P0110_EMPTY_INTERPOLATION,
    P0111_INVALID_VARIANT_SYNTAX,
    P0112_DO_BLOCK_LAST_STATEMENT_IS_LET,
    P0113_DO_BLOCK_LAST_STATEMENT_NEEDS_EXPR,
    P0114_DO_BLOCK_EMPTY,
    P0115_PIPE_MISSING_PLACEHOLDER,
    P0116_PIPE_MULTIPLE_PLACEHOLDERS,
    P0117_RECORD_FIELD_MISSING_TYPE,
    P0118_NAMED_ARGS_REQUIRED,    // function / constructor called with positional args or ()
    P0119_ABILITY_LEVEL_CONSTRAINT, // `ability A for 'a with B 'a` or `where 'a: B` — put it on a method instead
    P0120_WITH_AFTER_ARROW, // `{p} -> Ret with C 'a` — `with` must come BEFORE the arrow
    P0121_LAMBDA_BODY_BARE_LET, // `{p} -> let x = ...` — wrap in `do` to use let-bindings (#221)
    P0122_MATCH_NEEDS_IS, // `match x { case ... }` or `match x case ...` — Mezze uses `match x is` + indented arms
    P0123_LAMBDA_INLINE_RETURN_TYPE, // `{ p } -> Ret do body` — return-type annotation must go on the binding, not inline
    P0124_PIPE_PLACEHOLDER_AS_RECEIVER, // `x |> @.method {}` — `@` may be an argument value, not a method/field receiver
    P0125_INTEGER_LITERAL_OUT_OF_RANGE, // `9223372036854775808` — literal exceeds the 64-bit integer range
    P0126_DECL_NAME_ON_NEW_LINE, // `let\nname` / `type\nName` — the name must be on the same line as the declaration keyword
    P0127_HAVING_NEEDS_BINDING, // `having Int` — must bind a type variable: `having 'elem = Int`
    P0128_INVALID_LET_BINDING_TARGET, // `let 3 = x` — a let binds a name, `_`, or a `{ … }` destructuring pattern, not a literal/expression
    P0129_RECORD_FIELD_USES_EQUALS, // `{ a: 1 }` in value position — record LITERAL fields bind with `=` (`{ a = 1 }`); `:` annotates a type
    P0130_PIPE_MISSING_RHS, // `a |>` with nothing (or nothing valid) after it — the pipe needs a right-hand expression
    P0131_NESTING_TOO_DEEP, // `((((((…))))))` — expression/type nesting exceeds the depth limit (guards against a parser stack overflow)
    P0132_PARSER_LIMIT_EXCEEDED, // the parser hit its deterministic work budget on this input (pathological or malformed source) — a clean bail-out instead of ever hanging

    // ─── Typechecker: type mismatches (T02xx) ────────────────────────────
    T0201_TYPE_MISMATCH,
    T0202_RECORD_MISSING_FIELDS,
    T0203_RECORD_EXTRA_FIELDS,
    T0204_VARIANT_CONSTRUCTOR_ARITY,
    T0205_FIELD_NOT_FOUND,
    T0206_METHOD_NOT_FOUND,
    T0207_INFINITE_TYPE,
    T0208_LITERAL_PATTERN_ON_VARIANT,
    T0209_ABILITY_AS_VALUE,
    T0210_ANNOTATION_VS_BODY_MISMATCH,
    T0211_CALLBACK_PARAM_NAMES_MISMATCH, // `f = { acc, i } -> ...` where sig wants `{ acc, x }` — lambda param names ARE record destructuring, must match the caller's field names
    T0212_NOT_A_NEWTYPE, // `Foo.(x)` where Foo isn't a newtype (ability, effect, variant, record, primitive, or unknown)
    T0213_UNKNOWN_TYPE, // an uppercase type name in a type position resolves to no declaration/builtin (e.g. `{ val: Gadget }` with no `type Gadget`)
    T0214_TYPECHECK_LIMIT_EXCEEDED,
    T0215_AMBIGUOUS_ABILITY_TYPE, // an ability method whose type is decided by neither the parameters nor the result — no call site can pin it, so it could never dispatch // the typechecker hit an internal recursion/work limit on this program (pathological or malformed) — a clean bail-out instead of ever crashing/hanging
    T0216_AMBIGUOUS_DISPATCH, // two same-priority impls apply to this call and the receiver's type does not choose between them

    // ─── Typechecker: inference & binding (T03xx) ────────────────────────
    T0301_UNBOUND_TYPE_VAR,
    T0302_CANNOT_APPLY_NON_FUNCTION,
    T0303_OPEN_RECORD_FIELD_MISSING,
    T0304_DUPLICATE_RECORD_FIELD, // `{ a = 1, a = 2 }` — a field name appears more than once
    T0305_DUPLICATE_LAMBDA_PARAM, // `{ x, x } -> x` — a lambda parameter name appears more than once
    T0306_SHADOWED_BINDING_IN_SCOPE, // `{x} -> do { let x = 1; ... }` — a `let` reuses a name already bound in the same function scope

    // ─── Validator: abilities (V04xx) ────────────────────────────────────
    V0401_ABILITY_NOT_DEFINED,
    V0402_ABILITY_NOT_SATISFIED,
    V0403_DUPLICATE_ABILITY_DECL,
    V0404_IMPL_MISSING_METHOD,
    V0405_IMPL_EXTRA_METHOD,
    V0406_ORPHAN_INSTANCE,
    V0407_OVERLAPPING_INSTANCE,
    V0408_NO_IMPLEMENTATION,
    V0409_ABILITY_USED_AS_VALUE,
    V0410_ABILITY_LEVEL_CONSTRAINT_FORBIDDEN,
    V0411_POLYMORPHIC_EFFECT_IN_ABILITY_OP,
    V0412_PERFORMED_MARKER_IN_ABILITY_OP,
    V0413_MISSING_ASSOCIATED_TYPE_BINDING,
    V0414_EXTRA_ASSOCIATED_TYPE_BINDING,
    V0415_IMPL_OP_MUST_BE_LAMBDA,
    V0416_ABILITY_OP_RECORD_PARAM_REQUIRED,
    V0417_TYPE_VAR_FORBIDDEN_IN_CONCRETE_IMPL,
    V0418_INHERENT_IMPL_ON_UNDEFINED_TYPE,
    V0419_IMPL_METHOD_FIELD_CONFLICT,
    V0420_ABILITY_METHOD_SHADOWS_BUILTIN_OPERATOR,
    V0421_IMPL_EFFECT_MISMATCH, // impl op effects don't satisfy the ability op's effect contract

    // ─── Validator: effects (V05xx) ──────────────────────────────────────
    V0501_EFFECT_NOT_HANDLED,
    V0502_TOP_LEVEL_PERFORM,
    V0504_HERMETIC_EFFECT_CONFLICT,
    V0505_EFFECT_NOT_DEFINED,
    V0506_SCOPED_EFFECT_INIT_FINALIZE_REQUIRED,
    V0507_HANDLER_DUPLICATE,
    V0508_HANDLER_ON_PURE_EXPR,
    V0509_HANDLER_EFFECT_MISMATCH,
    V0510_HANDLER_UNNECESSARY,

    // ─── Validator: patterns (V06xx) ─────────────────────────────────────
    V0601_NON_EXHAUSTIVE_MATCH,
    V0602_NON_EXHAUSTIVE_RECORD_PATTERN,
    V0603_UNREACHABLE_PATTERN,
    V0604_CONSTRUCTOR_NO_PAYLOAD,
    V0605_OR_PATTERN_BINDS_DIFFERENT_VARS,
    V0606_NON_LINEAR_PATTERN,

    // ─── Validator: bindings (V07xx) ─────────────────────────────────────
    V0701_DUPLICATE_DECLARATION,
    V0702_UNDEFINED_VARIABLE,
    V0703_UNKNOWN_FIELD,
    V0704_UNKNOWN_CONSTRUCTOR,
    V0705_TYPE_ARITY_MISMATCH,
    V0707_CONSTRUCTOR_MUST_BE_UPPERCASE,
    V0708_SPREAD_ONLY_RECORD_FORBIDDEN,
    V0709_CIRCULAR_TYPE_DEPENDENCY,
    V0710_TOP_LEVEL_EXPRESSION, // a bare expression at the top level of a program (only decls allowed); fine in the REPL

    // ─── Typechecker: misc syntax/desugar (T04xx) ────────────────────────
    T0401_UNKNOWN_OPERATOR,
    T0402_PIPE_PLACEHOLDER_MISUSE,

    // ─── Module loader (M08xx) ───────────────────────────────────────────
    M0801_MODULE_NOT_FOUND,
    M0802_CIRCULAR_DEPENDENCY,
    M0803_NOT_EXPORTED,
    M0804_MODULE_HAS_ERRORS,

    // ─── Validator: structural (V09xx) ────────────────────────────────────
    V0901_STRUCTURAL_VALIDATION,

    // ─── Internal compiler errors (I00xx) ────────────────────────────────
    I0001_UNKNOWN_TYPECHECK_FAILURE,
    I0002_PIPELINE_NO_TYPED_MODULE,
    I0003_INTERNAL_EMIT_FAILURE,
}

/** Format an [ErrorCode] for display in the error header, e.g. `T0201`. */
fun ErrorCode.display(): String = when (this) {
    ErrorCode.UNCATEGORIZED -> "E0000"  // visible placeholder during migration
    else -> name.substringBefore('_')
}
