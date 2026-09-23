;; GENERATED — do not edit.
;;
;; Source: tooling/tree-sitter-mezze/queries/textobjects.scm
;; Regenerate: tooling/syntax-generators/gen-queries.py
;;
;; Captures renamed for this editor:
;;     @class.outer                 -> @class.around
;;     @comment.documentation       -> @comment.doc
;;     @comment.outer               -> @comment.around
;;     @constant.builtin.boolean    -> @boolean
;;     @function.inner              -> @function.inside
;;     @function.outer              -> @function.around
;;     @number.float                -> @number
;;     @type.interface              -> @type
;;     @variable.member             -> @property
;;
;; Every other capture is passed through unchanged. The `;;`
;; commentary below came from the source and names the SOURCE
;; captures — read it through the table above.
;; Tree-sitter text-objects — powers Vim/Helix motions like `daf`
;; (delete a function), `vif` (visual inner function), etc.
;;
;; Convention (nvim-treesitter-textobjects compatible):
;;   @function.outer — whole function decl (header + body)
;;   @function.inner — function body only
;;   @parameter.outer / .inner — one param + comma / just the param
;;   @call.outer / .inner — call expression / call args
;;   @class.outer — type/impl/ability/effect decl
;;   @block.outer / .inner — brace blocks
;;   @comment.outer — comments
;;   @assignment.outer / .inner — let bindings (LHS = RHS)

;; ============================================================
;; Functions (top-level let bound to a lambda)
;; ============================================================

(let_declaration
  body: (lambda_expression
    body: (_) @function.inside)) @function.around

;; Lambdas in expression position.
(lambda_expression
  body: (_) @function.inside) @function.around

;; ============================================================
;; Parameters
;; ============================================================

(lambda_parameter) @parameter.outer
(lambda_parameter name: (_) @parameter.inner)

(record_type_field) @parameter.outer
(record_type_field name: (_) @parameter.inner)

;; ============================================================
;; Calls
;; ============================================================

(function_call
  arguments: (_) @call.inner) @call.outer

(method_call
  arguments: (_) @call.inner) @call.outer

;; ============================================================
;; Class-like (types, impls, abilities, effects)
;; ============================================================

(type_declaration) @class.around
(newtype_declaration) @class.around
(impl_declaration) @class.around
(ability_declaration) @class.around
(effect_declaration) @class.around

;; ============================================================
;; Blocks
;; ============================================================

(record_expression) @block.outer
(record_pattern) @block.outer
(do_expression) @block.outer
(match_expression) @block.outer

;; ============================================================
;; Comments
;; ============================================================

(line_comment) @comment.around
(doc_comment) @comment.around

;; ============================================================
;; Assignments (let bindings)
;; ============================================================

(let_declaration
  pattern: (_) @assignment.lhs
  body: (_) @assignment.rhs) @assignment.outer

(let_statement
  pattern: (_) @assignment.lhs
  value: (_) @assignment.rhs) @assignment.outer
