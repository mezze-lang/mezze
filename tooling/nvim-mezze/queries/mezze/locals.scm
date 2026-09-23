;; GENERATED — do not edit.
;;
;; Source: tooling/tree-sitter-mezze/queries/locals.scm
;; Regenerate: tooling/syntax-generators/gen-queries.py
;;
;; Captures are passed through UNCHANGED: the canonical queries
;; are already written in this editor's dialect.
;; Mezze scope + binding queries. Powers:
;; - Same-name highlight (every occurrence of `x` lights up when cursor
;;   is on a `x` binding)
;; - Scope-aware go-to-def in tree-sitter-only consumers (Neovim's
;;   nvim-treesitter-refactor, Helix's "select symbol")
;;
;; Captures (locals.scm convention):
;;   @local.scope        — node that introduces a new scope
;;   @local.definition   — a binding site (the name being defined)
;;   @local.reference    — a use site

;; ============================================================
;; Scopes
;; ============================================================

(source_file) @local.scope
(lambda_expression) @local.scope
(match_arm) @local.scope
(do_expression) @local.scope
(let_declaration) @local.scope
(let_statement) @local.scope
(impl_declaration) @local.scope
(ability_declaration) @local.scope
(effect_declaration) @local.scope

;; ============================================================
;; Definitions
;; ============================================================

;; Top-level let names — `let f = …` introduces `f`.
(let_declaration
  pattern: (variable_pattern (lower_identifier) @local.definition.function))

;; Inline let names (inside do-blocks, etc).
(let_statement
  pattern: (variable_pattern (lower_identifier) @local.definition.var))

;; Lambda params.
(lambda_parameter name: (lower_identifier) @local.definition.parameter)

;; Match pattern bindings: `Some { val }` binds `val`.
(record_pattern_field name: (lower_identifier) @local.definition.var)
(variable_pattern (lower_identifier) @local.definition.var)
(rest_pattern (lower_identifier) @local.definition.var)

;; Type-level definitions.
(type_declaration name: (upper_identifier) @local.definition.type)
(newtype_declaration name: (upper_identifier) @local.definition.type)
(ability_declaration name: (upper_identifier) @local.definition.type)
(effect_declaration name: (upper_identifier) @local.definition.type)

;; Variant constructors.
(variant_constructor name: (upper_identifier) @local.definition.constant)

;; Use-imports introduce names into the current module scope.
(use_item name: (lower_identifier) @local.definition)
(use_item name: (upper_identifier) @local.definition.type)
(use_item alias: (lower_identifier) @local.definition)
(use_item alias: (upper_identifier) @local.definition.type)

;; ============================================================
;; References
;; ============================================================

(variable (lower_identifier) @local.reference)
(named_type (upper_identifier) @local.reference)
(qualified_reference name: (_) @local.reference)
(member_access field: (lower_identifier) @local.reference)
(method_call method: (lower_identifier) @local.reference)
