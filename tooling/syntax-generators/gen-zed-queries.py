#!/usr/bin/env python3
"""Generate the Zed extension's tree-sitter queries from the canonical ones.

Zed extensions must SHIP their query files — they cannot point at the grammar
repo — so a copy under zed-extension/languages/mezze/ is structural. What was
not structural is that the copy was maintained by hand. It drifted, and the
drift was not cosmetic: the copy still referenced an anonymous `"!"` token that
the grammar does not have, and a tree-sitter query that names a non-existent
node fails to COMPILE — the whole file, not the one pattern. Zed was rendering
Mezze with no syntax highlighting at all, and nothing said so.

The two files were never meant to be identical. zed-extension/README.md states
the relationship: tree-sitter-mezze is the source of truth, and captures are
"mapped to Zed's convention". That mapping turns out to be exactly five renames
— every other capture is shared verbatim — which is small enough to apply here
and be sure of.

  ./gen-zed-queries.py            # regenerate
  ./gen-zed-queries.py --check    # exit 1 if the checked-in files have drifted

Run --check in CI alongside tree-sitter-mezze/scripts/check-queries.py: this one
proves the copy matches its source, that one proves the result still compiles.
Both are needed. Identical-to-source would not have caught the `"!"` on its own,
because the source could just as easily have carried it.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLING = os.path.dirname(HERE)
SRC = os.path.join(TOOLING, "tree-sitter-mezze", "queries")
DST = os.path.join(TOOLING, "zed-extension", "languages", "mezze")

# Neovim/Helix capture -> Zed capture. Zed's theme vocabulary predates the
# nvim-treesitter split of these into sub-captures.
CAPTURE_MAP = {
    "@comment.documentation": "@comment.doc",
    "@constant.builtin.boolean": "@boolean",
    "@number.float": "@number",
    "@type.interface": "@type",
    "@variable.member": "@property",
}

# Only the files Zed actually loads. locals.scm and textobjects.scm exist in the
# source but are not wired into this extension; adding one means adding it here.
FILES = ["highlights.scm", "indents.scm", "folds.scm"]


def split_comment(line):
    """(code, comment) for one line — `;` starts a comment unless it is inside a
    string. Captures never appear inside strings, so this is enough to keep the
    rename out of prose: the comments describe the SOURCE captures and stay
    true to it, while the code below them is what Zed reads."""
    out, i, n, in_str = [], 0, len(line), False
    while i < n:
        c = line[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == ";":
            return line[:i], line[i:]
        i += 1
    return line, ""


def render(name, text):
    longest_first = sorted(CAPTURE_MAP, key=len, reverse=True)
    pattern = re.compile("|".join(re.escape(k) for k in longest_first))
    body = []
    for line in text.split("\n"):
        code, comment = split_comment(line)
        body.append(pattern.sub(lambda m: CAPTURE_MAP[m.group(0)], code) + comment)

    table = "\n".join(
        f";;     {k:<28} -> {v}" for k, v in sorted(CAPTURE_MAP.items())
    )
    header = f""";; GENERATED — do not edit.
;;
;; Source: tooling/tree-sitter-mezze/queries/{name}
;; Regenerate: tooling/syntax-generators/gen-zed-queries.py
;;
;; Captures renamed for Zed's theme vocabulary:
{table}
;;
;; Every other capture is passed through unchanged. The `;;` commentary below
;; came from the source file and names the SOURCE captures — read it through
;; the table above.
"""
    return header + "\n".join(body)


def main():
    check = "--check" in sys.argv[1:]
    drifted = []
    for name in FILES:
        src = os.path.join(SRC, name)
        dst = os.path.join(DST, name)
        if not os.path.exists(src):
            print(f"error: missing source {src}", file=sys.stderr)
            return 2
        want = render(name, open(src, encoding="utf-8").read())
        have = open(dst, encoding="utf-8").read() if os.path.exists(dst) else None
        if want == have:
            continue
        if check:
            drifted.append(name)
        else:
            with open(dst, "w", encoding="utf-8") as f:
                f.write(want)
            print(f"wrote {os.path.relpath(dst, TOOLING)}")

    if check:
        if drifted:
            print("zed queries have DRIFTED from their source: " + ", ".join(drifted))
            print("run tooling/syntax-generators/gen-zed-queries.py")
            return 1
        print(f"zed queries: {len(FILES)}/{len(FILES)} match their source")
    return 0


if __name__ == "__main__":
    sys.exit(main())
