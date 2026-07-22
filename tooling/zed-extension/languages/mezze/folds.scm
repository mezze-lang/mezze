;; Mezze code folding ranges. Each capture marks a node whose multi-line
;; body the editor should be able to collapse to a single placeholder.
;;
;; Match the @fold convention used by Neovim, Helix, and Zed.

;; ============================================================
;; Top-level decls with multi-line bodies
;; ============================================================

(impl_declaration) @fold
(ability_declaration) @fold
(effect_declaration) @fold
(type_declaration) @fold

;; let body — fold when the value spans multiple lines.
(let_declaration body: (_) @fold)

;; ============================================================
;; Expression-level blocks
;; ============================================================

(lambda_expression) @fold
(match_expression) @fold
(do_expression) @fold
(if_expression) @fold

;; Multi-line records and collections.
(record_expression) @fold
(record_pattern) @fold
(record_type) @fold
(sequence_expression) @fold
(sequence_pattern) @fold

;; Use-group imports: `use foo::{a, b, c, …}` can grow large.
(use_group) @fold

;; Variant types: `| A, B, C, … |`.
(variant_type) @fold

;; ============================================================
;; Comments — fold consecutive doc-comment blocks.
;; ============================================================

(doc_comment) @fold
