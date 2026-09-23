;; GENERATED — do not edit.
;;
;; Source: tooling/tree-sitter-mezze/queries/indents.scm
;; Regenerate: tooling/syntax-generators/gen-queries.py
;;
;; Captures renamed for this editor:
;;     @comment.documentation       -> @comment.doc
;;     @constant.builtin.boolean    -> @boolean
;;     @number.float                -> @number
;;     @type.interface              -> @type
;;     @variable.member             -> @property
;;
;; Every other capture is passed through unchanged. The `;;`
;; commentary below came from the source and names the SOURCE
;; captures — read it through the table above.
;; Mezze auto-indent rules. The host editor (Neovim, Helix) calls into
;; tree-sitter for "where should the cursor sit when I press Enter or
;; paste a block?"
;;
;; Conventions (Neovim-compatible):
;;   @indent.begin     — next line indents one level inside this node
;;   @indent.end       — close-bracket node; the line containing this
;;                       dedents one level
;;   @indent.align     — align the body to a column matching this node
;;                       (used for pipe / dot chains)
;;
;; Mezze uses braces freely so most indenting is brace-driven. The few
;; layout-sensitive constructs (`match … is`, `do <stmts>`) get explicit
;; rules.

;; ============================================================
;; Brace-delimited blocks indent their bodies.
;; ============================================================

;; All record-shaped containers and the various block-bodies.
[
  (record_expression)
  (record_pattern)
  (record_type)
  (lambda_parameters)
  (sequence_expression)
  (sequence_pattern)
  (sequence_type)
  (use_group)
] @indent.begin

;; impl / effect / ability / type body braces.
(impl_declaration "{" @indent.begin "}" @indent.end)
(effect_declaration "{" @indent.begin "}" @indent.end)
(ability_declaration "{" @indent.begin "}" @indent.end)

;; Variant type bodies `| A, B, C |` — indent inside the bars.
(variant_type "|" @indent.begin "|" @indent.end)

;; ============================================================
;; match / do — layout-driven, not brace-bounded.
;; ============================================================

;; After `match … is`, the arms below indent one level.
(match_expression
  "is" @indent.begin)

;; After `do`, statements indent one level.
(do_expression
  "do" @indent.begin)

;; ============================================================
;; Pipe and dot chains — align body to the first segment.
;; ============================================================

;; When a `|>` chain wraps onto the next line, align to the first |>
;; so multi-line pipes stay visually aligned.
(pipe_expression "|>" @indent.align)
