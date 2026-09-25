;; GENERATED — do not edit.
;;
;; Source: tooling/tree-sitter-mezze/queries/highlights.scm
;; Regenerate: tooling/syntax-generators/gen-zed-queries.py
;;
;; Captures renamed for Zed's theme vocabulary:
;;     @comment.documentation       -> @comment.doc
;;     @constant.builtin.boolean    -> @boolean
;;     @number.float                -> @number
;;     @type.interface              -> @type
;;     @variable.member             -> @property
;;
;; Every other capture is passed through unchanged. The `;;` commentary below
;; came from the source file and names the SOURCE captures — read it through
;; the table above.
;; Mezze syntax highlights. Tree-sitter queries are pattern-matched
;; bottom-up against the parse tree; later patterns win on overlap.
;; Captures map to editor highlight groups (Neovim/Helix conventions;
;; VSCode and Zed adapters translate as needed).
;;
;; Design rules:
;; - All keywords share the SAME color (`@keyword`).
;; - `pub` is its OWN node (`visibility_modifier`), tagged
;;   `@keyword.modifier`. It can NEVER bleed into the following keyword.
;;   This is the entire fix for the broken-TextMate cascade bug.
;; - Effects and abilities share `@type.interface` — distinct from
;;   `@type` (concrete types and aliases) and `@constructor` (variant
;;   constructors).
;; - Metadata `#{ … }` is `@attribute`, not `@comment` — it's
;;   compiler-meaningful.

;; ============================================================
;; Keywords
;; ============================================================

[
  "let"
  "type"
  "newtype"
  "effect"
  "ability"
  "impl"
  "use"
  "as"
  "if"
  "then"
  "else"
  "match"
  "is"
  "do"
  "perform"
  "in"
  "with"
  "having"
  "where"
  "for"
] @keyword

