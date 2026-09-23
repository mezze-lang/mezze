// External scanner for Mezze — indentation-aware block termination.
//
// Mezze's `match … is` arm lists and `do` blocks terminate by INDENTATION: an
// arm/statement continues only while the next code line is indented at or past
// the column of the first arm/statement; a line that dedents below it ends the
// block. Tree-sitter's LR core is whitespace-insensitive, so without help the
// arm-list `repeat` greedily swallows the following top-level token as another
// arm (see the parser's matchExpression, which stops on `column < firstArmCol`).
//
// This scanner mirrors that rule with a classic indentation stack. It emits
// three zero-width external tokens the grammar uses to bound blocks:
//
//   NEWLINE  — a line break to a new logical line at the SAME indentation as
//              the current block (i.e. "another arm / statement follows").
//   INDENT   — the first line of a block is indented deeper than its parent.
//   DEDENT   — a line break to a SHALLOWER indentation than the current block
//              (i.e. "this block is over"); one DEDENT per level popped.
//
// The grammar opens an indentation context with INDENT, separates items with
// NEWLINE, and closes with DEDENT. Because these are zero-width and only valid
// where the grammar asks for them (via `valid_symbols`), ordinary
// newline-insensitive parsing elsewhere is unaffected.

#include "tree_sitter/parser.h"
#include <stdlib.h>
#include <string.h>
#include <wctype.h>

enum TokenType {
  NEWLINE,
  INDENT,
  DEDENT,
  // Zero-width. Ends a `$(…` that has NO depth-matched `)` ahead of it — see
  // scan_interpolation_unclosed.
  INTERP_UNCLOSED,
};

// Indentation stack: the column (1-based, matching the Kotlin lexer) at which
// each currently-open block's items start. indents[0] is a sentinel 0 (column
// of the top level).
typedef struct {
  uint16_t len;
  uint16_t cap;
  int32_t *cols;
} Stack;

typedef struct {
  Stack stack;
  // When we compute a DEDENT we may need to pop several levels across multiple
  // scanner invocations; `pending_dedents` counts how many DEDENT tokens still
  // owe emission before we consume the newline.
  uint16_t pending_dedents;
  // After the last DEDENT lands exactly on a still-open level's column, the
  // enclosing block resumes with a new item at that column — so we owe it a
  // NEWLINE. e.g. an inner `match` dedents back to the outer arm column and the
  // outer match continues with its next arm.
  bool pending_newline;
} Scanner;

static void stack_push(Stack *s, int32_t col) {
  if (s->len == s->cap) {
    s->cap = s->cap ? s->cap * 2 : 8;
    s->cols = realloc(s->cols, s->cap * sizeof(int32_t));
  }
  s->cols[s->len++] = col;
}

static int32_t stack_top(Stack *s) {
  return s->len ? s->cols[s->len - 1] : 0;
}

// ---- required tree-sitter external-scanner entry points ----

void *tree_sitter_mezze_external_scanner_create(void) {
  Scanner *scanner = calloc(1, sizeof(Scanner));
  stack_push(&scanner->stack, 0); // top-level sentinel
  return scanner;
}

void tree_sitter_mezze_external_scanner_destroy(void *payload) {
  Scanner *scanner = (Scanner *)payload;
  free(scanner->stack.cols);
  free(scanner);
}

unsigned tree_sitter_mezze_external_scanner_serialize(void *payload, char *buffer) {
  Scanner *scanner = (Scanner *)payload;
  unsigned size = 0;
  buffer[size++] = (char)(scanner->pending_dedents & 0xFF);
  buffer[size++] = (char)(scanner->pending_newline ? 1 : 0);
  // Store stack (excluding the sentinel) as raw int32s, bounded by the
  // serialization buffer size.
  uint16_t n = scanner->stack.len;
  for (uint16_t i = 0; i < n; i++) {
    if (size + sizeof(int32_t) > TREE_SITTER_SERIALIZATION_BUFFER_SIZE) break;
    int32_t col = scanner->stack.cols[i];
    memcpy(&buffer[size], &col, sizeof(int32_t));
    size += sizeof(int32_t);
  }
  return size;
}

