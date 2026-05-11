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
    body: (_) @function.inner)) @function.outer

;; Lambdas in expression position.
(lambda_expression
  body: (_) @function.inner) @function.outer

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

(type_declaration) @class.outer
(newtype_declaration) @class.outer
(impl_declaration) @class.outer
(ability_declaration) @class.outer
(effect_declaration) @class.outer

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

(line_comment) @comment.outer
(doc_comment) @comment.outer

;; ============================================================
;; Assignments (let bindings)
;; ============================================================

(let_declaration
  pattern: (_) @assignment.lhs
  body: (_) @assignment.rhs) @assignment.outer

(let_statement
  pattern: (_) @assignment.lhs
  value: (_) @assignment.rhs) @assignment.outer
