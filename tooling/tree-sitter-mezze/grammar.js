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

  // Indentation-aware block termination is handled by the external scanner in
  // src/scanner.c. It emits zero-width INDENT / NEWLINE / DEDENT tokens that
  // bound `match … is` arm lists and `do` blocks — Mezze terminates those by
  // indentation (an arm/statement continues only while the next line is
  // indented at/past the first item's column). Elsewhere newlines stay plain
  // whitespace (see `extras`), so the rest of the grammar is unaffected.
  externals: $ => [
    $._newline,
    $._indent,
    $._dedent,
  ],

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
    // type_binding's value `'a = T` — T could be a `_type` (resolved)
    // or the start of a function_type. Explore both.
    [$._type, $.function_type],
    // A record `{…}` at the start of a type could be a standalone record_type
    // OR the parameter list of a function_type (`{p} with C -> Ret`). The
    // `with`/`->` that follows disambiguates, but the parser must keep both
    // live until it sees them — otherwise it reduces record_type early and the
    // trailing `with`/`->` dangles. Same for a bare type_variable/named_type as
    // the function_type parameter.
    [$.record_type, $.function_type],
    // `having 'a = T, ...` inside an effect_reference vs ability_constraint:
    // after the first type_binding, the comma could continue the binding
    // list OR terminate the effect/constraint and start the next sibling.
    // GLR picks the longer match (continue bindings) when `'` follows the
    // comma, since type_binding starts with a type_variable.
    [$.having_clause],
    [$.having_clause, $.effect_reference],
    [$.having_clause, $.ability_constraint],
    // A metadata value that is a bare literal/identifier could reduce directly
    // or via the _metadata_value wrapper; harmless, resolve either way.
    [$._metadata_value, $._literal],
  ],

  rules: {
    // Decls are separated implicitly by whitespace (extras eat newlines).
    // No explicit separator needed — the inner _top_level rules
    // self-terminate at their closing tokens (`= expr` for let/impl op,
    // `}` for impl/ability/effect bodies, etc.).
    source_file: $ => repeat($._top_level_with_metadata),

    _top_level_with_metadata: $ => seq(
      repeat($.metadata_block),
      $._top_level,
    ),

    // ===== Comments + metadata =====

    // Comment syntax mirrors the parser (Lexer.lexComment): a `#` / `##` MUST
    // be followed by a space/tab or end-of-line; `#foo` / `##foo` are typos,
    // not comments. Longer tokens (`##`, `#{`) win over `#`.

    // Doc comment: `##` optionally followed by a space/tab and text. The
    // trailing text is optional so a lone `##` at end of line is a valid empty
    // doc comment; `##foo` (no space) is NOT matched (→ rejected, matching the
    // parser). The newline itself is `extras` whitespace, not part of the token.
    doc_comment: _ => token(seq('##', optional(/[ \t][^\n]*/))),

    // Line comment: `#` optionally followed by a space/tab and text. A lone `#`
    // is a valid empty comment; `#foo` (no space) is not matched. `##`/`#{` win
    // as longer tokens.
    line_comment: _ => token(seq('#', optional(/[ \t][^\n]*/))),

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
      field('value', $._metadata_value),
    ),

    // A metadata value is a small, self-contained set — literals, identifiers,
    // sequences, and a NESTED metadata record (`#{config: {timeout: 5000}}`).
    // It deliberately does NOT route through `_expression`, so the metadata `{`
    // (a `key: value` record) never competes with a real record_expression
    // (`key = value`) — that competition is what made a shared rule destabilize
    // the whole `{` disambiguation.
    _metadata_value: $ => choice(
      $._literal,
      $.string_literal,
      $.lower_identifier,
      $.upper_identifier,
      $.sequence_expression,
      $.metadata_record,
    ),

    metadata_record: $ => seq(
      '{',
      sepBy(',', $.metadata_entry),
      '}',
    ),

    // ===== Top-level declarations =====

    // Top-level forms. Mezze allows bare expressions at module root —
    // they're wrapped into the implicit `__entry__` function. Parser tests
    // routinely use this for short examples: `match xs is …`, `perform
    // Console in …`, etc.
    _top_level: $ => choice(
      $.use_declaration,
      $.let_declaration,
      $.type_declaration,
      $.newtype_declaration,
      $.effect_declaration,
      $.ability_declaration,
      $.impl_declaration,
      $._expression,
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

    // ----- newtype ----
    // `pub newtype Promise 'a = Promise __NativePromise` — wraps an
    // opaque native handle into a named type. Body is a single
    // constructor (upper_identifier) followed by zero or more type atoms
    // (usually a single native-marker like __NativePromise).
    // newtype Foo 'a = Bar __NativeBar. Right-associative so the trailing
    // `repeat(_type_atom)` is greedy — without prec.right, a following
    // top-level expression starting with `{` looks like another type
    // argument.
    newtype_declaration: $ => prec.right(seq(
      optional($.visibility_modifier),
      'newtype',
      field('name', $.upper_identifier),
      repeat(field('parameter', $.type_variable)),
      '=',
      field('constructor', $.upper_identifier),
      // The wrapped payload is either a run of plain type atoms
      // (`Pair Int String`) OR a single function type
      // (`Handler { x: Int } -> String`, optionally with an effect row). The
      // two disambiguate on the presence of `->`.
      optional(choice(
        field('payload', $.function_type),
        repeat1(field('argument', $._type_atom)),
      )),
    )),

    _type_definition: $ => choice(
      $.variant_type,
      $._type,
    ),

    variant_type: $ => seq(
      '|',
      // `| Red, Green, Blue, ... |` — trailing `...` allowed as a
      // "non-exhaustive" placeholder (see log.mz LogLevel).
      sepBy1(',', choice($.variant_constructor, $.variant_ellipsis)),
      '|',
    ),

    variant_ellipsis: _ => '...',

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
        // A qualified ability/effect name: `Eq`, `log::Log`,
        // `std::effects::log::Log`. The module prefix is a run of
        // `lower_identifier ::` — the trailing `::` after a lowercase segment
        // is what tells the parser this is a module path and not an argument.
        optional(field('module_path', repeat1(seq($.lower_identifier, '::')))),
        field('name', $.upper_identifier),
        repeat(field('argument', $._type_atom)),
        'for',
        field('target', $._type),
        optional(field('performer_args', $.record_type)),
        optional($.having_clause),
        'where',
        '{',
        sepBy(',', $.impl_operation),
        '}',
      ),
    ),

    // Impl operations can optionally carry a type annotation:
    //   eq = {it, r} -> ...
    //   encode : {val: Int} -> Str = {val} -> ...
    // The parser stores both shapes as ParsedImplOp.
    impl_operation: $ => seq(
      field('name', $.lower_identifier),
      optional(seq(':', field('type', $._type))),
      '=',
      field('body', $._expression),
    ),

    type_binding: $ => prec.left(seq(
      field('var', $.type_variable),
      '=',
      field('type', $._type),
    )),

    // ===== Patterns =====

    _pattern: $ => choice(
      $.variable_pattern,
      $.wildcard_pattern,
      $.literal_pattern,
      $.record_pattern,
      $.variant_pattern,
      $.sequence_pattern,
    ),

    // `pat1 | pat2 | pat3` — alternative patterns in a match arm. Falls
    // through to a single _pattern when there's no `|`.
    _pattern_or: $ => choice(
      $._pattern,
      $.or_pattern,
    ),

    or_pattern: $ => prec.left(seq(
      $._pattern,
      repeat1(seq('|', $._pattern)),
    )),

    variable_pattern: $ => $.lower_identifier,
    wildcard_pattern: _ => '_',
    literal_pattern: $ => choice($.integer_literal, $.float_literal, $.string_literal, $.bool_literal),

    record_pattern: $ => seq(
      '{',
      sepBy(',', $.record_pattern_field),
      // Open record pattern: `{ name, ... }` matches a record with at least
      // these fields, ignoring the rest.
      optional(seq(optional(','), '...')),
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
      $.sequence_type,
      // Parenthesized type — used to group a compound type where only an atom
      // is otherwise allowed, e.g. a `having` binding value
      // `having 'item = (Option [Int])` or `(Result (Option String) Int)`.
      $.parenthesized_type,
    ),

    parenthesized_type: $ => seq('(', $._type, ')'),

    // [T] — sequence (list) of T. Used freely in type position:
    //   pub let to_seq : { s: Set 'a } -> ['a] = ...
    //   methods: [Method]
    //   list_directory: ... -> Result [Str] FileSystemError
    sequence_type: $ => seq('[', $._type, ']'),

    // _signature_type is the type form valid at top-level signature
    // positions. Previously distinct from _type to host `constrained_type`,
    // but `with` now lives inside function_type itself (right after the
    // params record). Kept as an alias so the grammar's intent stays
    // documented at let/effect-op/ability-op call sites — and so a future
    // top-level-only constraint form can be added here without churning
    // every reference.
    _signature_type: $ => $._type,

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
      $.sequence_type,
      seq('(', $._type, ')'),
    ),

    // Function type. `with`-clause sits AFTER the return type:
    //   { x: 'a } -> Str with Eq 'a, Show 'a
    //   { x: Int } -> Bool <Log>
    //   { c: 'c } -> 'item with Container 'c having 'item = Int
    //   'a -> 'b                  -- single bare param (no record wrapper)
    //   Int -> Int                -- bare concrete param
    // Effect row goes between return type and `with`. Constraints chain
    // with `,` and each may carry `having 'assoc = T`.
    // Canonical Mezze function type: `{params} with C1, C2 -> Ret <effects>`.
    // The `with`-clause sits BEFORE the arrow, right after the parameter record
    // — the parser enforces this (P0120 rejects `with` after the return type).
    function_type: $ => prec.right(seq(
      field('parameters', choice($.record_type, $.type_variable, $.named_type)),
      optional(seq('with', sepBy1(',', $.ability_constraint))),
      '->',
      field('return_type', $._type),
      optional(field('effects', $.effect_row)),
    )),

    record_type: $ => seq(
      '{',
      sepBy(',', $.record_type_field),
      // Open record: `{ a: Int, ... }` — the `...` marks the row as open
      // (extra fields allowed). Only valid trailing the field list.
      optional(seq(optional(','), field('rest', $.record_type_rest))),
      '}',
    ),

    record_type_rest: _ => '...',

    record_type_field: $ => seq(
      field('name', $.lower_identifier),
      ':',
      // _type (NOT _signature_type) — constrained types `with Eq 'a` only
      // bind at the top-level signature position, not inside record-type
      // fields. This also breaks the comma ambiguity between with-clause
      // separators and record-field separators.
      field('type', $._type),
    ),

    // A module-qualified Upper name: `Log`, `log::Log`,
    // `std::effects::log::Log`. Leading segments are lowercase module names, the
    // final segment is the Upper name. Used where an ability / effect name may
    // be qualified. prec.qualified keeps the `::` chain binding tightly so it
    // isn't mistaken for a `qualified_reference` (whose leaf may be lowercase).
    qualified_upper_name: $ => prec(PREC.qualified, seq(
      repeat(seq($.lower_identifier, '::')),
      $.upper_identifier,
    )),

    ability_constraint: $ => prec.left(seq(
      field('ability', $.qualified_upper_name),
      repeat1(field('argument', $._type_atom)),
      // `having 'item = Int, 'index = String` — reuse the having_clause node
      // (which already carries the GLR conflicts against ability_constraint /
      // effect_reference), so the comma after a binding can EITHER continue the
      // binding list OR end this constraint and start the next in a `with C1,
      // C2` list. The parser resolves it greedily (continue bindings); the
      // declared conflict lets tree-sitter explore both.
      optional($.having_clause),
    )),

    // <Effect1, Effect2, ...> or <*> or <...> — appears ONLY after a function
    // return type. See TypeParsers.kt:169–267. This is why it can coexist
    // with comparison `<` in expressions: the function_type rule consumes it.
    // Effect row: `<>`, `<*>`, `<...>`, `<Log, State>`, `<Log, ...>`.
    // The trailing `...` makes the row polymorphic (open) — it can carry
    // additional effects beyond those listed.
    effect_row: $ => seq(
      '<',
      optional(choice(
        '*',
        '...',
        seq(
          sepBy1(',', $.effect_reference),
          // A row can end with a `, *` (performed marker) or `, ...` (open row)
          // after its named effects: `<Log, *>`, `<State having 'state = Int, *>`.
          optional(seq(',', choice('*', '...'))),
        ),
      )),
      // empty `<>` — a closed, effect-free row (the `optional` above).
      '>',
    ),

    // Effect reference inside an effect_row `<L1, L2, ...>`. Each ref can
    // be a bare name (`Log`) or a name with `having` type bindings
    // (`State having 'state = Int`). The `having` list ends at the next
    // top-level `,` or `>`. We require type_binding to be left-greedy so
    // the comma-list terminates as soon as it hits something that doesn't
    // start a new type_binding.
    effect_reference: $ => prec.left(seq(
      field('name', $.qualified_upper_name),
      optional($.having_clause),
    )),

    // `having 'a = T, 'b = U` — multiple type bindings comma-separated.
    // Declared as an explicit GLR conflict against ability_constraint
    // and effect_reference (both of which can also follow `having ...`
    // with comma-continuation in their parent rule). Public name (not
    // underscore-prefixed) so the conflict can reference it.
    having_clause: $ => seq(
      'having',
      sepBy1(',', $.type_binding),
    ),

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

    // Lambda parameters — accepts empty braces (`{}`) for nullary lambdas
    // (`{} -> 42`) plus the usual `{x, y, …}` form. The `sepBy` helper
    // already permits empty, but the rest of the grammar previously
    // disambiguated `{}` away from a record_expression; the lambda
    // precedence (PREC.lambda) ensures arrow-followed `{}` parses here.
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

    // x.method {args} — strictly higher precedence than member_access so
    // when both `x.field` and `x.method {args}` parses are possible, the
    // method_call (longer/more specific) wins.
    method_call: $ => prec.left(PREC.member + 1, seq(
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

    // match `<expr>` is <arm>... Arms can be separated by `,` or by
    // nothing (just whitespace). Without an external scanner, we can't
    // tell "end of match" from "start of next arm at the parent scope"
    // — both look like a new pattern. Use prec.dynamic so when a
    // following top-level _expression would be valid, GLR can prefer
    // ending the match. prec.right keeps multi-arm matches greedy
    // INSIDE a let body where nothing else competes.
    // `match scrutinee is` followed by an indented block of arms. The scanner
    // opens the block with _indent, separates arms with _newline, and closes it
    // with _dedent when a line dedents below the arm column — this is what stops
    // the arm list from swallowing the following top-level token. A trailing
    // comma per arm stays allowed (some sources write `pat -> body,`).
    match_expression: $ => prec.right(seq(
      'match',
      field('scrutinee', $._expression),
      'is',
      $._indent,
      $.match_arm,
      optional(','),
      repeat(seq($._newline, $.match_arm, optional(','))),
      optional($._dedent),
    )),

    match_arm: $ => seq(
      // Alternative patterns: `1 | 2 | 3 -> "small"` matches any of three
      // values. Pipe-OR in patterns is left-associative; the alternatives
      // share the same arm body.
      field('pattern', $._pattern_or),
      optional(seq('if', field('guard', $._guard))),
      '->',
      field('body', $._expression),
    ),

    // A match-arm guard: a `&&`-chain (a "let-chain") whose links are either a
    // boolean expression or an `if-let` binding. Handles `if x > 0`,
    // `if let Some {v} = e`, and the chained
    // `if let Some {a} = e1 && let Some {b} = e2 && a > 0`.
    //
    // The chain `&&` is its OWN low-precedence node (guard_and, prec below every
    // binary op) so an ordinary `a && b` inside a single boolean link still
    // reduces as one binary_expression, while a `let` link — which can't be a
    // binary RHS — forces the split.
    _guard: $ => choice(
      $.let_condition,
      $._expression,
      $.guard_and,
    ),

    // Higher precedence than binary `&&` (PREC.and) so, at a `&&` token in guard
    // position, the parser prefers the guard chain over extending a
    // binary_expression — which is what lets a `let` link (an invalid binary
    // RHS) sit on either side of `&&`.
    guard_and: $ => prec.left(PREC.and + 1, seq(
      field('left', $._guard),
      '&&',
      field('right', $._guard),
    )),

    // `let pat = expr` as a guard link. The value is precedence-capped ABOVE
    // `&&` so a trailing `&&` can't extend it into a binary_expression — it
    // belongs to the enclosing guard chain instead.
    let_condition: $ => seq(
      'let',
      field('let_pattern', $._pattern),
      '=',
      field('let_value', prec(PREC.and + 1, $._expression)),
    ),

    // `do <stmt>* <result-expr>` — sequence of let-statements + final
    // expression, terminated by the enclosing context's closing token.
    // The parser handles this with newline-driven layout; we use prec.right
    // to keep the do-block consuming let-statements as long as one starts
    // the next token, then a single trailing expression.
    do_expression: $ => prec.right(seq(
      'do',
      repeat($.let_statement),
      field('result', $._expression),
    )),

    // Inline let in a do-block. Same shape as let_declaration minus the
    // `pub` modifier (which only makes sense at top level). The tail is either
    // a value bind (`= expr`) or a monadic do-bind (`<- expr`). Both forms
    // share the identical `let <pattern>` prefix so they parse through ONE
    // state; the `=` / `<-` token is what the LR parser shifts on to pick a
    // form. Keeping them in a single rule (rather than two sibling rules) is
    // what makes tree-sitter build the merged post-`let <pattern>` state that
    // expects BOTH `=` and `<-` — two separate rules land in distinct
    // continuation states and the do-block only reaches the `=` one.
    let_statement: $ => seq(
      'let',
      field('pattern', $._pattern),
      choice(
        seq(
          optional(seq(':', field('type', $._signature_type))),
          '=',
          field('value', $._expression),
        ),
        seq(
          '<-',
          field('bind_value', $._expression),
        ),
      ),
    ),

    perform_expression: $ => choice(
      // Form 1: effect-operation invocation — `perform info { msg = "hi" }`,
      // `perform Log::info { … }`, `perform std::effects::Log::debug { … }`.
      // The operation is a (possibly deeply-qualified) name — segments may be
      // lower (modules) or Upper (an effect namespace), leaf lower or Upper.
      prec(PREC.call, seq(
        'perform',
        field('operation', $.qualified_perform_name),
        field('arguments', $.record_expression),
      )),
      // Form 2: handler installation — `perform Handler1, Handler2 in expr`.
      seq(
        'perform',
        sepBy1(',', $.handler),
        'in',
        field('body', $._expression),
      ),
    ),

    // A dotted `::` path whose leaf is the thing being named (op or handler):
    // `info`, `Log::info`, `std::effects::log::Console`, `a::b::c::Handler`.
    // Segments and leaf may each be lower or Upper — this is the effect
    // namespace path the parser accepts for both perform forms.
    qualified_perform_name: $ => prec(PREC.qualified, seq(
      repeat(seq(choice($.lower_identifier, $.upper_identifier), '::')),
      choice($.lower_identifier, $.upper_identifier),
    )),

    // Effect handler in a `perform … in` block — a possibly-qualified name
    // (`Console`, `log::Console`, `a::b::c::Handler`) with an optional arg
    // record for parameterized handlers (`FileLogger {path = "/tmp"}`).
    handler: $ => seq(
      field('name', $.qualified_perform_name),
      optional(field('args', $.record_expression)),
    ),

    record_expression: $ => seq(
      '{',
      sepBy(',', choice($.record_spread, $.record_field)),
      '}',
    ),

    // Spread update: `{ ...person, age = 31 }` — copy `person`'s fields, then
    // override. The spread source is any expression.
    record_spread: $ => seq(
      '...',
      field('source', $._expression),
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

    // Numeric literals allow `_` as a digit separator (1_000_000). The
    // parser strips them at lex time; we keep them in the token text. No
    // leading underscore — `_x` is an identifier.
    integer_literal: _ => /-?\d[\d_]*[nN]?/,
    float_literal: _ => /-?\d[\d_]*\.\d[\d_]*/,
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

    // Ordinary string text: any run of chars that isn't a quote, backslash, or
    // `$`, PLUS a lone `$` that doesn't begin an interpolation (`$100`, `$` at
    // end). The `$(` form is claimed by string_interpolation (longer token).
    string_content: _ => token(prec(-1, choice(
      /[^"\\$]+/,
      /\$/,
    ))),

    escape_sequence: _ => token(seq('\\', /./)),

    // $(expr) inside "...".
    string_interpolation: $ => seq(
      '$(',
      $._expression,
      ')',
    ),

    // ===== Identifiers =====

    // Identifier kinds. Mezze convention: `__NativeFoo` is upper-style
    // (the alpha character after any leading underscores is uppercase),
    // `__native_foo` is lower-style. So we split on the first
    // alpha-character's case, ignoring any leading underscore run.
    lower_identifier: _ => /_*[a-z][a-zA-Z0-9_]*/,
    upper_identifier: _ => /_*[A-Z][a-zA-Z0-9_]*/,
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