void tree_sitter_mezze_external_scanner_deserialize(void *payload, const char *buffer, unsigned length) {
  Scanner *scanner = (Scanner *)payload;
  scanner->stack.len = 0;
  scanner->pending_dedents = 0;
  scanner->pending_newline = false;
  if (length == 0) {
    stack_push(&scanner->stack, 0);
    return;
  }
  unsigned size = 0;
  scanner->pending_dedents = (unsigned char)buffer[size++];
  scanner->pending_newline = buffer[size++] != 0;
  while (size + sizeof(int32_t) <= length) {
    int32_t col;
    memcpy(&col, &buffer[size], sizeof(int32_t));
    size += sizeof(int32_t);
    stack_push(&scanner->stack, col);
  }
  if (scanner->stack.len == 0) stack_push(&scanner->stack, 0);
}

// Is the `$(` we are sitting inside ever closed?
//
// The Kotlin parser decides an interpolation's extent by scanning from `$(` to
// the DEPTH-MATCHED `)`. The grammar cannot: the body is parsed with the
// ordinary expression rules, whose whitespace `extras` include `\n`, so an
// UNCLOSED `$(` let its expression walk into the following declarations and eat
// them — `let s = "total: $(count` stopped everything below it from
// highlighting, which is a state the editor is in on every keystroke.
//
// So do the parser's own scan here, and only when the answer is "never closed"
// offer the parser a zero-width way out. A multi-line interpolation that DOES
// close later still finds its `)` and is untouched — which is what separates
// this from the line rule that was tried and reverted (it closed every
// multi-line interpolation early, must-parse 63 -> 77).
//
// Bounded by the string's own `"` and by EOF, because a `)` beyond either of
// those cannot belong to this interpolation.
static bool interpolation_is_closed(TSLexer *lexer) {
  int32_t depth = 1;
  for (;;) {
    int32_t c = lexer->lookahead;
    if (c == 0) return false;          // EOF: never closed
    if (c == '"') return false;        // string ended first
    if (c == '(') depth++;
    if (c == ')') { depth--; if (depth == 0) return true; }
    lexer->advance(lexer, false);
  }
}

static void advance(TSLexer *lexer) { lexer->advance(lexer, false); }
static void skip(TSLexer *lexer) { lexer->advance(lexer, true); }

