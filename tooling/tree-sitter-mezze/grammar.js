/**
 * Tree-sitter grammar for the Mezze language.
 *
 * Source of truth: parser/src/main/kotlin/com/mezze/language/parser/
 *   internal/Lexer.kt              — tokens
 *   internal/ExpressionParsers.kt  — expression precedence + structure
 *   internal/DeclarationParsers.kt — top-level decls
 *   internal/TypeParsers.kt        — type annotations + effect rows
 *
 * Do NOT consult tooling/vscode-extension/extension/syntaxes/mezze.tmLanguage.json
 * — the TextMate grammar is broken (`pub` recolors the following keyword) and
 * exists only until this grammar replaces it.
 *
 * Precedence ladder (low → high) mirrors ExpressionParsers.kt 1482–2076:
 *
 *   1  ||
 *   2  &&
 *   3  |>
 *   4  == != < > <= >=
 *   5  + -
 *   6  * / %
 *   7  unary - !
 *   8  . (member access)
 *   9  :: (module qualification, atomic)
 */

const PREC = {
  or: 1,
  and: 2,
  pipe: 3,
  compare: 4,
  add: 5,
  mul: 6,
  unary: 7,
  member: 8,
  qualified: 9,
  // Used to force the lambda-vs-record decision in favor of lambda when an
  // arrow is present (see ExpressionParsers.kt:13 — `lookAheadForArrowAfterBrace`).
  lambda: 10,
  // Variant construction (`Some {val}`) binds tighter than method dispatch
  // so the receiver of `.method` is the whole constructor.
  call: 11,
};