;; `pub` is isolated — captured as @keyword.modifier so editor themes
;; can render it visibly distinct without affecting the keyword that
;; follows (#316).
(visibility_modifier) @keyword.modifier

;; ============================================================
;; Operators & punctuation
;; ============================================================

[
  "+" "-" "*" "/" "%"
  "==" "!=" "<" ">" "<=" ">="
  "&&" "||"
  "|>"
  "->"
  "::"
  "="
] @operator

(pipe_placeholder) @variable.special

[ "{" "}" "(" ")" "[" "]" ] @punctuation.bracket

[ "," ":" "|" ] @punctuation.delimiter
(variant_ellipsis) @punctuation.delimiter

;; ============================================================
;; Comments
;; ============================================================

(line_comment) @comment
(doc_comment)  @comment.doc

;; Metadata `#{ key: val, … }` — compiler-meaningful annotation.
;; Tag the whole block as @attribute; keys/values still get their own
;; per-node highlights via more-specific patterns later.
(metadata_block) @attribute

;; ============================================================
;; Literals
;; ============================================================

(integer_literal)  @number
(float_literal)    @number
;; `1//2` — a Frac. One token, so the `//` colours as part of the number
;; rather than as two operators with a number either side.
(fraction_literal) @number
(bool_literal)    @boolean
(unit_literal)    @constant.builtin

(string_literal) @string
(escape_sequence) @string.escape

;; Inside string interpolation: $( … ). Punctuation around the embedded
;; expression is highlighted distinctly; the expression itself gets
;; whatever rules apply (variables → @variable, etc.).
(string_interpolation "$(" @punctuation.special)
(string_interpolation ")"  @punctuation.special)

;; ============================================================
;; Types
;; ============================================================

;; Concrete types and type aliases.
(named_type (upper_identifier) @type)

;; Type variables like `'a`, `'state`.
(type_variable) @type.parameter

;; Type application: `Option Int`. The constructor is the head; the
;; arguments inherit @type via named_type.
(type_application
  constructor: (upper_identifier) @type)

;; Variant constructors at definition and use sites — distinct shade
;; from concrete types.
(variant_constructor name: (upper_identifier) @constructor)
(variant_pattern constructor: (upper_identifier) @constructor)
(variant_constructor_expression constructor: (upper_identifier) @constructor)

;; ============================================================
;; Abilities and effects — same color, distinct from types
;; ============================================================

;; The name in an `ability Foo for 'a where { … }` declaration.
(ability_declaration name: (upper_identifier) @type)

;; The name in an `effect Foo where { … }` declaration.
(effect_declaration name: (upper_identifier) @type)

;; The ability/effect name in an impl: `impl Eq for Bool` or
;; `impl Log for ConsoleLogger`. We can't statically distinguish
;; ability impls from effect impls in the grammar (unified into
;; impl_declaration) — both get @type.interface, which matches the
;; user's preference: effects and abilities highlight the same.
(impl_declaration name: (upper_identifier) @type)

;; Ability constraint in a signature: `with Eq 'a, Show 'a`. The `ability`
;; field is a qualified_upper_name; color its leaf upper_identifier.
(ability_constraint
  ability: (qualified_upper_name (upper_identifier) @type))

;; Effect references inside an effect row: `<Log, State>`. The `name` field is
;; a qualified_upper_name; color its leaf upper_identifier.
(effect_reference
  name: (qualified_upper_name (upper_identifier) @type))

;; ============================================================
;; Function names
;; ============================================================

;; Function declaration: `let f = …`. The pattern is a variable_pattern
;; whose inner lower_identifier is the function name.
(let_declaration
  pattern: (variable_pattern (lower_identifier) @function))

;; Lambda parameters.
(lambda_parameter name: (lower_identifier) @variable.parameter)

;; Method-name in dot calls: `x.method { … }`.
(method_call method: (lower_identifier) @function.method)

;; Member access (read, not call).
(member_access field: (lower_identifier) @property)

;; Ability/effect operation names — both at decl site and use sites.
(ability_operation name: (lower_identifier) @function.method)
(effect_operation name: (lower_identifier) @function.method)
(impl_operation name: (lower_identifier) @function.method)

;; The operation invoked by `perform info { … }` — an effect-op call. The
;; `operation` field is a qualified_perform_name; color its leaf name.
(perform_expression
  operation: (qualified_perform_name (lower_identifier) @function.method))

;; `Show.show` — a static ability operation. The two halves are different
;; things and want different colours: the ability is a TYPE, the operation is a
;; method on it.
(static_ability_op
  ability: (upper_identifier) @type
  operation: (lower_identifier) @function.method)

;; `UserId.(5)` / `UserId.(v)` — newtype construction and destructuring. The
;; constructor is the type's name.
;; Matched by POSITION, not by spelling: the constructor field admits a
;; lowercase name too (the parser does not case-check it), and whatever sits
;; there is the constructor.
(newtype_constructor_expression
  constructor: (_) @constructor)

(newtype_pattern
  constructor: (_) @constructor)

;; Qualified reference: `module::name` — the trailing name is the
;; thing being referenced. We tag the module path as @namespace and
;; the leaf depending on shape.
(qualified_reference
  module: (module_path (lower_identifier) @namespace)
  name: (lower_identifier) @function)

(qualified_reference
  module: (module_path (lower_identifier) @namespace)
  name: (upper_identifier) @type)

;; ============================================================
;; Records, patterns, and bindings
;; ============================================================

;; Record field names — both literal and pattern positions.
(record_field name: (lower_identifier) @property)
(record_pattern_field name: (lower_identifier) @property)
(record_type_field name: (lower_identifier) @property)

;; Spread `...`, in all three places it occurs — the record form
;; `{ ...base, … }`, the sequence form `[...ys, 1]`, and the rest pattern
;; `[h, ...rest]`. Only the record one was tagged, so the same three dots
;; changed colour depending on which bracket they sat in. The name after the
;; dots highlights via its own rules; what these tag is the punctuation.
(record_spread "..." @punctuation.special)
(sequence_spread "..." @punctuation.special)
(rest_pattern "..." @punctuation.special)
(rest_pattern (lower_identifier) @variable)

;; Open record type `{ a: Int, ... }` — the trailing `...` marks an open row.
(record_type_rest) @punctuation.special

;; Pattern variable bindings (e.g. the `x` in `let x = …` or `Some {x}`).
(variable_pattern (lower_identifier) @variable)

;; Bare variable references in expression position (`person` in
;; `{ ...person }`, `xs` in `xs |> …`). A general fallback — more specific
;; rules above (function callees, qualified refs) already tagged their own.
(variable (lower_identifier) @variable)

;; ============================================================
;; Imports / namespaces
;; ============================================================

(module_path (lower_identifier) @namespace)

(use_item
  name: (lower_identifier) @function)
(use_item
  name: (upper_identifier) @type)

;; ============================================================
;; Parse errors (debug)
;; ============================================================

;; While the grammar is still maturing, ERROR nodes mark regions
;; tree-sitter couldn't parse. Render them with the error severity
;; so they're visible in dev but the rest of the file highlights
;; normally.
(ERROR) @error