bool tree_sitter_mezze_external_scanner_scan(void *payload, TSLexer *lexer,
                                             const bool *valid_symbols) {
  Scanner *scanner = (Scanner *)payload;

  // An interpolation with no matching `)` ahead can end here, zero-width. Asked
  // first because it is only ever valid inside `$(…`, where no layout token is.
  // `mark_end` before the scan so the token stays zero-width no matter how far
  // the lookahead walked.
  // The guard matters: in ERROR RECOVERY tree-sitter marks every external token
  // valid, so without it this ran with no interpolation present at all, and the
  // lookahead scan below moved the lexer out from under the layout logic — a
  // plain unterminated string lost its containment (2/3 -> 1/3 surviving
  // declarations). Inside a real `$(…` the layout tokens are NOT expected, so
  // `INDENT` being valid too means this is recovery, not an interpolation.
  if (valid_symbols[INTERP_UNCLOSED] && !valid_symbols[INDENT]) {
    lexer->mark_end(lexer);
    if (!interpolation_is_closed(lexer)) {
      lexer->result_symbol = INTERP_UNCLOSED;
      return true;
    }
    // Deliberately FALL THROUGH rather than returning false. The layout tokens
    // below may also be valid at this position, and short-circuiting here cost
    // a plain unterminated string its containment (2/3 -> 1/3 surviving
    // declarations) — the scanner stopped emitting the NEWLINE the recovery
    // needed.
  }

  // Emit any DEDENTs we still owe from a previous scan of the same line break.
  if (scanner->pending_dedents > 0 && valid_symbols[DEDENT]) {
    scanner->pending_dedents--;
    if (scanner->stack.len > 1) scanner->stack.len--; // pop the level
    lexer->result_symbol = DEDENT;
    return true;
  }

  // After the dedents settle, if the resumed line sits exactly at a still-open
  // block's column, emit the NEWLINE that block owes for its next item (an
  // inner match/do dedented back to the enclosing arm/statement column).
  if (scanner->pending_newline && valid_symbols[NEWLINE]) {
    scanner->pending_newline = false;
    lexer->result_symbol = NEWLINE;
    return true;
  }

  // We only act at a line boundary. Consume leading spaces/newlines, tracking
  // the column the next non-space code token begins at. Blank lines and
  // comment-only lines don't change indentation.
  bool saw_newline = false;
  for (;;) {
    if (lexer->lookahead == '\n') {
      saw_newline = true;
      skip(lexer);
    } else if (lexer->lookahead == '\r' || lexer->lookahead == ' ' ||
               lexer->lookahead == '\t') {
      skip(lexer);
    } else {
      break;
    }
  }

  // At EOF: close every open block with DEDENTs, then nothing.
  if (lexer->eof(lexer)) {
    if (valid_symbols[DEDENT] && scanner->stack.len > 1) {
      scanner->stack.len--;
      lexer->result_symbol = DEDENT;
      return true;
    }
    return false;
  }

  if (!saw_newline) {
    return false; // mid-line: nothing indentation-related to emit
  }

  // Column of the first code token on this new line. TSLexer gives a 0-based
  // column; the Kotlin lexer/parser use 1-based, so add 1 to match the arm-col
  // comparison semantics.
  int32_t col = (int32_t)lexer->get_column(lexer) + 1;
  int32_t top = stack_top(&scanner->stack);

  // Deeper than the current block's item column → a new nested block opens.
  if (col > top && valid_symbols[INDENT]) {
    stack_push(&scanner->stack, col);
    lexer->result_symbol = INDENT;
    return true;
  }

  // Same column → another item in the current block.
  if (col == top && valid_symbols[NEWLINE]) {
    lexer->result_symbol = NEWLINE;
    return true;
  }

  // NOT added here: a branch absorbing an over-indented line as another item of
  // the current block (`col > top && valid_symbols[NEWLINE] -> NEWLINE`).
  //
  // It is tempting, because the Kotlin parser ignores the column entirely when
  // deciding whether a match arm continues, so arms at inconsistent indents are
  // ONE match to the parser and a hard error here — and inconsistent indents are
  // exactly what half-edited code looks like.
  //
  // MEASURED, and it is a net loss: it fixed the mixed-indent arms but took
  // must-parse from 188 failures to 197. Returning false here PRUNES a GLR
  // branch; emitting a NEWLINE instead keeps a wrong stack alive, and in nine
  // other snippets that wrong stack went on to win. (must-reject moved the other
  // way, 18227 -> 18258, i.e. the grammar got stricter elsewhere — the same
  // effect seen from the other side.) Reverted. Closing the mixed-indent case
  // needs the arm-list context, which this scanner cannot see from
  // `valid_symbols` alone.


  // Shallower → the current block (and possibly several) is over. Count how
  // many levels we need to pop, emit one DEDENT now and queue the rest.
  if (col < top && valid_symbols[DEDENT]) {
    uint16_t dedents = 0;
    // Peek how many levels close, without mutating yet.
    for (int i = (int)scanner->stack.len - 1; i >= 1; i--) {
      if (scanner->stack.cols[i] > col) dedents++;
      else break;
    }
    if (dedents > 0) {
      // After all `dedents` levels pop, the top-of-stack column is what remains.
      // If it equals the resumed line's column, the enclosing block continues
      // with a new item here — owe it a NEWLINE once the DEDENTs are done.
      int remaining = (int)scanner->stack.len - (int)dedents;
      int32_t post_pop_top = (remaining >= 1) ? scanner->stack.cols[remaining - 1] : 0;
      scanner->pending_newline = (post_pop_top == col);

      scanner->pending_dedents = dedents - 1;
      scanner->stack.len--; // pop first level now
      lexer->result_symbol = DEDENT;
      return true;
    }
  }

  return false;
}