module.exports = grammar({
  name: 'mezze',

  // Newlines as plain whitespace. Mezze isn't truly newline-significant —
  // top-level decls terminate by syntactic balance (close-brace, end of
  // expression). If we later need newline-as-terminator (e.g. for do-blocks
  // with stmt sequences), we'll add an external scanner that emits a
  // virtual NEWLINE token only in those contexts. For now, simpler is
  // better: \s eats everything, the corpus tests will catch the gaps.
  extras: $ => [
    /\s/,
    $.line_comment,
    $.doc_comment,
  ],

  word: $ => $.lower_identifier,

  // GLR-style conflicts. Tree-sitter explores all listed parses in parallel
  // and picks whichever yields a complete tree. The disambiguator is always
  // a token further right (`->` for lambda, `=` for let, etc.).
  conflicts: $ => [
    // {x} could be a lambda's parameter list or a record-literal punning
    // field. Resolved by whether `->` follows the closing `}`.
    [$.lambda_parameter, $.record_field],
    [$.lambda_parameters, $.record_expression],
    // `Foo {…}` is a variant constructor (e.g. `Some {val = 1}`) but
    // `f {…}` is a function call — the `Foo` upper_identifier alone could
    // be a no-arg variant or a callee. Resolved by lookahead.
    [$.function_call, $.variant_constructor_expression],
    // `use a::b::c` — the parser must decide whether `a::b::` continues
    // a module_path or starts the use_declaration's inlined group path.
    // GLR explores both; the group form wins when `{` follows.
    [$.use_declaration, $.module_path],
  ],

  rules: {
    // Decls are separated implicitly by whitespace (extras eat newlines).
    // No explicit separator needed — the inner _top_level rules
    // self-terminate at their closing tokens (`= expr` for let/impl op,
    // `}` for impl/ability/effect bodies, etc.).
    source_file: $ => repeat($._top_level_with_metadata),

    _top_level_with_metadata: $ => seq(
      optional($.metadata_block),
      $._top_level,
    ),

    // ===== Comments + metadata =====

    // Order matters in tree-sitter token resolution: longer tokens (##, #{)
    // beat shorter (#). Doc comments must precede line comments because
    // they share the `#` prefix.
    doc_comment: _ => token(seq('##', /[^\n]*/)),

    // Line comment: `#` followed by anything except another `#` (doc) or
    // `{` (metadata). The negative-set `[^#{\n]` keeps these tokens disjoint.
    line_comment: _ => token(seq('#', /[^#{\n][^\n]*/)),

    // `#{key = value, …}` — compiler-meaningful annotation attached to the
    // following declaration. Distinct from a plain `# comment` because the
    // following char is `{`. Highlighted as @attribute (not @comment).
    metadata_block: $ => seq(
      '#{',
      sepBy(',', $.metadata_entry),
      '}',
    ),

    metadata_entry: $ => seq(
      field('key', $.lower_identifier),
      ':',
      field('value', $._expression),
    ),

    // ===== Top-level declarations =====

    _top_level: $ => choice(
      $.use_declaration,
      $.let_declaration,
      $.type_declaration,
      $.effect_declaration,
      $.ability_declaration,
      $.impl_declaration,
    ),

    // Public visibility — its OWN node so highlights.scm can tag it as
    // @keyword.modifier without ever wrapping the following keyword.
    // This is the entire fix for the broken-TextMate `pub` cascade.
    visibility_modifier: _ => 'pub',

    // ----- use -----
    // Split by trailing form so the path-vs-group choice isn't a shift/reduce
    // tug-of-war. The path is a `::`-separated chain of lowercase identifiers
    // (matching parser: stdlib::collections::seq).
    use_declaration: $ => seq(
      optional($.visibility_modifier),
      'use',
      choice(
        // Group form: `use a::b::{c, d}`. We inline the path here so the
        // greedy `module_path` rule never competes with the `::{` boundary.
        seq(
          field('path', alias(repeat1(seq($.lower_identifier, '::')), $.module_path)),
          field('items', $.use_group),
        ),
        seq(field('path', $.module_path), 'as', field('alias', $.lower_identifier)),
        field('path', $.module_path),
      ),
    ),

    // Module path. Greedy `::`-chain of lower identifiers. Only used by
    // non-group `use` forms; the group form inlines the path (see above).
    module_path: $ => prec.left(sepBy1('::', $.lower_identifier)),

    use_group: $ => seq(
      '{',
      sepBy1(',', $.use_item),
      '}',
    ),

    use_item: $ => seq(
      field('name', choice($.lower_identifier, $.upper_identifier)),
      optional(seq('as', field('alias', choice($.lower_identifier, $.upper_identifier)))),
    ),

    // ----- let -----
    let_declaration: $ => seq(
      optional($.visibility_modifier),
      'let',
      field('pattern', $._pattern),
      optional(seq(':', field('type', $._signature_type))),
      '=',
      field('body', $._expression),
    ),

    // ----- type -----
    type_declaration: $ => seq(
      optional($.visibility_modifier),
      'type',
      field('name', $.upper_identifier),
      repeat(field('parameter', $.type_variable)),
      '=',
      field('definition', $._type_definition),
    ),

    _type_definition: $ => choice(
      $.variant_type,
      $._type,
    ),

    variant_type: $ => seq(
      '|',
      sepBy1(',', $.variant_constructor),
      '|',
    ),

    variant_constructor: $ => seq(
      field('name', $.upper_identifier),
      optional(field('payload', $.record_type)),
    ),

    // ----- effect -----
    effect_declaration: $ => seq(
      'effect',
      field('name', $.upper_identifier),
      optional(seq('having', sepBy1(',', $.type_variable))),
      'where',
      '{',
      sepBy(',', $.effect_operation),
      '}',
    ),

    effect_operation: $ => seq(
      field('name', $.lower_identifier),
      ':',
      // Plain _type — effect-op signatures don't use `with` constraints in
      // the stdlib. If a real example surfaces, add a dedicated rule that
      // wraps the operation rather than allowing _signature_type here (the
      // comma between ops is ambiguous against with-clause commas).
      field('type', $._type),
    ),

    // ----- ability -----
    ability_declaration: $ => seq(
      'ability',
      field('name', $.upper_identifier),
      repeat(field('parameter', $.type_variable)),
      'for',
      field('self', $.type_variable),
      'where',
      '{',
      sepBy(',', $.ability_operation),
      '}',
    ),

    ability_operation: $ => seq(
      field('name', $.lower_identifier),
      ':',
      // Plain _type — same constraint as effect_operation: with-clause
      // commas can't disambiguate against op-separator commas inside
      // the where { } block.
      field('type', $._type),
    ),

    // ----- impl (ability or effect) -----
    // Effect impls have an optional `{config}` record after the performer
    // name (e.g. `impl Log for ConsoleLogger {} where …`). Ability impls
    // can take type arguments on the ability (`impl Into Str for Bool`)
    // and use a full _type for the target (`impl Eq for List Int`).
    // We unify them here; the typechecker distinguishes by looking up the
    // name in the ability/effect registry.
    impl_declaration: $ => choice(
      // Inherent impl: `impl Bool where { … }` — adds methods directly to
      // a type, no ability/effect involved. Distinguished by no `for`.
      seq(
        'impl',
        field('target', $._type),
        'where',
        '{',
        sepBy(',', $.impl_operation),
        '}',
      ),
      // Ability or effect impl: `impl Eq for Bool where { … }`. Ability
      // names can carry type arguments (`impl Into Str for Bool …`).
      // Effect impls have an optional performer-config record after the
      // target (`impl Log for ConsoleLogger {} where …`). Unified here;
      // the typechecker disambiguates via name lookup.
      seq(
        'impl',
        field('name', $.upper_identifier),
        repeat(field('argument', $._type_atom)),
        'for',
        field('target', $._type),
        optional(field('performer_args', $.record_type)),
        optional($._having_clause),
        'where',
        '{',
        sepBy(',', $.impl_operation),
        '}',
      ),
    ),

    impl_operation: $ => seq(
      field('name', $.lower_identifier),
      '=',
      field('body', $._expression),
    ),

    type_binding: $ => seq(
      field('var', $.type_variable),
      '=',
      field('type', $._type),
    ),

    // ===== Patterns =====

    _pattern: $ => choice(
      $.variable_pattern,
      $.wildcard_pattern,
      $.literal_pattern,
      $.record_pattern,
      $.variant_pattern,
      $.sequence_pattern,
    ),

    variable_pattern: $ => $.lower_identifier,
    wildcard_pattern: _ => '_',
    literal_pattern: $ => choice($.integer_literal, $.string_literal, $.bool_literal),

    record_pattern: $ => seq(
      '{',
      sepBy(',', $.record_pattern_field),
      '}',
    ),

    record_pattern_field: $ => choice(
      // pun: {x, y} means {x = x, y = y}
      field('name', $.lower_identifier),
      seq(
        field('name', $.lower_identifier),
        '=',
        field('pattern', $._pattern),
      ),
    ),

    variant_pattern: $ => seq(
      field('constructor', $.upper_identifier),
      optional(field('payload', $.record_pattern)),
    ),

    sequence_pattern: $ => seq(
      '[',
      sepBy(',', choice($._pattern, $.rest_pattern)),
      ']',
    ),

    rest_pattern: $ => seq('...', optional($.lower_identifier)),

    // ===== Types =====

    // `_type` is the everywhere-legal type form (no `with`-clauses).
    // `_signature_type` adds `constrained_type` and is used ONLY at
    // top-level binding positions (let, ability/effect op signatures,
    // impl operations) — places where `with Eq 'a` syntactically binds
    // the whole signature, not a nested record field.
    _type: $ => choice(
      $.type_variable,
      $.named_type,
      $.type_application,
      $.function_type,
      $.record_type,
    ),

    _signature_type: $ => choice(
      $._type,
      $.constrained_type,
    ),

    // 'a — leading single quote distinguishes from upper_identifier in type
    // position.
    type_variable: _ => /'[a-zA-Z_][a-zA-Z0-9_]*/,

    named_type: $ => $.upper_identifier,

    type_application: $ => prec.left(PREC.call, seq(
      field('constructor', $.upper_identifier),
      repeat1(field('argument', $._type_atom)),
    )),

    _type_atom: $ => choice(
      $.type_variable,
      $.named_type,
      $.record_type,
      seq('(', $._type, ')'),
    ),

    function_type: $ => prec.right(seq(
      field('parameters', $.record_type),
      '->',
      field('return_type', $._type),
      optional(field('effects', $.effect_row)),
    )),

    record_type: $ => seq(
      '{',
      sepBy(',', $.record_type_field),
      '}',
    ),

    record_type_field: $ => seq(
      field('name', $.lower_identifier),
      ':',
      // _type (NOT _signature_type) — constrained types `with Eq 'a` only
      // bind at the top-level signature position, not inside record-type
      // fields. This also breaks the comma ambiguity between with-clause
      // separators and record-field separators.
      field('type', $._type),
    ),

    // Constrained type: `{Int} -> Str with Eq 'a, Show 'a`. `with` always
    // binds the whole signature, never just the return. Inner type is
    // _type (which excludes constrained_type itself, preventing recursive
    // re-application of `with`).
    constrained_type: $ => prec(1, seq(
      $._type,
      'with',
      sepBy1(',', $.ability_constraint),
    )),

    ability_constraint: $ => prec.left(seq(
      field('ability', $.upper_identifier),
      repeat1(field('argument', $._type_atom)),
      optional(seq('having', sepBy1(',', $.type_binding))),
    )),

    // <Effect1, Effect2, ...> or <*> or <...> — appears ONLY after a function
    // return type. See TypeParsers.kt:169–267. This is why it can coexist
    // with comparison `<` in expressions: the function_type rule consumes it.
    effect_row: $ => seq(
      '<',
      choice(
        '*',
        '...',
        sepBy(',', $.effect_reference),
      ),
      '>',
    ),

    // Effect reference inside an effect_row `<L1, L2, ...>`. Each ref can
    // be a bare name (`Log`) or a name with `having` type bindings
    // (`State having 'state = Int`). The `having` list ends at the next
    // top-level `,` or `>`. We require type_binding to be left-greedy so
    // the comma-list terminates as soon as it hits something that doesn't
    // start a new type_binding.
    effect_reference: $ => prec.left(seq(
      field('name', $.upper_identifier),
      optional($._having_clause),
    )),

    // Grouped so the `having ...` list has its own associativity boundary,
    // separate from the outer effect_row comma list.
    _having_clause: $ => prec.left(seq(
      'having',
      sepBy1(',', $.type_binding),
    )),

    // ===== Expressions =====

    _expression: $ => choice(
      $.lambda_expression,
      $.binary_expression,
      $.unary_expression,
      $.pipe_expression,
      $.member_access,
      $.method_call,
      $.function_call,
      $.qualified_reference,
      $.if_expression,
      $.match_expression,
      $.do_expression,
      $.perform_expression,
      $.record_expression,
      $.sequence_expression,
      $.variant_constructor_expression,
      $.pipe_placeholder,
      $.variable,
      $._literal,
      seq('(', $._expression, ')'),
    ),

    // {x} -> body  vs  {x = 1}: tree-sitter handles via dynamic prec.
    // The `->` after `}` makes lambda the strictly higher-precedence choice.
    lambda_expression: $ => prec(PREC.lambda, seq(
      field('parameters', $.lambda_parameters),
      '->',
      field('body', $._expression),
    )),

    lambda_parameters: $ => seq(
      '{',
      sepBy(',', $.lambda_parameter),
      '}',
    ),

    lambda_parameter: $ => seq(
      field('name', $.lower_identifier),
      optional(seq(':', field('type', $._type))),
    ),

    binary_expression: $ => choice(
      ...[
        ['||', PREC.or],
        ['&&', PREC.and],
        ['==', PREC.compare],
        ['!=', PREC.compare],
        ['<',  PREC.compare],
        ['>',  PREC.compare],
        ['<=', PREC.compare],
        ['>=', PREC.compare],
        ['+',  PREC.add],
        ['-',  PREC.add],
        ['*',  PREC.mul],
        ['/',  PREC.mul],
        ['%',  PREC.mul],
      ].map(([op, p]) => prec.left(p, seq(
        field('left', $._expression),
        field('operator', op),
        field('right', $._expression),
      ))),
    ),

    unary_expression: $ => prec.right(PREC.unary, seq(
      field('operator', choice('-', '!')),
      field('operand', $._expression),
    )),

    // value |> f {x = @}
    pipe_expression: $ => prec.left(PREC.pipe, seq(
      field('left', $._expression),
      '|>',
      field('right', $._expression),
    )),

    pipe_placeholder: _ => '@',

    member_access: $ => prec.left(PREC.member, seq(
      field('receiver', $._expression),
      '.',
      field('field', $.lower_identifier),
    )),

    method_call: $ => prec.left(PREC.member, seq(
      field('receiver', $._expression),
      '.',
      field('method', $.lower_identifier),
      field('arguments', $.record_expression),
    )),

    // f {args}
    function_call: $ => prec(PREC.call, seq(
      field('callee', choice($.variable, $.qualified_reference, $.upper_identifier)),
      field('arguments', $.record_expression),
    )),

    // module::name
    qualified_reference: $ => prec(PREC.qualified, seq(
      field('module', $.lower_identifier),
      '::',
      field('name', choice($.lower_identifier, $.upper_identifier)),
    )),

    if_expression: $ => seq(
      'if',
      field('condition', $._expression),
      'then',
      field('then', $._expression),
      'else',
      field('else', $._expression),
    ),

    match_expression: $ => prec.right(seq(
      'match',
      field('scrutinee', $._expression),
      'is',
      // arms are comma-separated; trailing comma optional. Using
      // prec.right pushes ambiguity (where does the match end?) toward
      // continuing to consume arms — the comma is the only safe terminator.
      sepBy1(',', $.match_arm),
    )),

    match_arm: $ => seq(
      field('pattern', $._pattern),
      optional(seq('if', field('guard', $._expression))),
      '->',
      field('body', $._expression),
    ),

    // do { expr1; expr2; final_expr } — sequence of expressions where the
    // last is the result. The parser allows newline-or-semicolon as
    // separator; we accept either for tolerance (the typechecker enforces
    // exactly what's allowed semantically).
    do_expression: $ => seq(
      'do',
      '{',
      sepBy(optional(';'), $._expression),
      '}',
    ),

    perform_expression: $ => seq(
      'perform',
      sepBy1(',', $.handler),
      'in',
      field('body', $._expression),
    ),

    handler: $ => seq(
      field('name', $.upper_identifier),
      optional(field('args', $.record_expression)),
    ),

    record_expression: $ => seq(
      '{',
      sepBy(',', $.record_field),
      '}',
    ),

    record_field: $ => choice(
      // pun
      field('name', $.lower_identifier),
      seq(
        field('name', $.lower_identifier),
        '=',
        field('value', $._expression),
      ),
    ),

    sequence_expression: $ => seq(
      '[',
      sepBy(',', $._expression),
      ']',
    ),

    variant_constructor_expression: $ => prec(PREC.call, seq(
      field('constructor', $.upper_identifier),
      optional(field('payload', $.record_expression)),
    )),

    variable: $ => $.lower_identifier,

    // ===== Literals =====

    _literal: $ => choice(
      $.integer_literal,
      $.float_literal,
      $.string_literal,
      $.bool_literal,
      $.unit_literal,
    ),

    integer_literal: _ => /-?\d+/,
    float_literal: _ => /-?\d+\.\d+/,
    bool_literal: _ => choice('True', 'False'),
    unit_literal: _ => seq('(', ')'),

    string_literal: $ => seq(
      '"',
      repeat(choice(
        $.string_content,
        $.escape_sequence,
        $.string_interpolation,
      )),
      '"',
    ),

    string_content: _ => /[^"\\$]+/,

    escape_sequence: _ => token(seq('\\', /./)),

    // $(expr) inside "...".
    string_interpolation: $ => seq(
      '$(',
      $._expression,
      ')',
    ),

    // ===== Identifiers =====

    lower_identifier: _ => /[a-z_][a-zA-Z0-9_]*/,
    upper_identifier: _ => /[A-Z][a-zA-Z0-9_]*/,
  },
});

/**
 * sepBy(sep, rule) — zero or more `rule` separated by `sep`, optional
 * trailing separator allowed. Matches the parser's permissive comma handling.
 */
function sepBy(sep, rule) {
  return optional(seq(rule, repeat(seq(sep, rule)), optional(sep)));
}

function sepBy1(sep, rule) {
  return seq(rule, repeat(seq(sep, rule)), optional(sep));
}
