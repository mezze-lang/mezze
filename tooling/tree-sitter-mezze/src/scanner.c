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
  if (length == 0) {
    stack_push(&scanner->stack, 0);
    return;
  }
  unsigned size = 0;
  scanner->pending_dedents = (unsigned char)buffer[size++];
  while (size + sizeof(int32_t) <= length) {
    int32_t col;
    memcpy(&col, &buffer[size], sizeof(int32_t));
    size += sizeof(int32_t);
    stack_push(&scanner->stack, col);
  }
  if (scanner->stack.len == 0) stack_push(&scanner->stack, 0);
}

static void advance(TSLexer *lexer) { lexer->advance(lexer, false); }
static void skip(TSLexer *lexer) { lexer->advance(lexer, true); }

bool tree_sitter_mezze_external_scanner_scan(void *payload, TSLexer *lexer,
                                             const bool *valid_symbols) {
  Scanner *scanner = (Scanner *)payload;

  // Emit any DEDENTs we still owe from a previous scan of the same line break.
  if (scanner->pending_dedents > 0 && valid_symbols[DEDENT]) {
    scanner->pending_dedents--;
    if (scanner->stack.len > 1) scanner->stack.len--; // pop the level
    lexer->result_symbol = DEDENT;
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
      scanner->pending_dedents = dedents - 1;
      scanner->stack.len--; // pop first level now
      lexer->result_symbol = DEDENT;
      return true;
    }
  }

  return false;
}
